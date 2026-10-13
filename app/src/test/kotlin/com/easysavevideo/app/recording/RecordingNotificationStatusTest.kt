package com.easysavevideo.app.recording

import com.easysavevideo.app.R
import com.easysavevideo.app.recording.ui.RecordingSegmentsPhase
import com.easysavevideo.recording.RecordingState
import java.io.File
import java.io.StringReader
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.xml.sax.InputSource

/** Pure mapper/XML fixtures. Android resource resolution and live notifications require device tests. */
class RecordingNotificationStatusTest {
    private val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private data class Leaf(val resource: Int, val key: String, val chinese: String)

    private val statusLeaves = linkedMapOf(
        RecordingNotificationStatus.INITIAL_PREPARING to Leaf(R.string.recording_notification_initial_preparing,
            "recording_notification_initial_preparing", "正在准备录制"),
        RecordingNotificationStatus.PAUSED to Leaf(R.string.recording_notification_paused,
            "recording_notification_paused", "已暂停，通知可停止并保存"),
        RecordingNotificationStatus.RECORDING to Leaf(R.string.recording_notification_recording,
            "recording_notification_recording", "录制中，系统选择的共享范围"),
        RecordingNotificationStatus.STOPPING to Leaf(R.string.recording_notification_stopping,
            "recording_notification_stopping", "正在结束录制"),
        RecordingNotificationStatus.CANCELLING to Leaf(R.string.recording_notification_cancelling,
            "recording_notification_cancelling", "正在放弃录制"),
        RecordingNotificationStatus.RECOVERY_REQUIRED to Leaf(R.string.recording_notification_recovery_required,
            "recording_notification_recovery_required", "系统资源仍未退出，请在系统中结束应用"),
        RecordingNotificationStatus.PREPARING_OR_VERIFYING to Leaf(R.string.recording_notification_preparing_or_verifying,
            "recording_notification_preparing_or_verifying", "正在准备或验证录制"),
        RecordingNotificationStatus.SEGMENT_PREPARING to Leaf(R.string.final_recording_segments_phase_label_0,
            "final_recording_segments_phase_label_0", "准备分段录制，尚未确认首帧"),
        RecordingNotificationStatus.SEGMENT_RECORDING to Leaf(R.string.final_recording_segments_phase_label_1,
            "final_recording_segments_phase_label_1", "分段录制中"),
        RecordingNotificationStatus.SEGMENT_PAUSED to Leaf(R.string.final_recording_segments_phase_label_3,
            "final_recording_segments_phase_label_3", "已暂停，暂停时间不进入成片"),
        RecordingNotificationStatus.SEGMENT_RESUMING to Leaf(R.string.final_recording_segments_phase_label_4,
            "final_recording_segments_phase_label_4", "正在准备下一片段"),
        RecordingNotificationStatus.SEGMENT_STOPPING to Leaf(R.string.final_recording_segments_phase_label_5,
            "final_recording_segments_phase_label_5", "正在停止并关闭片段"),
    )
    private val chromeLeaves = listOf(
        Leaf(R.string.recording_notification_channel, "recording_notification_channel", "屏幕录制"),
        Leaf(R.string.recording_notification_title, "recording_notification_title", "③ 屏幕录制"),
        Leaf(R.string.recording_notification_stop, "recording_notification_stop", "停止并保存"),
    )
    private val allLeaves get() = statusLeaves.values + chromeLeaves

