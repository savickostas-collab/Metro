package com.metro.start

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    private fun rel(tag: String, asset: String = "MetroLauncher.apk", draft: Boolean = false, pre: Boolean = false) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$pre,
         "assets":[{"name":"$asset","size":562179,
           "browser_download_url":"https://github.com/x/Metro/releases/download/$tag/$asset"}]}"""

    @Test
    fun picksTheHighestBuildNumber() {
        val json = "[" + listOf(rel("v2.0.3"), rel("v2.0.10"), rel("v2.0.4")).joinToString(",") + "]"
        val r = Updater.pick(json)!!
        assertEquals(10L, r.code)
        assertEquals("2.0.10", r.name)
        assertEquals(562179L, r.size)
        assertEquals("https://github.com/x/Metro/releases/download/v2.0.10/MetroLauncher.apk", r.url)
    }

    @Test
    fun ignoresOtherAppsDraftsAndWrongAssets() {
        val json = "[" + listOf(
            rel("v1.9.99"),                       // old launcher line
            rel("v2.0.50", draft = true),
            rel("v2.0.60", pre = true),
            rel("v2.0.70", asset = "notes.txt"),
            rel("v2.0.5")
        ).joinToString(",") + "]"
        assertEquals(5L, Updater.pick(json)!!.code)
    }

    @Test
    fun nothingUsableGivesNull() {
        assertNull(Updater.pick("[]"))
        assertNull(Updater.pick("[" + rel("latest") + "]"))
        assertNotNull(Updater.pick("[" + rel("v2.0.1") + "]"))
    }
}
