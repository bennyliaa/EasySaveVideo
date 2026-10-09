package com.easysavevideo.app.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.theme.*
import com.easysavevideo.app.library.export.AndroidExportRuntime
import com.easysavevideo.app.library.thumbnail.AndroidLibraryThumbnails
import com.easysavevideo.app.library.thumbnail.LibraryThumbnail
import com.easysavevideo.app.library.thumbnail.OrdinaryMediaNativeResources
import kotlinx.coroutines.delay
import com.easysavevideo.library.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Ordinary local catalog only. The caller owns foreground lifecycle and exact-reference playback. */
@Composable
fun LibraryScreen(
    runtime: AndroidLibraryRuntime,
    onBack: () -> Unit,
    onPreview: (MediaReadRef) -> Unit,
    onExport: ((MediaReadRef) -> Unit)? = null,
    onEdit: ((MediaItem) -> Unit)? = null,
    exports: AndroidExportRuntime? = null,
    thumbnails: AndroidLibraryThumbnails? = null,
) {
    val state = runtime.state
    val v = LocalEsvVisuals.current
    var grid by remember { mutableStateOf(false) }
    val interactive = state.foreground && state.ready && !state.busy && !state.loading
    var thumbnailAdmissionReady by remember(thumbnails) { mutableStateOf(false) }
    var thumbnailAdmissionFenced by remember(thumbnails) { mutableStateOf(false) }
    LaunchedEffect(thumbnails, state.foreground) {
        thumbnailAdmissionReady = false
        thumbnailAdmissionFenced = false
        if (thumbnails != null && state.foreground) {
            // Closing a preview is asynchronous. Never poison this new page with sticky BUSY
            // results while the old native owner is still genuinely releasing its resources.
            while (OrdinaryMediaNativeResources.isOccupied && !OrdinaryMediaNativeResources.requiresRestart) delay(50)
            thumbnailAdmissionFenced = OrdinaryMediaNativeResources.requiresRestart
            thumbnailAdmissionReady = !thumbnailAdmissionFenced
        }
    }
    SideEffect { thumbnails?.updatePage(state.items, enabled = interactive && state.move == null && thumbnailAdmissionReady) }
    BackHandler {
        when {
            state.move != null -> runtime.cancelMove()
            state.query.folderId != null -> runtime.up()
            else -> onBack()
        }
    }
    EsvBackdrop(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = if (grid && state.move == null) GridCells.Adaptive(240.dp) else GridCells.Fixed(1),
                modifier = Modifier.widthIn(max = 1_200.dp).fillMaxSize(),
                contentPadding = PaddingValues(v.spacingMedium),
                horizontalArrangement = Arrangement.spacedBy(v.spacingMedium),
                verticalArrangement = Arrangement.spacedBy(v.spacingMedium),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                        EsvText("媒体库", style = v.titleTextStyle)
                        Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                            EsvButton("返回", onClick = onBack)
                            EsvButton("Vault", onClick = runtime::unavailableVault)
                            EsvButton("刷新", onClick = runtime::refresh, enabled = state.foreground && !state.busy && !state.loading)
                        }
                        EsvText("普通媒体库免费使用，仅列出已验证并正式入库的视频。", style = v.captionTextStyle, color = v.colors.muted)
                        EsvText("缩略图只在当前前台页面按需生成；无法读取时会保留明确占位提示。", style = v.captionTextStyle, color = v.colors.muted)
                        if (thumbnailAdmissionFenced) EsvText("媒体资源尚未确认退出，缩略图暂不可用。请完全退出应用后重新打开。", style = v.captionTextStyle, color = v.colors.error)
                        else if (state.foreground && !thumbnailAdmissionReady) EsvText("正在等待上一预览的媒体资源实际退出，再生成缩略图…", style = v.captionTextStyle, color = v.colors.muted)
                        exports?.let { LibraryExportStatus(it, state.foreground) }
                        if (state.move == null) {
                            LibraryField(state.query.text, runtime::search, "搜索当前文件夹的视频名称", enabled = state.foreground && !state.busy)
                            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                EsvButton(sortLabel(LibrarySort.NAME, state.query.sort), { runtime.sort(LibrarySort.NAME) }, enabled = interactive, modifier = Modifier.weight(1f))
                                EsvButton(sortLabel(LibrarySort.DATE, state.query.sort), { runtime.sort(LibrarySort.DATE) }, enabled = interactive, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                EsvButton(sortLabel(LibrarySort.DURATION, state.query.sort), { runtime.sort(LibrarySort.DURATION) }, enabled = interactive, modifier = Modifier.weight(1f))
                                EsvButton(sortLabel(LibrarySort.SIZE, state.query.sort), { runtime.sort(LibrarySort.SIZE) }, enabled = interactive, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                EsvButton(if (state.query.direction == SortDirection.DESCENDING) "降序 ↓" else "升序 ↑", runtime::reverseSort, enabled = interactive)
                                EsvButton(if (grid) "切换列表" else "切换网格", { grid = !grid })
                                EsvButton("新建文件夹", runtime::createFolder, enabled = interactive)
                            }
                        }
                        EsvText(if (state.query.folderId == null) "位置：媒体库根目录" else "位置：${state.folder?.displayName ?: "正在读取文件夹…"}", style = v.titleTextStyle)
                        if (state.query.folderId != null) Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                            EsvButton("上一级", runtime::up, enabled = interactive)
                            EsvButton("根目录", { runtime.enterFolder(null) }, enabled = interactive)
                        }
                        state.move?.let { move ->
                            EsvText("移动：${move.target.name}")
                            EsvText("打开文件夹选择目标，再确认移动。只更改普通媒体库中的位置。", style = v.captionTextStyle, color = v.colors.muted)
                            EsvButton("移动到当前位置", runtime::confirmMove,
                                enabled = interactive && !(move.target is LibrarySelection.Folder && move.target.id == state.query.folderId), modifier = Modifier.fillMaxWidth())
                            EsvButton("取消移动", runtime::cancelMove, enabled = !state.busy)
                        }
                        if (state.busy) EsvText("正在处理操作，尚未确认完成…", color = v.colors.muted)
                        else if (state.loading) EsvText("正在读取媒体库…", color = v.colors.muted)
                        else if (!state.ready) EsvText("媒体库尚未就绪，可刷新重试。", color = v.colors.muted)
                        state.notice?.let {
                            EsvText(it, style = v.captionTextStyle)
                            EsvButton("关闭提示", runtime::dismissNotice)
                        }
                        if (state.pendingDeleteCount > 0) EsvText("${state.pendingDeleteCount} 个已确认删除的项目仍待清理；尚未完成。", color = v.colors.error, style = v.captionTextStyle)
                    }
                }
                if (state.folders.isNotEmpty()) {
                    item(key = "folder_header", span = { GridItemSpan(maxLineSpan) }) {
                        EsvText("文件夹（${state.folders.size}）", style = v.titleTextStyle)
                    }
                    items(state.folders.drop(state.folderPage * AndroidLibraryRuntime.FOLDER_PAGE_SIZE).take(AndroidLibraryRuntime.FOLDER_PAGE_SIZE), key = { "folder:${it.folderId}" }, span = { GridItemSpan(maxLineSpan) }) { folder ->
                        LibraryCard {
                            EsvText(folder.displayName, style = v.titleTextStyle, maxLines = 2)
                            EsvText("${folder.itemCount} 个视频 · ${folder.childFolderCount} 个子文件夹", style = v.captionTextStyle, color = v.colors.muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                EsvButton("打开文件夹", { runtime.enterFolder(folder.folderId) }, enabled = interactive)
                                if (state.move == null) EsvButton("管理", { runtime.select(folder) }, enabled = interactive)
                            }
                        }
                    }
                    item(key = "folder_paging", span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                            EsvButton("上一页文件夹", { runtime.folderPage(state.folderPage - 1) }, enabled = interactive && state.folderPage > 0)
                            EsvButton("下一页文件夹", { runtime.folderPage(state.folderPage + 1) }, enabled = interactive && (state.folderPage + 1) * AndroidLibraryRuntime.FOLDER_PAGE_SIZE < state.folders.size)
                        }
                    }
                }
                if (state.move == null) {
                    item(key = "media_header", span = { GridItemSpan(maxLineSpan) }) {
                        EsvText(if (state.query.text.isEmpty()) "视频" else "当前文件夹搜索结果", style = v.titleTextStyle)
                    }
                    if (state.ready && !state.loading && state.items.isEmpty()) item(key = "empty_media", span = { GridItemSpan(maxLineSpan) }) {
                        EsvText(if (state.query.text.isEmpty()) "此文件夹暂无已入库视频。" else "当前文件夹没有匹配名称的视频。", color = v.colors.muted)
                    }
                    items(state.items, key = { "media:${it.itemId}" }) { media ->
                        LibraryCard {
                            thumbnails?.let { LibraryThumbnail(media, it, Modifier.fillMaxWidth()) }
                            EsvText(media.displayName, style = v.titleTextStyle, maxLines = 2)
                            EsvText("MP4 · ${libraryDuration(media.durationMillis)} · ${libraryBytes(media.sizeBytes)}", style = v.captionTextStyle)
                            EsvText(libraryResolution(media), style = v.captionTextStyle, color = v.colors.muted)
                            EsvText(libraryDate(media), style = v.captionTextStyle, color = v.colors.muted)
                            EsvButton("详情 / 预览", { runtime.select(media) }, enabled = interactive, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    item(key = "media_paging", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                            EsvText("第 ${(state.query.cursor?.offset ?: 0) / state.query.pageSize + 1} 页 · 每页最多 ${state.query.pageSize} 个视频", style = v.captionTextStyle, color = v.colors.muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                EsvButton("上一页视频", runtime::previousPage, enabled = interactive && state.query.cursor != null)
                                EsvButton("下一页视频", runtime::nextPage, enabled = interactive && state.nextCursor != null)
                            }
                            EsvText("导出与本地保存是独立操作。不会自动公开、分享或移入 Vault。", style = v.captionTextStyle, color = v.colors.muted)
                        }
                    }
                }
            }
        }
    }
    when {
        state.deletion != null -> DeleteDialog(runtime, state.deletion)
        state.editor != null -> NameDialog(runtime, state.editor)
        state.selected != null -> DetailDialog(runtime, state.selected, onPreview, onExport, onEdit)
    }
}

