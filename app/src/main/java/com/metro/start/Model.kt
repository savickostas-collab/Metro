package com.metro.start

import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import kotlin.random.Random

/** One pinned tile. */
class Tile(
    val key: String,
    var size: Int,
    var color: Int,
    var userColor: Boolean = false,
    var live: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("k", key).put("s", size).put("c", color).put("u", userColor).put("l", live)

    companion object {
        const val SMALL = 0   // 1x1
        const val MEDIUM = 1  // 2x2
        const val WIDE = 2    // 4x2
        const val SETTINGS = "metro:settings"
        const val PHONE = "metro:phone"
        const val MUSIC = "metro:music"
        const val WEATHER = "metro:weather"
        /** Bump when new built-in Metro apps should be added to existing start screens. */
        const val BUILTINS_VERSION = 1

        fun parse(s: String): MutableList<Tile>? = try {
            val a = JSONArray(s)
            MutableList(a.length()) { i ->
                val o = a.getJSONObject(i)
                Tile(
                    o.getString("k"),
                    o.optInt("s", MEDIUM).coerceIn(SMALL, WIDE),
                    o.optInt("c", Metro.PALETTE[0]),
                    o.optBoolean("u", false),
                    o.optBoolean("l", true)
                )
            }
        } catch (e: Exception) {
            null
        }

        fun serialize(tiles: List<Tile>): String {
            val a = JSONArray()
            for (t in tiles) a.put(t.toJson())
            return a.toString()
        }
    }
}

/** A tile's place on the 4-column unit grid. */
class Cell(val col: Int, val row: Int, val w: Int, val h: Int) {
    fun touches(o: Cell): Boolean {
        val sideBySide = (col + w == o.col || o.col + o.w == col) && row < o.row + o.h && o.row < row + h
        val stacked = (row + h == o.row || o.row + o.h == row) && col < o.col + o.w && o.col < col + w
        return sideBySide || stacked
    }
}

/**
 * Windows Phone packing: the grid is made of 2x2 blocks. Medium tiles take a block,
 * wide tiles take two side by side, small tiles fill a block four at a time.
 * Each tile goes in the first free spot, so gaps fill up the way they did on WP.
 */
object Packer {
    fun pack(sizes: List<Int>): List<Cell> {
        val occ = ArrayList<BooleanArray>()
        fun free(r: Int, c: Int, w: Int, h: Int): Boolean {
            while (occ.size < r + h) occ.add(BooleanArray(4))
            for (i in r until r + h) for (j in c until c + w) if (occ[i][j]) return false
            return true
        }

        val out = ArrayList<Cell>(sizes.size)
        for (s in sizes) {
            var cell: Cell? = null
            var block = 0
            while (cell == null) {
                val br = (block / 2) * 2
                val bc = (block % 2) * 2
                when (s) {
                    Tile.SMALL -> for (k in 0..3) {
                        val r = br + k / 2
                        val c = bc + k % 2
                        if (free(r, c, 1, 1)) {
                            cell = Cell(c, r, 1, 1)
                            break
                        }
                    }
                    Tile.MEDIUM -> if (free(br, bc, 2, 2)) cell = Cell(bc, br, 2, 2)
                    else -> if (bc == 0 && free(br, 0, 4, 2)) cell = Cell(0, br, 4, 2)
                }
                block++
            }
            val placed: Cell = cell
            for (i in placed.row until placed.row + placed.h)
                for (j in placed.col until placed.col + placed.w) occ[i][j] = true
            out.add(placed)
        }
        return out
    }

    fun neighbours(cells: List<Cell>): List<Set<Int>> {
        val n = List(cells.size) { HashSet<Int>() }
        for (i in cells.indices) for (j in i + 1 until cells.size) {
            if (cells[i].touches(cells[j])) {
                n[i].add(j)
                n[j].add(i)
            }
        }
        return n
    }
}

/** Multi-colour logic: palette cycle, no two equal colours side by side, daily shuffle. */
object TileColors {
    fun next(p: Prefs): Int {
        val i = p.cursor
        p.cursor = i + 1
        return Metro.PALETTE[Math.floorMod(i, Metro.PALETTE.size)]
    }

    /** Recolours automatically coloured tiles that touch a tile of the same colour. */
    fun separate(tiles: List<Tile>): Boolean {
        if (tiles.size < 2) return false
        val nb = Packer.neighbours(Packer.pack(tiles.map { it.size }))
        var changed = false
        for (pass in 0 until 4) {
            var any = false
            for (i in tiles.indices) {
                val t = tiles[i]
                if (t.userColor) continue
                val taken = nb[i].map { tiles[it].color }.toSet()
                if (t.color !in taken) continue
                val start = Metro.PALETTE.indexOf(t.color).coerceAtLeast(0)
                for (k in 1..Metro.PALETTE.size) {
                    val c = Metro.PALETTE[(start + k) % Metro.PALETTE.size]
                    if (c !in taken) {
                        t.color = c
                        any = true
                        break
                    }
                }
            }
            if (!any) break
            changed = true
        }
        return changed
    }

    fun shuffle(tiles: List<Tile>, seed: Long) {
        val rnd = Random(seed)
        val nb = Packer.neighbours(Packer.pack(tiles.map { it.size }))
        for (i in tiles.indices) {
            val t = tiles[i]
            if (t.userColor) continue
            val taken = nb[i].filter { it < i || tiles[it].userColor }.map { tiles[it].color }.toSet()
            val options = Metro.PALETTE.filter { it !in taken }
            t.color = if (options.isEmpty()) Metro.PALETTE[rnd.nextInt(Metro.PALETTE.size)]
            else options[rnd.nextInt(options.size)]
        }
        separate(tiles)
    }

    fun today(): Int {
        val now = System.currentTimeMillis()
        return ((now + TimeZone.getDefault().getOffset(now)) / 86_400_000L).toInt()
    }
}
