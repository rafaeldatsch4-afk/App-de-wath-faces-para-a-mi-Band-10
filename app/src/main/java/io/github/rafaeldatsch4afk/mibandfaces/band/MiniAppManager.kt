package io.github.rafaeldatsch4afk.mibandfaces.band

import com.google.protobuf.ByteString
import io.github.rafaeldatsch4afk.mibandfaces.band.Commands.isA
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.miniapp.RpkFile
import io.github.rafaeldatsch4afk.mibandfaces.proto.Command
import io.github.rafaeldatsch4afk.mibandfaces.proto.Rpk
import io.github.rafaeldatsch4afk.mibandfaces.proto.RpkInfo
import io.github.rafaeldatsch4afk.mibandfaces.proto.RpkInfoList
import io.github.rafaeldatsch4afk.mibandfaces.util.toHexPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Um mini-app instalado na banda (RpkInfoList). [sha] é devolvido à banda para removê-lo. */
data class InstalledApp(val id: String, val name: String, val sha: ByteString) {
    override fun toString() = "$name[$id]"
}

class AppAlreadyInstalledException(val app: InstalledApp) :
    Exception("O app \"${app.name}\" (${app.id}) já está instalado. Remova a versão antiga antes de instalar a nova.")

/**
 * Lista, instala e remove mini-apps .rpk (XiaomiRpkService do Gadgetbridge, comando tipo 20):
 *
 * - lista: 20/0, a banda responde com `rpk.rpkList`;
 * - instalar: 20/1 com `rpkInfo` (id, versionCode, tamanho), a banda responde 20/1 com
 *   `rpkInstallStart.cmd` (0 = aceito); depois o envio 22/0 com o tipo 64 e, ao terminar, a banda
 *   avisa com 20/2. Aqui a confirmação é feita pela lista, que funciona com ou sem esse aviso;
 * - remover: 20/3 com `rpkDel` (id e sha da lista). O Gadgetbridge diz que a banda às vezes não
 *   responde, então o resultado é conferido pela lista.
 */
