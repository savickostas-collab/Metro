package com.metro.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class MainActivity : Activity(), TileView.Host {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private var dark = true
    private var accent = 0
    private var bg = Color.BLACK
    private var fg = Color.WHITE
    private var dim = Color.GRAY
    private lateinit var light: Typeface

    private lateinit var root: FrameLayout
    private lateinit var startPage: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var grid: TileGridLayout
    private lateinit var appPage: LinearLayout
    private lateinit var listView: ListView
    private lateinit var search: EditText
    private lateinit var appAdapter: AppAdapter
    private var overlay: FrameLayout? = null

    private var apps: List<AppEntry> = emptyList()
    private val byKey = HashMap<String, AppEntry>()
    private var populated = false
    private var suppressSave = false
    private var lastLoad = 0L

    private var onApps = false
    private var editing = false
    private var picked: TileView? = null
    private var launching = false

    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    private val handler = Handler(Looper.getMainLooper())
    private val flipRunnable = object : Runnable {
        override fun run() {
            grid.flipAll()
            handler.postDelayed(this, 7000)
        }
    }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = loadApps()
    }
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = grid.refreshClock()
    }

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = Prefs.dark(this)
        accent = Prefs.accent(this)
        bg = if (dark) Color.BLACK else Color.WHITE
        fg = if (dark) Color.WHITE else Color.BLACK
        dim = if (dark) 0xFF666666.toInt() else 0xFFAAAAAA.toInt()
        light = Typeface.create("sans-serif-light", Typeface.NORMAL)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        buildUi()
        setContentView(root)

        if (!dark) {
            var f = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = f
        }
        loadApps()
    }

    override fun onStart() {
        super.onStart()
        val pf = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val tf = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        register(pkgReceiver, pf)
        register(timeReceiver, tf)
        NotifService.listener = { updateBadges() }
        grid.refreshClock()
        if (populated && System.currentTimeMillis() - lastLoad > 60_000) loadApps()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(pkgReceiver)
            unregisterReceiver(timeReceiver)
        } catch (e: IllegalArgumentException) {
        }
        NotifService.listener = null
    }

    override fun onResume() {
        super.onResume()
        if (launching) {
            launching = false
            grid.playEnter()
        }
        updateBadges()
        handler.removeCallbacks(flipRunnable)
        handler.postDelayed(flipRunnable, 5000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(flipRunnable)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            goHome()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            overlay != null -> dismissLetters()
            editing -> exitEdit()
            onApps -> showApps(false)
            else -> goHome()
        }
    }

    private fun goHome() {
        if (overlay != null) dismissLetters()
        if (editing) exitEdit()
        if (onApps) showApps(false) else scroll.smoothScrollTo(0, 0)
    }

    private fun register(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(r, f)
    }

    // ================================================================ UI

    private fun circleButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = light
            setTextColor(fg)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((bg and 0xFFFFFF) or (0xCC shl 24))
                setStroke(dp(2), fg)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(bg)

        // ---- start page
        startPage = FrameLayout(this)
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            setOnClickListener { if (editing) exitEdit() }
        }
        grid = TileGridLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setOnClickListener { if (editing) exitEdit() }
            onChanged = { saveTiles() }
        }
        scroll.addView(grid, FrameLayout.LayoutParams(MATCH, WRAP))
        startPage.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        val more = circleButton("···") { showMenu() }
        val arrow = circleButton("→") { showApps(true) }
        startPage.addView(more, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(16), 0, 0, dp(20))
        })
        startPage.addView(arrow, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(16), dp(20))
        })

        // ---- app list page
        appAdapter = AppAdapter()
        appPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(bg)
        }
        val title = TextView(this).apply {
            text = "apps"
            setTextColor(accent)
            textSize = 40f
            typeface = light
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        search = EditText(this).apply {
            hint = "search"
            setSingleLine()
            setTextColor(fg)
            setHintTextColor(dim)
            textSize = 18f
            backgroundTintList = ColorStateList.valueOf(accent)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    appAdapter.setApps(apps, s?.toString()?.trim().orEmpty())
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        listView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            adapter = appAdapter
            setBackgroundColor(Color.TRANSPARENT)
            setOnItemClickListener { _, _, pos, _ ->
                val row = appAdapter.rows[pos]
                if (row is AppEntry) launchApp(row) else showLetters()
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                val row = appAdapter.rows[pos]
                if (row is AppEntry) appMenu(row)
                true
            }
        }
        appPage.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        appPage.addView(search, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(0), dp(4), dp(0), dp(8))
        })
        appPage.addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.addView(startPage, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(appPage, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ================================================================ page navigation

    private fun showApps(show: Boolean) {
        if (show == onApps) return
        onApps = show
        if (editing) exitEdit()
        val w = root.width.toFloat()
        if (show) {
            appPage.visibility = View.VISIBLE
            appPage.translationX = w
            appPage.animate().translationX(0f).setDuration(260).start()
            startPage.animate().translationX(-w * 0.25f).alpha(0f).setDuration(260).withEndAction {
                if (onApps) startPage.visibility = View.INVISIBLE
            }.start()
        } else {
            startPage.visibility = View.VISIBLE
            startPage.animate().translationX(0f).alpha(1f).setDuration(260).start()
            appPage.animate().translationX(w).setDuration(260).withEndAction {
                if (!onApps) appPage.visibility = View.GONE
            }.start()
            hideKeyboard()
            search.setText("")
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(root.windowToken, 0)
        search.clearFocus()
    }

    /** Swipe left on Start -> apps, swipe right on apps -> Start. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (editing || overlay != null) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swiping) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) > dp(40) && abs(dx) > abs(dy) * 1.6f &&
                        ((dx < 0 && !onApps) || (dx > 0 && onApps))
                    ) {
                        swiping = true
                        // Stop the tile/list under the finger from treating this as a tap.
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                if (swiping) return true
            }
            MotionEvent.ACTION_UP -> {
                if (swiping) {
                    swiping = false
                    val dx = ev.rawX - downX
                    if (dx < -dp(70) && !onApps) showApps(true)
                    else if (dx > dp(70) && onApps) showApps(false)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return super.dispatchTouchEvent(ev)
    }

    // ================================================================ apps + tiles

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = ArrayList<AppEntry>()
            for (ri in pm.queryIntentActivities(q, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == packageName) continue
                val label = ri.loadLabel(pm)?.toString() ?: ai.packageName
                val sys = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                list.add(AppEntry(label, ai.packageName, ai.name, ri.loadIcon(pm), sys))
            }
            list.sortWith(compareBy<AppEntry>({ letterOf(it.label) }, { it.label.lowercase() }))
            runOnUiThread { if (!isDestroyed) onAppsLoaded(list) }
        }.start()
    }

    private fun onAppsLoaded(list: List<AppEntry>) {
        apps = list
        lastLoad = System.currentTimeMillis()
        byKey.clear()
        for (e in list) byKey[e.key] = e
        appAdapter.setApps(list, search.text.toString().trim())

        val src: List<TileData> = if (!populated) {
            Prefs.loadTiles(this) ?: defaultTiles().also { Prefs.saveTiles(this, it) }
        } else {
            grid.data().toList()
        }
        if (editing) exitEdit()
        val first = !populated
        suppressSave = true
        grid.removeAllViews()
        for (t in src) {
            if (t.isClock) {
                grid.addTile(makeTile(t, null), false)
            } else {
                val e = byKey[t.key] ?: continue
                grid.addTile(makeTile(t, e), false)
            }
        }
        suppressSave = false
        populated = true
        if (!first) saveTiles()
        updateBadges()
    }

    private fun makeTile(t: TileData, e: AppEntry?): TileView =
        TileView(this, t, e).also {
            it.host = this
            it.accent = accent
        }

    private fun defaultTiles(): List<TileData> {
        val out = ArrayList<TileData>()
        out.add(TileData(TileData.CLOCK, TileData.WIDE))
        val pm = packageManager
        fun sel(cat: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, cat)
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            sel(Intent.CATEGORY_APP_MESSAGING),
            sel(Intent.CATEGORY_APP_CONTACTS),
            sel(Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            sel(Intent.CATEGORY_APP_GALLERY),
            sel(Intent.CATEGORY_APP_EMAIL),
            sel(Intent.CATEGORY_APP_MAPS),
            sel(Intent.CATEGORY_APP_CALENDAR),
            sel(Intent.CATEGORY_APP_MUSIC),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in intents) {
            val ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) ?: continue
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == "android") continue
            val e = apps.firstOrNull { it.pkg == pkg } ?: continue
            if (out.none { it.key == e.key }) out.add(TileData(e.key, TileData.MEDIUM))
        }
        // Fewer than a screenful of matches? Pad with the first few installed apps.
        for (e in apps) {
            if (out.size >= 7) break
            if (out.none { it.key == e.key }) out.add(TileData(e.key, TileData.MEDIUM))
        }
        return out
    }

    private fun saveTiles() {
        if (populated && !suppressSave) Prefs.saveTiles(this, grid.data())
    }

    private fun updateBadges() {
        if (!populated) return
        val previews = Prefs.previews(this)
        for (v in grid.tiles()) {
            val e = v.entry ?: continue
            val n = NotifService.counts[e.pkg] ?: 0
            val l = NotifService.latest[e.pkg]
            val title = if (previews && l != null) {
                if (l.first.isNotBlank()) l.first else l.second
            } else null
            val text = if (previews && l != null && l.first.isNotBlank()) l.second else null
            v.setLive(n, title, text)
        }
    }

    private fun pin(e: AppEntry) {
        if (grid.tiles().any { it.data.key == e.key }) {
            Toast.makeText(this, "already on Start", Toast.LENGTH_SHORT).show()
            return
        }
        showApps(false)
        grid.addTile(makeTile(TileData(e.key, TileData.MEDIUM), e), true)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun unpinKey(key: String) {
        grid.tiles().firstOrNull { it.data.key == key }?.let { grid.removeTile(it) }
    }

    private fun addClock() {
        grid.addTile(makeTile(TileData(TileData.CLOCK, TileData.WIDE), null), true)
    }

    // ================================================================ launching

    private fun launchApp(e: AppEntry) {
        try {
            val i = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i)
        } catch (ex: Exception) {
            Toast.makeText(this, "can't open ${e.label}", Toast.LENGTH_SHORT).show()
            launching = false
            grid.resetAnim()
        }
    }

    // ---- TileView.Host

    override fun onLaunch(v: TileView) {
        if (editing) {
            selectTile(v)
            return
        }
        if (v.data.isClock) {
            try {
                startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(this, "no clock app found", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val e = v.entry ?: return
        launching = true
        grid.playExit(v) { launchApp(e) }
    }

    override fun onLongPress(v: TileView) = enterEdit(v)
    override fun onSelect(v: TileView) = selectTile(v)
    override fun onUnpin(v: TileView) {
        if (picked === v) picked = null
        grid.removeTile(v)
    }

    override fun onResize(v: TileView) {
        grid.resizeTile(v, (v.data.size + 1) % 3)
    }

    override fun onDrop(v: TileView) = grid.dropTile(v)

    // ================================================================ edit mode

    private fun enterEdit(v: TileView) {
        editing = true
        picked = v
        grid.setEditing(true, v)
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun selectTile(v: TileView) {
        picked = v
        grid.setEditing(true, v)
    }

    private fun exitEdit() {
        editing = false
        picked = null
        grid.setEditing(false, null)
    }

    // ================================================================ dialogs

    private fun dlg(): AlertDialog.Builder = AlertDialog.Builder(
        this,
        if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    )

    private fun menu(title: String?, items: List<Pair<String, () -> Unit>>) {
        val b = dlg().setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
        if (title != null) b.setTitle(title)
        b.show()
    }

    private fun showMenu() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("accent color" to { pickAccent() })
        items.add("theme: ${if (dark) "dark" else "light"}" to {
            Prefs.setDark(this, !dark)
            recreate()
        })
        items.add("live tile previews: ${if (Prefs.previews(this)) "on" else "off"}" to {
            Prefs.setPreviews(this, !Prefs.previews(this))
            updateBadges()
        })
        if (!NotifService.isEnabled(this)) {
            items.add("enable live tile notifications" to {
                try {
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            })
        }
        if (grid.tiles().none { it.data.isClock }) items.add("add clock tile" to { addClock() })
        items.add("set as home app" to {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        })
        items.add("system settings" to { startActivity(Intent(Settings.ACTION_SETTINGS)) })
        menu(null, items)
    }

    private fun pickAccent() {
        val g = GridLayout(this).apply {
            columnCount = 5
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val d = dlg().setView(g).create()
        for ((i, col) in Prefs.ACCENTS.withIndex()) {
            val sw = View(this).apply {
                setBackgroundColor(col)
                setOnClickListener {
                    Prefs.setAccent(this@MainActivity, i)
                    d.dismiss()
                    recreate()
                }
            }
            g.addView(sw, GridLayout.LayoutParams().apply {
                width = dp(48)
                height = dp(48)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        d.show()
    }

    private fun appMenu(e: AppEntry) {
        val pinned = grid.tiles().any { it.data.key == e.key }
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add((if (pinned) "unpin from Start" else "pin to Start") to {
            if (pinned) unpinKey(e.key) else pin(e)
        })
        items.add("app info" to {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}")))
        })
        if (!e.isSystem) {
            items.add("uninstall" to {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.pkg}")))
            })
        }
        menu(e.label, items)
    }

    // ================================================================ letter jump

    private fun showLetters() {
        if (overlay != null) return
        hideKeyboard()
        val present = appAdapter.headerPos.keys
        val ov = FrameLayout(this).apply {
            setBackgroundColor((bg and 0xFFFFFF) or (0xF2 shl 24))
            isClickable = true
            setOnClickListener { dismissLetters() }
        }
        val g = GridLayout(this).apply { columnCount = 4 }
        val letters = listOf("#") + ('A'..'Z').map { it.toString() }
        for (l in letters) {
            val has = present.contains(l)
            val tv = TextView(this).apply {
                text = l.lowercase()
                gravity = Gravity.CENTER
                textSize = 28f
                typeface = light
                setTextColor(if (has) Color.WHITE else dim)
                background = GradientDrawable().apply {
                    setColor(if (has) accent else Color.TRANSPARENT)
                    setStroke(dp(2), if (has) accent else dim)
                }
                if (has) {
                    setOnClickListener {
                        appAdapter.headerPos[l]?.let { listView.setSelection(it) }
                        dismissLetters()
                    }
                }
            }
            g.addView(tv, GridLayout.LayoutParams().apply {
                width = dp(72)
                height = dp(72)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        ov.addView(g, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ov
    }

    private fun dismissLetters() {
        overlay?.let { root.removeView(it) }
        overlay = null
    }

    // ================================================================ app list adapter

    inner class AppAdapter : BaseAdapter() {
        /** Each row is either a String (section letter) or an AppEntry. */
        var rows: List<Any> = emptyList()
        val headerPos = LinkedHashMap<String, Int>()

        fun setApps(all: List<AppEntry>, query: String) {
            val out = ArrayList<Any>()
            headerPos.clear()
            if (query.isEmpty()) {
                var cur = ""
                for (e in all) {
                    val l = letterOf(e.label)
                    if (l != cur) {
                        cur = l
                        headerPos[l] = out.size
                        out.add(l)
                    }
                    out.add(e)
                }
            } else {
                val q = query.lowercase()
                for (e in all) if (e.label.lowercase().contains(q)) out.add(e)
            }
            rows = out
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            return if (row is String) headerView(row, convertView) else appView(row as AppEntry, convertView)
        }

        private fun headerView(letter: String, cv: View?): View {
            val v = cv as? FrameLayout ?: FrameLayout(this@MainActivity).apply {
                layoutParams = AbsListView.LayoutParams(MATCH, dp(64))
                addView(TextView(this@MainActivity).apply {
                    gravity = Gravity.CENTER
                    textSize = 26f
                    typeface = light
                    setTextColor(accent)
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(2), accent)
                    }
                }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL).apply {
                    leftMargin = dp(20)
                })
            }
            (v.getChildAt(0) as TextView).text = letter.lowercase()
            return v
        }

        private fun appView(e: AppEntry, cv: View?): View {
            val v = cv as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                layoutParams = AbsListView.LayoutParams(MATCH, dp(64))
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                addView(android.widget.ImageView(this@MainActivity), LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(TextView(this@MainActivity).apply {
                    textSize = 22f
                    typeface = light
                    setTextColor(fg)
                    setSingleLine()
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { leftMargin = dp(16) })
            }
            (v.getChildAt(0) as android.widget.ImageView).setImageDrawable(e.icon)
            (v.getChildAt(1) as TextView).text = e.label
            return v
        }
    }
}
