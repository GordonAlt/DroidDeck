package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

class ScreenKeys(context: Context, private val sendKey: (Int, Boolean) -> Unit, private val profile: String? = null) : View(context) {
    private class Entry(val codes: List<Int>) {
        var directionBindings: List<List<Int>> = emptyList()
        var glyph = ""
        var text = ""
        var image = ""
        var bitmap: Bitmap? = null
        var bitmapFor = ""
        var x = 0.5f
        var y = 0.5f
        var size = DEFAULT_SIZE
        var opacity = 1f
        var shape = SCREEN_KEY_PILL
        var toggle = false
        var labels = true
        var action = ""
        var width = 0
        var height = 0
        var fillColor = 0
        var borderColor = 0
        var textColor = 0
        var borderWidth = 0f
        var cornerRadius = -1f
        var radialGroup = ""
        var radialParent = false
        var radialSlot = -1
        var armed = -1
        var cx = 0f
        var cy = 0f
        var halfW = 0f
        var halfH = 0f
        var stickX = 0f
        var stickY = 0f
    }

    private val entries = mutableListOf<Entry>()
    private val down = HashMap<Int, Entry>()
    private val latched = mutableListOf<Entry>()
    private val dpadDown = HashMap<Int, Int>()
    private val stickEntries = HashMap<Int, Entry>()
    private val stickCodes = HashMap<Int, List<Int>>()
    private val analogSticks = HashMap<Int, Pair<Entry, Boolean>>()
    private val mouseDown = HashMap<Int, Entry>()
    private val mouseRegions = HashMap<Int, Triple<Entry, Float, Float>>()
    private val mouseButtonOwners = HashMap<Int, Int>()
    private val scrollRepeaters = HashMap<Entry, Runnable>()
    private val held = HashMap<Int, Int>()
    private var forwardedPointerBits = 0
    private var addX = -1f
    private var addY = -1f
    private var pressX = 0f
    private var pressY = 0f
    private var guideX = -1f
    private var guideY = -1f
    private val addPress = Runnable { addX = pressX; addY = pressY; onAdd?.invoke() }
    private var editing = false
    private var selected: Entry? = null
    private var activeRadialGroup: String? = null
    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false
    private var moved = false
    private var toolbarDrag = false
    private var safe = Rect()
    private var tint = ControllerPrefs.STEAM_BLUE

    init {
        ControllerPrefs.screenKeys(context, profile).forEach { key -> entries += entryOf(key) }
    }

    var followSteam = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var steamStyle: () -> Pair<Int, Float>? = { null }

    var onAdd: (() -> Unit)? = null
    var onSelect: ((List<Int>) -> Unit)? = null
    var onAnalogStick: ((Boolean, Float, Float) -> Unit)? = null
    var onMouseButton: ((Int, Boolean) -> Unit)? = null
    /** absolute gives normalized region coordinates; relative gives per-event pixel deltas. */
    var onMouseMove: ((absolute: Boolean, x: Float, y: Float) -> Unit)? = null
    var onMouseWheel: ((Int) -> Unit)? = null
    var onEditingChanged: ((Boolean) -> Unit)? = null
    var onTouchMiss: ((MotionEvent) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val bodyPathRect = RectF()
    private val bodyPath = android.graphics.Path()
    private val clipPath = android.graphics.Path()
    private val imageRect = RectF()
    private val bitmapRect = RectF()

    private val capsuleRadius = dp(24f)
    private val capsuleGap = dp(62f)
    private val toolbar = PointF()
    private val addCenter = PointF()
    private val doneCenter = PointF()

    fun startEditing() {
        if (editing) return
        editing = true
        releaseAll()
        selected = null
        placeToolbar()
        invalidate()
        onEditingChanged?.invoke(true)
    }

    fun stopEditing() {
        if (!editing) return
        releaseAll()
        editing = false
        selected = null
        save()
        invalidate()
        onEditingChanged?.invoke(false)
    }

    fun add(codes: List<Int>) {
        if (codes.isEmpty()) return
        entries.firstOrNull { it.codes == codes }?.let {
            selected = it
            invalidate()
            return
        }
        val entry = Entry(codes).also {
            if (GamepadTouchCodes.LEFT_ANALOG in codes || GamepadTouchCodes.RIGHT_ANALOG in codes) it.shape = SCREEN_KEY_STICK
        }
        if (addX >= 0f && width > 0) {
            entry.x = (addX / width).coerceIn(0.05f, 0.95f)
            entry.y = (addY / height).coerceIn(0.05f, 0.95f)
        } else {
            entry.x = (0.30f + 0.08f * (entries.size % 5)).coerceAtMost(0.70f)
            entry.y = 0.62f
        }
        addX = -1f
        addY = -1f
        entries += entry
        if (width > 0) measure(entry)
        selected = entry
        save()
        invalidate()
    }

    fun addAction(action: String) {
        val code = ScreenKeyActions.code(action) ?: return
        val codes = listOf(code)
        entries.firstOrNull { it.action == action }?.let { selected = it; invalidate(); return }
        val entry = Entry(codes).also {
            it.action = action
            it.shape = SCREEN_KEY_RECT
            it.size = if (action == ScreenKeyActions.MOUSE_REGION_ABSOLUTE || action == ScreenKeyActions.MOUSE_REGION_RELATIVE) 120 else DEFAULT_SIZE
            it.text = ScreenKeyActions.label(action)
        }
        if (addX >= 0f && width > 0) {
            entry.x = (addX / width).coerceIn(0.05f, 0.95f)
            entry.y = (addY / height).coerceIn(0.05f, 0.95f)
        } else {
            entry.x = (0.30f + 0.08f * (entries.size % 5)).coerceAtMost(0.70f)
            entry.y = 0.62f
        }
        addX = -1f; addY = -1f
        entries += entry
        if (width > 0) measure(entry)
        selected = entry
        save(); invalidate()
    }

    /** Adds one expandable parent and eight independently configurable child slots. */
    fun addRadialGroup(): ScreenKey? {
        val group = "radial-${System.nanoTime()}"
        val base = -41000 - (kotlin.math.abs(group.hashCode()) % 1000000) * 10
        val parent = Entry(listOf(base)).also {
            it.radialGroup = group
            it.radialParent = true
            it.text = "RADIAL"
            it.x = 0.5f
            it.y = 0.5f
            it.size = 48
        }
        entries += parent
        repeat(8) { slot ->
            entries += Entry(listOf(base - slot - 1)).also {
                it.radialGroup = group
                it.radialSlot = slot
                it.text = "${slot + 1}"
                it.size = 42
            }
        }
        if (width > 0 && height > 0) entries.forEach(::measure)
        selected = parent
        save()
        invalidate()
        return stateOf(parent.codes)
    }

    fun remove(codes: List<Int>) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        if (entry.radialParent) {
            if (activeRadialGroup == entry.radialGroup) activeRadialGroup = null
            entries.removeAll { it.radialGroup == entry.radialGroup }
        } else entries.remove(entry)
        if (selected === entry) selected = null
        save()
        invalidate()
    }

