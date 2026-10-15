package com.easysavevideo.app.ui

import com.easysavevideo.app.R
import com.easysavevideo.discovery.EngineEvidence
import com.easysavevideo.discovery.EngineOutcome
import com.easysavevideo.discovery.SourceAdmissionStatus

/** Display-only IDs; the current composition resolves text without changing admission or evidence. */
internal fun discoverySourceAdmissionLabel(value: SourceAdmissionStatus): Int = when (value) {
    SourceAdmissionStatus.UNCONFIGURED -> R.string.discovery_admission_unconfigured
    SourceAdmissionStatus.READY -> R.string.discovery_admission_ready
    SourceAdmissionStatus.UNSUPPORTED -> R.string.discovery_admission_unsupported
    SourceAdmissionStatus.DENIED -> R.string.discovery_admission_denied
}

internal fun discoveryVerificationOutcomeLabel(value: EngineOutcome): Int = when (value) {
    EngineOutcome.PAGE_EVIDENCE -> R.string.discovery_verification_outcome_page_evidence
    EngineOutcome.UNKNOWN -> R.string.discovery_verification_outcome_unknown
    EngineOutcome.UNAVAILABLE -> R.string.discovery_verification_outcome_unavailable
    EngineOutcome.DENIED -> R.string.discovery_verification_outcome_denied
    EngineOutcome.RATE_LIMITED -> R.string.discovery_verification_outcome_rate_limited
    EngineOutcome.TIMEOUT -> R.string.discovery_verification_outcome_timeout
    EngineOutcome.CANCELLED -> R.string.discovery_verification_outcome_cancelled
    EngineOutcome.ERROR -> R.string.discovery_verification_outcome_error
}

internal fun discoveryVerificationEvidenceLabel(value: EngineEvidence): Int = when (value) {
    EngineEvidence.NONE -> R.string.discovery_verification_evidence_none
    EngineEvidence.HTML_VIDEO -> R.string.discovery_verification_evidence_html_video
    EngineEvidence.DYNAMIC_VIDEO -> R.string.discovery_verification_evidence_dynamic_video
}

internal fun pendingVerificationActionLabel(active: Boolean, attempted: Boolean): Int = when {
    active -> R.string.discovery_pending_verify_active
    attempted -> R.string.discovery_pending_verify_attempted
    else -> R.string.discovery_pending_verify_action
}
