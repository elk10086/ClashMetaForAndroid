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
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenSettings,
        OpenPlans,
        OpenAnnouncement,
        DetectNetwork,
        ChangeMode,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)
    private var selectedMode: TunnelState.Mode = TunnelState.Mode.Rule
    private var powerPulseAnimator: AnimatorSet? = null
    private var lastConnectionTimeSeconds: Long? = null

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
            updatePowerAnimation(running)
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
                    interpolator = AccelerateDecelerateInterpolator()
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
            binding.lineName = name
        }
    }

    suspend fun setNetworkResult(value: String) = withContext(Dispatchers.Main) {
        binding.networkResult = value
    }

    suspend fun setTrafficSpeed(value: Long) = withContext(Dispatchers.Main) {
        binding.uploadSpeed = value.trafficUpload() + "/s"
        binding.downloadSpeed = value.trafficDownload() + "/s"
    }

    suspend fun setConnectionTime(value: String) = withContext(Dispatchers.Main) {
        val nextSeconds = value.toConnectionTimeSeconds()
        val animateTick = nextSeconds != null && lastConnectionTimeSeconds?.plus(1L) == nextSeconds
        lastConnectionTimeSeconds = nextSeconds

        val timeView = binding.connectionTimeView
        timeView.animate().cancel()
        if (!animateTick || !timeView.isAttachedToWindow) {
            timeView.alpha = 1f
            timeView.translationY = 0f
            binding.connectionTime = value
            return@withContext
        }

        val offset = 3f * context.resources.displayMetrics.density
        timeView.animate()
            .alpha(0.35f)
            .translationY(-offset)
            .setDuration(90L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                binding.connectionTime = value
                binding.executePendingBindings()
                timeView.translationY = offset
                timeView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(160L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun String.toConnectionTimeSeconds(): Long? {
        val parts = split(':')
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toLongOrNull() ?: return null
        return hours * 3_600L + minutes * 60L + seconds
    }

    suspend fun setCloudAccount(plan: String, expiry: String, usage: String, progress: Int) {
        withContext(Dispatchers.Main) {
            binding.cloudPlan = plan
            binding.cloudExpiry = expiry
            binding.cloudUsage = usage
            binding.cloudProgress = progress.coerceIn(0, 100)
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
        binding.networkResult = context.getString(R.string.cloud_dashboard_detection_hint)
        binding.uploadSpeed = "0 Bytes/s"
        binding.downloadSpeed = "0 Bytes/s"
        binding.connectionTime = "00:00:00"
        binding.cloudPlan = context.getString(R.string.cloud_membership_loading)
        binding.cloudExpiry = ""
        binding.cloudUsage = ""
        binding.cloudProgress = 0
        binding.announcementTitle = ""
        binding.announcementVisible = false
        binding.announcementUnread = false
        binding.powerPulseView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (binding.clashRunning == true) updatePowerAnimation(true)
            }

            override fun onViewDetachedFromWindow(view: View) {
                powerPulseAnimator?.cancel()
                powerPulseAnimator = null
            }
        })
    }

    fun request(request: Request) {
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
    }

    fun takeSelectedMode(): TunnelState.Mode = selectedMode
}