    fun setCodes(from: List<Int>, to: List<Int>): ScreenKey? {
        if (to.isEmpty()) return null
        val index = entries.indexOfFirst { it.codes == from }
        if (index < 0) return null
        val old = entries[index]
        if (to != from && entries.any { it !== old && it.codes == to }) return null
        val fresh = Entry(to).also {
            it.glyph = old.glyph
            it.text = when {
                old.radialSlot in 0..7 && old.text == "${old.radialSlot + 1}" -> ""
                old.action.isNotEmpty() && old.text == ScreenKeyActions.label(old.action) -> ""
                else -> old.text
            }
            it.image = old.image
            it.bitmap = old.bitmap
            it.bitmapFor = old.bitmapFor
            it.x = old.x
            it.y = old.y
            it.size = old.size
            it.opacity = old.opacity
            it.shape = if (GamepadTouchCodes.LEFT_ANALOG in to || GamepadTouchCodes.RIGHT_ANALOG in to) SCREEN_KEY_STICK else old.shape
            it.toggle = old.toggle
            it.labels = old.labels
            it.action = if (to.all { code -> code > 0 }) "" else old.action
            it.width = old.width
            it.height = old.height
            it.fillColor = old.fillColor
            it.borderColor = old.borderColor
            it.textColor = old.textColor
            it.borderWidth = old.borderWidth
            it.cornerRadius = old.cornerRadius
            it.radialGroup = old.radialGroup
            it.radialParent = old.radialParent
            it.radialSlot = old.radialSlot
            it.directionBindings = when {
                it.shape == SCREEN_KEY_STICK &&
                    GamepadTouchCodes.LEFT_ANALOG !in to && GamepadTouchCodes.RIGHT_ANALOG !in to -> when {
                    old.directionBindings.size == 8 -> old.directionBindings.map { group -> group.toList() }
                    old.shape == SCREEN_KEY_DPAD -> legacyDirectionBindings(to)
                    else -> defaultKeyboardStickBindings()
                }
                it.shape == SCREEN_KEY_DPAD -> legacyDirectionBindings(to)
                else -> old.directionBindings.map { group -> group.toList() }
            }
        }
        entries[index] = fresh
        measure(fresh)
        if (selected === old) selected = fresh
        save()
        invalidate()
        return stateOf(fresh.codes)
    }

