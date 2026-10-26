package io.github.rafaeldatsch4afk.mibandfaces.face

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Imagem decodificada em ARGB (um Int por pixel), sem depender de android.graphics. */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray)

/**
 * Decodificação da imagem de preview de uma watch face v2. Portado de XiaomiFWHelper
 * .getWatchfacePreview e XiaomiBitmapUtils (Gadgetbridge): descompressão RLE do LVGL (v1 e v2)
 * e os formatos de pixel conhecidos.
 */
object FacePreview {
    private val RLE_MAGIC = byteArrayOf(0xe0.toByte(), 0x21, 0xa5.toByte(), 0x5a)

    fun decode(fw: ByteArray, preview: FaceFile.PreviewHeader): ArgbImage? {
        val start = preview.offset + 12
        if (preview.dataSize < 0 || start + preview.dataSize > fw.size) return null
        var data = fw.copyOfRange(start, start + preview.dataSize)
        data = when (preview.compression) {
            0 -> data
            4 -> decompressRleV2(data) ?: return null
            8 -> decompressRleV1(data) ?: return null
            else -> return null
        }
        return decodePixels(data, preview.pixelFormat, preview.width, preview.height)
    }

    private fun rleHeader(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 8 || !RLE_MAGIC.indices.all { data[it] == RLE_MAGIC[it] }) return null
        val chunk = (data[4].toInt() and 0xf).let { if (it == 0) 1 else it }
        val word = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val size = (word shr 4) and 0xfffffff
        return chunk to size
    }

    /** XiaomiBitmapUtils.decompressLvglRleV1: bit 7 = repetir o próximo bloco n+1 vezes. */
    fun decompressRleV1(data: ByteArray): ByteArray? {
        val (chunk, size) = rleHeader(data) ?: return null
        if (size > MAX_PIXEL_BYTES) return null
        val out = ByteArray(size)
        var o = 0
        var i = 8
        while (i < data.size) {
            val control = data[i++].toInt() and 0xff
            val n = control and 0x7f
            if (o + chunk * (n + 1) > out.size) return null
            if (control and 0x80 != 0) {
                if (i + chunk > data.size) return null
                repeat(n + 1) {
                    data.copyInto(out, o, i, i + chunk)
                    o += chunk
                }
                i += chunk
            } else {
                val len = chunk * (n + 1)
                if (i + len > data.size) return null
                data.copyInto(out, o, i, i + len)
                o += len
                i += len
            }
        }
        return out
    }

    /** XiaomiBitmapUtils.decompressLvglRleV2: bit 7 = copiar n blocos literais; senão repetir 1 bloco n vezes. */
    fun decompressRleV2(data: ByteArray): ByteArray? {
        val (chunk, size) = rleHeader(data) ?: return null
        if (size > MAX_PIXEL_BYTES) return null
        val out = ByteArray(size)
        var o = 0
        var i = 8
        while (i < data.size) {
            val control = data[i++].toInt() and 0xff
            val n = control and 0x7f
            if (o + chunk * n > out.size) return null
            if (control and 0x80 != 0) {
                val len = chunk * n
                if (i + len > data.size) return null
                data.copyInto(out, o, i, i + len)
                o += len
                i += len
            } else {
                if (i + chunk > data.size) return null
                repeat(n) {
                    data.copyInto(out, o, i, i + chunk)
                    o += chunk
                }
                i += chunk
            }
        }
        return out
    }

    private fun rgb565(v: Int): Int {
        val c = v and 0xffff
        return ((c and 0xf800) shl 8) or ((c and 0x07e0) shl 5) or ((c and 0x001f) shl 3)
    }

    /** XiaomiBitmapUtils.decodeWatchfaceImage. */
    fun decodePixels(data: ByteArray, format: Int, width: Int, height: Int): ArgbImage? {
        if (width <= 0 || height <= 0 || width * height > MAX_PIXELS) return null
        val pixels = width * height
        val needed = when (format) {
            0 -> pixels * 4
            1, 3, 4, 7 -> pixels * 2
            6 -> pixels * 3
            16 -> 256 * 4 + pixels
            else -> return null
        }
        if (data.size < needed) return null
        val bb = ByteBuffer.wrap(data).order(if (format == 7) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val palette = if (format == 16) IntArray(256) { bb.int } else IntArray(0)
        val out = IntArray(pixels)
        for (p in 0 until pixels) {
            out[p] = when (format) {
                0 -> bb.int
                1, 3, 4, 7 -> 0xff000000.toInt() or rgb565(bb.short.toInt())
                6 -> {
                    val rgb = rgb565(bb.short.toInt())
                    val alpha = bb.get().toInt() and 0xff
                    (alpha shl 24) or rgb
                }
                else -> palette[bb.get().toInt() and 0xff]
            }
        }
        return ArgbImage(width, height, out)
    }

    private const val MAX_PIXELS = 1024 * 1024
    private const val MAX_PIXEL_BYTES = MAX_PIXELS * 4
}
