// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.min

/** Custom-rendered keyboard: weighted rows, press feedback, icons and slide-between-keys input. */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface OnKeyListener {
        /** Return true when the key was handled (e.g. Enter consumed an editor action). */
        fun onKey(key: KeyDef): Boolean
    }

    var onKeyListener: OnKeyListener? = null

    private data class PositionedKey(val def: KeyDef, val x: Float, val y: Float, val w: Float, val h: Float)

    private var layer = KeyboardLayer.LETTERS
    private var shiftState = ShiftState.OFF
    private var keys: List<PositionedKey> = emptyList()
    private var pressedKey: PositionedKey? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconCache = mutableMapOf<Int, Drawable>()

    private val horizontalPadding: Float
    private val gap: Float
    private val verticalGap: Float
    private val cornerRadius: Float
    private val charTextSize: Float
    private val functionTextSize: Float

    init {
        isClickable = true
        horizontalPadding = resources.getDimension(R.dimen.keyboard_horizontal_padding)
        gap = resources.getDimension(R.dimen.key_gap)
        verticalGap = resources.getDimension(R.dimen.key_vertical_gap)
        cornerRadius = resources.getDimension(R.dimen.key_corner_radius)
        charTextSize = resources.getDimension(R.dimen.key_char_text_size)
        functionTextSize = resources.getDimension(R.dimen.key_function_text_size)
    }

    fun setLayer(value: KeyboardLayer) {
        if (layer == value) return
        layer = value
        buildKeys()
        invalidate()
    }

    fun setShiftState(value: ShiftState) {
        if (shiftState == value) return
        shiftState = value
        buildKeys()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildKeys()
    }

    private fun buildKeys() {
        if (width <= 0 || height <= 0) return
        val rows = KeyLayouts.rows(layer, shiftState != ShiftState.OFF)
        val usableWidth = width - 2 * horizontalPadding
        val usableHeight = height - verticalGap * (rows.size - 1)
        if (usableWidth <= 0 || usableHeight <= 0) return

        val rowHeight = usableHeight / rows.size
        val result = mutableListOf<PositionedKey>()
        var y = 0f
        for (row in rows) {
            val totalWeight = row.keys.sumOf { it.weight.toDouble() }.toFloat()
            val cellWidth = (usableWidth - gap * (row.keys.size - 1)) / totalWeight
            var x = horizontalPadding
            for (def in row.keys) {
                val keyWidth = cellWidth * def.weight
                if (def.type != KeyType.SPACER) {
                    result.add(PositionedKey(def, x, y, keyWidth, rowHeight))
                }
                x += keyWidth + gap
            }
            y += rowHeight + verticalGap
        }
        keys = result
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (key in keys) drawKey(canvas, key)
    }

    private fun drawKey(canvas: Canvas, key: PositionedKey) {
        val def = key.def
        val isPressed = key == pressedKey
        val isFunction = def.type != KeyType.CHARACTER && def.type != KeyType.SPACE
        val isEncrypt = def.type == KeyType.ENCRYPT

        val background = when {
            isEncrypt -> color(if (isPressed) R.color.encrypt_key_pressed else R.color.encrypt_key_background)
            isPressed && isFunction -> color(R.color.key_function_pressed)
            isPressed -> color(R.color.key_background_pressed)
            isFunction -> color(R.color.key_function_background)
            else -> color(R.color.key_background)
        }
        paint.color = background
        val bounds = RectF(key.x, key.y, key.x + key.w, key.y + key.h)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, paint)

        val foreground = when {
            isEncrypt -> color(R.color.encrypt_key_text)
            isFunction -> color(R.color.key_function_text)
            else -> color(R.color.key_text)
        }

        val iconRes = def.iconRes
        if (iconRes != null) {
            drawIcon(canvas, iconRes, bounds, foreground, def.iconScale)
            return
        }

        val label = when {
            def.type == KeyType.CHARACTER && layer == KeyboardLayer.LETTERS && shiftState != ShiftState.OFF ->
                (def.text ?: def.label ?: "").uppercase()
            else -> def.label ?: def.text ?: ""
        }
        if (label.isEmpty()) return

        paint.color = foreground
        paint.typeface = if (isFunction) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (def.type == KeyType.CHARACTER) charTextSize else functionTextSize
        val metrics = paint.fontMetrics
        val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2
        canvas.drawText(label, bounds.centerX(), baseline, paint)
    }

    private fun drawIcon(canvas: Canvas, resId: Int, bounds: RectF, tint: Int, scale: Float) {
        val drawable = iconCache.getOrPut(resId) {
            requireNotNull(ContextCompat.getDrawable(context, resId)).mutate()
        }
        DrawableCompat.setTint(drawable, tint)
        val size = (min(bounds.width(), bounds.height()) * scale).toInt()
        val left = (bounds.centerX() - size / 2f).toInt()
        val top = (bounds.centerY() - size / 2f).toInt()
        drawable.setBounds(left, top, left + size, top + size)
        drawable.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                pressedKey = keyAt(event.getX(0), event.getY(0))
                if (pressedKey != null) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                invalidate()
                return pressedKey != null
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Secondary fingers must never trigger or retarget a key.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val candidate = keyAt(event.getX(index), event.getY(index))
                if (candidate != pressedKey) {
                    pressedKey = candidate
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val fallback = if (event.actionIndex == 0) 1 else 0
                    if (fallback < event.pointerCount) {
                        activePointerId = event.getPointerId(fallback)
                        pressedKey = keyAt(event.getX(fallback), event.getY(fallback))
                    } else {
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        pressedKey = null
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(event.actionIndex) != activePointerId) return true
                val tapped = keyAt(event.getX(event.actionIndex), event.getY(event.actionIndex))
                val handled = if (tapped != null) onKeyListener?.onKey(tapped.def) == true else false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                pressedKey = null
                invalidate()
                return handled || tapped != null
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                pressedKey = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun keyAt(x: Float, y: Float): PositionedKey? = keys.firstOrNull { key ->
        x >= key.x && x <= key.x + key.w && y >= key.y && y <= key.y + key.h
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(context, resId)
}