@Composable
private fun DetailDialog(runtime: AndroidLibraryRuntime, selection: LibrarySelection, onPreview: (MediaReadRef) -> Unit, onExport: ((MediaReadRef) -> Unit)?, onEdit: ((MediaItem) -> Unit)?) {
    val v = LocalEsvVisuals.current
    LibraryDialog(runtime::dismissSelection) {
        EsvText(selection.name, style = v.titleTextStyle)
        when (selection) {
            is LibrarySelection.Media -> {
                val media = selection.item
                EsvText("已验证的本地 MP4 · ${libraryBytes(media.sizeBytes)}")
                EsvText("${libraryDuration(media.durationMillis)} · ${libraryResolution(media)}")
                EsvText(libraryDate(media), style = v.captionTextStyle)
                if (media.dateKind == MediaDateKind.IMPORTED) EsvText("这是旧数据导入媒体库的时间，不代表拍摄或原始保存时间。", style = v.captionTextStyle, color = v.colors.muted)
                EsvText("普通媒体存储不等同于加密 Vault。预览为本地只读，需手动播放。", style = v.captionTextStyle, color = v.colors.muted)
                if (onEdit != null) EsvButton("视频编辑工具", {
                    if (runtime.takeSelectedReadRef(selection) == media.readRef()) onEdit(media)
                }, modifier = Modifier.fillMaxWidth())
                EsvButton("打开只读预览", { runtime.takeSelectedReadRef(selection)?.let(onPreview) }, modifier = Modifier.fillMaxWidth())
                EsvButton(if (onExport == null) "导出 / 分享（暂不可用）" else "导出 / 分享", {
                    if (onExport == null) runtime.unavailableExport()
                    else runtime.takeSelectedReadRef(selection)?.let(onExport)
                }, enabled = onExport != null, modifier = Modifier.fillMaxWidth())
                if (onExport == null) EsvText("导出服务尚未就绪，原件保留。可返回后稍后重试。", style = v.captionTextStyle, color = v.colors.muted)
            }
            is LibrarySelection.Folder -> {
                EsvText("${selection.folder.itemCount} 个视频 · ${selection.folder.childFolderCount} 个子文件夹")
                if (selection.folder.itemCount > 0 || selection.folder.childFolderCount > 0) EsvText("含内容的文件夹不能直接删除。请先逐项移动内容；不会静默递归删除。", style = v.captionTextStyle, color = v.colors.muted)
            }
        }
        EsvButton("重命名", runtime::rename, modifier = Modifier.fillMaxWidth())
        EsvButton("移动到文件夹", runtime::startMove, modifier = Modifier.fillMaxWidth())
        EsvButton(if (selection is LibrarySelection.Folder) "删除空文件夹…" else "删除本地视频…", runtime::requestDelete, modifier = Modifier.fillMaxWidth())
        EsvButton("关闭", runtime::dismissSelection, modifier = Modifier.fillMaxWidth())
        runtime.state.notice?.let { EsvText(it, style = v.captionTextStyle) }
    }
}

