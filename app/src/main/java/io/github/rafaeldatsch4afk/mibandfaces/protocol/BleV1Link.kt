package io.github.rafaeldatsch4afk.mibandfaces.protocol

import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID

/** Acesso ao GATT da banda (a implementação Android é a AndroidGattIo; os testes usam uma falsa). */
interface GattIo {
    val description: String

    /**
     * Conecta, pede o maior MTU, encontra as características e liga as notificações. Retorna o
     * tamanho máximo de cada escrita (MTU − 3) e as características que a banda tem.
     */
    suspend fun open(onNotify: (UUID, ByteArray) -> Unit, onClosed: (Throwable?) -> Unit): Opened

    class Opened(val maxWriteSize: Int, val characteristics: Set<UUID>)

    suspend fun write(characteristic: UUID, value: ByteArray, withoutResponse: Boolean)

    fun close()
}

/**
 * Enlace BLE "V1" da Mi Band 8 (XiaomiBleProtocolV1 no Gadgetbridge): serviço FE95 com quatro
 * características, cada uma com o protocolo de [CharacteristicV1]:
 * - 0051: comandos da banda para o celular;
 * - 0052: comandos do celular para a banda;
 * - 0053: dados de atividade (não usados aqui, mas a banda pode mandar);
 * - 0055: envio de arquivos (opcional em alguns aparelhos).
 *
 * Não há janela: cada pacote espera o ACK do anterior na mesma característica. O envio de arquivos
 * vai por escrita sem resposta, que numa Mi Band 8 foi 5× mais rápido (miband-hack).
 */
class BleV1Link(
    private val io: GattIo,
    private val log: PacketLog,
    private val ackTimeoutMs: Long = 15_000,
) : BandLink {
    override val description: String get() = io.description

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdAt = System.currentTimeMillis()

    private class RawWrite(val characteristic: UUID, val bytes: ByteArray)

    private val writes = Channel<RawWrite>(Channel.UNLIMITED)
    private var listener: BandLink.Listener? = null
    private val lock = Any()
    private var closed = false

    private fun characteristic(uuid: UUID, name: String, incrementNonce: Boolean = true) = CharacteristicV1(
        name = name,
        log = log,
        scope = scope,
        rawWrite = { bytes -> writes.trySend(RawWrite(uuid, bytes)) },
        incrementNonce = incrementNonce,
        ackTimeoutMs = ackTimeoutMs,
        onAckTimeout = { fail(AckTimeoutException(it)) },
    )

    private val cmdRead = characteristic(CMD_READ, "0051")
    private val cmdWrite = characteristic(CMD_WRITE, "0052")
    private val activity = characteristic(ACTIVITY, "0053")
    private val upload = characteristic(UPLOAD, "0055", incrementNonce = false)
    private var hasUpload = false

    private val all get() = listOf(cmdRead, cmdWrite, activity, upload)

    override val isOpen: Boolean get() = synchronized(lock) { !closed }

    override val effectiveWindow: Int = 1

    override suspend fun open(listener: BandLink.Listener) {
        this.listener = listener
        cmdRead.handler = { body -> this.listener?.onPacket(SppV2.CHANNEL_PROTOBUF, body) }
        activity.handler = { body -> log.debug("BLE V1: dados de atividade ignorados (${body.size} bytes)") }
        val opened = try {
            io.open(
                onNotify = { uuid, value ->
                    log.rx("BLE V1 ${uuid.toString().substring(4, 8)}", value)
                    when (uuid) {
                        CMD_READ -> cmdRead.onNotify(value)
                        CMD_WRITE -> cmdWrite.onNotify(value)
                        ACTIVITY -> activity.onNotify(value)
                        UPLOAD -> upload.onNotify(value)
                    }
                },
                onClosed = { cause -> fail(cause?.let { LinkClosedException("A conexão com a banda caiu: ${it.message}", it) }) },
            )
        } catch (e: Exception) {
            fail(e)
            throw e
        }
        hasUpload = UPLOAD in opened.characteristics
        for (c in all) c.maxWriteSize = opened.maxWriteSize
        log.info("BLE V1: pronto, escritas de até ${opened.maxWriteSize} bytes" + if (hasUpload) "" else " (sem a característica 0055 de arquivos)")
        scope.launch {
            for (w in writes) {
                try {
                    io.write(w.characteristic, w.bytes, withoutResponse = w.characteristic == UPLOAD)
                } catch (e: Exception) {
                    fail(LinkClosedException("Falha ao escrever na banda: ${e.message}", e))
                    break
                }
            }
        }
    }

    override suspend fun enqueue(channel: Int, body: ByteArray, encrypt: Boolean): Deferred<Unit> {
        if (!isOpen) throw LinkClosedException("Conexão com a banda encerrada")
        return when (channel) {
            SppV2.CHANNEL_PROTOBUF -> cmdWrite.write(body, encrypt)
            SppV2.CHANNEL_DATA -> {
                if (!hasUpload) throw LinkUnsupportedException("Esta banda não tem a característica de envio de arquivos (0055)")
                // Como no Gadgetbridge: arquivos vão cifrados depois da autenticação.
                upload.write(body, encrypt = upload.cipher != null)
            }
            else -> throw LinkUnsupportedException("Canal $channel não existe no BLE V1")
        }
    }

    override fun onAuthenticated(keys: SessionKeys) {
        val cipher = V1Cipher(keys)
        for (c in all) {
            c.cipher = cipher
            c.reset()
        }
    }

    override fun close() {
        fail(null)
    }

    private fun fail(cause: Throwable?) {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        val error = cause as? Exception ?: LinkClosedException(cause?.message ?: "Conexão encerrada", cause)
        val seconds = (System.currentTimeMillis() - createdAt) / 1000
        if (cause != null) log.error("Enlace BLE V1 encerrado (conexão durou $seconds s)", cause) else log.info("Enlace BLE V1 encerrado (conexão durou $seconds s)")
        for (c in all) c.failAll(error)
        writes.close()
        val l = listener
        scope.launch {
            io.close()
            l?.onClosed(cause)
            scope.cancel()
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
        val CMD_READ: UUID = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb")
        val CMD_WRITE: UUID = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb")
        val ACTIVITY: UUID = UUID.fromString("00000053-0000-1000-8000-00805f9b34fb")
        val UPLOAD: UUID = UUID.fromString("00000055-0000-1000-8000-00805f9b34fb")

        /** Obrigatórias; a 0055 é opcional (XiaomiBleProtocolV1.initializeDevice). */
        val REQUIRED = setOf(CMD_READ, CMD_WRITE, ACTIVITY)
        val ALL = REQUIRED + UPLOAD
    }
}
