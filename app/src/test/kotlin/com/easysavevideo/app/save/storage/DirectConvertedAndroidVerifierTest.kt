package com.easysavevideo.app.save.storage

import com.easysavevideo.app.media.verification.*
import com.easysavevideo.app.save.*
import com.easysavevideo.save.*
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Exercises production adapter/RAF/lane seams with synthetic native bodies, never native success. */
class DirectConvertedAndroidVerifierTest {
    private val observed = AvcAacOutputObservations(120_000, 16, 16, 0, 0, 80_000, 3, 0, 3, 0, 0)
    private fun adapter(f: AdoptedCanonicalTestFixture, latch: () -> Unit = {},
        body: (BorrowedAvcAacRead, AvcAacNativeResources, AvcAacOutputExpectation, () -> Unit) -> AvcAacOutputObservations) =
        DirectConvertedAndroidVerifier(f.owner, latch) { read -> BorrowedAvcAacInspection(read, body) }

    @Test fun sameOriginalFdNativeReleaseFinalHashIdentityAndSingleClosePrecedeEvidence() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
            var nativeReleased = false; var finalCheck = false
            val events = arrayListOf<String>()
            val verifier = adapter(f) { read, resources, expected, _ ->
                assertSame(f.io.handles.single().fd, read.originalFd)
                assertSame(f.io.handles.single().channel, read.channel)
                assertEquals(f.outputBytes.size.toLong(), read.extentBytes)
                assertEquals(AvcAacAudioProfile.Silent, expected.audio)
                assertEquals(3L, expected.videoFrameCount)
                resources.add(Any(), release = { nativeReleased = true; events += "native-release" })
                observed
            }
            f.io.before = { if (it.operation == "close") { assertTrue(finalCheck); events += "raf-close" } }
            val evidence = verifier.inspect(f.artifact, f.convertedOutput, f.context) {
                f.checkOperation(f.context)
                if (nativeReleased) { finalCheck = true; events += "after-native-check" }
            }
            events += "evidence"
            assertTrue(evidence.isValidFor(f.artifact, f.convertedOutput, false))
            assertFalse(evidence.isValidFor(f.artifact, f.convertedOutput, true))
            assertEquals(1, f.io.count("open")); assertEquals(1, f.io.count("close"))
            assertTrue(events.indexOf("native-release") < events.indexOf("after-native-check"))
            assertTrue(events.lastIndexOf("raf-close") < events.indexOf("evidence"))
            binding.requireConsumedAndReleased(); assertFalse(f.owner.requiresProcessRecovery())
        } }
    }

    @Test fun mappingRunsThroughActualNativeLaneAndExecutionExceptionWithoutLosingOriginalSaveFailure() {
        val original = listOf(SaveError.CANCELLED, SaveError.DEADLINE_EXCEEDED, SaveError.RESOURCE_CHANGED,
            SaveError.STORAGE_FAILURE, SaveError.RECOVERY_REQUIRED).map(::SaveFailure)
        for (selected in original) AdoptedCanonicalTestFixture(System.currentTimeMillis() + 60_000).use { f ->
            f.useConverted(); f.boundConverted {
                val caller = Thread.currentThread()
                val verifier = AndroidLocalMp4Verifier({ error("LEGACY_PATH_FORBIDDEN") }, 30_000, f.owner, null) { _, resources, _, _ ->
                    assertNotSame(caller, Thread.currentThread())
                    assertEquals("esv-local-media-check", Thread.currentThread().name)
                    resources.add(Any(), release = {})
                    throw selected
                }
                assertSame(selected, assertThrows(SaveFailure::class.java) {
                    verifier.verify(f.artifact, f.convertedOutput, f.context)
                })
                assertEquals(1, f.io.count("close")); assertFalse(f.owner.requiresProcessRecovery())
            }
        }
        val cases = listOf(
            AvcAacVerificationFailure(AvcAacVerificationError.VERIFICATION_FAILED) to SaveError.INVALID_MEDIA,
            AvcAacVerificationFailure(AvcAacVerificationError.UNSUPPORTED_PROFILE) to SaveError.UNSUPPORTED_MEDIA,
            AvcAacVerificationFailure(AvcAacVerificationError.TIMEOUT) to SaveError.DEADLINE_EXCEEDED,
            IOException("SYNTHETIC_LOCAL_IO") to SaveError.STORAGE_FAILURE,
            SecurityException("SYNTHETIC_LOCAL_SECURITY") to SaveError.STORAGE_FAILURE,
            IllegalStateException("SYNTHETIC_NATIVE_CALL") to SaveError.VERIFICATION_UNAVAILABLE,
        )
        for ((thrown, expected) in cases) AdoptedCanonicalTestFixture(System.currentTimeMillis() + 60_000).use { f ->
            f.useConverted(); f.boundConverted {
                val verifier = AndroidLocalMp4Verifier({ error("LEGACY_PATH_FORBIDDEN") }, 30_000, f.owner, null) { _, _, _, _ -> throw thrown }
                assertEquals(expected, assertThrows(SaveFailure::class.java) {
                    verifier.verify(f.artifact, f.convertedOutput, f.context)
                }.code)
                assertEquals(1, f.io.count("close"))
            }
        }
    }

    @Test fun failedLocalIoSelectsOriginalStopBeforeDerivativeStorageFailure() {
        for (code in listOf(SaveError.CANCELLED, SaveError.DEADLINE_EXCEEDED)) {
            val selected = SaveFailure(code)
            assertSame(selected, assertThrows(SaveFailure::class.java) {
                convertedLocalIo({ throw selected }) { throw IOException() }
            })
        }
        val selected = SaveFailure(SaveError.RESOURCE_CHANGED)
        assertSame(selected, mapConvertedVerificationFailure(selected))
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED,
            mapConvertedVerificationFailure(AvcAacVerificationFailure(AvcAacVerificationError.RELEASE_UNCONFIRMED)).code)
    }

    @Test fun originalIoIsNotReplacedByCancellationDuringLaterNativeCleanup() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
            val verifier = adapter(f) { _, resources, _, _ ->
                resources.add(Any(), release = { f.cancellation.cancel() })
                throw IOException("SYNTHETIC_ORIGINAL_IO")
            }
            assertEquals(SaveError.STORAGE_FAILURE, assertThrows(SaveFailure::class.java) {
                verifier.inspect(f.artifact, f.convertedOutput, f.context) { f.checkOperation(f.context) }
            }.code)
            assertEquals(1, f.io.count("close")); assertFalse(f.owner.requiresProcessRecovery())
            assertThrows(SaveFailure::class.java) { binding.requireConsumedAndReleased() }
        } }
    }

    @Test fun nativeReleaseUncertaintyRetainsActualInspectionReadAndSelectedPrimaryIndependently() {
        for (code in listOf(SaveError.CANCELLED, SaveError.INVALID_MEDIA, SaveError.STORAGE_FAILURE)) {
            AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
                val selected = SaveFailure(code); var latched = false; var releases = 0
                val verifier = adapter(f, { latched = true }) { _, resources, _, _ ->
                    resources.add(Any(), release = { releases++; throw IOException("SYNTHETIC_NATIVE_RELEASE") })
                    throw selected
                }
                assertSame(selected, assertThrows(SaveFailure::class.java) {
                    verifier.inspect(f.artifact, f.convertedOutput, f.context) { f.checkOperation(f.context) }
                })
                assertTrue(latched); assertTrue(f.owner.requiresProcessRecovery())
                assertEquals(1, releases); assertEquals(0, f.io.count("close"))
                assertTrue(f.io.handles.single().fd.valid())
                assertThrows(SaveFailure::class.java) { binding.requireConsumedAndReleased() }
            } }
        }
    }

    @Test fun successfulReleaseButFailedStopIsOrdinaryVerificationFailureAndClosesRead() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted {
            var latched = false
            val verifier = adapter(f, { latched = true }) { _, resources, _, _ ->
                val codec = Any(); resources.add(codec, stop = { throw IOException("SYNTHETIC_STOP") }, release = {})
                resources.started(codec); observed
            }
            assertEquals(SaveError.INVALID_MEDIA, assertThrows(SaveFailure::class.java) {
                verifier.inspect(f.artifact, f.convertedOutput, f.context) { f.checkOperation(f.context) }
            }.code)
            assertFalse(latched); assertFalse(f.owner.requiresProcessRecovery()); assertEquals(1, f.io.count("close"))
        } }
    }

    @Test fun rafUncertaintyAfterSelectedFailureCannotReplacePrimaryOrCreateCompletion() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
            val selected = SaveFailure(SaveError.INVALID_MEDIA); var latched = false
            f.io.closeMode = AdoptedCanonicalTestIo.CloseMode.THROWS_VALID
            val verifier = adapter(f, { latched = true }) { _, _, _, _ -> throw selected }
            assertSame(selected, assertThrows(SaveFailure::class.java) {
                verifier.inspect(f.artifact, f.convertedOutput, f.context) { f.checkOperation(f.context) }
            })
            assertTrue(latched); assertEquals(1, f.io.count("close")); assertTrue(f.owner.requiresProcessRecovery())
            assertThrows(SaveFailure::class.java) { binding.requireConsumedAndReleased() }
        } }
    }

    @Test fun changesAfterActualNativeReleaseFailSameFdFinalHashAndNeverReturnEvidence() {
        for (truncate in listOf(false, true)) AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted {
            val verifier = adapter(f) { _, resources, _, _ ->
                resources.add(Any(), release = {
                    RandomAccessFile(f.primary, "rw").use { write ->
                        if (truncate) write.setLength(f.outputBytes.size - 1L)
                        else { write.seek(f.outputBytes.size / 2L); write.write(0xFF) }
                    }
                }); observed
            }
            assertEquals(SaveError.RESOURCE_CHANGED, assertThrows(SaveFailure::class.java) {
                verifier.inspect(f.artifact, f.convertedOutput, f.context) { f.checkOperation(f.context) }
            }.code)
            assertEquals(1, f.io.count("close"))
        } }
    }

    @Test fun stopDuringFinalHashOrAfterPositiveCloseForbidsUsableEvidence() {
        for (afterClose in listOf(false, true)) AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
            var released = false; var checksAfterRelease = 0
            if (afterClose) f.io.after = { if (it.operation == "close") f.cancellation.cancel() }
            val verifier = adapter(f) { _, resources, _, _ ->
                resources.add(Any(), release = { released = true }); observed
            }
            assertEquals(SaveError.CANCELLED, assertThrows(SaveFailure::class.java) {
                verifier.inspect(f.artifact, f.convertedOutput, f.context) {
                    if (!afterClose && released && ++checksAfterRelease == 2) f.cancellation.cancel()
                    f.checkOperation(f.context)
                }
            }.code)
            assertEquals(1, f.io.count("close")); assertFalse(f.owner.requiresProcessRecovery())
            if (afterClose) binding.requireConsumedAndReleased()
        } }
    }

    @Test fun validEndpointsDoNotHideMiddleFixedOriginDeviationOrWrongIndependentFacts() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted()
            val bad = listOf(observed.copy(maximumFixedVideoDeviationUs = 1001), observed.copy(width = 18),
                observed.copy(videoSamples = 2), observed.copy(decodedVideoFrames = 2),
                observed.copy(audioSamples = 1), observed.copy(decodedAudioFrames = 1024))
            for (facts in bad) assertEquals(SaveError.INVALID_MEDIA, assertThrows(SaveFailure::class.java) {
                assembleConvertedVerificationEvidence(f.artifact, f.convertedOutput, f.convertedOutput.sha256, facts)
            }.code)
        }
    }

    @Test fun callerCancellationWhileNativeCleanupIsBlockedKeepsRealLaneAndReadUntilActualRelease() {
        AdoptedCanonicalTestFixture(System.currentTimeMillis() + 60_000).use { f -> f.useConverted()
            val entered = CountDownLatch(1); val unblock = CountDownLatch(1); val actualDone = CountDownLatch(1)
            val nativeReleased = AtomicBoolean(); val failure = AtomicReference<Throwable?>()
            val caller = Executors.newSingleThreadExecutor()
            try {
                val call = caller.submit {
                    try { f.boundConverted {
                        val verifier = AndroidLocalMp4Verifier({ error("LEGACY_PATH_FORBIDDEN") }, 30_000, f.owner, null) { _, resources, _, _ ->
                            resources.add(Any(), release = {
                                entered.countDown()
                                // Simulates a platform call ignoring interruption; no new lane is created.
                                var waiting = true
                                while (waiting) try { waiting = !unblock.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
                                nativeReleased.set(true)
                            })
                            observed
                        }
                        verifier.verify(f.artifact, f.convertedOutput, f.context)
                    } } catch (caught: Throwable) { failure.set(caught) }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS)); f.cancellation.cancel()
                call.get(5, TimeUnit.SECONDS)
                assertEquals(SaveError.CANCELLED, (failure.get() as SaveFailure).code)
                assertEquals(0, f.io.count("close")); assertFalse(nativeReleased.get())
                assertTrue(AndroidLocalMp4Verifier.isDraining)
                f.io.after = { if (it.operation == "close") actualDone.countDown() }
                unblock.countDown(); assertTrue(actualDone.await(5, TimeUnit.SECONDS))
                assertTrue(nativeReleased.get()); assertEquals(1, f.io.count("close"))
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while ((f.owner.hasAnyReservations() || AndroidLocalMp4Verifier.isDraining) && System.nanoTime() < until) Thread.sleep(5)
                assertFalse(f.owner.hasAnyReservations()); assertFalse(AndroidLocalMp4Verifier.isDraining)
            } finally { unblock.countDown(); caller.shutdownNow() }
        }
    }

    @Test fun policyTwoIndependentAudioTargetsEncoderEAndPreservesVideoTrackDuration() {
        AdoptedCanonicalTestFixture().use { f ->
            val audio = DirectConversionAudioExpectation(0, 600_000, 28_800, 48_000, 2, 0, 0, 0)
            val expectation = DirectConversionExpectation(
                DirectConversionVideoExpectation(16, 16, 25, 1_000_000, 25, 0, 960_000, 0), audio)
            val proof = f.conversionProof.copy(expectation = expectation,
                settings = DirectConversionEncodingSettings.forExpectation(expectation), policyRevision = 2,
                completion = f.conversionProof.completion.copy(audioDecoderInputEos = true,
                    audioDecoderOutputEnded = true, sourceConsumerAcceptedEos = true,
                    sourceAudioContinuous = true, audioEncoderInputEos = true, audioEncoderOutputEnded = true))
            val output = ArtifactOutputDescriptor.ConvertedDirect(f.convertedTransfer, proof)
            DirectConvertedPreflight.check(f.artifact, output, f.context)
            val actual = AvcAacOutputObservations(1_000_000, 16, 16, 0, 0, 960_000,
                25, 48, 25, 49_152, 0, AvcAacPresentedAudio(0, 1_000_000, 48_000, 48_000, 2, true, true))
            val evidence = assembleConvertedVerificationEvidence(f.artifact, output, output.sha256, actual)
            assertTrue(evidence.isValidFor(f.artifact, output, true))
            assertEquals(2, evidence.directConversionProof!!.policyRevision)
            assertEquals(48_000L, evidence.directConversionProof!!.audio!!.effectiveDecodedPcmFrameCount)
            assertEquals(1_000_000L, evidence.directConversionProof!!.videoDurationUs)
            assertEquals(SaveError.UNSUPPORTED_MEDIA, assertThrows(SaveFailure::class.java) {
                assembleConvertedVerificationEvidence(f.artifact, output, output.sha256, actual.copy(presentedAudio = null))
            }.code)
            assertEquals(SaveError.INVALID_MEDIA, assertThrows(SaveFailure::class.java) {
                assembleConvertedVerificationEvidence(f.artifact, output, output.sha256,
                    actual.copy(presentedAudio = actual.presentedAudio!!.copy(endUs = 600_000, frames = 28_800)))
            }.code)
            assertFalse(evidence.copy(directConversionProof = evidence.directConversionProof!!.copy(policyRevision = 1))
                .isValidFor(f.artifact, output, true))
        }
    }

    @Test fun historicalAudioPolicyOneCannotEnterIndependentNativeInspection() {
        AdoptedCanonicalTestFixture().use { f ->
            val audio = DirectConversionAudioExpectation(0, 120_000, 5760, 5760, 2, 0, 0, 0)
            val expectation = f.conversionExpectation.copy(audio = audio)
            val proof = f.conversionProof.copy(expectation = expectation,
                settings = DirectConversionEncodingSettings.forExpectation(expectation),
                completion = f.conversionProof.completion.copy(audioDecoderInputEos = true,
                    audioDecoderOutputEnded = true, sourceConsumerAcceptedEos = true,
                    sourceAudioContinuous = true, audioEncoderInputEos = true, audioEncoderOutputEnded = true))
            assertEquals(SaveError.RECOVERY_REQUIRED, assertThrows(SaveFailure::class.java) {
                DirectConvertedPreflight.check(f.artifact,
                    ArtifactOutputDescriptor.ConvertedDirect(f.convertedTransfer, proof), f.context)
            }.code)
        }
    }

}