@Composable
private fun NameDialog(runtime: AndroidLibraryRuntime, editor: LibraryNameEditor) {
    val v = LocalEsvVisuals.current
    LibraryDialog(runtime::cancelEditor) {
        EsvText(if (editor.target == null) "新建文件夹" else "重命名", style = v.titleTextStyle)
        LibraryField(editor.name, runtime::editName, "名称", enabled = !runtime.state.busy)
        EsvText("名称最多 255 个 UTF-8 字节，不能含路径分隔符或控制字符。同级名称按 NFC 与不依赖语言区域的大小写键比较。", style = v.captionTextStyle, color = v.colors.muted)
        libraryNameError(editor.name)?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.error) }
        EsvButton("保存名称", runtime::submitName, enabled = libraryNameError(editor.name) == null && !runtime.state.busy, modifier = Modifier.fillMaxWidth())
        EsvButton("取消", runtime::cancelEditor, modifier = Modifier.fillMaxWidth())
        runtime.state.notice?.let { EsvText(it, style = v.captionTextStyle) }
    }
}

@Composable
private fun DeleteDialog(runtime: AndroidLibraryRuntime, confirmation: LibraryDeleteSelection) {
    val v = LocalEsvVisuals.current
    val target = confirmation.target
    LibraryDialog(runtime::cancelDelete) {
        EsvText(if (target is LibrarySelection.Media) "永久删除这个本地视频？" else "删除这个空文件夹？", style = v.titleTextStyle)
        EsvText(target.name)
        EsvText("项目 ${target.id} · 版本 ${target.revision}", style = v.captionTextStyle, color = v.colors.muted)
        if (target is LibrarySelection.Media) {
            EsvText("这会从应用媒体库移除此视频并删除本地原件。没有回收站，无法在应用内恢复；不会删除你曾另行导出的副本。保存任务历史仍会保留。", color = v.colors.error)
            EsvText("若当前仍有媒体读取，文件清理会等待其退出。未完成时会明确显示待清理。", style = v.captionTextStyle, color = v.colors.muted)
        } else EsvText("仅删除此空文件夹。若内容或版本已变化，操作会被拒绝，不会递归删除内容。", style = v.captionTextStyle)
        EsvButton("确认不可恢复地删除", { runtime.confirmDelete(confirmation) }, enabled = !runtime.state.busy, modifier = Modifier.fillMaxWidth())
        EsvButton("保留，取消", runtime::cancelDelete, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun LibraryCard(content: @Composable ColumnScope.() -> Unit) {
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(v.spacingSmall), content = content)
}

@Composable
internal fun LibraryDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val v = LocalEsvVisuals.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 720.dp)
            .background(v.colors.surface, RoundedCornerShape(v.corner)).verticalScroll(rememberScrollState()).padding(v.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(v.spacingMedium), content = content)
    }
}

