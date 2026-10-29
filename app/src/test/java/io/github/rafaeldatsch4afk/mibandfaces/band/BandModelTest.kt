package io.github.rafaeldatsch4afk.mibandfaces.band

import io.github.rafaeldatsch4afk.mibandfaces.face.Compatibility
import io.github.rafaeldatsch4afk.mibandfaces.face.FaceFile
import io.github.rafaeldatsch4afk.mibandfaces.miniapp.RpkFile
import io.github.rafaeldatsch4afk.mibandfaces.testutil.FaceBuilder
import io.github.rafaeldatsch4afk.mibandfaces.testutil.RpkBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandModelTest {
    @Test
    fun detectsModelFromBluetoothName() {
        assertEquals(BandModel.BAND_8, BandModel.fromDeviceName("Xiaomi Smart Band 8 A1B2"))
        assertEquals(BandModel.BAND_9, BandModel.fromDeviceName("Xiaomi Smart Band 9 0F3C"))
        assertEquals(BandModel.BAND_10, BandModel.fromDeviceName("Xiaomi Smart Band 10 7E21"))
        assertEquals(BandModel.BAND_9, BandModel.fromDeviceName("Mi Band 9"))
        // Pro e Active têm outra tela.
        assertNull(BandModel.fromDeviceName("Xiaomi Smart Band 9 Pro 1234"))
        assertNull(BandModel.fromDeviceName("Xiaomi Smart Band 8 Active 1234"))
        assertNull(BandModel.fromDeviceName("Xiaomi Smart Band 7"))
        assertNull(BandModel.fromDeviceName(null))
        // "Band 10" não pode ser confundida com "Band 1".
        assertNull(BandModel.fromDeviceName("Mi Band 1"))
    }

    @Test
    fun previewIsCheckedAgainstTheChosenModel() {
        fun compat(w: Int, h: Int, model: BandModel) = FaceFile.compatibility(FaceFile.PreviewHeader(0, 4, 0, w, h, 0), model)
        assertEquals(Compatibility.EXACT, compat(192, 490, BandModel.BAND_9))
        assertEquals(Compatibility.EXACT, compat(192, 490, BandModel.BAND_8))
        assertEquals(Compatibility.KNOWN_SIZE, compat(122, 310, BandModel.BAND_8))
        assertEquals(Compatibility.INCOMPATIBLE, compat(212, 520, BandModel.BAND_9))
        assertEquals(Compatibility.INCOMPATIBLE, compat(192, 490, BandModel.BAND_10))
        val band9Face = FaceFile.parse(FaceBuilder.build(previewWidth = 192, previewHeight = 490), BandModel.BAND_9) as FaceFile.V2
        assertTrue(band9Face.canInstall)
        assertTrue(band9Face.compatibilityText.contains("Mi Band 9"))
    }

    @Test
    fun band8RequiresTheAlwaysOnFace() {
        val single = FaceFile.parse(FaceBuilder.build(previewWidth = 192, previewHeight = 490, faces = 1), BandModel.BAND_8)
        assertTrue(single is FaceFile.Rejected)
        assertTrue((single as FaceFile.Rejected).reason.contains("AOD"))
        val both = FaceFile.parse(FaceBuilder.build(previewWidth = 192, previewHeight = 490, faces = 2), BandModel.BAND_8)
        assertTrue(both is FaceFile.V2)
        // Na Mi Band 9 e 10 a regra não se aplica.
        assertTrue(FaceFile.parse(FaceBuilder.build(previewWidth = 192, previewHeight = 490, faces = 1), BandModel.BAND_9) is FaceFile.V2)
    }

    @Test
    fun band8MiniAppsAreExperimental() {
        // 192 px, a tela da Mi Band 8: só sobra o aviso de experimental.
        val parsed = RpkFile.parse(RpkBuilder.build(manifest = RpkBuilder.manifest(designWidth = 192)), BandModel.BAND_8) as RpkFile.Valid
        assertEquals(1, parsed.warnings.size)
        assertTrue(parsed.warnings[0].contains("Experimental na Mi Band 8"))
        assertTrue(BandModel.BAND_8.mayRunApps)
        assertTrue(!BandModel.BAND_8.supportsApps)
        val band9 = RpkFile.parse(RpkBuilder.build(manifest = RpkBuilder.manifest(designWidth = 192)), BandModel.BAND_9) as RpkFile.Valid
        assertEquals(emptyList<String>(), band9.warnings)
    }
}
