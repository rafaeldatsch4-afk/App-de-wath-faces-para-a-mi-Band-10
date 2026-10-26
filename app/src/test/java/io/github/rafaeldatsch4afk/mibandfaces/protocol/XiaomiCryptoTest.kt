package io.github.rafaeldatsch4afk.mibandfaces.protocol

import io.github.rafaeldatsch4afk.mibandfaces.key.AuthKey
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class XiaomiCryptoTest {

    private fun bouncyCcm(key: ByteArray, nonce: ByteArray, plain: ByteArray, macBits: Int = 32): ByteArray {
        @Suppress("DEPRECATION")
        val cipher = CCMBlockCipher(AESEngine())
        cipher.init(true, AEADParameters(KeyParameter(key), macBits, nonce, null))
        val out = ByteArray(cipher.getOutputSize(plain.size))
        val n = cipher.processBytes(plain, 0, plain.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }

    /** Port literal de XiaomiAuthService.computeAuthStep3Hmac (Gadgetbridge). */
    private fun gadgetbridgeKdf(secretKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray): ByteArray {
        val miwear = "miwear-auth".toByteArray()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(phoneNonce + watchNonce, "HmacSHA256"))
        val hk = mac.doFinal(secretKey)
        mac.init(SecretKeySpec(hk, "HmacSHA256"))
        val output = ByteArray(64)
        var tmp = ByteArray(0)
        var b: Byte = 1
        var i = 0
        while (i < output.size) {
            mac.update(tmp)
            mac.update(miwear)
            mac.update(b)
            tmp = mac.doFinal()
            var j = 0
            while (j < tmp.size && i < output.size) {
                output[i++] = tmp[j++]
            }
            b++
        }
        return output
    }

    @Test
    fun ccmMatchesBouncyCastle() {
        val random = Random(7)
        for (size in listOf(0, 1, 15, 16, 17, 31, 32, 33, 100, 255)) {
            val key = random.nextBytes(16)
            val nonce = random.nextBytes(12)
            val plain = random.nextBytes(size)
            assertArrayEquals("tamanho $size", bouncyCcm(key, nonce, plain), XiaomiCrypto.aesCcmEncrypt(key, nonce, plain))
        }
    }

    @Test
    fun ccmMatchesBouncyCastleWithLongerTag() {
        val random = Random(8)
        val key = random.nextBytes(16)
        val nonce = random.nextBytes(12)
        val plain = random.nextBytes(40)
        assertArrayEquals(bouncyCcm(key, nonce, plain, 128), XiaomiCrypto.aesCcmEncrypt(key, nonce, plain, 16))
    }

    @Test
    fun kdfMatchesGadgetbridge() {
        val random = Random(9)
        repeat(20) {
            val key = random.nextBytes(16)
            val phone = random.nextBytes(16)
            val watch = random.nextBytes(16)
            val expected = gadgetbridgeKdf(key, phone, watch)
            val keys = XiaomiCrypto.deriveSessionKeys(key, phone, watch)
            assertArrayEquals(expected.copyOfRange(0, 16), keys.decryptionKey)
            assertArrayEquals(expected.copyOfRange(16, 32), keys.encryptionKey)
            assertArrayEquals(expected.copyOfRange(32, 36), keys.decryptionNonce)
            assertArrayEquals(expected.copyOfRange(36, 40), keys.encryptionNonce)
        }
    }

    @Test
    fun ccmNonceLayoutMatchesGadgetbridge() {
        // ByteBuffer LE: nonce4 | putInt(0) | putInt(contador)
        val nonce = XiaomiCrypto.ccmPacketNonce(byteArrayOf(1, 2, 3, 4), 0x01020304)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 0, 0, 0, 0, 4, 3, 2, 1), nonce)
    }

    @Test
    fun ctrRoundTripRestartsCounterEachPacket() {
        val keys = XiaomiCrypto.deriveSessionKeys(Random(1).nextBytes(16), Random(2).nextBytes(16), Random(3).nextBytes(16))
        val cipher = SppV2Cipher(keys)
        val plain = "mesmo texto".toByteArray()
        val a = cipher.encrypt(plain)
        val b = cipher.encrypt(plain)
        // IV fixo = chave: o mesmo texto vira o mesmo cifrado (comportamento do protocolo).
        assertArrayEquals(a, b)
        // O lado da banda decifra com a chave de cifra do celular.
        assertArrayEquals(plain, XiaomiCrypto.aesCtr(keys.encryptionKey, keys.encryptionKey, a))
    }

    @Test
    fun authKeyNormalization() {
        val key = "0123456789abcdef0123456789abcdef"
        assertEquals(key, AuthKey.normalize("0x0123456789ABCDEF0123456789abcdef").getOrThrow())
        assertEquals(key, AuthKey.normalize("  0123 4567 89ab cdef 0123 4567 89ab cdef\n").getOrThrow())
        assertTrue(AuthKey.normalize("0123").isFailure)
        assertTrue(AuthKey.normalize("g123456789abcdef0123456789abcdef").isFailure)
        assertTrue(AuthKey.normalize("").isFailure)
        assertEquals("0123…cdef", AuthKey.mask(key))
    }
}
