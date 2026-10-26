package io.github.rafaeldatsch4afk.mibandfaces.band

import com.google.protobuf.ByteString
import io.github.rafaeldatsch4afk.mibandfaces.band.Commands.isA
import io.github.rafaeldatsch4afk.mibandfaces.face.FaceFile
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.proto.Command
import io.github.rafaeldatsch4afk.mibandfaces.proto.DataUpload
import io.github.rafaeldatsch4afk.mibandfaces.proto.DataUploadRequest
import io.github.rafaeldatsch4afk.mibandfaces.proto.Watchface
import io.github.rafaeldatsch4afk.mibandfaces.proto.WatchfaceInstallStart
import io.github.rafaeldatsch4afk.mibandfaces.util.md5
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Uma watch face instalada na banda (WatchfaceInfo). */
data class InstalledFace(val id: String, val name: String, val active: Boolean, val canDelete: Boolean)

/** Progresso da instalação, para a barra na tela. */
data class InstallProgress(val stage: String, val fraction: Float?)

class FaceAlreadyInstalledException(val face: InstalledFace) :
    Exception("A face \"${face.name}\" (ID ${face.id}) já está instalada. Remova a versão antiga antes de instalar a nova.")

class BandFullException(count: Int) :
    Exception("A banda já tem $count watch faces (o máximo é ${WatchfaceManager.MAX_FACES}). Remova uma na aba Faces.")

class InstallException(message: String) : Exception(message)

