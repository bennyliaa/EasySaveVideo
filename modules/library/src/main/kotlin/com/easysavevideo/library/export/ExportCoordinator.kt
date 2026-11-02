package com.easysavevideo.library.export

import com.easysavevideo.library.LibraryIds
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Application-scoped owner. Copying runs outside the gate; cancellation can persist during source
 * reads, writes, flush, close and readback. Only the final, durable publication decision is fenced.
 * A cancellation accepted before that decision cannot publish. Once PUBLISH_INTENT is durable,
 * cancellation reports TOO_LATE, including when the provider's publication outcome is uncertain.
 * Methods perform blocking IO and belong off the UI thread. Never run a second coordinator against
 * the same journal concurrently, including during recovery.
 */
class ExportCoordinator(private val journal: ExportJournal, private val port: ExportPort) {
    private val gate = Any()
    private val running = mutableSetOf<String>()

    fun run(request: ExportRequest): ExportRecord {
        val record = synchronized(gate) {
            val found = journal.create(request)
            requireRequest(found, request)
            if (found.phase != ExportPhase.PREPARED || request.operationId in running) return found
            claim(request.operationId)
            found
        }
        return try { execute(record) } finally { synchronized(gate) { running.remove(request.operationId) } }
    }

    fun cancel(operationId: String): ExportCancellation = synchronized(gate) {
        LibraryIds.requireValid(operationId)
        val record = journal.get(operationId) ?: return@synchronized ExportCancellation.NOT_FOUND
        if (record.phase == ExportPhase.COPIED_UNVERIFIED) {
            journal.put(record.copy(phase = ExportPhase.CLEANUP_PENDING, cancelRequested = true,
                cleanup = ExportCleanup.PENDING, error = ExportError.CANCELLED))
            return@synchronized ExportCancellation.ACCEPTED
        }
        if (record.phase.terminal || publicationDecided(record)) return@synchronized ExportCancellation.TOO_LATE
        if (!record.cancelRequested) journal.put(record.copy(cancelRequested = true))
        ExportCancellation.ACCEPTED
    }

    /** Explicit user cleanup of an unverified SAF copy; never attempts another write/export. */
    fun cleanupUnverified(operationId: String): ExportRecord {
        synchronized(gate) {
            val record = current(operationId)
            if (record.phase != ExportPhase.COPIED_UNVERIFIED &&
                !(record.cancelRequested && record.phase == ExportPhase.CLEANUP_PENDING)) {
                throw ExportFailure(ExportError.INVALID_STATE)
            }
            cancel(operationId)
        }
        return recover(operationId)
    }

    /** Retry reconciliation/cleanup of this operation; never create a second output on uncertainty. */
    fun recover(operationId: String): ExportRecord {
        LibraryIds.requireValid(operationId)
        val initial = synchronized(gate) {
            val record = journal.get(operationId) ?: throw ExportFailure(ExportError.INVALID_STATE)
            if (record.phase.terminal || operationId in running) return record
            if (record.phase != ExportPhase.PREPARED) claim(operationId)
            record
        }
        if (initial.phase == ExportPhase.PREPARED) return run(initial.request)
        return try {
            val reconciliation = try { port.reconcile(initial) } catch (_: Exception) { ExportReconciliation.Unknown }
            when (reconciliation) {
                ExportReconciliation.Unknown -> change(operationId) {
                    it.copy(phase = ExportPhase.RECONCILIATION_REQUIRED,
                        error = if (publicationDecided(it)) ExportError.PUBLISH_UNCERTAIN else ExportError.RECOVERY_REQUIRED)
                }
                ExportReconciliation.Absent -> change(operationId) {
                    it.copy(phase = if (it.cancelRequested) ExportPhase.CANCELLED else ExportPhase.FAILED,
                        cleanup = ExportCleanup.REMOVED,
                        error = if (it.cancelRequested) ExportError.CANCELLED else ExportError.RECOVERY_REQUIRED)
                }
                is ExportReconciliation.Owned -> recoverOwned(initial, reconciliation)
            }
        } finally { synchronized(gate) { running.remove(operationId) } }
    }

    fun recoverPending(limit: Int = ExportLimits.MAX_RECOVERY_BATCH): List<ExportRecord> {
        require(limit in 1..ExportLimits.MAX_RECOVERY_BATCH)
        val records = synchronized(gate) { journal.unfinished(limit) }
        require(records.size <= limit && records.map { it.operationId }.distinct().size == records.size)
        return records.map { recover(it.operationId) }
    }

