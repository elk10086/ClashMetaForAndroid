package com.github.kr328.clash

import android.os.Bundle
import android.text.InputType
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.cloud.CloudApi
import com.github.kr328.clash.cloud.CloudSession
import kotlinx.coroutines.launch
import com.github.kr328.clash.design.R as DesignR

class CloudAuthActivity : AppCompatActivity() {
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var code: EditText
    private lateinit var invite: EditText
    private lateinit var submit: Button
    private lateinit var sendCode: Button
    private lateinit var title: TextView
    private var mode = Mode.LOGIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CloudSession(this).loggedIn) {
            finish()
            return
        }
        setContentView(buildContent())
        render()
    }

    private fun buildContent(): View {
        fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
            this.hint = hint
            inputType = type
            isFocusableInTouchMode = true
            setTextColor(Color.rgb(32, 35, 45))
            setHintTextColor(Color.rgb(145, 151, 165))
            background = rounded(Color.rgb(245, 245, 245), 8f)
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(14) }
        }
        email = field(getString(DesignR.string.cloud_auth_email), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        password = field(getString(DesignR.string.cloud_auth_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        code = field(getString(DesignR.string.cloud_auth_code), InputType.TYPE_CLASS_NUMBER)
        invite = field(getString(DesignR.string.cloud_auth_invite))
        title = TextView(this).apply { textSize = 20f; setTextColor(Color.rgb(32, 35, 45)); setTypeface(typeface, Typeface.BOLD) }
        sendCode = Button(this).apply {
            text = getString(DesignR.string.cloud_auth_send_code); setTextColor(Color.WHITE); background = rounded(Color.rgb(33, 33, 33), 8f)
            setOnClickListener { sendCode() }
        }
        submit = Button(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; background = rounded(Color.rgb(33, 33, 33), 28f)
            layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(24) }
            setOnClickListener { submit() }
        }
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL
            addView(link(getString(DesignR.string.cloud_auth_login)) { mode = Mode.LOGIN; render() })
            addView(link(getString(DesignR.string.cloud_auth_register)) { mode = Mode.REGISTER; render() })
            addView(link(getString(DesignR.string.cloud_auth_forgot)) { mode = Mode.RESET; render() })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(54), dp(28), dp(32))
            addView(ImageView(this@CloudAuthActivity).apply {
                setImageResource(R.drawable.cloud_login_top)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(-1, dp(86))
            })
            addView(TextView(this@CloudAuthActivity).apply {
                text = getString(DesignR.string.cloud_brand); gravity = Gravity.CENTER; textSize = 32f; setTextColor(Color.rgb(32, 35, 45)); setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@CloudAuthActivity).apply {
                text = getString(DesignR.string.cloud_slogan); gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.rgb(66, 66, 66))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(42) }
            })
            addView(title); addView(email); addView(password); addView(code); addView(invite)
            addView(sendCode, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
            addView(submit); addView(actions)
        }
        return ScrollView(this).apply { setBackgroundColor(Color.rgb(250, 250, 250)); addView(content) }
    }

    private fun render() {
        title.text = when (mode) { Mode.LOGIN -> getString(DesignR.string.cloud_auth_welcome); Mode.REGISTER -> getString(DesignR.string.cloud_auth_register_title); Mode.RESET -> getString(DesignR.string.cloud_auth_reset_title) }
        submit.text = when (mode) { Mode.LOGIN -> getString(DesignR.string.cloud_auth_login); Mode.REGISTER -> getString(DesignR.string.cloud_auth_register_submit); Mode.RESET -> getString(DesignR.string.cloud_auth_reset_submit) }
        val extra = if (mode == Mode.LOGIN) View.GONE else View.VISIBLE
        code.visibility = extra; sendCode.visibility = extra
        invite.visibility = if (mode == Mode.REGISTER) View.VISIBLE else View.GONE
    }

    private fun sendCode() = runBusy {
        require(email.text.toString().contains('@')) { "请输入正确邮箱" }
        CloudApi(this).sendEmail(email.text.toString().trim())
        toast("验证码已发送")
    }

    private fun submit() = runBusy {
        val mail = email.text.toString().trim()
        val pass = password.text.toString()
        require(mail.contains('@')) { "请输入正确邮箱" }
        require(pass.length >= 8) { "密码至少 8 位" }
        val api = CloudApi(this)
        when (mode) {
            Mode.LOGIN -> api.login(mail, pass)
            Mode.REGISTER -> api.register(mail, pass, code.text.toString().trim(), invite.text.toString().trim())
            Mode.RESET -> {
                api.resetPassword(mail, pass, code.text.toString().trim())
                toast("密码已重置，请登录")
                mode = Mode.LOGIN
                render()
                return@runBusy
            }
        }
        setResult(RESULT_OK)
        finish()
    }

    private fun runBusy(block: suspend () -> Unit) {
        submit.isEnabled = false; sendCode.isEnabled = false
        lifecycleScope.launch {
            try { block() } catch (e: Throwable) { toast(e.message ?: "操作失败") }
            finally { submit.isEnabled = true; sendCode.isEnabled = true }
        }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun link(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.rgb(66, 66, 66)); textSize = 14f; gravity = Gravity.CENTER
        setPadding(dp(14), dp(20), dp(14), dp(20)); isFocusable = true; setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius.toInt()).toFloat()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private enum class Mode { LOGIN, REGISTER, RESET }
}
