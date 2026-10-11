package com.easysavevideo.app.editor.ui

import com.easysavevideo.library.MediaItem
import com.easysavevideo.library.MediaLifecycle
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.edit.EditCompression
import com.easysavevideo.library.edit.EditError
import com.easysavevideo.library.edit.EditFailure
import com.easysavevideo.library.edit.EditGeometry
import com.easysavevideo.library.edit.EditLimits
import com.easysavevideo.library.edit.EditRequest
import com.easysavevideo.library.edit.EditSegment
import com.easysavevideo.library.edit.NormalizedCrop
import java.util.Collections
import kotlin.math.abs

/** Catalog snapshot and exact read authority travel together; no path or mutable selection escapes. */
data class EditorSegment(
    val id: Long,
    val item: MediaItem,
    val startSeconds: String = "0",
    val endSeconds: String = item.durationMillis?.takeIf { it <= EditLimits.MAX_SOURCE_DURATION_US / 1_000 }
        ?.let { editorSeconds(it * 1_000) } ?: "",
    val crop: NormalizedCrop = NormalizedCrop.FULL,
) {
    init { require(id > 0); require(item.lifecycle == MediaLifecycle.READY) }
    val reference: MediaReadRef = item.readRef()
    override fun toString() = "EditorSegment(id=$id, [redacted])"
}

/** Defensive snapshot rather than a read-only view of a caller's mutable collection. */
class EditorDraft(
    segments: List<EditorSegment> = emptyList(),
    val compression: EditCompression = EditCompression.ORIGINAL_QUALITY,
    val selectedSegmentId: Long? = segments.firstOrNull()?.id,
    val nextSegmentId: Long = (segments.maxOfOrNull { it.id } ?: 0L) + 1L,
) {
    val segments: List<EditorSegment> = Collections.unmodifiableList(ArrayList(segments))
    init {
        require(segments.size <= EditLimits.MAX_SEGMENTS)
        require(segments.map { it.id }.distinct().size == segments.size)
        require(nextSegmentId > (segments.maxOfOrNull { it.id } ?: 0L))
        require(selectedSegmentId == null || segments.any { it.id == selectedSegmentId })
    }
    val selectedSegment: EditorSegment? get() = segments.firstOrNull { it.id == selectedSegmentId }
    fun copy(
        segments: List<EditorSegment> = this.segments,
        compression: EditCompression = this.compression,
        selectedSegmentId: Long? = this.selectedSegmentId,
        nextSegmentId: Long = this.nextSegmentId,
    ) = EditorDraft(segments, compression, selectedSegmentId, nextSegmentId)
    fun toEditRequest(operationId: String): EditRequest {
        if (editorDraftError(this) != null) throw EditFailure(EditError.INVALID_REQUEST)
        return EditRequest(operationId, segments.map {
            EditSegment(it.reference, requireNotNull(parseEditorSeconds(it.startSeconds)),
                requireNotNull(parseEditorSeconds(it.endSeconds)), it.crop)
        }, compression)
    }
    override fun toString() = "EditorDraft(segments=${segments.size}, compression=$compression, [redacted])"
}

/** Dimensions already transformed into display orientation by a verified source probe. */
data class EditorDisplayGeometry(val width: Int, val height: Int, val durationUs: Long) {
    init { require(width > 0 && height > 0 && durationUs > 0) }
}

/** Exact requested and frame-aligned processing ranges, retained for user comparison. */
data class EditorExecutionRange(
    val requestedStartUs: Long, val requestedEndUs: Long,
    val executionStartUs: Long, val executionEndUs: Long, val frameCount: Int,
) {
    init {
        require(requestedStartUs >= 0 && requestedEndUs > requestedStartUs)
        require(executionStartUs >= requestedStartUs && executionEndUs <= requestedEndUs && executionEndUs > executionStartUs)
        require(frameCount >= 3)
    }
    val durationUs: Long get() = executionEndUs - executionStartUs
}

enum class EditorPhase {
    IDLE, PROBING, PREPARING, PROCESSING, VERIFYING, PUBLISHING, CANCELLING,
    WAITING_NATIVE_RELEASE, BLOCKED, SUCCEEDED, FAILED, CANCELLED,
}

