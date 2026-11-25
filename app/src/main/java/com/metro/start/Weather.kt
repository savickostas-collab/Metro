package com.metro.start

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** Flat weather glyphs, drawn into any square. */
object WeatherArt {
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val path = Path()

    fun draw(c: Canvas, left: Float, top: Float, size: Float, code: Int, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val u = size / 100f
        fun X(v: Float) = left + v * u
        fun Y(v: Float) = top + v * u
        val layer = c.saveLayer(left - 2, top - 2, left + size + 2, top + size + 2, null)

        fun sun(cx: Float, cy: Float, r: Float) {
            c.drawCircle(X(cx), Y(cy), r * u, p)
            p.strokeWidth = r * 0.28f * u
            p.strokeCap = Paint.Cap.SQUARE
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val r1 = r * 1.4f
                val r2 = r * 1.85f
                c.drawLine(
                    X(cx + (r1 * Math.cos(a)).toFloat()), Y(cy + (r1 * Math.sin(a)).toFloat()),
                    X(cx + (r2 * Math.cos(a)).toFloat()), Y(cy + (r2 * Math.sin(a)).toFloat()), p
                )
            }
        }

        fun cloud(ox: Float, oy: Float, s: Float, cut: Boolean) {
            val circles = arrayOf(floatArrayOf(30f, 58f, 16f), floatArrayOf(52f, 46f, 22f), floatArrayOf(72f, 58f, 15f))
            if (cut) {
                // Clear a margin around the cloud so the sun behind it reads as separate.
                val m = 5f
                for (k in circles) c.drawCircle(X(ox + k[0] * s), Y(oy + k[1] * s), (k[2] + m) * s * u, clear)
                c.drawRect(X(ox + (14f - m) * s), Y(oy + 58f * s), X(ox + (87f + m) * s), Y(oy + (74f + m) * s), clear)
            }
            for (k in circles) c.drawCircle(X(ox + k[0] * s), Y(oy + k[1] * s), k[2] * s * u, p)
            c.drawRect(X(ox + 14f * s), Y(oy + 58f * s), X(ox + 87f * s), Y(oy + 74f * s), p)
        }

        fun drops(kind: Int) {
            p.strokeWidth = 5f * u
            p.strokeCap = Paint.Cap.ROUND
            for (i in 0..2) {
                val x = 32f + i * 18f
                when (kind) {
                    0 -> c.drawLine(X(x), Y(70f), X(x - 6f), Y(86f), p)
                    1 -> c.drawCircle(X(x - 3f), Y(80f), 4.5f * u, p)
                }
            }
        }

        when (code) {
            0, 1 -> sun(50f, 50f, if (code == 0) 20f else 18f)
            2 -> {
                sun(38f, 36f, 14f)
                cloud(6f, 14f, 0.92f, true)
            }
            3 -> cloud(0f, -6f, 1f, false)
            45, 48 -> {
                p.strokeWidth = 6f * u
                p.strokeCap = Paint.Cap.ROUND
                for (i in 0..3) c.drawLine(X(18f + (i % 2) * 8f), Y(30f + i * 14f), X(82f - ((i + 1) % 2) * 8f), Y(30f + i * 14f), p)
            }
            in 71..77, 85, 86 -> {
                cloud(0f, -18f, 1f, false)
                drops(1)
            }
            in 95..99 -> {
                cloud(0f, -18f, 1f, false)
                path.reset()
                path.moveTo(X(52f), Y(60f)); path.lineTo(X(38f), Y(80f)); path.lineTo(X(50f), Y(80f))
                path.lineTo(X(44f), Y(96f)); path.lineTo(X(64f), Y(72f)); path.lineTo(X(52f), Y(72f)); path.close()
                c.drawPath(path, p)
            }
            in 51..67, in 80..82 -> {
                cloud(0f, -18f, 1f, false)
                drops(0)
            }
            else -> cloud(0f, -6f, 1f, false)
        }
        c.restoreToCount(layer)
    }
}

class WeatherIcon(context: Context) : View(context) {
    var code = 0
        set(v) {
            field = v
            invalidate()
        }
    var color = Look.fg

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        WeatherArt.draw(c, (width - s) / 2f, (height - s) / 2f, s, code, color)
    }
}

