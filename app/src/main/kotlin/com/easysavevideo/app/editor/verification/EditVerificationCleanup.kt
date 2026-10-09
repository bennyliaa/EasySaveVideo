package com.easysavevideo.app.editor.verification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Acknowledges actual worker cleanup, never Future cancellation or a UI timeout. */
internal class EditVerificationCleanup {
    private val completed = CountDownLatch(1)
    @Volatile private var confirmed = false

    @Synchronized fun finished(releaseConfirmed: Boolean) {
        check(completed.count == 1L)
        confirmed = releaseConfirmed
        completed.countDown()
    }

    /** Cancellation still waits a bounded time for cleanup. Restore the caller's interrupt flag. */
    fun awaitConfirmed(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..60_000)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        var interrupted = Thread.interrupted()
        try {
            while (true) {
                if (completed.count == 0L) return confirmed
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try {
                    if (!completed.await(remaining, TimeUnit.NANOSECONDS)) return false
                    return confirmed
                } catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
