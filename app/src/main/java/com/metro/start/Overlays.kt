package com.metro.start

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/** Base for every Metro screen: edge-to-edge window, insets, and in-window overlays. */
open class MetroActivity : Activity() {
    lateinit var root: FrameLayout
    var insetTop = 0
    var insetBottom = 0
    private val overlays = ArrayList<Overlay>()

    fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = if (Look.navBar) Color.BLACK else Look.bg
        window.setBackgroundDrawable(ColorDrawable(Look.bg))
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.isAppearanceLightStatusBars = !Look.dark
        ctl.isAppearanceLightNavigationBars = !Look.dark && !Look.navBar
        root = FrameLayout(this)
        root.setBackgroundColor(Look.bg)
    }

    /** Calls [apply] with (top, bottom, imeBottom) whenever the system bars change. */
    fun onInsets(apply: (Int, Int, Int) -> Unit) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            insetTop = bars.top
            insetBottom = bars.bottom
            apply(bars.top, bars.bottom, ime.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    val hasOverlay: Boolean get() = overlays.isNotEmpty()

    fun show(o: Overlay) {
        val v = o.create()
        o.view = v
        root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlays.add(o)
        v.alpha = 0f
        v.post {
            v.alpha = 1f
            o.enter()
        }
    }

    fun dismiss(o: Overlay) {
        if (!overlays.remove(o)) return
        o.exit { root.removeView(o.view) }
    }

    fun closeOverlays() {
        for (o in overlays.toList()) dismiss(o)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (overlays.isNotEmpty()) dismiss(overlays.last()) else back()
    }

    @Suppress("DEPRECATION")
    open fun back() {
        super.onBackPressed()
    }

    /** A settings-style on/off row: title, optional subtitle, "on"/"off" and the toggle. */
    fun toggleRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): Pair<View, MetroToggle> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        box.addView(text(title, 20f, Metro.regular))
        if (sub != null) box.addView(text(sub, 14f, Metro.regular, Look.fg60))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val state = text(if (checked) "on" else "off", 18f, Metro.regular, Look.fg60)
        val tg = MetroToggle(this)
        tg.set(checked, false)
        tg.onChange = { on ->
            state.text = if (on) "on" else "off"
            onChange(on)
        }
        row.addView(state, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(tg)
        box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        return box to tg
    }
}

abstract class Overlay(val act: MetroActivity) {
    lateinit var view: View
    abstract fun create(): View
    open fun enter() {}
    open fun exit(done: () -> Unit) {
        view.ani(160).alpha(0f).withEndAction { done() }.start()
    }

    fun close() = act.dismiss(this)
}

/** Flat context menu: inverse-colour panel that swings down below the item you held. */
class MetroMenu(
    act: MetroActivity,
    private val items: List<Pair<String, () -> Unit>>,
    private val anchor: View?
) : Overlay(act) {
    private lateinit var panel: LinearLayout
    private val panelBg = if (Look.dark) Color.WHITE else 0xFF1F1F1F.toInt()
    private val panelFg = if (Look.dark) Color.BLACK else Color.WHITE

    override fun create(): View {
        val c = act
        val frame = FrameLayout(c).apply {
            isClickable = true
            setOnClickListener { close() }
        }
        panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panelBg)
            setPadding(0, c.dp(10), 0, c.dp(10))
            isClickable = true
        }
        for ((label, action) in items) {
            val tv = c.text(label, 22f, Metro.light, panelFg).apply {
                setPadding(c.dp(24), c.dp(11), c.dp(24), c.dp(11))
                setOnClickListener {
                    close()
                    action()
                }
            }
            Tilt.attach(tv, 2f)
            panel.addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        frame.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        return frame
    }

    override fun enter() {
        val frame = view
        val h = panel.height
        var y = (act.insetTop + act.dp(12)).toFloat()
        anchor?.let { a ->
            val al = IntArray(2)
            val fl = IntArray(2)
            a.getLocationInWindow(al)
            frame.getLocationInWindow(fl)
            val below = (al[1] - fl[1] + a.height).toFloat()
            val above = (al[1] - fl[1] - h).toFloat()
            y = if (below + h < frame.height - act.insetBottom) below else max(act.insetTop.toFloat(), above)
        }
        panel.translationY = y
        val dim = alpha(Look.bg, 0.65f)
        ValueAnimator.ofArgb(alpha(Look.bg, 0f), dim).apply {
            duration = 250
            addUpdateListener { frame.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
        panel.pivotX = panel.width / 2f
        panel.pivotY = 0f
        panel.cameraDistance = 9000f * act.resources.displayMetrics.density
        panel.rotationX = -60f
        panel.alpha = 0f
        panel.ani(320).rotationX(0f).alpha(1f).start()
    }
}

/** Full-page colour picker: the 12-colour palette plus any custom hex. */
class ColorPicker(
    act: MetroActivity,
    private val title: String,
    private val current: Int,
    private val live: Boolean?,
    private val onColor: (Int) -> Unit,
    private val onLive: ((Boolean) -> Unit)? = null
) : Overlay(act) {
    private lateinit var content: LinearLayout

    @SuppressLint("SetTextI18n")
    override fun create(): View {
        val c = act
        val scroll = ScrollView(c).apply {
            setBackgroundColor(Look.bg)
            isClickable = true
            isFillViewport = true
        }
        content = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(20), act.insetTop + c.dp(12), c.dp(20), act.insetBottom + c.dp(28))
        }
        content.addView(c.text("metro", 14f, Metro.semibold))
        content.addView(c.text(title, 44f, Metro.light))
        if (live != null && Look.mode == Metro.SINGLE) {
            content.addView(c.text("tiles use the accent color while tile colors is set to single accent.", 14f, Metro.regular, Look.fg60))
        }

        var row: LinearLayout? = null
        Metro.PALETTE.forEachIndexed { i, col ->
            if (i % 4 == 0) {
                row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                content.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(10) })
            }
            val sw = Swatch(c, Look.shade(col), col == (current or 0xFF000000.toInt()))
            sw.contentDescription = Metro.NAMES[i]
            sw.setOnClickListener { pick(col) }
            row?.addView(sw, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (i % 4 != 3) rightMargin = c.dp(10)
            })
        }

        content.addView(header(c, "custom"))
        val hexRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = View(c).apply { setBackgroundColor(current) }
        val hex = EditText(c).apply {
            setText(String.format("#%06x", current and 0xFFFFFF))
            setSingleLine()
            textSize = 20f
            typeface = Metro.regular
            setTextColor(Look.fg)
            setHintTextColor(Look.fg30)
            hint = "#ff8800"
            background = flatBox(Look.fg60, c.dp(2))
            setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10))
            filters = arrayOf(InputFilter.LengthFilter(7))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    parseHex(s.toString())?.let { preview.setBackgroundColor(it) }
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        }
        val apply = c.text("apply", 18f, Metro.regular).apply {
            background = flatBox(Look.fg, c.dp(2))
            setPadding(c.dp(18), c.dp(9), c.dp(18), c.dp(9))
            setOnClickListener {
                val v = parseHex(hex.text.toString())
                if (v != null) pick(v) else hex.background = flatBox(0xFFE51400.toInt(), c.dp(2))
            }
        }
        Tilt.attach(apply, 8f)
        hexRow.addView(hex, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        hexRow.addView(preview, LinearLayout.LayoutParams(c.dp(44), c.dp(44)).apply { leftMargin = c.dp(10) })
        hexRow.addView(apply, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = c.dp(10) })
        content.addView(hexRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(10) })

        if (live != null && onLive != null) {
            content.addView(header(c, "live tile"))
            val (rowView, _) = act.toggleRow("live updates", "counts, messages, photos and more on this tile", live) { onLive.invoke(it) }
            content.addView(rowView)
        }
        scroll.addView(content)
        return scroll
    }

    private fun header(c: Context, s: String): TextView = c.text(s, 24f, Metro.semilight, Look.accentShade).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(28) }
    }

    private fun pick(col: Int) {
        onColor(col)
        close()
    }

    override fun enter() {
        content.translationX = act.dp(60).toFloat()
        content.alpha = 0f
        content.ani(360).translationX(0f).alpha(1f).start()
    }

    companion object {
        fun parseHex(s: String): Int? {
            var h = s.trim().removePrefix("#")
            if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
            if (h.length != 6) return null
            return h.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
        }
    }
}

