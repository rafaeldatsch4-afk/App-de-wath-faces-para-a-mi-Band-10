package io.github.rafaeldatsch4afk.mibandfaces.cloud

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Base64

/** Xiaomi simulada: cada teste decide o que responder a cada requisição. */
private class FakeCloudHttp(val handler: (method: String, url: String, headers: Map<String, String>, body: String?) -> HttpResponse) : CloudHttp {
    class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?, val follow: Boolean, val readTimeoutMs: Int = 0)

    val calls = mutableListOf<Call>()

    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?, followRedirects: Boolean, readTimeoutMs: Int): HttpResponse {
        calls += Call(method, url, headers, body, followRedirects, readTimeoutMs)
        return handler(method, url, headers, body)
    }

    override suspend fun bytes(url: String): ByteArray = byteArrayOf(1, 2, 3)
}

private fun ok(body: String, cookies: List<String> = emptyList(), status: Int = 200) = HttpResponse(status, body, cookies, null)

class XiaomiCloudTest {
    private val ssecurity = "c3NlY3VyaXR5LXRlc3QtdmFsdWU="
    private val keyA = "00112233445566778899aabbccddeeff"

    private val sourceList = """
        {"code":0,"message":"ok","result":{"list":[
          {"identifier":"123456","name":"Xiaomi Smart Band 10 ","model":"miwear.watch.o66gl","status":1,
           "detail":{"mac":"AA:BB:CC:DD:EE:FF","encrypt_key":"$keyA","token":"00112233445566778899AABBCCDDEEFF","beaconkey":"ffeeddccbbaa99887766554433221100"}},
          {"name":"Band 7","model":"m7","identifier":"7","detail":"{\"mac\":\"11:22:33:44:55:66\",\"auth_key\":\"0xa3c10e34e5c14637eea6b9efc0610600\"}"},
          {"name":"Watch","model":"w","identifier":"8","detail":{"encrypt_key":"curta","token":"abcdefabcdefabcdefabcdefabcdefab"}},
          {"name":"Balança","model":"s","identifier":"9","detail":{"mac":"0A:0B:0C:0D:0E:0F"}}
        ]}}
    """.trimIndent()

    private fun encrypt(nonce: String, body: String): String {
        val key = Base64.getDecoder().decode(XiaomiCloudCrypto.signedNonce(ssecurity, nonce))
        return Base64.getEncoder().encodeToString(XiaomiCloudCrypto.rc4(key, body.toByteArray()))
    }

    private fun queryParam(url: String, name: String): String? =
        url.substringAfter('?', "").split('&').map { it.split('=', limit = 2) }.firstOrNull { it[0] == name }?.getOrNull(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    private fun session() = XiaomiCloud.Session("1", ssecurity, "token", "c")

    // ---------------------------------------------------------------- QR ---

    @Test
    fun startQrAsksForMiFitnessLogin() = runBlocking {
        val http = FakeCloudHttp { _, _, _, _ ->
            ok("""&&&START&&&{"qr":"https://account.xiaomi.com/pass/qr/login?x","loginUrl":"https://sgp.account.xiaomi.com/longPolling/login?ticket=lp_1","lp":"https://sgp.lp.account.xiaomi.com/lp/s?k=lp_1","timeout":300}""")
        }
        val start = XiaomiCloud(http).startQrLogin()
        val url = http.calls.single().url
        assertTrue(url.startsWith("https://account.xiaomi.com/longPolling/loginUrl?"))
        assertEquals("miothealth", queryParam(url, "sid"))
        assertEquals("https://sts-hlth.io.mi.com/healthapp/sts", queryParam(url, "callback"))
        assertEquals("https://sgp.lp.account.xiaomi.com/lp/s?k=lp_1", start.longPollUrl)
        assertEquals(300, start.timeoutSeconds)
    }

    @Test
    fun startQrRejectsForeignLongPollAddress() = runBlocking {
        val http = FakeCloudHttp { _, _, _, _ ->
            ok("""&&&START&&&{"qr":"https://account.xiaomi.com/q","loginUrl":"https://x","lp":"https://evil.example/lp/s?k=1","timeout":300}""")
        }
        try {
            XiaomiCloud(http).startQrLogin()
            fail("deveria recusar")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("inesperado"))
        }
    }

