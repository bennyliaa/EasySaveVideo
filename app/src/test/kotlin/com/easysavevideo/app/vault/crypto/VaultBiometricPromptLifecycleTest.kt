package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.library.export.ExportRelease
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Runs the SAME state machine used by the Android leaf, with opaque public tokens and fixed time.
 * Does not instantiate Android Context/Prompt/CancellationSignal/Cipher/KeyStore or generate keys.
 */
class VaultBiometricPromptLifecycleTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        var reject = false
        var beforeEnqueue: (() -> Unit)? = null
        override fun execute(command: Runnable) {
            beforeEnqueue?.invoke()
            if (reject) throw RejectedExecutionException("fixed-rejection")
            tasks.addLast(command)
        }
        fun one() { tasks.removeFirst().run() }
        fun all() { while (tasks.isNotEmpty()) one() }
    }
    private class Clock(var now: Long = 0) : VaultMonotonicClock {
        override fun elapsedMillis() = now
    }
    private class TimerTask(action: Runnable) : FutureTask<Unit>(action, Unit), ScheduledFuture<Unit> {
        override fun getDelay(unit: TimeUnit): Long = 0
        override fun compareTo(other: Delayed): Int = 0
    }
    private class Timer : ScheduledThreadPoolExecutor(1) {
        val timers = mutableListOf<TimerTask>()
        var reject = false
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            if (reject) throw RejectedExecutionException("fixed-timer-rejection")
            check(delay > 0)
            return TimerTask(command).also { timers += it }
        }
        fun fire() { timers.toList().forEach { it.run() } }
    }
    private class Fixture {
        val platform = Queue()
        val owner = Queue()
        val clock = Clock()
        val timer = Timer()
        val context = VaultOperationContext(1_000, clock)
        val crypto = Any()
        val cipher = Any()
        val value = Any()
        var current = true
        var prepares = 0
        var starts = 0
        var cancels = 0
        var finishes = 0
        var factoryFailure = false
        var factoryException: Exception? = null
        var start: (() -> Unit)? = null
        var finish: (() -> Unit)? = null
        lateinit var callbacks: VaultBiometricPromptCallbacks
        lateinit var callbackExecutor: Executor
        val outcomes = mutableListOf<AndroidVaultBiometric.Outcome<Any>>()
        val op = VaultBiometricPromptOperation(context, clock, VaultBiometricSerialDispatcher(platform), owner, timer,
            { if (!current) throw VaultOperationException(VaultFailure.CANCELLED) }, { outcomes += it },
            { cb, executor ->
                prepares++; callbacks = cb; callbackExecutor = executor
                factoryException?.let { throw it }
                if (factoryFailure) throw IllegalArgumentException("fixed-prepare")
                object : VaultBiometricPlatformSession<Any> {
                    override val cryptoObject = crypto
                    override val preparedCipher = cipher
                    override fun start() { starts++; start?.invoke() }
                    override fun cancel() { cancels++ }
                    override fun finish(): Any { finishes++; finish?.invoke(); return value }
                }
            })
        fun submit() { op.start(); platform.all() }
        fun success() = callbacks.succeeded(crypto, cipher, true)
        fun settled() { platform.all(); owner.all() }
        fun failure(): AndroidVaultBiometric.Failure = (outcomes.single() as AndroidVaultBiometric.Outcome.Failed).failure
    }

    @Test fun prepareHasNoSideEffectAndCancelBeforeStartNeverCallsPlatform() {
        val f = Fixture()
        assertEquals(0, f.prepares)
        assertEquals(AndroidVaultBiometric.Phase.PREPARED, f.op.phase())
        f.op.close(); f.op.start()
        assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain()) // Failure delivery still owned.
        f.settled()
        assertEquals(0, f.prepares); assertEquals(0, f.starts); assertEquals(0, f.cancels)
        assertEquals(AndroidVaultBiometric.Submission.NOT_STARTED, f.op.submission())
        assertFalse(f.op.isPlatformTerminal())
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
        assertEquals(AndroidVaultBiometric.Failure.CANCELLED, f.failure())
    }

    @Test fun queuedStartCancelledBeforeDispatchNeverPassesCancelledSignalToAuthenticate() {
        val f = Fixture()
        f.op.start(); f.op.close(); f.settled()
        assertEquals(0, f.starts); assertEquals(0, f.prepares); assertEquals(0, f.cancels)
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
    }

    @Test fun exactSuccessDoesCryptoOnceAndRetainsUntilOwnerContinuationReturns() {
        val f = Fixture(); f.submit(); f.success()
        assertTrue(f.op.isPlatformTerminal())
        assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        assertEquals(0, f.finishes)
        f.owner.all()
        assertEquals(1, f.finishes)
        assertSame(f.value, (f.outcomes.single() as AndroidVaultBiometric.Outcome.Completed<*>).value)
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
        f.success(); f.callbacks.error(AndroidVaultBiometric.Failure.CANCELLED); f.settled()
        assertEquals(1, f.outcomes.size); assertEquals(1, f.finishes)
    }

    @Test fun mismatchedCryptoWrapperCipherOrAuthenticationTypeRejectsBeforeCrypto() {
        for (case in 0..3) {
            val f = Fixture(); f.submit()
            f.callbacks.succeeded(if (case == 0) null else if (case == 1) Any() else f.crypto,
                if (case == 2) Any() else f.cipher, case != 3)
            f.settled()
            assertEquals(AndroidVaultBiometric.Failure.REJECTED, f.failure())
            assertEquals(0, f.finishes); assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
        }
    }

    @Test fun repeatedNonterminalScansKeepTheSameSubmission() {
        val f = Fixture(); f.submit()
        repeat(4) { f.callbacks.scanFailed() }
        assertFalse(f.op.isPlatformTerminal()); assertTrue(f.outcomes.isEmpty())
        assertEquals(1, f.starts)
        f.success(); f.settled(); assertEquals(1, f.finishes)
    }

    @Test fun actualNegativeDismissalWithoutErrorIsTerminalAndAllowsDrain() {
        val f = Fixture(); f.submit()
        f.callbackExecutor.execute { f.callbacks.negativeDismissed() }
        assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        f.platform.all()
        assertTrue(f.op.isPlatformTerminal())
        assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        f.owner.all()
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
        assertEquals(AndroidVaultBiometric.Failure.CANCELLED, f.failure()); assertEquals(0, f.cancels)
    }

    @Test fun cancelAfterSubmissionRequestsCancelButRetainsWithoutTerminal() {
        val f = Fixture(); f.submit(); f.op.close(); f.settled()
        assertEquals(1, f.cancels); assertFalse(f.op.isPlatformTerminal())
        assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        f.callbacks.error(AndroidVaultBiometric.Failure.CANCELLED); f.settled()
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
        assertEquals(1, f.outcomes.size)
    }

    @Test fun closeDuringStartCannotCancelSignalUntilStartReturns() {
        val f = Fixture()
        f.start = {
            assertEquals(AndroidVaultBiometric.Phase.SUBMITTING, f.op.phase())
            f.op.close()
            assertEquals(0, f.cancels)
            assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        }
        f.submit(); f.owner.all()
        assertEquals(1, f.cancels); assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        f.callbacks.negativeDismissed(); f.settled()
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
    }

    @Test fun terminalBeforeStartReturnsStillRetainsSubmissionAndCallbackWork() {
        val f = Fixture()
        f.start = {
            f.callbacks.negativeDismissed(); f.owner.all()
            assertTrue(f.op.isPlatformTerminal())
            assertEquals(AndroidVaultBiometric.Submission.IN_FLIGHT, f.op.submission())
            assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        }
        f.submit(); assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
    }

    @Test fun synchronousSuccessBeforeStartReturnsDoesNotLosePending() {
        val f = Fixture()
        f.start = {
            f.success(); f.owner.all()
            assertEquals(1, f.finishes)
            assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        }
        f.submit(); assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
        assertEquals(1, f.outcomes.size)
    }

    @Test fun factoryFailureConfirmsNotStartedWithoutInventingPlatformTerminal() {
        val f = Fixture(); f.factoryFailure = true; f.submit(); f.settled()
        assertEquals(0, f.starts)
        assertEquals(AndroidVaultBiometric.Submission.NOT_STARTED, f.op.submission())
        assertFalse(f.op.isPlatformTerminal()); assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
    }

    @Test fun authenticateThrowIsUnknownUntilAnExactTerminalArrives() {
        val f = Fixture(); f.start = { throw IllegalArgumentException("fixed-start-throw") }
        f.submit(); f.settled()
        assertEquals(AndroidVaultBiometric.Submission.UNCONFIRMED, f.op.submission())
        assertFalse(f.op.isPlatformTerminal()); assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        f.callbacks.error(AndroidVaultBiometric.Failure.SLOT_UNAVAILABLE); f.settled()
        assertEquals(AndroidVaultBiometric.Submission.CONFIRMED, f.op.submission())
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
    }

    @Test fun exactTerminalThenStartThrowHasObservedTerminationButWaitsForExit() {
        val f = Fixture()
        f.start = {
            f.callbacks.negativeDismissed()
            assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
            throw IllegalStateException("fixed-after-terminal")
        }
        f.submit(); f.settled()
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
        assertEquals(AndroidVaultBiometric.Submission.CONFIRMED, f.op.submission())
    }

    @Test fun schedulerOrStartDispatchRejectionProvesNeverStarted() {
        for (rejectTimer in listOf(false, true)) {
            val f = Fixture()
            f.timer.reject = rejectTimer; f.platform.reject = !rejectTimer
            f.op.start(); f.owner.all()
            assertEquals(0, f.starts); assertEquals(0, f.prepares)
            assertEquals(AndroidVaultBiometric.Submission.NOT_STARTED, f.op.submission())
            assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
        }
    }

    @Test fun callbackExecutorRejectionDoesNotForgeTerminal() {
        val f = Fixture(); f.submit(); f.platform.reject = true
        f.callbackExecutor.execute { f.success() }; f.owner.all()
        assertFalse(f.op.isPlatformTerminal()); assertEquals(0, f.finishes)
        assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        assertEquals(AndroidVaultBiometric.Failure.SLOT_UNAVAILABLE, f.failure())
    }

    @Test fun rejectedCryptoContinuationDoesNotPublishSuccessOrLeakOwnedWork() {
        val f = Fixture(); f.submit(); f.owner.reject = true
        f.success()
        assertEquals(0, f.finishes); assertTrue(f.outcomes.isEmpty())
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
    }

    @Test fun timeoutRequestsCancelButCannotStandInForTerminal() {
        val f = Fixture(); f.submit(); f.clock.now = 1_000; f.timer.fire(); f.settled()
        assertEquals(AndroidVaultBiometric.Failure.TIMEOUT, f.failure()); assertEquals(1, f.cancels)
        assertFalse(f.op.isPlatformTerminal()); assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        f.success(); f.settled(); assertEquals(0, f.finishes)
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
    }

    @Test fun deadlineEqualityNegativeClockAndStaleHostPreventPlatformStart() {
        for (case in 0..2) {
            val f = Fixture()
            when (case) { 0 -> f.clock.now = 1_000; 1 -> f.clock.now = -1; else -> f.current = false }
            f.op.start(); f.settled()
            assertEquals(0, f.starts); assertEquals(0, f.prepares)
            assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
        }
    }

    @Test fun changedHostOrCancellationAfterSuccessBeforeCryptoRejectsCompletion() {
        for (hostChange in listOf(false, true)) {
            val f = Fixture(); f.submit(); f.success()
            if (hostChange) f.current = false else f.context.cancel()
            f.settled()
            assertEquals(0, f.finishes)
            assertEquals(AndroidVaultBiometric.Failure.CANCELLED, f.failure())
            assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
        }
    }

    @Test fun cancellationInsideCryptoDiscardsValueAndDrainWaitsForCryptoExit() {
        val f = Fixture(); f.submit()
        f.finish = {
            f.op.close()
            assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        }
        f.success(); f.settled()
        assertEquals(1, f.finishes); assertEquals(AndroidVaultBiometric.Failure.CANCELLED, f.failure())
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
    }

    @Test fun errorThenSuccessAndOldCallbackCannotAffectNewOperation() {
        val old = Fixture(); old.submit()
        old.callbacks.error(AndroidVaultBiometric.Failure.PERMANENT_LOCKOUT); old.settled()
        val current = Fixture(); current.submit()
        old.success(); old.callbacks.negativeDismissed(); old.settled()
        assertEquals(0, old.finishes); assertEquals(1, old.outcomes.size)
        assertFalse(current.op.isPlatformTerminal()); assertEquals(0, current.cancels)
        current.success(); current.settled(); assertEquals(1, current.finishes)
    }

    @Test fun drainObserverOnlyRunsAfterAllOwnedContinuationsReturn() {
        val f = Fixture(); var drains = 0
        f.op.onDrain { drains++ }
        f.submit(); f.success()
        assertEquals(0, drains)
        f.owner.one() // Crypto completes, observer is now enqueued.
        assertEquals(0, drains)
        f.owner.all(); assertEquals(1, drains)
        f.op.onDrain { drains++ }; f.owner.all(); assertEquals(2, drains)
    }
    @Test fun callbackRunnableThrowRetiresItsWorkOnceAndCannotInventTerminal() {
        val f = Fixture(); f.submit()
        f.callbackExecutor.execute { throw IllegalStateException("fixed-callback-throw") }
        f.settled()
        assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        assertFalse(f.op.isPlatformTerminal())
        f.callbacks.error(AndroidVaultBiometric.Failure.SLOT_UNAVAILABLE); f.settled()
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
        assertEquals(1, f.outcomes.size)
    }

    @Test fun promptDeadlineCannotExtendOriginalContextOrLoseCancellationLineage() {
        val clock = Clock(Long.MAX_VALUE - 2)
        val original = VaultOperationContext(Long.MAX_VALUE, clock)
        val bounded = original.bounded(VaultRuntimeLimits.PROMPT_MILLIS)
        assertEquals(Long.MAX_VALUE, bounded.deadlineElapsedMillis)
        assertTrue(original.sharesOperation(bounded))
        original.cancel()
        try { bounded.checkActive(); fail("cancelled lineage") }
        catch (expected: VaultOperationException) { assertEquals(VaultFailure.CANCELLED, expected.category) }
    }

    @Test fun failureEnqueueBlockedOnAnotherThreadRetainsAppOwnedDrain() {
        val f = Fixture()
        val enqueueEntered = CountDownLatch(1)
        val enqueueAllowed = CountDownLatch(1)
        f.owner.beforeEnqueue = {
            enqueueEntered.countDown()
            check(enqueueAllowed.await(5, TimeUnit.SECONDS))
        }
        val closeThread = Thread { f.op.close() }
        closeThread.start()
        try {
            assertTrue(enqueueEntered.await(5, TimeUnit.SECONDS))
            assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
            assertFalse(f.op.isPlatformTerminal())
            assertEquals(0, f.prepares)
        } finally { enqueueAllowed.countDown(); closeThread.join(5_000) }
        assertFalse(closeThread.isAlive)
        f.owner.beforeEnqueue = null
        f.owner.all()
        assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
        assertEquals(AndroidVaultBiometric.Failure.CANCELLED, f.failure())
    }

    @Test fun keyMissingOrUnsupportedProfileReportsSlotUnavailableRatherThanUserCancellation() {
        for (category in listOf(VaultFailure.UNAVAILABLE, VaultFailure.UNSUPPORTED)) {
            val f = Fixture(); f.factoryException = VaultOperationException(category)
            f.submit(); f.settled()
            assertEquals(AndroidVaultBiometric.Failure.SLOT_UNAVAILABLE, f.failure())
            assertEquals(ExportRelease.RELEASED, f.op.observeDrain())
            assertEquals(0, f.starts)
        }
    }

    @Test fun systemPromptFocusExceptionIsOnlyForConfirmedAcceptingOperation() {
        val f = Fixture()
        assertFalse(f.op.ownsSystemPromptFocus())
        f.start = { assertTrue(f.op.ownsSystemPromptFocus()) }
        f.submit(); assertTrue(f.op.ownsSystemPromptFocus())
        f.op.close(); assertFalse(f.op.ownsSystemPromptFocus())
        f.settled(); assertEquals(ExportRelease.UNCONFIRMED, f.op.observeDrain())
        f.callbacks.negativeDismissed(); f.settled()
        assertFalse(f.op.ownsSystemPromptFocus())
        val unknown = Fixture(); unknown.start = { throw IllegalStateException("fixed-unknown") }
        unknown.submit(); assertFalse(unknown.op.ownsSystemPromptFocus())
        val complete = Fixture(); complete.submit(); complete.success()
        assertFalse(complete.op.ownsSystemPromptFocus())
        complete.settled()
    }

}