/** Square colour swatch for the picker. */
class Swatch(context: Context, private val color: Int, private val selected: Boolean) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        Tilt.attach(this, 10f)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(c: Canvas) {
        p.style = Paint.Style.FILL
        p.color = color
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        if (selected) {
            val s = dpf(3f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = s
            p.color = Look.fg
            c.drawRect(s / 2, s / 2, width - s / 2, height - s / 2, p)
        }
    }
}

/** The letter grid: tap a coloured letter to jump; unused letters are dimmed. */
class JumpGrid(
    act: MetroActivity,
    private val present: Set<Char>,
    private val onPick: (Char) -> Unit
) : Overlay(act) {
    private val cells = ArrayList<View>()

    override fun create(): View {
        val c = act
        val frame = FrameLayout(c).apply { setBackgroundColor(alpha(Look.bg, 0.94f)) }
        val scroll = ScrollView(c).apply { isFillViewport = true }
        val grid = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(16), act.insetTop + c.dp(24), c.dp(16), act.insetBottom + c.dp(24))
            isClickable = true
            setOnClickListener { close() }
        }
        val letters = listOf('#') + ('a'..'z').toList()
        var row: LinearLayout? = null
        val slots = ((letters.size + 3) / 4) * 4
        for (i in 0 until slots) {
            if (i % 4 == 0) {
                row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    if (i > 0) topMargin = c.dp(8)
                })
            }
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i % 4 != 3) rightMargin = c.dp(8) }
            if (i >= letters.size) {
                row?.addView(Space(c), lp)
                continue
            }
            val ch = letters[i]
            val has = ch in present
            val cell = LetterCell(c, ch, has)
            if (has) cell.setOnClickListener {
                close()
                onPick(ch)
            }
            row?.addView(cell, lp)
            cells.add(cell)
        }
        scroll.addView(grid)
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return frame
    }

    override fun enter() {
        for ((i, v) in cells.withIndex()) {
            v.scaleX = 0.6f
            v.scaleY = 0.6f
            v.alpha = 0f
            v.ani(320).setStartDelay(i * 8L).scaleX(1f).scaleY(1f).alpha(1f).start()
        }
    }
}

class LetterCell(context: Context, private val ch: Char, private val has: Boolean) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = has
        if (has) Tilt.attach(this, 10f)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        p.color = if (has) Look.letterColor(ch) else Look.fg15
        c.drawRect(0f, 0f, w, w, p)
        p.color = if (has) Color.WHITE else Look.fg30
        p.typeface = Metro.light
        p.textSize = w * 0.42f
        c.drawText(ch.toString(), w * 0.1f, w * 0.9f, p)
    }
}
