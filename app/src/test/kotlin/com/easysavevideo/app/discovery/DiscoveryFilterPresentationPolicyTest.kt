package com.easysavevideo.app.discovery

import com.easysavevideo.app.storage.DiscoveryCheckpointCodec
import com.easysavevideo.commons.CommonsIdentity
import com.easysavevideo.core.model.RepeatPolicy
import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryFilterPresentationPolicyTest {
    private val policy = DiscoveryFilterPresentationPolicy
    private val commons = ProviderDescriptor(CommonsIdentity.PROVIDER_ID, CommonsIdentity.VERSION)
    private val demo = ProviderDescriptor("local-demo", "1")
    private fun profile(filters: SearchFilters = SearchFilters()) = SearchProfile("profile", "A query", "zh", filters)

    @Test fun choicesRequireExactAlreadyRegisteredAdmittedDescriptor() {
        assertTrue(policy.admittedSources(emptyList(), false).isEmpty())
        assertTrue(policy.admittedSources(listOf(ProviderDescriptor("unknown", "1")), false).isEmpty())
        assertTrue(policy.admittedSources(listOf(ProviderDescriptor("commons", "1")), false).isEmpty())
        val choice = policy.admittedSources(listOf(commons), false).single()
        assertEquals("wikimedia-commons", choice.namespace)
        assertEquals(CommonsIdentity.SOURCE, choice.label)
        assertFalse(choice.synthetic)
    }

    @Test fun demoAndRealNamespacesCannotMix() {
        val descriptors = listOf(commons, demo)
        assertEquals(listOf("wikimedia-commons"), policy.admittedSources(descriptors, false).map { it.namespace })
        assertEquals(listOf("local-demo"), policy.admittedSources(descriptors, true).map { it.namespace })
        assertTrue(policy.admittedSources(listOf(commons), true).isEmpty())
        assertTrue(policy.admittedSources(listOf(demo), false).isEmpty())
        assertTrue(policy.admittedSources(listOf(demo), true).single().synthetic)
    }

    @Test fun descriptorInspectionAndPresentationNeverCallProvider() {
        var calls = 0
        val provider = object : VideoSearchProvider {
            override val descriptor = commons
            override fun refusal(profile: SearchProfile): DiscoveryError? { calls++; return null }
            override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage { calls++; error("NO_DEMAND") }
        }
        val descriptors = ProviderRegistry(listOf(provider)).all().map { it.descriptor }
        val choices = policy.admittedSources(descriptors, false)
        policy.sourceSummary(SearchFilters(), choices)
        policy.capabilityText(choices.single())
        policy.unknownDescriptorIds(descriptors, choices)
        policy.validate(policy.fromProfile(profile(), false))
        assertEquals(0, calls)
    }

    @Test fun restorePreservesEveryFilterIncludingUnknownAndCaseSensitiveStoredNamespace() {
        val filters = SearchFilters(setOf("OLD-source", "WIKIMEDIA-COMMONS"), 1_001, 12_345, false, false)
        val original = profile(filters)
        val checkpoint = DiscoveryCheckpoint(SessionStamp("session", 1), original)
        val restored = DiscoveryCheckpointCodec.decode(DiscoveryCheckpointCodec.encode(checkpoint)).profile
        val draft = policy.reconcile(null, restored, false)
        assertEquals(original, draft.appliedProfile)
        assertEquals(filters, policy.validate(draft).filters)
        assertEquals("1.001", draft.minimumSeconds)
        assertEquals("12.345", draft.maximumSeconds)
        assertEquals(setOf("OLD-source", "WIKIMEDIA-COMMONS"), draft.sourceNamespaces)
        assertFalse(policy.isDirty(draft))
        val choices = policy.admittedSources(listOf(commons), false)
        assertEquals(listOf("OLD-source"), policy.unavailableNamespaces(draft.sourceNamespaces, choices))
        assertTrue(policy.sourceSummary(filters, choices).contains("OLD-source（未启用或未知；筛选已保留）"))
    }

    @Test fun sourceUnavailableDoesNotClearExistingSelectionOrMakeZeroSourcesNonzero() {
        val draft = policy.fromProfile(profile(SearchFilters(setOf("wikimedia-commons"))), false)
        assertEquals(listOf("wikimedia-commons"), policy.unavailableNamespaces(draft.sourceNamespaces, emptyList()))
        assertEquals(draft, policy.reconcile(draft, draft.appliedProfile, false))
        assertTrue(policy.sourceSummary(SearchFilters(), emptyList()).contains("零来源"))
    }

    @Test fun unavailableOrLoadingSnapshotCannotReplaceRestoredFiltersWithDefaults() {
        val draft = policy.fromProfile(profile(SearchFilters(setOf("obsolete"), 1_001, 9_999, false, false)), false)
            .copy(query = "unsent edit")
        val placeholder = SearchProfile("local", "")
        assertEquals(draft, policy.reconcile(draft, placeholder, false, appliedReadable = false))
        assertEquals(draft, policy.reconcile(draft, placeholder, true, appliedReadable = false))
        assertEquals(setOf("obsolete"), draft.sourceNamespaces)
        assertFalse(draft.safeSearch)
    }

    @Test fun addingKnownSourceRetainsObsoleteSelectionsUntilExplicitRemoval() {
        val draft = policy.fromProfile(profile(SearchFilters(setOf("obsolete"))), false)
        val choice = policy.admittedSources(listOf(commons), false).single()
        val added = policy.addSource(draft, choice)
        assertEquals(setOf("obsolete", "wikimedia-commons"), added.sourceNamespaces)
        assertEquals(setOf("wikimedia-commons"), policy.removeSource(added, "obsolete").sourceNamespaces)
        assertEquals(setOf("obsolete"), draft.sourceNamespaces)
    }

    @Test fun caseInsensitiveSelectionDoesNotRewriteStoredCasingOrDuplicateNamespace() {
        val draft = policy.fromProfile(profile(SearchFilters(setOf("WIKIMEDIA-COMMONS"))), false)
        val choice = policy.admittedSources(listOf(commons), false).single()
        assertTrue(policy.isSelected(draft.sourceNamespaces, choice.namespace))
        assertEquals(draft, policy.addSource(draft, choice))
        assertTrue(policy.removeSource(draft, choice.namespace).sourceNamespaces.isEmpty())
    }

    @Test fun sourceLimitRefusesAdditionWithoutDroppingSavedSources() {
        val draft = policy.fromProfile(profile(SearchFilters((1..100).map { "source-$it" }.toSet())), false)
        assertEquals(draft, policy.addSource(draft, policy.admittedSources(listOf(commons), false).single()))
    }

    @Test fun secondsRoundTripEveryBoundaryWithoutRoundingOrOverflow() {
        listOf(0L, 1L, 999L, 1_000L, 1_001L, 60_000L, 600_001L, Long.MAX_VALUE).forEach { value ->
            val filters = SearchFilters(minimumDurationMs = value, maximumDurationMs = value)
            val draft = policy.fromProfile(profile(filters), false)
            assertEquals(filters, policy.validate(draft).filters)
        }
        val draft = policy.fromProfile(profile(), false)
        assertNull(policy.validate(draft).filters!!.minimumDurationMs)
        assertNull(policy.validate(draft).filters!!.maximumDurationMs)
        assertEquals("0.001", policy.seconds(1))
        assertEquals("1.01", policy.seconds(1_010))
    }

    @Test fun malformedNegativeSubMillisecondAndOverflowDurationsFailWithoutChangingAppliedFilters() {
        val original = profile(SearchFilters(maximumDurationMs = 12_345))
        val draft = policy.fromProfile(original, false)
        listOf("-1", "NaN", "1e3", "1.0001", ".1", "1.", " 1", "1,5", "9223372036854775.808", "9".repeat(21)).forEach { text ->
            val invalid = draft.copy(minimumSeconds = text)
            assertNull(text, policy.validate(invalid).filters)
            assertNotNull(policy.validate(invalid).error)
            assertFalse(policy.canSubmit(invalid))
            assertEquals(original, invalid.appliedProfile)
        }
    }

    @Test fun reversedRangeDoesNotSilentlySwapOrClampBounds() {
        val draft = policy.fromProfile(profile(), false).copy(minimumSeconds = "60", maximumSeconds = "1")
        assertNull(policy.validate(draft).filters)
        assertTrue(policy.validate(draft).error!!.contains("不能大于"))
        assertEquals("60", draft.minimumSeconds)
        assertEquals("1", draft.maximumSeconds)
    }

    @Test fun localDurationFilteringKeepsBoundsInclusiveAndUnknownSeparate() {
        val draft = policy.fromProfile(profile(), false).copy(minimumSeconds = "1.001", maximumSeconds = "2.001", includeUnknownDuration = false)
        val filters = policy.validate(draft).filters!!
        fun result(duration: Long?) = ResultNormalizer.normalize(RawVideoResult("https://example.com/video", true,
            sourceNamespace = "source", sourceVideoId = "id", durationMs = duration), "source", filters)
        assertNull(result(null)); assertNull(result(0)); assertNull(result(1_000)); assertNull(result(2_002))
        assertNotNull(result(1_001)); assertNotNull(result(2_001))
        val includeUnknown = policy.validate(draft.copy(includeUnknownDuration = true)).filters!!
        assertNotNull(ResultNormalizer.normalize(RawVideoResult("https://example.com/video", true), "source", includeUnknown))
    }

    @Test fun viewAndHistoryTicksDoNotDiscardQueryAndFilterEdits() {
        val applied = profile(SearchFilters(setOf("obsolete"), maximumDurationMs = 12_345))
        val draft = policy.fromProfile(applied, false).copy(query = "New draft", minimumSeconds = "1.2", safeSearch = false)
        val tick = applied.copy(historyPolicy = RepeatPolicy.SHOW_ALL, sortOrder = ResultSort.NEWEST)
        assertEquals(draft, policy.reconcile(draft, tick, false))
        assertTrue(policy.isDirty(draft))
        assertEquals(applied.filters, draft.appliedProfile.filters)
    }

    @Test fun changedAppliedQueryLocaleOrFiltersRebasesWithoutReinterpretingThem() {
        val original = profile()
        val draft = policy.fromProfile(original, false).copy(query = "unsent")
        listOf(original.copy(query = "other"), original.copy(locale = "en"),
            original.copy(filters = SearchFilters(setOf("obsolete"), 1_234, 56_789, false, false))).forEach { changed ->
            val rebased = policy.reconcile(draft, changed, false)
            assertEquals(changed.query, rebased.query)
            assertEquals(changed.filters, policy.validate(rebased).filters)
            assertFalse(policy.isDirty(rebased))
        }
    }

    @Test fun demoSwitchCannotLeakRealQueryOrFilterDraftIntoDemo() {
        val realDraft = policy.fromProfile(profile(SearchFilters(setOf("wikimedia-commons"))), false).copy(query = "private draft")
        val synthetic = SearchProfile("demo-profile", "演示数据")
        val demoDraft = policy.reconcile(realDraft, synthetic, true)
        assertEquals("演示数据", demoDraft.query)
        assertTrue(demoDraft.sourceNamespaces.isEmpty())
        val real = policy.reconcile(demoDraft.copy(query = "demo draft"), realDraft.appliedProfile, false)
        assertEquals(realDraft.appliedProfile.query, real.query)
        assertEquals(realDraft.appliedProfile.filters, policy.validate(real).filters)
    }

    @Test fun claimsRejectDoubleSubmitAndStaleConfirmationWithoutRecomposition() {
        val draft = policy.fromProfile(profile(), false).copy(query = "new query", maximumSeconds = "12.345")
        val target = DiscoveryControlTarget("profile", SessionStamp("session", 1), 0, 1)
        val claim = DiscoveryFilterClaim(target, false, draft)
        var busy = false
        fun current() = policy.validClaim(claim, draft, target, draft.appliedProfile, false, true, false, busy)
        assertTrue(current())
        busy = true // The first accepted main-thread claim reserves the controller before I/O starts.
        assertFalse(current()) // The same onClick closure cannot submit twice before recomposition.
        assertFalse(policy.validClaim(claim, draft.copy(safeSearch = false), target, draft.appliedProfile, false, true, false, false))
        assertFalse(policy.validClaim(claim, draft, target.copy(viewRevision = 1), draft.appliedProfile, false, true, false, false))
        assertFalse(policy.validClaim(claim, draft, target.copy(controllerGeneration = 2), draft.appliedProfile, false, true, false, false))
        assertFalse(policy.validClaim(claim, draft, target.copy(stamp = SessionStamp("session", 2)), draft.appliedProfile, false, true, false, false))
    }

    @Test fun claimsRejectSameQueryFromDifferentProfileLocaleOrIsolationAndUnavailableState() {
        val draft = policy.fromProfile(profile(), false)
        val target = DiscoveryControlTarget("profile", SessionStamp("session", 1), 0, 1)
        val claim = DiscoveryFilterClaim(target, false, draft)
        fun valid(current: SearchProfile = draft.appliedProfile, currentDraft: DiscoveryFilterDraft? = draft,
            currentTarget: DiscoveryControlTarget? = target, demo: Boolean = false, ready: Boolean = true,
            unavailable: Boolean = false) = policy.validClaim(claim, currentDraft, currentTarget, current, demo, ready, unavailable, false)
        assertTrue(valid())
        assertFalse(valid(current = draft.appliedProfile.copy(profileId = "different")))
        assertFalse(valid(current = draft.appliedProfile.copy(locale = "en")))
        assertFalse(valid(current = draft.appliedProfile.copy(filters = SearchFilters(safeSearch = false))))
        assertFalse(valid(demo = true)); assertFalse(valid(ready = false)); assertFalse(valid(unavailable = true))
        assertFalse(valid(currentDraft = null)); assertFalse(valid(currentTarget = null))
    }

    @Test fun staleCasReconciliationCannotBeTreatedAsACurrentAppliedProfile() {
        val original = profile()
        val oldDraft = policy.fromProfile(original, false).copy(query = "unsent edit")
        val changed = original.copy(query = "other", filters = SearchFilters(setOf("obsolete")))
        assertFalse(policy.matchesApplied(oldDraft, changed, false))
        val fresh = policy.reconcile(oldDraft, changed, false)
        assertTrue(policy.matchesApplied(fresh, changed, false))
        assertEquals(changed.filters, policy.validate(fresh).filters)
        assertTrue(policy.matchesApplied(oldDraft, original.copy(sortOrder = ResultSort.NEWEST), false))
    }

    @Test fun rapidTextCallbacksRebaseLatestFieldAndPreserveOtherAcceptedDraftEdits() {
        val initial = policy.fromProfile(profile(SearchFilters(setOf("obsolete"), safeSearch = false)), false)
        val target = DiscoveryControlTarget("profile", SessionStamp("session", 1), 0, 1)
        val captured = DiscoveryFilterClaim(target, false, initial)
        fun edit(current: DiscoveryFilterDraft, field: DiscoveryFilterTextField, value: String) =
            policy.editText(captured, current, target, initial.appliedProfile, false, true, false, false, field, value)!!
        val first = edit(initial, DiscoveryFilterTextField.QUERY, "a")
        val duration = edit(first, DiscoveryFilterTextField.MINIMUM_SECONDS, "1.001")
        val latest = edit(duration.copy(includeUnknownDuration = false), DiscoveryFilterTextField.QUERY, "ab")
        val maximum = edit(latest, DiscoveryFilterTextField.MAXIMUM_SECONDS, "9.999")
        assertEquals("ab", maximum.query)
        assertEquals("1.001", maximum.minimumSeconds)
        assertEquals("9.999", maximum.maximumSeconds)
        assertEquals(setOf("obsolete"), maximum.sourceNamespaces)
        assertFalse(maximum.includeUnknownDuration)
        assertFalse(maximum.safeSearch)
        assertEquals(initial.appliedProfile, maximum.appliedProfile)
        // Search, safety, and non-text toggles retain their stricter exact-draft claim semantics.
        assertFalse(policy.validClaim(captured, maximum, target, initial.appliedProfile, false, true, false, false))
    }

    @Test fun textEditsKeepEveryTargetAvailabilityAndBusyFence() {
        val draft = policy.fromProfile(profile(), false)
        val target = DiscoveryControlTarget("profile", SessionStamp("session", 1), 0, 1)
        val captured = DiscoveryFilterClaim(target, false, draft)
        fun edit(currentTarget: DiscoveryControlTarget? = target, demo: Boolean = false, ready: Boolean = true,
            unavailable: Boolean = false, busy: Boolean = false) =
            policy.editText(captured, draft.copy(query = "a"), currentTarget, draft.appliedProfile, demo, ready,
                unavailable, busy, DiscoveryFilterTextField.QUERY, "ab")
        assertNotNull(edit())
        assertNull(edit(currentTarget = null))
        assertNull(edit(currentTarget = target.copy(profileId = "other")))
        assertNull(edit(currentTarget = target.copy(stamp = SessionStamp("session", 2))))
        assertNull(edit(currentTarget = target.copy(viewRevision = 1)))
        assertNull(edit(currentTarget = target.copy(controllerGeneration = 2)))
        assertNull(edit(demo = true)); assertNull(edit(ready = false))
        assertNull(edit(unavailable = true)); assertNull(edit(busy = true))
    }

    @Test fun textEditsCannotRebaseAcrossChangedAppliedProfileOrLocale() {
        val initial = policy.fromProfile(profile(), false)
        val target = DiscoveryControlTarget("profile", SessionStamp("session", 1), 0, 1)
        val captured = DiscoveryFilterClaim(target, false, initial)
        val other = initial.appliedProfile.copy(locale = "en")
        assertNull(policy.editText(captured, policy.fromProfile(other, false), target, other, false, true,
            false, false, DiscoveryFilterTextField.QUERY, "ab"))
        assertNull(policy.editText(captured, policy.fromProfile(other, false), target, initial.appliedProfile, false,
            true, false, false, DiscoveryFilterTextField.QUERY, "ab"))
        assertNull(policy.editText(captured, null, target, initial.appliedProfile, false, true,
            false, false, DiscoveryFilterTextField.QUERY, "ab"))
    }

    @Test fun textRebasingKeepsExistingQueryBoundAndValidationOfDraftDurations() {
        val initial = policy.fromProfile(profile(), false)
        val target = DiscoveryControlTarget("profile", SessionStamp("session", 1), 0, 1)
        val captured = DiscoveryFilterClaim(target, false, initial)
        fun edit(field: DiscoveryFilterTextField, value: String) = policy.editText(captured, initial, target,
            initial.appliedProfile, false, true, false, false, field, value)
        assertEquals(512, edit(DiscoveryFilterTextField.QUERY, "x".repeat(513))!!.query.length)
        assertNull(edit(DiscoveryFilterTextField.MINIMUM_SECONDS, "9".repeat(21)))
        assertNull(edit(DiscoveryFilterTextField.MAXIMUM_SECONDS, "9".repeat(21)))
        val intermediate = edit(DiscoveryFilterTextField.MINIMUM_SECONDS, "1.")!!
        assertEquals("1.", intermediate.minimumSeconds)
        assertNull(policy.validate(intermediate).filters)
        assertEquals(initial.appliedProfile, intermediate.appliedProfile)
    }

    @Test fun emptyOrInvalidUnicodeQueryCannotSubmitButWhitespaceAndSymbolsAreNotRewritten() {
        val draft = policy.fromProfile(profile(), false)
        assertFalse(policy.canSubmit(draft.copy(query = "  ")))
        assertFalse(policy.canSubmit(draft.copy(query = "\uD800")))
        val exact = "  filetype:video (cat OR dog)  "
        val edited = draft.copy(query = exact)
        assertTrue(policy.canSubmit(edited))
        assertEquals(exact, edited.query)
    }

    @Test fun capabilityCopySeparatesUnsupportedUnknownAndSyntheticMetadata() {
        val commonsCopy = policy.capabilityText(policy.admittedSources(listOf(commons), false).single()).joinToString()
        assertTrue(commonsCopy.contains("不支持安全搜索"))
        assertTrue(commonsCopy.contains("发布日期未知"))
        assertTrue(commonsCopy.contains("可能未知"))
        val demoCopy = policy.capabilityText(policy.admittedSources(listOf(demo), true).single()).joinToString()
        assertTrue(demoCopy.contains("不能据此证明真实来源能力"))
        val historyCopy = policy.nativeHistoryNotice()
        assertTrue(historyCopy.contains("SAVED")); assertTrue(historyCopy.contains("PLAYED")); assertTrue(historyCopy.contains("UNKNOWN"))
        val unknown = ProviderDescriptor("unmapped", "1")
        assertEquals(listOf("unmapped"), policy.unknownDescriptorIds(listOf(commons, unknown), policy.admittedSources(listOf(commons, unknown), false)))
    }

    @Test fun draftDiagnosticsRedactQueryAndNamespaceSelections() {
        val draft = policy.fromProfile(profile(SearchFilters(setOf("private-namespace"))), false).copy(query = "private query")
        assertEquals("DiscoveryFilterDraft([redacted])", draft.toString())
        assertEquals("DiscoveryFilterClaim([redacted])", DiscoveryFilterClaim(
            DiscoveryControlTarget("profile", SessionStamp("session", 1), 0, 1), false, draft).toString())
    }
}
