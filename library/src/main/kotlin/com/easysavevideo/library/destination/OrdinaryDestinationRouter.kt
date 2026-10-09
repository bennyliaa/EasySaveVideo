package com.easysavevideo.library.destination

import com.easysavevideo.library.export.ExportPhase
import com.easysavevideo.library.export.ExportRecord
import com.easysavevideo.library.export.ExportRequest

interface OrdinaryDestinationCopyPort {
    fun existing(operationId: String): ExportRecord?
    /** Same application-wide ExportCoordinator as explicit ordinary copies. Never creates new IDs. */
    fun run(request: ExportRequest): ExportRecord
}

/** Durable publication outbox. A failure may mean the export record exists; the next pass replays
 * only that same identity. Uncertain/terminal phases are observations for explicit recovery, never
 * reasons to create another copy. This class has no settings, catalog search or deletion access. */
class OrdinaryDestinationRouter(private val journal: OrdinaryDestinationJournal, private val copies: OrdinaryDestinationCopyPort) {
    fun route(limit: Int): List<ExportRecord> {
        require(limit in 1..OrdinaryDestinationLimits.MAX_BATCH)
        val batch = journal.pendingCopy(limit)
        require(batch.size <= limit && batch.map { it.creationId }.distinct().size == batch.size)
        val completed = mutableListOf<ExportRecord>()
        var firstFailure: Exception? = null
        batch.forEach { admission ->
            try {
            if (journal.get(admission.creationId) != admission) throw OrdinaryDestinationFailure(OrdinaryDestinationError.SOURCE_MISMATCH)
            val request = admission.exportRequest() ?: throw OrdinaryDestinationFailure(OrdinaryDestinationError.INVALID_RECORD)
            val found = copies.existing(request.operationId)
            if (found != null && found.request != request) throw OrdinaryDestinationFailure(OrdinaryDestinationError.SOURCE_MISMATCH)
            val result = if (found != null && found.phase != ExportPhase.PREPARED) found else copies.run(request)
            admission.copyStatus(result) // Verify exact returned request, not just its phase.
            completed += result
            } catch (failure: Exception) { if (firstFailure == null) firstFailure = failure }
        }
        // One unavailable source/provider must not starve the rest of a bounded batch.
        firstFailure?.let { throw it }
        return completed
    }
}
