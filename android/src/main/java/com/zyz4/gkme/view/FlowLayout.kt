package com.zyz4.gkme.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

class FlowLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val horizontalSpacing: Int
    private val verticalSpacing: Int

    init {
        val density = resources.displayMetrics.density
        horizontalSpacing = (4f * density).toInt()
        verticalSpacing = (4f * density).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)

        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = 0
        var maxLineWidth = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            val needed = if (lineWidth == 0) childWidth else lineWidth + horizontalSpacing + childWidth
            if (widthMode != MeasureSpec.UNSPECIFIED && needed > widthSize && lineWidth > 0) {
                maxLineWidth = maxOf(maxLineWidth, lineWidth)
                totalHeight += lineHeight + verticalSpacing
                lineWidth = childWidth
                lineHeight = childHeight
            } else {
                lineWidth = needed
                lineHeight = maxOf(lineHeight, childHeight)
            }
        }
        maxLineWidth = maxOf(maxLineWidth, lineWidth)
        totalHeight += lineHeight

        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(maxLineWidth, widthSize)
            else -> maxLineWidth
        }
        setMeasuredDimension(measuredWidth, resolveSize(totalHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = 0
        var y = 0
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            if (x != 0 && x + childWidth > width) {
                x = 0
                y += lineHeight + verticalSpacing
                lineHeight = 0
            }
            child.layout(x, y, x + childWidth, y + childHeight)
            x += childWidth + horizontalSpacing
            lineHeight = maxOf(lineHeight, childHeight)
        }
    }
}
