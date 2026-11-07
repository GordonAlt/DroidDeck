package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Optional on-screen mouse surface. Touches outside its hit areas remain available to the game. */
class MouseTouchControls(context: Context) : View(context) {
    var onRelativeMove: ((Float, Float) -> Unit)? = null
    var onAbsoluteMove: ((Float, Float) -> Unit)? = null
    var onButton: ((Int, Boolean) -> Unit)? = null
    var onWheel: ((Int) -> Unit)? = null
    var onTouchMiss: ((MotionEvent) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad = RectF()
    private val left = RectF()
    private val right = RectF()
    private val middle = RectF()
    private val wheel = RectF()
    private val mode = RectF()
    private val toggle = RectF()
    private val active = HashMap<Int, Int>() // pointer -> button code; -1 pad, -2 wheel, -3 mode, -4 toggle
    private val forwarded = HashSet<Int>()
    private var enabled = false
    private var absolute = false
    private var lastX = 0f
    private var lastY = 0f
    private var wheelRemainder = 0f
    private val density = resources.displayMetrics.density

    init { isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val margin = 18f * density
        val padW = w * 0.34f
        val padH = h * 0.31f
        pad.set(margin, h - padH - margin, margin + padW, h - margin)
        val buttonSize = 54f * density
        val gap = 10f * density
        val rightX = w - margin - buttonSize
        right.set(rightX, h - margin - buttonSize, rightX + buttonSize, h - margin)
        left.set(rightX - buttonSize - gap, h - margin - buttonSize, rightX - gap, h - margin)
        middle.set(rightX - (buttonSize + gap) / 2f - buttonSize / 2f, h - margin - buttonSize * 2f - gap, rightX - (buttonSize + gap) / 2f + buttonSize / 2f, h - margin - buttonSize - gap)
        wheel.set(w - margin - 34f * density, h - padH - margin - 8f * density, w - margin, h - margin - buttonSize - gap)
        mode.set(margin, margin + 44f * density, margin + 76f * density, margin + 76f * density)
        toggle.set(w - margin - 84f * density, margin, w - margin, margin + 38f * density)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(95, 12, 18, 27)
        canvas.drawRoundRect(toggle, 18f * density, 18f * density, paint)
        paint.color = Color.WHITE
        paint.textSize = 12f * density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (enabled) "MOUSE ON" else "MOUSE", toggle.centerX(), toggle.centerY() + 4f * density, paint)
        if (!enabled) return
        drawSurface(canvas, pad, "MOVE")
        drawSurface(canvas, left, "L")
        drawSurface(canvas, right, "R")
        drawSurface(canvas, middle, "M")
        drawSurface(canvas, wheel, "SCROLL")
        drawSurface(canvas, mode, if (absolute) "ABS" else "REL")
    }

    private fun drawSurface(canvas: Canvas, r: RectF, label: String) {
        paint.color = Color.argb(78, 15, 23, 34)
        canvas.drawRoundRect(r, 14f * density, 14f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = Color.argb(165, 110, 190, 255)
        canvas.drawRoundRect(r, 14f * density, 14f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(225, 245, 248, 252)
        paint.textSize = 11f * density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, r.centerX(), r.centerY() + 4f * density, paint)
    }

    private fun buttonCode(target: Int): Int = when (target) {
        1 -> PointerGestures.BTN_LEFT
        2 -> PointerGestures.BTN_RIGHT
        else -> PointerGestures.BTN_MIDDLE
    }

    private fun hit(x: Float, y: Float): Int = when {
        toggle.contains(x, y) -> -4
        !enabled -> 0
        mode.contains(x, y) -> -3
        pad.contains(x, y) -> -1
        wheel.contains(x, y) -> -2
        left.contains(x, y) -> 1
        right.contains(x, y) -> 2
        middle.contains(x, y) -> 3
        else -> 0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val x = event.getX(i); val y = event.getY(i)
                when (val target = hit(x, y)) {
                    0 -> {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) return false
                        forwarded += id
                        forward(event)
                    }
                    -4 -> { enabled = !enabled; invalidate(); active[id] = -4 }
                    -3 -> { absolute = !absolute; invalidate(); active[id] = -3 }
                    -1 -> { active[id] = -1; lastX = x; lastY = y; if (absolute) onAbsoluteMove?.invoke(x, y) }
                    -2 -> { active[id] = -2; lastY = y; wheelRemainder = 0f }
                    else -> { active[id] = target; onButton?.invoke(buttonCode(target), true); invalidate() }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (forwarded.isNotEmpty()) forward(event)
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i); val y = event.getY(i)
                    when (active[id]) {
                        -1 -> if (absolute) onAbsoluteMove?.invoke(x, y) else {
                            onRelativeMove?.invoke(x - lastX, y - lastY)
                            lastX = x; lastY = y
                        }
                        -2 -> {
                            wheelRemainder += (lastY - y) / (42f * density)
                            val steps = wheelRemainder.toInt()
                            if (steps != 0) { onWheel?.invoke(steps); wheelRemainder -= steps }
                            lastY = y
                        }
                    }
                }
                return active.isNotEmpty() || forwarded.isNotEmpty()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex; val id = event.getPointerId(i)
                if (id in forwarded) { forward(event); forwarded.remove(id) }
                active.remove(id)?.let { if (it in 1..3) onButton?.invoke(buttonCode(it), false) }
                invalidate()
                return active.isNotEmpty() || forwarded.isNotEmpty() || event.actionMasked == MotionEvent.ACTION_UP
            }
            MotionEvent.ACTION_CANCEL -> {
                if (forwarded.isNotEmpty()) forward(event)
                forwarded.clear()
                active.values.filter { it in 1..3 }.forEach { onButton?.invoke(buttonCode(it), false) }
                active.clear(); invalidate(); return true
            }
        }
        return active.isNotEmpty() || forwarded.isNotEmpty()
    }

    private fun forward(event: MotionEvent) {
        if (forwarded.isEmpty()) return
        var bits = 0
        forwarded.forEach { if (it in 0..31) bits = bits or (1 shl it) }
        val split = event.pointerSubset(bits) ?: return
        try { onTouchMiss?.invoke(split) } finally { split.recycle() }
    }

    fun releaseAll() {
        active.values.filter { it in 1..3 }.forEach { onButton?.invoke(buttonCode(it), false) }
        active.clear(); forwarded.clear(); invalidate()
    }
}
