package com.github.kr328.clash

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
            addView(actionRow("我的订单") { open("orders") })
            addView(actionRow("邀请返利") { open("invite") })
            addView(actionRow("工单") { open("tickets") })
            addView(actionRow("公告") {
                lifecycleScope.launch { runCatching { CloudReleaseManager.showAnnouncement(this@CloudMineActivity) }.onFailure { toast(it) } }
            })
            addView(actionRow("检查更新", emphasized = true) {
                lifecycleScope.launch { runCatching { CloudReleaseManager.checkUpdate(this@CloudMineActivity) }.onFailure { toast(it) } }
            })
            addView(actionRow("线上客服", customer = true) {
                lifecycleScope.launch {
                    runCatching { CloudReleaseManager.customerServiceUrl(this@CloudMineActivity) }
                        .onSuccess { url -> if (url.startsWith("http")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) else toast(IllegalStateException("客服地址不可用")) }
                        .onFailure { toast(it) }
                }
            })
            addView(actionRow("刷新服务配置") { confirmRefreshServiceConfig() })
            addView(actionRow("设置") {
                startActivity(Intent(this@CloudMineActivity, SettingsActivity::class.java))
            })
            addView(View(this@CloudMineActivity), LinearLayout.LayoutParams(1, dp(24)))
            addView(logoutButton())
        }
        setContentView(vertical().apply {
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
    private val api by lazy { CloudApi(this) }
    private val type by lazy { intent.getStringExtra("type") ?: "plans" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pageTitle = mapOf("plans" to "会员中心", "orders" to "我的订单", "invite" to "邀请返利", "tickets" to "工单和客服")[type] ?: ""
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24)) }
        content.setBackgroundColor(Color.parseColor("#FAFAFA"))
        loading = ProgressBar(this)
        content.addView(loading, LinearLayout.LayoutParams(-1, dp(48)))
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            addView(pageHeader(pageTitle), LinearLayout.LayoutParams(-1, dp(56)))
            addView(ScrollView(this@CloudBusinessActivity).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
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
                "orders" -> renderOrders(api.orders())
                "invite" -> renderInvite(api.inviteSummary(), api.inviteDetails(), api.userInfo())
                "tickets" -> renderTickets(api.tickets())
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
        if (list.length() == 0) return addText("暂无可购买套餐")
        repeat(list.length()) { i ->
            val plan = list.optJSONObject(i) ?: return@repeat
            val periods = listOf(
                "month_price" to "月付", "quarter_price" to "季付", "half_year_price" to "半年付",
                "year_price" to "年付", "two_year_price" to "两年付", "three_year_price" to "三年付", "onetime_price" to "一次性",
            ).filter { plan.optLong(it.first, 0) >= 1000 }
            if (periods.isNotEmpty()) content.addView(planCard(plan, periods), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
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
        setOnClickListener { choosePeriod(plan, periods) }
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
        addView(LinearLayout(this@CloudBusinessActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            periods.take(3).forEach { period ->
                addView(TextView(this@CloudBusinessActivity).apply {
                    text = "${period.second} ￥${money(plan.optLong(period.first))}"; textSize = 12f; setTextColor(Color.parseColor("#212121")); gravity = Gravity.CENTER
                    setPadding(dp(9), dp(5), dp(9), dp(5)); background = rounded("#EEEEEE", 99)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
        })
        val rights = parseRights(plan.optString("content"))
        rights.take(3).forEach { right ->
            addView(TextView(this@CloudBusinessActivity).apply {
                text = right; textSize = 13f; setTextColor(Color.parseColor("#424242")); maxLines = 1; setPadding(0, dp(7), 0, 0)
            })
        }
        if (rights.size > 3) addView(TextView(this@CloudBusinessActivity).apply {
            text = "还有 ${rights.size - 3} 项权益，点击查看详情"; textSize = 12f; setTextColor(Color.BLACK); setPadding(0, dp(7), 0, 0)
        })
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

    private fun choosePeriod(plan: JSONObject, periods: List<Pair<String, String>>) {
        AlertDialog.Builder(this).setTitle("选择购买周期")
            .setItems(periods.map { "${it.second}  ¥${money(plan.optLong(it.first))}" }.toTypedArray()) { _, which ->
                askCoupon(plan.optString("id"), periods[which].first)
            }.show()
    }

    private fun askCoupon(planId: String, period: String) {
        val input = EditText(this).apply { hint = "优惠码（选填）"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("确认订单").setView(input)
            .setNegativeButton("取消", null)
            .setNeutralButton("无优惠码") { _, _ -> lifecycleScope.launch { createAndPay(planId, period, "") } }
            .setPositiveButton("继续") { _, _ ->
                lifecycleScope.launch {
                    val coupon = input.text.toString().trim()
                    if (coupon.isNotEmpty()) {
                        val result = runCatching { api.checkCoupon(coupon, planId) }
                        if (result.isFailure) return@launch toast(result.exceptionOrNull()!!)
                        val response = result.getOrThrow()
                        if (response.isNull("data") || response.opt("data") == false) {
                            return@launch toast(IllegalStateException(response.optString("message", "优惠码无效")))
                        }
                    }
                    createAndPay(planId, period, coupon)
                }
            }.show()
    }

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
        if (list.length() == 0) return addText("暂无订单")
        repeat(list.length()) { i ->
            val order = list.optJSONObject(i) ?: return@repeat
            val plan = order.optJSONObject("plan")
            val status = when (order.optInt("status")) { 0 -> "待支付"; 1 -> "已支付"; 2 -> "已取消"; else -> "已完成" }
            addCard(plan?.optString("name") ?: "订单", "订单号：${order.optString("trade_no")}\n$status · ¥${money(order.optLong("total_amount"))}") {
                if (order.optInt("status") == 0) orderActions(order.optString("trade_no"))
                else showOrderDetails(order.optString("trade_no"))
            }
        }
    }

    private fun orderActions(tradeNo: String) {
        AlertDialog.Builder(this).setTitle("待支付订单")
            .setItems(arrayOf("查看详情", "继续支付", "取消订单")) { _, which ->
                when (which) {
                    0 -> showOrderDetails(tradeNo)
                    1 -> lifecycleScope.launch { payExisting(tradeNo) }
                    else -> confirmCancel(tradeNo)
                }
            }.show()
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
        addText("我的邀请码", 16f, "#212121")
        val codes = data?.optJSONArray("codes") ?: data?.optJSONArray("code")
        addCard("邀请码", codes?.toString() ?: "尚未生成") {
            lifecycleScope.launch { try { api.createInviteCode(); recreate() } catch (e: Throwable) { toast(e) } }
        }
        val users = details.optJSONArray("data") ?: JSONArray()
        addText("已邀请 ${users.length()} 人", 15f, "#212121")
        repeat(users.length()) { index ->
            val invited = users.optJSONObject(index) ?: return@repeat
            addCard(
                "返利订单 ${invited.optString("trade_no", "-")}",
                "订单金额：¥${money(invited.optLong("order_amount"))}\n获得佣金：¥${money(invited.optLong("get_amount"))}\n时间：${formatTimestamp(invited.optLong("created_at"))}",
            ) {}
        }
    }

    private fun renderTickets(json: JSONObject) {
        val create = Button(this).apply { text = "创建工单"; setOnClickListener { createTicketDialog() } }
        content.addView(create)
        val list = json.optJSONArray("data") ?: JSONArray()
        if (list.length() == 0) return addText("暂无工单")
        repeat(list.length()) { i ->
            val item = list.optJSONObject(i) ?: return@repeat
            addCard(item.optString("subject", "工单"), "${item.optString("message")}\n状态：${if (item.optInt("status") == 0) "处理中" else "已关闭"}") {
                showTicketDetails(item.optString("id"), item.optInt("status") == 0)
            }
        }
    }

    private fun showTicketDetails(id: String, open: Boolean) = lifecycleScope.launch {
        runCatching { api.ticketDetails(id) }.onSuccess { response ->
            val data = response.optJSONObject("data") ?: response
            val messages = data.optJSONArray("message") ?: data.optJSONArray("messages") ?: JSONArray()
            val history = buildString {
                append(data.optString("subject", "工单详情"))
                repeat(messages.length()) { index ->
                    val message = messages.optJSONObject(index) ?: return@repeat
                    append("\n\n${message.optString("message")}")
                    message.optString("created_at").takeIf { it.isNotBlank() }?.let { append("\n$it") }
                }
            }
            val builder = AlertDialog.Builder(this@CloudBusinessActivity).setTitle("工单详情").setMessage(history).setNegativeButton("关闭", null)
            if (open) builder.setPositiveButton("操作") { _, _ -> ticketActions(id) }
            builder.show()
        }.onFailure { toast(it) }
    }

    private fun ticketActions(id: String) {
        AlertDialog.Builder(this).setTitle("工单操作").setItems(arrayOf("回复工单", "关闭工单")) { _, which ->
            if (which == 0) {
                val input = EditText(this).apply { hint = "回复内容"; minLines = 3 }
                AlertDialog.Builder(this).setTitle("回复工单").setView(input).setNegativeButton("取消", null).setPositiveButton("发送") { _, _ ->
                    lifecycleScope.launch { try { api.replyTicket(id, input.text.toString()); recreate() } catch (e: Throwable) { toast(e) } }
                }.show()
            } else {
                lifecycleScope.launch { try { api.closeTicket(id); recreate() } catch (e: Throwable) { toast(e) } }
            }
        }.show()
    }

    private fun createTicketDialog() {
        val subject = EditText(this).apply { hint = "工单主题" }
        val message = EditText(this).apply { hint = "问题描述"; minLines = 3 }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0); addView(subject); addView(message) }
        AlertDialog.Builder(this).setTitle("创建工单").setView(box).setNegativeButton("取消", null).setPositiveButton("提交") { _, _ ->
            lifecycleScope.launch { try { api.createTicket(subject.text.toString(), 1, message.text.toString()); recreate() } catch (e: Throwable) { toast(e) } }
        }.show()
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
