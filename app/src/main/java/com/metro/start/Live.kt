package com.metro.start

import android.Manifest
import android.app.Notification
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.format.DateFormat
import android.util.Size
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

// ===================================================================== notifications

object Notifs {
    val counts = ConcurrentHashMap<String, Int>()
    val latest = ConcurrentHashMap<String, Pair<String, String>>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(l: () -> Unit) = listeners.addIfAbsent(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)
    fun fire() = main.post { for (l in listeners) l() }

    fun enabled(c: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
}

/** Counts unread notifications per app and keeps the newest one for the back of the tile. */
class NotificationFeed : NotificationListenerService() {
    override fun onListenerConnected() {
        rebuild()
        Media.attach(applicationContext)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = rebuild()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = rebuild()

    override fun onListenerDisconnected() {
        Notifs.counts.clear()
        Notifs.latest.clear()
        Notifs.fire()
    }

    private fun rebuild() {
        val active = try {
            activeNotifications
        } catch (e: Exception) {
            null
        } ?: return
        val counts = HashMap<String, Int>()
        val latest = HashMap<String, Pair<String, String>>()
        val times = HashMap<String, Long>()
        for (sbn in active) {
            val n = sbn.notification ?: continue
            if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) continue
            if ((n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) continue
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            val pkg = sbn.packageName
            counts[pkg] = (counts[pkg] ?: 0) + 1
            if (n.visibility == Notification.VISIBILITY_SECRET) continue
            val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) continue
            if (sbn.postTime >= (times[pkg] ?: 0L)) {
                times[pkg] = sbn.postTime
                latest[pkg] = title to text
            }
        }
        Notifs.counts.clear(); Notifs.counts.putAll(counts)
        Notifs.latest.clear(); Notifs.latest.putAll(latest)
        Notifs.fire()
    }
}

// ===================================================================== photos, people, calendar, weather, calls

class CalEvent(val title: String, val time: String, val place: String)
class Hour(val label: String, val temp: Int, val code: Int, val rain: Int)
class Day(val label: String, val code: Int, val hi: Int, val lo: Int, val rain: Int)
class City(val name: String, val detail: String, val lat: Double, val lon: Double) {
    fun json(): String = JSONObject().put("name", name).put("detail", detail).put("lat", lat).put("lon", lon).toString()

    companion object {
        fun parse(s: String?): City? = try {
            if (s == null) null else JSONObject(s).let {
                City(it.getString("name"), it.optString("detail"), it.getDouble("lat"), it.getDouble("lon"))
            }
        } catch (e: Exception) {
            null
        }
    }
}

class Weather(
    val temp: Int, val desc: String, val hi: Int, val lo: Int, val place: String,
    val code: Int, val feels: Int, val humidity: Int, val wind: Int, val windUnit: String,
    val sunrise: String, val sunset: String,
    val hours: List<Hour>, val days: List<Day>, val updated: Long
)

object Live {
    @Volatile var photos: List<Bitmap> = emptyList(); private set
    @Volatile var people: List<Bitmap> = emptyList(); private set
    @Volatile var event: CalEvent? = null; private set
    @Volatile var weather: Weather? = null; private set
    @Volatile var weatherError = false; private set
    @Volatile var missed = 0; private set
    @Volatile var lastMissed: String? = null; private set

    private var photosAt = 0L
    private var peopleAt = 0L
    private var eventAt = 0L
    private var weatherAt = 0L
    private var callsAt = 0L

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun listen(l: () -> Unit) = listeners.addIfAbsent(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)
    private fun changed() = main.post { for (l in listeners) l() }

    val photoPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(c: Context, perm: String) =
        c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun invalidateWeather() {
        weatherAt = 0L
    }

    fun invalidateCalls() {
        callsAt = 0L
    }

