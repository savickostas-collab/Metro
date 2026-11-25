package com.metro.start

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** What a tile knows how to show when it is live. */
enum class Kind { APP, MESSAGES, PHOTOS, PEOPLE, CALENDAR, WEATHER, SETTINGS, PHONE, MUSIC }

class AppInfo(
    val key: String,
    val label: String,
    val pkg: String,
    val cls: String,
    val system: Boolean,
    val icon: Drawable?,
    val glyph: Bitmap,
    val kind: Kind,
    /** Main colour of the app's icon, ready for a tile; 0 if the icon has none. */
    val dominant: Int = 0
) {
    val name: String = label.lowercase(Locale.getDefault())
    val letter: Char = letterOf(name)
    val component: ComponentName get() = ComponentName(pkg, cls)

    companion object {
        fun letterOf(s: String): Char {
            val ch = s.trim().firstOrNull() ?: return '#'
            val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                .firstOrNull()?.lowercaseChar() ?: return '#'
            return if (base in 'a'..'z') base else '#'
        }
    }
}

object Apps {
    @Volatile
    var list: List<AppInfo> = emptyList()
        private set
    val byKey = HashMap<String, AppInfo>()

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val glyphCache = ConcurrentHashMap<String, Bitmap>()
    private val colorCache = ConcurrentHashMap<String, Int>()

    fun load(c: Context, done: () -> Unit) {
        val app = c.applicationContext
        Metro.fonts(app)
        exec.execute {
            val l = try {
                query(app)
            } catch (e: Exception) {
                list
            }
            main.post {
                list = l
                byKey.clear()
                for (a in l) byKey[a.key] = a
                done()
            }
        }
    }

    private fun query(c: Context): List<AppInfo> {
        val pm = c.packageManager
        val roles = roles(c, pm)
        val glyphRoles = GlyphRoles.find(c, pm)
        val out = ArrayList<AppInfo>()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == c.packageName) continue
            val key = "${ai.packageName}/${ai.name}"
            val label = ri.loadLabel(pm).toString().trim().ifEmpty { ai.packageName }
            val icon = try {
                ri.loadIcon(pm)
            } catch (e: Exception) {
                null
            }
            val updated = try {
                pm.getPackageInfo(ai.packageName, 0).lastUpdateTime
            } catch (e: Exception) {
                0L
            }
            val gk = "$key@$updated"
            val drawn = glyphRoles[ai.packageName]
            val glyph = if (drawn != null) Glyphs.vector(c, drawn)
            else glyphCache[gk] ?: Glyphs.fromIcon(icon, label).also { glyphCache[gk] = it }
            val dominant = colorCache[gk] ?: Dominant.of(icon).also { colorCache[gk] = it }
            val system = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val kind = roles[ai.packageName]
                ?: if (ai.packageName.contains("weather", ignoreCase = true)) Kind.WEATHER else Kind.APP
            out.add(AppInfo(key, label, ai.packageName, ai.name, system, icon, glyph, kind, dominant))
        }
        fun builtin(key: String, label: String, cls: Class<*>, glyph: Bitmap, kind: Kind, color: Int) =
            out.add(AppInfo(key, label, c.packageName, cls.name, true, null, glyph, kind, color))
        // Metro's own apps have fixed colours in icon mode.
        builtin(Tile.SETTINGS, "metro settings", SettingsActivity::class.java, Glyphs.vector(c, R.drawable.g_settings), Kind.SETTINGS, 0xFF647687.toInt())
        builtin(Tile.PHONE, "phone", PhoneActivity::class.java, Glyphs.vector(c, R.drawable.g_phone), Kind.PHONE, 0xFF008A00.toInt())
        builtin(Tile.MUSIC, "music", MusicActivity::class.java, Glyphs.vector(c, R.drawable.g_music), Kind.MUSIC, 0xFFD80073.toInt())
        builtin(Tile.WEATHER, "weather", WeatherActivity::class.java, Glyphs.weather(), Kind.WEATHER, 0xFF0050EF.toInt())
        val collator = Collator.getInstance()
        out.sortWith { a, b ->
            when {
                a.letter == b.letter -> collator.compare(a.name, b.name)
                a.letter == '#' -> -1
                b.letter == '#' -> 1
                else -> a.letter.compareTo(b.letter)
            }
        }
        return out
    }

    /** Finds the phone's messaging, photos, people and calendar apps. */
    private fun roles(c: Context, pm: PackageManager): Map<String, Kind> {
        val m = HashMap<String, Kind>()
        fun category(cat: String, k: Kind) {
            val ri = pm.resolveActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, cat), 0)
            val p = ri?.activityInfo?.packageName ?: return
            if (p != "android") m.putIfAbsent(p, k)
        }
        try {
            Telephony.Sms.getDefaultSmsPackage(c)?.let { m[it] = Kind.MESSAGES }
        } catch (e: Exception) {
        }
        category(Intent.CATEGORY_APP_MESSAGING, Kind.MESSAGES)
        category(Intent.CATEGORY_APP_GALLERY, Kind.PHOTOS)
        category(Intent.CATEGORY_APP_CONTACTS, Kind.PEOPLE)
        category(Intent.CATEGORY_APP_CALENDAR, Kind.CALENDAR)
        val known = mapOf(
            Kind.MESSAGES to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            Kind.PHOTOS to listOf(
                "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.miui.gallery",
                "com.oneplus.gallery", "com.coloros.gallery3d", "com.android.gallery3d", "com.vivo.gallery"
            ),
            Kind.PEOPLE to listOf("com.google.android.contacts", "com.samsung.android.app.contacts", "com.android.contacts"),
            Kind.CALENDAR to listOf("com.google.android.calendar", "com.samsung.android.calendar", "com.android.calendar")
        )
        for ((k, pkgs) in known) for (p in pkgs) m.putIfAbsent(p, k)
        return m
    }
}

