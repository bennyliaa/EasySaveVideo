package com.easysavevideo.app.recording.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.recording.*

enum class RecordingRegionProblem(val resource: Int, val chinese: String) {
    GEOMETRY_UNAVAILABLE(R.string.region_problem_0, "尚未获得默认显示屏尺寸，不能确认选区。"),
    SCOPE_UNCONFIRMED(R.string.region_problem_1, "当前默认显示屏授权方式尚未确认。"),
    BOUNDS_INVALID(R.string.region_problem_2, "选区向内对齐后须至少为 128 × 128 像素，且符合编码尺寸上限。请调整选区。"),
    PROCESSING_LIMIT(R.string.region_problem_3, "此选区超出当前处理范围：最长边 1920 像素，总像素不超过 1920 × 1080。请缩小选区；不会自动缩放或改录整屏。"),
    FRAME_EXPIRED(R.string.region_problem_4, "这张授权帧已失效，请取消并重新申请；不会沿用旧帧或旧选区。"),
}

@Composable
internal fun localizedRecordingRegionSummary(bounds: RegionNormalizedRect): String =
    stringResource(R.string.region_summary, bounds.left * 100, bounds.top * 100, bounds.right * 100, bounds.bottom * 100)

@Composable
internal fun localizedRecordingEffectiveCropSummary(recipe: RegionCaptureRecipe): String = recipe.pixelCrop.let {
    stringResource(R.string.region_pixel_summary, it.left, it.top, it.right, it.bottom, it.width, it.height)
}
