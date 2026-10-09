package com.easysavevideo.app.library.destination

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
import com.easysavevideo.library.destination.*

@Composable
fun OrdinaryDestinationSettings(runtime: AndroidOrdinaryDestinationRuntime) {
    val v = LocalEsvVisuals.current
    EsvText("普通媒体保存位置", style = v.titleTextStyle)
    EsvText("适用于之后新建的直接下载、静态 HLS、基础 / 高级录制和编辑成片。始终先在 App媒体库验证保存；另存相册或所选文件夹都是额外副本，成功、失败或取消都保留 App媒体库原件。", style = v.captionTextStyle)
    OrdinaryDestinationPreference.entries.forEach { value ->
        EsvButton((if (runtime.preference == value) "已选：" else "") + ordinaryDestinationLabel(value),
            onClick = { runtime.updatePreference(value) }, enabled = runtime.ready && !runtime.savingPreference && !runtime.choosingFolder,
            modifier = Modifier.fillMaxWidth())
    }
    EsvText("所选文件夹由系统文件提供方管理，可能同步到云端；写入中的副本可能提前可见。需要明确选择并记住持久读写授权，随后每次创建前都会重新核对。", style = v.captionTextStyle)
    EsvButton(if (runtime.hasRememberedFolder) "重新选择文件夹" else "选择并记住文件夹", onClick = runtime::requestFolder,
        enabled = runtime.ready && !runtime.savingPreference && !runtime.choosingFolder)
    if (runtime.hasRememberedFolder) EsvButton("停止记住文件夹", onClick = runtime::forgetFolder,
        enabled = runtime.ready && !runtime.savingPreference && !runtime.choosingFolder)
    if (runtime.choosingFolder) EsvButton("取消更改文件夹", onClick = runtime::cancelFolderSelection)
    EsvText("更改设置只影响之后新建的媒体，不改变已接受任务，也不复制历史媒体。切换或停止记住文件夹不会撤销旧系统授权，以便已有任务或未确认副本继续处理和清理；可在系统设置管理授权，撤销后可能需要手动清理。保险箱仍未启用。", style = v.captionTextStyle, color = v.colors.muted)
    OrdinaryDestinationNotice(runtime)
}

@Composable
fun OrdinaryDestinationNotice(runtime: AndroidOrdinaryDestinationRuntime) {
    val v = LocalEsvVisuals.current
    runtime.notice?.let {
        EsvText(it, style = v.captionTextStyle)
        EsvButton("关闭保存位置提示", onClick = runtime::clearNotice)
    }
    if (runtime.admitting) EsvText("正在保存本次位置选择，尚未开始创建媒体…", style = v.captionTextStyle)
}

@Composable
fun OrdinaryDestinationDialog(runtime: AndroidOrdinaryDestinationRuntime) {
    if (!runtime.asking) {
        val notice = runtime.notice ?: return
        val v = LocalEsvVisuals.current
        Dialog(onDismissRequest = runtime::clearNotice) {
            Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingLarge),
                verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                EsvText("普通媒体保存位置", style = v.titleTextStyle)
                EsvText(notice)
                EsvButton("知道了", onClick = runtime::clearNotice)
            }
        }
        return
    }
    val v = LocalEsvVisuals.current
    Dialog(onDismissRequest = runtime::cancelSelection) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
            .verticalScroll(rememberScrollState()).padding(v.spacingLarge), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            EsvText("本次普通媒体保存位置", style = v.titleTextStyle)
            EsvText("先验证保存到 App媒体库。选择相册或已记住文件夹会再复制一份，之后更改设置不会改变本次选择；所有结果都保留 App媒体库原件。")
            EsvButton("App媒体库", onClick = { runtime.choose(OrdinaryDestinationChoice.APP_LIBRARY) }, modifier = Modifier.fillMaxWidth())
            EsvButton("另存一份到相册", onClick = { runtime.choose(OrdinaryDestinationChoice.ALBUM_COPY) }, modifier = Modifier.fillMaxWidth())
            EsvButton("App媒体库并另存一份到已记住文件夹", onClick = { runtime.choose(OrdinaryDestinationChoice.FOLDER_COPY) },
                enabled = runtime.hasRememberedFolder, modifier = Modifier.fillMaxWidth())
            EsvText("文件提供方可能同步到云端；尚未选择文件夹时，请先到保存位置设置中选择。", style = v.captionTextStyle)
            EsvButton("取消，不创建媒体", onClick = runtime::cancelSelection, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun OrdinaryDestinationHistory(runtime: AndroidOrdinaryDestinationRuntime) {
    val v = LocalEsvVisuals.current
    EsvText("本次保存与额外副本", style = v.titleTextStyle)
    OrdinaryDestinationNotice(runtime)
    runtime.statuses.filter { it.internalSaved }.take(8).forEach { status ->
        EsvText("任务 ${status.creationId.take(8)} · App媒体库：创建时已验证入库", style = v.captionTextStyle)
        EsvText(ordinaryCopyStatus(status), style = v.captionTextStyle)
        if (ordinaryCopyCanCancel(status)) EsvButton("取消此额外副本", onClick = { runtime.cancelCopy(status.creationId) })
    }
    EsvText("以上是创建时记录，后续重命名或删除不会重新复制。尚未入库的任务请查看各自进度。", style = v.captionTextStyle, color = v.colors.muted)
    EsvButton("恢复核对额外副本", onClick = runtime::reconcile)
}
