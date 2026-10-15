package com.easysavevideo.app.library.destination

import com.easysavevideo.app.R
import com.easysavevideo.library.destination.OrdinaryDestinationChoice
import com.easysavevideo.library.export.ExportPhase
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM policy/resource/source contracts; not Compose rendering or device/SAF acceptance. */
class OrdinaryDestinationPresentationTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(name: String): String = File(root, "kotlin/com/easysavevideo/app/library/destination/$name.kt").readText()
    private val ui by lazy { source("OrdinaryDestinationUi") }
    private val mapper by lazy { source("OrdinaryDestinationResources") }
    private val runtime by lazy { source("AndroidOrdinaryDestinationRuntime") }
    private val policy by lazy { source("OrdinaryDestinationPresentation") }
    private val prefix = "ordinary_destination_"
    private val notices = mapOf(
        OrdinaryDestinationNoticeCode.LOCATION_OR_SOURCE_NOT_READY to R.string.ordinary_destination_notice_location_or_source_not_ready,
        OrdinaryDestinationNoticeCode.SOURCE_CHANGED_BEFORE_SELECTION to R.string.ordinary_destination_notice_source_changed_before_selection,
        OrdinaryDestinationNoticeCode.REMEMBERED_FOLDER_REQUIRED to R.string.ordinary_destination_notice_remembered_folder_required,
        OrdinaryDestinationNoticeCode.SOURCE_CHANGED_BEFORE_CREATION to R.string.ordinary_destination_notice_source_changed_before_creation,
        OrdinaryDestinationNoticeCode.FOLDER_PICKER_DETACHED to R.string.ordinary_destination_notice_folder_picker_detached,
        OrdinaryDestinationNoticeCode.FOLDER_PICKER_NOT_READY to R.string.ordinary_destination_notice_folder_picker_not_ready,
        OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATIONS_EXHAUSTED to R.string.ordinary_destination_notice_folder_picker_associations_exhausted,
        OrdinaryDestinationNoticeCode.FOLDER_PICKER_PENDING to R.string.ordinary_destination_notice_folder_picker_pending,
        OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATION_INVALID to R.string.ordinary_destination_notice_folder_picker_association_invalid,
        OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATION_STORAGE_FAILED to R.string.ordinary_destination_notice_folder_picker_association_storage_failed,
        OrdinaryDestinationNoticeCode.FOLDER_PICKER_LAUNCH_FAILED to R.string.ordinary_destination_notice_folder_picker_launch_failed,
        OrdinaryDestinationNoticeCode.FOLDER_SELECTION_CANCELLED to R.string.ordinary_destination_notice_folder_selection_cancelled,
        OrdinaryDestinationNoticeCode.FOLDER_RESULT_UNCONFIRMED to R.string.ordinary_destination_notice_folder_result_unconfirmed,
        OrdinaryDestinationNoticeCode.FOLDER_GRANT_CAPACITY_REACHED to R.string.ordinary_destination_notice_folder_grant_capacity_reached,
        OrdinaryDestinationNoticeCode.FOLDER_ADOPTION_FAILED to R.string.ordinary_destination_notice_folder_adoption_failed,
        OrdinaryDestinationNoticeCode.FOLDER_SELECTED to R.string.ordinary_destination_notice_folder_selected,
        OrdinaryDestinationNoticeCode.FOLDER_SELECTION_CHANGED to R.string.ordinary_destination_notice_folder_selection_changed,
        OrdinaryDestinationNoticeCode.FOLDER_CHANGE_CANCELLED to R.string.ordinary_destination_notice_folder_change_cancelled,
        OrdinaryDestinationNoticeCode.FOLDER_FORGOTTEN to R.string.ordinary_destination_notice_folder_forgotten,
        OrdinaryDestinationNoticeCode.FOLDER_PERMISSION_INVALIDATED to R.string.ordinary_destination_notice_folder_permission_invalidated,
        OrdinaryDestinationNoticeCode.COPY_STATUS_UNAVAILABLE to R.string.ordinary_destination_notice_copy_status_unavailable,
        OrdinaryDestinationNoticeCode.COPY_CANCELLATION_REQUESTED to R.string.ordinary_destination_notice_copy_cancellation_requested,
        OrdinaryDestinationNoticeCode.COPY_CANCELLATION_UNAVAILABLE to R.string.ordinary_destination_notice_copy_cancellation_unavailable,
        OrdinaryDestinationNoticeCode.REMEMBERED_FOLDER_UNAVAILABLE to R.string.ordinary_destination_notice_remembered_folder_unavailable,
        OrdinaryDestinationNoticeCode.LOCATION_UNAVAILABLE to R.string.ordinary_destination_notice_location_unavailable,
        OrdinaryDestinationNoticeCode.LOCATION_BUSY to R.string.ordinary_destination_notice_location_busy
    )
    private val states = mapOf(
        OrdinaryCopyState.NOT_SELECTED to R.string.ordinary_destination_copy_not_selected,
        OrdinaryCopyState.WAITING_FOR_LIBRARY to R.string.ordinary_destination_copy_waiting_for_library,
        OrdinaryCopyState.WAITING_TO_COPY to R.string.ordinary_destination_copy_waiting_to_copy,
        OrdinaryCopyState.VERIFIED_SAVED to R.string.ordinary_destination_copy_verified_saved,
        OrdinaryCopyState.INCOMPLETE to R.string.ordinary_destination_copy_incomplete,
        OrdinaryCopyState.CHECK_REQUIRED to R.string.ordinary_destination_copy_check_required
    )
    private val kinds = mapOf(
        OrdinaryCopyKind.ALBUM to R.string.ordinary_destination_copy_kind_album,
        OrdinaryCopyKind.FOLDER to R.string.ordinary_destination_copy_kind_folder
    )
    private val stateKeys = setOf("not_selected", "waiting_for_library", "waiting_to_copy", "verified_saved", "incomplete", "check_required")
        .map { prefix + "copy_" + it }.toSet()
    private val expectedKeys = notices.keys.map { prefix + "notice_" + it.name.lowercase(Locale.ROOT) }.toSet() +
        stateKeys + setOf(prefix + "copy_kind_album", prefix + "copy_kind_folder")
    private fun strings(locale: String, file: String = "ordinary_destination_display.xml"): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File(root, "res/$locale/$file")).getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val key = node.attributes.getNamedItem("name").nodeValue
            assertNull("duplicate resource $key", result.put(key, node.textContent))
        }
        return result
    }

    @Test fun everyTypedNoticeStateAndKindHasItsOwnExplicitResource() {
        assertEquals(26, OrdinaryDestinationNoticeCode.entries.size)
        assertEquals(OrdinaryDestinationNoticeCode.entries.toSet(), notices.keys)
        assertEquals(OrdinaryCopyState.entries.toSet(), states.keys)
        assertEquals(OrdinaryCopyKind.entries.toSet(), kinds.keys)
        notices.forEach { (notice, resource) -> assertEquals(notice.name, resource, ordinaryDestinationNoticeResource(notice)) }
        states.forEach { (state, resource) -> assertEquals(state.name, resource, ordinaryCopyStateResource(state)) }
        kinds.forEach { (kind, resource) -> assertEquals(kind.name, resource, ordinaryCopyKindResource(kind)) }
        val resources = notices.values + states.values + kinds.values
        assertEquals(34, resources.size)
        assertEquals(resources.size, resources.toSet().size)
        assertFalse(mapper.contains("else ->"))
        assertFalse(policy.substringBefore("internal enum class OrdinaryCopyKind").contains("("))
    }

    @Test fun allSeventyTwoChoiceSavedAndPhaseCombinationsPreserveOriginalPrecedence() {
        val phases = listOf<ExportPhase?>(null) + ExportPhase.entries
        assertEquals(3, OrdinaryDestinationChoice.entries.size)
        assertEquals(12, phases.size)
        // Explicit phase expectations are independent of the production branch ordering.
        val savedStates = mapOf(
            null to OrdinaryCopyState.WAITING_TO_COPY,
            ExportPhase.PREPARED to OrdinaryCopyState.WAITING_TO_COPY,
            ExportPhase.CREATE_INTENT to OrdinaryCopyState.CHECK_REQUIRED,
            ExportPhase.COPYING to OrdinaryCopyState.CHECK_REQUIRED,
            ExportPhase.VERIFYING to OrdinaryCopyState.CHECK_REQUIRED,
            ExportPhase.PUBLISH_INTENT to OrdinaryCopyState.CHECK_REQUIRED,
            ExportPhase.SUCCEEDED to OrdinaryCopyState.VERIFIED_SAVED,
            ExportPhase.COPIED_UNVERIFIED to OrdinaryCopyState.CHECK_REQUIRED,
            ExportPhase.CLEANUP_PENDING to OrdinaryCopyState.CHECK_REQUIRED,
            ExportPhase.FAILED to OrdinaryCopyState.INCOMPLETE,
            ExportPhase.CANCELLED to OrdinaryCopyState.INCOMPLETE,
            ExportPhase.RECONCILIATION_REQUIRED to OrdinaryCopyState.CHECK_REQUIRED
        )
        assertEquals(phases.toSet(), savedStates.keys)
        val seen = mutableSetOf<OrdinaryCopyState>()
        var combinations = 0
        for (choice in OrdinaryDestinationChoice.entries) for (saved in listOf(false, true)) for (phase in phases) {
            val row = OrdinaryDestinationStatus("opaque-task-id", saved, choice, phase)
            val expectedState = if (choice == OrdinaryDestinationChoice.APP_LIBRARY) OrdinaryCopyState.NOT_SELECTED
                else if (!saved) OrdinaryCopyState.WAITING_FOR_LIBRARY else savedStates.getValue(phase)
            val expectedKind = if (choice == OrdinaryDestinationChoice.FOLDER_COPY) OrdinaryCopyKind.FOLDER else OrdinaryCopyKind.ALBUM
            assertEquals(row.toString(), OrdinaryCopyDisplay(expectedKind, expectedState), ordinaryCopyDisplay(row))
            // This migration must not broaden the existing cancellation gate.
            val cancellable = saved && choice != OrdinaryDestinationChoice.APP_LIBRARY &&
                phase in setOf(null, ExportPhase.PREPARED, ExportPhase.CREATE_INTENT, ExportPhase.COPYING, ExportPhase.VERIFYING)
            assertEquals(row.toString(), cancellable, ordinaryCopyCanCancel(row))
            seen += ordinaryCopyDisplay(row).state
            combinations++
        }
        assertEquals(72, combinations)
        assertEquals(OrdinaryCopyState.entries.toSet(), seen)
    }

    @Test fun exactNewResourceKeysArePairedWithOnlySixSingleKindArguments() {
        assertEquals(34, expectedKeys.size)
        for (locale in listOf("values", "values-zh-rCN")) {
            val text = strings(locale)
            assertEquals(expectedKeys, text.keys)
            assertTrue(text.keys.intersect(strings(locale, "ordinary_destination.xml").keys).isEmpty())
            text.forEach { (key, value) ->
                assertTrue(key, value.isNotBlank())
                assertFalse(key, value.contains('\uFFFD'))
                val formats = Regex("%(?:[0-9]+\\$)?(?:\\.[0-9]+)?[a-zA-Z%]").findAll(value).map { it.value }.toList()
                assertEquals(key, if (key in stateKeys) listOf("%1\$s") else emptyList<String>(), formats)
                assertEquals(key, formats.size, value.count { it == '%' })
                if (locale == "values") assertFalse(key, Regex("[\\u4e00-\\u9fff]").containsMatchIn(value))
            }
            for (kind in listOf("album", "folder")) for (key in stateKeys) {
                val label = text.getValue(prefix + "copy_kind_" + kind)
                val format = text.getValue(key)
                assertEquals(format.replace("%1\$s", label), String.format(Locale.US, format, label))
            }
        }
    }

    @Test fun originalChineseNoticesAndCopySentencesRemainExact() {
        val originalNotices = mapOf(
            OrdinaryDestinationNoticeCode.LOCATION_OR_SOURCE_NOT_READY to "保存位置或来源尚未就绪，请重新选择。",
            OrdinaryDestinationNoticeCode.SOURCE_CHANGED_BEFORE_SELECTION to "来源或页面已变化，请重新选择保存位置。",
            OrdinaryDestinationNoticeCode.REMEMBERED_FOLDER_REQUIRED to "尚未记住可用文件夹，请先在普通媒体保存位置设置中明确选择文件夹。",
            OrdinaryDestinationNoticeCode.SOURCE_CHANGED_BEFORE_CREATION to "来源或页面已变化，尚未创建媒体；请重新选择。",
            OrdinaryDestinationNoticeCode.FOLDER_PICKER_DETACHED to "文件夹选择页面已变化，本次结果不会设为保存位置。请重新选择；系统授权可在系统设置中管理。",
            OrdinaryDestinationNoticeCode.FOLDER_PICKER_NOT_READY to "文件夹选择器尚未就绪，请稍后重试。",
            OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATIONS_EXHAUSTED to "本机文件夹选择关联次数已达安全上限，不能通过重试恢复。已有任务保留；可继续使用 App媒体库、相册或媒体库中的单次另存。",
            OrdinaryDestinationNoticeCode.FOLDER_PICKER_PENDING to "上一次系统文件夹选择尚未返回。请先关闭或完成那个选择器；本次没有打开新的选择器，App媒体库仍可使用。",
            OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATION_INVALID to "文件夹选择关联记录不可用，未重置或重复使用旧关联。可继续使用 App媒体库、相册或媒体库中的单次另存。",
            OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATION_STORAGE_FAILED to "本次文件夹选择关联未能安全保存，尚未打开系统选择器。请检查本机存储后重试。",
            OrdinaryDestinationNoticeCode.FOLDER_PICKER_LAUNCH_FAILED to "系统文件夹选择器未能打开，请稍后重试。",
            OrdinaryDestinationNoticeCode.FOLDER_SELECTION_CANCELLED to "已取消文件夹选择，原保存位置未改变。",
            OrdinaryDestinationNoticeCode.FOLDER_RESULT_UNCONFIRMED to "文件夹选择结果未能确认，保存位置需要重新核对。系统可能已记住授权，可在系统设置中管理；原件不会被删除。",
            OrdinaryDestinationNoticeCode.FOLDER_GRANT_CAPACITY_REACHED to "文件夹授权记录已达到本机安全上限，暂不能记住新文件夹；已有任务和记录保留。系统撤销授权不会清除此记录，可继续使用 App媒体库、相册或媒体库中的单次另存。",
            OrdinaryDestinationNoticeCode.FOLDER_ADOPTION_FAILED to "未能采用本次文件夹选择。系统可能已记住授权，可在系统设置中核对；检测到失效的旧授权不会随再次选择而复活，原件保留。",
            OrdinaryDestinationNoticeCode.FOLDER_SELECTED to "之后新建媒体会保留 App媒体库原件，并向所选文件夹另存一份。该文件提供方可能同步到云端；副本在校验前就可能可见。",
            OrdinaryDestinationNoticeCode.FOLDER_SELECTION_CHANGED to "文件夹选择后授权或设置已有变化，后续新建媒体将使用当前显示的保存位置；原件保留。",
            OrdinaryDestinationNoticeCode.FOLDER_CHANGE_CANCELLED to "已取消更改保存位置，迟到结果不会被采用。系统授权可在系统设置中管理。",
            OrdinaryDestinationNoticeCode.FOLDER_FORGOTTEN to "已停止记住此文件夹，后续默认保存到 App媒体库。已接受任务和未确认副本仍使用原选择；为保留它们的处理或清理能力，未撤销系统授权。可在系统设置手动管理，撤销后可能需要自行清理遗留副本。",
            OrdinaryDestinationNoticeCode.FOLDER_PERMISSION_INVALIDATED to "文件夹授权已失效，后续默认保存到 App媒体库。请明确重新选择文件夹；旧操作不会自动重建副本，原件保留。",
            OrdinaryDestinationNoticeCode.COPY_STATUS_UNAVAILABLE to "额外副本状态暂不可读取，App媒体库原件保留。可稍后恢复核对。",
            OrdinaryDestinationNoticeCode.COPY_CANCELLATION_REQUESTED to "已请求取消本次额外副本。正在等待读写退出并核对清理；App媒体库原件保留。",
            OrdinaryDestinationNoticeCode.COPY_CANCELLATION_UNAVAILABLE to "本次副本已完成、已进入最终提交或暂不可取消，请恢复核对。App媒体库原件保留。",
            OrdinaryDestinationNoticeCode.REMEMBERED_FOLDER_UNAVAILABLE to "已记住的文件夹选择不再可用，后续默认保存到 App媒体库。需要文件夹副本时请明确重新选择；旧失效授权的任务不会自动重建副本，原件保留。",
            OrdinaryDestinationNoticeCode.LOCATION_UNAVAILABLE to "普通媒体保存位置暂不可用。已有原件保留，未确认的额外副本请恢复核对。",
            OrdinaryDestinationNoticeCode.LOCATION_BUSY to "保存位置操作暂忙，请稍后重试。"
        )
        val text = strings("values-zh-rCN")
        assertEquals(OrdinaryDestinationNoticeCode.entries.toSet(), originalNotices.keys)
        originalNotices.forEach { (code, original) ->
            assertEquals(code.name, original, text.getValue(prefix + "notice_" + code.name.lowercase(Locale.ROOT)))
        }
        val originalStates = mapOf(
            "not_selected" to "本次未选择",
            "waiting_for_library" to "等待本次成片验证入库",
            "waiting_to_copy" to "等待复制",
            "verified_saved" to "已完整校验并保存",
            "incomplete" to "未完成。本次复制不会删除 App媒体库原件；请在媒体库核对来源后重新明确选择另存一份",
            "check_required" to "处理中或结果待核对，请恢复检查。不会自动新建重复副本"
        )
        assertEquals(stateKeys, originalStates.keys.map { prefix + "copy_" + it }.toSet())
        for ((kind, label) in mapOf("album" to "相册副本", "folder" to "文件夹副本")) {
            assertEquals(label, text.getValue(prefix + "copy_kind_" + kind))
            for ((state, original) in originalStates) {
                assertEquals("$label：$original", String.format(Locale.US, text.getValue(prefix + "copy_" + state), label))
            }
        }
    }

    @Test fun englishRetainsSafetyQualifiersWithoutClaimingSuccess() {
        val text = strings("values")
        fun notice(code: OrdinaryDestinationNoticeCode) = text.getValue(prefix + "notice_" + code.name.lowercase(Locale.ROOT))
        val qualifiers = mapOf(
            OrdinaryDestinationNoticeCode.SOURCE_CHANGED_BEFORE_CREATION to listOf("has not been created yet"),
            OrdinaryDestinationNoticeCode.FOLDER_PICKER_DETACHED to listOf("will not become the save location", "system settings"),
            OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATIONS_EXHAUSTED to listOf("retrying cannot recover", "Existing tasks are kept", "one-time Save a copy"),
            OrdinaryDestinationNoticeCode.FOLDER_PICKER_PENDING to listOf("has not returned", "No new picker was opened", "App Library is still available"),
            OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATION_INVALID to listOf("not been reset or reused", "one-time Save a copy"),
            OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATION_STORAGE_FAILED to listOf("could not be saved safely", "has not been opened"),
            OrdinaryDestinationNoticeCode.FOLDER_SELECTION_CANCELLED to listOf("previous save location is unchanged"),
            OrdinaryDestinationNoticeCode.FOLDER_RESULT_UNCONFIRMED to listOf("could not be confirmed", "may have retained permission", "original will not be deleted"),
            OrdinaryDestinationNoticeCode.FOLDER_GRANT_CAPACITY_REACHED to listOf("safety limit", "Existing tasks and records are kept", "Revoking system permission does not clear", "one-time Save a copy"),
            OrdinaryDestinationNoticeCode.FOLDER_ADOPTION_FAILED to listOf("may have retained permission", "does not revive old permissions", "original is kept"),
            OrdinaryDestinationNoticeCode.FOLDER_SELECTED to listOf("keep the App Library original", "may sync to the cloud", "may be visible before verification"),
            OrdinaryDestinationNoticeCode.FOLDER_SELECTION_CHANGED to listOf("currently displayed", "original is kept"),
            OrdinaryDestinationNoticeCode.FOLDER_CHANGE_CANCELLED to listOf("Late results will not be adopted", "system settings"),
            OrdinaryDestinationNoticeCode.FOLDER_FORGOTTEN to listOf("Accepted tasks and unconfirmed copies still use their original choice", "has not been revoked", "processed or cleaned up", "clean up leftover copies yourself"),
            OrdinaryDestinationNoticeCode.FOLDER_PERMISSION_INVALIDATED to listOf("Explicitly select a folder again", "will not automatically recreate copies", "original is kept"),
            OrdinaryDestinationNoticeCode.COPY_STATUS_UNAVAILABLE to listOf("temporarily unavailable", "original is kept", "resume checking later"),
            OrdinaryDestinationNoticeCode.COPY_CANCELLATION_REQUESTED to listOf("was requested", "Waiting for reading and writing to stop", "cleanup to be checked", "original is kept"),
            OrdinaryDestinationNoticeCode.COPY_CANCELLATION_UNAVAILABLE to listOf("has completed, entered final commit, or is temporarily unavailable", "resume checking", "original is kept"),
            OrdinaryDestinationNoticeCode.REMEMBERED_FOLDER_UNAVAILABLE to listOf("Explicitly select a folder again", "old invalid permissions", "will not automatically recreate copies", "original is kept"),
            OrdinaryDestinationNoticeCode.LOCATION_UNAVAILABLE to listOf("Existing originals are kept", "checking unconfirmed extra copies")
        )
        qualifiers.forEach { (code, phrases) -> phrases.forEach { assertTrue("$code: $it", notice(code).contains(it)) } }
        assertTrue(text.getValue(prefix + "copy_incomplete").contains("will not delete the App Library original"))
        assertTrue(text.getValue(prefix + "copy_incomplete").contains("explicitly choose Save a copy again"))
        assertTrue(text.getValue(prefix + "copy_check_required").contains("in progress or awaiting verification"))
        assertTrue(text.getValue(prefix + "copy_check_required").contains("No duplicate copy will be created automatically"))
        assertTrue(text.getValue(prefix + "copy_verified_saved").contains("fully verified and saved"))
    }

    @Test fun allRuntimeEmissionsStayTypedNullableAndOnlyCurrentCompositionResolvesText() {
        val emitted = Regex("OrdinaryDestinationNoticeCode\\.([A-Z_]+)").findAll(runtime).map { it.groupValues[1] }.toList()
        // Coverage is the closed set of localized kinds, not one textual reference per kind.
        // Failed source fences reuse LOCATION_UNAVAILABLE; ASK keeps its missing-folder
        // prompt pending while automatic selection rejects with that same localized kind.
        val reusedKinds = emitted.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertEquals(mapOf("LOCATION_UNAVAILABLE" to 3, "REMEMBERED_FOLDER_REQUIRED" to 2), reusedKinds)
        assertEquals(OrdinaryDestinationNoticeCode.entries.map { it.name }.toSet(), emitted.toSet())
        assertTrue(runtime.contains("private var noticePresentation by mutableStateOf<NoticePresentation?>(null)"))
        assertTrue(runtime.contains("private set(value) { noticePresentation = value?.let(::NoticePresentation) }"))
        assertEquals(4, Regex("notice = null").findAll(runtime).count())
        assertTrue(runtime.contains("fun clearNotice() { requireMain(); notice = null }"))
        assertFalse(Regex("[\\u4e00-\\u9fff]").containsMatchIn(runtime + policy + mapper + ui))
        assertFalse(runtime.contains("R.string"))
        assertFalse(runtime.contains("getString("))
        assertFalse(runtime.contains("ordinaryDestinationNoticeResource"))
        assertEquals(2, Regex("stringResource\\(ordinaryDestinationNoticeResource\\(").findAll(ui).count())
        assertTrue(ui.contains("EsvText(stringResource(ordinaryDestinationNoticeResource(it)), style = v.captionTextStyle)"))
        assertTrue(ui.contains("EsvText(stringResource(ordinaryDestinationNoticeResource(notice)))"))
        assertTrue(ui.contains("val display = ordinaryCopyDisplay(status)"))
        assertTrue(ui.contains("stringResource(ordinaryCopyStateResource(display.state),"))
        assertTrue(ui.contains("stringResource(ordinaryCopyKindResource(display.kind))"))
        listOf("ordinaryCopyStatus(", "ordinaryDestinationLabel(", "Locale.getDefault", "remember {", "LaunchedEffect")
            .forEach { assertFalse(it, (runtime + policy + mapper + ui).contains(it)) }
        // The central entry remembers only callback authority, never localized display text.
        assertEquals(1, Regex("remember\\(").findAll(ui).count())
        val remembered = ui.substringAfter("remember(runtime, actionScopeKey, canAct) {").substringBefore("DisposableEffect")
        assertTrue(remembered.contains("DestinationSettingsActions("))
        assertFalse(remembered.contains("stringResource"))
        assertTrue(ui.contains("onDispose { actions.close() }"))
        listOf("android.", "androidx.", "R.string", "Context", "Locale", "creationId", "String", "URI", "Uri")
            .forEach { assertFalse(it, policy.contains(it)) }
        assertTrue(ui.contains("runtime.statuses.filter { it.internalSaved }.take(8).forEach { status ->"))
        assertTrue(ui.contains("stringResource(R.string.ordinary_destination_task_summary, status.creationId.take(8))"))
    }

    @Test fun everyResourceHasAProductionConsumerAndEveryReadSourceIsTracked() {
        val fixed = strings("values", "ordinary_destination.xml")
        assertEquals(26, fixed.size)
        val references = Regex("R\\.string\\.(ordinary_destination_[a-z_]+)").findAll(ui + mapper)
            .map { it.groupValues[1] }.toSet()
        assertEquals(fixed.keys + expectedKeys, references)
        val build = File(root, "../../build.gradle.kts").readText()
        listOf("ordinary_destination.xml", "ordinary_destination_display.xml").forEach {
            assertTrue(it, build.substringAfter("val contractResourceInputs").substringBefore("val contractSourceInputs").contains(it))
        }
        for (name in listOf("AndroidOrdinaryDestinationRuntime", "OrdinaryDestinationPresentation", "OrdinaryDestinationResources", "OrdinaryDestinationUi")) {
            assertTrue(name, build.substringAfter("val contractSourceInputs").substringBefore("tasks.withType<Test>")
                .contains("src/main/kotlin/com/easysavevideo/app/library/destination/$name.kt"))
        }
    }
}
