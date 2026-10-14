package com.easysavevideo.app.library

import com.easysavevideo.app.ui.InputClearButton
import com.easysavevideo.app.BuildConfig
import com.easysavevideo.app.diagnostics.InitializationDiagnostic
import com.easysavevideo.app.diagnostics.initializationDiagnosticText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.theme.*
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.editor.EditorInitializationStatus
import com.easysavevideo.app.editor.editorEntryEnabled
import com.easysavevideo.app.library.export.AndroidExportRuntime
import com.easysavevideo.app.library.thumbnail.AndroidLibraryThumbnails
import com.easysavevideo.app.library.thumbnail.LibraryThumbnail
import com.easysavevideo.app.library.thumbnail.OrdinaryMediaNativeResources
import com.easysavevideo.app.library.thumbnail.libraryThumbnailAdmission
import com.easysavevideo.app.library.thumbnail.libraryVisibleThumbnailIds
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
    onBatchExport: ((LibraryBatchSelection) -> Unit)? = null,
    onEdit: ((MediaItem) -> Unit)? = null,
    exports: AndroidExportRuntime? = null,
    thumbnails: AndroidLibraryThumbnails? = null,
    editorInitialization: EditorInitializationStatus = if (onEdit != null) EditorInitializationStatus.READY else EditorInitializationStatus.INITIALIZING,
    editorDiagnostic: InitializationDiagnostic? = null,
    exportRecoveryIsCurrent: (AndroidExportRuntime) -> Boolean = { false },
) {
    val state = runtime.state
    val v = LocalEsvVisuals.current
    var grid by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var thumbnailsRequested by remember(thumbnails) { mutableStateOf(false) }
    var multi by remember { mutableStateOf(LibraryMultiSelection.inactive()) }
    val currentMulti = multi.reconcile(state.items)
    LaunchedEffect(state.query, state.foreground) { multi = LibraryMultiSelection.inactive() }
    LaunchedEffect(state.items, state.ready, state.loading) {
        if (state.ready && !state.loading) multi = multi.reconcile(state.items)
    }
    fun selectedAction(action: (LibrarySelection.Media) -> Unit) {
        val item = librarySingleMultiAction(multi, runtime.state) ?: return
        runtime.select(item)
        val selection = LibrarySelection.Media(item)
        if (runtime.state.selected != selection) return
        multi = LibraryMultiSelection.inactive()
        action(selection)
    }
    fun batchAction() {
        val callback=onBatchExport ?: return
        if(!libraryMultiSelectionInteractive(multi,runtime.state))return
        val selection=LibraryBatchSelection.admit(multi.references.toList(),runtime.state) ?: return
        multi=LibraryMultiSelection.inactive()
        callback(selection)
    }
    val gridState = rememberLazyGridState()
    val visibleThumbnailIds by remember { derivedStateOf {
        libraryVisibleThumbnailIds(gridState.layoutInfo.visibleItemsInfo.map { it.key })
    } }
    val interactive = state.foreground && state.ready && !state.busy && !state.loading
    var thumbnailAdmissionReady by remember(thumbnails) { mutableStateOf(false) }
    var thumbnailAdmissionFenced by remember(thumbnails) { mutableStateOf(false) }
    LaunchedEffect(state.foreground) { if (!state.foreground) thumbnailsRequested = false }
    LaunchedEffect(thumbnails, state.foreground, thumbnailsRequested) {
        thumbnailAdmissionReady = false
        thumbnailAdmissionFenced = false
        if (thumbnails != null && state.foreground && thumbnailsRequested) {
            // Closing a preview is asynchronous. Never poison this new page with sticky BUSY
            // results while the old native owner is still genuinely releasing its resources.
            while (OrdinaryMediaNativeResources.isOccupied && !OrdinaryMediaNativeResources.requiresRestart) delay(50)
            thumbnailAdmissionFenced = OrdinaryMediaNativeResources.requiresRestart
            thumbnailAdmissionReady = !thumbnailAdmissionFenced
        }
    }
    fun handleBack() = dispatchLibraryBack(runtime.state, multi.active,
        cancelDelete = runtime::cancelDelete, cancelEditor = runtime::cancelEditor,
        exitMulti = { multi = LibraryMultiSelection.inactive() }, dismissSelection = runtime::dismissSelection,
        cancelMove = runtime::cancelMove, up = runtime::up, leave = onBack)
    BackHandler { handleBack() }
    EsvBackdrop(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val layout = libraryPanelLayout(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale, v.spacingMedium.value)
            val expanded = layout.expanded
            val toolbarHeight = (maxHeight * 0.4f).coerceAtMost(240.dp)
            val thumbnailEnabled = libraryThumbnailAdmission(thumbnailsRequested, state.foreground, state.ready,
                state.busy, state.loading, (!expanded && state.selected != null) || state.deletion != null || state.editor != null,
                state.move != null, thumbnailAdmissionReady)
            SideEffect { thumbnails?.updatePage(state.items, enabled = thumbnailEnabled) }
            Row(Modifier.widthIn(max = if (expanded) maxWidth else 1_200.dp).fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                if (multi.active) LibraryMultiSelectionToolbar(currentMulti.count, libraryMultiSelectionInteractive(multi, state),
                    single = librarySingleMultiAction(multi, state) != null, exportAvailable = onExport != null && onBatchExport != null && exports?.state?.busy!=true,
                    onPage = { if (libraryMultiSelectionInteractive(multi, runtime.state)) multi = multi.selectPage(runtime.state.items) },
                    onClear = { multi = multi.clear() }, onCancel = { multi = LibraryMultiSelection.inactive() },
                    onDetails = { selectedAction {} }, onRename = { selectedAction { runtime.rename() } },
                    onMove = {
                        if (libraryMultiSelectionInteractive(multi, runtime.state) &&
                            runtime.startMoveBatch(multi.references.toList())) multi = LibraryMultiSelection.inactive()
                    },
                    onExport = { if(multi.count>1)batchAction() else if (onExport != null) selectedAction { selection -> runtime.takeSelectedReadRef(selection)?.let(onExport) } },
                    onShare = ::batchAction,
                    modifier = Modifier.heightIn(max = toolbarHeight))
                LazyVerticalGrid(
                    state = gridState,
                    columns = if (grid && state.move == null) GridCells.Adaptive(240.dp) else GridCells.Fixed(1),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(v.spacingMedium),
                    horizontalArrangement = Arrangement.spacedBy(v.spacingMedium),
                    verticalArrangement = Arrangement.spacedBy(v.spacingMedium),
                ) {
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                            EsvText(stringResource(R.string.flow_libraryscreen_0), style = v.titleTextStyle)
                            if (!multi.active) EsvButton(stringResource(R.string.library_multi_enter), {
                                if (interactive && state.move == null && state.editor == null && state.deletion == null) {
                                    runtime.dismissSelection(); multi = LibraryMultiSelection.enter(runtime.state.query)
                                }
                            }, enabled = interactive && state.move == null && state.editor == null && state.deletion == null)
                            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                EsvButton(stringResource(R.string.flow_libraryscreen_1), onClick = ::handleBack)
                                EsvButton(stringResource(R.string.flow_libraryscreen_2), onClick = runtime::unavailableVault, enabled = false)
                                EsvButton(stringResource(R.string.flow_libraryscreen_3), onClick = { runtime.refresh(preserveSelection = expanded) }, enabled = state.foreground && !state.busy && !state.loading)
                            }
                            EsvText(stringResource(R.string.flow_libraryscreen_4), style = v.captionTextStyle, color = v.colors.muted)
                            EsvText(stringResource(R.string.flow_libraryscreen_5), style = v.captionTextStyle, color = v.colors.muted)
                            if (thumbnailAdmissionFenced) EsvText(stringResource(R.string.flow_libraryscreen_6), style = v.captionTextStyle, color = v.colors.error)
                            else if (thumbnailsRequested && state.foreground && !thumbnailAdmissionReady) EsvText(stringResource(R.string.flow_libraryscreen_7), style = v.captionTextStyle, color = v.colors.muted)
                            EsvButton(stringResource(if (thumbnailsRequested) R.string.thumbnail_stop else R.string.thumbnail_load_visible),
                                onClick = { thumbnailsRequested = !thumbnailsRequested }, enabled = interactive && state.move == null && thumbnails != null)
                            if (thumbnails == null) EsvText(stringResource(R.string.thumbnail_module_unavailable), style = v.captionTextStyle, color = v.colors.muted)
                            exports?.let { LibraryExportStatus(it, state.foreground) { exportRecoveryIsCurrent(it) } }
                            if (state.move == null) {
                                LibraryField(state.query.text, runtime::search, stringResource(R.string.flow_library_search), enabled = state.foreground && !state.busy)
                                if (state.query.text.isNotEmpty()) InputClearButton(R.string.library_clear_search,
                                    enabled = state.foreground && !state.busy,
                                    onClear = { runtime.clearSearch(state) })
                                LibraryDisplayControls(state.query.sort, state.query.direction, grid, interactive,
                                    onSort = runtime::sort, onReverse = runtime::reverseSort,
                                    onToggleView = { grid = !grid }, onCreateFolder = runtime::createFolder)
                            }
                            EsvText(if (state.query.folderId == null) "位置：媒体库根目录" else "位置：${state.folder?.displayName ?: "正在读取文件夹…"}", style = v.titleTextStyle)
                            if (state.query.folderId != null) Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                EsvButton(stringResource(R.string.flow_libraryscreen_9), runtime::up, enabled = interactive)
                                EsvButton(stringResource(R.string.flow_libraryscreen_10), { runtime.enterFolder(null) }, enabled = interactive)
                            }
                            state.move?.let { move ->
                                EsvText(if (move.batchRefs.isEmpty()) "移动：${move.target.name}" else stringResource(R.string.library_batch_move_count, move.batchRefs.size))
                                if (move.batchRefs.isNotEmpty()) EsvText(stringResource(R.string.library_batch_move_partial), style = v.captionTextStyle)
                                if (move.batchRefs.isNotEmpty()) state.batchMoveNames.forEachIndexed { index, name ->
                                    EsvText(stringResource(R.string.library_batch_move_item, index + 1, name), style = v.captionTextStyle)
                                }
                                EsvText(stringResource(R.string.flow_libraryscreen_12), style = v.captionTextStyle, color = v.colors.muted)
                                EsvButton(stringResource(R.string.flow_libraryscreen_13), runtime::confirmMove,
                                    enabled = interactive && !(move.target is LibrarySelection.Folder && move.target.id == state.query.folderId), modifier = Modifier.fillMaxWidth())
                                EsvButton(stringResource(R.string.flow_libraryscreen_14), runtime::cancelMove, enabled = !state.busy || move.batchRefs.isNotEmpty())
                            }
                            if (state.busy) EsvText(stringResource(R.string.flow_libraryscreen_15), color = v.colors.muted)
                            else if (state.loading) EsvText(stringResource(R.string.flow_libraryscreen_16), color = v.colors.muted)
                            else if (!state.ready) EsvText(stringResource(R.string.flow_libraryscreen_17), color = v.colors.muted)
                            state.notice?.let {
                                EsvText(it, style = v.captionTextStyle)
                                EsvButton(stringResource(R.string.flow_libraryscreen_18), runtime::dismissNotice)
                            }
                            state.batchMoveResults.forEachIndexed { index, result ->
                                state.batchMoveNames.getOrNull(index)?.let { name -> EsvText(name, style = v.captionTextStyle) }
                                EsvText(stringResource(R.string.library_batch_move_item, index + 1, stringResource(when (result.status) {
                                    com.easysavevideo.library.BatchMoveStatus.MOVED -> R.string.library_batch_moved
                                    com.easysavevideo.library.BatchMoveStatus.ALREADY_AT_TARGET -> R.string.library_batch_already
                                    com.easysavevideo.library.BatchMoveStatus.STALE -> R.string.library_batch_stale
                                    com.easysavevideo.library.BatchMoveStatus.UNAVAILABLE -> R.string.library_batch_unavailable
                                    com.easysavevideo.library.BatchMoveStatus.FAILED_UNCONFIRMED -> R.string.library_batch_unknown
                                    com.easysavevideo.library.BatchMoveStatus.CANCELLED_BEFORE_START -> R.string.library_batch_cancelled
                                })), style = v.captionTextStyle)
                            }
                            if (state.pendingDeleteCount > 0) EsvText("${state.pendingDeleteCount} 个已确认删除的项目仍待清理；尚未完成。", color = v.colors.error, style = v.captionTextStyle)
                        }
                    }
                    if (state.folders.isNotEmpty()) {
                        item(key = "folder_header", span = { GridItemSpan(maxLineSpan) }) {
                            EsvText("文件夹（${state.folders.size}）", style = v.sectionTextStyle)
                        }
                        items(state.folders.drop(state.folderPage * AndroidLibraryRuntime.FOLDER_PAGE_SIZE).take(AndroidLibraryRuntime.FOLDER_PAGE_SIZE), key = { "folder:${it.folderId}" }, span = { GridItemSpan(maxLineSpan) }) { folder ->
                            LibraryCard {
                                EsvText(folder.displayName, style = v.titleTextStyle, maxLines = 2)
                                EsvText("${folder.itemCount} 个视频 · ${folder.childFolderCount} 个子文件夹", style = v.captionTextStyle, color = v.colors.muted)
                                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                    EsvButton(stringResource(R.string.flow_libraryscreen_22), { runtime.enterFolder(folder.folderId) }, enabled = interactive)
                                    if (state.move == null) EsvButton(stringResource(R.string.flow_libraryscreen_23), { runtime.select(folder) }, enabled = interactive && !multi.active)
                                }
                            }
                        }
                        item(key = "folder_paging", span = { GridItemSpan(maxLineSpan) }) {
                            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                EsvButton(stringResource(R.string.flow_libraryscreen_24), { runtime.folderPage(state.folderPage - 1) }, enabled = interactive && state.folderPage > 0)
                                EsvButton(stringResource(R.string.flow_libraryscreen_25), { runtime.folderPage(state.folderPage + 1) }, enabled = interactive && (state.folderPage + 1) * AndroidLibraryRuntime.FOLDER_PAGE_SIZE < state.folders.size)
                            }
                        }
                    }
                    if (state.move == null) {
                        item(key = "media_header", span = { GridItemSpan(maxLineSpan) }) {
                            EsvText(if (state.query.text.isEmpty()) "视频" else "当前文件夹搜索结果", style = v.sectionTextStyle)
                        }
                        if (state.ready && !state.loading && state.items.isEmpty()) item(key = "empty_media", span = { GridItemSpan(maxLineSpan) }) {
                            EsvText(if (state.query.text.isEmpty()) "此文件夹暂无已入库视频。" else stringResource(R.string.flow_library_no_match), color = v.colors.muted)
                        }
                        items(state.items, key = { "media:${it.itemId}" }) { media ->
                            LibraryCard {
                                thumbnails?.let { LibraryThumbnail(media, it, Modifier.fillMaxWidth(),
                                    visible = thumbnailEnabled && media.itemId in visibleThumbnailIds) }
                                if (multi.active) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .toggleable(value = currentMulti.contains(media), enabled = libraryMultiSelectionInteractive(multi, state) && media.lifecycle == MediaLifecycle.READY,
                                        role = Role.Checkbox, onValueChange = { if (libraryMultiSelectionInteractive(multi, runtime.state)) multi = multi.toggle(media, runtime.state.items) }),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                    Checkbox(checked = currentMulti.contains(media), onCheckedChange = null)
                                    EsvText(stringResource(R.string.library_multi_item, media.displayName), style = v.bodyTextStyle, maxLines = 2)
                                }
                                EsvText(media.displayName, style = v.titleTextStyle, maxLines = 2)
                                EsvText("MP4 · ${durationLabel(media.durationMillis)} · ${libraryBytes(media.sizeBytes)}", style = v.captionTextStyle)
                                EsvText(resolutionLabel(media), style = v.captionTextStyle, color = v.colors.muted)
                                EsvText(dateLabel(media), style = v.captionTextStyle, color = v.colors.muted)
                                EsvButton(stringResource(R.string.flow_libraryscreen_27), { runtime.select(media) }, enabled = interactive && !multi.active, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        item(key = "media_paging", span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                EsvText("第 ${(state.query.cursor?.offset ?: 0) / state.query.pageSize + 1} 页 · 每页最多 ${state.query.pageSize} 个视频", style = v.captionTextStyle, color = v.colors.muted)
                                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                                    EsvButton(stringResource(R.string.flow_libraryscreen_29), runtime::previousPage, enabled = interactive && state.query.cursor != null)
                                    EsvButton(stringResource(R.string.flow_libraryscreen_30), runtime::nextPage, enabled = interactive && state.nextCursor != null)
                                }
                                EsvText(stringResource(R.string.flow_libraryscreen_31), style = v.captionTextStyle, color = v.colors.muted)
                            }
                        }
                    }
                }
                }
                if (expanded) Column(Modifier.width(layout.detailWidthDp.dp).fillMaxHeight()
                    .background(v.colors.surface, RoundedCornerShape(v.cardCorner))
                    .verticalScroll(rememberScrollState()).padding(v.spacingMedium),
                    verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                    EsvText(stringResource(R.string.library_detail_heading), style = v.sectionTextStyle)
                    if (multi.active) EsvText(stringResource(R.string.library_multi_count, currentMulti.count), color = v.colors.muted)
                    else SelectedDetailContent(runtime, onPreview, onExport, onEdit, editorInitialization, editorDiagnostic)
                }
                when {
                    state.deletion != null -> DeleteDialog(runtime, state.deletion)
                    state.editor != null -> NameDialog(runtime, state.editor)
                    !expanded && state.selected != null -> DetailDialog(runtime, onPreview, onExport, onEdit, editorInitialization, editorDiagnostic)
                }
            }
        }
    }
}

