package com.easysavevideo.discovery

import com.easysavevideo.app.discovery.*
import com.easysavevideo.app.storage.DiscoveryCheckpointCodec
import com.easysavevideo.commons.*
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.RepeatPolicy
import org.junit.Assert.*
import org.junit.Test

/** Independent injected host review. No Android execution, network, timers, or production configuration. */
class IndependentFilterReviewTest {
    private val commons = ProviderDescriptor(CommonsIdentity.PROVIDER_ID, CommonsIdentity.VERSION)
    private val demo = ProviderDescriptor("local-demo", "1")
    private fun profile(query: String = "  Ａ   Exact Query 😃  ", filters: SearchFilters = SearchFilters()) =
        SearchProfile("review-profile", query, "zh-Hant", filters, RepeatPolicy.SHOW_ALL, ResultSort.DURATION_LONGEST)
    private fun operation(): DiscoveryOperation {
        val cancellation = DiscoveryCancellation()
        return DiscoveryOperation(OperationContext("review-op", 16_000, cancellation), cancellation)
    }
    private class CountingTimer : DiscoveryTimer {
        var schedules = 0
        override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle {
            schedules++
            return CancellationHandle { }
        }
    }
    private class CountingRuntime : ProviderRuntime {
        var requests = 0
        override fun execute(provider: VideoSearchProvider, request: SearchRequest,
            operation: DiscoveryOperation, callback: (ProviderOutcome) -> Unit): CancellationHandle {
            requests++
            return CancellationHandle { operation.cancellation.cancel() }
        }
    }
    private class CountingTransport : CommonsTransport {
        var creates = 0
        override fun create(request: CommonsHttpRequest): CommonsHttpCall {
            creates++
            throw AssertionError("Independent filter test must never create a network transport")
        }
    }
    private fun source(transport: CountingTransport, timer: CountingTimer = CountingTimer()) =
        CommonsVideoSearchProvider(CommonsMetadataProvider(transport = transport, timer = timer, clock = EpochClock { 1_000 }))
    private fun raw(duration: Long? = null, namespace: String? = CommonsIdentity.PROVIDER_ID) = RawVideoResult(
        "https://commons.wikimedia.org/wiki/File:Review.webm", true, title = "review", sourceNamespace = namespace,
        sourceVideoId = "review-id", durationMs = duration)

    @Test fun onlyTheProvenCommonsBridgeIsSelectableInNormalMode() {
        val unknown = ProviderDescriptor("arbitrary-provider", "1")
        val lookalike = ProviderDescriptor("WIKIMEDIA-COMMONS", "1")
        val choices = DiscoveryFilterPresentationPolicy.admittedSources(listOf(unknown, commons, demo, lookalike, commons), false)
        assertEquals(listOf(DiscoverySourceChoice(CommonsIdentity.PROVIDER_ID, CommonsIdentity.SOURCE)), choices)
        assertEquals(listOf("arbitrary-provider", "local-demo", "WIKIMEDIA-COMMONS"),
            DiscoveryFilterPresentationPolicy.unknownDescriptorIds(listOf(unknown, commons, demo, lookalike), choices))
    }

    @Test fun demoMappingIsExplicitIsolatedAndAbsentFromNormalChoices() {
        assertEquals(emptyList<DiscoverySourceChoice>(), DiscoveryFilterPresentationPolicy.admittedSources(listOf(demo), false))
        val choice = DiscoveryFilterPresentationPolicy.admittedSources(listOf(commons, demo), true).single()
        assertEquals("local-demo", choice.namespace)
        assertTrue(choice.synthetic)
        assertTrue(DiscoveryFilterPresentationPolicy.capabilityText(choice).joinToString().contains("合成"))
        assertFalse(DiscoveryFilterPresentationPolicy.admittedSources(listOf(commons), true).any())
        assertFalse(DiscoveryFilterPresentationPolicy.admittedSources(emptyList(), false).any())
    }

