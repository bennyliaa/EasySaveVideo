package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer

class RightsAttributionTest {
    private fun snapshot(revision: Long = 4, time: Long = 100): RightsSnapshot = RightsSnapshot(
        "commons", "Wikimedia Commons", "commons", "42", "https://commons.wikimedia.org/wiki/File:Example.webm",
        revision, time, "作者", "Credit", "Custom attribution", "CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/",
        "Share alike", true, true, false, emptyList(), null, RightsReview.REPORTED_REQUIRES_REVIEW,
        listOf(RightsFieldProvenance("Artist", "commons-desc-page", htmlConvertedToText = true)),
        "https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia",
    )
    private fun rights(s: RightsSnapshot = snapshot()) = ResultRights(listOf(s))
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: java.io.IOException) { }
    }
    @Test fun allFieldsAndUnknownBooleansRoundTripWithoutClearance() {
        val complete = rights(snapshot().copy(copyrighted = null, attributionRequired = false, nonFree = true,
            restrictions = listOf("Review restriction"), deletionReason = "Review deletion", review = RightsReview.FLAGGED_REQUIRES_REVIEW))
        assertEquals(complete, RightsCodec.decode(RightsCodec.encode(complete)))
        assertFalse(complete.downloadAuthorized); assertFalse(complete.snapshots.single().downloadAuthorized)
        assertTrue(complete.requiresReview)
        assertEquals(ResultRights(), RightsCodec.decode(RightsCodec.encode(ResultRights())))
    }
    @Test fun wholeObservationReplacesRatherThanFieldwiseAttributionMixing() {
        val newer = snapshot(5, 200).copy(author = null, licenseName = null, licenseUrl = null, review = RightsReview.INCOMPLETE)
        val merged = RightsAttribution.merge(rights(), rights(newer))
        assertEquals(listOf(newer), merged.snapshots)
        assertNull(merged.snapshots.single().author)
        assertTrue(RightsWarning.CONFLICTING_REPORTS in merged.warnings)
        assertTrue(RightsWarning.STALE_OBSERVATION in merged.warnings)
    }
    @Test fun stalePermissiveObservationCannotEraseNewRestriction() {
        val warning = snapshot(5, 200).copy(restrictions = listOf("Restricted"), review = RightsReview.FLAGGED_REQUIRES_REVIEW)
        val merged = RightsAttribution.merge(rights(warning), rights(snapshot(4, 100)))
        assertEquals(warning, merged.snapshots.single())
        assertEquals(merged, RightsAttribution.merge(rights(snapshot(4, 100)), rights(warning)))
    }
    @Test fun replacingOldFlagDoesNotSilentlyLosePriorWarning() {
        val old = snapshot().copy(nonFree = true, review = RightsReview.FLAGGED_REQUIRES_REVIEW)
        val merged = RightsAttribution.merge(rights(old), rights(snapshot(5, 200)))
        assertTrue(RightsWarning.PRIOR_REVIEW_WARNING in merged.warnings)
        assertTrue(RightsWarning.CONFLICTING_REPORTS in merged.warnings)
    }
    @Test fun repeatObservationIsIdempotentAndUnchangedFieldsOnlyFlagStaleness() {
        assertEquals(rights(), RightsAttribution.merge(rights(), rights()))
        val merged = RightsAttribution.merge(rights(), rights(snapshot(4, 200)))
        assertEquals(setOf(RightsWarning.STALE_OBSERVATION), merged.warnings)
    }
    @Test fun equalVersionConflictsSelectDeterministically() {
        val a = rights(); val b = rights(snapshot().copy(author = "Different author"))
        assertEquals(RightsAttribution.merge(a, b), RightsAttribution.merge(b, a))
        assertTrue(RightsWarning.CONFLICTING_REPORTS in RightsAttribution.merge(a, b).warnings)
    }
    @Test fun differentSourcesStayDistinctAndCountOverflowRejects() {
        val four = ResultRights((1..4).map { snapshot().copy(providerId = "provider$it") })
        assertEquals(4, RightsAttribution.merge(four, ResultRights()).snapshots.size)
        reject { RightsAttribution.merge(four, rights()) }
        reject { RightsCodec.encode(ResultRights(listOf(snapshot(), snapshot()))) }
    }
    @Test fun combinedByteLimitAppliesAcrossSnapshots() {
        val large = snapshot().copy(author = "界".repeat(3000), credit = "界".repeat(3000))
        assertTrue(RightsCodec.encode(rights(large)).size < RightsCodec.MAX_BYTES)
        reject { RightsCodec.encode(ResultRights(listOf(large, large.copy(providerId = "second")))) }
    }
    @Test fun unknownVersionCorruptTrailingAndTruncatedPayloadsReject() {
        val encoded = RightsCodec.encode(rights())
        reject { RightsCodec.decode(encoded.copyOf().also { it[7] = 99 }) }
        reject { RightsCodec.decode(encoded.copyOf(encoded.size - 1)) }
        reject { RightsCodec.decode(encoded + byteArrayOf(0)) }
        reject { RightsCodec.decode(byteArrayOf()) }
        reject { RightsCodec.decode(ByteArray(RightsCodec.MAX_BYTES + 1)) }
        reject { RightsCodec.encode(rights(snapshot().copy(schemaVersion = 2))) }
    }
    @Test fun malformedUtf16Utf8AndUnsafeLinksReject() {
        reject { RightsCodec.encode(rights(snapshot().copy(author = "bad\ud800"))) }
        val encoded = RightsCodec.encode(rights())
        // Empty warnings, one snapshot, version, then provider length and its bytes.
        reject { RightsCodec.decode(encoded.copyOf().also { it[24] = 0xff.toByte() }) }
        listOf("javascript:alert(1)", "http://example.com/", "https://user@example.com/", "https://example.com:443/").forEach {
            reject { RightsCodec.encode(rights(snapshot().copy(licenseUrl = it))) }
        }
    }
    @Test fun illegalTriStateAndPresenceBooleansReject() {
        val bytes = RightsCodec.encode(rights())
        val input = DataInputStream(ByteArrayInputStream(bytes))
        repeat(5) { input.readInt() } // magic, wire version, warnings count, snapshots count, snapshot schema
        fun skipText() { val n = input.readInt(); input.skipBytes(n) }
        repeat(5) { skipText() }
        val presentOffset = bytes.size - input.available()
        reject { RightsCodec.decode(bytes.copyOf().also { it[presentOffset] = 2 }) }
        assertTrue(input.readBoolean()); input.readLong(); input.readLong()
        repeat(6) { if (input.readBoolean()) skipText() }
        val triOffset = bytes.size - input.available()
        reject { RightsCodec.decode(bytes.copyOf().also { it[triOffset] = 3 }) }
    }
    @Test fun badCountsStringsAndWeakenedReviewReject() {
        reject { RightsCodec.decode(ByteBuffer.allocate(16).putInt(0x45535231).putInt(1).putInt(0).putInt(5).array()) }
        reject { RightsCodec.encode(rights(snapshot().copy(restrictions = List(33) { "a" }, review = RightsReview.FLAGGED_REQUIRES_REVIEW))) }
        reject { RightsCodec.encode(rights(snapshot().copy(author = "x".repeat(4097)))) }
        reject { RightsCodec.encode(rights(snapshot().copy(nonFree = true))) }
        reject { RightsCodec.encode(rights(snapshot().copy(author = null))) }
        reject { RightsCodec.encode(rights(snapshot().copy(provenance = List(2) { RightsFieldProvenance("Artist") }))) }
    }
    @Test fun normalizationDetachesAllMutableCollectionsAndPreservesUnknownDuration() {
        val restrictions = mutableListOf("Restriction")
        val fields = mutableListOf(RightsFieldProvenance("Artist"))
        val observations = mutableListOf(snapshot().copy(restrictions = restrictions, provenance = fields, review = RightsReview.FLAGGED_REQUIRES_REVIEW))
        val warnings = mutableSetOf(RightsWarning.STALE_OBSERVATION)
        val result = ResultNormalizer.normalize(RawVideoResult("https://example.com/video", true,
            rights = ResultRights(observations, warnings)), "provider")!!
        restrictions.clear(); fields.clear(); observations.clear(); warnings.clear()
        assertEquals(listOf("Restriction"), result.rights.snapshots.single().restrictions)
        assertEquals(1, result.rights.snapshots.single().provenance.size)
        assertEquals(setOf(RightsWarning.STALE_OBSERVATION), result.rights.warnings)
        assertNull(result.durationMs)
    }
    @Test fun invalidRightsRejectNormalizationRatherThanDroppingRights() {
        assertNull(ResultNormalizer.normalize(RawVideoResult("https://example.com/video", true,
            rights = rights(snapshot().copy(schemaVersion = 99))), "provider"))
        assertNull(ResultNormalizer.normalize(RawVideoResult("https://example.com/video", true, title = "bad\ud800"), "provider"))
    }
    @Test fun duplicatePoolMergeRetainsWholeRightsAndDetachedInput() {
        val raw = RawVideoResult("https://example.com/video", true, rights = rights())
        val a = ResultNormalizer.normalize(raw, "provider")!!
        val newer = snapshot(5, 200).copy(author = "New author")
        val b = a.copy(rights = rights(newer))
        val pool = ResultPool().admit(ResultPool().admit(PoolState(), listOf(a), 1).state, listOf(b), 2).state
        assertEquals(1, pool.entries.size)
        assertEquals(newer, pool.entries.single().result.rights.snapshots.single())
    }
    @Test fun inMemorySnapshotsRemainDetachedAcrossTransactionsAndRollback() {
        val restrictions = mutableListOf("Restriction")
        val fields = mutableListOf(RightsFieldProvenance("Artist"))
        val snapshots = mutableListOf(snapshot().copy(restrictions = restrictions, provenance = fields, review = RightsReview.FLAGGED_REQUIRES_REVIEW))
        val result = ResultNormalizer.normalize(RawVideoResult("https://example.com/video", true), "provider")!!.copy(rights = ResultRights(snapshots))
        val checkpoint = DiscoveryCheckpoint(SessionStamp("s", 1), SearchProfile("p", "q"), PoolState(listOf(PoolEntry(result, 1, 0)), nextOrder = 1))
        val store = InMemoryDiscoveryStore(); store.transaction { putCheckpoint(checkpoint) }
        restrictions.clear(); fields.clear(); snapshots.clear()
        val saved = store.transaction { checkpoint("s")!! }
        assertEquals(listOf("Restriction"), saved.pool.entries.single().result.rights.snapshots.single().restrictions)
        assertEquals(1, saved.pool.entries.single().result.rights.snapshots.single().provenance.size)
        try { store.transaction { removeCheckpoint("s"); throw IllegalStateException("injected") } } catch (_: IllegalStateException) { }
        assertEquals(saved, store.transaction { checkpoint("s") })
    }
    @Test fun newerUnknownRevisionKeepsItsAuthorAndSafetyFlagsVisible() {
        val old = rights(snapshot(900, 100))
        val newer = snapshot(time = 200).copy(sourcePageRevisionId = null, author = "New author", nonFree = true,
            deletionReason = "New review", review = RightsReview.FLAGGED_REQUIRES_REVIEW)
        val merged = RightsAttribution.merge(old, rights(newer))
        assertEquals(newer, merged.snapshots.single())
        assertEquals(merged, RightsAttribution.merge(rights(newer), old))
        assertTrue(RightsWarning.CONFLICTING_REPORTS in merged.warnings)
        assertTrue(RightsWarning.STALE_OBSERVATION in merged.warnings)
    }
    @Test fun changedRightsSuppressThumbnailInsteadOfPairingWithAnotherObservation() {
        val original = ResultNormalizer.normalize(RawVideoResult("https://example.com/video", true,
            thumbnailUrl = "https://example.com/old.jpg", rights = rights()), "provider")!!
        val updated = original.copy(thumbnailUrl = "https://example.com/new.jpg", rights = rights(snapshot(5, 200)))
        val first = ResultPool().admit(PoolState(), listOf(original), 1).state
        assertNull(ResultPool().admit(first, listOf(updated), 2).state.entries.single().result.thumbnailUrl)
        assertEquals(original.thumbnailUrl, ResultPool().admit(first, listOf(original), 2).state.entries.single().result.thumbnailUrl)
    }

    private fun assertAssociativeAcrossAllPermutations(a: ResultRights, b: ResultRights, c: ResultRights): ResultRights {
        val permutations = listOf(listOf(a, b, c), listOf(a, c, b), listOf(b, a, c), listOf(b, c, a), listOf(c, a, b), listOf(c, b, a))
        val expected = RightsAttribution.merge(RightsAttribution.merge(a, b), c)
        permutations.forEach { (x, y, z) ->
            assertEquals(expected, RightsAttribution.merge(RightsAttribution.merge(x, y), z))
            assertEquals(expected, RightsAttribution.merge(x, RightsAttribution.merge(y, z)))
        }
        return expected
    }
    @Test fun mixedKnownAndUnknownRevisionMergeIsAssociativeAcrossBatches() {
        val a = rights(snapshot(10, 100)); val b = rights(snapshot(time = 200).copy(sourcePageRevisionId = null))
        val c = rights(snapshot(9, 300))
        val result = assertAssociativeAcrossAllPermutations(a, b, c)
        assertEquals(c.snapshots, result.snapshots)
        assertEquals(setOf(RightsWarning.STALE_OBSERVATION, RightsWarning.CONFLICTING_REPORTS), result.warnings)
    }
    @Test fun allRevisionRegressionsRetainAssociativeWarningsEvenWithUnchangedFields() {
        val a = rights(snapshot(10, 100)); val b = rights(snapshot(8, 200)); val c = rights(snapshot(20, 300))
        val result = assertAssociativeAcrossAllPermutations(a, b, c)
        assertEquals(c.snapshots, result.snapshots)
        assertTrue(RightsWarning.CONFLICTING_REPORTS in result.warnings)
        val laterLowerRevision = RightsAttribution.merge(a, b)
        assertEquals(b.snapshots, laterLowerRevision.snapshots)
        assertTrue(RightsWarning.CONFLICTING_REPORTS in laterLowerRevision.warnings)
    }
    @Test fun laterPermissiveObservationRetainsPriorFlagAcrossAllMergeOrders() {
        val flagged = rights(snapshot(10, 100).copy(nonFree = true, author = "Earlier author",
            restrictions = listOf("Earlier restriction"), review = RightsReview.FLAGGED_REQUIRES_REVIEW))
        val unknown = rights(snapshot(time = 200).copy(sourcePageRevisionId = null))
        val latest = rights(snapshot(9, 300).copy(author = "Latest author"))
        val result = assertAssociativeAcrossAllPermutations(flagged, unknown, latest)
        assertEquals(latest.snapshots, result.snapshots)
        assertTrue(RightsWarning.PRIOR_REVIEW_WARNING in result.warnings)
        assertTrue(RightsWarning.CONFLICTING_REPORTS in result.warnings)
        assertFalse(result.downloadAuthorized)
    }

}
