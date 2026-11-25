// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

/** Three-slot suggestion strip: the typed word sits in the centre, alternatives at the sides. */
class SuggestionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface OnSuggestionListener {
        fun onSuggestionSelected(suggestion: String)
    }

    var onSuggestionListener: OnSuggestionListener? = null

    var suggestions: List<String> = emptyList()
        set(value) {
            field = value.take(SLOT_COUNT)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textSize = resources.getDimension(R.dimen.suggestion_text_size)

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = ContextCompat.getColor(context, R.color.suggestion_background)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val slotWidth = width / SLOT_COUNT.toFloat()
        for (index in 0 until SLOT_COUNT) {
            if (index > 0) {
                paint.color = ContextCompat.getColor(context, R.color.divider)
                val x = slotWidth * index
                canvas.drawRect(x - 1f, height * 0.22f, x + 1f, height * 0.78f, paint)
            }
            val text = suggestions.getOrNull(index).orEmpty()
            if (text.isEmpty()) continue
            paint.color = ContextCompat.getColor(
                context,
                if (index == 1) R.color.suggestion_highlight else R.color.suggestion_text
            )
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = textSize
            val metrics = paint.fontMetrics
            val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2
            canvas.drawText(text, slotWidth * (index + 0.5f), baseline, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && width > 0) {
            val slotWidth = width / SLOT_COUNT.toFloat()
            val index = min(SLOT_COUNT - 1, max(0, (event.x / slotWidth).toInt()))
            suggestions.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let {
                onSuggestionListener?.onSuggestionSelected(it)
            }
        }
        return true
    }

    private companion object {
        const val SLOT_COUNT = 3
    }
}
