package com.easysavevideo.app.save

import com.easysavevideo.core.model.BrowserContextRef
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AuthenticationOriginPolicyTest {
    @Test fun interactionFreshnessRequiresExactNonnegativeRevisionWithoutWraparound() {
        for (revision in listOf(0L, 1L, 42L, Long.MAX_VALUE)) {
            assertTrue(authenticationOriginInteractionCurrent(revision, revision))
        }
        for ((expected, current) in listOf(0L to 1L, 1L to 0L, 42L to 43L,
            -1L to -1L, Long.MIN_VALUE to Long.MIN_VALUE, -1L to 0L, 0L to -1L,
            Long.MAX_VALUE to Long.MIN_VALUE, Long.MIN_VALUE to Long.MAX_VALUE)) {
            assertFalse(authenticationOriginInteractionCurrent(expected, current))
        }
    }

    private class EqualIdentity {
        override fun equals(other: Any?) = other is EqualIdentity
        override fun hashCode() = 1
    }

    private class Fixture(capacity: Int = 100, ttlMillis: Long = 15 * 60_000L) {
        var now = 10_000L
        val entry = EqualIdentity()
        val view = EqualIdentity()
        val context = BrowserContextRef("original-tab", "original-session")
        val page = "https://media.example/watch?id=private#position"
        val source = "https://media.example/video.mp4?signature=private"
        val documents = AuthenticationOriginDocuments({ now }, capacity, ttlMillis)

        fun document(
            context: BrowserContextRef = this.context,
            epoch: Long = 7,
            documentRevision: Long = 9,
            entry: Any = this.entry,
            view: Any = this.view,
            page: String = this.page,
            ready: Boolean = true,
            incognito: Boolean = false,
        ) = AuthenticationOriginDocument(context, epoch, documentRevision, entry, view, page, ready, incognito)

        fun token(source: String = this.source, document: AuthenticationOriginDocument = document()) =
            requireNotNull(documents.capture(document, source))
    }

    private fun clearWeakIdentity(document: AuthenticationOriginDocument, fieldName: String) {
        val field = document.javaClass.getDeclaredField(fieldName)
        assertTrue(WeakReference::class.java.isAssignableFrom(field.type))
        field.isAccessible = true
        (field.get(document) as WeakReference<*>).clear()
    }

    @Test fun sameExactDocumentCanBeResolvedRepeatedlyAndFromAnotherSelectedTab() {
        val f = Fixture()
        val token = f.token()
        // Selection/interaction state intentionally is not part of this identity-only policy.
        repeat(5) {
            assertEquals(f.context, f.documents.context(token))
            assertTrue(f.documents.resolve(token, f.document(context = f.context.copy())))
        }
    }

    @Test fun captureRequiresReadyNormalHttpsDocumentWithValidRevisions() {
        val f = Fixture()
        val invalid = listOf(
            f.document(ready = false), f.document(incognito = true), f.document(epoch = 0),
            f.document(epoch = -1), f.document(documentRevision = -1),
            f.document(page = "http://media.example/watch"),
            f.document(page = "https://name:secret@media.example/watch"),
            f.document(page = "file:///media.example/watch"),
        )
        invalid.forEach { assertNull(f.documents.capture(it, f.source)) }
        assertNotNull(f.documents.capture(f.document(documentRevision = 0), f.source))
    }

    @Test fun exactContextEpochDocumentRevisionEntryViewAndAddressAllMatter() {
        val f = Fixture()
        val mismatches = listOf(
            f.document(context = BrowserContextRef("other-tab", f.context.sessionId)),
            f.document(context = BrowserContextRef(f.context.tabId, "restored-session")),
            f.document(epoch = 8), f.document(documentRevision = 10),
            f.document(entry = EqualIdentity()), f.document(view = EqualIdentity()),
            f.document(page = "https://media.example/another-page"),
            f.document(page = "https://media.example/watch?id=private#new-position"),
            f.document(page = "https://media.example:443/watch?id=private#position"),
            f.document(ready = false), f.document(incognito = true),
        )
        mismatches.forEach { changed ->
            val token = f.token()
            assertFalse(f.documents.resolve(token, changed))
            assertNull(f.documents.context(token))
            assertFalse(f.documents.resolve(token, f.document()))
            assertFalse(token.matchesSource(f.source))
        }
    }

    @Test fun equalLookingEntryOrViewDoesNotSatisfyReferentialIdentity() {
        val f = Fixture()
        assertEquals(f.entry, EqualIdentity())
        assertFalse(f.documents.resolve(f.token(), f.document(entry = EqualIdentity())))
        assertFalse(f.documents.resolve(f.token(), f.document(view = EqualIdentity())))
    }

    @Test fun sameAddressAbaReloadHistoryOrRendererReplacementCannotReviveToken() {
        val f = Fixture()
        for (revision in listOf(10L, 11L, 999L)) {
            val token = f.token()
            assertFalse(f.documents.resolve(token, f.document(documentRevision = revision)))
            assertFalse(f.documents.resolve(token, f.document()))
        }
        val rendererToken = f.token()
        assertFalse(f.documents.resolve(rendererToken, f.document(view = Any())))
        assertFalse(f.documents.resolve(rendererToken, f.document()))
    }

    @Test fun sameOriginInDifferentTabOrDifferentRegistryIsNotAReconstructionRoute() {
        val f = Fixture()
        val token = f.token()
        val restored = AuthenticationOriginDocuments({ f.now })
        assertNull(restored.context(token))
        assertFalse(restored.resolve(token, f.document()))
        restored.discard(token)
        assertTrue(f.documents.resolve(token, f.document()))
        assertFalse(f.documents.resolve(token,
            f.document(context = BrowserContextRef("same-url-tab", "same-url-session"))))
    }

    @Test fun httpsHostCaseAndDefaultPortAreCanonicalButOtherPortsAreSeparate() {
        val f = Fixture()
        val token = f.token(document = f.document(page = "HTTPS://MEDIA.EXAMPLE:443/page"))
        listOf("https://media.example/video", "https://MEDIA.EXAMPLE:443/other?x=1#f").forEach {
            assertTrue(token.matchesSource(it))
        }
        listOf("http://media.example/video", "https://media.example:444/video", "https://cdn.media.example/video",
            "https://other.example/video", "https://media.example.evil.test/video").forEach {
            assertFalse(token.matchesSource(it))
        }
        val custom = f.token("https://media.example:8443/video", f.document(page = "https://media.example:8443/page"))
        assertTrue(custom.matchesSource("https://MEDIA.EXAMPLE:8443/video"))
        assertFalse(custom.matchesSource(f.source))
    }

    @Test fun ipv6SameOriginUsesTheSameEffectivePortRule() {
        val f = Fixture()
        val token = f.token("https://[2001:db8::1]/video", f.document(page = "https://[2001:DB8::1]:443/page"))
        assertTrue(token.matchesSource("https://[2001:db8::1]:443/other"))
        assertFalse(token.matchesSource("https://[2001:db8::2]/video"))
        assertFalse(token.matchesSource("https://[2001:db8::1]:444/video"))
    }

    @Test fun malformedCredentialBearingNonHttpsAndCrossOriginSourcesCannotCapture() {
        val f = Fixture()
        val invalid = listOf("", "not a URL", "/video", "//media.example/video", "http://media.example/video",
            "https://user@media.example/video", "https://user:secret@media.example/video",
            "https://media.example@evil.test/video", "https://cdn.example/video", "https://media.example:444/video",
            "https://media.example:0/video", "https://media.example:65536/video", "https://media.example:/video",
            "https://media.example:-1/video", "https:///video", "https:media.example/video", "https://media.example/%xx",
            "https://media.example/video\n", "https://media.example\\@evil.test/video", "https://media.example/" + "x".repeat(8192))
        val token = f.token()
        invalid.forEach {
            assertFalse(token.matchesSource(it))
            assertNull(f.documents.capture(f.document(), it))
        }
        assertTrue(token.matchesSource(f.source))
    }

    @Test fun boundedDocumentRegistryFailsClosedWithoutEvictingEstablishedTokens() {
        val f = Fixture(capacity = 2)
        val first = f.token()
        val second = f.token()
        assertNull(f.documents.capture(f.document(), f.source))
        assertTrue(f.documents.resolve(first, f.document()))
        assertTrue(f.documents.resolve(second, f.document()))
        f.documents.discard(first)
        assertNotNull(f.documents.capture(f.document(), f.source))
        assertNull(f.documents.context(first))
        assertFalse(first.matchesSource(f.source))
    }

    @Test fun defaultCapacityAndMaximumLifetimeAreEnforced() {
        val f = Fixture()
        val tokens = List(100) { f.token() }
        assertNull(f.documents.capture(f.document(), f.source))
        f.now += 15 * 60_000L - 1
        tokens.forEach { assertTrue(it.matchesSource(f.source)) }
        f.now++
        tokens.forEach { assertFalse(it.matchesSource(f.source)) }
        assertNotNull(f.documents.capture(f.document(), f.source))
    }

    @Test fun expirationIsExclusiveAndPermanentAndPruningFreesCapacity() {
        val f = Fixture(capacity = 1, ttlMillis = 100)
        val token = f.token()
        f.now += 99
        assertNotNull(f.documents.context(token))
        f.now++
        assertNull(f.documents.context(token))
        assertFalse(f.documents.resolve(token, f.document()))
        f.now -= 100
        assertFalse(token.matchesSource(f.source))
        assertNotNull(f.documents.capture(f.document(), f.source))
    }

    @Test fun clockRollbackAndFailureRetireTokensRatherThanRevivingThem() {
        val f = Fixture()
        val token = f.token()
        f.now--
        assertFalse(token.matchesSource(f.source))
        f.now++
        assertFalse(token.matchesSource(f.source))
        var fail = false
        val documents = AuthenticationOriginDocuments({ if (fail) error("clock") else 1L })
        val issued = requireNotNull(documents.capture(f.document(), f.source))
        fail = true
        assertFalse(issued.matchesSource(f.source))
        assertNull(documents.context(issued))
        assertNull(documents.capture(f.document(), f.source))
        fail = false
        assertFalse(issued.matchesSource(f.source))
    }

    @Test fun monotonicLongWrapDoesNotExtendLifetime() {
        val f = Fixture(ttlMillis = 100)
        f.now = Long.MAX_VALUE - 50
        val token = f.token()
        f.now += 99
        assertTrue(token.matchesSource(f.source))
        f.now++
        assertFalse(token.matchesSource(f.source))
    }

    @Test fun invalidateUsesExactEntryIdentityAndClearRevokesHeldTokens() {
        val f = Fixture()
        val first = f.token()
        val second = f.token()
        f.documents.invalidate(EqualIdentity())
        assertTrue(first.matchesSource(f.source))
        f.documents.invalidate(f.entry)
        for (token in listOf(first, second)) {
            assertNull(f.documents.context(token))
            assertFalse(token.matchesSource(f.source))
        }
        val next = f.token()
        f.documents.clear()
        f.documents.clear()
        assertNull(f.documents.context(next))
        assertFalse(f.documents.resolve(next, f.document()))
        assertFalse(next.matchesSource(f.source))
    }

    @Test fun collectedEntryOrViewFailsClosedWithoutStrongRetentionOrGcTiming() {
        for (field in listOf("entry", "view")) {
            val f = Fixture(capacity = 1)
            val document = f.document()
            val token = f.token(document = document)
            clearWeakIdentity(document, field)
            assertNull(f.documents.context(token))
            assertFalse(token.matchesSource(f.source))
            assertFalse(f.documents.resolve(token, f.document()))
            assertNotNull(f.documents.capture(f.document(), f.source))
            assertFalse(AuthenticationOriginDocument::class.java.declaredFields.any { it.type == Any::class.java })
        }
    }

    @Test fun alreadyCollectedSnapshotCannotIssueTokenAndCollectedCurrentSnapshotCannotResolve() {
        for (field in listOf("entry", "view")) {
            val f = Fixture()
            val stale = f.document()
            clearWeakIdentity(stale, field)
            assertNull(f.documents.capture(stale, f.source))
            val token = f.token()
            assertFalse(f.documents.resolve(token, stale))
            assertFalse(token.matchesSource(f.source))
        }
    }

    @Test fun newlyAcceptedDirectAndSameOriginHlsFinalSourcesBind() {
        for (source in listOf("https://media.example/direct.mp4", "https://media.example:443/variant.m3u8")) {
            val f = Fixture()
            val tasks = AuthenticationTaskOrigins()
            val token = f.token()
            assertTrue(tasks.accepted("new-task", tasks.capture(token), existing = false, finalSource = source))
            assertSame(token, tasks.token("new-task"))
            assertTrue(f.documents.resolve(token, f.document()))
        }
    }

    @Test fun finalCrossOriginHlsVariantRetiresPreparationToken() {
        val f = Fixture()
        val tasks = AuthenticationTaskOrigins()
        val token = f.token()
        assertFalse(tasks.accepted("task", tasks.capture(token), false, "https://cdn.example/variant.m3u8"))
        assertNull(tasks.token("task"))
        assertNull(f.documents.context(token))
        assertFalse(token.matchesSource(f.source))
    }

    @Test fun existingAcceptanceAndDuplicateTaskNeverReplaceEstablishedAssociation() {
        val f = Fixture()
        val tasks = AuthenticationTaskOrigins()
        val established = f.token()
        assertTrue(tasks.accepted("task", tasks.capture(established), false, f.source))
        for (existing in listOf(true, false)) {
            val new = f.token()
            assertFalse(tasks.accepted("task", tasks.capture(new), existing, f.source))
            assertSame(established, tasks.token("task"))
            assertNull(f.documents.context(new))
            assertTrue(established.matchesSource(f.source))
        }
        assertFalse(tasks.accepted("task", tasks.capture(established), true, f.source))
        assertSame(established, tasks.token("task"))
        assertTrue(f.documents.resolve(established, f.document()))
    }

    @Test fun existingAcceptanceWithoutAnEstablishedLinkCannotManufactureOne() {
        val f = Fixture()
        val tasks = AuthenticationTaskOrigins()
        val token = f.token()
        assertFalse(tasks.accepted("existing-task", tasks.capture(token), true, f.source))
        assertNull(tasks.token("existing-task"))
        assertFalse(token.matchesSource(f.source))
    }

    @Test fun capturesAreOneShotAndCannotBindOneTokenToMultipleTasks() {
        val f = Fixture()
        val tasks = AuthenticationTaskOrigins()
        val token = f.token()
        val capture = tasks.capture(token)
        assertTrue(tasks.accepted("first", capture, false, f.source))
        assertFalse(tasks.accepted("second", capture, false, f.source))
        assertFalse(tasks.accepted("second", tasks.capture(token), false, f.source))
        assertSame(token, tasks.token("first"))
        assertNull(tasks.token("second"))
        assertTrue(token.matchesSource(f.source))
    }

    @Test fun capturesCannotBeUsedOrDiscardedByAnotherTaskRegistry() {
        val f = Fixture()
        val issuer = AuthenticationTaskOrigins()
        val other = AuthenticationTaskOrigins()
        val token = f.token()
        val capture = issuer.capture(token)
        assertFalse(other.accepted("task", capture, false, f.source))
        other.discard(capture)
        assertTrue(token.matchesSource(f.source))
        assertTrue(issuer.accepted("task", capture, false, f.source))
        assertNull(other.token("task"))
    }

    @Test fun rejectedBlankTaskAndDiscardedPreparationsCannotBeReused() {
        val f = Fixture()
        val tasks = AuthenticationTaskOrigins()
        for (taskId in listOf("", " ", "\n")) {
            val token = f.token()
            val capture = tasks.capture(token)
            assertFalse(tasks.accepted(taskId, capture, false, f.source))
            assertFalse(tasks.accepted("retry", capture, false, f.source))
            assertNull(f.documents.context(token))
        }
        val preparation = f.token()
        val capture = tasks.capture(preparation)
        tasks.discard(capture)
        tasks.discard(capture)
        tasks.discard(null)
        assertFalse(tasks.accepted("queue-rejected-or-cancelled", capture, false, f.source))
        assertNull(f.documents.context(preparation))
    }

    @Test fun discardingAcceptedOrDuplicateCapturePreservesTheEstablishedLink() {
        val f = Fixture()
        val tasks = AuthenticationTaskOrigins()
        val token = f.token()
        val capture = tasks.capture(token)
        assertTrue(tasks.accepted("task", capture, false, f.source))
        tasks.discard(capture)
        tasks.discard(tasks.capture(token))
        assertSame(token, tasks.token("task"))
        assertTrue(f.documents.resolve(token, f.document()))
    }

    @Test fun backgroundClearRejectsLateAcceptanceAndAllowsOnlyNewGeneration() {
        val f = Fixture()
        val tasks = AuthenticationTaskOrigins()
        val waiting = f.token()
        assertTrue(tasks.accepted("waiting", tasks.capture(waiting), false, f.source))
        val inFlight = f.token()
        val lateCapture = tasks.capture(inFlight)
        tasks.clear()
        assertNull(tasks.token("waiting"))
        assertFalse(waiting.matchesSource(f.source))
        assertFalse(tasks.accepted("late", lateCapture, false, f.source))
        assertFalse(inFlight.matchesSource(f.source))
        val fresh = f.token()
        assertTrue(tasks.accepted("fresh", tasks.capture(fresh), false, f.source))
        assertSame(fresh, tasks.token("fresh"))
        assertNull(tasks.token("late"))
    }

    @Test fun hostClearOrInvalidationAlsoBlocksLateWorkerAcceptance() {
        for (clear in listOf(true, false)) {
            val f = Fixture()
            val tasks = AuthenticationTaskOrigins()
            val token = f.token()
            val capture = tasks.capture(token)
            if (clear) f.documents.clear() else f.documents.invalidate(f.entry)
            assertFalse(tasks.accepted("late", capture, false, f.source))
            assertNull(tasks.token("late"))
        }
    }

    @Test fun issuerClockControlsTaskExpiryWithoutAnyOtherClockDomain() {
        val f = Fixture(ttlMillis = 50)
        f.now = 123L
        val tasks = AuthenticationTaskOrigins()
        val token = f.token()
        val pending = f.token()
        val capture = tasks.capture(pending)
        assertTrue(tasks.accepted("task", tasks.capture(token), false, f.source))
        f.now += 49
        assertSame(token, tasks.token("task"))
        f.now++
        assertNull(tasks.token("task"))
        assertFalse(tasks.accepted("late", capture, false, f.source))
        assertNull(f.documents.context(token))
        assertNull(f.documents.context(pending))
    }

    @Test fun taskCapacityRejectsNewLinksWithoutEvictionAndCancellationFreesCapacity() {
        val f = Fixture()
        val tasks = AuthenticationTaskOrigins(capacity = 1)
        val first = f.token()
        val refused = f.token()
        assertTrue(tasks.accepted("first", tasks.capture(first), false, f.source))
        assertFalse(tasks.accepted("second", tasks.capture(refused), false, f.source))
        assertSame(first, tasks.token("first"))
        assertFalse(refused.matchesSource(f.source))
        tasks.cancel("unrelated")
        assertTrue(first.matchesSource(f.source))
        tasks.cancel("first")
        assertNull(tasks.token("first"))
        assertNull(f.documents.context(first))
        val next = f.token()
        assertTrue(tasks.accepted("next", tasks.capture(next), false, f.source))
    }

    @Test fun authenticationWaitSurvivesSnapshotButTerminalAndAbsentTasksRetire() {
        val f = Fixture()
        val tasks = AuthenticationTaskOrigins()
        val waiting = f.token()
        val complete = f.token()
        val deleted = f.token()
        listOf("waiting" to waiting, "complete" to complete, "deleted" to deleted).forEach { (id, token) ->
            assertTrue(tasks.accepted(id, tasks.capture(token), false, f.source))
        }
        repeat(3) { tasks.retainNonterminal(setOf("waiting", "some-unassociated-task")) }
        assertSame(waiting, tasks.token("waiting"))
        assertTrue(waiting.matchesSource(f.source))
        for (token in listOf(complete, deleted)) {
            assertFalse(token.matchesSource(f.source))
            assertNull(f.documents.context(token))
        }
        tasks.retainNonterminal(emptySet())
        assertNull(tasks.token("waiting"))
        assertFalse(waiting.matchesSource(f.source))
    }

    @Test fun completeSnapshotPrunesExpiredLinksEvenIfIdRemainsNonterminal() {
        val f = Fixture(ttlMillis = 10)
        val tasks = AuthenticationTaskOrigins(capacity = 1)
        val token = f.token()
        assertTrue(tasks.accepted("waiting", tasks.capture(token), false, f.source))
        f.now += 10
        tasks.retainNonterminal(setOf("waiting"))
        assertNull(tasks.token("waiting"))
        val next = f.token()
        assertTrue(tasks.accepted("next", tasks.capture(next), false, f.source))
    }

    @Test fun workerAcceptanceAfterBackgroundBarrierCannotBind() {
        val f = Fixture()
        val tasks = AuthenticationTaskOrigins()
        val token = f.token()
        val capture = tasks.capture(token)
        val waiting = CountDownLatch(1)
        val afterBackground = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                waiting.countDown()
                check(afterBackground.await(5, TimeUnit.SECONDS))
                tasks.accepted("late-worker", capture, false, f.source)
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            tasks.clear()
            afterBackground.countDown()
            assertFalse(result.get(5, TimeUnit.SECONDS))
            assertNull(tasks.token("late-worker"))
            assertFalse(token.matchesSource(f.source))
        } finally { afterBackground.countDown(); executor.shutdownNow() }
    }

    @Test fun concurrentSameTaskAcceptanceHasOnlyOneWinner() {
        val f = Fixture()
        val tasks = AuthenticationTaskOrigins()
        val first = f.token()
        val second = f.token()
        val captures = listOf(tasks.capture(first), tasks.capture(second))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = captures.map { capture ->
                executor.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    tasks.accepted("same-task", capture, false, f.source)
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            val winner = requireNotNull(tasks.token("same-task"))
            val loser = if (winner === first) second else first
            assertTrue(winner.matchesSource(f.source))
            assertFalse(loser.matchesSource(f.source))
        } finally { start.countDown(); executor.shutdownNow() }
    }

    @Test fun allDebugTextIsRedactedAndTokenDoesNotRetainDocumentOrRenderer() {
        val f = Fixture()
        val document = f.document()
        val token = f.token(document = document)
        val tasks = AuthenticationTaskOrigins()
        val capture = tasks.capture(token)
        val values = listOf(document, token, capture, f.documents, tasks)
        values.forEach {
            assertTrue(it.toString().endsWith("([redacted])"))
            listOf(f.page, f.source, "media.example", "original-tab", "original-session", "signature").forEach { secret ->
                assertFalse(it.toString().contains(secret))
            }
        }
        assertFalse(AuthenticationOriginToken::class.java.declaredFields.any {
            it.type == AuthenticationOriginDocument::class.java || it.type == WeakReference::class.java ||
                it.type == String::class.java || it.type == BrowserContextRef::class.java
        })
        assertFalse(AuthenticationOriginDocument::class.java.methods.any {
            it.returnType == String::class.java && it.name != "toString"
        })
    }

    @Test fun invalidBoundsCannotCreateUnboundedRegistriesOrExtendedLifetimes() {
        val invalid = listOf<() -> Any>(
            { AuthenticationOriginDocuments(capacity = 0) }, { AuthenticationOriginDocuments(capacity = 101) },
            { AuthenticationOriginDocuments(ttlMillis = 0) }, { AuthenticationOriginDocuments(ttlMillis = 900_001) },
            { AuthenticationTaskOrigins(capacity = 0) }, { AuthenticationTaskOrigins(capacity = 101) },
        )
        invalid.forEach { create ->
            try { create(); fail("Invalid bound was accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