/** Lista, ativa, remove e instala watch faces (XiaomiWatchfaceService + XiaomiDataUploadService). */
class WatchfaceManager(
    private val client: BandClient,
    private val log: PacketLog,
    /** Espera depois do último pedaço, antes de conferir a lista (o miband-hack usa 1,5 s). */
    private val settleDelayMs: Long = 1_500,
    private val shortTimeoutMs: Long = 5_000,
) {
    private val _faces = MutableStateFlow<List<InstalledFace>?>(null)

    /** Faces instaladas (null = ainda não lido). */
    val faces: StateFlow<List<InstalledFace>?> = _faces.asStateFlow()

    fun clear() {
        _faces.value = null
    }

    suspend fun refresh(): List<InstalledFace> {
        val reply = client.request(Commands.simple(Commands.WATCHFACE, Commands.WATCHFACE_LIST), "lista de watch faces") {
            it.isA(Commands.WATCHFACE, Commands.WATCHFACE_LIST)
        }
        val list = reply.watchface.watchfaceList.watchfaceList.map {
            InstalledFace(id = it.id, name = it.name.ifBlank { it.id }, active = it.active, canDelete = it.canDelete)
        }
        log.info("Faces instaladas (${list.size}): " + list.joinToString { "${it.name}[${it.id}]${if (it.active) "*" else ""}" })
        _faces.value = list
        return list
    }

    suspend fun setActive(id: String) {
        log.info("Ativando a face $id")
        requestTolerant(watchfaceCommand(Commands.WATCHFACE_SET, id), "ativar face", Commands.WATCHFACE_SET)
        refresh()
    }

    suspend fun delete(face: InstalledFace) {
        // Mesmas recusas do Gadgetbridge (XiaomiWatchfaceService.deleteWatchface).
        if (!face.canDelete) throw InstallException("\"${face.name}\" é uma face do sistema e não pode ser removida.")
        if (face.active) throw InstallException("\"${face.name}\" é a face ativa. Ative outra antes de remover.")
        log.info("Removendo a face ${face.id}")
        requestTolerant(watchfaceCommand(Commands.WATCHFACE_DELETE, face.id), "remover face", Commands.WATCHFACE_DELETE)
        val after = refresh()
        if (after.any { it.id == face.id }) throw InstallException("A banda não removeu a face \"${face.name}\".")
    }

    /**
     * Instala uma face v2. Passos: confere a lista (versão antiga, banda cheia), pede a
     * instalação (4/4), negocia o envio (22/0), manda as partes esperando o ACK de cada uma,
     * confere a lista e ativa a face.
     *
     * Cancelamento: o coroutine pode ser cancelado a qualquer momento. Um quadro nunca é cortado
     * no meio (quem escreve no Bluetooth é o enlace, quadro inteiro por vez). Ao cancelar ou falhar
     * no meio do envio, a face não é ativada e a conexão é fechada, para a banda descartar a
     * sessão de envio incompleta.
     */
    suspend fun install(
        file: FaceFile.V2,
        replaceExisting: Boolean,
        onProgress: (InstallProgress) -> Unit,
    ) {
        if (!file.canInstall) throw InstallException(file.compatibility.description)
        if (!client.isReady) throw NotConnectedException()

        onProgress(InstallProgress("Lendo as faces instaladas…", null))
        var faces = refresh()
        val existing = faces.firstOrNull { it.id == file.id }
        if (existing != null) {
            if (!replaceExisting) throw FaceAlreadyInstalledException(existing)
            removeOldVersion(existing, faces, onProgress)
            faces = _faces.value ?: refresh()
        }
        if (faces.size >= MAX_FACES) throw BandFullException(faces.size)

        onProgress(InstallProgress("Pedindo a instalação…", null))
        val start = client.request(
            Command.newBuilder().setType(Commands.WATCHFACE).setSubtype(Commands.WATCHFACE_INSTALL)
                .setWatchface(
                    Watchface.newBuilder().setWatchfaceInstallStart(
                        WatchfaceInstallStart.newBuilder().setId(file.id).setSize(file.bytes.size),
                    ),
                ).build(),
            "pedido de instalação",
        ) { it.isA(Commands.WATCHFACE, Commands.WATCHFACE_INSTALL) }
        when (val status = start.watchface.installStatus) {
            0 -> Unit
            2 -> throw InstallException("A banda diz que esta face já está instalada (installStatus 2). Remova a versão antiga.")
            else -> throw InstallException("A banda recusou a instalação (installStatus $status).")
        }

        uploading {
            upload(file.bytes, onProgress)
        }

        onProgress(InstallProgress("Confirmando a instalação…", 1f))
        delay(settleDelayMs)
        var installed = false
        for (attempt in 1..3) {
            if (refresh().any { it.id == file.id }) {
                installed = true
                break
            }
            delay(settleDelayMs)
        }
        if (!installed) {
            throw InstallException("O envio terminou, mas a face não apareceu na lista da banda. Confira na banda antes de tentar de novo.")
        }
        onProgress(InstallProgress("Ativando a face…", 1f))
        try {
            setActive(file.id)
        } catch (e: Exception) {
            log.warn("Face instalada, mas não consegui ativá-la: ${e.message}")
        }
        log.info("Instalação de \"${file.name}\" (${file.id}) concluída")
    }

    private suspend fun removeOldVersion(existing: InstalledFace, faces: List<InstalledFace>, onProgress: (InstallProgress) -> Unit) {
        if (!existing.canDelete) throw InstallException("Já existe uma face do sistema com o ID ${existing.id}; não dá para substituí-la.")
        onProgress(InstallProgress("Removendo a versão antiga…", null))
        if (existing.active) {
            // A banda não remove a face ativa: ativa outra primeiro.
            val other = faces.firstOrNull { it.id != existing.id }
                ?: throw InstallException("A versão antiga é a única face e não há outra para ativar antes de removê-la.")
            log.info("A versão antiga está ativa: ativando \"${other.name}\" antes de remover")
            setActive(other.id)
            if (_faces.value?.firstOrNull { it.id == existing.id }?.active == true) {
                throw InstallException("A banda não trocou a face ativa; a versão antiga continua ativa.")
            }
        }
        delete(existing.copy(active = false))
    }

    /** Fecha a conexão se o envio for cancelado ou falhar no meio. */
    private suspend fun uploading(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                if (e is CancellationException) log.warn("Envio cancelado pelo usuário: fechando a conexão sem ativar a face")
                else log.error("Envio interrompido: fechando a conexão", e)
                client.disconnect()
            }
            throw e
        }
    }

    private suspend fun upload(bytes: ByteArray, onProgress: (InstallProgress) -> Unit) {
        onProgress(InstallProgress("Negociando o envio…", 0f))
        val reply = client.request(
            Command.newBuilder().setType(Commands.DATA_UPLOAD).setSubtype(Commands.DATA_UPLOAD_START)
                .setDataUpload(
                    DataUpload.newBuilder().setDataUploadRequest(
                        DataUploadRequest.newBuilder()
                            .setType(Commands.UPLOAD_TYPE_WATCHFACE)
                            .setMd5Sum(ByteString.copyFrom(md5(bytes)))
                            .setSize(bytes.size),
                    ),
                ).build(),
            "início do envio",
        ) { it.isA(Commands.DATA_UPLOAD, Commands.DATA_UPLOAD_START) }
        if (!reply.dataUpload.hasDataUploadAck()) throw InstallException("Resposta inválida da banda ao pedido de envio")
        val ack = reply.dataUpload.dataUploadAck
        if (ack.errno != 0) {
            throw InstallException("A banda recusou o envio (errno ${ack.errno}). Pode ser falta de espaço: remova uma face e tente de novo.")
        }
        val chunkSize = if (ack.hasChunkSize() && ack.chunkSize > 4) ack.chunkSize else UploadPayload.DEFAULT_CHUNK_SIZE
        val resume = ack.resumePosition
        if (resume < 0 || resume > bytes.size) throw InstallException("A banda pediu para retomar de uma posição inválida ($resume)")
        if (resume > 0) log.warn("A banda pediu para retomar o envio a partir do byte $resume")
        val parts = UploadPayload.parts(UploadPayload.build(Commands.UPLOAD_TYPE_WATCHFACE, bytes, resume), chunkSize)
        log.info("Envio: ${bytes.size} bytes em ${parts.size} partes de até $chunkSize bytes (janela ${client.linkWindow})")

        val pending = ArrayDeque<Deferred<Unit>>()
        var acked = 0
        fun report() = onProgress(InstallProgress("Enviando parte $acked de ${parts.size}…", acked.toFloat() / parts.size))
        report()
        for (part in parts) {
            coroutineContext.ensureActive()
            // enqueue() suspende enquanto a janela estiver cheia; com janela 1, cada parte só sai
            // depois do ACK da anterior.
            pending.addLast(client.enqueueData(part))
            while (pending.isNotEmpty() && pending.first().isCompleted) {
                pending.removeFirst().await()
                acked++
                report()
            }
        }
        while (pending.isNotEmpty()) {
            pending.removeFirst().await()
            acked++
            report()
        }
    }

    /** Pedidos em que a banda pode não responder: o resultado é conferido depois pela lista. */
    private suspend fun requestTolerant(command: Command, what: String, subtype: Int) {
        try {
            val reply = client.request(command, what, shortTimeoutMs) { it.isA(Commands.WATCHFACE, subtype) }
            log.debug("Resposta a \"$what\": ack=${reply.watchface.ack}")
        } catch (e: BandTimeoutException) {
            log.warn("Sem resposta a \"$what\"; conferindo pela lista")
        }
    }

    private fun watchfaceCommand(subtype: Int, id: String): Command =
        Command.newBuilder().setType(Commands.WATCHFACE).setSubtype(subtype)
            .setWatchface(Watchface.newBuilder().setWatchfaceId(id)).build()

    companion object {
        /**
         * Limite de faces instaladas. Não aparece no código de referência (Gadgetbridge/miband-hack):
         * é o valor informado na especificação do app.
         */
        const val MAX_FACES = 10
    }
}
