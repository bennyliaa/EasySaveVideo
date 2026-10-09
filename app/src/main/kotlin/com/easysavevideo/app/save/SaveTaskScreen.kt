package com.easysavevideo.app.save

import com.easysavevideo.library.MediaLifecycle
import com.easysavevideo.app.library.destination.OrdinaryDestinationHistory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.browser.SystemBrowserHost
import com.easysavevideo.app.theme.*
import com.easysavevideo.app.recording.recordingAudioOutcomeLabel
import com.easysavevideo.app.recording.recordingDurationLabel
import com.easysavevideo.app.recording.recordingStopReasonLabel
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.save.*
import java.net.URI

@Composable
fun SaveTaskScreen(
    runtime: AndroidSaveRuntime,
    browser: SystemBrowserHost,
    onRecord: (() -> Unit)? = null,
    onPreview: ((SaveRecord) -> Unit)? = null,
    onLibrary: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val v = LocalEsvVisuals.current
    BackHandler(onBack = onBack)
    EsvBackdrop(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().systemBarsPadding(), contentPadding = PaddingValues(v.spacingMedium),
            verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText("保存任务", style = v.titleTextStyle, modifier = Modifier.weight(1f))
                EsvButton("返回网页", onClick = onBack)
            }
            onLibrary?.let { open -> EsvButton("打开媒体库", onClick = open, modifier = Modifier.fillMaxWidth()) }
            onRecord?.let { open -> EsvButton("③ 录制 / 查看当前录制", onClick = open, modifier = Modifier.fillMaxWidth()) }
            EsvText("① 直接下载 · 免费；② 静态 HLS、③ 录制 · 需对应权益 · 开发版本", style = v.captionTextStyle)
            EsvText("①②仅 HTTPS 兼容 MP4 或受限静态 HLS。网络保存开发限额：单文件 2 GiB，每任务累计传输预算 4 GiB；每次手动执行最多 2 小时，校验最多 60 秒；最多同时保留 100 条未完成任务。", style = v.captionTextStyle, color = v.colors.muted)
            EsvText("①②切换或关闭标签不会取消已开始的任务；应用退到后台会暂停网络保存，回到前台后需手动继续。重启后需重新选择未下载完的来源，流媒体中断需重新选择。", style = v.captionTextStyle, color = v.colors.muted)
            EsvText("③通过每次系统授权独立录制，不随网络下载的后台暂停命令结束。切换页面可能改变捕获画面，隐藏网页播放没有保证。只有主动选择③才会请求录制，下载失败不会自动换用录制。", style = v.captionTextStyle, color = v.colors.muted)
            EsvText("②仅无加密静态 TS 点播、固定清晰度、连续 AVC Baseline + 可选 AAC-LC；不支持直播、DASH、分轨、fMP4、转码或格式变化。各片需独立关键帧与重复格式头，末片需现有 AUD/EOS 结束标记；多数普通 HLS 不符合此严格范围。缓存不是完成视频；需独立 MP4 校验。设备验证尚未执行。", style = v.captionTextStyle, color = v.colors.muted)
            EsvText("流媒体额外限制：最多 10,000 片、每片 128 MiB，清单单份 2 MiB/累计 8 MiB；下载前保留 2 GiB 输出空间。", style = v.captionTextStyle, color = v.colors.muted)
            if (runtime.draining) EsvText(if (runtime.networkDraining) "系统资源未能确认关闭。已保留文件；请完全退出应用后重新打开并恢复。"
                else "系统媒体资源尚在退出。迟到结果不会保存；资源确认关闭前不能开始新的录制或校验。", color = v.colors.error)
            runtime.notice?.let { text ->
                EsvText(text, color = v.colors.error)
                EsvButton("关闭提示", onClick = runtime::dismissNotice)
            }
            runtime.destinations?.let { OrdinaryDestinationHistory(it) }
            if (!runtime.ready) EsvText("保存存储尚未就绪；浏览器仍可使用。")
            else if (runtime.records.isEmpty()) EsvText("还没有保存任务。可回网页选择视频保存，或主动打开③录制。")
            } }
                items(runtime.records, key = { it.task.taskId }) { record ->
                    Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingMedium),
                        verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                        EsvText("${saveMethodLabel(record.request.method)} · ${taskLabel(record)}", style = v.titleTextStyle)
                        EsvText("任务 ${record.task.taskId.take(8)}", style = v.captionTextStyle, color = v.colors.muted)
                        if (record.task.state == TaskState.SUCCEEDED) {
                            runtime.discoverySaveOutcomes[record.task.taskId]?.let { outcome ->
                                EsvText(mediaTaskDiscoveryOutcomeLabel(outcome), style = v.captionTextStyle, color = v.colors.muted)
                            }
                            val evidence = record.verification
                            val disposition = record.receipt?.let { runtime.mediaDispositions[it.mediaId] }
                            EsvText(when (disposition) {
                                MediaLifecycle.READY -> "保存时已验证 MP4，当前仍在普通媒体库。"
                                MediaLifecycle.DELETE_PENDING -> "保存已完成；此成片已从列表移除，正在等待安全删除。"
                                MediaLifecycle.DELETED -> "保存已完成；此成片后来已删除，不会重新生成。"
                                null -> "保存已完成；当前成片不可用，请到媒体库刷新查看。"
                            })
                            if (evidence != null) EsvText("${formatBytes(evidence.sizeBytes)} · ${evidence.width} × ${evidence.height} · ${evidence.durationMillis / 1000} 秒 · 音轨 ${evidence.audioTrackCount}", style = v.captionTextStyle)
                            onPreview?.let { preview -> EsvButton("预览已验证成片", onClick = { preview(record) }, enabled = disposition == MediaLifecycle.READY && runtime.currentRecordingId == null && !runtime.draining) }
                            EsvText("此处保留保存完成记录。重命名、移动或删除请到媒体库；已删除成片不会由此记录重新生成。", style = v.captionTextStyle, color = v.colors.muted)
                        } else if (record.task.state in setOf(TaskState.RUNNING, TaskState.WAITING_USER, TaskState.QUEUED)) {
                            val checkpoint = record.checkpoint
                            val bytes = record.transfer?.bytesOnDisk
                            EsvText(if (bytes != null) {
                                    saveArtifactProgressLabel(record.request.method, formatBytes(bytes))
                                }
                                else if (checkpoint != null) "已下载 ${formatBytes(checkpoint.offset)} / ${formatBytes(checkpoint.totalBytes)}"
                                else if (record.request.method == TaskMethod.RECORD) "录制不显示下载百分比；当前录制状态与有效时长请打开③查看。"
                                else "大小或下载进度未知", style = v.captionTextStyle)
                        }
                        record.transfer?.recordingExpectation?.let { recording ->
                            EsvText("有效录制时长 ${recordingDurationLabel(recording.effectiveDurationUs)} · ${recording.actualProfile.width} × ${recording.actualProfile.height}", style = v.captionTextStyle)
                            EsvText(recordingAudioOutcomeLabel(recording.audioOutcome), style = v.captionTextStyle)
                            EsvText(recordingStopReasonLabel(recording.stopReason), style = v.captionTextStyle)
                        }
                        if (record.request.method == TaskMethod.RECORD && !record.task.state.isTerminal) onRecord?.let { open ->
                            EsvButton("打开③录制控制", onClick = open)
                        }
                        record.error?.let { EsvText(saveErrorText(it), color = v.colors.error, style = v.captionTextStyle) }
                        if (record.task.state == TaskState.WAITING_USER) EsvText(if (record.request.method == TaskMethod.RECORD && record.transfer == null)
                            "录制会话已中断，旧系统授权不能继续使用。可放弃此任务，然后主动申请新的录制会话。"
                        else when (record.waitReason) {
                            SaveWaitReason.BACKGROUND -> "应用退到后台，任务已暂停。请保持前台并手动继续。"
                            SaveWaitReason.AUTHENTICATION -> "需要有效会话。先打开并登录资源同源页面，再手动继续。"
                            SaveWaitReason.NETWORK -> "网络暂不可用。恢复网络后可手动继续。"
                            SaveWaitReason.STORAGE -> "请腾出存储空间后手动继续。"
                            else -> "中断的任务已保留。手动继续将重新检查文件与提交状态。"
                        }, style = v.captionTextStyle)
                        if (record.task.taskId in runtime.pendingCommands) EsvText("正在处理操作…", style = v.captionTextStyle)
                        Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                            if (record.task.state == TaskState.WAITING_USER &&
                                (record.request.method != TaskMethod.RECORD || record.transfer != null)) EsvButton(if (record.request.method == TaskMethod.RECORD) "重新检查本地成片" else "继续", onClick = { runtime.resume(record.task.taskId, browser) },
                                enabled = !runtime.draining && record.task.taskId !in runtime.pendingCommands)
                            if (!record.task.state.isTerminal) EsvButton("取消任务", onClick = { runtime.cancel(record.task.taskId) },
                                enabled = record.task.taskId !in runtime.pendingCommands)
                        }
                    }
                }
        }
    }
}

