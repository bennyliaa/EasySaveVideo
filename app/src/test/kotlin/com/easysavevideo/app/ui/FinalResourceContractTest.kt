package com.easysavevideo.app.ui

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class FinalResourceContractTest {
    private val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private fun strings(locale: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        File(root, locale).listFiles()!!.filter { it.name.startsWith("final_") && it.extension == "xml" }.forEach { file ->
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val key = node.attributes.getNamedItem("name").nodeValue
                assertNull("duplicate resource $key", result.put(key, node.textContent))
            }
        }
        return result
    }
    @Test fun bothLanguagesHaveMatchingKeysAndFormatContracts() {
        val en = strings("values")
        val zh = strings("values-zh-rCN")
        assertTrue(en.isNotEmpty())
        assertEquals(en.keys, zh.keys)
        val format = Regex("%(?:[0-9]+\\$)?(?:\\.[0-9]+)?[dsf]")
        en.keys.forEach { key ->
            assertFalse(en.getValue(key).contains('\uFFFD'))
            assertFalse(zh.getValue(key).contains('\uFFFD'))
            assertEquals(key, format.findAll(en.getValue(key)).map { it.value }.toList(),
                format.findAll(zh.getValue(key)).map { it.value }.toList())
        }
    }
    @Test fun cropSemanticsRetainAllFourCoordinatesAndPercentUnits() {
        listOf("values", "values-zh-rCN").forEach { locale ->
            val rendered = String.format(Locale.US, strings(locale).getValue("final_crop_summary"), 10.0, 20.0, 80.0, 90.0)
            listOf("10.0%", "20.0%", "80.0%", "90.0%").forEach { assertTrue(rendered.contains(it)) }
        }
    }
}
