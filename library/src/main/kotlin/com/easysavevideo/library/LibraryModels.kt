package com.easysavevideo.library

object LibraryLimits {
    const val MAX_NAME_UTF8_BYTES = 255
    const val MAX_SEARCH_UTF8_BYTES = 512
    const val MAX_PAGE_SIZE = 100
    const val MAX_FOLDERS = 1_000
    const val MAX_FOLDER_DEPTH = 16
    const val MAX_DELETE_BATCH = 100
}

enum class LibrarySort { NAME, DATE, DURATION, SIZE }
enum class SortDirection { ASCENDING, DESCENDING }
enum class MediaDateKind { SAVED, IMPORTED }
enum class MediaLifecycle { READY, DELETE_PENDING, DELETED }
enum class DeleteOutcome { DELETE_PENDING, DELETED }
enum class LibraryError {
    INVALID_ID, INVALID_NAME, INVALID_QUERY, NOT_FOUND, STALE_REVISION, STALE_PAGE,
    ALREADY_EXISTS, FOLDER_NOT_EMPTY, FOLDER_CYCLE, DEPTH_LIMIT, FOLDER_LIMIT,
    CONFIRMATION_REQUIRED, BUSY, STORAGE_FAILURE, RESOURCE_RELEASE_UNCONFIRMED,
    IDEMPOTENCY_CONFLICT, RECOVERY_REQUIRED,
}

/** A bounded technical code only; never include names, filters, locators or nested exception text. */
class LibraryFailure(val code: LibraryError) : RuntimeException(code.name)

/** Typed edit input identities. A private recording segment is never a catalog item. */
sealed interface EditInputRef

data class RecordedSegmentReadRef(val sessionId: String, val segmentId: String, val generation: Long, val sequence: Int) : EditInputRef {
    init { LibraryIds.requireValid(sessionId); LibraryIds.requireValid(segmentId); require(generation > 0 && sequence in 0..32) }
    override fun toString() = "RecordedSegmentReadRef([redacted])"
}

data class MediaReadRef(val itemId: String, val revision: Long, val representationGeneration: Long) : EditInputRef {
    init { LibraryIds.requireValid(itemId); require(revision > 0 && representationGeneration > 0) }
    override fun toString() = "MediaReadRef([redacted])"
}

data class MediaItem(
    val itemId: String,
    val revision: Long,
    val representationGeneration: Long,
    val folderId: String?,
    val displayName: String,
    val createdAtMillis: Long,
    val dateKind: MediaDateKind,
    val sizeBytes: Long,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val lifecycle: MediaLifecycle = MediaLifecycle.READY,
) {
    init {
        LibraryIds.requireValid(itemId); folderId?.let(LibraryIds::requireValid)
        require(revision > 0 && representationGeneration > 0 && createdAtMillis >= 0 && sizeBytes >= 0)
        if (displayName != LibraryNames.validate(displayName)) throw LibraryFailure(LibraryError.INVALID_NAME)
        require(durationMillis == null || durationMillis >= 0)
        require(width == null || width > 0); require(height == null || height > 0)
        require((width == null) == (height == null))
    }
    fun readRef(): MediaReadRef {
        if (lifecycle != MediaLifecycle.READY) throw LibraryFailure(LibraryError.NOT_FOUND)
        return MediaReadRef(itemId, revision, representationGeneration)
    }
    override fun toString() = "MediaItem([redacted], lifecycle=$lifecycle)"
}

data class LibraryFolder(
    val folderId: String,
    val revision: Long,
    val parentId: String?,
    val displayName: String,
    val itemCount: Int = 0,
    val childFolderCount: Int = 0,
) {
    init {
        LibraryIds.requireValid(folderId); parentId?.let(LibraryIds::requireValid)
        require(revision > 0 && itemCount >= 0 && childFolderCount >= 0)
        if (displayName != LibraryNames.validate(displayName)) throw LibraryFailure(LibraryError.INVALID_NAME)
    }
    override fun toString() = "LibraryFolder([redacted])"
}

/** A continuation is query-bound and only valid at its exact catalog version. */
data class LibraryCursor(val catalogVersion: Long, val queryKey: String, val offset: Int) {
    init {
        require(catalogVersion >= 0 && offset >= 0)
        require(queryKey.matches(Regex("[a-f0-9]{64}")))
    }
    override fun toString() = "LibraryCursor([redacted])"
}

data class LibraryQuery(
    val folderId: String? = null,
    val text: String = "",
    val sort: LibrarySort = LibrarySort.DATE,
    val direction: SortDirection = SortDirection.DESCENDING,
    val pageSize: Int = 50,
    val cursor: LibraryCursor? = null,
) {
    init {
        folderId?.let(LibraryIds::requireValid)
        if (pageSize !in 1..LibraryLimits.MAX_PAGE_SIZE) throw LibraryFailure(LibraryError.INVALID_QUERY)
        LibraryNames.searchKey(text)
    }
    fun queryKey(): String = LibraryQueryPolicy.fingerprint(this)
    override fun toString() = "LibraryQuery([redacted])"
}

data class LibraryPage(val items: List<MediaItem>, val catalogVersion: Long, val nextCursor: LibraryCursor?) {
    init {
        require(catalogVersion >= 0 && items.size <= LibraryLimits.MAX_PAGE_SIZE)
        require(items.all { it.lifecycle == MediaLifecycle.READY })
        require(nextCursor == null || nextCursor.catalogVersion == catalogVersion)
    }
    override fun toString() = "LibraryPage(count=${items.size}, catalogVersion=$catalogVersion)"
}

/** UI must display the local deletion consequence and supply the exact confirmed item version. */
data class ConfirmedDeleteRequest(val ref: MediaReadRef, val operationId: String, val confirmed: Boolean) {
    init { LibraryIds.requireValid(operationId) }
    fun requireConfirmation() { if (!confirmed) throw LibraryFailure(LibraryError.CONFIRMATION_REQUIRED) }
    override fun toString() = "ConfirmedDeleteRequest([redacted])"
}

data class PendingDeleteReport(val completed: Int, val pending: Int, val failed: Int) {
    init { require(completed >= 0 && pending >= 0 && failed >= 0) }
}