@Composable
fun SaveSelectionDialog(runtime: AndroidSaveRuntime, browser: SystemBrowserHost) {
    val v = LocalEsvVisuals.current
    if (runtime.preparingStream || runtime.hlsPreparation != null) {
        HlsSelectionDialog(runtime)
        return
    }
    val selection = runtime.selection ?: return
    Dialog(onDismissRequest = runtime::dismissSelection) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
            .verticalScroll(rememberScrollState()).padding(v.spacingLarge), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            EsvText("选择要保存的资源", style = v.titleTextStyle)
            EsvText("页面候选可能包含广告或其他片段，请核对后选择。清晰度与时长来自页面，保存前还会进行本地验证。", style = v.captionTextStyle)
            EsvText("①免费直接下载兼容 MP4；②需流媒体权益。HLS 清单检查后须明确选择固定清晰度。短期 Cookie 只发往原始同源资源，跳转与子资源不携带凭证。", style = v.captionTextStyle)
            selection.candidates.forEach { candidate ->
                Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvText(candidate.label)
                    EsvText(buildList {
                        add(URI(candidate.url.value).host ?: "未知来源")
                        add(if (candidate.width != null && candidate.height != null) "${candidate.width} × ${candidate.height}" else "清晰度未知")
                        add(candidate.durationMillis?.let { "${it / 1000} 秒" } ?: "时长未知")
                    }.joinToString(" · "), style = v.captionTextStyle, color = v.colors.muted)
                    EsvButton(when {
                        candidate.protected -> "受保护内容不可保存"
                        !candidate.supported -> "此格式尚未支持"
                        !runtime.canSave(candidate.method) -> "②需要流媒体权益"
                        candidate.method == TaskMethod.STREAM -> "检查②静态 HLS"
                        else -> "开始①直接下载"
                    },
                        onClick = { runtime.choose(candidate, browser) },
                        enabled = candidate.supported && !candidate.protected && runtime.canSave(candidate.method) && selection.isCurrent())
                }
            }
            EsvButton("取消", onClick = runtime::dismissSelection)
        }
    }
}
@Composable
private fun HlsSelectionDialog(runtime: AndroidSaveRuntime) {
    val v = LocalEsvVisuals.current
    val prepared = runtime.hlsPreparation
    Dialog(onDismissRequest = runtime::dismissSelection) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
            .verticalScroll(rememberScrollState()).padding(v.spacingLarge), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            EsvText("②选择固定流媒体资源", style = v.titleTextStyle)
            EsvText("仅支持无加密静态 TS 点播、AVC Baseline + 可选 AAC-LC。下列清晰度是清单声明；实际媒体仍须检查。不会自动切换清晰度或转码。", style = v.captionTextStyle)
            if (prepared == null) EsvText("正在限额内检查清单，尚未建立保存任务…")
            else {
                EsvText("已检查清单 ${formatBytes(prepared.bytesTransferred)}；这部分流量计入任务预算。选择有效期最多 60 秒，不延长原执行时限。", style = v.captionTextStyle)
                if (prepared.directMedia) EsvButton("保存此固定媒体清单", onClick = { runtime.chooseHlsVariant(null) },
                    enabled = runtime.canSave(TaskMethod.STREAM))
                prepared.variants.forEach { variant ->
                    val metadata = variant.metadata
                    val size = if (metadata.width != null && metadata.height != null) "${metadata.width} × ${metadata.height}" else "清晰度未声明"
                    EsvButton("${variant.index + 1}. $size · ${metadata.bandwidth / 1000} kbps", onClick = { runtime.chooseHlsVariant(variant.identity) },
                        enabled = runtime.canSave(TaskMethod.STREAM))
                }
            }
            EsvButton("取消", onClick = runtime::dismissSelection)
        }
    }
}