    private val qrStart = QrStart("https://account.xiaomi.com/q", "https://account.xiaomi.com/l", "https://sgp.lp.account.xiaomi.com/lp/s?k=1", 300)

    /** Relógio virtual: cada pausa avança o tempo, sem esperar de verdade. */
    private class VirtualTime {
        var t = 0L
        val pauses = mutableListOf<Long>()
        val now: () -> Long = { t }
        val pause: suspend (Long) -> Unit = { pauses += it; t += it }
    }

    private val loginBody =
        """&&&START&&&{"userId":1,"cUserId":"c","ssecurity":"$ssecurity","passToken":"p","location":"https://sts-hlth.io.mi.com/healthapp/sts?x=1","nonce":5}"""

    @Test
    fun awaitHoldsOneLongPollUntilTheQrExpires() = runBlocking {
        val http = FakeCloudHttp { _, _, _, _ -> ok(loginBody) }
        val login = XiaomiCloud(http).awaitQrLogin(qrStart)
        // Sem interromper de propósito: o limite de leitura vai até o fim da validade do QR.
        assertEquals(1, http.calls.size)
        assertEquals(300_000, http.calls.single().readTimeoutMs)
        assertEquals("1", login.userId)
        assertEquals("5", login.nonce)
        assertEquals("https://sts-hlth.io.mi.com/healthapp/sts?x=1", login.location)
        assertEquals("QrLogin(<redacted>)", login.toString())
    }

    @Test
    fun awaitPausesBeforeReconnectingAfterRefusalsDropsAndTimeouts() = runBlocking {
        val clock = VirtualTime()
        val answers = ArrayDeque<() -> HttpResponse>(
            listOf(
                { throw java.io.IOException("rede caiu") },
                { ok("<html>403</html>", status = 403) },
                { ok("<html>403</html>", status = 403) },
                { throw HttpTimeoutException("espera") },
                { ok(loginBody) },
            ),
        )
        val http = FakeCloudHttp { _, _, _, _ -> answers.removeFirst()() }
        XiaomiCloud(http).awaitQrLogin(qrStart, clock.now, clock.pause)
        assertEquals(5, http.calls.size)
        // Nunca religa na hora: sempre 4 s de pausa depois de uma queda, recusa ou fim de espera.
        assertEquals(List(4) { XiaomiCloud.RETRY_PAUSE_MS }, clock.pauses)
        // O limite de leitura diminui junto com a validade restante do QR.
        assertEquals(300_000, http.calls[0].readTimeoutMs)
        assertEquals(284_000, http.calls[4].readTimeoutMs)
    }

