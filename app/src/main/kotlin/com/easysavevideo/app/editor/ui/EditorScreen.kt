package com.easysavevideo.app.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import com.easysavevideo.app.save.InlineLibraryMediaPreview
import java.io.FileInputStream
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.easysavevideo.app.theme.*
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.edit.EditCompression
import com.easysavevideo.library.edit.EditGeometry
import com.easysavevideo.library.edit.EditLimits
import com.easysavevideo.library.edit.NormalizedCrop
import java.util.Locale

/**
 * Ordinary-library M6-A presentation only. Callbacks never open paths or make entitlement decisions.
 * The caller owns the immutable draft and must synchronously fence admission before native work.
 * Back, crop cancellation and safe stop are deliberately independent of feature entitlements.
 */
@Composable
fun EditorScreen(
    state: EditorViewState,
    onDraftChange: (EditorDraft) -> Unit,
    onSubmit: (EditorDraft) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    openPreviewMedia: (MediaReadRef) -> FileInputStream,
    onPreviewReleaseUnconfirmed: () -> Unit,
) {
    val v = LocalEsvVisuals.current
    var requestedPreview by remember { mutableStateOf<MediaReadRef?>(null) }
    var previewGeneration by remember { mutableStateOf(0L) }
    fun requestPreview(reference: MediaReadRef) {
        if (editorActivePreview(state, reference) != null) { requestedPreview = reference; previewGeneration++ }
    }
    fun changeDraft(value: EditorDraft) { requestedPreview = null; onDraftChange(value) }
    val cancelEdit: () -> Unit = { requestedPreview = null; onCancel() }
    val leaveEditor: () -> Unit = { requestedPreview = null; onBack() }
    LaunchedEffect(state.foreground, state.phase, state.draft.selectedSegment?.reference, state.displayGeometries, state.completedReference) {
        if (editorActivePreview(state, requestedPreview) == null) requestedPreview = null
    }
    val draft = state.draft
    val guard = remember { EditorSubmitGuard() }
    var pendingSubmissionRevision by remember { mutableStateOf<Long?>(null) }
    val pendingSubmit = state.submissionPending || pendingSubmissionRevision == state.submissionRevision
    val editable = state.editable && !pendingSubmit
    var cropSegmentId by remember { mutableStateOf<Long?>(null) }
    val error = editorDraftError(draft, state.displayGeometries)
    val publishedReference = editorPublishedReference(state)
    val status = stringResource(editorPhaseResource(state.phase, state.completedReference != null))
    BackHandler(onBack = leaveEditor)
    val headerItems: LazyListScope.() -> Unit = {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(stringResource(R.string.advanced_copy_1), style = v.titleTextStyle)
                EditorResponsivePair(first = { modifier ->
                    EsvButton(stringResource(R.string.advanced_copy_2), leaveEditor, modifier = modifier)
                }, second = { modifier ->
                    EsvButton(stringResource(R.string.advanced_copy_3), cancelEdit, modifier = modifier)
                })
                EsvText(stringResource(R.string.final_copy_0), style = v.captionTextStyle, color = v.colors.muted)
                EsvText(stringResource(R.string.final_copy_1), style = v.captionTextStyle, color = v.colors.muted)
                EsvText(stringResource(R.string.final_copy_2), style = v.captionTextStyle, color = v.colors.muted)
                EsvText(stringResource(R.string.final_copy_3), style = v.captionTextStyle, color = v.colors.muted)
            }
        }
        item(key = "status") {
            EditorCard {
                EsvText(status, modifier = Modifier.semantics { stateDescription = status },
                    color = if (state.phase == EditorPhase.FAILED || state.phase == EditorPhase.BLOCKED) v.colors.error else v.colors.text)
                if (!state.foreground) EsvText(stringResource(R.string.advanced_copy_4), style = v.captionTextStyle, color = v.colors.muted)
                state.notice?.let { EsvDiagnosticNotice(it) }
                editorRestorationResource(state.draftRestoration)?.let {
                    EsvText(stringResource(it), style = v.captionTextStyle, color = v.colors.muted)
                }
                if (publishedReference != null) {
                    EsvButton(stringResource(R.string.final_copy_4), { requestPreview(publishedReference) },
                        enabled = state.foreground && !state.busy, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    val sequenceItems: LazyListScope.() -> Unit = {
        item(key = "sequence_header") {
            EsvText("片段顺序（${draft.segments.size} / ${EditLimits.MAX_SEGMENTS}）", style = v.sectionTextStyle)
        }
        if (draft.segments.isEmpty()) item(key = "empty") {
            EsvText(stringResource(R.string.final_copy_5), color = v.colors.muted)
        }
        items(draft.segments, key = { "segment:${it.id}" }) { segment ->
            val index = draft.segments.indexOfFirst { it.id == segment.id }
            val selected = draft.selectedSegmentId == segment.id
            EditorCard(selected = selected) {
                EsvText("${index + 1}. ${segment.item.displayName}", style = v.sectionTextStyle, maxLines = 2)
                EsvText("${segment.startSeconds} → ${segment.endSeconds.ifEmpty { stringResource(R.string.final_pending_entry) }} 秒 · ${cropSummary(segment.crop)}",
                    style = v.captionTextStyle, color = v.colors.muted)
                EditorResponsivePair(first = { modifier ->
                    EsvButton(if (selected) stringResource(R.string.final_copy_6) else stringResource(R.string.final_copy_7), {
                        changeDraft(draft.copy(selectedSegmentId = segment.id))
                    }, enabled = editable, modifier = modifier)
                }, second = { modifier ->
                    EsvButton(stringResource(R.string.final_copy_8), { changeDraft(removeEditorSegment(draft, segment.id)) },
                        enabled = editable, modifier = modifier)
                })
                EditorResponsivePair(first = { modifier ->
                    EsvButton(stringResource(R.string.final_copy_9), { changeDraft(moveEditorSegment(draft, segment.id, -1)) },
                        enabled = editable && index > 0, modifier = modifier)
                }, second = { modifier ->
                    EsvButton(stringResource(R.string.final_copy_10), { changeDraft(moveEditorSegment(draft, segment.id, 1)) },
                        enabled = editable && index < draft.segments.lastIndex, modifier = modifier)
                })
            }
        }
        item(key = "sources_header") {
            Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(stringResource(R.string.advanced_copy_8), style = v.sectionTextStyle)
                EsvText(stringResource(R.string.final_copy_34), style = v.captionTextStyle, color = v.colors.muted)
                if (state.availableItems.isEmpty()) EsvText(stringResource(R.string.advanced_copy_6), color = v.colors.muted)
                if (draft.segments.size >= EditLimits.MAX_SEGMENTS) EsvText(stringResource(R.string.final_copy_35), color = v.colors.muted)
            }
        }
        items(state.availableItems, key = { "source:${it.itemId}:${it.revision}:${it.representationGeneration}" }) { item ->
            val sourceError = editorSourceError(item)
            EditorCard {
                EsvText(item.displayName, maxLines = 2)
                sourceError?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.muted) }
                EsvButton(stringResource(R.string.final_copy_36), { changeDraft(addEditorSegment(draft, item)) },
                    enabled = editable && sourceError == null && draft.segments.size < EditLimits.MAX_SEGMENTS,
                    modifier = Modifier.fillMaxWidth())
            }
        }

    }
    val inspectorItems: LazyListScope.() -> Unit = {
        draft.selectedSegment?.let { segment ->
            item(key = "segment_controls:${segment.id}") {
                val geometry = state.displayGeometries[segment.reference]
                EditorCard {
                    EsvText("编辑片段 ${draft.segments.indexOf(segment) + 1}", style = v.sectionTextStyle)
                    EsvText(segment.item.displayName, maxLines = 2)
                    EsvButton(stringResource(R.string.final_copy_11), { requestPreview(segment.reference) },
                        enabled = state.foreground && !state.busy && !pendingSubmit && geometry != null, modifier = Modifier.fillMaxWidth())
                    EsvText(stringResource(R.string.final_copy_12), style = v.captionTextStyle, color = v.colors.muted)
                    EditorResponsivePair(first = { modifier ->
                        EditorTimeField(segment.startSeconds, stringResource(R.string.final_copy_13), editable, modifier) { value ->
                            changeDraft(updateEditorSegment(draft, segment.id) { it.copy(startSeconds = value) })
                        }
                    }, second = { modifier ->
                        EditorTimeField(segment.endSeconds, stringResource(R.string.final_copy_14), editable, modifier) { value ->
                            changeDraft(updateEditorSegment(draft, segment.id) { it.copy(endSeconds = value) })
                        }
                    })
                    val verifiedDuration = geometry?.durationUs
                    val duration = verifiedDuration ?: segment.item.durationMillis?.let { it * 1_000 }
                    EsvText(if (duration == null) stringResource(R.string.final_copy_15) else "${if (verifiedDuration == null) stringResource(R.string.final_copy_16) else stringResource(R.string.final_copy_17)}时长：${editorSeconds(duration)} 秒",
                        style = v.captionTextStyle, color = v.colors.muted)
                    editorSegmentError(segment, geometry)?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.error) }
                    EsvButton(stringResource(R.string.final_copy_18), {
                        if (duration != null) changeDraft(updateEditorSegment(draft, segment.id) {
                            it.copy(startSeconds = "0", endSeconds = editorSeconds(duration))
                        })
                    }, enabled = editable && duration != null, modifier = Modifier.fillMaxWidth())
                    if (geometry == null) {
                        EsvText(stringResource(R.string.final_copy_19), style = v.captionTextStyle, color = v.colors.muted)
                    } else {
                        EsvText("显示方向：${geometry.width} × ${geometry.height} · ${cropSummary(segment.crop)}", style = v.captionTextStyle)
                        CropSchematic(segment.crop, geometry, enabled = false, onChange = {}, modifier = Modifier.fillMaxWidth().height(220.dp))
                        EsvText(stringResource(R.string.final_copy_20), style = v.captionTextStyle, color = v.colors.muted)
                    }
                    EsvButton(stringResource(R.string.final_copy_21), { requestedPreview = null; cropSegmentId = segment.id },
                        enabled = editable && geometry != null, modifier = Modifier.fillMaxWidth())
                    EsvButton(stringResource(R.string.final_copy_22), {
                        changeDraft(updateEditorSegment(draft, segment.id) { it.copy(crop = NormalizedCrop.FULL) })
                    }, enabled = editable && segment.crop != NormalizedCrop.FULL, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (state.executionRanges.isNotEmpty()) item(key = "execution_boundaries") {
            EditorCard {
                EsvText(stringResource(R.string.final_copy_23), style = v.sectionTextStyle)
                state.executionRanges.forEachIndexed { index, range ->
                    EsvText("片段 ${index + 1}：请求 ${editorSeconds(range.requestedStartUs)}–${editorSeconds(range.requestedEndUs)} 秒", style = v.captionTextStyle)
                    EsvText("向内对齐后执行 ${editorSeconds(range.executionStartUs)}–${editorSeconds(range.executionEndUs)} 秒 · ${range.frameCount} 帧", style = v.captionTextStyle)
                }
                EsvText("对齐后总时长 ${editorSeconds(state.executionRanges.sumOf { it.durationUs })} 秒。只有完成验证并入库才表示已完成。", style = v.captionTextStyle, color = v.colors.muted)
            }
        }
        item(key = "compression") {
            EditorCard {
                EsvText(stringResource(R.string.advanced_copy_7), style = v.sectionTextStyle)
                EsvButton((if (draft.compression == EditCompression.ORIGINAL_QUALITY) stringResource(R.string.final_copy_24) else "") + stringResource(R.string.final_copy_25), {
                    changeDraft(draft.copy(compression = EditCompression.ORIGINAL_QUALITY))
                }, enabled = editable, modifier = Modifier.fillMaxWidth())
                EsvText(stringResource(R.string.final_copy_27), style = v.captionTextStyle, color = v.colors.muted)
                EsvButton((if (draft.compression == EditCompression.SAVE_SPACE) stringResource(R.string.final_copy_24) else "") + stringResource(R.string.final_copy_26), {
                    changeDraft(draft.copy(compression = EditCompression.SAVE_SPACE))
                }, enabled = editable, modifier = Modifier.fillMaxWidth())
                EsvText(stringResource(R.string.final_copy_28), style = v.captionTextStyle, color = v.colors.muted)
                EsvText(stringResource(R.string.final_copy_29), style = v.captionTextStyle, color = v.colors.muted)
                EsvText(stringResource(R.string.final_copy_30), style = v.captionTextStyle, color = v.colors.muted)
            }
        }
        item(key = "submit") {
            EditorCard {
                error?.let { EsvText(it, color = v.colors.error, style = v.captionTextStyle) }
                EsvButton(if (pendingSubmit || state.busy) stringResource(R.string.final_copy_31) else stringResource(R.string.final_copy_32), {
                    if (guard.trySubmit(state.submissionRevision, editable && error == null)) {
                        pendingSubmissionRevision = state.submissionRevision
                        requestedPreview = null
                        onSubmit(draft)
                    }
                }, enabled = editable && error == null, modifier = Modifier.fillMaxWidth())
                EsvButton(stringResource(R.string.advanced_copy_3), cancelEdit, modifier = Modifier.fillMaxWidth())
                EsvText(stringResource(R.string.final_copy_33), style = v.captionTextStyle, color = v.colors.muted)
            }
        }
    }
    val compactList = rememberLazyListState()
    val sequenceList = rememberLazyListState()
    val inspectorList = rememberLazyListState()
    val previewScroll = rememberScrollState()
    val previewContent: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.fillMaxWidth().verticalScroll(previewScroll).padding(v.spacingMedium),
            verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            EsvText(stringResource(R.string.editor_inline_heading), style = v.sectionTextStyle)
            EsvText(stringResource(R.string.editor_inline_readonly), style = v.captionTextStyle, color = v.colors.muted)
            val active = editorActivePreview(state, requestedPreview)
            if (active == null) EsvText(stringResource(if (!state.foreground) R.string.editor_inline_background
                else if (state.busy || state.phase == EditorPhase.BLOCKED) R.string.editor_inline_busy
                else R.string.editor_inline_empty), style = v.captionTextStyle)
            else key(active, previewGeneration) {
                val generation = previewGeneration
                DisposableEffect(active, generation) {
                    onDispose { if (previewGeneration == generation) requestedPreview = null }
                }
                InlineLibraryMediaPreview(active, state.foreground, openPreviewMedia, onPreviewReleaseUnconfirmed)
            }
            if (requestedPreview != null) EsvButton(stringResource(R.string.editor_inline_close), onClick = { requestedPreview = null })
        }
    }
    val latestPreviewContent = rememberUpdatedState(previewContent)
    val previewPanel = remember { movableContentOf<Modifier> { modifier -> latestPreviewContent.value(modifier) } }
    EsvBackdrop(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(v.spacingMedium)) {
            val layout = editorPanelLayout(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale, v.spacingMedium.value)
            val availableHeight = (maxHeight - v.spacingMedium * 2).coerceAtLeast(0.dp)
            if (layout.expanded) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = availableHeight * 0.35f),
                    verticalArrangement = Arrangement.spacedBy(v.spacingMedium), content = headerItems)
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                    previewPanel(Modifier.width(layout.previewWidthDp.dp).fillMaxHeight())
                    LazyColumn(Modifier.width(layout.sequenceWidthDp.dp).fillMaxHeight(), state = sequenceList,
                        verticalArrangement = Arrangement.spacedBy(v.spacingMedium), content = sequenceItems)
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = inspectorList,
                        verticalArrangement = Arrangement.spacedBy(v.spacingMedium), content = inspectorItems)
                }
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = availableHeight * 0.25f),
                    verticalArrangement = Arrangement.spacedBy(v.spacingMedium), content = headerItems)
                previewPanel(Modifier.heightIn(max = (availableHeight * 0.45f).coerceAtMost(560.dp)))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = compactList,
                    verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                    sequenceItems()
                    inspectorItems()
                }
            }
        }
    }
    val cropSegment = draft.segments.firstOrNull { it.id == cropSegmentId }
    if (cropSegment != null) {
        EditorCropDialog(cropSegment, state.displayGeometries[cropSegment.reference], editable,
            onConfirm = { crop ->
                if (editable) changeDraft(updateEditorSegment(draft, cropSegment.id) { it.copy(crop = crop) })
                cropSegmentId = null
            }, onCancel = { cropSegmentId = null })
    }
}

