package com.github.kr328.clash.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.CloudUpdateActivity
import org.json.JSONObject

object CloudReleaseManager {
    private const val PREFS = "cloud_release"
    @Volatile private var updateDialogVisible = false

    suspend fun checkOnLaunch(activity: AppCompatActivity): JSONObject? {
        val data = CloudApi(activity).remoteConfig().optJSONObject("data") ?: return null
        showUpdate(activity, data)
        return data
    }

    suspend fun announcementData(context: Context): JSONObject? =
        CloudApi(context).remoteConfig().optJSONObject("data")

    suspend fun showAnnouncement(activity: AppCompatActivity, automatic: Boolean = false) {
        val data = announcementData(activity) ?: return
        showAnnouncement(activity, data, automatic, openLink = true)
    }

    fun showLoginAnnouncement(activity: AppCompatActivity, data: JSONObject, automatic: Boolean = false) =
        showAnnouncement(activity, data, automatic, openLink = true)

    fun isDashboardAnnouncementUnread(context: Context, data: JSONObject): Boolean {
        if (data.optInt("show", 0) != 1 || !hasAnnouncementContent(data)) return false
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("dashboard_announcement_read_${announcementSignature(data)}", false)
    }

    fun showDashboardAnnouncement(activity: AppCompatActivity, data: JSONObject, onRead: () -> Unit) {
        showAnnouncement(activity, data, automatic = false, openLink = false) {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("dashboard_announcement_read_${announcementSignature(data)}", true)
                .apply()
            onRead()
        }
    }

    suspend fun checkUpdate(activity: AppCompatActivity) {
        val data = CloudApi(activity).remoteConfig().optJSONObject("data") ?: return
        val remote = data.optString("version").trim().removePrefix("v")
        val local = BuildConfig.BRAND_VERSION_NAME
        val newer = isRemoteNewer(local, remote)
        val forced = newer && data.optInt("forcedFlag", 0) == 2
        activity.runOnUiThread { openUpdatePage(activity, data, remote.ifBlank { local }, local, forced, !newer, allowEmptyUrl = true) }
    }

    suspend fun customerServiceUrl(context: Context): String {
        val data = CloudApi(context).remoteConfig().optJSONObject("data") ?: return ""
        return data.optString("customerServiceUrl", data.optString("customer_service_url"))
    }

    /** The login "anti-loss" link follows free_time_new: remote img_url first, brand site fallback. */
    suspend fun officialSiteUrl(context: Context): String {
        val raw = CloudApi(context).remoteConfig().optJSONObject("data")
            ?.optString("img_url")
            ?.trim()
            .orEmpty()
        if (raw.isBlank()) return BuildConfig.BRAND_OFFICIAL_SITE_URL
        val normalized = if (raw.contains("://")) raw else "https://$raw"
        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
        return if (uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            normalized
        } else {
            BuildConfig.BRAND_OFFICIAL_SITE_URL
        }
    }

    private fun hasAnnouncementContent(data: JSONObject): Boolean =
        data.optString("title").isNotBlank() || data.optString("content").isNotBlank() || data.optString("img_url").isNotBlank()

    private fun announcementSignature(data: JSONObject): String {
        val raw = "${data.optString("title")}|${data.optString("content")}|${data.optString("btnTitle")}|${data.optString("img_url")}"
        return raw.hashCode().toString()
    }

