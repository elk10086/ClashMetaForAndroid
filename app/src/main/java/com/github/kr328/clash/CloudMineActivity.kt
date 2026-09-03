package com.github.kr328.clash

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.GridLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.cloud.CloudApi
import com.github.kr328.clash.cloud.CloudSession
import com.github.kr328.clash.cloud.CloudSubscription
import com.github.kr328.clash.cloud.CloudReleaseManager
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import com.github.kr328.clash.design.R as DesignR

class CloudMineActivity : AppCompatActivity() {
    private lateinit var expiryView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(DesignR.string.cloud_nav_mine)
        val root = vertical().apply {
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(dp(18), dp(35), dp(18), dp(24))
            addView(LinearLayout(this@CloudMineActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@CloudMineActivity).apply {
                    setImageResource(R.drawable.cloud_avatar); scaleType = ImageView.ScaleType.CENTER_CROP
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(TextView(this@CloudMineActivity).apply {
                    text = maskEmail(CloudSession(this@CloudMineActivity).email)
                    textSize = 16f; setTextColor(Color.parseColor("#212121")); setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            })
            addView(vipBanner())
            addView(actionRow("检查更新", emphasized = true) {
                lifecycleScope.launch { runCatching { CloudReleaseManager.checkUpdate(this@CloudMineActivity) }.onFailure { toast(it) } }
            })
            addView(actionRow("我的订单") { open("orders") })
            addView(actionRow("邀请返利") { open("invite") })
            addView(actionRow("意见反馈") { open("tickets") })
            addView(actionRow("线上客服", customer = true) {
                lifecycleScope.launch {
                    runCatching { CloudReleaseManager.customerServiceUrl(this@CloudMineActivity) }
                        .onSuccess { url -> if (url.startsWith("http")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) else toast(IllegalStateException("客服地址不可用")) }
                        .onFailure { toast(it) }
                }
            })
            addView(actionRow("刷新服务配置") { confirmRefreshServiceConfig() })
            addView(actionRow("主题") {
                startActivity(Intent(this@CloudMineActivity, AppSettingsActivity::class.java))
            })
            addView(actionRow("设置") {
                startActivity(Intent(this@CloudMineActivity, SettingsActivity::class.java))
            })
            addView(View(this@CloudMineActivity), LinearLayout.LayoutParams(1, dp(24)))
            addView(logoutButton())
        }
        setContentView(vertical().apply {
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            addView(ScrollView(this@CloudMineActivity).apply { addView(root) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(
                CloudBottomNavigation.create(this@CloudMineActivity, CloudBottomNavigation.Tab.MINE),
                LinearLayout.LayoutParams(-1, dp(80)),
            )
        })
        lifecycleScope.launch {
            runCatching { CloudApi(this@CloudMineActivity).subscription() }.onSuccess { subscription ->
                val text = if (subscription.expiresAt > 0) {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(subscription.expiresAt * 1000))
                } else "不限时长"
                expiryView.text = "到期时间：$text"
            }
        }
    }

    private fun open(type: String) = startActivity(
        Intent(this, CloudBusinessActivity::class.java).putExtra("type", type),
    )

    private fun logoutButton(): View = TextView(this).apply {
        text = getString(DesignR.string.cloud_logout)
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#3257FF"), Color.parseColor("#24D4F3"))).apply {
            cornerRadius = dp(25).toFloat()
        }
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
            .setTitle("刷新服务配置")
            .setMessage("清除当前缓存并重新获取远端服务地址？")
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
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            Color.parseColor("#3D2E24"), Color.parseColor("#63483D"), Color.parseColor("#BA987A"),
        )).apply { cornerRadius = dp(8).toFloat() }
        isClickable = true; isFocusable = true; setOnClickListener { open("plans") }
        addView(vertical().apply {
            addView(TextView(this@CloudMineActivity).apply { text = "VIP会员"; textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
            expiryView = TextView(this@CloudMineActivity).apply { text = "到期时间：加载中…"; textSize = 11f; setTextColor(Color.WHITE); setPadding(0, dp(2), 0, 0) }
            addView(expiryView)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(this@CloudMineActivity).apply { setImageResource(R.drawable.cloud_vip_mark); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(82), dp(40)))
    }.also { it.layoutParams = LinearLayout.LayoutParams(-1, dp(77)).apply { topMargin = dp(17); bottomMargin = dp(20) } }

    private fun actionRow(title: String, emphasized: Boolean = false, customer: Boolean = false, click: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            if (emphasized) background = roundRect("#FFFFFF", 10, "#E0E0E0")
            setPadding(dp(12), 0, dp(8), 0)
            isClickable = true
            isFocusable = true
            setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.035f else 1f).scaleY(if (focused) 1.035f else 1f).setDuration(120).start()
                foreground = if (focused) roundRect("#00000000", 12, "#212121") else null
            }
            setOnClickListener { click() }
            if (customer) addView(TextView(this@CloudMineActivity).apply {
                text = "●"; textSize = 20f; setTextColor(Color.parseColor("#BA987A")); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(TextView(this@CloudMineActivity).apply {
                text = title; textSize = 15f; setTextColor(Color.parseColor("#212121")); if (emphasized) setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (!customer) marginStart = 0 })
            addView(TextView(this@CloudMineActivity).apply { text = "›"; textSize = 25f; setTextColor(Color.parseColor("#9E9E9E")); gravity = Gravity.CENTER })
        }.also { it.layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { if (emphasized) bottomMargin = dp(12) } }

    private fun maskEmail(value: String): String {
        val parts = value.split('@')
        return if (parts.size == 2 && parts[0].length > 3) parts[0].take(3) + "****@" + parts[1] else value
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(e: Throwable) = Toast.makeText(this, e.message ?: "操作失败", Toast.LENGTH_LONG).show()
    private fun roundRect(fill: String, radius: Int, stroke: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(fill)); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
    }
}

class CloudBusinessActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var pageRoot: LinearLayout
    private val api by lazy { CloudApi(this) }
    private val type by lazy { intent.getStringExtra("type") ?: "plans" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pageTitle = mapOf("plans" to "会员中心", "plan_detail" to "商品详情", "orders" to "我的订单", "invite" to "邀请返利", "tickets" to "意见反馈", "ticket_detail" to "工单详情", "ticket_create" to "创建工单")[type] ?: ""
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24)) }
        content.setBackgroundColor(Color.parseColor("#FAFAFA"))
        loading = ProgressBar(this)
        content.addView(loading, LinearLayout.LayoutParams(-1, dp(48)))
        pageRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            addView(pageHeader(pageTitle), LinearLayout.LayoutParams(-1, dp(56)))
            addView(ScrollView(this@CloudBusinessActivity).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(pageRoot)
        reload()
    }

    private fun pageHeader(pageTitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(16), 0)
        setBackgroundColor(Color.WHITE)
        addView(ImageView(this@CloudBusinessActivity).apply {
            setImageResource(R.drawable.cloud_back)
            setColorFilter(Color.parseColor("#212121"))
            contentDescription = "返回"
            isClickable = true
            isFocusable = true
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@CloudBusinessActivity).apply {
            text = pageTitle
            textSize = 18f
            setTextColor(Color.parseColor("#212121"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun reload() = lifecycleScope.launch {
        loading.visibility = View.VISIBLE
        try {
            when (type) {
                "plans" -> renderPlans(api.plans(), runCatching { api.subscription() }.getOrNull())
                "plan_detail" -> renderPlanDetails(api.planDetails(intent.getStringExtra("plan_id").orEmpty()))
                "orders" -> renderOrders(api.orders())
                "invite" -> renderInvite(api.inviteSummary(), api.inviteDetails(), api.userInfo())
                "tickets" -> renderTickets(api.tickets())
                "ticket_detail" -> renderTicketConversation(api.ticketDetails(intent.getStringExtra("ticket_id").orEmpty()))
                "ticket_create" -> renderTicketCreate()
            }
        } catch (e: Throwable) {
            addText(e.message ?: "加载失败", 14f, "#B00020")
        } finally { loading.visibility = View.GONE }
    }

    private fun renderPlans(json: JSONObject, subscription: CloudSubscription?) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "商店"; textSize = 18f; setTextColor(Color.parseColor("#212121")); setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "↻  刷新"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.BLACK)
                isClickable = true; isFocusable = true; setOnClickListener { recreate() }
            }, LinearLayout.LayoutParams(dp(82), dp(44)))
        })
        content.addView(memberCard(subscription), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        val list = json.optJSONArray("data") ?: JSONArray()
        if (list.length() == 0) {
            addText("暂无可购买套餐\n请稍后刷新重试", 14f, "#757575")
            return
        }
        content.addView(TextView(this).apply {
            text = "可购买套餐 · ${list.length()}"; textSize = 13f; setTextColor(Color.parseColor("#757575")); setPadding(0, 0, 0, dp(10))
        })
        repeat(list.length()) { i ->
            val plan = list.optJSONObject(i) ?: return@repeat
            val periods = listOf(
                "month_price" to "月付", "quarter_price" to "季付", "half_year_price" to "半年付",
                "year_price" to "年付", "two_year_price" to "两年付", "three_year_price" to "三年付", "onetime_price" to "一次性",
            ).filter { plan.optLong(it.first, 0) > 0 }
            if (periods.isNotEmpty()) content.addView(planCard(plan, periods), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    private fun memberCard(subscription: CloudSubscription?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = roundedCard()
        addView(LinearLayout(this@CloudBusinessActivity).apply {
            orientation = LinearLayout.VERTICAL
            val account = subscription?.email?.ifBlank { CloudSession(this@CloudBusinessActivity).email }
                ?: CloudSession(this@CloudBusinessActivity).email
            addView(TextView(this@CloudBusinessActivity).apply {
                text = maskBusinessEmail(account); textSize = 16f; setTextColor(Color.parseColor("#212121")); setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "到期时间：${formatExpiry(subscription?.expiresAt ?: 0)}"; textSize = 11f; setTextColor(Color.parseColor("#757575")); setPadding(0, dp(3), 0, 0)
            })
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "当前套餐：${subscription?.plan?.ifBlank { "未开通" } ?: "未开通"}"; textSize = 11f; setTextColor(Color.parseColor("#757575")); setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(this@CloudBusinessActivity).apply {
            setImageResource(R.drawable.cloud_vip_flag); scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(63), dp(30)))
    }

    private fun planCard(plan: JSONObject, periods: List<Pair<String, String>>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = roundedCard()
        isClickable = true; isFocusable = true
        setOnClickListener { openPlanDetails(plan.optString("id")) }
        addView(LinearLayout(this@CloudBusinessActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            addView(TextView(this@CloudBusinessActivity).apply {
                text = plan.optString("name", "套餐"); textSize = 16f; setTextColor(Color.parseColor("#212121")); setTypeface(typeface, Typeface.BOLD); maxLines = 2
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (plan.optLong("onetime_price", 0) > 0) addView(TextView(this@CloudBusinessActivity).apply {
                text = "限时返场"; textSize = 11f; setTextColor(Color.parseColor("#E67817")); gravity = Gravity.CENTER
                setPadding(dp(8), dp(3), dp(8), dp(3)); background = rounded("#FFF3E8", 99, "#F2C28F")
            })
        })
        val lowest = periods.map { plan.optLong(it.first) }.filter { it > 0 }.minOrNull() ?: 0
        addView(TextView(this@CloudBusinessActivity).apply {
            text = "低至 ￥${money(lowest)}"; textSize = 14f; setTextColor(Color.parseColor("#ED492E")); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END; setPadding(0, dp(6), 0, dp(8))
        })
        val rights = parseRights(plan.optString("content"))
        rights.take(2).forEach { right ->
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "✓  $right"; textSize = 13f; setTextColor(Color.parseColor("#424242")); maxLines = 1; setPadding(0, dp(7), 0, 0)
            })
        }
        addView(TextView(this@CloudBusinessActivity).apply {
            text = "${periods.size} 种购买周期 · 完整权益请查看详情"; textSize = 12f; setTextColor(Color.parseColor("#757575")); setPadding(0, dp(9), 0, 0)
        })
        addView(TextView(this@CloudBusinessActivity).apply {
            text = "查看套餐详情  ›"; textSize = 13f; setTextColor(Color.BLACK); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END; setPadding(0, dp(12), 0, 0)
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
            "month_price" to "月付", "quarter_price" to "季付", "half_year_price" to "半年付",
            "year_price" to "年付", "two_year_price" to "两年付", "three_year_price" to "三年付", "onetime_price" to "一次性",
        ).filter { plan.optLong(it.first) > 0 }
        if (periods.isEmpty()) return addText("该商品暂无可购买周期")

        var selected = periods.first()
        var discount = 0L
        val coupon = EditText(this).apply { hint = "请输入优惠码"; isSingleLine = true; background = rounded("#F5F5F5", 12) }
        val amount = TextView(this).apply { textSize = 16f; setTextColor(Color.parseColor("#ED492E")); setTypeface(typeface, Typeface.BOLD) }
        fun updateAmount() {
            val original = plan.optLong(selected.first)
            val payable = (original - discount).coerceAtLeast(0)
            amount.text = if (discount > 0) "原价 ¥${money(original)}   应付 ¥${money(payable)}" else "应付金额：¥${money(payable)}"
        }

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); background = roundedCard()
            addView(TextView(this@CloudBusinessActivity).apply { text = plan.optString("name", "套餐"); textSize = 18f; setTextColor(Color.parseColor("#212121")); setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(this@CloudBusinessActivity).apply { text = "低至 ¥${money(periods.minOf { plan.optLong(it.first) })}"; textSize = 14f; setTextColor(Color.parseColor("#ED492E")); setPadding(0, dp(7), 0, dp(10)) })
            addView(planContentWebView(plan.optString("content")))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        content.addView(TextView(this).apply { text = "选择周期"; textSize = 16f; setTextColor(Color.parseColor("#212121")); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(8), 0, dp(10)) })
        val chips = GridLayout(this).apply { columnCount = 3 }
        fun redrawChips() {
            chips.removeAllViews()
            periods.forEachIndexed { index, period ->
                chips.addView(TextView(this).apply {
                    text = "${period.second}\n¥${money(plan.optLong(period.first))}"; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#212121")); setPadding(dp(6), dp(5), dp(6), dp(5))
                    background = rounded(if (period == selected) "#E8E8E8" else "#FFFFFF", 12, if (period == selected) "#212121" else "#E0E0E0")
                    isClickable = true; setOnClickListener { selected = period; discount = 0; redrawChips(); updateAmount() }
                }, GridLayout.LayoutParams().apply {
                    width = 0; height = dp(58); columnSpec = GridLayout.spec(index % 3, 1f); rowSpec = GridLayout.spec(index / 3)
                    setMargins(0, 0, if (index % 3 < 2) dp(8) else 0, dp(8))
                })
            }
        }
        redrawChips(); content.addView(chips)
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
            addView(coupon, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "验证"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = rounded("#212121", 12); isClickable = true
                setOnClickListener { lifecycleScope.launch {
                    runCatching { api.checkCoupon(coupon.text.toString().trim(), plan.optString("id")) }.onSuccess { result ->
                        val data = result.optJSONObject("data")
                        val value = data?.optLong("value") ?: 0
                        discount = if (data?.optInt("type") == 2) plan.optLong(selected.first) * value / 100 else value
                        if (result.isNull("data") || result.opt("data") == false) toast(IllegalStateException(result.optString("message", "优惠码无效"))) else { updateAmount(); Toast.makeText(this@CloudBusinessActivity, "优惠码可用", Toast.LENGTH_SHORT).show() }
                    }.onFailure { toast(it) }
                } }
            }, LinearLayout.LayoutParams(dp(76), dp(48)).apply { marginStart = dp(10) })
        })
        updateAmount()
        pageRoot.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12)); setBackgroundColor(Color.WHITE); elevation = dp(8).toFloat()
            addView(amount, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "立即购买"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = rounded("#212121", 18); isClickable = true
                setOnClickListener { lifecycleScope.launch { createAndPay(plan.optString("id"), selected.first, coupon.text.toString().trim()) } }
            }, LinearLayout.LayoutParams(dp(108), dp(42)))
        }, LinearLayout.LayoutParams(-1, dp(72)))
    }

    private fun planContentWebView(raw: String): View {
        val estimatedHeight = (parseRights(raw).size.coerceIn(2, 9) * 34 + 28)
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
            <style>html,body{margin:0;padding:0;background:transparent;color:#424242;font-family:sans-serif;font-size:14px;line-height:1.75}p{margin:4px 0}img{max-width:100%;height:auto}ul,ol{padding-left:22px;margin:6px 0}</style>
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
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            layoutParams = LinearLayout.LayoutParams(-1, dp(estimatedHeight))
        }
    }

    @Suppress("DEPRECATION")
    private fun parseRights(raw: String): List<String> = Html.fromHtml(raw.replace("<br>", "\n").replace("<br/>", "\n"))
        .toString().lines().map { it.trim() }.filter { it.isNotBlank() }

    private fun maskBusinessEmail(value: String): String {
        val parts = value.split('@')
        return if (parts.size == 2 && parts[0].length > 3) "${parts[0].take(3)}****${parts[1]}" else value
    }

    private fun formatExpiry(timestamp: Long): String = if (timestamp <= 0) "不限时长" else
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp * 1000))

    private suspend fun createAndPay(planId: String, period: String, coupon: String) {
        try {
            val methods = api.paymentMethods().optJSONArray("data") ?: JSONArray()
            if (methods.length() == 0) throw IllegalStateException("暂无可用支付方式")
            val names = Array(methods.length()) { methods.optJSONObject(it)?.optString("name") ?: "支付" }
            AlertDialog.Builder(this).setTitle("选择支付方式").setItems(names) { _, which ->
                lifecycleScope.launch {
                    try {
                        val order = api.createOrder(planId, period, coupon)
                        val tradeNo = order.optString("data")
                        val method = methods.optJSONObject(which)?.optString("id").orEmpty()
                        val pay = api.checkout(tradeNo, method)
                        val url = pay.opt("data")?.toString().orEmpty()
                        if (url.startsWith("http")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        else Toast.makeText(this@CloudBusinessActivity, "订单已创建", Toast.LENGTH_LONG).show()
                    } catch (e: Throwable) { toast(e) }
                }
            }.show()
        } catch (e: Throwable) { toast(e) }
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
                    addView(TextView(this@CloudBusinessActivity).apply { text = plan?.optString("name") ?: "订单"; textSize = 16f; setTextColor(Color.parseColor("#212121")); maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(this@CloudBusinessActivity).apply { text = status; textSize = 14f; setTextColor(Color.parseColor(statusColor)); setPadding(dp(8), 0, 0, 0) })
                })
                fun line(label: String, value: String) = TextView(this@CloudBusinessActivity).apply { text = "$label：$value"; textSize = 13f; setTextColor(Color.parseColor("#616161")); setPadding(0, dp(5), 0, 0) }
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
        setTextColor(Color.parseColor(if (primary) "#FFFFFF" else "#424242"))
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
        val user = info.optJSONObject("data")
        addText("我的返利", 16f, "#212121")
        val stats = data?.optJSONArray("stat") ?: JSONArray()
        val statRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = roundedCard() }
        listOf("邀请用户", "可提现佣金", "确认中佣金", "佣金比例").forEachIndexed { index, label ->
            val raw = if (index < stats.length()) stats.optDouble(index, 0.0) else if (index == 1) (user?.optDouble("commission_balance", 0.0) ?: 0.0) else 0.0
            statRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(TextView(this@CloudBusinessActivity).apply { text = if (index == 3) "${raw.toInt()}%" else if (index == 0) raw.toInt().toString() else String.format(java.util.Locale.US, "%.2f", raw / 100.0); textSize = 14f; setTextColor(Color.parseColor("#212121")) })
                addView(TextView(this@CloudBusinessActivity).apply { text = label; textSize = 12f; setTextColor(Color.parseColor("#757575")); setPadding(0, dp(4), 0, 0) })
            }, LinearLayout.LayoutParams(0, dp(76), 1f))
        }
        content.addView(statRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(20) })
        val commission = user?.optLong("commission_balance", 0) ?: 0
        if (commission > 0) content.addView(actionButton("提现到余额", true) { confirmCommissionTransfer(commission) }, LinearLayout.LayoutParams(dp(120), dp(40)).apply { gravity = Gravity.END; bottomMargin = dp(18) })
        addText("我的邀请码", 16f, "#212121")
        val codes = data?.optJSONArray("codes") ?: data?.optJSONArray("code")
        val inviteCode = data?.optString("invite_code").orEmpty().ifBlank { codes?.optJSONObject(0)?.optString("code").orEmpty() }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(10), dp(10)); background = roundedCard()
            addView(TextView(this@CloudBusinessActivity).apply { text = inviteCode.ifBlank { "尚未生成" }; textSize = 16f; setTextColor(Color.parseColor("#212121")); setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
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
        addText("邀请返佣明细 · 共 ${users.length()} 笔", 15f, "#212121")
        repeat(users.length()) { index ->
            val invited = users.optJSONObject(index) ?: return@repeat
            addCard(
                "返利订单 ${invited.optString("trade_no", "-")}",
                "订单金额：¥${money(invited.optLong("order_amount"))}\n获得佣金：¥${money(invited.optLong("get_amount"))}\n时间：${formatTimestamp(invited.optLong("created_at"))}",
            ) {}
        }
    }

    private fun confirmCommissionTransfer(amount: Long) {
        AlertDialog.Builder(this).setTitle("提现到余额").setMessage("确认将可提现佣金 ¥${money(amount)} 转入账户余额？")
            .setNegativeButton("取消", null).setPositiveButton("确认提现") { _, _ ->
                lifecycleScope.launch { runCatching { api.transferCommission(amount) }.onSuccess { Toast.makeText(this@CloudBusinessActivity, "提现成功", Toast.LENGTH_SHORT).show(); recreate() }.onFailure { toast(it) } }
            }.show()
    }

    private fun renderTickets(json: JSONObject) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@CloudBusinessActivity).apply { text = "问题与反馈"; textSize = 17f; setTextColor(Color.parseColor("#212121")); setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(TextView(this@CloudBusinessActivity).apply {
                text = "+ 创建工单"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = rounded("#212121", 18); isClickable = true
                setOnClickListener { startActivity(Intent(this@CloudBusinessActivity, CloudBusinessActivity::class.java).putExtra("type", "ticket_create")) }
            }, LinearLayout.LayoutParams(dp(122), dp(40)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        val list = json.optJSONArray("data") ?: JSONArray()
        if (list.length() == 0) {
            addText("◌", 38f, "#9E9E9E")
            addText("暂无工单\n遇到问题时，可以创建工单联系我们", 14f, "#757575")
            return
        }
        repeat(list.length()) { i ->
            val item = list.optJSONObject(i) ?: return@repeat
            val level = when (item.optInt("level")) { 0 -> "低"; 2 -> "高"; else -> "中" }
            val state = if (item.optInt("status") == 0) "处理中" else "已关闭"
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(15), dp(13), dp(10), dp(13)); background = roundedCard(); isClickable = true
                setOnClickListener { startActivity(Intent(this@CloudBusinessActivity, CloudBusinessActivity::class.java).putExtra("type", "ticket_detail").putExtra("ticket_id", item.optString("id"))) }
                addView(LinearLayout(this@CloudBusinessActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@CloudBusinessActivity).apply { text = item.optString("subject", "工单 #${item.optString("id")}"); textSize = 15f; setTextColor(Color.parseColor("#212121")); setTypeface(typeface, Typeface.BOLD); maxLines = 2 })
                    addView(TextView(this@CloudBusinessActivity).apply { text = "${level}优先级 · ${formatTimestamp(item.optLong("updated_at", item.optLong("created_at")))}"; textSize = 12f; setTextColor(Color.parseColor("#757575")); setPadding(0, dp(7), 0, 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@CloudBusinessActivity).apply { text = state; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(if (item.optInt("status") == 0) "#B45309" else "#757575")); background = rounded(if (item.optInt("status") == 0) "#FFF3E0" else "#EEEEEE", 99); setPadding(dp(9), dp(4), dp(9), dp(4)) })
                addView(TextView(this@CloudBusinessActivity).apply { text = "›"; textSize = 24f; setTextColor(Color.parseColor("#9E9E9E")); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(30), dp(42)))
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun renderTicketConversation(response: JSONObject) {
        val ticket = response.optJSONObject("data") ?: return addText("工单详情加载失败", 14f, "#B00020")
        val closed = ticket.optInt("status") == 1
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@CloudBusinessActivity).apply { text = ticket.optString("subject", "工单详情"); textSize = 16f; setTextColor(Color.parseColor("#212121")); setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            if (!closed) addView(actionButton("关闭工单", false) { confirmCloseTicket(ticket.optString("id")) }, LinearLayout.LayoutParams(dp(100), dp(38)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val messages = ticket.optJSONArray("message") ?: JSONArray()
        if (messages.length() == 0) addText("暂无消息")
        repeat(messages.length()) { index ->
            val message = messages.optJSONObject(index) ?: return@repeat
            val mine = message.optBoolean("is_me")
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(8)); background = rounded(if (mine) "#E5E5E5" else "#FFFFFF", 12, "#E0E0E0")
                addView(TextView(this@CloudBusinessActivity).apply { text = message.optString("message"); textSize = 14f; setTextColor(Color.parseColor("#212121")) })
                addView(TextView(this@CloudBusinessActivity).apply { text = formatTimestamp(message.optLong("created_at")); textSize = 11f; setTextColor(Color.parseColor("#757575")); setPadding(0, dp(5), 0, 0) })
            }, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.78f).toInt(), -2).apply { gravity = if (mine) Gravity.END else Gravity.START; bottomMargin = dp(10) })
        }
        if (!closed) {
            val reply = EditText(this).apply { hint = "请输入回复内容"; minLines = 1; maxLines = 4; background = rounded("#FFFFFF", 12, "#BDBDBD"); setPadding(dp(12), dp(8), dp(12), dp(8)) }
            pageRoot.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.WHITE); elevation = dp(8).toFloat()
                addView(reply, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@CloudBusinessActivity).apply {
                    text = "发送"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = rounded("#212121", 20); isClickable = true
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
        val subject = EditText(this).apply { hint = "请输入工单主题"; isSingleLine = true; background = rounded("#FFFFFF", 12, "#BDBDBD"); setPadding(dp(12), 0, dp(12), 0) }
        val message = EditText(this).apply { hint = "请详细描述遇到的问题"; minLines = 6; gravity = Gravity.TOP; background = rounded("#FFFFFF", 12, "#BDBDBD"); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        var level = 1
        val levels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun redraw() {
            levels.removeAllViews()
            listOf("低", "中", "高").forEachIndexed { index, label ->
                levels.addView(TextView(this).apply {
                    text = label; gravity = Gravity.CENTER; textSize = 14f; isClickable = true; setTextColor(Color.parseColor("#212121"))
                    background = rounded(if (level == index) "#E5E5E5" else "#FFFFFF", 12, if (level == index) "#212121" else "#D5D5D5")
                    setOnClickListener { level = index; redraw() }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (index > 0) marginStart = dp(8) })
            }
        }
        fun label(value: String) = TextView(this).apply { text = value; textSize = 15f; setTextColor(Color.parseColor("#212121")); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(14), 0, dp(8)) }
        content.addView(label("主题")); content.addView(subject, LinearLayout.LayoutParams(-1, dp(50)))
        content.addView(label("优先级")); redraw(); content.addView(levels)
        content.addView(label("问题描述")); content.addView(message, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(this).apply {
            text = "提交工单"; gravity = Gravity.CENTER; textSize = 15f; setTextColor(Color.WHITE); background = rounded("#212121", 22); isClickable = true
            setOnClickListener {
                val title = subject.text.toString().trim(); val body = message.text.toString().trim()
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
                (view.background as? GradientDrawable)?.setStroke(dp(if (focused) 3 else 1), Color.parseColor(if (focused) "#212121" else "#E0E0E0"))
            }
            addView(TextView(this@CloudBusinessActivity).apply { text = title; textSize = 16f; setTextColor(Color.parseColor("#212121")); setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(this@CloudBusinessActivity).apply { text = subtitle; textSize = 13f; setTextColor(Color.parseColor("#757575")); setPadding(0, dp(6), 0, 0) })
        }
        content.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun addText(value: String, size: Float = 14f, color: String = "#757575") {
        content.addView(TextView(this).apply { text = value; textSize = size; setTextColor(Color.parseColor(color)); gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18)) })
    }
    private fun money(cents: Long) = String.format(java.util.Locale.US, "%.2f", cents / 100.0)
    private fun formatTimestamp(timestamp: Long) = if (timestamp <= 0) "-" else
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp * 1000))
    private fun roundedCard() = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.parseColor("#E0E0E0")) }
    private fun rounded(fill: String, radius: Int, stroke: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(fill)); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), Color.parseColor(it)) }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(e: Throwable) = Toast.makeText(this, e.message ?: "操作失败", Toast.LENGTH_LONG).show()
}
