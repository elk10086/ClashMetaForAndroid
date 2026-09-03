package com.github.kr328.clash

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
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
import com.github.kr328.clash.cloud.CloudTestAccount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.github.kr328.clash.design.R as DesignR

/** Native Android implementation of the hle_fi cloud authentication flow. */
class CloudAuthActivity : AppCompatActivity() {
    private enum class Step { LOGIN, REGISTER_EMAIL, RESET_EMAIL, CODE, INVITE, PASSWORD, SUCCESS }
    private var step = Step.LOGIN
    private var email = ""
    private var code = ""
    private var invite = ""
    private var resetting = false
    private var busy = false
    private var lastBrandTapAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CloudSession(this).loggedIn) return finish()
        render()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (step == Step.LOGIN) super.onBackPressed() else {
            step = when (step) {
                Step.REGISTER_EMAIL, Step.RESET_EMAIL -> Step.LOGIN
                Step.CODE -> if (resetting) Step.RESET_EMAIL else Step.REGISTER_EMAIL
                Step.INVITE -> Step.CODE
                Step.PASSWORD -> if (resetting) Step.CODE else Step.INVITE
                Step.SUCCESS -> Step.LOGIN
                Step.LOGIN -> Step.LOGIN
            }
            render()
        }
    }

    private fun render() = setContentView(if (step == Step.LOGIN) loginPage() else flowPage())

    private fun loginPage(): View {
        val mail = input(getString(DesignR.string.cloud_auth_email), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        mail.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.cloud_clear, 0)
        mail.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP && event.x >= mail.width - mail.compoundPaddingEnd && mail.text.isNotEmpty()) {
                mail.text.clear(); true
            } else false
        }
        val password = input(getString(DesignR.string.cloud_auth_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.cloud_eye_closed, 0)
        password.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP && event.x >= password.width - password.compoundPaddingEnd) {
                val hidden = password.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
                password.inputType = InputType.TYPE_CLASS_TEXT or if (hidden) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                password.setCompoundDrawablesWithIntrinsicBounds(0, 0, if (hidden) R.drawable.cloud_eye_opened else R.drawable.cloud_eye_closed, 0)
                password.setSelection(password.length())
                true
            } else false
        }
        val form = vertical().apply {
            layoutParams = lp(minOf(resources.displayMetrics.widthPixels - dp(56), dp(440)), -2)
            addView(TextView(this@CloudAuthActivity).apply {
                text = getString(DesignR.string.cloud_brand); gravity = Gravity.CENTER; textSize = 32f
                setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); letterSpacing = .08f
                post {
                    paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(Color.rgb(45, 121, 251), Color.rgb(50, 87, 255), Color.rgb(36, 212, 243)), null, Shader.TileMode.CLAMP)
                    invalidate()
                }
                setOnClickListener {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastBrandTapAt <= 500L) {
                        lastBrandTapAt = 0L
                        showTestAccounts(mail)
                    } else lastBrandTapAt = now
                }
            })
            addView(link(getString(DesignR.string.cloud_official_site), underline = true) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://zhuiyun.one"))) }
                    .onFailure { toast(getString(DesignR.string.cloud_operation_failed)) }
            }, lp(-2, dp(40)))
            addSpace(34); addView(mail); addSpace(18); addView(password); addSpace(40)
            addView(primaryButton(getString(DesignR.string.cloud_auth_login), login = true) {
                runBusy {
                    email = mail.text.toString().trim()
                    require(validEmail(email)) { getString(DesignR.string.cloud_invalid_email) }
                    require(password.length() >= 6) { getString(DesignR.string.cloud_password_short) }
                    CloudApi(this@CloudAuthActivity).login(email, password.text.toString())
                    setResult(RESULT_OK); finish()
                }
            })
            addSpace(12)
            addView(LinearLayout(this@CloudAuthActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(link(getString(DesignR.string.cloud_create_account)) { resetting = false; step = Step.REGISTER_EMAIL; render() }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(link(getString(DesignR.string.cloud_auth_forgot)) { resetting = true; step = Step.RESET_EMAIL; render() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            })
        }
        val body = vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), (resources.displayMetrics.heightPixels * .075f).toInt().coerceIn(dp(28), dp(72)), dp(28), dp(32))
            addView(form)
        }
        return page(body, true)
    }

    private fun flowPage(): View {
        val content = vertical().apply { setPadding(dp(18), dp(20), dp(18), dp(36)) }
        content.addView(ImageView(this).apply { setImageResource(R.drawable.cloud_back); setPadding(dp(14), dp(14), dp(14), dp(14)); setOnClickListener { onBackPressed() } }, lp(dp(48), dp(48)))
        content.addSpace(25)
        when (step) {
            Step.REGISTER_EMAIL, Step.RESET_EMAIL -> emailStep(content)
            Step.CODE -> codeStep(content)
            Step.INVITE -> inviteStep(content)
            Step.PASSWORD -> passwordStep(content)
            Step.SUCCESS -> successStep(content)
            Step.LOGIN -> Unit
        }
        return page(content)
    }

    private fun emailStep(content: LinearLayout) {
        content.heading(if (resetting) getString(DesignR.string.cloud_reset_question) else getString(DesignR.string.cloud_create_account), if (resetting) getString(DesignR.string.cloud_reset_subtitle) else getString(DesignR.string.cloud_register_email_subtitle))
        val field = flowInput(getString(DesignR.string.cloud_auth_email), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply { setText(email) }
        content.addView(field); content.addSpace(20)
        content.addView(primaryButton(getString(DesignR.string.cloud_auth_send_code)) {
            runBusy { email = field.text.toString().trim(); require(validEmail(email)) { getString(DesignR.string.cloud_invalid_email) }; CloudApi(this@CloudAuthActivity).sendEmail(email); step = Step.CODE; render() }
        })
    }

    private fun codeStep(content: LinearLayout) {
        content.heading(getString(DesignR.string.cloud_enter_code), getString(DesignR.string.cloud_code_sent_to, email))
        val field = flowInput(getString(DesignR.string.cloud_auth_code), InputType.TYPE_CLASS_NUMBER).apply { setText(code) }
        content.addView(field); content.addSpace(20)
        content.addView(primaryButton(getString(DesignR.string.cloud_next)) { code = field.text.toString().trim(); if (code.isBlank()) toast(getString(DesignR.string.cloud_code_required)) else { step = if (resetting) Step.PASSWORD else Step.INVITE; render() } })
        content.addSpace(10)
        content.addView(outlineButton(getString(DesignR.string.cloud_resend_code)) { runBusy { CloudApi(this@CloudAuthActivity).sendEmail(email); toast(getString(DesignR.string.cloud_code_resent)) } })
    }

    private fun inviteStep(content: LinearLayout) {
        content.heading(getString(DesignR.string.cloud_invite_title), getString(DesignR.string.cloud_invite_body))
        val field = flowInput(getString(DesignR.string.cloud_auth_invite)).apply { setText(invite) }
        content.addView(field); content.addSpace(20)
        content.addView(primaryButton(getString(DesignR.string.cloud_next)) { invite = field.text.toString().trim(); step = Step.PASSWORD; render() })
        content.addSpace(10); content.addView(outlineButton(getString(DesignR.string.cloud_skip)) { invite = ""; step = Step.PASSWORD; render() })
    }

    private fun passwordStep(content: LinearLayout) {
        content.heading(getString(DesignR.string.cloud_create_password), getString(DesignR.string.cloud_password_subtitle))
        val field = flowInput(getString(DesignR.string.cloud_auth_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        content.addView(field); content.addSpace(20)
        content.addView(primaryButton(if (resetting) getString(DesignR.string.cloud_auth_reset_submit) else getString(DesignR.string.cloud_auth_register)) {
            runBusy {
                val password = field.text.toString(); require(password.length >= 6) { getString(DesignR.string.cloud_password_short) }
                if (resetting) CloudApi(this@CloudAuthActivity).resetPassword(email, password, code) else CloudApi(this@CloudAuthActivity).register(email, password, code, invite)
                step = Step.SUCCESS; render(); lifecycleScope.launch { delay(3000); if (!isFinishing) { step = Step.LOGIN; render() } }
            }
        })
    }

    private fun successStep(content: LinearLayout) {
        content.gravity = Gravity.CENTER_HORIZONTAL; content.addSpace(45)
        content.addView(ImageView(this).apply { setImageResource(R.drawable.cloud_succeed) }, lp(dp(55), dp(55))); content.addSpace(12)
        content.addView(TextView(this).apply { text = if (resetting) getString(DesignR.string.cloud_reset_success) else getString(DesignR.string.cloud_register_success); textSize = 20f; setTextColor(foreground()) })
    }

    private fun page(content: View, footer: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(blend(backgroundColor(), accent(), .05f), backgroundColor(), backgroundColor()))
        addView(ScrollView(this@CloudAuthActivity).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (footer) addView(TextView(this@CloudAuthActivity).apply { text = getString(DesignR.string.cloud_current_version, packageManager.getPackageInfo(packageName, 0).versionName); gravity = Gravity.CENTER; textSize = 12f; setTextColor(secondary()) }, lp(-1, dp(32)))
    }

    private fun input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint; inputType = type; textSize = 16f; setTextColor(foreground()); setHintTextColor(secondary())
        background = rounded(surface(), 16, outline()); setPadding(dp(20), 0, dp(16), 0); layoutParams = lp(-1, dp(58))
    }

    private fun flowInput(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint; inputType = type; textSize = 14f; setTextColor(foreground()); setHintTextColor(secondary())
        background = rounded(surface(), 10, outline()); setPadding(dp(15), 0, dp(15), 0); layoutParams = lp(-1, dp(55))
    }

    private fun primaryButton(label: String, login: Boolean = false, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = if (login) 17f else 15f; if (login) setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(50, 87, 255), Color.rgb(36, 212, 243))).apply { cornerRadius = dp(if (login) 28 else 25).toFloat() }
        setOnClickListener { if (!busy) action() }; layoutParams = lp(-1, dp(if (login) 56 else 50))
    }
    private fun outlineButton(label: String, action: () -> Unit) = TextView(this).apply { text = label; textSize = 15f; setTextColor(accent()); gravity = Gravity.CENTER; background = rounded(Color.TRANSPARENT, 25, outline()); setOnClickListener { if (!busy) action() }; layoutParams = lp(-1, dp(50)) }
    private fun link(label: String, underline: Boolean = false, action: () -> Unit) = TextView(this).apply { text = label; textSize = if (underline) 14f else 15f; setTextColor(accent()); gravity = Gravity.CENTER; if (!underline) setTypeface(typeface, Typeface.BOLD) else paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG; setOnClickListener { action() } }
    private fun LinearLayout.heading(title: String, subtitle: String) { addView(TextView(this@CloudAuthActivity).apply { text = title; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(foreground()) }); addSpace(6); addView(TextView(this@CloudAuthActivity).apply { text = subtitle; textSize = 13f; setTextColor(foreground()) }); addSpace(28) }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun LinearLayout.addSpace(height: Int) = addView(View(this@CloudAuthActivity), lp(1, dp(height)))
    private fun lp(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) } }
    private fun runBusy(block: suspend () -> Unit) { if (busy) return; busy = true; lifecycleScope.launch { try { block() } catch (e: Throwable) { toast(e.message ?: getString(DesignR.string.cloud_operation_failed)) } finally { busy = false } } }
    private fun validEmail(value: String) = android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()
    private fun showTestAccounts(emailField: EditText) {
        val accounts = CloudTestAccount.emails.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(DesignR.string.cloud_test_accounts)
            .setItems(accounts) { _, index ->
                emailField.setText(accounts[index])
                emailField.setSelection(emailField.length())
                toast(getString(DesignR.string.cloud_test_account_selected))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun backgroundColor() = if (isDark()) Color.rgb(23, 23, 35) else Color.rgb(250, 250, 252)
    private fun surface() = if (isDark()) Color.rgb(36, 39, 56) else Color.WHITE
    private fun foreground() = if (isDark()) Color.WHITE else Color.rgb(32, 35, 45)
    private fun secondary() = if (isDark()) Color.rgb(164, 173, 189) else Color.rgb(93, 93, 109)
    private fun outline() = if (isDark()) Color.rgb(73, 77, 103) else Color.rgb(224, 226, 232)
    private fun accent() = Color.rgb(50, 87, 255)
    private fun isDark() = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private fun blend(a: Int, b: Int, ratio: Float) = Color.rgb((Color.red(a) * (1 - ratio) + Color.red(b) * ratio).toInt(), (Color.green(a) * (1 - ratio) + Color.green(b) * ratio).toInt(), (Color.blue(a) * (1 - ratio) + Color.blue(b) * ratio).toInt())
}
