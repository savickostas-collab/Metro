package com.metro.start

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs

/**
 * Two full-screen pages side by side (start, apps). Follows the finger horizontally
 * and settles with the Metro curve.
 */
class Pager(context: Context) : ViewGroup(context) {
    var offset = 0f
        private set
    var page = 0
        private set
    var locked = false
    var onOffset: ((Float) -> Unit)? = null
    var onPage: ((Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startOffset = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private var settle: ValueAnimator? = null

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (!dragging && settle?.isRunning != true) offset = page * w.toFloat()
        apply()
    }

    private fun apply() {
        val w = width.toFloat()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.translationX = -offset
            val start = i * w
            c.visibility = if (offset > start - w && offset < start + w) View.VISIBLE else View.INVISIBLE
        }
        onOffset?.invoke(if (w > 0) offset / w else 0f)
    }

    fun go(p: Int, animate: Boolean = true) {
        settle?.cancel()
        val changed = p != page
        page = p
        val target = p * width.toFloat()
        if (!animate || width == 0) {
            offset = target
            apply()
        } else {
            settle = ValueAnimator.ofFloat(offset, target).apply {
                duration = 360
                interpolator = Metro.EASE
                addUpdateListener {
                    offset = it.animatedValue as Float
                    apply()
                }
                start()
            }
        }
        if (changed) onPage?.invoke(p)
    }

    private fun track(e: MotionEvent) {
        val t = tracker ?: VelocityTracker.obtain().also { tracker = it }
        t.addMovement(e)
    }

    private fun startsDrag(e: MotionEvent): Boolean {
        val dx = e.x - downX
        val dy = e.y - downY
        if (abs(dx) < slop * 1.5f || abs(dx) < abs(dy) * 1.4f) return false
        return (dx < 0 && page < childCount - 1) || (dx > 0 && page > 0)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (locked) return false
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && startsDrag(e)) beginDrag(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycle()
        }
        return dragging
    }

    private fun beginDrag(e: MotionEvent) {
        dragging = true
        settle?.cancel()
        downX = e.x
        startOffset = offset
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (locked) return false
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && startsDrag(e)) beginDrag(e)
                if (dragging) {
                    offset = (startOffset - (e.x - downX)).coerceIn(0f, (childCount - 1) * width.toFloat())
                    apply()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val t = tracker
                    t?.computeCurrentVelocity(1000)
                    val vx = t?.xVelocity ?: 0f
                    val fling = dpf(600f)
                    val cur = offset / width
                    val target = when {
                        vx < -fling -> kotlin.math.floor(cur).toInt() + 1
                        vx > fling -> kotlin.math.floor(cur).toInt()
                        else -> kotlin.math.round(cur).toInt()
                    }.coerceIn(0, childCount - 1)
                    go(target)
                }
                dragging = false
                recycle()
            }
        }
        return true
    }

    private fun recycle() {
        tracker?.recycle()
        tracker = null
    }
}

/** Back, start (Windows logo) and search, as thin white icons on black. */
class NavBar(context: Context) : View(context) {
    var onBack: (() -> Unit)? = null
    var onHome: (() -> Unit)? = null
    var onSearch: (() -> Unit)? = null
    private var inset = 0
    private var pressed = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dpf(1.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setInset(bottom: Int) {
        if (bottom == inset) return
        inset = bottom
        requestLayout()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(48) + inset)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val cy = dpf(24f)
        val u = dpf(1f)
        for (i in 0..2) {
            val cx = width * (2 * i + 1) / 6f
            p.alpha = if (pressed == i) 110 else 255
            when (i) {
                0 -> {
                    p.style = Paint.Style.STROKE
                    c.drawLine(cx + 10 * u, cy, cx - 10 * u, cy, p)
                    c.drawLine(cx - 10 * u, cy, cx - 4 * u, cy - 6 * u, p)
                    c.drawLine(cx - 10 * u, cy, cx - 4 * u, cy + 6 * u, p)
                }
                1 -> {
                    p.style = Paint.Style.FILL
                    val s = 7.5f * u
                    val g = 1.5f * u
                    val x0 = cx - s - g / 2
                    val y0 = cy - s - g / 2
                    c.drawRect(x0, y0, x0 + s, y0 + s, p)
                    c.drawRect(x0 + s + g, y0, x0 + 2 * s + g, y0 + s, p)
                    c.drawRect(x0, y0 + s + g, x0 + s, y0 + 2 * s + g, p)
                    c.drawRect(x0 + s + g, y0 + s + g, x0 + 2 * s + g, y0 + 2 * s + g, p)
                }
                else -> {
                    p.style = Paint.Style.STROKE
                    val r = 6.5f * u
                    val ox = cx + 3 * u
                    val oy = cy - 3 * u
                    c.drawCircle(ox, oy, r, p)
                    c.drawLine(ox - r * 0.7071f, oy + r * 0.7071f, cx - 8 * u, cy + 8 * u, p)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = (e.x / (width / 3f)).toInt().coerceIn(0, 2)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = i
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (pressed == i && e.y >= 0) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    when (i) {
                        0 -> onBack?.invoke()
                        1 -> onHome?.invoke()
                        else -> onSearch?.invoke()
                    }
                }
                pressed = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
            }
        }
        return true
    }
}

