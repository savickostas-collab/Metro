package com.metro.launcher

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import kotlin.math.max
import kotlin.math.min

/**
 * 4-column Metro tile grid. Small = 1x1, medium = 2x2, wide = 4x2.
 * Small tiles fill 2x2 blocks before the next block starts, like Windows Phone.
 */
class TileGridLayout(context: Context) : ViewGroup(context) {

    private val d = resources.displayMetrics.density
    private val gap = (4 * d).toInt()
    private val margin = (14 * d).toInt()
    private val topPad = (8 * d).toInt()
    private val bottomPad = (104 * d).toInt()
    private val rects = ArrayList<Rect>()

    /** Called after any change to the tile list (add / remove / resize / reorder). */
    var onChanged: (() -> Unit)? = null

    private fun spanW(size: Int) = when (size) {
        2 -> 4
        1 -> 2
        else -> 1
    }

    private fun spanH(size: Int) = if (size == 0) 1 else 2

    // ---------------------------------------------------------------- layout

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val unit = (w - 2 * margin - 3 * gap) / 4
        rects.clear()

        val occ = ArrayList<BooleanArray>()
        fun ensure(rows: Int) {
            while (occ.size < rows) occ.add(BooleanArray(4))
        }

        fun fits(r: Int, c: Int, sw: Int, sh: Int): Boolean {
            ensure(r + sh)
            for (i in r until r + sh) for (j in c until c + sw) if (occ[i][j]) return false
            return true
        }

        fun mark(r: Int, c: Int, sw: Int, sh: Int) {
            for (i in r until r + sh) for (j in c until c + sw) occ[i][j] = true
        }

        var maxRow = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i) as TileView
            val sw = spanW(v.data.size)
            val sh = spanH(v.data.size)
            var pr = 0
            var pc = 0
            var found = false

            if (sw == 1) {
                var br = 0
                while (!found) {
                    for (bc in 0..1) {
                        for (k in 0..3) {
                            val r = br * 2 + k / 2
                            val c = bc * 2 + k % 2
                            if (fits(r, c, 1, 1)) {
                                pr = r; pc = c; found = true
                                break
                            }
                        }
                        if (found) break
                    }
                    if (!found) br++
                }
            } else {
                var r = 0
                while (!found) {
                    var c = 0
                    while (c + sw <= 4) {
                        if (fits(r, c, sw, sh)) {
                            pr = r; pc = c; found = true
                            break
                        }
                        c += 2
                    }
                    if (!found) r += 2
                }
            }
            mark(pr, pc, sw, sh)

            val left = margin + pc * (unit + gap)
            val top = topPad + pr * (unit + gap)
            val width = sw * unit + (sw - 1) * gap
            val height = sh * unit + (sh - 1) * gap
            rects.add(Rect(left, top, left + width, top + height))
            v.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
            maxRow = max(maxRow, pr + sh)
        }
        setMeasuredDimension(w, topPad + maxRow * (unit + gap) + bottomPad)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            if (i >= rects.size) break
            val rc = rects[i]
            getChildAt(i).layout(rc.left, rc.top, rc.right, rc.bottom)
        }
    }

    // ---------------------------------------------------------------- access

    fun tiles(): List<TileView> = (0 until childCount).map { getChildAt(it) as TileView }
    fun data(): List<TileData> = tiles().map { it.data }

    // ---------------------------------------------------------------- animated changes

    /** Runs [change], then slides every surviving child from its old spot to its new one. */
    private fun animateChange(change: () -> Unit) {
        val old = HashMap<View, Pair<Float, Float>>()
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            old[v] = Pair(v.left + v.translationX, v.top + v.translationY)
        }
        change()
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                for (i in 0 until childCount) {
                    val v = getChildAt(i)
                    val o = old[v]
                    if (o != null) {
                        val dx = o.first - v.left
                        val dy = o.second - v.top
                        if (dx != 0f || dy != 0f) {
                            v.translationX = dx
                            v.translationY = dy
                            v.animate().translationX(0f).translationY(0f).setDuration(260).start()
                        }
                    } else {
                        v.alpha = 0f
                        v.scaleX = 0.6f
                        v.scaleY = 0.6f
                        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).start()
                    }
                }
                return true
            }
        })
    }

    fun addTile(v: TileView, animate: Boolean) {
        if (animate) animateChange { addView(v) } else addView(v)
        onChanged?.invoke()
    }

    fun removeTile(v: TileView) {
        v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(160).withEndAction {
            animateChange { removeView(v) }
            onChanged?.invoke()
        }.start()
    }

    fun resizeTile(v: TileView, size: Int) {
        animateChange {
            v.data.size = size
            requestLayout()
        }
        onChanged?.invoke()
    }

    /** Called after a drag ends: drop onto another tile to take its place, or snap back. */
    fun dropTile(v: TileView) {
        val cx = v.left + v.translationX + v.width / 2f
        val cy = v.top + v.translationY + v.height / 2f
        var target = -1
        for (i in 0 until childCount) {
            val o = getChildAt(i)
            if (o === v) continue
            if (cx >= o.left && cx <= o.right && cy >= o.top && cy <= o.bottom) {
                target = i
                break
            }
        }
        v.translationZ = 0f
        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        if (target == -1) {
            v.animate().translationX(0f).translationY(0f).setDuration(200).start()
            return
        }
        animateChange {
            removeView(v)
            addView(v, min(target, childCount))
        }
        onChanged?.invoke()
    }

    // ---------------------------------------------------------------- modes / effects

    fun setEditing(on: Boolean, picked: TileView?) {
        for (v in tiles()) v.setEdit(on, v === picked)
    }

    fun refreshClock() {
        for (v in tiles()) if (v.data.isClock) v.invalidate()
    }

    fun flipAll() {
        for ((i, v) in tiles().withIndex()) postDelayed({ v.flip() }, i * 220L)
    }

    /** Windows-Phone "turnstile": every tile swings away around the left edge, tapped tile last. */
    fun playExit(clicked: TileView, done: () -> Unit) {
        var last = 0L
        for (v in tiles()) {
            val delay = if (v === clicked) 150L else (v.top / (90f * d)).toLong() * 30L
            v.pivotX = -v.left.toFloat()
            v.pivotY = v.height / 2f
            v.animate().rotationY(80f).alpha(0f).setStartDelay(delay).setDuration(180).start()
            last = max(last, delay + 180L)
        }
        postDelayed({ done() }, last)
    }

    fun playEnter() {
        for (v in tiles()) {
            val delay = (v.top / (90f * d)).toLong() * 25L
            v.pivotX = -v.left.toFloat()
            v.pivotY = v.height / 2f
            v.rotationY = 80f
            v.alpha = 0f
            v.animate().rotationY(0f).alpha(1f).setStartDelay(delay).setDuration(200).withEndAction {
                v.pivotX = v.width / 2f
                v.pivotY = v.height / 2f
            }.start()
        }
    }

    fun resetAnim() {
        for (v in tiles()) {
            v.animate().cancel()
            v.rotationY = 0f
            v.alpha = 1f
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
        }
    }
}
