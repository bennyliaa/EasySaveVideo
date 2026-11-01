package com.easysavevideo.app.diagnostics

import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executor

/** A one-request capability. Implementations must not restore or persist picker grants. */
internal fun interface DiagnosticExportPort { fun open(): OutputStream }
/** Content failed final validation, but descriptor close positively completed. */
internal class ExportContentChangedAfterClose : Exception()
internal enum class DiagnosticExportPhase { IDLE, PREPARING, CHOOSING, WRITING, CANCELLING, EMPTY, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, BLOCKED }
internal data class DiagnosticExportState(
    val phase: DiagnosticExportPhase = DiagnosticExportPhase.IDLE,
    val releaseUnconfirmed: Boolean = false,
) {
    val busy get() = releaseUnconfirmed || phase in setOf(DiagnosticExportPhase.PREPARING, DiagnosticExportPhase.CHOOSING,
        DiagnosticExportPhase.WRITING, DiagnosticExportPhase.CANCELLING)
}

/** Shared by Android runtimes across Activity recreation; never cleared without worker/close evidence. */
internal class DiagnosticWorkFence {
    private var owner: String? = null
    @Synchronized fun acquire(id: String): Boolean { if (owner != null) return false; owner = id; return true }
    @Synchronized fun release(id: String) { if (owner == id) owner = null }
}