internal fun saveMethodLabel(method: TaskMethod): String = when (method) {
    TaskMethod.BROWSER -> "浏览任务"
    TaskMethod.DIRECT -> "① 直接下载"
    TaskMethod.STREAM -> "② 流媒体"
    TaskMethod.RECORD -> "③ 录制"
    TaskMethod.EDIT -> "编辑任务"
}
internal fun saveExecutionLabel(method: TaskMethod): String = when (method) {
    TaskMethod.BROWSER -> "浏览处理中"
    TaskMethod.DIRECT -> "下载中"
    TaskMethod.STREAM -> "下载 / 封装中"
    TaskMethod.RECORD -> "录制会话处理中"
    TaskMethod.EDIT -> "编辑处理中"
}
internal fun saveArtifactProgressLabel(method: TaskMethod, size: String): String = when (method) {
    TaskMethod.DIRECT -> "已传输 $size，等待验证或提交。"
    TaskMethod.STREAM -> "已生成 $size 的独立 MP4，等待验证或提交。"
    TaskMethod.RECORD -> "已关闭 $size 的录制 MP4，等待验证或提交；尚未保存成功。"
    TaskMethod.BROWSER, TaskMethod.EDIT -> "已准备 $size，等待验证或提交。"
}

internal fun taskLabel(record: SaveRecord): String = when (record.task.state) {
    TaskState.QUEUED -> "排队中"
    TaskState.RUNNING -> when (record.phase) {
        SavePhase.PREPARING -> "准备中"
        SavePhase.EXECUTING -> saveExecutionLabel(record.request.method)
        SavePhase.VERIFYING -> "本地验证中"
        SavePhase.COMMITTING -> "提交中"
        SavePhase.CLEANUP -> "收尾中"
        SavePhase.CANCELLING -> "正在取消"
        else -> "处理中"
    }
    TaskState.WAITING_USER -> "等待操作"
    TaskState.INTERRUPTED -> "已中断"
    TaskState.SUCCEEDED -> "完成"
    TaskState.FAILED -> "失败"
    TaskState.CANCELLED -> "已取消"
}
internal fun saveErrorText(error: SaveError): String = when (error) {
    SaveError.CANCELLED -> "任务已取消，不会自动换用其他保存方式。"
    SaveError.AUTHENTICATION_REQUIRED -> "资源需要有效的登录会话或访问权限。"
    SaveError.STORAGE_FULL -> "可用存储空间不足。"
    SaveError.STORAGE_FAILURE, SaveError.JOURNAL_UNAVAILABLE -> "私有存储暂时不可用，未清除已有媒体。"
    SaveError.NETWORK_TIMEOUT, SaveError.DEADLINE_EXCEEDED -> "操作超时，迟到结果不会提交。"
    SaveError.NETWORK_UNAVAILABLE, SaveError.RETRY_EXHAUSTED -> "网络未能在有限重试内完成，请稍后重试。"
    SaveError.TLS_REJECTED -> "无法验证网站安全连接，未绕过证书检查。"
    SaveError.POLICY_BLOCKED -> "资源地址或连接不符合安全限制。"
    SaveError.BYTE_LIMIT_EXCEEDED -> "已达到本开发版本的文件或累计传输预算。"
    SaveError.PROTECTED_MEDIA -> "检测到受保护内容，不支持保存。"
    SaveError.UNSUPPORTED_MEDIA, SaveError.METHOD_UNSUPPORTED -> "该媒体或保存方式不符合本开发版本的兼容范围。网络保存不支持直播、加密、分轨、格式变化或转码。"
    SaveError.INVALID_MEDIA, SaveError.INCOMPLETE_TRANSFER -> "文件不完整或未通过真实媒体验证，没有作为成功视频入库。"
    SaveError.INVALID_RANGE, SaveError.RESOURCE_CHANGED -> "源资源或断点身份不一致，没有拼接不匹配的字节。"
    SaveError.EXPIRED_RESOURCE -> "候选资源已过期，请回到网页重新检测。"
    SaveError.RESOURCE_RELEASE_UNCONFIRMED -> "系统资源尚未确认退出，任务文件保留，迟到结果不会提交。"
    SaveError.VERIFICATION_UNAVAILABLE -> "系统媒体校验资源尚未可用，请稍后重试。"
    SaveError.COMMIT_MISMATCH, SaveError.RECOVERY_REQUIRED -> "提交状态需要重新核验，文件已保留供恢复。"
    SaveError.RATE_LIMITED -> "网站限制了请求，请稍后重试。"
    SaveError.SOURCE_RESELECTION_REQUIRED -> "短期来源上下文已清除。请回网页重新检测并选择；新任务从头下载。"
    SaveError.HTTP_NOT_FOUND -> "资源已不存在（404），请回网页重新选择。"
    SaveError.HTTP_REJECTED -> "网站拒绝了此资源请求，未自动换用其他保存方式。"
    SaveError.HTTP_FAILURE -> "网站未返回可保存的完整媒体。"
    SaveError.BUSY -> "已有任务处理中，请稍后再试。"
    SaveError.CONSENT_DENIED -> "未获得本次系统录制授权，没有开始录制。"
    SaveError.AUDIO_PERMISSION_DENIED -> "内部声音权限未允许；必须明确选择无声音重新开始，或取消。"
    SaveError.CAPTURE_UNAVAILABLE, SaveError.FGS_START_REJECTED -> "系统暂时无法开始本次录制；不会重复使用旧授权。"
    SaveError.PROFILE_UNSUPPORTED -> "设备编码器不支持本次录制配置。"
    SaveError.CAPTURE_EMPTY, SaveError.CAPTURE_TOO_SHORT -> "有效录制样本为空或太短，未发布成片。"
    SaveError.KEYFRAME_TIMEOUT -> "等待录制关键帧超时，未确认完整成片。"
    SaveError.TIMESTAMP_INVALID -> "录制媒体时间线不符合要求，未发布成片。"
    SaveError.AUDIO_FAILED -> "内部播放音频采集失败；没有自动改用麦克风或无声音。"
    SaveError.ENCODER_FAILED -> "系统录制编码器失败，未确认保存成功。"
    SaveError.FINALIZATION_FAILED -> "录制文件未完整收尾，未发布成片。"
    else -> "任务未完成，请检查页面与存储后重试。"
}
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KiB"
}