/** Foundation field consumes the same SkinKit snapshot as every other control. */
@Composable
private fun LibraryField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean) {
    val v = LocalEsvVisuals.current
    Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(label, style = v.captionTextStyle, color = v.colors.muted)
        BasicTextField(value = value, onValueChange = onValueChange, singleLine = true, enabled = enabled,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                .border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
                .background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingMedium)
                .semantics { contentDescription = label },
            textStyle = v.bodyTextStyle.copy(color = v.colors.text), cursorBrush = SolidColor(v.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
    }
}

private fun sortLabel(sort: LibrarySort, selected: LibrarySort): String = (if (sort == selected) "已选 " else "") + when (sort) {
    LibrarySort.NAME -> "名称"
    LibrarySort.DATE -> "日期"
    LibrarySort.DURATION -> "时长"
    LibrarySort.SIZE -> "大小"
}
internal fun libraryDuration(millis: Long?): String {
    if (millis == null) return "时长未知"
    val seconds = millis.coerceAtLeast(0) / 1_000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
}
internal fun libraryBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.ROOT, "%.2f GiB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MiB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1_024.0)
    else -> "$bytes B"
}
internal fun libraryResolution(media: MediaItem): String = if (media.width == null) "分辨率未知" else "${media.width} × ${media.height}"
internal fun libraryDate(media: MediaItem): String =
    (if (media.dateKind == MediaDateKind.IMPORTED) "导入时间：" else "保存时间：") + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(media.createdAtMillis))
