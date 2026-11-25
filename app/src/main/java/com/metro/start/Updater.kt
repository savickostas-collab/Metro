package com.metro.start

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Self-updater. Looks at the GitHub releases of this project, downloads the newest
 * MetroLauncher.apk and hands it to Android's installer. Every build is signed with the
 * same key, so Android treats it as an update and keeps your tiles and settings.
 */
object Updater {
    const val REPO = "savickostas-collab/Metro"
    private const val ASSET = "MetroLauncher.apk"
    private const val ACTION_RESULT = "com.metro.start.UPDATE_RESULT"
    private val TAG = Regex("""^v2\.0\.(\d+)$""")

    enum class State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, ERROR }

    class Release(val code: Long, val name: String, val url: String, val size: Long)

    @Volatile var state = State.IDLE
        private set
    @Volatile var release: Release? = null
        private set
    @Volatile var progress = 0
        private set
    @Volatile var error: String? = null
        private set

    val hasUpdate: Boolean get() = release != null

    private var wantInstall = false
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun listen(l: () -> Unit) = listeners.addIfAbsent(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)

    private fun set(s: State, e: String? = null) {
        state = s
        error = e
        main.post { for (l in listeners) l() }
    }

    // ---------------------------------------------------------------- parsing

    /** The newest v2.0.N release that carries MetroLauncher.apk, or null. Pure, so it's unit-tested. */
    fun pick(json: String): Release? {
        var best: Release? = null
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            val code = TAG.matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            for (k in 0 until assets.length()) {
                val a = assets.getJSONObject(k)
                if (a.optString("name") != ASSET) continue
                val url = a.optString("browser_download_url")
                if (url.isBlank()) continue
                if (best == null || code > best.code) best = Release(code, tag.removePrefix("v"), url, a.optLong("size"))
            }
        }
        return best
    }

    fun currentCode(c: Context): Long =
        PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0))

    fun currentName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    // ---------------------------------------------------------------- check

    private val busy get() = state == State.CHECKING || state == State.DOWNLOADING || state == State.INSTALLING

    /** Quiet check at most every 6 hours, if the user hasn't turned it off. */
    fun autoCheck(c: Context) {
        val p = Prefs(c)
        if (!p.updateCheck || busy) return
        if (System.currentTimeMillis() - p.lastUpdateCheck < 6 * 3_600_000L) return
        check(c, silent = true)
    }

    fun check(c: Context, silent: Boolean = false) {
        if (busy) return
        val app = c.applicationContext
        if (!silent) set(State.CHECKING)
        exec.execute {
            try {
                val con = open("https://api.github.com/repos/$REPO/releases?per_page=20")
                con.setRequestProperty("Accept", "application/vnd.github+json")
                val body = try {
                    if (con.responseCode != 200) throw IllegalStateException("github said ${con.responseCode}")
                    con.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    con.disconnect()
                }
                Prefs(app).lastUpdateCheck = System.currentTimeMillis()
                val best = pick(body)
                if (best != null && best.code > currentCode(app)) {
                    release = best
                    set(State.AVAILABLE)
                } else {
                    release = null
                    set(State.UP_TO_DATE)
                }
            } catch (e: Exception) {
                if (!silent) set(State.ERROR, "couldn't reach github")
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15_000
        con.readTimeout = 30_000
        con.setRequestProperty("User-Agent", "MetroLauncher")
        return con
    }

    // ---------------------------------------------------------------- download + install

    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    /** Download the update and install it. Asks for "install unknown apps" first if needed. */
    fun start(c: Context) {
        val r = release ?: return
        if (busy) return
        val app = c.applicationContext
        if (!canInstall(app)) {
            wantInstall = true
            try {
                c.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                set(State.ERROR, "allow metro to install apps in android settings")
            }
            return
        }
        wantInstall = false
        progress = 0
        set(State.DOWNLOADING)
        exec.execute { download(app, r) }
    }

    /** Called when settings comes back to the front after the permission screen. */
    fun resume(c: Context) {
        if (wantInstall && canInstall(c)) start(c)
    }

    private fun download(app: Context, r: Release) {
        try {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, ASSET)
            val con = open(r.url)
            try {
                if (con.responseCode != 200) throw IllegalStateException("download failed (${con.responseCode})")
                val total = con.contentLengthLong.takeIf { it > 0 } ?: r.size
                var done = 0L
                var lastPct = -1
                con.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    progress = pct
                                    set(State.DOWNLOADING)
                                }
                            }
                        }
                    }
                }
            } finally {
                con.disconnect()
            }
            // Make sure it really is a newer Metro before handing it to Android.
            val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
            if (info == null || info.packageName != app.packageName || PackageInfoCompat.getLongVersionCode(info) <= currentCode(app)) {
                file.delete()
                set(State.ERROR, "that download wasn't a newer metro build")
                return
            }
            set(State.INSTALLING)
            install(app, file)
        } catch (e: Exception) {
            set(State.ERROR, "download failed. check your connection")
        }
    }

    private fun install(app: Context, file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        // Android 12+: lets later updates install without a confirmation when allowed.
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("metro.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(app, InstallReceiver::class.java).setAction(ACTION_RESULT)
            val pending = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    internal fun onInstallResult(c: Context, i: Intent) {
        when (val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants a tap: show its "update this app?" screen.
                val confirm = IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)
                try {
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
                } catch (e: Exception) {
                    set(State.ERROR, "couldn't open the installer")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Normally the app is replaced and restarted before this is seen.
                release = null
                set(State.UP_TO_DATE)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> set(State.AVAILABLE)
            else -> {
                val why = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().lowercase()
                set(State.ERROR, if (why.isBlank()) "install failed ($status)" else "install failed: $why")
            }
        }
    }

    fun cleanup(c: Context) {
        try {
            File(c.cacheDir, "updates").deleteRecursively()
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- text for the UI

    fun summary(c: Context): String {
        val cur = currentName(c)
        val r = release
        return when (state) {
            State.CHECKING -> "checking for updates…"
            State.UP_TO_DATE -> "metro $cur is the latest · tap to check again"
            State.AVAILABLE -> "version ${r?.name} is ready · tap to download and install"
            State.DOWNLOADING -> "downloading ${r?.name}… $progress%"
            State.INSTALLING -> "installing ${r?.name}… confirm the android prompt if it appears"
            State.ERROR -> "${error ?: "something went wrong"} · tap to try again"
            State.IDLE -> "metro $cur · tap to check for updates"
        }
    }

    fun onTap(c: Context) {
        when (state) {
            State.AVAILABLE -> start(c)
            State.ERROR -> if (release != null) start(c) else check(c)
            State.IDLE, State.UP_TO_DATE -> check(c)
            else -> {}
        }
    }
}

/** Receives the installer's answer for an update session. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Updater.onInstallResult(c.applicationContext, i)
}
