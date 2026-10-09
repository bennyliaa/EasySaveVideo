package com.easysavevideo.app.recording.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.easysavevideo.app.theme.*
import com.easysavevideo.recording.*

/** No screen content is read here. Confirmation only returns an immutable geometry-bound recipe. */
@Composable
fun RecordingRegionDialog(
    openedSetup: RecordingRegionSetup,
    currentSetup: RecordingRegionSetup?,
    initialBounds: RegionNormalizedRect = RegionNormalizedRect.FULL,
    enabled: Boolean,
    onConfirm: (RegionCaptureRecipe) -> Unit,
    onCancel: () -> Unit,
) {
    val v = LocalEsvVisuals.current
    var bounds by remember(openedSetup.geometry, openedSetup.scopeEvidence, initialBounds) { mutableStateOf(initialBounds) }
    var handle by remember { mutableStateOf(RecordingRegionHandle.MOVE) }
    val geometry = openedSetup.geometry
    val unchanged = geometry != null && currentSetup != null && currentSetup.geometry == geometry && currentSetup.scopeEvidence == openedSetup.scopeEvidence
    val adjustable = enabled && unchanged
    val error = if (!unchanged) "显示尺寸、方向、边距或捕获方式已改变。请取消后重新选择；不会沿用旧选区。"
        else recordingRegionError(bounds, openedSetup)
    val recipe = if (error == null && geometry != null && openedSetup.scopeEvidence != null)
        RegionCaptureRecipe(bounds, geometry, openedSetup.scopeEvidence) else null
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(v.spacingMedium), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner))
                .verticalScroll(rememberScrollState()).padding(v.spacingMedium), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText("比例示意模式：选择录制范围", style = v.titleTextStyle)
                EsvText("这是按当前默认显示屏尺寸绘制的比例示意图，不是实时预览。屏幕边框包含系统栏坐标；外侧留白不属于屏幕。", style = v.captionTextStyle, color = v.colors.muted)
                geometry?.let {
                    RecordingRegionSchematic(bounds, it, recipe, adjustable, { next -> bounds = next }, Modifier.fillMaxWidth().height(300.dp))
                    EsvText("完整显示屏 ${it.width} × ${it.height} · 方向 ${it.rotationDegrees}° · 边距 左${it.insets.left} 上${it.insets.top} 右${it.insets.right} 下${it.insets.bottom}", style = v.captionTextStyle)
                }
                EsvText("拖动框内移动，拖动四边或四角缩放。也可选择控制点后用方向按钮微调。", style = v.captionTextStyle, color = v.colors.muted)
                EsvText(recordingRegionSummary(bounds), style = v.captionTextStyle)
                recipe?.let { EsvText(recordingEffectiveCropSummary(it), style = v.captionTextStyle) }
                error?.let { EsvText(it, color = v.colors.error, style = v.captionTextStyle) }
                RecordingRegionHandle.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                        row.forEach { target ->
                            EsvButton((if (target == handle) "已选 " else "") + recordingRegionHandleLabel(target), { handle = target },
                                enabled = adjustable, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton("← 1%", { bounds = dragRecordingRegion(bounds, handle, -0.01, 0.0) }, enabled = adjustable, modifier = Modifier.weight(1f))
                    EsvButton("1% →", { bounds = dragRecordingRegion(bounds, handle, 0.01, 0.0) }, enabled = adjustable, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton("↑ 1%", { bounds = dragRecordingRegion(bounds, handle, 0.0, -0.01) }, enabled = adjustable, modifier = Modifier.weight(1f))
                    EsvButton("1% ↓", { bounds = dragRecordingRegion(bounds, handle, 0.0, 0.01) }, enabled = adjustable, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton("选择全屏", { bounds = RegionNormalizedRect.FULL }, enabled = adjustable, modifier = Modifier.weight(1f))
                    EsvButton("重置本次调整", { bounds = initialBounds }, enabled = adjustable, modifier = Modifier.weight(1f))
                }
                EsvText("本应用请求捕获整个默认显示屏；请核对系统共享范围，部分设备可能覆盖该请求。完整捕获内容会短暂经过内存 / GPU；编码与私有片段只保留上述选区，不会先保存整屏文件或失败后改存整屏。", style = v.captionTextStyle)
                EsvText("选区只定义比例，不绑定应用、网页或先前屏幕；将对你稍后在系统中授权的完整画面按这些坐标裁切，请核对系统授权范围。", style = v.captionTextStyle)
                EsvText("这里选择的是视频输出范围。内部声音不受矩形限制，可能包含其他允许捕获的应用；不会改用麦克风。", style = v.captionTextStyle, color = v.colors.muted)
                EsvText("确认只保存本次选择，稍后仍需你明确选择声音并在系统页面授权。尺寸、方向或边距变化时安全停止，下一次需要重新选区。", style = v.captionTextStyle, color = v.colors.muted)
                EsvButton("确认输出选区", { if (adjustable && recipe != null) onConfirm(recipe.copy(selectedAtMonotonicUs = System.nanoTime().coerceAtLeast(0) / 1_000)) },
                    enabled = adjustable && recipe != null, modifier = Modifier.fillMaxWidth())
                EsvButton("取消，不保留本次调整", onCancel, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RecordingRegionSchematic(
    bounds: RegionNormalizedRect,
    geometry: CaptureDisplayGeometry,
    recipe: RegionCaptureRecipe?,
    enabled: Boolean,
    onChange: (RegionNormalizedRect) -> Unit,
    modifier: Modifier,
) {
    val v = LocalEsvVisuals.current
    val currentBounds by rememberUpdatedState(bounds)
    val currentChange by rememberUpdatedState(onChange)
    Canvas(modifier.background(v.colors.bg, RoundedCornerShape(v.corner))
        .semantics { contentDescription = "包含系统栏的完整默认显示屏比例示意图，不绑定应用，不是实时预览"; stateDescription = recordingRegionSummary(bounds) }
        .pointerInput(geometry, enabled) {
            if (!enabled) return@pointerInput
            var target: RecordingRegionHandle? = null
            var gestureBounds = currentBounds
            detectDragGestures(
                onDragStart = { point ->
                    gestureBounds = currentBounds
                    val display = recordingViewport(size.width.toDouble(), size.height.toDouble(), geometry)
                    target = recordingRegionHit(currentBounds, (point.x - display.left) / display.width,
                        (point.y - display.top) / display.height, 24.dp.toPx() / display.width, 24.dp.toPx() / display.height)
                },
                onDragEnd = { target = null },
                onDragCancel = { target = null },
                onDrag = { change, drag ->
                    target?.let { selected ->
                        change.consume()
                        val display = recordingViewport(size.width.toDouble(), size.height.toDouble(), geometry)
                        gestureBounds = dragRecordingRegion(gestureBounds, selected, drag.x / display.width, drag.y / display.height)
                        currentChange(gestureBounds)
                    }
                },
            )
        }) {
        val display = recordingViewport(size.width.toDouble(), size.height.toDouble(), geometry)
        fun point(x: Double, y: Double) = Offset((display.left + x * display.width).toFloat(), (display.top + y * display.height).toFloat())
        fun rect(left: Double, top: Double, right: Double, bottom: Double, color: androidx.compose.ui.graphics.Color, outline: Boolean = false) {
            val origin = point(left, top)
            val extent = Size(((right - left) * display.width).toFloat(), ((bottom - top) * display.height).toFloat())
            if (outline) drawRect(color, origin, extent, style = Stroke(v.borderWidth.toPx().coerceAtLeast(2.dp.toPx())))
            else drawRect(color, origin, extent)
        }
        rect(0.0, 0.0, 1.0, 1.0, v.colors.surface)
        geometry.insets.let { insets ->
            rect(0.0, 0.0, 1.0, insets.top.toDouble() / geometry.height, v.colors.border)
            rect(0.0, 1.0 - insets.bottom.toDouble() / geometry.height, 1.0, 1.0, v.colors.border)
            rect(0.0, 0.0, insets.left.toDouble() / geometry.width, 1.0, v.colors.border)
            rect(1.0 - insets.right.toDouble() / geometry.width, 0.0, 1.0, 1.0, v.colors.border)
        }
        for (step in 1..3) {
            val ratio = step / 4.0
            drawLine(v.colors.border, point(ratio, 0.0), point(ratio, 1.0), v.borderWidth.toPx())
            drawLine(v.colors.border, point(0.0, ratio), point(1.0, ratio), v.borderWidth.toPx())
        }
        rect(0.0, 0.0, 1.0, 1.0, v.colors.border, true)
        rect(bounds.left, bounds.top, bounds.right, bounds.bottom, v.colors.accent.copy(alpha = 0.18f))
        rect(bounds.left, bounds.top, bounds.right, bounds.bottom, v.colors.accent, true)
        recipe?.pixelCrop?.let { crop ->
            rect(crop.left.toDouble() / geometry.width, crop.top.toDouble() / geometry.height,
                crop.right.toDouble() / geometry.width, crop.bottom.toDouble() / geometry.height, v.colors.text, true)
        }
        val xs = listOf(bounds.left, (bounds.left + bounds.right) / 2, bounds.right)
        val ys = listOf(bounds.top, (bounds.top + bounds.bottom) / 2, bounds.bottom)
        xs.forEachIndexed { ix, x -> ys.forEachIndexed { iy, y ->
            if (ix != 1 || iy != 1) drawCircle(v.colors.accent, 5.dp.toPx(), point(x, y))
        } }
    }
}

internal fun recordingRegionHandleLabel(handle: RecordingRegionHandle): String = when (handle) {
    RecordingRegionHandle.MOVE -> "整体移动"
    RecordingRegionHandle.LEFT -> "左边"
    RecordingRegionHandle.TOP -> "上边"
    RecordingRegionHandle.RIGHT -> "右边"
    RecordingRegionHandle.BOTTOM -> "下边"
    RecordingRegionHandle.TOP_LEFT -> "左上角"
    RecordingRegionHandle.TOP_RIGHT -> "右上角"
    RecordingRegionHandle.BOTTOM_LEFT -> "左下角"
    RecordingRegionHandle.BOTTOM_RIGHT -> "右下角"
}
