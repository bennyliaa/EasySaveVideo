package com.easysavevideo.app.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    onPreview: (MediaReadRef) -> Unit,
) {
    val v = LocalEsvVisuals.current
    val draft = state.draft
    val guard = remember { EditorSubmitGuard() }
    var pendingSubmissionRevision by remember { mutableStateOf<Long?>(null) }
    val pendingSubmit = pendingSubmissionRevision == state.submissionRevision
    val editable = state.editable && !pendingSubmit
    var cropSegmentId by remember { mutableStateOf<Long?>(null) }
    val error = editorDraftError(draft, state.displayGeometries)
    val status = editorPhaseText(state)
    BackHandler(onBack = onBack)
    EsvBackdrop(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 1_100.dp).fillMaxSize(),
                contentPadding = PaddingValues(v.spacingMedium),
                verticalArrangement = Arrangement.spacedBy(v.spacingMedium),
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                        EsvText("视频工具 · M6-A", style = v.titleTextStyle)
                        Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                            EsvButton("返回媒体库", onBack, modifier = Modifier.weight(1f))
                            EsvButton("取消 / 安全停止", onCancel, modifier = Modifier.weight(1f))
                        }
                        EsvText("对已入库的普通视频做时长裁剪、顺序拼接、二次画面裁切与压缩。新文件验证并入库后才算完成，原件始终保留。", style = v.captionTextStyle, color = v.colors.muted)
                        EsvText("时间裁剪向内对齐实际来源帧：入点和出点各可能缩短最多一个帧间隔。会记录实际执行边界，不承诺任意微秒边界精确。", style = v.captionTextStyle, color = v.colors.muted)
                        EsvText("当前上限：8 个片段、单来源及成片 30 分钟、请求片段至少 0.25 秒，帧对齐后至少 3 帧。支持范围与输出参数会在开始前再次验证。", style = v.captionTextStyle, color = v.colors.muted)
                        EsvText("M6-B 录制页提供比例示意选区与私有片段编排，尚无实际画面截图框选。这里的二次裁切不改变此前系统捕获范围，也不是区域独占录屏。Vault 未启用。", style = v.captionTextStyle, color = v.colors.muted)
                    }
                }
                item(key = "status") {
                    EditorCard {
                        EsvText(status, modifier = Modifier.semantics { stateDescription = status },
                            color = if (state.phase == EditorPhase.FAILED || state.phase == EditorPhase.BLOCKED) v.colors.error else v.colors.text)
                        if (!state.foreground) EsvText("应用不在前台，编辑和提交暂不可用。取消与返回仍可使用。", style = v.captionTextStyle, color = v.colors.muted)
                        state.notice?.let { EsvText(it, style = v.captionTextStyle) }
                        if (state.phase == EditorPhase.SUCCEEDED && state.completedReference != null) {
                            EsvButton("预览已入库的新视频", { onPreview(state.completedReference) },
                                enabled = state.foreground && !state.busy, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                item(key = "sequence_header") {
                    EsvText("片段顺序（${draft.segments.size} / ${EditLimits.MAX_SEGMENTS}）", style = v.titleTextStyle)
                }
                if (draft.segments.isEmpty()) item(key = "empty") {
                    EsvText("从下方添加视频。同一来源可以重复添加，每个片段分别设置入点、出点与画面裁切。", color = v.colors.muted)
                }
                items(draft.segments, key = { "segment:${it.id}" }) { segment ->
                    val index = draft.segments.indexOfFirst { it.id == segment.id }
                    val selected = draft.selectedSegmentId == segment.id
                    EditorCard(selected = selected) {
                        EsvText("${index + 1}. ${segment.item.displayName}", style = v.titleTextStyle, maxLines = 2)
                        EsvText("${segment.startSeconds} → ${segment.endSeconds.ifEmpty { "待填写" }} 秒 · ${cropSummary(segment.crop)}",
                            style = v.captionTextStyle, color = v.colors.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                            EsvButton(if (selected) "正在编辑" else "编辑此片段", {
                                onDraftChange(draft.copy(selectedSegmentId = segment.id))
                            }, enabled = editable, modifier = Modifier.weight(1f))
                            EsvButton("移除此片段", { onDraftChange(removeEditorSegment(draft, segment.id)) },
                                enabled = editable, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                            EsvButton("上移", { onDraftChange(moveEditorSegment(draft, segment.id, -1)) },
                                enabled = editable && index > 0, modifier = Modifier.weight(1f))
                            EsvButton("下移", { onDraftChange(moveEditorSegment(draft, segment.id, 1)) },
                                enabled = editable && index < draft.segments.lastIndex, modifier = Modifier.weight(1f))
                        }
                    }
                }
                draft.selectedSegment?.let { segment ->
                    item(key = "segment_controls:${segment.id}") {
                        val geometry = state.displayGeometries[segment.reference]
                        EditorCard {
                            EsvText("编辑片段 ${draft.segments.indexOf(segment) + 1}", style = v.titleTextStyle)
                            EsvText(segment.item.displayName, maxLines = 2)
                            EsvButton("打开原件只读预览", { onPreview(segment.reference) },
                                enabled = state.foreground && !state.busy && !pendingSubmit, modifier = Modifier.fillMaxWidth())
                            EsvText("原件预览用于确认内容；这里没有处理后实时预览。成片预览只在验证入库成功后提供。", style = v.captionTextStyle, color = v.colors.muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                EditorTimeField(segment.startSeconds, "入点（秒）", editable, Modifier.weight(1f)) { value ->
                                    onDraftChange(updateEditorSegment(draft, segment.id) { it.copy(startSeconds = value) })
                                }
                                EditorTimeField(segment.endSeconds, "出点（秒，不含此点）", editable, Modifier.weight(1f)) { value ->
                                    onDraftChange(updateEditorSegment(draft, segment.id) { it.copy(endSeconds = value) })
                                }
                            }
                            val verifiedDuration = geometry?.durationUs
                            val duration = verifiedDuration ?: segment.item.durationMillis?.let { it * 1_000 }
                            EsvText(if (duration == null) "来源时长待验证，请填写出点。" else "${if (verifiedDuration == null) "媒体库记录" else "已验证"}时长：${editorSeconds(duration)} 秒",
                                style = v.captionTextStyle, color = v.colors.muted)
                            editorSegmentError(segment, geometry)?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.error) }
                            EsvButton("恢复此片段完整时长", {
                                if (duration != null) onDraftChange(updateEditorSegment(draft, segment.id) {
                                    it.copy(startSeconds = "0", endSeconds = editorSeconds(duration))
                                })
                            }, enabled = editable && duration != null, modifier = Modifier.fillMaxWidth())
                            if (geometry == null) {
                                EsvText("显示尺寸与方向尚未验证；画面裁切暂不可用，不根据媒体库宽高猜测旋转方向。完整画面仍可提交验证。", style = v.captionTextStyle, color = v.colors.muted)
                            } else {
                                EsvText("显示方向：${geometry.width} × ${geometry.height} · ${cropSummary(segment.crop)}", style = v.captionTextStyle)
                                CropSchematic(segment.crop, geometry, enabled = false, onChange = {}, modifier = Modifier.fillMaxWidth().height(220.dp))
                                EsvText("上图是按已验证显示方向绘制的裁切示意图，不是视频画面。", style = v.captionTextStyle, color = v.colors.muted)
                            }
                            EsvButton("调整画面裁切…", { cropSegmentId = segment.id },
                                enabled = editable && geometry != null, modifier = Modifier.fillMaxWidth())
                            EsvButton("此片段恢复完整画面", {
                                onDraftChange(updateEditorSegment(draft, segment.id) { it.copy(crop = NormalizedCrop.FULL) })
                            }, enabled = editable && segment.crop != NormalizedCrop.FULL, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (state.executionRanges.isNotEmpty()) item(key = "execution_boundaries") {
                    EditorCard {
                        EsvText("请求与执行边界", style = v.titleTextStyle)
                        state.executionRanges.forEachIndexed { index, range ->
                            EsvText("片段 ${index + 1}：请求 ${editorSeconds(range.requestedStartUs)}–${editorSeconds(range.requestedEndUs)} 秒", style = v.captionTextStyle)
                            EsvText("向内对齐后执行 ${editorSeconds(range.executionStartUs)}–${editorSeconds(range.executionEndUs)} 秒 · ${range.frameCount} 帧", style = v.captionTextStyle)
                        }
                        EsvText("对齐后总时长 ${editorSeconds(state.executionRanges.sumOf { it.durationUs })} 秒。只有完成验证并入库才表示已完成。", style = v.captionTextStyle, color = v.colors.muted)
                    }
                }
                item(key = "compression") {
                    EditorCard {
                        EsvText("输出质量", style = v.titleTextStyle)
                        EsvButton((if (draft.compression == EditCompression.ORIGINAL_QUALITY) "已选 · " else "") + "原质量", {
                            onDraftChange(draft.copy(compression = EditCompression.ORIGINAL_QUALITY))
                        }, enabled = editable, modifier = Modifier.fillMaxWidth())
                        EsvText("保留可支持的裁切分辨率；当前处理仍会重新编码，不能保证无损或与原件完全一致。", style = v.captionTextStyle, color = v.colors.muted)
                        EsvButton((if (draft.compression == EditCompression.SAVE_SPACE) "已选 · " else "") + "节省空间", {
                            onDraftChange(draft.copy(compression = EditCompression.SAVE_SPACE))
                        }, enabled = editable, modifier = Modifier.fillMaxWidth())
                        EsvText("使用较低的输出分辨率与码率策略。实际大小取决于内容、来源与设备，不保证比原件小，不提供未经测量的大小估算。", style = v.captionTextStyle, color = v.colors.muted)
                        EsvText("单来源与输出最多 2 GiB，不同来源合计最多 4 GiB；最长边 1920 像素，总像素不超过 1920 × 1080，最高 60 fps。节省空间最长边 1280 像素。", style = v.captionTextStyle, color = v.colors.muted)
                        EsvText("M6-A 仅接受验证通过的 H.264/8-bit SDR/固定帧率视频。多段须兼容；静音或一致的 AAC-LC 音轨配置会被检查。不兼容时明确停止。", style = v.captionTextStyle, color = v.colors.muted)
                    }
                }
                item(key = "submit") {
                    EditorCard {
                        error?.let { EsvText(it, color = v.colors.error, style = v.captionTextStyle) }
                        EsvButton(if (pendingSubmit || state.busy) "正在处理，请等待状态更新" else "创建新视频", {
                            if (guard.trySubmit(state.submissionRevision, editable && error == null)) {
                                pendingSubmissionRevision = state.submissionRevision
                                onSubmit(draft)
                            }
                        }, enabled = editable && error == null, modifier = Modifier.fillMaxWidth())
                        EsvButton("取消 / 安全停止", onCancel, modifier = Modifier.fillMaxWidth())
                        EsvText("提交会重新核对所选项目的准确版本，不会静默换用更新后的原件。离开页面或重复点击不代表已经取消或完成。", style = v.captionTextStyle, color = v.colors.muted)
                    }
                }
                item(key = "sources_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                        EsvText("添加媒体库视频", style = v.titleTextStyle)
                        EsvText("以下为打开编辑器时可用的媒体库列表快照。要选择其他文件夹或页面，请返回媒体库。", style = v.captionTextStyle, color = v.colors.muted)
                        if (state.availableItems.isEmpty()) EsvText("当前没有可添加的视频。", color = v.colors.muted)
                        if (draft.segments.size >= EditLimits.MAX_SEGMENTS) EsvText("已达到 8 个片段上限；先移除片段再添加。", color = v.colors.muted)
                    }
                }
                items(state.availableItems, key = { "source:${it.itemId}:${it.revision}:${it.representationGeneration}" }) { item ->
                    val sourceError = editorSourceError(item)
                    EditorCard {
                        EsvText(item.displayName, maxLines = 2)
                        sourceError?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.muted) }
                        EsvButton("添加为末尾片段", { onDraftChange(addEditorSegment(draft, item)) },
                            enabled = editable && sourceError == null && draft.segments.size < EditLimits.MAX_SEGMENTS,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    val cropSegment = draft.segments.firstOrNull { it.id == cropSegmentId }
    if (cropSegment != null) {
        EditorCropDialog(cropSegment, state.displayGeometries[cropSegment.reference], editable,
            onConfirm = { crop ->
                if (editable) onDraftChange(updateEditorSegment(draft, cropSegment.id) { it.copy(crop = crop) })
                cropSegmentId = null
            }, onCancel = { cropSegmentId = null })
    }
}

@Composable
private fun EditorCard(selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner))
        .border(v.borderWidth, if (selected) v.colors.accent else v.colors.border, RoundedCornerShape(v.corner))
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
                .background(v.colors.surface, RoundedCornerShape(v.corner))
                .verticalScroll(rememberScrollState()).padding(v.spacingMedium),
                verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText("画面裁切", style = v.titleTextStyle)
                EsvText("按显示方向调整，上下左右均相对于正常观看的画面。外侧留白不属于视频。", style = v.captionTextStyle, color = v.colors.muted)
                if (geometry != null) {
                    CropSchematic(crop, geometry, adjustable, { crop = it }, Modifier.fillMaxWidth().height(300.dp))
                    EsvText("示意图，不是视频画面。拖动框内移动；拖动四角或四边中点缩放。", style = v.captionTextStyle, color = v.colors.muted)
                } else EsvText("来源显示方向尚未验证，暂不能确认裁切。", color = v.colors.error)
                EsvText(cropSummary(crop), style = v.captionTextStyle)
                cropError?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.error) }
                EsvText("也可选择一个控制点，再用下方方向按钮微调：", style = v.captionTextStyle, color = v.colors.muted)
                EditorCropHandle.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                        row.forEach { target ->
                            EsvButton((if (handle == target) "已选 " else "") + cropHandleLabel(target), { handle = target },
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
                    EsvButton("恢复完整画面", { crop = NormalizedCrop.FULL }, enabled = adjustable, modifier = Modifier.weight(1f))
                    EsvButton("重置本次调整", { crop = segment.crop }, enabled = adjustable, modifier = Modifier.weight(1f))
                }
                EsvText("确认只更新此片段参数。输出会向内对齐偶数像素，实际边缘可能略有变化；原件不变。", style = v.captionTextStyle, color = v.colors.muted)
                EsvButton("确认裁切", { onConfirm(crop) }, enabled = adjustable && cropError == null, modifier = Modifier.fillMaxWidth())
                EsvButton("取消，不保留本次调整", onCancel, modifier = Modifier.fillMaxWidth())
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
    Canvas(modifier.background(v.colors.bg, RoundedCornerShape(v.corner))
        .semantics { contentDescription = "按显示方向绘制的画面裁切示意图"; stateDescription = cropSummary(crop) }
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

private fun cropSummary(crop: NormalizedCrop): String = if (crop == NormalizedCrop.FULL) "完整画面" else
    String.format(Locale.ROOT, "保留范围：左 %.1f%% · 上 %.1f%% · 右 %.1f%% · 下 %.1f%%",
        crop.left * 100, crop.top * 100, crop.right * 100, crop.bottom * 100)

private fun cropHandleLabel(handle: EditorCropHandle): String = when (handle) {
    EditorCropHandle.MOVE -> "整体移动"
    EditorCropHandle.LEFT -> "左边"
    EditorCropHandle.TOP -> "上边"
    EditorCropHandle.RIGHT -> "右边"
    EditorCropHandle.BOTTOM -> "下边"
    EditorCropHandle.TOP_LEFT -> "左上角"
    EditorCropHandle.TOP_RIGHT -> "右上角"
    EditorCropHandle.BOTTOM_LEFT -> "左下角"
    EditorCropHandle.BOTTOM_RIGHT -> "右下角"
}
