package com.metro.start

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max

/** The launcher: start screen on the left, app list on the right. */
class StartActivity : MetroActivity(), TileGrid.Listener {

    private lateinit var prefs: Prefs
    private var rev = 0

    private lateinit var pager: Pager
    private lateinit var scroll: ScrollView
    lateinit var grid: TileGrid
        private set
    private var background: ImageView? = null
    private lateinit var arrow: ArrowButton

    private lateinit var appsTitle: TextView
    private lateinit var search: EditText
    private lateinit var list: RecyclerView
    private lateinit var adapter: AppListAdapter
    private var nav: NavBar? = null

    private var loaded = false
    private var launchedFrom = 0 // 0 = nothing, 1 = start screen, 2 = app list
    private var stopped = false

    private val liveListener: () -> Unit = { refreshLive() }
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Apps.load(this@StartActivity) { onAppsLoaded() }
        }
    }

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.load(this)
        prefs = Prefs(this)
        rev = prefs.rev
        setupWindow()
        build()
        setContentView(root)
        if (Look.navBar) {
            // Metro draws its own back / start / search bar, so hide the system one here.
            // A swipe up from the bottom still brings it back for a moment.
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
        Apps.load(this) { onAppsLoaded() }
        LockService.sync(this)
        Updater.cleanup(this)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packages, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(packages)
        } catch (e: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.rev != rev) {
            recreate()
            return
        }
        setupPage?.refresh()
        Notifs.listen(liveListener)
        Live.listen(liveListener)
        Media.listen(liveListener)
        Media.attach(this)
        Updater.listen(liveListener)
        Updater.autoCheck(this)
        Live.refresh(this)
        if (loaded) {
            checkShuffle()
            refreshLive()
        }
        when (launchedFrom) {
            1 -> {
                Motion.turnstileIn(grid.visibleTiles())
                arrow.ani(300).alpha(1f).start()
            }
            2 -> Motion.turnstileIn(adapter.visibleRows(list))
            else -> if (stopped && loaded && pager.page == 0) Motion.flyIn(grid.visibleTiles())
        }
        launchedFrom = 0
        stopped = false
        grid.startLive()
    }

    override fun onPause() {
        super.onPause()
        Notifs.unlisten(liveListener)
        Live.unlisten(liveListener)
        Media.unlisten(liveListener)
        Updater.unlisten(liveListener)
        grid.stopLive()
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        grid.exitEdit()
        // The live-tile setup page stays open while you grant access in Android settings.
        closeOverlays(keep = setupPage)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) goHome()
    }

    /** The start button: close whatever is open, otherwise scroll back to the top. */
    private fun goHome() {
        val animate = !stopped && hasWindowFocus()
        closeOverlays()
        grid.exitEdit()
        hideKeyboard()
        when {
            pager.page == 1 -> pager.go(0, animate)
            animate -> scroll.smoothScrollTo(0, 0)
            else -> scroll.scrollTo(0, 0)
        }
    }

    override fun back() {
        when {
            grid.editing -> grid.exitEdit()
            pager.page == 1 -> pager.go(0)
            else -> scroll.smoothScrollTo(0, 0)
        }
    }

    // ================================================================ layout

    private fun build() {
        pager = Pager(this)

        // ---- start screen
        val startPage = FrameLayout(this)
        val bgFile = Prefs.backgroundFile(this)
        if (bgFile.exists()) {
            background = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(bgFile.absolutePath))
            }
            startPage.addView(background, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            clipChildren = false
        }
        grid = TileGrid(this).apply {
            listener = this@StartActivity
            scroller = scroll
            clipChildren = false
        }
        scroll.addView(grid, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        startPage.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        arrow = ArrowButton(this).apply { setOnClickListener { openApps(false) } }
        startPage.addView(arrow, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(14)
        })

        // Parallax: the background drifts slower than the tiles; the arrow scrolls with them.
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            arrow.translationY = -y.toFloat()
            background?.let { bg ->
                val travel = (bg.height - scroll.height).coerceAtLeast(0)
                bg.translationY = -(y * 0.25f).coerceAtMost(travel.toFloat())
            }
        }

        // ---- app list
        val appsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appsTitle = text("apps", 48f, Metro.light).apply {
            setPadding(dp(18), dp(2), dp(18), 0)
        }
        search = EditText(this).apply {
            hint = "search"
            setSingleLine()
            textSize = 18f
            typeface = Metro.regular
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12), dp(10), dp(12), dp(10))
            styleSearch(false)
            setOnFocusChangeListener { _, has -> styleSearch(has) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = adapter.submit(Apps.list, s?.toString().orEmpty())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, _, _ ->
                val first = adapter.rows.firstOrNull { it is AppInfo } as? AppInfo
                if (first != null && text.isNotBlank()) {
                    hideKeyboard()
                    launch(first)
                }
                true
            }
        }
        adapter = AppListAdapter(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@StartActivity)
            adapter = this@StartActivity.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
                }
            })
        }
        appsPage.addView(appsTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        appsPage.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(20), dp(4), dp(20), dp(10))
        })
        appsPage.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        pager.addView(startPage)
        pager.addView(appsPage)
        // The title slides a little slower than the content.
        pager.onOffset = { f -> appsTitle.translationX = -(1f - f) * pager.width * 0.25f }
        pager.onPage = { p ->
            if (p == 0) {
                hideKeyboard()
                if (search.text.isNotEmpty()) search.setText("")
                list.scrollToPosition(0)
            }
        }
        root.addView(pager, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        if (Look.navBar) {
            nav = NavBar(this).apply {
                onBack = { onBackPressedCompat() }
                onHome = { goHome() }
                onSearch = { openApps(true) }
            }
            root.addView(nav, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }

        onInsets { top, bottom, ime ->
            val navH = if (nav != null) dp(48) + bottom else bottom
            nav?.setInset(bottom)
            grid.topPad = top + dp(52)
            grid.bottomPad = navH + dp(28)
            grid.requestLayout()
            (arrow.layoutParams as FrameLayout.LayoutParams).topMargin = top + dp(9)
            arrow.requestLayout()
            appsPage.setPadding(0, top, 0, 0)
            list.setPadding(0, 0, 0, max(navH, ime) + dp(12))
            background?.let { bg ->
                bg.layoutParams.height = (resources.displayMetrics.heightPixels * 1.35f).toInt()
                bg.requestLayout()
            }
        }
    }

    private fun EditText.styleSearch(focused: Boolean) {
        background = if (focused) flatBox(Look.fg, dp(2), Look.fg) else flatBox(Look.fg60, dp(2))
        setTextColor(if (focused) Look.bg else Look.fg)
        setHintTextColor(if (focused) alpha(Look.bg, 0.5f) else Look.fg60)
    }

    private fun onBackPressedCompat() {
        if (hasOverlay) closeOverlays() else back()
    }

    private fun openApps(focusSearch: Boolean) {
        grid.exitEdit()
        pager.go(1)
        if (focusSearch) {
            search.requestFocus()
            search.postDelayed({
                val imm = getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
            }, 200)
        }
    }

    private fun hideKeyboard() {
        if (!::search.isInitialized) return
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
        search.clearFocus()
    }

    // ================================================================ apps and tiles

    private fun onAppsLoaded() {
        adapter.submit(Apps.list, search.text.toString())
        if (!loaded) {
            val saved = prefs.tiles?.let { Tile.parse(it) }
            val tiles = saved ?: defaultTiles().also { TileColors.separate(it) }
            if (saved != null && prefs.builtins < Tile.BUILTINS_VERSION) {
                // Existing start screens get the new Metro apps pinned at the top, once.
                val add = listOf(Tile.PHONE to Tile.MEDIUM, Tile.WEATHER to Tile.MEDIUM, Tile.MUSIC to Tile.WIDE)
                    .filter { (k, _) -> saved.none { it.key == k } }
                saved.addAll(0, add.map { (k, size) -> Tile(k, size, TileColors.next(prefs)) })
                TileColors.separate(saved)
            }
            prefs.builtins = Tile.BUILTINS_VERSION
            grid.setTiles(tiles.mapNotNull { t -> Apps.byKey[t.key]?.let { TileView(this, t, it) } })
            loaded = true
            save()
            checkShuffle()
            refreshLive()
            if (!prefs.onboarded) grid.postDelayed({ if (!isFinishing) show(LiveSetup(this)) }, 900)
            if (pager.page == 0) grid.doOnPreDraw { Motion.flyIn(grid.visibleTiles()) }
        } else {
            var changed = false
            for (v in grid.views.toList()) {
                val a = Apps.byKey[v.tile.key]
                if (a == null) {
                    grid.removeNow(v)
                    changed = true
                } else if (a !== v.app) {
                    v.app = a
                }
            }
            if (changed) onTilesChanged()
        }
    }

    private fun defaultTiles(): MutableList<Tile> {
        val pm = packageManager
        fun res(i: Intent): String? =
            pm.resolveActivity(i, 0)?.activityInfo?.packageName?.takeIf { it != "android" }

        fun sel(cat: String) = res(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, cat))
        fun kind(k: Kind) = Apps.list.firstOrNull { it.kind == k }?.pkg
        // Entries are either a Metro key ("metro:...") or another app's package.
        val plan = listOf(
            Tile.PHONE to Tile.MEDIUM,
            kind(Kind.PEOPLE) to Tile.MEDIUM,
            kind(Kind.MESSAGES) to Tile.MEDIUM,
            Tile.WEATHER to Tile.MEDIUM,
            Tile.MUSIC to Tile.WIDE,
            kind(Kind.PHOTOS) to Tile.WIDE,
            kind(Kind.CALENDAR) to Tile.MEDIUM,
            sel(Intent.CATEGORY_APP_BROWSER) to Tile.SMALL,
            res(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) to Tile.SMALL,
            "com.android.vending" to Tile.SMALL,
            Tile.SETTINGS to Tile.SMALL,
            sel(Intent.CATEGORY_APP_EMAIL) to Tile.MEDIUM,
            sel(Intent.CATEGORY_APP_MAPS) to Tile.SMALL,
            res(Intent(Settings.ACTION_SETTINGS)) to Tile.SMALL
        )
        val out = ArrayList<Tile>()
        val used = HashSet<String>()
        for ((pkg, size) in plan) {
            if (pkg == null) continue
            val app = if (pkg.startsWith("metro:")) Apps.byKey[pkg]
            else Apps.list.firstOrNull { it.pkg == pkg && it.pkg != packageName }
            if (app == null || !used.add(app.key)) continue
            out.add(Tile(app.key, size, TileColors.next(prefs)))
        }
        for (a in Apps.list) {
            if (out.size >= 8) break
            if (used.add(a.key)) out.add(Tile(a.key, Tile.MEDIUM, TileColors.next(prefs)))
        }
        return out
    }

    private fun save() {
        prefs.tiles = Tile.serialize(grid.tiles())
    }

    private fun refreshLive() {
        for (v in grid.views) v.refreshLive()
    }

    private fun checkShuffle() {
        if (Look.mode != Metro.SHUFFLE) return
        val today = TileColors.today()
        if (prefs.shuffleDay == today) return
        prefs.shuffleDay = today
        TileColors.shuffle(grid.tiles(), today.toLong())
        for (v in grid.views) v.refreshColor(true)
        save()
    }

    fun iconColor(a: AppInfo): Int {
        if (Look.mode == Metro.SINGLE) return Look.accentShade
        grid.views.firstOrNull { it.tile.key == a.key }?.let { return Look.tileColor(it.tile) }
        return Look.shade(Metro.PALETTE[abs(a.pkg.hashCode() % Metro.PALETTE.size)])
    }

    private fun launch(a: AppInfo) {
        try {
            val opts = ActivityOptions.makeCustomAnimation(this, R.anim.app_enter, R.anim.hold).toBundle()
            if (a.pkg == packageName) {
                startActivity(Intent().setClassName(this, a.cls), opts)
                return
            }
            val i = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(a.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i, opts)
        } catch (e: Exception) {
            Toast.makeText(this, "can't open ${a.name}", Toast.LENGTH_SHORT).show()
            launchedFrom = 0
            Motion.reset(grid.views + adapter.visibleRows(list))
            arrow.alpha = 1f
        }
    }

    // ================================================================ TileGrid.Listener

    override fun onTileTap(v: TileView) {
        if (pager.page != 0 || launchedFrom != 0) return
        launchedFrom = 1
        arrow.ani(150).alpha(0f).start()
        Motion.turnstileOut(grid.visibleTiles(), v) { launch(v.app) }
    }

    override fun onEditChanged(editing: Boolean) {
        pager.locked = editing
        arrow.ani(220).alpha(if (editing) 0f else 1f).start()
    }

    override fun onTilesChanged() {
        if (Look.mode != Metro.SINGLE && TileColors.separate(grid.tiles())) {
            for (v in grid.views) v.refreshColor(true)
        }
        save()
        adapter.notifyDataSetChanged()
    }

    override fun onCustomize(v: TileView) {
        show(
            ColorPicker(
                this, "tile color", v.tile.color, v.tile.live,
                onColor = { c ->
                    v.tile.color = c
                    v.tile.userColor = true
                    v.refreshColor(true)
                    onTilesChanged()
                },
                onLive = { on ->
                    v.tile.live = on
                    v.refreshLive()
                    save()
                }
            )
        )
    }

    // ================================================================ app list

    fun onAppTap(a: AppInfo, row: View) {
        if (launchedFrom != 0) return
        launchedFrom = 2
        hideKeyboard()
        Motion.turnstileOut(adapter.visibleRows(list), row) { launch(a) }
    }

    fun onAppLongPress(a: AppInfo, row: View) {
        val pinned = grid.views.any { it.tile.key == a.key }
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add((if (pinned) "unpin from start" else "pin to start") to { if (pinned) unpin(a) else pin(a) })
        if (a.pkg != packageName) {
            if (!a.system) items.add("uninstall" to {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
            })
            items.add("info" to {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
            })
        }
        hideKeyboard()
        show(MetroMenu(this, items, row))
    }

    private fun pin(a: AppInfo) {
        val t = Tile(a.key, Tile.MEDIUM, TileColors.next(prefs))
        hideKeyboard()
        pager.go(0)
        grid.addTile(TileView(this, t, a))
        onTilesChanged()
        grid.views.lastOrNull()?.refreshLive()
        scroll.postDelayed({ scroll.smoothScrollTo(0, grid.height) }, 120)
    }

    private fun unpin(a: AppInfo) {
        grid.views.firstOrNull { it.tile.key == a.key }?.let {
            grid.removeNow(it)
            onTilesChanged()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Live.invalidateWeather()
        Live.refresh(this, force = true)
        setupPage?.refresh()
    }

    var setupPage: LiveSetup? = null

    fun showJumpGrid() {
        hideKeyboard()
        show(JumpGrid(this, adapter.headerPos.keys) { ch ->
            adapter.headerPos[ch]?.let { pos ->
                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
            }
        })
    }
}
