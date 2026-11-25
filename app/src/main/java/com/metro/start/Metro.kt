package com.metro.start

import android.animation.TimeInterpolator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.animation.PathInterpolator
import android.widget.TextView
import java.io.File

/** Design tokens straight from the brief. */
object Metro {
    val PALETTE = intArrayOf(
        0xFF0050EF.toInt(), // cobalt
        0xFFD80073.toInt(), // magenta
        0xFFA4C400.toInt(), // lime
        0xFFFA6800.toInt(), // orange
        0xFF00ABA9.toInt(), // teal
        0xFFE51400.toInt(), // crimson
        0xFF6A00FF.toInt(), // purple
        0xFF008A00.toInt(), // emerald
        0xFFF0A30A.toInt(), // amber
        0xFFF472D0.toInt(), // pink
        0xFF647687.toInt(), // steel
        0xFF76608A.toInt()  // mauve
    )
    val NAMES = arrayOf(
        "cobalt", "magenta", "lime", "orange", "teal", "crimson",
        "purple", "emerald", "amber", "pink", "steel", "mauve"
    )

    /** Fast in, slow out: cubic-bezier(0.1, 0.9, 0.2, 1). */
    val EASE: TimeInterpolator = PathInterpolator(0.1f, 0.9f, 0.2f, 1f)

    const val MULTI = 0
    const val SINGLE = 1
    const val SHUFFLE = 2

    lateinit var light: Typeface
        private set
    lateinit var semilight: Typeface
        private set
    lateinit var regular: Typeface
        private set
    lateinit var semibold: Typeface
        private set

    @Volatile
    var fontsReady = false
        private set

    @Synchronized
    fun fonts(c: Context) {
        if (fontsReady) return
        light = font(c, 300, "sans-serif-light")
        semilight = font(c, 360, "sans-serif-light")
        regular = font(c, 400, "sans-serif")
        semibold = font(c, 600, "sans-serif-medium")
        fontsReady = true
    }

    private fun font(c: Context, weight: Int, fallback: String): Typeface = try {
        Typeface.Builder(c.assets, "fonts/OpenSans.ttf")
            .setFontVariationSettings("'wght' $weight, 'wdth' 100")
            .setFallback(fallback)
            .build()
    } catch (e: Exception) {
        Typeface.create(fallback, Typeface.NORMAL)
    }

    fun colorName(c: Int): String {
        val i = PALETTE.indexOf(c or 0xFF000000.toInt())
        return if (i >= 0) NAMES[i] else String.format("#%06x", c and 0xFFFFFF)
    }
}

/** The current theme, read from preferences. */
object Look {
    var dark = true
    var accent = Metro.PALETTE[0]
    var mode = Metro.MULTI
    var glyphs = true
    var navBar = true

    val bg: Int get() = if (dark) Color.BLACK else Color.WHITE
    val fg: Int get() = if (dark) Color.WHITE else Color.BLACK
    val fg60: Int get() = alpha(fg, 0.6f)
    val fg30: Int get() = alpha(fg, 0.3f)
    val fg15: Int get() = alpha(fg, 0.15f)
    val accentShade: Int get() = shade(accent)

    /** Dark theme keeps full saturation; light theme is about 10% darker. */
    fun shade(c: Int): Int = if (dark) c else scale(c, 0.9f)

    fun tileColor(t: Tile): Int = shade(if (mode == Metro.SINGLE) accent else t.color)

    /** App-list letter squares: one accent in single mode, a mosaic otherwise. */
    fun letterColor(ch: Char): Int {
        if (mode == Metro.SINGLE) return accentShade
        val i = if (ch in 'a'..'z') ch - 'a' else 5
        return shade(Metro.PALETTE[(i * 5) % Metro.PALETTE.size])
    }

    fun load(c: Context) {
        Metro.fonts(c)
        val p = Prefs(c)
        dark = p.dark
        accent = p.accent
        mode = p.mode
        glyphs = p.glyphs
        navBar = p.navBar
    }
}