class MiniAppManager(
    private val client: BandClient,
    private val log: PacketLog,
    /** Espera entre as conferências da lista depois do envio. */
    private val settleDelayMs: Long = 1_500,
    /** Quantas vezes conferir a lista antes de desistir (a banda descompacta o app). */
    private val confirmAttempts: Int = 10,
    private val shortTimeoutMs: Long = 5_000,
    reconnectWaitMs: Long = 45_000,
) {
    private val uploader = FileUploader(client, log, reconnectWaitMs)

    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)

    /** Mini-apps instalados (null = ainda não lido). */
    val apps: StateFlow<List<InstalledApp>?> = _apps.asStateFlow()

    private val _answered = MutableStateFlow<Boolean?>(null)

    /**
     * Se a banda respondeu ao pedido da lista de apps: true respondeu, false ficou em silêncio
     * (firmware sem mini-apps, como a Mi Band 8 do miband-hack), null ainda não perguntado.
     */
    val answered: StateFlow<Boolean?> = _answered.asStateFlow()

    fun clear() {
        _apps.value = null
        _answered.value = null
    }

    /** Pergunta a lista com um tempo curto: para bandas em que os mini-apps são experimentais. */
    suspend fun probe(): List<InstalledApp> = refresh(shortTimeoutMs)

    suspend fun refresh(timeoutMs: Long? = null): List<InstalledApp> {
        val reply = try {
            if (timeoutMs != null) {
                client.request(Commands.simple(Commands.RPK, Commands.RPK_LIST), "lista de mini-apps", timeoutMs) {
                    it.isA(Commands.RPK, Commands.RPK_LIST)
                }
            } else {
                client.request(Commands.simple(Commands.RPK, Commands.RPK_LIST), "lista de mini-apps") {
                    it.isA(Commands.RPK, Commands.RPK_LIST)
                }
            }
        } catch (e: BandTimeoutException) {
            _answered.value = false
            throw e
        }
        _answered.value = true
        val list = reply.rpk.rpkList.rpkInfoList.map {
            InstalledApp(id = it.id, name = it.name.ifBlank { it.id }, sha = it.sha)
        }
        log.info("Mini-apps instalados (${list.size}): " + list.joinToString())
        _apps.value = list
        return list
    }

    suspend fun delete(app: InstalledApp) {
        log.info("Removendo o mini-app ${app.id} (sha ${app.sha.toByteArray().toHexPreview()})")
        val command = Command.newBuilder().setType(Commands.RPK).setSubtype(Commands.RPK_DELETE)
            .setRpk(Rpk.newBuilder().setRpkDel(RpkInfoList.newBuilder().setId(app.id).setSha(app.sha)))
            .build()
        try {
            client.request(command, "remover mini-app", shortTimeoutMs) { it.isA(Commands.RPK, Commands.RPK_DELETE) }
        } catch (e: BandTimeoutException) {
            log.warn("Sem resposta a \"remover mini-app\"; conferindo pela lista")
        }
        for (attempt in 1..3) {
            if (refresh().none { it.id == app.id }) return
            delay(settleDelayMs)
        }
        throw InstallException("A banda não removeu o app \"${app.name}\".")
    }

    /**
     * Instala um mini-app. Passos: confere a lista (versão antiga), pede a instalação (20/1), envia
     * o arquivo (22/0, tipo 64) esperando o ACK de cada parte e confere a lista. Cancelamento e
     * quedas da conexão funcionam como nas watch faces (veja [FileUploader]).
     */
    suspend fun install(file: RpkFile.Valid, replaceExisting: Boolean, onProgress: (InstallProgress) -> Unit) {
        if (!client.isReady) throw NotConnectedException()

        onProgress(InstallProgress("Perguntando à banda os apps instalados…", null))
        val existing = try {
            refresh().firstOrNull { it.id == file.packageName }
        } catch (e: BandTimeoutException) {
            throw InstallException(
                "A banda não respondeu ao pedido da lista de mini-apps, então nada foi enviado. " +
                    "A Mi Band 8 testada pelo miband-hack também ficava em silêncio: este firmware provavelmente não aceita mini-apps.",
            )
        }
        if (existing != null) {
            if (!replaceExisting) throw AppAlreadyInstalledException(existing)
            onProgress(InstallProgress("Removendo a versão antiga…", null))
            delete(existing)
        }

        checkInstallStatus(requestInstall(file, onProgress))
        uploader.send(Commands.UPLOAD_TYPE_RPK, file.bytes, onProgress) {
            checkInstallStatus(requestInstall(file, onProgress))
            FileUploader.Restart.CONTINUE
        }
        uploader.retryingOnLoss("conferir a instalação", onProgress) { confirm(file, onProgress) }
        log.info("Instalação do mini-app \"${file.name}\" (${file.packageName}) concluída")
    }

    /** Pedido de instalação (20/1). Retorna o status da banda (0 = aceito). */
    private suspend fun requestInstall(file: RpkFile.Valid, onProgress: (InstallProgress) -> Unit): Int {
        onProgress(InstallProgress("Pedindo a instalação…", null))
        val reply = try {
            client.request(
            Command.newBuilder().setType(Commands.RPK).setSubtype(Commands.RPK_INSTALL)
                .setRpk(
                    Rpk.newBuilder().setRpkInfo(
                        RpkInfo.newBuilder()
                            .setId(file.packageName)
                            .setUnknown2(file.versionCode)
                            .setSize(file.bytes.size),
                    ),
                ).build(),
            "pedido de instalação do app",
            ) { it.isA(Commands.RPK, Commands.RPK_INSTALL) }
        } catch (e: BandTimeoutException) {
            // O arquivo só vai depois que a banda aceita este pedido: sem resposta, nada foi enviado.
            throw InstallException("A banda não respondeu ao pedido de instalação do app; nada foi enviado.")
        }
        return reply.rpk.rpkInstallStart.cmd
    }

    private fun checkInstallStatus(status: Int) {
        // O significado dos códigos diferentes de 0 não é conhecido (o Gadgetbridge só registra o número).
        if (status != 0) throw InstallException("A banda recusou a instalação do app (status $status).")
    }

    private suspend fun confirm(file: RpkFile.Valid, onProgress: (InstallProgress) -> Unit) {
        onProgress(InstallProgress("Confirmando a instalação…", 1f))
        repeat(confirmAttempts) {
            delay(settleDelayMs)
            if (refresh().any { it.id == file.packageName }) return
        }
        throw InstallException("O envio terminou, mas o app não apareceu na lista da banda. Confira na banda antes de tentar de novo.")
    }
}