    private fun strings(locale: String, filename: String): Map<String, String> {
        val xml = String(Files.readAllBytes(File(root, "$locale/$filename").toPath()), UTF_8)
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val nodes = document.getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val key = node.attributes.getNamedItem("name").nodeValue
            assertNull("duplicate resource $key", result.put(key, node.textContent))
        }
        return result
    }

    private fun displayStrings(locale: String): Map<String, String> =
        strings(locale, "recording_notification.xml") + strings(locale, "final_status.xml")

    @Test fun everyNativeStatePreservesTheOriginalSixGroupsAndDefault() {
        val expected = mapOf(
            RecordingState.IDLE to RecordingNotificationStatus.PREPARING_OR_VERIFYING,
            RecordingState.AWAITING_USER_CONFIRMATION to RecordingNotificationStatus.PREPARING_OR_VERIFYING,
            RecordingState.AWAITING_AUDIO_PERMISSION to RecordingNotificationStatus.PREPARING_OR_VERIFYING,
            RecordingState.AWAITING_SYSTEM_CONSENT to RecordingNotificationStatus.PREPARING_OR_VERIFYING,
            RecordingState.PREPARING to RecordingNotificationStatus.PREPARING_OR_VERIFYING,
            RecordingState.RECORDING to RecordingNotificationStatus.RECORDING,
            RecordingState.PAUSED to RecordingNotificationStatus.PAUSED,
            RecordingState.RESUMING to RecordingNotificationStatus.PREPARING_OR_VERIFYING,
            RecordingState.STOPPING to RecordingNotificationStatus.STOPPING,
            RecordingState.FINALIZING to RecordingNotificationStatus.STOPPING,
            RecordingState.CANCELLING to RecordingNotificationStatus.CANCELLING,
            RecordingState.CAPTURE_FINISHED to RecordingNotificationStatus.PREPARING_OR_VERIFYING,
            RecordingState.CANCELLED to RecordingNotificationStatus.PREPARING_OR_VERIFYING,
            RecordingState.FAILED to RecordingNotificationStatus.PREPARING_OR_VERIFYING,
            RecordingState.RECOVERY_REQUIRED to RecordingNotificationStatus.RECOVERY_REQUIRED,
        )
        assertEquals(15, expected.size)
        assertEquals(RecordingState.entries.toSet(), expected.keys)
        expected.forEach { (state, status) -> assertEquals(state.name, status, recordingNotificationStatus(state)) }
        assertEquals(6, RecordingState.entries.map(::recordingNotificationStatus).toSet().size)
        assertEquals(9, expected.values.count { it == RecordingNotificationStatus.PREPARING_OR_VERIFYING })
    }

    @Test fun allThirteenSegmentPhasesKeepOnlyTheFiveExistingCaptureNotifications() {
        val expected = mapOf(
            RecordingSegmentsPhase.PREPARING to RecordingNotificationStatus.SEGMENT_PREPARING,
            RecordingSegmentsPhase.RECORDING to RecordingNotificationStatus.SEGMENT_RECORDING,
            RecordingSegmentsPhase.PAUSING to null,
            RecordingSegmentsPhase.PAUSED to RecordingNotificationStatus.SEGMENT_PAUSED,
            RecordingSegmentsPhase.RESUMING to RecordingNotificationStatus.SEGMENT_RESUMING,
            RecordingSegmentsPhase.STOPPING to RecordingNotificationStatus.SEGMENT_STOPPING,
            RecordingSegmentsPhase.PROCESSING to null,
            RecordingSegmentsPhase.VERIFYING to null,
            RecordingSegmentsPhase.SUCCEEDED to null,
            RecordingSegmentsPhase.RECOVERY_REQUIRED to null,
            RecordingSegmentsPhase.CANCELLING to null,
            RecordingSegmentsPhase.CANCELLED to null,
            RecordingSegmentsPhase.FAILED to null,
        )
        assertEquals(13, expected.size)
        assertEquals(RecordingSegmentsPhase.entries.toSet(), expected.keys)
        expected.forEach { (phase, status) -> assertEquals(phase.name, status, recordingSegmentNotificationStatus(phase)) }
        assertEquals(5, expected.values.filterNotNull().toSet().size)
        assertEquals(8, RecordingSegmentsPhase.entries.count { recordingSegmentNotificationStatus(it) == null })
        assertNull(recordingSegmentNotificationStatus(RecordingSegmentsPhase.SUCCEEDED))
    }

    @Test fun everyStatusHasItsExactDistinctResourceAndPreservesAllFifteenChineseLeaves() {
        val chinese = displayStrings("values-zh-rCN")
        assertEquals(12, RecordingNotificationStatus.entries.size)
        assertEquals(RecordingNotificationStatus.entries.toSet(), statusLeaves.keys)
        statusLeaves.forEach { (status, leaf) ->
            assertEquals(status.name, leaf.resource, recordingNotificationStatusResource(status))
            assertEquals(status.name, leaf.chinese, chinese.getValue(leaf.key))
        }
        chromeLeaves.forEach { leaf -> assertEquals(leaf.key, leaf.chinese, chinese.getValue(leaf.key)) }
        assertEquals(12, RecordingNotificationStatus.entries.map(::recordingNotificationStatusResource).toSet().size)
        assertEquals(15, allLeaves.size)
        assertEquals(15, allLeaves.map { it.resource }.toSet().size)
        assertEquals(15, allLeaves.map { it.chinese }.toSet().size)
    }

    @Test fun exactlyTenPairedNewKeysReuseOnlyTheFiveExistingSegmentResources() {
        val expectedNewKeys = allLeaves.map { it.key }.filter { it.startsWith("recording_notification_") }.toSet()
        assertEquals(10, expectedNewKeys.size)
        for (locale in listOf("values", "values-zh-rCN")) {
            val newStrings = strings(locale, "recording_notification.xml")
            assertEquals(locale, expectedNewKeys, newStrings.keys)
            val existing = strings(locale, "final_status.xml")
            val reusedKeys = allLeaves.map { it.key }.filter { it.startsWith("final_") }.toSet()
            assertEquals(setOf("final_recording_segments_phase_label_0", "final_recording_segments_phase_label_1",
                "final_recording_segments_phase_label_3", "final_recording_segments_phase_label_4",
                "final_recording_segments_phase_label_5"), reusedKeys)
            assertTrue(locale, existing.keys.containsAll(reusedKeys))
            assertTrue(locale, newStrings.keys.intersect(existing.keys).isEmpty())
        }
    }

    @Test fun everyDisplayLeafHasNonemptyBilingualTextWithoutParametersUrlsOrSessionIdentifiers() {
        val english = displayStrings("values")
        val chinese = displayStrings("values-zh-rCN")
        for (leaf in allLeaves) {
            val en = english.getValue(leaf.key)
            val zh = chinese.getValue(leaf.key)
            assertNotEquals(leaf.key, en, zh)
            assertFalse(leaf.key, Regex("[\\u4e00-\\u9fff]").containsMatchIn(en))
            for (text in listOf(en, zh)) {
                assertTrue(leaf.key, text.isNotBlank())
                for (forbidden in listOf("%", "{", "}", "$", "http://", "https://", "request_id", "requestId", "sessionId", "\uFFFD")) {
                    assertFalse("${leaf.key}: $forbidden", text.contains(forbidden))
                }
            }
        }
        assertEquals("③ Screen recording", english.getValue("recording_notification_title"))
        assertEquals("③ 屏幕录制", chinese.getValue("recording_notification_title"))
        assertEquals("Stop & Save", english.getValue("recording_notification_stop"))
    }

    @Test fun statusTokensHaveNoFreeTextFieldsAndStayUnchangedAcrossLocaleFixtures() {
        assertTrue(RecordingNotificationStatus::class.java.declaredFields.all { Modifier.isStatic(it.modifiers) })
        val english = displayStrings("values")
        val chinese = displayStrings("values-zh-rCN")
        for (status in RecordingNotificationStatus.entries) {
            val held = status
            val resource = recordingNotificationStatusResource(status)
            val leaf = statusLeaves.getValue(status)
            val firstEnglish = english.getValue(leaf.key)
            assertNotEquals(firstEnglish, chinese.getValue(leaf.key))
            assertEquals(firstEnglish, english.getValue(leaf.key))
            assertEquals(resource, recordingNotificationStatusResource(status))
            assertSame(held, status)
        }
    }
}
