package com.metro.start

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DominantTest {
    private fun icon(vararg parts: Pair<Int, Int>): IntArray {
        val out = ArrayList<Int>()
        for ((color, count) in parts) repeat(count) { out.add(color) }
        return out.toIntArray()
    }

    private fun hue(c: Int): Float {
        val hsv = FloatArray(3)
        Dominant.toHsv((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, hsv)
        return hsv[0]
    }

    @Test
    fun colourOnWhiteBackgroundWins() {
        // Most of the icon is a white background; the red logo is still the colour.
        val c = Dominant.of(icon(0xFFFFFFFF.toInt() to 1500, 0xFFE53935.toInt() to 400))
        assertTrue(hue(c) < 10f || hue(c) > 350f)
    }

    @Test
    fun biggestColourAreaWins() {
        val c = Dominant.of(icon(0xFF1E88E5.toInt() to 900, 0xFF43A047.toInt() to 300, 0xFFFDD835.toInt() to 200))
        assertTrue(hue(c) in 200f..220f)
    }

    @Test
    fun blackAndWhiteIconsGetANeutralTile() {
        // TikTok-style: mostly black with a white logo and a few coloured edges.
        val tiktok = icon(0xFF000000.toInt() to 1500, 0xFFFFFFFF.toInt() to 400, 0xFF25F4EE.toInt() to 30, 0xFFFE2C55.toInt() to 30)
        assertEquals(Dominant.CHARCOAL, Dominant.of(tiktok))
        // Mostly white or grey: steel.
        assertEquals(Dominant.STEEL, Dominant.of(icon(0xFFFFFFFF.toInt() to 1500, 0xFF222222.toInt() to 300)))
        assertEquals(Dominant.STEEL, Dominant.of(icon(0xFF9E9E9E.toInt() to 2000)))
        // Fully transparent: no colour at all.
        assertEquals(0, Dominant.of(icon(0x00FF0000 to 2000)))
    }

    @Test
    fun glyphAlphaSnapsToSolidOrClear() {
        assertEquals(0, Glyphs.crisp(0))
        assertEquals(0, Glyphs.crisp(100))   // faint ghost layer disappears
        assertEquals(255, Glyphs.crisp(180)) // mostly-solid layer becomes solid
        assertEquals(255, Glyphs.crisp(255))
        assertTrue(Glyphs.crisp(140) in 1..254) // edges keep a short ramp
    }

    @Test
    fun tileColoursStayBoldAndReadable() {
        val hsv = FloatArray(3)
        for (c in listOf(0xFFFFF59D.toInt(), 0xFFB3E5FC.toInt(), 0xFF101820.toInt() or 0x00300000)) {
            val t = Dominant.tileReady(c)
            Dominant.toHsv((t shr 16) and 0xFF, (t shr 8) and 0xFF, t and 0xFF, hsv)
            assertTrue("saturation ${hsv[1]}", hsv[1] >= 0.59f)
            assertTrue("brightness ${hsv[2]}", hsv[2] in 0.54f..0.93f)
        }
    }

    @Test
    fun hsvRoundTrip() {
        for (c in listOf(0xFF0050EF.toInt(), 0xFFD80073.toInt(), 0xFFA4C400.toInt(), 0xFFFA6800.toInt())) {
            val hsv = FloatArray(3)
            Dominant.toHsv((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, hsv)
            val back = Dominant.fromHsv(hsv)
            for (shift in listOf(16, 8, 0)) {
                assertTrue(kotlin.math.abs(((back shr shift) and 0xFF) - ((c shr shift) and 0xFF)) <= 1)
            }
        }
    }
}
