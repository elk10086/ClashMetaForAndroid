package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.common.util.firstSuccessfulHttp
import com.github.kr328.clash.common.util.readCancellable
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.PowerRequestGeneration
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.cloud.CloudApi
import com.github.kr328.clash.cloud.CloudSession
import com.github.kr328.clash.cloud.CLOUD_PROFILE_NAME
import com.github.kr328.clash.cloud.activateAndPruneCloudProfile
import com.github.kr328.clash.cloud.adoptAndPruneCloudProfile
import com.github.kr328.clash.cloud.resolveCloudProfile
import com.github.kr328.clash.cloud.withCloudProfileSync
import com.github.kr328.clash.cloud.CloudSubscription
import com.github.kr328.clash.cloud.CloudSubscriptionCache
import com.github.kr328.clash.cloud.CloudMembershipWarning
import com.github.kr328.clash.cloud.dashboardWarnings
import com.github.kr328.clash.common.constants.ClientUserAgent
import com.github.kr328.clash.cloud.CloudReleaseManager
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.PreviewService
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeoutOrNull
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.service.remote.IFetchObserver

class MainActivity : BaseActivity<MainDesign>() {
    private val connectionClock by lazy { getSharedPreferences("cloud_connection_clock", MODE_PRIVATE) }
    private val subscriptionMutex = Mutex()
    private val powerRequestGeneration = PowerRequestGeneration()
    private var networkDetectionJob: Job? = null
    private var proxyOpenJob: Job? = null
    private var previewRestartJob: Job? = null
    private var vpnStartPending = false
    private var coreTransitionUntilMillis = 0L
    private var cloudSubscription: CloudSubscription? = null
    private var cloudSyncError: String? = null
    private var lineLoadingStage: String? = null
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

