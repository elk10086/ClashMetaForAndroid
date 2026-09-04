package com.github.kr328.clash

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Branded cold-start flow ported from free_time_new's StartupSplash and
 * BusinessRootPage. The launcher points here while all internal entry points
 * continue to open MainActivity directly.
 */
class StartupActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(250, 250, 250))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        setContentView(content)
        showInitializingSplash()

        lifecycleScope.launch {
            delay(INITIALIZING_SPLASH_DURATION_MS)
            showWelcomeSplash()
            delay(WELCOME_SPLASH_DURATION_MS)
            startActivity(Intent(this@StartupActivity, MainActivity::class.java))
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun showInitializingSplash() {
        content.removeAllViews()
        content.addView(brandIcon())
        content.addView(space(20))
        content.addView(label(BuildConfig.BRAND_DISPLAY_NAME, 22f, Color.rgb(33, 33, 33), true))
        content.addView(space(28))
        content.addView(progress(36))
        content.addView(space(20))
        content.addView(label(getString(R.string.splash_starting), 14f, Color.rgb(97, 97, 97)))
    }

    private fun showWelcomeSplash() {
        content.removeAllViews()
        content.addView(brandIcon())
        content.addView(space(20))
        content.addView(
            label(
                getString(R.string.splash_welcome_brand, BuildConfig.BRAND_DISPLAY_NAME),
                22f,
                Color.rgb(33, 33, 33),
                true,
            )
        )
        content.addView(space(12))
        content.addView(label(getString(R.string.splash_slogan), 16f, Color.rgb(97, 97, 97)))
        content.addView(space(28))
        content.addView(progress(32))
    }

    private fun brandIcon() = ImageView(this).apply {
        setImageResource(R.drawable.ic_icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(20).toFloat()
        }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
    }

    private fun progress(size: Int) = ProgressBar(this).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(FLUTTER_SPLASH_PRIMARY)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun label(text: String, size: Float, color: Int, semibold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", if (semibold) Typeface.BOLD else Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val INITIALIZING_SPLASH_DURATION_MS = 600L
        const val WELCOME_SPLASH_DURATION_MS = 1_200L
        const val FLUTTER_SPLASH_PRIMARY = 0xFF8DB4C9.toInt()
    }
}
