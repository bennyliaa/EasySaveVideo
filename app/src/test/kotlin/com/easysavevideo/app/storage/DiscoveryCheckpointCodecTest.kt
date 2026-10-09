package com.easysavevideo.app.storage

import com.easysavevideo.core.model.RepeatPolicy
import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryCheckpointCodecTest {
    private fun checkpoint(): DiscoveryCheckpoint {
        val stamp = SessionStamp("session", 9)
        val result = ResultNormalizer.normalize(RawVideoResult("https://example.com/video?q=1", true, "标题", "Example", "example", "1", durationMs = 65_000), "source")!!
        return DiscoveryCheckpoint(stamp, SearchProfile("profile", "私密关键词", filters = SearchFilters(maximumDurationMs = 90_000), historyPolicy = RepeatPolicy.HIDE_SHOWN),
            PoolState(listOf(PoolEntry(result, 1, 0)), listOf(PageSnapshot(stamp, 3, 0, listOf(result.resultId), 20)), 3, 1))
    }
    private fun reject(action: () -> Unit) {
        try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: java.io.IOException) { }
    }
    @Test fun roundTripPreservesAliasesNullableFieldsPagesAndQuery() {
        val c = checkpoint(); assertEquals(c, DiscoveryCheckpointCodec.decode(DiscoveryCheckpointCodec.encode(c)))
        assertNull(c.pool.entries.single().result.thumbnailUrl)
        assertEquals(2, c.pool.entries.single().result.identityAliases.size)
    }
    @Test fun truncatedAndTrailingBytesAreRejected() {
        val bytes = DiscoveryCheckpointCodec.encode(checkpoint())
        reject { DiscoveryCheckpointCodec.decode(bytes.copyOf(bytes.size - 1)) }
        reject { DiscoveryCheckpointCodec.decode(bytes + byteArrayOf(1)) }
        reject { DiscoveryCheckpointCodec.decode(byteArrayOf()) }
    }
    @Test fun unknownSchemaAndMalformedUtf8AreRejected() {
        val bytes = DiscoveryCheckpointCodec.encode(checkpoint())
        bytes[7] = 99; reject { DiscoveryCheckpointCodec.decode(bytes) }
        val bad = DiscoveryCheckpointCodec.encode(checkpoint()); bad[12] = 0xff.toByte()
        reject { DiscoveryCheckpointCodec.decode(bad) }
    }
    @Test fun unsafeUrlCannotReachRestoredGrid() {
        val c = checkpoint(); val entry = c.pool.entries.single()
        reject { DiscoveryCheckpointCodec.encode(c.copy(pool = c.pool.copy(entries = listOf(entry.copy(result = entry.result.copy(canonicalPageUrl = "javascript:alert(1)")))))) }
    }
    @Test fun duplicateResultAndForeignPageIdsAreRejected() {
        val c = checkpoint()
        reject { DiscoveryCheckpointCodec.encode(c.copy(pool = c.pool.copy(entries = c.pool.entries + c.pool.entries))) }
        reject { DiscoveryCheckpointCodec.encode(c.copy(pool = c.pool.copy(pages = listOf(c.pool.pages.single().copy(orderedResultIds = listOf("f".repeat(64))))))) }
    }
    @Test fun staleStampAndRevisionAreRejected() {
        val c = checkpoint()
        reject { DiscoveryCheckpointCodec.encode(c.copy(pool = c.pool.copy(pages = listOf(c.pool.pages.single().copy(stamp = SessionStamp("old", 1)))))) }
        reject { DiscoveryCheckpointCodec.encode(c.copy(pool = c.pool.copy(pages = listOf(c.pool.pages.single().copy(revision = 1))))) }
    }
    @Test fun negativeOrderAndUnknownIdentityVersionAreRejected() {
        val c = checkpoint(); val e = c.pool.entries.single()
        reject { DiscoveryCheckpointCodec.encode(c.copy(pool = c.pool.copy(entries = listOf(e.copy(order = -1))))) }
        reject { DiscoveryCheckpointCodec.encode(c.copy(pool = c.pool.copy(entries = listOf(e.copy(result = e.result.copy(identityVersion = 2)))))) }
    }
    @Test fun maximumPoolWithWideUnicodeFieldsCanRoundTrip() {
        val c = checkpoint()
        val entries = List(500) { i ->
            val raw = RawVideoResult("https://example.com/" + "x".repeat(8000) + i, true, "界".repeat(512), "界".repeat(128), "source", "$i",
                "https://example.com/" + "y".repeat(8000), 1, "界".repeat(4096))
            PoolEntry(ResultNormalizer.normalize(raw, "provider")!!, 1, i.toLong())
        }
        val huge = c.copy(pool = PoolState(entries, emptyList(), 0, 500))
        val bytes = DiscoveryCheckpointCodec.encode(huge)
        assertTrue(bytes.size > 2 * 1024 * 1024)
        assertEquals(huge, DiscoveryCheckpointCodec.decode(bytes))
    }
    @Test fun currentAnchorMayExceedNewPageSizeWithoutLosingIds() {
        val c = checkpoint(); val anchored = c.copy(pool = c.pool.copy(pages = listOf(c.pool.pages.single().copy(pageSize = 1))))
        assertEquals(anchored, DiscoveryCheckpointCodec.decode(DiscoveryCheckpointCodec.encode(anchored)))
    }
    @Test fun headerOnlyCheckpointIsValid() {
        val c = checkpoint(); val header = c.copy(pool = c.pool.copy(entries = emptyList(), pages = emptyList()))
        assertEquals(header, DiscoveryCheckpointCodec.decode(DiscoveryCheckpointCodec.encode(header)))
    }
    @Test fun genuineFrozenV1FixtureRestoresLegacyRowsWithEmptyRights() {
        val bytes = javaClass.getResourceAsStream("/discovery-checkpoint-v1.bin")!!.use { it.readBytes() }
        assertEquals(1, java.nio.ByteBuffer.wrap(bytes, 4, 4).int)
        val restored = DiscoveryCheckpointCodec.decode(bytes)
        assertEquals(ResultSort.RELEVANCE, restored.profile.sortOrder)
        assertEquals("旧查询", restored.profile.query)
        assertEquals("旧标题", restored.pool.entries.single().result.title)
        assertEquals(ResultRights(), restored.pool.entries.single().result.rights)
        assertEquals(65_000L, restored.pool.entries.single().result.durationMs)
        assertEquals(listOf("a".repeat(64)), restored.pool.pages.single().orderedResultIds)
        val upgraded = DiscoveryCheckpointCodec.encode(restored)
        assertEquals(3, java.nio.ByteBuffer.wrap(upgraded, 4, 4).int)
        assertEquals(restored, DiscoveryCheckpointCodec.decode(upgraded))
    }
    @Test fun genuineFrozenV2FixtureDefaultsToRelevanceAndUpgradesWithoutLosingRights() {
        val bytes = javaClass.getResourceAsStream("/discovery-checkpoint-v2.bin")!!.use { it.readBytes() }
        assertEquals(2, java.nio.ByteBuffer.wrap(bytes, 4, 4).int)
        val restored = DiscoveryCheckpointCodec.decode(bytes)
        assertEquals(ResultSort.RELEVANCE, restored.profile.sortOrder)
        assertEquals("旧查询", restored.profile.query)
        assertEquals("旧标题", restored.pool.entries.single().result.title)
        assertEquals(65_000L, restored.pool.entries.single().result.durationMs)
        assertEquals(listOf("a".repeat(64)), restored.pool.pages.single().orderedResultIds)
        val rights = restored.pool.entries.single().result.rights
        assertEquals(setOf(RightsWarning.CONFLICTING_REPORTS), rights.warnings)
        val snapshot = rights.snapshots.single()
        assertEquals("作者", snapshot.author)
        assertEquals("Custom", snapshot.customAttribution)
        assertEquals(12L, snapshot.sourcePageRevisionId)
        assertEquals(true, snapshot.nonFree)
        assertEquals(RightsReview.FLAGGED_REQUIRES_REVIEW, snapshot.review)
        assertEquals(listOf("Restricted"), snapshot.restrictions)
        val upgraded = DiscoveryCheckpointCodec.encode(restored)
        assertEquals(3, java.nio.ByteBuffer.wrap(upgraded, 4, 4).int)
        assertEquals(restored, DiscoveryCheckpointCodec.decode(upgraded))
    }
    @Test fun versionThreeRoundTripRetainsCompleteAttributionAndWarnings() {
        val c = checkpoint(); val e = c.pool.entries.single()
        val rights = ResultRights(listOf(RightsSnapshot("source", "Example", "example", "1", "https://example.com/video",
            sourcePageRevisionId = 12, retrievedAtEpochMillis = 400, author = "作者", credit = "Credit", customAttribution = "Custom",
            licenseName = "License", licenseUrl = "https://example.com/license", usageTerms = "Terms", copyrighted = null,
            attributionRequired = true, nonFree = true, restrictions = listOf("Restricted"), deletionReason = "Review",
            review = RightsReview.FLAGGED_REQUIRES_REVIEW,
            provenance = listOf(RightsFieldProvenance("Artist", "source", htmlConvertedToText = true)),
            metadataTermsUrl = "https://example.com/metadata")), setOf(RightsWarning.CONFLICTING_REPORTS))
        val full = c.copy(pool = c.pool.copy(entries = listOf(e.copy(result = e.result.copy(rights = rights)))))
        assertEquals(full, DiscoveryCheckpointCodec.decode(DiscoveryCheckpointCodec.encode(full)))
    }
    @Test fun versionThreePersistsEverySortWithoutChangingQueryIdentity() {
        val original = checkpoint()
        ResultSort.entries.forEach { sort ->
            val checkpoint = original.copy(profile = original.profile.copy(sortOrder = sort))
            val bytes = DiscoveryCheckpointCodec.encode(checkpoint)
            assertEquals(3, java.nio.ByteBuffer.wrap(bytes, 4, 4).int)
            val restored = DiscoveryCheckpointCodec.decode(bytes)
            assertEquals(checkpoint, restored)
            assertEquals(sort, restored.profile.sortOrder)
            assertEquals(original.profile.queryKey, restored.profile.queryKey)
            assertEquals(original.profile.profileId, restored.profile.profileId)
        }
    }
    @Test fun versionThreeRejectsUnknownSortAndMissingSortInsteadOfGuessing() {
        val bytes = DiscoveryCheckpointCodec.encode(checkpoint())
        val marker = ResultSort.RELEVANCE.name.toByteArray(Charsets.UTF_8)
        val offset = bytes.indices.first { i -> i + marker.size <= bytes.size && marker.indices.all { bytes[i + it] == marker[it] } }
        listOf("UNDEFINED", "relevance").forEach { invalid ->
            reject { DiscoveryCheckpointCodec.decode(bytes.copyOf().also { invalid.toByteArray(Charsets.UTF_8).copyInto(it, offset) }) }
        }
        val genuineV2 = javaClass.getResourceAsStream("/discovery-checkpoint-v2.bin")!!.use { it.readBytes() }
        reject { DiscoveryCheckpointCodec.decode(genuineV2.copyOf().also { it[7] = 3 }) }
    }
    @Test fun checkpointEncodingRejectsMalformedUtf16BeforeReplacement() {
        val c = checkpoint(); val e = c.pool.entries.single()
        reject { DiscoveryCheckpointCodec.encode(c.copy(profile = c.profile.copy(query = "bad\ud800"))) }
        reject { DiscoveryCheckpointCodec.encode(c.copy(pool = c.pool.copy(entries = listOf(e.copy(result = e.result.copy(description = "bad\udc00")))))) }
    }
    @Test fun checkpointPresenceAndFilterBooleansAreStrictInAllVersions() {
        val v1 = javaClass.getResourceAsStream("/discovery-checkpoint-v1.bin")!!.use { it.readBytes() }
        val v2 = javaClass.getResourceAsStream("/discovery-checkpoint-v2.bin")!!.use { it.readBytes() }
        val v3 = DiscoveryCheckpointCodec.encode(checkpoint())
        for (original in listOf(v1, v2, v3)) {
            val input = java.io.DataInputStream(java.io.ByteArrayInputStream(original))
            fun skipText() { input.skipBytes(input.readInt()) }
            input.readInt(); input.readInt(); skipText(); input.readLong()
            repeat(3) { skipText() }
            repeat(input.readInt()) { skipText() }
            val presenceOffset = original.size - input.available()
            reject { DiscoveryCheckpointCodec.decode(original.copyOf().also { it[presenceOffset] = 2 }) }
            repeat(2) { if (input.readBoolean()) input.readLong() }
            val flagOffset = original.size - input.available()
            reject { DiscoveryCheckpointCodec.decode(original.copyOf().also { it[flagOffset] = 2 }) }
        }
    }
    @Test fun checkpointRejectsUnknownOrCorruptNestedRightsInsteadOfClearingThem() {
        val bytes = DiscoveryCheckpointCodec.encode(checkpoint())
        val marker = byteArrayOf(0x45, 0x53, 0x52, 0x31)
        val offset = bytes.indices.first { i -> i + 4 <= bytes.size && marker.indices.all { bytes[i + it] == marker[it] } }
        reject { DiscoveryCheckpointCodec.decode(bytes.copyOf().also { it[offset + 7] = 99 }) }
        reject { DiscoveryCheckpointCodec.decode(bytes.copyOf().also { it[offset + 15] = 5 }) }
    }
    @Test fun wholeCheckpointAndNestedRightsBoundsAreIndependent() {
        reject { DiscoveryCheckpointCodec.decode(ByteArray(DiscoveryCheckpointCodec.MAX_BYTES + 1)) }
        val c = checkpoint(); val e = c.pool.entries.single()
        val invalid = ResultRights(listOf(RightsSnapshot("provider", "Source", "source", "42", "https://example.com/video",
            retrievedAtEpochMillis = 1, author = "界".repeat(4096), credit = "界".repeat(4096), customAttribution = "界".repeat(4096))))
        reject { DiscoveryCheckpointCodec.encode(c.copy(pool = c.pool.copy(entries = listOf(e.copy(result = e.result.copy(rights = invalid)))))) }
    }

}
