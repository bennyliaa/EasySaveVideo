package com.easysavevideo.app.recording.ui

import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.easysavevideo.app.recording.nativeregion.NativeRegionPreviewFrame
import com.easysavevideo.app.theme.*
import com.easysavevideo.recording.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One memory-only, actually authorized frozen frame. This component has no persistence/export path.
 * The drawing lease is scoped to this composition; no Bitmap/ImageBitmap is remembered or saved.
 * onRetired reports composition disposal only. The host must independently draw its uncovered
 * browser and revalidate authority before the runtime may admit any encoded frame.
 */
@Composable
fun AuthorizedRecordingRegionDialog(
    frame: NativeRegionPreviewFrame,
    enabled: Boolean,
    onConfirm: (RegionPreviewSnapshot, RegionNormalizedRect) -> Unit,
    onCancel: () -> Unit,
    onRetired: (RegionPreviewSnapshot) -> Unit,
) {
    val snapshot = frame.snapshot
    val v = LocalEsvVisuals.current
    val fence = remember(frame) { RecordingPreviewUiFence(snapshot) }
    var showing by remember(frame) { mutableStateOf(true) }
    var bounds by remember(frame) { mutableStateOf(RegionNormalizedRect.FULL) }
    var handle by remember(frame) { mutableStateOf(RecordingRegionHandle.MOVE) }
    val currentConfirm by rememberUpdatedState(onConfirm)
    val currentCancel by rememberUpdatedState(onCancel)
    val currentRetired by rememberUpdatedState(onRetired)
    val currentEnabled by rememberUpdatedState(enabled)
    fun cancel() {
        showing = false
        if (fence.cancel()) currentCancel()
    }
    if (!showing || !enabled) return

    Dialog(onDismissRequest = ::cancel, properties = DialogProperties(
        usePlatformDefaultWidth = false,
        securePolicy = SecureFlagPolicy.SecureOn,
    )) {
        val view = LocalView.current
        val context = LocalContext.current
        val lifecycle = remember(context) { previewLifecycleOwner(context)?.lifecycle }
        val window = (view.parent as? DialogWindowProvider)?.window
        val focusFence = remember(frame, view) { RecordingPreviewWindowFocusFence() }
        // A lease, never a copied/saved raster. The native owner must never recycle shared pixels.
        var drawing by remember(frame) { mutableStateOf<NativeRegionPreviewFrame.Drawing?>(null) }
        val foreground = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        fun secureAndForeground(): Boolean = showing && currentEnabled && focusFence.canUseWindow &&
            view.isAttachedToWindow && view.hasWindowFocus() &&
            lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true &&
            ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        fun maskAndCancel() {
            // Mask this dialog's own content immediately, without altering any host/window flags.
            view.alpha = 0f
            cancel()
        }

        DisposableEffect(frame, view, lifecycle) {
            val treeObserver = view.viewTreeObserver
            val focusObserver = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                // A normal initial composition may precede first focus. Once focused, loss is
                // terminal even in multi-resume, where the Activity may never receive onPause.
                if (focusFence.onFocusChanged(hasFocus, intentionallyRetiring = !showing)) maskAndCancel()
            }
            focusFence.onFocusChanged(view.hasWindowFocus(), intentionallyRetiring = !showing)
            if (treeObserver.isAlive) treeObserver.addOnWindowFocusChangeListener(focusObserver)
            else maskAndCancel()
            val observer = LifecycleEventObserver { _, event ->
                if (event in setOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY))
                    maskAndCancel()
            }
            lifecycle?.addObserver(observer)
            onDispose {
                focusFence.retire()
                if (treeObserver.isAlive) treeObserver.removeOnWindowFocusChangeListener(focusObserver)
                lifecycle?.removeObserver(observer)
                drawing?.close()
                drawing = null
                if (fence.retire()) currentRetired(snapshot)
            }
        }
        LaunchedEffect(frame, view) {
            val startedAt = previewNowUs()
            // Wait for actual owned-window focus, not a fixed delay or the Activity's RESUMED
            // state. SecureOn must also be installed before any raster enters composition.
            // This bounded readiness wait carries no pixels and never extends snapshot expiry.
            val acquiredInTime = withTimeoutOrNull(PREVIEW_WINDOW_READY_TIMEOUT_US / 1000) {
                while (showing && currentEnabled && focusFence.canUseWindow) {
                    withFrameNanos { }
                    val now = previewNowUs()
                    if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true ||
                        now < startedAt || now - startedAt >= PREVIEW_WINDOW_READY_TIMEOUT_US ||
                        now >= snapshot.expiresAtMonotonicUs) return@withTimeoutOrNull false
                    if (secureAndForeground()) {
                        focusFence.onFocusChanged(true, intentionallyRetiring = false)
                        val acquired = frame.acquireDrawing() ?: return@withTimeoutOrNull false
                        drawing = acquired
                        return@withTimeoutOrNull true
                    }
                }
                false
            }
            if (acquiredInTime != true && showing && currentEnabled) maskAndCancel()
        }
        LaunchedEffect(frame) {
            val remaining = snapshot.expiresAtMonotonicUs - previewNowUs()
            if (remaining > 0) delay((remaining + 999) / 1000)
            maskAndCancel()
        }
        val error = recordingPreviewProblem(snapshot, bounds, previewNowUs())?.let { stringResource(it.resource) }
        val adjustable = foreground && enabled && drawing != null
        val recipe = if (error == null) RegionCaptureRecipe(bounds, snapshot.observation.geometry,
            snapshot.observation.scopeEvidence) else null

        Box(Modifier.fillMaxSize().systemBarsPadding().padding(v.spacingMedium), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth()
                .background(v.colors.surface, RoundedCornerShape(v.corner))
                .verticalScroll(rememberScrollState()).padding(v.spacingMedium),
                verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(stringResource(R.string.provider_dialog_48), style = v.titleTextStyle)
                EsvText(stringResource(R.string.provider_dialog_49), style = v.captionTextStyle)
                EsvText(stringResource(R.string.region_preview_scope), style = v.captionTextStyle, color = v.colors.muted)
                drawing?.let { pixels ->
                    FrozenRecordingRegionImage(pixels, snapshot, bounds, recipe, adjustable,
                        canDraw = ::secureAndForeground, onChange = { bounds = it },
                        modifier = Modifier.fillMaxWidth().height(340.dp))
                } ?: EsvText(stringResource(R.string.provider_dialog_50), style = v.captionTextStyle)
                EsvButton(stringResource(R.string.provider_dialog_51), ::maskAndCancel, modifier = Modifier.fillMaxWidth())
                val geometry = snapshot.observation.geometry
                EsvText(stringResource(R.string.provider_dialog_52, geometry.width, geometry.height, snapshot.rasterWidth, snapshot.rasterHeight, geometry.rotationDegrees), style = v.captionTextStyle)
                EsvText(stringResource(R.string.provider_dialog_53), style = v.captionTextStyle, color = v.colors.muted)
                EsvText(localizedRecordingRegionSummary(bounds), style = v.captionTextStyle)
                recipe?.let { EsvText(localizedRecordingEffectiveCropSummary(it), style = v.captionTextStyle) }
                error?.let { EsvText(it, color = v.colors.error, style = v.captionTextStyle) }
                RecordingRegionHandle.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                        row.forEach { target ->
                            EsvButton((if (target == handle) stringResource(R.string.provider_dialog_29) else "") + recordingRegionHandleLabel(target),
                                { handle = target }, enabled = drawing != null && enabled, modifier = Modifier.weight(1f))
                        }
                    }
                }
                // Invalid/tiny/full high-resolution selections must remain adjustable.
                val canAdjust = foreground && enabled && drawing != null
                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton("← 1%", { bounds = dragRecordingRegion(bounds, handle, -0.01, 0.0) }, enabled = canAdjust, modifier = Modifier.weight(1f))
                    EsvButton("1% →", { bounds = dragRecordingRegion(bounds, handle, 0.01, 0.0) }, enabled = canAdjust, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton("↑ 1%", { bounds = dragRecordingRegion(bounds, handle, 0.0, -0.01) }, enabled = canAdjust, modifier = Modifier.weight(1f))
                    EsvButton("1% ↓", { bounds = dragRecordingRegion(bounds, handle, 0.0, 0.01) }, enabled = canAdjust, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton(stringResource(R.string.provider_dialog_30), { bounds = RegionNormalizedRect.FULL }, enabled = canAdjust, modifier = Modifier.weight(1f))
                    EsvButton(stringResource(R.string.provider_dialog_31), { bounds = RegionNormalizedRect.FULL; handle = RecordingRegionHandle.MOVE }, enabled = canAdjust, modifier = Modifier.weight(1f))
                }
                EsvText(stringResource(R.string.provider_dialog_54), style = v.captionTextStyle)
                EsvText(stringResource(R.string.provider_dialog_55), style = v.captionTextStyle, color = v.colors.muted)
                EsvText(stringResource(R.string.provider_dialog_56), style = v.captionTextStyle, color = v.colors.muted)
                EsvButton(stringResource(R.string.provider_dialog_57), {
                    if (fence.confirm(snapshot, bounds, previewNowUs(), secureAndForeground(), drawing != null)) {
                        view.alpha = 0f
                        showing = false
                        currentConfirm(snapshot, bounds)
                    }
                }, enabled = adjustable && error == null && recipe != null, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun FrozenRecordingRegionImage(
    drawing: NativeRegionPreviewFrame.Drawing,
    snapshot: RegionPreviewSnapshot,
    bounds: RegionNormalizedRect,
    recipe: RegionCaptureRecipe?,
    enabled: Boolean,
    canDraw: () -> Boolean,
    onChange: (RegionNormalizedRect) -> Unit,
    modifier: Modifier,
) {
    val v = LocalEsvVisuals.current
    val geometry = snapshot.observation.geometry
    val currentBounds by rememberUpdatedState(bounds)
    val currentChange by rememberUpdatedState(onChange)
    val frameLabel = stringResource(R.string.provider_dialog_58)
    val regionLabel = localizedRecordingRegionSummary(bounds)
    Canvas(modifier.background(v.colors.bg, RoundedCornerShape(v.corner))
        .semantics {
            contentDescription = frameLabel
            stateDescription = regionLabel
        }
        .pointerInput(snapshot, enabled) {
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
                onDrag = { change, drag -> target?.let { selected ->
                    change.consume()
                    val display = recordingViewport(size.width.toDouble(), size.height.toDouble(), geometry)
                    gestureBounds = dragRecordingRegion(gestureBounds, selected, drag.x / display.width, drag.y / display.height)
                    currentChange(gestureBounds)
                } },
            )
        }) {
        if (!canDraw()) return@Canvas
        val display = recordingViewport(size.width.toDouble(), size.height.toDouble(), geometry)
        // The bounded raster can round by <1 pixel. Map its full edges to the original source
        // extent, exactly like normalized selection/encoder crop; never crop or stretch to the UI.
        withTransform({
            translate(display.left.toFloat(), display.top.toFloat())
            scale((display.width / geometry.width).toFloat(), (display.height / geometry.height).toFloat(), Offset.Zero)
        }) {
            drawImage(drawing.bitmap.asImageBitmap(), dstSize = IntSize(geometry.width, geometry.height))
        }
        fun point(x: Double, y: Double) = Offset((display.left + x * display.width).toFloat(), (display.top + y * display.height).toFloat())
        fun rect(left: Double, top: Double, right: Double, bottom: Double, color: androidx.compose.ui.graphics.Color, outline: Boolean = false) {
            val origin = point(left, top)
            val extent = Size(((right - left) * display.width).toFloat(), ((bottom - top) * display.height).toFloat())
            if (outline) drawRect(color, origin, extent, style = Stroke(v.borderWidth.toPx().coerceAtLeast(2.dp.toPx())))
            else drawRect(color, origin, extent)
        }
        val mask = v.colors.bg.copy(alpha = 0.6f)
        rect(0.0, 0.0, 1.0, bounds.top, mask)
        rect(0.0, bounds.bottom, 1.0, 1.0, mask)
        rect(0.0, bounds.top, bounds.left, bounds.bottom, mask)
        rect(bounds.right, bounds.top, 1.0, bounds.bottom, mask)
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

private fun previewNowUs(): Long = System.nanoTime().coerceAtLeast(0) / 1_000

private const val PREVIEW_WINDOW_READY_TIMEOUT_US = 5_000_000L

private fun previewLifecycleOwner(context: Context): LifecycleOwner? {
    var candidate = context
    repeat(16) {
        if (candidate is LifecycleOwner) return candidate as LifecycleOwner
        val base = (candidate as? ContextWrapper)?.baseContext ?: return null
        if (base === candidate) return null
        candidate = base
    }
    return null
}