    fun refresh(c: Context, force: Boolean = false) {
        val app = c.applicationContext
        exec.execute {
            val now = SystemClock.elapsedRealtime()
            var any = false
            if (force || now - photosAt > 120_000) {
                photosAt = now
                photos = if (granted(app, photoPermission)) safe { loadPhotos(app) } ?: photos else emptyList()
                any = true
            }
            if (force || now - peopleAt > 600_000) {
                peopleAt = now
                people = if (granted(app, Manifest.permission.READ_CONTACTS)) safe { loadPeople(app) } ?: people else emptyList()
                any = true
            }
            if (force || now - eventAt > 60_000) {
                eventAt = now
                event = if (granted(app, Manifest.permission.READ_CALENDAR)) safe { loadEvent(app) } else null
                any = true
            }
            if (force || now - callsAt > 20_000) {
                callsAt = now
                if (granted(app, Manifest.permission.READ_CALL_LOG)) safe { loadMissed(app) } else {
                    missed = 0
                    lastMissed = null
                }
                any = true
            }
            if (force || now - weatherAt > 30 * 60_000) {
                weatherAt = now
                val city = City.parse(Prefs(app).city)
                if (city != null) {
                    val w = safe { fetchWeather(app, city.lat, city.lon, city.name) }
                    if (w != null) weather = w else weatherAt = 0L
                    weatherError = w == null
                } else if (granted(app, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    val loc = safe { location(app) }
                    if (loc != null) {
                        val w = safe { fetchWeather(app, loc.latitude, loc.longitude, null) }
                        if (w != null) weather = w else weatherAt = 0L
                        weatherError = w == null
                    } else {
                        weatherAt = 0L
                    }
                } else {
                    weather = null
                }
                any = true
            }
            if (any) changed()
        }
    }

    private inline fun <T> safe(f: () -> T): T? = try {
        f()
    } catch (e: Throwable) {
        null
    }

    private fun loadMissed(c: Context) {
        val seen = Prefs(c).phoneSeen
        var n = 0
        var who: String? = null
        c.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls.DATE} > ?",
            arrayOf(CallLog.Calls.MISSED_TYPE.toString(), seen.toString()),
            "${CallLog.Calls.DATE} DESC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                if (n == 0) who = cur.getString(0)?.takeIf { it.isNotBlank() } ?: cur.getString(1)
                n++
            }
        }
        missed = n
        lastMissed = who
    }

    private fun loadPhotos(c: Context): List<Bitmap> {
        val cr = c.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<Bitmap>()
        cr.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cur ->
            while (cur.moveToNext() && out.size < 10) {
                val id = cur.getLong(0)
                val bmp = try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        cr.loadThumbnail(ContentUris.withAppendedId(uri, id), Size(480, 480), null)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Thumbnails.getThumbnail(cr, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    }
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) out.add(bmp)
            }
        }
        return out
    }

    private fun loadPeople(c: Context): List<Bitmap> {
        val cr = c.contentResolver
        val uris = ArrayList<String>()
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
            "${ContactsContract.Contacts.PHOTO_THUMBNAIL_URI} IS NOT NULL", null, null
        )?.use { cur ->
            while (cur.moveToNext() && uris.size < 200) cur.getString(0)?.let { uris.add(it) }
        }
        uris.shuffle()
        val out = ArrayList<Bitmap>()
        for (u in uris) {
            if (out.size >= 24) break
            try {
                cr.openInputStream(Uri.parse(u))?.use { BitmapFactory.decodeStream(it) }?.let { out.add(it) }
            } catch (e: Exception) {
            }
        }
        return out
    }

    private fun loadEvent(c: Context): CalEvent? {
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now)
        ContentUris.appendId(b, now + 7L * 86_400_000L)
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION
        )
        c.contentResolver.query(b.build(), proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
            while (cur.moveToNext()) {
                val end = cur.getLong(2)
                if (end < now) continue
                val title = cur.getString(0)?.takeIf { it.isNotBlank() } ?: "busy"
                val begin = cur.getLong(1)
                val allDay = cur.getInt(3) == 1
                return CalEvent(title, When.text(c, begin, allDay), cur.getString(4).orEmpty())
            }
        }
        return null
    }

    @Suppress("MissingPermission")
    private fun location(c: Context): Location? {
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                null
            } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        if (best == null && Build.VERSION.SDK_INT >= 30) {
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider != null) {
                lm.getCurrentLocation(provider, null, exec) { loc ->
                    if (loc != null) {
                        safe { fetchWeather(c, loc.latitude, loc.longitude, null) }?.let {
                            weather = it
                            weatherError = false
                            weatherAt = SystemClock.elapsedRealtime()
                            changed()
                        }
                    }
                }
            }
        }
        return best
    }

    fun fahrenheit(c: Context): Boolean = when (Prefs(c).tempUnit) {
        1 -> false
        2 -> true
        else -> Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")
    }

    private fun get(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 8000
        con.readTimeout = 8000
        return try {
            con.inputStream.bufferedReader().use { it.readText() }
        } finally {
            con.disconnect()
        }
    }

    private fun fetchWeather(c: Context, lat: Double, lon: Double, placeName: String?): Weather {
        val f = fahrenheit(c)
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code" +
                "&hourly=temperature_2m,weather_code,precipitation_probability" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
                "&forecast_days=7&timezone=auto%s",
            lat, lon, if (f) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""
        )
        val j = JSONObject(get(url))
        val cur = j.getJSONObject("current")
        val hourly = j.getJSONObject("hourly")
        val daily = j.getJSONObject("daily")

        // Hourly: the next 24 hours, starting with the current one.
        val nowHour = cur.getString("time").take(13)
        val hTimes = hourly.getJSONArray("time")
        val hTemp = hourly.getJSONArray("temperature_2m")
        val hCode = hourly.getJSONArray("weather_code")
        val hRain = hourly.optJSONArray("precipitation_probability")
        val hours = ArrayList<Hour>()
        for (i in 0 until hTimes.length()) {
            val t = hTimes.getString(i)
            if (t.take(13) < nowHour) continue
            if (hours.size >= 24) break
            val label = if (hours.isEmpty()) "now" else When.clock(c, t.substring(11, 13).toInt(), 0, hourOnly = true)
            hours.add(Hour(label, hTemp.getDouble(i).roundToInt(), hCode.optInt(i), hRain?.optInt(i) ?: 0))
        }

        val dTimes = daily.getJSONArray("time")
        val days = ArrayList<Day>()
        for (i in 0 until dTimes.length()) {
            val label = when (i) {
                0 -> "today"
                1 -> "tomorrow"
                else -> When.dayName(dTimes.getString(i))
            }
            days.add(
                Day(
                    label, daily.getJSONArray("weather_code").optInt(i),
                    daily.getJSONArray("temperature_2m_max").getDouble(i).roundToInt(),
                    daily.getJSONArray("temperature_2m_min").getDouble(i).roundToInt(),
                    daily.optJSONArray("precipitation_probability_max")?.optInt(i) ?: 0
                )
            )
        }
        fun sun(key: String): String = try {
            val s = daily.getJSONArray(key).getString(0)
            When.clock(c, s.substring(11, 13).toInt(), s.substring(14, 16).toInt())
        } catch (e: Exception) {
            ""
        }

        val place = placeName ?: try {
            @Suppress("DEPRECATION")
            Geocoder(c, Locale.getDefault()).getFromLocation(lat, lon, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        } catch (e: Exception) {
            null
        }
        val code = cur.getInt("weather_code")
        return Weather(
            cur.getDouble("temperature_2m").roundToInt(),
            describe(code),
            days.firstOrNull()?.hi ?: 0,
            days.firstOrNull()?.lo ?: 0,
            place?.lowercase(Locale.getDefault()).orEmpty(),
            code,
            cur.optDouble("apparent_temperature", 0.0).roundToInt(),
            cur.optDouble("relative_humidity_2m", 0.0).roundToInt(),
            cur.optDouble("wind_speed_10m", 0.0).roundToInt(),
            if (f) "mph" else "km/h",
            sun("sunrise"), sun("sunset"),
            hours, days, System.currentTimeMillis()
        )
    }

    /** City search for weather without location access (blocking; call off the main thread). */
    fun searchCities(q: String): List<City> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=8&format=json&language=" +
            Locale.getDefault().language + "&name=" + URLEncoder.encode(q, "UTF-8")
        val r = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
        return List(r.length()) { i ->
            val o = r.getJSONObject(i)
            val detail = listOf(o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
            City(
                o.getString("name").lowercase(Locale.getDefault()), detail.lowercase(Locale.getDefault()),
                o.getDouble("latitude"), o.getDouble("longitude")
            )
        }
    }

    fun describe(code: Int) = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "cloudy"
        45, 48 -> "fog"
        in 51..57 -> "drizzle"
        in 61..67 -> "rain"
        in 71..77 -> "snow"
        in 80..82 -> "showers"
        85, 86 -> "snow showers"
        in 95..99 -> "thunderstorms"
        else -> ""
    }
}

