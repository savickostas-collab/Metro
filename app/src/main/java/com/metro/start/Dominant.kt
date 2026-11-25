package com.metro.start

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the main colour of an app icon, and makes it tile-ready:
 * saturated enough to look Metro, dark enough for white text.
 */
object Dominant {
    private const val BINS = 36

    fun of(icon: Drawable?): Int {
        if (icon == null) return 0
        return try {
            val n = 48
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val d = (icon.constantState?.newDrawable() ?: icon).mutate()
            d.setBounds(0, 0, n, n)
            d.draw(Canvas(bmp))
            val px = IntArray(n * n)
            bmp.getPixels(px, 0, n, 0, 0, n, n)
            bmp.recycle()
            of(px)
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Pure version on ARGB pixels (unit-tested). Greys, whites, blacks and transparent
     * pixels are ignored; colourful pixels vote for a hue bucket, weighted by how vivid they are.
     * Returns 0 when the icon has no real colour (e.g. a white logo on black).
     */
    fun of(px: IntArray): Int {
        val weight = FloatArray(BINS)
        val r = FloatArray(BINS)
        val g = FloatArray(BINS)
        val b = FloatArray(BINS)
        var counted = 0
        var opaque = 0
        val hsv = FloatArray(3)
        for (p in px) {
            if ((p ushr 24) < 200) continue
            opaque++
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            toHsv(pr, pg, pb, hsv)
            val s = hsv[1]
            val v = hsv[2]
            if (s < 0.28f || v < 0.22f) continue
            val w = s * v
            val bin = ((hsv[0] / 360f) * BINS).toInt().coerceIn(0, BINS - 1)
            weight[bin] += w
            r[bin] += pr * w
            g[bin] += pg * w
            b[bin] += pb * w
            counted++
        }
        if (opaque == 0 || counted < opaque * 0.06f || counted < 12) return 0
        // Neighbouring hue buckets count together, so a gradient isn't split in two.
        var best = -1
        var bestW = 0f
        for (i in 0 until BINS) {
            val w = weight[i] + 0.5f * (weight[(i + 1) % BINS] + weight[(i + BINS - 1) % BINS])
            if (w > bestW) {
                bestW = w
                best = i
            }
        }
        if (best < 0 || weight[best] <= 0f) return 0
        val w = weight[best]
        return tileReady(rgb((r[best] / w).toInt(), (g[best] / w).toInt(), (b[best] / w).toInt()))
    }

    /** Flat and bold like the Metro palette; light colours are darkened for white labels. */
    fun tileReady(c: Int): Int {
        val hsv = FloatArray(3)
        toHsv((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, hsv)
        hsv[1] = max(hsv[1], 0.6f)
        // Yellows and greens read brighter, so they need to come down further.
        val maxV = if (hsv[0] in 40f..170f) 0.80f else 0.92f
        hsv[2] = hsv[2].coerceIn(0.55f, maxV)
        return fromHsv(hsv)
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun toHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val mx = max(rf, max(gf, bf))
        val mn = min(rf, min(gf, bf))
        val d = mx - mn
        var h = when {
            d == 0f -> 0f
            mx == rf -> 60f * (((gf - bf) / d) % 6f)
            mx == gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }
        if (h < 0) h += 360f
        out[0] = h
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    fun fromHsv(hsv: FloatArray): Int {
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return rgb(((r + m) * 255 + 0.5f).toInt(), ((g + m) * 255 + 0.5f).toInt(), ((b + m) * 255 + 0.5f).toInt())
    }
}

/** Which installed apps get a hand-drawn Metro glyph instead of a traced one. */
object GlyphRoles {
    fun find(c: Context, pm: PackageManager): Map<String, Int> {
        val m = HashMap<String, Int>()
        fun put(pkg: String?, res: Int) {
            if (pkg != null && pkg != "android" && pkg != c.packageName) m.putIfAbsent(pkg, res)
        }
        fun resolve(i: Intent): String? = try {
            pm.resolveActivity(i, 0)?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        fun sel(cat: String) = resolve(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, cat))

        try {
            put(c.getSystemService(TelecomManager::class.java)?.defaultDialerPackage, R.drawable.g_phone)
        } catch (e: Exception) {
        }
        put(sel(Intent.CATEGORY_APP_MESSAGING), R.drawable.g_messages)
        put(sel(Intent.CATEGORY_APP_CONTACTS), R.drawable.g_people)
        put(resolve(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)), R.drawable.g_camera)
        put(sel(Intent.CATEGORY_APP_BROWSER), R.drawable.g_browser)
        put(sel(Intent.CATEGORY_APP_EMAIL), R.drawable.g_mail)
        put(sel(Intent.CATEGORY_APP_MAPS), R.drawable.g_maps)
        put(sel(Intent.CATEGORY_APP_CALENDAR), R.drawable.g_calendar)
        put(sel(Intent.CATEGORY_APP_GALLERY), R.drawable.g_photos)
        put(sel(Intent.CATEGORY_APP_CALCULATOR), R.drawable.g_calculator)
        put(sel(Intent.CATEGORY_APP_FILES), R.drawable.g_files)
        put(resolve(Intent(Settings.ACTION_SETTINGS)), R.drawable.g_settings)

        val known = mapOf(
            R.drawable.g_phone to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            R.drawable.g_messages to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            R.drawable.g_people to listOf("com.google.android.contacts", "com.samsung.android.app.contacts", "com.android.contacts"),
            R.drawable.g_camera to listOf("com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.android.camera", "com.android.camera2"),
            R.drawable.g_clock to listOf(
                "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
                "com.oneplus.deskclock", "com.coloros.alarmclock", "com.miui.clock"
            ),
            R.drawable.g_calendar to listOf("com.google.android.calendar", "com.samsung.android.calendar", "com.android.calendar"),
            R.drawable.g_calculator to listOf("com.google.android.calculator", "com.sec.android.app.popupcalculator", "com.android.calculator2", "com.miui.calculator"),
            R.drawable.g_files to listOf(
                "com.google.android.apps.nbu.files", "com.google.android.documentsui", "com.android.documentsui",
                "com.sec.android.app.myfiles", "com.mi.android.globalFileexplorer"
            ),
            R.drawable.g_store to listOf("com.android.vending", "com.sec.android.app.samsungapps"),
            R.drawable.g_photos to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d", "com.miui.gallery", "com.android.gallery3d"),
            R.drawable.g_mail to listOf("com.google.android.gm", "com.samsung.android.email.provider"),
            R.drawable.g_settings to listOf("com.android.settings")
        )
        for ((res, pkgs) in known) for (p in pkgs) put(p, res)
        return m
    }
}
