package com.metro.start

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LayoutAndColorTest {

    private fun cellsOf(vararg sizes: Int) = Packer.pack(sizes.toList())

    @Test
    fun mediumTilesSitTwoPerRow() {
        val c = cellsOf(Tile.MEDIUM, Tile.MEDIUM, Tile.MEDIUM)
        assertEquals(listOf(0, 2, 0), c.map { it.col })
        assertEquals(listOf(0, 0, 2), c.map { it.row })
    }

    @Test
    fun wideTileTakesFullWidth() {
        val c = cellsOf(Tile.WIDE, Tile.MEDIUM)
        assertEquals(0, c[0].col); assertEquals(4, c[0].w); assertEquals(2, c[0].h)
        assertEquals(2, c[1].row)
    }

    @Test
    fun fourSmallTilesFillOneBlock() {
        val c = cellsOf(Tile.SMALL, Tile.SMALL, Tile.SMALL, Tile.SMALL, Tile.MEDIUM)
        val small = c.take(4).map { it.col to it.row }.toSet()
        assertEquals(setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), small)
        assertEquals(2, c[4].col); assertEquals(0, c[4].row)
    }

    @Test
    fun smallTilesFillGapNextToMedium() {
        val c = cellsOf(Tile.MEDIUM, Tile.SMALL, Tile.SMALL)
        assertEquals(2, c[1].col); assertEquals(0, c[1].row)
        assertEquals(3, c[2].col); assertEquals(0, c[2].row)
    }

    @Test
    fun packedTilesNeverOverlap() {
        val rnd = Random(1)
        repeat(200) {
            val sizes = List(rnd.nextInt(1, 30)) { rnd.nextInt(0, 3) }
            val cells = Packer.pack(sizes)
            val seen = HashSet<Pair<Int, Int>>()
            for (c in cells) {
                assertTrue(c.col >= 0 && c.col + c.w <= 4)
                for (r in c.row until c.row + c.h) for (k in c.col until c.col + c.w) assertTrue(seen.add(r to k))
            }
        }
    }

    @Test
    fun neighboursShareAnEdge() {
        val c = cellsOf(Tile.MEDIUM, Tile.MEDIUM, Tile.WIDE)
        val n = Packer.neighbours(c)
        assertEquals(setOf(1, 2), n[0])
        assertEquals(setOf(0, 2), n[1])
        assertEquals(setOf(0, 1), n[2])
    }

    @Test
    fun separateLeavesNoTwoEqualColorsSideBySide() {
        val rnd = Random(7)
        repeat(300) {
            val tiles = List(rnd.nextInt(2, 25)) {
                Tile("k$it", rnd.nextInt(0, 3), Metro.PALETTE[rnd.nextInt(0, 3)])
            }
            TileColors.separate(tiles)
            val n = Packer.neighbours(Packer.pack(tiles.map { it.size }))
            for (i in tiles.indices) for (j in n[i]) assertFalse(tiles[i].color == tiles[j].color)
        }
    }

    @Test
    fun separateRespectsUserChosenColors() {
        val red = Metro.PALETTE[5]
        val tiles = listOf(
            Tile("a", Tile.MEDIUM, red, userColor = true),
            Tile("b", Tile.MEDIUM, red)
        )
        TileColors.separate(tiles)
        assertEquals(red, tiles[0].color)
        assertTrue(tiles[1].color != red)
    }

    @Test
    fun shuffleKeepsNeighboursApart() {
        val tiles = List(20) { Tile("k$it", it % 3, Metro.PALETTE[0]) }
        TileColors.shuffle(tiles, 12345L)
        val n = Packer.neighbours(Packer.pack(tiles.map { it.size }))
        for (i in tiles.indices) for (j in n[i]) assertFalse(tiles[i].color == tiles[j].color)
    }

    @Test
    fun tilesRoundTripThroughJson() {
        val tiles = listOf(
            Tile("com.a/.Main", Tile.WIDE, Metro.PALETTE[3], userColor = true, live = false),
            Tile(Tile.SETTINGS, Tile.SMALL, 0xFF123456.toInt())
        )
        val back = Tile.parse(Tile.serialize(tiles))!!
        assertEquals(2, back.size)
        assertEquals("com.a/.Main", back[0].key)
        assertEquals(Tile.WIDE, back[0].size)
        assertTrue(back[0].userColor)
        assertFalse(back[0].live)
        assertEquals(0xFF123456.toInt(), back[1].color)
        assertNull(Tile.parse("not json"))
    }

    @Test
    fun hexParsing() {
        assertEquals(0xFFFF8800.toInt(), ColorPicker.parseHex("#ff8800"))
        assertEquals(0xFFFF8800.toInt(), ColorPicker.parseHex("FF8800"))
        assertEquals(0xFFFFAA00.toInt(), ColorPicker.parseHex("#fa0"))
        assertNull(ColorPicker.parseHex("#zz0000"))
        assertNull(ColorPicker.parseHex("#12345"))
    }

    @Test
    fun letterHeaders() {
        assertEquals('c', AppInfo.letterOf("čitač"))
        assertEquals('#', AppInfo.letterOf("2048"))
        assertEquals('m', AppInfo.letterOf("  Maps"))
    }
}
