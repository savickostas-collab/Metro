package com.metro.start

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/** A Metro tile: flat colour, white glyph, lowercase label, live faces that flip. */
@SuppressLint("ViewConstructor")
class TileView(context: Context, val tile: Tile, app0: AppInfo) : View(context) {

    interface Host {
        fun tileTap(v: TileView)
        fun tileLongPress(v: TileView)
        fun tileSelect(v: TileView)
        fun tileUnpin(v: TileView)
        fun tileResize(v: TileView)
        fun tileCustomize(v: TileView)
        fun dragStart(v: TileView, rawX: Float, rawY: Float)
        fun dragMove(v: TileView, rawX: Float, rawY: Float)
        fun dragEnd(v: TileView)
    }

    var host: Host? = null

    var app: AppInfo = app0
        set(v) {
            field = v
            iconCopy = v.icon?.constantState?.newDrawable()?.mutate()
            invalidate()
        }
    private var iconCopy: Drawable? = app0.icon?.constantState?.newDrawable()?.mutate()
    val kind: Kind get() = app.kind

    // grid bookkeeping
    var cellX = 0
    var cellY = 0
    var nextFlip = 0L
    val phase = Random.nextFloat() * 6.28f

    var editMode = false
        private set
    var picked = false
        private set

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val src = Rect()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        cameraDistance = 9000f * d
    }

    // ---------------------------------------------------------------- colour

    private var shown = Look.tileColor(tile)
    private var colorAnim: ValueAnimator? = null

    fun refreshColor(animate: Boolean) {
        val target = Look.tileColor(tile)
        colorAnim?.cancel()
        if (target == shown) return
        if (!animate || !isLaidOut) {
            shown = target
            invalidate()
            return
        }
        colorAnim = ValueAnimator.ofArgb(shown, target).apply {
            duration = 350
            interpolator = Metro.EASE
            addUpdateListener {
                shown = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    // ---------------------------------------------------------------- live state

    private var badge = 0
    private var note: Pair<String, String>? = null
    private var back = false
    private var photo = 0
    private var people = IntArray(0)
    private var cellFlip = -1
    private var cellProgress = 0f

    private val small get() = tile.size == Tile.SMALL

    fun refreshLive() {
        badge = if (tile.live) Notifs.counts[app.pkg] ?: 0 else 0
        note = if (tile.live) Notifs.latest[app.pkg] else null
        if (back && !hasBack()) back = false
        invalidate()
    }

    private fun hasBack(): Boolean = when (kind) {
        Kind.CALENDAR -> Live.event != null || note != null
        Kind.WEATHER -> Live.weather != null
        else -> note != null
    }

    private fun showPhotos() = kind == Kind.PHOTOS && tile.live && !small && Live.photos.isNotEmpty()
    private fun showPeople() = kind == Kind.PEOPLE && tile.live && !small && Live.people.size >= 4
    private fun peopleCols() = if (tile.size == Tile.WIDE) 6 else 3

    fun canFlip(): Boolean {
        if (!tile.live || small || editMode) return false
        return when {
            showPhotos() -> Live.photos.size > 1
            showPeople() -> true
            else -> hasBack()
        }
    }

    /** Vertical 3D flip to the other face. */
    fun flip() {
        if (showPeople()) {
            flipCell()
            return
        }
        pivotX = width / 2f
        pivotY = height / 2f
        ani(220).setInterpolator(AccelerateInterpolator()).rotationX(90f).withEndAction {
            if (showPhotos()) photo++ else back = !back
            rotationX = -90f
            invalidate()
            ani(380).rotationX(0f).start()
        }.start()
    }

    /** People tile: flip one photo in the mosaic at a time. */
    private fun flipCell() {
        val n = peopleCols() * 3
        ensurePeople(n)
        val cell = Random.nextInt(n)
        val total = Live.people.size
        var next = Random.nextInt(total)
        for (k in 0 until 8) {
            if (next !in people) break
            next = Random.nextInt(total)
        }
        cellFlip = cell
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = Metro.EASE
            var swapped = false
            addUpdateListener {
                cellProgress = it.animatedValue as Float
                if (!swapped && cellProgress >= 0.5f) {
                    swapped = true
                    if (cell < people.size) people[cell] = next
                }
                invalidate()
            }
            start()
        }
    }

    private fun ensurePeople(n: Int) {
        if (people.size == n) return
        val total = Live.people.size.coerceAtLeast(1)
        val order = (0 until total).shuffled()
        people = IntArray(n) { order[it % total] }
    }

    // ---------------------------------------------------------------- edit mode

    fun setEdit(on: Boolean, isPicked: Boolean) {
        editMode = on
        picked = isPicked
        pivotX = width / 2f
        pivotY = height / 2f
        val s = if (!on || isPicked) 1f else 0.9f
        val a = if (!on || isPicked) 1f else 0.8f
        ani(260).scaleX(s).scaleY(s).alpha(a).rotationX(0f).rotationY(0f).start()
        if (!on || isPicked) rotation = 0f
        if (on) back = false
        invalidate()
    }

    // ---------------------------------------------------------------- drawing

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        fill.color = shown
        c.drawRect(0f, 0f, w, h, fill)
        val faceBack = back && tile.live && !small && !editMode
        when {
            showPhotos() -> drawPhoto(c, w, h)
            showPeople() -> drawPeople(c, w, h)
            faceBack && kind == Kind.CALENDAR && Live.event != null -> drawEvent(c, w, h)
            faceBack && kind == Kind.WEATHER && Live.weather != null -> drawWeatherBack(c, w, h)
            faceBack && note != null -> drawNote(c, w, h)
            kind == Kind.WEATHER && tile.live && !small && Live.weather != null -> drawWeatherFront(c, w, h)
            else -> drawFront(c, w, h)
        }
        if (editMode && picked) drawButtons(c)
    }

    private fun drawGlyph(c: Canvas, cx: Float, cy: Float, size: Float) {
        val ic = iconCopy
        if (!Look.glyphs && ic != null) {
            val s = size * 1.25f
            ic.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            ic.draw(c)
            return
        }
        // Glyph bitmaps carry padding (the 72dp safe zone), so draw them a bit larger.
        val s = size * 1.5f
        rect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        c.drawBitmap(app.glyph, null, rect, bmpPaint)
    }

    private fun countText() = if (badge > 99) "99+" else badge.toString()

    private fun drawFront(c: Canvas, w: Float, h: Float) {
        val g = if (small) w * 0.42f else h * 0.30f
        val cy = if (small) h / 2f else h * 0.44f
        if (badge <= 0) {
            drawGlyph(c, w / 2f, cy, g)
        } else {
            // Windows Phone puts the count right next to the icon.
            val count = countText()
            tp.typeface = Metro.light
            tp.color = Color.WHITE
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = if (small) g * 0.85f else g * 1.1f
            val cw = tp.measureText(count)
            val gap = if (small) 3 * d else 8 * d
            val gs = if (small) min(g, w - cw - gap - 8 * d) else g
            val x0 = (w - (gs + gap + cw)) / 2f
            drawGlyph(c, x0 + gs / 2f, cy, gs)
            val fm = tp.fontMetrics
            c.drawText(count, x0 + gs + gap, cy - (fm.ascent + fm.descent) / 2f, tp)
        }
        if (!small) drawLabel(c, w, h, null)
    }

    /** strip == null: plain label (with a darker band on light tiles); otherwise a coloured strip. */
    private fun drawLabel(c: Canvas, w: Float, h: Float, strip: Int?, right: String? = null) {
        tp.typeface = Metro.regular
        tp.textSize = sp(13f)
        tp.color = Color.WHITE
        tp.textAlign = Paint.Align.LEFT
        val pad = 8 * d
        val reserve = if (right != null) tp.measureText(right) + pad else 0f
        val text = TextUtils.ellipsize(app.name, tp, w - 2 * pad - reserve, TextUtils.TruncateAt.END).toString()
        val fm = tp.fontMetrics
        val bottomPad = 7 * d
        val baseline = h - bottomPad - fm.descent
        val band = (fm.descent - fm.ascent) + bottomPad * 2
        if (strip != null) {
            fill.color = strip
            c.drawRect(0f, h - band, min(w, tp.measureText(text) + 2 * pad + reserve), h, fill)
        } else if (Color.luminance(shown) > 0.35f) {
            fill.color = 0x30000000
            c.drawRect(0f, h - band, w, h, fill)
        }
        c.drawText(text, pad, baseline, tp)
        if (right != null) {
            tp.textAlign = Paint.Align.RIGHT
            c.drawText(right, w - pad, baseline, tp)
            tp.textAlign = Paint.Align.LEFT
        }
    }

    private fun cropInto(c: Canvas, b: Bitmap, l: Float, t: Float, r: Float, btm: Float) {
        val dw = r - l
        val dh = btm - t
        if (dw <= 0 || dh <= 0) return
        val sr = dw / dh
        val br = b.width.toFloat() / b.height
        if (br > sr) {
            val sw = (b.height * sr).toInt()
            val x = (b.width - sw) / 2
            src.set(x, 0, x + sw, b.height)
        } else {
            val sh = (b.width / sr).toInt()
            val y = (b.height - sh) / 2
            src.set(0, y, b.width, y + sh)
        }
        rect.set(l, t, r, btm)
        c.drawBitmap(b, src, rect, bmpPaint)
    }

    private fun drawPhoto(c: Canvas, w: Float, h: Float) {
        val list = Live.photos
        val b = list[Math.floorMod(photo, list.size)]
        cropInto(c, b, 0f, 0f, w, h)
        drawLabel(c, w, h, shown)
    }

    private fun drawPeople(c: Canvas, w: Float, h: Float) {
        val list = Live.people
        val cols = peopleCols()
        val n = cols * 3
        ensurePeople(n)
        val cw = w / cols
        val ch = h / 3
        for (i in 0 until n) {
            val b = list[people[i] % list.size]
            val x = (i % cols) * cw
            val y = (i / cols) * ch
            var sy = 1f
            if (i == cellFlip) sy = abs(cos(cellProgress * PI)).toFloat()
            val inset = ch * (1 - sy) / 2
            cropInto(c, b, x, y + inset, x + cw, y + ch - inset)
        }
        drawLabel(c, w, h, shown)
    }

    private fun multiline(c: Canvas, text: String, x: Float, y: Float, width: Float, maxLines: Int): Float {
        if (maxLines <= 0 || text.isEmpty()) return y
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, tp, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        c.save()
        c.translate(x, y)
        sl.draw(c)
        c.restore()
        return y + sl.height
    }

    private fun lines(avail: Float): Int {
        val fm = tp.fontMetrics
        return (avail / (fm.descent - fm.ascent)).toInt()
    }

    private fun drawNote(c: Canvas, w: Float, h: Float) {
        val n = note ?: return drawFront(c, w, h)
        val pad = 10 * d
        val title = n.first.ifBlank { n.second }
        val body = if (n.first.isNotBlank()) n.second else ""
        val labelBand = sp(13f) + 18 * d
        tp.color = Color.WHITE
        tp.textAlign = Paint.Align.LEFT
        tp.typeface = Metro.semibold
        tp.textSize = sp(15f)
        var y = multiline(c, title, pad, pad, w - 2 * pad, if (tile.size == Tile.WIDE) 1 else 2)
        tp.typeface = Metro.regular
        tp.textSize = sp(13f)
        multiline(c, body, pad, y + 2 * d, w - 2 * pad, lines(h - y - labelBand))
        drawLabel(c, w, h, null, if (badge > 0) countText() else null)
    }

    private fun drawEvent(c: Canvas, w: Float, h: Float) {
        val e = Live.event ?: return
        val pad = 10 * d
        tp.color = Color.WHITE
        tp.textAlign = Paint.Align.LEFT
        tp.typeface = Metro.semibold
        tp.textSize = sp(16f)
        var y = multiline(c, e.title, pad, pad, w - 2 * pad, 2)
        tp.typeface = Metro.regular
        tp.textSize = sp(13f)
        y = multiline(c, e.time, pad, y + 2 * d, w - 2 * pad, 1)
        if (e.place.isNotBlank()) {
            tp.color = alpha(Color.WHITE, 0.6f)
            multiline(c, e.place, pad, y, w - 2 * pad, 1)
        }
        drawLabel(c, w, h, null)
    }

    private fun drawWeatherFront(c: Canvas, w: Float, h: Float) {
        val wx = Live.weather ?: return
        val pad = 10 * d
        tp.color = Color.WHITE
        tp.textAlign = Paint.Align.LEFT
        tp.typeface = Metro.light
        tp.textSize = h * 0.38f
        val fm = tp.fontMetrics
        c.drawText("${wx.temp}°", pad, pad - fm.ascent * 0.85f, tp)
        tp.typeface = Metro.regular
        tp.textSize = sp(13f)
        multiline(c, wx.desc, pad, pad + (fm.descent - fm.ascent) * 0.85f, w - 2 * pad, 1)
        drawLabel(c, w, h, null)
    }

    private fun drawWeatherBack(c: Canvas, w: Float, h: Float) {
        val wx = Live.weather ?: return
        val pad = 10 * d
        tp.color = Color.WHITE
        tp.textAlign = Paint.Align.LEFT
        tp.typeface = Metro.semibold
        tp.textSize = sp(16f)
        var y = multiline(c, wx.place.ifBlank { "today" }, pad, pad, w - 2 * pad, 1)
        tp.typeface = Metro.regular
        tp.textSize = sp(13f)
        y = multiline(c, wx.desc, pad, y + 2 * d, w - 2 * pad, 1)
        tp.color = alpha(Color.WHITE, 0.6f)
        multiline(c, "${wx.hi}° / ${wx.lo}°", pad, y, w - 2 * pad, 1)
        drawLabel(c, w, h, null)
    }

    // edit buttons: 1 = unpin (top right), 2 = resize (bottom right), 3 = colour (top left)
    private val br get() = 13 * d
    private val inset get() = 17 * d
    private fun bx(i: Int) = if (i == 3) inset else width - inset
    private fun by(i: Int) = if (i == 2) height - inset else inset

    private fun drawButtons(c: Canvas) {
        val r = br
        val k = r * 0.42f
        for (i in 1..3) {
            fill.style = Paint.Style.FILL
            fill.color = Color.WHITE
            c.drawCircle(bx(i), by(i), r, fill)
        }
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 2 * d
        fill.strokeCap = Paint.Cap.SQUARE
        fill.color = Color.BLACK
        // unpin: x
        val ux = bx(1)
        val uy = by(1)
        c.drawLine(ux - k, uy - k, ux + k, uy + k, fill)
        c.drawLine(ux - k, uy + k, ux + k, uy - k, fill)
        // resize: diagonal arrow
        val rx = bx(2)
        val ry = by(2)
        c.drawLine(rx - k, ry + k, rx + k, ry - k, fill)
        c.drawLine(rx + k, ry - k, rx + k * 0.05f, ry - k, fill)
        c.drawLine(rx + k, ry - k, rx + k, ry - k * 0.05f, fill)
        // colour: a tiny mosaic
        fill.style = Paint.Style.FILL
        val cx = bx(3)
        val cy = by(3)
        val q = r * 0.36f
        val gap = d
        val cols = intArrayOf(Metro.PALETTE[0], Metro.PALETTE[1], Metro.PALETTE[2], Metro.PALETTE[3])
        for (i in 0..3) {
            fill.color = cols[i]
            val x = if (i % 2 == 0) cx - q - gap / 2 else cx + gap / 2
            val y = if (i < 2) cy - q - gap / 2 else cy + gap / 2
            c.drawRect(x, y, x + q, y + q, fill)
        }
    }

    private fun hitButton(x: Float, y: Float): Int {
        for (i in 1..3) if (hypot(x - bx(i), y - by(i)) < br * 1.7f) return i
        return 0
    }

    // ---------------------------------------------------------------- touch

    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false
    private var dragging = false
    private var longFired = false
    private var button = 0

    private val longPress = Runnable {
        longFired = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        untilt()
        host?.tileLongPress(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                moved = false
                dragging = false
                longFired = false
                button = if (editMode && picked) hitButton(e.x, e.y) else 0
                if (editMode && picked) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    if (!editMode) tilt(e.x, e.y)
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(e.rawX - downRawX, e.rawY - downRawY) > slop) {
                    moved = true
                    removeCallbacks(longPress)
                    if (!editMode) untilt()
                }
                if (moved && editMode && picked && button == 0) {
                    if (!dragging) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        host?.dragStart(this, downRawX, downRawY)
                    }
                    host?.dragMove(this, e.rawX, e.rawY)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                when {
                    dragging -> host?.dragEnd(this)
                    moved || longFired -> if (!editMode) untilt()
                    editMode && picked -> {
                        if (button != 0 && button == hitButton(e.x, e.y)) {
                            when (button) {
                                1 -> host?.tileUnpin(this)
                                2 -> host?.tileResize(this)
                                3 -> host?.tileCustomize(this)
                            }
                        }
                    }
                    editMode -> host?.tileSelect(this)
                    else -> {
                        performClick()
                        host?.tileTap(this)
                    }
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) host?.dragEnd(this)
                dragging = false
                if (!editMode) untilt()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Press feedback: the tile tilts toward your finger. */
    private fun tilt(x: Float, y: Float) {
        pivotX = width / 2f
        pivotY = height / 2f
        val nx = ((x / width) - 0.5f) * 2f
        val ny = ((y / height) - 0.5f) * 2f
        val k = if (tile.size == Tile.WIDE) 6f else 10f
        ani(110).rotationY(nx * k).rotationX(ny * k).scaleX(0.97f).scaleY(0.97f).start()
    }

    fun untilt() {
        ani(220).rotationX(0f).rotationY(0f).scaleX(1f).scaleY(1f).start()
    }
}
