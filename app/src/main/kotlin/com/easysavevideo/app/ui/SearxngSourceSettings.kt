package com.easysavevideo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.discovery.SearxngConfigurationClaim
import com.easysavevideo.app.discovery.SearxngConfigurationController
import com.easysavevideo.app.discovery.SearxngConfigurationEdit
import com.easysavevideo.app.discovery.SearxngConfigurationPolicy
import com.easysavevideo.app.discovery.SearxngConfigurationStatus
import com.easysavevideo.app.theme.EsvButton
import com.easysavevideo.app.theme.EsvText
import com.easysavevideo.app.theme.LocalEsvVisuals

/** Insert inside the existing options column. No provider, SafeSearch control, or network access. */
@Composable
fun SearxngSourceSettings(controller: SearxngConfigurationController, modifier: Modifier = Modifier) {
    val v = LocalEsvVisuals.current
    val state = controller.state
    var edit by remember(controller) { mutableStateOf<SearxngConfigurationClaim?>(null) }
    var reset by remember(controller) { mutableStateOf<SearxngConfigurationClaim?>(null) }
    LaunchedEffect(state.config?.generation, state.busy, state.status) {
        if (edit?.let(controller::isCurrent) == false) edit = null
        if (reset?.let(controller::isCurrent) == false) reset = null
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText("SearXNG 搜索来源", style = v.titleTextStyle)
        EsvText("最多配置 3 个公共 HTTPS /search 端点；默认没有端点。服务是否开放 JSON 搜索需由服务运营方确认。", style = v.captionTextStyle)
        EsvText("主动查找时，输入的查询会发送给已启用的端点及其上游搜索引擎。请只配置你信任且有权使用的服务。", style = v.captionTextStyle)
        EsvText("这些来源的安全搜索能力未知。安全搜索开启的请求会被拒绝，不会发送查询；这里不会改变已请求的安全搜索设置。", style = v.captionTextStyle, color = v.colors.error)
        EsvText("配置编辑、启停和清空都不会自动查找。已生成候选及来源进度将失效；之后需主动点击查找。", style = v.captionTextStyle)
        when (state.status) {
            SearxngConfigurationStatus.NOT_LOADED -> EsvText(if (state.busy) "正在读取本地来源配置。" else "尚未读取本地来源配置。", style = v.captionTextStyle)
            SearxngConfigurationStatus.MALFORMED -> EsvText("本地来源配置损坏或版本不可识别；原始文件已保留，所有 SearXNG 来源停用。", style = v.captionTextStyle, color = v.colors.error)
            SearxngConfigurationStatus.UNAVAILABLE -> EsvText("无法可靠读取或写入来源配置；原始资料已保留，所有 SearXNG 来源停用。", style = v.captionTextStyle, color = v.colors.error)
            SearxngConfigurationStatus.CLOSED -> EsvText("来源配置已关闭。", style = v.captionTextStyle)
            else -> if (state.busy) EsvText("正在保存本地来源配置。", style = v.captionTextStyle)
        }
        state.notice?.let { EsvText(it, style = v.captionTextStyle) }
        state.config?.slots?.forEachIndexed { index, slot ->
            Column(Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
                .padding(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText("来源 ${index + 1} · ${slot.id}", style = v.bodyTextStyle)
                EsvText(slot.hostname?.let { "$it · ${if (slot.enabled) "已启用" else "已关闭"}" } ?: "未配置", style = v.captionTextStyle)
                EsvButton(if (slot.searchUrl == null) "配置来源" else "编辑、启停或移除", enabled = state.editable,
                    onClick = { edit = controller.beginEdit(slot.id) })
            }
        }
        EsvButton("重新读取配置", onClick = { controller.reload() }, enabled = !state.busy && state.status != SearxngConfigurationStatus.CLOSED)
        EsvButton("清空 SearXNG 来源配置", onClick = { reset = controller.beginReset() }, enabled = state.editable)
    }
    edit?.let { claim ->
        SearxngEndpointEditor(controller, claim, onDismiss = { edit = null })
    }
    reset?.let { claim ->
        Dialog(onDismissRequest = { reset = null }) {
            Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingLarge),
                verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText("清空全部 SearXNG 来源？", style = v.titleTextStyle)
                EsvText("会移除三个端点并停用它们，使旧候选和来源进度失效。不会自动查找。", style = v.bodyTextStyle)
                EsvButton("取消", onClick = { reset = null })
                EsvButton("确认清空", enabled = controller.isCurrent(claim), onClick = {
                    if (controller.reset(claim)) reset = null
                })
            }
        }
    }
}

