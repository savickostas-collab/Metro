package com.metro.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Section letter for the app list. Anything that isn't A-Z goes under "#". */
fun letterOf(label: String): String {
    val ch = label.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (ch in 'A'..'Z') ch.toString() else "#"
}

class AppEntry(
    val label: String,
    val pkg: String,
    val cls: String,
    val icon: Drawable,
    val isSystem: Boolean
) {
    val key: String get() = "$pkg/$cls"
    val component: ComponentName get() = ComponentName(pkg, cls)
}

/** One pinned tile. size: 0 = small (1x1), 1 = medium (2x2), 2 = wide (4x2). */
class TileData(val key: String, var size: Int) {
    val isClock: Boolean get() = key == CLOCK

    companion object {
        const val CLOCK = "clock"
        const val SMALL = 0
        const val MEDIUM = 1
        const val WIDE = 2
    }
}

object Prefs {
    // The classic Windows Phone 8 accent palette.
    val ACCENTS = intArrayOf(
        0xFFA4C400.toInt(), 0xFF60A917.toInt(), 0xFF008A00.toInt(), 0xFF00ABA9.toInt(),
        0xFF1BA1E2.toInt(), 0xFF0050EF.toInt(), 0xFF6A00FF.toInt(), 0xFFAA00FF.toInt(),
        0xFFF472D0.toInt(), 0xFFD80073.toInt(), 0xFFA20025.toInt(), 0xFFE51400.toInt(),
        0xFFFA6800.toInt(), 0xFFF0A30A.toInt(), 0xFFE3C800.toInt(), 0xFF825A2C.toInt(),
        0xFF6D8764.toInt(), 0xFF647687.toInt(), 0xFF76608A.toInt(), 0xFF87794E.toInt()
    )

    private fun sp(c: Context) = c.getSharedPreferences("metro", Context.MODE_PRIVATE)

    fun accentIndex(c: Context): Int = sp(c).getInt("accent", 4).coerceIn(0, ACCENTS.size - 1)
    fun accent(c: Context): Int = ACCENTS[accentIndex(c)]
    fun setAccent(c: Context, i: Int) = sp(c).edit().putInt("accent", i).apply()

    fun dark(c: Context): Boolean = sp(c).getBoolean("dark", true)
    fun setDark(c: Context, v: Boolean) = sp(c).edit().putBoolean("dark", v).apply()

    fun previews(c: Context): Boolean = sp(c).getBoolean("previews", true)
    fun setPreviews(c: Context, v: Boolean) = sp(c).edit().putBoolean("previews", v).apply()

    fun loadTiles(c: Context): List<TileData>? {
        val s = sp(c).getString("tiles", null) ?: return null
        return try {
            val arr = JSONArray(s)
            val out = ArrayList<TileData>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(TileData(o.getString("k"), o.getInt("s").coerceIn(0, 2)))
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    fun saveTiles(c: Context, tiles: List<TileData>) {
        val arr = JSONArray()
        for (t in tiles) {
            arr.put(JSONObject().put("k", t.key).put("s", t.size))
        }
        sp(c).edit().putString("tiles", arr.toString()).apply()
    }
}
