package com.easysavevideo.app.library.destination

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import com.easysavevideo.app.save.storage.AndroidSaveStorage

/** Android alone holds capability authority. Private URI text and opaque IDs are only identities.
 * No grant is released automatically: another admitted operation or app owner may still need it. */
internal class AndroidFolderGrantRegistry(context: Context, private val storage: AndroidSaveStorage,
    private val onInvalidated: () -> Unit = {},
) {
    private val context = context.applicationContext
    private val resolver = context.contentResolver

    fun accept(uri: Uri, offeredFlags: Int, isCurrent: () -> Boolean): String {
        require(isCurrent())
        requireValidTree(uri)
        val flags = offeredFlags and READ_WRITE
        require(flags == READ_WRITE && offeredFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        requireGranted(uri, flags)
        // Android has no immutable grant-generation token. Observed loss irreversibly invalidates
        // every old registry generation BEFORE a new take can make the same URI accessible again.
        if (!hasPersisted(uri)) invalidate(uri.toString())
        if (!storage.folderGrants.availableCapacity(uri.toString(), flags)) throw FolderGrantCapacityFailure()
        // Reserve bounded private tracking before the Android side effect, including failed or
        // cancelled adoptions. This row is not a selected destination or Android authority.
        val row = storage.folderGrants.issue(uri.toString(), flags)
        require(isCurrent())
        // Only flags actually returned by this request may be persisted. No broadening.
        resolver.takePersistableUriPermission(uri, flags)
        requirePersisted(uri, flags)
        requireDirectory(uri)
        require(!requireNotNull(storage.folderGrants.get(row.id)).invalidated)
        return row.id
    }
    /** Forgetting retains old admitted work. Observed permission loss never does. */
    fun requireTree(id: String, forCreate: Boolean = true): Uri {
        val row = requireNotNull(storage.folderGrants.get(id))
        require(!row.invalidated)
        val uri = Uri.parse(row.uri)
        requireValidTree(uri)
        require(row.flags == READ_WRITE)
        if (!hasPersisted(uri) || !isGranted(uri, row.flags)) {
            invalidate(row.uri)
            error("FOLDER_GRANT_INVALIDATED")
        }
        if (forCreate) requireDirectory(uri)
        require(!requireNotNull(storage.folderGrants.get(id)).invalidated)
        return uri
    }
    fun requireChild(id: String, child: Uri): Uri {
        val tree = requireTree(id, forCreate = false)
        require(child.scheme == "content" && child.authority == tree.authority && child.query == null && child.fragment == null)
        require(child.toString().toByteArray(Charsets.UTF_8).size <= 8192 && DocumentsContract.isDocumentUri(context, child))
        require(DocumentsContract.isTreeUri(child) && DocumentsContract.getTreeDocumentId(child) == DocumentsContract.getTreeDocumentId(tree))
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        require(DocumentsContract.getDocumentId(child) != DocumentsContract.getTreeDocumentId(tree))
        require(DocumentsContract.isChildDocument(resolver, parent, child))
        requireGranted(child, READ_WRITE)
        return child
    }
    fun parent(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private fun requireValidTree(uri: Uri) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.query == null && uri.fragment == null)
        require(uri.toString().toByteArray(Charsets.UTF_8).size in 1..8192 && DocumentsContract.isTreeUri(uri))
        require(DocumentsContract.getTreeDocumentId(uri).isNotEmpty())
        // Persist only the exact root tree token, not an arbitrary nested document locator.
        require(uri == DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri)))
    }
    private fun requirePersisted(uri: Uri, flags: Int) {
        require(flags == READ_WRITE && hasPersisted(uri))
    }
    private fun invalidate(uri: String) {
        storage.folderGrants.invalidateUri(uri)
        onInvalidated()
    }
    private fun hasPersisted(uri: Uri): Boolean = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    private fun isGranted(uri: Uri, flags: Int): Boolean =
        context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flags) == PackageManager.PERMISSION_GRANTED
    private fun requireGranted(uri: Uri, flags: Int) { require(isGranted(uri, flags)) }
    private fun requireDirectory(tree: Uri) {
        resolver.query(parent(tree), arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            require(it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR)
            require(it.getLong(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L)
            require(!it.moveToNext())
        } ?: error("FOLDER_UNAVAILABLE")
    }
    companion object { const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION }
}

internal class FolderGrantCapacityFailure : RuntimeException("FOLDER_GRANT_CAPACITY")
