package com.github.kr328.clash.design.util

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import kotlin.math.abs

/** One typography policy for initial content, recycled rows and asynchronous UI. */
object ThemeTypography {
    private data class Policy(val skin: Int, val scale: Float)
    private data class Applied(
        val basePx: Float, val emphasis: Boolean, val policy: Policy,
        val face: Typeface, val sizePx: Float, val layoutHeight: Int?,
    )
    private val faces = mutableMapOf<Pair<Int, Boolean>, Typeface>()

    fun typeface(context: Context, emphasis: Boolean = false): Typeface =
        typeface(context, UiStore(context).cloudThemeStyle, emphasis)

    private fun typeface(context: Context, skin: Int, emphasis: Boolean): Typeface =
        faces.getOrPut(skin to emphasis) {
            if (skin == 1) SakuraTypography.typeface(context)
            else {
                val family = when (skin) {
                    0 -> ResourcesCompat.getFont(context, R.font.skin_minimal)
                    2 -> ResourcesCompat.getFont(context, R.font.skin_god)
                    3 -> ResourcesCompat.getFont(context, R.font.skin_anime)
                    else -> Typeface.DEFAULT
                }
                Typeface.create(family, if (emphasis) Typeface.BOLD else Typeface.NORMAL)
            }
        }

    fun scale(context: Context): Float = UiStore(context).let {
        typographyScale(it.cloudThemeStyle, it.cloudTextScaleEnabled, it.cloudTextScalePercent)
    }

    private fun emphasized(face: Typeface?): Boolean = face?.isBold == true ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && (face?.weight ?: 400) >= 500) ||
        face == Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun apply(root: View) {
        val policy = Policy(UiStore(root.context).cloudThemeStyle, scale(root.context))
        if (policy.skin < 0) return
        fun visit(view: View) {
            if (view.tag == "preserve_typography") return
            if (view is TextView && (view is EditText || view.typeface != Typeface.MONOSPACE)) {
                val previous = view.getTag(R.id.theme_typography_applied) as? Applied
                val changedFace = previous == null || view.typeface != previous.face
                val changedSize = previous == null || abs(view.textSize - previous.sizePx) > .01f
                val layoutHeight = view.layoutParams?.height
                if (previous == null || previous.policy != policy || changedFace || changedSize ||
                    previous.layoutHeight != layoutHeight) {
                    val basePx = typographyBaseSize(previous?.basePx, previous?.sizePx, view.textSize)
                    val emphasis = if (changedFace) emphasized(view.typeface) else previous!!.emphasis
                    val face = typeface(view.context, policy.skin, emphasis)
                    val size = basePx * policy.scale
                    view.setTag(R.id.theme_typography_applied, Applied(basePx, emphasis, policy, face, size, layoutHeight))
                    if (view.typeface != face) view.typeface = face
                    if (policy.skin == 1) SakuraTypography.apply(view)
                    else {
                        view.textScaleX = if (policy.skin == 2) 1.03f else 1f
                        view.letterSpacing = when (policy.skin) { 0 -> .015f; 3 -> .035f; else -> 0f }
                    }
                    if (abs(view.textSize - size) > .01f) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                    if (!view.hint.isNullOrBlank()) view.setHintTextColor(view.context.resolveThemedColor(android.R.attr.textColorHint))
                }
            }
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        visit(root)
    }

    /** Track layout changes without replacing RecyclerView/ViewGroup hierarchy listeners. */
    fun install(root: View) {
        apply(root)
        if (root.getTag(R.id.theme_typography_installed) == true) return
        root.setTag(R.id.theme_typography_installed, true)
        var observer: ViewTreeObserver? = null
        val listener = ViewTreeObserver.OnGlobalLayoutListener { apply(root) }
        fun attach() {
            if (observer?.isAlive == true) return
            observer = root.viewTreeObserver.also { it.addOnGlobalLayoutListener(listener) }
            apply(root)
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = attach()
            override fun onViewDetachedFromWindow(view: View) {
                observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
                observer = null
            }
        })
        if (root.isAttachedToWindow) attach()
    }
}

fun AlertDialog.Builder.showThemed(): AlertDialog = show().also { dialog ->
    dialog.window?.decorView?.let(ThemeTypography::install)
}
