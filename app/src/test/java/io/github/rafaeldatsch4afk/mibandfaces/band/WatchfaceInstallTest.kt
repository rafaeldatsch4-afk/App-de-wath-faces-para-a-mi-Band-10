package io.github.rafaeldatsch4afk.mibandfaces.band

import io.github.rafaeldatsch4afk.mibandfaces.face.FaceFile
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.protocol.LinkConfig
import io.github.rafaeldatsch4afk.mibandfaces.testutil.FaceBand
import io.github.rafaeldatsch4afk.mibandfaces.testutil.FaceBuilder
import io.github.rafaeldatsch4afk.mibandfaces.util.crc32
import io.github.rafaeldatsch4afk.mibandfaces.util.hexToBytes
import io.github.rafaeldatsch4afk.mibandfaces.util.md5
import io.github.rafaeldatsch4afk.mibandfaces.util.readU16le
import io.github.rafaeldatsch4afk.mibandfaces.util.readU32le
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WatchfaceInstallTest {
    private val key = "00112233445566778899aabbccddeeff".hexToBytes()
    private val companion = CompanionInfo(35, "Teste", "PT")
    private val config = LinkConfig(resendTimeoutMs = 300, versionProbeTimeoutMs = 1_000, sessionTimeoutMs = 1_000)

    private suspend fun connected(band: FaceBand): Pair<BandClient, WatchfaceManager> {
        val client = BandClient(PacketLog(), companion, config, requestTimeoutMs = 2_000)
        client.connect(band.transport, key)
        return client to WatchfaceManager(client, PacketLog(), settleDelayMs = 50, shortTimeoutMs = 1_000)
    }

    private val face = FaceFile.parse(FaceBuilder.build(id = "300")) as FaceFile.V2

    @Test
    fun uploadPayloadLayout() {
        val file = ByteArray(5000) { (it * 7).toByte() }
        val payload = UploadPayload.build(16, file)
        assertEquals(0, payload[0].toInt())
        assertEquals(16, payload[1].toInt())
        assertArrayEquals(md5(file), payload.copyOfRange(2, 18))
        assertEquals(5000L, payload.readU32le(18))
        assertArrayEquals(file, payload.copyOfRange(22, 22 + file.size))
        assertEquals(crc32(payload.copyOf(payload.size - 4)), payload.readU32le(payload.size - 4))

        val parts = UploadPayload.parts(payload, 2048)
        assertEquals(3, parts.size)
        parts.forEachIndexed { i, part ->
            assertTrue(part.size <= 2048)
            assertEquals(3, part.readU16le(0))
            assertEquals(i + 1, part.readU16le(2))
        }
        assertArrayEquals(payload, parts.fold(ByteArray(0)) { acc, p -> acc + p.copyOfRange(4, p.size) })

        val resumed = UploadPayload.build(16, file, resumePosition = 1000)
        assertEquals(5000L, resumed.readU32le(18)) // o tamanho é sempre o do arquivo inteiro
        assertArrayEquals(file.copyOfRange(1000, 5000), resumed.copyOfRange(22, resumed.size - 4))
    }

    @Test
    fun installsUploadsAndActivates() = runBlocking {
        val band = FaceBand(key)
        val (client, manager) = connected(band)
        val progress = mutableListOf<InstallProgress>()
        manager.install(face, replaceExisting = false) { progress += it }
        assertEquals(true, band.uploadOk)
        assertTrue(band.partSizes.all { it <= 1024 })
        assertEquals(band.partSizes.size, (UploadPayload.build(16, face.bytes).size + 1019) / 1020)
        val installed = manager.faces.value!!.single { it.id == "300" }
        assertTrue(installed.active)
        assertTrue(progress.any { it.fraction == 1f })
        client.disconnect()
    }

    @Test
    fun usesDefaultChunkSizeWhenBandOmitsIt() = runBlocking {
        val band = FaceBand(key).apply { chunkSize = null }
        val (client, manager) = connected(band)
        manager.install(face, replaceExisting = false) {}
        assertEquals(true, band.uploadOk)
        assertTrue(band.partSizes.max() == 2048 || band.partSizes.size == 1)
        client.disconnect()
    }

    @Test
    fun refusesWhenSameFaceIsInstalledUnlessReplacing() = runBlocking {
        val band = FaceBand(key).apply { faces += FaceBand.Face("300", "Versão antiga", active = true, canDelete = true) }
        band.faces.first().active = false
        val (client, manager) = connected(band)
        try {
            manager.install(face, replaceExisting = false) {}
            fail("deveria pedir para remover a versão antiga")
        } catch (e: FaceAlreadyInstalledException) {
            assertEquals("300", e.face.id)
        }
        assertEquals(null, band.uploadOk)

        // Com substituição: ativa outra face, remove a antiga e instala a nova.
        manager.install(face, replaceExisting = true) {}
        assertEquals(true, band.uploadOk)
        val now = band.faces.single { it.id == "300" }
        assertEquals("Nova face", now.name)
        assertTrue(now.active)
        client.disconnect()
    }

    @Test
    fun refusesWhenBandIsFull() = runBlocking {
        val band = FaceBand(key)
        for (i in 0 until 8) band.faces += FaceBand.Face("${400 + i}", "Face $i", active = false, canDelete = true)
        val (client, manager) = connected(band)
        try {
            manager.install(face, replaceExisting = false) {}
            fail("deveria recusar com a banda cheia")
        } catch (e: BandFullException) {
            assertTrue(e.message!!.contains("10"))
        }
        client.disconnect()
    }

    @Test
    fun reportsUploadRefusal() = runBlocking {
        val band = FaceBand(key).apply { uploadErrno = 3 }
        val (client, manager) = connected(band)
        try {
            manager.install(face, replaceExisting = false) {}
            fail("deveria falhar")
        } catch (e: InstallException) {
            assertTrue(e.message!!.contains("errno 3"))
        }
        client.disconnect()
    }

    @Test
    fun cancelStopsBetweenPartsAndDisconnects() = runBlocking {
        val band = FaceBand(key).apply { chunkSize = 512 }
        val (client, manager) = connected(band)
        val reachedPart = CompletableDeferred<Unit>()
        band.onPart = { index, _ ->
            if (index == 3) {
                band.transport.silent = true // a banda confirma a parte 3 e para de responder
                reachedPart.complete(Unit)
            }
        }
        val job = launch { manager.install(face, replaceExisting = false) {} }
        withTimeout(5_000) { reachedPart.await() }
        job.cancel()
        job.join()
        // Nenhuma parte passou da 3, a face não foi ativada e a conexão foi fechada.
        assertEquals(3, band.partSizes.size)
        assertEquals(null, band.uploadOk)
        assertTrue(band.faces.none { it.id == "300" })
        withTimeout(2_000) { while (!band.transport.closed) delay(10) }
        assertEquals(BandState.Disconnected, client.state.value)
    }

    @Test
    fun resumesUploadAfterAutomaticReconnection() = runBlocking {
        val band = FaceBand(key).apply {
            chunkSize = 512
            resumable = true
        }
        val client = BandClient(
            PacketLog(), companion, config, requestTimeoutMs = 2_000, keepAliveIdleMs = 0,
            reconnectDelaysMs = listOf(50, 50, 50),
        )
        client.connect({ band.newTransport() }, key, autoReconnect = true)
        val manager = WatchfaceManager(client, PacketLog(), settleDelayMs = 50, shortTimeoutMs = 1_000, reconnectWaitMs = 10_000)
        var dropped = false
        band.onPart = { index, total ->
            assertTrue("arquivo pequeno demais para o teste", total > 4)
            if (index == 3 && !dropped) {
                dropped = true
                band.transport.silent = true
                band.transport.dropConnection()
            }
        }
        val stages = mutableListOf<String>()
        manager.install(face, replaceExisting = false) { stages += it.stage }
        assertTrue(dropped)
        assertEquals(2, band.connections)
        // A banda pediu para continuar do byte em que parou, e o arquivo chegou inteiro e conferido.
        assertEquals(3 * (512 - 4) - 22, band.lastResumePosition)
        assertEquals(true, band.uploadOk)
        assertTrue(band.faces.any { it.id == "300" && it.active })
        assertTrue(stages.any { it.contains("Reconectando") })
        client.disconnect()
    }

    @Test
    fun connectionLossDuringUploadIsReported() = runBlocking {
        val band = FaceBand(key).apply { chunkSize = 512 }
        val (client, manager) = connected(band)
        band.onPart = { index, _ ->
            if (index == 2) {
                band.transport.silent = true
                band.transport.dropConnection()
            }
        }
        try {
            manager.install(face, replaceExisting = false) {}
            fail("deveria falhar com a queda")
        } catch (e: Exception) {
            assertTrue(e.message, e.message!!.contains("caiu") || e.message!!.contains("encerrada") || e.message!!.contains("conectada"))
        }
        assertEquals(null, band.uploadOk)
        assertTrue(client.state.value !is BandState.Ready)
    }
}
