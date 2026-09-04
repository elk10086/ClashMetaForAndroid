package com.github.kr328.clash

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.github.kr328.clash.cloud.CloudApi
import com.github.kr328.clash.cloud.CloudSession
import com.github.kr328.clash.cloud.CloudSubscription
import com.github.kr328.clash.cloud.CloudReleaseManager
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.util.ApplicationObserver
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import com.github.kr328.clash.design.R as DesignR

internal fun AppCompatActivity.applyCloudTheme() {
    val store = UiStore(this)
    val dark = when (store.darkMode) {
        DarkMode.ForceDark -> true
        DarkMode.ForceLight -> false
        DarkMode.Auto -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    setTheme(if (dark) DesignR.style.AppThemeDark else DesignR.style.AppThemeLight)
    when (store.cloudThemeStyle) {
        0 -> theme.applyStyle(if (dark) DesignR.style.CloudThemeMinimalDark else DesignR.style.CloudThemeMinimalLight, true)
        1 -> theme.applyStyle(if (dark) DesignR.style.CloudThemeSakuraDark else DesignR.style.CloudThemeSakuraLight, true)
        2 -> theme.applyStyle(if (dark) DesignR.style.CloudThemeGodDark else DesignR.style.CloudThemeGodLight, true)
        3 -> theme.applyStyle(if (dark) DesignR.style.CloudThemeAnimeDark else DesignR.style.CloudThemeAnimeLight, true)
    }
    if (dark && store.pureBlack) theme.applyStyle(DesignR.style.CloudThemePureBlack, true)
}

private fun Context.cloudColor(value: String): Int {
    val uiStore = UiStore(this)
    val dark = when (UiStore(this).darkMode) {
        DarkMode.ForceDark -> true
        DarkMode.ForceLight -> false
        DarkMode.Auto -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    if (uiStore.cloudThemeStyle >= 0) {
        val page = resolveThemedColor(android.R.attr.windowBackground)
        val surface = resolveThemedColor(com.google.android.material.R.attr.colorSurface)
        val onSurface = resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        return when (value.uppercase()) {
            "#FAFAFA" -> if (dark && uiStore.pureBlack) Color.BLACK else page
            "#FFFFFF", "#F5F5F5" -> surface
            "#212121", "#000000", "#424242" -> onSurface
            "#616161", "#757575", "#9E9E9E" -> blendCloudColor(onSurface, surface, .38f)
            "#EEEEEE", "#E8E8E8" -> blendCloudColor(surface, onSurface, .09f)
            "#E0E0E0", "#BDBDBD" -> blendCloudColor(surface, onSurface, .18f)
            else -> Color.parseColor(value)
        }
    }
    if (!dark) return Color.parseColor(value)
    return when (value.uppercase()) {
        "#FAFAFA" -> Color.parseColor(if (uiStore.pureBlack) "#000000" else "#121212")
        "#FFFFFF", "#F5F5F5" -> Color.parseColor("#202020")
        "#212121", "#000000", "#424242" -> Color.parseColor("#F5F5F5")
        "#616161", "#757575", "#9E9E9E" -> Color.parseColor("#B8B8B8")
        "#EEEEEE", "#E8E8E8" -> Color.parseColor("#303030")
        "#E0E0E0", "#BDBDBD" -> Color.parseColor("#484848")
        else -> Color.parseColor(value)
    }
}

private fun blendCloudColor(a: Int, b: Int, ratio: Float): Int = Color.rgb(
    (Color.red(a) * (1 - ratio) + Color.red(b) * ratio).toInt(),
    (Color.green(a) * (1 - ratio) + Color.green(b) * ratio).toInt(),
    (Color.blue(a) * (1 - ratio) + Color.blue(b) * ratio).toInt(),
)

private fun Context.cloudPrimaryColor(): Int = when (UiStore(this).cloudThemeStyle) {
    0 -> Color.parseColor(if (isCloudDark()) "#FFFFFF" else "#212121")
    1 -> Color.parseColor("#FFB3C9")
    2 -> Color.parseColor("#45B6E5")
    3 -> Color.parseColor("#C9A227")
    else -> resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
}

private fun Context.cloudSecondaryColor(): Int = resolveThemedColor(com.google.android.material.R.attr.colorSecondary)

private fun Context.cloudErrorColor(): Int = resolveThemedColor(com.google.android.material.R.attr.colorError)

private fun Context.cloudPriceChipColor(): Int = blendCloudColor(
    resolveThemedColor(com.google.android.material.R.attr.colorSurface),
    cloudPrimaryColor(),
    .12f,
)

private fun Context.cloudButtonTextColor(): Int = if (UiStore(this).cloudThemeStyle == 0 && !isCloudDark()) {
    Color.WHITE
} else {
    Color.parseColor("#121212")
}

private fun Context.isCloudDark(): Boolean = when (UiStore(this).darkMode) {
    DarkMode.ForceDark -> true
    DarkMode.ForceLight -> false
    DarkMode.Auto -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

/** Mirrors CommonCard's selected secondaryContainer/onSecondaryContainer colors in free_time_new. */
private fun Context.cloudSelectedContainerColor(): Int {
    val dark = isCloudDark()
    val container = when (UiStore(this).cloudThemeStyle) {
        0 -> Color.parseColor(if (dark) "#242424" else "#E0E0E0")
        1 -> Color.parseColor(if (dark) "#32232D" else "#FFEDF4")
        2 -> Color.parseColor(if (dark) "#122530" else "#D8F2FA")
        3 -> Color.parseColor(if (dark) "#1C1C1C" else "#E9DFC7")
        else -> blendCloudColor(
            resolveThemedColor(com.google.android.material.R.attr.colorSurface),
            resolveThemedColor(com.google.android.material.R.attr.colorPrimary),
            .18f,
        )
    }
    // CommonCardType.filled uses secondaryContainer.opacity80 over the surface.
    return blendCloudColor(container, resolveThemedColor(com.google.android.material.R.attr.colorSurface), .20f)
}

private fun Context.cloudSelectedTextColor(): Int {
    val dark = isCloudDark()
    return when (UiStore(this).cloudThemeStyle) {
        0 -> Color.parseColor(if (dark) "#E8E8E8" else "#212121")
        1 -> Color.parseColor(if (dark) "#F5DDE8" else "#4A1730")
        2 -> Color.parseColor(if (dark) "#DDF4FA" else "#052A3A")
        3 -> Color.parseColor(if (dark) "#ECE8E0" else "#1A1816")
        else -> resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
    }
}

private fun Context.applyCloudTypography(view: View) {
    val skin = UiStore(this).cloudThemeStyle
    if (skin < 0) return
    if (view is TextView) {
        val style = if (view.typeface?.isBold == true) Typeface.BOLD else Typeface.NORMAL
        val family = ResourcesCompat.getFont(this, when (skin) {
            0 -> DesignR.font.skin_minimal
            1 -> DesignR.font.skin_sakura
            2 -> DesignR.font.skin_god
            else -> DesignR.font.skin_anime
        })
        view.typeface = Typeface.create(family, style)
        view.textScaleX = when (skin) { 1 -> .90f; 2 -> 1.03f; else -> 1f }
        view.letterSpacing = when (skin) { 0 -> .015f; 1 -> .025f; 3 -> .035f; else -> 0f }
        val store = UiStore(this)
        if (store.cloudTextScaleEnabled) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * store.cloudTextScalePercent.coerceIn(80, 140) / 100f)
        }
    }
    if (view is ViewGroup) repeat(view.childCount) { applyCloudTypography(view.getChildAt(it)) }
}

class CloudMineActivity : AppCompatActivity() {
    private lateinit var expiryView: TextView
    private lateinit var balanceView: TextView
    private lateinit var accountView: TextView
    private var appliedThemeSignature = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        applyCloudTheme()
        super.onCreate(savedInstanceState)
        appliedThemeSignature = themeSignature()
        title = getString(DesignR.string.cloud_nav_mine)
        val root = vertical().apply {
            setBackgroundColor(cloudColor("#FAFAFA"))
            setPadding(dp(18), dp(35), dp(18), dp(24))
            addView(LinearLayout(this@CloudMineActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@CloudMineActivity).apply {
                    setImageResource(R.drawable.cloud_avatar); scaleType = ImageView.ScaleType.CENTER_CROP
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                accountView = TextView(this@CloudMineActivity).apply {
                    text = ""
                    textSize = 16f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.NORMAL)
                }
                addView(accountView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            })
            addView(sectionTitle("会员信息"))
            addView(vipBanner())
            addView(sectionTitle("账号与服务"))
            addView(sectionCard(
                actionRow("我的订单") { open("orders") },
                actionRow("邀请返利") { open("invite") },
                actionRow("意见反馈") { open("tickets") },
            ))
            addView(sectionTitle("应用设置"))
            addView(sectionCard(
                actionRow("检查更新", leadingIcon = DesignR.drawable.ic_outline_update) {
                    lifecycleScope.launch { runCatching { CloudReleaseManager.checkUpdate(this@CloudMineActivity) }.onFailure { toast(it) } }
                },
                actionRow("清除远端缓存") { confirmRefreshServiceConfig() },
                actionRow("主题") { startActivity(Intent(this@CloudMineActivity, AppSettingsActivity::class.java)) },
                actionRow("一键换肤") { open("skin") },
                actionRow("设置") { startActivity(Intent(this@CloudMineActivity, SettingsActivity::class.java)) },
            ))
            addView(sectionTitle("账号安全"))
            addView(logoutButton())
        }
        setContentView(vertical().apply {
            setBackgroundColor(cloudColor("#FAFAFA"))
            addView(ScrollView(this@CloudMineActivity).apply { addView(root) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(
                CloudBottomNavigation.create(this@CloudMineActivity, CloudBottomNavigation.Tab.MINE),
                LinearLayout.LayoutParams(-1, dp(80)),
            )
        }.also { applyCloudTypography(it) })
        lifecycleScope.launch {
            runCatching { CloudApi(this@CloudMineActivity).subscription() }.onSuccess { subscription ->
                accountView.text = maskEmail(subscription.email)
                val text = if (subscription.expiresAt > 0 && subscription.expiresAt * 1000 < System.currentTimeMillis()) {
                    "已过期"
                } else if (subscription.expiresAt > 0) {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(subscription.expiresAt * 1000))
                } else "不限时长"
                expiryView.text = "到期时间：$text"
                balanceView.text = "钱包余额：¥${formatBalance(subscription.balance)}"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (appliedThemeSignature.isNotEmpty() && appliedThemeSignature != themeSignature()) recreate()
    }

    private fun themeSignature(): String = UiStore(this).let { "${it.darkMode}:${it.cloudThemeStyle}:${it.pureBlack}" }

    private fun open(type: String) = startActivity(
        Intent(this, CloudBusinessActivity::class.java).putExtra("type", type),
    )

    private fun logoutButton(): View = TextView(this).apply {
        text = getString(DesignR.string.cloud_logout)
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(cloudButtonTextColor())
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(cloudPrimaryColor(), cloudSecondaryColor()),
        ).apply { cornerRadius = dp(25).toFloat() }
        setOnClickListener { confirmLogout() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(dp(200), dp(50)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(20) } }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(DesignR.string.cloud_logout)
            .setMessage(DesignR.string.cloud_logout_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> performLogout() }
            .show()
    }

    private fun confirmRefreshServiceConfig() {
        AlertDialog.Builder(this)
            .setTitle("清除远端缓存")
            .setMessage("清除远端服务地址缓存并重新获取？")
            .setNegativeButton("取消", null)
            .setPositiveButton("刷新") { _, _ ->
                CloudApi(this).clearRemoteConfigCache()
                lifecycleScope.launch {
                    runCatching { CloudApi(this@CloudMineActivity).remoteConfig() }
                        .onSuccess { Toast.makeText(this@CloudMineActivity, "服务配置已刷新", Toast.LENGTH_SHORT).show() }
                        .onFailure { toast(it) }
                }
            }
            .show()
    }

    private fun performLogout() {
        val progress = AlertDialog.Builder(this)
            .setTitle(DesignR.string.cloud_logout_progress_title)
            .setMessage(DesignR.string.cloud_logout_progress_message)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val session = CloudSession(this@CloudMineActivity)
            val profileId = session.profileUuid
            stopClashService()
            delay(500)
            runCatching {
                profileId?.let { id -> withProfile { delete(UUID.fromString(id)) } }
            }
            session.clear()
            getSharedPreferences("cloud_config", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("cloud_connection_clock", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("cloud_membership_cache", MODE_PRIVATE).edit().clear().apply()
            progress.dismiss()
            val login = Intent(this@CloudMineActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(login)
            finish()
        }
    }

    private fun vipBanner(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), dp(12), dp(15), dp(12))
        background = roundRect("#FFFFFF", 8, "#E0E0E0")
        isClickable = true; isFocusable = true; setOnClickListener { open("plans") }
        addView(vertical().apply {
            addView(TextView(this@CloudMineActivity).apply { text = "VIP会员"; textSize = 16f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.NORMAL) })
            expiryView = TextView(this@CloudMineActivity).apply { text = "到期时间：加载中…"; textSize = 11f; setTextColor(cloudColor("#757575")); setPadding(0, dp(2), 0, 0) }
            addView(expiryView)
            balanceView = TextView(this@CloudMineActivity).apply { text = "钱包余额：加载中…"; textSize = 11f; setTextColor(cloudColor("#757575")); setPadding(0, dp(2), 0, 0) }
            addView(balanceView)
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }.also { it.layoutParams = LinearLayout.LayoutParams(-1, dp(78)) }

    private fun sectionTitle(title: String) = TextView(this).apply {
        text = title
        textSize = 13f
        setTextColor(cloudColor("#757575"))
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, dp(36)).apply {
            topMargin = dp(12)
            marginStart = dp(12)
        }
    }

    private fun sectionCard(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect("#FFFFFF", 10, "#E0E0E0")
        rows.forEachIndexed { index, row ->
            if (index > 0) addView(View(this@CloudMineActivity).apply {
                setBackgroundColor(cloudColor("#E0E0E0"))
                alpha = .72f
            }, LinearLayout.LayoutParams(-1, dp(1)).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            })
            addView(row)
        }
    }

    private fun actionRow(title: String, leadingIcon: Int? = null, click: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            isClickable = true
            isFocusable = true
            setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.035f else 1f).scaleY(if (focused) 1.035f else 1f).setDuration(120).start()
                foreground = if (focused) roundRect("#00000000", 12, "#212121") else null
            }
            setOnClickListener { click() }
            if (leadingIcon != null) addView(ImageView(this@CloudMineActivity).apply {
                setImageResource(leadingIcon); setColorFilter(cloudPrimaryColor()); setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(2) })
            addView(TextView(this@CloudMineActivity).apply {
                text = title; textSize = 15f; setTextColor(cloudColor("#212121"))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(this@CloudMineActivity).apply {
                setImageResource(R.drawable.cloud_chevron_right); setColorFilter(cloudColor("#9E9E9E")); scaleType = ImageView.ScaleType.CENTER
            }, LinearLayout.LayoutParams(dp(22), dp(52)))
        }.also { it.layoutParams = LinearLayout.LayoutParams(-1, dp(52)) }

    private fun formatBalance(cents: Long) = String.format(java.util.Locale.US, "%.2f", cents / 100.0)

    private fun maskEmail(value: String): String {
        val parts = value.split('@')
        return if (parts.size == 2 && parts[0].length > 3) parts[0].take(3) + "****@" + parts[1] else value
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(e: Throwable) = Toast.makeText(this, e.message ?: "操作失败", Toast.LENGTH_LONG).show()
    private fun roundRect(fill: String, radius: Int, stroke: String? = null) = GradientDrawable().apply {
        setColor(if (fill == "#212121") cloudPrimaryColor() else cloudColor(fill)); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), if (stroke == "#212121") cloudPrimaryColor() else cloudColor(stroke))
    }
}

class CloudBusinessActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var pageRoot: LinearLayout
    private val api by lazy { CloudApi(this) }
    private val type by lazy { intent.getStringExtra("type") ?: "plans" }
    private var creatingOrder = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyCloudTheme()
        super.onCreate(savedInstanceState)
        val pageTitle = mapOf("plans" to "会员", "plan_detail" to "商品详情", "payment" to "支付", "pay_success" to "支付结果", "orders" to "我的订单", "invite" to "邀请返利", "tickets" to "意见反馈", "ticket_detail" to "工单详情", "ticket_create" to "创建工单", "skin" to "一键换肤")[type] ?: ""
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (type == "plans" || type == "payment" || type == "pay_success") setPadding(0, 0, 0, 0)
            else setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        content.setBackgroundColor(cloudColor("#FAFAFA"))
        loading = ProgressBar(this)
        content.addView(loading, LinearLayout.LayoutParams(-1, dp(48)))
        pageRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cloudColor("#FAFAFA"))
            addView(pageHeader(pageTitle), LinearLayout.LayoutParams(-1, dp(56)))
            if (type == "plans" || type == "payment" || type == "pay_success") addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            else addView(ScrollView(this@CloudBusinessActivity).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(pageRoot)
        reload()
    }

    private fun pageHeader(pageTitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(16), 0)
        setBackgroundColor(cloudColor("#FFFFFF"))
        addView(ImageView(this@CloudBusinessActivity).apply {
            setImageResource(R.drawable.cloud_back)
            setColorFilter(cloudColor("#212121"))
            contentDescription = "返回"
            isClickable = true
            isFocusable = true
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@CloudBusinessActivity).apply {
            text = pageTitle
            textSize = 18f
            setTextColor(cloudColor("#212121"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun reload() = lifecycleScope.launch {
        loading.visibility = View.VISIBLE
        try {
            when (type) {
                "plans" -> renderPlans(loadPlansWithCache(), runCatching { api.subscription() }.getOrNull())
                "plan_detail" -> renderPlanDetails(api.planDetails(intent.getStringExtra("plan_id").orEmpty()))
                "payment" -> renderPayment(intent.getStringExtra("pay_url").orEmpty(), intent.getStringExtra("trade_no").orEmpty())
                "pay_success" -> renderPaymentSuccess(intent.getStringExtra("trade_no").orEmpty())
                "orders" -> renderOrders(api.orders())
                "invite" -> renderInvite(api.inviteSummary(), api.inviteDetails(), api.userInfo())
                "tickets" -> renderTickets(api.tickets())
                "ticket_detail" -> renderTicketConversation(api.ticketDetails(intent.getStringExtra("ticket_id").orEmpty()))
                "ticket_create" -> renderTicketCreate()
                "skin" -> renderSkin()
            }
        } catch (e: Throwable) {
            addText(e.message ?: "加载失败", 14f, "#B00020")
        } finally {
            loading.visibility = View.GONE
            applyCloudTypography(pageRoot)
        }
    }

    private fun renderSkin() {
        val store = UiStore(this)
        val styles = listOf(
            Triple(0, "❀", "极简风"),
            Triple(1, "✦", "公主风"),
            Triple(2, "♂", "男神风"),
            Triple(3, "★", "二次元"),
        )
        val styleNames = mapOf(0 to "极简风", 1 to "公主风", 2 to "男神风", 3 to "二次元")
        fun sectionTitle(icon: String, title: String, action: String? = null, onAction: (() -> Unit)? = null) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(8), dp(2), dp(10))
                addView(TextView(this@CloudBusinessActivity).apply {
                    text = "$icon  $title"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(cloudColor("#424242"))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (action != null) addView(TextView(this@CloudBusinessActivity).apply {
                    text = action; textSize = 13f; setTextColor(cloudPrimaryColor()); gravity = Gravity.CENTER
                    setPadding(dp(12), dp(6), dp(12), dp(6)); isClickable = true; setOnClickListener { onAction?.invoke() }
                })
            })
        }
        fun selectStyle(value: Int) {
            if (store.cloudThemeStyle == value) return
            store.cloudThemeStyle = value
            refreshAllThemedActivities()
            recreate()
        }
        fun optionCard(label: String, icon: String, selected: Boolean, click: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0)
            background = if (selected) GradientDrawable().apply {
                setColor(cloudSelectedContainerColor()); cornerRadius = dp(12).toFloat()
            } else rounded("#FFFFFF", 12, "#E0E0E0")
            isClickable = true; isFocusable = true; setOnClickListener { click() }
            addView(TextView(this@CloudBusinessActivity).apply {
                text = icon; textSize = 18f; setTextColor(if (selected) cloudSelectedTextColor() else cloudPrimaryColor())
            })
            addView(TextView(this@CloudBusinessActivity).apply {
                text = label; textSize = 14f; setPadding(dp(8), 0, 0, 0)
                setTextColor(if (selected) cloudSelectedTextColor() else cloudColor("#424242"))
            })
        }

        sectionTitle("▣", "主题预览", if (store.cloudThemeStyle != 0) "重置" else null) { selectStyle(0) }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14)); background = roundedCard()
            addView(LinearLayout(this@CloudBusinessActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(cloudPrimaryColor(), cloudSelectedContainerColor(), cloudColor("#E0E0E0"), cloudColor("#FFFFFF")).forEach { color ->
                    addView(View(this@CloudBusinessActivity).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(1), cloudColor("#E0E0E0")) }
                    }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
                }
            })
            addView(TextView(this@CloudBusinessActivity).apply {
                val mode = when (store.darkMode) { DarkMode.Auto -> "自动"; DarkMode.ForceLight -> "浅色"; DarkMode.ForceDark -> "深色" }
                text = "$mode   ${styleNames[store.cloudThemeStyle] ?: "极简风"}   ${if (store.pureBlack) "纯黑" else "标准"}"
                textSize = 12f; setTextColor(cloudSelectedTextColor()); setPadding(0, dp(10), 0, 0)
            })
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "字体风格：${listOf("Zen Maru Gothic", "Barlow Condensed", "Raleway", "ZCOOL KuaiLe")[store.cloudThemeStyle.coerceIn(0, 3)]}"
                textSize = 14f; setTextColor(cloudPrimaryColor()); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(12), 0, dp(2))
            })
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "The quick brown fox jumps over the lazy dog\n这是一段主题预览文本"
                textSize = 14f; setTextColor(cloudColor("#757575"))
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        sectionTitle("☀", "主题模式")
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@CloudBusinessActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(Triple(DarkMode.Auto, "◉", "自动"), Triple(DarkMode.ForceLight, "☀", "浅色"), Triple(DarkMode.ForceDark, "☾", "深色")).forEachIndexed { index, item ->
                    addView(optionCard(item.third, item.second, store.darkMode == item.first) {
                        store.darkMode = item.first; refreshAllThemedActivities(); recreate()
                    }, LinearLayout.LayoutParams(-2, dp(56)).apply { if (index > 0) marginStart = dp(16) })
                }
            })
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(16) })

        sectionTitle("◉", "主题风格")
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@CloudBusinessActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                styles.forEachIndexed { index, item ->
                    addView(optionCard(item.third, item.second, store.cloudThemeStyle == item.first) { selectStyle(item.first) },
                        LinearLayout.LayoutParams(-2, dp(56)).apply { if (index > 0) marginStart = dp(16) })
                }
            })
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(18) })

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(8))
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "◐"; textSize = 21f; setTextColor(cloudPrimaryColor())
            }, LinearLayout.LayoutParams(dp(36), -2))
            addView(LinearLayout(this@CloudBusinessActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@CloudBusinessActivity).apply { text = "纯黑模式"; textSize = 15f; setTextColor(cloudColor("#424242")) })
                addView(TextView(this@CloudBusinessActivity).apply { text = "深色模式下使用纯黑背景"; textSize = 12f; setTextColor(cloudColor("#757575")) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Switch(this@CloudBusinessActivity).apply {
                isChecked = store.pureBlack
                setOnCheckedChangeListener { _, checked ->
                    store.pureBlack = checked; refreshAllThemedActivities(); recreate()
                }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(4))
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "Aa"; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(cloudPrimaryColor())
            }, LinearLayout.LayoutParams(dp(36), -2))
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "文字缩放"; textSize = 15f; setTextColor(cloudColor("#424242"))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Switch(this@CloudBusinessActivity).apply {
                isChecked = store.cloudTextScaleEnabled
                setOnCheckedChangeListener { _, checked ->
                    store.cloudTextScaleEnabled = checked; refreshAllThemedActivities(); recreate()
                }
            })
        })
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(4), dp(8))
            addView(SeekBar(this@CloudBusinessActivity).apply {
                max = 60; progress = store.cloudTextScalePercent.coerceIn(80, 140) - 80
                isEnabled = store.cloudTextScaleEnabled
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) store.cloudTextScalePercent = progress + 80
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        refreshAllThemedActivities(); recreate()
                    }
                })
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "${store.cloudTextScalePercent.coerceIn(80, 140)}%"; textSize = 14f; gravity = Gravity.END; setTextColor(cloudColor("#424242"))
            }, LinearLayout.LayoutParams(dp(58), -2))
        })
    }

    private fun refreshAllThemedActivities() {
        ApplicationObserver.createdActivities.toList().forEach { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
        }
    }

    private suspend fun loadPlansWithCache(): JSONObject {
        val prefs = getSharedPreferences("cloud_business_cache", MODE_PRIVATE)
        return runCatching { api.plans() }
            .onSuccess { prefs.edit().putString("plans", it.toString()).apply() }
            .getOrElse { error ->
                val cached = prefs.getString("plans", "").orEmpty()
                if (cached.isBlank()) throw error
                JSONObject(cached)
            }
    }

    private fun renderPlans(json: JSONObject, subscription: CloudSubscription?) {
        val membershipSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), 0, dp(15), 0)
            addView(View(this@CloudBusinessActivity), LinearLayout.LayoutParams(1, dp(25)))
            addView(LinearLayout(this@CloudBusinessActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@CloudBusinessActivity).apply {
                    text = "套餐商店"; textSize = 18f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
                addView(LinearLayout(this@CloudBusinessActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                    isClickable = true; isFocusable = true; setOnClickListener { recreate() }
                    addView(ImageView(this@CloudBusinessActivity).apply {
                        setImageResource(DesignR.drawable.ic_baseline_sync); setColorFilter(cloudPrimaryColor()); scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }, LinearLayout.LayoutParams(dp(16), dp(16)))
                    addView(TextView(this@CloudBusinessActivity).apply {
                        text = "刷新"; textSize = 14f; setTextColor(cloudPrimaryColor()); setPadding(dp(6), 0, 0, 0)
                    })
                }, LinearLayout.LayoutParams(dp(82), dp(48)))
            })
            addView(View(this@CloudBusinessActivity), LinearLayout.LayoutParams(1, dp(16)))
            addView(memberCard(subscription), LinearLayout.LayoutParams(-1, -2))
            addView(View(this@CloudBusinessActivity), LinearLayout.LayoutParams(1, dp(20)))
        }
        content.addView(membershipSection, LinearLayout.LayoutParams(-1, -2))

        val list = json.optJSONArray("data") ?: JSONArray()
        val purchasablePlans = buildList {
            repeat(list.length()) { index ->
                val plan = list.optJSONObject(index) ?: return@repeat
                val hasPriceAtLeastTenYuan = listOf(
                    "month_price", "quarter_price", "half_year_price", "year_price",
                    "two_year_price", "three_year_price", "onetime_price",
                ).any { planPrice(plan, it) >= 1_000 }
                if (hasPriceAtLeastTenYuan) add(plan)
            }
        }

        val planList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), 0, dp(15), dp(10))
        }
        if (purchasablePlans.isEmpty()) {
            planList.addView(TextView(this).apply {
                text = "暂无可购买套餐"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(cloudColor("#757575"))
                setPadding(0, dp(32), 0, dp(32))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        purchasablePlans.forEach { plan ->
            val periods = listOf(
                "month_price" to "月付", "quarter_price" to "季付", "half_year_price" to "半年付",
                "year_price" to "年付", "onetime_price" to "一次性",
            ).filter { planPrice(plan, it.first) > 0 }
            planList.addView(planCard(plan, periods), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        content.addView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(planList)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun memberCard(subscription: CloudSubscription?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), dp(15), dp(15), dp(15))
        background = roundedCard()
        addView(LinearLayout(this@CloudBusinessActivity).apply {
            orientation = LinearLayout.VERTICAL
            val account = subscription?.email?.ifBlank { CloudSession(this@CloudBusinessActivity).email }
                ?: CloudSession(this@CloudBusinessActivity).email
            var revealed = false
            val accountView = TextView(this@CloudBusinessActivity).apply {
                text = maskBusinessEmail(account); textSize = 16f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.NORMAL)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val eye = ImageView(this@CloudBusinessActivity).apply {
                setImageResource(R.drawable.cloud_eye_opened); setColorFilter(cloudPrimaryColor()); scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true; isFocusable = true; contentDescription = "显示完整邮箱"
                setOnClickListener {
                    revealed = !revealed
                    accountView.text = if (revealed) account else maskBusinessEmail(account)
                    setImageResource(if (revealed) R.drawable.cloud_eye_closed else R.drawable.cloud_eye_opened)
                    setColorFilter(cloudPrimaryColor())
                    contentDescription = if (revealed) "隐藏完整邮箱" else "显示完整邮箱"
                }
            }
            addView(LinearLayout(this@CloudBusinessActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(accountView, LinearLayout.LayoutParams(0, -2, 1f))
                addView(eye, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(4) })
            })
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "到期时间：${formatExpiry(subscription?.expiresAt ?: 0)}"; textSize = 11f; setTextColor(cloudColor("#212121")); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
            })
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "当前套餐：${subscription?.plan?.ifBlank { "未开通" } ?: "未开通"}"; textSize = 11f; setTextColor(cloudColor("#757575")); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(this@CloudBusinessActivity).apply {
            setImageResource(R.drawable.cloud_vip_flag); scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(63), dp(30)))
    }

    private fun planCard(plan: JSONObject, periods: List<Pair<String, String>>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded("#FFFFFF", 12)
        elevation = dp(1).toFloat()
        isClickable = true; isFocusable = true
        setOnClickListener { openPlanDetails(plan.optString("id")) }
        addView(LinearLayout(this@CloudBusinessActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            addView(TextView(this@CloudBusinessActivity).apply {
                text = plan.optString("name", "套餐"); textSize = 16f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.BOLD); maxLines = 2
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (planPrice(plan, "onetime_price") > 0) addView(TextView(this@CloudBusinessActivity).apply {
                text = "限时返场"; textSize = 11f; setTextColor(Color.parseColor("#E67817")); gravity = Gravity.CENTER
                setPadding(dp(8), dp(3), dp(8), dp(3)); background = rounded("#FFF3E8", 99, "#F2C28F")
            })
        })
        val startingPrice = listOf(
            "onetime_price", "month_price", "quarter_price", "half_year_price",
            "year_price", "two_year_price", "three_year_price",
        ).firstNotNullOfOrNull { key -> planPrice(plan, key).takeIf { it > 0 } } ?: 0
        addView(TextView(this@CloudBusinessActivity).apply {
            text = "低至 ￥${money(startingPrice)}"; textSize = 14f; setTextColor(cloudErrorColor()); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END; setPadding(0, dp(6), 0, dp(10))
        })
        val rights = parseRights(plan.optString("content"))
        addView(CloudFlowLayout(this@CloudBusinessActivity, dp(8), dp(8)).apply {
            periods.forEach { period ->
                addView(TextView(this@CloudBusinessActivity).apply {
                    text = "${period.second} ￥${money(planPrice(plan, period.first))}"; textSize = 12f; setTextColor(cloudColor("#212121")); gravity = Gravity.CENTER
                    setPadding(dp(10), dp(5), dp(10), dp(5)); background = GradientDrawable().apply {
                        setColor(cloudPriceChipColor()); cornerRadius = dp(99).toFloat()
                    }
                }, ViewGroup.LayoutParams(-2, -2))
            }
        })
        addView(View(this@CloudBusinessActivity), LinearLayout.LayoutParams(1, dp(10)))
        rights.take(3).forEach { right ->
            addView(TextView(this@CloudBusinessActivity).apply {
                text = right; textSize = 13f; setTextColor(cloudColor("#424242")); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 0, 0, dp(4))
            })
        }
        if (rights.size > 3) addView(TextView(this@CloudBusinessActivity).apply {
            text = "还有 ${rights.size - 3} 项权益，点击查看详情"; textSize = 12f; setTextColor(cloudColor("#212121")); setPadding(0, dp(6), 0, 0)
        })
    }

    private fun openPlanDetails(planId: String) {
        startActivity(Intent(this, CloudBusinessActivity::class.java).putExtra("type", "plan_detail").putExtra("plan_id", planId))
    }

    private fun renderPlanDetails(response: JSONObject) {
        val raw = response.opt("data")
        val plan = when (raw) {
            is JSONObject -> raw
            is JSONArray -> raw.optJSONObject(0)
            else -> null
        } ?: return addText("商品详情加载失败", 14f, "#B00020")
        val periods = listOf(
            "onetime_price" to "一次性", "month_price" to "月付", "quarter_price" to "季付", "half_year_price" to "半年付",
            "year_price" to "年付", "two_year_price" to "两年付", "three_year_price" to "三年付",
        ).filter { planPrice(plan, it.first) > 0 }
        if (periods.isEmpty()) return addText("该商品暂无可购买周期")

        var selected = periods.first()
        var discount = 0L
        val coupon = EditText(this).apply {
            hint = "请输入优惠码"; isSingleLine = true
            setTextColor(cloudColor("#212121")); setHintTextColor(cloudColor("#757575"))
            typeface = Typeface.create("sans-serif-rounded", Typeface.NORMAL)
            background = rounded("#F5F5F5", 12, "#E0E0E0")
            setPadding(dp(13), 0, dp(13), 0)
        }
        val amount = TextView(this).apply { textSize = 16f; setTextColor(Color.parseColor("#ED492E")); setTypeface(typeface, Typeface.BOLD) }
        fun updateAmount() {
            val original = planPrice(plan, selected.first)
            val payable = (original - discount).coerceAtLeast(0)
            amount.text = if (discount > 0) "原价 ¥${money(original)}   应付 ¥${money(payable)}" else "应付金额：¥${money(payable)}"
        }

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); background = roundedCard()
            addView(TextView(this@CloudBusinessActivity).apply { text = plan.optString("name", "套餐"); textSize = 18f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(this@CloudBusinessActivity).apply { text = "低至 ¥${money(periods.minOf { planPrice(plan, it.first) })}"; textSize = 14f; setTextColor(Color.parseColor("#ED492E")); setPadding(0, dp(7), 0, dp(10)) })
            addView(planContentWebView(plan.optString("content")))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        if (periods.size > 1) content.addView(TextView(this).apply { text = "选择周期"; textSize = 16f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(8), 0, dp(10)) })
        val chips = GridLayout(this).apply { columnCount = 3 }
        fun redrawChips() {
            chips.removeAllViews()
            periods.forEachIndexed { index, period ->
                chips.addView(TextView(this).apply {
                    text = "${period.second}\n¥${money(planPrice(plan, period.first))}"; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(if (period == selected) cloudSelectedTextColor() else cloudColor("#212121")); setPadding(dp(6), dp(5), dp(6), dp(5))
                    background = if (period == selected) GradientDrawable().apply {
                        setColor(cloudSelectedContainerColor()); cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), cloudPrimaryColor())
                    } else rounded("#FFFFFF", 12, "#E0E0E0")
                    isClickable = true; setOnClickListener { selected = period; discount = 0; redrawChips(); updateAmount() }
                }, GridLayout.LayoutParams().apply {
                    width = 0; height = dp(58); columnSpec = GridLayout.spec(index % 3, 1f); rowSpec = GridLayout.spec(index / 3)
                    setMargins(0, 0, if (index % 3 < 2) dp(8) else 0, dp(8))
                })
            }
        }
        if (periods.size > 1) { redrawChips(); content.addView(chips) }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "优惠码"; textSize = 14f; setTextColor(cloudColor("#212121")); gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(dp(62), dp(48)))
            addView(coupon, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "验证"; gravity = Gravity.CENTER; setTextColor(cloudButtonTextColor()); background = rounded("#212121", 12); isClickable = true
                setOnClickListener { lifecycleScope.launch {
                    runCatching { api.checkCoupon(coupon.text.toString().trim(), plan.optString("id")) }.onSuccess { result ->
                        val data = result.optJSONObject("data")
                        val value = data?.optLong("value") ?: 0
                        discount = if (data?.optInt("type") == 2) planPrice(plan, selected.first) * value / 100 else value
                        if (result.isNull("data") || result.opt("data") == false) toast(IllegalStateException(result.optString("message", "优惠码无效"))) else { updateAmount(); Toast.makeText(this@CloudBusinessActivity, "优惠码可用", Toast.LENGTH_SHORT).show() }
                    }.onFailure { toast(it) }
                } }
            }, LinearLayout.LayoutParams(dp(76), dp(48)).apply { marginStart = dp(10) })
        })
        updateAmount()
        pageRoot.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12)); setBackgroundColor(cloudColor("#FFFFFF")); elevation = dp(8).toFloat()
            addView(amount, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "立即购买"; gravity = Gravity.CENTER; setTextColor(cloudButtonTextColor()); background = rounded("#212121", 18); isClickable = true
                setOnClickListener {
                    lifecycleScope.launch {
                        startPurchase(
                            planId = plan.optString("id"),
                            period = selected.first,
                            coupon = coupon.text.toString().trim(),
                            originalAmount = planPrice(plan, selected.first),
                        )
                    }
                }
            }, LinearLayout.LayoutParams(dp(108), dp(42)))
        }, LinearLayout.LayoutParams(-1, dp(72)))
    }

    private fun planContentWebView(raw: String): View {
        val estimatedHeight = (parseRights(raw).size.coerceIn(2, 9) * 34 + 28)
        val htmlTextColor = String.format("#%06X", 0xFFFFFF and cloudColor("#424242"))
        val htmlLinkColor = String.format("#%06X", 0xFFFFFF and cloudPrimaryColor())
        val webFont = when (UiStore(this).cloudThemeStyle) {
            0 -> "zen_maru_gothic_regular.ttf"
            1 -> "barlow_condensed_regular.ttf"
            2 -> "raleway_variable.ttf"
            3 -> "zcool_kuaile_regular.ttf"
            else -> null
        }
        val fontFace = webFont?.let { "@font-face{font-family:SkinFont;src:url('$it')}" }.orEmpty()
        val fontFamily = if (webFont == null) "sans-serif" else "SkinFont,sans-serif"
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
            <style>$fontFace html,body{margin:0;padding:0;background:transparent;color:$htmlTextColor;font-family:$fontFamily;font-size:14px;line-height:1.75}p{margin:4px 0}a{color:$htmlLinkColor}img{max-width:100%;height:auto;border-radius:8px}ul,ol{padding-left:22px;margin:6px 0}table{max-width:100%;border-collapse:collapse}td,th{padding:6px;border:1px solid currentColor}</style>
            </head><body>$raw</body></html>
        """.trimIndent()
        return WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript("document.documentElement.scrollHeight.toString()") { value ->
                        val cssHeight = value.trim('"').toFloatOrNull() ?: return@evaluateJavascript
                        view.layoutParams = view.layoutParams.apply {
                            height = dp(cssHeight.toInt().coerceIn(120, 1200))
                        }
                    }
                }
            }
            loadDataWithBaseURL("file:///android_res/font/", html, "text/html", "UTF-8", null)
            layoutParams = LinearLayout.LayoutParams(-1, dp(estimatedHeight))
        }
    }

    @Suppress("DEPRECATION")
    private fun parseRights(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val normalized = raw
            .replace("\n", "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("&#x2714;?", RegexOption.IGNORE_CASE), "✅")
        return normalized.lines()
            .map { Html.fromHtml(it).toString().trim() }
            .filter { it.isNotEmpty() && Regex("^[①②③④⑤⑥⑦⑧⑨]").containsMatchIn(it) }
    }

    private fun maskBusinessEmail(value: String): String {
        val parts = value.split('@')
        return if (parts.size == 2 && parts[0].length > 3) "${parts[0].take(3)}****@${parts[1]}" else value
    }

    private fun formatExpiry(timestamp: Long): String = when {
        timestamp <= 0 -> "不限时长"
        timestamp * 1000 < System.currentTimeMillis() -> "已过期"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp * 1000))
    }

    private suspend fun startPurchase(planId: String, period: String, coupon: String, originalAmount: Long) {
        if (creatingOrder) return
        creatingOrder = true
        try {
            val orders = api.orders().optJSONArray("data") ?: JSONArray()
            val hasPendingOrder = (0 until orders.length()).any {
                orders.optJSONObject(it)?.optInt("status", -1) == 0
            }
            if (hasPendingOrder) {
                AlertDialog.Builder(this)
                    .setTitle("存在待支付订单")
                    .setMessage("请先处理已有的待支付订单，再购买新的套餐。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("查看订单") { _, _ -> openBusinessPage("orders") }
                    .show()
                return
            }

            val methods = api.paymentMethods().optJSONArray("data") ?: JSONArray()
            val method = methods.optJSONObject(methods.length() - 1)
                ?: throw IllegalStateException("暂无可用支付方式")
            val methodId = method.optString("id")
            if (methodId.isBlank()) throw IllegalStateException("支付方式不可用")

            val orderResponse = api.createOrder(planId, period, coupon)
            val tradeNo = when (val data = orderResponse.opt("data")) {
                is JSONObject -> data.optString("trade_no")
                null, JSONObject.NULL -> ""
                else -> data.toString()
            }
            if (tradeNo.isBlank()) throw IllegalStateException(orderResponse.optString("message", "订单创建失败"))
            val detailsResponse = api.orderDetails(tradeNo)
            val details = detailsResponse.optJSONObject("data") ?: JSONObject()
            showOrderConfirmation(
                tradeNo = tradeNo,
                methodId = methodId,
                originalAmount = originalAmount,
                discountAmount = details.optLong("discount_amount"),
                surplusAmount = details.optLong("surplus_amount"),
                balanceAmount = details.optLong("balance_amount"),
                totalAmount = details.optLong("total_amount", originalAmount),
            )
        } catch (e: Throwable) {
            toast(e)
        } finally {
            creatingOrder = false
        }
    }

    private fun showOrderConfirmation(
        tradeNo: String,
        methodId: String,
        originalAmount: Long,
        discountAmount: Long,
        surplusAmount: Long,
        balanceAmount: Long,
        totalAmount: Long,
    ) {
        val dialog = BottomSheetDialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(28))
            setBackgroundColor(cloudColor("#FFFFFF"))
            addView(LinearLayout(this@CloudBusinessActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@CloudBusinessActivity).apply {
                    text = "确认订单"; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
                    setTextColor(cloudColor("#212121")); gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(0, dp(52), 1f))
                addView(TextView(this@CloudBusinessActivity).apply {
                    text = "×"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(cloudColor("#757575"))
                    isClickable = true; setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
            })
            fun amountRow(label: String, value: Long, emphasized: Boolean = false) =
                LinearLayout(this@CloudBusinessActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@CloudBusinessActivity).apply {
                        text = label; textSize = 14f; setTextColor(cloudColor("#616161"))
                    }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
                    addView(TextView(this@CloudBusinessActivity).apply {
                        text = "¥${money(value)}"; textSize = if (emphasized) 16f else 14f
                        setTextColor(if (emphasized) cloudErrorColor() else cloudColor("#212121"))
                        if (emphasized) setTypeface(typeface, Typeface.BOLD)
                    })
                }
            addView(amountRow("订单金额", originalAmount))
            if (discountAmount > 0) addView(amountRow("优惠券抵扣", discountAmount))
            if (surplusAmount > 0) addView(amountRow("旧订阅折抵", surplusAmount))
            if (balanceAmount > 0) addView(amountRow("余额抵扣", balanceAmount))
            addView(View(this@CloudBusinessActivity).apply {
                setBackgroundColor(cloudColor("#E0E0E0"))
            }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
            addView(amountRow("实付金额", totalAmount, true))
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "立即支付"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(cloudButtonTextColor())
                background = rounded("#212121", 22); isClickable = true; isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    lifecycleScope.launch { checkoutAndOpen(tradeNo, methodId) }
                }
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(18) })
        }
        applyCloudTypography(sheet)
        dialog.setContentView(sheet)
        dialog.show()
    }

    private suspend fun checkoutAndOpen(tradeNo: String, methodId: String) {
        try {
            val response = api.checkout(tradeNo, methodId)
            val url = response.opt("data")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
            if (response.optInt("type") == -1) {
                openPaymentSuccess(tradeNo)
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                startActivity(Intent(this, CloudBusinessActivity::class.java)
                    .putExtra("type", "payment")
                    .putExtra("pay_url", url)
                    .putExtra("trade_no", tradeNo))
            } else {
                throw IllegalStateException(response.optString("message", "支付地址异常"))
            }
        } catch (e: Throwable) {
            toast(e)
        }
    }

    private fun openBusinessPage(pageType: String) {
        startActivity(Intent(this, CloudBusinessActivity::class.java).putExtra("type", pageType))
    }

    private fun openPaymentSuccess(tradeNo: String) {
        startActivity(Intent(this, CloudBusinessActivity::class.java)
            .putExtra("type", "pay_success")
            .putExtra("trade_no", tradeNo))
    }

    private fun renderPayment(payUrl: String, tradeNo: String) {
        content.removeAllViews()
        if (!payUrl.startsWith("http://") && !payUrl.startsWith("https://")) {
            addText("支付地址异常", 15f, "#B00020")
            return
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        val webView = WebView(this).apply {
            setBackgroundColor(cloudColor("#FFFFFF"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    val upper = target.uppercase()
                    if (upper.contains("TRADE_STATUS=TRADE_SUCCES") || upper.contains("TRADE_STATUS=TRADE_SUCCESS")) {
                        openPaymentSuccess(tradeNo)
                        finish()
                        return true
                    }
                    if (upper.contains("TRADE_STATUS=TRADE_FAILED") || upper.contains("TRADE_STATUS=TRADE_CLOSED") || upper.contains("PAY_STATUS=FAILED")) {
                        Toast.makeText(this@CloudBusinessActivity, "支付未完成", Toast.LENGTH_LONG).show()
                        finish()
                        return true
                    }
                    if (target.startsWith("https://wx.tenpay.com") && request.requestHeaders["Referer"] != "H5 Referer") {
                        view.loadUrl(target, mapOf("Referer" to "H5 Referer"))
                        return true
                    }
                    if (target.startsWith("http://") || target.startsWith("https://")) return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                        .onFailure { Toast.makeText(this@CloudBusinessActivity, "未找到可处理此支付方式的应用", Toast.LENGTH_LONG).show() }
                    return true
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        Toast.makeText(this@CloudBusinessActivity, "支付页面加载失败，可返回后重试", Toast.LENGTH_LONG).show()
                    }
                }
            }
            loadUrl(payUrl)
        }
        content.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun renderPaymentSuccess(tradeNo: String) {
        content.removeAllViews()
        content.gravity = Gravity.CENTER
        getSharedPreferences("cloud_payment", MODE_PRIVATE).edit().putBoolean("refresh_pending", true).apply()
        val status = TextView(this).apply {
            text = "正在同步会员信息…"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(cloudColor("#757575")); setPadding(dp(24), dp(14), dp(24), 0)
        }
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.cloud_succeed)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(58), dp(58)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        content.addView(TextView(this).apply {
            text = "支付成功"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(cloudColor("#212121"))
            setPadding(0, dp(12), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(status, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(this).apply {
            text = "返回仪表盘"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(cloudColor("#212121"))
            background = rounded("#FFFFFF", 8, "#9E9E9E"); isClickable = true; isFocusable = true
            setOnClickListener {
                getSharedPreferences("cloud_payment", MODE_PRIVATE).edit().putBoolean("refresh_pending", true).apply()
                startActivity(Intent(this@CloudBusinessActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }, LinearLayout.LayoutParams(dp(150), dp(42)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(24) })
        lifecycleScope.launch {
            var synchronized = false
            repeat(15) { attempt ->
                if (!synchronized) {
                    val order = runCatching { api.orderDetails(tradeNo) }.getOrNull()?.optJSONObject("data")
                    if (order != null && order.optInt("status", 0) != 0) {
                        synchronized = runCatching { api.subscription() }.isSuccess
                    }
                    if (!synchronized && attempt < 14) delay(2_000)
                }
            }
            status.text = if (synchronized) "会员信息已同步" else "后台仍在处理中，返回仪表盘后将继续刷新"
        }
    }

    private fun renderOrders(json: JSONObject) {
        val list = json.optJSONArray("data") ?: JSONArray()
        if (list.length() == 0) {
            addText("▧", 38f, "#9E9E9E")
            addText("暂无订单", 16f, "#424242")
            return
        }
        repeat(list.length()) { i ->
            val order = list.optJSONObject(i) ?: return@repeat
            val plan = order.optJSONObject("plan")
            val status = when (order.optInt("status")) { 0 -> "待支付"; 2 -> "已取消"; else -> "已完成" }
            val statusColor = when (order.optInt("status")) { 0 -> "#212121"; 2 -> "#9E9E9E"; else -> "#FF9800" }
            val tradeNo = order.optString("trade_no")
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(12), dp(10), dp(10)); background = roundedCard()
                addView(LinearLayout(this@CloudBusinessActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@CloudBusinessActivity).apply { text = plan?.optString("name") ?: "订单"; textSize = 16f; setTextColor(cloudColor("#212121")); maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(this@CloudBusinessActivity).apply { text = status; textSize = 14f; setTextColor(cloudColor(statusColor)); setPadding(dp(8), 0, 0, 0) })
                })
                fun line(label: String, value: String) = TextView(this@CloudBusinessActivity).apply { text = "$label：$value"; textSize = 13f; setTextColor(cloudColor("#616161")); setPadding(0, dp(5), 0, 0) }
                addView(line("订单号", tradeNo))
                addView(line("下单时间", formatTimestamp(order.optLong("updated_at", order.optLong("created_at")))))
                addView(line("订单金额", "¥${money(orderOriginalAmount(order))}"))
                if (order.optLong("discount_amount") > 0) addView(line("优惠金额（优惠券）", "¥${money(order.optLong("discount_amount"))}"))
                if (order.optLong("surplus_amount") > 0) addView(line("旧订阅折抵金额", "¥${money(order.optLong("surplus_amount"))}"))
                if (order.optLong("balance_amount") > 0) addView(line("余额", "¥${money(order.optLong("balance_amount"))}"))
                addView(line("支付金额", "¥${money(order.optLong("total_amount"))}"))
                if (order.optInt("status") == 0) addView(LinearLayout(this@CloudBusinessActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(12), 0, dp(10))
                    addView(actionButton("取消", false) { confirmCancel(tradeNo) }, LinearLayout.LayoutParams(dp(100), dp(34)))
                    addView(actionButton("付款", true) { lifecycleScope.launch { payExisting(tradeNo) } }, LinearLayout.LayoutParams(dp(100), dp(34)).apply { marginStart = dp(20) })
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun orderOriginalAmount(order: JSONObject): Long {
        val plan = order.optJSONObject("plan") ?: return order.optLong("total_amount")
        return plan.optLong(order.optString("period"), order.optLong("total_amount"))
    }

    private fun actionButton(label: String, primary: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 14f; isClickable = true; setOnClickListener { click() }
        setTextColor(if (primary) cloudButtonTextColor() else cloudColor("#424242"))
        background = rounded(if (primary) "#212121" else "#FFFFFF", 19, if (primary) null else "#9E9E9E")
        layoutParams = LinearLayout.LayoutParams(dp(100), dp(38))
    }

    private fun showOrderDetails(tradeNo: String) = lifecycleScope.launch {
        runCatching { api.orderDetails(tradeNo) }.onSuccess { response ->
            val order = response.optJSONObject("data") ?: response
            val plan = order.optJSONObject("plan")?.optString("name").orEmpty()
            val detail = buildString {
                if (plan.isNotBlank()) append("套餐：$plan\n")
                append("订单号：${order.optString("trade_no", tradeNo)}\n")
                append("金额：¥${money(order.optLong("total_amount"))}\n")
                append("创建时间：${order.optString("created_at", "-")}")
            }
            AlertDialog.Builder(this@CloudBusinessActivity).setTitle("订单详情").setMessage(detail).setPositiveButton("关闭", null).show()
        }.onFailure { toast(it) }
    }

    private suspend fun payExisting(tradeNo: String) {
        try {
            val methods = api.paymentMethods().optJSONArray("data") ?: JSONArray()
            val names = Array(methods.length()) { methods.optJSONObject(it)?.optString("name") ?: "支付" }
            AlertDialog.Builder(this).setTitle("选择支付方式").setItems(names) { _, which ->
                lifecycleScope.launch {
                    try {
                        val method = methods.optJSONObject(which)?.optString("id").orEmpty()
                        val pay = api.checkout(tradeNo, method)
                        val url = pay.opt("data")?.toString().orEmpty()
                        if (url.startsWith("http")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        else Toast.makeText(this@CloudBusinessActivity, "支付请求已提交", Toast.LENGTH_LONG).show()
                    } catch (e: Throwable) { toast(e) }
                }
            }.show()
        } catch (e: Throwable) { toast(e) }
    }

    private fun confirmCancel(tradeNo: String) {
        AlertDialog.Builder(this).setMessage("取消此订单？").setNegativeButton("返回", null).setPositiveButton("取消订单") { _, _ ->
            lifecycleScope.launch { try { api.cancelOrder(tradeNo); recreate() } catch (e: Throwable) { toast(e) } }
        }.show()
    }

    private fun renderInvite(summary: JSONObject, details: JSONObject, info: JSONObject) {
        val data = summary.optJSONObject("data")
        val stats = data?.optJSONArray("stat") ?: JSONArray()
        fun heading(value: String) = TextView(this).apply {
            text = value; textSize = 16f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START; setPadding(0, dp(8), 0, dp(10))
        }
        content.addView(heading("我的返利"))
        val commission = if (stats.length() > 4) stats.optLong(4) else info.optJSONObject("data")?.optLong("commission_balance", 0) ?: 0
        val statCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); background = roundedCard()
        }
        val statRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("邀请用户", "可提现佣金", "确认中佣金", "佣金比例").forEachIndexed { index, label ->
            val raw = when (index) { 1 -> commission.toDouble(); else -> if (index < stats.length()) stats.optDouble(index, 0.0) else 0.0 }
            statRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(TextView(this@CloudBusinessActivity).apply { text = if (index == 3) "${raw.toInt()}%" else if (index == 0) raw.toInt().toString() else String.format(java.util.Locale.US, "%.2f", raw / 100.0); textSize = 14f; setTextColor(cloudColor("#212121")) })
                addView(TextView(this@CloudBusinessActivity).apply { text = label; textSize = 14f; setTextColor(cloudColor("#212121")); setPadding(0, dp(4), 0, 0) })
            }, LinearLayout.LayoutParams(0, dp(76), 1f))
        }
        statCard.addView(statRow)
        if (commission > 0) statCard.addView(actionButton("提现到余额", true) { confirmCommissionTransfer(commission) }, LinearLayout.LayoutParams(dp(110), dp(38)).apply { gravity = Gravity.END; topMargin = dp(6) })
        content.addView(statCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        content.addView(heading("我的邀请码"))
        val codes = data?.optJSONArray("codes") ?: data?.optJSONArray("code")
        var newestCode = ""
        var newestTime = Long.MIN_VALUE
        if (codes != null) repeat(codes.length()) { index ->
            val item = codes.optJSONObject(index) ?: return@repeat
            val time = item.optLong("updated_at", item.optLong("created_at"))
            if (item.optString("code").isNotBlank() && time >= newestTime) { newestTime = time; newestCode = item.optString("code") }
        }
        val inviteCode = data?.optString("invite_code").orEmpty().ifBlank { newestCode }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(10), dp(10)); background = roundedCard()
            addView(TextView(this@CloudBusinessActivity).apply { text = inviteCode.ifBlank { "尚未生成" }; textSize = 16f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(actionButton(if (inviteCode.isBlank()) "生成邀请码" else "复制邀请码", true) {
                if (inviteCode.isBlank()) lifecycleScope.launch { runCatching { api.createInviteCode() }.onSuccess { recreate() }.onFailure { toast(it) } }
                else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("邀请码", inviteCode))
                    Toast.makeText(this@CloudBusinessActivity, "邀请码已复制", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(dp(112), dp(40)))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(20) })
        val users = details.optJSONArray("data") ?: JSONArray()
        content.addView(heading("邀请返佣明细"))
        val records = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); background = roundedCard()
            addView(LinearLayout(this@CloudBusinessActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@CloudBusinessActivity).apply { text = "返佣记录"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(cloudColor("#212121")) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@CloudBusinessActivity).apply { text = "共 ${users.length()} 笔"; textSize = 12f; setTextColor(cloudColor("#757575")) })
            })
        }
        if (users.length() == 0) records.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(34), 0, dp(34))
            addView(TextView(this@CloudBusinessActivity).apply { text = "▤"; textSize = 40f; setTextColor(cloudColor("#9E9E9E")); gravity = Gravity.CENTER })
            addView(TextView(this@CloudBusinessActivity).apply { text = "暂无返佣明细"; textSize = 14f; setTextColor(cloudColor("#757575")); gravity = Gravity.CENTER })
            addView(TextView(this@CloudBusinessActivity).apply { text = "好友购买套餐后，返佣记录会显示在这里"; textSize = 12f; setTextColor(cloudColor("#757575")); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        })
        repeat(users.length()) { index ->
            val invited = users.optJSONObject(index) ?: return@repeat
            records.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded("#EEEEEE", 14)
                addView(TextView(this@CloudBusinessActivity).apply { text = "￥"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(cloudSelectedTextColor()); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cloudSelectedContainerColor()) } }, LinearLayout.LayoutParams(dp(38), dp(38)))
                addView(LinearLayout(this@CloudBusinessActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                    addView(TextView(this@CloudBusinessActivity).apply { text = "返佣到账"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(cloudColor("#212121")) })
                    addView(TextView(this@CloudBusinessActivity).apply { text = formatTimestamp(invited.optLong("created_at")); textSize = 12f; setTextColor(cloudColor("#757575")); setPadding(0, dp(3), 0, 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@CloudBusinessActivity).apply { text = "+￥${money(invited.optLong("get_amount"))}"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#2E9D57")) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        content.addView(records)
    }

    private fun confirmCommissionTransfer(amount: Long) {
        AlertDialog.Builder(this).setTitle("提现到余额").setMessage("确认将可提现佣金 ¥${money(amount)} 转入账户余额？")
            .setNegativeButton("取消", null).setPositiveButton("确认提现") { _, _ ->
                lifecycleScope.launch { runCatching { api.transferCommission(amount) }.onSuccess { Toast.makeText(this@CloudBusinessActivity, "提现成功", Toast.LENGTH_SHORT).show(); recreate() }.onFailure { toast(it) } }
            }.show()
    }

    private fun renderTickets(json: JSONObject) {
        pageRoot.addView(TextView(this).apply {
            text = "+  创建工单"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(cloudButtonTextColor())
            background = rounded("#212121", 24); elevation = dp(6).toFloat(); isClickable = true
            setOnClickListener { startActivity(Intent(this@CloudBusinessActivity, CloudBusinessActivity::class.java).putExtra("type", "ticket_create")) }
        }, LinearLayout.LayoutParams(dp(128), dp(48)).apply { gravity = Gravity.END; marginEnd = dp(16); bottomMargin = dp(16) })
        val list = json.optJSONArray("data") ?: JSONArray()
        if (list.length() == 0) {
            addText("◌", 38f, "#9E9E9E")
            addText("暂无工单\n遇到问题时，可以创建工单联系我们", 14f, "#757575")
            return
        }
        repeat(list.length()) { i ->
            val item = list.optJSONObject(i) ?: return@repeat
            val level = when (item.optInt("level")) { 0 -> "低"; 2 -> "高"; else -> "中" }
            val closed = item.optInt("status") == 1
            val state = if (closed) "已关闭" else if (item.optInt("reply_status") == 1) "等待回复" else "已回复"
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(15), dp(13), dp(10), dp(13)); background = roundedCard(); isClickable = true
                setOnClickListener { startActivity(Intent(this@CloudBusinessActivity, CloudBusinessActivity::class.java).putExtra("type", "ticket_detail").putExtra("ticket_id", item.optString("id"))) }
                addView(LinearLayout(this@CloudBusinessActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@CloudBusinessActivity).apply { text = item.optString("subject", "工单 #${item.optString("id")}"); textSize = 15f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.BOLD); maxLines = 2 })
                    addView(TextView(this@CloudBusinessActivity).apply { text = "$level · $state\n${formatTimestamp(item.optLong("updated_at", item.optLong("created_at")))}"; textSize = 12f; setTextColor(cloudColor("#757575")); setPadding(0, dp(6), 0, 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@CloudBusinessActivity).apply { text = "›"; textSize = 24f; setTextColor(cloudColor("#9E9E9E")); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(30), dp(42)))
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun renderTicketConversation(response: JSONObject) {
        val ticket = response.optJSONObject("data") ?: return addText("工单详情加载失败", 14f, "#B00020")
        val closed = ticket.optInt("status") == 1
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@CloudBusinessActivity).apply { text = ticket.optString("subject", "工单详情"); textSize = 16f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            if (!closed) addView(actionButton("关闭工单", false) { confirmCloseTicket(ticket.optString("id")) }, LinearLayout.LayoutParams(dp(100), dp(38)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val messages = ticket.optJSONArray("message") ?: JSONArray()
        if (messages.length() == 0) addText("暂无消息")
        repeat(messages.length()) { index ->
            val message = messages.optJSONObject(index) ?: return@repeat
            val mine = message.optBoolean("is_me")
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(8)); background = if (mine) GradientDrawable().apply {
                    setColor(cloudSelectedContainerColor()); cornerRadius = dp(12).toFloat()
                } else rounded("#EEEEEE", 12)
                addView(TextView(this@CloudBusinessActivity).apply { text = message.optString("message"); textSize = 14f; setTextColor(cloudColor("#212121")) })
                addView(TextView(this@CloudBusinessActivity).apply { text = formatTimestamp(message.optLong("created_at")); textSize = 11f; setTextColor(cloudColor("#757575")); setPadding(0, dp(5), 0, 0) })
            }, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.78f).toInt(), -2).apply { gravity = if (mine) Gravity.END else Gravity.START; bottomMargin = dp(10) })
        }
        if (!closed) {
            val reply = EditText(this).apply { hint = "请输入回复内容"; minLines = 1; maxLines = 4; background = rounded("#FFFFFF", 12, "#BDBDBD"); setPadding(dp(12), dp(8), dp(12), dp(8)) }
            pageRoot.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(cloudColor("#FFFFFF")); elevation = dp(8).toFloat()
                addView(reply, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@CloudBusinessActivity).apply {
                    text = "发送"; gravity = Gravity.CENTER; setTextColor(cloudButtonTextColor()); background = rounded("#212121", 20); isClickable = true
                    setOnClickListener { val value = reply.text.toString().trim(); if (value.isNotEmpty()) lifecycleScope.launch { runCatching { api.replyTicket(ticket.optString("id"), value) }.onSuccess { recreate() }.onFailure { toast(it) } } }
                }, LinearLayout.LayoutParams(dp(72), dp(42)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun confirmCloseTicket(id: String) {
        AlertDialog.Builder(this).setTitle("关闭工单").setMessage("关闭后将无法继续回复，确定关闭？").setNegativeButton("取消", null).setPositiveButton("关闭") { _, _ ->
            lifecycleScope.launch { runCatching { api.closeTicket(id) }.onSuccess { recreate() }.onFailure { toast(it) } }
        }.show()
    }

    private fun renderTicketCreate() {
        val subject = EditText(this).apply { hint = "补充工单主题"; isSingleLine = true; background = rounded("#FFFFFF", 12, "#BDBDBD"); setPadding(dp(12), 0, dp(12), 0) }
        val message = EditText(this).apply { hint = "请详细描述遇到的问题"; minLines = 6; gravity = Gravity.TOP; background = rounded("#FFFFFF", 12, "#BDBDBD"); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        var level = 1
        val levels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun redraw() {
            levels.removeAllViews()
            listOf("低", "中", "高").forEachIndexed { index, label ->
                levels.addView(TextView(this).apply {
                    text = label; gravity = Gravity.CENTER; textSize = 14f; isClickable = true
                    setTextColor(if (level == index) cloudSelectedTextColor() else cloudColor("#212121"))
                    background = if (level == index) GradientDrawable().apply { setColor(cloudSelectedContainerColor()); cornerRadius = dp(12).toFloat() } else rounded("#FFFFFF", 12, "#D5D5D5")
                    setOnClickListener { level = index; redraw() }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (index > 0) marginStart = dp(8) })
            }
        }
        fun label(value: String) = TextView(this).apply { text = value; textSize = 15f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(14), 0, dp(8)) }
        content.addView(label("主题")); content.addView(subject, LinearLayout.LayoutParams(-1, dp(50)))
        content.addView(label("优先级")); redraw(); content.addView(levels)
        content.addView(label("问题描述")); content.addView(message, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(this).apply {
            text = "提交工单"; gravity = Gravity.CENTER; textSize = 15f; setTextColor(cloudButtonTextColor()); background = rounded("#212121", 22); isClickable = true
            setOnClickListener {
                val suffix = subject.text.toString().trim(); val body = message.text.toString().trim()
                val title = "[Android ${BuildConfig.VERSION_NAME}]${if (suffix.isBlank()) "" else " $suffix"}"
                if (title.isBlank() || body.isBlank()) toast(IllegalArgumentException("请填写完整的主题和问题描述"))
                else lifecycleScope.launch { runCatching { api.createTicket(title, level, body) }.onSuccess {
                    Toast.makeText(this@CloudBusinessActivity, "工单已提交", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@CloudBusinessActivity, CloudBusinessActivity::class.java).putExtra("type", "tickets").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                }.onFailure { toast(it) } }
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(22) })
    }

    private fun addCard(title: String, subtitle: String, click: () -> Unit) {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13)); isClickable = true; isFocusable = true
            background = roundedCard()
            setOnClickListener { click() }
            setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.035f else 1f).scaleY(if (focused) 1.035f else 1f).setDuration(120).start()
                (view.background as? GradientDrawable)?.setStroke(dp(if (focused) 3 else 1), cloudColor(if (focused) "#212121" else "#E0E0E0"))
            }
            addView(TextView(this@CloudBusinessActivity).apply { text = title; textSize = 16f; setTextColor(cloudColor("#212121")); setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(this@CloudBusinessActivity).apply { text = subtitle; textSize = 13f; setTextColor(cloudColor("#757575")); setPadding(0, dp(6), 0, 0) })
        }
        content.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun addText(value: String, size: Float = 14f, color: String = "#757575") {
        content.addView(TextView(this).apply { text = value; textSize = size; setTextColor(cloudColor(color)); gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18)) })
    }
    private fun money(cents: Long) = String.format(java.util.Locale.US, "%.2f", cents / 100.0)
    private fun planPrice(plan: JSONObject, key: String): Long {
        val raw = plan.opt(key)
        return when (raw) {
            is Number -> raw.toDouble().toLong()
            is String -> raw.trim().toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }.coerceAtLeast(0L)
    }
    private fun formatTimestamp(timestamp: Long) = if (timestamp <= 0) "-" else
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp * 1000))
    private fun roundedCard() = GradientDrawable().apply { setColor(cloudColor("#FFFFFF")); cornerRadius = dp(14).toFloat(); setStroke(dp(1), cloudColor("#E0E0E0")) }
    private fun rounded(fill: String, radius: Int, stroke: String? = null) = GradientDrawable().apply {
        setColor(if (fill == "#212121") cloudPrimaryColor() else cloudColor(fill)); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), if (it == "#212121") cloudPrimaryColor() else cloudColor(it)) }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(e: Throwable) = Toast.makeText(this, e.message ?: "操作失败", Toast.LENGTH_LONG).show()
}
