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
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.*
import com.easysavevideo.discovery.PendingVideoCandidate
import com.easysavevideo.discovery.SourceAdmissionStatus

/** Text-only reports: no image, page, media, or metadata network request occurs in these cards. */
@Composable
fun PendingCandidateCards(candidates: List<PendingVideoCandidate>, columns: Int,
    admission: Map<String, SourceAdmissionStatus> = emptyMap(),
    onEvaluate: ((PendingVideoCandidate) -> Unit)? = null,
    onVerify: (PendingVideoCandidate) -> Unit, onOpen: (PendingVideoCandidate) -> Unit) {
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(stringResource(R.string.discovery_pending_notice_candidates), style = v.captionTextStyle, color = v.colors.error)
        EsvText(stringResource(R.string.discovery_pending_notice_scope), style = v.captionTextStyle)
        LazyVerticalGrid(GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            items(candidates, key = { "pending-${it.candidateId}" }) { candidate ->
                Column(Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.cardCorner))
                    .background(v.colors.surface, RoundedCornerShape(v.cardCorner)).padding(v.spacingSmall),
                    verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvText(stringResource(R.string.discovery_pending_badge), style = v.captionTextStyle, color = v.colors.error)
                    EsvText(candidate.sourceReported?.title ?: stringResource(R.string.flow_title_unknown), style = v.bodyTextStyle)
                    EsvText(candidate.sourceReported?.sourceName ?: stringResource(R.string.discovery_pending_site_unknown), style = v.captionTextStyle)
                    EsvText(stringResource(R.string.discovery_pending_sources, candidate.provenance.sorted().joinToString()), style = v.captionTextStyle)
                    candidate.sourceReported?.description?.take(320)?.let { EsvText(it, style = v.captionTextStyle) }
                    EsvText(stringResource(R.string.discovery_pending_unverified_metadata), style = v.captionTextStyle)
                    EsvText(stringResource(R.string.discovery_pending_admission_notice), style = v.captionTextStyle)
                    EsvText(stringResource(R.string.discovery_copy_5,
                        stringResource(discoverySourceAdmissionLabel(admission[candidate.candidateId] ?: SourceAdmissionStatus.UNCONFIGURED))), style = v.captionTextStyle)
                    candidate.verification?.let { summary ->
                        EsvText(stringResource(R.string.discovery_copy_6,
                            stringResource(discoveryVerificationOutcomeLabel(summary.outcome)),
                            stringResource(discoveryVerificationEvidenceLabel(summary.evidence))), style = v.captionTextStyle)
                    }
                    EsvButton(stringResource(pendingVerificationActionLabel(candidate.verificationActive, candidate.verification != null)),
                        onClick = { onVerify(candidate) }, enabled = !candidate.verificationActive && candidate.verification == null, modifier = Modifier.fillMaxWidth())
                    if (com.easysavevideo.app.BuildConfig.DEBUG && onEvaluate != null)
                        EsvButton(stringResource(R.string.evaluation_open), onClick = { onEvaluate(candidate) }, modifier = Modifier.fillMaxWidth())
                    EsvButton(stringResource(R.string.discovery_pending_open), onClick = { onOpen(candidate) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
