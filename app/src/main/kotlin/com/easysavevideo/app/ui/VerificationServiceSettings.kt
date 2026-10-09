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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.discovery.VerificationConfigurationClaim
import com.easysavevideo.app.discovery.VerificationConfigurationController
import com.easysavevideo.app.discovery.VerificationConfigurationPolicy
import com.easysavevideo.app.theme.EsvButton
import com.easysavevideo.app.theme.EsvText
import com.easysavevideo.app.theme.LocalEsvVisuals

@Composable
fun VerificationServiceSettings(controller: VerificationConfigurationController) {
    val v = LocalEsvVisuals.current
    val state = controller.state
    var edit by remember(controller) { mutableStateOf<VerificationConfigurationClaim?>(null) }
    LaunchedEffect(state.generation) { if (edit?.let(controller::isCurrent) == false) edit = null }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText("可选网页验证服务 · 开发接口", style = v.titleTextStyle)
        EsvText(if (state.endpoint == null) "已关闭；未配置默认服务。" else "本次应用会话已启用；应用进程重启后关闭。", style = v.captionTextStyle)
        EsvText("搜索来源只给出候选。启用后，主动查找每批最多尝试验证一个候选；其余及验证不足的候选仍显示为未验证。服务不可达不影响浏览、下载或录制。", style = v.captionTextStyle)
        EsvText("当前仅完成隔离开发接口；没有已部署或通过公开站点验收的服务。网页证据不代表可播放、可下载或有保存权限，缺失时长保持未知。", style = v.captionTextStyle, color = v.colors.error)
        EsvButton("配置本次会话的验证服务", onClick = { edit = controller.claim() })
        if (state.endpoint != null) EsvButton("关闭验证并清除会话端点", onClick = { controller.disable(controller.claim()) })
    }
    edit?.let { claim -> VerificationEditor(controller, claim) { edit = null } }
}

@Composable
private fun VerificationEditor(controller: VerificationConfigurationController, claim: VerificationConfigurationClaim,
    dismiss: () -> Unit) {
    val v = LocalEsvVisuals.current
    var url by remember(claim) { mutableStateOf("") }
    var dynamic by remember(claim) { mutableStateOf(false) }
    var acknowledged by remember(claim) { mutableStateOf(false) }
    val current = controller.isCurrent(claim)
    val valid = VerificationConfigurationPolicy.endpoint(url) != null
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
            .padding(v.spacingLarge).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            EsvText("选择你信任且有权使用的服务", style = v.titleTextStyle)
            EsvText("输入完整公共 HTTPS /v1/verify 地址。禁止账号、密码、查询参数、片段、IP 和本地地址。地址及同意仅保留在本次应用进程内。", style = v.captionTextStyle)
            BasicTextField(url, onValueChange = { if (it.length <= 2048 && it.toByteArray(Charsets.UTF_8).size <= 2048) {
                url = it; acknowledged = false
            } }, singleLine = true, enabled = current,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                textStyle = v.bodyTextStyle.copy(color = v.colors.text), cursorBrush = SolidColor(v.colors.accent),
                modifier = Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
                    .padding(v.spacingMedium).semantics { contentDescription = "HTTPS 网页验证服务端点" })
            EsvText("主动查找时，会把候选的完整网页地址（可能含查询参数及其中的信息）发给此端点；服务可读取该网页。不会附加你的搜索词、浏览器 Cookie 或登录凭据。请勿使用需要私密信息的地址。", style = v.captionTextStyle)
            VerificationCheckbox("允许服务在其已获准的网页上执行 JavaScript 验证", dynamic, current) {
                dynamic = it; acknowledged = false
            }
            EsvText("动态验证仍须由服务独立准入；本开关不授权部署、付费或绕过网站限制。配置变化会取消旧验证、使旧结果失效；不会自动查找。", style = v.captionTextStyle)
            VerificationCheckbox("我同意将候选完整网页地址发送给以上端点，按以上方式验证", acknowledged, current && valid) {
                acknowledged = it
            }
            if (url.isNotEmpty() && !valid) EsvText("地址不符合验证服务端点规则。", color = v.colors.error, style = v.captionTextStyle)
            EsvButton("取消", onClick = dismiss)
            EsvButton("同意并在本次会话启用", enabled = current && valid && acknowledged,
                onClick = { if (controller.enable(claim, url, dynamic, acknowledged)) dismiss() })
        }
    }
}

@Composable
private fun VerificationCheckbox(text: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    val v = LocalEsvVisuals.current
    Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).toggleable(checked, enabled = enabled,
        role = Role.Checkbox, onValueChange = change).padding(v.spacingSmall), horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(if (checked) "☑" else "☐", color = v.colors.accent)
        EsvText(text, color = if (enabled) v.colors.text else v.colors.muted)
    }
}