/** Turns any app icon into a plain white glyph, Windows Phone style. */
object Glyphs {
    const val SIZE = 144

    fun fromIcon(icon: Drawable?, label: String): Bitmap {
        if (icon != null) render(icon)?.let { return it }
        return letter(label)
    }

    private fun render(src: Drawable): Bitmap? {
        val d = (src.constantState?.newDrawable() ?: src).mutate()
        var layer: Drawable? = d
        var scale = 1f
        if (d is AdaptiveIconDrawable) {
            // Adaptive layers are 108dp with a 72dp visible area, so draw them 1.5x larger.
            layer = if (Build.VERSION.SDK_INT >= 33 && d.monochrome != null) d.monochrome else d.foreground
            scale = 1.5f
        }
        val l = layer ?: return null
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val s = (SIZE * scale).toInt()
        val o = (SIZE - s) / 2
        try {
            l.setBounds(o, o, o + s, o + s)
            l.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            l.draw(Canvas(bmp))
        } catch (e: Exception) {
            return null
        }
        // Icons drawn with semi-transparent layers trace as grey ghosts. Snap alpha to
        // solid or clear, keeping a short ramp so edges stay smooth.
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        for (k in px.indices) {
            val a = px[k] ushr 24
            px[k] = (crisp(a) shl 24) or 0xFFFFFF
        }
        bmp.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        var on = 0
        var n = 0
        var i = 0
        while (i < px.size) {
            n++
            if ((px[i] ushr 24) > 128) on++
            i += 3
        }
        val coverage = on.toFloat() / n
        if (coverage < 0.01f || coverage > 0.5f) return null
        return bmp
    }

    /** Alpha 0..110 -> clear, 110..170 -> ramp, 170.. -> solid. */
    fun crisp(a: Int): Int = ((a - 110) * 255 / 60).coerceIn(0, 255)

    /** Fallback for apps without a usable glyph: the first letter in Metro light. */
    fun letter(label: String): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = SIZE * 0.6f
            typeface = if (Metro.fontsReady) Metro.light else Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val ch = label.trim().firstOrNull()?.lowercase() ?: "?"
        val fm = p.fontMetrics
        Canvas(bmp).drawText(ch, SIZE / 2f, SIZE / 2f - (fm.ascent + fm.descent) / 2f, p)
        return bmp
    }

    private fun canvas(): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp)
    }

    /** A hand-drawn Metro glyph (res/drawable/g_*.xml, generated by tools/glyphs.py). */
    fun vector(c: Context, res: Int): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val d = androidx.core.content.ContextCompat.getDrawable(c, res) ?: return letter("?")
        // Same visual size as the gear and weather glyphs: the 48-unit canvas fills the bitmap.
        val inset = (SIZE * 0.08f).toInt()
        d.setBounds(inset, inset, SIZE - inset, SIZE - inset)
        d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        d.draw(Canvas(bmp))
        return bmp
    }

    /** Sun behind a cloud. */
    fun weather(): Bitmap {
        val (bmp, cv) = canvas()
        WeatherArt.draw(cv, 18f, 18f, SIZE - 36f, 2, Color.WHITE)
        return bmp
    }
}
