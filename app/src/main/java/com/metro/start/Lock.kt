package com.metro.start

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Metro lock screen: big light clock, date below, swipe up (with a bounce) to unlock. */
class LockActivity : Activity() {

    companion object {
        fun show(c: Context) {
            try {
                c.startActivity(
                    Intent(c, LockActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                )
            } catch (e: Exception) {
            }
        }
    }

    private lateinit var content: FrameLayout
    private lateinit var time: TextView
    private lateinit var date: TextView
    private lateinit var counts: LinearLayout
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateClock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.load(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(build())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        content = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val bg = Prefs.backgroundFile(this)
        if (bg.exists()) {
            content.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(bg.absolutePath))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            content.addView(View(this).apply { setBackgroundColor(0x40000000) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), dp(72))
        }
        counts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        time = text("", 96f, Metro.light, Color.WHITE).apply { includeFontPadding = false }
        date = text("", 26f, Metro.light, Color.WHITE)
        block.addView(counts, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        block.addView(time)
        block.addView(date)
        content.addView(block, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        root.setOnTouchListener { _, e ->
            val t = tracker ?: VelocityTracker.obtain().also { tracker = it }
            t.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    dragging = false
                    content.animate().cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (abs(dy) > slop) dragging = true
                    if (dragging) content.translationY = if (dy < 0) dy else dy * 0.2f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    t.computeCurrentVelocity(1000)
                    val vy = t.yVelocity
                    tracker?.recycle()
                    tracker = null
                    when {
                        !dragging -> hop()
                        content.translationY < -content.height * 0.3f || vy < -content.dpf(900f) -> unlock()
                        else -> settle()
                    }
                }
            }
            true
        }
        return root
    }

    /** Tap: the screen hops up and bounces back, hinting at the swipe. */
    private fun hop() {
        content.animate().translationY(-dp(110).toFloat()).setDuration(200)
            .setInterpolator(DecelerateInterpolator()).withEndAction { settle() }.start()
    }

    private fun settle() {
        content.animate().translationY(0f).setDuration(650).setInterpolator(BounceInterpolator()).start()
    }

    private fun unlock() {
        content.animate().translationY(-content.height.toFloat()).setDuration(260)
            .setInterpolator(Metro.EASE).withEndAction { dismiss() }.start()
    }

    private fun dismiss() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = done()
                override fun onDismissCancelled() = settle()
                override fun onDismissError() = done()
            })
        } else {
            done()
        }
    }

    private fun done() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        content.translationY = 0f
        updateClock()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, tick, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(tick)
        } catch (e: Exception) {
        }
    }

    private fun updateClock() {
        val now = Date()
        val is24 = DateFormat.is24HourFormat(this)
        time.text = DateFormat.format(if (is24) "HH:mm" else "h:mm", now)
        date.text = DateFormat.format("EEEE\nMMMM d", now).toString().lowercase(Locale.getDefault())
        // Unread counts, WP style: small glyph + number.
        counts.removeAllViews()
        val top = Notifs.counts.entries.sortedByDescending { it.value }.take(5)
        for ((pkg, n) in top) {
            val app = Apps.list.firstOrNull { it.pkg == pkg } ?: continue
            counts.addView(ImageView(this).apply { setImageBitmap(app.glyph) }, LinearLayout.LayoutParams(dp(30), dp(30)))
            counts.addView(text(n.toString(), 20f, Metro.light, Color.WHITE).apply {
                setPadding(dp(2), 0, dp(16), 0)
            })
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // The lock screen only goes away with a swipe.
    }
}

/** Keeps the lock screen ready: shows it every time the screen turns off. */
class LockService : Service() {

    companion object {
        private const val CHANNEL = "lock"

        fun sync(c: Context) {
            val want = Prefs(c).lock && Settings.canDrawOverlays(c)
            val i = Intent(c, LockService::class.java)
            try {
                if (want) ContextCompat.startForegroundService(c, i) else c.stopService(i)
            } catch (e: Exception) {
            }
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) LockActivity.show(c)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "lock screen", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("metro lock screen is on")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, 7, n, type)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        ContextCompat.registerReceiver(this, screen, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs(this).lock) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screen)
        } catch (e: Exception) {
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        LockService.sync(c)
    }
}
