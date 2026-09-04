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
import com.github.kr328.clash.common.constants.ClientUserAgent
import com.github.kr328.clash.cloud.CloudReleaseManager
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.PreviewService
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    private val connectionClock by lazy { getSharedPreferences("cloud_connection_clock", MODE_PRIVATE) }
    private val membershipCache by lazy { getSharedPreferences("cloud_membership_cache", MODE_PRIVATE) }
    private val subscriptionMutex = Mutex()
    private var networkDetectionJob: Job? = null
    private var cloudSubscription: CloudSubscription? = null
    private var cloudSyncError: String? = null
    private var previewCoreRunning = false
    private var announcementData: JSONObject? = null

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

        cloudSubscription = loadCachedCloudSubscription()
        design.fetch()
        if (cachedNetworkResult == null) requestNetworkDetection(design)
        launch {
            runCatching { CloudReleaseManager.checkOnLaunch(this@MainActivity) }.getOrNull()?.let { data ->
                announcementData = data
                design.setAnnouncement(
                    data.optString("title").ifBlank { "公告" },
                    CloudReleaseManager.isDashboardAnnouncementUnread(this@MainActivity, data),
                )
            }
        }
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
                            if (it == Event.ClashStop ||
                                it == Event.ClashStart ||
                                it == Event.ProfileLoaded ||
                                it == Event.ProfileChanged
                            ) {
                                requestNetworkDetection(design, 500)
                            }
                            if (it == Event.ClashStop && CloudSession(this@MainActivity).loggedIn) {
                                this@MainActivity.launch {
                                    delay(400)
                                    startPreviewCore()
                                    design.fetch()
                                }
                            }
                            if (it == Event.ActivityStart && CloudSession(this@MainActivity).loggedIn) {
                                val paymentState = getSharedPreferences("cloud_payment", MODE_PRIVATE)
                                val paymentRefreshPending = paymentState.getBoolean("refresh_pending", false)
                                if (paymentRefreshPending) paymentState.edit().remove("refresh_pending").apply()
                                if (cloudSubscription == null || paymentRefreshPending) {
                                    this@MainActivity.launch { refreshCloudSubscription(design) }
                                }
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
                        MainDesign.Request.OpenAnnouncement -> announcementData?.let { data ->
                            CloudReleaseManager.showDashboardAnnouncement(this@MainActivity, data) {
                                launch { design.setAnnouncement("", false) }
                            }
                        }
                        MainDesign.Request.DetectNetwork -> requestNetworkDetection(design)
                        MainDesign.Request.ChangeMode -> {
                            val mode = design.takeSelectedMode()
                            withClash {
                                val override = queryOverride(Clash.OverrideSlot.Session)
                                override.mode = mode
                                patchOverride(Clash.OverrideSlot.Session, override)
                            }
                            design.setMode(mode)
                            requestNetworkDetection(design, 500)
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
        setNetworkResult(cachedNetworkResult ?: getString(DesignR.string.cloud_dashboard_detection_hint))

        val state = withClash {
            queryTunnelState()
        }
        setMode(state.mode)

        val currentLine = if (clashRunning || previewCoreRunning) {
            runCatching {
                withClash {
                    val names = queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                    names.firstOrNull()?.let { queryProxyGroup(it, uiStore.proxySort).now }
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }
        } else null
        if (currentLine != null) cachedLineName = currentLine
        // Switching between the VPN core and PreviewService has a short period where
        // neither core exposes proxy groups. Keep the last valid selection instead of
        // replacing it with a loading label on every start/stop action.
        setLineName(currentLine ?: cachedLineName ?: "正在加载线路…")

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
                "",
                "",
                0,
            )
            else -> {
                val expired = subscription.expiresAt > 0 && subscription.expiresAt * 1000 < System.currentTimeMillis()
                val transferFull = subscription.total > 0 && subscription.used >= subscription.total
                val abnormal = subscription.planId == null || expired || transferFull
                val expiry = when {
                    subscription.expiresAt <= 0 -> "不限时长"
                    expired -> "已过期"
                    else -> java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(subscription.expiresAt * 1000))
                }
                val progress = if (subscription.total > 0) {
                    (subscription.used * 100L / subscription.total).coerceIn(0, 100).toInt()
                } else 0
                setCloudAccount(
                    subscription.plan.ifBlank { if (abnormal) "未开通套餐" else "套餐" },
                    "到期时间：$expiry",
                    "${formatBytes(subscription.used)} / ${formatBytes(subscription.total)}",
                    progress,
                )
            }
        }
    }

    private suspend fun refreshCloudSubscription(design: MainDesign) {
        if (!CloudSession(this).loggedIn) return
        try {
            subscriptionMutex.withLock {
                var failure: Throwable? = null
                var subscription: CloudSubscription? = null
                val cloudApi = CloudApi(this@MainActivity)
                repeat(3) { attempt ->
                    if (subscription == null) {
                        runCatching { cloudApi.subscription() }
                            .onSuccess { subscription = it }
                            .onFailure { failure = it }
                        if (subscription == null && attempt < 2) delay(2_000)
                    }
                }
                val loaded = subscription ?: throw failure ?: IllegalStateException("会员信息加载失败")
                cloudSubscription = loaded
                cloudSyncError = null
                saveCachedCloudSubscription(loaded)
                design.fetchCloudAccount()
                // Membership display and profile import are independent in hle_fi. A profile
                // refresh failure must not hide membership data that was fetched successfully.
                val subscriptionUrls = cloudApi.subscriptionUrls(loaded.subscribeUrl)
                var importFailure: Throwable? = if (subscriptionUrls.isEmpty()) {
                    IllegalStateException("订阅地址无效")
                } else null
                for (url in subscriptionUrls) {
                    val imported = runCatching { importCloudProfile(url) }
                        .onFailure { importFailure = it }
                        .isSuccess
                    if (imported) {
                        cloudApi.rememberSuccessfulSubscriptionUrl(url)
                        importFailure = null
                        break
                    }
                }
                if (importFailure != null) cloudSyncError = importFailure?.message ?: "订阅内容导入失败"
                design.fetch()
            }
        } catch (e: Throwable) {
            if (cloudSubscription == null) cloudSyncError = e.message ?: "未知错误"
            design.fetchCloudAccount()
        }
    }

    private fun loadCachedCloudSubscription(): CloudSubscription? {
        val sessionEmail = CloudSession(this).email.trim().lowercase()
        if (sessionEmail.isBlank()) return null
        return runCatching {
            val json = JSONObject(membershipCache.getString("membership", "").orEmpty())
            if (json.optString("account_email") != sessionEmail) return null
            CloudSubscription(
                email = json.optString("email", sessionEmail),
                planId = if (json.isNull("plan_id")) null else json.optLong("plan_id"),
                plan = json.optString("plan"),
                subscribeUrl = json.optString("subscribe_url"),
                used = json.optLong("used"),
                total = json.optLong("total"),
                expiresAt = json.optLong("expires_at"),
                balance = json.optLong("balance"),
            )
        }.getOrNull()
    }

    private fun saveCachedCloudSubscription(subscription: CloudSubscription) {
        val json = JSONObject()
            .put("account_email", CloudSession(this).email.trim().lowercase())
            .put("email", subscription.email)
            .put("plan_id", subscription.planId ?: JSONObject.NULL)
            .put("plan", subscription.plan)
            .put("subscribe_url", subscription.subscribeUrl)
            .put("used", subscription.used)
            .put("total", subscription.total)
            .put("expires_at", subscription.expiresAt)
            .put("balance", subscription.balance)
        membershipCache.edit().putString("membership", json.toString()).apply()
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
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var index = 0
        while (amount >= 1024 && index < units.lastIndex) {
            amount /= 1024
            index++
        }
        return String.format(java.util.Locale.US, if (index < 2) "%.0f%s" else "%.2f%s", amount, units[index])
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

    private fun requestNetworkDetection(design: MainDesign, delayMillis: Long = 0L) {
        networkDetectionJob?.cancel()
        networkDetectionJob = launch {
            if (delayMillis > 0) delay(delayMillis)
            detectNetwork(design)
        }
    }

    private suspend fun detectNetwork(design: MainDesign) {
        design.setNetworkResult(getString(DesignR.string.cloud_dashboard_detecting))
        val ipInfo = queryFirstIpInfo()
        val result = ipInfo?.let { (ip, country) ->
            listOf(countryFlag(country), ip).filter { it.isNotBlank() }.joinToString(" ")
        } ?: getString(DesignR.string.cloud_dashboard_detection_timeout)
        cachedNetworkResult = result
        design.setNetworkResult(result)
    }

    /** Matches free_time_new: race all providers directly and keep the first valid response. */
    private suspend fun queryFirstIpInfo(): Pair<String, String>? = coroutineScope {
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
                    val connection = URL(endpoint).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", ClientUserAgent.value(this@MainActivity))
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
        try {
            while (requests.isNotEmpty()) {
                val (completedIndex, value) = select<Pair<Int, Pair<String, String>?>> {
                    requests.forEachIndexed { index, request ->
                        request.onAwait { index to it }
                    }
                }
                requests.removeAt(completedIndex)
                if (value != null) return@coroutineScope value
            }
            null
        } finally {
            requests.forEach { it.cancel() }
        }
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

    private companion object {
        /** Riverpod's keepAlive equivalent for Activity-backed bottom-tab navigation. */
        @Volatile
        var cachedNetworkResult: String? = null

        /** Retains the selected route while the service core is being restarted. */
        @Volatile
        var cachedLineName: String? = null
    }
}