    private fun execute(initial: ExportRecord): ExportRecord {
        val id = initial.operationId
        var source: ExportInput? = null
        var writer: ExportWrite? = null
        var readback: ExportInput? = null
        var creationAttempted = false
        var releaseUnconfirmed = false
        fun closeSource() {
            val owned = source ?: return
            source = null
            if (!release { owned.close() }) releaseUnconfirmed = true
        }
        fun closeWriter() {
            val owned = writer ?: return
            writer = null
            if (!release { owned.close() }) releaseUnconfirmed = true
        }
        fun closeReadback() {
            val owned = readback ?: return
            readback = null
            if (!release { owned.close() }) releaseUnconfirmed = true
        }
        try {
            checkCancellation(id)
            val opened = port.openSource(initial.request)
            source = opened.input
            if (opened.ref != initial.request.source) throw ExportFailure(ExportError.SOURCE_MISMATCH)
            val evidence = opened.evidence
            change(id) { it.copy(evidence = evidence) }
            val intent = changeUnlessCancelled(id) { it.copy(phase = ExportPhase.CREATE_INTENT) }
            creationAttempted = true
            val output = port.create(intent)
            if (output.kind != initial.request.destination.kind || (initial.output != null && initial.output != output)) {
                throw ExportFailure(ExportError.CREATE_UNCERTAIN)
            }
            val copying = change(id) { it.copy(phase = ExportPhase.COPYING, output = output) }
            checkCancellation(id)
            writer = port.openWrite(copying, output)
            verify(source!!.stream, writer!!.stream, evidence, ExportError.SOURCE_MISMATCH) { checkCancellation(id) }
            writer!!.stream.flush()
            checkCancellation(id)
            closeWriter()
            closeSource()
            if (releaseUnconfirmed) throw ExportFailure(ExportError.RELEASE_UNCONFIRMED)
            val verifying = changeUnlessCancelled(id) {
                it.copy(phase = ExportPhase.VERIFYING, verification = ExportVerification.SOURCE_VERIFIED)
            }
            when (val result = port.openReadback(verifying, output)) {
                ExportReadback.Unsupported -> {
                    if (output.kind != ExportKind.SAF) throw ExportFailure(ExportError.READBACK_UNSUPPORTED)
                    return changeUnlessCancelled(id) {
                        it.copy(phase = ExportPhase.COPIED_UNVERIFIED,
                            verification = ExportVerification.READBACK_UNSUPPORTED,
                            error = ExportError.READBACK_UNSUPPORTED)
                    }
                }
                is ExportReadback.Opened -> {
                    readback = result.input
                    verify(readback!!.stream, null, evidence, ExportError.DESTINATION_MISMATCH) { checkCancellation(id) }
                    closeReadback()
                    if (releaseUnconfirmed) throw ExportFailure(ExportError.RELEASE_UNCONFIRMED)
                }
            }
            val decision = synchronized(gate) {
                val latest = current(id)
                if (latest.cancelRequested) throw ExportFailure(ExportError.CANCELLED)
                val decision = latest.copy(phase = ExportPhase.PUBLISH_INTENT,
                    verification = ExportVerification.DESTINATION_VERIFIED)
                journal.put(decision)
                decision
            }
            // A blocked provider cannot stall another operation's cancellation. This operation's
            // durable decision already makes subsequent cancellation explicitly TOO_LATE.
            port.publish(decision, output)
            return change(id) { it.copy(phase = ExportPhase.SUCCEEDED) }
        } catch (failure: Exception) {
            closeReadback(); closeWriter(); closeSource()
            val code = if (releaseUnconfirmed) ExportError.RELEASE_UNCONFIRMED
                else (failure as? ExportFailure)?.code ?: ExportError.IO
            return fail(id, code, creationAttempted, releaseUnconfirmed)
        } finally {
            // Also retain uncertain ownership on fatal exceptions; durable unfinished phase remains.
            closeReadback(); closeWriter(); closeSource()
        }
    }

    private fun fail(id: String, error: ExportError, creationAttempted: Boolean, uncertain: Boolean): ExportRecord {
        val record = synchronized(gate) { current(id) }
        if (record.phase.terminal) return record // a durable write may have completed before throwing
        if (publicationDecided(record)) return change(id) {
            it.copy(phase = ExportPhase.RECONCILIATION_REQUIRED, error = ExportError.PUBLISH_UNCERTAIN)
        }
        if (error == ExportError.CREATE_UNCERTAIN || (record.output == null && creationAttempted)) return change(id) {
            it.copy(phase = ExportPhase.RECONCILIATION_REQUIRED, error = ExportError.CREATE_UNCERTAIN,
                cleanup = if (uncertain) ExportCleanup.UNCONFIRMED else ExportCleanup.PENDING)
        }
        if (uncertain || error == ExportError.RELEASE_UNCONFIRMED) return change(id) {
            it.copy(phase = ExportPhase.CLEANUP_PENDING, cleanup = ExportCleanup.UNCONFIRMED,
                error = ExportError.RELEASE_UNCONFIRMED)
        }
        if (record.output == null) return change(id) {
            it.copy(phase = if (it.cancelRequested) ExportPhase.CANCELLED else ExportPhase.FAILED,
                error = if (it.cancelRequested) ExportError.CANCELLED else error)
        }
        val pending = change(id) { it.copy(phase = ExportPhase.CLEANUP_PENDING, cleanup = ExportCleanup.PENDING, error = error) }
        return cleanup(pending)
    }

