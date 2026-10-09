package com.easysavevideo.app.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.discovery.AndroidDiscoveryController
import com.easysavevideo.app.discovery.BuildDiscovery
import com.easysavevideo.app.discovery.DiscoveryPresentationPolicy
import com.easysavevideo.app.discovery.DiscoveryControlRequest
import com.easysavevideo.app.discovery.DiscoveryLocalAction
import com.easysavevideo.app.discovery.DiscoveryFilterDraft
import com.easysavevideo.app.discovery.DiscoveryFilterClaim
import com.easysavevideo.app.discovery.DiscoveryFilterTextField
import com.easysavevideo.app.discovery.DiscoveryFilterPresentationPolicy
import com.easysavevideo.app.discovery.RightsPresentationPolicy
import com.easysavevideo.app.theme.*
import com.easysavevideo.core.model.*
import com.easysavevideo.discovery.*
import kotlinx.coroutines.delay

@Composable
fun DiscoveryScreen(controller: AndroidDiscoveryController, settings: DiscoverySettings, active: Boolean,
    onSettings: (DiscoverySettings) -> Unit, onBrowser: () -> Unit, onOpen: (PageSnapshot, VideoSearchResult) -> Unit,
    onLink: (String) -> Unit, onLibrary: () -> Unit, onManageProviders: () -> Unit) {
    val v = LocalEsvVisuals.current
    val state = controller.state
    var options by remember { mutableStateOf(false) }
    var showPending by remember { mutableStateOf(false) }
    var localControlConfirm by remember { mutableStateOf<DiscoveryControlRequest?>(null) }
    var safeDisableConfirm by remember { mutableStateOf<DiscoveryFilterClaim?>(null) }
    var rightsDetails by remember { mutableStateOf<VideoSearchResult?>(null) }
    val storedDraft = controller.filterDraft
    val appliedReadable = state.ready && !state.storageUnavailable
    val draft = DiscoveryFilterPresentationPolicy.reconcile(storedDraft, state.profile, state.demo, appliedReadable)
    val editClaim = state.controlTarget?.let { DiscoveryFilterClaim(it, state.demo, draft) }
    val validation = DiscoveryFilterPresentationPolicy.validate(draft)
    val choices = DiscoveryFilterPresentationPolicy.admittedSources(state.admittedProviderDescriptors, state.demo)
    val editable = state.ready && !state.storageUnavailable && !controller.filterControlsBusy &&
        storedDraft == draft && editClaim != null
    val canSearch = editable && DiscoveryFilterPresentationPolicy.canSubmit(draft)
    SideEffect { if (appliedReadable && storedDraft != draft) controller.updateFilterDraft(storedDraft, draft) }
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()
    LaunchedEffect(options, state.controlTarget) {
        if (options && state.controlTarget != null) controller.refreshHistoryStatistics(state.controlTarget)
    }
    LaunchedEffect(state.controlTarget, state.storageUnavailable) {
        if (localControlConfirm?.target != state.controlTarget || state.storageUnavailable) localControlConfirm = null
    }
    fun currentClaimValid(claim: DiscoveryFilterClaim): Boolean {
        val current = controller.state
        return DiscoveryFilterPresentationPolicy.validClaim(claim, controller.filterDraft, current.controlTarget,
            current.profile, current.demo, current.ready, current.storageUnavailable, controller.filterControlsBusy)
    }
    fun edit(value: DiscoveryFilterDraft) {
        val claim = editClaim ?: return
        if (currentClaimValid(claim)) controller.updateFilterDraft(storedDraft, value)
    }
    fun editText(field: DiscoveryFilterTextField, value: String) {
        val claim = editClaim ?: return
        controller.editFilterText(claim, field, value)
    }
    fun search() {
        if (controller.submitFilterDraft(draft, state.controlTarget)) focus.clearFocus()
    }
    val page = state.page
    LaunchedEffect(page?.stamp, page?.revision, page?.pageIndex) { grid.scrollToItem(0) }
    LaunchedEffect(page?.stamp, page?.revision, page?.pageIndex, active, showPending, options, localControlConfirm, safeDisableConfirm, rightsDetails) {
        if (page == null || !active || showPending || options || localControlConfirm != null || safeDisableConfirm != null || rightsDetails != null) return@LaunchedEffect
        val tracker = ShownVisibilityTracker()
        val results = state.results.associateBy { it.resultId }
        while (true) {
            val layout = grid.layoutInfo
            val visible = layout.visibleItemsInfo.associateBy { it.key.toString() }
            val now = SystemClock.elapsedRealtime()
            results.forEach { (id, result) ->
                val item = visible[id]
                val fraction = if (item == null) 0.0 else DiscoveryPresentationPolicy.fraction(
                    item.offset.x, item.offset.y, item.size.width, item.size.height, layout.viewportSize.width,
                    layout.viewportStartOffset, layout.viewportEndOffset, active, !options && localControlConfirm == null && safeDisableConfirm == null && rightsDetails == null)
                if (tracker.update(id, fraction.coerceIn(0.0, 1.0), now)) controller.recordSeen(page, result, SeenEvent.SHOWN)
            }
            delay(100)
        }
    }
    EsvBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(v.spacingMedium), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvThemeIcon(null, Modifier.size(v.iconSize))
                EsvText("视频发现", style = v.titleTextStyle, modifier = Modifier.weight(1f))
                EsvButton("浏览器", onClick = onBrowser)
                EsvButton("媒体库", onClick = onLibrary)
                EsvButton("选项", onClick = { options = true })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                BasicTextField(draft.query, onValueChange = { editText(DiscoveryFilterTextField.QUERY, it) }, singleLine = true,
                    enabled = editable,
                    modifier = Modifier.weight(1f).border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
                        .background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingMedium).semantics { contentDescription = "视频关键词" },
                    textStyle = v.bodyTextStyle.copy(color = v.colors.text), cursorBrush = SolidColor(v.colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                    decorationBox = { input -> Box { if (draft.query.isEmpty()) EsvText("输入视频关键词", color = v.colors.muted); input() } })
                EsvButton("查找", onClick = { search() }, enabled = canSearch)
            }
            if (controller.filterSearchPending) EsvText("正在应用本次查找；等待本次操作确认。", style = v.captionTextStyle)
            else if (appliedReadable && DiscoveryFilterPresentationPolicy.isDirty(draft)) EsvText(
                "草稿尚未应用；当前结果仍对应已应用关键词“${state.profile.query}”及其筛选。", style = v.captionTextStyle)
            validation.error?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.error) }
            if (state.demo) EsvText("演示数据 · 本地合成，非真实搜索。点击只测试网页导航。", color = v.colors.error, style = v.captionTextStyle)
            if (!state.demo && state.commonsConfigured && state.commonsEnabled) EsvText("Wikimedia Commons · 开放媒体资料库", style = v.captionTextStyle)
            if (appliedReadable && !state.demo && state.profile.filters.safeSearch && state.commonsConfigured && state.commonsEnabled) EsvText(
                "Wikimedia Commons 暂不支持安全搜索。保持开启时，不会向该来源发送查询。", style = v.captionTextStyle, color = v.colors.error)
            EsvText(statusText(state.status, state.storageUnavailable, state.ready), style = v.captionTextStyle, color = v.colors.muted)
            if (state.saveActive || state.recordingActive) EsvText(
                if (state.recordingActive) "实际录制或录制资源仍在运行，发现补充已暂停；离开录制页面不会解除暂停。"
                else "实际保存、准备或资源清理仍在运行，发现补充已暂停；离开任务页面不会解除暂停。",
                style = v.captionTextStyle, color = v.colors.muted)
            if (state.status?.state == DiscoveryState.CAPACITY_REACHED) EsvText(
                "为保护已生成页面及其历史和来源进度，本次写入未越过本地容量上限。", style = v.captionTextStyle)
            state.notice?.let { EsvText(it, style = v.captionTextStyle) }
            if (state.needsExplicitSearch) EsvText("来源配置已变化，旧结果和续页已失效。请核对来源与筛选，然后主动点击查找。", style = v.captionTextStyle)
            if (state.pendingCandidates.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvButton("已核验来源结果 (${state.results.size})", onClick = { showPending = false })
                EsvButton("待验证网页 (${state.pendingCandidates.size})", onClick = { showPending = true })
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val columns = if (maxWidth >= 720.dp) maxOf(settings.columns, (maxWidth.value / 220).toInt().coerceIn(2, 6)) else settings.columns
                if ((showPending || state.results.isEmpty()) && state.pendingCandidates.isNotEmpty()) {
                    PendingCandidateCards(state.pendingCandidates, columns) { candidate ->
                        controller.candidateUrl(state.controlTarget, candidate)?.let(onLink)
                    }
                } else if (state.results.isEmpty()) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(v.spacingLarge),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    EsvText(if (state.storageUnavailable) "发现数据暂不可用" else if (state.status?.state == DiscoveryState.NO_SOURCES) "当前无已启用来源" else "暂无符合条件的结果", style = v.titleTextStyle)
                    Spacer(Modifier.height(v.spacingMedium))
                    EsvText(if (state.storageUnavailable) "本地原始数据已保留。" else if (state.status?.state == DiscoveryState.NO_SOURCES) "来源未配置或已关闭。仍可直接使用浏览器。" else if (state.profile.filters.safeSearch && !state.demo) "当前来源的安全搜索能力可能不可核验；保持开启时不会向不支持的来源发送查询。请在选项查看说明。" else "输入关键词查找开放媒体资料；可调整筛选或重复策略后重试。", color = v.colors.muted)
                    Spacer(Modifier.height(v.spacingMedium))
                    EsvButton("输入网址浏览", onClick = onBrowser)
                } else LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    items(state.results, key = { it.resultId }) { result ->
                        Column(Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
                            .background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(v.backdrop, RoundedCornerShape(v.corner)), contentAlignment = Alignment.Center) {
                                EsvThemeIcon("视频封面占位", Modifier.size(v.iconSize))
                            }
                            EsvText(result.title ?: "标题未知", style = v.bodyTextStyle)
                            EsvText(result.sourceName, style = v.captionTextStyle, color = v.colors.muted)
                            if (result.provenance.any { it.startsWith("searxng-") }) EsvText(
                                "网页视频证据；播放、下载及保存权限尚未验证", style = v.captionTextStyle, color = v.colors.muted)
                            EsvText(result.durationMs?.let { "${it / 60_000}:${(it / 1_000 % 60).toString().padStart(2, '0')}" } ?: "时长未知", style = v.captionTextStyle)
                            RightsPresentationPolicy.summary(result.rights).forEach { EsvText(it, style = v.captionTextStyle) }
                            EsvButton("完整来源与许可资料", onClick = { rightsDetails = result }, modifier = Modifier.fillMaxWidth())
                            EsvButton("打开网页", onClick = { page?.let { onOpen(it, result) } }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
                EsvButton("上一页", onClick = { controller.movePage(-1) }, enabled = state.pageIndex > 0)
                EsvText("第 ${state.pageIndex + 1} 页", style = v.captionTextStyle)
                EsvButton("下一页", onClick = { controller.movePage(1) }, enabled = state.results.isNotEmpty())
                EsvButton("重试 / 补充", onClick = controller::retry, enabled = state.ready && !state.storageUnavailable)
            }
        }
    }
    if (options) Dialog(onDismissRequest = { options = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
            .padding(v.spacingLarge).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            EsvText("发现选项", style = v.titleTextStyle)
            EsvButton("完成", onClick = { options = false })
            state.notice?.let { EsvText(it, style = v.captionTextStyle) }
            EsvText("本地排序：保留当前页，对后续页生效", style = v.captionTextStyle)
            ResultSort.entries.forEach { order ->
                EsvButton((if (state.profile.sortOrder == order) "✓ " else "") + DiscoveryPresentationPolicy.sortLabel(order),
                    onClick = { controller.updateSort(order) }, enabled = state.ready && !state.storageUnavailable)
            }
            EsvText("未知日期或时长排后；优先新内容策略先分已展示与未展示。来源顺序不代表统一相关度分数。", style = v.captionTextStyle)
            EsvText("重复策略", style = v.captionTextStyle)
            RepeatPolicy.entries.forEach { policy -> EsvButton((if (settings.repeatPolicy == policy) "✓ " else "") + policyText(policy), onClick = { onSettings(settings.copy(repeatPolicy = policy)) }) }
            EsvText("每页 ${settings.pageSize}；手机 ${settings.columns} 列，平板随宽度增加。", style = v.captionTextStyle)
            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvButton("每页－", onClick = { onSettings(settings.copy(pageSize = (settings.pageSize - 10).coerceAtLeast(10))) }, enabled = settings.pageSize > 10)
                EsvButton("每页＋", onClick = { onSettings(settings.copy(pageSize = (settings.pageSize + 10).coerceAtMost(100))) }, enabled = settings.pageSize < 100)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvButton("列－", onClick = { onSettings(settings.copy(columns = settings.columns - 1)) }, enabled = settings.columns > 1)
                EsvButton("列＋", onClick = { onSettings(settings.copy(columns = settings.columns + 1)) }, enabled = settings.columns < 6)
            }
            DiscoveryMode.entries.forEach { mode -> EsvButton((if (settings.mode == mode) "✓ " else "") + when(mode) {
                DiscoveryMode.POWER_SAVING -> "省电：并发 1"; DiscoveryMode.BALANCED -> "平衡：并发 2"; DiscoveryMode.AGGRESSIVE -> "积极：并发最多 3"
            }, onClick = { onSettings(settings.copy(mode = mode)) }) }
            DiscoveryPowerControls(state, controller)
            EsvText("已应用筛选", style = v.titleTextStyle)
            if (appliedReadable) {
                EsvText("来源：${DiscoveryFilterPresentationPolicy.sourceSummary(state.profile.filters, choices)}", style = v.captionTextStyle)
                EsvText(DiscoveryFilterPresentationPolicy.durationSummary(state.profile.filters), style = v.captionTextStyle)
                EsvText("已应用安全搜索：${if (state.profile.filters.safeSearch) "开" else "关"}", style = v.captionTextStyle)
            } else EsvText("已应用关键词与筛选暂不可核对；未按默认值替换已保存资料。", style = v.captionTextStyle)
            EsvText("筛选草稿", style = v.titleTextStyle)
            EsvText("关键词与以下筛选仅在点击“查找”或“应用草稿查找”后一起生效。关闭选项或切换页面会保留草稿；应用重建或重启后恢复已应用资料。", style = v.captionTextStyle)
            EsvButton((if (draft.sourceNamespaces.isEmpty()) "✓ " else "") + "全部当前启用来源", onClick = {
                edit(draft.copy(sourceNamespaces = emptySet()))
            }, enabled = editable)
            if (choices.isEmpty()) EsvText("当前注册表没有可选来源；已保存的来源筛选仍原样保留。", style = v.captionTextStyle)
            choices.forEach { choice ->
                val selected = DiscoveryFilterPresentationPolicy.isSelected(draft.sourceNamespaces, choice.namespace)
                EsvButton((if (selected) "✓ " else "加入筛选：") + "${choice.label} [${choice.namespace}]", onClick = {
                    edit(DiscoveryFilterPresentationPolicy.addSource(draft, choice))
                }, enabled = editable && !selected && draft.sourceNamespaces.size < 100)
                if (selected) EsvButton(if (draft.sourceNamespaces.size == 1) "移除此限制，恢复全部来源" else "移除 ${choice.namespace} 限制", onClick = {
                    edit(DiscoveryFilterPresentationPolicy.removeSource(draft, choice.namespace))
                }, enabled = editable)
            }
            DiscoveryFilterPresentationPolicy.unavailableNamespaces(draft.sourceNamespaces, choices).forEach { namespace ->
                EsvText("$namespace：未启用、已过时或命名空间未知，筛选仍保留；可能没有匹配结果。", style = v.captionTextStyle, color = v.colors.error)
                EsvButton(if (draft.sourceNamespaces.size == 1) "移除 $namespace，恢复全部来源" else "移除 $namespace 限制", onClick = {
                    edit(DiscoveryFilterPresentationPolicy.removeSource(draft, namespace))
                }, enabled = editable)
            }
            EsvText("来源筛选只限制结果命名空间，不会启用来源或改写关键词；空选择表示全部当前启用来源。", style = v.captionTextStyle)
            DurationFilterField("最短时长（秒；留空不限）", draft.minimumSeconds, editable) {
                editText(DiscoveryFilterTextField.MINIMUM_SECONDS, it)
            }
            DurationFilterField("最长时长（秒；留空不限）", draft.maximumSeconds, editable) {
                editText(DiscoveryFilterTextField.MAXIMUM_SECONDS, it)
            }
            EsvText("可填写最多三位小数；边界包含本身。未知时长不能证明符合时长范围，由下面的开关单独决定。", style = v.captionTextStyle)
            EsvButton("草稿包含未知时长：${if (draft.includeUnknownDuration) "开" else "关"}", onClick = {
                edit(draft.copy(includeUnknownDuration = !draft.includeUnknownDuration))
            }, enabled = editable)
            EsvButton("草稿安全搜索：${if (draft.safeSearch) "开" else "关"}", onClick = {
                if (draft.safeSearch) editClaim?.takeIf(::currentClaimValid)?.let { safeDisableConfirm = it }
                else edit(draft.copy(safeSearch = true))
            }, enabled = editable)
            validation.error?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.error) }
            EsvButton("应用草稿查找", onClick = { search() }, enabled = canSearch)
            EsvButton("放弃草稿，恢复已应用关键词与筛选", onClick = {
                edit(DiscoveryFilterPresentationPolicy.fromProfile(state.profile, state.demo))
            }, enabled = editable && DiscoveryFilterPresentationPolicy.isDirty(draft))
            EsvText("来源能力与未知信息", style = v.titleTextStyle)
            choices.forEach { choice -> DiscoveryFilterPresentationPolicy.capabilityText(choice).forEach {
                EsvText(it, style = v.captionTextStyle)
            } }
            DiscoveryFilterPresentationPolicy.unknownDescriptorIds(state.admittedProviderDescriptors, choices).forEach {
                EsvText("来源 $it 的结果命名空间与筛选能力未知，未提供新的可选命名空间。", style = v.captionTextStyle)
            }
            EsvText(DiscoveryFilterPresentationPolicy.nativeHistoryNotice(), style = v.captionTextStyle)
            EsvText(if (state.commonsConfigured) "Wikimedia Commons：已配置元数据来源" else "Wikimedia Commons：本构建未配置服务联系信息，零来源模式", style = v.captionTextStyle)
            EsvButton("Provider 管理与恢复", onClick = { options = false; onManageProviders() })
            if (state.commonsConfigured) EsvButton("Commons 来源：${if (state.commonsEnabled) "开" else "关"}",
                onClick = { controller.enableCommons(!state.commonsEnabled) })
            SearxngSourceSettings(controller.sourceSettings)
            VerificationServiceSettings(controller.verificationSettings)
            EsvText("本地历史与来源进度", style = v.titleTextStyle)
            EsvText("本地统计快照。一个资料对应一组关键词、筛选和语言；Seen 统计记录身份及其别名，不是播放次数或媒体文件数量。", style = v.captionTextStyle)
            EsvText("从发现打开页面后的短期关联，只能确认保存来自该页面，不能证明所选媒体就是同一发现视频。当前发现视频的保存和播放状态都保持未知，不会据此隐藏视频。页面跳转、清历史或重启会使关联失效，媒体保存本身不受影响。", style = v.captionTextStyle)
            if (state.demo) EsvText("当前是隔离演示数据：以下统计、历史清除和进度重置仅作用于当前演示内存记录，不影响真实本地发现数据。", style = v.captionTextStyle, color = v.colors.error)
            val statistics = state.historyStatistics
            if (statistics != null) {
                EsvText(DiscoveryPresentationPolicy.statisticsText("当前资料", statistics.currentProfile), style = v.captionTextStyle)
                EsvText(DiscoveryPresentationPolicy.statisticsText("全部资料", statistics.allProfiles), style = v.captionTextStyle)
            } else EsvText(if (state.historyStatisticsUnavailable || state.storageUnavailable) "本地统计暂不可读，未将未知数据显示为零。"
                else "本地统计尚未读取。", style = v.captionTextStyle)
            EsvButton("刷新本地统计", onClick = { controller.refreshHistoryStatistics(state.controlTarget) },
                enabled = state.controlTarget != null && !state.storageUnavailable)
            EsvText(DiscoveryPresentationPolicy.retentionNotice(), style = v.captionTextStyle)
            EsvButton("清除当前资料的 Seen 历史…", onClick = {
                state.controlTarget?.let { localControlConfirm = DiscoveryControlRequest(it, DiscoveryLocalAction.CLEAR_CURRENT_HISTORY) }
            }, enabled = state.controlTarget != null && !state.storageUnavailable)
            EsvButton("清除全部资料的 Seen 历史…", onClick = {
                state.controlTarget?.let { localControlConfirm = DiscoveryControlRequest(it, DiscoveryLocalAction.CLEAR_ALL_HISTORY) }
            }, enabled = state.controlTarget != null && !state.storageUnavailable)
            state.status?.providers?.forEach { progress ->
                EsvText(DiscoveryPresentationPolicy.providerProgressText(progress), style = v.captionTextStyle)
                EsvButton("重置来源 ${progress.providerId} 的当前资料进度…", onClick = {
                    state.controlTarget?.let { localControlConfirm = DiscoveryControlRequest(it, DiscoveryLocalAction.RESET_PROVIDER_PROGRESS, progress.providerId) }
                }, enabled = state.controlTarget != null && !state.storageUnavailable)
            }
            EsvText("读取统计、清除历史和重置进度不会请求来源。重置后可主动点击“重试 / 补充”。切到浏览器或应用后台会立即取消发现任务。实际保存、录制与资源清理期间暂停发现补充；切换页面不会解除暂停。", style = v.captionTextStyle)
            if (BuildDiscovery.supportsDemo) EsvButton(if (state.demo) "关闭演示数据" else "显式启用演示数据（Debug）", onClick = { controller.enableDemo(!state.demo); options = false })
        }
    }
    safeDisableConfirm?.let { captured -> Dialog(onDismissRequest = { safeDisableConfirm = null }) {
        val valid = currentClaimValid(captured)
        Column(Modifier.background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            EsvText("在草稿中关闭安全搜索？", style = v.titleTextStyle)
            EsvText("当前来源无法保证过滤不适宜内容。SearXNG 及其上游的安全搜索能力也尚未核验；关闭后可能出现不适宜内容。此处仅修改草稿，之后点击“查找”或“应用草稿查找”才会提交关键词与全部草稿筛选。")
            if (!valid) EsvText("资料或草稿已变化，请关闭后重新确认。", style = v.captionTextStyle, color = v.colors.error)
            EsvButton("确认关闭草稿安全搜索", onClick = {
                controller.confirmFilterSafety(captured)
                safeDisableConfirm = null
            }, enabled = valid)
            EsvButton("保持开启", onClick = { safeDisableConfirm = null })
        }
    } }
    rightsDetails?.let { result -> Dialog(onDismissRequest = { rightsDetails = null }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
            .padding(v.spacingLarge).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            EsvText("来源与许可资料", style = v.titleTextStyle)
            EsvButton("关闭", onClick = { rightsDetails = null })
            EsvText("以下是来源报告，需逐文件核对；元数据、完整署名或许可标签均不代表已经获准下载或使用。", style = v.captionTextStyle, color = v.colors.error)
            if (result.rights.snapshots.isEmpty()) EsvText("此结果没有可核对的作者与许可资料。")
            if (result.rights.warnings.isNotEmpty()) EsvText("记录警示：${result.rights.warnings.joinToString { it.name }}", style = v.captionTextStyle)
            result.rights.snapshots.forEach { snapshot ->
                RightsPresentationPolicy.details(snapshot).forEach { EsvText(it, style = v.captionTextStyle) }
                listOf("来源文件页" to snapshot.sourcePageUrl, "许可链接" to snapshot.licenseUrl,
                    "元数据使用说明" to snapshot.metadataTermsUrl).forEach { (label, url) ->
                    RightsPresentationPolicy.safeLink(url)?.let { safe ->
                        EsvText("$label：$safe", style = v.captionTextStyle)
                        EsvButton("打开$label", onClick = { rightsDetails = null; onLink(safe) })
                    }
                }
            }
        }
    } }
    localControlConfirm?.let { request -> Dialog(onDismissRequest = { localControlConfirm = null }) {
        val current = state.controlTarget
        val valid = !state.storageUnavailable && DiscoveryPresentationPolicy.validControl(request, current,
            state.status?.providers?.map { it.providerId }?.toSet().orEmpty())
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
            .padding(v.spacingLarge).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            EsvText(DiscoveryPresentationPolicy.controlTitle(request), style = v.titleTextStyle)
            if (state.demo) EsvText("本次仅影响当前演示的历史或来源进度，不影响真实本地发现数据；“全部资料”在此仅指演示资料。", style = v.captionTextStyle)
            EsvText(DiscoveryPresentationPolicy.controlConsequence(request), style = v.captionTextStyle)
            if (!valid) EsvText("查询或本地视图已变化，请关闭后重新选择操作。", style = v.captionTextStyle, color = v.colors.error)
            EsvButton(if (request.action == DiscoveryLocalAction.RESET_PROVIDER_PROGRESS) "确认重置此来源进度" else "确认清除 Seen 历史",
                onClick = { localControlConfirm = null; controller.confirmLocalControl(request) }, enabled = valid)
            EsvButton("取消", onClick = { localControlConfirm = null })
        }
    } }

}
@Composable
private fun DurationFilterField(label: String, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    val v = LocalEsvVisuals.current
    EsvText(label, style = v.captionTextStyle)
    BasicTextField(value, onValueChange = { if (it.length <= DiscoveryFilterPresentationPolicy.MAX_DURATION_TEXT) onValue(it) },
        enabled = enabled, singleLine = true,
        modifier = Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
            .padding(v.spacingMedium).semantics { contentDescription = label },
        textStyle = v.bodyTextStyle.copy(color = v.colors.text), cursorBrush = SolidColor(v.colors.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done))
}
private fun policyText(policy: RepeatPolicy): String = when(policy) {
    RepeatPolicy.PREFER_NEW -> "优先新内容"; RepeatPolicy.HIDE_WATCHED -> "隐藏打开或播放过的内容"
    RepeatPolicy.HIDE_SHOWN -> "隐藏展示过的内容"; RepeatPolicy.SHOW_ALL -> "显示全部（仍去重）"
}
private fun statusText(status: DiscoveryStatus?, failed: Boolean, ready: Boolean): String {
    if (failed) return "本地数据不可用，已暂停发现；浏览器仍可用"
    if (!ready) return "正在恢复本地发现数据"
    if (status?.state == DiscoveryState.UNSUPPORTED_FILTER && status.lastError == DiscoveryError.INVALID_QUERY) return "关键词无效或规范化后过长，请修改后重试"
    if (status?.state == DiscoveryState.UNSUPPORTED_FILTER && status.lastError == DiscoveryError.SOURCE_CONFIGURATION) return "来源配置不可用，浏览器仍可使用"
    val title = when(status?.state) {
        DiscoveryState.NO_SOURCES -> "无搜索来源"; DiscoveryState.FILLING -> "正在补充结果"
        DiscoveryState.BUFFERED -> "结果已就绪"; DiscoveryState.BACKOFF -> "来源暂缓，请稍后重试"
        DiscoveryState.EXHAUSTED -> "本轮已结束"; DiscoveryState.SUSPENDED -> "发现已暂停"
        DiscoveryState.UNSUPPORTED_FILTER -> "来源不支持当前筛选，不会发送查询"
        DiscoveryState.FAILED -> "发现暂不可用"; DiscoveryState.CAPACITY_REACHED -> "已达到本地容量保护上限"
        else -> "等待查找"
    }
    return "$title · 可供后续页 ${status?.availableCount ?: 0} · 本轮已尝试 ${status?.requestsThisRound ?: 0}；" +
        "当前每轮上限 ${if (status?.powerPolicyUpdatePending == true) "等待策略确认" else status?.powerPolicy?.maxRequestsPerRound?.toString() ?: "未知"}"
}
