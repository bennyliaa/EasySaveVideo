package com.easysavevideo.app.media.transform

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Wait for the worker's actual completion acknowledgement, without mistaking interruption for it. */
internal object TransformerCleanupWait {
    fun awaitCompletion(result: CompletableFuture<*>, timeoutMillis: Long, previouslyInterrupted: Boolean = false): Boolean {
        require(timeoutMillis in 1..60_000)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var interrupted = Thread.interrupted() || previouslyInterrupted
        try {
            while (!result.isDone) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try { result.get(remaining, TimeUnit.NANOSECONDS); return true }
                catch (_: InterruptedException) { interrupted = true }
                catch (_: ExecutionException) { return true } // Caller must still inspect release proof.
                catch (_: java.util.concurrent.CancellationException) { return true }
                catch (_: TimeoutException) { return false }
            }
            return true
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