/** Metro weather: today, the next 24 hours and the week. */
class WeatherActivity : PivotActivity() {
    private lateinit var nowPage: FrameLayout
    private lateinit var nowContent: LinearLayout
    private lateinit var hourRows: Rows
    private lateinit var dayRows: Rows
    private lateinit var hourList: RecyclerView
    private lateinit var dayList: RecyclerView
    private var bottomPad = 0
    private val listener: () -> Unit = { render() }
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Metro.fonts(this)
        nowPage = FrameLayout(this)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        nowContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(nowContent)
        nowPage.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        hourRows = Rows({ 0 }, { p, _ -> row(p.context) }, { v, item -> bindHour(v, item as Hour) })
        dayRows = Rows({ 0 }, { p, _ -> row(p.context) }, { v, item -> bindDay(v, item as Day) })
        hourList = metroList(hourRows)
        dayList = metroList(dayRows)
        setupPivot("metro weather", listOf("today" to nowPage, "hourly" to hourList, "week" to dayList))
    }

    override fun onBottomInset(bottom: Int) {
        bottomPad = bottom
        hourList.setPadding(0, 0, 0, bottom + dp(12))
        dayList.setPadding(0, 0, 0, bottom + dp(12))
        nowContent.setPadding(dp(20), 0, dp(20), bottom + dp(24))
    }

    override fun onResume() {
        super.onResume()
        Live.listen(listener)
        val hasCity = Prefs(this).city != null
        if (!hasCity && !Live.granted(this, Manifest.permission.ACCESS_COARSE_LOCATION) && !asked) {
            asked = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 3)
        }
        Live.refresh(this)
        render()
    }

    override fun onPause() {
        super.onPause()
        Live.unlisten(listener)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Live.invalidateWeather()
        Live.refresh(this)
        render()
    }

    private fun refresh() {
        Live.invalidateWeather()
        Live.refresh(this)
    }

    private fun render() {
        nowContent.removeAllViews()
        val w = Live.weather
        val hasCity = Prefs(this).city != null
        val located = hasCity || Live.granted(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (w == null) {
            val e = when {
                !located -> emptyState(
                    "where are you?",
                    "allow approximate location, or pick a city. either way the weather tile comes alive.",
                    "choose a city"
                ) { pickCity() }.also { box ->
                    box.addView(metroButton("use my location") {
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 3)
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
                }
                Live.weatherError -> emptyState("no forecast", "couldn't reach the weather service. check your connection.", "try again") { refresh() }
                else -> emptyState("getting the forecast…", "this only takes a moment.", "choose a city instead") { pickCity() }
            }
            e.setPadding(0, 0, 0, 0)
            nowContent.addView(e)
            hourRows.items = emptyList()
            dayRows.items = emptyList()
            return
        }

        nowContent.addView(text(w.place.ifBlank { "your location" }, 24f, Metro.semilight, Look.accentShade).apply {
            isClickable = true
            setOnClickListener { pickCity() }
        })
        val big = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        big.addView(text("${w.temp}°", 96f, Metro.light).apply { includeFontPadding = false })
        big.addView(WeatherIcon(this).apply { code = w.code }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { leftMargin = dp(12) })
        nowContent.addView(big)
        nowContent.addView(text(w.desc, 28f, Metro.light))

        nowContent.addView(text("details", 24f, Metro.semilight, Look.accentShade).apply { setPadding(0, dp(28), 0, dp(4)) })
        detail("feels like", "${w.feels}°")
        detail("high / low", "${w.hi}° / ${w.lo}°")
        w.days.firstOrNull()?.let { if (it.rain > 0) detail("chance of rain", "${it.rain}%") }
        detail("humidity", "${w.humidity}%")
        detail("wind", "${w.wind} ${w.windUnit}")
        if (w.sunrise.isNotBlank()) detail("sunrise", w.sunrise)
        if (w.sunset.isNotBlank()) detail("sunset", w.sunset)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(metroButton("refresh") { refresh() })
        bar.addView(metroButton("change city") { pickCity() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
        nowContent.addView(bar, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(24) })
        nowContent.addView(text("updated " + When.text(this, w.updated), 14f, Metro.regular, Look.fg60).apply { setPadding(0, dp(14), 0, 0) })

        // Open the phone's own weather app too, if there is one.
        Apps.list.firstOrNull { it.kind == Kind.WEATHER && it.pkg != packageName }?.let { other ->
            nowContent.addView(text("open ${other.name}", 15f, Metro.regular, Look.accentShade).apply {
                setPadding(0, dp(10), 0, 0)
                isClickable = true
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(other.component))
                    } catch (e: Exception) {
                    }
                }
            })
        }

        hourRows.items = w.hours
        dayRows.items = w.days
    }

    private fun detail(label: String, value: String) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        r.addView(text(label, 18f, Metro.regular, Look.fg60), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(text(value, 18f, Metro.regular))
        nowContent.addView(r)
    }

    /** Row: label (+ sub) on the left, icon, then temperatures on the right. */
    private fun row(c: Context): View {
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        left.addView(c.text("", 22f, Metro.light))
        left.addView(c.text("", 14f, Metro.regular, Look.fg60))
        r.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(WeatherIcon(c), LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(16) })
        r.addView(c.text("", 22f, Metro.light).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(56), WRAP_CONTENT))
        r.addView(c.text("", 22f, Metro.light, Look.fg60).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(56), WRAP_CONTENT))
        return r
    }

    private fun fill(v: View, label: String, sub: String, code: Int, a: String, b: String) {
        val r = v as LinearLayout
        val left = r.getChildAt(0) as LinearLayout
        (left.getChildAt(0) as TextView).text = label
        (left.getChildAt(1) as TextView).apply {
            text = sub
            visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
        }
        (r.getChildAt(1) as WeatherIcon).code = code
        (r.getChildAt(2) as TextView).text = a
        (r.getChildAt(3) as TextView).text = b
    }

    private fun bindHour(v: View, h: Hour) =
        fill(v, h.label, Live.describe(h.code), h.code, "${h.temp}°", if (h.rain > 0) "${h.rain}%" else "")

    private fun bindDay(v: View, d: Day) =
        fill(v, d.label, Live.describe(d.code) + if (d.rain >= 20) " · ${d.rain}% rain" else "", d.code, "${d.hi}°", "${d.lo}°")

    private fun pickCity() {
        show(CityPicker(this) {
            Live.invalidateWeather()
            Live.refresh(this)
            render()
        })
    }
}