/** Lowercase, Metro-style dates and times. */
object When {
    fun clock(c: Context, h: Int, m: Int, hourOnly: Boolean = false): String {
        if (DateFormat.is24HourFormat(c)) return String.format(Locale.US, "%02d:%02d", h, m)
        val h12 = if (h % 12 == 0) 12 else h % 12
        val ap = if (h < 12) "am" else "pm"
        return if (hourOnly) "$h12 $ap" else String.format(Locale.US, "%d:%02d %s", h12, m, ap)
    }

    fun dayName(isoDate: String): String = try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate)
        DateFormat.format("EEEE", d).toString().lowercase(Locale.getDefault())
    } catch (e: Exception) {
        isoDate
    }

    fun text(c: Context, t: Long, allDay: Boolean = false): String {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrow = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.add(Calendar.DAY_OF_YEAR, -2)
        val yesterday = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.timeInMillis = t
        val day = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        val age = System.currentTimeMillis() - t
        val dayText = when {
            day == today -> "today"
            day == tomorrow -> "tomorrow"
            day == yesterday -> "yesterday"
            abs(age) < 6L * 86_400_000L -> DateFormat.format("EEEE", Date(t)).toString().lowercase(Locale.getDefault())
            else -> DateFormat.getMediumDateFormat(c).format(Date(t)).lowercase(Locale.getDefault())
        }
        if (allDay) return "$dayText, all day"
        val time = clock(c, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        return if (day == today) time else "$dayText $time"
    }
}
