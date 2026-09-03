package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.cloud.CloudApi
import com.github.kr328.clash.cloud.CloudSession
import com.github.kr328.clash.cloud.CloudSubscription
import com.github.kr328.clash.cloud.CloudReleaseManager
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.PreviewService
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    private val connectionClock by lazy { getSharedPreferences("cloud_connection_clock", MODE_PRIVATE) }
    private val subscriptionMutex = Mutex()
    private val networkDetectionMutex = Mutex()
    private var cloudSubscription: CloudSubscription? = null
    private var cloudSyncError: String? = null
    private var previewCoreRunning = false

    override suspend fun main() {
        if (!requireCloudLogin()) {
            finish()
            return
        }

        val design = MainDesign(this)

        setContentDesign(design)
        withContext(Dispatchers.Main) {
            CloudBottomNavigation.attach(this@MainActivity, CloudBottomNavigation.Tab.DASHBOARD)
        }

        design.fetch()
        launch { detectNetwork(design) }
        launch { runCatching { CloudReleaseManager.checkOnLaunch(this@MainActivity) } }
        if (CloudSession(this).loggedIn) launch { refreshCloudSubscription(design) }

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> {
                            syncConnectionClock()
                            design.fetch()
                            if (it == Event.ActivityStart ||
                                it == Event.ClashStop ||
                                it == Event.ClashStart ||
                                it == Event.ProfileLoaded ||
                                it == Event.ProfileChanged
                            ) {
                                this@MainActivity.launch {
                                    delay(500)
                                    detectNetwork(design)
                                }
                            }
                            if (it == Event.ClashStop && CloudSession(this@MainActivity).loggedIn) {
                                this@MainActivity.launch {
                                    delay(400)
                                    startPreviewCore()
                                    design.fetch()
                                }
                            }
                            if (it == Event.ActivityStart &&
                                CloudSession(this@MainActivity).loggedIn &&
                                cloudSubscription == null
                            ) {
                                this@MainActivity.launch { refreshCloudSubscription(design) }
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning) {
                                clearConnectionClock()
                                stopClashService()
                            } else {
                                startConnectionClock()
                                stopPreviewCore()
                                delay(400)
                                design.startClash()
                            }
                        }
                        MainDesign.Request.OpenProxy -> {
                            if (clashRunning || previewCoreRunning) openPrimary(ProxyActivity::class.java)
                            else design.showToast(DesignR.string.cloud_lines_connect_first, ToastDuration.Long)
                        }
                        MainDesign.Request.OpenProfiles ->
                            openPrimary(ProfilesActivity::class.java)
                        MainDesign.Request.OpenSettings ->
                            openPrimary(CloudMineActivity::class.java)
                        MainDesign.Request.OpenPlans ->
                            startActivity(Intent(this@MainActivity, CloudBusinessActivity::class.java).putExtra("type", "plans"))
                        MainDesign.Request.DetectNetwork -> launch { detectNetwork(design) }
                        MainDesign.Request.ChangeMode -> {
                            val mode = design.takeSelectedMode()
                            withClash {
                                val override = queryOverride(Clash.OverrideSlot.Session)
                                override.mode = mode
                                patchOverride(Clash.OverrideSlot.Session, override)
                            }
                            design.setMode(mode)
                            launch {
                                delay(500)
                                detectNetwork(design)
                            }
                        }
                    }
                }
                if (clashRunning || previewCoreRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun requireCloudLogin(): Boolean {
        if (CloudSession(this).loggedIn) return true
        val result = startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            CloudAuthActivity::class.intent,
        )
        return result.resultCode == RESULT_OK && CloudSession(this).loggedIn
    }

    private suspend fun MainDesign.fetch() {
        syncConnectionClock()
        setClashRunning(clashRunning)
        setConnectionTime(formatConnectionTime())
        setNetworkResult(getString(DesignR.string.cloud_dashboard_detection_hint))

        val state = withClash {
            queryTunnelState()
        }
        setMode(state.mode)

        if (clashRunning || previewCoreRunning) {
            val line = withClash {
                val names = queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                names.firstOrNull()?.let { queryProxyGroup(it, uiStore.proxySort).now }
            }
            setLineName(line?.takeIf { it.isNotBlank() } ?: "正在加载线路…")
        } else {
            setLineName("正在加载线路…")
        }

        withProfile {
            setProfileName(queryActive()?.name)
        }
        fetchCloudAccount()
    }

    private suspend fun MainDesign.fetchCloudAccount() {
        val session = CloudSession(this@MainActivity)
        val subscription = cloudSubscription
        when {
            !session.loggedIn -> setCloudAccount("未登录", "登录后自动同步订阅", "", 0)
            cloudSyncError != null -> setCloudAccount(
                "会员信息加载失败",
                "订阅同步失败：${cloudSyncError}",
                "",
                0,
            )
            subscription == null -> setCloudAccount(
                getString(DesignR.string.cloud_membership_loading),
                session.email,
                "",
                0,
            )
            else -> {
                val expiry = if (subscription.expiresAt > 0) {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(java.util.Date(subscription.expiresAt * 1000))
                } else "长期有效"
                val progress = if (subscription.total > 0) {
                    (subscription.used * 100L / subscription.total).coerceIn(0, 100).toInt()
                } else 0
                setCloudAccount(
                    subscription.plan.ifBlank { "未开通套餐" },
                    "到期时间：$expiry",
                    "已用 ${formatBytes(subscription.used)} / ${formatBytes(subscription.total)}",
                    progress,
                )
            }
        }
    }

    private suspend fun refreshCloudSubscription(design: MainDesign) {
        if (!CloudSession(this).loggedIn) return
        try {
            subscriptionMutex.withLock {
                val subscription = CloudApi(this).subscription()
                cloudSubscription = subscription
                cloudSyncError = null
                importCloudProfile(subscription.subscribeUrl)
                design.fetch()
            }
        } catch (e: Throwable) {
            cloudSyncError = e.message ?: "未知错误"
            design.fetchCloudAccount()
        }
    }

    private suspend fun importCloudProfile(url: String) = withProfile {
        val session = CloudSession(this@MainActivity)
        val existing = session.profileUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let { queryByUUID(it) }
        val uuid = if (existing == null) {
            create(Profile.Type.Url, "云端订阅", url).also { session.profileUuid = it.toString() }
        } else {
            patch(existing.uuid, "云端订阅", url, TimeUnit.HOURS.toMillis(6), existing.ageSecretKey)
            existing.uuid
        }
        commit(uuid)
        val imported = queryByUUID(uuid)
            ?: throw IllegalStateException("订阅配置未保存")
        if (!imported.imported || imported.pending) {
            throw IllegalStateException("订阅内容导入失败")
        }
        setActive(imported)

        // Flutter 版本的 applyProfile(force: true) 会立即重载核心。原生版本在新建
        // Profile 时 commit 广播早于 setActive，运行中的核心可能错过有效加载事件，
        // 因此需要重启一次服务，让 ConfigurationModule 从 active profile 重新加载。
        if (clashRunning) {
            stopClashService()
            delay(500)
            startClashService()
        } else {
            restartPreviewCore()
        }
    }

    private fun startPreviewCore() {
        if (previewCoreRunning || clashRunning) return
        previewCoreRunning = true
        startForegroundServiceCompat(PreviewService::class.intent)
    }

    private fun stopPreviewCore() {
        if (!previewCoreRunning) return
        stopService(PreviewService::class.intent)
        previewCoreRunning = false
    }

    private suspend fun restartPreviewCore() {
        stopPreviewCore()
        delay(300)
        startPreviewCore()
    }

    private fun formatBytes(value: Long): String {
        val gib = value / 1024.0 / 1024.0 / 1024.0
        return if (gib >= 1) String.format(java.util.Locale.US, "%.1f GB", gib)
        else String.format(java.util.Locale.US, "%.1f MB", value / 1024.0 / 1024.0)
    }

    private suspend fun MainDesign.fetchTraffic() {
        setConnectionTime(formatConnectionTime())
        withClash {
            setForwarded(queryTrafficTotal())
            setTrafficSpeed(queryTrafficNow())
        }
    }

    private fun syncConnectionClock() {
        if (clashRunning) {
            if (connectionClock.getLong("started_at", 0L) <= 0L) startConnectionClock()
        } else {
            clearConnectionClock()
        }
    }

    private fun startConnectionClock() {
        if (connectionClock.getLong("started_at", 0L) <= 0L) {
            connectionClock.edit().putLong("started_at", System.currentTimeMillis()).apply()
        }
    }

    private fun clearConnectionClock() {
        connectionClock.edit().remove("started_at").apply()
    }

    private fun formatConnectionTime(): String {
        if (!clashRunning) return "00:00:00"
        val startedAt = connectionClock.getLong("started_at", 0L)
        if (startedAt <= 0L) return "00:00:00"
        val elapsed = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        if (elapsed >= 100L * 3600L) return "99:59:59"
        val hours = elapsed / 3600L
        val minutes = elapsed / 60L % 60L
        val seconds = elapsed % 60L
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private suspend fun detectNetwork(design: MainDesign) {
        networkDetectionMutex.withLock {
            design.setNetworkResult(getString(DesignR.string.cloud_dashboard_detecting))
            // PreviewService only loads groups for browsing. Before the user starts
            // the VPN, network detection must report the device's direct public IP.
            val ipInfo = queryFirstIpInfo(useClashProxy = clashRunning)
            val result = ipInfo?.let { (ip, country) ->
                listOf(countryFlag(country), ip).filter { it.isNotBlank() }.joinToString(" ")
            } ?: getString(DesignR.string.cloud_dashboard_detection_timeout)
            design.setNetworkResult(result)
        }
    }

    private suspend fun queryFirstIpInfo(useClashProxy: Boolean): Pair<String, String>? = coroutineScope {
        val sources = listOf(
            "https://ipwho.is" to Pair("ip", "country_code"),
            "https://api.myip.com" to Pair("ip", "cc"),
            "https://ipapi.co/json" to Pair("ip", "country_code"),
            "https://ident.me/json" to Pair("ip", "cc"),
            "http://ip-api.com/json" to Pair("query", "countryCode"),
            "https://api.ip.sb/geoip" to Pair("ip", "country_code"),
            "https://ipinfo.io/json" to Pair("ip", "country"),
        )
        val requests = sources.map { (endpoint, fields) ->
            async(Dispatchers.IO) {
                runCatching {
                    val connection = if (useClashProxy) {
                        val clashProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7890))
                        URL(endpoint).openConnection(clashProxy)
                    } else {
                        URL(endpoint).openConnection()
                    } as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.setRequestProperty("Accept", "application/json")
                    try {
                        if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        val ip = json.optString(fields.first)
                        val country = json.optString(fields.second)
                        if (ip.isBlank()) null else ip to country
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
            }
        }.toMutableList()
        while (requests.any { !it.isCompleted }) {
            requests.firstOrNull { it.isCompleted }?.let { completed ->
                val value = completed.await()
                requests.remove(completed)
                if (value != null) {
                    requests.forEach { it.cancel() }
                    return@coroutineScope value
                }
            }
            delay(20)
        }
        requests.awaitAll().firstOrNull { it != null }
    }

    private fun countryFlag(code: String): String {
        if (code.length != 2) return ""
        return code.uppercase().map { char ->
            String(Character.toChars(0x1F1E6 + char.code - 'A'.code))
        }.joinToString("")
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setupShortcuts()
    }

    private fun setupShortcuts() {
        // Skip dynamic shortcut setup when the app icon is hidden.
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }

    private fun openPrimary(target: Class<*>) {
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        overridePendingTransition(0, 0)
    }
}
