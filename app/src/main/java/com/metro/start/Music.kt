package com.metro.start

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

// ===================================================================== media sessions

class Track(val title: String, val artist: String, val album: String, val art: Bitmap?, val duration: Long)

/**
 * Follows whatever is playing (Spotify first) through Android media sessions.
 * Needs notification access, which Metro already uses for live tiles.
 */
object Media {
    const val SPOTIFY = "com.spotify.music"

    var controller: MediaController? = null
        private set
    var track: Track? = null
        private set
    var state: PlaybackState? = null
        private set
    val recent = ArrayList<Pair<String, String>>()

    private var msm: MediaSessionManager? = null
    private var comp: ComponentName? = null
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun listen(l: () -> Unit) = listeners.addIfAbsent(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)
    private fun fire() = main.post { for (l in listeners) l() }

    val playing: Boolean get() = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onPlaybackStateChanged(s: PlaybackState?) {
            val wasPlaying = playing
            update()
            // If this player paused, another one may have started.
            if (wasPlaying && !playing) main.postDelayed({ repick() }, 600)
        }

        override fun onSessionDestroyed() = repick()
    }

    /** Safe to call often: connects once, then just re-checks which player to follow. */
    fun attach(c: Context) {
        val a = c.applicationContext
        app = a
        if (recent.isEmpty()) loadRecent(a)
        if (!Notifs.enabled(a)) return
        try {
            if (msm == null) {
                val m = a.getSystemService(MediaSessionManager::class.java) ?: return
                val cn = ComponentName(a, NotificationFeed::class.java)
                m.addOnActiveSessionsChangedListener(sessions, cn, main)
                msm = m
                comp = cn
            }
            repick()
        } catch (e: SecurityException) {
            msm = null
        }
    }

    private fun repick() {
        val m = msm ?: return
        try {
            pick(m.getActiveSessions(comp))
        } catch (e: SecurityException) {
        }
    }

    private fun isPlaying(mc: MediaController) =
        mc.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun pick(list: List<MediaController>?) {
        val l = list.orEmpty()
        val best = l.firstOrNull { isPlaying(it) && it.packageName == SPOTIFY }
            ?: l.firstOrNull { isPlaying(it) }
            ?: l.firstOrNull { it.sessionToken == controller?.sessionToken }
            ?: l.firstOrNull { it.packageName == SPOTIFY }
            ?: l.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            try {
                controller?.unregisterCallback(cb)
            } catch (e: Exception) {
            }
            controller = best
            best?.registerCallback(cb, main)
        }
        update()
    }

    private fun update() {
        val mc = controller
        state = mc?.playbackState
        val md = mc?.metadata
        track = if (md == null) null else {
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
            val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (title.isBlank()) null
            else Track(title, artist, md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(), art, md.getLong(MediaMetadata.METADATA_KEY_DURATION))
        }
        track?.let { remember(it) }
        fire()
    }

    private fun remember(t: Track) {
        val item = t.title to t.artist
        if (recent.firstOrNull() == item) return
        recent.remove(item)
        recent.add(0, item)
        while (recent.size > 40) recent.removeAt(recent.size - 1)
        app?.let { a ->
            val arr = JSONArray()
            for ((ti, ar) in recent) arr.put(JSONObject().put("t", ti).put("a", ar))
            Prefs(a).recentTracks = arr.toString()
        }
    }

    private fun loadRecent(c: Context) {
        try {
            val arr = JSONArray(Prefs(c).recentTracks ?: return)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                recent.add(o.getString("t") to o.optString("a"))
            }
        } catch (e: Exception) {
        }
    }

    fun position(): Long {
        val s = state ?: return 0L
        var p = s.position
        if (s.state == PlaybackState.STATE_PLAYING && s.lastPositionUpdateTime > 0) {
            p += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        val d = track?.duration ?: 0L
        return if (d > 0) p.coerceIn(0L, d) else p.coerceAtLeast(0L)
    }

    private fun can(action: Long) = ((state?.actions ?: 0L) and action) != 0L

    fun playPause() {
        val t = controller?.transportControls ?: return
        if (playing) t.pause() else t.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
    fun seek(ms: Long) = controller?.transportControls?.seekTo(ms)
    fun custom(a: PlaybackState.CustomAction) = controller?.transportControls?.sendCustomAction(a, null)
    val canSeek: Boolean get() = can(PlaybackState.ACTION_SEEK_TO)

    fun sourceLabel(c: Context): String {
        val p = controller?.packageName ?: return ""
        return try {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(p, 0)).toString().lowercase(Locale.getDefault())
        } catch (e: Exception) {
            p
        }
    }

    /** Plays a song by name: asks the player directly, otherwise opens Spotify's search. */
    fun playSearch(c: Context, query: String) {
        val mc = controller
        if (mc != null && can(PlaybackState.ACTION_PLAY_FROM_SEARCH)) {
            mc.transportControls.playFromSearch(query, Bundle())
            return
        }
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))).setPackage(SPOTIFY),
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, query)
        )
        for (i in tries) {
            try {
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
            }
        }
        Toast.makeText(c, "no music app could play that", Toast.LENGTH_SHORT).show()
    }

    fun openPlayer(c: Context) {
        val pkg = controller?.packageName ?: SPOTIFY
        val i = c.packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SPOTIFY"))
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }
}

