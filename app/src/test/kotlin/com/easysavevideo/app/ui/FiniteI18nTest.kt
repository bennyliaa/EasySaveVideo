package com.easysavevideo.app.ui

import com.easysavevideo.app.R
import com.easysavevideo.app.discovery.BuildDiscoveryPower
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class FiniteI18nTest {
    private fun resources(folder:String):Map<String,String> {
        val root=listOf(File("src/main/res"),File("app/src/main/res")).first{it.isDirectory}
        val f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
        val nodes=f.newDocumentBuilder().parse(File(root,"$folder/finite_i18n.xml")).getElementsByTagName("string")
        return (0 until nodes.length).associate{nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent}
    }
    @Test fun pairedStaticTextKeysAndFormatArgumentsAreIdentical() {
        val en=resources("values");val zh=resources("values-zh-rCN");assertEquals(18,en.size);assertEquals(en.keys,zh.keys)
        val format=Regex("%[0-9]+\\$[ds]")
        en.keys.forEach{key->assertTrue(en.getValue(key).isNotBlank());assertTrue(zh.getValue(key).isNotBlank())
            assertEquals(format.findAll(en.getValue(key)).map{it.value}.toList(),format.findAll(zh.getValue(key)).map{it.value}.toList())}
    }
    @Test fun everyAdmittedDebugScenarioHasDistinctTranslatedLabelAndDoesNotChangeSelection() {
        val resources=BuildDiscoveryPower.scenarios.map{powerScenarioLabelResource(it.id)}
        assertEquals(5,resources.distinct().size);assertFalse(resources.contains(R.string.finite_power_unavailable))
        assertEquals(R.string.finite_power_unavailable,powerScenarioLabelResource("unadmitted"))
        assertNull(BuildDiscoveryPower.selection("unadmitted"))
        assertFalse(BuildDiscoveryPower.selection("disconnected")!!.signals.enabled)
    }
    @Test fun labelsKeepSimulatedAndUnmeasuredDeviceSemanticsExplicit() {
        val en=resources("values");val zh=resources("values-zh-rCN")
        assertTrue(en.getValue("finite_power_debug").contains("not device readings"))
        assertTrue(zh.getValue("finite_power_debug").contains("不是设备读数"))
        assertTrue(en.getValue("finite_power_unknown").contains("unknown"))
        assertTrue(zh.getValue("finite_power_unknown").contains("未知"))
        assertTrue(en.getValue("finite_duration_unknown").contains("unknown"))
    }
}
