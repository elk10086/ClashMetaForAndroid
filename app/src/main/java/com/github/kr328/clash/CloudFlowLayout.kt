package com.github.kr328.clash

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** A small equivalent of Flutter's Wrap for cloud price chips. */
class CloudFlowLayout(
    context: Context,
    private val horizontalSpacing: Int,
    private val verticalSpacing: Int,
) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var rowWidth = 0
        var rowHeight = 0
        var totalHeight = paddingTop + paddingBottom
        var widest = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val nextWidth = if (rowWidth == 0) child.measuredWidth else rowWidth + horizontalSpacing + child.measuredWidth
            if (rowWidth > 0 && nextWidth > available) {
                widest = maxOf(widest, rowWidth)
                totalHeight += rowHeight + verticalSpacing
                rowWidth = child.measuredWidth
                rowHeight = child.measuredHeight
            } else {
                rowWidth = nextWidth
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
        }
        widest = maxOf(widest, rowWidth)
        totalHeight += rowHeight
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val availableRight = right - left - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            if (x > paddingLeft && x + child.measuredWidth > availableRight) {
                x = paddingLeft
                y += rowHeight + verticalSpacing
                rowHeight = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + horizontalSpacing
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}
