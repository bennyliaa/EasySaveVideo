package com.easysavevideo.app.discovery

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.RepeatPolicy
import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryReadingAnchorPolicyTest {
    private val policy = DiscoveryReadingAnchorPolicy
    private val profile = SearchProfile("profile", "applied private query")
    private val ids = (1..4).map(::id)
    private val page = PageSnapshot(SessionStamp("session", 1), 2, 0, ids, 20)
    private val key = policy.key(page, profile.queryKey, false)!!
    private val top = DiscoveryReadingPosition(0, 0)

    @Test fun initialEntryStartsAtTopWithoutSavedAnchor() {
        assertEquals(top, policy.resolve(null, key, ids))
        assertEquals(top, policy.resolve(null, null, emptyList()))
    }

    @Test fun samePageReturnRestoresStableResultAndPixelOffset() {
        val saved = capture()
        assertEquals(ids[2], saved.resultId)
        assertEquals(key, saved.key)
        assertEquals(DiscoveryReadingPosition(2, 37), policy.resolve(saved, key, ids))
    }

    @Test fun samePageRecompositionDoesNotResetReadingPosition() {
        val saved = capture()
        repeat(20) {
            val recomposedKey = policy.key(page.copy(orderedResultIds = ids.toList()), profile.copy().queryKey, false)
            assertEquals(DiscoveryReadingPosition(2, 37), policy.resolve(saved, recomposedKey, ids.toList()))
        }
    }

    @Test fun recreationRoundTripsOnlySaveablePrimitiveState() {
        val saved = capture()
        val encoded = policy.encode(saved)
        assertEquals(9, encoded.size)
        assertTrue(encoded.all { it is String || it is Int || it is Long || it is Boolean })
        assertEquals(saved, policy.decode(encoded))
        assertEquals(DiscoveryReadingPosition(2, 37), policy.resolve(policy.decode(encoded), key, ids))
        assertFalse(encoded.contains(profile.query))
        assertFalse(encoded.any { it is String && it.contains("://") })
    }

    @Test fun sameIdentityReorderedResultsRestoreByIdRatherThanOldIndex() {
        val reordered = listOf(ids[2], ids[0], ids[3], ids[1])
        val sameKey = policy.key(page.copy(orderedResultIds = reordered), profile.queryKey, false)
        assertEquals(key, sameKey)
        assertEquals(DiscoveryReadingPosition(0, 37), policy.resolve(capture(), sameKey, reordered))
    }

    @Test fun firstCardCanRetainNonzeroOffset() {
        val saved = policy.capture(key, ids, 0, 91)
        assertEquals(DiscoveryReadingPosition(0, 91), policy.resolve(saved, key, ids))
    }

    @Test fun missingStableIdResetsToTopWithoutChoosingItsFormerNeighbour() {
        assertEquals(top, policy.resolve(capture(), key, ids.filterNot { it == ids[2] }))
        assertEquals(top, policy.resolve(capture(), key, emptyList()))
    }

    @Test fun duplicateSelectedOrUnselectedIdsInvalidateTheRenderedPage() {
        for (duplicate in listOf(ids[0], ids[2])) {
            val ambiguous = ids + duplicate
            assertEquals(top, policy.resolve(capture(), key, ambiguous))
            assertNull(policy.capture(key, ambiguous, 2, 37))
            assertNull(policy.key(page.copy(orderedResultIds = ambiguous), profile.queryKey, false))
        }
    }

    @Test fun realPageNavigationAndViewRevisionResetToTop() {
        for (differentPage in listOf(page.copy(pageIndex = 1), page.copy(revision = 3))) {
            assertEquals(top, policy.resolve(capture(), policy.key(differentPage, profile.queryKey, false), ids))
        }
    }

    @Test fun newSessionOrEpochCannotReusePreviousReadingAnchor() {
        for (stamp in listOf(SessionStamp("new-session", 1), SessionStamp("session", 2))) {
            val nextKey = policy.key(page.copy(stamp = stamp), profile.queryKey, false)
            assertEquals(top, policy.resolve(capture(), nextKey, ids))
        }
    }

    @Test fun appliedQueryLocaleAndSourceFilterChangesResetToTop() {
        val changedProfiles = listOf(profile.copy(query = "new applied query"), profile.copy(locale = "zh"),
            profile.copy(filters = SearchFilters(safeSearch = false)))
        for (changed in changedProfiles) {
            assertEquals(top, policy.resolve(capture(), policy.key(page, changed.queryKey, false), ids))
        }
    }

    @Test fun explicitlyAppliedQueryClearResetsWhileEditableDraftClearDoesNot() {
        val saved = capture()
        // Draft changes are deliberately absent from the key API; use the still-applied profile.
        val clearedDraft = profile.copy(query = "")
        assertEquals(DiscoveryReadingPosition(2, 37), policy.resolve(saved, policy.key(page, profile.queryKey, false), ids))
        assertEquals(top, policy.resolve(saved, policy.key(page, clearedDraft.queryKey, false), ids))
    }

    @Test fun historyClearThatPreservesTheAppliedPageDoesNotForceAReset() {
        val saved = capture()
        // History records/statistics are not source-query parameters or reading-page identity.
        val unchangedPage = page.copy(orderedResultIds = ids.toList())
        assertEquals(DiscoveryReadingPosition(2, 37), policy.resolve(saved,
            policy.key(unchangedPage, profile.queryKey, false), ids))
        assertEquals(key, policy.key(unchangedPage, profile.copy(historyPolicy = RepeatPolicy.HIDE_SHOWN).queryKey, false))
    }

    @Test fun normalizedEquivalentQueryDoesNotInventANewIdentity() {
        assertEquals(key, policy.key(page, profile.copy(query = "  APPLIED  private query ").queryKey, false))
    }

    @Test fun demoBoundaryAndUnavailablePageResetToTop() {
        assertEquals(top, policy.resolve(capture(), policy.key(page, profile.queryKey, true), ids))
        assertEquals(top, policy.resolve(capture(), null, ids))
        val demoKey = policy.key(page, profile.queryKey, true)!!
        val demoAnchor = policy.capture(demoKey, ids, 2, 37)
        assertEquals(top, policy.resolve(demoAnchor, key, ids))
    }

    @Test fun invalidCaptureIndexAndNegativeOffsetAreRejected() {
        for (index in listOf(-1, ids.size, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertNull(policy.capture(key, ids, index, 0))
        }
        assertNull(policy.capture(key, ids, 0, -1))
        assertNull(policy.capture(key, ids, 0, Int.MIN_VALUE))
        assertNull(policy.capture(null, ids, 0, 0))
        assertNull(policy.capture(key, emptyList(), 0, 0))
    }

    @Test fun malformedAndOversizedIdsAreNeverCapturedOrRestored() {
        for (invalidId in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64),
            "https://example.invalid/private", "private result title", "a".repeat(10_000))) {
            val invalidIds = ids + invalidId
            assertNull(policy.capture(key, invalidIds, 0, 0))
            assertEquals(top, policy.resolve(capture(), key, invalidIds))
        }
        val tooMany = (1..101).map(::id)
        assertNull(policy.capture(key, tooMany, 0, 0))
        assertEquals(top, policy.resolve(capture(), key, tooMany))
    }

    @Test fun keyFactoryRejectsMissingAndOutOfRangePageState() {
        assertNull(policy.key(null, profile.queryKey, false))
        for (invalid in listOf(page.copy(revision = -1), page.copy(pageIndex = -1), page.copy(pageIndex = 500),
            page.copy(pageIndex = Int.MAX_VALUE), page.copy(pageSize = 0), page.copy(pageSize = 101),
            page.copy(orderedResultIds = emptyList()), page.copy(orderedResultIds = (1..101).map(::id)))) {
            assertNull(policy.key(invalid, profile.queryKey, false))
        }
        for (query in listOf("", profile.query, "https://example.invalid/search", "f".repeat(63), "F".repeat(64))) {
            assertNull(policy.key(page, query, false))
        }
    }

    @Test fun malformedManuallyConstructedAnchorsAreNotEncodedOrRestored() {
        val saved = capture()
        val invalid = listOf(saved.copy(scrollOffset = -1), saved.copy(resultId = "private title"),
            saved.copy(key = key.copy(revision = -1)), saved.copy(key = key.copy(pageIndex = 500)),
            saved.copy(key = key.copy(queryKey = profile.query)))
        invalid.forEach {
            assertTrue(policy.encode(it).isEmpty())
            assertEquals(top, policy.resolve(it, it.key, ids))
        }
        for (invalidKey in listOf(key.copy(revision = -1), key.copy(pageIndex = 500), key.copy(queryKey = profile.query)))
            assertNull(policy.capture(invalidKey, ids, 0, 0))
    }

    @Test fun savedCodecRejectsWrongShapeAndUnknownVersion() {
        val encoded = policy.encode(capture())
        assertNull(policy.decode(emptyList<Any>()))
        assertNull(policy.decode(encoded.dropLast(1)))
        assertNull(policy.decode(encoded + "extra"))
        assertNull(policy.decode(listOf(encoded)))
        for (version in listOf<Any>(0, 2, -1, 1L, 1.0, "1")) {
            assertNull(policy.decode(changed(0, version)))
        }
    }

    @Test fun savedCodecRejectsNullAndWrongPrimitiveTypesWithoutCoercion() {
        for (index in 0..8) assertNull(policy.decode(changed(index, null)))
        val wrongTypes = listOf(1 to 1, 2 to 1, 3 to 2, 4 to 0L, 5 to 5, 6 to "false", 7 to 7, 8 to 37L,
            2 to 1.0, 3 to "2", 4 to 0.0, 6 to 0, 8 to 37.0)
        wrongTypes.forEach { (index, value) -> assertNull(policy.decode(changed(index, value))) }
    }

    @Test fun savedCodecRejectsInvalidOrOutOfRangeValuesWithoutThrowing() {
        val invalidValues = listOf(1 to "", 1 to "s".repeat(129), 1 to "https://example.invalid/session", 1 to "bad session",
            2 to 0L, 2 to -1L, 2 to Long.MIN_VALUE, 3 to -1L, 3 to Long.MIN_VALUE,
            4 to -1, 4 to 500, 4 to Int.MAX_VALUE, 4 to Long.MAX_VALUE,
            5 to profile.query, 5 to "0".repeat(63), 5 to "G".repeat(64),
            7 to "private result title", 7 to "0".repeat(65), 8 to -1, 8 to Int.MIN_VALUE, 8 to Long.MAX_VALUE)
        invalidValues.forEach { (index, value) -> assertNull(policy.decode(changed(index, value))) }
    }

    @Test fun largestValidPrimitiveRangesRoundTripWithoutOverflow() {
        val boundaryPage = page.copy(stamp = SessionStamp("s".repeat(128), Long.MAX_VALUE), revision = Long.MAX_VALUE,
            pageIndex = 499, pageSize = 100, orderedResultIds = (1..100).map(::id))
        val boundaryKey = policy.key(boundaryPage, profile.queryKey, false)!!
        val anchor = policy.capture(boundaryKey, boundaryPage.orderedResultIds, 99, Int.MAX_VALUE)!!
        assertEquals(anchor, policy.decode(policy.encode(anchor)))
        assertEquals(DiscoveryReadingPosition(99, Int.MAX_VALUE),
            policy.resolve(anchor, boundaryKey, boundaryPage.orderedResultIds))
    }

    @Test fun captureDoesNotRetainMutableResultList() {
        val rendered = ids.toMutableList()
        val saved = policy.capture(key, rendered, 2, 37)!!
        rendered.clear()
        assertEquals(ids[2], saved.resultId)
        assertEquals(DiscoveryReadingPosition(2, 37), policy.resolve(saved, key, ids))
    }

    @Test fun currentClaimAllowsSameIdentityButRejectsEveryOldIdentityComponent() {
        val claim = DiscoveryReadingRestorationClaim(key)
        assertTrue(claim.isCurrent(key.copy()))
        listOf(key.copy(stamp = SessionStamp("other", 1)), key.copy(stamp = SessionStamp("session", 2)),
            key.copy(revision = key.revision + 1), key.copy(pageIndex = 1),
            key.copy(queryKey = profile.copy(query = "different").queryKey), key.copy(demo = true), null).forEach {
            assertFalse(claim.isCurrent(it))
        }
    }

    @Test fun retiringDisposedClaimPreventsSamePageReturnFromRevivingIt() {
        val oldClaim = DiscoveryReadingRestorationClaim(key)
        assertTrue(oldClaim.isCurrent(key))
        oldClaim.retire()
        oldClaim.retire()
        assertFalse(oldClaim.isCurrent(key))
        assertFalse(oldClaim.isCurrent(key.copy()))
        assertTrue(DiscoveryReadingRestorationClaim(key).isCurrent(key))
    }

    @Test fun changedAppliedQueryFencesQueuedRestoreEvenWhenPageObjectIsUnchanged() {
        val claim = DiscoveryReadingRestorationClaim(key)
        val liveKey = policy.key(page, profile.copy(query = "newer applied query").queryKey, false)
        assertFalse(claim.isCurrent(liveKey))
        assertEquals(top, policy.resolve(capture(), liveKey, ids))
    }

    @Test fun malformedClaimCannotAuthorizeItsOwnInvalidIdentity() {
        val malformed = key.copy(queryKey = profile.query)
        assertFalse(DiscoveryReadingRestorationClaim(malformed).isCurrent(malformed))
    }

    @Test fun realCoordinatorRestoreRestampsRetainedPageAndExplicitProofPreservesReading() {
        val previous = checkpoint()
        val store = InMemoryDiscoveryStore().also { it.transaction { putCheckpoint(previous) } }
        coordinator(store).use { current ->
            assertTrue(current.restore(previous.stamp.sessionId))
            val restored = current.currentCheckpoint()!!
            val displayed = restored.pool.pages.single()
            assertEquals(previous.stamp.epoch + 1, displayed.stamp.epoch)
            val currentKey = policy.key(displayed, restored.profile.queryKey, false)
            val encoded = policy.encode(capture())
            assertEquals(top, policy.resolve(policy.decode(encoded), currentKey, ids))
            val proof = policy.restoreProof(previous, restored)!!
            val restoredFrom = policy.restoredFromStamp(proof, restored, displayed)
            assertEquals(previous.stamp, restoredFrom)
            assertEquals(DiscoveryReadingPosition(2, 37), policy.resolve(policy.decode(encoded), currentKey, ids, restoredFrom))
            // The display proof does not give callbacks from the previous epoch any authority.
            assertFalse(DiscoveryReadingRestorationClaim(key).isCurrent(currentKey))
        }
    }

    @Test fun realSameQueryNewSearchCannotReuseRestoreProofEvenWhenResultsReturn() {
        val previous = checkpoint()
        val store = InMemoryDiscoveryStore().also { it.transaction { putCheckpoint(previous) } }
        coordinator(store).use { current ->
            assertTrue(current.restore(previous.stamp.sessionId))
            val restored = current.currentCheckpoint()!!
            val proof = policy.restoreProof(previous, restored)!!
            current.start(previous.stamp.sessionId, previous.profile)
            val searched = current.currentCheckpoint()!!
            assertTrue(searched.pool.pages.isEmpty())
            assertTrue(searched.pool.entries.isEmpty())
            assertNull(policy.restoreProof(previous, searched))
            assertNull(policy.restoredFromStamp(proof, searched, null))
            val laterPage = page.copy(stamp = searched.stamp)
            val later = searched.copy(pool = previous.pool.copy(pages = listOf(laterPage)))
            assertNull(policy.restoredFromStamp(proof, later, laterPage))
            assertEquals(top, policy.resolve(capture(), policy.key(laterPage, searched.profile.queryKey, false), ids,
                policy.restoredFromStamp(proof, later, laterPage)))
        }
    }

    @Test fun realChangedSourceOrExplicitRecoveryRestoreCannotProduceReadingProof() {
        for (changedSource in listOf(false, true)) {
            val previous = checkpoint()
            val store = InMemoryDiscoveryStore().also { it.transaction { putCheckpoint(previous) } }
            coordinator(store, if (changedSource) "b".repeat(64) else null).use { current ->
                assertTrue(current.restore(previous.stamp.sessionId, requireExplicitSearch = !changedSource))
                val restored = current.currentCheckpoint()!!
                assertTrue(restored.pool.pages.isEmpty())
                assertFalse(restored.queryAuthorized)
                assertNull(policy.restoreProof(previous, restored))
            }
        }
    }

    @Test fun proofFactoryRejectsChangedProfileAuthorizationOrSourceConfiguration() {
        val previous = checkpoint()
        val restored = restamped(previous)
        val changed = listOf(restored.copy(profile = profile.copy(query = "new query")),
            restored.copy(profile = profile.copy(query = " APPLIED private query ")),
            restored.copy(profile = profile.copy(profileId = "new-profile")),
            restored.copy(profile = profile.copy(filters = SearchFilters(safeSearch = false))),
            restored.copy(profile = profile.copy(historyPolicy = RepeatPolicy.HIDE_SHOWN)),
            restored.copy(queryAuthorized = false), restored.copy(sourceConfigurationKey = "a".repeat(64)))
        changed.forEach { assertNull(policy.restoreProof(previous, it)) }
    }

    @Test fun proofFactoryRejectsClearedChangedOrReorderedPoolAndRetainedPages() {
        val previous = checkpoint()
        val restored = restamped(previous)
        val pool = restored.pool
        val changedPools = listOf(PoolState(), pool.copy(entries = emptyList()), pool.copy(pages = emptyList()),
            pool.copy(viewRevision = pool.viewRevision + 1), pool.copy(nextOrder = pool.nextOrder + 1),
            pool.copy(entries = pool.entries.reversed()), pool.copy(entries = pool.entries.dropLast(1)),
            pool.copy(entries = pool.entries.mapIndexed { index, entry ->
                if (index == 0) entry.copy(result = entry.result.copy(provenance = setOf("changed-provider"))) else entry
            }), pool.copy(pages = listOf(pool.pages.single().copy(orderedResultIds = ids.reversed()))),
            pool.copy(pages = listOf(pool.pages.single().copy(pageSize = 40))))
        changedPools.forEach { assertNull(policy.restoreProof(previous, restored.copy(pool = it))) }
    }

    @Test fun proofFactoryRequiresDirectForwardSameSessionAndConsistentRestamps() {
        val previous = checkpoint()
        assertNull(policy.restoreProof(null, restamped(previous)))
        assertNull(policy.restoreProof(previous, null))
        assertNull(policy.restoreProof(previous, previous))
        assertNull(policy.restoreProof(restamped(previous), previous))
        assertNull(policy.restoreProof(previous, restamped(previous, SessionStamp("other-session", 2))))
        val restored = restamped(previous)
        assertNull(policy.restoreProof(previous, restored.copy(pool = previous.pool)))
        val malformedPrevious = previous.copy(pool = previous.pool.copy(pages = listOf(page.copy(stamp = restored.stamp))))
        assertNull(policy.restoreProof(malformedPrevious, restored))
        assertNotNull(policy.restoreProof(previous, restamped(previous, SessionStamp("session", 4))))
    }

    @Test fun malformedRetainedCheckpointCannotCreateProof() {
        val previous = checkpoint()
        val pool = previous.pool
        val invalidPools = listOf(pool.copy(entries = pool.entries + pool.entries.first()),
            pool.copy(pages = listOf(page.copy(orderedResultIds = ids + ids.first()))),
            pool.copy(pages = listOf(page.copy(orderedResultIds = listOf(id(99))))),
            pool.copy(pages = listOf(page.copy(pageIndex = 1))), pool.copy(pages = listOf(page.copy(revision = 99))),
            pool.copy(pages = listOf(page.copy(pageSize = 0))), pool.copy(nextOrder = -1),
            pool.copy(pages = List(501) { page }), pool.copy(entries = List(501) { pool.entries.first() }))
        invalidPools.forEach {
            val invalid = previous.copy(pool = it)
            assertNull(policy.restoreProof(invalid, restamped(invalid)))
        }
    }

    @Test fun proofExposureRequiresExactCurrentAndDisplayedPageStampsAndMembership() {
        val previous = checkpoint()
        val restored = restamped(previous)
        val proof = policy.restoreProof(previous, restored)!!
        val displayed = restored.pool.pages.single()
        assertEquals(previous.stamp, policy.restoredFromStamp(proof, restored, displayed))
        assertNull(policy.restoredFromStamp(null, restored, displayed))
        assertNull(policy.restoredFromStamp(proof, null, displayed))
        assertNull(policy.restoredFromStamp(proof, restored, null))
        assertNull(policy.restoredFromStamp(proof, previous, displayed))
        assertNull(policy.restoredFromStamp(proof, restored, page))
        assertNull(policy.restoredFromStamp(proof, restored, displayed.copy(pageIndex = 1)))
        assertNull(policy.restoredFromStamp(proof, restored.copy(pool = PoolState()), displayed))
        assertNull(policy.restoredFromStamp(proof.copy(currentStamp = previous.stamp), previous, page))
        assertNull(policy.restoredFromStamp(proof.copy(previousStamp = SessionStamp("other-session", 1)), restored, displayed))
    }

    @Test fun proofIsNotReusableInLaterEpochAndNeverGuessesAcrossARestoreChain() {
        val first = checkpoint()
        val second = restamped(first)
        val third = restamped(second)
        val firstProof = policy.restoreProof(first, second)!!
        val nextProof = policy.restoreProof(second, third)!!
        val thirdPage = third.pool.pages.single()
        val thirdKey = policy.key(thirdPage, third.profile.queryKey, false)
        assertNull(policy.restoredFromStamp(firstProof, third, thirdPage))
        val directPrevious = policy.restoredFromStamp(nextProof, third, thirdPage)
        assertEquals(second.stamp, directPrevious)
        assertEquals(top, policy.resolve(capture(), thirdKey, ids, directPrevious))
        val secondAnchor = policy.capture(policy.key(second.pool.pages.single(), second.profile.queryKey, false), ids, 2, 37)
        assertEquals(DiscoveryReadingPosition(2, 37), policy.resolve(secondAnchor, thirdKey, ids, directPrevious))
    }

    @Test fun explicitRestoreStillRequiresEveryOtherPageIdentityFieldToMatch() {
        val previous = checkpoint()
        val restored = restamped(previous)
        val currentKey = policy.key(restored.pool.pages.single(), restored.profile.queryKey, false)!!
        val changedKeys = listOf(currentKey.copy(queryKey = profile.copy(query = "other").queryKey),
            currentKey.copy(pageIndex = 1), currentKey.copy(revision = 3), currentKey.copy(demo = true),
            currentKey.copy(stamp = SessionStamp("other-session", 2)))
        changedKeys.forEach { assertEquals(top, policy.resolve(capture(), it, ids, previous.stamp)) }
        assertEquals(top, policy.resolve(capture(), currentKey, ids, SessionStamp("session", 3)))
        assertEquals(top, policy.resolve(capture(), currentKey, ids, SessionStamp("other-session", 1)))
        assertEquals(top, policy.resolve(capture(), currentKey, ids.dropLast(2), previous.stamp))
    }

    @Test fun explicitRestoreCannotMoveBackwardsAndNewAnchorCodecRemainsEpochStrict() {
        val restored = restamped(checkpoint())
        val currentKey = policy.key(restored.pool.pages.single(), restored.profile.queryKey, false)!!
        val newAnchor = policy.capture(currentKey, ids, 2, 37)!!
        assertEquals(top, policy.resolve(newAnchor, key, ids, restored.stamp))
        val recreated = policy.decode(policy.encode(newAnchor))!!
        assertEquals(currentKey, recreated.key)
        assertEquals(top, policy.resolve(recreated, key, ids))
        assertEquals(DiscoveryReadingPosition(2, 37), policy.resolve(recreated, currentKey, ids))
    }

    @Test fun restoreProofContainsOnlyRedactedStampsAndIsNotInSavedCodec() {
        val previous = checkpoint()
        val restored = restamped(previous)
        val proof = policy.restoreProof(previous, restored)!!
        assertEquals("DiscoveryReadingRestoreProof([redacted])", proof.toString())
        assertEquals(9, policy.encode(capture()).size)
        assertFalse(policy.encode(capture()).contains(proof))
    }

    @Test fun diagnosticStringsNeverRevealIdentityOrSearchInformation() {
        val diagnostic = listOf(key, capture(), DiscoveryReadingRestorationClaim(key)).joinToString()
        assertFalse(diagnostic.contains(profile.query))
        assertFalse(diagnostic.contains(profile.queryKey))
        assertFalse(diagnostic.contains(key.stamp.sessionId))
        assertFalse(diagnostic.contains(ids[2]))
        assertTrue(diagnostic.contains("[redacted]"))
    }

    private fun capture() = policy.capture(key, ids, 2, 37)!!
    private fun changed(index: Int, value: Any?): List<Any?> = policy.encode(capture()).toMutableList<Any?>()
        .also { it[index] = value }
    private fun id(value: Int): String = value.toString(16).padStart(64, '0')

    private fun checkpoint(): DiscoveryCheckpoint {
        val entries = ids.mapIndexed { index, resultId ->
            val result = ResultNormalizer.normalize(RawVideoResult("https://fixture.invalid/$index", true,
                title = "Owned fixture"), "source")!!.copy(fingerprint = resultId, identityAliases = setOf(resultId))
            PoolEntry(result, 1_000, index.toLong())
        }
        return DiscoveryCheckpoint(page.stamp, profile, PoolState(entries, listOf(page), page.revision, entries.size.toLong()))
    }

    private fun restamped(previous: DiscoveryCheckpoint,
        next: SessionStamp = SessionStamp(previous.stamp.sessionId, previous.stamp.epoch + 1)): DiscoveryCheckpoint =
        previous.copy(stamp = next, pool = previous.pool.copy(pages = previous.pool.pages.map { it.copy(stamp = next) }))

    /** Real restore/start logic, no providers, worker threads, timers, or network calls. */
    private fun coordinator(store: InMemoryDiscoveryStore, configurationKey: String? = null) = VideoDiscoveryCoordinator(
        ProviderRegistry(), object : ProviderRuntime {
            override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
                callback: (ProviderOutcome) -> Unit): CancellationHandle = error("No provider work permitted")
        }, store, object : DiscoveryTimer {
            override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle {}
        }, EpochClock { 1_000 }, sourceConfigurationKey = configurationKey)
}
