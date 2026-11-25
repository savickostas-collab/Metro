package com.metro.start

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * First-run page: live tiles need permissions, so ask for them in one place,
 * explaining what each one brings to the start screen.
 */
class LiveSetup(private val start: StartActivity) : Overlay(start) {
    private lateinit var rows: LinearLayout
    private val asked = HashSet<String>()

    private class Item(val title: String, val what: String, val perms: List<String>, val special: Int = 0)

    private val items = listOf(
        Item("notifications & music", "unread counts, latest messages, and what's playing on spotify", emptyList(), special = 1),
        Item("phone", "missed calls on the phone tile, your call history and contacts",
            listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)),
        Item("photos", "your latest pictures on the photos tile", listOf(Live.photoPermission)),
        Item("people & calendar", "faces on the people tile, your next event on calendar",
            listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALENDAR)),
        Item("weather", "temperature and forecast for where you are (or pick a city in weather)",
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION))
    )

    override fun create(): View {
        val c = start
        start.setupPage = this
        val scroll = ScrollView(c).apply {
            setBackgroundColor(Look.bg)
            isClickable = true
            isFillViewport = true
        }
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(20), start.insetTop + c.dp(12), c.dp(20), start.insetBottom + c.dp(28))
        }
        box.addView(c.text("metro", 14f, Metro.semibold))
        box.addView(c.text("live tiles", 44f, Metro.light))
        box.addView(c.text("tiles come alive with what's on your phone. turn on whatever you like; you can change this later in metro settings.",
            15f, Metro.regular, Look.fg60).apply { setPadding(0, c.dp(4), 0, c.dp(12)) })
        rows = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(rows)
        box.addView(c.metroButton("done") { close() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = c.dp(24)
        })
        scroll.addView(box)
        refresh()
        return scroll
    }

    private fun on(i: Item): Boolean = when (i.special) {
        1 -> Notifs.enabled(start)
        else -> i.perms.all { Live.granted(start, it) }
    }

    fun refresh() {
        if (!::rows.isInitialized) return
        rows.removeAllViews()
        val c = start
        for (i in items) {
            val enabled = on(i)
            val r = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, c.dp(12), 0, c.dp(12))
                isClickable = !enabled
                setOnClickListener { turnOn(i) }
            }
            r.addView(c.text(i.title, 22f, Metro.light))
            r.addView(c.text(i.what, 14f, Metro.regular, Look.fg60))
            r.addView(c.text(if (enabled) "on" else "tap to turn on", 15f, Metro.semibold, if (enabled) Look.accentShade else Look.fg))
            if (i.special == 1 && !enabled && Build.VERSION.SDK_INT >= 33) {
                r.addView(c.text("switch greyed out? open settings › apps › metro › ⋮ › allow restricted settings, then try again.",
                    13f, Metro.regular, Look.fg60).apply { setPadding(0, c.dp(4), 0, 0) })
            }
            if (!enabled) Tilt.attach(r, 1.5f)
            rows.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun turnOn(i: Item) {
        if (i.special == 1) {
            try {
                start.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                start.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            return
        }
        val missing = i.perms.filter { !Live.granted(start, it) }
        // Asked before and Android won't show the prompt again: send them to app settings.
        if (missing.any { it in asked && !start.shouldShowRequestPermissionRationale(it) }) {
            start.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${start.packageName}")))
            return
        }
        asked.addAll(missing)
        start.requestPermissions(missing.distinct().toTypedArray(), 9)
    }

    override fun exit(done: () -> Unit) {
        Prefs(start).onboarded = true
        start.setupPage = null
        Live.refresh(start, force = true)
        Media.attach(start)
        super.exit(done)
    }
}
