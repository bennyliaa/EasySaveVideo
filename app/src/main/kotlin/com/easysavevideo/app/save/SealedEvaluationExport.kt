package com.easysavevideo.app.save

import com.easysavevideo.app.diagnostics.*
import com.easysavevideo.discovery.*
import java.io.OutputStream
import java.util.concurrent.Executor

/** Reuses the proven one-shot bounded writer; each click freezes JSON before any picker or worker. */
internal class SealedEvaluationExport(
    private val enabled: Boolean,
    private val currentReport: () -> SaveEvaluationSessionReport?,
    worker: Executor, dispatch: (() -> Unit) -> Unit, private val changed: (DiagnosticExportState) -> Unit,
    choose: (String) -> Unit, dismiss: (String) -> Unit,
    fence: DiagnosticWorkFence = DiagnosticWorkFence(),
    monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    schedule: (Long, () -> Unit) -> AutoCloseable = { _, _ -> AutoCloseable {} },
) : AutoCloseable {
    private class Frozen(val report: SaveEvaluationSessionReport, val payload: DiagnosticSnapshot)
    @Volatile private var frozen: Frozen? = null
    @Volatile private var closed = false
    val controller = DiagnosticExportController(
        read = { frozen?.let { DiagnosticSnapshotResult.Ready(it.payload) } ?: DiagnosticSnapshotResult.Failed },
        worker = worker, dispatch = { action ->
            val notificationOwner = frozen
            dispatch { if (frozen === notificationOwner) action() }
        }, changed = { state ->
            changed(if (!state.busy && frozen?.report !== currentReport()) DiagnosticExportState() else state)
        }, choose = choose, dismiss = dismiss,
        fence = fence, monotonicMillis = monotonicMillis, schedule = schedule,
        stillCurrent = { enabled && !closed && frozen?.let { currentReport() === it.report } == true },
    )
    @Synchronized fun begin(report: SaveEvaluationSessionReport?): Boolean {
        if (!enabled || closed || controller.state.busy || report == null ||
            report.state != SaveEvaluationSessionState.FINISHED || currentReport() !== report) return false
        val payload = try { DiagnosticSnapshot(DiscoverySaveEvaluationJson.export(report), DiscoverySaveEvaluationJson.MAX_EXPORT_BYTES) }
            catch (_: Exception) { return false }
        frozen = Frozen(report, payload)
        return controller.tryBegin()
    }
    fun reportChanged() {
        controller.cancel()
        changed(if (controller.state.busy) controller.state else DiagnosticExportState())
    }
    override fun close() { closed = true; controller.close(); frozen = null }
}

/** Android must open non-truncating, regular, append-only descriptors. Null size is never zero. */
internal interface EvaluationAppendDocument {
    fun size(): Long?
    fun append(bytes: ByteArray, offset: Int, length: Int)
    fun flush()
    fun close()
}
internal class EvaluationEmptyDocumentPort(private val openDocument: () -> EvaluationAppendDocument) : DiagnosticExportPort {
    override fun open(): OutputStream {
        val doc = openDocument()
        return object : OutputStream() {
            private var count = 0L
            private var closed = false
            private var verified = false
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                check(!closed && length >= 0 && count + length <= DiscoverySaveEvaluationJson.MAX_EXPORT_BYTES)
                check(doc.size() == count) // Same descriptor, never a stale URI metadata size.
                doc.append(bytes, offset, length) // OS append semantics: a racing writer cannot be overwritten.
                count += length
                check(doc.size() == count) // A concurrent extent change is failure, not successful JSON.
            }
            override fun flush() { check(!closed);doc.flush();check(doc.size() == count); verified = true }
            override fun close() {
                if (closed) return
                closed = true
                val unchanged = !verified || try { doc.size() == count } catch (_: Exception) { false }
                doc.close() // A throw here means release is unconfirmed, not merely invalid content.
                if (!unchanged) throw ExportContentChangedAfterClose()
            }
        }
    }
}
