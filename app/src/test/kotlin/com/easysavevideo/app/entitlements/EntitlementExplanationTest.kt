package com.easysavevideo.app.entitlements

import com.easysavevideo.core.domain.FeatureGate
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class EntitlementExplanationTest {
    private val all = EntitlementId.entries.map { EntitlementGrant(it,GrantState.ACTIVE) }
    private fun snapshot(grants:List<EntitlementGrant> = all,source:EntitlementSourceState = EntitlementSourceState.VALIDATED,
        at:Long?=100,until:Long?=null)=EntitlementSnapshot(1,grants,at,until,source)
    private fun status(s:EntitlementSnapshot,feature:Feature,now:Long=200)=
        explainEntitlements(s,now,false).features.single{it.feature==feature}.entitlement
    @Test fun plannedGroupsHaveOnlyTheEightPaidFeaturesAndNoInventedProductIds() {
        assertEquals(3,PlannedEntitlementModule.entries.size)
        val features=PlannedEntitlementModule.entries.flatMap{it.features}
        assertEquals(8,features.size);assertEquals(8,features.toSet().size)
        assertEquals(setOf(Feature.PROVIDER_PLUS,Feature.STREAM_SAVE,Feature.RECORD_SAVE,Feature.SEGMENT_RECORDING,
            Feature.REGION_RECORDING,Feature.VIDEO_CROP,Feature.VIDEO_MERGE,Feature.VIDEO_COMPRESS),features.toSet())
    }
    @Test fun unknownAndUnavailableSourceNeverDisplaySatisfiedEvenWithActiveGrants() {
        for(source in listOf(EntitlementSourceState.UNKNOWN,EntitlementSourceState.UNAVAILABLE)) {
            val e=explainEntitlements(snapshot(source=source),200,false)
            assertTrue(e.features.all{it.entitlement==FeatureEntitlementExplanation.UNKNOWN})
            assertNull(e.nextTimeBoundaryMillis)
        }
    }
    @Test fun validatedEmptyGrantsAreNotSatisfiedRatherThanOwned() {
        assertTrue(explainEntitlements(snapshot(emptyList()),200,false).features.all{it.entitlement==FeatureEntitlementExplanation.NOT_SATISFIED})
    }
    @Test fun validatedCurrentGrantsSatisfyEveryRequiredFeature() {
        assertTrue(explainEntitlements(snapshot(),200,false).features.all{it.entitlement==FeatureEntitlementExplanation.SATISFIED})
    }
    @Test fun missingValidationTimestampIsUnknown() {
        assertTrue(explainEntitlements(snapshot(at=null),200,false).features.all{it.entitlement==FeatureEntitlementExplanation.UNKNOWN})
    }
    @Test fun futureValidationAndNegativeClockDoNotPromoteGrants() {
        for(now in listOf(-1L,99L))assertTrue(explainEntitlements(snapshot(),now,false).features.all{it.entitlement==FeatureEntitlementExplanation.UNKNOWN})
        assertEquals(100L,explainEntitlements(snapshot(),99,false).nextTimeBoundaryMillis)
    }
    @Test fun snapshotExpiryIsExclusiveAndHasARecheckBoundary() {
        val s=snapshot(until=300)
        assertEquals(300L,explainEntitlements(s,200,false).nextTimeBoundaryMillis)
        assertTrue(explainEntitlements(s,299,false).features.all{it.entitlement==FeatureEntitlementExplanation.SATISFIED})
        assertTrue(explainEntitlements(s,300,false).features.all{it.entitlement==FeatureEntitlementExplanation.UNKNOWN})
    }
    @Test fun offlineCacheRequiresAnUnexpiredFiniteTrustWindow() {
        assertTrue(explainEntitlements(snapshot(source=EntitlementSourceState.OFFLINE_CACHE),200,false).features.all{it.entitlement==FeatureEntitlementExplanation.UNKNOWN})
        val valid=snapshot(source=EntitlementSourceState.OFFLINE_CACHE,until=300)
        assertTrue(explainEntitlements(valid,200,false).features.all{it.entitlement==FeatureEntitlementExplanation.SATISFIED})
        assertTrue(explainEntitlements(valid,300,false).features.all{it.entitlement==FeatureEntitlementExplanation.UNKNOWN})
    }
    @Test fun revokedOrExpiredGrantsDoNotSatisfyRequirements() {
        for(state in listOf(GrantState.EXPIRED,GrantState.REVOKED)) {
            val s=snapshot(all.map{it.copy(state=state)})
            assertTrue(explainEntitlements(s,200,false).features.all{it.entitlement==FeatureEntitlementExplanation.NOT_SATISFIED})
        }
    }
    @Test fun individualGrantExpiryStopsAtExactBoundary() {
        val s=snapshot(all.map{it.copy(expiresAtEpochMillis=250)})
        assertEquals(250L,explainEntitlements(s,200,false).nextTimeBoundaryMillis)
        assertTrue(explainEntitlements(s,249,false).features.all{it.entitlement==FeatureEntitlementExplanation.SATISFIED})
        assertTrue(explainEntitlements(s,250,false).features.all{it.entitlement==FeatureEntitlementExplanation.NOT_SATISFIED})
    }
    @Test fun advancedRecordingRequiresBaseRecordingButExistingVideoEditingDoesNot() {
        val tools=all.filter{it.entitlementId in setOf(EntitlementId.SEGMENT_RECORDING,EntitlementId.REGION_RECORDING,
            EntitlementId.VIDEO_CROP,EntitlementId.VIDEO_MERGE,EntitlementId.VIDEO_COMPRESS)}
        val s=snapshot(tools)
        for(feature in listOf(Feature.SEGMENT_RECORDING,Feature.REGION_RECORDING))assertEquals(FeatureEntitlementExplanation.NOT_SATISFIED,status(s,feature))
        for(feature in listOf(Feature.VIDEO_CROP,Feature.VIDEO_MERGE,Feature.VIDEO_COMPRESS))assertEquals(FeatureEntitlementExplanation.SATISFIED,status(s,feature))
        val withBase=snapshot(tools+EntitlementGrant(EntitlementId.RECORD_SAVE,GrantState.ACTIVE))
        assertEquals(FeatureEntitlementExplanation.SATISFIED,status(withBase,Feature.SEGMENT_RECORDING))
    }
    @Test fun partialSaveGrantCannotClaimTheOtherFeature() {
        val s=snapshot(listOf(EntitlementGrant(EntitlementId.STREAM_SAVE,GrantState.ACTIVE)))
        assertEquals(FeatureEntitlementExplanation.SATISFIED,status(s,Feature.STREAM_SAVE))
        assertEquals(FeatureEntitlementExplanation.NOT_SATISFIED,status(s,Feature.RECORD_SAVE))
    }
    @Test fun unknownRequiredGrantIsShownAsUnknownAndUnrelatedUnknownDoesNotPollute() {
        val s=snapshot(all.map{if(it.entitlementId==EntitlementId.RECORD_SAVE)it.copy(state=GrantState.UNKNOWN)else it})
        assertEquals(FeatureEntitlementExplanation.UNKNOWN,status(s,Feature.RECORD_SAVE))
        assertEquals(FeatureEntitlementExplanation.UNKNOWN,status(s,Feature.SEGMENT_RECORDING))
        assertEquals(FeatureEntitlementExplanation.SATISFIED,status(s,Feature.VIDEO_CROP))
        assertEquals(FeatureEntitlementExplanation.SATISFIED,status(s,Feature.STREAM_SAVE))
    }
    @Test fun simulationFlagNeverChangesGrantEvaluation() {
        val s=snapshot()
        val real=explainEntitlements(s,200,false);val debug=explainEntitlements(s,200,true)
        assertFalse(real.simulated);assertTrue(debug.simulated);assertEquals(real.features,debug.features)
    }
    @Test fun projectionDoesNotRetainGrantOriginsOrMutateInput() {
        val s=snapshot(all.map{it.copy(originRef="private-test-receipt")})
        val before=s.grants.toList();val out=explainEntitlements(s,200,false)
        assertEquals(before,s.grants);assertFalse(out.toString().contains("private-test-receipt"))
    }
    @Test fun readUsesOnlyOneSnapshotReadAndFailureBecomesUnknown() {
        var calls=0
        val ok=readEntitlementExplanation({calls++;snapshot()},200,false)
        assertEquals(1,calls);assertTrue(ok.features.all{it.entitlement==FeatureEntitlementExplanation.SATISFIED})
        val failed=readEntitlementExplanation({throw IllegalStateException("private-test-account")},200,false)
        assertEquals(EntitlementSourceState.UNAVAILABLE,failed.sourceState)
        assertTrue(failed.features.all{it.entitlement==FeatureEntitlementExplanation.UNKNOWN})
        assertFalse(failed.toString().contains("private-test-account"))
    }
    @Test fun missingNativeLinkageDoesNotCreateAFalseGrant() {
        val failed=readEntitlementExplanation({throw UnsatisfiedLinkError("private-test-account")},200,true)
        assertTrue(failed.simulated);assertTrue(failed.features.all{it.entitlement==FeatureEntitlementExplanation.UNKNOWN})
    }
    @Test fun earliestFutureBoundaryExcludesPastDeadlines() {
        val s=snapshot(all.mapIndexed{i,g->g.copy(expiresAtEpochMillis=if(i==0)150 else 400)},until=300)
        assertEquals(300L,explainEntitlements(s,200,false).nextTimeBoundaryMillis)
        assertEquals(400L,explainEntitlements(s,300,false).nextTimeBoundaryMillis)
        assertNull(explainEntitlements(s,400,false).nextTimeBoundaryMillis)
    }
    @Test fun timerIsAbsentWithoutBoundaryAndBoundedAcrossClockJumps() {
        assertNull(entitlementExplanationDelay(null,100))
        assertEquals(100L,entitlementExplanationDelay(200,100))
        assertEquals(1L,entitlementExplanationDelay(100,100))
        assertEquals(1L,entitlementExplanationDelay(99,100))
        assertEquals(60_000L,entitlementExplanationDelay(Long.MAX_VALUE,0))
        assertEquals(60_000L,entitlementExplanationDelay(Long.MAX_VALUE,Long.MIN_VALUE))
    }
    @Test fun displayedSatisfiedMatchesProductionGateAcrossEveryPaidGrantCombination() {
        val paid=all.filter{it.entitlementId !in setOf(EntitlementId.DIRECT_SAVE,EntitlementId.VAULT)}
        val gate=FeatureGate()
        for(mask in 0 until (1 shl paid.size)) {
            val grants=paid.filterIndexed{index,_->mask and (1 shl index)!=0}
            val s=snapshot(grants)
            for(row in explainEntitlements(s,200,false).features) {
                val expected=gate.evaluate(FeatureRequest(row.feature),s,PlatformCapabilities(setOf(row.feature)),BuildFeaturePolicy(),200).allowed
                assertEquals(expected,row.entitlement==FeatureEntitlementExplanation.SATISFIED)
            }
        }
    }
}
