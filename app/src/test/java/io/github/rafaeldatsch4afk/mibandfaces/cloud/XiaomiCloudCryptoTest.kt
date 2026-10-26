package io.github.rafaeldatsch4afk.mibandfaces.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Vetores gerados pelas ferramentas em Python (token_extractor.py e huami-token) e publicados nos
 * testes do miband-hack (test/xiaomi-cloud.test.ts). Valores sintéticos, sem dados de conta real.
 */
class XiaomiCloudCryptoTest {
    private val ssecurity = "c3NlY3VyaXR5LXRlc3QtdmFsdWU="
    private val nonce = "AAECAwQFBgcAAQID"

    @Test
    fun matchesTokenExtractorGenerateEncParams() {
        val params = XiaomiCloudCrypto.buildEncryptedParams("/v2/homeroom/gethome", """{"fg": true, "fetch_share": true}""", ssecurity, nonce)
        assertEquals(
            listOf(
                "data" to "/xDmSesBHg85H7usewGg+cR2WLdqFBk4Wolx039dtHeo",
                "rc4_hash__" to "9QXQdL51CUN9DI+xM1SEt/dQabJ6DCg4ZsVzzg==",
                "signature" to "JZ0emAb8U3Z+6jp0eXK9u60Hz+8=",
                "ssecurity" to ssecurity,
                "_nonce" to nonce,
            ),
            params,
        )
    }

    @Test
    fun decryptsRc4Response() {
        assertEquals(
            """{"code":0,"result":{"ok":1}}""",
            XiaomiCloudCrypto.decryptResponse(ssecurity, nonce, "/xDjQa1eHEF7RvzyPlCz8MQ3CpM7ExNoBZo2jg=="),
        )
    }

    @Test
    fun matchesHuamiTokenStreamParams() {
        val p = XiaomiCloudCrypto.buildStreamParams("/app/v1/source/get_source_list", mapOf("data" to """{"page_size":50,"status":1}"""), ssecurity, nonce).toMap()
        assertEquals(
            mapOf(
                "data" to "/xDwT65eYQgiELuiYRb2sJJmRIltCQtoBZo2",
                "rc4_hash__" to "wD5uslCzL6uwNG10EVHFyEgnbuF19XFg6sZfww==",
                "signature" to "gkpR/qHCiz+by9zd7GGaBHJtPeA=",
                "_nonce" to nonce,
            ),
            p,
        )
    }

    @Test
    fun decryptsResponseEncryptedByHuamiToken() {
        val body = "/xDjQa1eHEF7RvztPlC1/ddwEtI7ExNoE4k5lnharWb3UpHCNHFpJkOp5l59eNJEgCAqud0+3L3YFQFhagIt7JhNedsegQS/0dtJ7I7JVOonMMbayXiTjsj6jOOmTHY0x2tw76wJbZCOnqaSevVNaAaIylVLqIN6JyUJ8/gAccIKMSNISgSHmURKwHs5PRtU4V2cE7jN1+Pn1AJeymlERnT4oqhqQ6kCNGPVGDVfD9Cff4cDJRYXBrIhiJTJL4QSv0a7JCEvicpMZ+oF0H3jSo89zoK+WbRhOGliFceXb08MwGD1ceL7b44ALqxNTHEYab5kst1guArz9O9ESMe8Vv352Hb5Wm89MjK3HI6IZNaj9pF4eK6nygE7thx8Y1do9iqoTYSSqUmfNq+vmRlBuMDgKWh4pIjSDPE8t36OcS83S9j78w=="
        val parsed = JSONObject(XiaomiCloudCrypto.decryptResponse(ssecurity, nonce, body))
        val first = parsed.getJSONObject("result").getJSONArray("list").getJSONObject(0)
        assertEquals("00112233445566778899aabbccddeeff", first.getJSONObject("detail").getString("encrypt_key"))
    }

    @Test
    fun computesClientSignLikeHuamiToken() {
        assertEquals("FQOy1e1ntVXrQpv0QbLHtldZT2U=", XiaomiCloudCrypto.clientSign("123", ssecurity))
    }

    @Test
    fun nonceHasEightRandomBytesAndTheMinute() {
        val n = java.util.Base64.getDecoder().decode(XiaomiCloudCrypto.makeNonce(nowMillis = 60_000L * 0x01020304))
        assertEquals(12, n.size)
        assertEquals(listOf<Byte>(1, 2, 3, 4), n.drop(8))
    }
}
