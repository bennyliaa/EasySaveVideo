package com.easysavevideo.app.save

import com.easysavevideo.app.R
import com.easysavevideo.app.discovery.DiscoveryOriginHistoryOutcome
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** XML/source checks do not replace actual Android locale or rendered-screen acceptance. */
class SaveDisplayResourcesTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun resourceMap(locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(File(root, "res/$locale/save_display.xml"))
        val result = linkedMapOf<String, String>()
        val strings = document.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val node = strings.item(i)
            assertNull(result.put(node.attributes.getNamedItem("name").nodeValue, node.textContent))
        }
        val plurals = document.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val node = plurals.item(i); val name = node.attributes.getNamedItem("name").nodeValue
            for (j in 0 until node.childNodes.length) {
                val item = node.childNodes.item(j)
                if (item.nodeName == "item") assertNull(result.put("$name/" + item.attributes.getNamedItem("quantity").nodeValue, item.textContent))
            }
        }
        return result
    }
    private fun source(name: String) = File(root, "kotlin/com/easysavevideo/app/save/$name.kt").readText()
    private val en by lazy { resourceMap("values") }
    private val zh by lazy { resourceMap("values-zh-rCN") }
    @Test fun pairedStringsAndLocaleAppropriatePluralQuantitiesHaveMatchingParameters() {
        assertEquals(15, en.keys.count { '/' !in it })
        assertEquals(en.keys.filter { '/' !in it }.toSet(), zh.keys.filter { '/' !in it }.toSet())
        val pattern = Regex("""%[0-9]+[$]s""")
        for (key in en.keys) {
            val counterpart = zh[key] ?: zh.getValue(key.replace("/one", "/other"))
            assertEquals(key, pattern.findAll(en.getValue(key)).map { it.value }.toList(),
                pattern.findAll(counterpart).map { it.value }.toList())
            assertFalse(en.getValue(key).contains('\uFFFD')); assertFalse(counterpart.contains('\uFFFD'))
        }
        assertEquals(setOf("save_display_seconds/other", "save_display_audio_tracks/other"), zh.keys.filter { '/' in it }.toSet())
    }
    @Test fun secondsAndAudioTracksHaveTrueEnglishSingularAndPluralResources() {
        assertEquals("1 second", String.format(Locale.US, en.getValue("save_display_seconds/one"), "1"))
        assertEquals("0 seconds", String.format(Locale.US, en.getValue("save_display_seconds/other"), "0"))
        assertEquals("2 seconds", String.format(Locale.US, en.getValue("save_display_seconds/other"), "2"))
        assertEquals("1 audio track", String.format(Locale.US, en.getValue("save_display_audio_tracks/one"), "1"))
        assertEquals("2 audio tracks", String.format(Locale.US, en.getValue("save_display_audio_tracks/other"), "2"))
    }
    @Test fun fullSentencesAcceptFormattedNumbersWithoutChangingUnitsOrPolicies() {
        val size = String.format(Locale.GERMANY, en.getValue("save_display_mib"), saveDisplaySize(1_572_864, Locale.GERMANY).amount)
        val budget = String.format(Locale.GERMANY, en.getValue("save_display_hls_budget"), size)
        assertTrue(budget.contains("1,5 MiB")); assertTrue(budget.contains("60 seconds"))
        assertTrue(budget.contains("does not extend the original execution deadline"))
        assertEquals("2. Resolution not declared · 999 kbps", String.format(Locale.US,
            en.getValue("save_display_hls_variant"), "2", en.getValue("save_display_resolution_undeclared"), "999"))
    }
    @Test fun allFourHistoryOutcomesRemainDistinctAndDoNotRewriteMediaStatus() {
        assertEquals(R.string.save_display_history_recorded, saveDiscoveryOutcomeResource(DiscoveryOriginHistoryOutcome.RECORDED))
        assertEquals(R.string.save_display_history_stale, saveDiscoveryOutcomeResource(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE))
        assertEquals(R.string.save_display_history_failed, saveDiscoveryOutcomeResource(DiscoveryOriginHistoryOutcome.HISTORY_FAILED))
        assertEquals(R.string.save_display_history_unverified, saveDiscoveryOutcomeResource(DiscoveryOriginHistoryOutcome.IDENTITY_UNVERIFIED))
        assertEquals(4, DiscoveryOriginHistoryOutcome.entries.map(::saveDiscoveryOutcomeResource).toSet().size)
        assertTrue(en.getValue("save_display_history_unverified").contains("remains unknown"))
        assertTrue(en.getValue("save_display_history_failed").contains("Media saving completed"))
    }
    @Test fun resourcesReadCurrentComposeLocaleWithNoGlobalOrStaleCache() {
        val text = source("SaveDisplayResources")
        assertTrue(text.contains("LocalConfiguration.current.locales"))
        assertFalse(text.contains("Locale.getDefault")); assertFalse(text.contains("remember"))
        assertTrue(text.contains("pluralStringResource("))
        assertTrue(text.contains("saveDisplayQuantity(seconds)"))
        assertFalse(source("SaveDisplayFormatting").contains("Locale.getDefault"))
    }
    @Test fun liveScreenUsesResourceMappersInsteadOfLegacyChineseDisplayHelpers() {
        val text = source("SaveTaskScreen").substringBefore("internal fun saveMethodLabel")
        assertTrue(text.contains("stringResource(recordingAudioOutcomeResource(recording.audioOutcome))"))
        assertTrue(text.contains("stringResource(recordingStopReasonResource(recording.stopReason))"))
        assertTrue(text.contains("stringResource(saveDiscoveryOutcomeResource(outcome.history))"))
        assertFalse(text.contains("recordingAudioOutcomeLabel(")); assertFalse(text.contains("recordingStopReasonLabel("))
        assertFalse(text.contains("mediaTaskDiscoveryOutcomeLabel(")); assertFalse(text.contains("formatBytes("))
        assertFalse(Regex("""[\u4e00-\u9fff]""").containsMatchIn(text))
    }
    @Test fun originalSourceMetadataAndSaveActionsAreKeptAlongsideGeneratedLabel() {
        val text = source("SaveTaskScreen")
        assertTrue(text.contains("EsvText(pageSaveCandidateLabel(candidate.displayIndices))"))
        assertTrue(text.contains("URI(candidate.url.value).host"))
        assertTrue(text.contains("runtime.choose(candidate, browser)"))
        assertTrue(text.contains("runtime.chooseHlsVariant(variant.identity)"))
        assertTrue(text.contains("enabled = candidate.supported && !candidate.protected && runtime.canSave(candidate.method) && selection.isCurrent()"))
        assertTrue(text.contains("runtime.resume(record.task.taskId, browser)"))
        assertTrue(text.contains("runtime.cancel(record.task.taskId)"))
        assertTrue(text.contains("metadata.bandwidth / 1000"))
    }
    @Test fun successfulPresentationRemainsInsideExistingStateAndVerificationBranches() {
        val text = source("SaveTaskScreen")
        val successful = text.substringAfter("if (record.task.state == TaskState.SUCCEEDED) {").substringBefore("} else if (record.task.state")
        assertTrue(successful.contains("runtime.discoverySaveOutcomes"))
        assertTrue(successful.contains("if (evidence != null)"))
        assertTrue(successful.contains("enabled = disposition == MediaLifecycle.READY && runtime.currentRecordingId == null && !runtime.draining"))
        assertFalse(source("SaveDisplayResources").contains("mediaTaskHasCommittedSave"))
    }
}