@Composable
private fun DetailDialog(runtime: AndroidLibraryRuntime, onPreview: (MediaReadRef) -> Unit, onExport: ((MediaReadRef) -> Unit)?, onEdit: ((MediaItem) -> Unit)?, editorInitialization: EditorInitializationStatus, editorDiagnostic: InitializationDiagnostic?) {
    LibraryDialog(runtime::dismissSelection) { SelectedDetailContent(runtime, onPreview, onExport, onEdit, editorInitialization, editorDiagnostic) }
}

@Composable
private fun ColumnScope.SelectedDetailContent(runtime: AndroidLibraryRuntime, onPreview: (MediaReadRef) -> Unit, onExport: ((MediaReadRef) -> Unit)?, onEdit: ((MediaItem) -> Unit)?, editorInitialization: EditorInitializationStatus, editorDiagnostic: InitializationDiagnostic?) {
    val state = runtime.state
    val v = LocalEsvVisuals.current
    val selected = state.selected
    val current = libraryCurrentDetail(selected, state.items, state.folders)
    when {
        selected == null -> EsvText(stringResource(R.string.library_detail_select), color = v.colors.muted)
        state.loading || state.busy || !state.ready -> {
            EsvText(stringResource(R.string.library_detail_waiting), color = v.colors.muted)
            EsvButton(stringResource(R.string.flow_libraryscreen_43), runtime::dismissSelection)
        }
        current == null -> {
            EsvText(stringResource(R.string.library_detail_missing), color = v.colors.muted)
            EsvButton(stringResource(R.string.flow_libraryscreen_43), runtime::dismissSelection)
        }
        else -> DetailContent(runtime, current, onPreview, onExport, onEdit, editorInitialization, editorDiagnostic)
    }
}