    fun setText(codes: List<Int>, text: String) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        entry.text = text
        measure(entry)
        save()
        invalidate()
    }

    /** Assigns a mouse/semantic action to an existing control, including a radial child slot. */
    fun setAction(codes: List<Int>, action: String): ScreenKey? {
        if (ScreenKeyActions.code(action) == null) return null
        val entry = entries.firstOrNull { it.codes == codes } ?: return null
        val previousLabel = entry.action.takeIf { it.isNotEmpty() }?.let { ScreenKeyActions.label(it) }.orEmpty()
        if (entry.text.isEmpty() || entry.text == previousLabel ||
            (entry.radialSlot in 0..7 && entry.text == "${entry.radialSlot + 1}")
        ) {
            entry.text = ScreenKeyActions.label(action)
        }
        entry.action = action
        entry.toggle = false
        if (action == ScreenKeyActions.MOUSE_REGION_ABSOLUTE ||
            action == ScreenKeyActions.MOUSE_REGION_RELATIVE
        ) {
            entry.shape = SCREEN_KEY_RECT
            if (entry.size < 80) entry.size = 120
        }
        measure(entry)
        save()
        invalidate()
        return stateOf(entry.codes)
    }

    fun setImage(codes: List<Int>, image: String) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        val previous = entry.image
        entry.image = image
        entry.bitmap = null
        entry.bitmapFor = ""
        if (previous.isNotEmpty() && previous != image) File(File(context.filesDir, IMAGE_DIR), previous).delete()
        measure(entry)
        save()
        invalidate()
    }

    fun resize(codes: List<Int>, size: Int) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        entry.size = size.coerceIn(MIN_SIZE, MAX_SIZE)
        measure(entry)
        save()
        invalidate()
    }

    fun setWidth(codes: List<Int>, value: Int) = updateEntry(codes) { it.width = value.coerceIn(0, 280) }
    fun setHeight(codes: List<Int>, value: Int) = updateEntry(codes) { it.height = value.coerceIn(0, 180) }
    fun setFillColor(codes: List<Int>, value: Int) = updateEntry(codes) { it.fillColor = value }
    fun setBorderColor(codes: List<Int>, value: Int) = updateEntry(codes) { it.borderColor = value }
    fun setTextColor(codes: List<Int>, value: Int) = updateEntry(codes) { it.textColor = value }
    fun setBorderWidth(codes: List<Int>, value: Float) = updateEntry(codes) { it.borderWidth = value.coerceIn(0f, 12f) }
    fun setCornerRadius(codes: List<Int>, value: Float) = updateEntry(codes) { it.cornerRadius = value.coerceIn(0f, 80f) }

    private inline fun updateEntry(codes: List<Int>, change: (Entry) -> Unit) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        change(entry)
        measure(entry)
        save()
        invalidate()
    }

    fun setOpacity(codes: List<Int>, opacity: Float) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        entry.opacity = opacity.coerceIn(0.2f, 1f)
        save()
        invalidate()
    }

    fun setShape(codes: List<Int>, shape: Int) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        val nextShape = shape.coerceIn(SCREEN_KEY_PILL, SCREEN_KEY_STICK)
        if (nextShape == SCREEN_KEY_STICK &&
            GamepadTouchCodes.LEFT_ANALOG !in entry.codes &&
            GamepadTouchCodes.RIGHT_ANALOG !in entry.codes &&
            entry.directionBindings.size != 8
        ) {
            entry.directionBindings = if (entry.shape == SCREEN_KEY_DPAD) {
                legacyDirectionBindings(entry.codes)
            } else {
                defaultKeyboardStickBindings()
            }
        }
        entry.shape = nextShape
        measure(entry)
        save()
        invalidate()
    }

    fun setToggle(codes: List<Int>, toggle: Boolean) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        entry.toggle = toggle && entry.action.isEmpty()
        if (!toggle && latched.remove(entry)) release(entry)
        save()
        invalidate()
    }

    fun setLabels(codes: List<Int>, labels: Boolean) {
        val entry = entries.firstOrNull { it.codes == codes } ?: return
        entry.labels = labels
        save()
        invalidate()
    }

    fun setDirection(codes: List<Int>, index: Int, selectedCodes: List<Int>): ScreenKey? {
        if (index !in 0..7) return null
        val entry = entries.firstOrNull { it.codes == codes } ?: return null
        val bindings = (if (entry.directionBindings.size == 8) entry.directionBindings
            else legacyDirectionBindings(entry.codes)).map { it.toList() }.toMutableList()
        bindings[index] = selectedCodes.filter { it > 0 }.distinct()
        val updated = Entry(entry.codes).also {
            it.directionBindings = bindings
            it.glyph = entry.glyph
            it.text = entry.text
            it.image = entry.image
            it.bitmap = entry.bitmap
            it.bitmapFor = entry.bitmapFor
            it.x = entry.x
            it.y = entry.y
            it.size = entry.size
            it.opacity = entry.opacity
            it.shape = entry.shape
            it.toggle = entry.toggle
            it.labels = entry.labels
            it.action = entry.action
            it.width = entry.width
            it.height = entry.height
            it.fillColor = entry.fillColor
            it.borderColor = entry.borderColor
            it.textColor = entry.textColor
            it.borderWidth = entry.borderWidth
            it.cornerRadius = entry.cornerRadius
            it.radialGroup = entry.radialGroup
            it.radialParent = entry.radialParent
            it.radialSlot = entry.radialSlot
        }
        entries[entries.indexOf(entry)] = updated
        measure(updated)
        if (selected === entry) selected = updated
        save()
        invalidate()
        return stateOf(updated.codes)
    }

    private fun defaultKeyboardStickBindings(): List<List<Int>> = listOf(
        listOf(17), listOf(17, 32), listOf(32), listOf(32, 31),
        listOf(31), listOf(31, 30), listOf(30), listOf(30, 17),
    )

    private fun legacyDirectionBindings(codes: List<Int>): List<List<Int>> =
        (0..7).map { index ->
            val code = codes.getOrNull(index) ?: 0
            if (code != 0) listOf(code)
            else if (index % 2 == 1) listOfNotNull(
                codes.getOrNull((index + 7) % 8)?.takeIf { it != 0 },
                codes.getOrNull((index + 1) % 8)?.takeIf { it != 0 },
            ).distinct() else emptyList()
        }

    private fun trimmed(codes: List<Int>): List<Int> = codes.dropLastWhile { it == 0 }.ifEmpty { listOf(0) }

    fun stateOf(codes: List<Int>): ScreenKey? = entries.firstOrNull { it.codes == codes }?.let {
        ScreenKey(it.codes, it.x, it.y, it.size, it.glyph, it.text, it.image, it.opacity, it.shape, it.toggle, it.labels, it.action, it.directionBindings,
            it.width, it.height, it.fillColor, it.borderColor, it.textColor, it.borderWidth, it.cornerRadius,
            it.radialGroup, it.radialParent, it.radialSlot)
    }

    fun deselect() {
        if (selected == null) return
        selected = null
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshAppearance()
    }

    fun refreshAppearance() {
        tint = ControllerPrefs.read(context).tint
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        entries.forEach { measure(it) }
        placeToolbar()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
        val fresh = Rect(
            cutout?.safeInsetLeft ?: 0, cutout?.safeInsetTop ?: 0,
            cutout?.safeInsetRight ?: 0, cutout?.safeInsetBottom ?: 0,
        )
        if (fresh != safe) {
            safe = fresh
            placeToolbar()
            invalidate()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) releaseAll()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility != VISIBLE) releaseAll()
    }

    fun releaseAll() {
        mouseDown.values.forEach(::release)
        mouseDown.clear(); mouseRegions.clear()
        mouseButtonOwners.toMap().forEach { (button, _) -> onMouseButton?.invoke(button, false) }
        mouseButtonOwners.clear()
        scrollRepeaters.values.toList().forEach { removeCallbacks(it) }
        scrollRepeaters.clear()
        dpadDown.values.forEach { releaseKey(it) }
        dpadDown.clear()
        entries.forEach { it.armed = -1 }
        stickCodes.values.flatten().forEach { if (it != 0) releaseKey(it) }
        stickCodes.clear()
        stickEntries.clear()
        analogSticks.values.forEach { (entry, left) -> entry.stickX = 0f; entry.stickY = 0f; onAnalogStick?.invoke(left, 0f, 0f) }
        analogSticks.clear()
        down.values.forEach { release(it) }
        down.clear()
        latched.toList().forEach { release(it) }
        latched.clear()
        invalidate()
    }

    private fun snap(value: Float) = if (width > 0) round(value / (width / 100f)) * (width / 100f) else value

    private fun placeToolbar() {
        if (width == 0 || height == 0) return
        if (toolbar.x == 0f && toolbar.y == 0f) {
            toolbar.set(width - safe.right - capsuleGap - capsuleRadius, height - safe.bottom - capsuleRadius - dp(20f))
        }
        addCenter.set(toolbar.x - capsuleGap / 2f, toolbar.y)
        doneCenter.set(toolbar.x + capsuleGap / 2f, toolbar.y)
    }

    private fun entryOf(key: ScreenKey): Entry = Entry(key.codes).also {
        it.glyph = key.glyph
        it.text = key.text
        it.image = key.image
        it.x = key.x
        it.y = key.y
        it.size = key.size
        it.opacity = key.opacity
        it.shape = key.shape
        it.toggle = key.toggle
        it.labels = key.labels
        it.action = key.action.ifEmpty { ScreenKeyActions.fromCode(key.codes.singleOrNull() ?: 0).orEmpty() }
        it.width = key.width
        it.height = key.height
        it.fillColor = key.fillColor
        it.borderColor = key.borderColor
        it.textColor = key.textColor
        it.borderWidth = key.borderWidth
        it.cornerRadius = key.cornerRadius
        it.radialGroup = key.radialGroup
        it.radialParent = key.radialParent
        it.radialSlot = key.radialSlot
        it.directionBindings = when {
            key.directionBindings.size == 8 -> key.directionBindings.map { group -> group.filter { code -> code > 0 }.distinct() }
            (key.shape == SCREEN_KEY_STICK || key.shape == SCREEN_KEY_DPAD) &&
                GamepadTouchCodes.LEFT_ANALOG !in key.codes && GamepadTouchCodes.RIGHT_ANALOG !in key.codes ->
                legacyDirectionBindings(key.codes)
            else -> emptyList()
        }
    }

    private fun label(entry: Entry) = when {
        entry.text.isNotEmpty() -> entry.text
        entry.glyph.isNotEmpty() -> entry.glyph
        entry.action.isNotEmpty() -> ScreenKeyActions.label(entry.action)
        else -> screenKeyLabel(context, entry.codes)
    }

    private fun bitmapFor(entry: Entry): Bitmap? {
        if (entry.image.isEmpty()) return null
        if (entry.bitmapFor == entry.image) return entry.bitmap
        val file = File(File(context.filesDir, IMAGE_DIR), entry.image)
        val bitmap = if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
        entry.bitmap = bitmap
        entry.bitmapFor = entry.image
        return bitmap
    }

    private fun measure(entry: Entry) {
        val h = dp((entry.height.takeIf { it > 0 } ?: entry.size).toFloat())
        entry.halfH = h / 2f
        val automaticWidth = when {
            entry.action == ScreenKeyActions.MOUSE_REGION_ABSOLUTE || entry.action == ScreenKeyActions.MOUSE_REGION_RELATIVE -> h * 1.35f
            entry.shape == SCREEN_KEY_CIRCLE -> entry.halfH
            entry.shape == SCREEN_KEY_TRIGGER -> entry.halfH * 1.7f
            entry.shape == SCREEN_KEY_DPAD || entry.shape == SCREEN_KEY_STICK -> entry.halfH * 1.15f
            entry.image.isNotEmpty() -> entry.halfH * 1.15f
            hasIcon(entry) -> entry.halfH * 1.15f
            else -> {
                val chars = label(entry).length
                entry.halfH * (0.55f * chars + 1.05f).coerceIn(1.7f, 4.6f)
            }
        }
        entry.halfW = if (entry.width > 0) dp(entry.width.toFloat()) / 2f else automaticWidth
        entry.cx = entry.x * width
        entry.cy = entry.y * height
        if (entry.radialSlot in 0..7 && entry.radialGroup.isNotEmpty()) {
            val parent = entries.firstOrNull { it.radialParent && it.radialGroup == entry.radialGroup }
            if (parent != null && width > 0 && height > 0) {
                val angle = Math.toRadians((entry.radialSlot * 45 - 90).toDouble())
                val radius = maxOf(parent.halfW, parent.halfH) + maxOf(entry.halfW, entry.halfH) * 1.65f
                entry.cx = (parent.cx + kotlin.math.cos(angle).toFloat() * radius).coerceIn(entry.halfW, width - entry.halfW)
                entry.cy = (parent.cy + kotlin.math.sin(angle).toFloat() * radius).coerceIn(entry.halfH, height - entry.halfH)
                entry.x = if (width > 0) entry.cx / width else entry.x
                entry.y = if (height > 0) entry.cy / height else entry.y
            }
        }
        if (entry.radialParent) {
            entries.filter { it.radialGroup == entry.radialGroup && it.radialSlot in 0..7 }
                .forEach(::measure)
        }
    }

    private fun hasIcon(entry: Entry): Boolean =
        entry.action.isEmpty() && entry.text.isEmpty() && entry.glyph.isEmpty() && entry.codes.size == 1 && ScreenKeyIcons.has(entry.codes[0])

    private fun unit() = minOf(width / 1280f, height / 720f)

    private fun accent(): Pair<Int, Float> {
        val style = if (followSteam) steamStyle() else null
        return (style?.first ?: tint) to (style?.second ?: 1f)
    }

    private fun tinted(color: Int, alpha: Float): Int {
        val scale = accent().second
        return Color.argb(
            (alpha * scale * 255f).toInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color),
        )
    }

    private fun triggerPath(entry: Entry, path: android.graphics.Path, inset: Float = 0f) {
        val lean = (entry.halfH - inset) * 0.6f
        val left = entry.cx - entry.halfW + inset
        val right = entry.cx + entry.halfW - inset
        val top = entry.cy - entry.halfH + inset
        val bottom = entry.cy + entry.halfH - inset
        path.reset()
        path.moveTo(left + lean, top)
        path.lineTo(right, top)
        path.lineTo(right - lean, bottom)
        path.lineTo(left, bottom)
        path.close()
    }

    private fun body(canvas: Canvas, entry: Entry, radius: Float, paint: Paint, inset: Float = 0f) {
        if (entry.shape != SCREEN_KEY_TRIGGER) {
            canvas.drawRoundRect(rect, radius, radius, paint)
            return
        }
        triggerPath(entry, bodyPath, inset)
        canvas.drawPath(bodyPath, paint)
    }

    private fun drawDpad(canvas: Canvas, entry: Entry) {
        val color = accent().first
        val alpha = entry.opacity
        val r = minOf(entry.halfW, entry.halfH)
        val t = r * 0.42f
        val hairline = maxOf(dp(1.2f), entry.halfH * 0.032f)
        val engaged = entry.armed >= 0
        crossPath(entry, bodyPath, r, t)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = tinted(if (entry.fillColor != 0) entry.fillColor else color, if (entry.armed >= 0) 0.26f * alpha else 0.10f * alpha)
        canvas.drawPath(bodyPath, fillPaint)
        if (entry.armed >= 0) {
            armRect(entry, bodyPathRect, entry.armed, r, t)
            fillPaint.color = tinted(if (entry.fillColor != 0) entry.fillColor else color, 0.55f * alpha)
            canvas.drawRoundRect(bodyPathRect, t * 0.6f, t * 0.6f, fillPaint)
        }
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeWidth = if (entry.borderWidth > 0f) dp(entry.borderWidth) else if (engaged) hairline * 1.6f else hairline
        strokePaint.color = if (entry.borderColor != 0) tinted(entry.borderColor, alpha) else tinted(color, (if (editing) 0.90f else 0.80f) * alpha)
        canvas.drawPath(bodyPath, strokePaint)
        textPaint.setShadowLayer(0f, 0f, 0f, 0)
        fillPaint.color = tinted(color, if (editing) 0.90f else 0.80f)
        DPAD_DIRS.forEachIndexed { index, dir ->
            val on = entry.armed == index
            val code = entry.codes.getOrNull(index) ?: 0
            val analog = GamepadTouchCodes.LEFT_ANALOG in entry.codes || GamepadTouchCodes.RIGHT_ANALOG in entry.codes
            val name = if (code == 0 || analog) "" else screenKeyLabel(context, listOf(code))
            if (entry.labels && name.isNotEmpty()) {
                textPaint.color = if (on) Color.WHITE else if (entry.textColor != 0) tinted(entry.textColor, alpha) else tinted(color, alpha)
                textPaint.textSize = dp(48f)
                val fit = 48f * (t * 1.9f) / textPaint.measureText(name).coerceAtLeast(1f)
                textPaint.textSize = minOf(fit, t * 1.7f).coerceIn(dp(8f), dp(60f))
                val tx = entry.cx + dir.first * (r - t * 0.6f)
                val ty = entry.cy + dir.second * (r - t * 0.6f)
                canvas.drawText(name, tx, ty - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            } else {
                fillPaint.color = if (on) Color.WHITE else if (entry.textColor != 0) tinted(entry.textColor, alpha) else tinted(color, if (editing) 0.90f else 0.80f)
                arrow(canvas, fillPaint, entry.cx, entry.cy, r - t * 0.55f, dir.first, dir.second, t * 0.8f)
            }
        }
        if (editing && selected === entry) {
            ringPaint.strokeWidth = hairline * 0.8f
            ringPaint.color = tinted(color, 0.9f)
            ringPaint.style = Paint.Style.STROKE
            canvas.drawCircle(entry.cx, entry.cy, r + hairline * 3f, ringPaint)
        }
    }

    private fun crossPath(entry: Entry, path: android.graphics.Path, r: Float, t: Float) {
        val cx = entry.cx
        val cy = entry.cy
        path.reset()
        path.moveTo(cx - t, cy - r)
        path.lineTo(cx + t, cy - r)
        path.lineTo(cx + t, cy - t)
        path.lineTo(cx + r, cy - t)
        path.lineTo(cx + r, cy + t)
        path.lineTo(cx + t, cy + t)
        path.lineTo(cx + t, cy + r)
        path.lineTo(cx - t, cy + r)
        path.lineTo(cx - t, cy + t)
        path.lineTo(cx - r, cy + t)
        path.lineTo(cx - r, cy - t)
        path.lineTo(cx - t, cy - t)
        path.close()
    }

    private fun armRect(entry: Entry, rect: RectF, index: Int, r: Float, t: Float) {
        when (index) {
            0 -> rect.set(entry.cx - t, entry.cy - r, entry.cx + t, entry.cy - t)
            1 -> rect.set(entry.cx + t, entry.cy - t, entry.cx + r, entry.cy + t)
            2 -> rect.set(entry.cx - t, entry.cy + t, entry.cx + t, entry.cy + r)
            else -> rect.set(entry.cx - r, entry.cy - t, entry.cx - t, entry.cy + t)
        }
    }

    private fun arrow(canvas: Canvas, paint: Paint, cx: Float, cy: Float, dist: Float, dx: Float, dy: Float, size: Float) {
        val tipX = cx + dx * dist
        val tipY = cy + dy * dist
        val baseX = cx + dx * (dist - size)
        val baseY = cy + dy * (dist - size)
        val nx = -dy * size * 0.62f
        val ny = dx * size * 0.62f
        bodyPath.reset()
        bodyPath.moveTo(tipX, tipY)
        bodyPath.lineTo(baseX + nx, baseY + ny)
        bodyPath.lineTo(baseX - nx, baseY - ny)
        bodyPath.close()
        canvas.drawPath(bodyPath, paint)
    }

    private fun armOf(entry: Entry, x: Float, y: Float): Int {
        val dx = x - entry.cx
        val dy = y - entry.cy
        return if (abs(dx) > abs(dy)) (if (dx > 0f) 1 else 3) else (if (dy > 0f) 2 else 0)
    }

    private fun drawStick(canvas: Canvas, entry: Entry) {
        val color = accent().first
        val alpha = entry.opacity
        val engaged = entry.armed >= 0
        val radius = minOf(entry.halfW, entry.halfH)
        val hairline = maxOf(dp(1.2f), entry.halfH * 0.032f)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = tinted(if (entry.fillColor != 0) entry.fillColor else color, 0.14f * alpha)
        canvas.drawCircle(entry.cx, entry.cy, radius - hairline, fillPaint)
        strokePaint.strokeWidth = if (entry.borderWidth > 0f) dp(entry.borderWidth) else if (engaged) hairline * 1.6f else hairline
        strokePaint.color = if (entry.borderColor != 0) tinted(entry.borderColor, alpha) else tinted(color, (if (editing) 0.90f else 0.80f) * alpha)
        canvas.drawCircle(entry.cx, entry.cy, radius - hairline / 2f, strokePaint)
        val dir = if (entry.stickX != 0f || entry.stickY != 0f) entry.stickX to entry.stickY
            else if (engaged) STICK_DIRS[entry.armed.coerceIn(0, 7)] else 0f to 0f
        val knob = radius * 0.36f
        fillPaint.color = tinted(if (entry.fillColor != 0) entry.fillColor else color, if (engaged) 0.55f * alpha else 0.24f * alpha)
        canvas.drawCircle(entry.cx + dir.first * radius * 0.40f, entry.cy + dir.second * radius * 0.40f, knob, fillPaint)
        strokePaint.strokeWidth = if (entry.borderWidth > 0f) dp(entry.borderWidth) else hairline
        strokePaint.color = if (entry.borderColor != 0) tinted(entry.borderColor, alpha) else tinted(color, (if (editing) 0.90f else 0.80f) * alpha)
        canvas.drawCircle(entry.cx + dir.first * radius * 0.40f, entry.cy + dir.second * radius * 0.40f, knob, strokePaint)
        textPaint.setShadowLayer(0f, 0f, 0f, 0)
        STICK_DIRS.forEachIndexed { index, unit ->
            val on = entry.armed == index
            val analog = GamepadTouchCodes.LEFT_ANALOG in entry.codes || GamepadTouchCodes.RIGHT_ANALOG in entry.codes
            val bound = if (analog) emptyList() else directionCodes(entry, index)
            val name = bound.joinToString("+") { code ->
                GamepadTouchCodes.label(code) ?: pcKeyLabel(context, code)
            }
            if (entry.labels && name.isNotEmpty()) {
                textPaint.color = if (on) Color.WHITE else if (entry.textColor != 0) tinted(entry.textColor, alpha) else tinted(color, 0.85f * alpha)
                textPaint.textSize = dp(48f)
                val fit = 48f * (radius * 0.34f) / textPaint.measureText(name).coerceAtLeast(1f)
                textPaint.textSize = minOf(fit, radius * 0.22f).coerceAtLeast(dp(6f))
                val tx = entry.cx + unit.first * radius * 0.66f
                val ty = entry.cy + unit.second * radius * 0.66f
                canvas.drawText(name, tx, ty - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            } else {
                fillPaint.color = if (on) Color.WHITE else tinted(color, if (editing) 0.88f else 0.72f)
                arrow(canvas, fillPaint, entry.cx, entry.cy, radius * 0.88f, unit.first, unit.second, radius * 0.16f)
            }
        }
        if (editing && selected === entry) {
            ringPaint.strokeWidth = hairline * 0.8f
            ringPaint.color = tinted(color, 0.9f)
            ringPaint.style = Paint.Style.STROKE
            canvas.drawCircle(entry.cx, entry.cy, radius + hairline * 3f, ringPaint)
        }
    }

    private fun directionCodes(entry: Entry, index: Int): List<Int> {
        if (index !in 0..7) return emptyList()
        if (entry.directionBindings.size == 8) return entry.directionBindings[index].distinct()
        val code = entry.codes.getOrNull(index) ?: 0
        if (code != 0) return listOf(code)
        if (entry.shape != SCREEN_KEY_STICK || index % 2 == 0) return emptyList()
        return listOfNotNull(
            entry.codes.getOrNull((index + 7) % 8)?.takeIf { it != 0 },
            entry.codes.getOrNull((index + 1) % 8)?.takeIf { it != 0 },
        ).distinct()
    }

    private fun stickSector(entry: Entry, x: Float, y: Float): Int {
        val dx = x - entry.cx
        val dy = y - entry.cy
        if (hypot(dx, dy) < minOf(entry.halfW, entry.halfH) * 0.24f) return -1
        val degrees = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
        val index = (round(degrees / 45f).toInt() + 2) % 8
        return (index + 8) % 8
    }

    private fun radiusOf(entry: Entry): Float = if (entry.cornerRadius >= 0f) dp(entry.cornerRadius) else when (entry.shape) {
        SCREEN_KEY_ROUND -> entry.halfH * 0.30f
        SCREEN_KEY_RECT -> entry.halfH * 0.12f
        SCREEN_KEY_TRIGGER -> entry.halfH * 0.45f
        else -> entry.halfH
    }

    /** Whether a finger going down at ([x], [y]) lands on something here, as [onTouchEvent] decides it. */
    fun takesTouchAt(x: Float, y: Float): Boolean = editing || entryAt(x, y) != null

    // DROIDDECK_RADIAL_COMPACT_PATCH: retain full touch bounds and resolve
    // overlapping radial children by nearest center.
    private fun entryAt(x: Float, y: Float): Entry? {
        val candidates = entries.filter { entry ->
            (editing || entry.radialSlot !in 0..7 || activeRadialGroup == entry.radialGroup) &&
                x >= entry.cx - entry.halfW && x <= entry.cx + entry.halfW &&
                y >= entry.cy - entry.halfH && y <= entry.cy + entry.halfH
        }
        val topmost = candidates.lastOrNull() ?: return null
        if (topmost.radialSlot !in 0..7) return topmost
        return candidates.asSequence()
            .filter { it.radialSlot in 0..7 && it.radialGroup == topmost.radialGroup }
            .minByOrNull { child ->
                val dx = child.cx - x
                val dy = child.cy - y
                dx * dx + dy * dy
            } ?: topmost
    }

    private fun inCircle(center: PointF, x: Float, y: Float, radius: Float) = hypot(x - center.x, y - center.y) <= radius

    private fun inToolbar(x: Float, y: Float): Boolean =
        editing && x >= toolbar.x - capsuleGap / 2f - capsuleRadius && x <= toolbar.x + capsuleGap / 2f + capsuleRadius &&
            y >= toolbar.y - capsuleRadius && y <= toolbar.y + capsuleRadius

    override fun onDraw(canvas: Canvas) {
        if (editing) drawGrid(canvas)
        entries.filter { editing || it.radialSlot !in 0..7 || activeRadialGroup == it.radialGroup }.forEach { entry ->
            if (entry.radialSlot in 0..7) {
                // Render child artwork smaller without shrinking touch rectangles.
                canvas.save()
                canvas.scale(0.82f, 0.82f, entry.cx, entry.cy)
                draw(canvas, entry)
                canvas.restore()
            } else {
                draw(canvas, entry)
            }
        }
        if (editing) {
            drawGuides(canvas)
            drawToolbar(canvas)
        }
    }

    private fun drawGuides(canvas: Canvas) {
        if (guideX < 0f && guideY < 0f) return
        gridPaint.strokeWidth = dp(1f)
        gridPaint.color = tinted(accent().first, 0.9f)
        if (guideX >= 0f) canvas.drawLine(guideX, 0f, guideX, height.toFloat(), gridPaint)
        if (guideY >= 0f) canvas.drawLine(0f, guideY, width.toFloat(), guideY, gridPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val step = width / 100f
        gridPaint.strokeWidth = dp(1f)
        gridPaint.color = Color.argb(38, 255, 255, 255)
        var x = step
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = step
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }
        gridPaint.color = Color.argb(96, 255, 255, 255)
        gridPaint.strokeWidth = dp(1f)
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), gridPaint)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, gridPaint)
    }

    private fun draw(canvas: Canvas, entry: Entry) {
        if (entry.shape == SCREEN_KEY_DPAD) {
            drawDpad(canvas, entry)
            return
        }
        if (entry.shape == SCREEN_KEY_STICK) {
            drawStick(canvas, entry)
            return
        }
        val style = if (followSteam && entry.fillColor == 0 && entry.borderColor == 0 && entry.textColor == 0) steamStyle() else null
        val engaged = down.values.any { it === entry } || mouseDown.values.any { it === entry } || latched.contains(entry)
        val bitmap = bitmapFor(entry)
        val icon = bitmap == null && hasIcon(entry)
        val frameless = entry.text.isBlank() && (bitmap != null || icon)
        val alpha = entry.opacity
        val h2 = entry.halfH * 2f
        val radius = radiusOf(entry)
        val selectedNow = editing && selected === entry
        val hairline = maxOf(dp(1.2f), h2 * 0.032f)
        rect.set(entry.cx - entry.halfW, entry.cy - entry.halfH, entry.cx + entry.halfW, entry.cy + entry.halfH)
        val inkColor: Int
        if (style != null) {
            val inkAlpha = (255f * style.second * alpha).toInt().coerceIn(30, 255)
            inkColor = Color.argb(inkAlpha, Color.red(style.first), Color.green(style.first), Color.blue(style.first))
            fillPaint.style = Paint.Style.FILL
            if (engaged) {
                fillPaint.color = Color.argb((inkAlpha * 0.85f).toInt(), 230, 233, 236)
                body(canvas, entry, radius, fillPaint)
            }
            if (frameless) {
                if (engaged) {
                    fillPaint.color = Color.argb((inkAlpha * 0.45f).toInt(), 230, 233, 236)
                    body(canvas, entry, radius, fillPaint)
                }
            } else {
                strokePaint.color = if (selectedNow) Color.rgb(26, 159, 255) else inkColor
                strokePaint.strokeWidth = if (engaged) hairline * 1.6f else hairline
                body(canvas, entry, radius, strokePaint)
            }
        } else {
            fillPaint.style = Paint.Style.FILL
            if (!frameless) {
                fillPaint.color = tinted(if (entry.fillColor != 0) entry.fillColor else BODY, 0.60f * alpha)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }
            if (engaged) {
                fillPaint.color = tinted(accent().first, if (frameless) 0.32f * alpha else 0.42f * alpha)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }
            if (!frameless) {
                strokePaint.strokeWidth = if (entry.borderWidth > 0f) dp(entry.borderWidth) else if (engaged) hairline * 1.6f else hairline
                strokePaint.color = if (entry.borderColor != 0) tinted(entry.borderColor, alpha) else tinted(
                    accent().first,
                    (if (engaged) 0.96f else if (editing) 0.80f else 0.68f) * alpha,
                )
                body(canvas, entry, radius, strokePaint)
            }
            inkColor = if (entry.textColor != 0) tinted(entry.textColor, alpha) else if (engaged) Color.WHITE else tinted(accent().first, alpha)
        }
        if (editing && selected === entry) {
            ringPaint.strokeWidth = hairline * 0.8f
            ringPaint.color = tinted(accent().first, 0.9f)
            val gap = hairline * 2.4f
            rect.set(
                entry.cx - entry.halfW - gap, entry.cy - entry.halfH - gap,
                entry.cx + entry.halfW + gap, entry.cy + entry.halfH + gap,
            )
            if (entry.shape == SCREEN_KEY_TRIGGER) {
                body(canvas, entry, radius + gap, ringPaint, gap)
            } else {
                canvas.drawRoundRect(rect, radius + gap, radius + gap, ringPaint)
            }
            rect.set(entry.cx - entry.halfW, entry.cy - entry.halfH, entry.cx + entry.halfW, entry.cy + entry.halfH)
        }

        if (bitmap != null) {
            imageRect.set(rect)
            canvas.save()
            clipPath.reset()
            when (entry.shape) {
                SCREEN_KEY_CIRCLE -> clipPath.addCircle(
                    entry.cx, entry.cy, minOf(entry.halfW, entry.halfH), android.graphics.Path.Direction.CW,
                )
                SCREEN_KEY_TRIGGER -> triggerPath(entry, clipPath, 0f)
                else -> clipPath.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            val scale = maxOf(imageRect.width() / bitmap.width, imageRect.height() / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            bitmapRect.set(
                imageRect.centerX() - w / 2f, imageRect.centerY() - h / 2f,
                imageRect.centerX() + w / 2f, imageRect.centerY() + h / 2f,
            )
            canvas.drawBitmap(bitmap, null, bitmapRect, fillPaint)
            canvas.restore()
        }

        val name = label(entry)
        textPaint.color = inkColor
        if (bitmap != null && name.isEmpty()) return
        var iconDrawn = false
        if (icon) {
            val box = minOf(entry.halfW, entry.halfH) * 1.35f
            iconDrawn = ScreenKeyIcons.draw(canvas, entry.codes[0], entry.cx, entry.cy, box, textPaint)
            textPaint.color = inkColor
        }
        if (iconDrawn || name.isEmpty()) return
        val overImage = bitmap != null
        if (overImage) textPaint.color = Color.WHITE
        textPaint.setShadowLayer(if (overImage) dp(3f) else 0f, 0f, 0f, Color.argb(200, 0, 0, 0))
        textPaint.textSize = dp(48f)
        val available = rect.width() - hairline * 4f
        val fit = 48f * available / textPaint.measureText(name).coerceAtLeast(1f)
        textPaint.textSize = minOf(fit, entry.halfH * 1.15f).coerceIn(dp(8f), dp(120f))
        canvas.drawText(name, entry.cx, entry.cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        textPaint.clearShadowLayer()
    }

    private fun drawToolbar(canvas: Canvas) {
        val (color, _) = accent()
        val capsule = RectF(
            toolbar.x - capsuleGap / 2f - capsuleRadius, toolbar.y - capsuleRadius,
            toolbar.x + capsuleGap / 2f + capsuleRadius, toolbar.y + capsuleRadius,
        )
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = tinted(color, 0.30f)
        canvas.drawRoundRect(capsule, capsuleRadius, capsuleRadius, fillPaint)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = tinted(color, 0.85f)
        canvas.drawRoundRect(capsule, capsuleRadius, capsuleRadius, strokePaint)

        textPaint.color = Color.WHITE
        textPaint.setShadowLayer(0f, 0f, 0f, 0)
        textPaint.textSize = capsuleRadius * 1.15f
        val plus = "+"
        canvas.drawText(plus, addCenter.x, addCenter.y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        val path = android.graphics.Path()
        val check = capsuleRadius * 0.42f
        path.moveTo(doneCenter.x - check, doneCenter.y)
        path.lineTo(doneCenter.x - check * 0.25f, doneCenter.y + check * 0.7f)
        path.lineTo(doneCenter.x + check, doneCenter.y - check * 0.7f)
        ringPaint.strokeWidth = dp(3f)
        ringPaint.strokeCap = Paint.Cap.ROUND
        ringPaint.color = Color.WHITE
        canvas.drawPath(path, ringPaint)
        ringPaint.strokeCap = Paint.Cap.BUTT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = if (editing) editTouch(event) else playTouch(event)

    private fun playTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val entry = entryAt(event.getX(index), event.getY(index))
                if (entry == null) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (activeRadialGroup != null) {
                            activeRadialGroup = null
                            invalidate()
                        }
                        return false
                    }
                    forwardedPointerBits = forwardedPointerBits or (1 shl event.getPointerId(index))
                    forwardPointers(event, forwardedPointerBits)
                    return true
                }
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                if (entry.radialParent) {
                    activeRadialGroup = if (activeRadialGroup == entry.radialGroup) null else entry.radialGroup
                    invalidate()
                    return true
                }
                // Selecting any child or another control dismisses the currently open wheel.
                if (activeRadialGroup != null) {
                    activeRadialGroup = null
                    invalidate()
                }
                if (entry.action == ScreenKeyActions.MOUSE_REGION_ABSOLUTE || entry.action == ScreenKeyActions.MOUSE_REGION_RELATIVE) {
                    val pid = event.getPointerId(index)
                    val px = event.getX(index); val py = event.getY(index)
                    mouseRegions[pid] = Triple(entry, px, py)
                    if (entry.action == ScreenKeyActions.MOUSE_REGION_ABSOLUTE) {
                        onMouseMove?.invoke(true, ((px - (entry.cx - entry.halfW)) / (entry.halfW * 2f)).coerceIn(0f, 1f), ((py - (entry.cy - entry.halfH)) / (entry.halfH * 2f)).coerceIn(0f, 1f))
                    }
                    return true
                }
                if (entry.shape == SCREEN_KEY_DPAD) {
                    val arm = armOf(entry, event.getX(index), event.getY(index))
                    val code = entry.codes.getOrNull(arm) ?: 0
                    if (code == 0) return true
                    val pid = event.getPointerId(index)
                    dpadDown.remove(pid)?.let { releaseKey(it) }
                    dpadDown[pid] = code
                    entry.armed = arm
                    pressKey(code)
                    invalidate()
                    return true
                }
                if (entry.shape == SCREEN_KEY_STICK) {
                    val analogLeft = when {
                        GamepadTouchCodes.LEFT_ANALOG in entry.codes -> true
                        GamepadTouchCodes.RIGHT_ANALOG in entry.codes -> false
                        else -> null
                    }
                    if (analogLeft != null) {
                        val pid = event.getPointerId(index)
                        analogSticks[pid] = entry to analogLeft
                        updateAnalogStick(entry, analogLeft, event.getX(index), event.getY(index))
                        return true
                    }
                    val sector = stickSector(entry, event.getX(index), event.getY(index))
                    val pid = event.getPointerId(index)
                    stickCodes.remove(pid)?.forEach { if (it != 0) releaseKey(it) }
                    val codes = directionCodes(entry, sector)
                    stickEntries[pid] = entry
                    stickCodes[pid] = codes
                    entry.armed = if (codes.isEmpty()) -1 else sector
                    codes.forEach { pressKey(it) }
                    invalidate()
                    return true
                }
                if (entry.action == ScreenKeyActions.SCROLL_UP || entry.action == ScreenKeyActions.SCROLL_DOWN) {
                    pressEntry(entry)
                    return true
                }
                if (entry.action == ScreenKeyActions.SCROLL_UP_CONTINUOUS || entry.action == ScreenKeyActions.SCROLL_DOWN_CONTINUOUS) {
                    val pid = event.getPointerId(index)
                    if (down.values.any { it === entry }) return true
                    down[pid] = entry
                    pressEntry(entry)
                    invalidate()
                    return true
                }
                if (entry.action == ScreenKeyActions.MOUSE_LEFT || entry.action == ScreenKeyActions.MOUSE_RIGHT || entry.action == ScreenKeyActions.MOUSE_MIDDLE) {
                    val pid = event.getPointerId(index)
                    mouseDown[pid] = entry
                    pressEntry(entry)
                    invalidate()
                    return true
                }
                if (entry.toggle) {
                    if (latched.remove(entry)) release(entry) else {
                        latched += entry
                        pressEntry(entry)
                    }
                    invalidate()
                    return true
                }
                if (down.values.any { it === entry }) return true
                down[event.getPointerId(index)] = entry
                pressEntry(entry)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                repeat(event.pointerCount) { index ->
                    val pid = event.getPointerId(index)
                    val region = mouseRegions[pid]
                    if (region != null) {
                        val (entry, prevX, prevY) = region
                        val x = event.getX(index); val y = event.getY(index)
                        if (entry.action == ScreenKeyActions.MOUSE_REGION_ABSOLUTE) {
                            onMouseMove?.invoke(true, ((x - (entry.cx - entry.halfW)) / (entry.halfW * 2f)).coerceIn(0f, 1f), ((y - (entry.cy - entry.halfH)) / (entry.halfH * 2f)).coerceIn(0f, 1f))
                        } else {
                            onMouseMove?.invoke(false, x - prevX, y - prevY)
                        }
                        mouseRegions[pid] = Triple(entry, x, y)
                    }
                    val analog = analogSticks[pid]
                    if (analog != null) updateAnalogStick(analog.first, analog.second, event.getX(index), event.getY(index))
                }
                if (stickEntries.isEmpty()) return true
                repeat(event.pointerCount) { index ->
                    val pid = event.getPointerId(index)
                    val entry = stickEntries[pid] ?: return@repeat
                    val sector = stickSector(entry, event.getX(index), event.getY(index))
                    val codes = directionCodes(entry, sector)
                    val previous = stickCodes[pid].orEmpty()
                    val nextArmed = if (codes.isEmpty()) -1 else sector
                    if (codes != previous || entry.armed != nextArmed) {
                        previous.filterNot { it in codes }.forEach { releaseKey(it) }
                        codes.filterNot { it in previous }.forEach { pressKey(it) }
                        stickCodes[pid] = codes
                        entry.armed = nextArmed
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(event.actionIndex)
                mouseRegions.remove(pid)
                mouseDown.remove(pid)?.let { release(it); return true }
                if (forwardedPointerBits and (1 shl pid) != 0) {
                    forwardPointers(event, forwardedPointerBits)
                    forwardedPointerBits = forwardedPointerBits and (1 shl pid).inv()
                    if (stickCodes.containsKey(pid) || dpadDown.containsKey(pid)) return true
                    down.remove(pid)?.let(::release)
                    return true
                }
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                analogSticks.remove(pid)?.let { (entry, left) ->
                    entry.stickX = 0f; entry.stickY = 0f
                    onAnalogStick?.invoke(left, 0f, 0f)
                    invalidate()
                    return true
                }
                if (stickCodes.containsKey(pid)) {
                    stickCodes.remove(pid)?.forEach { if (it != 0) releaseKey(it) }
                    stickEntries.remove(pid)?.armed = -1
                    invalidate()
                    return true
                }
                val armedCode = dpadDown.remove(pid)
                if (armedCode != null) {
                    releaseKey(armedCode)
                    entries.firstOrNull { it.armed >= 0 && it.codes.contains(armedCode) }?.armed = -1
                    invalidate()
                    return true
                }
                val entry = down.remove(pid)
                if (entry != null) release(entry)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                forwardedPointerBits = 0
                mouseDown.values.forEach(::release)
                mouseDown.clear(); mouseRegions.clear()
                stickCodes.values.flatten().forEach { if (it != 0) releaseKey(it) }
                stickCodes.clear()
                stickEntries.clear()
                analogSticks.values.forEach { (entry, left) -> entry.stickX = 0f; entry.stickY = 0f; onAnalogStick?.invoke(left, 0f, 0f) }
                analogSticks.clear()
                entries.forEach { it.armed = -1 }
                dpadDown.values.forEach { releaseKey(it) }
                dpadDown.clear()
                entries.forEach { it.armed = -1 }
                down.values.forEach { release(it) }
                down.clear()
                return true
            }
        }
        return true
    }

    private fun updateAnalogStick(entry: Entry, left: Boolean, x: Float, y: Float) {
        val radius = minOf(entry.halfW, entry.halfH).coerceAtLeast(1f) * 0.68f
        var dx = ((x - entry.cx) / radius).coerceIn(-1f, 1f)
        var dy = ((y - entry.cy) / radius).coerceIn(-1f, 1f)
        val magnitude = hypot(dx, dy)
        if (magnitude > 1f) { dx /= magnitude; dy /= magnitude }
        entry.stickX = dx
        entry.stickY = dy
        onAnalogStick?.invoke(left, dx, dy)
        invalidate()
    }

    private fun forwardPointers(event: MotionEvent, pointerIdBits: Int) {
        val split = event.pointerSubset(pointerIdBits) ?: return
        try {
            onTouchMiss?.invoke(split)
        } finally {
            split.recycle()
        }
    }

    private fun editTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                if (inCircle(addCenter, x, y, capsuleRadius)) {
                    onAdd?.invoke()
                    return true
                }
                if (inCircle(doneCenter, x, y, capsuleRadius)) {
                    stopEditing()
                    return true
                }
                if (inToolbar(x, y)) {
                    toolbarDrag = true
                    dragX = toolbar.x - x
                    dragY = toolbar.y - y
                    return true
                }
                val entry = entryAt(x, y)
                if (entry == null) {
                    pressX = x
                    pressY = y
                    postDelayed(addPress, 520)
                    selected = null
                    invalidate()
                    return true
                }
                selected = entry
                dragging = true
                moved = false
                dragX = entry.cx - x
                dragY = entry.cy - y
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (toolbarDrag) {
                    val maxX = width - safe.right - capsuleGap / 2f - capsuleRadius
                    val maxY = height - safe.bottom - capsuleRadius
                    toolbar.x = (event.x + dragX).coerceIn(safe.left + capsuleGap / 2f + capsuleRadius, maxOf(safe.left + capsuleGap / 2f + capsuleRadius, maxX))
                    toolbar.y = (event.y + dragY).coerceIn(safe.top + capsuleRadius, maxOf(safe.top + capsuleRadius, maxY))
                    addCenter.set(toolbar.x - capsuleGap / 2f, toolbar.y)
                    doneCenter.set(toolbar.x + capsuleGap / 2f, toolbar.y)
                    invalidate()
                    return true
                }
                val entry = selected
                if (entry == null) {
                    if (hypot(event.x - pressX, event.y - pressY) > dp(10f)) removeCallbacks(addPress)
                    return true
                }
                if (!dragging) return true
                if (hypot(event.x - (entry.cx - dragX), event.y - (entry.cy - dragY)) > dp(10f)) removeCallbacks(addPress)
                var nextX = snap((event.x + dragX).coerceIn(entry.halfW + safe.left, width - entry.halfW - safe.right))
                var nextY = snap((event.y + dragY).coerceIn(entry.halfH + safe.top, height - entry.halfH - safe.bottom))
                guideX = -1f
                guideY = -1f
                entries.forEach { other ->
                    if (other === entry) return@forEach
                    if (abs(nextX - other.cx) < dp(8f)) { nextX = other.cx; guideX = other.cx }
                    if (abs(nextY - other.cy) < dp(8f)) { nextY = other.cy; guideY = other.cy }
                }
                if (abs(nextX - width / 2f) < dp(8f)) { nextX = width / 2f; guideX = nextX }
                if (abs(nextY - height / 2f) < dp(8f)) { nextY = height / 2f; guideY = nextY }
                if (hypot(nextX - entry.cx, nextY - entry.cy) > dp(4f)) moved = true
                entry.cx = nextX
                entry.cy = nextY
                entry.x = entry.cx / width
                entry.y = entry.cy / height
                if (entry.radialParent) entries.filter { it.radialGroup == entry.radialGroup && it.radialSlot in 0..7 }.forEach(::measure)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(addPress)
                guideX = -1f
                guideY = -1f
                if (toolbarDrag) {
                    toolbarDrag = false
                    return true
                }
                val entry = selected
                if (entry != null && dragging) {
                    dragging = false
                    if (moved) {
                        save()
                        invalidate()
                    } else {
                        onSelect?.invoke(entry.codes)
                    }
                }
                return true
            }
        }
        return true
    }

    private fun pressEntry(entry: Entry) {
        if (entry.radialSlot in 0..7 && entry.action.isEmpty() && entry.codes.all { it <= -41000 }) return
        when (entry.action) {
            ScreenKeyActions.MOUSE_LEFT, ScreenKeyActions.MOUSE_RIGHT, ScreenKeyActions.MOUSE_MIDDLE -> {
                val button = mouseButtonCode(entry.action)
                val owners = mouseButtonOwners[button] ?: 0
                mouseButtonOwners[button] = owners + 1
                if (owners == 0) onMouseButton?.invoke(button, true)
            }
            ScreenKeyActions.SCROLL_UP -> onMouseWheel?.invoke(1)
            ScreenKeyActions.SCROLL_DOWN -> onMouseWheel?.invoke(-1)
            ScreenKeyActions.SCROLL_UP_CONTINUOUS -> startContinuousScroll(entry, 1)
            ScreenKeyActions.SCROLL_DOWN_CONTINUOUS -> startContinuousScroll(entry, -1)
            else -> entry.codes.forEach(::pressKey)
        }
    }

    private fun releaseEntry(entry: Entry) {
        if (entry.radialSlot in 0..7 && entry.action.isEmpty() && entry.codes.all { it <= -41000 }) return
        when (entry.action) {
            ScreenKeyActions.MOUSE_LEFT, ScreenKeyActions.MOUSE_RIGHT, ScreenKeyActions.MOUSE_MIDDLE -> {
                val button = mouseButtonCode(entry.action)
                val owners = mouseButtonOwners[button] ?: return
                if (owners <= 1) { mouseButtonOwners.remove(button); onMouseButton?.invoke(button, false) }
                else mouseButtonOwners[button] = owners - 1
            }
            ScreenKeyActions.SCROLL_UP, ScreenKeyActions.SCROLL_DOWN -> Unit
            ScreenKeyActions.SCROLL_UP_CONTINUOUS, ScreenKeyActions.SCROLL_DOWN_CONTINUOUS ->
                stopContinuousScroll(entry)
            else -> entry.codes.asReversed().forEach(::releaseKey)
        }
    }

    private fun startContinuousScroll(entry: Entry, direction: Int) {
        if (scrollRepeaters.containsKey(entry)) return
        onMouseWheel?.invoke(direction)
        val repeat = object : Runnable {
            override fun run() {
                if (scrollRepeaters[entry] !== this) return
                onMouseWheel?.invoke(direction)
                postDelayed(this, 110L)
            }
        }
        scrollRepeaters[entry] = repeat
        postDelayed(repeat, 260L)
    }

    private fun stopContinuousScroll(entry: Entry) {
        scrollRepeaters.remove(entry)?.let { removeCallbacks(it) }
    }

    private fun mouseButtonCode(action: String): Int = when (action) {
        ScreenKeyActions.MOUSE_RIGHT -> 0x111
        ScreenKeyActions.MOUSE_MIDDLE -> 0x112
        else -> 0x110
    }

    private fun pressKey(code: Int) {
        val owners = held[code] ?: 0
        held[code] = owners + 1
        if (owners == 0) sendKey(code, true)
    }

    private fun releaseKey(code: Int) {
        val owners = held[code] ?: return
        if (owners > 1) {
            held[code] = owners - 1
            return
        }
        held.remove(code)
        sendKey(code, false)
    }

    private fun release(entry: Entry) {
        releaseEntry(entry)
        invalidate()
    }

    fun export(): String = ControllerPrefs.encodeScreenKeys(
        entries.map { ScreenKey(it.codes, it.x, it.y, it.size, it.glyph, it.text, it.image, it.opacity, it.shape, it.toggle, it.labels, it.action, it.directionBindings,
            it.width, it.height, it.fillColor, it.borderColor, it.textColor, it.borderWidth, it.cornerRadius, it.radialGroup, it.radialParent, it.radialSlot) },
    )

    fun load(raw: String) {
        releaseAll()
        entries.clear()
        ControllerPrefs.decodeScreenKeys(raw).forEach { entries += entryOf(it) }
        entries.forEach { measure(it) }
        selected = null
        save()
        invalidate()
    }

    private fun save() {
        ControllerPrefs.setScreenKeys(
            context,
            entries.map { ScreenKey(it.codes, it.x, it.y, it.size, it.glyph, it.text, it.image, it.opacity, it.shape, it.toggle, it.labels, it.action, it.directionBindings,
                it.width, it.height, it.fillColor, it.borderColor, it.textColor, it.borderWidth, it.cornerRadius, it.radialGroup, it.radialParent, it.radialSlot) },
            profile,
        )
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object {
        private val DPAD_DIRS = listOf(
            0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f,
        )
        private val STICK_DIRS = listOf(
            0f to -1f, 0.7071f to -0.7071f, 1f to 0f, 0.7071f to 0.7071f,
            0f to 1f, -0.7071f to 0.7071f, -1f to 0f, -0.7071f to -0.7071f,
        )
        const val DEFAULT_SIZE = 44
        const val MIN_SIZE = 24
        const val MAX_SIZE = 140
        const val IMAGE_DIR = "screenkeys"
        private const val BODY = 0xFF14181F.toInt()
    }
}
