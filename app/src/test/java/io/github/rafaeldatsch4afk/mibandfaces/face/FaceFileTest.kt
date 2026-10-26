package io.github.rafaeldatsch4afk.mibandfaces.face

import io.github.rafaeldatsch4afk.mibandfaces.testutil.FaceBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FaceFileTest {

    @Test
    fun parsesValidV2Face() {
        val face = FaceFile.parse(FaceBuilder.build(id = "8888001", name = "Minha face")) as FaceFile.V2
        assertEquals("8888001", face.id)
        assertEquals("Minha face", face.name)
        assertEquals(2, face.faceCount)
        assertEquals(212, face.preview!!.width)
        assertEquals(520, face.preview!!.height)
        assertEquals(Compatibility.EXACT, face.compatibility)
        assertTrue(face.canInstall)
    }

    @Test
    fun decodesPreview() {
        val bytes = FaceBuilder.build()
        val face = FaceFile.parse(bytes) as FaceFile.V2
        val image = FacePreview.decode(bytes, face.preview!!)!!
        assertEquals(212, image.width)
        assertEquals(520, image.height)
        // RGB565 0xF800 = vermelho puro (com os 3 bits baixos zerados)
        assertEquals(0xFFF80000.toInt(), image.pixels[0])
    }

    @Test
    fun compatibilityRules() {
        fun compat(w: Int, h: Int) = FaceFile.compatibility(FaceFile.PreviewHeader(0, 4, 0, w, h, 0))
        assertEquals(Compatibility.EXACT, compat(212, 520))
        assertEquals(Compatibility.SAME_ASPECT, compat(106, 260))
        assertEquals(Compatibility.INCOMPATIBLE, compat(192, 490)) // Mi Band 9
        assertEquals(Compatibility.INCOMPATIBLE, compat(424, 1040)) // maior que a tela
        assertEquals(Compatibility.UNKNOWN, FaceFile.compatibility(null))
        val noPreview = FaceFile.parse(FaceBuilder.build(withPreview = false)) as FaceFile.V2
        assertEquals(Compatibility.UNKNOWN, noPreview.compatibility)
        val band9 = FaceFile.parse(FaceBuilder.build(previewWidth = 192, previewHeight = 490)) as FaceFile.V2
        assertTrue(!band9.canInstall)
    }

    @Test
    fun rejectsNonNumericId() {
        val r = FaceFile.parse(FaceBuilder.build(id = "abc")) as FaceFile.Rejected
        assertTrue(r.reason.contains("ID"))
    }

    @Test
    fun rejectsBrokenRleImage() {
        val r = FaceFile.parse(FaceBuilder.build(corruptRle = true)) as FaceFile.Rejected
        assertTrue(r.reason, r.reason.contains("Estrutura interna"))
    }

    @Test
    fun rejectsElementPointingToMissingImage() {
        val r = FaceFile.parse(FaceBuilder.build(elementTarget = 0x02000005)) as FaceFile.Rejected
        assertTrue(r.reason, r.reason.contains("inexistente"))
    }

    @Test
    fun rejectsTruncatedFile() {
        val full = FaceBuilder.build()
        val r = FaceFile.parse(full.copyOf(0x100)) as FaceFile.Rejected
        assertTrue(r.reason, r.reason.contains("Estrutura interna"))
    }

    @Test
    fun rejectsTableOutsideFile() {
        val bytes = FaceBuilder.build()
        // aponta a tabela da seção 2 da primeira face para muito além do fim do arquivo
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0xa8 + 12 + 16, bytes.size + 100)
        val r = FaceFile.parse(bytes) as FaceFile.Rejected
        assertTrue(r.reason, r.reason.contains("fora do arquivo"))
    }

    @Test
    fun rejectsOtherFormats() {
        val v3 = ByteArray(200).also {
            byteArrayOf(0x00, 0x00, 0x06, 0x07, 0x00, 0x02, 0x00, 0x01).copyInto(it)
            it[20] = 3
            "abc".toByteArray().copyInto(it, 21)
        }
        val r3 = FaceFile.parse(v3) as FaceFile.Rejected
        assertTrue(r3.reason.contains("v3"))
        assertEquals("abc", r3.name)

        val firmware = byteArrayOf(96, 90, 90, 0, 126) + ByteArray(100)
        assertTrue((FaceFile.parse(firmware) as FaceFile.Rejected).reason.contains("FIRMWARE"))

        val zip = byteArrayOf(0x50, 0x4b, 3, 4) + ByteArray(100)
        assertTrue((FaceFile.parse(zip) as FaceFile.Rejected).reason.contains("ZIP"))

        assertTrue(FaceFile.parse(ByteArray(0)) is FaceFile.Rejected)
        assertTrue(FaceFile.parse(ByteArray(300) { 1 }) is FaceFile.Rejected)
    }

    @Test
    fun localizedNamePrefersPortuguese() {
        val bytes = FaceBuilder.build(name = "")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // nome traduzido: FF FF FF FF em 0x68 e tabela em 0x74/0x78. Usa o espaço livre do preview.
        for (i in 0 until 4) bytes[0x68 + i] = 0xff.toByte()
        val names = listOf("English" to 0, "Português" to 8) // en_US = bit 0, pt_BR = bit 8
        val mask = names.fold(0L) { acc, (_, bit) -> acc or (1L shl bit) }
        val encoded = names.map { it.first.toByteArray() }
        val table = ByteBuffer.allocate(8 + 4 * encoded.size + encoded.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        table.putLong(mask)
        encoded.forEach { table.putInt(it.size) }
        encoded.forEach { table.put(it) }
        val offset = bytes.size - table.capacity() - 16
        table.array().copyInto(bytes, offset)
        b.putInt(0x74, offset)
        b.putInt(0x78, table.capacity())
        val face = FaceFile.parse(bytes) as FaceFile.V2
        assertEquals("Português", face.name)
    }

    @Test
    fun rleDecompressors() {
        // v2: 0x83 = copia 3 blocos literais; 0x02 = repete 1 bloco 2 vezes (bloco de 1 byte)
        val header = byteArrayOf(0xe0.toByte(), 0x21, 0xa5.toByte(), 0x5a) +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((5 shl 4) or 1).array()
        val v2 = FacePreview.decompressRleV2(header + byteArrayOf(0x83.toByte(), 1, 2, 3, 0x02, 9))
        assertNotNull(v2)
        assertEquals(listOf<Byte>(1, 2, 3, 9, 9), v2!!.toList())
        // v1: 0x81 = repete o próximo bloco 2 vezes; 0x02 = copia 3 blocos literais
        val v1 = FacePreview.decompressRleV1(header + byteArrayOf(0x81.toByte(), 7, 0x02, 4, 5, 6))
        assertEquals(listOf<Byte>(7, 7, 4, 5, 6), v1!!.toList())
        // dados que estouram o tamanho declarado
        assertNull(FacePreview.decompressRleV2(header + byteArrayOf(0x86.toByte(), 1, 2, 3, 4, 5, 6)))
    }
}
