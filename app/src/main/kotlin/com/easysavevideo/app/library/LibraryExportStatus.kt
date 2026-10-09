package com.easysavevideo.app.library

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.easysavevideo.app.library.export.AndroidExportRuntime
import com.easysavevideo.app.library.export.UnverifiedExportView
import com.easysavevideo.app.theme.*

internal fun unverifiedCleanupStillCurrent(
    selected: UnverifiedExportView,
    current: List<UnverifiedExportView>,
    foreground: Boolean,
    busy: Boolean,
): Boolean = foreground && !busy && selected.canAttemptCleanup && selected in current

/** Output responsibilities remain visible independently of source selection and the latest notice. */
@Composable
internal fun LibraryExportStatus(exports: AndroidExportRuntime, foreground: Boolean) {
    val state = exports.state
    val v = LocalEsvVisuals.current
    var showingUnverified by remember { mutableStateOf(false) }
    var cleanupConfirmation by remember { mutableStateOf<UnverifiedExportView?>(null) }
    LaunchedEffect(foreground, state.unverified) {
        if (!foreground) { showingUnverified = false; cleanupConfirmation = null }
        else if (cleanupConfirmation?.let { it !in state.unverified } == true) cleanupConfirmation = null
    }
    Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        if (state.busy) EsvText("正在处理导出 / 分享副本，尚未确认完成。", style = v.captionTextStyle)
        state.notice?.let { EsvText(it, style = v.captionTextStyle) }
        if (state.recoverable > 0) {
            EsvText("${state.recoverable} 个导出操作仍待核对或清理。导出不会删除原件；外部位置可能已有副本或部分文件。", style = v.captionTextStyle, color = v.colors.error)
            EsvButton("核对并重试未完成操作", exports::retry, enabled = foreground && !state.busy, modifier = Modifier.fillMaxWidth())
        }
        if (state.unverifiedCount > 0) {
            EsvText("有 ${state.unverifiedCount} 条文件导出记录需要核对，包含未校验或尚未确认清理的副本。", style = v.captionTextStyle, color = v.colors.muted)
            EsvButton("查看待核对的文件副本", { showingUnverified = true }, enabled = foreground && !state.busy, modifier = Modifier.fillMaxWidth())
        }
        if (state.canCancel) EsvButton("取消本次导出 / 准备分享", exports::cancel, enabled = foreground, modifier = Modifier.fillMaxWidth())
        EsvButton("撤销临时分享读取并清理副本", exports::revokeShares, enabled = foreground && !state.busy, modifier = Modifier.fillMaxWidth())
        EsvText("撤销不会收回其他应用已经读到或保存的内容。部分系统或接收应用可能不支持临时只读分享；遇到兼容问题可改用导出文件。", style = v.captionTextStyle, color = v.colors.muted)
    }
    val confirmation = cleanupConfirmation
    if (confirmation != null && foreground && confirmation in state.unverified) {
        LibraryDialog(onDismiss = { cleanupConfirmation = null }) {
            EsvText("删除这个外部导出副本？", style = v.titleTextStyle)
            EsvText(confirmation.label)
            EsvText("导出操作：${confirmation.operationId}", style = v.captionTextStyle, color = v.colors.muted)
            EsvText("来源项目：${confirmation.source.itemId} · 版本 ${confirmation.source.revision}", style = v.captionTextStyle, color = v.colors.muted)
            EsvText("这会尝试删除上面这条导出记录对应的文件副本，此清理不会操作应用内原件。所选服务可能永久删除该副本；不支持清理时会保留明确的待处理结果。")
            EsvButton("确认删除这个导出副本", {
                if (unverifiedCleanupStillCurrent(confirmation, state.unverified, foreground, state.busy)) {
                    cleanupConfirmation = null
                    exports.cleanupUnverified(confirmation.operationId)
                }
            }, enabled = unverifiedCleanupStillCurrent(confirmation, state.unverified, foreground, state.busy), modifier = Modifier.fillMaxWidth())
            EsvButton("保留副本，取消", { cleanupConfirmation = null }, modifier = Modifier.fillMaxWidth())
        }
    } else if (showingUnverified && foreground) {
        LibraryDialog(onDismiss = { showingUnverified = false }) {
            EsvText("待核对的文件副本", style = v.titleTextStyle)
            EsvText("以下记录与最近一次导出结果分别保存。重启后不会自动重新取得文件授权；没有可用授权的副本需要到原来选择的文件位置手动核对。", style = v.captionTextStyle, color = v.colors.muted)
            if (state.unverified.isEmpty()) EsvText("当前没有待核对的文件副本记录。", style = v.captionTextStyle)
            state.unverified.forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvText(entry.label, maxLines = 3)
                    EsvText(entry.status, style = v.captionTextStyle, color = v.colors.muted)
                    EsvButton("清理这个副本…", { cleanupConfirmation = entry },
                        enabled = entry.canAttemptCleanup && !state.busy, modifier = Modifier.fillMaxWidth())
                }
            }
            if (state.unverifiedPages > 0) {
                EsvText("第 ${state.unverifiedPage + 1} / ${state.unverifiedPages} 页 · ${state.unverifiedCount} 条记录", style = v.captionTextStyle)
                Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton("上一页记录", { exports.unverifiedPage(state.unverifiedPage - 1) }, enabled = !state.busy && state.unverifiedPage > 0)
                    EsvButton("下一页记录", { exports.unverifiedPage(state.unverifiedPage + 1) }, enabled = !state.busy && state.unverifiedPage + 1 < state.unverifiedPages)
                }
            }
            EsvButton("关闭列表", { showingUnverified = false }, modifier = Modifier.fillMaxWidth())
        }
    }
}
