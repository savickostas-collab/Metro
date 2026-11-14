package com.metro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A single Metro tile: flat accent colour, big icon, label bottom-left,
 * press-tilt, flip-to-notification live tile, and edit-mode buttons.
 */
class TileView(context: Context, val data: TileData, val entry: AppEntry?) : View(context) {

    interface Host {
        fun onLaunch(v: TileView)
        fun onLongPress(v: TileView)
        fun onSelect(v: TileView)
        fun onUnpin(v: TileView)
        fun onResize(v: TileView)
        fun onDrop(v: TileView)
    }

    var host: Host? = null

    var accent: Int = Color.BLUE
        set(value) {
            field = value
            invalidate()
        }

    var badge = 0
        private set
    private var noteTitle: String? = null
    private var noteText: String? = null
    private var showNote = false

    var editMode = false
        private set
    var picked = false
        private set

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    // Own copy of the icon so bounds changes here never affect the app list.
    private val icon: Drawable? = entry?.icon?.let { it.constantState?.newDrawable()?.mutate() ?: it }
    private val mono: Drawable? =
        if (Build.VERSION.SDK_INT >= 33 && icon is AdaptiveIconDrawable) icon.monochrome else null

    // drag / edit-button state
    private var tracking = false
    private var dragging = false
    private var btn = 0 // 1 = unpin, 2 = resize
    private var startRawX = 0f
    private var startRawY = 0f
    private var baseTx = 0f
    private var baseTy = 0f

    init {
        isClickable = true
        cameraDistance = 12000f * d
        setOnClickListener {
            if (editMode) host?.onSelect(this) else host?.onLaunch(this)
        }
        setOnLongClickListener {
            if (!editMode) host?.onLongPress(this)
            true
        }
    }

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // ---------------------------------------------------------------- live data

    fun setLive(count: Int, title: String?, text: String?) {
        badge = count
        noteTitle = title
        noteText = text
        if (title == null && showNote) showNote = false
        invalidate()
    }

    /** Flip between the icon face and the latest-notification face. */
    fun flip() {
        if (data.isClock || data.size == 0 || editMode) return
        if (noteTitle == null && !showNote) return
        animate().rotationX(90f).setDuration(180).withEndAction {
            showNote = !showNote && noteTitle != null
            rotationX = -90f
            animate().rotationX(0f).setDuration(180).start()
        }.start()
    }

    fun setEdit(on: Boolean, isPicked: Boolean) {
        editMode = on
        picked = isPicked
        val s = if (!on || isPicked) 1f else 0.9f
        val a = if (!on || isPicked) 1f else 0.6f
        animate().scaleX(s).scaleY(s).alpha(a).rotationX(0f).rotationY(0f).setDuration(150).start()
        invalidate()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = accent
        c.drawRect(0f, 0f, w, h, paint)

        when {
            data.isClock -> drawClock(c, w, h)
            showNote && noteTitle != null -> drawNote(c, w, h)
            else -> drawIconFace(c, w, h)
        }
        if (editMode && picked) drawEditButtons(c, w, h)
    }

