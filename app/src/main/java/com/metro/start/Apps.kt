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
enum class Kind { APP, MESSAGES, PHOTOS, PEOPLE, CALENDAR, WEATHER, SETTINGS }

class AppInfo(
    val key: String,
    val label: String,
    val pkg: String,
    val cls: String,
    val system: Boolean,
    val icon: Drawable?,
    val glyph: Bitmap,
    val kind: Kind
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
            val glyph = glyphCache[gk] ?: Glyphs.fromIcon(icon, label).also { glyphCache[gk] = it }
            val system = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val kind = roles[ai.packageName]
                ?: if (ai.packageName.contains("weather", ignoreCase = true)) Kind.WEATHER else Kind.APP
            out.add(AppInfo(key, label, ai.packageName, ai.name, system, icon, glyph, kind))
        }
        out.add(
            AppInfo(
                Tile.SETTINGS, "metro settings", c.packageName, SettingsActivity::class.java.name,
                true, null, Glyphs.gear(), Kind.SETTINGS
            )
        )
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
        // A good glyph is a shape, not a filled square: reject full-bleed artwork.
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
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

    fun gear(): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val c = SIZE / 2f
        for (i in 0 until 8) {
            cv.save()
            cv.rotate(i * 45f, c, c)
            cv.drawRect(c - 8f, c - 44f, c + 8f, c - 28f, p)
            cv.restore()
        }
        cv.drawCircle(c, c, 32f, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        cv.drawCircle(c, c, 13f, p)
        return bmp
    }
}
