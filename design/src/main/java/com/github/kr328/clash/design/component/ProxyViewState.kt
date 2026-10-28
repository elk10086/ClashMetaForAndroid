package com.github.kr328.clash.design.component

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.text.TextPaint
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.util.formatProxyDelay
import kotlin.math.absoluteValue
import kotlin.math.max

class ProxyViewState(
    val config: ProxyViewConfig,
    val proxy: Proxy,
    private val parent: ProxyState,
    private val link: ProxyState?
) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val rect = Rect()
    val path = Path()
    val testing: Boolean get() = parent.testing

    var title: String = ""
    var subtitle: String = ""
    var server: String = ""
    var delayText: String = ""
    var delayUntested: Boolean = true
    var delayColor: Int = config.unselectedControl
    var background: Int = config.unselectedBackground
    var controls: Int = config.unselectedControl
    var outline: Int = config.unselectedOutline

    private var delay: Int = Int.MIN_VALUE
    private var selected: Boolean = false
    private var parentNow: String = ""
    private var linkNow: String? = null

    private var lastFrameTime = System.currentTimeMillis()

    fun update(snap: Boolean): Boolean {
        val frameTime = System.currentTimeMillis()
        var invalidate = false
        server = if (config.showTechnicalDetails) proxy.server else ""

        if (proxy.isGroup) {
            title = proxy.name

            if (link == null) {
                subtitle = proxy.type
            } else {
                if (linkNow !== link.now) {
                    linkNow = link.now

                    subtitle = "%s(%s)".format(
                        proxy.type,
                        link.now.ifEmpty { "*" }
                    )
                }
            }
        } else {
            title = proxy.name
            subtitle = if (config.showTechnicalDetails) proxy.type else ""
        }

        if (delay != proxy.delay) {
            delay = proxy.delay
            delayText = formatProxyDelay(proxy.delay)
            delayUntested = proxy.delay == 0
            delayColor = when {
                proxy.delay < 0 || proxy.delay > Short.MAX_VALUE -> config.delayFailed
                proxy.delay == 0 -> config.delayUntested
                proxy.delay < 600 -> config.delayGood
                else -> config.delaySlow
            }
        }

        if (parentNow !== parent.now) {
            parentNow = parent.now
            selected = proxy.name == parent.now
        }

        controls = if (selected) config.selectedControl else config.unselectedControl
        outline = if (selected) config.selectedOutline else config.unselectedOutline

        if (delay == 0) {
            delayColor = config.delayUntested
        }

        if (snap) {
            background = if (selected) config.selectedBackground else config.unselectedBackground
        } else {
            val target = if (selected) config.selectedBackground else config.unselectedBackground

            if (background != target) {
                val sa = Color.alpha(background)
                val sr = Color.red(background)
                val sg = Color.green(background)
                val sb = Color.blue(background)

                val ta = Color.alpha(target)
                val tr = Color.red(target)
                val tg = Color.green(target)
                val tb = Color.blue(target)

                val da = ta - sa
                val dr = tr - sr
                val dg = tg - sg
                val db = tb - sb

                val max = max(
                    da.absoluteValue,
                    max(
                        dr.absoluteValue,
                        max(
                            dg.absoluteValue,
                            db.absoluteValue
                        )
                    )
                )

                val frameOffset = frameTime - lastFrameTime

                val colorOffset = (frameOffset / max.toFloat().coerceAtLeast(0.001f))
                    .coerceIn(0.0f, 1.0f)

                background = if (colorOffset > 0.999f) {
                    target
                } else {
                    Color.argb(
                        (sa + da * colorOffset).toInt(),
                        (sr + dr * colorOffset).toInt(),
                        (sg + dg * colorOffset).toInt(),
                        (sb + db * colorOffset).toInt()
                    )
                }

                invalidate = true
            }
        }

        lastFrameTime = frameTime

        return invalidate
    }
}