    private fun drawIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        val m = mono
        if (m != null) {
            // Adaptive layers are 108dp with a 72dp visible area, so scale up by 1.5.
            val s = size * 1.5f
            m.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            m.setTint(Color.WHITE)
            m.draw(c)
        } else {
            val ic = icon ?: return
            ic.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
            ic.draw(c)
        }
    }

    private fun drawLabel(c: Canvas, w: Float, h: Float) {
        tp.color = Color.WHITE
        tp.typeface = Typeface.DEFAULT
        tp.textSize = sp(13f)
        tp.textAlign = Paint.Align.LEFT
        val reserve = if (badge > 0) 40 * d else 0f
        val label = TextUtils.ellipsize(entry?.label ?: "", tp, w - 20 * d - reserve, TextUtils.TruncateAt.END)
        c.drawText(label.toString(), 10 * d, h - 10 * d, tp)
        if (badge > 0) {
            tp.typeface = light
            tp.textSize = sp(24f)
            tp.textAlign = Paint.Align.RIGHT
            c.drawText(badge.toString(), w - 10 * d, h - 10 * d, tp)
            tp.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawIconFace(c: Canvas, w: Float, h: Float) {
        val small = data.size == 0
        val s = if (small) w * 0.55f else h * 0.40f
        val cy = if (small) h / 2f else h * 0.44f
        drawIcon(c, w / 2f, cy, s)
        if (!small) drawLabel(c, w, h)
    }

    private fun drawNote(c: Canvas, w: Float, h: Float) {
        val pad = 12 * d
        tp.color = Color.WHITE
        tp.textAlign = Paint.Align.LEFT
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textSize = sp(15f)
        val title = noteTitle?.ifBlank { null } ?: noteText.orEmpty()
        val body = if (noteTitle.isNullOrBlank()) "" else noteText.orEmpty()
        c.drawText(TextUtils.ellipsize(title, tp, w - 2 * pad, TextUtils.TruncateAt.END).toString(), pad, pad + sp(15f), tp)
        if (body.isNotEmpty()) {
            tp.typeface = Typeface.DEFAULT
            tp.textSize = sp(13f)
            val sl = StaticLayout.Builder
                .obtain(body, 0, body.length, tp, (w - 2 * pad).toInt())
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            c.save()
            c.translate(pad, pad + sp(15f) + 8 * d)
            sl.draw(c)
            c.restore()
        }
        drawLabel(c, w, h)
    }

    private fun drawClock(c: Canvas, w: Float, h: Float) {
        val now = Date()
        val is24 = android.text.format.DateFormat.is24HourFormat(context)
        val time = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now)

        tp.color = Color.WHITE
        tp.typeface = light
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = if (data.size == 2) h * 0.46f else w * 0.30f
        val maxW = w - 24 * d
        val tw = tp.measureText(time)
        if (tw > maxW) tp.textSize = tp.textSize * maxW / tw
        c.drawText(time, 12 * d, h * 0.55f, tp)

        if (data.size != 0) {
            tp.textSize = sp(if (data.size == 2) 15f else 12f)
            val dt = TextUtils.ellipsize(date, tp, maxW, TextUtils.TruncateAt.END)
            c.drawText(dt.toString(), 14 * d, h * 0.55f + sp(26f), tp)
        }
    }

    // edit-mode button geometry
    private val br get() = 13 * d
    private val bm get() = 6 * d
    private fun unpinX() = width - bm - br
    private fun unpinY() = bm + br
    private fun resizeX() = width - bm - br
    private fun resizeY() = height - bm - br

    private fun drawEditButtons(c: Canvas, w: Float, h: Float) {
        val r = br
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        c.drawCircle(unpinX(), unpinY(), r, paint)
        c.drawCircle(resizeX(), resizeY(), r, paint)

        paint.style = Paint.Style.STROKE
        paint.color = Color.BLACK
        paint.strokeWidth = 2 * d
        paint.strokeCap = Paint.Cap.ROUND
        val k = r * 0.4f
        // unpin: an X
        val ux = unpinX()
        val uy = unpinY()
        c.drawLine(ux - k, uy - k, ux + k, uy + k, paint)
        c.drawLine(ux - k, uy + k, ux + k, uy - k, paint)
        // resize: diagonal arrow
        val rx = resizeX()
        val ry = resizeY()
        c.drawLine(rx - k, ry + k, rx + k, ry - k, paint)
        val a = k * 0.9f
        c.drawLine(rx + k, ry - k, rx + k - a, ry - k, paint)
        c.drawLine(rx + k, ry - k, rx + k, ry - k + a, paint)
        paint.style = Paint.Style.FILL
    }

    private fun hit(x: Float, y: Float, cx: Float, cy: Float) = hypot(x - cx, y - cy) < br * 1.8f

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (editMode && picked) return handleEditTouch(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> tilt(ev.x, ev.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> untilt()
        }
        return super.onTouchEvent(ev)
    }

    private fun tilt(x: Float, y: Float) {
        val nx = (x / width - 0.5f) * 2f
        val ny = (y / height - 0.5f) * 2f
        animate().rotationY(nx * 12f).rotationX(-ny * 12f).scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
    }

    private fun untilt() {
        val s = if (editMode && !picked) 0.9f else 1f
        animate().rotationX(0f).rotationY(0f).scaleX(s).scaleY(s).setDuration(140).start()
    }

    private fun handleEditTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                dragging = false
                startRawX = ev.rawX
                startRawY = ev.rawY
                baseTx = translationX
                baseTy = translationY
                btn = when {
                    hit(ev.x, ev.y, unpinX(), unpinY()) -> 1
                    hit(ev.x, ev.y, resizeX(), resizeY()) -> 2
                    else -> 0
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) {
                    // Arrived here straight from a long-press: start dragging from now.
                    tracking = true
                    dragging = false
                    btn = 0
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    baseTx = translationX
                    baseTy = translationY
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                val dx = ev.rawX - startRawX
                val dy = ev.rawY - startRawY
                if (!dragging && btn == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    translationZ = 24 * d
                    animate().scaleX(1.06f).scaleY(1.06f).setDuration(100).start()
                }
                if (dragging) {
                    translationX = baseTx + dx
                    translationY = baseTy + dy
                }
            }
            MotionEvent.ACTION_UP -> {
                if (tracking) {
                    if (btn == 1 && hit(ev.x, ev.y, unpinX(), unpinY())) {
                        host?.onUnpin(this)
                    } else if (btn == 2 && hit(ev.x, ev.y, resizeX(), resizeY())) {
                        host?.onResize(this)
                    } else if (dragging) {
                        post { host?.onDrop(this) }
                    }
                }
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) post { host?.onDrop(this) }
                endTouch()
            }
        }
        return true
    }

    private fun endTouch() {
        tracking = false
        dragging = false
        btn = 0
        isPressed = false
    }
}