@Composable
private fun ColumnScope.DetailContent(runtime: AndroidLibraryRuntime, selection: LibrarySelection, onPreview: (MediaReadRef) -> Unit, onExport: ((MediaReadRef) -> Unit)?, onEdit: ((MediaItem) -> Unit)?, editorInitialization: EditorInitializationStatus, editorDiagnostic: InitializationDiagnostic?) {
    val v = LocalEsvVisuals.current
    EsvText(selection.name, style = v.titleTextStyle)
    when (selection) {
        is LibrarySelection.Media -> {
            val media = selection.item
            EsvText("已验证的本地 MP4 · ${libraryBytes(media.sizeBytes)}")
            EsvText("${durationLabel(media.durationMillis)} · ${resolutionLabel(media)}")
            EsvText(dateLabel(media), style = v.captionTextStyle)
            if (media.dateKind == MediaDateKind.IMPORTED) EsvText(stringResource(R.string.flow_libraryscreen_34), style = v.captionTextStyle, color = v.colors.muted)
            EsvText(stringResource(R.string.flow_libraryscreen_35), style = v.captionTextStyle, color = v.colors.muted)
            EsvButton(stringResource(R.string.flow_libraryscreen_36), {
                if (onEdit != null && editorEntryEnabled(true, editorInitialization) && runtime.takeSelectedReadRef(selection) == media.readRef()) onEdit(media)
            }, enabled = editorEntryEnabled(onEdit != null, editorInitialization), modifier = Modifier.fillMaxWidth())
            val editorNotice = when(editorInitialization) {
                EditorInitializationStatus.INITIALIZING -> R.string.editor_initializing
                EditorInitializationStatus.STORAGE_UNAVAILABLE -> R.string.editor_storage_unavailable
                EditorInitializationStatus.RUNTIME_UNAVAILABLE -> R.string.editor_runtime_unavailable
                EditorInitializationStatus.READY -> null
            }
            editorNotice?.let { EsvText(stringResource(it), style=v.captionTextStyle, color=v.colors.muted) }
            initializationDiagnosticText(BuildConfig.DEBUG, editorDiagnostic)?.let {
                EsvText(it, style = v.captionTextStyle, color = v.colors.muted)
            }
            EsvButton(stringResource(R.string.flow_libraryscreen_37), { runtime.takeSelectedReadRef(selection)?.let(onPreview) }, modifier = Modifier.fillMaxWidth())
            EsvButton(stringResource(if (onExport == null) R.string.flow_export_unavailable else R.string.flow_export), {
                if (onExport == null) runtime.unavailableExport()
                else runtime.takeSelectedReadRef(selection)?.let(onExport)
            }, enabled = onExport != null, modifier = Modifier.fillMaxWidth())
            if (onExport == null) EsvText(stringResource(R.string.flow_libraryscreen_38), style = v.captionTextStyle, color = v.colors.muted)
        }
        is LibrarySelection.Folder -> {
            EsvText("${selection.folder.itemCount} 个视频 · ${selection.folder.childFolderCount} 个子文件夹")
            if (selection.folder.itemCount > 0 || selection.folder.childFolderCount > 0) EsvText(stringResource(R.string.flow_libraryscreen_40), style = v.captionTextStyle, color = v.colors.muted)
        }
    }
    EsvButton(stringResource(R.string.flow_libraryscreen_41), runtime::rename, modifier = Modifier.fillMaxWidth())
    EsvButton(stringResource(R.string.flow_libraryscreen_42), runtime::startMove, modifier = Modifier.fillMaxWidth())
    EsvButton(if (selection is LibrarySelection.Folder) "删除空文件夹…" else "删除本地视频…", runtime::requestDelete, modifier = Modifier.fillMaxWidth())
    EsvButton(stringResource(R.string.flow_libraryscreen_43), runtime::dismissSelection, modifier = Modifier.fillMaxWidth())
    runtime.state.notice?.let { EsvText(it, style = v.captionTextStyle) }
}

