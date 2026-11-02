package com.easysavevideo.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RegionPreviewContractsTest {
    private val session = RecordingSessionId("preview-session", 4)
    private val source = CaptureDisplayGeometry(1080, 2400, 0, CaptureInsets(top = 96, bottom = 120))
    private val scope = CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY
    private val matrix = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    private val full = RegionNormalizedRect.FULL
    private fun snapshot() = RegionPreviewSnapshot(session, 3, 1,
        CaptureGeometryObservation(source, scope, CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK, 110),
        540, 1200, RegionPreviewTransform(matrix), 120_000, 120, 60_000_120)
    private fun gate() = RegionPreviewGate(session, 3, 100)
    private fun visible(): RegionPreviewGate = gate().also {
        assertTrue(it.markHostReady(110)); assertTrue(it.publish(snapshot(), 120))
    }
    private fun confirmed(): RegionPreviewGate = visible().also { assertTrue(it.confirm(snapshot(), full, 130)) }
    private fun retired(): RegionPreviewGate = confirmed().also { assertTrue(it.retireUi(snapshot(), 140)) }
    private fun ready(): RegionPreviewGate = retired().also { assertTrue(it.markCaptureReady(snapshot(), 150)) }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid contract") } catch (_: IllegalArgumentException) { }
    }
    private fun assertTerminal(gate: RegionPreviewGate, now: Long, reason: RegionPreviewInvalidation) {
        assertEquals(RegionPreviewPhase.INVALIDATED, gate.phase)
        assertTrue(gate.isInvalidated)
        assertEquals(reason, gate.invalidationReason)
        assertNull(gate.snapshot)
        assertNull(gate.confirmation)
        assertFalse(gate.markHostReady(now))
        assertFalse(gate.publish(snapshot(), now))
        assertFalse(gate.confirm(snapshot(), full, now))
        assertFalse(gate.retireUi(snapshot(), now))
        assertFalse(gate.markCaptureReady(snapshot(), now))
        assertNull(gate.takeReady(now))
        assertTrue(gate.expire(now))
        gate.invalidate(RegionPreviewInvalidation.RESOURCE_RELEASED)
        assertEquals(reason, gate.invalidationReason)
    }

    @Test fun sourceTransformIsDefensiveAndUsesEveryActualMatrixValue() {
        val mutable = matrix.copyOf()
        val transform = RegionPreviewTransform(mutable)
        mutable[12] = 1f
        assertTrue(transform.sameAs(matrix))
        assertFalse(transform.sameAs(mutable))
        val returned = transform.copyValues()
        returned[13] = 0f
        assertTrue(transform.sameAs(matrix))
        assertEquals(transform, RegionPreviewTransform(matrix.copyOf()))
        assertEquals(transform.hashCode(), RegionPreviewTransform(matrix.copyOf()).hashCode())
        for (index in matrix.indices) {
            val changed = matrix.copyOf().also { it[index] += 0.000001f }
            assertNotEquals(transform, RegionPreviewTransform(changed))
            assertFalse(transform.sameAs(changed))
        }
        assertFalse(transform.sameAs(FloatArray(15)))
        assertNotEquals(transform, matrix)
    }

    @Test fun transformRejectsWrongLengthAndEveryNonfinitePosition() {
        for (length in listOf(0, 1, 15, 17, 100)) invalid { RegionPreviewTransform(FloatArray(length)) }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (index in matrix.indices) invalid { RegionPreviewTransform(matrix.copyOf().also { it[index] = value }) }
        }
    }

    @Test fun exactTransformMetadataDoesNotPretendToProveNativeSupportOrContentIdentity() {
        // The adapter's independent policy rejects this producer scale; the core merely binds it.
        val producerScale = RegionPreviewTransform(matrix.copyOf().also { it[0] = 0.5f })
        assertEquals(producerScale, snapshot().copy(sourceTransform = producerScale).sourceTransform)
        assertEquals(scope, snapshot().observation.scopeEvidence)
    }

    @Test fun rasterBoundsAndByteBudgetAreIndependentOfSourceAndEncoderSize() {
        assertEquals(3_686_400L, RegionPreviewLimits.MAX_RASTER_BYTES)
        assertEquals(648_000L, snapshot().rasterWidth.toLong() * snapshot().rasterHeight)
        for (dimension in listOf(Int.MIN_VALUE, -1, 0, 1281, Int.MAX_VALUE)) {
            invalid { snapshot().copy(rasterWidth = dimension) }
            invalid { snapshot().copy(rasterHeight = dimension) }
        }
        val square = snapshot().observation.copy(geometry = CaptureDisplayGeometry(2000, 2000, 0))
        invalid { snapshot().copy(observation = square, rasterWidth = 1280, rasterHeight = 1280) }
        assertEquals(921_600L, snapshot().copy(observation = square, rasterWidth = 960, rasterHeight = 960)
            .let { it.rasterWidth.toLong() * it.rasterHeight })
    }

    @Test fun rasterPreservesWholeSourceAspectAndRejectsStretchingOrUpsampling() {
        invalid { snapshot().copy(rasterWidth = 1080, rasterHeight = 720) }
        invalid { snapshot().copy(rasterWidth = 540, rasterHeight = 1197) }
        assertEquals(1199, snapshot().copy(rasterHeight = 1199).rasterHeight)
        val small = snapshot().observation.copy(geometry = CaptureDisplayGeometry(128, 256, 0))
        invalid { snapshot().copy(observation = small, rasterWidth = 256, rasterHeight = 512) }
        val odd = snapshot().observation.copy(geometry = CaptureDisplayGeometry(1081, 2401, 0))
        assertEquals(576, snapshot().copy(observation = odd, rasterWidth = 576, rasterHeight = 1280).rasterWidth)
    }

    @Test fun observedGeometryVersionAndScopeProvenanceAreValidatedWithoutInventingSourceIdentity() {
        invalid { snapshot().copy(observation = snapshot().observation.copy(geometry = source.copy(transformVersion = 2))) }
        invalid { snapshot().copy(observation = snapshot().observation.copy(
            provenance = CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_SURFACE)) }
        invalid { snapshot().copy(observation = snapshot().observation.copy(
            scopeEvidence = CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY)) }
        val older = snapshot().copy(observation = snapshot().observation.copy(
            scopeEvidence = CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY,
            provenance = CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_SURFACE))
        assertEquals(CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY, older.observation.scopeEvidence)
    }

    @Test fun snapshotIdsAndFrameObservationTimeRequireValidBoundedLifetime() {
        invalid { snapshot().copy(geometryGeneration = 0) }
        invalid { snapshot().copy(frameSequence = 0) }
        invalid { snapshot().copy(geometryGeneration = -1) }
        invalid { snapshot().copy(frameSequence = -1) }
        invalid { snapshot().copy(capturedAtMonotonicUs = 109) }
        invalid { snapshot().copy(sourceFrameTimestampNs = 0) }
        invalid { snapshot().copy(sourceFrameTimestampNs = -1) }
        invalid { snapshot().copy(sourceFrameTimestampNs = 121_000) }
        invalid { snapshot().copy(expiresAtMonotonicUs = 120) }
        invalid { snapshot().copy(expiresAtMonotonicUs = 119) }
        invalid { snapshot().copy(expiresAtMonotonicUs = 60_000_121) }
        invalid { snapshot().copy(expiresAtMonotonicUs = Long.MAX_VALUE) }
        assertEquals(120_999L, snapshot().copy(sourceFrameTimestampNs = 120_999).sourceFrameTimestampNs)
    }

    @Test fun gateConstructionRejectsInvalidGenerationAndOpeningClock() {
        invalid { RegionPreviewGate(session, 0, 100) }
        invalid { RegionPreviewGate(session, 3, -1) }
        val late = RegionPreviewGate(session, 3, Long.MAX_VALUE - 5)
        assertFalse(late.expire(Long.MAX_VALUE)) // No deadline addition may overflow.
        assertEquals(RegionPreviewPhase.WAITING_FOR_HOST, late.phase)
    }

    @Test fun noInitialHostReadinessMeansNoPublicationOrAuthority() {
        val gate = gate()
        assertFalse(gate.publish(snapshot(), 120))
        assertFalse(gate.confirm(snapshot(), full, 120))
        assertFalse(gate.retireUi(snapshot(), 120))
        assertFalse(gate.markCaptureReady(snapshot(), 120))
        assertNull(gate.takeReady(120))
        assertNull(gate.snapshot)
        assertNull(gate.hostReadyAtMonotonicUs)
        assertEquals(RegionPreviewPhase.WAITING_FOR_HOST, gate.phase)
    }

    @Test fun hostReadinessAndFramePublicationAreBothOneShot() {
        val gate = gate()
        assertTrue(gate.markHostReady(110))
        assertEquals(110L, gate.hostReadyAtMonotonicUs)
        assertFalse(gate.markHostReady(111))
        assertEquals(110L, gate.hostReadyAtMonotonicUs)
        assertTrue(gate.publish(snapshot(), 120))
        assertFalse(gate.publish(snapshot().copy(frameSequence = 2), 121))
        assertEquals(snapshot(), gate.snapshot)
        assertEquals(RegionPreviewPhase.VISIBLE, gate.phase)
    }

    @Test fun aFreshReadbackTimeCannotLaunderAFrameFromBeforeHostReadiness() {
        val gate = gate()
        assertTrue(gate.markHostReady(110))
        assertFalse(gate.publish(snapshot().copy(sourceFrameTimestampNs = 109_999), 120))
        assertNull(gate.snapshot)
        assertFalse(gate.publish(snapshot().copy(sourceFrameTimestampNs = 110_000), 120))
        assertFalse(gate.publish(snapshot().copy(sourceFrameTimestampNs = 110_999), 120))
        assertTrue(gate.publish(snapshot().copy(sourceFrameTimestampNs = 111_000), 120))
    }

    @Test fun publicationRejectsOldSessionGeometryGenerationObservationAndFutureOrExpiredFrame() {
        val base = snapshot()
        val stale = listOf(
            base.copy(session = RecordingSessionId("other-session", 4)),
            base.copy(session = session.copy(generation = 5)),
            base.copy(geometryGeneration = 4),
            base.copy(observation = base.observation.copy(observedAtMonotonicUs = 99)),
            base.copy(capturedAtMonotonicUs = 121),
            base.copy(expiresAtMonotonicUs = 121),
        )
        for (candidate in stale) {
            val gate = gate().also { assertTrue(it.markHostReady(110)) }
            val now = if (candidate.expiresAtMonotonicUs == 121L) 121L else 120L
            assertFalse(gate.publish(candidate, now))
            assertEquals(RegionPreviewPhase.WAITING_FOR_FRAME, gate.phase)
            assertNull(gate.snapshot)
            assertTrue(gate.publish(base, now))
        }
    }

    @Test fun everySnapshotIdentityFieldIsBoundToTheConfirmation() {
        val base = snapshot()
        val alternatives = listOf(
            base.copy(session = RecordingSessionId("other-session", 4)),
            base.copy(session = session.copy(generation = 5)),
            base.copy(geometryGeneration = 4), base.copy(frameSequence = 2),
            base.copy(observation = base.observation.copy(geometry = source.copy(rotationDegrees = 90))),
            base.copy(observation = base.observation.copy(geometry = source.copy(insets = CaptureInsets(top = 98, bottom = 120)))),
            base.copy(observation = base.observation.copy(geometry = source.copy(width = 1081))),
            base.copy(observation = base.observation.copy(observedAtMonotonicUs = 111)),
            base.copy(observation = base.observation.copy(scopeEvidence = CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY,
                provenance = CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_SURFACE)),
            base.copy(rasterWidth = 539), base.copy(rasterHeight = 1199),
            base.copy(sourceTransform = RegionPreviewTransform(matrix.copyOf().also { it[0] = -1f; it[12] = 1f })),
            base.copy(sourceFrameTimestampNs = 120_001),
            base.copy(capturedAtMonotonicUs = 121), base.copy(expiresAtMonotonicUs = 60_000_119),
        )
        for (candidate in alternatives) {
            val gate = visible()
            assertFalse(gate.confirm(candidate, full, 130))
            assertEquals(RegionPreviewPhase.VISIBLE, gate.phase)
            assertTrue(gate.confirm(base, full, 130))
        }
    }

    @Test fun ordinaryDynamicFramesKeepFrozenPreviewIdentityAndRemainConfirmable() {
        val frozen = snapshot()
        val gate = visible()
        // Repeated changing frame sequence/timestamps model normal video motion. They are not
        // republished as new snapshots, and do not change the snapshot awaiting user selection.
        for (laterFrame in 2L..1001L) {
            val laterTime = 120 + laterFrame
            val liveObservation = frozen.observation.copy(observedAtMonotonicUs = laterTime)
            val liveFrame = frozen.copy(frameSequence = laterFrame, observation = liveObservation,
                sourceFrameTimestampNs = laterTime * 1000, capturedAtMonotonicUs = laterTime,
                expiresAtMonotonicUs = laterTime + RegionPreviewLimits.SNAPSHOT_LIFETIME_US)
            assertNotEquals(frozen, liveFrame)
            assertTrue(frozen.matchesSource(liveFrame.session, liveFrame.geometryGeneration,
                liveFrame.observation, liveFrame.sourceTransform.copyValues()))
            assertFalse(gate.expire(laterTime))
            assertEquals(frozen, gate.snapshot)
            assertEquals(RegionPreviewPhase.VISIBLE, gate.phase)
        }
        assertTrue(gate.confirm(frozen, full, 1200))
        assertTrue(gate.retireUi(frozen, 1210))
        assertTrue(gate.markCaptureReady(frozen, 1220))
        val accepted = requireNotNull(gate.takeReady(1230))
        assertEquals(frozen, accepted.snapshot)
        assertEquals(1L, accepted.snapshot.frameSequence)
        assertEquals(120_000L, accepted.snapshot.sourceFrameTimestampNs)
        assertEquals(PixelCrop(0, 0, 1080, 2400), accepted.recipe.pixelCrop)
    }

    @Test fun sourceGeometryOrGenerationChangeRevokesTheFrozenSelectionInsteadOfRemappingIt() {
        val frozen = snapshot()
        val later = frozen.observation.copy(observedAtMonotonicUs = 200)
        val changes = listOf(
            Triple(session.copy(sessionId = "other-session"), 3L, later),
            Triple(session.copy(generation = 5), 3L, later),
            Triple(session, 4L, later),
            Triple(session, 3L, later.copy(geometry = source.copy(width = 1082))),
            Triple(session, 3L, later.copy(geometry = source.copy(height = 2402))),
            Triple(session, 3L, later.copy(geometry = source.copy(rotationDegrees = 90))),
            Triple(session, 3L, later.copy(geometry = source.copy(insets = CaptureInsets(top = 98, bottom = 120)))),
            Triple(session, 3L, later.copy(geometry = source.copy(transformVersion = 2))),
            Triple(session, 3L, later.copy(scopeEvidence = CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY)),
            Triple(session, 3L, later.copy(provenance = CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_SURFACE)),
        )
        for ((actualSession, generation, observation) in changes) {
            for (gate in listOf(visible(), confirmed(), retired(), ready())) {
                assertFalse(frozen.matchesSource(actualSession, generation, observation, matrix))
                // Native mismatch handling invalidates the same gate; test that even an already
                // confirmed snapshot cannot survive that explicit source-change event.
                gate.invalidate(RegionPreviewInvalidation.GEOMETRY_CHANGED)
                assertFalse(gate.confirm(frozen, full, 200))
                assertNull(gate.takeReady(201))
                assertTerminal(gate, 202, RegionPreviewInvalidation.GEOMETRY_CHANGED)
            }
        }
    }

    @Test fun exactLiveTransformChangeRevokesSnapshotEvenWhenBothTransformsDescribeFullFrames() {
        val frozen = snapshot()
        // Full-image horizontal flip, not merely a producer crop rejected by native admission.
        val flipped = matrix.copyOf().also { it[0] = -1f; it[12] = 1f }
        assertFalse(frozen.matchesSource(session, 3, frozen.observation, flipped))
        assertFalse(frozen.matchesSource(session, 3, frozen.observation, FloatArray(15)))
        assertFalse(frozen.matchesSource(session, 3, frozen.observation,
            matrix.copyOf().also { it[5] = Float.NaN }))
        val gate = ready()
        gate.invalidate(RegionPreviewInvalidation.TRANSFORM_CHANGED)
        assertTerminal(gate, 200, RegionPreviewInvalidation.TRANSFORM_CHANGED)
    }

    @Test fun scaledPreviewCoordinatesStillProduceOriginalSourcePixelCropIncludingInsets() {
        val gate = visible()
        val bounds = RegionNormalizedRect(0.101, 0.103, 0.899, 0.901)
        assertTrue(gate.confirm(snapshot(), bounds, 130))
        assertTrue(gate.retireUi(snapshot(), 140))
        assertTrue(gate.markCaptureReady(snapshot(), 150))
        val accepted = requireNotNull(gate.takeReady(160))
        assertEquals(snapshot(), accepted.snapshot)
        assertEquals(source, accepted.recipe.source)
        assertEquals(PixelCrop(110, 248, 970, 2162), accepted.recipe.pixelCrop)
        assertEquals(source.insets, accepted.recipe.source.insets)
        assertEquals(130L, accepted.recipe.selectedAtMonotonicUs)
        assertEquals(130L, accepted.confirmedAtMonotonicUs)
        assertEquals(140L, accepted.uiRetiredAtMonotonicUs)
        assertEquals(150L, accepted.captureReadyAtMonotonicUs)
        assertEquals(0, accepted.recipe.encodedRotationDegrees)
    }

    @Test fun tooSmallOrDegenerateRoundedSelectionDoesNotDestroyVisiblePreview() {
        for (bounds in listOf(RegionNormalizedRect(0.0, 0.0, 0.01, 0.01),
            RegionNormalizedRect(0.1, 0.1, 0.1001, 0.1001))) {
            val gate = visible()
            assertFalse(gate.confirm(snapshot(), bounds, 130))
            assertEquals(RegionPreviewPhase.VISIBLE, gate.phase)
            assertTrue(gate.confirm(snapshot(), full, 131))
        }
    }

    @Test fun confirmingDoesNotIssueEncodingAuthorityAndDuplicateConfirmationCannotChangeSelection() {
        val gate = confirmed()
        assertNull(gate.confirmation)
        assertNull(gate.takeReady(131))
        assertFalse(gate.confirm(snapshot(), RegionNormalizedRect(0.2, 0.2, 0.8, 0.8), 132))
        assertFalse(gate.markCaptureReady(snapshot(), 133))
        assertEquals(RegionPreviewPhase.CONFIRMED, gate.phase)
        assertTrue(gate.retireUi(snapshot(), 140))
        assertTrue(gate.markCaptureReady(snapshot(), 150))
        assertEquals(full, requireNotNull(gate.takeReady(160)).recipe.bounds)
    }

    @Test fun actualUiDisposalAndSubsequentHostDrawAreSeparatelyRequired() {
        val visible = visible()
        assertFalse(visible.retireUi(snapshot(), 130))
        assertFalse(visible.markCaptureReady(snapshot(), 130))
        val gate = confirmed()
        assertFalse(gate.markCaptureReady(snapshot(), 140))
        assertTrue(gate.retireUi(snapshot(), 140))
        assertEquals(RegionPreviewPhase.WAITING_FOR_CAPTURE_READY, gate.phase)
        assertNull(gate.confirmation)
        assertNull(gate.takeReady(141))
        assertFalse(gate.markHostReady(142)) // Initial readiness cannot count as post-retirement draw.
        assertFalse(gate.retireUi(snapshot(), 143))
        assertTrue(gate.markCaptureReady(snapshot(), 150))
        assertEquals(RegionPreviewPhase.READY_TO_ENCODE, gate.phase)
        assertFalse(gate.markCaptureReady(snapshot(), 151))
        assertNotNull(gate.takeReady(160))
        assertNull(gate.takeReady(161))
        assertEquals(RegionPreviewPhase.ADMITTED, gate.phase)
    }

    @Test fun wrongSnapshotDisposalAndPostDisposalCallbacksCannotReleaseCurrentConfirmation() {
        val wrong = snapshot().copy(frameSequence = 2)
        val gate = confirmed()
        assertFalse(gate.retireUi(wrong, 140))
        assertNull(gate.takeReady(141))
        assertTrue(gate.retireUi(snapshot(), 142))
        assertFalse(gate.markCaptureReady(wrong, 150))
        assertNull(gate.takeReady(151))
        assertTrue(gate.markCaptureReady(snapshot(), 152))
        assertNotNull(gate.takeReady(153))
    }

    @Test fun firstFrameTimeoutIncludesWaitingForHostAndIsNotRenewedByReadiness() {
        for (readiness in listOf(false, true)) {
            val gate = gate()
            if (readiness) assertTrue(gate.markHostReady(15_000_099))
            assertFalse(gate.expire(15_000_099))
            assertTrue(gate.expire(15_000_100))
            assertTerminal(gate, 15_000_101, RegionPreviewInvalidation.FIRST_FRAME_TIMEOUT)
        }
    }

    @Test fun publicationAtExactFirstFrameDeadlineFailsEvenForOtherwiseValidSnapshot() {
        val gate = gate()
        assertTrue(gate.markHostReady(110))
        assertFalse(gate.publish(snapshot(), 15_000_100))
        assertTerminal(gate, 15_000_101, RegionPreviewInvalidation.FIRST_FRAME_TIMEOUT)
    }

    @Test fun visibleSnapshotExpiresAtExactDeadlineWithoutRenewalByInvalidSelection() {
        val gate = visible()
        assertFalse(gate.confirm(snapshot(), RegionNormalizedRect(0.0, 0.0, 0.01, 0.01), 60_000_119))
        assertFalse(gate.confirm(snapshot(), full, 60_000_120))
        assertTerminal(gate, 60_000_121, RegionPreviewInvalidation.SNAPSHOT_EXPIRED)
    }

    @Test fun shortSnapshotLifetimeExpiresInEachConfirmedButUnconsumedPhase() {
        for (stage in 0..2) {
            val short = snapshot().copy(expiresAtMonotonicUs = 200)
            val gate = gate()
            assertTrue(gate.markHostReady(110)); assertTrue(gate.publish(short, 120))
            assertTrue(gate.confirm(short, full, 130))
            if (stage >= 1) assertTrue(gate.retireUi(short, 140))
            if (stage >= 2) assertTrue(gate.markCaptureReady(short, 150))
            assertFalse(gate.expire(199))
            assertNull(gate.takeReady(200))
            assertTerminal(gate, 201, RegionPreviewInvalidation.SNAPSHOT_EXPIRED)
        }
    }

    @Test fun retirementDeadlineStartsAtConfirmationAndIsNeverRenewedByDisposalOrHostDraw() {
        for (stage in 0..2) {
            val gate = confirmed()
            if (stage >= 1) assertTrue(gate.retireUi(snapshot(), 5_000_128))
            if (stage >= 2) assertTrue(gate.markCaptureReady(snapshot(), 5_000_129))
            assertFalse(gate.expire(5_000_129))
            assertNull(gate.takeReady(5_000_130))
            assertTerminal(gate, 5_000_131, RegionPreviewInvalidation.UI_RETIREMENT_TIMEOUT)
        }
    }

    @Test fun retirementAndHostDrawAtExactRetirementDeadlineFailClosed() {
        val gate = confirmed()
        assertFalse(gate.retireUi(snapshot(), 5_000_130))
        assertTerminal(gate, 5_000_131, RegionPreviewInvalidation.UI_RETIREMENT_TIMEOUT)
        val retired = retired()
        assertFalse(retired.markCaptureReady(snapshot(), 5_000_130))
        assertTerminal(retired, 5_000_131, RegionPreviewInvalidation.UI_RETIREMENT_TIMEOUT)
    }

    @Test fun consumedPreviewDeadlineDoesNotBecomeARecordingDurationLimit() {
        val gate = ready()
        assertNotNull(gate.takeReady(160))
        assertFalse(gate.expire(Long.MAX_VALUE))
        assertEquals(RegionPreviewPhase.ADMITTED, gate.phase)
        assertNull(gate.takeReady(Long.MAX_VALUE))
    }

    @Test fun everyExplicitInvalidationReasonRevokesEveryPhaseAndAllLateCallbacks() {
        for (reason in RegionPreviewInvalidation.entries) {
            for (factory in listOf<() -> RegionPreviewGate>(::gate,
                { gate().also { it.markHostReady(110) } }, ::visible, ::confirmed, ::retired, ::ready,
                { ready().also { it.takeReady(160) } })) {
                val gate = factory()
                gate.invalidate(reason)
                assertTerminal(gate, 1000, reason)
            }
        }
    }

    @Test fun sameGeometryNewSessionOrGenerationCannotInheritOldConfirmation() {
        val old = ready()
        old.invalidate(RegionPreviewInvalidation.CANCELLED)
        val nextSession = session.copy(generation = 5)
        val next = RegionPreviewGate(nextSession, 4, 100)
        assertTrue(next.markHostReady(110))
        assertFalse(next.publish(snapshot(), 120))
        val fresh = snapshot().copy(session = nextSession, geometryGeneration = 4)
        assertTrue(next.publish(fresh, 120))
        assertFalse(next.confirm(snapshot(), full, 130))
        assertFalse(next.retireUi(snapshot(), 140))
        assertFalse(next.markCaptureReady(snapshot(), 150))
        assertNull(next.takeReady(160))
        assertEquals(RegionPreviewPhase.VISIBLE, next.phase)
        assertTerminal(old, 1000, RegionPreviewInvalidation.CANCELLED)
    }

    @Test fun backwardClockInvalidatesAllWaitingStatesAndNegativeClockNeverUnderflowsDeadlines() {
        for (factory in listOf<() -> RegionPreviewGate>(::gate,
            { gate().also { it.markHostReady(110) } }, ::visible, ::confirmed, ::retired, ::ready)) {
            val gate = factory()
            assertTrue(gate.expire(99))
            assertTerminal(gate, 1000, RegionPreviewInvalidation.CLOCK_INVALID)
        }
        val negative = visible()
        assertNull(negative.takeReady(Long.MIN_VALUE))
        assertTerminal(negative, 1000, RegionPreviewInvalidation.CLOCK_INVALID)
    }

    @Test fun reversedCrossThreadClockArrivalUsesConservativeHighWaterBarriers() {
        val gate = gate()
        val ready = CountDownLatch(1)
        val laterSampleArrived = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val earlier = Thread {
            try {
                val sampledBeforePreemption = 110L
                ready.countDown()
                laterSampleArrived.await()
                assertTrue(gate.markHostReady(sampledBeforePreemption))
            } catch (error: Throwable) { failure.set(error) }
        }.apply { start() }
        ready.await()
        assertFalse(gate.expire(115))
        laterSampleArrived.countDown()
        earlier.join(5000); assertFalse(earlier.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(115L, gate.hostReadyAtMonotonicUs)
        assertFalse(gate.publish(snapshot().copy(sourceFrameTimestampNs = 115_999), 114))
        assertTrue(gate.publish(snapshot(), 120))
        assertFalse(gate.expire(135))
        assertTrue(gate.confirm(snapshot(), full, 130))
        assertFalse(gate.expire(145))
        assertTrue(gate.retireUi(snapshot(), 140))
        assertFalse(gate.expire(155))
        assertTrue(gate.markCaptureReady(snapshot(), 150))
        val accepted = requireNotNull(gate.takeReady(151))
        assertEquals(135L, accepted.confirmedAtMonotonicUs)
        assertEquals(135L, accepted.recipe.selectedAtMonotonicUs)
        assertEquals(145L, accepted.uiRetiredAtMonotonicUs)
        assertEquals(155L, accepted.captureReadyAtMonotonicUs)
    }

    @Test fun staleCallerTimesCannotRenewRetirementDeadlineOrReviveAnExpiredGate() {
        val gate = confirmed()
        assertFalse(gate.expire(5_000_129))
        assertTrue(gate.retireUi(snapshot(), 140))
        assertTrue(gate.markCaptureReady(snapshot(), 150))
        assertFalse(gate.expire(130))
        assertNull(gate.takeReady(5_000_130))
        assertTerminal(gate, 130, RegionPreviewInvalidation.UI_RETIREMENT_TIMEOUT)
        val sourceExpired = visible()
        assertTrue(sourceExpired.expire(snapshot().expiresAtMonotonicUs))
        assertTerminal(sourceExpired, 120, RegionPreviewInvalidation.SNAPSHOT_EXPIRED)
    }

    @Test fun concurrentConfirmationHasExactlyOneWinnerWithoutChangingItsSelection() {
        val gate = visible()
        val choices = listOf(full, RegionNormalizedRect(0.2, 0.2, 0.8, 0.8))
        val winner = AtomicReference<RegionNormalizedRect>()
        val failures = AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val workers = choices.map { choice -> Thread {
            try { start.await(); if (gate.confirm(snapshot(), choice, 130)) assertTrue(winner.compareAndSet(null, choice)) }
            catch (failure: Throwable) { failures.compareAndSet(null, failure) }
        }.apply { start() } }
        start.countDown(); workers.forEach { it.join(5000); assertFalse(it.isAlive) }
        failures.get()?.let { throw AssertionError(it) }
        assertNotNull(winner.get())
        assertTrue(gate.retireUi(snapshot(), 140)); assertTrue(gate.markCaptureReady(snapshot(), 150))
        assertEquals(winner.get(), requireNotNull(gate.takeReady(160)).recipe.bounds)
    }

    @Test fun concurrentAdmissionIsOneShotAndPreAdmissionCancellationExcludesEveryConsumer() {
        val gate = ready()
        val winners = AtomicInteger()
        val start = CountDownLatch(1)
        val workers = (1..8).map { Thread {
            start.await(); if (gate.takeReady(160) != null) winners.incrementAndGet()
        }.apply { start() } }
        start.countDown(); workers.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(1, winners.get())
        val cancelled = ready()
        cancelled.invalidate(RegionPreviewInvalidation.CANCELLED)
        repeat(8) { assertNull(cancelled.takeReady(160)) }
    }
}
