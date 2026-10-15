package com.easysavevideo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.discovery.DiscoveryPresentationCopy
import com.easysavevideo.app.discovery.RightsPresentationCopy
import java.util.Locale

// Read the current configuration and resources on recomposition. Never cache by controller/result:
// changing the display language only replaces these immutable values and does not issue an intent.
@Composable
internal fun discoveryPresentationCopy(): DiscoveryPresentationCopy {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return DiscoveryPresentationCopy(
        locale = locale,
        localizedCounts = true,
        sortRelevance = stringResource(R.string.discovery_presentation_sort_relevance),
        sortNewest = stringResource(R.string.discovery_presentation_sort_newest),
        sortShortest = stringResource(R.string.discovery_presentation_sort_shortest),
        sortLongest = stringResource(R.string.discovery_presentation_sort_longest),
        retention = stringResource(R.string.discovery_presentation_retention),
        statistics = stringResource(R.string.discovery_presentation_statistics),
        providerProgress = stringResource(R.string.discovery_presentation_provider_progress),
        providerNotStarted = stringResource(R.string.discovery_presentation_provider_not_started),
        providerRequesting = stringResource(R.string.discovery_presentation_provider_requesting),
        providerRetryWait = stringResource(R.string.discovery_presentation_provider_retry_wait),
        providerCooldown = stringResource(R.string.discovery_presentation_provider_cooldown),
        providerUnsupportedFilter = stringResource(R.string.discovery_presentation_provider_unsupported_filter),
        providerInvalidQuery = stringResource(R.string.discovery_presentation_provider_invalid_query),
        providerUnavailable = stringResource(R.string.discovery_presentation_provider_unavailable),
        providerReady = stringResource(R.string.discovery_presentation_provider_ready),
        providerExhausted = stringResource(R.string.discovery_presentation_provider_exhausted),
        providerCursorExpired = stringResource(R.string.discovery_presentation_provider_cursor_expired),
        providerCursorIncompatible = stringResource(R.string.discovery_presentation_provider_cursor_incompatible),
        providerSuspended = stringResource(R.string.discovery_presentation_provider_suspended),
        providerStorageUnavailable = stringResource(R.string.discovery_presentation_provider_storage_unavailable),
        committedPage = stringResource(R.string.discovery_presentation_committed_page),
        savedContinuation = stringResource(R.string.discovery_presentation_saved_continuation),
        noticeSourceConfigurationChecking = stringResource(R.string.discovery_notice_source_configuration_checking),
        noticeVerificationNotStarted = stringResource(R.string.discovery_notice_verification_not_started),
        noticeViewSettingsChanged = stringResource(R.string.discovery_notice_view_settings_changed),
        noticeInvalidQuery = stringResource(R.string.discovery_notice_invalid_query),
        noticeSortChanged = stringResource(R.string.discovery_notice_sort_changed),
        noticeStorageRetryUnavailable = stringResource(R.string.discovery_notice_storage_retry_unavailable),
        noticeStorageRecoveryUnavailable = stringResource(R.string.discovery_notice_storage_recovery_unavailable),
        noticeControlStale = stringResource(R.string.discovery_notice_control_stale),
        noticeControlFailed = stringResource(R.string.discovery_notice_control_failed),
        noticeControlCurrentHistoryCleared = stringResource(R.string.discovery_notice_control_current_history_cleared),
        noticeControlAllHistoryCleared = stringResource(R.string.discovery_notice_control_all_history_cleared),
        noticeControlProviderProgressReset = stringResource(R.string.discovery_notice_control_provider_progress_reset),
        duration = stringResource(R.string.discovery_presentation_duration),
    )
}

@Composable
internal fun rightsPresentationCopy(): RightsPresentationCopy {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return RightsPresentationCopy(
        locale = locale,
        localizedCounts = true,
        authorUnknown = stringResource(R.string.discovery_rights_author_unknown),
        licenseUnknown = stringResource(R.string.discovery_rights_license_unknown),
        author = stringResource(R.string.discovery_rights_author),
        summaryLicense = stringResource(R.string.discovery_rights_summary_license),
        customAttribution = stringResource(R.string.discovery_rights_custom_attribution),
        otherRecords = stringResource(R.string.discovery_rights_other_records),
        warnings = stringResource(R.string.discovery_rights_warnings),
        source = stringResource(R.string.discovery_rights_source),
        sourceIdentity = stringResource(R.string.discovery_rights_source_identity),
        credit = stringResource(R.string.discovery_rights_credit),
        license = stringResource(R.string.discovery_rights_license),
        usageTerms = stringResource(R.string.discovery_rights_usage_terms),
        copyrighted = stringResource(R.string.discovery_rights_copyrighted),
        attributionRequired = stringResource(R.string.discovery_rights_attribution_required),
        nonFree = stringResource(R.string.discovery_rights_non_free),
        restrictions = stringResource(R.string.discovery_rights_restrictions),
        restrictionSeparator = stringResource(R.string.discovery_rights_restriction_separator),
        deletionReason = stringResource(R.string.discovery_rights_deletion_reason),
        review = stringResource(R.string.discovery_rights_review),
        reviewIncomplete = stringResource(R.string.discovery_rights_review_incomplete),
        reviewReported = stringResource(R.string.discovery_rights_review_reported),
        reviewFlagged = stringResource(R.string.discovery_rights_review_flagged),
        sourceRevision = stringResource(R.string.discovery_rights_source_revision),
        observedAtUtc = stringResource(R.string.discovery_rights_observed_at_utc),
        provenance = stringResource(R.string.discovery_rights_provenance),
        unstructured = stringResource(R.string.discovery_rights_unstructured),
        structured = stringResource(R.string.discovery_rights_structured),
        yes = stringResource(R.string.discovery_rights_yes),
        no = stringResource(R.string.discovery_rights_no),
        unknown = stringResource(R.string.discovery_rights_unknown),
        notProvided = stringResource(R.string.discovery_rights_not_provided),
        incomplete = stringResource(R.string.discovery_rights_incomplete),
    )
}
