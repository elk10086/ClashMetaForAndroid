package com.github.kr328.clash

import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.cloud.CloudApi
import com.github.kr328.clash.cloud.CloudSession
import com.github.kr328.clash.cloud.CLOUD_PROFILE_NAME
import com.github.kr328.clash.cloud.activateAndPruneCloudProfile
import com.github.kr328.clash.cloud.resolveCloudProfile
import com.github.kr328.clash.cloud.withCloudProfileSync
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.util.ProxyDelayCache
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR

class ProxyActivity : BaseActivity<ProxyDesign>() {
    private var subscriptionUpdateJob: Job? = null
    private var profileReady: CompletableDeferred<Unit>? = null
    private var leavingPage = false
    override fun onProfileLoaded() {
        profileReady?.complete(Unit)
        super.onProfileLoaded()
    }

    companion object {
        const val EXTRA_RETURN_TO_DASHBOARD = "return_to_dashboard_after_selection"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun relaunch() {
        // A queued profile event must not reopen this page after selection/navigation.
        if (leavingPage || isFinishing || isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        leavingPage = true
        startActivity(ProxyActivity::class.intent.putExtra(
            EXTRA_RETURN_TO_DASHBOARD,
            intent.getBooleanExtra(EXTRA_RETURN_TO_DASHBOARD, false),
        ))
        finish()
    }

    override suspend fun main() {
        val mode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
        val profileUuid = CloudSession(this).profileUuid.orEmpty()
        val states = List(names.size) { ProxyState("?") }
        val unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(10)

        val design = ProxyDesign(
            this,
            mode,
            names,
            uiStore,
            showTechnicalDetails = BuildConfig.DEBUG || CloudSession(this).isTestAccount,
        )

        setContentDesign(design)
        withContext(Dispatchers.Main) {
            CloudBottomNavigation.attach(this@ProxyActivity, CloudBottomNavigation.Tab.LINES)
        }

        design.requests.send(ProxyDesign.Request.ReloadAll)

        while (isActive && !leavingPage) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ProfileLoaded -> {
                            if (subscriptionUpdateJob?.isActive == true) return@onReceive
                            val newNames = withClash {
                                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                            }

                            if (newNames != names) {
                                relaunch()
                            } else {
                                design.requests.trySend(ProxyDesign.Request.ReloadAll)
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProxyDesign.Request.ReLaunch -> {
                            relaunch()
                        }
                        ProxyDesign.Request.ReloadAll -> {
                            names.indices.forEach { idx ->
                                design.requests.trySend(ProxyDesign.Request.Reload(idx))
                            }
                        }
                        is ProxyDesign.Request.Reload -> {
                            launch {
                                val queriedGroup = reloadLock.withPermit {
                                    withClash {
                                        queryProxyGroup(names[it.index], uiStore.proxySort)
                                    }
                                }
                                val group = ProxyDelayCache.restore(
                                    this@ProxyActivity,
                                    profileUuid,
                                    names[it.index],
                                    queriedGroup,
                                )
                                ProxyDelayCache.save(
                                    this@ProxyActivity,
                                    profileUuid,
                                    names[it.index],
                                    group.proxies,
                                )
                                val state = states[it.index]

                                state.now = group.now
                                design.updateGroup(
                                    it.index,
                                    group.proxies,
                                    group.type == "Selector",
                                    state,
                                    unorderedStates
                                )
                            }
                        }
                        is ProxyDesign.Request.Select -> {
                            val selected = withClash {
                                patchSelector(names[it.index], it.name)
                            }
                            if (!selected) {
                                Toast.makeText(this@ProxyActivity, "线路切换失败，请重试", Toast.LENGTH_SHORT).show()
                                return@onReceive
                            }
                            states[it.index].now = it.name

                            getSharedPreferences("cloud_dashboard_state", MODE_PRIVATE)
                                .edit()
                                .putBoolean("network_refresh_pending", true)
                                .putString("line_profile_uuid", CloudSession(this@ProxyActivity).profileUuid.orEmpty())
                                .putString("line_name", it.name)
                                .apply()

                            if (intent.getBooleanExtra(EXTRA_RETURN_TO_DASHBOARD, false)) {
                                leavingPage = true
                                CloudBottomNavigation.open(this@ProxyActivity, CloudBottomNavigation.Tab.DASHBOARD)
                                finish()
                                return@onReceive
                            }
                            design.requestRedrawVisible()
                            if (it.name.trim() == "自动选择") {
                                design.showAutoSelectTips()
                            } else if (it.name.trim() == "故障转移") {
                                design.showFallbackTips()
                            }
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            launch {
                                states[it.index].testing = true
                                design.requestRedrawVisible()
                                try {
                                    withClash { healthCheck(names[it.index]) }
                                    val queriedGroup = withClash {
                                        queryProxyGroup(names[it.index], uiStore.proxySort)
                                    }
                                    ProxyDelayCache.save(
                                        this@ProxyActivity,
                                        profileUuid,
                                        names[it.index],
                                        queriedGroup.proxies,
                                    )
                                    val group = ProxyDelayCache.restore(
                                        this@ProxyActivity,
                                        profileUuid,
                                        names[it.index],
                                        queriedGroup,
                                    )
                                    design.updateGroup(it.index, group.proxies, group.type == "Selector", states[it.index], unorderedStates)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    design.showSubscriptionUpdateResult("测速失败，请重试：${error.message.orEmpty()}")
                                } finally {
                                    states[it.index].testing = false
                                    design.finishUrlTesting(it.index)
                                }
                            }
                        }
                        ProxyDesign.Request.UpdateSubscription -> {
                            if (subscriptionUpdateJob?.isActive != true) {
                                subscriptionUpdateJob = launch {
                                    if (design.confirmSubscriptionUpdate()) {
                                        updateCloudSubscription(design)
                                    }
                                }
                            }
                        }
                        is ProxyDesign.Request.PatchMode -> {
                            design.showModeSwitchTips()

                            withClash {
                                val o = queryOverride(Clash.OverrideSlot.Session)

                                o.mode = it.mode

                                patchOverride(Clash.OverrideSlot.Session, o)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun updateCloudSubscription(design: ProxyDesign) {
        design.setSubscriptionUpdating(true)
        try {
            withCloudRequestLoading("正在更新订阅与线路…") {
                val session = CloudSession(this@ProxyActivity)
                check(session.loggedIn) { "请先登录" }
                val cloudApi = CloudApi(this@ProxyActivity)
                val subscription = cloudApi.subscription(forceRefresh = true)
                val urls = cloudApi.subscriptionUrls(subscription.subscribeUrl)
                check(urls.isNotEmpty()) { "订阅地址无效" }

                var lastFailure: Throwable? = null
                for (url in urls) {
                    val updated = runCatching {
                                withCloudProfileSync {
                            withProfile {
                                val existing = resolveCloudProfile(session, requireReady = false)
                                val uuid = if (existing == null) {
                                    create(Profile.Type.Url, CLOUD_PROFILE_NAME, url).also {
                                        patch(it, CLOUD_PROFILE_NAME, url, TimeUnit.HOURS.toMillis(6), null)
                                    }
                                } else {
                                    patch(existing.uuid, CLOUD_PROFILE_NAME, url, TimeUnit.HOURS.toMillis(6), existing.ageSecretKey)
                                    existing.uuid
                                }
                                profileReady = CompletableDeferred()
                                commit(uuid)
                                val imported = queryByUUID(uuid)
                                    ?: error("订阅配置未保存")
                                check(imported.imported && !imported.pending) { "订阅内容导入失败" }
                                activateAndPruneCloudProfile(session, imported)
                                }
                                ProxyDelayCache.clearProfile(
                                    this@ProxyActivity,
                                    session.profileUuid.orEmpty(),
                                )
                            }
                    }.onFailure {
                        if (it is CancellationException) throw it
                        lastFailure = it
                    }.isSuccess
                    if (updated) {
                        cloudApi.rememberSuccessfulSubscriptionUrl(url)
                        val ready = withTimeoutOrNull(60_000) { profileReady?.await(); true } == true
                        if (!ready) {
                            design.showSubscriptionUpdateResult("订阅已保存，核心加载尚未完成，请稍后再测速")
                            return@withCloudRequestLoading
                        }
                        design.showSubscriptionUpdateResult(getString(DesignR.string.cloud_update_subscription_success))
                        relaunch()
                        return@withCloudRequestLoading
                    }
                }
                throw lastFailure ?: IllegalStateException("订阅更新失败")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            design.showSubscriptionUpdateResult(
                getString(DesignR.string.cloud_update_subscription_failure, error.message ?: "未知错误"),
            )
        } finally {
            profileReady = null
            design.setSubscriptionUpdating(false)
        }
    }
}
