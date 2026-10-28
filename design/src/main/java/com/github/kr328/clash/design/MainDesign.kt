package com.github.kr328.clash.design

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.util.SakuraCards
import com.github.kr328.clash.design.util.ThemeTypography
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.PowerRequestGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    // Power commands must not wait behind remote profile/status queries.
    val powerRequests = Channel<Unit>(Channel.CONFLATED)

    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenSettings,
        OpenPlans,
        RefreshMembership,
        OpenAnnouncement,
        DetectNetwork,
        ChangeMode,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)
    private var selectedMode: TunnelState.Mode = TunnelState.Mode.Rule
    private var powerPulseAnimator: AnimatorSet? = null
    private var powerTransitionAnimator: AnimatorSet? = null
    private var powerTransitionTarget: Boolean? = null
    private var powerTransitionTimeout: Job? = null
    private val powerRequestGate = PowerRequestGate()

    suspend fun beginPowerTransition(starting: Boolean) = withContext(Dispatchers.Main) {
        if (powerTransitionTarget != null) return@withContext
        powerTransitionTarget = starting
        binding.powerButtonView.isEnabled = false
        binding.powerButtonView.contentDescription = if (starting) "正在启动" else "正在关闭"
        updatePowerAnimation(false)
        binding.powerChargeView.begin(starting)
        powerTransitionAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.powerButtonView, View.SCALE_X, 1f, .975f, 1f),
                ObjectAnimator.ofFloat(binding.powerButtonView, View.SCALE_Y, 1f, .975f, 1f),
                ObjectAnimator.ofFloat(binding.powerButtonView, View.ALPHA, 1f, .9f, 1f),
            )
            childAnimations.forEach {
                (it as ValueAnimator).apply {
                    duration = 1_600
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = DecelerateInterpolator(1.35f)
                }
            }
            start()
        }
        // Do not attach this timer to withContext: it would delay VPN startup
        // until the child job completes. The design owns and cancels the timer.
        powerTransitionTimeout = this@MainDesign.launch(Dispatchers.Main) {
            delay(20_000)
            cancelPowerTransition()
        }
    }

    suspend fun cancelPowerTransition() = withContext(Dispatchers.Main) {
        resetPowerTransition(animateCharge = true)
        updatePowerAnimation(binding.clashRunning == true)
    }

    private fun resetPowerTransition(completed: Boolean = false, animateCharge: Boolean = false) {
        powerTransitionTimeout?.cancel()
        powerTransitionTimeout = null
        powerTransitionTarget = null
        powerRequestGate.release()
        powerTransitionAnimator?.cancel()
        powerTransitionAnimator = null
        when {
            completed -> binding.powerChargeView.finish()
            animateCharge -> binding.powerChargeView.dismiss()
            else -> binding.powerChargeView.stop()
        }
        binding.powerButtonView.apply {
            animate().cancel()
            if (completed || animateCharge) {
                // The service can acknowledge a request before the decorative
                // transition has had a frame to render. Cross-fade the new icon
                // and surface so fast starts/stops do not look like a hard cut.
                alpha = if (completed) .78f else .88f
                scaleX = if (completed) .975f else .99f
                scaleY = scaleX
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(if (completed) 260L else 220L)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
            } else {
                scaleX = 1f
                scaleY = 1f
                alpha = 1f
            }
            isEnabled = true
            contentDescription = if (binding.clashRunning == true) "点击关闭" else "点击启动"
        }
    }

    override val root: View
        get() = binding.root

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashRunning = running
            binding.executePendingBindings()
            powerTransitionTarget?.let { target ->
                if (target == running) {
                    // A normal start/stop completes the ring in the same direction
                    // as the state change, so the closing animation can be seen.
                    resetPowerTransition(completed = true)
                } else {
                    // The service reported a state different from the requested
                    // transition (for example a failed start); dismiss softly.
                    resetPowerTransition(animateCharge = true)
                }
            }
            if (powerTransitionTarget == null) {
                binding.powerButtonView.contentDescription = if (running) "点击关闭" else "点击启动"
                updatePowerAnimation(running)
            }
        }
    }

    private fun updatePowerAnimation(running: Boolean) {
        if (!running) {
            powerPulseAnimator?.cancel()
            powerPulseAnimator = null
            binding.powerPulseView.apply {
                alpha = 0f
                scaleX = 1f
                scaleY = 1f
            }
            return
        }
        if (powerPulseAnimator != null) return
        val pulse = binding.powerPulseView
        powerPulseAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(pulse, View.SCALE_X, 0.94f, 1.14f),
                ObjectAnimator.ofFloat(pulse, View.SCALE_Y, 0.94f, 1.14f),
                ObjectAnimator.ofFloat(pulse, View.ALPHA, 0f, 0.38f, 0f),
            )
            childAnimations.forEach {
                (it as ValueAnimator).apply {
                duration = 1_500L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = DecelerateInterpolator(1.25f)
                }
            }
            start()
        }
    }

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            binding.forwarded = value.trafficTotal()
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            selectedMode = mode
            binding.mode = when (mode) {
                TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
                TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
                else -> context.getString(R.string.rule_mode)
            }
        }
    }

    suspend fun setLineName(name: String) {
        withContext(Dispatchers.Main) {
            binding.lineLoading = false
            binding.lineName = name
        }
    }

    suspend fun setLineLoading(message: String) {
        withContext(Dispatchers.Main) {
            binding.lineLoading = true
            binding.lineName = message
        }
    }

    suspend fun setNetworkResult(value: String) = withContext(Dispatchers.Main) {
        binding.networkResult = value
        binding.networkResultView.setTextColor(context.resolveThemedColor(
            if (value == context.getString(R.string.cloud_dashboard_detection_timeout)) {
                com.google.android.material.R.attr.colorError
            } else {
                android.R.attr.colorControlNormal
            },
        ))
    }

    suspend fun setTrafficSpeed(value: Long) = withContext(Dispatchers.Main) {
        binding.uploadSpeed = value.trafficUpload() + "/s"
        binding.downloadSpeed = value.trafficDownload() + "/s"
    }

    suspend fun setConnectionTime(value: String) = withContext(Dispatchers.Main) {
        binding.connectionTime = value
    }

    suspend fun setCloudAccount(plan: String, expiry: String, usage: String, progress: Int, expired: Boolean, warning: String? = null) {
        withContext(Dispatchers.Main) {
            binding.cloudPlan = plan
            binding.cloudExpiry = expiry
            binding.cloudUsage = usage
            binding.cloudProgress = progress.coerceIn(0, 100)
            binding.cloudExpired = expired
            binding.cloudWarning = if (expired) null else warning
        }
    }

    suspend fun setAnnouncement(title: String, visible: Boolean, unread: Boolean) {
        withContext(Dispatchers.Main) {
            binding.announcementTitle = "官方公告：$title"
            binding.announcementVisible = visible
            binding.announcementUnread = unread
        }
    }

    init {
        binding.self = this
        binding.membershipWarningView.background = GradientDrawable().apply {
            cornerRadius = 12 * context.resources.displayMetrics.density
            setColor(ColorUtils.blendARGB(
                context.resolveThemedColor(com.google.android.material.R.attr.colorSurface),
                context.resolveThemedColor(com.google.android.material.R.attr.colorError),
                .08f,
            ))
        }
        if (SakuraCards.enabled(context)) {
            val density = context.resources.displayMetrics.density
            SakuraCards.apply(binding.membershipCardView, featured = true)
            binding.membershipCardView.setPadding((18 * density).toInt(), (18 * density).toInt(),
                (18 * density).toInt(), (18 * density).toInt())
            SakuraCards.apply(binding.announcementCardView)
            listOf(binding.modeCardView, binding.lineCardView, binding.detectionCardView, binding.speedCardView).forEach { card ->
                SakuraCards.apply(card)
                card.minimumHeight = (96 * density).toInt()
                card.setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
                (card.parent as View).layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
            (binding.speedCardView.parent.parent as View).apply {
                setPadding(paddingLeft, paddingTop, paddingRight, (24 * density).toInt())
            }
        }
        binding.networkResult = context.getString(R.string.cloud_dashboard_auto_detecting)
        binding.lineLoading = false
        binding.uploadSpeed = "0B/s"
        binding.downloadSpeed = "0B/s"
        binding.connectionTime = "00:00:00"
        binding.cloudPlan = context.getString(R.string.cloud_membership_loading)
        binding.cloudExpiry = ""
        binding.cloudUsage = ""
        binding.cloudProgress = 0
        binding.cloudExpired = false
        binding.cloudWarning = null
        binding.announcementTitle = ""
        binding.announcementVisible = false
        binding.announcementUnread = false
        binding.powerPulseView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (binding.clashRunning == true) updatePowerAnimation(true)
            }

            override fun onViewDetachedFromWindow(view: View) {
                resetPowerTransition()
                powerPulseAnimator?.cancel()
                powerPulseAnimator = null
            }
        })
    }

    fun request(request: Request) {
        if (request == Request.ToggleStatus) {
            if (!powerRequestGate.acquire()) return
            binding.powerButtonView.isEnabled = false
            if (powerRequests.trySend(Unit).isFailure) resetPowerTransition()
            return
        }
        requests.trySend(request)
    }

    fun showModeSelector() {
        val dialog = BottomSheetDialog(context)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun background(color: Int, radius: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(20))
            background = background(context.resolveThemedColor(com.google.android.material.R.attr.colorSurface), 16)
        }
        content.addView(View(context).apply {
            background = background(context.resolveThemedColor(com.google.android.material.R.attr.colorOutline), 2)
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        })
        content.addView(TextView(context).apply {
            setText(R.string.cloud_dashboard_mode)
            setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        })
        content.addView(TextView(context).apply {
            setText(R.string.cloud_mode_switch_hint)
            setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorError))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.08f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        val options = listOf(
            Triple(TunnelState.Mode.Rule, R.string.rule_mode, R.string.cloud_mode_rule_desc),
            Triple(TunnelState.Mode.Global, R.string.global_mode, R.string.cloud_mode_global_desc),
            Triple(TunnelState.Mode.Direct, R.string.direct_mode, R.string.cloud_mode_direct_desc),
        )
        options.forEachIndexed { index, (mode, title, description) ->
            val selected = mode == selectedMode
            val primaryColor = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
            val selectedContentColor = context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
            val normalContentColor = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(8), dp(10))
                background = background(
                    if (selected) primaryColor else context.resolveThemedColor(com.google.android.material.R.attr.colorSurface),
                    12,
                )
                isClickable = true
                isFocusable = true
            }
            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    setText(title)
                    setTextColor(if (selected) selectedContentColor else normalContentColor)
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    setText(description)
                    setTextColor(if (selected) selectedContentColor else normalContentColor)
                    alpha = .65f
                    textSize = 13f
                    setPadding(0, dp(3), 0, 0)
                })
            }
            row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(RadioButton(context).apply {
                isChecked = selected
                isClickable = false
                buttonTintList = ColorStateList.valueOf(if (selected) selectedContentColor else primaryColor)
            })
            row.setOnClickListener {
                selectedMode = mode
                requests.trySend(Request.ChangeMode)
                dialog.dismiss()
            }
            content.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = dp(8)
            })
        }
        dialog.setContentView(content)
        dialog.show()
        dialog.window?.decorView?.let(ThemeTypography::install)
    }

    fun takeSelectedMode(): TunnelState.Mode = selectedMode
}
