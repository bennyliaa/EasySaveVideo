package com.easysavevideo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.easysavevideo.app.theme.*
import com.easysavevideo.discovery.PendingVideoCandidate
import com.easysavevideo.discovery.SourceAdmissionStatus

/** Text-only reports: no image, page, media, or metadata network request occurs in these cards. */
@Composable
fun PendingCandidateCards(candidates: List<PendingVideoCandidate>, columns: Int,
    admission: Map<String, SourceAdmissionStatus> = emptyMap(),
    onVerify: (PendingVideoCandidate) -> Unit, onOpen: (PendingVideoCandidate) -> Unit) {
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText("待验证：仅搜索来源报告。尚未取得视频页面证据。", style = v.captionTextStyle, color = v.colors.error)
        EsvText("不会自动获取网页或缩略图；时长、可播放性和使用许可均未知。这里只保留本次查询的有界候选。", style = v.captionTextStyle)
        LazyVerticalGrid(GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            items(candidates, key = { "pending-${it.candidateId}" }) { candidate ->
                Column(Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
                    .background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingSmall),
                    verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvText("待验证 · 来源报告", style = v.captionTextStyle, color = v.colors.error)
                    EsvText(candidate.sourceReported?.title ?: "标题未知", style = v.bodyTextStyle)
                    EsvText(candidate.sourceReported?.sourceName ?: "网站未知", style = v.captionTextStyle)
                    EsvText("来源：${candidate.provenance.sorted().joinToString()}", style = v.captionTextStyle)
                    candidate.sourceReported?.description?.take(320)?.let { EsvText(it, style = v.captionTextStyle) }
                    EsvText("视频、时长、许可：未核验", style = v.captionTextStyle)
                    EsvText("本地验证须有明确来源准入；未配置或不匹配时拒绝。页面证据不代表可播放、下载或保存。", style = v.captionTextStyle)
                    EsvText("来源准入：${admission[candidate.candidateId] ?: SourceAdmissionStatus.UNCONFIGURED}", style = v.captionTextStyle)
                    candidate.verification?.let { summary ->
                        EsvText("验证状态：${summary.outcome} · ${summary.evidence}", style = v.captionTextStyle)
                    }
                    EsvButton(if (candidate.verificationActive) "验证中" else if (candidate.verification != null) "本轮已验证" else "验证（有限预算）",
                        onClick = { onVerify(candidate) }, enabled = !candidate.verificationActive && candidate.verification == null, modifier = Modifier.fillMaxWidth())
                    EsvButton("打开候选原网页", onClick = { onOpen(candidate) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