/** The runtime owns lifecycle, source probes, feature checks, native ownership, and publication. */
class EditorViewState(
    val draft: EditorDraft = EditorDraft(),
    availableItems: List<MediaItem> = emptyList(),
    val foreground: Boolean = true,
    val phase: EditorPhase = EditorPhase.IDLE,
    displayGeometries: Map<MediaReadRef, EditorDisplayGeometry> = emptyMap(),
    val notice: String? = null,
    val completedReference: MediaReadRef? = null,
    /** Increment after EVERY submit attempt, even one rejected before a task starts. */
    val submissionRevision: Long = 0,
    executionRanges: List<EditorExecutionRange> = emptyList(),
    val draftRestoration: EditorDraftRestoration = EditorDraftRestoration.NONE,
) {
    val availableItems: List<MediaItem> = Collections.unmodifiableList(ArrayList(availableItems.take(100)))
    val displayGeometries: Map<MediaReadRef, EditorDisplayGeometry> =
        Collections.unmodifiableMap(HashMap(displayGeometries))
    val executionRanges: List<EditorExecutionRange> = Collections.unmodifiableList(ArrayList(executionRanges.take(EditLimits.MAX_SEGMENTS)))
    val busy: Boolean get() = phase in setOf(EditorPhase.PROBING, EditorPhase.PREPARING, EditorPhase.PROCESSING,
        EditorPhase.VERIFYING, EditorPhase.PUBLISHING, EditorPhase.CANCELLING, EditorPhase.WAITING_NATIVE_RELEASE)
    val editable: Boolean get() = foreground && !busy && phase != EditorPhase.BLOCKED
    fun copy(
        draft: EditorDraft = this.draft,
        availableItems: List<MediaItem> = this.availableItems,
        foreground: Boolean = this.foreground,
        phase: EditorPhase = this.phase,
        displayGeometries: Map<MediaReadRef, EditorDisplayGeometry> = this.displayGeometries,
        notice: String? = this.notice,
        completedReference: MediaReadRef? = this.completedReference,
        submissionRevision: Long = this.submissionRevision,
        executionRanges: List<EditorExecutionRange> = this.executionRanges,
        draftRestoration: EditorDraftRestoration = this.draftRestoration,
    ) = EditorViewState(draft, availableItems, foreground, phase, displayGeometries, notice,
        completedReference, submissionRevision, executionRanges, draftRestoration)
    override fun toString() = "EditorViewState(phase=$phase, [redacted])"
}

fun addEditorSegment(draft: EditorDraft, item: MediaItem): EditorDraft {
    if (draft.segments.size >= EditLimits.MAX_SEGMENTS || editorSourceError(item) != null) return draft
    val segment = EditorSegment(draft.nextSegmentId, item)
    return draft.copy(segments = draft.segments + segment, selectedSegmentId = segment.id,
        nextSegmentId = segment.id + 1)
}

fun removeEditorSegment(draft: EditorDraft, id: Long): EditorDraft {
    val index = draft.segments.indexOfFirst { it.id == id }
    if (index < 0) return draft
    val remaining = draft.segments.filterNot { it.id == id }
    val selected = if (draft.selectedSegmentId == id) remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.id
        else draft.selectedSegmentId
    return draft.copy(segments = remaining, selectedSegmentId = selected)
}

fun moveEditorSegment(draft: EditorDraft, id: Long, direction: Int): EditorDraft {
    if (direction != -1 && direction != 1) return draft
    val from = draft.segments.indexOfFirst { it.id == id }
    val to = from + direction
    if (from < 0 || to !in draft.segments.indices) return draft
    val items = draft.segments.toMutableList()
    Collections.swap(items, from, to)
    return draft.copy(segments = items)
}

fun updateEditorSegment(draft: EditorDraft, id: Long, transform: (EditorSegment) -> EditorSegment): EditorDraft =
    draft.copy(segments = draft.segments.map { if (it.id == id) transform(it) else it })

/** Decimal seconds, up to microseconds. Scientific notation, negative values and overflow are rejected. */
fun parseEditorSeconds(text: String): Long? {
    if (!text.matches(Regex("[0-9]{1,4}(?:\\.[0-9]{1,6})?"))) return null
    val pieces = text.split('.')
    val seconds = pieces[0].toLongOrNull() ?: return null
    val micros = pieces.getOrNull(1)?.padEnd(6, '0')?.toLongOrNull() ?: 0
    val result = seconds * 1_000_000 + micros
    return result.takeIf { it <= EditLimits.MAX_SOURCE_DURATION_US }
}

