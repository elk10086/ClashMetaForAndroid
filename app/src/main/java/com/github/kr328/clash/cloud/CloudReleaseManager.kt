package com.github.kr328.clash.cloud

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.CloudUpdateActivity
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

object CloudReleaseManager {
    private const val PREFS = "cloud_release"
    @Volatile private var updateDialogVisible = false
    @Volatile private var launchCheckCompleted = false
    @Volatile private var launchData: JSONObject? = null
    private val launchCheckMutex = Mutex()

    suspend fun checkOnLaunch(activity: AppCompatActivity): JSONObject? = launchCheckMutex.withLock {
        if (launchCheckCompleted) return@withLock launchData
        val data = CloudApi(activity).remoteConfig().optJSONObject("data") ?: return@withLock null
        launchData = data
        launchCheckCompleted = true
        showUpdate(activity, data)
        data
    }

    fun hasUpdateOffer(data: JSONObject): Boolean =
        data.optInt("forcedFlag", 0) in 1..2 && isRemoteNewer(BuildConfig.BRAND_VERSION_NAME, data.optString("version"))

    suspend fun announcementData(context: Context): JSONObject? =
        CloudApi(context).remoteConfig().optJSONObject("data")

    suspend fun showAnnouncement(activity: AppCompatActivity, automatic: Boolean = false) {
        val data = announcementData(activity) ?: return
        showAnnouncement(activity, data, automatic, openLink = true)
    }

    fun showLoginAnnouncement(activity: AppCompatActivity, data: JSONObject, automatic: Boolean = false) =
        showAnnouncement(activity, data, automatic, openLink = true)

    fun isDashboardAnnouncementUnread(context: Context, data: JSONObject): Boolean {
        val show = data.optInt("show", 0)
        if (show !in 1..2 || !hasAnnouncementContent(data)) return false
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("dashboard_announcement_read_${announcementSignature(data)}", false)
    }

    fun isDashboardAnnouncementVisible(context: Context, data: JSONObject): Boolean =
        when (data.optInt("show", 0)) {
            2 -> hasAnnouncementContent(data)
            1 -> isDashboardAnnouncementUnread(context, data)
            else -> false
        }

