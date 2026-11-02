package com.easysavevideo.library.destination

import com.easysavevideo.library.LibraryIds
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportDestination
import com.easysavevideo.library.export.ExportKind
import com.easysavevideo.library.export.ExportPhase
import com.easysavevideo.library.export.ExportRecord
import com.easysavevideo.library.export.ExportRequest

enum class OrdinaryDestinationPreference { APP_LIBRARY, ASK, ALBUM_COPY, FOLDER_COPY }
enum class OrdinaryDestinationChoice { APP_LIBRARY, ALBUM_COPY, FOLDER_COPY }
enum class OrdinaryPrivateStatus { AWAITING_PUBLICATION, PUBLISHED }
enum class OrdinaryCopyStatus { NOT_REQUESTED, AWAITING_PRIVATE, PENDING, COPYING, SUCCEEDED, FAILED, CANCELLED, RECONCILIATION_REQUIRED }
enum class OrdinaryDestinationError { INVALID_RECORD, IDEMPOTENCY_CONFLICT, SOURCE_MISMATCH, ALREADY_CREATED, STORAGE_FAILURE }
class OrdinaryDestinationFailure(val code: OrdinaryDestinationError) : RuntimeException(code.name)

/** A resolved per-creation snapshot. It contains no mutable settings or caller-selected source. */
data class OrdinaryDestinationAdmission(
    val creationId: String,
    val choice: OrdinaryDestinationChoice,
    val operationId: String,
    val grantId: String,
    val source: MediaReadRef? = null,
    val folderGrantId: String? = null,
) {
    init {
        LibraryIds.requireValid(creationId); LibraryIds.requireValid(operationId); LibraryIds.requireValid(grantId)
        require((choice == OrdinaryDestinationChoice.FOLDER_COPY) == (folderGrantId != null))
        folderGrantId?.let { LibraryIds.requireValid(it); require(it != grantId) }
    }
    val privateStatus: OrdinaryPrivateStatus get() = if (source == null) OrdinaryPrivateStatus.AWAITING_PUBLICATION else OrdinaryPrivateStatus.PUBLISHED

    /** Called only from verified private publication, within its visibility transaction. */
    fun bindVerifiedSource(exactSource: MediaReadRef): OrdinaryDestinationAdmission {
        if (source != null && source != exactSource) throw OrdinaryDestinationFailure(OrdinaryDestinationError.SOURCE_MISMATCH)
        return if (source == exactSource) this else copy(source = exactSource)
    }

    fun exportRequest(): ExportRequest? {
        val bound = source ?: return null
        val kind = when (choice) {
            OrdinaryDestinationChoice.APP_LIBRARY -> return null
            OrdinaryDestinationChoice.ALBUM_COPY -> ExportKind.MEDIA_STORE
            OrdinaryDestinationChoice.FOLDER_COPY -> ExportKind.SAF
        }
        // The durable folder identifies a selection. Every export retains its own separate grant.
        return ExportRequest(operationId, bound, ExportDestination(kind, grantId))
    }

    /** External outcome is derived from the existing export journal, never duplicated here. */
    fun copyStatus(export: ExportRecord?): OrdinaryCopyStatus {
        val request = exportRequest()
        if (export != null && export.request != request) throw OrdinaryDestinationFailure(OrdinaryDestinationError.SOURCE_MISMATCH)
        if (choice == OrdinaryDestinationChoice.APP_LIBRARY) return OrdinaryCopyStatus.NOT_REQUESTED
        if (source == null) return OrdinaryCopyStatus.AWAITING_PRIVATE
        return when (export?.phase) {
            null, ExportPhase.PREPARED -> OrdinaryCopyStatus.PENDING
            ExportPhase.CREATE_INTENT, ExportPhase.COPYING, ExportPhase.VERIFYING, ExportPhase.PUBLISH_INTENT -> OrdinaryCopyStatus.COPYING
            ExportPhase.SUCCEEDED -> OrdinaryCopyStatus.SUCCEEDED
            ExportPhase.FAILED -> OrdinaryCopyStatus.FAILED
            ExportPhase.CANCELLED -> OrdinaryCopyStatus.CANCELLED
            ExportPhase.COPIED_UNVERIFIED, ExportPhase.CLEANUP_PENDING, ExportPhase.RECONCILIATION_REQUIRED -> OrdinaryCopyStatus.RECONCILIATION_REQUIRED
        }
    }
    override fun toString() = "OrdinaryDestinationAdmission(choice=$choice, privateStatus=$privateStatus, [redacted])"
}

/** One atomic settings read. A non-folder preference may retain a remembered folder selection. */
data class OrdinaryDestinationSelectionSnapshot(
    val preference: OrdinaryDestinationPreference,
    val folderGrantId: String? = null,
    val revision: Long = 0,
) {
    init {
        require(revision >= 0)
        folderGrantId?.let(LibraryIds::requireValid)
        require(preference != OrdinaryDestinationPreference.FOLDER_COPY || folderGrantId != null)
    }
    override fun toString() = "OrdinaryDestinationSelectionSnapshot(preference=$preference, [redacted])"
}

/** IDs and choices remain retained; neither changed preferences nor recovery creates admissions. */
interface OrdinaryDestinationJournal {
    fun preference(): OrdinaryDestinationPreference
    fun setPreference(value: OrdinaryDestinationPreference)
    fun selection(): OrdinaryDestinationSelectionSnapshot = OrdinaryDestinationSelectionSnapshot(preference())
    fun selectedFolderId(): String? = selection().folderGrantId
    fun setFolderPreference(folderGrantId: String): Unit = throw OrdinaryDestinationFailure(OrdinaryDestinationError.INVALID_RECORD)
    fun admit(creationId: String, choice: OrdinaryDestinationChoice): OrdinaryDestinationAdmission
    /** Compatibility overload. Folder-aware journals override this; old journals fail closed. */
    fun admit(creationId: String, choice: OrdinaryDestinationChoice, folderGrantId: String?): OrdinaryDestinationAdmission {
        if (folderGrantId != null || choice == OrdinaryDestinationChoice.FOLDER_COPY)
            throw OrdinaryDestinationFailure(OrdinaryDestinationError.INVALID_RECORD)
        return admit(creationId, choice)
    }
    fun get(creationId: String): OrdinaryDestinationAdmission?
    /** Link a recorded session's already-admitted choice before the processing edit is created. */
    fun linkPublication(publicationId: String, creationId: String)
    /** Bounded, fair scan: bound external copies with no export yet, or an existing PREPARED export. */
    fun pendingCopy(limit: Int): List<OrdinaryDestinationAdmission>
    fun recent(limit: Int): List<OrdinaryDestinationAdmission>
}

object OrdinaryDestinationLimits { const val MAX_BATCH = 32 }
