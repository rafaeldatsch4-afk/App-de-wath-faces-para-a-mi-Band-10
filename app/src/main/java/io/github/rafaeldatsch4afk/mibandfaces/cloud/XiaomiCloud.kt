package io.github.rafaeldatsch4afk.mibandfaces.cloud

import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom

/** Erro do fluxo da nuvem, com mensagem pronta para o usuário. */
class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** QR code pedido à Xiaomi. */
class QrStart(
    /** Imagem do QR (a página da conta Xiaomi que o celular lê). */
    val qrUrl: String,
    /** Mesma página do QR, para abrir no navegador deste celular. */
    val loginUrl: String,
    /** Endereço da espera longa: responde quando o login for confirmado. */
    val longPollUrl: String,
    val timeoutSeconds: Int,
)

/** Sessão devolvida depois que o login foi confirmado. Contém segredos: nunca registrar. */
class QrLogin(
    val userId: String,
    val cUserId: String,
    val ssecurity: String,
    val passToken: String,
    val location: String,
    val nonce: String?,
) {
    override fun toString() = "QrLogin(<redacted>)"
}

/** Um aparelho da conta, com a chave que o Mi Fitness guarda para ele (se houver). */
data class CloudDevice(
    val host: String,
    val name: String,
    val model: String,
    val key: String?,
    /** Campo do registro de onde a chave saiu (encrypt_key, auth_key, token…). */
    val keyField: String?,
)

class FitnessResult(val ok: Boolean, val devices: List<CloudDevice>)

/**
 * Login na conta Xiaomi por QR code e leitura das chaves dos aparelhos do Mi Fitness.
 * Portado de `worker/xiaomi-cloud.ts` do miband-hack (AGPL-3.0). O app nunca vê a senha: o login
 * acontece na página da própria Xiaomi. A sessão fica só na memória.
 *
 * **Não validado com uma conta real:** o próprio miband-hack avisa que este caminho segue três
 * clientes de código aberto, mas ainda não foi confirmado com uma conta e uma banda de verdade.
 */