        cloudSubscription = CloudSubscriptionCache.peek(this)
        cachedLineName = loadCachedLineName()
        launch {
            for (request in design.powerRequests) toggleClash(design)
        }
        design.fetch()
        launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    CloudSubscriptionCache.changes.collect {
                        design.fetchCloudAccount()
                    }
                }
                // Crossing the three-day threshold must not wait for tomorrow's API refresh.
                // Read only the local snapshot, even while the VPN is stopped.
                while (isActive) {
                    delay(60_000L)
                    design.fetchCloudAccount()
                }
            }
        }
        if (cachedNetworkResult == null) requestNetworkDetection(design, automatic = true)
        launch {
            runCatching { CloudReleaseManager.checkOnLaunch(this@MainActivity) }
            while (isActive) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    CloudReleaseManager.refreshDashboardRelease(this@MainActivity)?.let { data ->
                        announcementData = data
                        renderAnnouncement(design, data)
                        CloudReleaseManager.showDashboardUpdate(this@MainActivity, data)
                        CloudReleaseManager.showNewDashboardAnnouncement(this@MainActivity, data) {
                            launch { renderAnnouncement(design, data) }
                        }
                    }
                }
                // Only checks the persisted deadline here; network requests occur
                // once per day, with a 15-minute retry after a failed request.
                delay(30_000L)
            }
        }
        CloudSession(this).takeIf { it.loggedIn }?.let { session ->
            launch {
                val cloudProfileReady = withCloudProfileSync {
                    withProfile {
                        resolveCloudProfile(session, requireReady = true)?.also { profile ->
                            adoptAndPruneCloudProfile(session, profile)
                        }
                    }
                } != null
                if (!cloudProfileReady) {
                    refreshCloudSubscription(design)
                } else {
                    refreshDdnsOnColdStart(design)
                }
            }
        }

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
                                previewRestartJob?.cancel()
                                previewRestartJob = this@MainActivity.launch {
                                    delay(400)
                                    startPreviewCore()
                                    design.fetch()
                                }
                            }
                            if (it == Event.ActivityStart && CloudSession(this@MainActivity).loggedIn) {
                                val paymentState = getSharedPreferences("cloud_payment", MODE_PRIVATE)
                                val paymentRefreshPending = paymentState.getBoolean("refresh_pending", false)
                                if (paymentRefreshPending) paymentState.edit().remove("refresh_pending").apply()
                                if (paymentRefreshPending) {
                                    // The initial login sync above already refreshes membership.
                                    // Ordinary foreground refreshes are handled by the shared
                                    // 24-hour scheduler; only a completed payment needs a force refresh.
                                    this@MainActivity.launch { refreshCloudSubscription(design, forceRefresh = true) }
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> Unit // handled by powerRequests
                        MainDesign.Request.OpenProxy -> requestOpenProxy(design)
                        MainDesign.Request.OpenProfiles -> {
                            if (canViewConfigurationModule()) {
                                openPrimary(ProfilesActivity::class.java)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            openPrimary(CloudMineActivity::class.java)
                        MainDesign.Request.OpenPlans ->
                            startActivity(Intent(this@MainActivity, CloudBusinessActivity::class.java).putExtra("type", "plans"))
                        MainDesign.Request.RefreshMembership -> this@MainActivity.launch {
                            design.showToast(DesignR.string.cloud_plan_refreshing, ToastDuration.Short)
                            refreshCloudSubscription(design, refreshProfile = false, forceRefresh = true)
                            design.showToast(
                                if (isCloudPlanExpired()) DesignR.string.cloud_plan_still_expired
                                else DesignR.string.cloud_plan_refreshed,
                                ToastDuration.Short,
                            )
                        }
                        MainDesign.Request.OpenAnnouncement -> announcementData?.let { data ->
                            CloudReleaseManager.showDashboardAnnouncement(this@MainActivity, data) {
                                launch { renderAnnouncement(design, data) }
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

    private suspend fun toggleClash(design: MainDesign) {
        powerRequestGeneration.onUserRequest()
        if (!clashRunning && isCloudPlanExpired()) {
            design.cancelPowerTransition()
            design.showToast(DesignR.string.cloud_plan_expired_hint, ToastDuration.Long)
            return
        }
        proxyOpenJob?.cancel()
        proxyOpenJob = null
        previewRestartJob?.cancel()
        design.beginPowerTransition(starting = !clashRunning)
        coreTransitionUntilMillis = SystemClock.elapsedRealtime() + 5_000L
        if (clashRunning) {
            clearConnectionClock()
            stopClashService()
        } else {
            vpnStartPending = true
            startConnectionClock()
            stopPreviewCore()
            if (!design.startClash()) {
                vpnStartPending = false
                clearConnectionClock()
                startPreviewCore()
            }
        }
    }

    override fun onStarted() {
        vpnStartPending = false
        launch { design?.setClashRunning(true) }
        super.onStarted()
    }

    override fun onStopped(cause: String?) {
        vpnStartPending = false
        launch {
            // Let setClashRunning decide whether this is a normal close (play the
            // ring's return animation) or a failed start (softly dismiss it).
            design?.setClashRunning(false)
        }
        super.onStopped(cause)
    }

    override fun onServiceRecreated() {
        vpnStartPending = false
        previewCoreRunning = false
        launch { design?.cancelPowerTransition(); design?.setClashRunning(false) }
        super.onServiceRecreated()
    }

    private suspend fun MainDesign.fetch() {
        val persistedLine = loadCachedLineName()?.also { cachedLineName = it }
        val lineSelectionChanged = consumeNetworkRefreshPending()
        syncConnectionClock()
        setClashRunning(clashRunning)
        setConnectionTime(formatConnectionTime())
        setNetworkResult(cachedNetworkResult ?: getString(DesignR.string.cloud_dashboard_auto_detecting))

        val state = withClash {
            queryTunnelState()
        }
        setMode(state.mode)

        val currentLine = if (clashRunning || previewCoreRunning) queryCurrentLine() else null
        when {
            lineSelectionChanged && persistedLine != null -> {
                if (currentLine != persistedLine) setLineName(persistedLine)
                refreshLineAfterSelection(this, persistedLine)
            }
            currentLine != null -> {
                lineLoadingStage = null
                rememberLineName(currentLine)
                setLineName(currentLine)
            }
            lineLoadingStage != null -> setLineLoading(lineLoadingStage!!)
            clashRunning || previewCoreRunning || cachedLineName == null -> setLineLoading("正在加载线路…")
            else -> setLineName(cachedLineName!!)
        }

        if (lineSelectionChanged && persistedLine == null && (clashRunning || previewCoreRunning)) {
            requestNetworkDetection(this, 500)
        }

        withProfile {
            setProfileName(queryActive()?.name)
        }
        fetchCloudAccount()
    }

    private suspend fun MainDesign.fetchCloudAccount() {
        val session = CloudSession(this@MainActivity)
        val subscription = CloudSubscriptionCache.peek(this@MainActivity).also { cloudSubscription = it }
        when {
            !session.loggedIn -> setCloudAccount("未登录", "登录后自动同步订阅", "", 0, false)
            subscription == null -> setCloudAccount(
                if (cloudSyncError == null) getString(DesignR.string.cloud_membership_loading) else "会员信息加载失败",
                cloudSyncError?.let { "订阅同步失败：$it" }.orEmpty(),
                "", 0, false,
            )
            else -> {
                val expired = isCloudPlanExpired(subscription)
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
                val warning = subscription.dashboardWarnings(System.currentTimeMillis())
                    .joinToString("\n") { item ->
                        getString(when (item) {
                            CloudMembershipWarning.EXPIRING -> DesignR.string.cloud_plan_expiring_warning
                            CloudMembershipWarning.LOW_TRAFFIC -> DesignR.string.cloud_plan_low_traffic_warning
                            CloudMembershipWarning.TRAFFIC_EXHAUSTED -> DesignR.string.cloud_plan_traffic_exhausted_warning
                        })
                    }.takeIf { it.isNotEmpty() }
                setCloudAccount(
                    subscription.plan.ifBlank { if (abnormal) "未开通套餐" else "套餐" },
                    "到期时间：$expiry",
                    "${formatBytes(subscription.used)} / ${formatBytes(subscription.total)}",
                    progress,
                    expired,
                    warning,
                )
                if (expired) setLineName(getString(DesignR.string.cloud_plan_expired_title))
            }
        }
    }

    private suspend fun renderAnnouncement(design: MainDesign, data: JSONObject) {
        design.setAnnouncement(
            data.optString("title").ifBlank { "公告" },
            CloudReleaseManager.isDashboardAnnouncementVisible(this, data),
            CloudReleaseManager.isDashboardAnnouncementUnread(this, data),
        )
    }

    private fun isCloudPlanExpired(subscription: CloudSubscription? = cloudSubscription): Boolean =
        subscription?.let { it.expiresAt > 0 && it.expiresAt * 1000 <= System.currentTimeMillis() } == true

    private suspend fun refreshCloudSubscription(design: MainDesign, refreshProfile: Boolean = true, forceRefresh: Boolean = false) {
        if (!CloudSession(this).loggedIn) return
        if (refreshProfile) updateLineLoading(design, "正在获取订阅链接…")
        try {
            subscriptionMutex.withLock {
                var failure: Throwable? = null
                var subscription: CloudSubscription? = null
                val cloudApi = CloudApi(this@MainActivity)
                repeat(3) { attempt ->
                    if (subscription == null) {
                        runCatching { cloudApi.subscription(forceRefresh) }
                            .onSuccess { subscription = it }
                            .onFailure { failure = it }
                        if (subscription == null && attempt < 2) delay(2_000)
                    }
                }
                val loaded = subscription ?: throw failure ?: IllegalStateException("会员信息加载失败")
                cloudSubscription = loaded
                cloudSyncError = null
                design.fetchCloudAccount()
                if (!refreshProfile) return@withLock
                // Membership display and profile import are independent in hle_fi. A profile
                // refresh failure must not hide membership data that was fetched successfully.
                val subscriptionUrls = cloudApi.subscriptionUrls(loaded.subscribeUrl)
                updateLineLoading(design, "已获取订阅链接，正在下载订阅配置…")
                val fetchObserver = IFetchObserver { status ->
                    val message = when (status.action) {
                        FetchStatus.Action.FetchConfiguration -> "正在下载订阅配置…"
                        FetchStatus.Action.FetchProviders -> {
                            val current = (status.progress + 1).coerceAtLeast(1)
                            if (status.max > 0) "正在加载线路资源 $current/${status.max}…"
                            else "正在加载线路资源…"
                        }
                        FetchStatus.Action.Verifying -> "正在验证订阅配置…"
                        FetchStatus.Action.SubscriptionInfo -> null
                    }
                    if (message != null) {
                        launch { updateLineLoading(design, message) }
                    }
                }
                var importFailure: Throwable? = if (subscriptionUrls.isEmpty()) {
                    IllegalStateException("订阅地址无效")
                } else null
                for (url in subscriptionUrls) {
                    val imported = runCatching { importCloudProfile(url, fetchObserver) }
                        .onFailure { importFailure = it }
                        .isSuccess
                    if (imported) {
                        cloudApi.rememberSuccessfulSubscriptionUrl(url)
                        importFailure = null
                        break
                    }
                }
                if (importFailure != null) {
                    cloudSyncError = importFailure?.message ?: "订阅内容导入失败"
                    finishLineLoading(design)
                } else if (!clashRunning) {
                    updateLineLoading(design, "正在加载线路…")
                    awaitPreviewLine(design)
                } else {
                    design.fetch()
                }
            }
        } catch (e: Throwable) {
            if (cloudSubscription == null) cloudSyncError = e.message ?: "未知错误"
            if (refreshProfile) finishLineLoading(design)
            design.fetchCloudAccount()
        }
    }

    private suspend fun updateLineLoading(design: MainDesign, message: String) {
        lineLoadingStage = message
        design.setLineLoading(message)
    }

    private suspend fun finishLineLoading(design: MainDesign) {
        lineLoadingStage = null
        design.setLineName(cachedLineName ?: getString(DesignR.string.cloud_lines_still_loading))
    }

    private suspend fun refreshDdnsOnColdStart(design: MainDesign) {
        val changed = runCatching { CloudApi(this).refreshDdnsConfiguration() }.getOrDefault(false)
        if (clashRunning) {
            if (changed) {
                restartClashAfterConfigurationChange()
            }
            design.fetch()
        } else {
            if (changed) restartPreviewCore() else startPreviewCore()
            awaitPreviewLine(design)
        }
    }

    private suspend fun awaitPreviewLine(design: MainDesign) {
        withTimeoutOrNull(5_000L) {
            while (isActive) {
                if (queryCurrentLine() != null) return@withTimeoutOrNull
                delay(150L)
            }
        }
        design.fetch()
    }

    private suspend fun queryCurrentLine(): String? = runCatching {
        withClash {
            val names = queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
            names.firstOrNull()?.let { queryProxyGroup(it, uiStore.proxySort).now }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun loadCachedLineName(): String? {
        val state = getSharedPreferences("cloud_dashboard_state", MODE_PRIVATE)
        val profileUuid = CloudSession(this).profileUuid.orEmpty()
        if (profileUuid.isBlank() || state.getString("line_profile_uuid", "") != profileUuid) return null
        return state.getString("line_name", "")?.takeIf { it.isNotBlank() }
    }

    private fun rememberLineName(lineName: String) {
        cachedLineName = lineName
        getSharedPreferences("cloud_dashboard_state", MODE_PRIVATE).edit()
            .putString("line_profile_uuid", CloudSession(this).profileUuid.orEmpty())
            .putString("line_name", lineName)
            .apply()
    }

    private suspend fun importCloudProfile(url: String, callback: IFetchObserver? = null) = withCloudProfileSync {
        withProfile {
            val session = CloudSession(this@MainActivity)
            val existing = resolveCloudProfile(session, requireReady = false)
            val uuid = if (existing == null) {
                create(Profile.Type.Url, CLOUD_PROFILE_NAME, url).also {
                    patch(it, CLOUD_PROFILE_NAME, url, TimeUnit.HOURS.toMillis(6), null)
                }
            } else {
                patch(existing.uuid, CLOUD_PROFILE_NAME, url, TimeUnit.HOURS.toMillis(6), existing.ageSecretKey)
                existing.uuid
            }
            commit(uuid, callback)
            val imported = queryByUUID(uuid)
                ?: throw IllegalStateException("订阅配置未保存")
            if (!imported.imported || imported.pending) {
                throw IllegalStateException("订阅内容导入失败")
            }
            activateAndPruneCloudProfile(session, imported)

            // Flutter 版本的 applyProfile(force: true) 会立即重载核心。原生版本在新建
            // Profile 时 commit 广播早于 setActive，运行中的核心可能错过有效加载事件，
            // 因此需要重启一次服务，让 ConfigurationModule 从 active profile 重新加载。
            if (clashRunning) {
                restartClashAfterConfigurationChange()
            } else {
                restartPreviewCore()
            }
        }
    }

    private suspend fun restartClashAfterConfigurationChange() = withContext(Dispatchers.Main) {
        // Profile import also runs on IO. Serialize the generation check and the
        // actual start with accepted user power commands on the main thread.
        powerRequestGeneration.restartIfUnchanged(
            stop = { stopClashService() },
            awaitStop = { delay(500) },
            start = { startClashService() },
        )
    }

    private fun startPreviewCore() {
        if (previewCoreRunning || clashRunning || vpnStartPending) return
        startForegroundServiceCompat(PreviewService::class.intent)
        previewCoreRunning = true
    }

    private fun stopPreviewCore() {
        // The service can outlive this Activity and its local preview flag.
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

    private fun requestNetworkDetection(
        design: MainDesign,
        delayMillis: Long = 0L,
        automatic: Boolean = false,
    ) {
        networkDetectionJob?.cancel()
        networkDetectionJob = launch {
            if (delayMillis > 0) delay(delayMillis)
            detectNetwork(design, automatic)
        }
    }

    private fun consumeNetworkRefreshPending(): Boolean {
        val state = getSharedPreferences("cloud_dashboard_state", MODE_PRIVATE)
        if (!state.getBoolean("network_refresh_pending", false)) return false
        state.edit().remove("network_refresh_pending").apply()
        cachedNetworkResult = null
        return true
    }

    private fun refreshLineAfterSelection(design: MainDesign, expectedLine: String) {
        launch {
            repeat(20) {
                delay(150)
                if (!isActive) return@launch
                if (queryCurrentLine() == expectedLine) {
                    design.fetch()
                    if (clashRunning || previewCoreRunning) requestNetworkDetection(design, 500)
                    return@launch
                }
            }
            // Keep the user's selected line visible if the core needs longer to report it.
            design.setLineName(expectedLine)
            if (clashRunning || previewCoreRunning) requestNetworkDetection(design, 500)
        }
    }

    private suspend fun detectNetwork(design: MainDesign, automatic: Boolean) {
        design.setNetworkResult(
            getString(
                if (automatic) DesignR.string.cloud_dashboard_auto_detecting
                else DesignR.string.cloud_dashboard_detecting,
            ),
        )
        val ipInfo = queryFirstIpInfo()
        val result = ipInfo?.let { (ip, country) ->
            listOf(countryFlag(country), ip).filter { it.isNotBlank() }.joinToString(" ")
        } ?: getString(DesignR.string.cloud_dashboard_detection_timeout)
        cachedNetworkResult = result
        design.setNetworkResult(result)
    }

    /** Matches free_time_new: race all providers directly and keep the first valid response. */
    private suspend fun queryFirstIpInfo(): Pair<String, String>? {
        val systemProxy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val connectivity = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                if (connectivity.defaultProxy != null) "enabled" else "disabled"
            }.getOrDefault("unknown")
        } else {
            "unknown"
        }
        com.github.kr328.clash.common.log.Log.i("DashboardNetwork: start systemProxy=$systemProxy")
        val sources = listOf(
            "https://ipwho.is" to Pair("ip", "country_code"),
            "https://api.myip.com" to Pair("ip", "cc"),
            "https://ipapi.co/json" to Pair("ip", "country_code"),
            "https://ident.me/json" to Pair("ip", "cc"),
            "http://ip-api.com/json" to Pair("query", "countryCode"),
            "https://api.ip.sb/geoip" to Pair("ip", "country_code"),
            "https://ipinfo.io/json" to Pair("ip", "country"),
        )
        return firstSuccessfulHttp(sources) { (endpoint, fields) ->
            val url = URL(endpoint)
            val host = url.host
            var stage = "open"
            var connection: HttpURLConnection? = null
            try {
                val request = (url.openConnection() as HttpURLConnection).also { connection = it }
                stage = "configure"
                request.connectTimeout = 10_000
                request.readTimeout = 10_000
                request.useCaches = false
                request.setRequestProperty("Accept", "application/json")
                request.setRequestProperty("User-Agent", ClientUserAgent.value(this@MainActivity))
                stage = "response"
                request.readCancellable {
                    val status = request.responseCode
                    com.github.kr328.clash.common.log.Log.d(
                        "DashboardNetwork: route host=$host usingProxy=${request.usingProxy()}",
                    )
                    if (status != HttpURLConnection.HTTP_OK) {
                        com.github.kr328.clash.common.log.Log.w(
                            "DashboardNetwork: failed host=$host stage=response status=$status",
                        )
                        return@readCancellable null
                    }
                    stage = "read_body"
                    val body = request.inputStream.bufferedReader().use { it.readText() }
                    stage = "parse_body"
                    val json = JSONObject(body)
                    val ip = json.optString(fields.first)
                    val country = json.optString(fields.second)
                    if (ip.isBlank()) {
                        com.github.kr328.clash.common.log.Log.w(
                            "DashboardNetwork: failed host=$host stage=validate_body reason=missing_ip",
                        )
                        null
                    } else {
                        com.github.kr328.clash.common.log.Log.i("DashboardNetwork: success host=$host")
                        ip to country
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Never log exception messages or bodies: either can contain IPs or full URLs.
                val usingProxy = runCatching { connection?.usingProxy()?.toString() }.getOrNull() ?: "unknown"
                com.github.kr328.clash.common.log.Log.w(
                    "DashboardNetwork: failed host=$host stage=$stage exception=${error.javaClass.simpleName} usingProxy=$usingProxy",
                )
                throw error
            }
        }
    }

    private fun countryFlag(code: String): String {
        if (code.length != 2) return ""
        return code.uppercase().map { char ->
            String(Character.toChars(0x1F1E6 + char.code - 'A'.code))
        }.joinToString("")
    }

    private suspend fun MainDesign.startClash(): Boolean {
        try {
            val active = withProfile { queryActive() }

            if (active == null || !active.imported) {
                cancelPowerTransition()
                showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                    if (canViewConfigurationModule()) {
                        setAction(DesignR.string.profiles) {
                            startActivity(ProfilesActivity::class.intent)
                        }
                    }
                }

                return false
            }

            val vpnRequest = startClashService()
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK) {
                    startClashService()
                } else {
                    cancelPowerTransition()
                    return false
                }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cancelPowerTransition()
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
            return false
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
        startActivity(Intent(this, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (target == ProxyActivity::class.java) {
                putExtra(ProxyActivity.EXTRA_RETURN_TO_DASHBOARD, true)
            }
        })
        overridePendingTransition(0, 0)
    }

    private suspend fun requestOpenProxy(design: MainDesign) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val transitioning = SystemClock.elapsedRealtime() < coreTransitionUntilMillis
        if (!transitioning) {
            if (clashRunning || previewCoreRunning) openPrimary(ProxyActivity::class.java)
            else design.showToast(DesignR.string.cloud_lines_connect_first, ToastDuration.Long)
            return
        }

        if (proxyOpenJob?.isActive == true) return
        design.showToast(DesignR.string.cloud_lines_preparing, ToastDuration.Short)
        proxyOpenJob = launch {
            val ready = withTimeoutOrNull(4_500L) {
                // Let the previous core fully stop before checking, otherwise its stale
                // proxy groups can briefly look ready during the handover.
                delay(500L)
                while (isActive) {
                    val hasGroups = runCatching {
                        withClash {
                            queryProxyGroupNames(uiStore.proxyExcludeNotSelectable).isNotEmpty()
                        }
                    }.getOrDefault(false)
                    if (hasGroups) return@withTimeoutOrNull true
                    delay(150L)
                }
                false
            } == true

            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            if (ready) openPrimary(ProxyActivity::class.java)
            else design.showToast(DesignR.string.cloud_lines_still_loading, ToastDuration.Long)
        }
    }

    override fun onPause() {
        proxyOpenJob?.cancel()
        proxyOpenJob = null
        super.onPause()
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
