package io.github.rafaeldatsch4afk.mibandfaces.protocol

/**
 * Enquadramento SPP V1 (XiaomiSppPacketV1 no Gadgetbridge). Este app só usa o V1 para a
 * "sondagem de versão" logo após abrir o socket RFCOMM: se a banda responder com
 * `payload[0] >= 2`, a sessão continua em SPP V2. A Mi Band 10 responde `03 00 41`.
 *
 * ```
 * ba dc fe | canal(1) | flags(1) | tamanho(2 LE = payload + 3) | opcode | serial | dataType | payload | ef
 * ```
 */
object SppV1 {
    private val PREAMBLE = byteArrayOf(0xba.toByte(), 0xdc.toByte(), 0xfe.toByte())
    private const val EPILOGUE: Byte = 0xef.toByte()
    private const val HEADER_SIZE = 10 // preâmbulo(3) + canal + flags + tamanho(2) + opcode + serial + dataType

    const val CHANNEL_VERSION = 0
    private const val FLAG = 0x80
    private const val NEEDS_RESPONSE = 0x40
    private const val OPCODE_READ = 0
    private const val DATA_TYPE_PLAIN = 0

    /** Pedido de versão: `badcfe 00 c0 0300 00 00 00 ef`. */
    fun versionRequest(): ByteArray = byteArrayOf(
        *PREAMBLE,
        CHANNEL_VERSION.toByte(),
        (FLAG or NEEDS_RESPONSE).toByte(),
        3, 0, // tamanho = payload vazio + 3
        OPCODE_READ.toByte(),
        0, // serial do quadro
        DATA_TYPE_PLAIN.toByte(),
        EPILOGUE,
    )

    data class Packet(val channel: Int, val payload: ByteArray)

    sealed interface ParseResult {
        /** Faltam bytes para completar o pacote. */
        data object Incomplete : ParseResult

        /** Lixo no início do buffer: descartar [skip] bytes e tentar de novo. */
        data class Invalid(val skip: Int) : ParseResult

        data class Complete(val packet: Packet, val size: Int) : ParseResult
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

        val channel = buffer[3].toInt() and 0x0f
        val payload = buffer.copyOfRange(HEADER_SIZE, packetSize - 1)
        return ParseResult.Complete(Packet(channel, payload), packetSize)
    }

    private fun nextPreamble(buffer: ByteArray): Int {
        for (i in 1 until buffer.size) if (buffer[i] == PREAMBLE[0]) return i
        return buffer.size
    }
}
