package com.github.kr328.clash.cloud

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Shared account snapshot; serialize reads to coalesce ordinary page refreshes. */
internal object CloudSubscriptionCache {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private const val TTL = 60_000L
    private fun prefs(context: Context) = context.getSharedPreferences("cloud_membership_cache", Context.MODE_PRIVATE)

    fun clear(context: Context) {
        generation.incrementAndGet()
        prefs(context).edit().clear().apply()
        revision.update { it + 1 }
    }

    fun peek(context: Context): CloudSubscription? = runCatching {
        val session = CloudSession(context)
        if (!session.loggedIn) return null
        val json = JSONObject(prefs(context).getString("membership", "").orEmpty())
        if (json.optString("account_email") != session.email.trim().lowercase()) return null
        CloudSubscription(
            email = json.optString("email"),
            planId = if (json.isNull("plan_id")) null else json.optLong("plan_id"),
            plan = json.optString("plan"), subscribeUrl = json.optString("subscribe_url"),
            used = json.optLong("used"), total = json.optLong("total"),
            expiresAt = json.optLong("expires_at"), balance = json.optLong("balance"),
        )
    }.getOrNull()

    suspend fun get(context: Context, forceRefresh: Boolean, fetch: suspend () -> CloudSubscription): CloudSubscription = mutex.withLock {
        val prefs = prefs(context)
        val age = System.currentTimeMillis() - prefs.getLong("fetched_at", 0)
        if (!forceRefresh && age in 0 until TTL) peek(context)?.let { return@withLock it }
        fetchAndCache(context, fetch)
    }

    suspend fun refreshIfDue(context: Context, fetch: suspend () -> CloudSubscription) = mutex.withLock {
        if (!CloudSession(context).loggedIn) return@withLock
        val prefs = prefs(context)
        val lastSuccess = if (peek(context) != null) prefs.getLong("fetched_at", 0) else 0L
        if (!CloudMembershipRefreshPolicy.isDue(
                System.currentTimeMillis(), lastSuccess, prefs.getLong("retry_after", 0),
            )) return@withLock
        // Reuse the page-read lock so a simultaneous page refresh cannot duplicate this request.
        fetchAndCache(context, fetch)
        Unit
    }

    /** Caller holds [mutex]; ordinary reads and the daily job share the same success time. */
    private suspend fun fetchAndCache(context: Context, fetch: suspend () -> CloudSubscription): CloudSubscription {
        val prefs = prefs(context)
        val session = CloudSession(context)
        val auth = session.authData
        val email = session.email.trim().lowercase()
        val requestGeneration = generation.get()
        fun isCurrentAccount() = generation.get() == requestGeneration &&
            session.authData == auth && session.email.trim().lowercase() == email
        check(auth.isNotBlank()) { "请先登录" }
        try {
            val result = fetch()
            // A response from a logged-out account must never replace another account's data.
            check(isCurrentAccount()) { "登录状态已变化，请重试" }
            val json = JSONObject()
                .put("account_email", email).put("email", result.email)
                .put("plan_id", result.planId ?: JSONObject.NULL).put("plan", result.plan)
                .put("subscribe_url", result.subscribeUrl).put("used", result.used)
                .put("total", result.total).put("expires_at", result.expiresAt)
                .put("balance", result.balance)
            prefs.edit().putString("membership", json.toString())
                .putLong("fetched_at", System.currentTimeMillis()).remove("retry_after").apply()
            revision.update { it + 1 }
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (isCurrentAccount()) prefs.edit()
                .putLong("retry_after", System.currentTimeMillis() + CloudMembershipRefreshPolicy.RETRY)
                .apply()
            throw failure
        }
    }
}
