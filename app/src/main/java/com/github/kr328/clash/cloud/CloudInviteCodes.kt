package com.github.kr328.clash.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class CloudInviteSummary(
    val json: JSONObject,
    val code: String,
    val generationError: String? = null,
)

/** Serialize the read/create/read sequence, including retries from multiple page instances. */
internal object CloudInviteCodes {
    private val mutex = Mutex()

    fun findCode(summary: JSONObject): String {
        val data = summary.optJSONObject("data") ?: return ""
        val direct = data.codeValue("invite_code")
        if (direct.isNotBlank()) return direct
        var newestCode = ""
        var newestTime = Long.MIN_VALUE
        for (key in listOf("codes", "code")) {
            val codes = data.optJSONArray(key) ?: continue
            repeat(codes.length()) { index ->
                val item = codes.optJSONObject(index) ?: return@repeat
                val code = item.codeValue("code")
                val time = item.optLong("updated_at", item.optLong("created_at"))
                if (code.isNotBlank() && time >= newestTime) {
                    newestTime = time
                    newestCode = code
                }
            }
        }
        return newestCode
    }

    private fun JSONObject.codeValue(key: String): String =
        if (isNull(key)) "" else optString(key).trim()

    suspend fun ensure(
        fetch: suspend () -> JSONObject,
        create: suspend () -> Unit,
        isCurrentSession: () -> Boolean,
    ): CloudInviteSummary = mutex.withLock {
        fun checkSession() = check(isCurrentSession()) { "登录状态已变化，请重新进入邀请返利" }
        checkSession()
        val original = fetch()
        checkSession()
        val existingCode = findCode(original)
        if (existingCode.isNotBlank()) return@withLock CloudInviteSummary(original, existingCode)

        val data = original.optJSONObject("data")
        // An invalid/partial response is not proof that the user has never generated a code.
        if (data == null || !(data.has("invite_code") || data.optJSONArray("codes") != null ||
                data.optJSONArray("code") != null)) {
            return@withLock CloudInviteSummary(original, "", "邀请码信息暂不可用，请点击重试。")
        }

        var creationFailure: Exception? = null
        try {
            checkSession()
            create()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            creationFailure = failure
        }
        checkSession()
        // A timeout may occur after the server saved the code. Read back before offering a retry.
        var latest = original
        try {
            latest = fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (creationFailure == null) creationFailure = failure
        }
        checkSession()
        val code = findCode(latest)
        CloudInviteSummary(
            latest, code,
            if (code.isNotBlank()) null else creationFailure?.message?.takeIf { it.isNotBlank() }
                ?: "邀请码暂未生成，请点击重试。",
        )
    }
}
