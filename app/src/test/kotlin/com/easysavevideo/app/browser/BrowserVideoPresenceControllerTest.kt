package com.easysavevideo.app.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserVideoPresenceControllerTest {
    private class Fixture {
        var time = 0L
        data class Work(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val work = mutableListOf<Work>()
        val replies = mutableListOf<(String?) -> Unit>()
        val owners = mutableListOf<Any>()
        var state = BrowserVideoPresence.UNKNOWN
        val controller = BrowserVideoPresenceController({ time }, { delay, action ->
            val item = Work(time + delay, action); work += item
            val cancel: () -> Unit = { item.cancelled = true }; cancel
        }, { target, reply -> owners += target.owner; replies += reply }, { state = it })
        fun advance(amount: Long = 0) {
            val end = time + amount
            while (true) {
                val next = work.filter { !it.cancelled && it.due <= end }.minByOrNull { it.due } ?: break
                work.remove(next); time = next.due; next.action()
            }
            time = end
        }
        fun target() = BrowserVideoPresenceTarget(Any(), BrowserVideoPresenceBudget(), 1, 1, 1)
    }
    @Test fun exactBooleanOnlyNeverConflatesUnknownAndNegative() {
        assertEquals(BrowserVideoPresence.PRESENT, BrowserVideoPresenceController.decode("true"))
        assertEquals(BrowserVideoPresence.NOT_OBSERVED, BrowserVideoPresenceController.decode("false"))
        listOf(null, "null", "\"true\"", " true", "TRUE", "0", "{}", "a".repeat(10000)).forEach {
            assertEquals(BrowserVideoPresence.UNKNOWN, BrowserVideoPresenceController.decode(it))
        }
    }
    @Test fun repeatedUpdatesDoNotScanAndPositiveStopsRetry() {
        val f = Fixture(); val target = f.target()
        repeat(100) { f.controller.update(target) }; f.advance()
        assertEquals(1, f.replies.size); f.replies[0]("true"); f.advance(60000)
        assertEquals(BrowserVideoPresence.PRESENT, f.state); assertEquals(1, f.replies.size)
    }
    @Test fun negativeGetsOnlyOneDelayedRecheck() {
        val f = Fixture(); f.controller.update(f.target()); f.advance(); f.replies[0]("false")
        f.advance(1499); assertEquals(1, f.replies.size)
        f.advance(1); assertEquals(2, f.replies.size); f.replies[1]("false"); f.advance(60000)
        assertEquals(2, f.replies.size); assertEquals(BrowserVideoPresence.NOT_OBSERVED, f.state)
    }
    @Test fun timeoutLateAndDuplicateRepliesCannotOverwriteNewResult() {
        val f = Fixture(); f.controller.update(f.target()); f.advance(); f.advance(1500)
        assertEquals(2, f.replies.size)
        f.replies[0]("true"); assertEquals(BrowserVideoPresence.UNKNOWN, f.state)
        f.replies[1]("false"); f.replies[1]("true")
        assertEquals(BrowserVideoPresence.NOT_OBSERVED, f.state)
    }
    @Test fun hiddenBackgroundOrDisabledCancelsAllScheduledWork() {
        val f = Fixture(); f.controller.update(f.target()); f.advance(); f.controller.update(null)
        f.replies[0]("true"); f.advance(60000)
        assertEquals(1, f.replies.size); assertEquals(BrowserVideoPresence.UNKNOWN, f.state)
    }
    @Test fun switchAwayAndBackCannotReuseOldCallbackOrRefundBudget() {
        val f = Fixture(); val target = f.target(); f.controller.update(target); f.advance()
        f.controller.update(null); f.controller.update(target.copy(interaction = 2)); f.advance(1500)
        f.replies[0]("true"); assertEquals(BrowserVideoPresence.UNKNOWN, f.state)
        f.replies[1]("false"); f.controller.update(null); f.controller.update(target.copy(interaction = 3)); f.advance(60000)
        assertEquals(2, f.replies.size); assertEquals(2, target.document.attempts)
    }
    @Test fun eachNavigationIdentityInvalidatesLateCallback() {
        listOf<(BrowserVideoPresenceTarget) -> BrowserVideoPresenceTarget>(
            { it.copy(owner = Any()) }, { it.copy(document = BrowserVideoPresenceBudget()) },
            { it.copy(epoch = 2) }, { it.copy(interaction = 2) }, { it.copy(navigation = 2) },
        ).forEach { replace ->
            val f = Fixture(); val target = f.target(); f.controller.update(target); f.advance()
            f.controller.update(replace(target)); f.replies[0]("true")
            assertEquals(BrowserVideoPresence.UNKNOWN, f.state)
        }
    }
    @Test fun sameDocumentHistoryDoesNotRefundAttempts() {
        val f = Fixture(); val target = f.target(); f.controller.update(target); f.advance(); f.replies[0]("false")
        f.controller.update(target.copy(navigation = 2)); f.advance(1500); f.replies[1]("false")
        f.controller.update(target.copy(navigation = 3)); f.advance(60000)
        assertEquals(2, f.replies.size)
    }
    @Test fun globalBudgetSurvivesNewDocumentsAndHasNoEndlessRetry() {
        val f = Fixture()
        repeat(6) { f.controller.update(f.target()); f.advance(1500); f.replies.lastOrNull()?.invoke("true") }
        assertEquals(4, f.replies.size); f.advance(60000); assertEquals(4, f.replies.size)
        f.controller.update(f.target()); f.advance(); assertEquals(5, f.replies.size)
    }
    @Test fun closeCancelsAndCannotRestart() {
        val f = Fixture(); f.controller.update(f.target()); f.advance(); f.controller.close()
        f.replies[0]("true"); f.controller.update(f.target()); f.advance(60000)
        assertEquals(1, f.replies.size); assertEquals(BrowserVideoPresence.UNKNOWN, f.state)
    }
    @Test fun retiringBeforeInitialDispatchExecutesNothing() {
        val f = Fixture(); f.controller.update(f.target()); f.controller.update(null); f.advance(60000)
        assertTrue(f.replies.isEmpty())
    }
    @Test fun evaluationExceptionIsUnknownAndStillBounded() {
        var count = 0; val f = Fixture()
        val controller = BrowserVideoPresenceController({ f.time }, { delay, action ->
            val item = Fixture.Work(f.time + delay, action); f.work += item
            val cancel: () -> Unit = { item.cancelled = true }; cancel
        }, { _, _ -> count++; throw IllegalStateException() }, { f.state = it })
        controller.update(f.target()); f.advance(60000)
        assertEquals(2, count); assertEquals(BrowserVideoPresence.UNKNOWN, f.state)
    }
    @Test fun rateBudgetIsRollingAcrossThirtySecondBoundary() {
        val f = Fixture()
        f.controller.update(f.target()); f.advance(); f.replies.last()("true")
        f.advance(26000)
        repeat(3) { f.advance(1500); f.controller.update(f.target()); f.advance(); f.replies.last()("true") }
        assertEquals(4, f.replies.size)
        // The first attempt aged out, but the three recent attempts still consume their slots.
        f.advance(1500); f.controller.update(f.target()); f.advance(); f.replies.last()("true")
        assertEquals(5, f.replies.size)
        f.controller.update(f.target()); f.advance(1500)
        assertEquals(5, f.replies.size)
    }

    @Test fun pauseResumeSameTargetStillRejectsOldGeneration() {
        val f = Fixture(); val target = f.target(); f.controller.update(target); f.advance()
        f.controller.update(null); f.controller.update(target); f.advance(1500)
        assertEquals(2, f.replies.size)
        f.replies[0]("true"); assertEquals(BrowserVideoPresence.UNKNOWN, f.state)
        f.replies[1]("false"); assertEquals(BrowserVideoPresence.NOT_OBSERVED, f.state)
        assertEquals(2, target.document.attempts)
    }

}
