package com.easysavevideo.discovery

import com.easysavevideo.core.model.EpochClock
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionCandidateAdmissionTest {
    private class Source(id: String) : VideoSearchProvider {
        override var descriptor = ProviderDescriptor(id, "1", progressScope = ProviderProgressScope.SESSION)
        override val searchBudget = SourceSearchBudget(1, 1024)
        var searches = 0
        override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage { searches++; return ProviderPage() }
    }
    private class Engine : VerificationEngine {
        override val id = "local"
        override val capabilities = setOf(EngineCapability.STATIC_PAGE)
        override val sendsDataToAnotherProcess = false
        var calls = 0
        var request: EngineRequest? = null
        var beforeResult: ((EngineRequest, DiscoveryOperation) -> Unit)? = null
        var known = true
        var evidence = true
        var duration: Long? = null
        override fun isAvailable() = true
        override fun isPhysicallyIdle() = true
        override fun verify(request: EngineRequest, operation: DiscoveryOperation): EngineResult {
            calls++; this.request = request
            assertTrue(request.network.isCurrent(request.candidate, operation))
            beforeResult?.invoke(request, operation)
            return EngineResult(request.requestId, request.generation, id,
                if (evidence) EngineOutcome.PAGE_EVIDENCE else EngineOutcome.UNKNOWN,
                if (evidence) EngineEvidence.HTML_VIDEO else EngineEvidence.NONE,
                if (evidence) RawVideoResult(request.candidate.pageUrl, true, durationMs = duration) else null,
                1, 100, known)
        }
    }
    private class Fixture(limits: SessionCandidateLimits = SessionCandidateLimits()) {
        var epoch = 1000L
        var monotonic = 0L
        val a = Source("source-a")
        val b = Source("source-b")
        val registry = ProviderRegistry(listOf(a, b))
        val admission = SessionCandidateAdmission(registry, limits, { epoch }, { monotonic })
        val scheduler = VerificationEngineScheduler { monotonic }
        val engine = Engine()
        val engines = VerificationEngineRegistry().apply { register(engine, EngineAccessBoundary.LOCAL_TARGET) }.all()
        val profile = SearchProfile("profile", "query")
        var stamp = SessionStamp("session", 1)
        val delegate = scheduler.validator(engines, "generation", false, false,
            epochMillis = { epoch }, sessionAdmission = admission)
        var intercept: ((CandidateUrl, DiscoveryOperation) -> Unit)? = null
        val pipeline = ValidationPipeline(object : CandidateValidator {
            override fun validate(candidate: CandidateUrl, operation: DiscoveryOperation) = validateEvidence(candidate, operation).raw
            override fun validateEvidence(candidate: CandidateUrl, operation: DiscoveryOperation): CandidateValidation {
                intercept?.invoke(candidate, operation)
                return delegate.validateEvidence(candidate, operation)
            }
        }, clock = EpochClock { epoch }, retainFailedValidationCandidates = true, sessionAdmission = admission)
        init { admission.beginUserSearch(stamp, profile.queryKey) }
        fun request(source: Source = a, p: SearchProfile = profile) = SearchRequest(stamp, p, null, source.descriptor)
        fun page(source: Source = a, url: String = "https://public.example.org/page", operation: DiscoveryOperation = operation(),
            authority: ProviderAuthorityView? = null): ValidatedProviderPage {
            pipeline.reserveTrustedSource(source, request(source), authority, operation)
            return pipeline.validateTrusted(source, request(source), authority,
                ProviderPage(candidates = listOf(CandidateUrl(url, SourceReportedCandidateMetadata(title = "Source report")))), operation)
        }
        fun engineRequest(candidate: CandidateUrl, operation: DiscoveryOperation) = EngineRequest("request", "generation", candidate,
            3000, 3, 2_097_152, false, false, admission.network(candidate, operation)!!)
    }

    @Test fun onlyTrustedPipelineMintsForExactObjectAndOperationAndOneAdmission() {
        val f = Fixture()
        var captured: EngineRequest? = null
        var originalOperation: DiscoveryOperation? = null
        f.intercept = { candidate, op ->
            originalOperation = op
            assertNull(f.admission.network(candidate.copy(), op))
            assertNull(f.admission.network(candidate.copy(pageUrl = "https://other.example.org/page"), op))
            assertNull(f.admission.network(candidate, operation()))
            val request = f.engineRequest(candidate, op)
            captured = request
            for (forged in listOf(request.copy(candidate = candidate.copy()),
                request.copy(candidate = candidate.copy(pageUrl = "https://other.example.org/page")))) {
                assertEquals(EngineOutcome.DENIED, f.scheduler.verify(forged, op, f.engines).outcome)
            }
            assertFalse(request.network.isCurrent(candidate, op)) // Not consumed yet.
        }
        assertEquals(1, f.page().results.size)
        assertEquals(1, f.engine.calls)
        assertEquals(EngineOutcome.DENIED, f.scheduler.verify(captured!!, originalOperation!!, f.engines).outcome)
        assertFalse(captured!!.network.isCurrent(captured!!.candidate, originalOperation!!))
        assertEquals(1, f.engine.calls)
    }

    @Test fun directPublicPipelineAndUnregisteredProviderCannotMintAuthority() {
        val f = Fixture()
        val untrusted = f.pipeline.validate("source-a", ProviderPage(candidates = listOf(CandidateUrl("https://public.example.org/page"))), SearchFilters(), operation())
        assertTrue(untrusted.results.isEmpty()); assertEquals(0, f.engine.calls)
        val impostor = Source("source-a")
        val forged = f.pipeline.validateTrusted(impostor, f.request(), null,
            ProviderPage(candidates = listOf(CandidateUrl("https://public.example.org/forged"))), operation())
        assertTrue(forged.results.isEmpty()); assertEquals(1, forged.pendingCandidates.size); assertEquals(0, f.engine.calls)
    }

    @Test fun cancellationNewQueryAndExpiredLeaseRejectCapturedAuthority() {
        for (change in 0..2) {
            val f = Fixture()
            f.intercept = { candidate, op ->
                val captured = f.engineRequest(candidate, op)
                when (change) {
                    0 -> op.cancellation.cancel()
                    1 -> f.admission.beginUserSearch(SessionStamp("session", 2), SearchProfile("profile", "other").queryKey)
                    else -> f.monotonic = 60_000
                }
                assertEquals(EngineOutcome.DENIED, f.scheduler.verify(captured, op, f.engines).outcome)
            }
            try { assertTrue(f.page().results.isEmpty()) } catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.CANCELLED, failure.code) }
            assertEquals(0, f.engine.calls)
        }
    }

    @Test fun sourceDescriptorAndAuthorityGenerationAreLiveFences() {
        val f = Fixture()
        f.intercept = { candidate, op ->
            val captured = f.engineRequest(candidate, op)
            f.a.descriptor = f.a.descriptor.copy(version = "2")
            assertEquals(EngineOutcome.DENIED, f.scheduler.verify(captured, op, f.engines).outcome)
        }
        assertTrue(f.page().results.isEmpty()); assertEquals(0, f.engine.calls)
        val g = Fixture()
        var current = true
        val authority = object : ProviderAuthorityView {
            override val descriptor = g.a.descriptor
            override fun isCurrent(nowMillis: Long) = current
            override fun publishIfCurrent(nowMillis: Long, operation: DiscoveryOperation?, publish: () -> Unit): Boolean {
                if (!current) return false
                publish(); return true
            }
        }
        g.engine.beforeResult = { request, op -> current = false; assertFalse(request.network.isCurrent(request.candidate, op)) }
        assertTrue(g.page(authority = authority).results.isEmpty())
        assertEquals(1, g.engine.calls)
    }

    @Test fun duplicatesAcrossSourcesAndPagesValidateOnceAndRetainAllProvenance() {
        val f = Fixture()
        assertEquals(setOf("source-a"), f.page().results.single().provenance)
        val duplicate = f.page(f.b, "HTTPS://PUBLIC.EXAMPLE.ORG:443/page")
        assertEquals(setOf("source-a", "source-b"), duplicate.results.single().provenance)
        assertEquals(setOf("source-a", "source-b"), f.page().results.single().provenance)
        assertEquals(1, f.engine.calls)
    }

    @Test fun inFlightDuplicateDoesNotStartBAndFirstEvidenceIncludesBothSources() {
        val f = Fixture()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.engine.beforeResult = { _, _ -> entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
        var first: ValidatedProviderPage? = null
        val worker = Thread { first = f.page() }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val duplicate = f.page(f.b)
        assertTrue(duplicate.results.isEmpty())
        assertEquals(setOf("source-a", "source-b"), duplicate.pendingCandidates.single().provenance)
        release.countDown(); worker.join(2500)
        assertFalse(worker.isAlive)
        assertEquals(setOf("source-a", "source-b"), first!!.results.single().provenance)
        assertEquals(1, f.engine.calls)
    }

    @Test fun sharedRequestAndByteBudgetsNeverRefundUnknownConsumption() {
        for (requestLimited in listOf(true, false)) {
            val f = Fixture(if (requestLimited) SessionCandidateLimits(maxRequests = 5)
                else SessionCandidateLimits(maxBytes = 512L * 1024 + 2048))
            f.engine.known = false
            assertTrue(f.page().results.isEmpty())
            assertTrue(f.page(f.b, "https://second.example.org/page").results.isEmpty())
            assertEquals(1, f.engine.calls)
            assertEquals(if (requestLimited) SessionAdmissionStatus.REQUEST_LIMIT else SessionAdmissionStatus.BYTE_LIMIT, f.admission.status())
        }
    }

    @Test fun finiteRegistryAndDurationCapDoNotBecomeNonVideoOrRenew() {
        val f = Fixture(SessionCandidateLimits(maxUniquePages = 1, lifetimeMillis = 100))
        assertEquals(1, f.page().results.size)
        assertTrue(f.engine.request!!.deadlineMonotonicMillis <= 100)
        val capped = f.page(f.b, "https://second.example.org/page")
        assertTrue(capped.results.isEmpty()); assertEquals(1, capped.pendingCandidates.size)
        assertEquals(SessionAdmissionStatus.PAGE_LIMIT, f.admission.status())
        f.monotonic = 100
        assertEquals(SessionAdmissionStatus.EXPIRED, f.admission.status())
        assertEquals(DiscoveryError.DEADLINE_EXCEEDED, assertThrows(DiscoveryFailure::class.java) { f.page(f.b) }.code)
        assertEquals(1, f.engine.calls)
    }

    @Test fun httpPrivateLiteralAndFragmentNeverGetTemporaryAdmission() {
        for (url in listOf("http://public.example.org/page", "https://127.0.0.1/page", "https://10.0.0.1/page",
            "https://localhost/page", "https://public.example.org/page#fragment", "https://user@public.example.org/page")) {
            val f = Fixture()
            assertTrue(f.page(url = url).results.isEmpty())
            assertEquals(0, f.engine.calls)
        }
    }

    @Test fun manuallySelectedRobotsModeWithoutGrantFailsClosedAndNoSourceMetadataBecomesEvidence() {
        val candidate = CandidateUrl("https://public.example.org/page")
        assertFalse(EngineNetworkConstraints(setOf("https://public.example.org:443"), requiresRobotsCheck = true).isCurrent(candidate, operation()))
        val f = Fixture(); f.engine.evidence = false
        val page = f.page()
        assertTrue(page.results.isEmpty())
        assertEquals(CandidateMetadataLevel.SOURCE_REPORTED, page.pendingCandidates.single().sourceReported!!.metadataLevel)
        assertEquals(EngineEvidence.NONE, page.verificationReports.single().summary.evidence)
    }


    @Test fun sourceEnvelopeIsReservedBeforeSearchAndInsufficientQuotaCallsNoProvider() {
        val f = Fixture(SessionCandidateLimits(maxBytes = 512))
        val runtime = IsolatedProviderRuntime(f.pipeline, FakeTimer(FakeClock()), EpochClock { f.epoch })
        val completed = CountDownLatch(1)
        var outcome: ProviderOutcome? = null
        try {
            runtime.execute(f.a, f.request(), operation()) { outcome = it; completed.countDown() }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(DiscoveryError.CAPACITY_REACHED, (outcome as ProviderOutcome.Failure).code)
            assertEquals(0, f.a.searches)
            assertEquals(0, f.engine.calls)
            assertEquals(SessionAdmissionStatus.BYTE_LIMIT, f.admission.status())
        } finally { runtime.close() }
    }

    @Test fun sourceOperationCannotReplayOrMintAfterQueryOrRegistrationChanges() {
        val f = Fixture(); val op = operation()
        f.pipeline.reserveTrustedSource(f.a, f.request(), null, op)
        assertEquals(DiscoveryError.CANCELLED, assertThrows(DiscoveryFailure::class.java) {
            f.pipeline.reserveTrustedSource(f.a, f.request(), null, op)
        }.code)
        assertEquals(DiscoveryError.CANCELLED, assertThrows(DiscoveryFailure::class.java) {
            f.pipeline.reserveTrustedSource(f.a, f.request(p = SearchProfile("profile", "other")), null, operation())
        }.code)
        f.registry.remove("source-a"); f.registry.register(Source("source-a"))
        assertEquals(DiscoveryError.CANCELLED, assertThrows(DiscoveryFailure::class.java) {
            f.pipeline.reserveTrustedSource(f.a, f.request(), null, operation())
        }.code)
    }

    @Test fun capLeavesCandidateUnrunAndLaterRealSourceMayClaimItOnce() {
        val f = Fixture()
        val pipeline = ValidationPipeline(f.delegate, clock = EpochClock { f.epoch }, retainFailedValidationCandidates = true,
            maxValidationsPerPage = 1, sessionAdmission = f.admission)
        val firstOperation = operation()
        pipeline.reserveTrustedSource(f.a, f.request(), null, firstOperation)
        val first = pipeline.validateTrusted(f.a, f.request(), null, ProviderPage(candidates = listOf(
            CandidateUrl("https://public.example.org/first"), CandidateUrl("https://public.example.org/unrun"))), firstOperation)
        assertEquals(1, first.results.size)
        val unrun = first.pendingCandidates.single()
        assertNull(unrun.verification)
        assertEquals(setOf("source-a"), unrun.provenance)
        val nextOperation = operation()
        pipeline.reserveTrustedSource(f.b, f.request(f.b), null, nextOperation)
        val next = pipeline.validateTrusted(f.b, f.request(f.b), null,
            ProviderPage(candidates = listOf(CandidateUrl(unrun.canonicalPageUrl))), nextOperation)
        assertEquals(setOf("source-a", "source-b"), next.results.single().provenance)
        assertEquals(2, f.engine.calls)
        assertEquals(setOf("source-a", "source-b"), f.page(f.a, unrun.canonicalPageUrl).results.single().provenance)
        assertEquals(2, f.engine.calls)
    }

    @Test fun boundedSourceDeadlineNeverOutlivesSessionWindowOrExtendsOriginalDeadline() {
        val f = Fixture()
        assertEquals(16000L, f.admission.boundDeadline(f.stamp, 16000))
        f.monotonic = 59_000; f.epoch = 60_000
        assertEquals(61_000L, f.admission.boundDeadline(f.stamp, 75_000))
        assertEquals(60_500L, f.admission.boundDeadline(f.stamp, 60_500))
        f.admission.revoke()
        assertEquals(SessionAdmissionStatus.INACTIVE, f.admission.status())
        assertEquals(f.epoch, f.admission.boundDeadline(f.stamp, 75_000))
    }


    @Test fun busyPhysicalSlotDoesNotSpendGrantAndLaterSourceMayClaimUnrunCandidate() {
        val f = Fixture(SessionCandidateLimits(maxRequests = 9, maxBytes = 2L * 512 * 1024 + 3L * 1024))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var stale: EngineRequest? = null; var staleOperation: DiscoveryOperation? = null
        f.intercept = { candidate, op ->
            if (candidate.pageUrl.endsWith("second") && stale == null) { stale = f.engineRequest(candidate, op); staleOperation = op }
        }
        f.engine.beforeResult = { request, _ ->
            if (request.candidate.pageUrl.endsWith("first")) { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
        }
        var first: ValidatedProviderPage? = null
        val worker = Thread { first = f.page(url = "https://public.example.org/first") }
        worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        val unrun = f.page(f.b, "https://public.example.org/second")
        assertTrue(unrun.results.isEmpty()); assertEquals(1, unrun.pendingCandidates.size)
        assertEquals(1, f.engine.calls)
        release.countDown(); worker.join(2500); assertFalse(worker.isAlive); assertEquals(1, first!!.results.size)
        assertEquals(EngineOutcome.DENIED, f.scheduler.verify(stale!!, staleOperation!!, f.engines).outcome)
        val retried = f.page(f.a, "https://public.example.org/second")
        assertEquals(1, retried.results.size)
        assertEquals(setOf("source-a", "source-b"), retried.results.single().provenance)
        assertEquals(2, f.engine.calls)
    }

    @Test fun unknownSourceDurationCanBeMeasuredBeforeActualDurationFilter() {
        val f = Fixture(); f.engine.duration = 1500
        val filters = SearchFilters(includeUnknownDuration = false, minimumDurationMs = 1000, maximumDurationMs = 2000)
        val profile = f.profile.copy(filters = filters)
        f.admission.beginUserSearch(f.stamp, profile.queryKey)
        val request = f.request(p = profile); val op = operation()
        f.pipeline.reserveTrustedSource(f.a, request, null, op)
        val page = f.pipeline.validateTrusted(f.a, request, null,
            ProviderPage(candidates = listOf(CandidateUrl("https://public.example.org/measured"))), op)
        assertEquals(1, page.results.size); assertEquals(1500L, page.results.single().durationMs)
        assertTrue(page.pendingCandidates.isEmpty()); assertEquals(1, f.engine.calls)
    }

    @Test fun neverAuthorizedRestoreAndResumeAfterRevokeCannotBypassSourceLedger() {
        val f = Fixture()
        val clock = FakeClock(); val timer = FakeTimer(clock); val store = InMemoryDiscoveryStore()
        val runtime = object : ProviderRuntime {
            override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
                callback: (ProviderOutcome) -> Unit): CancellationHandle {
                try {
                    operation.check(clock.now)
                    f.pipeline.reserveTrustedSource(provider, request, null, operation)
                    val page = provider.search(request, operation)
                    callback(ProviderOutcome.Success(f.pipeline.validateTrusted(provider, request, null, page, operation)))
                } catch (failure: DiscoveryFailure) { callback(ProviderOutcome.Failure(failure.code)) }
                finally { f.pipeline.endTrustedSource(operation) }
                return CancellationHandle { operation.cancellation.cancel() }
            }
        }
        val coordinator = VideoDiscoveryCoordinator(f.registry, runtime, store, timer, clock, sessionAdmission = f.admission)
        try {
            coordinator.setVisibility(true, true)
            coordinator.start("session", f.profile)
            assertEquals(0, f.a.searches + f.b.searches)
            coordinator.start("session", f.profile, allowSessionCandidateVerification = true)
            val searched = f.a.searches + f.b.searches
            assertTrue(searched > 0)
            val generation = coordinator.suspendImmediately()
            coordinator.setVisibility(false, false, generation)
            coordinator.setVisibility(true, true, generation)
            coordinator.requestMore()
            assertEquals(searched, f.a.searches + f.b.searches)
            assertTrue(coordinator.restore("session"))
            coordinator.requestMore()
            assertEquals(searched, f.a.searches + f.b.searches)
        } finally { coordinator.close() }
    }

    @Test fun publicAddressRejectsReservedDocumentationAndTransitionNetworks() {
        fun v4(vararg parts: Int) = parts.map(Int::toByte).toByteArray()
        for (value in listOf(v4(192,0,0,1), v4(192,0,2,1), v4(198,51,100,1), v4(203,0,113,1), v4(192,88,99,1)))
            assertFalse(VerificationOriginPolicy.publicAddress(value))
        for (prefix in listOf(listOf(0x20,1,0,0), listOf(0x20,1,0x0d,0xb8), listOf(0x20,2,0,0), listOf(0x3f,0xff,0,0))) {
            val address = ByteArray(16); prefix.forEachIndexed { index, value -> address[index] = value.toByte() }
            assertFalse(VerificationOriginPolicy.publicAddress(address))
        }
        assertTrue(VerificationOriginPolicy.publicAddress(v4(8,8,8,8)))
    }
}
