package com.github.kr328.clash

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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
import com.github.kr328.clash.cloud.CloudReleaseManager
import kotlinx.coroutines.launch
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
            addView(actionRow("设置") {
                startActivity(Intent(this@CloudMineActivity, SettingsActivity::class.java))
            })
        }
        setContentView(vertical().apply {
            addView(ScrollView(this@CloudMineActivity).apply { addView(root) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottomNavigation(), LinearLayout.LayoutParams(-1, dp(80)))
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

    private fun bottomNavigation(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor("#F3F3F3"))
        addView(navItem("仪表盘", DesignR.drawable.cloud_nav_dashboard, false) { openPrimary(MainActivity::class.java) })
        addView(navItem("线路", DesignR.drawable.cloud_nav_lines, false) { openPrimary(ProxyActivity::class.java) })
        addView(navItem("配置", DesignR.drawable.cloud_nav_profiles, false) { openPrimary(ProfilesActivity::class.java) })
        addView(navItem("我的", DesignR.drawable.cloud_nav_mine_selected, true) {})
    }

    private fun navItem(label: String, icon: Int, selected: Boolean, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
        setOnClickListener { click() }
        addView(ImageView(this@CloudMineActivity).apply {
            setImageResource(icon)
            if (selected) setBackgroundResource(DesignR.drawable.cloud_nav_indicator)
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }, LinearLayout.LayoutParams(dp(64), dp(32)))
        addView(TextView(this@CloudMineActivity).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(if (selected) "#212121" else "#8B91A2")); if (selected) setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(24)))
    }.also { it.layoutParams = LinearLayout.LayoutParams(0, -1, 1f) }

    private fun openPrimary(target: Class<*>) {
        if (target == CloudMineActivity::class.java) return
        val flags = if (target == MainActivity::class.java) {
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        } else {
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(Intent(this, target).addFlags(flags))
        overridePendingTransition(0, 0)
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
        title = mapOf("plans" to "会员中心", "orders" to "我的订单", "invite" to "邀请返利", "tickets" to "工单和客服")[type]
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24)) }
        content.setBackgroundColor(Color.parseColor("#FAFAFA"))
        loading = ProgressBar(this)
        content.addView(loading, LinearLayout.LayoutParams(-1, dp(48)))
        setContentView(ScrollView(this).apply { addView(content) })
        reload()
    }

    private fun reload() = lifecycleScope.launch {
        loading.visibility = View.VISIBLE
        try {
            when (type) {
                "plans" -> renderPlans(api.plans())
                "orders" -> renderOrders(api.orders())
                "invite" -> renderInvite(api.inviteSummary(), api.inviteDetails(), api.userInfo())
                "tickets" -> renderTickets(api.tickets())
            }
        } catch (e: Throwable) {
            addText(e.message ?: "加载失败", 14f, "#B00020")
        } finally { loading.visibility = View.GONE }
    }

    private fun renderPlans(json: JSONObject) {
        addText("商店", 18f, "#212121")
        addCard(CloudSession(this).email, "选择适合你的套餐，购买后订阅将自动更新") {}
        val list = json.optJSONArray("data") ?: JSONArray()
        if (list.length() == 0) return addText("暂无可购买套餐")
        repeat(list.length()) { i ->
            val plan = list.optJSONObject(i) ?: return@repeat
            val periods = listOf(
                "month_price" to "月付", "quarter_price" to "季付", "half_year_price" to "半年付",
                "year_price" to "年付", "two_year_price" to "两年付", "three_year_price" to "三年付", "onetime_price" to "一次性",
            ).filter { plan.optLong(it.first, 0) > 0 }
            addCard(plan.optString("name", "套餐"), "${plan.optLong("transfer_enable")} GB · ${periods.joinToString(" / ") { it.second }}") {
                if (periods.isNotEmpty()) choosePeriod(plan, periods)
            }
        }
    }

    private fun choosePeriod(plan: JSONObject, periods: List<Pair<String, String>>) {
        AlertDialog.Builder(this).setTitle("选择购买周期")
            .setItems(periods.map { "${it.second}  ¥${money(plan.optLong(it.first))}" }.toTypedArray()) { _, which ->
                lifecycleScope.launch { createAndPay(plan.optString("id"), periods[which].first) }
            }.show()
    }

    private suspend fun createAndPay(planId: String, period: String) {
        try {
            val methods = api.paymentMethods().optJSONArray("data") ?: JSONArray()
            if (methods.length() == 0) throw IllegalStateException("暂无可用支付方式")
            val names = Array(methods.length()) { methods.optJSONObject(it)?.optString("name") ?: "支付" }
            AlertDialog.Builder(this).setTitle("选择支付方式").setItems(names) { _, which ->
                lifecycleScope.launch {
                    try {
                        val order = api.createOrder(planId, period)
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
            }
        }
    }

    private fun orderActions(tradeNo: String) {
        AlertDialog.Builder(this).setTitle("待支付订单")
            .setItems(arrayOf("继续支付", "取消订单")) { _, which ->
                if (which == 0) lifecycleScope.launch { payExisting(tradeNo) } else confirmCancel(tradeNo)
            }.show()
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
    }

    private fun renderTickets(json: JSONObject) {
        val create = Button(this).apply { text = "创建工单"; setOnClickListener { createTicketDialog() } }
        content.addView(create)
        val list = json.optJSONArray("data") ?: JSONArray()
        if (list.length() == 0) return addText("暂无工单")
        repeat(list.length()) { i ->
            val item = list.optJSONObject(i) ?: return@repeat
            addCard(item.optString("subject", "工单"), "${item.optString("message")}\n状态：${if (item.optInt("status") == 0) "处理中" else "已关闭"}") {
                if (item.optInt("status") == 0) ticketActions(item.optString("id"))
            }
        }
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
    private fun roundedCard() = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.parseColor("#E0E0E0")) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(e: Throwable) = Toast.makeText(this, e.message ?: "操作失败", Toast.LENGTH_LONG).show()
}
