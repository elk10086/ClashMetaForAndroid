package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.Toast
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.adapter.ProxyAdapter
import com.github.kr328.clash.design.adapter.ProxyPageAdapter
import com.github.kr328.clash.design.component.ProxyMenu
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.databinding.DesignProxyBinding
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.util.ThemeTypography
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ProxyDesign(
    context: Context,
    overrideMode: TunnelState.Mode?,
    groupNames: List<String>,
    uiStore: UiStore,
    showTechnicalDetails: Boolean,
) : Design<ProxyDesign.Request>(context) {
    sealed class Request {
        object ReloadAll : Request()
        object ReLaunch : Request()

        data class PatchMode(val mode: TunnelState.Mode?) : Request()
        data class Reload(val index: Int) : Request()
        data class Select(val index: Int, val name: String) : Request()
        data class UrlTest(val index: Int) : Request()
        object UpdateSubscription : Request()
    }

    private val binding = DesignProxyBinding
        .inflate(context.layoutInflater, context.root, false)

    private var config = ProxyViewConfig(context, uiStore.proxyLine, showTechnicalDetails)
    private val selectableGroups = BooleanArray(groupNames.size)
    private var lineTipToast: Toast? = null

    private val menu: ProxyMenu by lazy {
        ProxyMenu(context, binding.menuView, overrideMode, uiStore, requests) {
            config.proxyLine = uiStore.proxyLine
        }
    }

    private val adapter: ProxyPageAdapter
        get() = binding.pagesView.adapter!! as ProxyPageAdapter

    private var horizontalScrolling = false
    private var subscriptionUpdating = false
    private val verticalBottomScrolled: Boolean
        get() = adapter.states[binding.pagesView.currentItem].bottom
    private var urlTesting: Boolean
        get() = adapter.states[binding.pagesView.currentItem].urlTesting
        set(value) {
            adapter.states[binding.pagesView.currentItem].urlTesting = value
        }

    override val root: View = binding.root

    suspend fun updateGroup(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>
    ) {
        selectableGroups[position] = selectable
        adapter.updateAdapter(position, proxies, selectable, parent, links)

        withContext(Dispatchers.Main) { updateUrlTestButtonStatus() }
    }

    suspend fun finishUrlTesting(position: Int) = withContext(Dispatchers.Main) {
        adapter.states[position].urlTesting = false
        adapter.requestRedrawVisible()
        updateUrlTestButtonStatus()
    }

    suspend fun requestRedrawVisible() {
        withContext(Dispatchers.Main) {
            adapter.requestRedrawVisible()
        }
    }

    suspend fun showModeSwitchTips() {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.mode_switch_tips, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun showAutoSelectTips() {
        withContext(Dispatchers.Main) {
            showLineTip(context.getString(R.string.cloud_auto_select_tips))
        }
    }

    suspend fun showFallbackTips() {
        withContext(Dispatchers.Main) {
            showLineTip(context.getString(R.string.cloud_fallback_tips))
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.menuView.setOnClickListener {
            menu.show()
        }

        if (groupNames.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE

            binding.urlTestView.visibility = View.GONE
            binding.tabLayoutView.visibility = View.GONE
            binding.elevationView.visibility = View.GONE
            binding.pagesView.visibility = View.GONE
            binding.urlTestFloatView.visibility = View.GONE
        } else {
            binding.urlTestFloatView.supportImageTintList = ColorStateList.valueOf(
                context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
            )

            binding.pagesView.apply {
                adapter = ProxyPageAdapter(
                    surface,
                    config,
                    List(groupNames.size) { index ->
                        ProxyAdapter(
                            config = config,
                            clicked = { name -> requests.trySend(Request.Select(index, name)) },
                            notSelectableClicked = { showSelectableGroupTips(groupNames[index], groupNames) },
                        )
                    }
                ) {
                    if (it == currentItem)
                        updateUrlTestButtonStatus()
                }

                registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageScrollStateChanged(state: Int) {
                        horizontalScrolling = state != ViewPager2.SCROLL_STATE_IDLE

                        updateUrlTestButtonStatus()
                    }

                    override fun onPageSelected(position: Int) {
                        uiStore.proxyLastGroup = groupNames[position]
                        updateUrlTestButtonStatus()
                    }
                })
            }

            TabLayoutMediator(binding.tabLayoutView, binding.pagesView) { tab, index ->
                tab.text = groupNames[index]
            }.attach()

            val initialPosition = groupNames.indexOf(uiStore.proxyLastGroup)

            binding.pagesView.post {
                if (initialPosition > 0)
                    binding.pagesView.setCurrentItem(initialPosition, false)
            }
        }
    }

    fun requestUrlTesting() {
        if (binding.pagesView.adapter == null || urlTesting || subscriptionUpdating) return
        if (adapter.states.any { it.urlTesting }) {
            showLineTip("其他分组正在测速，请稍候")
            return
        }
        urlTesting = true

        requests.trySend(Request.UrlTest(binding.pagesView.currentItem))

        updateUrlTestButtonStatus()
    }

    fun requestSubscriptionUpdate() {
        if (subscriptionUpdating) return
        if (binding.pagesView.adapter != null && adapter.states.any { it.urlTesting }) {
            showLineTip("请等待线路测速完成后再更新订阅")
            return
        }
        requests.trySend(Request.UpdateSubscription)
    }

    suspend fun confirmSubscriptionUpdate(): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.cloud_update_subscription)
                .setMessage(R.string.cloud_update_subscription_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.update) { _, _ ->
                    if (continuation.isActive) continuation.resume(true)
                }
                .create()
            dialog.setOnDismissListener {
                if (continuation.isActive) continuation.resume(false)
            }
            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
            dialog.window?.decorView?.let(ThemeTypography::install)
        }
    }

    suspend fun setSubscriptionUpdating(updating: Boolean) = withContext(Dispatchers.Main) {
        subscriptionUpdating = updating
        binding.updateSubscriptionView.visibility = if (updating) View.GONE else View.VISIBLE
        binding.updateSubscriptionProgressView.visibility = if (updating) View.VISIBLE else View.GONE
        binding.updateSubscriptionLayout.isEnabled = !updating
        binding.updateSubscriptionView.isEnabled = !updating
        if (binding.pagesView.adapter != null) updateUrlTestButtonStatus()
    }

    suspend fun showSubscriptionUpdateResult(message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun showLineTip(message: CharSequence) {
        lineTipToast?.cancel()
        lineTipToast = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun showSelectableGroupTips(currentGroup: String, groupNames: List<String>) {
        val available = groupNames.filterIndexed { index, _ -> selectableGroups[index] }
        val message = if (available.isEmpty()) {
            context.getString(R.string.cloud_proxy_group_no_selectable_tips, currentGroup)
        } else {
            context.getString(
                R.string.cloud_proxy_group_not_selectable_tips,
                currentGroup,
                available.joinToString("、"),
            )
        }
        showLineTip(message)
    }

    private fun updateUrlTestButtonStatus() {
        if (binding.pagesView.adapter == null) return
        val hidden = verticalBottomScrolled || horizontalScrolling
        if (hidden) {
            binding.urlTestFloatView.hide()
        } else {
            binding.urlTestFloatView.show()
        }

        binding.urlTestFloatView.setImageResource(if (urlTesting) 0 else R.drawable.ic_baseline_flash_on)
        binding.urlTestFloatView.isEnabled = !urlTesting && !subscriptionUpdating
        binding.urlTestView.isEnabled = !subscriptionUpdating
        binding.urlTestFloatProgress.visibility = if (urlTesting && !hidden) View.VISIBLE else View.GONE

        if (urlTesting) {
            binding.urlTestView.visibility = View.GONE
            binding.urlTestProgressView.visibility = View.VISIBLE
        } else {
            binding.urlTestView.visibility = View.VISIBLE
            binding.urlTestProgressView.visibility = View.GONE
        }
    }
}
