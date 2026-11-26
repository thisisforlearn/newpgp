// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import java.util.Locale
import kotlin.math.min

/** Dark-first palette selection: only an explicit system light mode opts into light colors. */
enum class KeyboardTheme { DARK, LIGHT }

object KeyboardPalette {
    private const val UI_MODE_NIGHT_MASK = 0x30
    private const val UI_MODE_NIGHT_NO = 0x10

    fun themeForUiMode(uiMode: Int): KeyboardTheme =
        if (uiMode and UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_NO) KeyboardTheme.LIGHT
        else KeyboardTheme.DARK

    fun resolve(context: Context, theme: KeyboardTheme): KeyboardColors {
        val light = theme == KeyboardTheme.LIGHT
        fun color(darkRes: Int, lightRes: Int): Int =
            ContextCompat.getColor(context, if (light) lightRes else darkRes)
        return KeyboardColors(
            background = color(R.color.kb_background, R.color.kb_background_light),
            surface = color(R.color.kb_surface, R.color.kb_surface_light),
            key = color(R.color.kb_key, R.color.kb_key_light),
            keyPressed = color(R.color.kb_key_pressed, R.color.kb_key_pressed_light),
            functionKey = color(R.color.kb_function_key, R.color.kb_function_key_light),
            functionKeyPressed = color(R.color.kb_function_key_pressed, R.color.kb_function_key_pressed_light),
            accent = color(R.color.kb_accent, R.color.kb_accent_light),
            accentPressed = color(R.color.kb_accent_pressed, R.color.kb_accent_pressed_light),
            glyph = color(R.color.kb_glyph, R.color.kb_glyph_light),
            glyphMuted = color(R.color.kb_glyph_muted, R.color.kb_glyph_muted_light),
            divider = color(R.color.kb_divider, R.color.kb_divider_light),
            suggestionPrimary = color(R.color.kb_suggestion_primary, R.color.kb_suggestion_primary_light),
            suggestionSecondary = color(R.color.kb_suggestion_secondary, R.color.kb_suggestion_secondary_light),
            tooltipBackground = color(R.color.kb_tooltip_background, R.color.kb_tooltip_background_light),
            tooltipText = color(R.color.kb_tooltip_text, R.color.kb_tooltip_text_light)
        )
    }
}

data class KeyboardColors(
    val background: Int,
    val surface: Int,
    val key: Int,
    val keyPressed: Int,
    val functionKey: Int,
    val functionKeyPressed: Int,
    val accent: Int,
    val accentPressed: Int,
    val glyph: Int,
    val glyphMuted: Int,
    val divider: Int,
    val suggestionPrimary: Int,
    val suggestionSecondary: Int,
    val tooltipBackground: Int,
    val tooltipText: Int
)

/** Pure layout math so key geometry can be unit-tested without an Android runtime. */
object KeyGeometry {
    data class Placement(val index: Int, val x: Float, val width: Float)

    fun placeRow(weights: List<Float>, startX: Float, usableWidth: Float, gap: Float): List<Placement> {
        if (weights.isEmpty() || usableWidth <= 0f) return emptyList()
        val total = weights.sum()
        val available = usableWidth - gap * (weights.size - 1)
        val cellWidth = if (total > 0f && available > 0f) available / total else 0f
        var x = startX
        val placements = ArrayList<Placement>(weights.size)
        for (index in weights.indices) {
            val width = cellWidth * weights[index]
            placements.add(Placement(index, x, width))
            x += width + gap
        }
        return placements
    }

    /** Row 0 is the toolbar; remaining rows split what is left after gaps. */
    fun rowHeights(rowCount: Int, totalHeight: Float, gap: Float, toolbarHeight: Float): FloatArray {
        if (rowCount <= 0) return FloatArray(0)
        val usable = totalHeight - gap * (rowCount - 1)
        if (usable <= 0f) return FloatArray(rowCount)
        val toolbar = if (rowCount == 1) usable else minOf(toolbarHeight, usable)
        val rest = if (rowCount == 1) 0f else (usable - toolbar) / (rowCount - 1)
        return FloatArray(rowCount) { index -> if (index == 0) toolbar else rest }
    }
}

