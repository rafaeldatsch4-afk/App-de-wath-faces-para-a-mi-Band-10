package io.github.rafaeldatsch4afk.mibandfaces.face

import io.github.rafaeldatsch4afk.mibandfaces.util.readU16le
import io.github.rafaeldatsch4afk.mibandfaces.util.readU32le

/**
 * Checagem estrutural de uma watch face v2 antes de enviá-la para a banda. Portada de
 * `src/watchface/check.ts` do miband-hack (AGPL-3.0): uma Mi Band 8 parou de ligar depois de
 * receber uma face com um layout inesperado, então um arquivo cujas tabelas apontam para fora
 * dele, cujas imagens não descomprimem para o tamanho declarado ou cujos elementos apontam para
 * imagens inexistentes é recusado. Tipos de imagem e seções desconhecidos são ignorados (faces de
 * outras bandas usam), não recusados.
 */
object FaceStructureCheck {
    private const val HEADER_SIZE = 0xa8
    private const val SECTIONS = 10
    private const val IMAGES = 2
    private const val LISTS = 3
    private const val WIDGETS = 7
    private val RLE_MAGIC = byteArrayOf(0xe0.toByte(), 0x21, 0xa5.toByte(), 0x5a)

    /** Bytes por pixel dos tipos de payload comprimido que sabemos percorrer (2 = RGB565, 4 = BGRA). */
    private val PIXEL_SIZE = mapOf(2 to 2, 4 to 4)

    /** Quantidade de faces no arquivo: a normal e, depois, a "sempre ligada" (AOD). */
    fun faceCount(fw: ByteArray): Int = fw[0x1c].toInt() and 0xff

    /** Retorna o motivo pelo qual o arquivo não é seguro, ou null se a estrutura está coerente. */
    fun check(fw: ByteArray): String? {
        if (fw.size < HEADER_SIZE) return "arquivo menor que o cabeçalho"
        val size = fw.size.toLong()
        fun u32(o: Long): Long = fw.readU32le(o.toInt())
        fun u16(o: Long): Int = fw.readU16le(o.toInt())

        val faces = faceCount(fw)
        if (faces < 1 || faces > 4) return "$faces faces no cabeçalho"
        var h = HEADER_SIZE.toLong() + 4L * ((fw[0x1d].toInt() and 0xff) + (fw[0x18].toInt() and 0xff))

        for (f in 0 until faces) {
            if (h + 8 + 8 * SECTIONS > size) return "cabeçalho da face $f fora do arquivo"
            val count = LongArray(SECTIONS)
            val table = LongArray(SECTIONS)
            for (s in 0 until SECTIONS) {
                count[s] = u32(h + 8 + 8 * s)
                table[s] = u32(h + 12 + 8 * s)
                if (count[s] > 4096 || table[s] + 16 * count[s] > size) return "face $f: tabela da seção $s fora do arquivo"
            }
            h += 8 + 8 * SECTIONS

            fun entryOffset(s: Int, i: Int) = u32(table[s] + 16L * i + 8)
            fun entryLength(s: Int, i: Int) = u32(table[s] + 16L * i + 12)

            for (s in 0 until SECTIONS) {
                for (i in 0 until count[s].toInt()) {
                    if (entryOffset(s, i) + entryLength(s, i) > size) return "face $f: item $i da seção $s fora do arquivo"
                }
            }
            for (i in 0 until count[IMAGES].toInt()) {
                val off = entryOffset(IMAGES, i)
                val len = entryLength(IMAGES, i)
                if (len < 12) return "face $f: imagem $i curta demais"
                val bad = checkPixels(fw, off + 12, minOf(u32(off + 8), len - 12), u16(off + 4), u16(off + 6))
                if (bad != null) return "face $f: imagem $i: $bad"
            }
            for (i in 0 until count[LISTS].toInt()) {
                val off = entryOffset(LISTS, i)
                val len = entryLength(LISTS, i)
                if (len < 2) return "face $f: lista de imagens $i curta demais"
                val frames = fw[(off + 1).toInt()].toInt() and 0xff
                if (len < 12 + 4L * frames) return "face $f: lista de imagens $i curta demais"
                var p = off + 12 + 4L * frames
                for (k in 0 until frames) {
                    val frameSize = u32(off + 12 + 4L * k)
                    if (p + frameSize > off + len) return "face $f: quadro $k da lista $i fora da lista"
                    val bad = checkPixels(fw, p, frameSize, u16(off + 4), u16(off + 6))
                    if (bad != null) return "face $f: lista $i quadro $k: $bad"
                    p += frameSize
                }
            }
            // Um alvo é tipo << 24 | índice; imagens, listas e widgets citados precisam existir nesta face.
            fun missing(target: Long): Boolean {
                val type = (target ushr 24).toInt()
                val index = target and 0xffffff
                val n = when (type) {
                    0x02 -> count[IMAGES]
                    0x03 -> count[LISTS]
                    0x07 -> count[WIDGETS]
                    else -> Long.MAX_VALUE
                }
                return index >= n
            }
            for (i in 0 until count[0].toInt()) {
                val off = entryOffset(0, i)
                if (entryLength(0, i) >= 4 && missing(u32(off))) return "face $f: elemento $i aponta para um item inexistente"
            }
            for (i in 0 until count[WIDGETS].toInt()) {
                val off = entryOffset(WIDGETS, i)
                if (entryLength(WIDGETS, i) >= 12 && fw[(off + 11).toInt()].toInt() == 0x03 && missing(u32(off + 8))) {
                    return "face $f: widget $i aponta para uma lista de imagens inexistente"
                }
            }
        }
        return null
    }

    /** Percorre um payload comprimido (RLE do LVGL); retorna o problema ou null. Não aloca os pixels. */
    private fun checkPixels(fw: ByteArray, at: Long, len: Long, width: Int, height: Int): String? {
        if (len < 8 || at + len > fw.size) return "dados da imagem fora do arquivo"
        for (i in RLE_MAGIC.indices) {
            if (fw[(at + i).toInt()] != RLE_MAGIC[i]) return null // sem compressão ou outra codificação: não é conosco
        }
        val word = fw.readU32le((at + 4).toInt())
        val pixelSize = PIXEL_SIZE[(word and 0xf).toInt()] ?: return null
        val expect = width.toLong() * height * pixelSize
        if (word ushr 4 != expect) return "imagem ${width}×$height declara ${word ushr 4} bytes, precisa de $expect"
        var o = at + 8
        var out = 0L
        val end = at + len
        while (o < end) {
            val c = fw[(o++).toInt()].toInt() and 0xff
            val n = c and 0x7f
            if (n == 0) return "dados da imagem com uma sequência vazia"
            o += if (c and 0x80 != 0) n.toLong() * pixelSize else pixelSize.toLong()
            out += n.toLong() * pixelSize
        }
        if (o != end || out != expect) return "imagem ${width}×$height descomprime para $out bytes, precisa de $expect"
        return null
    }
}
