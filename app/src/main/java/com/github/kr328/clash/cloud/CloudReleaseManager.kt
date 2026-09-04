package com.github.kr328.clash.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.BuildConfig
import org.json.JSONObject

object CloudReleaseManager {
    private const val PREFS = "cloud_release"

    suspend fun checkOnLaunch(activity: AppCompatActivity) {
        val data = CloudApi(activity).remoteConfig().optJSONObject("data") ?: return
        showAnnouncement(activity, data, automatic = true)
        showUpdate(activity, data, userInitiated = false)
    }

    suspend fun showAnnouncement(activity: AppCompatActivity, automatic: Boolean = false) {
        val data = CloudApi(activity).remoteConfig().optJSONObject("data") ?: return
        showAnnouncement(activity, data, automatic)
    }

    suspend fun checkUpdate(activity: AppCompatActivity) {
        val data = CloudApi(activity).remoteConfig().optJSONObject("data") ?: return
        if (!showUpdate(activity, data, userInitiated = true)) {
            activity.runOnUiThread {
                android.widget.Toast.makeText(activity, "当前已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun showAnnouncement(activity: AppCompatActivity, data: JSONObject, automatic: Boolean) {
        if (automatic && data.optInt("show", 0) != 1) return
        val title = data.optString("title").trim()
        val content = data.optString("content").trim()
        if (title.isBlank() && content.isBlank()) return
        val signature = "$title|$content|${data.optString("btnTitle")}|${data.optString("img_url")}".hashCode().toString()
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (automatic && prefs.getBoolean("announcement_auto_shown_$signature", false)) return
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle(title.ifBlank { "公告" })
                .setMessage(content)
                .setPositiveButton(data.optString("btnTitle").ifBlank { "我知道了" }) { _, _ ->
                    prefs.edit().putBoolean("announcement_auto_shown_$signature", true).apply()
                }
                .setOnDismissListener { prefs.edit().putBoolean("announcement_auto_shown_$signature", true).apply() }
                .show()
        }
    }

    private fun showUpdate(activity: AppCompatActivity, data: JSONObject, userInitiated: Boolean): Boolean {
        val flag = data.optInt("forcedFlag", 0)
        val remote = data.optString("version")
        if (flag !in 1..2 || !isRemoteNewer(BuildConfig.VERSION_NAME, remote)) return false
        val url = data.optString("updateAddress_android").ifBlank { data.optString("updateAddress") }
        activity.runOnUiThread {
            val dialog = AlertDialog.Builder(activity)
                .setTitle("版本更新")
                .setMessage("最新版本：$remote\n当前版本：${BuildConfig.VERSION_NAME}\n\n${data.optString("versionIntroduction")}")
                .setPositiveButton("立即更新") { _, _ ->
                    if (url.startsWith("http")) activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                .apply { if (flag != 2) setNegativeButton("稍后", null) }
                .create()
            dialog.setCancelable(flag != 2)
            dialog.setCanceledOnTouchOutside(flag != 2)
            dialog.show()
        }
        return true
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
