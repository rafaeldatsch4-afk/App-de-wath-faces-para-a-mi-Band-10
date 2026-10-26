package io.github.rafaeldatsch4afk.mibandfaces.protocol

import io.github.rafaeldatsch4afk.mibandfaces.util.readU16le

/**
 * Enquadramento SPP V2 (XiaomiSppPacketV2 no Gadgetbridge). É usado tanto no Bluetooth
 * Clássico (RFCOMM) quanto no BLE V2 (serviço FE95, características 005E/005F).
 *
 * ```
 * a5 a5 | tipo (4 bits baixos) | seq(1) | tamanho(2 LE) | crc16/ARC do payload(2 LE) | payload
 * ```
 */
object SppV2 {
    private const val PREAMBLE: Byte = 0xa5.toByte()
    const val HEADER_SIZE = 8

    const val TYPE_NACK = 0
    const val TYPE_ACK = 1
    const val TYPE_SESSION_CONFIG = 2
    const val TYPE_DATA = 3

    // Canais do payload DATA.
    const val CHANNEL_PROTOBUF = 1 // comandos protobuf (e autenticação)
    const val CHANNEL_DATA = 2 // envio de arquivos (não cifrado)
    const val CHANNEL_ACTIVITY = 5

    // Opcodes do payload DATA.
    const val OPCODE_PLAIN = 1
    const val OPCODE_ENCRYPTED = 2

    const val SESSION_START_REQUEST = 1
    const val SESSION_START_RESPONSE = 2

    private const val KEY_VERSION = 1
    private const val KEY_MAX_PACKET_SIZE = 2
    private const val KEY_TX_WIN = 3
    private const val KEY_SEND_TIMEOUT = 4

    data class Frame(val type: Int, val seq: Int, val payload: ByteArray)

    /** Configuração de sessão anunciada pela banda. Valores nulos = não informados. */
    data class SessionConfig(
        val opcode: Int,
        val version: ByteArray? = null,
        val maxPacketSize: Int? = null,
        val txWindow: Int? = null,
        val sendTimeoutMs: Int? = null,
    )

    fun encode(type: Int, seq: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = PREAMBLE
        out[1] = PREAMBLE
        out[2] = (type and 0x0f).toByte()
        out[3] = (seq and 0xff).toByte()
        out[4] = payload.size.toByte()
        out[5] = (payload.size ushr 8).toByte()
        val crc = Crc16Arc.compute(payload)
        out[6] = crc.toByte()
        out[7] = (crc ushr 8).toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    fun ack(seq: Int): ByteArray = encode(TYPE_ACK, seq, ByteArray(0))

    /**
     * Pedido de início de sessão, com os mesmos valores que o app oficial envia (dump citado no
     * Gadgetbridge): versão 1.0.0, quadro máximo 0xfc00, janela de 32 quadros, timeout 10000 ms.
     */
    fun sessionStartRequest(): ByteArray = encode(
        TYPE_SESSION_CONFIG,
        0,
        byteArrayOf(
            SESSION_START_REQUEST.toByte(),
            KEY_VERSION.toByte(), 0x03, 0x00, 0x01, 0x00, 0x00,
            KEY_MAX_PACKET_SIZE.toByte(), 0x02, 0x00, 0x00, 0xfc.toByte(),
            KEY_TX_WIN.toByte(), 0x02, 0x00, 0x20, 0x00,
            KEY_SEND_TIMEOUT.toByte(), 0x02, 0x00, 0x10, 0x27,
        ),
    )

    /** Payload de um quadro DATA: canal | opcode | corpo. */
    fun dataPayload(channel: Int, opcode: Int, body: ByteArray): ByteArray =
        byteArrayOf((channel and 0x0f).toByte(), opcode.toByte(), *body)

    fun parseSessionConfig(payload: ByteArray): SessionConfig? {
        if (payload.isEmpty()) return null
        val opcode = payload[0].toInt() and 0xff
        var version: ByteArray? = null
        var maxPacket: Int? = null
        var window: Int? = null
        var timeout: Int? = null
        var i = 1
        while (i + 3 <= payload.size) {
            val key = payload[i].toInt() and 0xff
            val size = payload.readU16le(i + 1)
            i += 3
            if (i + size > payload.size) break
            when {
                key == KEY_VERSION -> version = payload.copyOfRange(i, i + size)
                key == KEY_MAX_PACKET_SIZE && size == 2 -> maxPacket = payload.readU16le(i)
                key == KEY_TX_WIN && size == 2 -> window = payload.readU16le(i)
                key == KEY_SEND_TIMEOUT && size == 2 -> timeout = payload.readU16le(i)
            }
            i += size
        }
        return SessionConfig(opcode, version, maxPacket, window, timeout)
    }

    /**
     * Junta os bytes que chegam do transporte (que podem vir fragmentados ou vários quadros
     * juntos) e devolve quadros completos. Quadros com CRC errado são descartados.
     */
    class Parser(private val onBadFrame: (String) -> Unit = {}) {
        private var buffer = ByteArray(0)

        fun feed(bytes: ByteArray): List<Frame> {
            buffer += bytes
            val frames = mutableListOf<Frame>()
            while (true) {
                if (buffer.isEmpty()) break
                if (buffer[0] != PREAMBLE || (buffer.size >= 2 && buffer[1] != PREAMBLE)) {
                    skip(nextPreamble())
                    continue
                }
                if (buffer.size < HEADER_SIZE) break
                val length = buffer.readU16le(4)
                val total = HEADER_SIZE + length
                if (buffer.size < total) break
                val payload = buffer.copyOfRange(HEADER_SIZE, total)
                val crc = buffer.readU16le(6)
                if (crc != Crc16Arc.compute(payload)) {
                    onBadFrame("CRC inválido (recebido %04x, calculado %04x)".format(crc, Crc16Arc.compute(payload)))
                } else {
                    frames += Frame(buffer[2].toInt() and 0x0f, buffer[3].toInt() and 0xff, payload)
                }
                skip(total)
            }
            return frames
        }

        fun reset() {
            buffer = ByteArray(0)
        }

        private fun nextPreamble(): Int {
            for (i in 1 until buffer.size) if (buffer[i] == PREAMBLE) return i
            return buffer.size
        }

        private fun skip(count: Int) {
            buffer = if (count >= buffer.size) ByteArray(0) else buffer.copyOfRange(count, buffer.size)
        }
    }
}
