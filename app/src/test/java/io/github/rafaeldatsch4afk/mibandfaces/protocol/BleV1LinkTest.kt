package io.github.rafaeldatsch4afk.mibandfaces.protocol

import io.github.rafaeldatsch4afk.mibandfaces.band.BandClient
import io.github.rafaeldatsch4afk.mibandfaces.band.BandState
import io.github.rafaeldatsch4afk.mibandfaces.band.CompanionInfo
import io.github.rafaeldatsch4afk.mibandfaces.band.WatchfaceManager
import io.github.rafaeldatsch4afk.mibandfaces.face.FaceFile
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.testutil.FaceBand
import io.github.rafaeldatsch4afk.mibandfaces.testutil.FaceBuilder
import io.github.rafaeldatsch4afk.mibandfaces.testutil.FakeBand
import io.github.rafaeldatsch4afk.mibandfaces.testutil.FakeBleV1Peer
import io.github.rafaeldatsch4afk.mibandfaces.util.hexToBytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleV1LinkTest {
    private val key = "0123456789abcdef0123456789abcdef".hexToBytes()
    private val companion = CompanionInfo(apiLevel = 35, phoneModel = "Pixel Teste", region = "PT")

    private fun client() = BandClient(PacketLog(), companion, requestTimeoutMs = 3_000, keepAliveIdleMs = 0)

    @Test
    fun authenticatesAndTalksEncryptedOverBleV1() = runBlocking {
        val band = FakeBand(key)
        val peer = FakeBleV1Peer(band, maxWriteSize = 64)
        val client = client()
        client.connectLink({ BleV1Link(peer, PacketLog()) }, key, autoReconnect = false)
        assertTrue(client.state.value is BandState.Ready)
        assertTrue(band.authenticated)
        assertEquals("Pixel Teste", band.companionReceived!!.phoneName)
        // A confirmação da autenticação passa de 64 bytes: foi em partes.
        assertTrue(peer.chunkedFromPhone.contains(BleV1Link.CMD_WRITE))
        // Hora, modelo e bateria foram e voltaram cifrados com AES-CCM.
        assertTrue(band.commandsReceived.any { it.type == 2 && it.subtype == 3 })
        assertEquals("Xiaomi Smart Band 10", client.info.value.model)
        assertEquals(77, client.info.value.batteryPercent)
        client.disconnect()
    }

    @Test
    fun installsWatchfaceThroughCharacteristic0055() = runBlocking {
        val band = FaceBand(key).apply { chunkSize = 512 }
        val peer = FakeBleV1Peer(band, maxWriteSize = 180)
        val client = client()
        client.connectLink({ BleV1Link(peer, PacketLog()) }, key, autoReconnect = false)
        val manager = WatchfaceManager(client, PacketLog(), settleDelayMs = 50, shortTimeoutMs = 1_000)
        val face = FaceFile.parse(FaceBuilder.build(id = "300")) as FaceFile.V2
        // A lista de faces (resposta grande) também chega em partes pela 0051.
        manager.install(face, replaceExisting = false) {}
        assertEquals(true, band.uploadOk)
        assertTrue(peer.chunkedFromPhone.contains(BleV1Link.UPLOAD))
        assertTrue(band.faces.any { it.id == "300" && it.active })
        client.disconnect()
    }

    @Test
    fun resendsChunkTheBandAsksFor() = runBlocking {
        val band = FakeBand(key)
        val peer = FakeBleV1Peer(band, maxWriteSize = 64).apply { dropChunkOnce = 1 }
        val client = client()
        client.connectLink({ BleV1Link(peer, PacketLog()) }, key, autoReconnect = false)
        assertTrue(client.state.value is BandState.Ready)
        assertEquals(1, peer.resendRequests)
        client.disconnect()
    }

    @Test
    fun refusesFileUploadWithoutCharacteristic0055() = runBlocking {
        val band = FaceBand(key)
        val peer = FakeBleV1Peer(band, withUpload = false)
        val client = client()
        client.connectLink({ BleV1Link(peer, PacketLog()) }, key, autoReconnect = false)
        val manager = WatchfaceManager(client, PacketLog(), settleDelayMs = 50, shortTimeoutMs = 1_000)
        val face = FaceFile.parse(FaceBuilder.build(id = "300")) as FaceFile.V2
        try {
            manager.install(face, replaceExisting = false) {}
            org.junit.Assert.fail("deveria recusar")
        } catch (e: LinkUnsupportedException) {
            assertTrue(e.message!!.contains("0055"))
        }
        assertEquals(null, band.uploadOk)
    }
}
