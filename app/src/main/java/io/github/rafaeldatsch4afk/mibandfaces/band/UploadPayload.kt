package io.github.rafaeldatsch4afk.mibandfaces.band

import io.github.rafaeldatsch4afk.mibandfaces.util.crc32
import io.github.rafaeldatsch4afk.mibandfaces.util.md5
import io.github.rafaeldatsch4afk.mibandfaces.util.u16le
import io.github.rafaeldatsch4afk.mibandfaces.util.u32le

/**
 * Montagem do envio de arquivo (XiaomiDataUploadService.doUpload no Gadgetbridge):
 *
 * ```
 * payload = 00 | tipo | md5(16) | tamanho total (u32 LE) | bytes[resume:] | crc32 (u32 LE) de tudo antes
 * parte   = total de partes (u16 LE) | índice começando em 1 (u16 LE) | pedaço do payload
 * ```
 */
object UploadPayload {
    /** Tamanho padrão quando a banda não informa chunkSize (Gadgetbridge: 2048). */
    const val DEFAULT_CHUNK_SIZE = 2048

    fun build(type: Int, file: ByteArray, resumePosition: Int = 0): ByteArray {
        require(resumePosition in 0..file.size) { "posição de retomada inválida" }
        val body = byteArrayOf(0, type.toByte()) + md5(file) + u32le(file.size.toLong()) +
            file.copyOfRange(resumePosition, file.size)
        return body + u32le(crc32(body))
    }

    /** Divide o payload em partes de no máximo [chunkSize] bytes (4 de cabeçalho + dados). */
    fun parts(payload: ByteArray, chunkSize: Int): List<ByteArray> {
        val dataPerPart = chunkSize - 4
        require(dataPerPart > 0) { "chunkSize pequeno demais: $chunkSize" }
        val total = (payload.size + dataPerPart - 1) / dataPerPart
        require(total <= 0xffff) { "arquivo grande demais para o envio em partes" }
        return List(total) { i ->
            val start = i * dataPerPart
            val end = minOf(payload.size, start + dataPerPart)
            u16le(total) + u16le(i + 1) + payload.copyOfRange(start, end)
        }
    }
}
