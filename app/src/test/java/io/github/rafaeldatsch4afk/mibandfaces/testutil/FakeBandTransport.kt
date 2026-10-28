package io.github.rafaeldatsch4afk.mibandfaces.testutil

import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BandTransport
import io.github.rafaeldatsch4afk.mibandfaces.protocol.SppV1
import io.github.rafaeldatsch4afk.mibandfaces.protocol.SppV2
import io.github.rafaeldatsch4afk.mibandfaces.util.hexToBytes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Banda simulada na camada de transporte: responde à sondagem de versão SPP V1, à configuração de
 * sessão SPP V2, confirma (ACK) os quadros DATA em ordem e envia NACK quando falta um quadro
 * (go-back-N), como descrito no Gadgetbridge (XiaomiSppPacketV2 / XiaomiSppProtocolV2).
 *
 * Se a versão respondida for < 2 (ou não houver resposta), a banda fica em SPP V1: sem ACK, e
 * [onData] recebe o dataType do pacote no lugar do opcode.
 *
 * As camadas de cima (autenticação, comandos) são simuladas por [onData].
 */
class FakeBandTransport(
    override val needsVersionProbe: Boolean = true,
    private val versionPayload: ByteArray? = "030041".hexToBytes(),
    private val bandWindow: Int = 3,
) : BandTransport {

    override val description = "banda simulada"

    /** Chamado para cada pacote DATA aceito (em ordem): canal, opcode e corpo como chegaram. */
    var onData: (channel: Int, opcode: Int, body: ByteArray) -> Unit = { _, _, _ -> }

    /** Quantos quadros DATA do celular a banda vai "perder" (ignorar sem ACK). */
    @Volatile
    var loseFrames = 0

    /** Quantos quadros DATA a banda vai recusar com NACK antes de aceitar. */
    @Volatile
    var nackFrames = 0

    /** Se true, a banda para de responder a qualquer coisa (simula banda travada/fora de alcance). */
    @Volatile
    var silent = false

    val acceptedSeqs = CopyOnWriteArrayList<Int>()
    val acksFromHost = CopyOnWriteArrayList<Int>()
    val nacksSent = CopyOnWriteArrayList<Int>()

    private val executor = Executors.newSingleThreadExecutor()
    private var listener: BandTransport.Listener? = null
    private var v2 = !needsVersionProbe

    /** A banda ficou em SPP V1 depois da sondagem. */
    @Volatile
    var isV1 = false
        private set
    private var v1Buffer = ByteArray(0)
    private val parser = SppV2.Parser()
    private var expectedSeq = 0
    private var bandSeq = 0

    @Volatile
    var closed = false
        private set

    override suspend fun open(listener: BandTransport.Listener) {
        this.listener = listener
    }

    override suspend fun write(bytes: ByteArray) {
        if (closed) throw IllegalStateException("fechado")
        val copy = bytes.copyOf()
        executor.execute { process(copy) }
    }

    override fun close() {
        closed = true
        executor.shutdown()
    }

    /**
     * Envia um pacote da banda para o celular. Em V2, [opcode] é o opcode (1 puro, 2 cifrado); em
     * V1, é o dataType (0 puro, 1 cifrado, 2 autenticação) e o canal é o lógico (1 comandos).
     */
    fun sendToHost(channel: Int, opcode: Int, body: ByteArray) {
        executor.execute {
            val seq = bandSeq
            bandSeq = (bandSeq + 1) and 0xff
            if (isV1) {
                val raw = if (channel == SppV2.CHANNEL_PROTOBUF) SppV1.CHANNEL_PROTO_RX else SppV1.CHANNEL_MASS
                deliver(SppV1.encode(raw, 0x80, SppV1.OPCODE_SEND, seq, opcode, body))
            } else {
                deliver(SppV2.encode(SppV2.TYPE_DATA, seq, SppV2.dataPayload(channel, opcode, body)))
            }
        }
    }

    /** Simula a banda derrubando a conexão. */
    fun dropConnection() {
        executor.execute { listener?.onClosed(java.io.IOException("banda desconectou")) }
    }

    fun awaitIdle() {
        val latch = java.util.concurrent.CountDownLatch(1)
        executor.execute { latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
    }

    private fun deliver(bytes: ByteArray) {
        if (closed) return
        // Entrega em pedaços pequenos para exercitar a remontagem no celular.
        var i = 0
        while (i < bytes.size) {
            val end = minOf(bytes.size, i + 7)
            listener?.onData(bytes.copyOfRange(i, end))
            i = end
        }
    }

    private fun process(bytes: ByteArray) {
        if (silent) return
        if (isV1) {
            processV1(bytes)
            return
        }
        if (!v2) {
            if (bytes.contentEquals(SppV1.versionRequest())) {
                val payload = versionPayload
                // Versão < 2 ou sem resposta: a banda fica em V1 (XiaomiSppSupport).
                if (payload == null || payload.isEmpty() || (payload[0].toInt() and 0xff) < 2) isV1 = true else v2 = true
                payload ?: return // sem resposta: sondagem expira
                val length = payload.size + 3
                deliver(
                    byteArrayOf(0xba.toByte(), 0xdc.toByte(), 0xfe.toByte(), 0, 0, length.toByte(), (length ushr 8).toByte(), 0, 0, 0) +
                        payload + byteArrayOf(0xef.toByte()),
                )
            }
            return
        }
        for (frame in parser.feed(bytes)) handleFrame(frame)
    }

    /** Pacotes SPP V1: canal 2 = comandos, 5 = arquivos. Não há ACK no V1. */
    private fun processV1(bytes: ByteArray) {
        v1Buffer += bytes
        while (true) {
            when (val r = SppV1.parse(v1Buffer)) {
                SppV1.ParseResult.Incomplete -> return
                is SppV1.ParseResult.Invalid -> error("pacote SPP V1 inválido do celular")
                is SppV1.ParseResult.Complete -> {
                    v1Buffer = v1Buffer.copyOfRange(r.size, v1Buffer.size)
                    val p = r.packet
                    check(p.opcode == SppV1.OPCODE_SEND && p.flags == 0x80) { "cabeçalho V1 diferente do Gadgetbridge" }
                    acceptedSeqs += p.serial
                    val channel = when (p.channel) {
                        SppV1.CHANNEL_PROTO_TX -> SppV2.CHANNEL_PROTOBUF
                        SppV1.CHANNEL_MASS -> SppV2.CHANNEL_DATA
                        else -> error("canal V1 ${p.channel} inesperado")
                    }
                    onData(channel, p.dataType, p.payload)
                }
            }
        }
    }

    private fun handleFrame(frame: SppV2.Frame) {
        when (frame.type) {
            SppV2.TYPE_SESSION_CONFIG -> deliver(
                SppV2.encode(
                    SppV2.TYPE_SESSION_CONFIG,
                    0,
                    byteArrayOf(
                        SppV2.SESSION_START_RESPONSE.toByte(),
                        1, 3, 0, 1, 0, 0, // versão 1.0.0
                        2, 2, 0, 0x00, 0x80.toByte(), // quadro máximo 32768
                        3, 2, 0, bandWindow.toByte(), 0, // janela
                        4, 2, 0, 0x10, 0x27, // timeout 10000
                    ),
                ),
            )
            SppV2.TYPE_ACK -> acksFromHost += frame.seq
            SppV2.TYPE_NACK -> Unit
            SppV2.TYPE_DATA -> {
                if (loseFrames > 0) {
                    loseFrames--
                    return
                }
                if (frame.seq != expectedSeq) {
                    nacksSent += expectedSeq
                    deliver(SppV2.encode(SppV2.TYPE_NACK, expectedSeq, ByteArray(0)))
                    return
                }
                if (nackFrames > 0) {
                    nackFrames--
                    nacksSent += expectedSeq
                    deliver(SppV2.encode(SppV2.TYPE_NACK, expectedSeq, ByteArray(0)))
                    return
                }
                acceptedSeqs += frame.seq
                expectedSeq = (expectedSeq + 1) and 0xff
                deliver(SppV2.ack(frame.seq))
                val channel = frame.payload[0].toInt() and 0x0f
                val opcode = frame.payload[1].toInt() and 0xff
                onData(channel, opcode, frame.payload.copyOfRange(2, frame.payload.size))
            }
        }
    }
}
