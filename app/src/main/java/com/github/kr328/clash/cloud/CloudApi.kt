package com.github.kr328.clash.cloud

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.common.constants.ClientUserAgent
import com.github.kr328.clash.common.util.firstSuccessfulHttp
import com.github.kr328.clash.common.util.readCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class CloudSubscription(
    val email: String,
    val planId: Long?,
    val plan: String,
    val subscribeUrl: String,
    val used: Long,
    val total: Long,
    val expiresAt: Long,
    val balance: Long,
)

class CloudApi(private val context: Context) {
    private val session = CloudSession(context)
    private val prefs = context.getSharedPreferences("cloud_config", Context.MODE_PRIVATE)

    suspend fun login(email: String, password: String) = withContext(Dispatchers.IO) {
        val json = request("POST", "/api/v1/passport/auth/login", mapOf("email" to email, "password" to password))
        val data = requireData(json)
        val auth = data.optString("auth_data")
        if (auth.isBlank()) throw CloudException(messageOf(json, "登录失败"))
        session.save(email, data.optString("token"), auth)
    }

    suspend fun sendEmail(email: String) = withContext(Dispatchers.IO) {
        val json = request("POST", "/api/v1/passport/comm/sendEmailVerify", mapOf("email" to email))
        if (!json.optBoolean("data", false)) throw CloudException(messageOf(json, "验证码发送失败"))
    }

    suspend fun register(email: String, password: String, code: String, invite: String) = withContext(Dispatchers.IO) {
        val json = request("POST", "/api/v1/passport/auth/register", mapOf(
            "email" to email, "password" to password, "email_code" to code, "invite_code" to invite
        ))
        val data = requireData(json)
        val auth = data.optString("auth_data")
        if (auth.isBlank()) throw CloudException(messageOf(json, "注册失败"))
        session.save(email, data.optString("token"), auth)
    }

    suspend fun resetPassword(email: String, password: String, code: String) = withContext(Dispatchers.IO) {
        val json = request("POST", "/api/v1/passport/auth/forget", mapOf(
            "email" to email, "password" to password, "email_code" to code
        ))
        if (!json.optBoolean("data", false)) throw CloudException(messageOf(json, "密码重置失败"))
    }

    suspend fun subscription(forceRefresh: Boolean = false): CloudSubscription =
        CloudSubscriptionCache.get(context, forceRefresh) { fetchSubscription() }

    internal suspend fun refreshSubscriptionIfDue() =
        CloudSubscriptionCache.refreshIfDue(context) { fetchSubscription() }

