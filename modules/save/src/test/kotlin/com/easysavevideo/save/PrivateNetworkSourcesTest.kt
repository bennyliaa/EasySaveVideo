package com.easysavevideo.save

import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskSnapshot
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.recording.CaptureDescriptor
import com.easysavevideo.recording.RecordingAudioChoice
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Core value/fence fixtures only. Possession of these objects grants no browser-session authority. */
class PrivateNetworkSourcesTest {
    private val selectedUrl = SourceUrl("https://private-origin.invalid/private-path/video.mp4?token=private-query")
    private val candidateHash = SourceUrl("https://private-origin.invalid/manifest.m3u8").identity()
    private val variantHash = SourceUrl("https://private-cdn.invalid/variant.m3u8").identity()
    private val artifact = StagingArtifact("artifact", "commit")

    private fun liveResource(source: PrivateNetworkSource, audio: Boolean = false) = SelectedMediaResource(
        candidateHash, variantHash, selectedUrl, audio, RequestContextRef("private-request-context"),
        expiresAtEpochMillis = 10_000, sourceIdentity = source.sourceIdentity.encoded, privateSource = source,
    )

    private fun liveRequest(taskId: String = "task", audio: Boolean = false,
        method: TaskMethod = TaskMethod.DIRECT,
        source: PrivateNetworkSource = PrivateNetworkSourceSeed.create().bindTask(taskId),
    ) = SaveRequest(taskId, "key-$taskId", liveResource(source, audio), method)

    private fun privateSource(request: SaveRequest) = (request.input as SaveInput.PrivateNetwork).source

    private fun record(request: SaveRequest = liveRequest().asDurableReference(),
        transfer: TransferResult? = null,
        verification: VerificationEvidence? = null,
        checkpoint: DownloadCheckpoint? = null,
    ) = SaveRecord(request, TaskSnapshot(request.requestId, 0, TaskState.RUNNING, request.method),
        "commit", "operation", 10_000, 0, artifact = artifact, transfer = transfer,
        verification = verification, checkpoint = checkpoint)

    @Test fun oneSeedKeepsAllRandomIdentitiesAcrossBindingAndRepeatedDurableConversion() {
        val seed = PrivateNetworkSourceSeed.create()
        val prepared = liveResource(seed)
        val binding = seed.bindTask("task")
        assertSame(seed.sourceIdentity, binding.sourceIdentity)
        assertSame(seed.candidateId, binding.candidateId)
        assertSame(seed.variantId, binding.variantId)
        assertTrue(binding.isFrom(seed))
        assertEquals("task", binding.taskId)
        assertTrue(seed.isLive())
        assertTrue(binding.isLive())

        val request = SaveRequest("task", "key", prepared.copy(privateSource = binding))
        val first = request.asDurableReference()
        repeat(20) { assertEquals(first, request.asDurableReference()) }
        assertSame(first, first.asDurableReference())
        val durable = privateSource(first)
        assertEquals(prepared.sourceIdentity, durable.sourceIdentity)
        assertEquals(seed.sourceIdentity, durable.identity)
        assertEquals(seed.candidateId, durable.candidateId)
        assertEquals(seed.variantId, durable.variantId)
    }

    @Test fun separateTasksForTheSameActualUrlHaveUnrelatedSourceAndSelectionValues() {
        val sources = (0 until 64).map { privateSource(liveRequest("task-$it").asDurableReference()) }
        assertEquals(sources.size, sources.map { it.sourceIdentity }.toSet().size)
        assertEquals(sources.size * 2, sources.flatMap { listOf(it.candidateId, it.variantId) }.toSet().size)
        for (source in sources) {
            assertTrue(source.sourceIdentity.matches(Regex("[a-f0-9]{64}")))
            assertNotEquals(selectedUrl.identity(), source.sourceIdentity)
            assertNotEquals(candidateHash, source.sourceIdentity)
            assertNotEquals(variantHash, source.sourceIdentity)
            for (selection in listOf(source.candidateId, source.variantId)) {
                assertEquals(36, selection.encoded.length)
                assertEquals(selection.encoded, UUID.fromString(selection.encoded).toString())
                assertNotEquals(candidateHash, selection.encoded)
                assertNotEquals(variantHash, selection.encoded)
            }
        }
    }