@Composable
private fun NameDialog(runtime: AndroidLibraryRuntime, editor: LibraryNameEditor) {
    val v = LocalEsvVisuals.current
    LibraryDialog(runtime::cancelEditor) {
        EsvText(if (editor.target == null) "新建文件夹" else "重命名", style = v.titleTextStyle)
        LibraryField(editor.name, runtime::editName, "名称", enabled = !runtime.state.busy)
        EsvText(stringResource(R.string.flow_libraryscreen_44), style = v.captionTextStyle, color = v.colors.muted)
        libraryNameError(editor.name)?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.error) }
        EsvButton(stringResource(R.string.flow_libraryscreen_45), runtime::submitName, enabled = libraryNameError(editor.name) == null && !runtime.state.busy, modifier = Modifier.fillMaxWidth())
        EsvButton(stringResource(R.string.flow_libraryscreen_46), runtime::cancelEditor, modifier = Modifier.fillMaxWidth())
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
            EsvText(stringResource(R.string.flow_libraryscreen_48), color = v.colors.error)
            EsvText(stringResource(R.string.flow_libraryscreen_49), style = v.captionTextStyle, color = v.colors.muted)
        } else EsvText(stringResource(R.string.flow_libraryscreen_50), style = v.captionTextStyle)
        EsvButton(stringResource(R.string.flow_libraryscreen_51), { runtime.confirmDelete(confirmation) }, enabled = !runtime.state.busy, modifier = Modifier.fillMaxWidth())
        EsvButton(stringResource(R.string.flow_libraryscreen_52), runtime::cancelDelete, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun LibraryCard(content: @Composable ColumnScope.() -> Unit) {
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.cardCorner)).padding(v.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(v.spacingSmall), content = content)
}

