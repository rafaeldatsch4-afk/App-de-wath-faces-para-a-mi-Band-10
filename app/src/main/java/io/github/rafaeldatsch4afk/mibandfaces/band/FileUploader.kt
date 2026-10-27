package io.github.rafaeldatsch4afk.mibandfaces.band

import com.google.protobuf.ByteString
import io.github.rafaeldatsch4afk.mibandfaces.band.Commands.isA
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.proto.Command
import io.github.rafaeldatsch4afk.mibandfaces.proto.DataUpload
import io.github.rafaeldatsch4afk.mibandfaces.proto.DataUploadRequest
import io.github.rafaeldatsch4afk.mibandfaces.protocol.LinkException
import io.github.rafaeldatsch4afk.mibandfaces.protocol.NackException
import io.github.rafaeldatsch4afk.mibandfaces.util.md5
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Envio de arquivo para a banda (XiaomiDataUploadService, tipo 22), usado pelas watch faces e
 * pelos mini-apps: negocia o envio (22/0), manda as partes esperando o ACK de cada uma e, se a
 * conexão cair e o [BandClient] reconectar sozinho, continua de onde a banda parou.
 */
internal class FileUploader(
    private val client: BandClient,
    private val log: PacketLog,
    /** Quanto esperar a reconexão automática quando a conexão cai no meio. */
    private val reconnectWaitMs: Long,
) {
    /** O que fazer depois de reconectar, antes de continuar o envio. */
    enum class Restart {
        /** O pedido de instalação foi aceito de novo: continuar o envio. */
        CONTINUE,

        /** A banda diz que já terminou de instalar (a queda foi depois do último pedaço). */
        ALREADY_INSTALLED,
    }

    /**
     * Envia [bytes] como [type]. Depois de cada reconexão chama [restart] (que repete o pedido de
     * instalação) e manda de novo o 22/0; a banda responde com resumePosition e o envio continua
     * dali, como no Gadgetbridge. Até [MAX_RESUMES] vezes.
     *
     * Ao cancelar ou falhar de vez, fecha a conexão para a banda descartar o envio incompleto.
     */
    suspend fun send(
        type: Int,
        bytes: ByteArray,
        onProgress: (InstallProgress) -> Unit,
        restart: suspend () -> Restart,
    ) {
        try {
            var resumes = 0
            while (true) {
                val generation = client.connectionGeneration
                try {
                    if (resumes > 0 && restart() == Restart.ALREADY_INSTALLED) {
                        log.info("Depois de reconectar, a banda diz que a instalação já terminou")
                        return
                    }
                    upload(type, bytes, onProgress)
                    return
                } catch (e: Exception) {
                    if (!isConnectionLoss(e) || resumes >= MAX_RESUMES) throw e
                    resumes++
                    log.warn("A conexão caiu durante o envio (${e.message}). Esperando a reconexão para continuar ($resumes de $MAX_RESUMES)")
                    awaitReconnection(generation, onProgress, "continuar o envio")
                }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                if (e is CancellationException) log.warn("Envio cancelado pelo usuário: fechando a conexão sem ativar nada")
                else log.error("Envio interrompido: fechando a conexão", e)
                client.disconnect()
            }
            throw e
        }
    }

    /** Executa [block]; se a conexão cair e voltar, executa de novo (até [MAX_RESUMES] vezes). */
    suspend fun <T> retryingOnLoss(what: String, onProgress: (InstallProgress) -> Unit, block: suspend () -> T): T {
        var resumes = 0
        while (true) {
            val generation = client.connectionGeneration
            try {
                return block()
            } catch (e: Exception) {
                if (!isConnectionLoss(e) || resumes >= MAX_RESUMES) throw e
                resumes++
                log.warn("A conexão caiu ao $what (${e.message}). Esperando a reconexão")
                awaitReconnection(generation, onProgress, what)
            }
        }
    }

    /** Queda do Bluetooth (reconectável). Um NACK repetido não é queda: a banda recusou os dados. */
    private fun isConnectionLoss(e: Exception): Boolean =
        e !is NackException && (e is LinkException || e is NotConnectedException || e is java.io.IOException)

    private suspend fun awaitReconnection(generation: Int, onProgress: (InstallProgress) -> Unit, toDo: String) {
        onProgress(InstallProgress("A conexão caiu. Reconectando para $toDo…", null))
        if (!client.awaitReconnection(generation, reconnectWaitMs)) {
            throw InstallException("A conexão caiu durante a instalação e não voltou. Nada foi ativado; tente de novo.")
        }
        log.info("Reconectado: retomando para $toDo")
    }

    private suspend fun upload(type: Int, bytes: ByteArray, onProgress: (InstallProgress) -> Unit) {
        onProgress(InstallProgress("Negociando o envio…", 0f))
        val reply = client.request(
            Command.newBuilder().setType(Commands.DATA_UPLOAD).setSubtype(Commands.DATA_UPLOAD_START)
                .setDataUpload(
                    DataUpload.newBuilder().setDataUploadRequest(
                        DataUploadRequest.newBuilder()
                            .setType(type)
                            .setMd5Sum(ByteString.copyFrom(md5(bytes)))
                            .setSize(bytes.size),
                    ),
                ).build(),
            "início do envio",
        ) { it.isA(Commands.DATA_UPLOAD, Commands.DATA_UPLOAD_START) }
        if (!reply.dataUpload.hasDataUploadAck()) throw InstallException("Resposta inválida da banda ao pedido de envio")
        val ack = reply.dataUpload.dataUploadAck
        if (ack.errno != 0) {
            throw InstallException("A banda recusou o envio (errno ${ack.errno}). Pode ser falta de espaço: remova algo na aba Faces e tente de novo.")
        }
        val chunkSize = if (ack.hasChunkSize() && ack.chunkSize > 4) ack.chunkSize else UploadPayload.DEFAULT_CHUNK_SIZE
        val resume = ack.resumePosition
        if (resume < 0 || resume > bytes.size) throw InstallException("A banda pediu para retomar de uma posição inválida ($resume)")
        if (resume > 0) log.warn("A banda pediu para retomar o envio a partir do byte $resume")
        val parts = UploadPayload.parts(UploadPayload.build(type, bytes, resume), chunkSize)
        log.info("Envio (tipo $type): ${bytes.size} bytes em ${parts.size} partes de até $chunkSize bytes (janela ${client.linkWindow})")

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

    companion object {
        /** Quantas vezes o envio continua depois de uma queda da conexão. */
        const val MAX_RESUMES = 5
    }
}
