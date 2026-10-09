package com.easysavevideo.app.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.theme.*
import com.easysavevideo.app.library.share.SharePolicy
import com.easysavevideo.library.MediaItem
import com.easysavevideo.library.MediaReadRef

internal enum class LibraryExportDestination { GALLERY, FILES, SHARE }

/** Foreground, exact-version choice only. It carries no system permission or stored destination. */
internal fun exportSelectionIsCurrent(reference: MediaReadRef, items: List<MediaItem>, foreground: Boolean): Boolean =
    foreground && items.any { it.lifecycle == com.easysavevideo.library.MediaLifecycle.READY && it.readRef() == reference }

@Composable
internal fun LibraryExportDialog(
    item: MediaItem,
    enabled: Boolean,
    onChoose: (LibraryExportDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    val v = LocalEsvVisuals.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 720.dp)
            .background(v.colors.surface, RoundedCornerShape(v.corner))
            .verticalScroll(rememberScrollState()).padding(v.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            EsvText("导出 / 分享副本", style = v.titleTextStyle)
            EsvText(item.displayName)
            EsvText("${libraryBytes(item.sizeBytes)} · MP4", style = v.captionTextStyle)
            EsvText("每次操作会另建普通视频副本，保留媒体库原件。只有完整写入并核对后才确认导出完成。", style = v.captionTextStyle)
            EsvText("相册副本发布后可由其他有权限的应用读取。", style = v.captionTextStyle, color = v.colors.muted)
            EsvButton("导出一份到相册", { onChoose(LibraryExportDestination.GALLERY) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
            EsvText("文件位置由你在系统中选择。所选服务可能同步到云端；取消、权限丢失或服务不支持核对时，可能留下部分文件，需要在该位置检查或清理。", style = v.captionTextStyle, color = v.colors.muted)
            EsvButton("选择文件位置并导出", { onChoose(LibraryExportDestination.FILES) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
            EsvText("分享支持单个不超过 ${libraryBytes(SharePolicy.MAX_COPY_BYTES)} 的视频。应用内最多保留 ${SharePolicy.MAX_COPIES} 份临时副本，总计不超过 ${libraryBytes(SharePolicy.MAX_TOTAL_BYTES)}。副本只读、有效期 10 分钟，最多打开 ${SharePolicy.MAX_OPENS} 次，支持按位置读取。打开系统面板不代表已送达；到期或撤销会阻止后续读取，但接收方已读到或复制的内容无法收回。", style = v.captionTextStyle, color = v.colors.muted)
            if (item.sizeBytes > SharePolicy.MAX_COPY_BYTES) EsvText("此视频超过临时分享大小上限，可使用上方的相册或文件导出。", style = v.captionTextStyle, color = v.colors.muted)
            EsvButton("准备副本并打开系统分享", { onChoose(LibraryExportDestination.SHARE) }, enabled = enabled && item.sizeBytes in 1..SharePolicy.MAX_COPY_BYTES, modifier = Modifier.fillMaxWidth())
            EsvButton("取消", onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
