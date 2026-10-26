package io.github.rafaeldatsch4afk.mibandfaces.protocol

import io.github.rafaeldatsch4afk.mibandfaces.util.hexToBytes
import io.github.rafaeldatsch4afk.mibandfaces.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FramingTest {

    /** Cópia literal do algoritmo do Gadgetbridge (XiaomiSppPacketV2.calculatePayloadChecksum). */
    private fun gadgetbridgeCrc(payload: ByteArray): Int {
        var crc = 0
        for (b in payload) {
            for (j in 0 until 8) {
                crc = crc shl 1
                if ((((crc shr 16) and 1) xor ((b.toInt() shr j) and 1)) == 1) crc = crc xor 0x8005
            }
        }
        return Integer.reverse(crc) ushr 16
    }

    @Test
    fun crc16ArcCheckValue() {
        assertEquals(0xBB3D, Crc16Arc.compute("123456789".toByteArray()))
    }

    @Test
    fun crc16MatchesGadgetbridge() {
        val random = Random(42)
        repeat(200) {
            val data = random.nextBytes(random.nextInt(0, 300))
            assertEquals(gadgetbridgeCrc(data), Crc16Arc.compute(data))
        }
    }

    @Test
    fun v1VersionRequestMatchesReference() {
        // Valor documentado no miband-hack (docs/protocol.md) e gerado pelo Gadgetbridge.
        assertEquals("badcfe00c00300000000ef", SppV1.versionRequest().toHex())
    }

    @Test
    fun v1ParsesVersionReplyWithGarbageAndFragments() {
        val reply = "badcfe00000600000000030041ef".hexToBytes()
        assertEquals(SppV1.ParseResult.Incomplete, SppV1.parse(reply.copyOf(8)))
        val withGarbage = byteArrayOf(0x01, 0x02) + reply
        val first = SppV1.parse(withGarbage)
        assertTrue(first is SppV1.ParseResult.Invalid)
        val complete = SppV1.parse(reply) as SppV1.ParseResult.Complete
        assertEquals(0, complete.packet.channel)
        assertEquals("030041", complete.packet.payload.toHex())
        assertEquals(reply.size, complete.size)
    }

    @Test
    fun v2RoundTripFragmentedAndConcatenated() {
        val a = SppV2.encode(SppV2.TYPE_DATA, 7, SppV2.dataPayload(1, 1, "hello".toByteArray()))
        val b = SppV2.ack(9)
        val stream = byteArrayOf(0x00, 0x13) + a + b
        val parser = SppV2.Parser()
        val frames = mutableListOf<SppV2.Frame>()
        // Entrega byte a byte, como um RFCOMM bem fragmentado.
        for (byte in stream) frames += parser.feed(byteArrayOf(byte))
        assertEquals(2, frames.size)
        assertEquals(SppV2.TYPE_DATA, frames[0].type)
        assertEquals(7, frames[0].seq)
        assertArrayEquals(SppV2.dataPayload(1, 1, "hello".toByteArray()), frames[0].payload)
        assertEquals(SppV2.TYPE_ACK, frames[1].type)
        assertEquals(9, frames[1].seq)
    }

    @Test
    fun v2DropsFrameWithBadCrc() {
        val bad = SppV2.encode(SppV2.TYPE_DATA, 1, byteArrayOf(1, 2, 3)).also { it[it.size - 1] = 0x7f }
        val good = SppV2.ack(2)
        val errors = mutableListOf<String>()
        val frames = SppV2.Parser { errors += it }.feed(bad + good)
        assertEquals(1, errors.size)
        assertEquals(1, frames.size)
        assertEquals(SppV2.TYPE_ACK, frames[0].type)
    }

    @Test
    fun v2SessionConfigRequestAndParse() {
        val request = SppV2.sessionStartRequest()
        val frame = SppV2.Parser().feed(request).single()
        assertEquals(SppV2.TYPE_SESSION_CONFIG, frame.type)
        val config = SppV2.parseSessionConfig(frame.payload)!!
        assertEquals(SppV2.SESSION_START_REQUEST, config.opcode)
        assertEquals("010000", config.version!!.toHex())
        assertEquals(0xfc00, config.maxPacketSize)
        assertEquals(32, config.txWindow)
        assertEquals(10_000, config.sendTimeoutMs)
    }
}
