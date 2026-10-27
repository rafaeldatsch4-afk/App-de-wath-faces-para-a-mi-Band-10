package io.github.rafaeldatsch4afk.mibandfaces.band

import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.miniapp.RpkFile
import io.github.rafaeldatsch4afk.mibandfaces.protocol.LinkConfig
import io.github.rafaeldatsch4afk.mibandfaces.testutil.FaceBand
import io.github.rafaeldatsch4afk.mibandfaces.testutil.RpkBuilder
import io.github.rafaeldatsch4afk.mibandfaces.util.hexToBytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MiniAppInstallTest {
    private val key = "00112233445566778899aabbccddeeff".hexToBytes()
    private val companion = CompanionInfo(35, "Teste", "PT")
    private val config = LinkConfig(resendTimeoutMs = 300, versionProbeTimeoutMs = 1_000, sessionTimeoutMs = 1_000)
    private val rpk = RpkFile.parse(RpkBuilder.build()) as RpkFile.Valid

    private fun manager(client: BandClient) =
        MiniAppManager(client, PacketLog(), settleDelayMs = 50, confirmAttempts = 5, shortTimeoutMs = 300, reconnectWaitMs = 10_000)

    @Test
    fun installsRpkWithUploadType64() = runBlocking {
        val band = FaceBand(key).apply { chunkSize = 512 }
        val client = BandClient(PacketLog(), companion, config, requestTimeoutMs = 2_000, keepAliveIdleMs = 0)
        client.connect(band.transport, key)
        val apps = manager(client)
        assertEquals(0, apps.refresh().size)
        apps.install(rpk, replaceExisting = false) {}
        assertEquals(64, band.uploadType)
        assertEquals(true, band.uploadOk)
        assertEquals(listOf("com.exemplo.calc"), apps.apps.value!!.map { it.id })
        // A banda recebeu o versionCode e o tamanho no pedido 20/1.
        val request = band.commandsReceived.first { it.type == 20 && it.subtype == 1 }.rpk.rpkInfo
        assertEquals(3, request.unknown2)
        assertEquals(rpk.bytes.size, request.size)
        client.disconnect()
    }

    @Test
    fun asksBeforeReplacingAndDeletesWithSha() = runBlocking {
        val band = FaceBand(key).apply { apps += "com.exemplo.calc" to "Calculadora antiga" }
        val client = BandClient(PacketLog(), companion, config, requestTimeoutMs = 2_000, keepAliveIdleMs = 0)
        client.connect(band.transport, key)
        val apps = manager(client)
        try {
            apps.install(rpk, replaceExisting = false) {}
            fail("deveria pedir confirmação")
        } catch (e: AppAlreadyInstalledException) {
            assertEquals("Calculadora antiga", e.app.name)
        }
        apps.install(rpk, replaceExisting = true) {}
        // Removeu a antiga (com o sha da lista) e instalou a nova.
        assertTrue(band.commandsReceived.any { it.type == 20 && it.subtype == 3 })
        assertEquals(listOf("com.exemplo.calc" to "Novo app"), band.apps.toList())
        client.disconnect()
    }

    @Test
    fun reportsRefusal() = runBlocking {
        val band = FaceBand(key).apply { rpkInstallStatus = 5 }
        val client = BandClient(PacketLog(), companion, config, requestTimeoutMs = 2_000, keepAliveIdleMs = 0)
        client.connect(band.transport, key)
        try {
            manager(client).install(rpk, replaceExisting = false) {}
            fail("deveria falhar")
        } catch (e: InstallException) {
            assertTrue(e.message!!.contains("status 5"))
        }
        // Uma recusa antes do envio não derruba a conexão.
        assertTrue(client.isReady)
        assertEquals(0, band.uploadType)
        client.disconnect()
    }

    @Test
    fun resumesAfterAutomaticReconnection() = runBlocking {
        val band = FaceBand(key).apply {
            chunkSize = 512
            resumable = true
        }
        val client = BandClient(
            PacketLog(), companion, config, requestTimeoutMs = 2_000, keepAliveIdleMs = 0,
            reconnectDelaysMs = listOf(50, 50, 50),
        )
        client.connect({ band.newTransport() }, key, autoReconnect = true)
        var dropped = false
        // Asserções dentro do onPart rodariam na thread da banda simulada: confere o tamanho antes.
        assertTrue("arquivo pequeno demais para o teste", rpk.bytes.size > 4 * 512)
        band.onPart = { index, _ ->
            if (index == 2 && !dropped) {
                dropped = true
                band.transport.silent = true
                band.transport.dropConnection()
            }
        }
        manager(client).install(rpk, replaceExisting = false) {}
        assertEquals(2, band.connections)
        assertEquals(2 * (512 - 4) - 22, band.lastResumePosition)
        assertEquals(true, band.uploadOk)
        client.disconnect()
    }
}
