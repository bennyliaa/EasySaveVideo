package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*
import java.text.NumberFormat
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPresentationLocalizationTest {
    private val folders = listOf("values", "values-zh-rCN")
    private fun progress(state: ProviderDiscoveryState, error: DiscoveryError? = null,
        continuation: Boolean = false, success: Long? = null) =
        ProviderDiscoveryProgress("source-%1\$s-来源-👋", state, continuation, success, null, 0, error)
    private fun snapshot() = RightsSnapshot(
        providerId = "provider-%1\$s-来源", sourceName = "Source 原文 <b>100%</b>",
        sourceNamespace = "namespace-%2\$s", sourceItemId = "item-原文", sourcePageUrl = "https://example.test/item",
        sourcePageRevisionId = 1234567890L, retrievedAtEpochMillis = 1234L,
        author = "Author 原文 <script>%1\$s</script>", credit = "Credit 原文 & 100%", customAttribution = "Custom 原文\n%2\$s",
        licenseName = "License 原文 %n", usageTerms = "Terms 原文 %s", copyrighted = true,
        attributionRequired = false, nonFree = null, restrictions = listOf("Restriction 原文 1", "Restriction 原文 2"),
        deletionReason = "Warning 原文 %n", review = RightsReview.FLAGGED_REQUIRES_REVIEW,
        provenance = listOf(RightsFieldProvenance("Field 原文 %s", "API 原文 %1\$s", htmlConvertedToText = true, truncated = false)))

    @Test fun everySortRetainsItsDistinctMeaningInBothResourceLocales() {
        for (folder in folders) {
            val copy = discoveryPresentationFixture(folder)
            assertEquals(listOf(copy.sortRelevance, copy.sortNewest, copy.sortShortest, copy.sortLongest),
                ResultSort.entries.map { DiscoveryPresentationPolicy.sortLabel(it, copy) })
            assertEquals(4, ResultSort.entries.map { DiscoveryPresentationPolicy.sortLabel(it, copy) }.toSet().size)
        }
        assertEquals("Source order", DiscoveryPresentationPolicy.sortLabel(ResultSort.RELEVANCE, discoveryPresentationFixture("values")))
        assertEquals("来源顺序", DiscoveryPresentationPolicy.sortLabel(ResultSort.RELEVANCE, discoveryPresentationFixture("values-zh-rCN")))
    }

    @Test fun eachProviderStateAndBlockedErrorRetainsTheExactSemanticBranch() {
        for (folder in folders) {
            val copy = discoveryPresentationFixture(folder)
            val states = mapOf(
                ProviderDiscoveryState.NOT_STARTED to copy.providerNotStarted,
                ProviderDiscoveryState.REQUESTING to copy.providerRequesting,
                ProviderDiscoveryState.RETRY_WAIT to copy.providerRetryWait,
                ProviderDiscoveryState.COOLDOWN to copy.providerCooldown,
                ProviderDiscoveryState.BLOCKED to copy.providerUnavailable,
                ProviderDiscoveryState.READY to copy.providerReady,
                ProviderDiscoveryState.EXHAUSTED to copy.providerExhausted,
                ProviderDiscoveryState.CURSOR_EXPIRED to copy.providerCursorExpired,
                ProviderDiscoveryState.CURSOR_INCOMPATIBLE to copy.providerCursorIncompatible,
                ProviderDiscoveryState.SUSPENDED to copy.providerSuspended,
                ProviderDiscoveryState.STORAGE_UNAVAILABLE to copy.providerStorageUnavailable)
            assertEquals(ProviderDiscoveryState.entries.toSet(), states.keys)
            for ((state, label) in states) {
                val progress = progress(state)
                assertEquals(copy.format(copy.providerProgress, progress.providerId, label, "", ""),
                    DiscoveryPresentationPolicy.providerProgressText(progress, copy))
            }
            for (error in DiscoveryError.entries + listOf(null)) {
                val progress = progress(ProviderDiscoveryState.BLOCKED, error)
                val label = when (error) {
                    DiscoveryError.UNSUPPORTED_FILTER -> copy.providerUnsupportedFilter
                    DiscoveryError.INVALID_QUERY -> copy.providerInvalidQuery
                    else -> copy.providerUnavailable
                }
                assertEquals(copy.format(copy.providerProgress, progress.providerId, label, "", ""),
                    DiscoveryPresentationPolicy.providerProgressText(progress, copy))
            }
        }
    }

    @Test fun providerCommittedAndContinuationFactsStayIndependentWithoutInventedCounts() {
        for (folder in folders) for (state in ProviderDiscoveryState.entries) {
            val copy = discoveryPresentationFixture(folder)
            for (success in listOf(null, 0L, 1234L)) for (continuation in listOf(false, true)) {
                val progress = progress(state, continuation = continuation, success = success)
                val rendered = DiscoveryPresentationPolicy.providerProgressText(progress, copy)
                assertTrue(rendered.contains(progress.providerId))
                assertEquals(success != null, rendered.contains(copy.committedPage))
                assertEquals(continuation, rendered.contains(copy.savedContinuation))
                // The only percent sign belongs to the external provider ID.
                assertFalse(rendered.removeRange(rendered.indexOf(progress.providerId),
                    rendered.indexOf(progress.providerId) + progress.providerId.length).contains('%'))
            }
        }
    }

    @Test fun allLocalCountsAndActualLimitsUseCapturedLocaleFormatting() {
        val stats = HistoryStatistics(0, 1, 1234, 65536, Int.MAX_VALUE)
        val policy = DiscoveryRetentionPolicy(1001, 65536, 2002, 3003)
        for (folder in folders) {
            val copy = discoveryPresentationFixture(folder)
            val numbers = NumberFormat.getIntegerInstance(copy.locale)
            val label = "Local 原文 %1\$s"
            assertEquals(copy.format(copy.statistics, label, numbers.format(0), numbers.format(1), numbers.format(1234),
                numbers.format(65536), numbers.format(Int.MAX_VALUE)), DiscoveryPresentationPolicy.statisticsText(label, stats, copy))
            assertEquals(copy.format(copy.retention, numbers.format(1001), numbers.format(65536), numbers.format(2002),
                numbers.format(3003)), DiscoveryPresentationPolicy.retentionNotice(policy, copy))
            assertTrue(DiscoveryPresentationPolicy.statisticsText(label, stats, copy).contains("1,234"))
        }
        // Formatting follows the captured display locale, not the process default or ASCII conversion.
        val french = discoveryPresentationFixture("values", Locale.FRANCE)
        assertTrue(DiscoveryPresentationPolicy.statisticsText("Local", stats, french)
            .contains(NumberFormat.getIntegerInstance(Locale.FRANCE).format(65536)))
    }

    @Test fun retentionInBothLanguagesStillDisclosesCapacityProtectionAndIndependentHistory() {
        val en = DiscoveryPresentationPolicy.retentionNotice(copy = discoveryPresentationFixture("values"))
        listOf("without age-based expiry", "oldest local changes", "currently materialized pages is protected",
            "Old results may reappear", "stored independently", "does not add display, open, play or save events",
            "or delete media files or Vault").forEach { assertTrue(it, en.contains(it)) }
        val zh = DiscoveryPresentationPolicy.retentionNotice(copy = discoveryPresentationFixture("values-zh-rCN"))
        listOf("不按天数过期", "最久未在本地变更", "当前已生成页面的数据受保护", "旧结果可能再次出现", "独立保存",
            "不会因此补记", "不会删除媒体文件或 Vault").forEach { assertTrue(it, zh.contains(it)) }
    }

    @Test fun rightsEmptyMissingMultipleAndWarningBranchesRemainConservative() {
        for (folder in folders) {
            val copy = rightsPresentationFixture(folder)
            assertEquals(listOf(copy.authorUnknown, copy.licenseUnknown), RightsPresentationPolicy.summary(ResultRights(), copy))
            assertEquals(listOf(copy.authorUnknown, copy.licenseUnknown),
                RightsPresentationPolicy.summary(ResultRights(warnings = RightsWarning.entries.toSet()), copy))
            val absent = snapshot().copy(author = null, licenseName = null, customAttribution = null)
            assertEquals(listOf(copy.format(copy.author, copy.unknown), copy.format(copy.summaryLicense, copy.incomplete)),
                RightsPresentationPolicy.summary(ResultRights(listOf(absent)), copy))
            for (count in listOf(1, 2, 1235)) for (warning in listOf(null) + RightsWarning.entries) {
                val result = ResultRights(List(count) { absent }, warning?.let { setOf(it) }.orEmpty())
                val output = RightsPresentationPolicy.summary(result, copy)
                assertEquals(2 + (if (count > 1) 1 else 0) + (if (warning != null) 1 else 0), output.size)
                assertEquals(warning != null, copy.warnings in output)
                if (count > 1) assertTrue(output.contains(copy.format(copy.otherRecords, copy.count(count - 1))))
                assertFalse(result.downloadAuthorized)
                assertTrue(result.requiresReview)
            }
        }
    }

    @Test fun rightsNeverMergeDifferentSnapshotsOrNormalizeExternalContent() {
        val first = snapshot()
        val later = snapshot().copy(author = "Different author", licenseName = "Different license")
        for (folder in folders) {
            val copy = rightsPresentationFixture(folder)
            val summary = RightsPresentationPolicy.summary(ResultRights(listOf(first, later)), copy)
            assertTrue(summary.contains(copy.format(copy.author, requireNotNull(first.author))))
            assertTrue(summary.contains(copy.format(copy.summaryLicense, requireNotNull(first.licenseName))))
            assertTrue(summary.contains(copy.format(copy.customAttribution, requireNotNull(first.customAttribution))))
            assertFalse(summary.any { it.contains("Different author") || it.contains("Different license") })
            val details = RightsPresentationPolicy.details(first, copy).joinToString("\n")
            listOf(first.providerId, first.sourceName, first.sourceNamespace, first.sourceItemId, first.author,
                first.credit, first.customAttribution, first.licenseName, first.usageTerms, first.deletionReason,
                first.provenance.single().field, first.provenance.single().apiReportedSource).forEach {
                val original = requireNotNull(it)
                assertTrue(original, details.contains(original))
            }
            first.restrictions.forEach { assertTrue(details.contains(it)) }
            assertTrue(details.contains("1234567890")) // A revision is an external ID, not a grouped quantity.
            assertTrue(details.contains("1970-01-01T00:00:01.234Z")) // UTC instant keeps exact millisecond precision.
        }
    }

    @Test fun allRightsTriStatesReviewsAndProvenanceKindsUseExplicitCopy() {
        for (folder in folders) {
            val copy = rightsPresentationFixture(folder)
            val booleans = mapOf<Boolean?, String>(true to copy.yes, false to copy.no, null to copy.unknown)
            val reviews = mapOf(RightsReview.INCOMPLETE to copy.reviewIncomplete,
                RightsReview.REPORTED_REQUIRES_REVIEW to copy.reviewReported,
                RightsReview.FLAGGED_REQUIRES_REVIEW to copy.reviewFlagged)
            assertEquals(RightsReview.entries.toSet(), reviews.keys)
            for ((value, label) in booleans) for ((review, reviewText) in reviews) {
                val lines = RightsPresentationPolicy.details(snapshot().copy(copyrighted = value, attributionRequired = value,
                    nonFree = value, review = review), copy)
                assertTrue(lines.contains(copy.format(copy.copyrighted, label)))
                assertTrue(lines.contains(copy.format(copy.attributionRequired, label)))
                assertTrue(lines.contains(copy.format(copy.nonFree, label)))
                assertTrue(lines.contains(copy.format(copy.review, reviewText)))
            }
            for (kind in RightsMetadataKind.entries) for (converted in listOf(false, true)) for (truncated in listOf(false, true)) {
                val provenance = RightsFieldProvenance("field", kind = kind, htmlConvertedToText = converted, truncated = truncated)
                val lines = RightsPresentationPolicy.details(snapshot().copy(provenance = listOf(provenance)), copy)
                assertEquals(copy.format(copy.provenance, "field", copy.unknown,
                    if (kind == RightsMetadataKind.EXTRACTED_UNSTRUCTURED_TEXT) copy.unstructured else copy.structured,
                    booleans.getValue(converted), booleans.getValue(truncated)), lines.last())
            }
        }
    }

    @Test fun nullsAndEmptyExternalStringsKeepTheirDifferentExistingMeaning() {
        for (folder in folders) {
            val copy = rightsPresentationFixture(folder)
            val empty = snapshot().copy(author = "", credit = "", customAttribution = "", licenseName = "", usageTerms = "",
                restrictions = listOf(""), deletionReason = "")
            val emptyDetails = RightsPresentationPolicy.details(empty, copy)
            for (template in listOf(copy.author, copy.credit, copy.customAttribution, copy.license, copy.usageTerms,
                copy.restrictions, copy.deletionReason)) assertTrue(emptyDetails.contains(copy.format(template, "")))
            val absent = empty.copy(author = null, credit = null, customAttribution = null, licenseName = null,
                usageTerms = null, restrictions = emptyList(), deletionReason = null, sourcePageRevisionId = null)
            val lines = RightsPresentationPolicy.details(absent, copy)
            for (template in listOf(copy.author, copy.credit, copy.license, copy.sourceRevision))
                assertTrue(lines.contains(copy.format(template, copy.unknown)))
            for (template in listOf(copy.customAttribution, copy.usageTerms, copy.restrictions, copy.deletionReason))
                assertTrue(lines.contains(copy.format(template, copy.notProvided)))
        }
    }

    @Test fun languageRemapKeepsInputModelsUnchangedAndTheOldSnapshotIndependent() {
        val rights = ResultRights(listOf(snapshot()), setOf(RightsWarning.PRIOR_REVIEW_WARNING))
        val before = rights.detached()
        val progress = progress(ProviderDiscoveryState.READY, continuation = true, success = 0)
        val enRights = rightsPresentationFixture("values")
        val enProgress = discoveryPresentationFixture("values")
        val firstRights = RightsPresentationPolicy.details(rights.snapshots.single(), enRights)
        val firstProgress = DiscoveryPresentationPolicy.providerProgressText(progress, enProgress)
        assertNotEquals(firstRights, RightsPresentationPolicy.details(rights.snapshots.single(), rightsPresentationFixture("values-zh-rCN")))
        assertNotEquals(firstProgress, DiscoveryPresentationPolicy.providerProgressText(progress, discoveryPresentationFixture("values-zh-rCN")))
        assertEquals(firstRights, RightsPresentationPolicy.details(rights.snapshots.single(), enRights))
        assertEquals(firstProgress, DiscoveryPresentationPolicy.providerProgressText(progress, enProgress))
        assertEquals(before, rights)
        assertEquals(progress.copy(), progress)
    }

    @Test fun noCopyDefaultsRemainExactlyTheHistoricalChineseCompatibilitySnapshot() {
        val discovery = discoveryPresentationFixture("values-zh-rCN").copy(locale = Locale.ROOT, localizedCounts = false)
        val rights = rightsPresentationFixture("values-zh-rCN").copy(locale = Locale.ROOT, localizedCounts = false)
        assertEquals(DiscoveryPresentationCopy(), discovery)
        assertEquals(RightsPresentationCopy(), rights)
        for (sort in ResultSort.entries) assertEquals(DiscoveryPresentationPolicy.sortLabel(sort), DiscoveryPresentationPolicy.sortLabel(sort, discovery))
        for (state in ProviderDiscoveryState.entries) for (error in DiscoveryError.entries + listOf(null)) {
            val progress = progress(state, error, continuation = true, success = 1)
            assertEquals(DiscoveryPresentationPolicy.providerProgressText(progress), DiscoveryPresentationPolicy.providerProgressText(progress, discovery))
        }
        assertEquals(DiscoveryPresentationPolicy.retentionNotice(), DiscoveryPresentationPolicy.retentionNotice(copy = discovery))
        val statistics = HistoryStatistics(1, 2, 3, 65536, 5)
        assertEquals(DiscoveryPresentationPolicy.statisticsText("当前资料", statistics),
            DiscoveryPresentationPolicy.statisticsText("当前资料", statistics, discovery))
        assertTrue(DiscoveryPresentationPolicy.retentionNotice().contains("65536"))
        for (value in listOf(ResultRights(), ResultRights(listOf(snapshot(), snapshot()), RightsWarning.entries.toSet()))) {
            assertEquals(RightsPresentationPolicy.summary(value), RightsPresentationPolicy.summary(value, rights))
        }
        assertEquals(RightsPresentationPolicy.details(snapshot()), RightsPresentationPolicy.details(snapshot(), rights))
    }

    @Test fun explicitLinkAdmissionRemainsHttpsOnlyWithoutCredentialsPortsControlsOrOversize() {
        for (value in listOf(null, "", "javascript:alert(1)", "data:text/html,hello", "http://example.test/",
            "HTTPS://example.test/", "https://name:pass@example.test/", "https://example.test:443/",
            "https://example.test/\n", "https://example.test/" + "x".repeat(8192))) assertNull(RightsPresentationPolicy.safeLink(value))
        for (value in listOf("https://example.test/", "https://example.test/path?q=100%25#fragment"))
            assertEquals(value, RightsPresentationPolicy.safeLink(value))
        assertEquals("https://example.test/%E5%8E%9F%E6%96%87", RightsPresentationPolicy.safeLink("https://example.test/原文"))
    }
}
