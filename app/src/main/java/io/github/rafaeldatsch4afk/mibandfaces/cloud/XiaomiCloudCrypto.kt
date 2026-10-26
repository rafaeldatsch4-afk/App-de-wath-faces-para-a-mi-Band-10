package io.github.rafaeldatsch4afk.mibandfaces.cloud

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Assinatura e criptografia das requisições à nuvem da Xiaomi (RC4 de "ENCRYPT-RC4").
 * Portado de `worker/xiaomi-cloud.ts` do miband-hack (AGPL-3.0), que por sua vez segue o
 * Xiaomi-cloud-tokens-extractor (MIT), o ha_miband (Apache-2.0) e o huami-token (MIT).
 * Os testes usam os vetores que o miband-hack gerou com as implementações em Python.
 */
object XiaomiCloudCrypto {
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text.trim())
    private fun sha1(data: ByteArray) = MessageDigest.getInstance("SHA-1").digest(data)
    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)

    /** RC4 descartando os primeiros 1024 bytes do fluxo de chave. */
    fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
            s[i] = s[j].also { s[j] = s[i] }
        }
        var i = 0
        j = 0
        val out = ByteArray(data.size)
        for (n in -1024 until data.size) {
            i = (i + 1) and 0xff
            j = (j + s[i]) and 0xff
            s[i] = s[j].also { s[j] = s[i] }
            if (n >= 0) out[n] = (data[n].toInt() xor s[(s[i] + s[j]) and 0xff]).toByte()
        }
        return out
    }

    /** `base64(SHA-256(ssecurity ‖ nonce))`, com os dois valores decodificados de base64. */
    fun signedNonce(ssecurity: String, nonce: String): String = b64(sha256(unb64(ssecurity) + unb64(nonce)))

    /** 8 bytes aleatórios + o minuto atual (u32 big-endian), em base64. */
    fun makeNonce(nowMillis: Long = System.currentTimeMillis(), random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(12)
        val head = ByteArray(8).also { random.nextBytes(it) }
        head.copyInto(bytes)
        val minutes = nowMillis / 60_000
        for (i in 0 until 4) bytes[8 + i] = (minutes ushr (8 * (3 - i))).toByte()
        return b64(bytes)
    }

    private fun encSignature(path: String, signed: String, params: List<Pair<String, String>>): String {
        val parts = listOf("POST", path) + params.map { (k, v) -> "$k=$v" } + signed
        return b64(sha1(parts.joinToString("&").toByteArray(Charsets.UTF_8)))
    }

    /** `generate_enc_params` do Xiaomi-cloud-tokens-extractor. [path] sem o prefixo /app. */
    fun buildEncryptedParams(path: String, data: String, ssecurity: String, nonce: String): List<Pair<String, String>> {
        val signed = signedNonce(ssecurity, nonce)
        val key = unb64(signed)
        val params = mutableListOf("data" to data)
        params += "rc4_hash__" to encSignature(path, signed, params)
        val encrypted = params.map { (k, v) -> k to b64(rc4(key, v.toByteArray(Charsets.UTF_8))) }.toMutableList()
        encrypted += "signature" to encSignature(path, signed, encrypted)
        encrypted += "ssecurity" to ssecurity
        encrypted += "_nonce" to nonce
        return encrypted
    }

    /** Variante do huami-token: um único fluxo RC4 sobre os parâmetros ordenados. */
    fun buildStreamParams(path: String, plain: Map<String, String>, ssecurity: String, nonce: String): List<Pair<String, String>> {
        val signed = signedNonce(ssecurity, nonce)
        val withHash = plain + ("rc4_hash__" to encSignature(path, signed, plain.toSortedMap().toList()))
        val keys = withHash.keys.sorted()
        val stream = rc4(unb64(signed), keys.joinToString("") { withHash.getValue(it) }.toByteArray(Charsets.UTF_8))
        var at = 0
        val out = keys.map { k ->
            val len = withHash.getValue(k).toByteArray(Charsets.UTF_8).size
            (k to b64(stream.copyOfRange(at, at + len))).also { at += len }
        }.toMutableList()
        out += "signature" to encSignature(path, signed, out)
        out += "_nonce" to nonce
        return out
    }

    fun decryptResponse(ssecurity: String, nonce: String, body: String): String {
        val key = unb64(signedNonce(ssecurity, nonce))
        return String(rc4(key, unb64(body)), Charsets.UTF_8)
    }

    /** `base64(SHA-1("nonce=<nonce>&<ssecurity>"))`, anexado à URL do STS. */
    fun clientSign(nonce: String, ssecurity: String): String =
        b64(sha1("nonce=$nonce&$ssecurity".toByteArray(Charsets.UTF_8)))
}