    @Test fun bindIsOnceOnlyEvenForTheSameTaskAndUnrelatedSeedsAreNotInterchangeable() {
        val seed = PrivateNetworkSourceSeed.create()
        val binding = seed.bindTask("task")
        assertFalse(binding.isFrom(PrivateNetworkSourceSeed.create()))
        for (taskId in listOf("task", "other-task")) {
            val failure = assertThrows(SaveFailure::class.java) { seed.bindTask(taskId) }
            assertEquals(SaveError.SOURCE_RESELECTION_REQUIRED, failure.code)
        }
        assertTrue(binding.isLive())
        assertEquals("task", binding.taskId)
    }

    @Test fun invalidTaskIdsCannotConsumeAnOtherwiseLiveSeed() {
        val seed = PrivateNetworkSourceSeed.create()
        for (invalid in listOf("", "task/other", "task with space", "t".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { seed.bindTask(invalid) }
            assertTrue(seed.isLive())
        }
        assertEquals("valid-task", seed.bindTask("valid-task").taskId)
    }

    @Test fun seedAndBindingShareMonotonicRevocation() {
        for (revokeViaSeed in listOf(true, false)) {
            val seed = PrivateNetworkSourceSeed.create()
            val binding = seed.bindTask("task")
            val request = liveRequest(source = binding)
            val alreadyDurable = request.asDurableReference()
            if (revokeViaSeed) seed.revoke() else binding.revoke()
            repeat(3) {
                assertFalse(seed.isLive())
                assertFalse(binding.isLive())
                assertThrows(IllegalArgumentException::class.java) { request.asDurableReference() }
                assertThrows(SaveFailure::class.java) { seed.bindTask("task") }
                seed.revoke()
                binding.revoke()
            }
            // Revocation does not destroy output-only values or create a new source authority.
            assertSame(alreadyDurable, alreadyDurable.asDurableReference())
            assertEquals(binding.sourceIdentity.encoded, record(alreadyDurable).request.networkSourceMetadata!!.sourceIdentity)
        }
    }

    @Test fun retiringAnUnboundSeedPermanentlyPreventsLaterBinding() {
        val seed = PrivateNetworkSourceSeed.create()
        seed.revoke()
        assertFalse(seed.isLive())
        repeat(3) {
            val failure = assertThrows(SaveFailure::class.java) { seed.bindTask("task") }
            assertEquals(SaveError.SOURCE_RESELECTION_REQUIRED, failure.code)
        }
    }

    @Test fun unboundAndWrongTaskSourcesCannotBecomeDurableTaskInputs() {
        val seed = PrivateNetworkSourceSeed.create()
        assertThrows(IllegalArgumentException::class.java) { liveRequest(source = seed).asDurableReference() }
        val bound = seed.bindTask("task")
        assertThrows(IllegalArgumentException::class.java) {
            liveRequest(taskId = "another-task", source = bound).asDurableReference()
        }
        assertTrue(liveRequest(source = bound).asDurableReference().input is SaveInput.PrivateNetwork)
    }

    @Test fun privateLiveResourceMustCarryItsExactRandomIdentityAndNeverAnOrdinaryDurableFlag() {
        val binding = PrivateNetworkSourceSeed.create().bindTask("task")
        val resource = liveResource(binding)
        assertThrows(IllegalArgumentException::class.java) { resource.copy(sourceIdentity = selectedUrl.identity()) }
        assertThrows(IllegalArgumentException::class.java) { resource.copy(privateSource = null) }
        assertThrows(IllegalArgumentException::class.java) { resource.copy(durableReference = true) }
        assertThrows(IllegalArgumentException::class.java) {
            resource.copy(url = selectedUrl.originOnly(), requestContextRef = null, durableReference = true)
        }
        assertThrows(IllegalArgumentException::class.java) { resource.asDurableReference() }
    }

    @Test fun privateDurableInputHasNoUrlOrCopiedLiveCandidateVariantLeaseOrExpiry() {
        val request = liveRequest(audio = true, method = TaskMethod.STREAM)
        val durable = request.asDurableReference()
        val source = privateSource(durable)
        assertTrue(request.isPrivateNetwork)
        assertTrue(durable.isPrivateNetwork)
        assertSame(request.resource, request.networkSourceMetadata)
        assertSame(source, durable.networkSourceMetadata)
        assertNull(durable.networkResource)
        assertNull(durable.captureDescriptor)
        assertThrows(IllegalArgumentException::class.java) { durable.resource }
        assertTrue(source.expectedAudio)
        assertEquals(TaskMethod.STREAM, durable.method)
        assertEquals(request.requestId, durable.requestId)
        assertEquals(request.idempotencyKey, durable.idempotencyKey)
        assertNotEquals(candidateHash, source.candidateId.encoded)
        assertNotEquals(variantHash, source.variantId.encoded)

        val fields = PrivateDurableSource::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
        assertEquals(setOf("identity", "candidateId", "variantId", "expectedAudio"), fields.map { it.name }.toSet())
        assertFalse(fields.any { SourceUrl::class.java.isAssignableFrom(it.type) || RequestContextRef::class.java.isAssignableFrom(it.type) })
        assertEquals(setOf("getSourceIdentity", "getExpectedAudio"),
            NetworkSourceMetadata::class.java.declaredMethods.map { it.name }.toSet())
        assertFalse(PrivateDurableSource::class.java.methods.any { it.name in setOf("getUrl", "getRequestContextRef", "getExpiresAtEpochMillis", "bindTask", "isLive") })
    }

    @Test fun finalizedExpectedAudioUsesTheSelectionWhileRandomValuesRemainFixed() {
        val seed = PrivateNetworkSourceSeed.create()
        val prepared = liveResource(seed, audio = false)
        val selected = prepared.copy(expectedAudio = true, privateSource = seed.bindTask("task"))
        val durable = privateSource(SaveRequest("task", "key", selected).asDurableReference())
        assertTrue(durable.expectedAudio)
        assertEquals(prepared.sourceIdentity, durable.sourceIdentity)
        assertEquals(seed.candidateId, durable.candidateId)
        assertEquals(seed.variantId, durable.variantId)
        assertFalse(prepared.expectedAudio)
        assertSame(seed, prepared.privateSource)
    }

    @Test fun ordinaryValueEqualityAndOriginOnlyDurabilityRemainUnchanged() {
        val live = SelectedMediaResource("candidate", "variant", selectedUrl, true,
            RequestContextRef("ordinary-context"), expiresAtEpochMillis = 10_000)
        val request = SaveRequest("ordinary", "ordinary-key", live)
        assertEquals(live, live.copy())
        assertEquals(request, request.copy(resource = live.copy()))
        assertSame(live, request.networkSourceMetadata)
        assertFalse(request.isPrivateNetwork)
        val durable = request.asDurableReference()
        assertTrue(durable.input is SaveInput.Network)
        assertFalse(durable.isPrivateNetwork)
        assertEquals(live.asDurableReference(), durable.resource)
        assertEquals(selectedUrl.originOnly(), durable.resource.url)
        assertEquals(selectedUrl.identity(), durable.resource.sourceIdentity)
        assertEquals("candidate", durable.resource.candidateId)
        assertEquals("variant", durable.resource.variantId)
        assertEquals(10_000L, durable.resource.expiresAtEpochMillis)
        assertTrue(durable.resource.expectedAudio)
        assertNull(durable.resource.requestContextRef)
        assertNull(durable.resource.privateSource)
        assertTrue(durable.resource.durableReference)
        assertEquals(durable, durable.asDurableReference())
    }

    @Test fun captureRemainsUrlFreeWithoutBecomingAPrivateNetworkInput() {
        val capture = SaveRequest("capture", "capture-key",
            SaveInput.Capture(CaptureDescriptor("capture", 7, RecordingAudioChoice.NONE)))
        assertNull(capture.networkResource)
        assertNull(capture.networkSourceMetadata)
        assertFalse(capture.isPrivateNetwork)
        assertSame(capture, capture.asDurableReference())
        assertEquals(TaskMethod.RECORD, capture.method)
    }

    @Test fun restoredIdentifiersAreCanonicalValueObjectsWithValueEqualityOnly() {
        val identity = PrivateTaskSourceIdentity.fromDurable("ab".repeat(32))
        val candidate = PrivateSelectionIdentity.fromDurable("00000000-0000-4000-8000-000000000001")
        val variant = PrivateSelectionIdentity.fromDurable("00000000-0000-4000-8000-000000000002")
        val original = PrivateDurableSource(identity, candidate, variant, true)
        val restored = PrivateDurableSource(PrivateTaskSourceIdentity.fromDurable(identity.encoded),
            PrivateSelectionIdentity.fromDurable(candidate.encoded), PrivateSelectionIdentity.fromDurable(variant.encoded), true)
        assertNotSame(original.identity, restored.identity)
        assertNotSame(original.candidateId, restored.candidateId)
        assertEquals(original, restored)
        assertEquals(original.hashCode(), restored.hashCode())
        assertEquals(identity.encoded, restored.sourceIdentity)
        assertNotEquals(identity, PrivateTaskSourceIdentity.fromDurable("cd".repeat(32)))
        assertNotEquals(candidate, variant)
        assertNotEquals(original, restored.copy(expectedAudio = false))
        assertNotEquals(original, restored.copy(candidateId = variant))
        assertNotEquals(original, restored.copy(variantId = candidate))
        assertEquals(1, setOf(original, restored).size)

        // A decoder validates shape, not whether caller-provided bytes actually have entropy.
        assertEquals("0".repeat(64), PrivateTaskSourceIdentity.fromDurable("0".repeat(64)).encoded)
        assertEquals("00000000-0000-0000-0000-000000000000",
            PrivateSelectionIdentity.fromDurable("00000000-0000-0000-0000-000000000000").encoded)
    }

    @Test fun invalidRestoredValuesAreRejectedRatherThanNormalizedOrHashed() {
        for (invalid in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64),
            " ${"a".repeat(64)}", "${"a".repeat(64)}\n", selectedUrl.value)) {
            assertThrows(IllegalArgumentException::class.java) { PrivateTaskSourceIdentity.fromDurable(invalid) }
        }
        for (invalid in listOf("", "1-1-1-1-1", "00000000000040008000000000000001",
            "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "00000000-0000-4000-8000-00000000000g",
            "00000000-0000-4000-8000-000000000001 ", candidateHash)) {
            assertThrows(IllegalArgumentException::class.java) { PrivateSelectionIdentity.fromDurable(invalid) }
        }
    }

    @Test fun decodingDurableValuesCannotImportLiveSeedOrTaskBindingAuthority() {
        val request = liveRequest().asDurableReference()
        val source = privateSource(request)
        val restored = source.copy(identity = PrivateTaskSourceIdentity.fromDurable(source.identity.encoded),
            candidateId = PrivateSelectionIdentity.fromDurable(source.candidateId.encoded),
            variantId = PrivateSelectionIdentity.fromDurable(source.variantId.encoded))
        assertFalse(PrivateNetworkSource::class.java.isInstance(restored))
        assertFalse(PrivateNetworkSource::class.java.isInstance(restored.identity))
        assertFalse(PrivateNetworkSource::class.java.isInstance(restored.candidateId))
        for (type in listOf(PrivateTaskSourceIdentity::class.java, PrivateSelectionIdentity::class.java, PrivateDurableSource::class.java)) {
            assertFalse(type.methods.any { PrivateNetworkSource::class.java.isAssignableFrom(it.returnType) })
        }
        val createMethods = PrivateNetworkSourceSeed.Companion::class.java.methods.filter { it.name == "create" }
        assertTrue(createMethods.isNotEmpty())
        assertTrue(createMethods.all { it.parameterCount == 0 })
        for (type in listOf(PrivateNetworkSourceSeed::class.java, PrivateNetworkSourceBinding::class.java)) {
            assertTrue(type.constructors.filterNot { it.isSynthetic }.isEmpty())
        }
        val newSeed = PrivateNetworkSourceSeed.create()
        assertNotEquals(restored.identity, newSeed.sourceIdentity)
        assertEquals(request, request.copy(input = SaveInput.PrivateNetwork(restored)))
    }

    @Test fun allSourceAndRequestDiagnosticsRemainRedacted() {
        val seed = PrivateNetworkSourceSeed.create()
        val binding = seed.bindTask("task")
        val request = liveRequest(source = binding)
        val durable = request.asDurableReference()
        val source = privateSource(durable)
        val prohibited = listOf(selectedUrl.value, selectedUrl.originOnly().value, selectedUrl.identity(),
            candidateHash, variantHash, "private-query", "private-request-context", source.sourceIdentity,
            source.candidateId.encoded, source.variantId.encoded)
        for (value in listOf(seed, binding, source.identity, source.candidateId, source.variantId,
            request.resource, request.input, request, source, durable.input, durable, record(durable))) {
            for (sentinel in prohibited) assertFalse("Diagnostic exposed private source material", value.toString().contains(sentinel))
        }
    }

    @Test fun durableRecordsRejectLivePrivateResourcesAndOnlyAcceptNetworkMethods() {
        val request = liveRequest()
        assertThrows(IllegalArgumentException::class.java) { record(request) }
        val durable = request.asDurableReference()
        assertEquals(durable, record(durable).request)
        assertEquals(TaskMethod.STREAM, record(durable.copy(method = TaskMethod.STREAM)).request.method)
        for (method in listOf(TaskMethod.BROWSER, TaskMethod.RECORD, TaskMethod.EDIT)) {
            assertThrows(IllegalArgumentException::class.java) { durable.copy(method = method) }
        }
    }

    @Test fun privateCheckpointsRejectEvenWhenOnlyRedirectHashOrServerEtagCouldLeak() {
        val request = liveRequest().asDurableReference()
        val identity = request.networkSourceMetadata!!.sourceIdentity
        val redirectHash = SourceUrl("https://redirect-sentinel.invalid/final.mp4").identity()
        for ((checkpointIdentity, etag) in listOf(identity to "\"server-private-etag\"",
            redirectHash to "\"opaque-validator\"", redirectHash to "\"server-private-etag\"")) {
            val checkpoint = DownloadCheckpoint(artifact.artifactId, checkpointIdentity, 512, etag, 1024)
            assertThrows(IllegalArgumentException::class.java) { record(request, checkpoint = checkpoint) }
        }
        assertNull(record(request).checkpoint)
    }

    @Test fun privateRawMimeUsesAStaticBareCanonicalAllowlistIncludingHistoricalOwnedFormats() {
        val request = liveRequest().asDurableReference()
        // Durable historical readability has no dependency on a current live MOV/WebM admission flag.
        for (mime in listOf("video/mp4", "application/mp4", "application/octet-stream", "video/quicktime", "video/webm")) {
            val transfer = TransferResult(artifact.artifactId, 1024, mime, 1024)
            val stored = record(request, transfer)
            assertSame(transfer, stored.transfer)
            assertEquals(mime, stored.selectedOutputDescriptor()!!.mimeType)
        }
        for (mime in listOf("", "VIDEO/MP4", "video/MP4", "video/mp4 ", " video/mp4", "video/mp4; private=sentinel",
            "video/quicktime; codecs=avc1", "application/octet-stream\n", "text/html", "video/webm; codecs=vp8", "VIDEO/WEBM", "video/webm ", "private-mime-sentinel")) {
            assertThrows(IllegalArgumentException::class.java) {
                record(request, TransferResult(artifact.artifactId, 1024, mime, 1024))
            }
        }
    }

    @Test fun privateExpectedAudioCannotBeLostWhenVerificationHasNoNetworkResource() {
        for (method in listOf(TaskMethod.DIRECT, TaskMethod.STREAM)) {
            val request = liveRequest(audio = true, method = method).asDurableReference()
            val transfer = TransferResult(artifact.artifactId, 1024, "video/mp4", 1024,
                streamExpectation = if (method == TaskMethod.STREAM) StreamArtifactExpectation(1000, 1, 1, 1920, 1080) else null)
            val silent = goodEvidence(artifact, transfer)
            assertNull(request.networkResource)
            assertTrue(request.networkSourceMetadata!!.expectedAudio)
            assertThrows(IllegalArgumentException::class.java) { record(request, transfer, silent) }
            val sound = silent.copy(audioTrackCount = 1, audioTrackReadable = true)
            assertEquals(sound, record(request, transfer, sound).verification)
            assertThrows(IllegalArgumentException::class.java) {
                record(request, transfer, sound.copy(audioTrackReadable = false))
            }
        }
    }

    @Test fun privateNormalizedOutputProofRequiresTheExactRandomSourceIdentity() {
        val request = liveRequest().asDurableReference()
        val transfer = TransferResult(artifact.artifactId, 4096, "video/quicktime", 4096)
        val conservation = videoConservation()
        val proof = DirectNormalizationProof("00000000-0000-4000-8000-000000000001", request.requestId,
            request.networkSourceMetadata!!.sourceIdentity, artifact.artifactId, artifact.commitId,
            4096, "d".repeat(64), sourceTransferFingerprint(transfer), 2048, "e".repeat(64), conservation)
        val independent = DirectNormalizationVerificationProof(proof.normalizationId, proof.taskId, proof.sourceIdentity,
            proof.artifactId, proof.commitId, proof.digest(), conservation,
            DirectVideoTailProof(conservation.precedingVideoSync, conservation.video.lastSample,
                conservation.video.lastSample.presentationTimeUs, true))
        val evidence = VerificationEvidence(artifact.artifactId, 2048, proof.outputSha256, true, 1, 0, 1000,
            16, 16, false, DecodePosition.entries.toSet(), false, directNormalizationProof = independent)
        val base = record(request, transfer).copy(directNormalization = proof, verification = evidence)
        assertNull(base.request.networkResource)
        assertSame(transfer, base.selectedOutputDescriptor()!!.sourceTransfer)
        assertTrue(base.selectedOutputDescriptor() is ArtifactOutputDescriptor.NormalizedDirect)
        for (foreignIdentity in listOf(selectedUrl.identity(), PrivateNetworkSourceSeed.create().sourceIdentity.encoded)) {
            assertThrows(IllegalArgumentException::class.java) { base.copy(directNormalization = proof.copy(sourceIdentity = foreignIdentity)) }
            assertThrows(IllegalArgumentException::class.java) {
                base.copy(verification = evidence.copy(directNormalizationProof = independent.copy(sourceIdentity = foreignIdentity)))
            }
        }
        val needsAudio = privateSource(request).copy(expectedAudio = true)
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(request = request.copy(input = SaveInput.PrivateNetwork(needsAudio)))
        }
    }

    @Test fun concurrentBindsHaveExactlyOneWinnerForSameOrDifferentTaskIds() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(64) { iteration ->
                val seed = PrivateNetworkSourceSeed.create()
                val start = CyclicBarrier(3)
                val tasks = listOf("task", if (iteration % 2 == 0) "task" else "other-task")
                val futures = tasks.map { task -> pool.submit<Result<PrivateNetworkSourceBinding>> {
                    start.await(5, TimeUnit.SECONDS)
                    runCatching { seed.bindTask(task) }
                } }
                start.await(5, TimeUnit.SECONDS)
                val results = futures.map { it.get(5, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it.isSuccess })
                val binding = results.single { it.isSuccess }.getOrThrow()
                assertTrue(binding.isFrom(seed))
                assertTrue(binding.isLive())
                assertSame(seed.sourceIdentity, binding.sourceIdentity)
                val failure = results.single { it.isFailure }.exceptionOrNull()
                assertTrue(failure is SaveFailure)
                assertEquals(SaveError.SOURCE_RESELECTION_REQUIRED, (failure as SaveFailure).code)
            }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun concurrentBindAndRevokeNeverLeaveOrResurrectLiveAuthority() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(128) {
                val seed = PrivateNetworkSourceSeed.create()
                val start = CyclicBarrier(3)
                val bindingFuture = pool.submit<Result<PrivateNetworkSourceBinding>> {
                    start.await(5, TimeUnit.SECONDS)
                    runCatching { seed.bindTask("task") }
                }
                val revokeFuture = pool.submit {
                    start.await(5, TimeUnit.SECONDS)
                    seed.revoke()
                }
                start.await(5, TimeUnit.SECONDS)
                val result = bindingFuture.get(5, TimeUnit.SECONDS)
                revokeFuture.get(5, TimeUnit.SECONDS)
                assertFalse(seed.isLive())
                result.fold(onSuccess = { binding ->
                    assertTrue(binding.isFrom(seed))
                    assertFalse(binding.isLive())
                    assertThrows(IllegalArgumentException::class.java) { liveRequest(source = binding).asDurableReference() }
                }, onFailure = { failure ->
                    assertTrue(failure is SaveFailure)
                    assertEquals(SaveError.SOURCE_RESELECTION_REQUIRED, (failure as SaveFailure).code)
                })
                assertThrows(SaveFailure::class.java) { seed.bindTask("later-task") }
                assertFalse(seed.isLive())
            }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun videoConservation(): DirectRemuxConservation {
        val last = DirectSampleIdentity(29, 966_667, 4, "a".repeat(64), 0)
        val video = DirectTrackConservation(DirectSampleKind.AVC_BASELINE, "1".repeat(64), 30, 120,
            "2".repeat(64), "3".repeat(64), "4".repeat(64), 0, last, DirectTrackEndpoint(1_000_000, 1_000_000))
        return DirectRemuxConservation(16, 16, 30, video, null, null, null,
            DirectSampleIdentity(0, 0, 4, "c".repeat(64), 1))
    }
}
