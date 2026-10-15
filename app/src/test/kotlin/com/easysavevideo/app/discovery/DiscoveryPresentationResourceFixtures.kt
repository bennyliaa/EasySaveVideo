package com.easysavevideo.app.discovery

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

internal fun discoveryPresentationResourceRoot(): File =
    listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

internal fun discoveryPresentationSourceRoot(): File =
    listOf(File("src/main/kotlin/com/easysavevideo/app"), File("app/src/main/kotlin/com/easysavevideo/app")).first { it.isDirectory }

internal fun discoveryPresentationResourceStrings(folder: String): Map<String, String> {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val document = factory.newDocumentBuilder().parse(File(discoveryPresentationResourceRoot(), "$folder/discovery_presentation.xml"))
    val nodes = document.getElementsByTagName("string")
    return (0 until nodes.length).associate { index ->
        val node = nodes.item(index)
        val raw = node.textContent
        // These resources use Android quotes only to preserve suffix/separator whitespace.
        node.attributes.getNamedItem("name").nodeValue to raw.removeSurrounding("\"")
    }.also { check(it.size == nodes.length) { "Duplicate resource name" } }
}

internal fun discoveryPresentationFixtureLocale(folder: String): Locale =
    if (folder == "values") Locale.US else Locale.SIMPLIFIED_CHINESE

internal fun discoveryPresentationFixture(folder: String, locale: Locale = discoveryPresentationFixtureLocale(folder)): DiscoveryPresentationCopy {
    val strings = discoveryPresentationResourceStrings(folder)
    return DiscoveryPresentationCopy(
        locale = locale, localizedCounts = true,
        sortRelevance = strings.getValue("discovery_presentation_sort_relevance"),
        sortNewest = strings.getValue("discovery_presentation_sort_newest"),
        sortShortest = strings.getValue("discovery_presentation_sort_shortest"),
        sortLongest = strings.getValue("discovery_presentation_sort_longest"),
        retention = strings.getValue("discovery_presentation_retention"),
        statistics = strings.getValue("discovery_presentation_statistics"),
        providerProgress = strings.getValue("discovery_presentation_provider_progress"),
        providerNotStarted = strings.getValue("discovery_presentation_provider_not_started"),
        providerRequesting = strings.getValue("discovery_presentation_provider_requesting"),
        providerRetryWait = strings.getValue("discovery_presentation_provider_retry_wait"),
        providerCooldown = strings.getValue("discovery_presentation_provider_cooldown"),
        providerUnsupportedFilter = strings.getValue("discovery_presentation_provider_unsupported_filter"),
        providerInvalidQuery = strings.getValue("discovery_presentation_provider_invalid_query"),
        providerUnavailable = strings.getValue("discovery_presentation_provider_unavailable"),
        providerReady = strings.getValue("discovery_presentation_provider_ready"),
        providerExhausted = strings.getValue("discovery_presentation_provider_exhausted"),
        providerCursorExpired = strings.getValue("discovery_presentation_provider_cursor_expired"),
        providerCursorIncompatible = strings.getValue("discovery_presentation_provider_cursor_incompatible"),
        providerSuspended = strings.getValue("discovery_presentation_provider_suspended"),
        providerStorageUnavailable = strings.getValue("discovery_presentation_provider_storage_unavailable"),
        committedPage = strings.getValue("discovery_presentation_committed_page"),
        savedContinuation = strings.getValue("discovery_presentation_saved_continuation"),
        noticeSourceConfigurationChecking = strings.getValue("discovery_notice_source_configuration_checking"),
        noticeVerificationNotStarted = strings.getValue("discovery_notice_verification_not_started"),
        noticeViewSettingsChanged = strings.getValue("discovery_notice_view_settings_changed"),
        noticeInvalidQuery = strings.getValue("discovery_notice_invalid_query"),
        noticeSortChanged = strings.getValue("discovery_notice_sort_changed"),
        noticeStorageRetryUnavailable = strings.getValue("discovery_notice_storage_retry_unavailable"),
        noticeStorageRecoveryUnavailable = strings.getValue("discovery_notice_storage_recovery_unavailable"),
        noticeControlStale = strings.getValue("discovery_notice_control_stale"),
        noticeControlFailed = strings.getValue("discovery_notice_control_failed"),
        noticeControlCurrentHistoryCleared = strings.getValue("discovery_notice_control_current_history_cleared"),
        noticeControlAllHistoryCleared = strings.getValue("discovery_notice_control_all_history_cleared"),
        noticeControlProviderProgressReset = strings.getValue("discovery_notice_control_provider_progress_reset"),
        duration = strings.getValue("discovery_presentation_duration"),
    )
}

internal fun rightsPresentationFixture(folder: String, locale: Locale = discoveryPresentationFixtureLocale(folder)): RightsPresentationCopy {
    val strings = discoveryPresentationResourceStrings(folder)
    return RightsPresentationCopy(
        locale = locale, localizedCounts = true,
        authorUnknown = strings.getValue("discovery_rights_author_unknown"),
        licenseUnknown = strings.getValue("discovery_rights_license_unknown"),
        author = strings.getValue("discovery_rights_author"),
        summaryLicense = strings.getValue("discovery_rights_summary_license"),
        customAttribution = strings.getValue("discovery_rights_custom_attribution"),
        otherRecords = strings.getValue("discovery_rights_other_records"),
        warnings = strings.getValue("discovery_rights_warnings"),
        source = strings.getValue("discovery_rights_source"),
        sourceIdentity = strings.getValue("discovery_rights_source_identity"),
        credit = strings.getValue("discovery_rights_credit"),
        license = strings.getValue("discovery_rights_license"),
        usageTerms = strings.getValue("discovery_rights_usage_terms"),
        copyrighted = strings.getValue("discovery_rights_copyrighted"),
        attributionRequired = strings.getValue("discovery_rights_attribution_required"),
        nonFree = strings.getValue("discovery_rights_non_free"),
        restrictions = strings.getValue("discovery_rights_restrictions"),
        restrictionSeparator = strings.getValue("discovery_rights_restriction_separator"),
        deletionReason = strings.getValue("discovery_rights_deletion_reason"),
        review = strings.getValue("discovery_rights_review"),
        reviewIncomplete = strings.getValue("discovery_rights_review_incomplete"),
        reviewReported = strings.getValue("discovery_rights_review_reported"),
        reviewFlagged = strings.getValue("discovery_rights_review_flagged"),
        sourceRevision = strings.getValue("discovery_rights_source_revision"),
        observedAtUtc = strings.getValue("discovery_rights_observed_at_utc"),
        provenance = strings.getValue("discovery_rights_provenance"),
        unstructured = strings.getValue("discovery_rights_unstructured"),
        structured = strings.getValue("discovery_rights_structured"),
        yes = strings.getValue("discovery_rights_yes"),
        no = strings.getValue("discovery_rights_no"),
        unknown = strings.getValue("discovery_rights_unknown"),
        notProvided = strings.getValue("discovery_rights_not_provided"),
        incomplete = strings.getValue("discovery_rights_incomplete"),
    )
}