// ===================================================================== the music app

class MusicActivity : PivotActivity() {
    private lateinit var nowPage: FrameLayout
    private lateinit var playerView: LinearLayout
    private lateinit var art: ImageView
    private lateinit var artGlyph: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var seek: SeekLine
    private lateinit var elapsed: TextView
    private lateinit var remaining: TextView
    private lateinit var playBtn: RoundButton
    private lateinit var actions: LinearLayout
    private lateinit var source: TextView
    private var empty: View? = null
    private lateinit var recentRows: Rows
    private lateinit var recentList: androidx.recyclerview.widget.RecyclerView
    private var lastArt: Bitmap? = null
    private val ticker = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }

    private val tick = object : Runnable {
        override fun run() {
            renderTime()
            ticker.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Metro.fonts(this)
        nowPage = FrameLayout(this)
        buildPlayer()
        recentRows = Rows({ 0 }, { parent, _ -> recentRow(parent.context) }, { v, item -> bindRecent(v, item) })
        recentList = metroList(recentRows)
        setupPivot("metro music", listOf("now playing" to nowPage, "recent" to recentList))
    }

    override fun onBottomInset(bottom: Int) {
        playerView.setPadding(dp(20), 0, dp(20), bottom + dp(16))
        recentList.setPadding(0, 0, 0, bottom + dp(12))
    }

    override fun onResume() {
        super.onResume()
        Media.attach(this)
        Media.listen(listener)
        render()
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        Media.unlisten(listener)
        ticker.removeCallbacks(tick)
    }

    private fun buildPlayer() {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        playerView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val artBox = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                val size = MeasureSpec.getSize(w)
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
            }
        }
        artBox.setBackgroundColor(Look.accentShade)
        artGlyph = ImageView(this).apply {
            setImageBitmap(Glyphs.music())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        artBox.addView(artGlyph, FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER))
        artBox.addView(art, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        Tilt.attach(artBox, 6f)
        artBox.setOnClickListener { Media.openPlayer(this) }
        playerView.addView(artBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        title = text("", 30f, Metro.light).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
        }
        artist = text("", 20f, Metro.regular).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        album = text("", 15f, Metro.regular, Look.fg60).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        playerView.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        playerView.addView(artist)
        playerView.addView(album)

        seek = SeekLine(this)
        seek.onSeeking = { f -> elapsed.text = time((f * (Media.track?.duration ?: 0L)).toLong()) }
        seek.onSeek = { f -> Media.track?.duration?.let { Media.seek((f * it).toLong()) } }
        playerView.addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, dp(30)).apply { topMargin = dp(12) })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        elapsed = text("0:00", 13f, Metro.regular, Look.fg60)
        remaining = text("", 13f, Metro.regular, Look.fg60).apply { gravity = Gravity.END }
        times.addView(elapsed, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        times.addView(remaining, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        playerView.addView(times)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = RoundButton(this, RoundButton.PREV).apply { setOnClickListener { Media.previous() } }
        playBtn = RoundButton(this, RoundButton.PLAY).apply { setOnClickListener { Media.playPause() } }
        val next = RoundButton(this, RoundButton.NEXT).apply { setOnClickListener { Media.next() } }
        controls.addView(prev, LinearLayout.LayoutParams(dp(58), dp(58)))
        controls.addView(playBtn, LinearLayout.LayoutParams(dp(76), dp(76)).apply { setMargins(dp(28), 0, dp(28), 0) })
        controls.addView(next, LinearLayout.LayoutParams(dp(58), dp(58)))
        playerView.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

        val actionScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionScroll.addView(actions)
        playerView.addView(actionScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })

        source = text("", 15f, Metro.regular, Look.accentShade).apply {
            setPadding(0, dp(16), 0, dp(4))
            setOnClickListener { Media.openPlayer(this@MusicActivity) }
        }
        playerView.addView(source)

        scroll.addView(playerView)
        nowPage.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun render() {
        val t = Media.track
        empty?.let { nowPage.removeView(it) }
        empty = null
        if (t == null) {
            playerView.visibility = View.INVISIBLE
            val e = if (!Notifs.enabled(this)) {
                emptyState(
                    "see what's playing",
                    "metro music follows spotify (or any player) through notification access. turn it on for metro, then come back.",
                    "allow access"
                ) {
                    try {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (ex: Exception) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            } else {
                emptyState(
                    "nothing playing",
                    "start something in spotify and it'll show up here, with controls.",
                    "open spotify"
                ) { Media.openPlayer(this) }
            }
            nowPage.addView(e, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            empty = e
        } else {
            playerView.visibility = View.VISIBLE
            if (t.art !== lastArt) {
                lastArt = t.art
                art.setImageBitmap(t.art)
                art.alpha = 0f
                art.ani(300).alpha(1f).start()
            }
            if (title.text.toString() != t.title.lowercase(Locale.getDefault())) {
                title.text = t.title.lowercase(Locale.getDefault())
                title.translationX = dp(30).toFloat()
                title.alpha = 0f
                title.ani(320).translationX(0f).alpha(1f).start()
            }
            artist.text = t.artist.lowercase(Locale.getDefault())
            album.text = t.album.lowercase(Locale.getDefault())
            album.visibility = if (t.album.isBlank()) View.GONE else View.VISIBLE
            playBtn.kind = if (Media.playing) RoundButton.PAUSE else RoundButton.PLAY
            seek.isEnabled = Media.canSeek
            val src = Media.sourceLabel(this)
            source.text = if (src.isBlank()) "" else "playing on $src · open"
            renderActions()
            renderTime()
        }
        recentRows.items = Media.recent.toList()
    }

    private fun renderActions() {
        actions.removeAllViews()
        val list = Media.state?.customActions.orEmpty()
        for (a in list) {
            val label = a.name?.toString()?.lowercase(Locale.getDefault())?.takeIf { it.isNotBlank() } ?: continue
            val b = text(label, 15f, Metro.regular).apply {
                background = flatBox(Look.fg60, dp(2))
                setPadding(dp(14), dp(7), dp(14), dp(8))
                isClickable = true
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    Media.custom(a)
                }
            }
            Tilt.attach(b, 8f)
            actions.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
    }

    private fun renderTime() {
        val t = Media.track ?: return
        if (seek.dragging) return
        val pos = Media.position()
        val d = t.duration
        seek.fraction = if (d > 0) pos.toFloat() / d else 0f
        elapsed.text = time(pos)
        remaining.text = if (d > 0) "-" + time(d - pos) else ""
    }

    private fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun recentRow(c: Context): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isClickable = true
            layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        row.addView(c.text("", 22f, Metro.light).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
        row.addView(c.text("", 15f, Metro.regular, Look.fg60).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
        Tilt.attach(row, 2f)
        return row
    }

    @Suppress("UNCHECKED_CAST")
    private fun bindRecent(v: View, item: Any) {
        val (t, a) = item as Pair<String, String>
        val row = v as LinearLayout
        (row.getChildAt(0) as TextView).text = t.lowercase(Locale.getDefault())
        (row.getChildAt(1) as TextView).text = a.lowercase(Locale.getDefault())
        row.setOnClickListener {
            Media.playSearch(this, "$t $a")
            pivot.go(0)
        }
    }
}

/** Thin progress line; drag to seek. */
class SeekLine(context: Context) : View(context) {
    var fraction = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var dragging = false
        private set
    var onSeeking: ((Float) -> Unit)? = null
    var onSeek: ((Float) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val y = height / 2f
        val h = dpf(if (dragging) 6f else 4f)
        p.color = Look.fg30
        c.drawRect(0f, y - h / 2, width.toFloat(), y + h / 2, p)
        p.color = Look.accentShade
        val x = width * fraction
        c.drawRect(0f, y - h / 2, x, y + h / 2, p)
        if (dragging) {
            p.color = Look.fg
            val t = dpf(8f)
            c.drawRect(x - t / 2, y - dpf(11f), x + t / 2, y + dpf(11f), p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        val f = (e.x / width).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                fraction = f
                onSeeking?.invoke(f)
            }
            MotionEvent.ACTION_MOVE -> {
                fraction = f
                onSeeking?.invoke(f)
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                fraction = f
                onSeek?.invoke(f)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }
}

/** Circled transport button, drawn in Metro style. */
class RoundButton(context: Context, kind0: Int) : View(context) {
    companion object {
        const val PREV = 0
        const val PLAY = 1
        const val PAUSE = 2
        const val NEXT = 3
    }

    var kind = kind0
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        isClickable = true
        Tilt.attach(this, 14f)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        p.style = Paint.Style.STROKE
        p.strokeWidth = dpf(2f)
        p.color = Look.fg
        c.drawCircle(cx, cy, w / 2f - p.strokeWidth, p)
        p.style = Paint.Style.FILL
        val u = w / 100f
        path.reset()
        when (kind) {
            PLAY -> {
                path.moveTo(cx - 12 * u, cy - 18 * u)
                path.lineTo(cx + 18 * u, cy)
                path.lineTo(cx - 12 * u, cy + 18 * u)
                path.close()
                c.drawPath(path, p)
            }
            PAUSE -> {
                c.drawRect(cx - 14 * u, cy - 17 * u, cx - 4 * u, cy + 17 * u, p)
                c.drawRect(cx + 4 * u, cy - 17 * u, cx + 14 * u, cy + 17 * u, p)
            }
            else -> {
                val d = if (kind == NEXT) 1f else -1f
                for (k in 0..1) {
                    val ox = cx + d * (k * 14 - 12) * u
                    path.reset()
                    path.moveTo(ox, cy - 13 * u)
                    path.lineTo(ox + d * 15 * u, cy)
                    path.lineTo(ox, cy + 13 * u)
                    path.close()
                    c.drawPath(path, p)
                }
                val bx = cx + d * 17 * u
                c.drawRect(bx - 2 * u, cy - 13 * u, bx + 2 * u, cy + 13 * u, p)
            }
        }
    }
}
