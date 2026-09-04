package com.github.kr328.clash.cloud

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.common.constants.ClientUserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

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

    suspend fun subscription(): CloudSubscription = withContext(Dispatchers.IO) {
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
    suspend fun inviteSummary(): JSONObject = authenticatedGet("/api/v1/user/invite/fetch")
    suspend fun inviteDetails(): JSONObject = authenticatedGet("/api/v1/user/invite/details")
    suspend fun createInviteCode(): JSONObject = authenticatedGet("/api/v1/user/invite/save")
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
    }

    suspend fun remoteConfig(): JSONObject = withContext(Dispatchers.IO) {
        val urls = configUrls.take(3).shuffled() + configUrls.drop(3)
        urls.mapNotNull { url -> runCatching { readConfigDocument(url) }.getOrNull() }
            .firstOrNull { it.optJSONObject("data") != null }
            ?: throw CloudException("远端配置不可用")
    }

    /** free_time_new order: remembered origin -> subDomains -> subUrl -> API subscribe_url. */
    suspend fun subscriptionUrls(subscribeUrl: String): List<String> = withContext(Dispatchers.IO) {
        val original = validHttpUri(subscribeUrl) ?: return@withContext emptyList()
        val data = runCatching { remoteConfig().optJSONObject("data") }.getOrNull()
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
                if (recoveryUsed || !isRecoverableBaseUrlFailure(e, authenticated)) {
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
        val connection = URL(target).openConnection() as HttpURLConnection
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

            val reachable = coroutineScope {
                candidates.map { candidate ->
                    async(Dispatchers.IO) { candidate.takeIf { probeBaseUrl(it) } }
                }.awaitAll().filterNotNull()
            }
            val selected = reachable.randomOrNull()
                ?: fallback.takeIf { it.isNotBlank() && excludedOrigins.isEmpty() }
                ?: throw CloudException("没有可用的登录域名")
            prefs.edit().putString("base_url", selected).apply()
            return selected
        } finally {
            baseUrlSelectionMutex.unlock()
        }
    }

    private fun probeBaseUrl(base: String): Boolean {
        val connection = URL("${base.trimEnd('/')}/api/v1/guest/comm/config").openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", ClientUserAgent.value(context))
        return try {
            if (connection.responseCode !in 200..299) return false
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            when (val data = json.opt("data")) {
                is JSONObject -> data.length() > 0
                is org.json.JSONArray -> data.length() > 0
                is Boolean -> data
                is Number -> data.toInt() != 0
                is String -> data.isNotBlank()
                else -> json.optString("message").equals("success", true) || json.optString("status") in setOf("success", "1")
            }
        } catch (_: Throwable) {
            false
        } finally {
            connection.disconnect()
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

    private fun readConfigDocument(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", ClientUserAgent.value(context))
        return try {
            val raw = connection.inputStream.bufferedReader().use { it.readText() }.trim().trim('"')
            val text = if (raw.startsWith("{")) raw else decryptConfig(raw)
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun decryptConfig(value: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("fastflyfly@789.!".toByteArray(), "AES"))
        return String(cipher.doFinal(Base64.decode(value, Base64.DEFAULT)))
    }

    private fun requireData(json: JSONObject): JSONObject = json.optJSONObject("data")
        ?: throw CloudException(messageOf(json, "服务返回数据异常"))
    private fun messageOf(json: JSONObject, fallback: String) = json.optString("message").ifBlank { fallback }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private val configUrls: List<String>
        get() = BuildConfig.BRAND_CONFIG_URLS.split('|').filter { it.startsWith("https://") }

    private class HttpStatusException(val code: Int, message: String) : IOException(message)

    companion object {
        private val baseUrlSelectionMutex = Mutex()
    }
}

class CloudException(message: String) : Exception(message)