    @Test fun absentAndObsoleteSourcesRemainExactUntilExplicitRemoval() {
        val original = profile(filters = SearchFilters(linkedSetOf("Legacy-Source", "WIKIMEDIA-COMMONS", "not-registered")))
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(original, false)
        assertEquals(original, draft.appliedProfile)
        assertEquals(original.filters, DiscoveryFilterPresentationPolicy.validate(draft).filters)
        assertEquals(listOf("Legacy-Source", "not-registered"), DiscoveryFilterPresentationPolicy.unavailableNamespaces(
            draft.sourceNamespaces, DiscoveryFilterPresentationPolicy.admittedSources(listOf(commons), false)))
        assertEquals(draft, DiscoveryFilterPresentationPolicy.reconcile(draft, original, false))
        assertEquals(original.filters.sourceNamespaces.sorted(), DiscoveryFilterPresentationPolicy.unavailableNamespaces(draft.sourceNamespaces, emptyList()))
        assertTrue(DiscoveryFilterPresentationPolicy.sourceSummary(original.filters, emptyList()).contains("筛选已保留"))
        val removed = DiscoveryFilterPresentationPolicy.removeSource(draft, "legacy-source")
        assertEquals(setOf("WIKIMEDIA-COMMONS", "not-registered"), removed.sourceNamespaces)
        assertEquals(original.filters.sourceNamespaces, draft.sourceNamespaces)
    }

    @Test fun sourceEditsAreCaseInsensitiveWithoutRewritingExistingNamespaceSpelling() {
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(profile(filters = SearchFilters(setOf("WIKIMEDIA-COMMONS"))), false)
        val choice = DiscoveryFilterPresentationPolicy.admittedSources(listOf(commons), false).single()
        assertTrue(DiscoveryFilterPresentationPolicy.isSelected(draft.sourceNamespaces, choice.namespace))
        assertSame(draft, DiscoveryFilterPresentationPolicy.addSource(draft, choice))
        assertTrue(DiscoveryFilterPresentationPolicy.removeSource(draft, choice.namespace).sourceNamespaces.isEmpty())
        val full = draft.copy(sourceNamespaces = (1..100).map { "source-$it" }.toSet())
        assertSame(full, DiscoveryFilterPresentationPolicy.addSource(full, choice))
    }