/** The small circled arrow that opens the app list. */
class ArrowButton(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1.8f)
        strokeCap = Paint.Cap.ROUND
    }

    init {
        isClickable = true
        contentDescription = "apps"
        Tilt.attach(this, 14f)
    }

    override fun onDraw(c: Canvas) {
        p.color = Look.fg
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - p.strokeWidth
        c.drawCircle(cx, cy, r, p)
        val a = r * 0.42f
        c.drawLine(cx - a, cy, cx + a, cy, p)
        c.drawLine(cx + a, cy, cx + a * 0.2f, cy - a * 0.75f, p)
        c.drawLine(cx + a, cy, cx + a * 0.2f, cy + a * 0.75f, p)
    }
}

/** The flat WP8 toggle: outlined track, accent fill, solid rectangular thumb. */
class MetroToggle(context: Context) : View(context) {
    var checked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private var pos = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isClickable = true
        setOnClickListener { set(!checked, true, true) }
    }

    fun set(on: Boolean, animate: Boolean, notify: Boolean = false) {
        checked = on
        val target = if (on) 1f else 0f
        if (!animate) {
            pos = target
            invalidate()
        } else {
            ValueAnimator.ofFloat(pos, target).apply {
                duration = 250
                interpolator = Metro.EASE
                addUpdateListener {
                    pos = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        if (notify) onChange?.invoke(on)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(dp(76), dp(32))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sw = dpf(2f)
        val thumb = dpf(14f)
        val inner = dpf(5f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.color = Look.fg
        r.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
        c.drawRect(r, p)
        p.style = Paint.Style.FILL
        val tx = pos * (w - thumb)
        if (tx > inner) {
            p.color = Look.accentShade
            c.drawRect(inner, inner, tx, h - inner, p)
        }
        p.color = Look.fg
        c.drawRect(tx, 0f, tx + thumb, h, p)
    }
}

/** Windows Phone press feedback for any view: it tilts toward the finger. */
object Tilt {
    @SuppressLint("ClickableViewAccessibility")
    fun attach(v: View, strength: Float = 4f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.pivotX = view.width / 2f
                    view.pivotY = view.height / 2f
                    view.cameraDistance = 9000f * view.resources.displayMetrics.density
                    val nx = (e.x / view.width - 0.5f) * 2f
                    val ny = (e.y / view.height - 0.5f) * 2f
                    view.ani(110).rotationY(nx * strength).rotationX(ny * strength)
                        .scaleX(0.98f).scaleY(0.98f).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.ani(220).rotationX(0f).rotationY(0f).scaleX(1f).scaleY(1f).start()
            }
            false
        }
    }
}

/** App-list icon: white glyph on a coloured square (or the original icon if chosen). */
class IconSquare(context: Context) : View(context) {
    var app: AppInfo? = null
        set(v) {
            field = v
            invalidate()
        }
    var color: Int = Look.accentShade
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val a = app ?: return
        val w = width.toFloat()
        val ic = a.icon
        if (!Look.glyphs && ic != null) {
            ic.setBounds(0, 0, width, height)
            ic.draw(c)
            return
        }
        p.color = color
        c.drawRect(0f, 0f, w, w, p)
        val s = w * 0.9f
        r.set((w - s) / 2, (w - s) / 2, (w + s) / 2, (w + s) / 2)
        c.drawBitmap(a.glyph, null, r, p)
    }
}
