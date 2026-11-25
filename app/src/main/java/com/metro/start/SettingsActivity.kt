package com.metro.start

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max

/** Settings, in the same flat style: big lowercase title, accent headers, flat toggles. */
class SettingsActivity : MetroActivity() {

    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout
    private lateinit var content: LinearLayout
    private val refreshers = ArrayList<() -> Unit>()
    private var pendingLock = false
    private val asked = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.load(this)
        prefs = Prefs(this)
        setupWindow()
        build()
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (pendingLock) {
            pendingLock = false
            if (Settings.canDrawOverlays(this)) {
                prefs.lock = true
                LockService.sync(this)
            }
        }
        for (r in refreshers) r()
    }

    override fun back() {
        finish()
    }

    // ---------------------------------------------------------------- page

    private fun build() {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body = content
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        onInsets { top, bottom, _ ->
            content.setPadding(dp(20), top + dp(12), dp(20), bottom + dp(32))
        }

        body.addView(text("metro", 14f, Metro.semibold))
        body.addView(text("settings", 48f, Metro.light))

        header("start")
        choice("theme", listOf("dark", "light"), { if (prefs.dark) 0 else 1 }) {
            prefs.dark = it == 0
            recreate()
        }
        choice("tile colors", listOf("multi-color", "single accent", "shuffle daily"), { prefs.mode }) {
            prefs.mode = it
        }
        colorRow()
        choice("icons", listOf("white glyphs", "original icons"), { if (prefs.glyphs) 0 else 1 }) {
            prefs.glyphs = it == 0
        }
        action("start background", {
            if (Prefs.backgroundFile(this).exists()) "photo set · tap to change or remove" else "none · tap to choose a photo"
        }) { backgroundMenu(it) }
        val (navRow, _) = toggleRow("navigation bar", "back, start and search at the bottom of the screen", prefs.navBar) {
            prefs.navBar = it
        }
        body.addView(navRow)

        header("live tiles")
        action("notifications", {
            if (Notifs.enabled(this)) "allowed · counts and latest messages" else "tap to allow · counts and latest messages"
        }) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        permissionRow("calls", "missed calls on the phone tile and call history", Manifest.permission.READ_CALL_LOG)
        permissionRow("photos", "recent pictures on the photos tile", Live.photoPermission)
        permissionRow("people", "contact photos on the people tile", Manifest.permission.READ_CONTACTS)
        permissionRow("calendar", "your next event on the calendar tile", Manifest.permission.READ_CALENDAR)
        permissionRow("weather", "uses your approximate location", Manifest.permission.ACCESS_COARSE_LOCATION)
        choice("temperature", listOf("automatic", "celsius", "fahrenheit"), { prefs.tempUnit }) {
            prefs.tempUnit = it
            Live.invalidateWeather()
            Live.refresh(this)
        }

        header("lock screen")
        val (lockRow, lockToggle) = toggleRow(
            "metro lock screen",
            "large clock and date, swipe up to unlock. needs permission to appear on top.",
            prefs.lock
        ) { on ->
            if (!on) {
                prefs.lock = false
                LockService.sync(this)
            } else if (Settings.canDrawOverlays(this)) {
                prefs.lock = true
                LockService.sync(this)
                askNotifications()
            } else {
                pendingLock = true
                askNotifications()
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
        refreshers.add { if (lockToggle.checked != prefs.lock && !pendingLock) lockToggle.set(prefs.lock, true) }
        body.addView(lockRow)
        action("preview lock screen", { "see how it looks" }) { LockActivity.show(this) }

        header("system")
        action("home app", { "make metro your home screen" }) {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        action("phone settings", { "open android settings" }) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        body.addView(text("metro launcher $version", 14f, Metro.regular, Look.fg60).apply {
            setPadding(0, dp(28), 0, 0)
        })
    }

    // ---------------------------------------------------------------- rows

    private fun header(s: String) {
        body.addView(text(s, 24f, Metro.semilight, Look.accentShade).apply {
            setPadding(0, dp(28), 0, dp(2))
        })
    }

    private fun row(title: String, value: TextView): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
        }
        r.addView(text(title, 20f, Metro.regular))
        r.addView(value)
        Tilt.attach(r, 1.5f)
        body.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return r
    }

    private fun choice(title: String, options: List<String>, current: () -> Int, onPick: (Int) -> Unit) {
        val value = text(options[current().coerceIn(0, options.size - 1)], 15f, Metro.regular, Look.fg60)
        val r = row(title, value)
        refreshers.add { value.text = options[current().coerceIn(0, options.size - 1)] }
        r.setOnClickListener {
            show(MetroMenu(this, options.mapIndexed { i, s ->
                s to {
                    onPick(i)
                    value.text = options[current().coerceIn(0, options.size - 1)]
                }
            }, r))
        }
    }

    private fun action(title: String, sub: () -> String, onClick: (View) -> Unit) {
        val value = text(sub(), 15f, Metro.regular, Look.fg60)
        val r = row(title, value)
        refreshers.add { value.text = sub() }
        r.setOnClickListener { onClick(r) }
    }

    private fun colorRow() {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
        }
        val swatch = View(this).apply { setBackgroundColor(Look.accentShade) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text("accent color", 20f, Metro.regular))
        texts.addView(text(Metro.colorName(prefs.accent), 15f, Metro.regular, Look.fg60))
        r.addView(swatch, LinearLayout.LayoutParams(dp(44), dp(44)))
        r.addView(texts, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = dp(14) })
        Tilt.attach(r, 1.5f)
        r.setOnClickListener {
            show(ColorPicker(this, "accent color", prefs.accent, null, onColor = { c ->
                prefs.accent = c
                recreate()
            }))
        }
        body.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun permissionRow(title: String, what: String, perm: String) {
        action(title, {
            if (Live.granted(this, perm)) "on · $what" else "off · tap to allow · $what"
        }) {
            when {
                Live.granted(this, perm) -> openAppDetails()
                perm in asked && !shouldShowRequestPermissionRationale(perm) -> openAppDetails()
                else -> {
                    asked.add(perm)
                    requestPermissions(arrayOf(perm), 1)
                }
            }
        }
    }

    private fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Live.granted(this, Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        for (r in refreshers) r()
        Live.refresh(this, force = true)
    }

    // ---------------------------------------------------------------- background photo

    private fun backgroundMenu(anchor: View) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("choose photo" to {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(i, 7)
            } catch (e: Exception) {
                Toast.makeText(this, "no photo picker found", Toast.LENGTH_SHORT).show()
            }
        })
        if (Prefs.backgroundFile(this).exists()) items.add("remove photo" to {
            Prefs.backgroundFile(this).delete()
            prefs.bumpLook()
            for (r in refreshers) r()
        })
        show(MetroMenu(this, items, anchor))
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != 7 || resultCode != RESULT_OK || uri == null) return
        val app = applicationContext
        Executors.newSingleThreadExecutor().execute {
            val ok = try {
                val bmp = decode(uri)
                FileOutputStream(Prefs.backgroundFile(app)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (ok) prefs.bumpLook() else Toast.makeText(this, "couldn't use that photo", Toast.LENGTH_SHORT).show()
                for (r in refreshers) r()
            }
        }
    }

    private fun decode(uri: Uri): Bitmap {
        val maxSide = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 1.4f
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                val s = info.size
                val f = maxSide / max(s.width, s.height)
                if (f < 1f) dec.setTargetSize((s.width * f).toInt(), (s.height * f).toInt())
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("decode failed")
    }
}