/** Memory-only operation: no saved-state, journal, retry, background upload, or target deletion. */
internal class DiagnosticExportController(
    private val read: () -> DiagnosticSnapshotResult,
    private val worker: Executor,
    private val dispatch: (() -> Unit) -> Unit,
    private val changed: (DiagnosticExportState) -> Unit,
    private val choose: (String) -> Unit,
    private val dismiss: (String) -> Unit,
    private val fence: DiagnosticWorkFence = DiagnosticWorkFence(),
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val schedule: (Long, () -> Unit) -> AutoCloseable = { _, _ -> AutoCloseable {} },
    private val stillCurrent: () -> Boolean = { true },
) : AutoCloseable {
    private val lock = Any()
    private var request: String? = null
    private var snapshot: DiagnosticSnapshot? = null
    private var running = false
    private var closed = false
    private var deadline = 0L
    private var deadlineGeneration = 0L
    private var timer: AutoCloseable? = null
    private var current = DiagnosticExportState()
    val state get() = synchronized(lock) { current }
    private fun update(phase: DiagnosticExportPhase, releaseUnconfirmed: Boolean = false) {
        current = DiagnosticExportState(phase, releaseUnconfirmed)
        val value = current
        dispatch { changed(value) }
    }
    private fun disarm() { deadlineGeneration++; timer?.close(); timer = null }
    private fun arm(duration: Long) {
        disarm(); deadline = monotonicMillis() + duration
        val version = deadlineGeneration
        fun wake() {
            synchronized(lock) {
                if (version != deadlineGeneration) return@synchronized
                val remaining = deadline - monotonicMillis()
                if (remaining > 0) timer = schedule(remaining, ::wake) else expire()
            }
        }
        timer = schedule(duration, ::wake)
    }
    fun checkDeadline() = synchronized(lock) {
        if (timer != null && monotonicMillis() >= deadline) expire()
    }
    private fun expire() {
        val id = request
        request = null; snapshot = null; disarm()
        if (id != null) { dismiss(id); if (!running) fence.release(id) }
        update(DiagnosticExportPhase.TIMED_OUT, running)
    }
    fun begin() { tryBegin() }
    fun tryBegin(): Boolean = synchronized(lock) {
        if (closed || running || current.busy) return@synchronized false
        val id = UUID.randomUUID().toString()
        if (!fence.acquire(id)) { update(DiagnosticExportPhase.BLOCKED); return@synchronized false }
        request = id; snapshot = null; running = true
        update(DiagnosticExportPhase.PREPARING); arm(IO_TIMEOUT_MILLIS)
        try { worker.execute {
            val result = try { if (stillCurrent()) read() else DiagnosticSnapshotResult.Failed } catch (_: Exception) { DiagnosticSnapshotResult.Failed }
            synchronized(lock) {
                running = false
                if (request != id || closed) { drained(id, true); return@synchronized }
                if (monotonicMillis() >= deadline) { expire(); drained(id, true); return@synchronized }
                when (result) {
                    is DiagnosticSnapshotResult.Ready -> {
                        snapshot = result.snapshot
                        update(DiagnosticExportPhase.CHOOSING); arm(PICKER_TIMEOUT_MILLIS)
                        dispatch {
                            synchronized(lock) {
                                checkDeadline()
                                if (!closed && request == id && current.phase == DiagnosticExportPhase.CHOOSING) {
                                    try { check(stillCurrent()); choose(id) } catch (_: Exception) { selectionFailed(id) }
                                }
                            }
                        }
                    }
                    DiagnosticSnapshotResult.Empty -> terminal(id, DiagnosticExportPhase.EMPTY)
                    DiagnosticSnapshotResult.Failed -> terminal(id, DiagnosticExportPhase.FAILED)
                }
            }
        } } catch (_: Exception) { running = false; terminal(id, DiagnosticExportPhase.FAILED); return@synchronized false }
        true
    }
    private fun terminal(id: String, phase: DiagnosticExportPhase, released: Boolean = true) {
        request = null; snapshot = null; disarm()
        if (released) fence.release(id)
        update(phase, !released)
    }
    fun selectionFailed(id: String) = synchronized(lock) {
        if (request != id || current.phase != DiagnosticExportPhase.CHOOSING) return@synchronized
        dismiss(id); terminal(id, DiagnosticExportPhase.FAILED)
    }
    fun selected(id: String, port: DiagnosticExportPort?) = synchronized(lock) {
        checkDeadline()
        if (closed || request != id || current.phase != DiagnosticExportPhase.CHOOSING) return@synchronized
        dismiss(id)
        if (port == null) { terminal(id, DiagnosticExportPhase.CANCELLED); return@synchronized }
        val bytes = requireNotNull(snapshot).text.toByteArray(Charsets.UTF_8)
        snapshot = null; running = true; update(DiagnosticExportPhase.WRITING); arm(IO_TIMEOUT_MILLIS)
        try { worker.execute {
            var successful = false
            var output: OutputStream? = null
            var released = true
            try {
                output = admitted(id) { port.open() }
                var offset = 0
                while (offset < bytes.size) {
                    val count = minOf(4096, bytes.size - offset)
                    admitted(id) { output.write(bytes, offset, count) }
                    offset += count
                }
                admitted(id) { output.flush() }
                successful = true
            } catch (_: Exception) { /* Technical status only; never expose exception text. */ }
            finally {
                // Cleanup is always allowed, even after cancellation. No forced concurrent close.
                try { output?.close() } catch (failure: Exception) { released = failure is ExportContentChangedAfterClose; successful = false }
                bytes.fill(0)
                synchronized(lock) {
                    if (request == id && monotonicMillis() >= deadline) expire()
                    running = false
                    successful = successful && (try { stillCurrent() } catch (_: Exception) { false })
                    if (request != id || closed) drained(id, released)
                    else terminal(id, if (successful) DiagnosticExportPhase.SUCCEEDED else DiagnosticExportPhase.FAILED, released)
                }
            }
        } } catch (_: Exception) { bytes.fill(0); running = false; terminal(id, DiagnosticExportPhase.FAILED) }
    }
    /**
     * Admission under the cancellation lock is the linearization point for a provider call.
     * Cancellation revokes future admissions, not the single already-admitted call. That call may
     * finish later (including open/truncate); cancellation remains release-unconfirmed until close.
     * Never hold the UI lock across provider I/O or claim that a remote write was rolled back.
     */
    private fun <T> admitted(id: String, call: () -> T): T {
        synchronized(lock) {
            checkDeadline()
            check(!closed && request == id && stillCurrent())
        }
        return call()
    }
    fun background() = synchronized(lock) {
        checkDeadline()
        // The system picker itself pauses this Activity. Only its waiting request survives that pause.
        if (current.phase != DiagnosticExportPhase.CHOOSING) cancel()
    }
    fun cancel() = synchronized(lock) {
        if (current.phase !in setOf(DiagnosticExportPhase.PREPARING, DiagnosticExportPhase.CHOOSING, DiagnosticExportPhase.WRITING)) return@synchronized
        val id = request
        request = null; snapshot = null
        if (id != null) { dismiss(id); if (!running) fence.release(id) }
        if (!running) disarm() // Running cancellation retains its stage deadline.
        update(if (running) DiagnosticExportPhase.CANCELLING else DiagnosticExportPhase.CANCELLED, running)
    }
    private fun drained(id: String, released: Boolean) {
        disarm()
        if (released) fence.release(id)
        update(if (current.phase == DiagnosticExportPhase.TIMED_OUT) DiagnosticExportPhase.TIMED_OUT else DiagnosticExportPhase.CANCELLED, !released)
    }
    override fun close() = synchronized(lock) { cancel(); closed = true }
    companion object {
        const val IO_TIMEOUT_MILLIS = 30_000L
        const val PICKER_TIMEOUT_MILLIS = 300_000L
    }
}
