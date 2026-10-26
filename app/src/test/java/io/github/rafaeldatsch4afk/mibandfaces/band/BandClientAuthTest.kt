package io.github.rafaeldatsch4afk.mibandfaces.band

import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.protocol.LinkConfig
import io.github.rafaeldatsch4afk.mibandfaces.testutil.FakeBand
import io.github.rafaeldatsch4afk.mibandfaces.util.hexToBytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BandClientAuthTest {
    private val key = "0123456789abcdef0123456789abcdef".hexToBytes()
    private val companion = CompanionInfo(apiLevel = 35, phoneModel = "Pixel Teste", region = "PT")
    private val config = LinkConfig(resendTimeoutMs = 300, versionProbeTimeoutMs = 1_000, sessionTimeoutMs = 1_000)

    private fun client() = BandClient(PacketLog(), companion, config, requestTimeoutMs = 2_000)

    @Test
    fun authenticatesAndReadsInfo() = runBlocking {
        val band = FakeBand(key)
        val client = client()
        client.connect(band.transport, key)
        assertTrue(client.state.value is BandState.Ready)
        assertTrue(band.authenticated)
        val companionSent = band.companionReceived!!
        assertEquals(0, companionSent.unknown1)
        assertEquals(35f, companionSent.phoneApiLevel)
        assertEquals("Pixel Teste", companionSent.phoneName)
        assertEquals(224, companionSent.unknown3)
        assertEquals("PT", companionSent.region)
        // Depois da autenticação, comandos vão e voltam cifrados com AES-CTR.
        assertEquals("Xiaomi Smart Band 10", client.info.value.model)
        assertEquals("3.0.1", client.info.value.firmware)
        assertEquals(77, client.info.value.batteryPercent)
        client.disconnect()
        assertEquals(BandState.Disconnected, client.state.value)
    }

    @Test
    fun wrongKeyIsDetectedByHmac() = runBlocking {
        val band = FakeBand(key)
        val client = client()
        try {
            client.connect(band.transport, "ffffffffffffffffffffffffffffffff".hexToBytes())
            fail("deveria recusar a chave errada")
        } catch (e: AuthException) {
            assertTrue(e.message!!.contains("incorreta"))
        }
        assertTrue(client.state.value is BandState.Failed)
        assertTrue(!band.authenticated)
    }

    @Test
    fun noBoundRefusalIsExplained() = runBlocking {
        val band = FakeBand(key).apply { refuseNoBound = true }
        val client = client()
        try {
            client.connect(band.transport, key)
            fail("deveria recusar")
        } catch (e: AuthException) {
            assertTrue(e.message!!.contains("NO_BOUND"))
        }
    }

    @Test
    fun disconnectFailsPendingRequest() = runBlocking {
        val band = FakeBand(key)
        val client = client()
        client.connect(band.transport, key)
        band.transport.silent = true
        band.transport.dropConnection()
        // Espera o estado refletir a queda.
        kotlinx.coroutines.withTimeout(2_000) { while (client.state.value is BandState.Ready) kotlinx.coroutines.delay(10) }
        assertTrue(client.state.value is BandState.Failed)
        try {
            client.refreshInfo() // não lança: só registra o erro
            client.request(Commands.simple(Commands.SYSTEM, Commands.SYSTEM_BATTERY), "bateria") { true }
            fail("deveria falhar sem conexão")
        } catch (e: NotConnectedException) {
            // esperado
        }
    }
}
