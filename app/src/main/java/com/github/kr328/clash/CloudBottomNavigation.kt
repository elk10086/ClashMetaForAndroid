package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import com.github.kr328.clash.design.util.ThemeTypography
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.R as DesignR

/** Activity-backed equivalent of hle_fi's persistent four-destination NavigationBar. */
object CloudBottomNavigation {
    enum class Tab { DASHBOARD, LINES, PROFILES, MINE }

    fun attach(activity: Activity, selected: Tab) {
        val host = activity.findViewById<ViewGroup>(android.R.id.content)
        if (host.findViewWithTag<View>("cloud_bottom_navigation") != null) return
        val page = host.getChildAt(0) ?: return
        host.removeView(page)
        host.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
            val navigation = create(activity, selected)
            addView(navigation, LinearLayout.LayoutParams(-1, dp(activity, 80)))
        }, FrameLayout.LayoutParams(-1, -1))
    }

    fun create(activity: Activity, selected: Tab): View = bar(activity, selected).also { navigation ->
        ViewCompat.setOnApplyWindowInsetsListener(navigation) { view, insets ->
            // The design root consumes system-bar insets before nested views receive them.
            // Read the unconsumed window insets from the decor view so the navigation bar
            // remains above both three-button navigation and the gesture handle.
            val windowInsets = ViewCompat.getRootWindowInsets(activity.window.decorView) ?: insets
            val bottomInset = maxOf(
                windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                windowInsets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom,
            )
            view.setPadding(0, 0, 0, bottomInset)
            view.layoutParams = view.layoutParams.apply {
                height = dp(activity, 80) + bottomInset
            }
            insets
        }
        navigation.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                ViewCompat.requestApplyInsets(view)
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }

    fun open(activity: Activity, tab: Tab) {
        if (tab == Tab.PROFILES && !activity.canViewConfigurationModule()) return

        val target = when (tab) {
            Tab.DASHBOARD -> MainActivity::class.java
            Tab.LINES -> ProxyActivity::class.java
            Tab.PROFILES -> ProfilesActivity::class.java
            Tab.MINE -> CloudMineActivity::class.java
        }
        if (activity.javaClass == target) return
        activity.startActivity(
            Intent(activity, target).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            // Bottom-tab navigation is a standalone lines page. Only the
            // dashboard's line card sets this flag through MainActivity.openPrimary.
            ).putExtra(ProxyActivity.EXTRA_RETURN_TO_DASHBOARD, false),
        )
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    private fun bar(activity: Activity, selected: Tab) = LinearLayout(activity).apply {
        tag = "cloud_bottom_navigation"
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(activity.resolveThemedColor(com.google.android.material.R.attr.colorSurface))
        add(Tab.DASHBOARD, DesignR.string.cloud_nav_dashboard, DesignR.drawable.cloud_nav_dashboard, selected == Tab.DASHBOARD)
        add(Tab.LINES, DesignR.string.cloud_nav_lines, DesignR.drawable.cloud_nav_lines, selected == Tab.LINES)
        if (activity.canViewConfigurationModule()) {
            add(Tab.PROFILES, DesignR.string.cloud_nav_profiles, DesignR.drawable.cloud_nav_profiles, selected == Tab.PROFILES)
        }
        add(Tab.MINE, DesignR.string.cloud_nav_mine, DesignR.drawable.cloud_nav_mine, selected == Tab.MINE)
    }

    private fun LinearLayout.add(tab: Tab, label: Int, icon: Int, active: Boolean) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { open(context as Activity, tab) }
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(context.resolveThemedColor(if (active) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface))
                alpha = if (active) 1f else .62f
                if (active) setBackgroundResource(DesignR.drawable.cloud_nav_indicator)
                setPadding(dp(context, 20), dp(context, 4), dp(context, 20), dp(context, 4))
            }, LinearLayout.LayoutParams(dp(context, 64), dp(context, 32)))
            addView(TextView(context).apply {
                val store = UiStore(context)
                val skin = store.cloudThemeStyle.coerceIn(0, 3)
                setText(label)
                textSize = 12f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(context.resolveThemedColor(if (active) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface))
                alpha = if (active) 1f else .62f
                typeface = ThemeTypography.typeface(context, emphasis = active)
                if (skin == 1) {
                    setTextColor(context.resolveThemedColor(if (active)
                        com.google.android.material.R.attr.colorPrimary else
                        com.google.android.material.R.attr.colorOnSurfaceVariant))
                    alpha = 1f
                }
                ThemeTypography.apply(this)
                tag = "cloud_bottom_navigation_typography"
            }, LinearLayout.LayoutParams(-1, dp(context, 24)))
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun dp(activity: android.content.Context, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
