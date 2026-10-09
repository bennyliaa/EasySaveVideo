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

/** Text-only reports: no image, page, media, or metadata network request occurs in these cards. */
@Composable
fun PendingCandidateCards(candidates: List<PendingVideoCandidate>, columns: Int,
    onOpen: (PendingVideoCandidate) -> Unit) {
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText("待验证：仅搜索来源报告。尚未由 Scrapling 验证为视频页。", style = v.captionTextStyle, color = v.colors.error)
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
                    EsvButton("打开候选原网页", onClick = { onOpen(candidate) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
