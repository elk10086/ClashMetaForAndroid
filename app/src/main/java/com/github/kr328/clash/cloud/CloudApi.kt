package com.github.kr328.clash.cloud

import android.content.Context
import android.util.Base64
import com.github.kr328.clash.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
        configUrls.mapNotNull { url -> runCatching { readConfigDocument(url) }.getOrNull() }
            .firstOrNull { it.optJSONObject("data") != null }
            ?: throw CloudException("远端配置不可用")
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
        var last: Throwable? = null
        val candidates = baseUrls()
        for (base in candidates) {
            try {
                val query = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
                val target = base.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
                val connection = URL(target).openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "FreeTime-Android")
                if (authenticated) connection.setRequestProperty("Authorization", session.authData)
                val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (connection.responseCode == 401) {
                    session.clear()
                    throw CloudException("登录已失效，请重新登录")
                }
                if (connection.responseCode !in 200..299) throw CloudException("服务请求失败 (${connection.responseCode})")
                prefs.edit().putString("base_url", base).apply()
                return JSONObject(body)
            } catch (e: Throwable) {
                last = e
            }
        }
        throw CloudException(last?.message ?: "暂时无法连接服务")
    }

    private suspend fun baseUrls(): List<String> {
        val cached = prefs.getString("base_url", "").orEmpty()
        val configured = loadRemoteConfig()
        return (listOf(cached) + configured).map { it.trimEnd('/') }.filter { it.startsWith("http") }.distinct()
            .ifEmpty { throw CloudException("服务配置不可用，请检查网络") }
    }

    private suspend fun loadRemoteConfig(): List<String> = coroutineScope {
        configUrls.map { url -> async(Dispatchers.IO) { runCatching { readConfig(url) }.getOrNull() } }
            .awaitAll().filterNotNull().firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun readConfig(url: String): List<String> {
        val data = readConfigDocument(url).optJSONObject("data") ?: return emptyList()
        val result = mutableListOf<String>()
        data.optJSONArray("loginDomains")?.let { array ->
            for (i in 0 until array.length()) result += array.optString(i)
        }
        result += data.optString("baseUrl")
        return result.filter { it.isNotBlank() }
    }

    private fun readConfigDocument(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val raw = connection.inputStream.bufferedReader().use { it.readText() }.trim().trim('"')
        val text = if (raw.startsWith("{")) raw else decryptConfig(raw)
        return JSONObject(text)
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
}

class CloudException(message: String) : Exception(message)