/** Search for a city (or go back to using location). */
class CityPicker(act: MetroActivity, private val done: () -> Unit) : Overlay(act) {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var results: LinearLayout
    private lateinit var field: EditText
    private var query = ""

    override fun create(): View {
        val c = act
        val scroll = ScrollView(c).apply {
            setBackgroundColor(Look.bg)
            isClickable = true
            isFillViewport = true
        }
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(20), act.insetTop + c.dp(12), c.dp(20), act.insetBottom + c.dp(28))
        }
        box.addView(c.text("metro weather", 14f, Metro.semibold))
        box.addView(c.text("city", 44f, Metro.light))
        field = EditText(c).apply {
            hint = "search for a city"
            setSingleLine()
            textSize = 20f
            typeface = Metro.regular
            setTextColor(Look.fg)
            setHintTextColor(Look.fg30)
            background = flatBox(Look.fg60, c.dp(2))
            setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s.toString().trim()
                    main.removeCallbacksAndMessages(null)
                    main.postDelayed({ search(query) }, 350)
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        }
        box.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(12) })
        results = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(results, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(12) })
        showLocationOption()
        scroll.addView(box)
        return scroll
    }

    override fun enter() {
        field.requestFocus()
        field.postDelayed({
            act.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    override fun exit(done: () -> Unit) {
        act.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        super.exit(done)
    }

    private fun showLocationOption() {
        results.removeAllViews()
        addRow("use my location", "follow where the phone is") {
            Prefs(act).city = null
            if (!Live.granted(act, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                act.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 3)
            }
            finish()
        }
    }

    private fun search(q: String) {
        if (q.length < 2) {
            showLocationOption()
            return
        }
        exec.execute {
            val list = try {
                Live.searchCities(q)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (q != query) return@post
                showLocationOption()
                when {
                    list == null -> results.addView(act.text("couldn't search right now.", 15f, Metro.regular, Look.fg60))
                    list.isEmpty() -> results.addView(act.text("no cities found.", 15f, Metro.regular, Look.fg60))
                    else -> for (city in list) addRow(city.name, city.detail) {
                        Prefs(act).city = city.json()
                        finish()
                    }
                }
            }
        }
    }

    private fun addRow(title: String, sub: String, onClick: () -> Unit) {
        val c = act
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, c.dp(10), 0, c.dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }
        r.addView(c.text(title, 22f, Metro.light))
        if (sub.isNotBlank()) r.addView(c.text(sub, 14f, Metro.regular, Look.fg60))
        Tilt.attach(r, 2f)
        results.addView(r)
    }

    private fun finish() {
        close()
        done()
    }
}
