package io.github.rafaeldatsch4afk.mibandfaces.key

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AuthKeyImporterTest {
    private val keyA = "0123456789abcdef0123456789abcdef"
    private val keyB = "fedcba9876543210fedcba9876543210"
    private val keyC = "a1a1a1a1b2b2b2b2c3c3c3c3d4d4d4d4"

    @Test
    fun logFieldsLastOccurrenceFirst() {
        val log = """
            2026-01-01 BleConnect encryptKey=$keyA mac=AA:BB
            2026-01-02 something token: "$keyB"
            2026-01-03 huamiAuthKey=0x${keyC.uppercase()}
            hash=0123456789abcdef0123456789abcdef0123 (longo demais, ignorado)
        """.trimIndent()
        val found = AuthKeyImporter(null).import("XiaomiFit.device.log", log.toByteArray())
        assertEquals(listOf(keyC, keyB, keyA), found.map { it.key })
        assertTrue(found.all { it.recommended })
        assertTrue(found[0].source.contains("huamiAuthKey"))
    }

    @Test
    fun registerListAnyRegionInRawFile() {
        // Simula o conteúdo binário de um -wal: chaves de região diferentes, com lixo em volta.
        val blob = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 1, 2, 3))
            write("registerList_de".toByteArray())
            write(byteArrayOf(0x5f, 0x10, 0x20))
            write(keyA.toByteArray())
            write(ByteArray(300))
            write("registerList_sg".toByteArray())
            write(byteArrayOf(0x00, 0x5f))
            write(keyB.toByteArray())
        }.toByteArray()
        val found = AuthKeyImporter(null).import("manifest.sqlite-wal", blob)
        assertEquals(setOf(keyA, keyB), found.map { it.key }.toSet())
        assertTrue(found.any { it.source.startsWith("registerList_de") })
        assertTrue(found.any { it.source.startsWith("registerList_sg") })
    }

    @Test
    fun zipWithManifestSqliteUsesAllRegions() {
        val db = "SQLite format 3\u0000".toByteArray() + ByteArray(100)
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("Mi Fitness/MHWCahe/42/VirtualDevice_registerList/manifest.sqlite"))
                z.write(db)
                z.closeEntry()
                z.putNextEntry(ZipEntry("__MACOSX/lixo"))
                z.write(keyC.toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        val rows = listOf(
            SqliteRow("registerList_de", listOf("registerList_de".toByteArray(), "bplist00...encryptKey...$keyA".toByteArray())),
            SqliteRow("registerList_ru", listOf("registerList_ru".toByteArray(), "bplist00...$keyB".toByteArray())),
        )
        val found = AuthKeyImporter { database, wal ->
            assertTrue(AuthKeyImporter.isSqlite(database))
            assertEquals(null, wal)
            rows
        }.import("MiFitness.zip", zip)
        assertEquals(listOf(keyA, keyB), found.map { it.key })
        assertTrue(found[0].source.contains("registerList_de"))
        assertTrue(found[1].source.contains("registerList_ru"))
    }

    @Test
    fun sqliteFallsBackToRawScanWhenUnreadable() {
        val db = "SQLite format 3\u0000".toByteArray() + "registerList_us\u0000\u0001$keyA".toByteArray()
        val found = AuthKeyImporter { _, _ -> throw IllegalStateException("corrompido") }.import("manifest.sqlite", db)
        assertEquals(listOf(keyA), found.map { it.key })
    }

    @Test
    fun nothingFound() {
        assertTrue(AuthKeyImporter(null).import("x.txt", "nada aqui".toByteArray()).isEmpty())
    }
}
