package com.easysavevideo.app.editor.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class EditorCleanupWaitTest {
    @Test fun initiallyInterruptedCallerWaitsForActualCleanupAndRestoresInterrupt() {
        val done = CompletableFuture<Unit>()
        val worker = Thread { Thread.sleep(40); done.complete(Unit) }.apply { start() }
        try {
            Thread.currentThread().interrupt()
            assertTrue(EditorCleanupWait.awaitCompletion(done, 2_000))
            assertTrue(done.isDone)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted(); worker.join(2_000) }
    }
    @Test fun interruptionDuringCleanupWaitDoesNotShortCircuitActualAcknowledgement() {
        val done = CompletableFuture<Unit>()
        val entered = CountDownLatch(1)
        val caller = Thread.currentThread()
        val worker = Thread {
            check(entered.await(2, TimeUnit.SECONDS)); Thread.sleep(30)
            caller.interrupt(); Thread.sleep(40); done.complete(Unit)
        }.apply { start() }
        try {
            entered.countDown()
            assertTrue(EditorCleanupWait.awaitCompletion(done, 2_000))
            assertTrue(done.isDone)
            assertTrue(caller.isInterrupted)
        } finally { Thread.interrupted(); worker.join(2_000) }
    }
    @Test fun trueTimeoutIsNeverAReleaseAcknowledgement() {
        val never = CompletableFuture<Unit>()
        try {
            Thread.currentThread().interrupt()
            assertFalse(EditorCleanupWait.awaitCompletion(never, 30))
            assertFalse(never.isDone)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun originalInterruptedExceptionIsRememberedEvenIfItsFlagWasCleared() {
        try {
            assertTrue(EditorCleanupWait.awaitCompletion(CompletableFuture.completedFuture(Unit), 30, true))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