    private fun showAnnouncement(
        activity: AppCompatActivity,
        data: JSONObject,
        automatic: Boolean,
        openLink: Boolean,
        onDismiss: (() -> Unit)? = null,
    ) {
        if (automatic && data.optInt("show", 0) != 1) return
        val title = data.optString("title").trim()
        val content = data.optString("content").trim()
        if (!hasAnnouncementContent(data)) return
        val signature = announcementSignature(data)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (automatic && prefs.getBoolean("announcement_auto_shown_$signature", false)) return
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            val dialog = AlertDialog.Builder(activity)
                .setTitle(title.ifBlank { "公告" })
                .setMessage(content)
                .setPositiveButton(data.optString("btnTitle").ifBlank { if (openLink) "关闭" else "我知道了" }, null)
                .create()
            dialog.setOnDismissListener {
                if (automatic) prefs.edit().putBoolean("announcement_auto_shown_$signature", true).apply()
                onDismiss?.invoke()
            }
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (!openLink) {
                        dialog.dismiss()
                    } else {
                        val raw = data.optString("img_url").trim().ifBlank { BuildConfig.BRAND_OFFICIAL_SITE_URL }
                        val target = if (raw.contains("://")) raw else "https://$raw"
                        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                            .onSuccess { dialog.dismiss() }
                            .onFailure { Toast.makeText(activity, "无法打开公告链接", Toast.LENGTH_LONG).show() }
                    }
                }
            }
            dialog.show()
        }
    }

    private fun showUpdate(activity: AppCompatActivity, data: JSONObject): Boolean {
        val flag = data.optInt("forcedFlag", 0)
        val remote = data.optString("version")
        val local = BuildConfig.BRAND_VERSION_NAME
        if (flag !in 1..2 || !isRemoteNewer(local, remote)) return false
        if (updateDialogVisible) return true
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || updateDialogVisible) return@runOnUiThread
            updateDialogVisible = true
            val dialog = AlertDialog.Builder(activity)
                .setTitle(if (flag == 2) "版本更新" else "有新版本啦")
                .setMessage("最新版本：${remote.removePrefix("v")}\n当前版本：$local\n\n${data.optString("versionIntroduction").ifBlank { "暂无更新说明" }}")
                .setPositiveButton("立即更新", null)
                .apply { if (flag != 2) setNegativeButton("稍后再说", null) }
                .create()
            dialog.setCancelable(flag != 2)
            dialog.setCanceledOnTouchOutside(flag != 2)
            dialog.setOnDismissListener { updateDialogVisible = false }
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val url = data.optString("updateAddress_android").ifBlank { data.optString("updateAddress") }.trim()
                    if (url.isBlank()) {
                        Toast.makeText(activity, "当前没有可用的 Android 下载地址", Toast.LENGTH_LONG).show()
                    } else {
                        dialog.dismiss()
                        openUpdatePage(activity, data, remote.removePrefix("v"), local, flag == 2, false)
                    }
                }
            }
            dialog.show()
        }
        return true
    }

    private fun openUpdatePage(
        activity: AppCompatActivity,
        data: JSONObject,
        remote: String,
        local: String,
        forced: Boolean,
        latest: Boolean,
        allowEmptyUrl: Boolean = false,
    ) {
        val url = data.optString("updateAddress_android").ifBlank { data.optString("updateAddress") }.trim()
        if (!allowEmptyUrl && url.isBlank()) {
            Toast.makeText(activity, "当前没有可用的 Android 下载地址", Toast.LENGTH_LONG).show()
            return
        }
        activity.startActivity(Intent(activity, CloudUpdateActivity::class.java)
            .putExtra(CloudUpdateActivity.EXTRA_REMOTE, remote)
            .putExtra(CloudUpdateActivity.EXTRA_LOCAL, local)
            .putExtra(CloudUpdateActivity.EXTRA_LOGS, data.optString("versionIntroduction"))
            .putExtra(CloudUpdateActivity.EXTRA_URL, url)
            .putExtra(CloudUpdateActivity.EXTRA_PLATFORM_URLS, otherPlatformUrls(data).toString())
            .putExtra(CloudUpdateActivity.EXTRA_FORCED, forced)
            .putExtra(CloudUpdateActivity.EXTRA_LATEST, latest))
    }

    /** Matches free_time_new: the detail page also exposes every non-current platform address. */
    private fun otherPlatformUrls(data: JSONObject): JSONObject = JSONObject().apply {
        data.optString("updateAddress_ios").trim().takeIf(String::isNotEmpty)?.let { put("iOS", it) }
        pickArchitectureUrl(
            data.optString("updateAddress_windows").trim(),
            data.optString("updateAddress_windows_arm64").trim(),
            data.optString("updateAddress_windows_x64").trim(),
        ).takeIf(String::isNotEmpty)?.let { put("Windows", it) }
        val macGeneric = data.optString("updateAddress_mac").trim()
        val macCombined = parseMacCombinedUrls(macGeneric)
        pickArchitectureUrl(
            macGeneric,
            data.optString("updateAddress_mac_arm64").trim().ifBlank { macCombined.first },
            data.optString("updateAddress_mac_x64").trim().ifBlank { macCombined.second },
        ).takeIf(String::isNotEmpty)?.let { put("macOS", it) }
    }

    private fun pickArchitectureUrl(generic: String, arm64: String, x64: String): String {
        val isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64", true) || it.contains("aarch64", true) }
        return if (isArm64) arm64.ifBlank { generic.ifBlank { x64 } }
        else x64.ifBlank { generic.ifBlank { arm64 } }
    }

    /** The backend may put both labelled macOS links in the legacy generic field. */
    private fun parseMacCombinedUrls(raw: String): Pair<String, String> {
        val armHints = listOf("arm64", "aarch64", "apple", "苹果", "m芯片", "m1", "m2", "m3")
        val x64Hints = listOf("x64", "x86_64", "amd64", "intel", "英特尔", "因特尔")
        var arm = ""
        var x64 = ""
        Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).findAll(raw).forEach { match ->
            val url = match.value.trim().trimEnd(')', ']', '>', ',', ';', '，', '；')
            val line = raw.lineSequence().firstOrNull { it.contains(match.value) }.orEmpty().lowercase()
            val hint = "$line ${url.lowercase()}"
            if (arm.isBlank() && armHints.any(hint::contains)) arm = url
            else if (x64.isBlank() && x64Hints.any(hint::contains)) x64 = url
        }
        return arm to x64
    }

    private fun isRemoteNewer(localRaw: String, remoteRaw: String): Boolean {
        fun parts(value: String) = value.trim().removePrefix("v").split('.').map {
            Regex("^\\d+").find(it)?.value?.toIntOrNull() ?: 0
        }
        val local = parts(localRaw)
        val remote = parts(remoteRaw)
        repeat(maxOf(local.size, remote.size)) { index ->
            val a = local.getOrElse(index) { 0 }
            val b = remote.getOrElse(index) { 0 }
            if (a != b) return a < b
        }
        return false
    }
}