    private fun recoverOwned(initial: ExportRecord, found: ExportReconciliation.Owned): ExportRecord {
        val id = initial.operationId
        if (found.output.kind != initial.request.destination.kind ||
            (initial.output != null && found.output != initial.output)) return change(id) {
            it.copy(phase = ExportPhase.RECONCILIATION_REQUIRED,
                error = if (publicationDecided(it)) ExportError.PUBLISH_UNCERTAIN else ExportError.RECOVERY_REQUIRED)
        }
        val record = change(id) { it.copy(output = found.output) }
        if (!found.resourcesReleased) return change(id) {
            it.copy(phase = ExportPhase.CLEANUP_PENDING, cleanup = ExportCleanup.UNCONFIRMED,
                error = if (publicationDecided(it)) ExportError.PUBLISH_UNCERTAIN else ExportError.RELEASE_UNCONFIRMED)
        }
        // SAF has no pending/publication toggle. A prior durable verified commit decision plus
        // the exact owned, existing document is completion proof even when published is false.
        val safCommitted = found.output.kind == ExportKind.SAF && publicationDecided(record) &&
            record.verification == ExportVerification.DESTINATION_VERIFIED
        if (found.published || safCommitted) {
            if (!publicationDecided(record) || record.cancelRequested ||
                record.verification != ExportVerification.DESTINATION_VERIFIED) return change(id) {
                it.copy(phase = ExportPhase.RECONCILIATION_REQUIRED,
                    error = if (publicationDecided(it)) ExportError.PUBLISH_UNCERTAIN else ExportError.RECOVERY_REQUIRED)
            }
            // MediaStore still requires actual IS_PENDING=0; SAF makes no atomic visibility claim.
            return change(id) { it.copy(phase = ExportPhase.SUCCEEDED, error = ExportError.NONE) }
        }
        val pending = change(id) { it.copy(phase = ExportPhase.CLEANUP_PENDING, cleanup = ExportCleanup.PENDING) }
        return cleanup(pending)
    }

    private fun cleanup(record: ExportRecord): ExportRecord {
        val output = record.output ?: throw ExportFailure(ExportError.INVALID_STATE)
        val outcome = try { port.cleanup(record, output) } catch (_: Exception) { ExportCleanup.FAILED }
        return change(record.operationId) {
            if (outcome == ExportCleanup.REMOVED) it.copy(
                phase = if (it.cancelRequested) ExportPhase.CANCELLED else ExportPhase.FAILED,
                cleanup = outcome, error = if (it.cancelRequested) ExportError.CANCELLED else it.error)
            else it.copy(phase = ExportPhase.CLEANUP_PENDING,
                cleanup = if (outcome == ExportCleanup.NOT_NEEDED) ExportCleanup.UNCONFIRMED else outcome)
        }
    }

    private fun change(id: String, transform: (ExportRecord) -> ExportRecord): ExportRecord = synchronized(gate) {
        val before = current(id)
        val after = transform(before)
        requireRequest(after, before.request)
        check(!before.cancelRequested || after.cancelRequested)
        journal.put(after)
        after
    }
    private fun changeUnlessCancelled(id: String, transform: (ExportRecord) -> ExportRecord): ExportRecord = change(id) {
        if (it.cancelRequested) throw ExportFailure(ExportError.CANCELLED)
        transform(it)
    }
    private fun checkCancellation(id: String) = synchronized(gate) {
        if (current(id).cancelRequested) throw ExportFailure(ExportError.CANCELLED)
    }
    private fun current(id: String): ExportRecord = journal.get(id) ?: throw ExportFailure(ExportError.RECOVERY_REQUIRED)
    private fun claim(id: String) {
        if (running.size >= ExportLimits.MAX_RUNNING) throw ExportFailure(ExportError.CAPACITY)
        running.add(id)
    }
    private fun requireRequest(record: ExportRecord, request: ExportRequest) {
        if (record.request != request || record.fingerprint != request.fingerprint()) throw ExportFailure(ExportError.IDEMPOTENCY_CONFLICT)
    }
    private fun publicationDecided(record: ExportRecord): Boolean =
        record.phase == ExportPhase.PUBLISH_INTENT || record.error == ExportError.PUBLISH_UNCERTAIN
    private fun release(close: () -> ExportRelease): Boolean = try { close() == ExportRelease.RELEASED } catch (_: Exception) { false }

    /** Full EOF is mandatory, including the byte immediately after the expected count. */
    private fun verify(input: InputStream, output: OutputStream?, expected: ExportEvidence,
        mismatch: ExportError, checkpoint: () -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(ExportLimits.BUFFER_BYTES)
        var count = 0L
        var emptyReads = 0
        while (true) {
            checkpoint()
            val length = minOf(buffer.size.toLong(), expected.bytes - count + 1).toInt()
            val read = input.read(buffer, 0, length)
            checkpoint()
            if (read == -1) break
            if (read < -1 || read > length) throw ExportFailure(mismatch)
            if (read == 0) {
                if (++emptyReads >= 8) throw ExportFailure(ExportError.IO)
                continue
            }
            emptyReads = 0
            if (read.toLong() > expected.bytes - count) throw ExportFailure(mismatch)
            digest.update(buffer, 0, read)
            output?.write(buffer, 0, read)
            count += read
        }
        if (count != expected.bytes || digest.digest().exportHex() != expected.sha256) throw ExportFailure(mismatch)
        checkpoint()
    }
}