    @Test fun durationFormattingAndValidationRoundTripEveryBoundaryExactly() {
        val values = listOf(0L, 1L, 9L, 10L, 99L, 100L, 999L, 1_000L, 1_001L, 99_999L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        values.forEach { value ->
            val original = profile(filters = SearchFilters(minimumDurationMs = value, maximumDurationMs = value))
            val draft = DiscoveryFilterPresentationPolicy.fromProfile(original, false)
            assertTrue(draft.minimumSeconds.length <= DiscoveryFilterPresentationPolicy.MAX_DURATION_TEXT)
            assertEquals(original.filters, DiscoveryFilterPresentationPolicy.validate(draft).filters)
            assertFalse(DiscoveryFilterPresentationPolicy.isDirty(draft))
        }
        val random = java.util.Random(812_303)
        repeat(512) {
            val value = random.nextLong().ushr(1)
            val original = profile(filters = SearchFilters(minimumDurationMs = value, maximumDurationMs = value))
            assertEquals(original.filters, DiscoveryFilterPresentationPolicy.validate(
                DiscoveryFilterPresentationPolicy.fromProfile(original, false)).filters)
        }
    }

    @Test fun invalidDurationTextAndOverflowNeverChangeAppliedFilters() {
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(profile(), false)
        listOf("-1", "+1", " 1", "1 ", ".5", "1.", "1.0001", "1e3", "NaN", "Infinity", "９", "9223372036854775.808", "9".repeat(21)).forEach { text ->
            val invalid = draft.copy(minimumSeconds = text)
            assertNull(DiscoveryFilterPresentationPolicy.validate(invalid).filters)
            assertNotNull(DiscoveryFilterPresentationPolicy.validate(invalid).error)
            assertFalse(DiscoveryFilterPresentationPolicy.canSubmit(invalid))
            assertEquals(draft.appliedProfile, invalid.appliedProfile)
        }
        assertNull(DiscoveryFilterPresentationPolicy.validate(draft.copy(minimumSeconds = "1.002", maximumSeconds = "1.001")).filters)
        val exact = DiscoveryFilterPresentationPolicy.validate(draft.copy(minimumSeconds = "0001.1", maximumSeconds = "1.100"))
        assertEquals(1_100L, exact.filters!!.minimumDurationMs)
        assertEquals(1_100L, exact.filters!!.maximumDurationMs)
    }

    @Test fun unknownDurationPolicyIsIndependentOfInclusiveKnownBounds() {
        val filters = SearchFilters(minimumDurationMs = 1_001, maximumDurationMs = 1_999, includeUnknownDuration = true)
        assertNotNull(ResultNormalizer.normalize(raw(), "metadata-source", filters))
        assertNotNull(ResultNormalizer.normalize(raw(0), "metadata-source", filters))
        assertNull(ResultNormalizer.normalize(raw(), "metadata-source", filters.copy(includeUnknownDuration = false)))
        assertNull(ResultNormalizer.normalize(raw(0), "metadata-source", filters.copy(includeUnknownDuration = false)))
        assertNull(ResultNormalizer.normalize(raw(1_000), "metadata-source", filters))
        assertNotNull(ResultNormalizer.normalize(raw(1_001), "metadata-source", filters))
        assertNotNull(ResultNormalizer.normalize(raw(1_999), "metadata-source", filters))
        assertNull(ResultNormalizer.normalize(raw(2_000), "metadata-source", filters))
    }

    @Test fun resultNamespaceNeverFallsBackToProviderDescriptorIdentity() {
        val onlyCommons = SearchFilters(setOf(CommonsIdentity.PROVIDER_ID))
        assertNull(ResultNormalizer.normalize(raw(namespace = "other-namespace"), CommonsIdentity.PROVIDER_ID, onlyCommons))
        assertNull(ResultNormalizer.normalize(raw(namespace = null), CommonsIdentity.PROVIDER_ID, onlyCommons))
        assertNotNull(ResultNormalizer.normalize(raw(), "different-descriptor", onlyCommons))
        assertNull(ResultNormalizer.normalize(raw(), "different-descriptor", SearchFilters(setOf("missing-source"))))
    }

    @Test fun unsupportedCommonsSafeSearchRefusesWithZeroTransportAndNoTimer() {
        val transport = CountingTransport(); val timer = CountingTimer(); val provider = source(transport, timer)
        val safe = profile(filters = SearchFilters(safeSearch = true))
        assertEquals(DiscoveryError.UNSUPPORTED_FILTER, provider.refusal(safe))
        try { provider.search(SearchRequest(SessionStamp("review", 1), safe, null), operation()); fail("Expected refusal") }
        catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.UNSUPPORTED_FILTER, failure.code) }
        assertEquals(0, transport.creates)
        assertEquals(0, timer.schedules)
        val text = DiscoveryFilterPresentationPolicy.capabilityText(
            DiscoveryFilterPresentationPolicy.admittedSources(listOf(commons), false).single()).joinToString()
        assertTrue(text.contains("不支持安全搜索")); assertTrue(text.contains("不会向此来源发送查询"))
        assertTrue(text.contains("日期未知")); assertTrue(text.contains("可能未知"))
    }

    @Test fun unrelatedRetainedNamespaceReturnsNoNetworkWithoutBroadeningSearch() {
        val transport = CountingTransport(); val provider = source(transport)
        val applied = profile(filters = SearchFilters(setOf("obsolete-only"), safeSearch = false))
        val page = provider.search(SearchRequest(SessionStamp("review", 1), applied, null), operation())
        assertEquals(0, transport.creates)
        assertTrue(page.exhausted); assertTrue(page.results.isEmpty())
        assertEquals(setOf("obsolete-only"), applied.filters.sourceNamespaces)
    }

    @Test fun commonsBridgeDoesNotInventPublicationDateOrUnknownDuration() {
        val rights = CommonsRights("https://commons.wikimedia.org/wiki/File:Review.webm", null, 1_000,
            null, null, null, null, null, null, null, null, null, emptyList(), null,
            CommonsRightsReview.INCOMPLETE, emptyList())
        val metadata = CommonsVideoMetadata(42, "File:Review.webm", rights.sourcePageUrl, null, null, null, null, "video/webm", rights)
        val mapped = toDiscoveryResult(metadata)
        assertEquals(CommonsIdentity.PROVIDER_ID, mapped.sourceNamespace)
        assertNull(mapped.durationMs); assertNull(mapped.publishedAtEpochMillis)
        assertEquals("42", mapped.sourceVideoId)
        assertFalse(mapped.rights.snapshots.single().downloadAuthorized)
    }

    @Test fun editingAndPresentationDoNotRewriteQueryOrAppliedIdentity() {
        val applied = profile(filters = SearchFilters(setOf("Unregistered-Case"), 1, Long.MAX_VALUE, false, true))
        val original = DiscoveryFilterPresentationPolicy.fromProfile(applied, false)
        val edited = original.copy(query = "  New   Ｂ 😃  ", maximumSeconds = "2.002")
        assertEquals(applied, edited.appliedProfile)
        assertEquals(applied.queryKey, edited.appliedProfile.queryKey)
        DiscoveryFilterPresentationPolicy.validate(edited)
        DiscoveryFilterPresentationPolicy.sourceSummary(applied.filters, emptyList())
        DiscoveryFilterPresentationPolicy.durationSummary(applied.filters)
        assertEquals("  New   Ｂ 😃  ", edited.query)
        assertEquals(applied.query, original.query)
        assertTrue(DiscoveryFilterPresentationPolicy.isDirty(edited))
        assertTrue(DiscoveryFilterPresentationPolicy.canSubmit(edited))
    }

    @Test fun navigationReconciliationAndSortHistoryTicksPreserveUnsavedDraft() {
        val applied = profile()
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(applied, false).copy(query = "local edit", minimumSeconds = "invalid", sourceNamespaces = setOf("old-source"))
        repeat(100) {
            assertSame(draft, DiscoveryFilterPresentationPolicy.reconcile(draft,
                applied.copy(sortOrder = ResultSort.NEWEST, historyPolicy = RepeatPolicy.HIDE_SHOWN), false))
        }
        assertEquals("invalid", draft.minimumSeconds)
        assertEquals("local edit", draft.query)
    }

    @Test fun externalAppliedIdentityAndDemoChangesResetDraftToExactNewProfile() {
        val applied = profile()
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(applied, false).copy(query = "unsaved")
        listOf(applied.copy(profileId = "another"), applied.copy(query = "other"), applied.copy(locale = "en"),
            applied.copy(filters = applied.filters.copy(includeUnknownDuration = false))).forEach { changed ->
            assertEquals(DiscoveryFilterPresentationPolicy.fromProfile(changed, false), DiscoveryFilterPresentationPolicy.reconcile(draft, changed, false))
        }
        assertEquals(DiscoveryFilterPresentationPolicy.fromProfile(applied, true), DiscoveryFilterPresentationPolicy.reconcile(draft, applied, true))
    }

    @Test fun blankAndMalformedQueriesCannotBeSubmitted() {
        val baseline = DiscoveryFilterPresentationPolicy.fromProfile(profile(), false)
        listOf("", "   ", "bad\uD800", "bad\uDC00", "a".repeat(513)).forEach { query ->
            val draft = baseline.copy(query = query)
            assertFalse(DiscoveryFilterPresentationPolicy.canSubmit(draft))
        }
    }

    @Test fun appliedCheckpointRoundTripAndZeroProviderRestoreKeepEveryFilterByte() {
        val applied = profile(filters = SearchFilters(linkedSetOf("OBSOLETE-A", "wikimedia-commons", "Unknown-b"), 1, Long.MAX_VALUE, false, true))
        val checkpoint = DiscoveryCheckpoint(SessionStamp("review-session", 7), applied)
        val bytes = DiscoveryCheckpointCodec.encode(checkpoint)
        val decoded = DiscoveryCheckpointCodec.decode(bytes)
        assertEquals(checkpoint, decoded)
        assertArrayEquals(bytes, DiscoveryCheckpointCodec.encode(decoded))
        val store = InMemoryDiscoveryStore(); store.transaction { putCheckpoint(decoded) }
        val runtime = CountingRuntime(); val timer = CountingTimer()
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(), runtime, store, timer, EpochClock { 1_000 })
        assertTrue(coordinator.restore("review-session"))
        val restored = coordinator.currentCheckpoint()!!
        assertEquals(applied, restored.profile)
        assertEquals(applied.queryKey, restored.profile.queryKey)
        assertEquals(8L, restored.stamp.epoch)
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(restored.profile, false)
        assertEquals(applied.filters, DiscoveryFilterPresentationPolicy.validate(draft).filters)
        assertEquals(applied.query, draft.query)
        assertEquals(0, runtime.requests); assertEquals(0, timer.schedules)
        coordinator.close()
    }

    @Test fun filterPresentationAndLocalCountReadsDoNotInitializeOrDemandASource() {
        val transport = CountingTransport(); val provider = source(transport)
        val runtime = CountingRuntime(); val timer = CountingTimer(); val store = InMemoryDiscoveryStore()
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(provider)), runtime, store, timer, EpochClock { 1_000 })
        val applied = profile(filters = SearchFilters(safeSearch = false))
        coordinator.start("review-session", applied) // Foreground/visibility remain closed.
        val before = coordinator.currentCheckpoint()
        repeat(32) {
            val draft = DiscoveryFilterPresentationPolicy.fromProfile(applied, false).copy(sourceNamespaces = setOf("obsolete-source"), minimumSeconds = "1.001")
            DiscoveryFilterPresentationPolicy.validate(draft)
            DiscoveryFilterPresentationPolicy.admittedSources(listOf(provider.descriptor), false)
            DiscoveryFilterPresentationPolicy.reconcile(draft, applied, false)
            assertNotNull(coordinator.historyStatistics(applied.profileId))
            assertNotNull(coordinator.historyStatistics())
            assertFalse(DiscoveryPresentationPolicy.mayFulfillPageDemand(true, true, true, true, false, true, true))
        }
        assertEquals(before, coordinator.currentCheckpoint())
        assertEquals(0, runtime.requests); assertEquals(0, timer.schedules); assertEquals(0, transport.creates)
        coordinator.close()
    }

    @Test fun filterAndClaimDiagnosticsRedactQueryAndRetainedNamespaces() {
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(profile("secret query", SearchFilters(setOf("secret-source"))), false)
        val claim = DiscoveryFilterClaim(target(draft.appliedProfile), false, draft)
        listOf(draft.toString(), claim.toString()).forEach {
            assertFalse(it.contains("secret query")); assertFalse(it.contains("secret-source")); assertTrue(it.contains("redacted"))
        }
    }

    private fun target(profile: SearchProfile) = DiscoveryControlTarget(profile.profileId,
        SessionStamp("review-session", 7), 3, 11)

    private fun valid(claim: DiscoveryFilterClaim, draft: DiscoveryFilterDraft? = claim.draft,
        target: DiscoveryControlTarget? = claim.target, profile: SearchProfile = claim.draft.appliedProfile,
        demo: Boolean = claim.demo, ready: Boolean = true, unavailable: Boolean = false, busy: Boolean = false) =
        DiscoveryFilterPresentationPolicy.validClaim(claim, draft, target, profile, demo, ready, unavailable, busy)

    @Test fun claimsFenceEveryTargetComponentIncludingSameProfileSourceReinitialization() {
        val applied = profile()
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(applied, false).copy(query = "edited")
        val claim = DiscoveryFilterClaim(target(applied), false, draft)
        assertTrue(valid(claim))
        listOf(claim.target.copy(profileId = "another"),
            claim.target.copy(stamp = SessionStamp("another-session", 7)),
            claim.target.copy(stamp = SessionStamp("review-session", 8)),
            claim.target.copy(viewRevision = 4),
            claim.target.copy(controllerGeneration = 12)).forEach { changed ->
            assertFalse(valid(claim, target = changed))
        }
        assertFalse(valid(claim, target = null))
        assertFalse(valid(claim.copy(target = claim.target.copy(profileId = "inconsistent-target"))))
        assertFalse(valid(claim, draft = null))
        assertFalse(valid(claim, draft = draft.copy(query = "new edit")))
        assertFalse(valid(claim, draft = draft.copy(sourceNamespaces = setOf("changed"))))
    }

    @Test fun equalQueriesAndFiltersNeverMakeAnUnrelatedProfileOrLocaleAValidClaim() {
        val applied = profile()
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(applied, false)
        val claim = DiscoveryFilterClaim(target(applied), false, draft)
        assertFalse(valid(claim, profile = applied.copy(profileId = "different-profile")))
        assertFalse(valid(claim, profile = applied.copy(locale = "en")))
        assertFalse(valid(claim, profile = applied.copy(query = applied.query.trim())))
        assertFalse(valid(claim, profile = applied.copy(filters = applied.filters.copy(safeSearch = false))))
        assertTrue(valid(claim, profile = applied.copy(sortOrder = ResultSort.NEWEST, historyPolicy = RepeatPolicy.HIDE_SHOWN)))
    }

    @Test fun availabilityBusyAndDemoSwitchesRejectCapturedClaimsBeforeAnyCommit() {
        val applied = profile()
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(applied, false)
        val claim = DiscoveryFilterClaim(target(applied), false, draft)
        assertFalse(valid(claim, ready = false))
        assertFalse(valid(claim, unavailable = true))
        assertFalse(valid(claim, busy = true))
        assertFalse(valid(claim, demo = true))
        assertFalse(valid(claim.copy(demo = true)))
        assertFalse(valid(claim.copy(draft = draft.copy(demo = true))))
    }

    @Test fun twoCallbacksWithOneCapturedClaimAreFencedByImmediateBusyAndCurrentDraft() {
        val applied = profile()
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(applied, false).copy(query = "  new exact query  ")
        val claim = DiscoveryFilterClaim(target(applied), false, draft)
        var busy = false
        var accepted = 0
        repeat(64) {
            if (valid(claim, busy = busy) && DiscoveryFilterPresentationPolicy.canSubmit(claim.draft)) {
                busy = true
                accepted++
            }
        }
        assertEquals(1, accepted)
        assertEquals("  new exact query  ", draft.query)
        assertFalse(valid(claim, draft = draft.copy(minimumSeconds = "1.001")))
        // Releasing busy does not revive a claim whose committed target has changed.
        busy = false
        assertFalse(valid(claim, busy = busy, target = claim.target.copy(stamp = SessionStamp("review-session", 8))))
    }

    @Test fun safetyConfirmationOnlyChangesTheExactClaimedDraftOnceAndNeverAppliesSearch() {
        val applied = profile()
        var current = DiscoveryFilterPresentationPolicy.fromProfile(applied, false).copy(query = "not yet applied", sourceNamespaces = setOf("retained"))
        val claim = DiscoveryFilterClaim(target(applied), false, current)
        var changes = 0
        repeat(64) {
            if (valid(claim, draft = current)) {
                current = claim.draft.copy(safeSearch = false)
                changes++
            }
        }
        assertEquals(1, changes)
        assertFalse(current.safeSearch)
        assertEquals(applied, current.appliedProfile)
        assertEquals("not yet applied", current.query)
        assertEquals(setOf("retained"), current.sourceNamespaces)
        assertTrue(current.appliedProfile.filters.safeSearch)
        assertTrue(DiscoveryFilterPresentationPolicy.isDirty(current))
    }

    @Test fun fromProfileDetachesMutableNamespaceInputsAndReconstructionDropsOnlyUnsavedEdits() {
        val namespaces = linkedSetOf("retained-source")
        val applied = profile(filters = SearchFilters(namespaces))
        val draft = DiscoveryFilterPresentationPolicy.fromProfile(applied, false)
        namespaces += "later-mutation"
        assertEquals(setOf("retained-source"), draft.appliedProfile.filters.sourceNamespaces)
        assertEquals(setOf("retained-source"), draft.sourceNamespaces)
        val dirty = draft.copy(query = "unsaved", minimumSeconds = "bad")
        val restored = DiscoveryFilterPresentationPolicy.fromProfile(dirty.appliedProfile, false)
        assertEquals(draft, restored)
        assertEquals(draft.appliedProfile.query, restored.query)
        assertFalse(DiscoveryFilterPresentationPolicy.isDirty(restored))
    }

    @Test fun unavailableOrUninitializedPublishedPlaceholdersNeverEraseKnownAppliedOrDraftData() {
        val applied = profile(filters = SearchFilters(setOf("obsolete-source"), 1, Long.MAX_VALUE, false, true))
        val dirty = DiscoveryFilterPresentationPolicy.fromProfile(applied, false).copy(query = "unsaved exact query", maximumSeconds = "bad")
        val placeholder = SearchProfile("local", "")
        assertSame(dirty, DiscoveryFilterPresentationPolicy.reconcile(dirty, placeholder, true, appliedReadable = false))
        assertEquals(applied.filters, dirty.appliedProfile.filters)
        assertEquals("unsaved exact query", dirty.query)
        assertEquals("bad", dirty.maximumSeconds)
        val claim = DiscoveryFilterClaim(target(applied), false, dirty)
        assertFalse(valid(claim, profile = placeholder, ready = false))
        assertFalse(valid(claim, unavailable = true))
        assertSame(dirty, DiscoveryFilterPresentationPolicy.reconcile(dirty, applied, false))
    }

    private fun textEdit(claim: DiscoveryFilterClaim, current: DiscoveryFilterDraft?, field: DiscoveryFilterTextField,
        text: String, target: DiscoveryControlTarget? = claim.target, profile: SearchProfile = claim.draft.appliedProfile,
        demo: Boolean = claim.demo, ready: Boolean = true, unavailable: Boolean = false, busy: Boolean = false) =
        DiscoveryFilterPresentationPolicy.editText(claim, current, target, profile, demo, ready, unavailable, busy, field, text)

    @Test fun rapidImeUpdatesUseNewestFieldValueAndPreserveOtherConcurrentDraftFields() {
        val applied = profile()
        val captured = DiscoveryFilterPresentationPolicy.fromProfile(applied, false)
        val claim = DiscoveryFilterClaim(target(applied), false, captured)
        var current = textEdit(claim, captured, DiscoveryFilterTextField.QUERY, "a")!!
        current = current.copy(sourceNamespaces = setOf("kept-source"), safeSearch = false)
        current = textEdit(claim, current, DiscoveryFilterTextField.MINIMUM_SECONDS, "1")!!
        current = textEdit(claim, current, DiscoveryFilterTextField.QUERY, "ab")!!
        current = textEdit(claim, current, DiscoveryFilterTextField.MAXIMUM_SECONDS, "2.001")!!
        current = textEdit(claim, current, DiscoveryFilterTextField.MINIMUM_SECONDS, "1.001")!!
        assertEquals("ab", current.query)
        assertEquals("1.001", current.minimumSeconds)
        assertEquals("2.001", current.maximumSeconds)
        assertEquals(setOf("kept-source"), current.sourceNamespaces)
        assertFalse(current.safeSearch)
        assertEquals(applied, current.appliedProfile)
        // Search and safety confirmations do not inherit the permissive per-field edit semantics.
        assertFalse(valid(claim, draft = current))
        assertEquals(SearchFilters(setOf("kept-source"), 1_001, 2_001, true, false),
            DiscoveryFilterPresentationPolicy.validate(current).filters)
    }

    @Test fun rapidTextUpdatesStillRejectStaleSourceProfileDemoBusyAndUnreadableTargets() {
        val applied = profile()
        val captured = DiscoveryFilterPresentationPolicy.fromProfile(applied, false)
        val claim = DiscoveryFilterClaim(target(applied), false, captured)
        val current = captured.copy(query = "first update")
        assertNull(textEdit(claim, current, DiscoveryFilterTextField.QUERY, "second", target = null))
        assertNull(textEdit(claim, current, DiscoveryFilterTextField.QUERY, "second", target = claim.target.copy(controllerGeneration = 12)))
        assertNull(textEdit(claim, current, DiscoveryFilterTextField.QUERY, "second", profile = applied.copy(locale = "en")))
        assertNull(textEdit(claim, current, DiscoveryFilterTextField.QUERY, "second", demo = true))
        assertNull(textEdit(claim, current, DiscoveryFilterTextField.QUERY, "second", ready = false))
        assertNull(textEdit(claim, current, DiscoveryFilterTextField.QUERY, "second", unavailable = true))
        assertNull(textEdit(claim, current, DiscoveryFilterTextField.QUERY, "second", busy = true))
        assertNull(textEdit(claim, null, DiscoveryFilterTextField.QUERY, "second"))
        assertNull(textEdit(claim, current.copy(appliedProfile = applied.copy(profileId = "another")), DiscoveryFilterTextField.QUERY, "second"))
        assertEquals("first update", current.query)
    }

    @Test fun textEditsKeepExistingUnicodeBoundsAndShowInvalidDurationWithoutApplyingIt() {
        val applied = profile()
        val current = DiscoveryFilterPresentationPolicy.fromProfile(applied, false)
        val claim = DiscoveryFilterClaim(target(applied), false, current)
        val bounded = textEdit(claim, current, DiscoveryFilterTextField.QUERY, "a".repeat(511) + "😃tail")!!
        assertEquals("a".repeat(511), bounded.query)
        assertNull(textEdit(claim, current, DiscoveryFilterTextField.MINIMUM_SECONDS, "1".repeat(21)))
        assertNull(textEdit(claim, current, DiscoveryFilterTextField.MAXIMUM_SECONDS, "2".repeat(21)))
        val invalid = textEdit(claim, current, DiscoveryFilterTextField.MINIMUM_SECONDS, "-2")!!
        assertEquals("-2", invalid.minimumSeconds)
        assertNull(DiscoveryFilterPresentationPolicy.validate(invalid).filters)
        assertEquals(applied, invalid.appliedProfile)
    }

    @Test fun nativeSavedAndPlayedPresentationRemainUnknown() {
        val notice = DiscoveryFilterPresentationPolicy.nativeHistoryNotice()
        assertTrue(notice.contains("SAVED")); assertTrue(notice.contains("PLAYED")); assertTrue(notice.contains("UNKNOWN"))
        assertTrue(notice.contains("不能证明"))
    }
}
