// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import kotlin.math.max
import kotlin.math.min

/** Mutable three-slot state; updated in place so typing never allocates a new holder. */
class SuggestionSlots {
    var left: String = ""
        private set
    var centre: String = ""
        private set
    var right: String = ""
        private set

    fun update(values: List<String>?): SuggestionSlots {
        left = values?.getOrNull(0).orEmpty()
        centre = values?.getOrNull(1).orEmpty()
        right = values?.getOrNull(2).orEmpty()
        return this
    }

    fun at(index: Int): String = when (index) {
        0 -> left
        1 -> centre
        2 -> right
        else -> ""
    }

    val hasSuggestions: Boolean
        get() = left.isNotEmpty() || centre.isNotEmpty() || right.isNotEmpty()
}

/**
 * Three-slot suggestion strip with the SwiftKey hierarchy: muted alternatives left and
 * right, bold high-contrast typed word in the centre. A separate status chip (TextView
 * in input_view.xml) sits above the strip; the service controls its text and visibility.
 */
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
            field = value
            slots.update(value)
            invalidate()
        }

    private val slots = SuggestionSlots()
    private var theme = KeyboardPalette.themeForUiMode(resources.configuration.uiMode)
    private var colors = KeyboardPalette.resolve(context, theme)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontMetrics = Paint.FontMetrics()
    private val location = IntArray(2)
    private val textSize = resources.getDimension(R.dimen.suggestion_text_size)
    private val primaryTextSize = resources.getDimension(R.dimen.suggestion_primary_text_size)

    init {
        isClickable = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val next = KeyboardPalette.themeForUiMode(newConfig.uiMode)
        if (next == theme) return
        theme = next
        colors = KeyboardPalette.resolve(context, next)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = colors.surface
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val slotWidth = width / SLOT_COUNT.toFloat()
        paint.color = colors.divider
        canvas.drawRect(0f, 0f, width.toFloat(), 1f, paint)
        for (index in 1 until SLOT_COUNT) {
            val x = slotWidth * index
            canvas.drawRect(x - 0.5f, height * 0.22f, x + 0.5f, height * 0.78f, paint)
        }

        for (index in 0 until SLOT_COUNT) {
            val text = slots.at(index)
            if (text.isEmpty()) continue
            val primary = index == 1
            paint.color = if (primary) colors.suggestionPrimary else colors.suggestionSecondary
            paint.typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = if (primary) primaryTextSize else textSize
            paint.getFontMetrics(fontMetrics)
            val baseline = height / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f
            canvas.drawText(text, slotWidth * (index + 0.5f), baseline, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && width > 0) {
            val slotWidth = width / SLOT_COUNT.toFloat()
            val index = min(SLOT_COUNT - 1, max(0, (event.x / slotWidth).toInt()))
            val text = slots.at(index)
            if (text.isNotEmpty()) onSuggestionListener?.onSuggestionSelected(text)
        }
        return true
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? = accessibilityProvider

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.contentDescription = context.getString(R.string.keyboard_content_description)
    }

    private val accessibilityProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                return AccessibilityNodeInfo.obtain(this@SuggestionStripView).apply {
                    contentDescription = context.getString(R.string.keyboard_content_description)
                }
            }
            val text = slots.at(virtualViewId)
            if (text.isEmpty()) return null
            val slotWidth = width / SLOT_COUNT.toFloat()
            val stripHeight = height
            return AccessibilityNodeInfo.obtain(this@SuggestionStripView, virtualViewId).apply {
                className = "android.widget.Button"
                isClickable = true
                contentDescription = context.getString(R.string.suggestion, text)
                this@SuggestionStripView.getLocationOnScreen(location)
                setBoundsInScreen(
                    Rect(
                        location[0] + (slotWidth * virtualViewId).toInt(),
                        location[1],
                        location[0] + (slotWidth * (virtualViewId + 1)).toInt(),
                        location[1] + stripHeight
                    )
                )
            }
        }

        override fun performAction(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
            val text = slots.at(virtualViewId)
            if (text.isEmpty()) return false
            onSuggestionListener?.onSuggestionSelected(text)
            return true
        }
    }

    private companion object {
        const val SLOT_COUNT = 3
    }
}
