package com.easysavevideo.app.save.storage

import com.easysavevideo.app.media.transform.*
import com.easysavevideo.app.save.conversion.OwnedWebmInputBinding
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual registered owner/async descriptor/core entry; fake OS only, never native codec acceptance. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class OwnedDirectTranscodeAttemptTest {
    @Test fun genuineWitnessCreatesOneReadOnlyAnchorAfterAcknowledgedWriting() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val f = h.f
        assertEquals(NormalizationIoReservations.Stage.TRANSCODE, h.token.stage)
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
        assertEquals(1, f.io.handles.count { it.active })
        val anchor = f.io.handles.single { it.active }
        assertEquals(f.primary, anchor.path); assertEquals(NormalizationDescriptorIo.Open.READ, anchor.mode)
        assertEquals(0, f.io.bytes(f.outputFile(a.key)).size)
        assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
        val binding = a.bindWebmInputOnce(); val input = a.borrowedWebmInput(binding)
        val bytes = ByteArray(32); assertEquals(32, input.readAt(11, bytes, 0, 32))
        assertArrayEquals(f.rawBytes.copyOfRange(11, 43), bytes)
        a.close(); assertFalse(f.attempts.hasAnyLeases())
        assertEquals(1, f.io.calls.count { it.operation == "close" && it.descriptor === anchor })
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
        assertNull(f.record!!.directConversion); assertNull(f.record!!.directNormalization)
    }
    @Test fun noWitnessForeignScopeWrongContextAndClosedScopeCannotIssue() {
        OwnedTranscodeTestFixture().also { it.probe = false }.run { h, _ ->
            val opens = h.f.io.calls.count { it.operation == "open" }
            assertThrows(NormalizationRecovery::class.java) { h.prepare() }
            assertEquals(opens, h.f.io.calls.count { it.operation == "open" }); assertNull(h.f.ledger.row)
        }
        OwnedTranscodeTestFixture().run { h, scope ->
            val opens = h.f.io.calls.count { it.operation == "open" }
            h.f.transcodeScopeVerifier = { throw NormalizationRecovery() }
            assertThrows(NormalizationRecovery::class.java) { h.prepare() }
            h.f.transcodeScopeVerifier = { it.requireIssuer(h.bridge) }
            assertThrows(NormalizationRecovery::class.java) {
                h.f.attempts.prepareTranscode(h.f.owner, h.f.context.copy(), h.f.limits, h.token, scope)
            }
            scope.close(); assertThrows(Exception::class.java) { h.prepare() }
            assertEquals(opens, h.f.io.calls.count { it.operation == "open" })
        }
    }
    @Test fun sameBridgeDifferentScopeCannotTakeAnotherScopesProbedReservation() = OwnedTranscodeTestFixture().run { h, scope ->
        val other = h.bridge.capture(scope.execution, scope.permit, scope.context)
        val opens = h.f.io.calls.count { it.operation == "open" }
        try {
            assertThrows(NormalizationRecovery::class.java) {
                h.f.attempts.prepareTranscode(h.f.owner, h.f.context, h.f.limits, h.token, other)
            }
            assertEquals(opens, h.f.io.calls.count { it.operation == "open" })
            h.prepare().close() // Foreign attempt did not consume the genuine raw witness.
        } finally { other.close() }
    }
    @Test fun unregisteredLookalikeCloseCannotRevokeRealAttemptToken() = OwnedTranscodeTestFixture().run { h, scope ->
        val a = h.prepare(); val b = a.bindWebmInputOnce()
        val raw = NormalizationBoundFile(h.f.files.capture(a.key, NormalizationRole.PRIMARY), h.f.raw)
        val empty = h.f.files.capture(a.key, NormalizationRole.OUTPUT, empty = true)
        val foreign = OwnedDirectTranscodeAttempt.createUnregistered(h.f.attempts, h.f.files,
            h.f.ledger.row!!, h.f.context, raw, empty, a.outputMaximum, h.token, h.f.record!!, scope)
        assertThrows(NormalizationRecovery::class.java) { foreign.close() }
        h.token.requireLive(); assertEquals(1, a.borrowedWebmInput(b).readAt(0, ByteArray(1), 0, 1))
        a.close(); a.close(); assertFalse(h.f.attempts.hasAnyLeases())
    }
    @Test fun unregisteredAsyncDescriptorCannotCloseRealAnchorOrRetireItsObligation() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val anchor = h.f.io.handles.single { it.active }
        val identity = h.f.files.capture(a.key, NormalizationRole.PRIMARY)
        val forged = h.f.files.AsyncRawDescriptor(a.key, anchor, identity,
            NormalizationIoReservations.Obligation.issue(h.token), h.token, {}, {})
        val closes = h.f.io.calls.count { it.operation == "close" && it.descriptor === anchor }
        assertThrows(NormalizationRecovery::class.java) { forged.requestClose() }
        assertEquals(closes, h.f.io.calls.count { it.operation == "close" && it.descriptor === anchor })
        assertTrue(anchor.active); assertFalse(h.f.attempts.releaseUnconfirmed())
        assertTrue(h.f.files.hasLeases(a.key)); assertEquals(1, a.borrowedWebmInput(b).readAt(0, ByteArray(1), 0, 1))
        a.close(); a.close()
        assertFalse(h.f.attempts.hasAnyLeases()); assertEquals(closes + 1, h.f.io.calls.count { it.operation == "close" && it.descriptor === anchor })
    }
    @Test fun lostWritingAcknowledgementAndRecoveredWritingCannotIssueAnchor() = OwnedTranscodeTestFixture().run { h, _ ->
        val f = h.f
        f.ledger.afterWrite = { if (it.lifecycle == NormalizationLifecycle.WRITING) throw IOException("HOST_LOST_ACK") }
        assertThrows(Exception::class.java) { h.prepare() }
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
        assertFalse(f.io.handles.any { it.active })
        f.ledger.afterWrite = {}
        assertThrows(Exception::class.java) { h.prepare() }
        assertFalse(f.attempts.hasAnyLeases())
    }
    @Test fun secondBindForeignBindingAndRetiredAttemptFailWithoutOpening() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val exact = a.bindWebmInputOnce()
        val forged = OwnedWebmInputBinding.createUnregistered(a)
        assertThrows(NormalizationRecovery::class.java) { a.requireRegisteredBinding(forged) }
        assertThrows(NormalizationRecovery::class.java) { a.bindWebmInputOnce() }
        a.requireRegisteredBinding(exact)
        val opens = h.f.io.calls.count { it.operation == "open" }
        a.close(); assertThrows(Exception::class.java) { a.requireRegisteredBinding(exact) }
        assertEquals(opens, h.f.io.calls.count { it.operation == "open" })
    }
    @Test fun constructorAndQueuedSessionCannotOpenOrRetainNativeSource() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val session = h.session()
        a.bindSession(b, session); val source = a.ownedSource(b)
        assertThrows(TransformerFailure::class.java) { source.openReader() }
        assertThrows(TransformerFailure::class.java) { source.retainNativeReader() }
        assertThrows(Exception::class.java) { a.bindSession(b, h.session()) }
        assertThrows(Exception::class.java) { a.requireRegisteredSession(b, h.session()) }
        a.close(); assertFalse(h.f.attempts.hasAnyLeases())
    }
    @Test fun independentLogicalReadersAndSuffixUseOneOriginalDescriptor() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val session = h.session()
        a.bindSession(b, session); val source = a.ownedSource(b)
        val anchor = h.f.io.handles.single { it.active }
        val result = h.entered(session) {
            session.retain(source)
            val r1 = session.openInput(source); val r2 = session.openInput(source)
            h.f.io.calls.clear()
            val one = start { ByteArray(25).also { r1.readAt(31, it, 0, it.size) } }
            val two = start { ByteArray(19).also { r2.readAt(40, it, 0, it.size) } }
            assertArrayEquals(h.f.rawBytes.copyOfRange(31, 56), one.get(5, TimeUnit.SECONDS))
            assertArrayEquals(h.f.rawBytes.copyOfRange(40, 59), two.get(5, TimeUnit.SECONDS))
            a.borrowedWebmInput(b).readAt(2, ByteArray(12), 0, 12)
            assertTrue(h.f.io.calls.filter { it.operation == "pread" }.all { it.descriptor === anchor })
            session.closeInput(r1); session.closeInput(r2)
            assertTrue(anchor.active) // Last logical close is not anchor close.
        }
        assertTrue(result is TransformerOutcome.Completed)
        assertTrue(anchor.active) // Native retention release is not implicit anchor close either.
        a.close(); assertFalse(anchor.active); assertFalse(h.f.attempts.hasAnyLeases())
    }
    @Test fun fourthReaderAcceptedFifthRejectedAndCumulativeOpensNeverReset() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val s = h.session()
        a.bindSession(b, s); val source = a.ownedSource(b)
        val result = h.entered(s) {
            s.retain(source)
            // Use production source directly to exercise storage's independent bounds.
            val first = List(4) { source.openReader() }
            assertThrows(SaveFailure::class.java) { source.openReader() }
            first.forEach { it.close(); assertTrue(it.releaseConfirmed) }
            first.first().close() // A stale close cannot affect a replacement reader.
            val second = List(4) { source.openReader() }
            second.forEach { it.verifyIdentity(); it.close() }
            assertThrows(SaveFailure::class.java) { source.openReader() }
        }
        assertTrue(result is TransformerOutcome.Completed); a.close()
    }
    @Test fun fourClosingBlockedReadersKeepTheirSlotsUntilActualDrain() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val s = h.session()
        a.bindSession(b, s); val source = a.ownedSource(b)
        val blocked = CountDownLatch(4); val release = CountDownLatch(1)
        val result = h.entered(s) {
            s.retain(source); val readers = List(4) { source.openReader() }
            h.f.io.before = { if (it.operation == "pread") { blocked.countDown(); await(release) } }
            val reads = readers.map { r -> start { runCatching { r.readAt(0, ByteArray(1), 0, 1) } } }
            await(blocked); readers.forEach { it.close(); assertFalse(it.releaseConfirmed) }
            assertThrows(SaveFailure::class.java) { source.openReader() }
            assertTrue(h.f.io.handles.single { it.active }.active)
            release.countDown(); reads.forEach { assertTrue(it.get(5, TimeUnit.SECONDS).isFailure) }
            readers.forEach { assertTrue(it.releaseConfirmed) }
            val replacement = List(4) { source.openReader() }
            replacement.forEach { it.close() }
        }
        assertTrue(result is TransformerOutcome.Completed); a.close(); assertFalse(h.f.attempts.hasAnyLeases())
    }
    @Test fun sameCursorConcurrentReadIsRejectedAndCloseDoesNotPermitLateSuccess() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val s = h.session()
        a.bindSession(b, s); val source = a.ownedSource(b)
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        val result = h.entered(s) {
            s.retain(source); val r = source.openReader()
            h.f.io.before = { if (it.operation == "pread") { blocked.countDown(); await(release) } }
            val read = start { runCatching { r.readAt(0, ByteArray(1), 0, 1) } }
            await(blocked)
            assertThrows(Exception::class.java) { r.readAt(0, ByteArray(1), 0, 1) }
            r.close(); assertFalse(r.releaseConfirmed)
            release.countDown(); assertTrue(read.get(5, TimeUnit.SECONDS).isFailure); assertTrue(r.releaseConfirmed)
        }
        assertTrue(result is TransformerOutcome.Completed); a.close()
    }
    @Test fun closingOnlyOneCursorRejectsItsLateResultButLeavesOtherCursorAndSlotUsable() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val session = h.session()
        a.bindSession(b, session); val source = a.ownedSource(b)
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        val result = h.entered(session) {
            session.retain(source); val first = source.openReader(); val second = source.openReader()
            h.f.io.before = { if (it.operation == "pread" && it.position == 17L) { blocked.countDown(); await(release) } }
            val late = start { runCatching { first.readAt(17, ByteArray(1), 0, 1) } }
            await(blocked); first.close(); assertFalse(first.releaseConfirmed)
            assertEquals(1, second.readAt(23, ByteArray(1), 0, 1))
            release.countDown(); assertTrue(late.get(5, TimeUnit.SECONDS).isFailure); assertTrue(first.releaseConfirmed)
            val replacements = List(3) { source.openReader() }
            assertThrows(SaveFailure::class.java) { source.openReader() }
            first.close(); assertEquals(1, second.readAt(23, ByteArray(1), 0, 1))
            replacements.forEach { it.close() }; second.close()
        }
        assertTrue(result is TransformerOutcome.Completed); a.close()
    }
    @Test fun attemptCloseWaitsForNativeRetentionAndRealSessionReleaseClosesExactlyOnce() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val s = h.session()
        a.bindSession(b, s); val source = a.ownedSource(b); val anchor = h.f.io.handles.single { it.active }
        val result = h.entered(s) {
            s.retain(source); val r = s.openInput(source); s.closeInput(r)
            a.close(); assertTrue(anchor.active); assertTrue(h.f.attempts.hasActiveSession(h.f.owner.artifactId))
            assertThrows(Exception::class.java) { source.openReader() }
        }
        assertTrue(result is TransformerOutcome.Completed)
        assertFalse(anchor.active); assertFalse(h.f.attempts.hasAnyLeases())
        a.close(); assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === anchor })
    }
    @Test fun onlyActualRegisteredSessionCleanupCanReleaseNativeRetention() = OwnedTranscodeTestFixture().run { h, scope ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val session = h.session()
        a.bindSession(b, session); val source = a.ownedSource(b); val anchor = h.f.io.handles.single { it.active }
        val other = h.session()
        val result = h.entered(session) {
            session.retain(source)
            assertThrows(TransformerFailure::class.java) { session.requireSourceRelease(source) }
            assertThrows(TransformerFailure::class.java) { other.requireSourceRelease(source) }
            assertTrue(other.finish()) // Empty unrelated cleanup cannot retire the real source.
            scope.close(); a.close(); assertTrue(anchor.active)
        }
        assertTrue(result is TransformerOutcome.Completed)
        assertFalse(anchor.active); assertFalse(h.f.attempts.hasAnyLeases())
        assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === anchor })
    }
    @Test fun concurrentCloseRequestsAndDrainingCloseStayIdempotent() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val anchor = h.f.io.handles.single { it.active }
        val closing = CountDownLatch(1); val release = CountDownLatch(1)
        h.f.io.before = { if (it.operation == "close" && it.descriptor === anchor) { closing.countDown(); await(release) } }
        val first = start { a.close() }
        await(closing)
        val go = CountDownLatch(1)
        val others = List(32) { start { await(go); a.close() } }
        go.countDown(); release.countDown()
        first.get(5, TimeUnit.SECONDS); others.forEach { it.get(5, TimeUnit.SECONDS) }
        a.close()
        assertFalse(h.f.attempts.hasAnyLeases()); assertFalse(h.f.attempts.releaseUnconfirmed())
        assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === anchor })
    }
    @Test fun scopeExitStillPermitsPositiveReleaseOnlyClose() = OwnedTranscodeTestFixture().run { h, scope ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val input = a.borrowedWebmInput(b)
        scope.close(); assertThrows(Exception::class.java) { input.readAt(0, ByteArray(1), 0, 1) }
        a.close(); assertFalse(h.f.attempts.hasAnyLeases()); assertFalse(h.f.attempts.releaseUnconfirmed())
    }
    @Test fun revokeDuringAcceptedReadRejectsLateBytesAndDrainsAnchor() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val input = a.borrowedWebmInput(b)
        val anchor = h.f.io.handles.single { it.active }; val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        h.f.io.before = { if (it.operation == "pread") { blocked.countDown(); await(release) } }
        val read = start { runCatching { input.readAt(0, ByteArray(1), 0, 1) } }
        await(blocked); a.close(); assertTrue(anchor.active)
        release.countDown(); assertTrue(read.get(5, TimeUnit.SECONDS).isFailure)
        assertFalse(anchor.active); assertFalse(h.f.attempts.hasAnyLeases())
        assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === anchor })
    }
    @Test fun cancellationDuringReadPreservesOriginalStopAndDoesNotNeedSuccessHashToClose() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val input = a.borrowedWebmInput(b)
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        h.f.io.before = { if (it.operation == "pread") { blocked.countDown(); await(release) } }
        val read = start { runCatching { input.readAt(0, ByteArray(1), 0, 1) } }
        await(blocked); h.f.cancellation.cancel(); a.close(); release.countDown()
        assertEquals(SaveError.CANCELLED, (read.get(5, TimeUnit.SECONDS).exceptionOrNull() as SaveFailure).code)
        assertFalse(h.f.attempts.hasAnyLeases()); assertFalse(h.f.attempts.releaseUnconfirmed())
    }
    @Test fun closeThrowOrStillValidReturnPoisonsSameOwnerAndIsNeverRetried() {
        for (mode in MemoryNormalizationDescriptorIo.CloseMode.entries.filter { it != MemoryNormalizationDescriptorIo.CloseMode.NORMAL }) {
            OwnedTranscodeTestFixture().run { h, _ ->
                val a = h.prepare(); val anchor = h.f.io.handles.single { it.active }
                h.f.io.closeMode = { if (it === anchor) mode else MemoryNormalizationDescriptorIo.CloseMode.NORMAL }
                assertThrows(SaveFailure::class.java) { a.close() }
                assertTrue(h.f.attempts.releaseUnconfirmed()); assertTrue(h.f.attempts.hasActiveSession(h.f.owner.artifactId))
                assertThrows(SaveFailure::class.java) { a.close() }
                assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === anchor })
                assertTrue(h.f.files.hasLeases(a.key))
            }
        }
    }
    @Test fun unknownAnchorOpenRetainsPendingObligationAndKnownNoHandleRejectionDoesNot() {
        for (unknown in listOf(true, false)) OwnedTranscodeTestFixture().run { h, _ ->
            h.f.io.before = { call ->
                if (call.operation == "open" && h.token.stage == NormalizationIoReservations.Stage.TRANSCODE) {
                    if (unknown) throw IOException("HOST_UNKNOWN_OPEN")
                    else throw NormalizationOpenRejected(IOException("HOST_REJECTED_OPEN"))
                }
            }
            assertThrows(Exception::class.java) { h.prepare() }
            assertEquals(unknown, h.f.attempts.releaseUnconfirmed())
            assertEquals(unknown, h.f.attempts.hasAnyLeases())
        }
    }
    @Test fun stageCannotAdvanceWithHeldAnchorAndNoTranscodeFinishTransitionExists() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare()
        assertThrows(NormalizationRecovery::class.java) {
            h.f.attempts.reservations.advance(h.token, NormalizationIoReservations.Stage.TRANSCODE, NormalizationIoReservations.Stage.FINISH)
        }
        a.close(); assertFalse(h.token.registered)
    }
    @Test fun ownerMemoryChecksAndPerReadIdentityDoNotReadDurableLedger() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val input = a.borrowedWebmInput(b)
        h.f.ledger.unreadable = true; h.f.authorityUnreadable = true
        assertEquals(1, input.readAt(0, ByteArray(1), 0, 1))
        a.close(); assertFalse(h.f.attempts.hasAnyLeases())
    }
    @Test fun sameAnchorContentIdentityChangeFailsWithoutOpeningReplacementPath() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val input = a.borrowedWebmInput(b)
        val opens = h.f.io.calls.count { it.operation == "open" }
        h.f.io.mutate(h.f.primary, h.f.rawBytes)
        assertThrows(Exception::class.java) { input.readAt(0, ByteArray(1), 0, 1) }
        assertEquals(opens, h.f.io.calls.count { it.operation == "open" }); a.close()
    }
    @Test fun originalFdCannotHideReplacedPrimaryOrParentPath() {
        for (parent in listOf(false, true)) OwnedTranscodeTestFixture().run { h, _ ->
            val a = h.prepare(); val b = a.bindWebmInputOnce(); val input = a.borrowedWebmInput(b)
            val fd = h.f.io.handles.single { it.active }; val oldNode = fd.node
            if (parent) h.f.io.replaceDirectory(h.f.staging) else h.f.io.file(h.f.primary, h.f.rawBytes)
            assertSame(oldNode, fd.node)
            assertThrows(Exception::class.java) { input.readAt(0, ByteArray(1), 0, 1) }
            a.close(); assertFalse(fd.active)
        }
    }
    @Test fun requestCapMinusOneCapAndOverCapAreSharedAndNotRefunded() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val budget = a.webmBudget(b)
        val cap = a.webmLimits(b).maxIoBytes.toInt()
        budget.request(cap - 1); budget.request(1)
        assertThrows(SaveFailure::class.java) { a.webmBudget(b).request(1) }
        assertThrows(Exception::class.java) { a.bindWebmInputOnce() }; a.close()
    }
    @Test fun racingLastRequestCreditHasOnlyOneWinner() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val budget = a.webmBudget(b)
        budget.request(a.webmLimits(b).maxIoBytes.toInt() - 1)
        val go = CountDownLatch(1); val won = AtomicInteger()
        val contenders = List(2) { start { await(go); runCatching { budget.request(1); won.incrementAndGet() } } }
        go.countDown(); contenders.forEach { it.get(5, TimeUnit.SECONDS) }; assertEquals(1, won.get()); a.close()
    }
    @Test fun physicalCapMinusOneRacePermitsOnlyOneReadToEnterPread() = OwnedTranscodeTestFixture(65_536).run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val input = a.borrowedWebmInput(b)
        val cap = 8L * input.length + 4L * 1024 * 1024
        val buffer = ByteArray(65_536); var remaining = cap - 1
        while (remaining > 0) { val count = minOf(remaining, buffer.size.toLong()).toInt(); input.readAt(0, buffer, 0, count); remaining -= count }
        h.f.io.calls.clear(); val go = CountDownLatch(1)
        val contenders = List(2) { start { await(go); runCatching { input.readAt(0, ByteArray(1), 0, 1) } } }
        go.countDown(); contenders.forEach { it.get(5, TimeUnit.SECONDS) }
        assertTrue(h.f.io.calls.count { it.operation == "pread" } <= 1)
        a.close()
    }
    @Test fun eventCapIsSharedAndLastCreditCannotBeSpentTwice() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); val budget = a.webmBudget(b)
        repeat(1_999_999) { budget.event() }
        val go = CountDownLatch(1); val winners = AtomicInteger()
        val contenders = List(2) { start { await(go); runCatching { a.webmBudget(b).event(); winners.incrementAndGet() } } }
        go.countDown(); contenders.forEach { it.get(5, TimeUnit.SECONDS) }; assertEquals(1, winners.get())
        assertThrows(SaveFailure::class.java) { budget.event() }; a.close()
    }
    @Test fun originalDeadlineIsNeverRenewedByBindingOrAnotherReader() = OwnedTranscodeTestFixture().run { h, _ ->
        val a = h.prepare(); val b = a.bindWebmInputOnce(); h.f.now = h.f.context.deadlineEpochMillis
        assertEquals(SaveError.DEADLINE_EXCEEDED, assertThrows(SaveFailure::class.java) { a.borrowedWebmInput(b) }.code)
        a.close(); assertFalse(h.f.attempts.hasAnyLeases())
    }
    private fun await(latch: CountDownLatch) { assertTrue(latch.await(5, TimeUnit.SECONDS)) }
    private fun <T> start(action: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { result ->
        Thread { try { result.complete(action()) } catch (failure: Throwable) { result.completeExceptionally(failure) } }.apply { isDaemon = true; start() }
    }
}
