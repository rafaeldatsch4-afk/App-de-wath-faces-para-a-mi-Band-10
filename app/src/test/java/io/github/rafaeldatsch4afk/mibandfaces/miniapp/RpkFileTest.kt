package io.github.rafaeldatsch4afk.mibandfaces.miniapp

import io.github.rafaeldatsch4afk.mibandfaces.testutil.RpkBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpkFileTest {
    private fun rejected(bytes: ByteArray): String {
        val parsed = RpkFile.parse(bytes)
        assertTrue("deveria recusar", parsed is RpkFile.Rejected)
        return (parsed as RpkFile.Rejected).reason
    }

    @Test
    fun readsManifestOfValidPackage() {
        val parsed = RpkFile.parse(RpkBuilder.build()) as RpkFile.Valid
        assertEquals("com.exemplo.calc", parsed.packageName)
        assertEquals("Calculadora", parsed.name)
        assertEquals("1.0.1", parsed.versionName)
        assertEquals(3, parsed.versionCode)
        assertEquals(212, parsed.designWidth)
        assertEquals(listOf("system.prompt", "system.vibrator"), parsed.features)
        assertEquals(4, parsed.icon!!.size)
        assertEquals(6, parsed.fileCount)
        assertEquals(emptyList<String>(), parsed.warnings)
    }

    @Test
    fun acceptsPackageNameWithoutDots() {
        // Pacote real de um jogo para a Mi Band 10.
        val parsed = RpkFile.parse(RpkBuilder.build(manifest = RpkBuilder.manifest(packageName = "Fruits_cut")))
        assertEquals("Fruits_cut", (parsed as RpkFile.Valid).packageName)
    }

    @Test
    fun warnsAboutOtherScreenWidthMissingEntryPageAndOtherDevices() {
        val parsed = RpkFile.parse(
            RpkBuilder.build(manifest = RpkBuilder.manifest(designWidth = 192, deviceType = "phone"), withEntryPage = false),
        ) as RpkFile.Valid
        assertEquals(3, parsed.warnings.size)
        assertTrue(parsed.warnings.any { it.contains("192 px") })
        assertTrue(parsed.warnings.any { it.contains("pages/index/index.js") })
        assertTrue(parsed.warnings.any { it.contains("watch") })
    }

    @Test
    fun rejectsNonZipAndWatchfaces() {
        assertTrue(!RpkFile.looksLikeRpk(byteArrayOf(0x5a, 0xa5.toByte(), 0, 0)))
        assertTrue(rejected(byteArrayOf(0x5a, 0xa5.toByte(), 0, 0)).contains("ZIP"))
    }

    @Test
    fun rejectsMissingOrBrokenManifest() {
        assertTrue(rejected(RpkBuilder.build(manifest = null)).contains("manifest.json"))
        assertTrue(rejected(RpkBuilder.build(manifest = "{ isto não é json")).contains("JSON"))
        assertTrue(rejected(RpkBuilder.build(manifest = """{"name": "x"}""")).contains("package"))
        assertTrue(rejected(RpkBuilder.build(manifest = RpkBuilder.manifest(packageName = "../../etc"))).contains("pacote"))
    }

    @Test
    fun rejectsPathTraversal() {
        assertTrue(rejected(RpkBuilder.build(extra = mapOf("../fora.js" to ByteArray(1)))).contains("caminho"))
    }

    @Test
    fun rejectsCorruptedZip() {
        val bytes = RpkBuilder.build()
        // Estraga um byte no meio dos dados compactados: o CRC ou a descompressão falham.
        val corrupted = bytes.copyOf().also { it[it.size / 3] = (it[it.size / 3].toInt() xor 0xff).toByte() }
        assertTrue(RpkFile.parse(corrupted) is RpkFile.Rejected)
    }
}
