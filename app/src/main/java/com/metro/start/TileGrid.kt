package com.metro.start

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** The start screen: a 4-column unit grid with 8dp gaps. */
class TileGrid(context: Context) : ViewGroup(context), TileView.Host {

    interface Listener {
        fun onTileTap(v: TileView)
        fun onEditChanged(editing: Boolean)
        fun onTilesChanged()
        fun onCustomize(v: TileView)
    }

    var listener: Listener? = null
    var scroller: ScrollView? = null

    val views = ArrayList<TileView>()
    private val gap = dp(8)
    private val side = dp(12)
    var topPad = dp(64)
    var bottomPad = dp(96)
    private var unit = 0
    private var contentBottom = 0

    var editing = false
        private set
    private var picked: TileView? = null
    private var animateNext = false

    // ---------------------------------------------------------------- layout

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        unit = (w - 2 * side - 3 * gap) / 4
        val cells = Packer.pack(views.map { it.tile.size })
        var rows = 0
        for ((i, v) in views.withIndex()) {
            val cl = cells[i]
            v.cellX = side + cl.col * (unit + gap)
            v.cellY = topPad + cl.row * (unit + gap)
            val cw = cl.w * unit + (cl.w - 1) * gap
            val ch = cl.h * unit + (cl.h - 1) * gap
            v.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
            rows = max(rows, cl.row + cl.h)
        }
        contentBottom = topPad + rows * (unit + gap)
        val min = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) 0 else MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w, max(min, contentBottom + bottomPad))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (v in views) {
            val ox = v.left
            val oy = v.top
            val wasLaidOut = v.isLaidOut
            v.layout(v.cellX, v.cellY, v.cellX + v.measuredWidth, v.cellY + v.measuredHeight)
            if (v === dragView) {
                v.translationX = dragX - v.left
                v.translationY = dragY - v.top
            } else if (animateNext && wasLaidOut && (ox != v.left || oy != v.top)) {
                v.translationX += (ox - v.left)
                v.translationY += (oy - v.top)
                v.ani(320).translationX(0f).translationY(0f).start()
            }
        }
        animateNext = false
    }

    // ---------------------------------------------------------------- tiles

    fun setTiles(list: List<TileView>) {
        removeAllViews()
        views.clear()
        for (v in list) attach(v)
        requestLayout()
    }

    private fun attach(v: TileView) {
        v.host = this
        views.add(v)
        addView(v)
        if (editing) v.setEdit(true, false)
    }

    fun addTile(v: TileView) {
        attach(v)
        v.scaleX = 0.4f
        v.scaleY = 0.4f
        v.alpha = 0f
        animateNext = true
        requestLayout()
        post { v.ani(360).scaleX(1f).scaleY(1f).alpha(1f).start() }
    }

    fun removeNow(v: TileView) {
        views.remove(v)
        removeView(v)
        if (picked === v) picked = null
        animateNext = true
        requestLayout()
    }

    fun visibleTiles(): List<TileView> {
        val s = scroller ?: return views
        val top = s.scrollY
        val bottom = top + s.height
        return views.filter { it.bottom > top && it.top < bottom }
    }

    fun tiles(): List<Tile> = views.map { it.tile }

    // ---------------------------------------------------------------- edit mode

    private var wobble: ValueAnimator? = null

    private fun enterEdit(v: TileView) {
        editing = true
        picked = v
        for (t in views) t.setEdit(true, t === v)
        startWobble()
        listener?.onEditChanged(true)
    }

    fun exitEdit() {
        if (!editing) return
        editing = false
        picked = null
        wobble?.cancel()
        wobble = null
        for (t in views) t.setEdit(false, false)
        listener?.onEditChanged(false)
    }

    private fun select(v: TileView) {
        picked = v
        for (t in views) t.setEdit(true, t === v)
    }

    /** Unselected tiles sway gently, each on its own phase. */
    private fun startWobble() {
        wobble?.cancel()
        wobble = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val t = SystemClock.uptimeMillis() / 1000f
                for (v in views) {
                    v.rotation = if (v === picked || v === dragView) 0f
                    else sin(t * 7.5f + v.phase) * 0.9f
                }
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Tapping empty space leaves edit mode.
        if (!editing) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) exitEdit()
        return true
    }

    // ---------------------------------------------------------------- TileView.Host

    override fun tileTap(v: TileView) {
        listener?.onTileTap(v)
    }

    override fun tileLongPress(v: TileView) {
        if (editing) select(v) else enterEdit(v)
    }

    override fun tileSelect(v: TileView) = select(v)

    override fun tileUnpin(v: TileView) {
        v.ani(200).scaleX(0f).scaleY(0f).alpha(0f).withEndAction {
            removeNow(v)
            listener?.onTilesChanged()
            if (views.isEmpty()) exitEdit()
        }.start()
    }

    override fun tileResize(v: TileView) {
        // Same order as Windows Phone: medium -> small -> wide -> medium.
        v.tile.size = when (v.tile.size) {
            Tile.MEDIUM -> Tile.SMALL
            Tile.SMALL -> Tile.WIDE
            else -> Tile.MEDIUM
        }
        animateNext = true
        requestLayout()
        v.invalidate()
        listener?.onTilesChanged()
    }

    override fun tileCustomize(v: TileView) {
        listener?.onCustomize(v)
    }

    // ---------------------------------------------------------------- drag to reorder

    private var dragView: TileView? = null
    private var grabX = 0f
    private var grabY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var pendingTarget = -1
    private val loc = IntArray(2)

    override fun dragStart(v: TileView, rawX: Float, rawY: Float) {
        if (picked !== v) select(v)
        getLocationOnScreen(loc)
        grabX = rawX - loc[0] - v.left
        grabY = rawY - loc[1] - v.top
        dragView = v
        v.animate().cancel()
        v.translationZ = dpf(16f)
        v.ani(160).scaleX(1.05f).scaleY(1.05f).alpha(0.92f).start()
        post(autoScroll)
    }

    override fun dragMove(v: TileView, rawX: Float, rawY: Float) {
        if (dragView !== v) return
        lastRawX = rawX
        lastRawY = rawY
        getLocationOnScreen(loc)
        val fx = rawX - loc[0]
        val fy = rawY - loc[1]
        dragX = fx - grabX
        dragY = fy - grabY
        v.translationX = dragX - v.left
        v.translationY = dragY - v.top
        // Which tile is under the finger?
        var target = -1
        for ((i, o) in views.withIndex()) {
            if (o === v) continue
            if (fx >= o.left && fx <= o.right && fy >= o.top && fy <= o.bottom) {
                target = i
                break
            }
        }
        if (target == -1 && fy > contentBottom) target = views.size - 1
        if (target != pendingTarget) {
            pendingTarget = target
            removeCallbacks(reorder)
            if (target >= 0 && target != views.indexOf(v)) postDelayed(reorder, 140)
        }
    }

    private val reorder = Runnable {
        val v = dragView ?: return@Runnable
        val to = pendingTarget
        val from = views.indexOf(v)
        if (to < 0 || from < 0 || to == from) return@Runnable
        views.removeAt(from)
        views.add(to.coerceAtMost(views.size), v)
        pendingTarget = -1
        animateNext = true
        requestLayout()
    }

    /** Dragging near the top or bottom edge scrolls the start screen. */
    private val autoScroll = object : Runnable {
        override fun run() {
            val v = dragView ?: return
            val s = scroller
            if (s != null) {
                s.getLocationOnScreen(loc)
                val y = lastRawY - loc[1]
                val zone = dpf(72f)
                val step = when {
                    y < zone -> -dp(12)
                    y > s.height - zone -> dp(12)
                    else -> 0
                }
                if (step != 0) {
                    val before = s.scrollY
                    s.scrollBy(0, step)
                    if (s.scrollY != before) dragMove(v, lastRawX, lastRawY)
                }
            }
            postOnAnimation(this)
        }
    }

    override fun dragEnd(v: TileView) {
        removeCallbacks(reorder)
        removeCallbacks(autoScroll)
        pendingTarget = -1
        dragView = null
        v.translationZ = 0f
        v.ani(300).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).start()
        listener?.onTilesChanged()
    }

    // ---------------------------------------------------------------- live tiles

    private var lastFlip = 0L
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            postDelayed(this, 400)
        }
    }

    fun startLive() {
        removeCallbacks(ticker)
        postDelayed(ticker, 800)
    }

    fun stopLive() = removeCallbacks(ticker)

    /** Each tile flips every 5–10 s, and never two at the same moment. */
    private fun tick() {
        if (editing || dragView != null) return
        val now = SystemClock.uptimeMillis()
        for (v in views) if (v.nextFlip == 0L) v.nextFlip = now + Random.nextLong(1500, 9000)
        if (now - lastFlip < 900) return
        val due = views.filter { it.nextFlip <= now }
        if (due.isEmpty()) return
        val v = due[Random.nextInt(due.size)]
        v.nextFlip = now + Random.nextLong(5000, 10000)
        if (v.canFlip() && v.isShown) {
            v.flip()
            lastFlip = now
        }
    }
}