class Prefs(c: Context) {
    private val sp: SharedPreferences =
        c.applicationContext.getSharedPreferences("metro", Context.MODE_PRIVATE)

    /** Bumped whenever something that changes the look is saved. */
    val rev: Int get() = sp.getInt("rev", 0)

    private fun look(f: SharedPreferences.Editor.() -> Unit) {
        val e = sp.edit()
        e.f()
        e.putInt("rev", rev + 1)
        e.apply()
    }

    var dark: Boolean
        get() = sp.getBoolean("dark", true)
        set(v) = look { putBoolean("dark", v) }
    var accent: Int
        get() = sp.getInt("accent", Metro.PALETTE[0])
        set(v) = look { putInt("accent", v) }
    var mode: Int
        get() = sp.getInt("mode", Metro.MULTI)
        set(v) = look { putInt("mode", v); putInt("shuffleDay", -1) }
    var glyphs: Boolean
        get() = sp.getBoolean("glyphs", true)
        set(v) = look { putBoolean("glyphs", v) }
    var navBar: Boolean
        get() = sp.getBoolean("nav", true)
        set(v) = look { putBoolean("nav", v) }

    var lock: Boolean
        get() = sp.getBoolean("lock", false)
        set(v) = sp.edit().putBoolean("lock", v).apply()
    /** 0 = automatic, 1 = celsius, 2 = fahrenheit */
    var tempUnit: Int
        get() = sp.getInt("temp", 0)
        set(v) = sp.edit().putInt("temp", v).apply()
    var cursor: Int
        get() = sp.getInt("cursor", 0)
        set(v) = sp.edit().putInt("cursor", v).apply()
    var shuffleDay: Int
        get() = sp.getInt("shuffleDay", -1)
        set(v) = sp.edit().putInt("shuffleDay", v).apply()
    var tiles: String?
        get() = sp.getString("tiles", null)
        set(v) = sp.edit().putString("tiles", v).apply()
    /** Chosen weather city as JSON {name, lat, lon}, or null to use location. */
    var city: String?
        get() = sp.getString("city", null)
        set(v) = sp.edit().putString("city", v).apply()
    /** Missed calls older than this have been seen in the Metro phone app. */
    var phoneSeen: Long
        get() = sp.getLong("phoneSeen", 0L)
        set(v) = sp.edit().putLong("phoneSeen", v).apply()
    var recentTracks: String?
        get() = sp.getString("recentTracks", null)
        set(v) = sp.edit().putString("recentTracks", v).apply()
    /** Version of the built-in Metro apps last added to start. */
    var builtins: Int
        get() = sp.getInt("builtins", 0)
        set(v) = sp.edit().putInt("builtins", v).apply()
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    fun bumpLook() = look { }

    companion object {
        fun backgroundFile(c: Context) = File(c.filesDir, "start_background.jpg")
    }
}

// ------------------------------------------------------------------ helpers

fun alpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a * 255).toInt() shl 24)

fun scale(c: Int, f: Float): Int =
    Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun View.dp(v: Int): Int = context.dp(v)
fun View.dpf(v: Float): Float = v * resources.displayMetrics.density

/** Every animation in the launcher goes through here: one curve, no leftover delays. */
fun View.ani(ms: Long): ViewPropertyAnimator =
    animate().setStartDelay(0).setDuration(ms).setInterpolator(Metro.EASE)

fun Context.text(s: String, sp: Float, tf: Typeface, color: Int = Look.fg): TextView =
    TextView(this).apply {
        text = s
        textSize = sp
        typeface = tf
        setTextColor(color)
    }

/** Flat, square-cornered box with a thin outline. */
fun flatBox(stroke: Int, strokeWidth: Int, fill: Int = Color.TRANSPARENT) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = 0f
    setColor(fill)
    setStroke(strokeWidth, stroke)
}
