package com.github.kr328.clash.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.CloudUpdateActivity
import org.json.JSONObject

object CloudReleaseManager {
    private const val PREFS = "cloud_release"
    @Volatile private var updateDialogVisible = false

    suspend fun checkOnLaunch(activity: AppCompatActivity) {
        val data = CloudApi(activity).remoteConfig().optJSONObject("data") ?: return
        showAnnouncement(activity, data, automatic = true)
        showUpdate(activity, data)
    }

    suspend fun showAnnouncement(activity: AppCompatActivity, automatic: Boolean = false) {
        val data = CloudApi(activity).remoteConfig().optJSONObject("data") ?: return
        showAnnouncement(activity, data, automatic)
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
                .apply { if (flag != 2) setNegativeButton("稍后", null) }
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
            .putExtra(CloudUpdateActivity.EXTRA_FORCED, forced)
            .putExtra(CloudUpdateActivity.EXTRA_LATEST, latest))
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
