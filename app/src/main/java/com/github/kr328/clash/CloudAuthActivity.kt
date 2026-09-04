package com.github.kr328.clash

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.res.ResourcesCompat
import com.github.kr328.clash.cloud.CloudApi
import com.github.kr328.clash.cloud.CloudSession
import com.github.kr328.clash.cloud.CloudTestAccount
import com.github.kr328.clash.cloud.CloudReleaseManager
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.resolveThemedColor
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
        applySelectedTheme()
        super.onCreate(savedInstanceState)
        if (CloudSession(this).loggedIn) return finish()
        window.statusBarColor = backgroundColor()
        window.navigationBarColor = backgroundColor()
        render()
        lifecycleScope.launch { runCatching { CloudReleaseManager.showAnnouncement(this@CloudAuthActivity, automatic = true) } }
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

    private fun render() {
        val page = if (step == Step.LOGIN) loginPage() else flowPage()
        setContentView(page)
        applySkinTypography(page)
    }

    private fun applySkinTypography(view: View) {
        val skin = UiStore(this).cloudThemeStyle
        if (skin < 0) return
        if (view is TextView) {
            val style = if (view.typeface?.isBold == true) Typeface.BOLD else Typeface.NORMAL
            view.typeface = themeTypeface(style)
            view.textScaleX = when (skin) { 1 -> .90f; 2 -> 1.03f; else -> 1f }
            view.letterSpacing = when (skin) { 0 -> .015f; 1 -> .025f; 3 -> .035f; else -> 0f }
            val store = UiStore(this)
            if (store.cloudTextScaleEnabled) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * store.cloudTextScalePercent.coerceIn(80, 140) / 100f)
            }
        }
        if (view is android.view.ViewGroup) repeat(view.childCount) { applySkinTypography(view.getChildAt(it)) }
    }

    private fun loginPage(): View {
        val mail = input(getString(DesignR.string.cloud_auth_email), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        updateClearIcon(mail)
        mail.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateClearIcon(mail)
            override fun afterTextChanged(s: Editable?) = Unit
        })
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
                text = BuildConfig.BRAND_DISPLAY_NAME; gravity = Gravity.CENTER; textSize = 32f
                typeface = themeTypeface(Typeface.BOLD); setTextColor(foreground()); letterSpacing = .08f
                setOnClickListener {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastBrandTapAt <= 500L) {
                        lastBrandTapAt = 0L
                        showTestAccounts(mail)
                    } else lastBrandTapAt = now
                }
            })
            var officialSiteUrl = BuildConfig.BRAND_OFFICIAL_SITE_URL
            val officialSiteLink = link("防失联：${BuildConfig.BRAND_OFFICIAL_SITE_HOST}", underline = true, bold = false) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(officialSiteUrl))) }
                    .onFailure { toast(getString(DesignR.string.cloud_operation_failed)) }
            }
            addView(officialSiteLink, LinearLayout.LayoutParams(-2, dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            lifecycleScope.launch {
                officialSiteUrl = runCatching { CloudReleaseManager.officialSiteUrl(this@CloudAuthActivity) }
                    .getOrDefault(BuildConfig.BRAND_OFFICIAL_SITE_URL)
                val host = runCatching { Uri.parse(officialSiteUrl).host }.getOrNull().orEmpty()
                officialSiteLink.text = "防失联：${host.ifBlank { BuildConfig.BRAND_OFFICIAL_SITE_HOST }}"
            }
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
                addView(link(getString(DesignR.string.cloud_create_account), gravity = Gravity.START or Gravity.CENTER_VERTICAL) { resetting = false; step = Step.REGISTER_EMAIL; render() }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(link(getString(DesignR.string.cloud_auth_forgot), color = secondary(), bold = false, gravity = Gravity.END or Gravity.CENTER_VERTICAL) { resetting = true; step = Step.RESET_EMAIL; render() }, LinearLayout.LayoutParams(0, dp(48), 1f))
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
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.cloud_back)
            setColorFilter(foreground())
            contentDescription = getString(android.R.string.cancel)
            isClickable = true
            isFocusable = true
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener { onBackPressed() }
        }, lp(dp(48), dp(48)))
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
        setBackgroundColor(backgroundColor())
        addView(FrameLayout(this@CloudAuthActivity).apply {
            addView(ScrollView(this@CloudAuthActivity).apply { isFillViewport = true; addView(content) }, FrameLayout.LayoutParams(-1, -1))
            if (footer) addView(serviceButton(), FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(16); bottomMargin = dp(16)
            })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (footer) addView(TextView(this@CloudAuthActivity).apply { text = getString(DesignR.string.cloud_current_version, packageManager.getPackageInfo(packageName, 0).versionName); gravity = Gravity.CENTER; textSize = 12f; setTextColor(secondary()) }, lp(-1, dp(32)))
    }

    private fun input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint; inputType = type; textSize = 16f; setTextColor(foreground()); setHintTextColor(secondary())
        isSingleLine = true; typeface = themeTypeface(Typeface.NORMAL)
        background = rounded(surface(), 16, outline()); setPadding(dp(20), 0, dp(16), 0); layoutParams = lp(-1, dp(58))
    }

    private fun flowInput(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint; inputType = type; textSize = 14f; setTextColor(foreground()); setHintTextColor(secondary())
        background = rounded(surface(), 10, outline()); setPadding(dp(15), 0, dp(15), 0); layoutParams = lp(-1, dp(55))
    }

    private fun primaryButton(label: String, login: Boolean = false, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = if (login) 17f else 15f; if (login) setTypeface(typeface, Typeface.BOLD); setTextColor(onAccent()); gravity = Gravity.CENTER; letterSpacing = if (login) .07f else 0f
        background = rounded(accent(), if (login) 28 else 25)
        if (login) elevation = dp(12).toFloat()
        setOnClickListener { if (!busy) action() }; layoutParams = lp(-1, dp(if (login) 56 else 50))
    }
    private fun outlineButton(label: String, action: () -> Unit) = TextView(this).apply { text = label; textSize = 15f; setTextColor(accent()); gravity = Gravity.CENTER; background = rounded(Color.TRANSPARENT, 25, outline()); setOnClickListener { if (!busy) action() }; layoutParams = lp(-1, dp(50)) }
    private fun link(label: String, underline: Boolean = false, color: Int = accent(), bold: Boolean = true, gravity: Int = Gravity.CENTER, action: () -> Unit) = TextView(this).apply { text = label; textSize = if (underline) 14f else 15f; setTextColor(color); this.gravity = gravity; if (bold) setTypeface(typeface, Typeface.BOLD); if (underline) paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG; setOnClickListener { action() } }
    private fun LinearLayout.heading(title: String, subtitle: String) { addView(TextView(this@CloudAuthActivity).apply { text = title; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(foreground()) }); addSpace(6); addView(TextView(this@CloudAuthActivity).apply { text = subtitle; textSize = 13f; setTextColor(foreground()) }); addSpace(28) }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun LinearLayout.addSpace(height: Int) = addView(View(this@CloudAuthActivity), lp(1, dp(height)))
    private fun lp(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(borderPx(), it) } }
    private fun borderPx() = (resources.displayMetrics.density * .5f).toInt().coerceAtLeast(1)
    private fun updateClearIcon(field: EditText) = field.setCompoundDrawablesWithIntrinsicBounds(0, 0, if (field.text.isNullOrEmpty()) 0 else R.drawable.cloud_clear, 0)
    private fun serviceButton() = ImageView(this).apply {
        setImageResource(R.drawable.cloud_headset)
        setColorFilter(onAccent())
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(accent(), 28)
        elevation = dp(5).toFloat()
        contentDescription = "线上客服"
        setOnClickListener {
            lifecycleScope.launch {
                val remote = runCatching { CloudReleaseManager.customerServiceUrl(this@CloudAuthActivity) }.getOrDefault("")
                val url = remote.ifBlank { BuildConfig.BRAND_CUSTOMER_SERVICE_URL }
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast(getString(DesignR.string.cloud_operation_failed)) }
            }
        }
    }
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
    private fun backgroundColor() = resolveThemedColor(android.R.attr.windowBackground)
    private fun surface() = resolveThemedColor(com.google.android.material.R.attr.colorSurface)
    private fun foreground() = resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
    private fun secondary() = blend(foreground(), backgroundColor(), .38f)
    private fun outline() = blend(foreground(), backgroundColor(), .72f)
    private fun accent() = resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
    private fun onAccent() = resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
    private fun isDark(): Boolean = when (UiStore(this).darkMode) {
        DarkMode.ForceDark -> true
        DarkMode.ForceLight -> false
        DarkMode.Auto -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    private fun applySelectedTheme() {
        val store = UiStore(this)
        setTheme(if (isDark()) DesignR.style.AppThemeDark else DesignR.style.AppThemeLight)
        when (store.cloudThemeStyle) {
            0 -> theme.applyStyle(if (isDark()) DesignR.style.CloudThemeMinimalDark else DesignR.style.CloudThemeMinimalLight, true)
            1 -> theme.applyStyle(if (isDark()) DesignR.style.CloudThemeSakuraDark else DesignR.style.CloudThemeSakuraLight, true)
            2 -> theme.applyStyle(if (isDark()) DesignR.style.CloudThemeGodDark else DesignR.style.CloudThemeGodLight, true)
            3 -> theme.applyStyle(if (isDark()) DesignR.style.CloudThemeAnimeDark else DesignR.style.CloudThemeAnimeLight, true)
        }
        if (isDark() && store.pureBlack) theme.applyStyle(DesignR.style.CloudThemePureBlack, true)
    }
    private fun themeTypeface(style: Int): Typeface {
        val font = when (UiStore(this).cloudThemeStyle) {
            0 -> DesignR.font.skin_minimal
            1 -> DesignR.font.skin_sakura
            2 -> DesignR.font.skin_god
            3 -> DesignR.font.skin_anime
            else -> return Typeface.create("sans-serif", style)
        }
        return Typeface.create(ResourcesCompat.getFont(this, font), style)
    }
    private fun blend(a: Int, b: Int, ratio: Float) = Color.rgb((Color.red(a) * (1 - ratio) + Color.red(b) * ratio).toInt(), (Color.green(a) * (1 - ratio) + Color.green(b) * ratio).toInt(), (Color.blue(a) * (1 - ratio) + Color.blue(b) * ratio).toInt())
}
