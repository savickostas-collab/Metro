package com.metro.start

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.Executors

class CallEntry(val number: String, val name: String?, val type: Int, val date: Long, var count: Int = 1)
class Contact(val id: Long, val name: String, val numbers: List<Pair<String, String>>, val photo: String?, val starred: Boolean) {
    val letter: Char = AppInfo.letterOf(name.lowercase(Locale.getDefault()))
}

/** Metro phone: history, a keypad and contacts. */
class PhoneActivity : PivotActivity() {
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var historyRows: Rows
    private lateinit var contactRows: Rows
    private lateinit var historyList: RecyclerView
    private lateinit var contactList: RecyclerView
    private lateinit var historyPage: FrameLayout
    private lateinit var contactPage: FrameLayout
    private lateinit var dialPage: LinearLayout
    private lateinit var number: TextView
    private lateinit var match: TextView
    private var historyEmpty: View? = null
    private var contactEmpty: View? = null
    private var calls: List<CallEntry> = emptyList()
    private var contacts: List<Contact> = emptyList()
    private val headerPos = HashMap<Char, Int>()
    private var tones: ToneGenerator? = null
    private val photos = LruCache<String, Bitmap>(80)
    private var asked = false

    private val needed = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Metro.fonts(this)
        historyRows = Rows({ 0 }, { p, _ -> twoLineRow(p.context) }, { v, item -> bindCall(v, item as CallEntry) })
        historyList = metroList(historyRows)
        historyPage = FrameLayout(this).apply { addView(historyList, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }

        contactRows = Rows(
            { if (it is Char) 0 else 1 },
            { p, t -> if (t == 0) letterHeader(p.context) else contactRow(p.context) },
            { v, item -> if (item is Char) bindLetter(v, item) else bindContact(v, item as Contact) }
        )
        contactList = metroList(contactRows)
        contactPage = FrameLayout(this).apply { addView(contactList, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }

        dialPage = buildDialPad()
        setupPivot("metro phone", listOf("history" to historyPage, "keypad" to dialPage, "contacts" to contactPage))
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Other apps can open Metro as the dialer with a number filled in. */
    private fun handleIntent(i: Intent?) {
        val data = i?.data
        if (i?.action == Intent.ACTION_DIAL || data?.scheme == "tel") {
            val n = data?.schemeSpecificPart.orEmpty()
            setNumber(Uri.decode(n))
            pivot.post { pivot.go(1, false) }
        }
    }

    override fun onBottomInset(bottom: Int) {
        historyList.setPadding(0, 0, 0, bottom + dp(12))
        contactList.setPadding(0, 0, 0, bottom + dp(12))
        dialPage.setPadding(dp(16), 0, dp(16), bottom + dp(12))
    }

    override fun onResume() {
        super.onResume()
        val missing = needed.filter { !Live.granted(this, it) }
        if (missing.isNotEmpty() && !asked) {
            asked = true
            requestPermissions(missing.toTypedArray(), 4)
        }
        load()
        try {
            if (tones == null) tones = ToneGenerator(AudioManager.STREAM_DTMF, 70)
        } catch (e: Exception) {
        }
    }

    override fun onPause() {
        super.onPause()
        tones?.release()
        tones = null
        // Opening history counts as seeing your missed calls.
        if (pivot.page == 0 && Live.granted(this, Manifest.permission.READ_CALL_LOG)) markSeen()
    }

    private fun markSeen() {
        Prefs(this).phoneSeen = System.currentTimeMillis()
        Live.invalidateCalls()
        Live.refresh(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        load()
    }

    // ================================================================ data

    private fun load() {
        val app = applicationContext
        exec.execute {
            val cl = if (Live.granted(app, Manifest.permission.READ_CALL_LOG)) safeLoad { loadCalls(app) } else null
            val ct = if (Live.granted(app, Manifest.permission.READ_CONTACTS)) safeLoad { loadContacts(app) } else null
            runOnUiThread {
                calls = cl.orEmpty()
                contacts = ct.orEmpty()
                renderHistory(cl != null)
                renderContacts(ct != null)
                updateMatch()
            }
        }
    }

    private inline fun <T> safeLoad(f: () -> T): T? = try {
        f()
    } catch (e: Exception) {
        null
    }

    private fun loadCalls(c: Context): List<CallEntry> {
        val out = ArrayList<CallEntry>()
        c.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null, null, "${CallLog.Calls.DATE} DESC"
        )?.use { cur ->
            var rows = 0
            while (cur.moveToNext() && rows < 300) {
                rows++
                val e = CallEntry(cur.getString(0).orEmpty(), cur.getString(1)?.takeIf { it.isNotBlank() }, cur.getInt(2), cur.getLong(3))
                val last = out.lastOrNull()
                // Fold repeated calls to the same number of the same kind, like WP did: "missed (3)".
                if (last != null && last.number == e.number && kindOf(last.type) == kindOf(e.type) &&
                    last.date - e.date < 6 * 3_600_000L
                ) last.count++ else out.add(e)
                if (out.size >= 100) break
            }
        }
        return out
    }

    private fun loadContacts(c: Context): List<Contact> {
        val map = LinkedHashMap<Long, MutableList<Pair<String, String>>>()
        val meta = HashMap<Long, Triple<String, String?, Boolean>>()
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                ContactsContract.CommonDataKinds.Phone.STARRED
            ), null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val name = cur.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val num = cur.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(c.resources, cur.getInt(3), cur.getString(4))
                    .toString().lowercase(Locale.getDefault())
                val list = map.getOrPut(id) { ArrayList() }
                val digits = PhoneNumberUtils.normalizeNumber(num)
                if (list.none { PhoneNumberUtils.normalizeNumber(it.second) == digits }) list.add(label to num)
                meta[id] = Triple(name, cur.getString(5), cur.getInt(6) == 1)
            }
        }
        val out = map.map { (id, nums) ->
            val m = meta[id]!!
            Contact(id, m.first, nums, m.second, m.third)
        }
        val coll = java.text.Collator.getInstance()
        return out.sortedWith { a, b ->
            when {
                a.letter == b.letter -> coll.compare(a.name, b.name)
                a.letter == '#' -> 1
                b.letter == '#' -> -1
                else -> a.letter.compareTo(b.letter)
            }
        }
    }

    // ================================================================ history

    private fun kindOf(type: Int) = when (type) {
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "missed"
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "call"
    }

    private fun renderHistory(allowed: Boolean) {
        historyEmpty?.let { historyPage.removeView(it) }
        historyEmpty = null
        historyRows.items = calls
        if (calls.isNotEmpty()) return
        val e = if (!allowed) emptyState("no history yet", "metro phone needs access to your call log to show recent calls.", "allow") { askAgain() }
        else emptyState("no calls yet", "calls you make and get will show up here.", null, null)
        historyPage.addView(e, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        historyEmpty = e
    }

    private fun bindCall(v: View, e: CallEntry) {
        val row = v as LinearLayout
        val title = row.getChildAt(0) as TextView
        val sub = row.getChildAt(1) as TextView
        title.text = (e.name ?: pretty(e.number)).lowercase(Locale.getDefault())
        val kind = kindOf(e.type)
        val count = if (e.count > 1) " (${e.count})" else ""
        sub.text = "$kind$count · ${When.text(this, e.date)}"
        val missed = kind == "missed"
        title.setTextColor(if (missed) Look.accentShade else Look.fg)
        sub.setTextColor(if (missed) Look.accentShade else Look.fg60)
        row.setOnClickListener { call(e.number) }
        row.setOnLongClickListener {
            val items = arrayListOf<Pair<String, () -> Unit>>(
                "call" to { call(e.number) },
                "text" to { sms(e.number) },
                "edit before call" to {
                    setNumber(e.number)
                    pivot.go(1)
                },
                "copy number" to { copy(e.number) }
            )
            contactFor(e.number)?.let { ct -> items.add("view contact" to { openContact(ct.id) }) }
                ?: items.add("save to contacts" to { saveContact(e.number) })
            show(MetroMenu(this, items, row))
            true
        }
    }

    // ================================================================ contacts

    private fun renderContacts(allowed: Boolean) {
        contactEmpty?.let { contactPage.removeView(it) }
        contactEmpty = null
        val rows = ArrayList<Any>()
        headerPos.clear()
        val favs = contacts.filter { it.starred }
        if (favs.isNotEmpty()) {
            headerPos['★'] = rows.size
            rows.add('★')
            rows.addAll(favs)
        }
        var cur = ' '
        for (c in contacts) {
            if (c.letter != cur) {
                cur = c.letter
                headerPos[cur] = rows.size
                rows.add(cur)
            }
            rows.add(c)
        }
        contactRows.items = rows
        if (contacts.isNotEmpty()) return
        val e = if (!allowed) emptyState("no contacts", "metro phone needs access to your contacts.", "allow") { askAgain() }
        else emptyState("no contacts", "contacts with phone numbers will show up here.", null, null)
        contactPage.addView(e, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        contactEmpty = e
    }

    private fun letterHeader(c: Context): View {
        val frame = FrameLayout(c).apply { layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, dp(64)) }
        val sq = c.text("", 26f, Metro.light, Color.WHITE).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(6), 0, 0, dp(1))
            includeFontPadding = false
            isClickable = true
        }
        Tilt.attach(sq, 10f)
        frame.addView(sq, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL).apply { leftMargin = dp(20) })
        return frame
    }

    private fun bindLetter(v: View, ch: Char) {
        val sq = (v as FrameLayout).getChildAt(0) as TextView
        sq.text = ch.toString()
        sq.setBackgroundColor(if (ch == '★') Look.accentShade else Look.letterColor(ch))
        sq.setOnClickListener {
            show(JumpGrid(this, headerPos.keys) { pick ->
                headerPos[pick]?.let { (contactList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(it, 0) }
            })
        }
    }

    private fun contactRow(c: Context): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, dp(64))
            setPadding(dp(20), 0, dp(20), 0)
            isClickable = true
            isLongClickable = true
        }
        row.addView(Avatar(c), LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(c.text("", 22f, Metro.light).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = dp(14) })
        Tilt.attach(row, 2f)
        return row
    }

    private fun bindContact(v: View, ct: Contact) {
        val row = v as LinearLayout
        val av = row.getChildAt(0) as Avatar
        av.name = ct.name
        av.color = Look.letterColor(ct.letter)
        av.photo = ct.photo?.let { photo(it) }
        (row.getChildAt(1) as TextView).text = ct.name.lowercase(Locale.getDefault())
        val menu = {
            val items = ArrayList<Pair<String, () -> Unit>>()
            for ((label, num) in ct.numbers) items.add("call $label" to { call(num) })
            items.add("text" to { sms(ct.numbers.first().second) })
            items.add("view contact" to { openContact(ct.id) })
            show(MetroMenu(this, items, row))
        }
        // One number: tap calls. More than one: tap asks which.
        row.setOnClickListener { if (ct.numbers.size == 1) call(ct.numbers[0].second) else menu() }
        row.setOnLongClickListener {
            menu()
            true
        }
    }

    private fun photo(uri: String): Bitmap? {
        photos.get(uri)?.let { return it }
        return try {
            contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }?.also { photos.put(uri, it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun contactFor(number: String): Contact? {
        val n = PhoneNumberUtils.normalizeNumber(number)
        if (n.length < 3) return null
        return contacts.firstOrNull { c -> c.numbers.any { PhoneNumberUtils.normalizeNumber(it.second).endsWith(n.takeLast(9)) } }
    }

    // ================================================================ keypad

    private fun buildDialPad(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        number = text("", 40f, Metro.light).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.START
            setAutoSizeTextTypeUniformWithConfiguration(20, 40, 2, TypedValue.COMPLEX_UNIT_SP)
            setPadding(dp(4), 0, dp(4), 0)
        }
        match = text("", 16f, Metro.regular, Look.accentShade).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), 0)
            isClickable = true
        }
        page.addView(number, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))
        page.addView(match, LinearLayout.LayoutParams(MATCH_PARENT, dp(26)))
        val space = View(this)
        page.addView(space, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val keys = listOf(
            "1" to "", "2" to "abc", "3" to "def",
            "4" to "ghi", "5" to "jkl", "6" to "mno",
            "7" to "pqrs", "8" to "tuv", "9" to "wxyz",
            "*" to "", "0" to "+", "#" to ""
        )
        val g = dp(6)
        for (r in 0 until 4) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 3) {
                val (d, l) = keys[r * 3 + k]
                val key = DialKey(this, d, l)
                key.onPress = {
                    tone(d)
                    append(d)
                }
                if (d == "0") key.onLong = {
                    backspace()
                    append("+")
                }
                row.addView(key, LinearLayout.LayoutParams(0, dp(62), 1f).apply { if (k < 2) rightMargin = g })
            }
            page.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = g })
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val callKey = DialKey(this, "call", "", accent = true).apply {
            onPress = { callTyped() }
        }
        val back = DialKey(this, "⌫", "").apply {
            onPress = { backspace() }
            onLong = { setNumber("") }
        }
        bottom.addView(callKey, LinearLayout.LayoutParams(0, dp(62), 2f).apply { rightMargin = g })
        bottom.addView(back, LinearLayout.LayoutParams(0, dp(62), 1f))
        page.addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = g })
        return page
    }

    private var typed = ""

    private fun setNumber(n: String) {
        typed = n
        number.text = pretty(typed)
        updateMatch()
    }

    private fun append(d: String) = setNumber(typed + d)
    private fun backspace() = setNumber(typed.dropLast(1))

    private fun updateMatch() {
        if (!::match.isInitialized) return
        val digits = PhoneNumberUtils.normalizeNumber(typed)
        val hit = if (digits.length >= 3) contacts.firstNotNullOfOrNull { c ->
            c.numbers.firstOrNull { PhoneNumberUtils.normalizeNumber(it.second).contains(digits) }?.let { c to it.second }
        } else null
        match.text = hit?.let { "${it.first.name.lowercase(Locale.getDefault())} · tap to use" }.orEmpty()
        match.setOnClickListener { hit?.let { setNumber(it.second) } }
    }

    private fun callTyped() {
        if (typed.isBlank()) {
            // Empty keypad: bring back the last number you dialled.
            calls.firstOrNull { it.type == CallLog.Calls.OUTGOING_TYPE }?.let { setNumber(it.number) }
            return
        }
        call(typed)
    }

    private fun tone(d: String) {
        val t = when (d) {
            "0" -> ToneGenerator.TONE_DTMF_0
            "1" -> ToneGenerator.TONE_DTMF_1
            "2" -> ToneGenerator.TONE_DTMF_2
            "3" -> ToneGenerator.TONE_DTMF_3
            "4" -> ToneGenerator.TONE_DTMF_4
            "5" -> ToneGenerator.TONE_DTMF_5
            "6" -> ToneGenerator.TONE_DTMF_6
            "7" -> ToneGenerator.TONE_DTMF_7
            "8" -> ToneGenerator.TONE_DTMF_8
            "9" -> ToneGenerator.TONE_DTMF_9
            "*" -> ToneGenerator.TONE_DTMF_S
            else -> ToneGenerator.TONE_DTMF_P
        }
        try {
            val am = getSystemService(AudioManager::class.java)
            if (am?.ringerMode == AudioManager.RINGER_MODE_NORMAL) tones?.startTone(t, 120)
        } catch (e: Exception) {
        }
    }

    // ================================================================ actions

    private fun pretty(n: String): String =
        PhoneNumberUtils.formatNumber(n, Locale.getDefault().country) ?: n

    private fun call(n: String) {
        if (n.isBlank()) return
        val uri = Uri.fromParts("tel", n, null)
        val i = if (Live.granted(this, Manifest.permission.CALL_PHONE)) {
            Intent(Intent.ACTION_CALL, uri)
        } else {
            // Without call permission, hand the number to the system dialer (never back to Metro).
            val dialer = getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            Intent(Intent.ACTION_DIAL, uri).setPackage(dialer?.takeIf { it != packageName })
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "can't call from this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sms(n: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", n, null)))
        } catch (e: Exception) {
        }
    }

    private fun copy(n: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("number", n))
        Toast.makeText(this, "number copied", Toast.LENGTH_SHORT).show()
    }

    private fun openContact(id: Long) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)))
        } catch (e: Exception) {
        }
    }

    private fun saveContact(n: String) {
        try {
            startActivity(
                Intent(ContactsContract.Intents.Insert.ACTION)
                    .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, n)
            )
        } catch (e: Exception) {
        }
    }

    private fun askAgain() {
        val missing = needed.filter { !Live.granted(this, it) }
        if (missing.any { !shouldShowRequestPermissionRationale(it) } && asked) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            asked = true
            requestPermissions(missing.toTypedArray(), 4)
        }
    }

    private fun twoLineRow(c: Context): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(9), dp(20), dp(9))
            isClickable = true
            isLongClickable = true
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        row.addView(c.text("", 22f, Metro.light).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
        row.addView(c.text("", 14f, Metro.regular, Look.fg60).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
        Tilt.attach(row, 2f)
        return row
    }
}