    fun showDashboardAnnouncement(activity: AppCompatActivity, data: JSONObject, onRead: () -> Unit) {
        showAnnouncement(activity, data, automatic = false, openLink = false) {
            if (data.optInt("show", 0) !in 1..2) return@showAnnouncement
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

    /** Login anti-loss address: use img_url only when configured, otherwise keep the brand default. */
    suspend fun officialSiteUrl(context: Context): String {
        val data = CloudApi(context).remoteConfig().optJSONObject("data")
        return resolveOfficialSiteUrl(data)
    }

    private fun resolveOfficialSiteUrl(data: JSONObject?): String {
        val raw = sequenceOf(
            data?.optString("img_url"),
            BuildConfig.BRAND_OFFICIAL_SITE_URL,
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.first()
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
        val show = data.optInt("show", 0)
        if (automatic && show != 2) return
        if (!automatic && show !in 1..2) return
        val title = data.optString("title").trim()
        val content = data.optString("content").trim()
        if (!hasAnnouncementContent(data)) return
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            showCardDialog(
                activity = activity,
                title = title.ifBlank { "公告" },
                content = content,
                primaryLabel = if (openLink) data.optString("btnTitle").ifBlank { "关闭" } else "关闭",
                closeDescription = "关闭公告",
                showClose = true,
                cancelable = true,
                onDismiss = onDismiss,
            ) {
                if (!openLink) {
                    true
                } else {
                    val target = resolveOfficialSiteUrl(data)
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                        .onFailure { Toast.makeText(activity, "无法打开公告链接", Toast.LENGTH_LONG).show() }
                        .isSuccess
                }
            }
        }
    }

    private fun showCardDialog(
        activity: AppCompatActivity,
        title: String,
        content: String,
        primaryLabel: String,
        closeDescription: String,
        showClose: Boolean,
        cancelable: Boolean,
        secondaryLabel: String? = null,
        onDismiss: (() -> Unit)? = null,
        onPrimary: () -> Boolean,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

        val primary = activity.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val onPrimaryColor = activity.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
        val surface = activity.resolveThemedColor(com.google.android.material.R.attr.colorSurface)
        val onSurface = activity.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val outline = activity.resolveThemedColor(com.google.android.material.R.attr.colorOutline)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = rounded(surface, 22, outline)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(activity).apply {
            text = title
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(onSurface)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val close = if (showClose) TextView(activity).apply {
            text = "×"
            this.contentDescription = closeDescription
            gravity = Gravity.CENTER
            textSize = 25f
            setTextColor(onSurface)
            background = rounded(Color.TRANSPARENT, 18, outline)
            isClickable = true
            isFocusable = true
        }.also {
            header.addView(it, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(12) })
        } else null
        root.addView(header)

        val message = TextView(activity).apply {
            text = content
            textSize = 15f
            setTextColor(onSurface)
            setLineSpacing(dp(4).toFloat(), 1f)
            visibility = if (content.isBlank()) View.GONE else View.VISIBLE
        }
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                setMeasuredDimension(measuredWidth, measuredHeight.coerceAtMost(dp(320)))
            }
        }.apply {
            isFillViewport = false
            clipToPadding = false
            addView(message, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = if (content.isBlank()) 0 else dp(18)
        })

        fun actionButton(label: String, filled: Boolean) = MaterialButton(activity).apply {
            text = label
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (filled) onPrimaryColor else onSurface)
            backgroundTintList = ColorStateList.valueOf(if (filled) primary else surface)
            strokeColor = ColorStateList.valueOf(if (filled) primary else outline)
            strokeWidth = if (filled) 0 else dp(1)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
        }

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val secondary = secondaryLabel?.let { label ->
            actionButton(label, false).also {
                actions.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
            }
        }
        val primaryAction = actionButton(primaryLabel, true)
        actions.addView(primaryAction, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            if (secondary != null) marginStart = dp(5)
        })
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(22) })

        val dialog = AlertDialog.Builder(activity)
            .setView(root, 0, 0, 0, 0)
            .create()
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setOnDismissListener { onDismiss?.invoke() }
        close?.setOnClickListener { dialog.dismiss() }
        secondary?.setOnClickListener { dialog.dismiss() }
        primaryAction.setOnClickListener { if (onPrimary()) dialog.dismiss() }
        dialog.setOnShowListener {
            val width = (activity.resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(480))
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
                setDimAmount(0.42f)
            }
        }
        dialog.show()
    }

    private fun showUpdate(activity: AppCompatActivity, data: JSONObject): Boolean {
        val flag = data.optInt("forcedFlag", 0)
        val remote = data.optString("version")
        val local = BuildConfig.BRAND_VERSION_NAME
        if (!hasUpdateOffer(data)) return false
        if (updateDialogVisible) return true
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || updateDialogVisible) return@runOnUiThread
            updateDialogVisible = true
            showCardDialog(
                activity = activity,
                title = if (flag == 2) "版本更新" else "有新版本啦",
                content = "最新版本：${remote.removePrefix("v")}\n当前版本：$local\n\n${data.optString("versionIntroduction").ifBlank { "暂无更新说明" }}",
                primaryLabel = "立即更新",
                closeDescription = "关闭更新提示",
                showClose = flag != 2,
                cancelable = flag != 2,
                secondaryLabel = if (flag != 2) "稍后再说" else null,
                onDismiss = { updateDialogVisible = false },
            ) {
                val url = data.optString("updateAddress_android").ifBlank { data.optString("updateAddress") }.trim()
                if (url.isBlank()) {
                    Toast.makeText(activity, "当前没有可用的 Android 下载地址", Toast.LENGTH_LONG).show()
                    false
                } else {
                    openUpdatePage(activity, data, remote.removePrefix("v"), local, flag == 2, false)
                    true
                }
            }
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
