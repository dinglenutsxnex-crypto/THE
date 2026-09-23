package com.nexora.hammerscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the battle hijack controls in the overlay layout. The battle id field is the whole
 * point of the feature and it is easy to lose while reshuffling rows, so assert it is present
 * and correctly nested rather than trusting a review.
 */
class OverlayLayoutTest {

    private val ns = "http://schemas.android.com/apk/res/android"

    private val root: Element by lazy {
        val f = File("src/main/res/layout/layout_overlay.xml")
        assertTrue("layout_overlay.xml not found at ${f.absolutePath}", f.exists())
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(f)
            .documentElement
    }

    private fun allElements(): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(e: Element) {
            out += e
            for (i in 0 until e.childNodes.length) {
                val c = e.childNodes.item(i)
                if (c is Element) walk(c)
            }
        }
        walk(root)
        return out
    }

    private fun elementWithId(id: String): Element? =
        allElements().firstOrNull { it.getAttributeNS(ns, "id") == "@+id/$id" }

    private fun ancestorsOf(e: Element): List<Element> {
        val out = mutableListOf<Element>()
        var p = e.parentNode
        while (p is Element) { out += p; p = p.parentNode }
        return out
    }

    @Test
    fun `battle hijack has a toggle, a battle id field and a start control`() {
        assertTrue("hijack toggle missing", elementWithId("sw_battle_hijack") != null)
        assertTrue("battle id field missing", elementWithId("et_battle_hijack_id") != null)
        assertTrue("start control missing", elementWithId("btn_battle_hijack_start") != null)
        assertTrue("input row missing", elementWithId("row_battle_hijack_input") != null)
    }

    @Test
    fun `battle id field sits inside the user mode panel and the scroll content`() {
        val et = elementWithId("et_battle_hijack_id")!!
        val ids = ancestorsOf(et).map { it.getAttributeNS(ns, "id") }

        // It has to live under the scrollable user-mode panel, or it is unreachable.
        assertTrue("battle id field is not under panel_user_mode", "@+id/panel_user_mode" in ids)
        assertTrue("battle id field is not under overlay_scroll_content", "@+id/overlay_scroll_content" in ids)
        assertTrue("battle id field is not under overlay_scroll", "@+id/overlay_scroll" in ids)
    }

    @Test
    fun `battle id field is an editable text input`() {
        val et = elementWithId("et_battle_hijack_id")!!
        assertEquals("EditText", et.tagName)
    }

    @Test
    fun `input row starts hidden so the field appears when the toggle is switched on`() {
        val row = elementWithId("row_battle_hijack_input")!!
        assertEquals("gone", row.getAttributeNS(ns, "visibility"))
    }
}
