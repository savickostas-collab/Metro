package com.metro.launcher

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Feeds live tiles: counts active notifications per package and remembers
 * the newest title/text so tiles can flip over and show it.
 * Only runs after the user grants notification access.
 */
class NotifService : NotificationListenerService() {

    companion object {
        val counts = HashMap<String, Int>()
        val latest = HashMap<String, Pair<String, String>>()
        var listener: (() -> Unit)? = null

        fun isEnabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")
                ?: return false
            return flat.contains(c.packageName)
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onListenerConnected() = rebuild()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = rebuild()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = rebuild()

    override fun onListenerDisconnected() {
        counts.clear()
        latest.clear()
        main.post { listener?.invoke() }
    }

    private fun rebuild() {
        val active = try {
            activeNotifications
        } catch (e: Exception) {
            null
        } ?: return

        val c = HashMap<String, Int>()
        val l = HashMap<String, Pair<String, String>>()
        val times = HashMap<String, Long>()

        for (sbn in active) {
            val n = sbn.notification ?: continue
            if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) continue
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            val pkg = sbn.packageName
            c[pkg] = (c[pkg] ?: 0) + 1

            if (n.visibility == Notification.VISIBILITY_SECRET) continue
            val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) continue
            val t = sbn.postTime
            if (t >= (times[pkg] ?: 0L)) {
                times[pkg] = t
                l[pkg] = Pair(title, text)
            }
        }

        counts.clear(); counts.putAll(c)
        latest.clear(); latest.putAll(l)
        main.post { listener?.invoke() }
    }
}
