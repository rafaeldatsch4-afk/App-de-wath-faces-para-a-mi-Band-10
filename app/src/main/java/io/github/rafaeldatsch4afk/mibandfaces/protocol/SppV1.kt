package io.github.rafaeldatsch4afk.mibandfaces.protocol

/**
 * Enquadramento SPP V1 (XiaomiSppPacketV1 / XiaomiSppProtocolV1 no Gadgetbridge). Serve para a
 * "sondagem de versão" logo após abrir o socket RFCOMM e, se a banda não falar V2 (firmwares mais
 * antigos da Mi Band 9), para a sessão inteira. A Mi Band 10 responde `03 00 41` e segue em V2.
 *
 * ```
 * ba dc fe | canal(1) | flags(1) | tamanho(2 LE = payload + 3) | opcode | serial | dataType | payload | ef
 * ```
 *
 * No V1 não há ACK nem reenvio: cada pacote vai uma vez, como no Gadgetbridge.
 */
object SppV1 {
    private val PREAMBLE = byteArrayOf(0xba.toByte(), 0xdc.toByte(), 0xfe.toByte())
    private const val EPILOGUE: Byte = 0xef.toByte()
    private const val HEADER_SIZE = 10 // preâmbulo(3) + canal + flags + tamanho(2) + opcode + serial + dataType

    const val CHANNEL_VERSION = 0

    /** Protobuf vindo da banda. */
    const val CHANNEL_PROTO_RX = 1

    /** Protobuf indo para a banda. */
    const val CHANNEL_PROTO_TX = 2
    const val CHANNEL_FITNESS = 3

    /** Envio de arquivos ("mass"). */
    const val CHANNEL_MASS = 5

    const val DATA_TYPE_PLAIN = 0
    const val DATA_TYPE_ENCRYPTED = 1
    const val DATA_TYPE_AUTH = 2

    private const val FLAG = 0x80
    private const val NEEDS_RESPONSE = 0x40
    private const val OPCODE_READ = 0
    const val OPCODE_SEND = 2

    /** Pedido de versão: `badcfe 00 c0 0300 00 00 00 ef`. */
    fun versionRequest(): ByteArray = encode(CHANNEL_VERSION, FLAG or NEEDS_RESPONSE, OPCODE_READ, 0, DATA_TYPE_PLAIN, ByteArray(0))

    /** Monta um pacote. [payload] já vem cifrado, se for o caso. */
    fun encode(channel: Int, flags: Int, opcode: Int, serial: Int, dataType: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 3
        require(length <= 0xffff) { "pacote SPP V1 grande demais" }
        return byteArrayOf(
            *PREAMBLE,
            (channel and 0x0f).toByte(),
            flags.toByte(),
            length.toByte(),
            (length ushr 8).toByte(),
            opcode.toByte(),
            serial.toByte(),
            dataType.toByte(),
        ) + payload + EPILOGUE
    }

    /** Pacote de dados enviado pelo celular (flag 0x80, opcode SEND), como o Gadgetbridge. */
    fun send(channel: Int, serial: Int, dataType: Int, payload: ByteArray): ByteArray =
        encode(channel, FLAG, OPCODE_SEND, serial, dataType, payload)

    class Packet(
        val channel: Int,
        val payload: ByteArray,
        val flags: Int = 0,
        val opcode: Int = 0,
        val serial: Int = 0,
        val dataType: Int = DATA_TYPE_PLAIN,
    )

    sealed interface ParseResult {
        /** Faltam bytes para completar o pacote. */
        data object Incomplete : ParseResult

        /** Lixo no início do buffer: descartar [skip] bytes e tentar de novo. */
        data class Invalid(val skip: Int) : ParseResult

        class Complete(val packet: Packet, val size: Int) : ParseResult
    }

    fun parse(buffer: ByteArray): ParseResult {
        if (buffer.isNotEmpty() && buffer[0] != PREAMBLE[0]) return ParseResult.Invalid(nextPreamble(buffer))
        if (buffer.size < HEADER_SIZE + 1) return ParseResult.Incomplete
        if (buffer[1] != PREAMBLE[1] || buffer[2] != PREAMBLE[2]) return ParseResult.Invalid(nextPreamble(buffer))

        val lengthField = (buffer[5].toInt() and 0xff) or ((buffer[6].toInt() and 0xff) shl 8)
        if (lengthField < 3) return ParseResult.Invalid(nextPreamble(buffer))
        val packetSize = lengthField + 8
        if (buffer.size < packetSize) return ParseResult.Incomplete
        if (buffer[packetSize - 1] != EPILOGUE) return ParseResult.Invalid(nextPreamble(buffer))

        val packet = Packet(
            channel = buffer[3].toInt() and 0x0f,
            payload = buffer.copyOfRange(HEADER_SIZE, packetSize - 1),
            flags = buffer[4].toInt() and 0xff,
            opcode = buffer[7].toInt() and 0xff,
            serial = buffer[8].toInt() and 0xff,
            dataType = buffer[9].toInt() and 0xff,
        )
        return ParseResult.Complete(packet, packetSize)
    }

    private fun nextPreamble(buffer: ByteArray): Int {
        for (i in 1 until buffer.size) if (buffer[i] == PREAMBLE[0]) return i
        return buffer.size
    }
}