@Composable
private fun EditorCard(selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.cardCorner))
        .border(v.borderWidth, if (selected) v.colors.accent else v.colors.border, RoundedCornerShape(v.cardCorner))
        .padding(v.spacingMedium), verticalArrangement = Arrangement.spacedBy(v.spacingSmall), content = content)
}

@Composable
private fun EditorTimeField(value: String, label: String, enabled: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    val v = LocalEsvVisuals.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(label, style = v.captionTextStyle, color = v.colors.muted)
        BasicTextField(value, onValueChange = { onChange(it.take(16)) }, enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                .border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
                .padding(v.spacingSmall).semantics { contentDescription = label },
            textStyle = v.bodyTextStyle.copy(color = v.colors.text), cursorBrush = SolidColor(v.colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done))
    }
}

@Composable
private fun EditorCropDialog(
    segment: EditorSegment,
    geometry: EditorDisplayGeometry?,
    enabled: Boolean,
    onConfirm: (NormalizedCrop) -> Unit,
    onCancel: () -> Unit,
) {
    val v = LocalEsvVisuals.current
    var crop by remember(segment.id, segment.crop) { mutableStateOf(segment.crop) }
    var handle by remember { mutableStateOf(EditorCropHandle.MOVE) }
    val adjustable = enabled && geometry != null
    val cropError = geometry?.let { editorCropError(crop, it) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(v.spacingMedium), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 800.dp).fillMaxWidth()
                .background(v.colors.elevated, RoundedCornerShape(v.sheetCorner))
                .verticalScroll(rememberScrollState()).padding(v.spacingMedium),
                verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(stringResource(R.string.advanced_copy_9), style = v.titleTextStyle)
                EsvText(stringResource(R.string.final_copy_37), style = v.captionTextStyle, color = v.colors.muted)
                if (geometry != null) {
                    CropSchematic(crop, geometry, adjustable, { crop = it }, Modifier.fillMaxWidth().height(300.dp))
                    EsvText(stringResource(R.string.final_copy_38), style = v.captionTextStyle, color = v.colors.muted)
                } else EsvText(stringResource(R.string.advanced_copy_10), color = v.colors.error)
                EsvText(cropSummary(crop), style = v.captionTextStyle)
                cropError?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.error) }
                EsvText(stringResource(R.string.final_copy_39), style = v.captionTextStyle, color = v.colors.muted)
                EditorCropHandle.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                        row.forEach { target ->
                            EsvButton((if (handle == target) stringResource(R.string.final_copy_40) else "") + cropHandleLabel(target), { handle = target },
                                enabled = adjustable, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton("← 1%", { crop = dragEditorCrop(crop, handle, -0.01, 0.0) }, enabled = adjustable, modifier = Modifier.weight(1f))
                    EsvButton("1% →", { crop = dragEditorCrop(crop, handle, 0.01, 0.0) }, enabled = adjustable, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton("↑ 1%", { crop = dragEditorCrop(crop, handle, 0.0, -0.01) }, enabled = adjustable, modifier = Modifier.weight(1f))
                    EsvButton("1% ↓", { crop = dragEditorCrop(crop, handle, 0.0, 0.01) }, enabled = adjustable, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton(stringResource(R.string.final_copy_41), { crop = NormalizedCrop.FULL }, enabled = adjustable, modifier = Modifier.weight(1f))
                    EsvButton(stringResource(R.string.final_copy_42), { crop = segment.crop }, enabled = adjustable, modifier = Modifier.weight(1f))
                }
                EsvText(stringResource(R.string.final_copy_43), style = v.captionTextStyle, color = v.colors.muted)
                EsvButton(stringResource(R.string.final_copy_44), { onConfirm(crop) }, enabled = adjustable && cropError == null, modifier = Modifier.fillMaxWidth())
                EsvButton(stringResource(R.string.final_copy_45), onCancel, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CropSchematic(
    crop: NormalizedCrop,
    geometry: EditorDisplayGeometry,
    enabled: Boolean,
    onChange: (NormalizedCrop) -> Unit,
    modifier: Modifier,
) {
    val v = LocalEsvVisuals.current
    val currentCrop by rememberUpdatedState(crop)
    val currentChange by rememberUpdatedState(onChange)
    val diagramDescription = stringResource(R.string.final_crop_diagram)
    val cropDescription = cropSummary(crop)
    Canvas(modifier.background(v.colors.bg, RoundedCornerShape(v.corner))
        .semantics { contentDescription = diagramDescription; stateDescription = cropDescription }
        .pointerInput(geometry, enabled) {
            if (!enabled) return@pointerInput
            var target: EditorCropHandle? = null
            var gestureCrop = currentCrop
            detectDragGestures(
                onDragStart = { point ->
                    gestureCrop = currentCrop
                    val content = EditGeometry.viewportContent(size.width.toDouble(), size.height.toDouble(), geometry.width, geometry.height)
                    target = editorCropHit(currentCrop, (point.x - content.left) / content.width,
                        (point.y - content.top) / content.height, 24.dp.toPx() / content.width, 24.dp.toPx() / content.height)
                },
                onDragEnd = { target = null },
                onDragCancel = { target = null },
                onDrag = { change, drag ->
                    target?.let { selected ->
                        change.consume()
                        val content = EditGeometry.viewportContent(size.width.toDouble(), size.height.toDouble(), geometry.width, geometry.height)
                        gestureCrop = dragEditorCrop(gestureCrop, selected, drag.x / content.width, drag.y / content.height)
                        currentChange(gestureCrop)
                    }
                },
            )
        }) {
        val content = EditGeometry.viewportContent(size.width.toDouble(), size.height.toDouble(), geometry.width, geometry.height)
        val frame = EditGeometry.normalizedToViewport(crop, size.width.toDouble(), size.height.toDouble(), geometry.width, geometry.height)
        val contentTopLeft = Offset(content.left.toFloat(), content.top.toFloat())
        val contentSize = Size(content.width.toFloat(), content.height.toFloat())
        drawRect(v.colors.surface, contentTopLeft, contentSize)
        for (step in 1..3) {
            val x = (content.left + content.width * step / 4).toFloat()
            val y = (content.top + content.height * step / 4).toFloat()
            drawLine(v.colors.border, Offset(x, content.top.toFloat()), Offset(x, content.bottom.toFloat()), v.borderWidth.toPx())
            drawLine(v.colors.border, Offset(content.left.toFloat(), y), Offset(content.right.toFloat(), y), v.borderWidth.toPx())
        }
        drawRect(v.colors.border, contentTopLeft, contentSize, style = Stroke(v.borderWidth.toPx()))
        drawRect(v.colors.accent.copy(alpha = 0.18f), Offset(frame.left.toFloat(), frame.top.toFloat()), Size(frame.width.toFloat(), frame.height.toFloat()))
        drawRect(v.colors.accent, Offset(frame.left.toFloat(), frame.top.toFloat()), Size(frame.width.toFloat(), frame.height.toFloat()),
            style = Stroke(v.borderWidth.toPx().coerceAtLeast(2.dp.toPx())))
        val xs = listOf(frame.left, (frame.left + frame.right) / 2, frame.right)
        val ys = listOf(frame.top, (frame.top + frame.bottom) / 2, frame.bottom)
        xs.forEachIndexed { ix, x -> ys.forEachIndexed { iy, y ->
            if (ix != 1 || iy != 1) drawCircle(v.colors.accent, 5.dp.toPx(), Offset(x.toFloat(), y.toFloat()))
        } }
    }
}

@Composable
private fun cropSummary(crop: NormalizedCrop): String = if (crop == NormalizedCrop.FULL) stringResource(R.string.final_crop_full) else
    stringResource(R.string.final_crop_summary, crop.left * 100, crop.top * 100, crop.right * 100, crop.bottom * 100)

@Composable
private fun cropHandleLabel(handle: EditorCropHandle): String = when (handle) {
    EditorCropHandle.MOVE -> stringResource(R.string.final_crop_move)
    EditorCropHandle.LEFT -> stringResource(R.string.final_crop_left)
    EditorCropHandle.TOP -> stringResource(R.string.final_crop_top)
    EditorCropHandle.RIGHT -> stringResource(R.string.final_crop_right)
    EditorCropHandle.BOTTOM -> stringResource(R.string.final_crop_bottom)
    EditorCropHandle.TOP_LEFT -> stringResource(R.string.final_crop_top_left)
    EditorCropHandle.TOP_RIGHT -> stringResource(R.string.final_crop_top_right)
    EditorCropHandle.BOTTOM_LEFT -> stringResource(R.string.final_crop_bottom_left)
    EditorCropHandle.BOTTOM_RIGHT -> stringResource(R.string.final_crop_bottom_right)
}
