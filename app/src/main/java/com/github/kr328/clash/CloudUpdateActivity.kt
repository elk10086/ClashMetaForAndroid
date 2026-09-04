package com.github.kr328.clash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.common.constants.ClientUserAgent
import com.github.kr328.clash.design.util.resolveThemedColor
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class CloudUpdateActivity : AppCompatActivity() {
    private val forced by lazy { intent.getBooleanExtra(EXTRA_FORCED, false) }
    private val latest by lazy { intent.getBooleanExtra(EXTRA_LATEST, false) }
    private val downloadUrl by lazy { intent.getStringExtra(EXTRA_URL).orEmpty().trim() }
    private var downloadJob: Job? = null
    private var pendingInstaller: File? = null
    private lateinit var actionButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        applyCloudTheme()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(forced) {
            override fun handleOnBackPressed() = Unit
        })
        setContentView(buildPage())
    }

    override fun onResume() {
        super.onResume()
        val file = pendingInstaller ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            pendingInstaller = null
            launchInstaller(file)
        }
    }

    override fun onDestroy() {
        if (isFinishing) downloadJob?.cancel()
        super.onDestroy()
    }

    private fun buildPage(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color(android.R.attr.windowBackground))
        fitsSystemWindows = true
        addView(header(), LinearLayout.LayoutParams(-1, dp(56)))
        addView(ScrollView(this@CloudUpdateActivity).apply {
            isVerticalScrollBarEnabled = false
            addView(details())
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!latest || downloadJob != null) addView(bottomBar())
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(16), 0)
        if (!forced) addView(ImageView(this@CloudUpdateActivity).apply {
            setImageResource(R.drawable.cloud_back); setColorFilter(color(com.google.android.material.R.attr.colorOnSurface))
            contentDescription = "返回"; setPadding(dp(13), dp(13), dp(13), dp(13)); isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@CloudUpdateActivity).apply {
            text = if (forced) "请更新版本" else "版本更新"; textSize = 18f; gravity = Gravity.CENTER_VERTICAL
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface)); setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun details() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(24))
        val remote = intent.getStringExtra(EXTRA_REMOTE).orEmpty()
        val local = intent.getStringExtra(EXTRA_LOCAL).orEmpty()
        addView(label(
            when {
                forced -> "当前版本过低，请下载安装新版本后继续使用。"
                latest -> "当前已是最新版本"
                else -> "发现新版本 v$remote"
            }, 20f, true,
        ))
        addView(label("远端版本：v${remote.ifBlank { local }}   本地版本：v$local", 13f).apply {
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(surfaceVariant(), 12)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        addView(label("更新内容", 16f, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(8) })
        val logs = intent.getStringExtra(EXTRA_LOGS).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (logs.isEmpty()) addView(label(if (latest) "当前版本无新增更新内容。" else "暂无更新说明", 14f))
        else logs.forEach { addView(label("• $it", 14f), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }) }
        addView(label("Android 下载地址", 16f, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(8) })
        addView(LinearLayout(this@CloudUpdateActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(surface(), 12, outline())
            addView(TextView(this@CloudUpdateActivity).apply {
                text = downloadUrl.ifBlank { "暂无可用下载地址" }; textSize = 13f; setTextColor(color(com.google.android.material.R.attr.colorOnSurface)); setTextIsSelectable(true)
            })
            addView(LinearLayout(this@CloudUpdateActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                addView(smallAction("复制地址") { copyAddress() })
                addView(smallAction("浏览器打开") { openAddress() }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginStart = dp(10) })
            }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })
        })
        addView(label(
            if (downloadUrl.isBlank()) "温馨提示：当前没有可用下载地址，请稍后重试。"
            else "温馨提示：下载期间可取消任务；安装前会验证应用包名与签名证书。",
            13f,
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }

    private fun bottomBar() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(16)); setBackgroundColor(surface())
        progressLabel = label("准备下载", 13f).apply { visibility = View.GONE }
        progress = ProgressBar(this@CloudUpdateActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
        }
        actionButton = label("在线升级（下载并安装）", 15f, true).apply {
            gravity = Gravity.CENTER; setTextColor(color(com.google.android.material.R.attr.colorOnPrimary))
            background = rounded(color(com.google.android.material.R.attr.colorPrimary), 24)
            isClickable = downloadUrl.isNotBlank(); alpha = if (isClickable) 1f else .45f
            setOnClickListener { if (downloadJob == null) confirmDownload() else cancelDownload() }
        }
        addView(progressLabel)
        addView(progress, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(7); bottomMargin = dp(10) })
        addView(actionButton, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun confirmDownload() {
        AlertDialog.Builder(this).setTitle("确认升级")
            .setMessage("即将下载更新包并打开系统安装程序。安装完成后请重新打开应用。")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续升级") { _, _ -> startDownload() }
            .show()
    }

    private fun startDownload() {
        if (downloadJob != null) return
        downloadJob = lifecycleScope.launch {
            actionButton.text = "取消下载"; progress.visibility = View.VISIBLE; progressLabel.visibility = View.VISIBLE
            val targetDir = File(externalCacheDir ?: cacheDir, "updates").apply { mkdirs() }
            val target = File(targetDir, "${BuildConfig.BRAND_ID}-${intent.getStringExtra(EXTRA_REMOTE).orEmpty()}.apk")
            try {
                withContext(Dispatchers.IO) {
                    try {
                        download(downloadUrl, target, Proxy.NO_PROXY)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        target.delete()
                        download(downloadUrl, target, Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7890)))
                    }
                    verifyPackage(target)
                }
                progress.progress = 1000; progressLabel.text = "下载完成，正在打开安装程序…"
                requestInstall(target)
            } catch (_: CancellationException) {
                target.delete(); Toast.makeText(this@CloudUpdateActivity, "已取消下载", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                target.delete(); Toast.makeText(this@CloudUpdateActivity, "下载失败：${e.message ?: "请稍后重试"}", Toast.LENGTH_LONG).show()
            } finally {
                downloadJob = null; actionButton.text = "在线升级（下载并安装）"
                progress.visibility = View.GONE; progressLabel.visibility = View.GONE
            }
        }
    }

    private fun cancelDownload() = downloadJob?.cancel()

    private suspend fun download(value: String, target: File, proxy: Proxy) {
        val connection = URL(value).openConnection(proxy) as HttpURLConnection
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection.disconnect()
        }
        connection.instanceFollowRedirects = true; connection.connectTimeout = 20_000; connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", ClientUserAgent.value(this))
        try {
            if (connection.responseCode !in 200..299) error("服务器返回 ${connection.responseCode}")
            val total = connection.contentLengthLong
            var received = 0L; var sampleBytes = 0L; var sampleAt = System.currentTimeMillis()
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count); received += count
                        val now = System.currentTimeMillis()
                        if (now - sampleAt >= 500 || total > 0 && received >= total) {
                            val speed = (received - sampleBytes) * 1000.0 / (now - sampleAt).coerceAtLeast(1)
                            sampleAt = now; sampleBytes = received
                            val ratio = if (total > 0) (received * 1000 / total).toInt().coerceIn(0, 1000) else 0
                            withContext(Dispatchers.Main) {
                                progress.isIndeterminate = total <= 0; if (total > 0) progress.progress = ratio
                                progressLabel.text = "更新包 ${if (total > 0) "${ratio / 10}%" else formatBytes(received)}   ${formatSpeed(speed)}"
                            }
                        }
                    }
                }
            }
            if (target.length() <= 0) error("下载文件为空")
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File) {
        val info = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageArchiveInfo(file.path, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        } else packageManager.getPackageArchiveInfo(file.path, android.content.pm.PackageManager.GET_SIGNATURES))
            ?: error("无法识别更新包")
        if (info.packageName != packageName) error("更新包应用标识不匹配")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
        val fingerprint = signatures?.firstOrNull()?.toByteArray()?.let {
            MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02X".format(byte) }
        }.orEmpty()
        if (fingerprint != RELEASE_CERTIFICATE_SHA256) error("更新包签名校验失败")
    }

    private fun requestInstall(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingInstaller = file
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } else launchInstaller(file)
    }

    private fun launchInstaller(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun copyAddress() {
        if (downloadUrl.isBlank()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("下载地址", downloadUrl))
        Toast.makeText(this, "下载地址已复制", Toast.LENGTH_SHORT).show()
    }

    private fun openAddress() {
        if (downloadUrl.isBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))) }
            .onFailure { Toast.makeText(this, "无法打开下载地址", Toast.LENGTH_LONG).show() }
    }

    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun smallAction(value: String, click: () -> Unit) = label(value, 13f, true).apply {
        gravity = Gravity.CENTER; setTextColor(color(com.google.android.material.R.attr.colorPrimary)); isClickable = true; setOnClickListener { click() }
        setPadding(dp(10), 0, dp(10), 0)
    }
    private fun color(attr: Int) = resolveThemedColor(attr)
    private fun surface() = color(com.google.android.material.R.attr.colorSurface)
    private fun surfaceVariant() = color(com.google.android.material.R.attr.colorSurfaceVariant)
    private fun outline() = color(com.google.android.material.R.attr.colorOutline)
    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }
    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", value / 1024.0 / 1024.0)
        value >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", value / 1024.0)
        else -> "$value B"
    }
    private fun formatSpeed(value: Double): String = if (value >= 1024 * 1024) String.format(java.util.Locale.US, "%.2f MB/s", value / 1024 / 1024) else String.format(java.util.Locale.US, "%.1f KB/s", value / 1024)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_REMOTE = "remote_version"
        const val EXTRA_LOCAL = "local_version"
        const val EXTRA_LOGS = "update_logs"
        const val EXTRA_URL = "download_url"
        const val EXTRA_FORCED = "forced"
        const val EXTRA_LATEST = "latest"
        private const val RELEASE_CERTIFICATE_SHA256 = "6F431690613AE76C2F8FF5DE0AE9656F0623CE528F3F107B2AB5A005D0ED75ED"
    }
}
