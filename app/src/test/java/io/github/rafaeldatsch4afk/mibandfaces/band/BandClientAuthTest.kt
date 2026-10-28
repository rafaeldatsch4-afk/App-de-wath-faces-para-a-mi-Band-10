package io.github.rafaeldatsch4afk.mibandfaces.band

import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.protocol.LinkConfig
import io.github.rafaeldatsch4afk.mibandfaces.testutil.FakeBand
import io.github.rafaeldatsch4afk.mibandfaces.testutil.FakeBandTransport
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

    private fun reconnectingClient() = BandClient(
        PacketLog(), companion, config, requestTimeoutMs = 2_000, keepAliveIdleMs = 0,
        reconnectDelaysMs = listOf(50, 50, 50),
    )

    @Test
    fun reconnectsAutomaticallyWhenTheBandDrops() = runBlocking {
        val band = FakeBand(key)
        val client = reconnectingClient()
        client.connect({ band.newTransport() }, key, autoReconnect = true)
        val generation = client.connectionGeneration
        band.transport.dropConnection()
        assertTrue("não reconectou", client.awaitReconnection(generation, 10_000))
        assertTrue(client.isReady)
        assertEquals(2, band.connections)
        assertTrue(band.authenticated)
        // A conexão nova funciona.
        client.refreshInfo()
        assertEquals(77, client.info.value.batteryPercent)
        client.disconnect()
        assertEquals(BandState.Disconnected, client.state.value)
    }

    @Test
    fun givesUpReconnectingAfterFailedAttempts() = runBlocking {
        val band = FakeBand(key)
        val client = reconnectingClient()
        var created = 0
        client.connect({
            created++
            // Da segunda vez em diante a banda não responde (fora de alcance).
            if (created == 1) band.newTransport() else FakeBandTransport().apply { silent = true }
        }, key, autoReconnect = true)
        val generation = client.connectionGeneration
        band.transport.dropConnection()
        assertEquals(false, client.awaitReconnection(generation, 15_000))
        val state = client.state.value
        assertTrue("$state", state is BandState.Failed && state.message.contains("reconectar"))
        assertEquals(4, created)
    }

    @Test
    fun doesNotReconnectAfterUserDisconnects() = runBlocking {
        val band = FakeBand(key)
        val client = reconnectingClient()
        client.connect({ band.newTransport() }, key, autoReconnect = true)
        client.disconnect()
        kotlinx.coroutines.delay(300)
        assertEquals(BandState.Disconnected, client.state.value)
        assertEquals(1, band.connections)
    }

    @Test
    fun authenticatesOverSppV1WithCcm() = runBlocking {
        // Firmware antigo da Mi Band 9: responde à sondagem com versão 1.
        val band = FakeBand(key, FakeBandTransport(versionPayload = "01".hexToBytes()))
        val client = client()
        client.connect(band.transport, key)
        assertTrue(client.state.value is BandState.Ready)
        assertTrue((client.state.value as BandState.Ready).transport.contains("SPP V1"))
        // Hora, informações e bateria foram e voltaram cifrados com AES-CCM.
        assertTrue(band.commandsReceived.any { it.type == 2 && it.subtype == 3 })
        assertEquals("Xiaomi Smart Band 10", client.info.value.model)
        assertEquals(77, client.info.value.batteryPercent)
        client.disconnect()
    }

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
    fun syncsTimeRightAfterAuthentication() = runBlocking {
        val band = FakeBand(key)
        val fixed = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("America/Sao_Paulo")).apply {
            set(2026, java.util.Calendar.OCTOBER, 26, 14, 5, 9)
            set(java.util.Calendar.MILLISECOND, 321)
        }
        val client = BandClient(PacketLog(), companion, config, requestTimeoutMs = 2_000, is24HourFormat = { false }, clock = { fixed })
        client.connect(band.transport, key)
        // O primeiro comando depois da autenticação (tipo 1) é o acerto de hora 2/3.
        val afterAuth = band.commandsReceived.filter { it.type != XiaomiAuth.COMMAND_TYPE }
        val clockCommand = afterAuth.first()
        assertEquals(Commands.SYSTEM, clockCommand.type)
        assertEquals(Commands.SYSTEM_CLOCK, clockCommand.subtype)
        val clock = clockCommand.system.clock
        assertEquals(2026, clock.date.year)
        assertEquals(10, clock.date.month)
        assertEquals(26, clock.date.day)
        assertEquals(14, clock.time.hour)
        assertEquals(5, clock.time.minute)
        assertEquals(9, clock.time.second)
        assertEquals(321, clock.time.millisecond)
        assertEquals(-12, clock.timezone.zoneOffset) // UTC−3 em blocos de 15 min
        assertEquals(0, clock.timezone.dstOffset)
        assertEquals("America/Sao_Paulo", clock.timezone.name)
        assertTrue(clock.isNot24Hour)
        assertTrue(client.state.value is BandState.Ready)
        client.disconnect()
    }

    @Test
    fun keepsIdleConnectionAliveWithBatteryRequests() = runBlocking {
        val band = FakeBand(key)
        val client = BandClient(PacketLog(), companion, config, requestTimeoutMs = 2_000, keepAliveIdleMs = 200)
        client.connect(band.transport, key)
        band.transport.awaitIdle()
        val before = synchronized(band.commandsReceived) { band.commandsReceived.count { it.type == 2 && it.subtype == 1 } }
        kotlinx.coroutines.delay(1_200)
        band.transport.awaitIdle()
        val after = synchronized(band.commandsReceived) { band.commandsReceived.count { it.type == 2 && it.subtype == 1 } }
        // Conexão parada: o app pede a bateria periodicamente para a banda não fechar o canal.
        assertTrue("keep-alives enviados: ${after - before}", after - before >= 2)
        assertTrue(client.state.value is BandState.Ready)
        client.disconnect()
        val stopped = synchronized(band.commandsReceived) { band.commandsReceived.size }
        kotlinx.coroutines.delay(600)
        // Depois de desconectar, o keep-alive para.
        assertEquals(stopped, synchronized(band.commandsReceived) { band.commandsReceived.size })
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
