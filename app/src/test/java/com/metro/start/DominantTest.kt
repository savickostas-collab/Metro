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
    fun greyOrMonochromeIconsHaveNoColour() {
        assertEquals(0, Dominant.of(icon(0xFF000000.toInt() to 800, 0xFFFFFFFF.toInt() to 800)))
        assertEquals(0, Dominant.of(icon(0xFF777777.toInt() to 2000)))
        assertEquals(0, Dominant.of(icon(0x00FF0000 to 2000))) // fully transparent
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
