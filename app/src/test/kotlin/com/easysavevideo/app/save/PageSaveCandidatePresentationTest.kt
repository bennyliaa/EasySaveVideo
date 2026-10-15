package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.save.SourceUrl
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Generated numbering and source wiring only; no WebView or Compose rendering. */
class PageSaveCandidatePresentationTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(name: String) = File(root, "kotlin/com/easysavevideo/app/save/$name.kt").readText()
    private fun label(locale: String): String {
        val f = DocumentBuilderFactory.newInstance()
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = f.newDocumentBuilder().parse(File(root, "res/$locale/save_display.xml")).getElementsByTagName("string")
        val matches = (0 until nodes.length).map { nodes.item(it) }
            .filter { it.attributes.getNamedItem("name").nodeValue == "save_display_candidate_label" }
        assertEquals(1, matches.size)
        return matches.single().textContent
    }
    @Test fun allBoundedResourcePositionsKeepOriginalIndicesAndVideoClamping() {
        for (index in 0..31) for (video in listOf(Int.MIN_VALUE, -1, 0, 1, 16, 32, 33, Int.MAX_VALUE)) {
            val actual = pageSaveCandidateIndices(video, index)
            assertEquals(video.coerceIn(1, 32), actual.videoNumber)
            assertEquals(index + 1, actual.resourceNumber)
        }
        assertEquals(PageSaveCandidateIndices(8, 8), pageSaveCandidateIndices(8, 7))
    }
    @Test fun resourceNumberIsSourceArrayPositionRatherThanDisplayedListPosition() {
        // Invalid or duplicate earlier entries may be omitted; surviving original ordinal is kept.
        assertEquals(7, pageSaveCandidateIndices(2, 6).resourceNumber)
        assertEquals(32, pageSaveCandidateIndices(1, 31).resourceNumber)
        val detector = source("PageSaveDetector")
        assertTrue(detector.contains("pageSaveCandidateIndices(item.optInt(\"video\", i + 1), i)"))
        assertTrue(detector.contains("if (array.length() > 32) throw IllegalArgumentException()"))
        assertTrue(detector.contains("repeat(array.length()) { i ->"))
        assertFalse(detector.contains("candidates.size + 1"))
    }
    @Test fun hlsCopyPreservesIndicesOpaqueIdentityAndSourceBytes() {
        val source = SourceUrl("https://example.invalid/media.mp4?name=%E8%A7%86%E9%A2%91&token=A%2FB")
        val indices = pageSaveCandidateIndices(2, 4)
        val candidate = PageSaveCandidate("opaque-id", source, indices, 320, 240, 1000, true, false, true)
        val hls = candidate.copy(supported = true, method = TaskMethod.STREAM)
        assertSame(indices, hls.displayIndices)
        assertEquals(candidate.id, hls.id)
        assertSame(source, hls.url)
        assertEquals(candidate.url.value, hls.url.value)
        assertEquals("PageSaveCandidate([redacted])", hls.toString())
        assertFalse(hls.toString().contains("token"))
        assertEquals(candidate.width, hls.width)
        assertEquals(candidate.durationMillis, hls.durationMillis)
    }
    @Test fun englishAndChineseLabelsAreFullResourceTemplatesWithTwoNumberArguments() {
        assertEquals("Video %1\$s · Resource %2\$s", label("values"))
        assertEquals("视频 %1\$s · 资源 %2\$s", label("values-zh-rCN"))
        for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE, Locale.forLanguageTag("ar"))) {
            val video = saveDisplayInteger(2, locale)
            val resource = saveDisplayInteger(32, locale)
            assertEquals("Video $video · Resource $resource", String.format(locale, label("values"), video, resource))
            assertEquals("视频 $video · 资源 $resource", String.format(locale, label("values-zh-rCN"), video, resource))
        }
    }
    @Test fun actualUiResolvesIndicesWithCurrentCompositionLocaleWithoutFlattening() {
        val display = source("SaveDisplayResources")
        val function = display.substringAfter("@Composable internal fun pageSaveCandidateLabel")
        assertTrue(function.contains("stringResource("))
        assertTrue(function.contains("R.string.save_display_candidate_label"))
        assertTrue(function.contains("saveIntegerText(indices.videoNumber.toLong())"))
        assertTrue(function.contains("saveIntegerText(indices.resourceNumber.toLong())"))
        assertFalse(function.contains("remember"))
        assertFalse(function.contains("Locale.getDefault"))
        val screen = source("SaveTaskScreen")
        assertTrue(screen.contains("EsvText(pageSaveCandidateLabel(candidate.displayIndices))"))
        assertFalse(screen.contains("candidate.label"))
        assertTrue(screen.contains("URI(candidate.url.value).host"))
        assertTrue(screen.contains("runtime.choose(candidate, browser)"))
        assertTrue(screen.contains("val selection = runtime.selection ?: return"))
        assertTrue(screen.contains("val admission = LocalPrivateInputAdmission.current"))
        assertTrue(screen.contains("fun ownsSelection() = admission() && runtime.selection === selection &&\n        runtime.currentSelectionPresentationIdentity === selection"))
        assertTrue(screen.contains("onClick = { if (ownsSelection() && selection.isCurrent()) runtime.choose(candidate, browser) }"))
        assertTrue(screen.contains("enabled = candidate.supported && !candidate.protected && runtime.canSave(candidate.method) && ownsSelection() && selection.isCurrent()"))
    }
    @Test fun detectorAndDtoDoNotParseTitlesOrSerializedLocalizedLabels() {
        val detector = source("PageSaveDetector")
        val dto = source("PageSaveCandidateIndices")
        assertTrue(detector.contains("val displayIndices: PageSaveCandidateIndices"))
        assertFalse(detector.contains("val label: String"))
        assertFalse(detector.contains("视频 "))
        assertFalse(detector.contains("optString(\"title\")"))
        assertFalse(dto.contains("split("))
        assertFalse(dto.contains("String"))
        assertFalse(dto.contains("Serializable"))
        val build = File(root, "../../build.gradle.kts").readText()
        for (name in listOf("PageSaveDetector", "PageSaveCandidateIndices", "SaveTaskScreen", "SaveDisplayResources"))
            assertTrue(build.contains("src/main/kotlin/com/easysavevideo/app/save/$name.kt"))
        assertTrue(build.contains("\"save_display.xml\""))
    }
}