    private suspend fun fetchSubscription(): CloudSubscription = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/v1/user/getSubscribe", authenticated = true)
        val data = requireData(json)
        val plan = data.optJSONObject("plan")?.optString("name").orEmpty()
        CloudSubscription(
            email = data.optString("email", session.email),
            planId = if (data.isNull("plan_id")) null else data.optLong("plan_id"),
            plan = plan,
            subscribeUrl = data.optString("subscribe_url"),
            used = data.optLong("u") + data.optLong("d"),
            total = data.optLong("transfer_enable"),
            expiresAt = data.optLong("expired_at"),
            balance = data.optLong("balance"),
        ).also { if (it.subscribeUrl.isBlank()) throw CloudException("订阅地址为空") }
    }

    suspend fun plans(): JSONObject = authenticatedGet("/api/v1/user/plan/fetch")
    suspend fun planDetails(id: String): JSONObject = authenticatedGet(
        "/api/v1/user/plan/fetch",
        mapOf("id" to id),
    )
    suspend fun orders(): JSONObject = authenticatedGet("/api/v1/user/order/fetch")
    suspend fun orderDetails(tradeNo: String): JSONObject = authenticatedGet(
        "/api/v1/user/order/detail",
        mapOf("trade_no" to tradeNo),
    )
    suspend fun paymentMethods(): JSONObject = authenticatedGet("/api/v1/user/order/getPaymentMethod")
    suspend fun checkCoupon(code: String, planId: String): JSONObject = authenticatedPost(
        "/api/v1/user/coupon/check",
        mapOf("code" to code, "plan_id" to planId),
    )
    private suspend fun inviteSummary(): JSONObject = authenticatedGet("/api/v1/user/invite/fetch")
    internal suspend fun inviteSummaryWithCode(): CloudInviteSummary {
        val auth = session.authData
        val email = session.email
        return CloudInviteCodes.ensure(
            fetch = { inviteSummary() },
            create = { createInviteCode(); Unit },
            isCurrentSession = { auth.isNotBlank() && session.authData == auth && session.email == email },
        )
    }
    suspend fun inviteDetails(): JSONObject = authenticatedGet("/api/v1/user/invite/details")
    private suspend fun createInviteCode(): JSONObject = withContext(Dispatchers.IO) {
        // This GET mutates server state; never replay it on another origin after a timeout.
        request("GET", "/api/v1/user/invite/save", authenticated = true, allowRecovery = false)
    }
    suspend fun transferCommission(amount: Long): JSONObject = authenticatedPost(
        "/api/v1/user/transfer",
        mapOf("transfer_amount" to amount.toString()),
    )
    suspend fun tickets(): JSONObject = authenticatedGet("/api/v1/user/ticket/fetch")
    suspend fun ticketDetails(id: String): JSONObject = authenticatedGet(
        "/api/v1/user/ticket/fetch",
        mapOf("id" to id),
    )
    suspend fun userInfo(): JSONObject = authenticatedGet("/api/v1/user/info")

    fun clearRemoteConfigCache() {
        prefs.edit().remove("base_url").apply()
        remoteConfigGeneration.incrementAndGet()
    }

    suspend fun remoteConfig(): JSONObject = withContext(Dispatchers.IO) {
        remoteConfigMutex.withLock {
            val generation = remoteConfigGeneration.get()
            cachedRemoteConfig?.takeIf {
                it.generation == generation && System.nanoTime() - it.loadedAt < 60_000_000_000L
            }?.let { return@withLock JSONObject(it.json) }
            // Race primary mirrors; only use the final fallback if all primaries fail.
            val urls = configUrls.distinct()
            suspend fun fetch(candidates: List<String>) = firstSuccessfulHttp(candidates) {
                readConfigDocument(it).takeIf { json -> json.optJSONObject("data") != null }
            }
            val result = fetch(urls.take(3)) ?: fetch(urls.drop(3))
                ?: throw CloudException("远端配置不可用")
            cachedRemoteConfig = RemoteConfigSnapshot(result.toString(), System.nanoTime(), generation)
            result
        }
    }

    /** free_time_new order: remembered origin -> subDomains -> subUrl -> API subscribe_url. */
    suspend fun subscriptionUrls(subscribeUrl: String): List<String> = withContext(Dispatchers.IO) {
        val original = validHttpUri(subscribeUrl) ?: return@withContext emptyList()
        val data = runCatching { remoteConfig().optJSONObject("data") }.getOrNull()
        updateDdnsConfiguration(data)
        val bases = mutableListOf<String>()
        prefs.getString("subscription_domain", "")?.let(bases::add)
        data?.optJSONArray("subDomains")?.let { array ->
            for (index in 0 until array.length()) bases += array.optString(index)
        }
        data?.optString("subUrl")?.let(bases::add)

        buildList {
            bases.forEach { base -> rewriteUrlBase(original, base)?.let { if (it !in this) add(it) } }
            original.toString().let { if (it !in this) add(it) }
        }
    }

    suspend fun refreshDdnsConfiguration(): Boolean = withContext(Dispatchers.IO) {
        val data = runCatching { remoteConfig().optJSONObject("data") }.getOrNull() ?: return@withContext false
        updateDdnsConfiguration(data)
    }

    fun rememberSuccessfulSubscriptionUrl(url: String) {
        val uri = validHttpUri(url) ?: return
        prefs.edit().putString("subscription_domain", origin(uri)).apply()
    }

    suspend fun createOrder(planId: String, period: String, coupon: String = ""): JSONObject =
        authenticatedPost(
            "/api/v1/user/order/save",
            mapOf("plan_id" to planId, "period" to period) +
                if (coupon.isBlank()) emptyMap() else mapOf("coupon_code" to coupon),
        )

    suspend fun checkout(tradeNo: String, method: String): JSONObject = authenticatedPost(
        "/api/v1/user/order/checkout",
        mapOf("trade_no" to tradeNo, "method" to method),
    )

    suspend fun cancelOrder(tradeNo: String): JSONObject = authenticatedPost(
        "/api/v1/user/order/cancel",
        mapOf("trade_no" to tradeNo),
    )

    suspend fun createTicket(subject: String, level: Int, message: String): JSONObject =
        authenticatedPost(
            "/api/v1/user/ticket/save",
            mapOf("subject" to subject, "level" to level.toString(), "message" to message),
        )

    suspend fun replyTicket(id: String, message: String): JSONObject = authenticatedPost(
        "/api/v1/user/ticket/reply",
        mapOf("id" to id, "message" to message),
    )

    suspend fun closeTicket(id: String): JSONObject = authenticatedPost(
        "/api/v1/user/ticket/close",
        mapOf("id" to id),
    )

    private suspend fun authenticatedGet(path: String, params: Map<String, String> = emptyMap()) =
        withContext(Dispatchers.IO) { request("GET", path, params, authenticated = true) }

    private suspend fun authenticatedPost(path: String, params: Map<String, String>) =
        withContext(Dispatchers.IO) { request("POST", path, params, authenticated = true) }

    private suspend fun request(
        method: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        authenticated: Boolean = false,
        allowRecovery: Boolean = true,
    ): JSONObject {
        var base = selectBaseUrl(forceRefresh = false)
        var recoveryUsed = false
        while (true) {
            try {
                val result = performRequest(base, method, path, params, authenticated)
                prefs.edit().putString("base_url", base).apply()
                return result
            } catch (e: Throwable) {
                if (e is HttpStatusException && e.code == 401) {
                    session.clear()
                    throw CloudException("登录已失效，请重新登录")
                }
                if (!allowRecovery || recoveryUsed || !isRecoverableBaseUrlFailure(e, authenticated)) {
                    throw if (e is CloudException) e else CloudException(e.message ?: "暂时无法连接服务")
                }
                recoveryUsed = true
                base = selectBaseUrl(forceRefresh = true, excludedOrigins = setOf(origin(Uri.parse(base))))
            }
        }
    }

    private fun performRequest(
        base: String,
        method: String,
        path: String,
        params: Map<String, String>,
        authenticated: Boolean,
    ): JSONObject {
        val query = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val target = base.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val connection = cloudConnection(target)
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", ClientUserAgent.value(context))
        if (authenticated) connection.setRequestProperty("Authorization", session.authData)
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { messageOf(JSONObject(body), "服务请求失败 ($code)") }
                    .getOrDefault("服务请求失败 ($code)")
                throw HttpStatusException(code, message)
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun selectBaseUrl(
        forceRefresh: Boolean,
        excludedOrigins: Set<String> = emptySet(),
    ): String {
        baseUrlSelectionMutex.lock()
        try {
            val cached = normalizeBaseUrl(prefs.getString("base_url", "").orEmpty())
            if (!forceRefresh && validHttpUri(cached) != null) return cached

            val data = remoteConfig().optJSONObject("data") ?: throw CloudException("远端配置不可用")
            val fallback = normalizeBaseUrl(data.optString("baseUrl"))
            val candidates = buildList {
                data.optJSONArray("loginDomains")?.let { array ->
                    for (index in 0 until array.length()) add(normalizeBaseUrl(array.optString(index)))
                }
                add(fallback)
            }.filter { validHttpUri(it) != null && origin(Uri.parse(it)) !in excludedOrigins }.distinct()

            val selected = firstSuccessfulHttp(candidates) { candidate ->
                candidate.takeIf { probeBaseUrl(it) }
            }
                ?: fallback.takeIf { it.isNotBlank() && excludedOrigins.isEmpty() }
                ?: throw CloudException("没有可用的登录域名")
            prefs.edit().putString("base_url", selected).apply()
            return selected
        } finally {
            baseUrlSelectionMutex.unlock()
        }
    }

    private suspend fun probeBaseUrl(base: String): Boolean {
        val connection = cloudConnection("${base.trimEnd('/')}/api/v1/guest/comm/config")
        connection.connectTimeout = 12_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", ClientUserAgent.value(context))
        return connection.readCancellable {
            if (connection.responseCode !in 200..299) return@readCancellable false
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            when (val data = json.opt("data")) {
                is JSONObject -> data.length() > 0
                is org.json.JSONArray -> data.length() > 0
                is Boolean -> data
                is Number -> data.toInt() != 0
                is String -> data.isNotBlank()
                else -> json.optString("message").equals("success", true) || json.optString("status") in setOf("success", "1")
            }
        }
    }

    private fun isRecoverableBaseUrlFailure(error: Throwable, authenticated: Boolean): Boolean = when (error) {
        is HttpStatusException -> error.code in setOf(404, 502, 503, 504) || authenticated && error.code == 403
        is IOException -> true
        else -> false
    }

    private fun rewriteUrlBase(original: Uri, rawBase: String): String? {
        val base = validHttpUri(rawBase) ?: return null
        val basePath = base.encodedPath.orEmpty().trimEnd('/')
        val originalPath = original.encodedPath.orEmpty().let { if (it.startsWith('/')) it else "/$it" }
        return base.buildUpon()
            .encodedPath(basePath + originalPath)
            .encodedQuery(original.encodedQuery)
            .encodedFragment(original.encodedFragment)
            .build().toString()
    }

    private fun validHttpUri(value: String?): Uri? = runCatching { Uri.parse(value?.trim().orEmpty()) }.getOrNull()
        ?.takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() }

    private fun normalizeBaseUrl(value: String) = value.trim().trimEnd('/')
    private fun origin(uri: Uri): String = "${uri.scheme!!.lowercase()}://${uri.host!!.lowercase()}${if (uri.port >= 0) ":${uri.port}" else ""}"

    private suspend fun readConfigDocument(url: String): JSONObject {
        val connection = cloudConnection(url)
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", ClientUserAgent.value(context))
        return connection.readCancellable {
            val raw = connection.inputStream.bufferedReader().use { it.readText() }.trim().trim('"')
            val text = if (raw.startsWith("{")) raw else decryptConfig(raw)
            JSONObject(text)
        }
    }

    private fun decryptConfig(value: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("fastflyfly@789.!".toByteArray(), "AES"))
        return String(cipher.doFinal(Base64.decode(value, Base64.DEFAULT)))
    }

    private fun updateDdnsConfiguration(data: JSONObject?): Boolean {
        val stateFile = context.filesDir.resolve("ddns-config.json")
        val enabled = data?.opt("enableDdns").let {
            it == true || it == 1 || it?.toString()?.equals("true", ignoreCase = true) == true
        }
        if (!enabled) {
            return writeDdnsConfiguration(stateFile, JSONObject().put("enabled", false))
        }

        val protocols = data?.optJSONArray("replaceNodeDomainProtocols")
            ?: data?.optJSONArray("replace_node_domain_protocols")
            ?: data?.optJSONArray("nodeDomainReplaceProtocols")
            ?: data?.optJSONArray("node_domain_replace_protocols")
        val protocolValues = org.json.JSONArray()
        if (protocols != null) {
            for (index in 0 until protocols.length()) {
                protocols.optString(index).trim().takeIf(String::isNotEmpty)?.let(protocolValues::put)
            }
        }
        // As in free_time_new, an empty whitelist must never enable unrestricted replacement.
        if (protocolValues.length() == 0) {
            return writeDdnsConfiguration(stateFile, JSONObject().put("enabled", false))
        }

        val customUrl = data?.optString("ddnsDomainsUrl").orEmpty().ifBlank {
            data?.optString("ddns_domains_url").orEmpty()
        }.trim()
        val endpoint = validHttpUri(customUrl)?.toString() ?: DEFAULT_DDNS_DOMAINS_URL
        val mapping = runCatching { readDdnsDomainMap(endpoint) }.getOrNull() ?: return false
        return writeDdnsConfiguration(
            stateFile,
            JSONObject().put("enabled", true).put("protocols", protocolValues).put("mapping", mapping),
        )
    }

    private fun readDdnsDomainMap(url: String): JSONObject {
        val connection = cloudConnection(url)
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", ClientUserAgent.value(context))
        return try {
            if (connection.responseCode !in 200..299) throw IOException("DDNS request failed")
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val data = root.optJSONObject("data")
            val payload = if (data?.has("nonce") == true && data.has("ciphertext")) {
                val nonce = Base64.decode(data.getString("nonce"), Base64.DEFAULT)
                val ciphertext = Base64.decode(data.getString("ciphertext"), Base64.DEFAULT)
                require(nonce.size == 12) { "Invalid DDNS nonce" }
                val key = Base64.decode(DDNS_AES_KEY, Base64.DEFAULT)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            } else {
                data ?: root.optJSONObject("domains") ?: throw IOException("Invalid DDNS response")
            }
            JSONObject().also { output ->
                payload.keys().forEach { key ->
                    val normalizedKey = key.trim().lowercase()
                    val raw = payload.opt(key)
                    val value = when (raw) {
                        is org.json.JSONArray -> buildList {
                            for (index in 0 until raw.length()) raw.optString(index).trim()
                                .takeIf(String::isNotEmpty)?.let(::add)
                        }.joinToString(",")
                        else -> raw?.toString()?.trim().orEmpty()
                    }
                    if (normalizedKey.isNotEmpty() && value.isNotEmpty()) output.put(normalizedKey, value)
                }
            }.also { require(it.length() > 0) { "Empty DDNS mapping" } }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeDdnsConfiguration(file: java.io.File, value: JSONObject): Boolean {
        val content = value.toString()
        if (runCatching { file.readText() }.getOrNull() == content) return false
        val temporary = java.io.File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(file)) {
            file.writeText(content)
            temporary.delete()
        }
        return true
    }

    private fun requireData(json: JSONObject): JSONObject = json.optJSONObject("data")
        ?: throw CloudException(messageOf(json, "服务返回数据异常"))
    private fun messageOf(json: JSONObject, fallback: String) = json.optString("message").ifBlank { fallback }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private val configUrls: List<String>
        get() = BuildConfig.BRAND_CONFIG_URLS.split('|').filter { it.startsWith("https://") }

    /** Mirrors free_time_new CloudRequest.badCertificateCallback for cloud routes only. */
    private fun cloudConnection(url: String): HttpURLConnection =
        (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            if (this is HttpsURLConnection) {
                sslSocketFactory = cloudSslSocketFactory
                hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
        }

    private class HttpStatusException(val code: Int, message: String) : IOException(message)

    companion object {
        private data class RemoteConfigSnapshot(val json: String, val loadedAt: Long, val generation: Long)
        private val remoteConfigMutex = Mutex()
        private val remoteConfigGeneration = AtomicLong()
        private var cachedRemoteConfig: RemoteConfigSnapshot? = null
        private const val DEFAULT_DDNS_DOMAINS_URL =
            "https://fzzzznn.dibiaozuiqiangddnsdomain.top/api/v1/ddns/domains"
        private const val DDNS_AES_KEY = "XhITVtnWA+advZ9tM0sInHGRjfreLAX5FPTGc0qvPFU="
        private val baseUrlSelectionMutex = Mutex()
        private val cloudSslSocketFactory by lazy {
            val trustManager = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            }
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }.socketFactory
        }
    }
}

class CloudException(message: String) : Exception(message)
