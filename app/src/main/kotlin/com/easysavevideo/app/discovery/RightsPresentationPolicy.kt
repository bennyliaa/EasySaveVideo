package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*
import java.net.URI

/** Every string is inert text. Only dedicated, user-initiated validated link actions navigate. */
object RightsPresentationPolicy {
    fun summary(rights: ResultRights, copy: RightsPresentationCopy = RightsPresentationCopy()): List<String> =
        if (rights.snapshots.isEmpty()) listOf(copy.authorUnknown, copy.licenseUnknown) else buildList {
            val snapshot = rights.snapshots.first()
            add(copy.format(copy.author, snapshot.author ?: copy.unknown))
            add(copy.format(copy.summaryLicense, snapshot.licenseName ?: copy.incomplete))
            snapshot.customAttribution?.let { add(copy.format(copy.customAttribution, it)) }
            if (rights.snapshots.size > 1) add(copy.format(copy.otherRecords, copy.count(rights.snapshots.size - 1)))
            if (rights.warnings.isNotEmpty()) add(copy.warnings)
        }
    fun details(snapshot: RightsSnapshot, copy: RightsPresentationCopy = RightsPresentationCopy()): List<String> = listOf(
        copy.format(copy.source, snapshot.sourceName),
        copy.format(copy.sourceIdentity, snapshot.providerId, snapshot.sourceNamespace, snapshot.sourceItemId),
        copy.format(copy.author, snapshot.author ?: copy.unknown),
        copy.format(copy.credit, snapshot.credit ?: copy.unknown),
        copy.format(copy.customAttribution, snapshot.customAttribution ?: copy.notProvided),
        copy.format(copy.license, snapshot.licenseName ?: copy.unknown),
        copy.format(copy.usageTerms, snapshot.usageTerms ?: copy.notProvided),
        copy.format(copy.copyrighted, triState(snapshot.copyrighted, copy)),
        copy.format(copy.attributionRequired, triState(snapshot.attributionRequired, copy)),
        copy.format(copy.nonFree, triState(snapshot.nonFree, copy)),
        copy.format(copy.restrictions, snapshot.restrictions.takeIf { it.isNotEmpty() }
            ?.joinToString(copy.restrictionSeparator) ?: copy.notProvided),
        copy.format(copy.deletionReason, snapshot.deletionReason ?: copy.notProvided),
        copy.format(copy.review, when (snapshot.review) {
            RightsReview.INCOMPLETE -> copy.reviewIncomplete
            RightsReview.REPORTED_REQUIRES_REVIEW -> copy.reviewReported
            RightsReview.FLAGGED_REQUIRES_REVIEW -> copy.reviewFlagged
        }),
        copy.format(copy.sourceRevision, snapshot.sourcePageRevisionId?.toString() ?: copy.unknown),
        // This is a lossless source-observation instant, not a locale-dependent wall-clock claim.
        copy.format(copy.observedAtUtc, java.time.Instant.ofEpochMilli(snapshot.retrievedAtEpochMillis).toString()),
    ) + snapshot.provenance.map {
        copy.format(copy.provenance, it.field, it.apiReportedSource ?: copy.unknown,
            if (it.kind == RightsMetadataKind.EXTRACTED_UNSTRUCTURED_TEXT) copy.unstructured else copy.structured,
            triState(it.htmlConvertedToText, copy), triState(it.truncated, copy))
    }
    fun safeLink(value: String?): String? = try {
        value?.takeIf { v -> v.length in 1..8192 && v.none { it.isISOControl() } }?.let { URI(it) }
            ?.takeIf { it.scheme == "https" && it.host != null && it.userInfo == null && it.port == -1 && !it.isOpaque }
            ?.toASCIIString()
    } catch (_: Exception) { null }
    private fun triState(value: Boolean?, copy: RightsPresentationCopy): String = when (value) {
        true -> copy.yes; false -> copy.no; null -> copy.unknown
    }
}
