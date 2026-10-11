package com.easysavevideo.app.ui

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class DiscoveryResponsiveLayoutTest {
    @Test fun compactDefaultKeepsTwoColumnsAndLargeTextReducesRatherThanShrinks() {
        assertEquals(2, discoveryResponsiveLayout(360f, 1f, 2, 8f).columns)
        val large = discoveryResponsiveLayout(360f, 2f, 2, 8f)
        assertEquals(1, large.columns); assertTrue(large.stackSearch)
    }
    @Test fun wideWindowNeverOverridesSingleColumnPreference() {
        assertEquals(1, discoveryResponsiveLayout(1200f, 1f, 1, 8f).columns)
        assertEquals(2, discoveryResponsiveLayout(1200f, 1f, 2, 8f).columns)
    }
    @Test fun compactSixColumnPreferenceIsOnlyReducedForPresentation() {
        val preference = 6
        assertEquals(2, discoveryResponsiveLayout(360f, 1f, preference, 8f).columns)
        assertEquals(6, preference)
        assertEquals(6, discoveryResponsiveLayout(1200f, 1f, preference, 8f).columns)
    }
    @Test fun capacityAndFontMatrixPreservesPreferenceAndNeverCrowdsMultipleCards() {
        for (pref in 1..6) for (width in listOf(240f, 320f, 360f, 600f, 840f, 1200f)) {
            var previous = pref
            for (font in listOf(1f, 1.3f, 1.5f, 2f, 3f)) {
                val layout = discoveryResponsiveLayout(width, font, pref, 8f)
                assertTrue(layout.columns in 1..pref)
                assertTrue(layout.columns <= previous)
                if (layout.columns > 1) assertTrue((width - (layout.columns - 1) * 8f) / layout.columns >= 160f * font)
                previous = layout.columns
            }
        }
    }
    @Test fun widerViewportCannotReduceCapacity() {
        for (font in listOf(1f, 2f)) {
            val columns = (200..1400 step 40).map { discoveryResponsiveLayout(it.toFloat(), font, 6, 8f).columns }
            assertEquals(columns.sorted(), columns)
        }
    }
    @Test fun invalidMeasurementsUseConservativeSingleColumnStack() {
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(DiscoveryResponsiveLayout(1, true), discoveryResponsiveLayout(width, 1f, 6, 8f))
        }
        assertEquals(DiscoveryResponsiveLayout(1, true), discoveryResponsiveLayout(360f, Float.NaN, 6, 8f))
        assertEquals(DiscoveryResponsiveLayout(1, true), discoveryResponsiveLayout(360f, 1f, 6, -1f))
    }
    @Test fun adaptiveExplanationHasMatchedLocaleArguments() {
        val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val messages = listOf("values", "values-zh-rCN").map { locale ->
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val node = factory.newDocumentBuilder().parse(File(root, "$locale/discovery_layout.xml")).getElementsByTagName("string").item(0)
            assertEquals("discovery_grid_adapted", node.attributes.getNamedItem("name").nodeValue)
            assertFalse(node.textContent.contains('\uFFFD'))
            node.textContent
        }
        val format = Regex("%[0-9]+\\\$d")
        assertEquals(listOf("%1\$d", "%2\$d"), format.findAll(messages[0]).map { it.value }.toList())
        assertEquals(format.findAll(messages[0]).map { it.value }.toList(), format.findAll(messages[1]).map { it.value }.toList())
        messages.forEach { val formatted = String.format(Locale.ROOT, it, 6, 1); assertTrue(formatted.contains("6")); assertTrue(formatted.contains("1")) }
    }
}
