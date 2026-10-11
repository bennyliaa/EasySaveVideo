package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

/** Candidate cleanup shares confirmed URL identities without granting candidate metadata authority. */
class PendingConfirmedIdentityTest {
    private val filters=SearchFilters(safeSearch=false)
    private val first="https://video.example/retained"
    private val alternate="https://video.example/alternate?id=1"
    private fun pending(url:String=alternate)=PendingCandidatePolicy.normalize(CandidateUrl(url),"source",filters)!!
    private fun merged():VideoSearchResult {
        val pool=ResultPool()
        val results=listOf(first,alternate).map { ResultNormalizer.normalize(
            RawVideoResult(it,true,sourceNamespace="site",sourceVideoId="same-video"),"source",filters)!! }
        return pool.admit(PoolState(),results,1000).state.entries.single().result
    }
    @Test fun mergedAlternateUrlDoesNotEnterPendingPool() {
        val confirmed=merged();assertEquals(first,confirmed.canonicalPageUrl)
        val pool=PendingCandidatePool(2)
        assertEquals(0,pool.add(listOf(pending()),setOf(first),confirmed.identityAliases))
        assertEquals(0,pool.size)
    }
    @Test fun existingActiveCandidateIsRemovedAfterExactAliasConfirmation() {
        val pool=PendingCandidatePool(2);val candidate=pending()
        pool.add(listOf(candidate),emptySet());pool.mark(candidate.candidateId,true)
        pool.add(emptyList(),setOf(first),merged().identityAliases)
        assertTrue(pool.snapshot().isEmpty())
    }
    @Test fun queryPathAndFragmentDifferencesAreNotGuessedEquivalent() {
        val pool=PendingCandidatePool(5)
        val rows=listOf("https://video.example/alternate?id=2","https://video.example/Alternate?id=1",
            "$alternate#player").map(::pending)
        assertEquals(3,pool.add(rows,setOf(first),merged().identityAliases))
        assertEquals(rows.map {it.candidateId},pool.snapshot().map {it.candidateId})
    }
    @Test fun sourceIdentityAndCandidateIdentityCannotSubstituteForConfirmedUrlAlias() {
        val pool=PendingCandidatePool(2);val candidate=pending()
        val unrelatedAliases=setOf(candidate.candidateId,digest(framed("source-v1","site","same-video")))
        assertEquals(1,pool.add(listOf(candidate),setOf(first),unrelatedAliases))
        assertFalse(pool.snapshot().single().verificationActive)
        assertNull(pool.snapshot().single().verification)
    }
    @Test fun clearingMatchedCandidateFreesBoundedCapacityForUnconfirmedPage() {
        val pool=PendingCandidatePool(1);pool.add(listOf(pending()),emptySet())
        val replacement=pending("https://video.example/new")
        assertEquals(1,pool.add(listOf(pending(),replacement),setOf(first),merged().identityAliases))
        assertEquals(replacement,pool.snapshot().single())
    }
}