fun editorSeconds(microseconds: Long): String {
    if (microseconds < 0) return ""
    val fraction = (microseconds % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    return (microseconds / 1_000_000).toString() + if (fraction.isEmpty()) "" else ".$fraction"
}

fun editorSourceError(item: MediaItem): String? {
    val durationMillis = item.durationMillis
    return when {
        item.lifecycle != MediaLifecycle.READY -> "此来源已不可读取，请重新选择。"
        item.sizeBytes !in 1..EditLimits.MAX_SOURCE_BYTES -> "此来源大小超出 M6-A 范围（单个最多 2 GiB）。"
        durationMillis != null && durationMillis > EditLimits.MAX_SOURCE_DURATION_US / 1_000 -> "此来源超过 M6-A 的 30 分钟上限。"
        durationMillis != null && durationMillis < EditLimits.MIN_SEGMENT_DURATION_US / 1_000 -> "此来源不足 0.25 秒。"
        else -> null
    }
}

fun editorSegmentError(segment: EditorSegment, geometry: EditorDisplayGeometry? = null): String? {
    editorSourceError(segment.item)?.let { return it }
    val start = parseEditorSeconds(segment.startSeconds) ?: return "入点请输入有效秒数，最多 6 位小数。"
    val end = parseEditorSeconds(segment.endSeconds) ?: return "出点请输入有效秒数，最多 6 位小数。"
    if (end <= start || end - start < EditLimits.MIN_SEGMENT_DURATION_US) return "出点须晚于入点，片段至少 0.25 秒。"
    val duration = geometry?.durationUs ?: segment.item.durationMillis?.let { it * 1_000 }
    if (duration != null && end > duration) return "出点不能超过来源时长 ${editorSeconds(duration)} 秒。"
    if (geometry != null) editorCropError(segment.crop, geometry)?.let { return it }
    return null
}

fun editorCropError(crop: NormalizedCrop, geometry: EditorDisplayGeometry): String? = try {
    EditGeometry.displayCrop(crop, geometry.width, geometry.height, 0)
    null
} catch (_: EditFailure) {
    "此裁切范围不能形成受支持的偶数像素画面，请扩大选区或恢复完整画面。"
}

fun editorDraftError(draft: EditorDraft, geometries: Map<MediaReadRef, EditorDisplayGeometry> = emptyMap()): String? {
    if (draft.segments.isEmpty()) return "请先添加至少一个普通媒体库视频。"
    draft.segments.forEachIndexed { index, segment ->
        editorSegmentError(segment, geometries[segment.reference])?.let { return "片段 ${index + 1}：$it" }
    }
    if (draft.segments.sumOf { requireNotNull(parseEditorSeconds(it.endSeconds)) - requireNotNull(parseEditorSeconds(it.startSeconds)) }
        > EditLimits.MAX_OUTPUT_DURATION_US) return "总有效时长不能超过 30 分钟。"
    if (draft.segments.distinctBy { it.reference }.sumOf { it.item.sizeBytes } > EditLimits.MAX_TOTAL_SOURCE_BYTES)
        return "所选不同来源合计不能超过 4 GiB。"
    return null
}

fun editorPhaseText(state: EditorViewState): String = when (state.phase) {
    EditorPhase.IDLE -> "就绪：编辑参数后创建一个新的普通媒体库视频。"
    EditorPhase.PROBING -> "正在验证来源格式和显示方向，尚未开始输出…"
    EditorPhase.PREPARING -> "正在检查来源、权益、空间与资源，尚未完成…"
    EditorPhase.PROCESSING -> "正在处理视频，进度未知。请保持应用在前台。"
    EditorPhase.VERIFYING -> "正在验证新视频，尚未入库…"
    EditorPhase.PUBLISHING -> "正在提交到普通媒体库，尚未确认完成…"
    EditorPhase.CANCELLING -> "已请求安全停止，正在等待实际退出；原件保留。"
    EditorPhase.WAITING_NATIVE_RELEASE -> "正在等待原生媒体资源实际退出；仍未完成，不能开始新任务。"
    EditorPhase.BLOCKED -> "媒体资源退出或结果状态尚未确认，编辑暂不可用。请按下方提示处理。"
    EditorPhase.SUCCEEDED -> if (state.completedReference != null) "新视频已验证并入库，原件保留。" else "结果尚未确认入库，不能预览为已完成视频。"
    EditorPhase.FAILED -> "本次编辑未完成，原件保留。"
    EditorPhase.CANCELLED -> "本次编辑已安全停止，原件保留。"
}

/** Per-composition click latch. The runtime must also synchronously reject duplicate admission. */
internal class EditorSubmitGuard {
    private var submittedRevision: Long? = null
    fun isPending(revision: Long): Boolean = submittedRevision == revision
    fun trySubmit(revision: Long, enabled: Boolean): Boolean {
        if (!enabled || submittedRevision == revision) return false
        submittedRevision = revision
        return true
    }
}

enum class EditorCropHandle { MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Hit coordinates are normalized within the actual display-content rectangle, excluding letterbox bars. */
fun editorCropHit(crop: NormalizedCrop, x: Double, y: Double, slopX: Double, slopY: Double): EditorCropHandle? {
    if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0 ||
        !slopX.isFinite() || !slopY.isFinite() || slopX <= 0.0 || slopY <= 0.0) return null
    val handles = listOf(
        Triple(EditorCropHandle.TOP_LEFT, crop.left, crop.top), Triple(EditorCropHandle.TOP_RIGHT, crop.right, crop.top),
        Triple(EditorCropHandle.BOTTOM_LEFT, crop.left, crop.bottom), Triple(EditorCropHandle.BOTTOM_RIGHT, crop.right, crop.bottom),
        Triple(EditorCropHandle.LEFT, crop.left, (crop.top + crop.bottom) / 2),
        Triple(EditorCropHandle.TOP, (crop.left + crop.right) / 2, crop.top),
        Triple(EditorCropHandle.RIGHT, crop.right, (crop.top + crop.bottom) / 2),
        Triple(EditorCropHandle.BOTTOM, (crop.left + crop.right) / 2, crop.bottom),
    )
    val close = handles.filter { abs(x - it.second) <= slopX && abs(y - it.third) <= slopY }
        .minByOrNull { val dx = (x - it.second) / slopX; val dy = (y - it.third) / slopY; dx * dx + dy * dy }
    if (close != null) return close.first
    return EditorCropHandle.MOVE.takeIf { x in crop.left..crop.right && y in crop.top..crop.bottom }
}

fun dragEditorCrop(crop: NormalizedCrop, handle: EditorCropHandle, dx: Double, dy: Double): NormalizedCrop {
    if (!dx.isFinite() || !dy.isFinite()) return crop
    val minimum = 0.02
    if (handle == EditorCropHandle.MOVE) {
        val x = dx.coerceIn(-crop.left, 1.0 - crop.right)
        val y = dy.coerceIn(-crop.top, 1.0 - crop.bottom)
        return NormalizedCrop((crop.left + x).coerceIn(0.0, 1.0), (crop.top + y).coerceIn(0.0, 1.0),
            (crop.right + x).coerceIn(0.0, 1.0), (crop.bottom + y).coerceIn(0.0, 1.0))
    }
    val left = if (handle in setOf(EditorCropHandle.LEFT, EditorCropHandle.TOP_LEFT, EditorCropHandle.BOTTOM_LEFT))
        (crop.left + dx).coerceIn(0.0, (crop.right - minimum).coerceAtLeast(0.0)) else crop.left
    val right = if (handle in setOf(EditorCropHandle.RIGHT, EditorCropHandle.TOP_RIGHT, EditorCropHandle.BOTTOM_RIGHT))
        (crop.right + dx).coerceIn((left + minimum).coerceAtMost(1.0), 1.0) else crop.right
    val top = if (handle in setOf(EditorCropHandle.TOP, EditorCropHandle.TOP_LEFT, EditorCropHandle.TOP_RIGHT))
        (crop.top + dy).coerceIn(0.0, (crop.bottom - minimum).coerceAtLeast(0.0)) else crop.top
    val bottom = if (handle in setOf(EditorCropHandle.BOTTOM, EditorCropHandle.BOTTOM_LEFT, EditorCropHandle.BOTTOM_RIGHT))
        (crop.bottom + dy).coerceIn((top + minimum).coerceAtMost(1.0), 1.0) else crop.bottom
    return NormalizedCrop(left, top, right, bottom)
}
