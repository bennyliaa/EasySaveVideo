package com.easysavevideo.commons.rules

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.CandidateMetadataLevel
import com.easysavevideo.discovery.DiscoveryCancellation
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryLimits
import com.easysavevideo.discovery.ProviderDescriptor
import com.easysavevideo.discovery.SearchFilters
import com.easysavevideo.discovery.ValidationPipeline
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DeclarativeJsonRuleExecutorTest {
    @Test fun twoIndependentSourceShapesProduceOnlyPendingCandidatesThroughExistingPipeline() {
        val alpha = RuleFixtures.executor()
        val betaRule = DeclarativeJsonRule.compile(RuleFixtures.bytes(
            itemsPath = "[\"data\",\"groups\",0,\"entries\"]",
            fields = "\"pageUrl\":[\"links\",0,\"href\"],\"title\":[\"name\"],\"reportedDurationMs\":[\"timing\",\"ms\"]",
            cursorPath = "[\"paging\",\"token\"]", version = "7"))
        val beta = RuleFixtures.executor(betaRule, id = "fixture-beta", name = "Fixture Beta")
        val alphaPage = alpha.execute(RuleFixtures.response(next = "\"alpha-page-two\""), RuleFixtures.operation())
        val betaPage = beta.execute("""{"data":{"groups":[{"entries":[{"links":[{"href":"https://example.org/two"}],"name":"Two","timing":{"ms":1250}}]}]},"paging":{"token":"beta-page-two"}}""".toByteArray(), RuleFixtures.operation())
        for ((executor, page) in listOf(alpha to alphaPage, beta to betaPage)) {
            assertTrue(page.results.isEmpty())
            assertEquals(CandidateMetadataLevel.SOURCE_REPORTED, page.candidates.single().sourceReported!!.metadataLevel)
            val pending = ValidationPipeline(clock = EpochClock { 1000 }, retainUnvalidatedCandidates = true)
                .validate(executor.descriptor.id, page, SearchFilters(), RuleFixtures.operation())
            assertTrue(pending.results.isEmpty())
            assertEquals(1, pending.pendingCandidates.size)
            assertNull(pending.pendingCandidates.single().verification)
            assertEquals(setOf(executor.descriptor.id), pending.pendingCandidates.single().provenance)
            assertEquals(page.nextCursor, pending.nextCursor)
        }
        assertEquals("Fixture Alpha", alphaPage.candidates.single().sourceReported!!.sourceName)
        assertEquals("Fixture Beta", betaPage.candidates.single().sourceReported!!.sourceName)
        assertEquals(1250L, betaPage.candidates.single().sourceReported!!.reportedDurationMs)
        assertFalse(alphaPage.exhausted)
        assertFalse(betaPage.exhausted)
    }

    @Test fun absentValidatorDoesNotPromoteMappedCandidatesToVerifiedResults() {
        val page = RuleFixtures.executor().execute(RuleFixtures.response(), RuleFixtures.operation())
        RuleFixtures.fails(DiscoveryError.VALIDATOR_UNAVAILABLE) {
            ValidationPipeline(clock = EpochClock { 1000 }).validate("fixture-alpha", page, SearchFilters(), RuleFixtures.operation())
        }
    }

    @Test fun callerIdentityIsFixedAndResponseAuthorityFieldsAreIgnored() {
        val page = RuleFixtures.executor().execute(RuleFixtures.response("""{"url":"https://example.org/one","sourceName":"Forged","providerId":"forged","videoConfirmed":true,"rights":{"downloadAuthorized":true},"script":"throw secret"}"""), RuleFixtures.operation())
        assertTrue(page.results.isEmpty())
        assertEquals("Fixture Alpha", page.candidates.single().sourceReported!!.sourceName)
        assertEquals(CandidateMetadataLevel.SOURCE_REPORTED, page.candidates.single().sourceReported!!.metadataLevel)
    }

    @Test fun callerDescriptorCollectionsAreDetachedAndNotMutableThroughExport() {
        val supplied = mutableSetOf("older")
        val executor = DeclarativeJsonRuleExecutor(DeclarativeJsonRule.compile(RuleFixtures.bytes()),
            ProviderDescriptor("fixture-alpha", "1", compatibleCursorVersions = supplied), "Fixture Alpha", clock = EpochClock { 1000 })
        supplied.clear()
        assertEquals(setOf("older"), executor.descriptor.compatibleCursorVersions)
        try { (executor.descriptor.compatibleCursorVersions as MutableSet<String>).clear(); fail("Mutable descriptor export") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun missingAndNullOptionalFieldsRemainUnknown() {
        for (row in listOf("""{"url":"https://example.org/one"}""",
            """{"url":"https://example.org/one","title":null,"description":null,"duration":null,"published":null}""")) {
            val page = RuleFixtures.executor().execute(RuleFixtures.response(row, "null"), RuleFixtures.operation())
            val metadata = page.candidates.single().sourceReported!!
            assertNull(metadata.title); assertNull(metadata.description)
            assertNull(metadata.reportedDurationMs); assertNull(metadata.reportedPublishedAtEpochMillis)
            assertNull(page.nextCursor); assertNull(page.cursorExpiresAtEpochMillis); assertTrue(page.exhausted)
        }
    }

    @Test fun undeclaredCursorAndRootArraySelectionAreSupported() {
        val rule = DeclarativeJsonRule.compile(RuleFixtures.bytes(itemsPath = "[]", cursorPath = null))
        val page = RuleFixtures.executor(rule).execute("""[{"url":"https://example.org/one"}]""".toByteArray(), RuleFixtures.operation())
        assertEquals(1, page.candidates.size); assertTrue(page.exhausted)
        val noCursor = RuleFixtures.executor(DeclarativeJsonRule.compile(RuleFixtures.bytes(cursorPath = null)))
            .execute(RuleFixtures.response(next = "true"), RuleFixtures.operation())
        assertNull(noCursor.nextCursor)
    }

    @Test fun emptyPageWithOpaqueNextTokenDoesNotFetchOrInventResults() {
        val page = RuleFixtures.executor().execute(RuleFixtures.response(row = "", next = "\"https://example.org/not-a-request\""), RuleFixtures.operation())
        assertTrue(page.results.isEmpty()); assertTrue(page.candidates.isEmpty())
        assertEquals("https://example.org/not-a-request", page.nextCursor)
        assertNull(page.cursorExpiresAtEpochMillis); assertFalse(page.exhausted)
    }

    @Test fun normalizesAndDeduplicatesUrlsInFirstSeenOrderWithoutMergingEvidence() {
        val rows = """{"url":"HTTPS://EXAMPLE.ORG:443/one","title":"First"},{"url":"https://example.org/one","title":"Second"},{"url":"http://example.org:80/two","title":"Third"}"""
        val page = RuleFixtures.executor().execute(RuleFixtures.response(rows), RuleFixtures.operation())
        assertEquals(listOf("https://example.org/one", "http://example.org/two"), page.candidates.map { it.pageUrl })
        assertEquals(listOf("First", "Third"), page.candidates.map { it.sourceReported!!.title })
    }

    @Test fun dropsMissingNullUnsafeAndOverlengthUrlsButRejectsWrongUrlTypes() {
        val rows = """{},{"url":null},{"url":"javascript:alert(1)"},{"url":"file:///tmp/x"},{"url":"https://user:pass@example.org/one"},{"url":"https://example.org/ok"}"""
        val page = RuleFixtures.executor().execute(RuleFixtures.response(rows), RuleFixtures.operation())
        assertEquals("https://example.org/ok", page.candidates.single().pageUrl)
        for (value in listOf("1", "true", "[]", "{}")) RuleFixtures.fails(DiscoveryError.INVALID_RESULT) {
            RuleFixtures.executor().execute(RuleFixtures.response("{\"url\":$value}"), RuleFixtures.operation())
        }
        val prefix = "https://example.org/"
        val exact = prefix + "x".repeat(DeclarativeJsonRuleExecutor.MAX_URL_LENGTH - prefix.length)
        assertEquals(1, RuleFixtures.executor().execute(RuleFixtures.response("{\"url\":\"$exact\"}"), RuleFixtures.operation()).candidates.size)
        assertTrue(RuleFixtures.executor().execute(RuleFixtures.response("{\"url\":\"${exact}x\"}"), RuleFixtures.operation()).candidates.isEmpty())
    }

    @Test fun missingOrWrongItemsEnvelopeAndNonObjectRowsFailClosed() {
        for (text in listOf("{}", "{\"items\":null}", "{\"items\":{}}", "{\"items\":true}",
            "{\"items\":[null]}", "{\"items\":[\"https://example.org\"]}")) RuleFixtures.fails(DiscoveryError.INVALID_RESULT) {
            RuleFixtures.executor().execute(text.toByteArray(), RuleFixtures.operation())
        }
    }

    @Test fun optionalFieldsNeverStringifyWrongTypesAndWholePageIsRejected() {
        for (field in listOf("title", "description")) for (value in listOf("false", "123", "[]", "{}")) {
            RuleFixtures.fails(DiscoveryError.INVALID_RESULT) {
                RuleFixtures.executor().execute(RuleFixtures.response("{\"url\":\"https://example.org/one\",\"$field\":$value}"), RuleFixtures.operation())
            }
        }
        RuleFixtures.fails(DiscoveryError.INVALID_RESULT) {
            RuleFixtures.executor().execute(RuleFixtures.response("""{"url":"https://example.org/valid"},{"title":true}"""), RuleFixtures.operation())
        }
    }

    @Test fun intermediatePathTypeMismatchIsRejectedButMissingOptionalBranchIsAllowed() {
        val rule = DeclarativeJsonRule.compile(RuleFixtures.bytes(fields = "\"pageUrl\":[\"url\"],\"title\":[\"metadata\",\"title\"]"))
        val executor = RuleFixtures.executor(rule)
        for (row in listOf("""{"url":"https://example.org/one"}""", """{"url":"https://example.org/one","metadata":null}""")) {
            assertNull(executor.execute(RuleFixtures.response(row), RuleFixtures.operation()).candidates.single().sourceReported!!.title)
        }
        RuleFixtures.fails(DiscoveryError.INVALID_RESULT) {
            executor.execute(RuleFixtures.response("""{"url":"https://example.org/one","metadata":17}"""), RuleFixtures.operation())
        }
    }

    @Test fun numericMetadataRequiresExactPositiveDurationAndNonnegativeEpoch() {
        for (value in listOf("0", "-1", "1.5", "\"1000\"", "true", "{}", "9223372036854775808", "1e99")) {
            RuleFixtures.fails(DiscoveryError.INVALID_RESULT) {
                RuleFixtures.executor().execute(RuleFixtures.response("{\"url\":\"https://example.org/one\",\"duration\":$value}"), RuleFixtures.operation())
            }
        }
        for (value in listOf("-1", "0.1", "\"0\"", "[]", "9223372036854775808")) {
            RuleFixtures.fails(DiscoveryError.INVALID_RESULT) {
                RuleFixtures.executor().execute(RuleFixtures.response("{\"url\":\"https://example.org/one\",\"published\":$value}"), RuleFixtures.operation())
            }
        }
        val metadata = RuleFixtures.executor().execute(RuleFixtures.response("""{"url":"https://example.org/one","duration":9223372036854775807,"published":0}"""), RuleFixtures.operation()).candidates.single().sourceReported!!
        assertEquals(Long.MAX_VALUE, metadata.reportedDurationMs); assertEquals(0L, metadata.reportedPublishedAtEpochMillis)
        assertEquals(1000L, RuleFixtures.executor().execute(RuleFixtures.response("""{"url":"https://example.org/one","duration":1e3}"""), RuleFixtures.operation()).candidates.single().sourceReported!!.reportedDurationMs)
    }

    @Test fun sanitizesMarkupToPlainTextAndBoundsTextWithoutSilentOversizeSuccess() {
        val metadata = RuleFixtures.executor().execute(RuleFixtures.response("""{"url":"https://example.org/one","title":"<b>A &amp; B</b><script>private()</script>","description":"A\u202e B"}"""), RuleFixtures.operation()).candidates.single().sourceReported!!
        assertEquals("A & B", metadata.title); assertEquals("A B", metadata.description)
        for ((field, maximum) in listOf("title" to 512, "description" to 2048)) {
            RuleFixtures.executor().execute(RuleFixtures.response("{\"url\":\"https://example.org/one\",\"$field\":\"${"x".repeat(maximum)}\"}"), RuleFixtures.operation())
            RuleFixtures.fails(DiscoveryError.INVALID_RESULT) {
                RuleFixtures.executor().execute(RuleFixtures.response("{\"url\":\"https://example.org/one\",\"$field\":\"${"x".repeat(maximum + 1)}\"}"), RuleFixtures.operation())
            }
        }
    }

    @Test fun enforcesHardCallerAndOperationByteBudgetsBeforeParsing() {
        val bytes = RuleFixtures.response()
        val exact = bytes + ByteArray(DeclarativeJsonRuleExecutor.MAX_RESPONSE_BYTES.toInt() - bytes.size) { 32 }
        assertEquals(exact.size.toLong(), RuleFixtures.executor().execute(exact, RuleFixtures.operation()).responseBytes)
        RuleFixtures.fails(DiscoveryError.RESPONSE_TOO_LARGE) { RuleFixtures.executor().execute(exact + byteArrayOf(32), RuleFixtures.operation()) }
        RuleFixtures.executor(limits = DiscoveryLimits(maxResponseBytes = bytes.size.toLong())).execute(bytes, RuleFixtures.operation())
        RuleFixtures.fails(DiscoveryError.RESPONSE_TOO_LARGE) {
            RuleFixtures.executor(limits = DiscoveryLimits(maxResponseBytes = bytes.size - 1L)).execute(bytes, RuleFixtures.operation())
        }
        RuleFixtures.executor().execute(bytes, RuleFixtures.operation(maxBytes = bytes.size.toLong()))
        RuleFixtures.fails(DiscoveryError.RESPONSE_TOO_LARGE) {
            RuleFixtures.executor().execute(bytes, RuleFixtures.operation(maxBytes = bytes.size - 1L))
        }
    }

    @Test fun enforcesRawRowCountBeforeDiscardingOrDeduplicatingCandidates() {
        fun rows(count: Int) = List(count) { "{\"url\":\"javascript:ignored\"}" }.joinToString(",")
        assertTrue(RuleFixtures.executor().execute(RuleFixtures.response(rows(100)), RuleFixtures.operation()).candidates.isEmpty())
        RuleFixtures.fails(DiscoveryError.TOO_MANY_CANDIDATES) {
            RuleFixtures.executor().execute(RuleFixtures.response(rows(101)), RuleFixtures.operation())
        }
        RuleFixtures.fails(DiscoveryError.TOO_MANY_CANDIDATES) {
            RuleFixtures.executor(limits = DiscoveryLimits(maxCandidatesPerResponse = 1)).execute(RuleFixtures.response(rows(2)), RuleFixtures.operation())
        }
    }

    @Test fun cursorUsesUtf8ByteLimitAndRejectsBlankControlOrWrongType() {
        val exact = "é".repeat(1024)
        assertEquals(exact, RuleFixtures.executor().execute(RuleFixtures.response(next = "\"$exact\""), RuleFixtures.operation()).nextCursor)
        for (value in listOf("\"\"", "\"   \"", "\"x\\ny\"", "\"x\\u0000y\"", "1", "true", "[]", "{}", "\"${exact}x\"")) {
            RuleFixtures.fails(DiscoveryError.INVALID_RESULT) {
                RuleFixtures.executor().execute(RuleFixtures.response(next = value), RuleFixtures.operation())
            }
        }
    }

    @Test fun responseDuplicateKeysMalformedUnicodeDepthAndNodeLimitsArePreserved() {
        val invalid = listOf("{\"items\":[],\"items\":[]}".toByteArray(),
            "{\"items\":[{\"url\":\"https://example.org/one\",\"url\":null}]}".toByteArray(),
            "{\"items\":[],\"unknown\":\"\\ud800\"}".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28),
            ("{\"items\":[],\"unknown\":" + "[".repeat(18) + "0" + "]".repeat(18) + "}").toByteArray(),
            ("{\"items\":[],\"unknown\":[" + List(20_001) { "0" }.joinToString(",") + "]}").toByteArray(),
            "{\"items\":[]} trailing".toByteArray())
        for (bytes in invalid) RuleFixtures.fails(DiscoveryError.INVALID_RESULT) {
            RuleFixtures.executor().execute(bytes, RuleFixtures.operation())
        }
    }

    @Test fun cancelledAndExpiredOperationsFailBeforeParse() {
        val cancellation = DiscoveryCancellation().apply { cancel() }
        RuleFixtures.fails(DiscoveryError.CANCELLED) {
            RuleFixtures.executor().execute(byteArrayOf(), RuleFixtures.operation(cancellation = cancellation))
        }
        RuleFixtures.fails(DiscoveryError.DEADLINE_EXCEEDED) {
            RuleFixtures.executor().execute(byteArrayOf(), RuleFixtures.operation(deadline = 1000))
        }
    }

    @Test fun cancellationAndDeadlineDuringParsingDoNotBecomeInvalidResultOrPartialSuccess() {
        var checks = 0
        val cancellation = DiscoveryCancellation()
        val executor = RuleFixtures.executor(clock = EpochClock { if (++checks == 5) cancellation.cancel(); 1000 })
        RuleFixtures.fails(DiscoveryError.CANCELLED) {
            executor.execute(RuleFixtures.response(), RuleFixtures.operation(cancellation = cancellation))
        }
        checks = 0
        RuleFixtures.fails(DiscoveryError.DEADLINE_EXCEEDED) {
            RuleFixtures.executor(clock = EpochClock { if (++checks == 5) 2000 else 1000 })
                .execute(RuleFixtures.response(), RuleFixtures.operation())
        }
    }

    @Test fun finalOutputFenceRechecksCancellationAndDeadline() {
        var totalChecks = 0
        RuleFixtures.executor(clock = EpochClock { totalChecks++; 1000 }).execute(RuleFixtures.response(), RuleFixtures.operation())
        assertTrue(totalChecks > 5)
        var checks = 0
        RuleFixtures.fails(DiscoveryError.DEADLINE_EXCEEDED) {
            RuleFixtures.executor(clock = EpochClock { if (++checks == totalChecks) 2000 else 1000 })
                .execute(RuleFixtures.response(), RuleFixtures.operation())
        }
        checks = 0
        val cancellation = DiscoveryCancellation()
        RuleFixtures.fails(DiscoveryError.CANCELLED) {
            RuleFixtures.executor(clock = EpochClock { if (++checks == totalChecks) cancellation.cancel(); 1000 })
                .execute(RuleFixtures.response(), RuleFixtures.operation(cancellation = cancellation))
        }
    }

    @Test fun executorHonorsThreadInterruptionWithoutClearingIt() {
        val executor = RuleFixtures.executor()
        try {
            Thread.currentThread().interrupt()
            RuleFixtures.fails(DiscoveryError.CANCELLED) { executor.execute(RuleFixtures.response(), RuleFixtures.operation()) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun executionIsDeterministicDetachedAndReusableConcurrently() {
        val executor = RuleFixtures.executor()
        val input = RuleFixtures.response()
        val original = executor.execute(input, RuleFixtures.operation())
        input.fill(0)
        assertEquals("One", original.candidates.single().sourceReported!!.title)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..8).map { workers.submit(Callable { executor.execute(RuleFixtures.response(), RuleFixtures.operation()) }) }
            for (future in futures) assertEquals(original, future.get(5, TimeUnit.SECONDS))
        } finally { workers.shutdownNow() }
    }
}
