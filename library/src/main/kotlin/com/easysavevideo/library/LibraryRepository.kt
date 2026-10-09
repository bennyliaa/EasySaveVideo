package com.easysavevideo.library

/**
 * Synchronous ordinary-library port. Invoke storage work off the UI thread. Implementations own
 * atomic CAS mutations and durable operation-id replay protection. Never resolve caller paths.
 * Successful queries include READY items only. A catalog change invalidates an existing cursor.
 */
interface LibraryRepository {
    fun query(query: LibraryQuery): LibraryPage
    /** Lifecycle-aware lookup; DELETE_PENDING and DELETED are never playable. */
    fun item(itemId: String): MediaItem?
    fun folder(folderId: String): LibraryFolder?
    /** Direct children only. The adapter must enforce [LibraryLimits.MAX_FOLDERS] globally. */
    fun folders(parentId: String? = null): List<LibraryFolder>
    fun createFolder(displayName: String, parentId: String?, operationId: String): LibraryFolder
    fun renameFolder(folderId: String, expectedRevision: Long, displayName: String, operationId: String): LibraryFolder
    fun moveFolder(folderId: String, expectedRevision: Long, parentId: String?, operationId: String): LibraryFolder
    fun deleteEmptyFolder(folderId: String, expectedRevision: Long, operationId: String)
    fun renameItem(itemId: String, expectedRevision: Long, displayName: String, operationId: String): MediaItem
    fun moveItem(itemId: String, expectedRevision: Long, folderId: String?, operationId: String): MediaItem
    /** Persist the intent and remove visibility before unlink; readers keep their lease until closed. */
    fun requestDelete(request: ConfirmedDeleteRequest): DeleteOutcome
    /** Retry only durable delete intents; never unlink an item while any reader/writer owns it. */
    fun processPendingDeletes(limit: Int = 100): PendingDeleteReport
}
