package com.easysavevideo.app.ui

import com.easysavevideo.app.discovery.*
import com.easysavevideo.app.recording.ui.*
import com.easysavevideo.commons.maintenance.*
import com.easysavevideo.discovery.*
import com.easysavevideo.recording.*
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceProviderDialogResourcesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private fun strings(locale: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        listOf("provider_copy.xml", "provider_dialog.xml", "region_validation.xml", "discovery_copy.xml", "filter_copy.xml").forEach { filename ->
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val nodes = factory.newDocumentBuilder().parse(File(root, "$locale/$filename")).getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                assertNull(result.put(node.attributes.getNamedItem("name").nodeValue, node.textContent.replace("\\'", "'")))
            }
        }
        return result
    }
    private val en by lazy { strings("values") }
    private val providerText = ProviderCopyText { key, args -> String.format(Locale.US, en.getValue("provider_copy_${key.ordinal}"), *args) }
    private val filterText = FilterCopyText { key, args -> String.format(Locale.US, en.getValue("filter_copy_${key.ordinal}"), *args) }

    @Test fun languageKeysAndParametersMatchAcrossEveryNewResource() {
        val zh = strings("values-zh-rCN")
        assertEquals(276, en.size)
        assertEquals(en.keys, zh.keys)
        val format = Regex("%(?:[0-9]+\\$)?(?:\\.[0-9]+)?[dsf]")
        en.keys.forEach { key ->
            assertFalse(en.getValue(key).contains('\uFFFD'))
            assertFalse(zh.getValue(key).contains('\uFFFD'))
            assertEquals(key, format.findAll(en.getValue(key)).map { it.value }.toList(),
                format.findAll(zh.getValue(key)).map { it.value }.toList())
        }
    }
    @Test fun localizationCannotGrantRecoveryOrInventReadableState() {
        val config = ProviderManagementConfiguration(contactConfigured = false)
        val unknown = ProviderManagementState(active = true, problem = ProviderManagementProblem.LOCAL_UNAVAILABLE)
        val view = ProviderManagementPresentationPolicy.present(unknown, config, false, providerText)
        assertEquals("Current maintenance state unknown", view.summary)
        assertFalse(view.canRejectCandidate); assertFalse(view.canRollback)
        assertTrue(view.versions.single().contains("unknown"))
        val snapshot = ProviderRepository(temporary.newFolder()).use { it.snapshot() }
        listOf(
            ProviderManagementState(active = true, snapshot = snapshot),
            ProviderManagementState(active = false, snapshot = snapshot),
            ProviderManagementState(active = true, busy = true, snapshot = snapshot),
            ProviderManagementState(active = true, snapshot = snapshot.copy(state = ProviderRepositoryState.DISABLED,
                error = ProviderRepositoryError.CORRUPT_STATE, pendingVersion = 9, canRejectCandidate = true, canRollback = true)),
        ).forEach { state ->
            val chinese = ProviderManagementPresentationPolicy.present(state, config, false)
            val english = ProviderManagementPresentationPolicy.present(state, config, false, providerText)
            assertEquals(chinese.canRejectCandidate, english.canRejectCandidate)
            assertEquals(chinese.canRollback, english.canRollback)
        }
    }
    @Test fun filtersKeepAdmissionAndOriginalArgumentsWhenLanguageChanges() {
        val profile = SearchProfile("profile", "Original query", "en", SearchFilters(setOf("chosen-source"), 1001, 12000, false, true))
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(profile, false)
        assertEquals(DiscoveryFilterPresentationPolicy.validate(draft).filters,
            DiscoveryFilterPresentationPolicy.validate(draft, filterText).filters)
        val bad = draft.copy(minimumSeconds = "invalid")
        assertNull(DiscoveryFilterPresentationPolicy.validate(bad, filterText).filters)
        assertTrue(DiscoveryFilterPresentationPolicy.validate(bad, filterText).error!!.startsWith("Minimum duration"))
        val raw = "Original %s \$word"
        val summary = DiscoveryFilterPresentationPolicy.sourceSummary(profile.filters,
            listOf(DiscoverySourceChoice("chosen-source", raw)), filterText)
        assertTrue(summary.contains(raw))
        assertTrue(DiscoveryFilterPresentationPolicy.admittedSources(emptyList(), false, filterText).isEmpty())
        assertTrue(DiscoveryFilterPresentationPolicy.sourceSummary(SearchFilters(), emptyList(), filterText).contains("zero sources"))
    }
    @Test fun regionSemanticsKeepCoordinatesAndUnconfirmedCaptureCannotBeSelected() {
        val description = String.format(Locale.US, en.getValue("region_summary"), 10.0, 20.0, 80.0, 90.0)
        listOf("10.0%", "20.0%", "80.0%", "90.0%").forEach { assertTrue(description.contains(it)) }
        assertEquals(RecordingRegionProblem.GEOMETRY_UNAVAILABLE,
            recordingRegionProblem(RegionNormalizedRect.FULL, RecordingRegionSetup(null, null)))
        val setup = RecordingRegionSetup(CaptureDisplayGeometry(1080, 1920, 0), null)
        assertEquals(RecordingRegionProblem.SCOPE_UNCONFIRMED, recordingRegionProblem(RegionNormalizedRect.FULL, setup))
        assertEquals(recordingRegionProblem(RegionNormalizedRect.FULL, setup)?.chinese,
            recordingRegionError(RegionNormalizedRect.FULL, setup))
    }
}
