package com.metro.start

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * A Windows Phone pivot app: small app title, big lowercase page headers that slide
 * (slower than the content), and swipeable pages underneath.
 */
open class PivotActivity : MetroActivity() {
    lateinit var pivot: Pager
        private set
    lateinit var header: PivotHeader
        private set
    lateinit var column: LinearLayout
        private set

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        Look.load(this)
    }

    fun setupPivot(appTitle: String, pages: List<Pair<String, View>>) {
        Look.load(this)
        setupWindow()
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(appTitle, 14f, Metro.semibold).apply {
            setPadding(dp(20), dp(12), dp(20), 0)
        })
        header = PivotHeader(this, pages.map { it.first })
        column.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, dp(70)))
        pivot = Pager(this)
        for ((_, v) in pages) pivot.addView(v)
        pivot.onOffset = { f -> header.position = f }
        header.onTap = { pivot.go(it) }
        column.addView(pivot, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        onInsets { top, bottom, ime ->
            column.setPadding(0, top, 0, 0)
            onBottomInset(maxOf(bottom, ime))
        }
        // Content slides in from the right when the app opens.
        pivot.translationX = dp(80).toFloat()
        pivot.alpha = 0f
        header.translationX = dp(40).toFloat()
        pivot.post {
            pivot.ani(380).translationX(0f).alpha(1f).start()
            header.ani(420).translationX(0f).start()
        }
    }

    /** Pages pad their lists by this so nothing hides behind the system bar or keyboard. */
    open fun onBottomInset(bottom: Int) {}

    override fun back() {
        finish()
    }
}

class PivotHeader(context: Context, private val labels: List<String>) : View(context) {
    var onTap: ((Int) -> Unit)? = null
    var position = 0f
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Metro.light
        textSize = dpf(44f) * resources.configuration.fontScale.coerceAtMost(1.15f)
    }
    private val gap get() = dpf(22f)
    private val start get() = dpf(18f)

    private fun x(i: Int): Float {
        var x = start
        for (j in 0 until i) x += p.measureText(labels[j]) + gap
        return x
    }

    private fun scroll(): Float {
        val a = floor(position).toInt().coerceIn(0, labels.size - 1)
        val b = ceil(position).toInt().coerceIn(0, labels.size - 1)
        val f = position - a
        return (x(a) + (x(b) - x(a)) * f) - start
    }

    override fun onDraw(c: Canvas) {
        val s = scroll()
        val base = height * 0.78f
        for (i in labels.indices) {
            val d = min(1f, abs(i - position))
            p.color = alpha(Look.fg, 1f - d * 0.7f)
            c.drawText(labels[i], x(i) - s, base, p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val s = scroll()
            for (i in labels.indices) {
                val l = x(i) - s
                if (e.x >= l - gap / 2 && e.x <= l + p.measureText(labels[i]) + gap / 2) {
                    onTap?.invoke(i)
                    break
                }
            }
        }
        return true
    }
}

/** A small RecyclerView adapter driven by lambdas; rows are any objects. */
class Rows(
    private val type: (Any) -> Int,
    private val create: (ViewGroup, Int) -> View,
    private val bind: (View, Any) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    class H(v: View) : RecyclerView.ViewHolder(v)

    var items: List<Any> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = type(items[position])
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H(create(parent, viewType))
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = bind(holder.itemView, items[position])
}

fun Context.metroList(adapter: RecyclerView.Adapter<*>): RecyclerView = RecyclerView(this).apply {
    layoutManager = LinearLayoutManager(context)
    this.adapter = adapter
    overScrollMode = View.OVER_SCROLL_NEVER
    clipToPadding = false
    itemAnimator = null
}

/** A big lowercase message for empty pages, with an optional action underneath. */
fun Context.emptyState(title: String, body: String, action: String?, onAction: (() -> Unit)?): LinearLayout {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(20))
    }
    box.addView(text(title, 26f, Metro.light))
    box.addView(text(body, 15f, Metro.regular, Look.fg60).apply { setPadding(0, dp(6), 0, 0) })
    if (action != null && onAction != null) box.addView(metroButton(action, onAction), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
        topMargin = dp(18)
    })
    return box
}

/** Flat outlined Windows Phone button. */
fun Context.metroButton(label: String, onClick: () -> Unit): TextView = text(label, 18f, Metro.regular).apply {
    background = flatBox(Look.fg, dp(2))
    setPadding(dp(20), dp(9), dp(20), dp(10))
    isClickable = true
    setOnClickListener { onClick() }
    Tilt.attach(this, 8f)
}