/** Square contact picture, or a coloured square with the initial. */
class Avatar(context: Context) : View(context) {
    var name = ""
        set(v) {
            field = v
            invalidate()
        }
    var color = Look.accentShade
        set(v) {
            field = v
            invalidate()
        }
    var photo: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val b = photo
        if (b != null) {
            r.set(0f, 0f, w, h)
            c.drawBitmap(b, null, r, p)
            return
        }
        p.color = color
        c.drawRect(0f, 0f, w, h, p)
        p.color = Color.WHITE
        p.typeface = Metro.light
        p.textSize = h * 0.55f
        p.textAlign = Paint.Align.CENTER
        val fm = p.fontMetrics
        val ch = name.trim().firstOrNull()?.lowercase() ?: "?"
        c.drawText(ch, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, p)
    }
}

/** Keypad key: flat grey (or accent), digit on the left, letters beside it. Flashes accent when pressed. */
@SuppressLint("ViewConstructor")
class DialKey(context: Context, private val label: String, private val sub: String, private val accent: Boolean = false) : View(context) {
    var onPress: (() -> Unit)? = null
    var onLong: (() -> Unit)? = null
    private var down = false
    private var longFired = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val longPress = Runnable {
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLong?.invoke()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = when {
            down -> Look.accentShade
            accent -> Look.accentShade
            else -> Look.fg15
        }
        if (down && accent) p.color = scale(Look.accentShade, 0.8f)
        c.drawRect(0f, 0f, w, h, p)
        if (label == "⌫") {
            drawBackspace(c, w, h)
            return
        }
        val word = label.length > 1
        p.color = if (down || accent) Color.WHITE else Look.fg
        p.typeface = if (word) Metro.regular else Metro.light
        p.textSize = if (word) dpf(22f) else dpf(32f)
        p.textAlign = Paint.Align.LEFT
        val fm = p.fontMetrics
        val base = h / 2f - (fm.ascent + fm.descent) / 2f
        val dw = p.measureText(label)
        val subSize = dpf(13f)
        p.textSize = subSize
        val sw = if (sub.isEmpty()) 0f else p.measureText(sub) + dpf(8f)
        p.textSize = if (word) dpf(22f) else dpf(32f)
        val x = (w - dw - sw) / 2f
        c.drawText(label, x, base, p)
        if (sub.isNotEmpty()) {
            p.textSize = subSize
            p.typeface = Metro.regular
            p.color = if (down) Color.WHITE else Look.fg60
            c.drawText(sub, x + dw + dpf(8f), base, p)
        }
    }

    private fun drawBackspace(c: Canvas, w: Float, h: Float) {
        val u = dpf(1f)
        val cx = w / 2f
        val cy = h / 2f
        p.color = if (down) Color.WHITE else Look.fg
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f * u
        p.strokeJoin = Paint.Join.MITER
        val path = android.graphics.Path()
        path.moveTo(cx - 16 * u, cy)
        path.lineTo(cx - 8 * u, cy - 10 * u)
        path.lineTo(cx + 16 * u, cy - 10 * u)
        path.lineTo(cx + 16 * u, cy + 10 * u)
        path.lineTo(cx - 8 * u, cy + 10 * u)
        path.close()
        c.drawPath(path, p)
        c.drawLine(cx - 1 * u, cy - 5 * u, cx + 9 * u, cy + 5 * u, p)
        c.drawLine(cx - 1 * u, cy + 5 * u, cx + 9 * u, cy - 5 * u, p)
        p.style = Paint.Style.FILL
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true
                longFired = false
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (onLong != null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                // The press registers immediately, like a real keypad; long-press then corrects it.
                if (label != "call") onPress?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (label == "call" && down && !longFired) onPress?.invoke()
                down = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                down = false
                invalidate()
            }
        }
        return true
    }
}
