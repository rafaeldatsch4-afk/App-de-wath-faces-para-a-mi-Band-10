package io.github.rafaeldatsch4afk.mibandfaces.protocol

import io.github.rafaeldatsch4afk.mibandfaces.bluetooth.BandTransport
import io.github.rafaeldatsch4afk.mibandfaces.log.PacketLog
import io.github.rafaeldatsch4afk.mibandfaces.testutil.FakeBandTransport
import io.github.rafaeldatsch4afk.mibandfaces.util.hexToBytes
import io.github.rafaeldatsch4afk.mibandfaces.util.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class XiaomiLinkTest {

    private val fastConfig = LinkConfig(
        maxWindow = 1,
        resendTimeoutMs = 200,
        versionProbeTimeoutMs = 500,
        sessionTimeoutMs = 500,
    )

    private class Collector : BandLink.Listener {
        val packets = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
        val closed = CompletableDeferred<Throwable?>()
        override fun onPacket(channel: Int, body: ByteArray) {
            packets += channel to body
        }

        override fun onClosed(cause: Throwable?) {
            closed.complete(cause)
        }
    }

    @Test
    fun opensSessionAfterVersionProbe() = runBlocking {
        val band = FakeBandTransport(bandWindow = 3)
        val link = XiaomiLink(band, PacketLog(), fastConfig.copy(maxWindow = 8))
        link.open(Collector())
        assertEquals(32768, link.bandSession!!.maxPacketSize)
        // A menor janela vale: a banda anunciou 3.
        assertEquals(3, link.effectiveWindow)
        link.close()
    }

    @Test
    fun bleSkipsVersionProbe() = runBlocking {
        val band = FakeBandTransport(needsVersionProbe = false)
        val link = XiaomiLink(band, PacketLog(), fastConfig)
        link.open(Collector())
        assertEquals(1, link.effectiveWindow)
        link.close()
    }

    @Test
    fun staysInSppV1WhenBandAnswersVersion1() = runBlocking {
        val band = FakeBandTransport(versionPayload = "01".hexToBytes())
        val link = XiaomiLink(band, PacketLog(), fastConfig)
        link.open(Collector())
        assertTrue(link.isV1)
        assertTrue(link.description.contains("SPP V1"))
        // Sem configuração de sessão V2 nem janela.
        assertEquals(1, link.effectiveWindow)
        link.close()
    }

    @Test
    fun silentVersionProbeMeansSppV1() = runBlocking {
        // XiaomiSppSupport: sem resposta à sondagem, a banda fala V1.
        val band = FakeBandTransport(versionPayload = null)
        val link = XiaomiLink(band, PacketLog(), fastConfig)
        link.open(Collector())
        assertTrue(link.isV1)
        link.close()
    }

    @Test
    fun sppV1FramesFollowGadgetbridgeLayout() = runBlocking {
        val band = FakeBandTransport(versionPayload = "01".hexToBytes())
        val sent = mutableListOf<ByteArray>()
        val link = XiaomiLink(RecordingTransport(band, sent), PacketLog(), fastConfig)
        link.open(Collector())
        // Comando de autenticação: texto puro, dataType 2, canal 2, serial 0.
        link.send(SppV2.CHANNEL_PROTOBUF, byteArrayOf(0x08, 0x01), encrypt = false)
        assertEquals("badcfe0280050002000208 01ef".replace(" ", ""), sent.last().toHex())
        link.close()
    }

    @Test
    fun sendWaitsForAckAndKeepsOrder() = runBlocking {
        val band = FakeBandTransport()
        val received = CopyOnWriteArrayList<ByteArray>()
        band.onData = { _, _, body -> received += body }
        val link = XiaomiLink(band, PacketLog(), fastConfig)
        link.open(Collector())
        for (i in 0 until 20) link.send(SppV2.CHANNEL_DATA, byteArrayOf(i.toByte()), encrypt = false)
        // A banda simulada entrega o pacote à camada de cima logo depois de mandar o ACK.
        band.awaitIdle()
        assertEquals((0 until 20).toList(), band.acceptedSeqs.toList())
        assertEquals((0 until 20).toList(), received.map { it[0].toInt() })
        link.close()
    }

    @Test
    fun resendsAfterLostFrame() = runBlocking {
        val band = FakeBandTransport()
        val link = XiaomiLink(band, PacketLog(), fastConfig)
        link.open(Collector())
        band.loseFrames = 1
        withTimeout(3_000) { link.send(SppV2.CHANNEL_DATA, byteArrayOf(1), encrypt = false) }
        assertEquals(listOf(0), band.acceptedSeqs.toList())
        link.close()
    }

    @Test
    fun resendsAfterNack() = runBlocking {
        val band = FakeBandTransport()
        val link = XiaomiLink(band, PacketLog(), fastConfig)
        link.open(Collector())
        band.nackFrames = 2
        withTimeout(3_000) { link.send(SppV2.CHANNEL_DATA, byteArrayOf(1), encrypt = false) }
        assertEquals(listOf(0, 0), band.nacksSent.toList())
        assertEquals(listOf(0), band.acceptedSeqs.toList())
        link.close()
    }

    @Test
    fun windowedSendRecoversWithGoBackN() = runBlocking {
        val band = FakeBandTransport(bandWindow = 4)
        val link = XiaomiLink(band, PacketLog(), fastConfig.copy(maxWindow = 4))
        link.open(Collector())
        band.loseFrames = 1 // perde o quadro 0; a banda dá NACK nos seguintes
        val acks = (0 until 10).map { link.enqueue(SppV2.CHANNEL_DATA, byteArrayOf(it.toByte()), encrypt = false) }
        withTimeout(5_000) { acks.forEach { it.await() } }
        assertEquals((0 until 10).toList(), band.acceptedSeqs.toList())
        link.close()
    }

    @Test
    fun failsAfterRepeatedTimeouts() = runBlocking {
        val band = FakeBandTransport()
        val collector = Collector()
        val link = XiaomiLink(band, PacketLog(), fastConfig)
        link.open(collector)
        band.silent = true
        try {
            withTimeout(5_000) { link.send(SppV2.CHANNEL_DATA, byteArrayOf(1), encrypt = false) }
            fail("deveria falhar sem ACK")
        } catch (e: AckTimeoutException) {
            assertTrue(e.message!!.contains("ACK"))
        }
        assertTrue(withTimeout(2_000) { collector.closed.await() } is AckTimeoutException)
        assertTrue(band.closed)
    }

    @Test
    fun deliversAndAcksBandPackets() = runBlocking {
        val band = FakeBandTransport()
        val collector = Collector()
        val link = XiaomiLink(band, PacketLog(), fastConfig)
        link.open(collector)
        band.sendToHost(SppV2.CHANNEL_PROTOBUF, SppV2.OPCODE_PLAIN, byteArrayOf(1, 2, 3))
        band.sendToHost(SppV2.CHANNEL_PROTOBUF, SppV2.OPCODE_PLAIN, byteArrayOf(4))
        withTimeout(2_000) { while (collector.packets.size < 2 || band.acksFromHost.size < 2) kotlinx.coroutines.delay(10) }
        assertArrayEquals(byteArrayOf(1, 2, 3), collector.packets[0].second)
        assertEquals(listOf(0, 1), band.acksFromHost.toList())
        link.close()
    }

    @Test
    fun pendingSendFailsWhenConnectionDrops() = runBlocking {
        val band = FakeBandTransport()
        val collector = Collector()
        val link = XiaomiLink(band, PacketLog(), fastConfig.copy(resendTimeoutMs = 5_000))
        link.open(collector)
        band.silent = true
        val pending = link.enqueue(SppV2.CHANNEL_DATA, byteArrayOf(1), encrypt = false)
        band.dropConnection()
        try {
            withTimeout(2_000) { pending.await() }
            fail("deveria falhar com a desconexão")
        } catch (e: LinkClosedException) {
            assertTrue(e.message!!.contains("caiu"))
        }
    }

    /** Repassa tudo para [inner] e guarda o que o celular escreveu. */
    private class RecordingTransport(private val inner: BandTransport, private val sent: MutableList<ByteArray>) : BandTransport by inner {
        override suspend fun write(bytes: ByteArray) {
            sent += bytes.copyOf()
            inner.write(bytes)
        }
    }
}