@Composable
internal fun LibraryDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val v = LocalEsvVisuals.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 720.dp)
            .background(v.colors.elevated, RoundedCornerShape(v.sheetCorner)).verticalScroll(rememberScrollState()).padding(v.spacingLarge),
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

@Composable private fun durationLabel(millis:Long?)=libraryDuration(millis,stringResource(R.string.finite_duration_unknown))
@Composable private fun resolutionLabel(media:MediaItem)=libraryResolution(media,stringResource(R.string.finite_resolution_unknown))
@Composable private fun dateLabel(media:MediaItem):String = stringResource(
    if(media.dateKind==MediaDateKind.IMPORTED)R.string.finite_imported_at else R.string.finite_saved_at,
    libraryDate(media,LocalConfiguration.current.locales[0]))
internal fun libraryDuration(millis: Long?, unknownLabel:String): String {
    if (millis == null) return unknownLabel
    val seconds = millis.coerceAtLeast(0) / 1_000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
}
internal fun libraryBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.ROOT, "%.2f GiB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MiB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1_024.0)
    else -> "$bytes B"
}
internal fun libraryResolution(media: MediaItem,unknownLabel:String): String = if (media.width == null) unknownLabel else "${media.width} × ${media.height}"
internal fun libraryDate(media: MediaItem,locale:Locale): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,locale).format(Date(media.createdAtMillis))
