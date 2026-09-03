package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
            val navigation = bar(activity, selected)
            addView(navigation, LinearLayout.LayoutParams(-1, dp(activity, 80)))
            ViewCompat.setOnApplyWindowInsetsListener(navigation) { view, insets ->
                val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                view.setPadding(0, 0, 0, bottomInset)
                view.layoutParams = view.layoutParams.apply {
                    height = dp(activity, 80) + bottomInset
                }
                insets
            }
            ViewCompat.requestApplyInsets(navigation)
        }, FrameLayout.LayoutParams(-1, -1))
    }

    fun open(activity: Activity, tab: Tab) {
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
            ),
        )
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    private fun bar(activity: Activity, selected: Tab) = LinearLayout(activity).apply {
        tag = "cloud_bottom_navigation"
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(243, 243, 243))
        add(Tab.DASHBOARD, DesignR.string.cloud_nav_dashboard, DesignR.drawable.cloud_nav_dashboard, selected == Tab.DASHBOARD)
        add(Tab.LINES, DesignR.string.cloud_nav_lines, DesignR.drawable.cloud_nav_lines, selected == Tab.LINES)
        add(Tab.PROFILES, DesignR.string.cloud_nav_profiles, DesignR.drawable.cloud_nav_profiles, selected == Tab.PROFILES)
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
                setColorFilter(Color.parseColor(if (active) "#212121" else "#8B91A2"))
                if (active) setBackgroundResource(DesignR.drawable.cloud_nav_indicator)
                setPadding(dp(context, 20), dp(context, 4), dp(context, 20), dp(context, 4))
            }, LinearLayout.LayoutParams(dp(context, 64), dp(context, 32)))
            addView(TextView(context).apply {
                setText(label)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(if (active) "#212121" else "#8B91A2"))
                if (active) setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-1, dp(context, 24)))
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun dp(activity: android.content.Context, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