/**
 * Custom-rendered keyboard: persistent toolbar row, weighted rows, press feedback,
 * icons and slide-between-keys input.
 *
 * Performance: colors, labels, icons and accessibility descriptions are resolved once in
 * [buildKeys] (on layer/shift/size/configuration change, never on a keystroke). [onDraw]
 * reuses one Paint, one RectF, one FontMetrics and one text bounds Rect; hit testing uses
 * an index loop rather than allocating a lambda/selection per touch. No animations.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface OnKeyListener {
        /** Return true when the key was handled (e.g. Enter consumed an editor action). */
        fun onKey(key: KeyDef): Boolean
    }

    var onKeyListener: OnKeyListener? = null

    private class PositionedKey(
        val def: KeyDef,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val label: String,
        val normalBackground: Int,
        val pressedBackground: Int,
        val foreground: Int,
        val icon: Drawable?,
        val iconTint: Int,
        val tintIcon: Boolean,
        val showBackground: Boolean,
        val isToolbar: Boolean,
        val description: String,
        val bold: Boolean
    )

    private var layer = KeyboardLayer.LETTERS
    private var shiftState = ShiftState.OFF
    private var keys: List<PositionedKey> = emptyList()
    private var pressedKey: PositionedKey? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private var theme = KeyboardPalette.themeForUiMode(resources.configuration.uiMode)
    private var colors = KeyboardPalette.resolve(context, theme)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val tooltipBounds = RectF()
    private val textBounds = Rect()
    private val fontMetrics = Paint.FontMetrics()
    private val iconCache = mutableMapOf<Int, Drawable>()
    private val handler = Handler(Looper.getMainLooper())
    private val location = IntArray(2)

    private var toolbarDividerY = 0f
    private var tooltipKey: PositionedKey? = null
    private var tooltipText = ""
    private val showTooltipRunnable = Runnable {
        val key = tooltipKey
        if (key != null) {
            val resId = key.def.tooltipRes
            tooltipText = if (resId == null) "" else context.getString(resId)
            if (tooltipText.isNotEmpty()) invalidate()
        }
    }

    private val horizontalPadding: Float
    private val gap: Float
    private val verticalGap: Float
    private val toolbarGap: Float
    private val toolbarTouchSlop: Float
    private val cornerRadius: Float
    private val toolbarHeight: Float
    private val charTextSize: Float
    private val functionTextSize: Float
    private val tooltipTextSize: Float
    private val tooltipPadding: Float

    init {
        isClickable = true
        horizontalPadding = resources.getDimension(R.dimen.keyboard_horizontal_padding)
        gap = resources.getDimension(R.dimen.key_gap)
        verticalGap = resources.getDimension(R.dimen.key_vertical_gap)
        toolbarGap = resources.getDimension(R.dimen.toolbar_gap)
        toolbarTouchSlop = resources.getDimension(R.dimen.toolbar_touch_slop)
        cornerRadius = resources.getDimension(R.dimen.key_corner_radius)
        toolbarHeight = resources.getDimension(R.dimen.toolbar_height)
        charTextSize = resources.getDimension(R.dimen.key_char_text_size)
        functionTextSize = resources.getDimension(R.dimen.key_function_text_size)
        tooltipTextSize = resources.getDimension(R.dimen.tooltip_text_size)
        tooltipPadding = resources.getDimension(R.dimen.tooltip_padding)
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val next = KeyboardPalette.themeForUiMode(newConfig.uiMode)
        if (next == theme) return
        theme = next
        colors = KeyboardPalette.resolve(context, next)
        buildKeys()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildKeys()
    }

    private fun buildKeys() {
        if (width <= 0 || height <= 0) return
        val rows = ArrayList<KeyRow>(5)
        rows.add(KeyLayouts.toolbarRow())
        rows.addAll(KeyLayouts.rows(layer, shiftState != ShiftState.OFF))

        val heights = KeyGeometry.rowHeights(rows.size, height.toFloat(), verticalGap, toolbarHeight)
        val usableWidth = width - 2 * horizontalPadding
        val result = ArrayList<PositionedKey>(48)
        var y = 0f
        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            val rowGap = if (row.isToolbar) toolbarGap else gap
            val weights = ArrayList<Float>(row.keys.size)
            for (def in row.keys) weights.add(def.weight)
            val placements = KeyGeometry.placeRow(weights, horizontalPadding, usableWidth, rowGap)
            val rowHeight = heights[rowIndex]
            for (placement in placements) {
                val def = row.keys[placement.index]
                if (def.type == KeyType.SPACER) continue
                result.add(makeKey(def, placement.x, y, placement.width, rowHeight, row.isToolbar))
            }
            if (rowIndex == 0) toolbarDividerY = y + rowHeight + verticalGap / 2f
            y += rowHeight + verticalGap
        }
        keys = result
        pressedKey = null
    }

    private fun makeKey(
        def: KeyDef, x: Float, y: Float, w: Float, h: Float, isToolbar: Boolean
    ): PositionedKey {
        val type = def.type
        val accent = type == KeyType.SPACE || type == KeyType.ENTER ||
            type == KeyType.SHIELD || type == KeyType.ENCRYPT
        val functional = !accent && type != KeyType.CHARACTER && type != KeyType.BRAND
        val background = when {
            type == KeyType.BRAND -> 0
            accent -> colors.accent
            functional -> colors.functionKey
            else -> colors.key
        }
        val pressedBackground = when {
            accent -> colors.accentPressed
            functional -> colors.functionKeyPressed
            else -> colors.keyPressed
        }
        val rawLabel = def.text ?: def.label ?: ""
        val label = if (type == KeyType.CHARACTER && layer == KeyboardLayer.LETTERS &&
            shiftState != ShiftState.OFF
        ) rawLabel.uppercase(Locale.ROOT) else rawLabel
        val description = def.descriptionRes?.let { context.getString(it) }
            ?: label.ifEmpty { context.getString(R.string.keyboard_content_description) }
        val icon = def.iconRes?.let(::iconDrawable)
        return PositionedKey(
            def = def,
            x = x, y = y, w = w, h = h,
            label = label,
            normalBackground = background,
            pressedBackground = pressedBackground,
            foreground = colors.glyph,
            icon = icon,
            iconTint = if (type == KeyType.AI_TONE) colors.accent else colors.glyph,
            tintIcon = type != KeyType.BRAND,
            showBackground = type != KeyType.BRAND,
            isToolbar = isToolbar,
            description = description,
            bold = functional || accent
        )
    }

    private fun iconDrawable(resId: Int): Drawable =
        iconCache.getOrPut(resId) { requireNotNull(ContextCompat.getDrawable(context, resId)).mutate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = colors.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        if (keys.isEmpty()) return
        paint.color = colors.divider
        canvas.drawRect(
            0f, toolbarDividerY - 0.5f, width.toFloat(), toolbarDividerY + 0.5f, paint
        )
        for (key in keys) drawKey(canvas, key)
        val tipKey = tooltipKey
        if (tooltipText.isNotEmpty() && tipKey != null) drawTooltip(canvas, tipKey)
    }

    private fun drawKey(canvas: Canvas, key: PositionedKey) {
        bounds.set(key.x, key.y, key.x + key.w, key.y + key.h)
        if (key.showBackground) {
            paint.color = if (pressedKey === key) key.pressedBackground else key.normalBackground
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, paint)
        }
        val icon = key.icon
        if (icon != null) {
            if (key.tintIcon) DrawableCompat.setTint(icon, key.iconTint)
            val size = (min(key.w, key.h) * key.def.iconScale).toInt().coerceAtLeast(1)
            val left = (bounds.centerX() - size / 2f).toInt()
            val top = (bounds.centerY() - size / 2f).toInt()
            icon.setBounds(left, top, left + size, top + size)
            icon.draw(canvas)
            return
        }
        if (key.label.isEmpty()) return
        paint.color = key.foreground
        paint.typeface = if (key.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (key.def.type == KeyType.CHARACTER) charTextSize else functionTextSize
        paint.getFontMetrics(fontMetrics)
        val baseline = bounds.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(key.label, bounds.centerX(), baseline, paint)
    }

    private fun drawTooltip(canvas: Canvas, key: PositionedKey) {
        paint.typeface = Typeface.DEFAULT
        paint.textSize = tooltipTextSize
        paint.getTextBounds(tooltipText, 0, tooltipText.length, textBounds)
        val widthNeeded = textBounds.width() + 2 * tooltipPadding
        val heightNeeded = textBounds.height() + 2 * tooltipPadding
        val left = (key.x + key.w / 2f - widthNeeded / 2f)
            .coerceIn(0f, (width - widthNeeded).coerceAtLeast(0f))
        var top = key.y - heightNeeded - verticalGap
        if (top < 0f) top = key.y + key.h + verticalGap
        tooltipBounds.set(left, top, left + widthNeeded, top + heightNeeded)
        paint.color = colors.tooltipBackground
        canvas.drawRoundRect(tooltipBounds, cornerRadius, cornerRadius, paint)
        paint.color = colors.tooltipText
        paint.textAlign = Paint.Align.CENTER
        paint.getFontMetrics(fontMetrics)
        val baseline = tooltipBounds.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(tooltipText, tooltipBounds.centerX(), baseline, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                val downKey = keyAt(event.getX(0), event.getY(0))
                pressedKey = downKey
                if (downKey != null) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    scheduleTooltip(downKey)
                }
                invalidate()
                return downKey != null
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
                    if (candidate == null) cancelTooltip() else scheduleTooltip(candidate)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val fallback = if (event.actionIndex == 0) 1 else 0
                    if (fallback < event.pointerCount) {
                        activePointerId = event.getPointerId(fallback)
                        val fallbackKey = keyAt(event.getX(fallback), event.getY(fallback))
                        pressedKey = fallbackKey
                        if (fallbackKey == null) cancelTooltip() else scheduleTooltip(fallbackKey)
                    } else {
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        pressedKey = null
                        cancelTooltip()
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(event.actionIndex) != activePointerId) return true
                val tapped = keyAt(event.getX(event.actionIndex), event.getY(event.actionIndex))
                val tooltipWasVisible = tooltipText.isNotEmpty()
                cancelTooltip()
                val handled = if (tapped != null && !tooltipWasVisible && tapped.def.type != KeyType.BRAND) {
                    onKeyListener?.onKey(tapped.def) == true
                } else {
                    false
                }
                activePointerId = MotionEvent.INVALID_POINTER_ID
                pressedKey = null
                invalidate()
                return handled || tapped != null
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                pressedKey = null
                cancelTooltip()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scheduleTooltip(key: PositionedKey?) {
        handler.removeCallbacks(showTooltipRunnable)
        tooltipKey = null
        tooltipText = ""
        if (key == null || key.def.tooltipRes == null) return
        tooltipKey = key
        handler.postDelayed(showTooltipRunnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelTooltip() {
        handler.removeCallbacks(showTooltipRunnable)
        tooltipKey = null
        tooltipText = ""
    }

    /**
     * Toolbar icons are drawn narrower than 48dp on small phones; an 8dp horizontal slop
     * plus nearest-centre tie breaking keeps their effective target at least 48dp.
     */
    private fun keyAt(x: Float, y: Float): PositionedKey? {
        var best: PositionedKey? = null
        var bestDistance = Float.MAX_VALUE
        for (index in keys.indices) {
            val key = keys[index]
            val slop = if (key.isToolbar) toolbarTouchSlop else 0f
            if (x < key.x - slop || x > key.x + key.w + slop || y < key.y || y > key.y + key.h) continue
            val dx = x - (key.x + key.w / 2f)
            val distance = dx * dx
            if (distance < bestDistance) {
                best = key
                bestDistance = distance
            }
        }
        return best
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? = accessibilityProvider

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.contentDescription = context.getString(R.string.keyboard_content_description)
    }

    private val accessibilityProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                return AccessibilityNodeInfo.obtain(this@KeyboardView).apply {
                    contentDescription = context.getString(R.string.keyboard_content_description)
                }
            }
            val key = keys.getOrNull(virtualViewId) ?: return null
            return AccessibilityNodeInfo.obtain(this@KeyboardView, virtualViewId).apply {
                className = "android.widget.Button"
                isClickable = true
                contentDescription = key.description
                this@KeyboardView.getLocationOnScreen(location)
                setBoundsInScreen(
                    Rect(
                        location[0] + key.x.toInt(),
                        location[1] + key.y.toInt(),
                        location[0] + (key.x + key.w).toInt(),
                        location[1] + (key.y + key.h).toInt()
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
            val key = keys.getOrNull(virtualViewId) ?: return false
            if (key.def.type == KeyType.BRAND) return false
            onKeyListener?.onKey(key.def)
            return true
        }
    }
}
