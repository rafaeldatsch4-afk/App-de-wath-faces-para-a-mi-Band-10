package io.github.rafaeldatsch4afk.mibandfaces.testutil

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Monta arquivos de watch face v2 sintéticos e mínimos, com o layout que o Gadgetbridge e o
 * miband-hack leem: cabeçalho de 0xA8 bytes, uma face com um elemento que aponta para uma imagem,
 * e uma imagem de preview RGB565 sem compressão.
 */
object FaceBuilder {
    fun build(
        id: String = "1234567",
        name: String = "Face de teste",
        previewWidth: Int = 212,
        previewHeight: Int = 520,
        withPreview: Boolean = true,
        rleImage: Boolean = true,
        corruptRle: Boolean = false,
        elementTarget: Int = 0x02000000, // tipo 2 (imagem), índice 0
        faces: Int = 2,
    ): ByteArray {
        val header = 0xa8
        val faceHeader = 8 + 8 * 10
        val tables = header + faceHeader * faces
        val tableBytes = 16 * 2 // um elemento + uma imagem (compartilhados pelas faces)
        val element = tables + tableBytes
        val image = element + 4
        val imageData = imageData(rleImage, corruptRle)
        val preview = image + imageData.size
        val previewPixels = if (withPreview) previewWidth * previewHeight * 2 else 0
        val total = preview + (if (withPreview) 12 + previewPixels else 0)

        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 0x5a)
        b.put(1, 0xa5.toByte())
        b.put(0x18, 0)
        b.put(0x1c, faces.toByte())
        b.put(0x1d, 0)
        b.putInt(0x20, if (withPreview) preview else 0)
        id.toByteArray().forEachIndexed { i, c -> b.put(0x28 + i, c) }
        name.toByteArray().forEachIndexed { i, c -> b.put(0x68 + i, c) }

        for (f in 0 until faces) {
            val h = header + faceHeader * f
            // seção 0 (elementos): 1 item na tabela em `tables`
            b.putInt(h + 8, 1)
            b.putInt(h + 12, tables)
            // seção 2 (imagens): 1 item na tabela em `tables + 16`
            b.putInt(h + 8 + 8 * 2, 1)
            b.putInt(h + 12 + 8 * 2, tables + 16)
        }
        // entrada do elemento: offset e tamanho em +8/+12
        b.putInt(tables + 8, element)
        b.putInt(tables + 12, 4)
        b.putInt(element, elementTarget)
        // entrada da imagem
        b.putInt(tables + 16 + 8, image)
        b.putInt(tables + 16 + 12, imageData.size)
        imageData.forEachIndexed { i, v -> b.put(image + i, v) }

        if (withPreview) {
            b.put(preview, 4) // formato 4 = RGB565
            b.put(preview + 1, 0) // sem compressão
            b.putShort(preview + 4, previewWidth.toShort())
            b.putShort(preview + 6, previewHeight.toShort())
            b.putInt(preview + 8, previewPixels)
            // pixels vermelhos (RGB565 0xF800)
            for (p in 0 until previewWidth * previewHeight) b.putShort(preview + 12 + p * 2, 0xF800.toShort())
        }
        return b.array()
    }

    /** Cabeçalho de imagem (12 bytes) + pixels 2×2 em RGB565 comprimidos com o RLE do LVGL. */
    private fun imageData(rle: Boolean, corrupt: Boolean): ByteArray {
        val w = 2
        val h = 2
        val pixels = if (rle) {
            val expect = w * h * 2
            val declared = if (corrupt) expect + 2 else expect
            val word = (declared shl 4) or 2 // tipo 2 = RGB565
            byteArrayOf(0xe0.toByte(), 0x21, 0xa5.toByte(), 0x5a) +
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(word).array() +
                byteArrayOf(0x04, 0x1f, 0x00) // repete 4× o pixel 0x001f
        } else {
            ByteArray(w * h * 2) { 0x55 }
        }
        val head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        head.put(0, 4)
        head.putShort(4, w.toShort())
        head.putShort(6, h.toShort())
        head.putInt(8, pixels.size)
        return head.array() + pixels
    }
}
