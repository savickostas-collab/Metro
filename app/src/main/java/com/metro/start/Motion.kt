package com.metro.start

import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** The signature Windows Phone transitions. */
object Motion {
    private val main = Handler(Looper.getMainLooper())
    private val loc = IntArray(2)

    private fun hingeOnScreenLeft(v: View) {
        v.getLocationOnScreen(loc)
        v.pivotX = -(loc[0] - v.translationX)
        v.pivotY = v.height / 2f
        v.cameraDistance = 9000f * v.resources.displayMetrics.density
    }

    private fun order(views: List<View>): List<View> {
        return views.sortedWith(compareBy({ screenY(it) }, { screenX(it) }))
    }

    private fun screenY(v: View): Int {
        v.getLocationOnScreen(loc)
        return loc[1]
    }

    private fun screenX(v: View): Int {
        v.getLocationOnScreen(loc)
        return loc[0]
    }

    /** Everything swings away around the left edge of the screen, the tapped item last. */
    fun turnstileOut(views: List<View>, tapped: View?, done: () -> Unit) {
        val sorted = order(views)
        for (v in sorted) hingeOnScreenLeft(v)
        var i = 0
        var last = 0L
        for (v in sorted) {
            if (v === tapped) continue
            val delay = min(i * 22L, 220L)
            v.ani(300).setStartDelay(delay).rotationY(80f).alpha(0f).start()
            last = max(last, delay)
            i++
        }
        var end = last + 160
        if (tapped != null) {
            val delay = last + 60
            tapped.ani(300).setStartDelay(delay).rotationY(80f).alpha(0f).rotationX(0f).scaleX(1f).scaleY(1f).start()
            end = delay + 170
        }
        main.postDelayed(done, end)
    }

    /** Coming back: the same swing, reversed. */
    fun turnstileIn(views: List<View>) {
        val sorted = order(views)
        for ((i, v) in sorted.withIndex()) {
            hingeOnScreenLeft(v)
            v.rotationY = 80f
            v.alpha = 0f
            v.ani(380).setStartDelay(min(i * 22L, 220L)).rotationY(0f).alpha(1f).withEndAction {
                v.pivotX = v.width / 2f
                v.pivotY = v.height / 2f
            }.start()
        }
    }

    /** Start screen entry: tiles fly in from the right, 30 ms apart. */
    fun flyIn(views: List<View>) {
        val sorted = order(views)
        for ((i, v) in sorted.withIndex()) {
            v.rotationY = 0f
            v.translationX = v.resources.displayMetrics.widthPixels * 0.55f
            v.alpha = 0f
            v.ani(400).setStartDelay(min(i * 30L, 360L)).translationX(0f).alpha(1f).start()
        }
    }

    fun reset(views: List<View>) {
        for (v in views) {
            v.animate().cancel()
            v.rotationY = 0f
            v.rotationX = 0f
            v.translationX = 0f
            v.alpha = 1f
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
        }
    }
}
