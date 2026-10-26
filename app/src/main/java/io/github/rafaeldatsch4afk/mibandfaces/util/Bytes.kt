package io.github.rafaeldatsch4afk.mibandfaces.util

import java.security.MessageDigest
import java.util.zip.CRC32

private val HEX = "0123456789abcdef".toCharArray()

/** Converte bytes em texto hexadecimal minúsculo, sem separadores. */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(out)
}

/** Hex limitado, para logs: pacotes grandes (chunks de upload) são truncados. */
fun ByteArray.toHexPreview(max: Int = 96): String =
    if (size <= max) toHex() else copyOf(max).toHex() + "… (+${size - max} bytes)"

/** Converte texto hexadecimal (com ou sem espaços) em bytes. Lança se o texto for inválido. */
fun String.hexToBytes(): ByteArray {
    val clean = filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0) { "hex com tamanho ímpar" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

fun u16le(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

fun u32le(value: Long): ByteArray = byteArrayOf(
    value.toByte(),
    (value ushr 8).toByte(),
    (value ushr 16).toByte(),
    (value ushr 24).toByte(),
)

fun ByteArray.readU16le(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

/** Lê um uint32 little-endian. Retorna Long para não perder o bit de sinal. */
fun ByteArray.readU32le(offset: Int): Long =
    (this[offset].toLong() and 0xff) or
        ((this[offset + 1].toLong() and 0xff) shl 8) or
        ((this[offset + 2].toLong() and 0xff) shl 16) or
        ((this[offset + 3].toLong() and 0xff) shl 24)

/** Lê uma string ASCII terminada em zero a partir de [offset]. Retorna null se não houver terminador. */
fun ByteArray.cString(offset: Int, maxLength: Int = 256): String? {
    if (offset < 0 || offset >= size) return null
    val end = minOf(size, offset + maxLength)
    for (i in offset until end) {
        if (this[i].toInt() == 0) return String(this, offset, i - offset, Charsets.UTF_8)
    }
    return null
}

fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value
