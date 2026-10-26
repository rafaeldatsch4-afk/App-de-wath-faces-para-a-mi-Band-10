package io.github.rafaeldatsch4afk.mibandfaces.protocol

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Chaves da sessão, derivadas da auth key e dos dois nonces (XiaomiAuthService.handleWatchNonce). */
class SessionKeys(
    /** Para decifrar o que vem da banda. */
    val decryptionKey: ByteArray,
    /** Para cifrar o que vai para a banda. */
    val encryptionKey: ByteArray,
    val decryptionNonce: ByteArray,
    val encryptionNonce: ByteArray,
)

/**
 * Criptografia do protocolo Xiaomi, portada de XiaomiAuthService (Gadgetbridge).
 *
 * O Android não oferece AES-CCM no javax.crypto (o Gadgetbridge usa BouncyCastle). Como só
 * precisamos cifrar um pacote na autenticação, o CCM foi implementado aqui sobre AES/ECB,
 * seguindo a RFC 3610. Os testes conferem o resultado com o BouncyCastle.
 */
object XiaomiCrypto {
    private val MIWEAR_AUTH = "miwear-auth".toByteArray(Charsets.US_ASCII)

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /**
     * `hk = HMAC(nonceCelular ‖ nonceBanda, authKey)`, depois `T(i) = HMAC(hk, T(i-1) ‖ "miwear-auth" ‖ i)`
     * até 64 bytes (computeAuthStep3Hmac no Gadgetbridge).
     */
    fun deriveSessionKeys(authKey: ByteArray, phoneNonce: ByteArray, bandNonce: ByteArray): SessionKeys {
        val hk = hmacSha256(phoneNonce + bandNonce, authKey)
        val output = ByteArray(64)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < output.size) {
            previous = hmacSha256(hk, previous, MIWEAR_AUTH, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, output.size - filled)
            previous.copyInto(output, filled, 0, n)
            filled += n
            counter++
        }
        return SessionKeys(
            decryptionKey = output.copyOfRange(0, 16),
            encryptionKey = output.copyOfRange(16, 32),
            decryptionNonce = output.copyOfRange(32, 36),
            encryptionNonce = output.copyOfRange(36, 40),
        )
    }

    /** AES-128-CTR com o IV dado (no SPP V2, IV = a própria chave). */
    fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /**
     * AES-CCM (RFC 3610) sem dados associados. Retorna `textoCifrado ‖ tag`, no mesmo formato do
     * CCMBlockCipher do BouncyCastle usado pelo Gadgetbridge.
     */
    fun aesCcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, tagLength: Int = 4): ByteArray {
        require(nonce.size in 7..13) { "nonce CCM deve ter de 7 a 13 bytes" }
        require(tagLength in 4..16 && tagLength % 2 == 0) { "tag CCM inválida" }
        val l = 15 - nonce.size // bytes do campo de tamanho
        require(l >= 4 || plaintext.size < (1 shl (8 * l))) { "mensagem grande demais para o nonce" }

        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))

        // CBC-MAC sobre B0 e a mensagem com preenchimento de zeros.
        val b0 = ByteArray(16)
        b0[0] = ((((tagLength - 2) / 2) shl 3) or (l - 1)).toByte()
        nonce.copyInto(b0, 1)
        var length = plaintext.size
        for (i in 15 downTo 16 - l) {
            b0[i] = length.toByte()
            length = length ushr 8
        }
        var x = aes.doFinal(b0)
        var offset = 0
        while (offset < plaintext.size) {
            val block = ByteArray(16)
            plaintext.copyInto(block, 0, offset, minOf(plaintext.size, offset + 16))
            for (i in 0 until 16) block[i] = (block[i].toInt() xor x[i].toInt()).toByte()
            x = aes.doFinal(block)
            offset += 16
        }

        // Modo contador: A0 cifra a tag, A1.. cifram a mensagem.
        fun counterBlock(i: Int): ByteArray {
            val a = ByteArray(16)
            a[0] = (l - 1).toByte()
            nonce.copyInto(a, 1)
            var c = i
            for (j in 15 downTo 16 - l) {
                a[j] = c.toByte()
                c = c ushr 8
            }
            return aes.doFinal(a)
        }

        val out = ByteArray(plaintext.size + tagLength)
        var block = 1
        offset = 0
        while (offset < plaintext.size) {
            val s = counterBlock(block++)
            for (i in 0 until minOf(16, plaintext.size - offset)) {
                out[offset + i] = (plaintext[offset + i].toInt() xor s[i].toInt()).toByte()
            }
            offset += 16
        }
        val s0 = counterBlock(0)
        for (i in 0 until tagLength) out[plaintext.size + i] = (x[i].toInt() xor s0[i].toInt()).toByte()
        return out
    }

    /** Nonce CCM de 12 bytes: `nonce4 ‖ 00000000 ‖ contador (u32 LE)` (XiaomiAuthService.encrypt). */
    fun ccmPacketNonce(nonce4: ByteArray, counter: Int): ByteArray =
        nonce4 + byteArrayOf(0, 0, 0, 0) + byteArrayOf(counter.toByte(), (counter ushr 8).toByte(), (counter ushr 16).toByte(), (counter ushr 24).toByte())
}

/** Cifra do SPP V2 depois da autenticação: AES-CTR com IV = chave (encryptV2/decryptV2). */
class SppV2Cipher(private val keys: SessionKeys) : PayloadCipher {
    override fun encrypt(plain: ByteArray): ByteArray =
        XiaomiCrypto.aesCtr(keys.encryptionKey, keys.encryptionKey, plain)

    override fun decrypt(cipher: ByteArray): ByteArray =
        XiaomiCrypto.aesCtr(keys.decryptionKey, keys.decryptionKey, cipher)
}