    @Test
    fun awaitGivesUpWhenXiaomiKeepsRefusing() = runBlocking {
        val clock = VirtualTime()
        val http = FakeCloudHttp { _, _, _, _ -> ok("403", status = 403) }
        try {
            XiaomiCloud(http).awaitQrLogin(qrStart, clock.now, clock.pause)
            fail()
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("403"))
        }
        assertEquals(10, http.calls.size)
    }

    @Test
    fun awaitReportsExpiredQr() = runBlocking {
        val clock = VirtualTime()
        val http = FakeCloudHttp { _, _, _, _ -> throw HttpTimeoutException("espera") }
        try {
            XiaomiCloud(http).awaitQrLogin(qrStart, clock.now, clock.pause)
            fail()
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("expirou"))
        }
        assertTrue(clock.t >= 300_000)
    }

    @Test
    fun awaitFailsWhenAnswerHasNoSession() = runBlocking {
        val http = FakeCloudHttp { _, _, _, _ -> ok("""&&&START&&&{"code":70016,"desc":"x"}""") }
        try {
            XiaomiCloud(http).awaitQrLogin(qrStart)
            fail()
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("70016"))
        }
    }

    // --------------------------------------------------------------- STS ---

    private fun login(location: String = "https://sts-hlth.io.mi.com/healthapp/sts?d=1", nonce: String? = "7") =
        QrLogin("1", "c", ssecurity, "p", location, nonce)

    @Test
    fun stsPlainRequestFirst() = runBlocking {
        val http = FakeCloudHttp { _, _, _, _ -> ok("", listOf("serviceToken=abc; path=/")) }
        val s = XiaomiCloud(http).healthSession(login())
        assertEquals("abc", s.serviceToken)
        assertEquals(1, http.calls.size)
        assertTrue(http.calls[0].follow)
        assertTrue(http.calls[0].headers.getValue("User-Agent").contains("APP/com.xiaomi.mihome"))
    }

    @Test
    fun stsRetriesWithClientSignAndNeverLogsTheToken() = runBlocking {
        val logs = mutableListOf<String>()
        val http = FakeCloudHttp { _, url, _, _ -> if ("clientSign=" in url) ok("", listOf("serviceToken=secretvalue; path=/"), 302) else ok("ok") }
        val s = XiaomiCloud(http, log = { logs += it }).healthSession(login())
        assertEquals("secretvalue", s.serviceToken)
        assertEquals(2, http.calls.size)
        assertTrue(http.calls[1].url.startsWith("https://sts-hlth.io.mi.com/healthapp/sts?d=1&clientSign="))
        assertFalse(http.calls[1].follow)
        assertTrue(logs.joinToString("\n").contains("token não"))
        assertFalse(logs.joinToString("\n").contains("secretvalue"))
    }

    @Test
    fun stsFailsWhenNoTokenIsGiven() = runBlocking {
        val http = FakeCloudHttp { _, _, _, _ -> ok("ok") }
        try {
            XiaomiCloud(http).healthSession(login())
            fail()
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("token de serviço"))
        }
    }

    @Test
    fun stsRefusesForeignHosts() = runBlocking {
        val http = FakeCloudHttp { _, _, _, _ -> ok("", listOf("serviceToken=abc")) }
        try {
            XiaomiCloud(http).healthSession(login(location = "https://evil.example/sts"))
            fail()
        } catch (e: CloudException) {
            assertTrue(http.calls.isEmpty())
        }
    }

    // -------------------------------------------------------- Mi Fitness ---

    @Test
    fun fallsBackToStreamVariantAndReturnsKeys() = runBlocking {
        val http = FakeCloudHttp { _, url, _, body ->
            if (queryParam(url, "_nonce") != null) {
                ok("unauthorized", status = 401) // variante "mihome" recusada
            } else {
                val nonce = body!!.split('&').map { it.split('=', limit = 2) }.first { it[0] == "_nonce" }[1].let { java.net.URLDecoder.decode(it, "UTF-8") }
                ok(encrypt(nonce, sourceList))
            }
        }
        val res = XiaomiCloud(http).listFitnessDevices(session(), "sg")
        assertEquals(
            listOf("hlth.io.mi.com/app/v1/source/get_source_list"),
            http.calls.map { it.url.substringAfter("https://").substringBefore('?') }.distinct(),
        )
        assertEquals(2, http.calls.size)
        assertTrue(res.ok)
        assertEquals(3, res.devices.count { it.key != null })
    }

    @Test
    fun triesRegionHostWhenBaseHostHasNoDevices() = runBlocking {
        val hosts = mutableListOf<String>()
        val http = FakeCloudHttp { _, url, _, _ ->
            val host = url.substringAfter("https://").substringBefore('/')
            hosts += host
            val body = if (host == "sg.hlth.io.mi.com") sourceList else """{"code":0,"result":{"list":[]}}"""
            ok(encrypt(queryParam(url, "_nonce")!!, body))
        }
        val res = XiaomiCloud(http).listFitnessDevices(session(), null)
        assertEquals(listOf("hlth.io.mi.com", "sg.hlth.io.mi.com"), hosts)
        assertEquals("sg.hlth.io.mi.com", res.devices.first().host)
    }

    @Test
    fun reportsNotOkWhenXiaomiRejectsEverything() = runBlocking {
        val http = FakeCloudHttp { _, _, _, _ -> ok("", status = 401) }
        val res = XiaomiCloud(http).listFitnessDevices(session(), "sg")
        assertFalse(res.ok)
        assertTrue(res.devices.isEmpty())
    }

    @Test
    fun healthRequestSendsTheSessionOnlyToMiHosts() = runBlocking {
        val http = FakeCloudHttp { _, _, _, _ -> ok("", status = 401) }
        XiaomiCloud(http).listFitnessDevices(session(), "evil.example/#")
        assertTrue(http.calls.all { it.url.startsWith("https://hlth.io.mi.com/") })
    }

    @Test
    fun parseSourceListKeepsOneRowPerValidKeyAndNeverOffersBeaconKey() {
        val rows = XiaomiCloud.parseSourceList(JSONObject(sourceList), "hlth.io.mi.com")
        val band = rows.filter { it.name == "Xiaomi Smart Band 10" }
        assertEquals(1, band.size) // a mesma chave aparece também em "token": só uma linha
        assertEquals(keyA, band.single().key)
        assertEquals("encrypt_key", band.single().keyField)

        val band7 = rows.single { it.name == "Band 7" }
        assertEquals("a3c10e34e5c14637eea6b9efc0610600", band7.key)
        assertEquals("auth_key", band7.keyField)

        assertEquals(listOf("token"), rows.filter { it.name == "Watch" }.map { it.keyField })
        assertFalse(rows.any { it.key == "ffeeddccbbaa99887766554433221100" })

        val scale = rows.single { it.name == "Balança" }
        assertNull(scale.key)
        assertTrue(XiaomiCloud.parseSourceList(null, "x").isEmpty())
        assertTrue(XiaomiCloud.parseSourceList(JSONObject("""{"code":0,"result":{"list":null}}"""), "x").isEmpty())
    }

    @Test
    fun urlChecks() {
        assertTrue(XiaomiCloud.isXiaomiLongPollUrl("https://sgp.lp.account.xiaomi.com/lp/s?k=lp_96D43D0116fa57"))
        assertTrue(XiaomiCloud.isXiaomiLongPollUrl("https://account.xiaomi.com/longPolling/login?ticket=1"))
        assertFalse(XiaomiCloud.isXiaomiLongPollUrl("https://evil.example/longPolling/"))
        assertFalse(XiaomiCloud.isXiaomiLongPollUrl("https://account.xiaomi.com.evil.example/longPolling/"))
        assertFalse(XiaomiCloud.isXiaomiLongPollUrl("http://account.xiaomi.com/longPolling/"))
        assertFalse(XiaomiCloud.isXiaomiLongPollUrl("https://account.xiaomi.com/pass/serviceLogin"))
        assertTrue(XiaomiCloud.isXiaomiUrl("https://account.xiaomi.com/pass/qr/login?x"))
        assertTrue(XiaomiCloud.isXiaomiUrl("https://sts-hlth.io.mi.com/x"))
        assertFalse(XiaomiCloud.isXiaomiUrl("https://notxiaomi.com/x"))
        assertFalse(XiaomiCloud.isXiaomiUrl("http://account.xiaomi.com/x"))
        assertFalse(XiaomiCloud.isXiaomiUrl("javascript:alert(1)"))
        assertEquals("hlth.io.mi.com", XiaomiCloud.healthHost(null))
        assertEquals("hlth.io.mi.com", XiaomiCloud.healthHost("cn"))
        assertEquals("sg.hlth.io.mi.com", XiaomiCloud.healthHost("sg"))
        assertEquals("hlth.io.mi.com", XiaomiCloud.healthHost("evil.example/#"))
    }

    @Test
    fun downloadQrOnlyFromXiaomiHosts() = runBlocking {
        val cloud = XiaomiCloud(FakeCloudHttp { _, _, _, _ -> ok("") })
        assertNotNull(cloud.downloadQr(QrStart("https://account.xiaomi.com/qr", "", "https://account.xiaomi.com/lp/x", 300)))
        assertNull(cloud.downloadQr(QrStart("https://evil.example/qr", "", "https://account.xiaomi.com/lp/x", 300)))
    }
}
