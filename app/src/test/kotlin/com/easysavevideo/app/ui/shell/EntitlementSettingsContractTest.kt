package com.easysavevideo.app.ui.shell

import com.easysavevideo.app.entitlements.*
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class EntitlementSettingsContractTest {
    private fun path(relative:String)=Path.of(relative).takeIf(Files::exists)?:Path.of("app",relative)
    private fun read(relative:String)=String(Files.readAllBytes(path(relative)),Charsets.UTF_8)
    @Test fun categoryHasStableAuxiliaryBackBehavior() {
        for(split in listOf(false,true)) {
            val p=settingsPresentation("entitlements",split)
            assertEquals(SettingsCategory.ENTITLEMENTS,p.active);assertTrue(p.detail)
            assertEquals(if(split)SettingsBackAction.EXIT else SettingsBackAction.CATEGORIES,settingsBackAction(false,p))
        }
    }
    @Test fun everyFeatureModuleAndStatusHasOneResourceMapping() {
        assertEquals(3,PlannedEntitlementModule.entries.map(::plannedModuleTitle).toSet().size)
        assertEquals(8,PlannedEntitlementModule.entries.flatMap{it.features}.map(::explainedFeatureTitle).toSet().size)
        assertEquals(3,FeatureEntitlementExplanation.entries.map(::explainedEntitlementStatus).toSet().size)
        assertThrows(IllegalStateException::class.java){explainedFeatureTitle(Feature.VAULT)}
    }
    @Test fun localizedCopyKeepsFreeDisabledAndPlannedStatesDistinct() {
        fun parse(locale:String):Map<String,String> {
            val nodes=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path("src/main/res/$locale/settings_entitlements.xml").toFile()).getElementsByTagName("string")
            return (0 until nodes.length).associate{val n=nodes.item(it);n.attributes.getNamedItem("name").nodeValue to n.textContent}
        }
        val en=parse("values");val zh=parse("values-zh-rCN")
        assertEquals(27,en.size);assertEquals(en.keys,zh.keys)
        for(key in en.keys) {
            val format=Regex("%[0-9]+\\$[ds]")
            assertEquals(format.findAll(en.getValue(key)).map{it.value}.toList(),format.findAll(zh.getValue(key)).map{it.value}.toList())
            assertTrue(en.getValue(key).isNotBlank());assertTrue(zh.getValue(key).isNotBlank())
        }
        assertTrue(en.getValue("entitlements_free_vault").contains("disabled"))
        assertTrue(en.getValue("entitlements_debug_notice").contains("not purchases"))
        assertTrue(en.getValue("entitlements_store_unconfigured").contains("undecided"))
        assertTrue(en.getValue("entitlements_recording_dependency").contains("basic recording entitlement"))
        for(key in listOf("entitlements_provider_plus","entitlements_save_pro","entitlements_video_tools_pro"))assertTrue(en.getValue(key).contains("planned module"))
    }
    @Test fun screenReadsOnlySnapshotAndKeepsRefreshLocalAndForeground() {
        val s=read("src/main/kotlin/com/easysavevideo/app/ui/shell/EntitlementSettingsDetail.kt")
        assertTrue(s.contains("LaunchedEffect(generation, active, refresh)"));assertTrue(s.contains("while (canRead())"))
        assertTrue(s.contains("if (!canRead()) break"));assertTrue(s.contains("entitlementExplanationDelay("))
        assertTrue(s.contains("observed.takeIf { active }"));assertTrue(s.contains("if (simulated)"))
        for(bad in listOf("purchase(","restore(","restorePurchases(",".refresh(",".select(","FakeEntitlementSource","originRef","priceLabel","rememberSaveable","launch(","GlobalScope"))assertFalse(bad,s.contains(bad))
    }
    @Test fun activityUsesExistingManagerAndActualBuildIdentity() {
        val s=read("src/main/kotlin/com/easysavevideo/app/MainActivity.kt").substringAfter("entitlements = {").substringBefore("privacy = {")
        assertTrue(s.contains("EntitlementSettingsDetail(generation, BuildConfig.DEBUG"))
        assertTrue(s.contains("readSnapshot = entitlements::snapshot"))
        for(required in listOf("generation == newTabInputGeneration","isForeground","EsvDestination.SETTINGS","!isDestroyed && !isFinishing"))assertTrue(s.contains(required))
        for(bad in listOf("purchase","restore",".select(","EntitlementGrant"))assertFalse(s.contains(bad))
    }
}