@Composable
private fun SearxngEndpointEditor(controller: SearxngConfigurationController, claim: SearxngConfigurationClaim,
    onDismiss: () -> Unit) {
    val v = LocalEsvVisuals.current
    val config = controller.state.config ?: return
    val original = config.slots.firstOrNull { it.id == claim.slotId } ?: return
    var url by remember(claim) { mutableStateOf(original.searchUrl.orEmpty()) }
    var enabled by remember(claim) { mutableStateOf(original.enabled) }
    var acknowledged by remember(claim) { mutableStateOf(false) }
    val validation = SearxngConfigurationPolicy.replace(config, original.id, url, enabled, acknowledged)
    val current = controller.isCurrent(claim)
    val needsAcknowledgement = SearxngConfigurationPolicy.acknowledgementRequired(original, url, enabled)
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
            .padding(v.spacingLarge).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            EsvText("配置 ${original.id}", style = v.titleTextStyle)
            EsvText("输入完整的公共 HTTPS /search 地址。仅允许公共域名，不允许账号、密码、查询参数、片段、端口、IP 或本地地址。", style = v.captionTextStyle)
            BasicTextField(url, onValueChange = { text ->
                if (text.length <= SearxngConfigurationPolicy.MAX_URL_BYTES &&
                    text.toByteArray(Charsets.UTF_8).size <= SearxngConfigurationPolicy.MAX_URL_BYTES) {
                    url = text; acknowledged = false
                    if (text.isEmpty()) enabled = false
                }
            }, singleLine = true, enabled = current,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                textStyle = v.bodyTextStyle.copy(color = v.colors.text), cursorBrush = SolidColor(v.colors.accent),
                modifier = Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
                    .padding(v.spacingMedium).semantics { contentDescription = "SearXNG HTTPS 搜索端点" },
                decorationBox = { input -> Column { if (url.isEmpty()) EsvText("端点地址；留空可移除", color = v.colors.muted); input() } })
            SourceCheckbox("启用这个来源", enabled, current && url.isNotEmpty()) { enabled = it; acknowledged = false }
            EsvText("主动查找时，输入的查询会发送给这个端点及其上游搜索引擎。端点地址只作为本地功能配置保存。", style = v.captionTextStyle)
            SourceCheckbox("我理解并同意向此端点及其上游搜索引擎发送查询", acknowledged,
                current && url.isNotEmpty()) { acknowledged = it }
            EsvText("安全搜索能力未知；安全搜索开启的请求会被拒绝。保存不会修改安全搜索，也不会发送或重新发送查询。", style = v.captionTextStyle, color = v.colors.error)
            if (validation is SearxngConfigurationEdit.Rejected &&
                (!needsAcknowledgement || validation.issue != com.easysavevideo.app.discovery.SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED))
                EsvText(SearxngConfigurationController.issueText(validation.issue), style = v.captionTextStyle, color = v.colors.error)
            if (!current) EsvText("配置已改变，请关闭后重新打开。", style = v.captionTextStyle, color = v.colors.error)
            EsvButton("取消", onClick = onDismiss)
            EsvButton("保存本地配置", enabled = current && validation is SearxngConfigurationEdit.Accepted,
                onClick = { if (controller.replace(claim, url, enabled, acknowledged)) onDismiss() })
            if (original.searchUrl != null) EsvButton("移除此端点", enabled = current,
                onClick = { if (controller.replace(claim, "", enabled = false, acknowledged = false)) onDismiss() })
        }
    }
}

/** Foundation checkbox semantics using the active SkinKit text, border, spacing, and colors. */
@Composable
private fun SourceCheckbox(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    val v = LocalEsvVisuals.current
    Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).toggleable(value = checked, enabled = enabled,
        role = Role.Checkbox, onValueChange = onChecked).padding(v.spacingSmall),
        horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
        EsvText(if (checked) "☑" else "☐", color = if (enabled) v.colors.accent else v.colors.muted)
        EsvText(label, modifier = Modifier.weight(1f), color = if (enabled) v.colors.text else v.colors.muted)
    }
}