class XiaomiCloud(
    private val http: CloudHttp,
    private val log: (String) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
) {
    /** Pede o QR do login do Mi Fitness (serviço `miothealth`). */
    suspend fun startQrLogin(): QrStart {
        val query = linkedMapOf(
            "_qrsize" to "480",
            "qs" to "%3Fsid%3D$HEALTH_SID%26_json%3Dtrue",
            "callback" to HEALTH_CALLBACK,
            "_hasLogo" to "false",
            "sid" to HEALTH_SID,
            "serviceParam" to "",
            "_locale" to "en_GB",
            "_dc" to System.currentTimeMillis().toString(),
        ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val r = fetch("GET", "https://account.xiaomi.com/longPolling/loginUrl?$query", what = "pedido do QR")
        if (r.status != 200) throw CloudException("A Xiaomi recusou o pedido do QR (HTTP ${r.status})")
        val json = parseJson(r.body) ?: throw CloudException("Resposta inesperada da Xiaomi ao pedir o QR")
        val qr = json.optString("qr")
        val lp = json.optString("lp")
        if (qr.isEmpty() || lp.isEmpty()) throw CloudException("A Xiaomi não devolveu o QR code")
        if (!isXiaomiLongPollUrl(lp)) throw CloudException("Endereço de espera inesperado vindo da Xiaomi")
        val loginUrl = json.optString("loginUrl")
        log("Xiaomi: QR pedido (validade ${json.optInt("timeout", 300)} s)")
        return QrStart(qr, loginUrl, lp, json.optInt("timeout", 300))
    }

    /** Baixa a imagem do QR. Retorna null se o endereço não for da Xiaomi ou o download falhar. */
    suspend fun downloadQr(start: QrStart): ByteArray? {
        if (!isXiaomiUrl(start.qrUrl)) return null
        return try {
            http.bytes(start.qrUrl)
        } catch (e: Exception) {
            log("Xiaomi: não consegui baixar a imagem do QR (${e.javaClass.simpleName})")
            null
        }
    }

    /** Espera até [waitMs] pelo login. Retorna null enquanto ninguém confirmou. */
    suspend fun pollQrLogin(start: QrStart, waitMs: Int): QrLogin? {
        val r = try {
            http.request("GET", start.longPollUrl, readTimeoutMs = waitMs)
        } catch (e: HttpTimeoutException) {
            return null
        } catch (e: java.io.IOException) {
            throw CloudException("Falha de rede ao esperar o login: ${e.message}", e)
        }
        if (r.status != 200) return null
        val json = parseJson(r.body) ?: return null
        if (json.optString("ssecurity").isEmpty() || json.optString("location").isEmpty()) {
            throw CloudException("O login na Xiaomi não foi concluído")
        }
        return QrLogin(
            userId = json.opt("userId")?.toString().orEmpty(),
            cUserId = json.opt("cUserId")?.toString().orEmpty(),
            ssecurity = json.getString("ssecurity"),
            passToken = json.opt("passToken")?.toString().orEmpty(),
            location = json.getString("location"),
            nonce = json.opt("nonce")?.toString(),
        )
    }

    /** Segue o endereço STS do login e guarda o `serviceToken` que ele entrega. */
    suspend fun healthSession(login: QrLogin): Session {
        if (!isHttpsHost(login.location, "mi.com", "xiaomi.com")) {
            throw CloudException("Endereço de sessão inesperado vindo da Xiaomi")
        }
        val token = fetchServiceToken(login.location, login.ssecurity, login.nonce)
        return Session(login.userId, login.ssecurity, token, login.cUserId)
    }

    /**
     * Primeiro como o ha_miband (GET simples com User-Agent de app, seguindo redirecionamentos);
     * depois com `clientSign` anexado, como o extrator do Mi Home: o STS do Mi Fitness respondeu
     * 200 sem token à segunda forma sozinha.
     */
    private suspend fun fetchServiceToken(location: String, ssecurity: String, nonce: String?): String {
        val host = URI(location).host
        val attempts = mutableListOf(Triple("simples", location, true))
        if (nonce != null && !Regex("[?&]clientSign=").containsMatchIn(location)) {
            val sign = URLEncoder.encode(XiaomiCloudCrypto.clientSign(nonce, ssecurity), "UTF-8")
            attempts += Triple("clientSign", location + (if (location.contains('?')) "&" else "?") + "clientSign=$sign", false)
        }
        for ((label, url, follow) in attempts) {
            val r = fetch(
                "GET", url,
                headers = mapOf("User-Agent" to stsAgent(), "Content-Type" to "application/x-www-form-urlencoded"),
                follow = follow, what = "sessão (STS $label)",
            )
            val token = r.setCookies.firstNotNullOfOrNull { Regex("^serviceToken=([^;]+)").find(it)?.groupValues?.get(1) }
            log("Xiaomi: STS $host ($label): HTTP ${r.status}, token ${if (token != null) "sim" else "não"}")
            if (token != null) return token
        }
        throw CloudException("A Xiaomi não entregou o token de serviço do Mi Fitness")
    }

    /** Lista os aparelhos do Mi Fitness com as chaves. [region] null = detectar entre as regiões. */
    suspend fun listFitnessDevices(session: Session, region: String?): FitnessResult {
        val regions = if (region != null) listOf(region) else AUTO_REGIONS
        val hosts = (listOf(healthHost(null)) + regions.map { healthHost(it) }).distinct()
        var variants = listOf(HealthVariant.MIHOME, HealthVariant.STREAM)
        var ok = false
        for (host in hosts) {
            for (variant in variants) {
                val res = callHealth(session, host, variant) ?: continue
                if (res.optInt("code", -1) != 0) continue
                ok = true
                variants = listOf(variant) // a codificação que a Xiaomi aceitou; os outros hosts só usam ela
                val devices = parseSourceList(res, host)
                if (devices.isNotEmpty()) return FitnessResult(true, devices)
                break // este host respondeu; uma lista vazia não muda com a outra variante
            }
        }
        return FitnessResult(ok, emptyList())
    }

    private enum class HealthVariant { MIHOME, STREAM }

    private suspend fun callHealth(s: Session, host: String, variant: HealthVariant): JSONObject? {
        val nonce = XiaomiCloudCrypto.makeNonce(random = random)
        val json = JSONObject().put("page_size", 50).put("status", 1).toString()
        val params = if (variant == HealthVariant.MIHOME) {
            XiaomiCloudCrypto.buildEncryptedParams(HEALTH_PATH, json, s.ssecurity, nonce)
        } else {
            XiaomiCloudCrypto.buildStreamParams(HEALTH_PATH, mapOf("data" to json), s.ssecurity, nonce)
        }
        val query = params.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
        val cookies = if (variant == HealthVariant.MIHOME) {
            listOf("userId=${s.userId}", "cUserId=${s.cUserId}", "yetAnotherServiceToken=${s.serviceToken}", "serviceToken=${s.serviceToken}", "locale=en_GB", "channel=MI_APP_STORE")
        } else {
            listOf("cUserId=${s.cUserId}", "serviceToken=${s.serviceToken}", "locale=en_us")
        }
        val headers = linkedMapOf(
            "Accept-Encoding" to "identity",
            "User-Agent" to if (variant == HealthVariant.MIHOME) stsAgent() else "Android-12-9.8.348i-google-Pixel 4",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Cookie" to cookies.joinToString("; "),
        )
        if (variant == HealthVariant.MIHOME) headers["MIOT-ENCRYPT-ALGORITHM"] = "ENCRYPT-RC4" else headers["region_tag"] = "ru"
        val url = if (variant == HealthVariant.MIHOME) "https://$host$HEALTH_PATH?$query" else "https://$host$HEALTH_PATH"
        val r = fetch("POST", url, headers, body = if (variant == HealthVariant.STREAM) query else null, what = "lista do Mi Fitness")
        val tag = "$host ${variant.name.lowercase()}"
        if (r.status !in 200..299) {
            log("Xiaomi: $tag: HTTP ${r.status}")
            return null
        }
        return try {
            val text = XiaomiCloudCrypto.decryptResponse(s.ssecurity, nonce, r.body.trim().trim('"'))
            val res = JSONObject(text)
            log("Xiaomi: $tag: HTTP ${r.status}, code ${res.opt("code")}")
            res
        } catch (e: Exception) {
            log("Xiaomi: $tag: HTTP ${r.status}, resposta ilegível")
            null
        }
    }

    private suspend fun fetch(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        follow: Boolean = false,
        what: String,
    ): HttpResponse = try {
        http.request(method, url, headers, body, follow)
    } catch (e: CloudException) {
        throw e
    } catch (e: java.io.IOException) {
        throw CloudException("Sem conexão com a Xiaomi ($what): ${e.message ?: e.javaClass.simpleName}", e)
    }

    private fun stsAgent(): String {
        fun chars(n: Int, from: Char, to: Char) = (1..n).joinToString("") { (from + random.nextInt(to - from + 1)).toString() }
        return "${chars(18, 'a', 'z')}-${chars(13, 'A', 'E')} APP/com.xiaomi.mihome APPV/10.5.201"
    }

    /** Sessão do Mi Fitness. Contém segredos: nunca registrar. */
    class Session(val userId: String, val ssecurity: String, val serviceToken: String, val cUserId: String) {
        override fun toString() = "Session(<redacted>)"
    }

    companion object {
        const val HEALTH_SID = "miothealth"
        const val HEALTH_CALLBACK = "https://sts-hlth.io.mi.com/healthapp/sts"
        const val HEALTH_PATH = "/app/v1/source/get_source_list"

        /** Regiões conhecidas (as mesmas do miband-hack). */
        val REGIONS = listOf("sg", "de", "us", "cn", "i2", "ru", "tw", "in")

        /** Regiões testadas na detecção automática, a mais provável primeiro. */
        private val AUTO_REGIONS = listOf("sg", "de", "us", "i2", "ru", "in")

        /** Campos em que já se viu a chave de 16 bytes da banda, do mais confiável para o menos. */
        private val KEY_FIELDS = listOf("encrypt_key", "encryptKey", "auth_key", "authKey", "token")

        /** Só monta hosts a partir de regiões conhecidas: o valor vira parte do endereço que recebe a sessão. */
        fun healthHost(region: String?): String =
            if (region == null || region == "cn" || region !in REGIONS) "hlth.io.mi.com" else "$region.hlth.io.mi.com"

        private fun parseJson(text: String): JSONObject? = try {
            JSONObject(text.removePrefix("&&&START&&&"))
        } catch (e: JSONException) {
            null
        }

        private fun isHttpsHost(url: String, vararg suffixes: String): Boolean = try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: ""
            uri.scheme == "https" && suffixes.any { host == it || host.endsWith(".$it") }
        } catch (e: Exception) {
            false
        }

        /** Endereço https de um domínio da Xiaomi (xiaomi.com ou mi.com). */
        fun isXiaomiUrl(url: String) = isHttpsHost(url, "xiaomi.com", "mi.com")

        /** Só a espera longa da própria Xiaomi, ex.: https://sgp.lp.account.xiaomi.com/lp/s?k=lp_… */
        fun isXiaomiLongPollUrl(url: String): Boolean = try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: ""
            val hostOk = host == "account.xiaomi.com" || host.endsWith(".account.xiaomi.com")
            uri.scheme == "https" && hostOk && (uri.path.startsWith("/lp/") || uri.path.startsWith("/longPolling/"))
        } catch (e: Exception) {
            false
        }

        /** Uma linha por chave candidata distinta e válida (32 hex), do campo mais confiável para o menos. */
        fun parseSourceList(res: JSONObject?, host: String): List<CloudDevice> {
            val out = mutableListOf<CloudDevice>()
            val list = res?.optJSONObject("result")?.optJSONArray("list") ?: return out
            for (i in 0 until list.length()) {
                val src = list.optJSONObject(i) ?: continue
                val detail = when (val d = src.opt("detail")) {
                    is JSONObject -> d
                    is String -> try { JSONObject(d) } catch (e: JSONException) { JSONObject() }
                    else -> JSONObject()
                }
                val name = src.optString("name").trim()
                val model = src.optString("model")
                val seen = mutableSetOf<String>()
                for (field in KEY_FIELDS) {
                    val raw = detail.opt(field) as? String ?: continue
                    val key = raw.trim().lowercase().removePrefix("0x")
                    if (!Regex("^[0-9a-f]{32}$").matches(key) || !seen.add(key)) continue
                    out += CloudDevice(host, name, model, key, field)
                }
                if (seen.isEmpty()) out += CloudDevice(host, name, model, null, null)
            }
            return out
        }
    }
}
