package com.easysavevideo.app.library.export

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.app.library.destination.AndroidFolderGrantRegistry
import com.easysavevideo.library.export.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Android capabilities are issued by the picker/owned MediaStore insert, never accepted as paths. */
internal class AndroidExportPort(
    private val context: Context,
    private val storage: AndroidSaveStorage,
    private val journal: AndroidExportJournal,
    onFolderInvalidated: () -> Unit = {},
) : ExportPort {
    private val resolver = context.contentResolver
    private val folders = AndroidFolderGrantRegistry(context, storage, onFolderInvalidated)
    private val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val lock = Any()
    private val liveSaf = linkedMapOf<String, Uri>()
    private val handles = mutableMapOf<String, MutableSet<Any>>()
    private val uncertainSources = mutableMapOf<String, Any>()
    private val relativePath = Environment.DIRECTORY_MOVIES + "/EasySaveVideo/"

    /** Only runtime's single outstanding CREATE_DOCUMENT result may call this. No persistent grant. */
    fun acceptSafResult(request: ExportRequest, uri: Uri, resultFlags: Int) {
        require(request.destination.kind == ExportKind.SAF)
        val flags = resultFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(uri.scheme == "content" && uri.authority?.isNotBlank() == true && uri.fragment == null && uri.toString().toByteArray(Charsets.UTF_8).size <= 8192)
        require(DocumentsContract.isDocumentUri(context, uri) && flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        require(context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED)
        journal.bindSafOutput(request, uri.toString(), flags)
        synchronized(lock) {
            // Only a bounded recent set of temporary picker capabilities is kept in memory.
            // Older durable rows stay visible for manual provider checks; authority is not rebuilt.
            while (liveSaf.size >= ExportLimits.MAX_ACTIVE && request.operationId !in liveSaf) liveSaf.remove(liveSaf.keys.first())
            liveSaf[request.operationId] = uri
        }
    }
    /** Package metadata only; no document read, URI/path disclosure, or grant restoration. */
    fun destinationLabel(operationId: String): String {
        val target = journal.target(operationId) ?: return "所选文件提供方"
        val authority = target.uri?.let { Uri.parse(it).authority } ?: return "所选文件提供方"
        val fallback = safeExportLabel(authority, 80).ifBlank { "所选文件提供方" }
        return try {
            val provider = context.packageManager.resolveContentProvider(authority, 0)
            provider?.loadLabel(context.packageManager)?.let { safeExportLabel(it, 80) }?.takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) { fallback }
    }
    fun releaseFinishedGrant(record: ExportRecord) {
        if (record.phase == ExportPhase.SUCCEEDED || record.cleanup == ExportCleanup.REMOVED ||
            (record.phase.terminal && record.output == null)) synchronized(lock) { liveSaf.remove(record.operationId) }
    }
    fun hasLiveSafGrant(operationId: String): Boolean = try {
        val target = journal.target(operationId)
        target != null && target.kind == ExportKind.SAF && requireSaf(target).scheme == "content"
    } catch (_: Exception) { false }
    override fun openSource(request: ExportRequest): ExportSource {
        folderId(request.operationId)?.let { folders.requireTree(it) }
        val opened = storage.openLibraryMedia(request.source)
        val owner = object : ExportInput {
            override val stream = opened.stream
            override fun close(): ExportRelease {
                try { opened.close() } catch (_: Exception) { }
                val released = try { !opened.stream.fd.valid() } catch (_: Exception) { false }
                synchronized(lock) {
                    if (released) uncertainSources.remove(request.operationId) else uncertainSources[request.operationId] = opened
                }
                return if (released) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
            }
        }
        return try { ExportSource(request.source, ExportEvidence(opened.sizeBytes, opened.sha256), owner) }
        catch (failure: Exception) { owner.close(); throw failure }
    }
    override fun create(record: ExportRecord): ExportOutput {
        require(record.phase == ExportPhase.CREATE_INTENT && !record.cancelRequested)
        val target = journal.reserve(record.request)
        val output = ExportOutput(target.operationId, target.kind)
        if (target.kind == ExportKind.SAF) {
            val folder = folderId(record.operationId)
            if (folder == null) requireSaf(target) else {
                // The coordinator durably wrote CREATE_INTENT before this call. A bound child is
                // never recreated or reopened for writing by replay, even if an earlier call failed.
                require(target.uri == null)
                val tree = folders.requireTree(folder) // Revalidate actual persisted permission before every create.
                val child = DocumentsContract.createDocument(resolver, folders.parent(tree), "video/mp4", fileName(target))
                    ?: throw IOException("EXPORT_CREATE_UNCERTAIN")
                folders.requireChild(folder, child)
                // Exact returned identity must be durable before any bytes are written. If bind
                // fails, recovery reports Unknown; it never searches a name, guesses or recreates.
                journal.bind(target.operationId, target.grantId, child.toString(), AndroidFolderGrantRegistry.READ_WRITE)
                requireSaf(requireNotNull(journal.target(target.operationId)))
            }
            return output
        }
        require(target.kind == ExportKind.MEDIA_STORE)
        // An existing destination is never overwritten or recreated for the same operation.
        if (target.uri != null) {
            require(media(target).let { it is MediaLookup.Owned && it.pending })
            return output
        }
        when (val prior = media(target)) {
            is MediaLookup.Owned -> {
                require(prior.pending)
                journal.bind(target.operationId, target.grantId, prior.uri.toString(), 0)
                return output
            }
            MediaLookup.Unknown -> throw IOException("EXPORT_OWNERSHIP_UNCERTAIN")
            MediaLookup.Absent -> Unit
        }
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName(target))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }) ?: throw IOException("EXPORT_CREATE_UNCERTAIN")
        require(validMediaUri(uri))
        // Durable identity before any write. If this fails, exact nonce + package reconciliation
        // resolves the insert/record crash gap; it never guesses from an absent in-memory URI.
        journal.bind(target.operationId, target.grantId, uri.toString(), 0)
        require(media(requireNotNull(journal.target(target.operationId))).let { it is MediaLookup.Owned && it.pending })
        return output
    }
    override fun openWrite(record: ExportRecord, output: ExportOutput): ExportWrite {
        val target = target(record, output)
        val uri = if (target.kind == ExportKind.MEDIA_STORE) {
            val found = media(target)
            require(found is MediaLookup.Owned && found.pending); found.uri
        } else requireSaf(target)
        val descriptor = resolver.openFileDescriptor(uri, "wt") ?: throw IOException("EXPORT_OPEN_FAILED")
        val outputStream = try { FileOutputStream(descriptor.fileDescriptor) } catch (failure: Exception) {
            retainOnFailedClose(record.operationId, descriptor); throw failure
        }
        val owner = object : ExportWrite {
            override val stream = outputStream
            override fun close(): ExportRelease {
                var syncFailed = false
                try {
                    outputStream.flush()
                    // Pipes/providers need not support fsync. Full readback is separate proof.
                    if (OsConstants.S_ISREG(Os.fstat(outputStream.fd).st_mode)) outputStream.fd.sync()
                } catch (_: Exception) { syncFailed = true }
                try { outputStream.close() } catch (_: Exception) { }
                try { descriptor.close() } catch (_: Exception) { }
                val released = try { !outputStream.fd.valid() && !descriptor.fileDescriptor.valid() } catch (_: Exception) { false }
                if (released) release(record.operationId, this)
                if (syncFailed) throw IOException("EXPORT_FLUSH_FAILED")
                return if (released) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
            }
        }
        retain(record.operationId, owner)
        return owner
    }
    override fun openReadback(record: ExportRecord, output: ExportOutput): ExportReadback {
        val target = target(record, output)
        val uri = if (target.kind == ExportKind.MEDIA_STORE) {
            val found = media(target); require(found is MediaLookup.Owned); found.uri
        } else {
            val selected = requireSaf(target)
            if (target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 ||
                context.checkUriPermission(selected, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)
                return ExportReadback.Unsupported
            selected
        }
        val descriptor = try { resolver.openFileDescriptor(uri, "r") }
        catch (unsupported: UnsupportedOperationException) { if (target.kind == ExportKind.SAF) return ExportReadback.Unsupported else throw unsupported }
        // File-not-found, permission loss, transient IO or corrupt bytes are failures, not Unsupported.
            ?: throw IOException("EXPORT_READBACK_FAILED")
        val input = try { FileInputStream(descriptor.fileDescriptor) } catch (failure: Exception) {
            retainOnFailedClose(record.operationId, descriptor); throw failure
        }
        val owner = object : ExportInput {
            override val stream = input
            override fun close(): ExportRelease {
                try { input.close() } catch (_: Exception) { }
                try { descriptor.close() } catch (_: Exception) { }
                val released = try { !input.fd.valid() && !descriptor.fileDescriptor.valid() } catch (_: Exception) { false }
                if (released) release(record.operationId, this)
                return if (released) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
            }
        }
        retain(record.operationId, owner)
        return ExportReadback.Opened(owner)
    }
    override fun publish(record: ExportRecord, output: ExportOutput) {
        require(record.phase == ExportPhase.PUBLISH_INTENT && record.verification == ExportVerification.DESTINATION_VERIFIED && !record.cancelRequested)
        require(released(record.operationId))
        val target = target(record, output)
        if (target.kind == ExportKind.SAF) { requireSaf(target); return }
        val found = media(target); require(found is MediaLookup.Owned)
        if (!found.pending) return
        val changed = resolver.update(found.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
            "${MediaStore.Video.Media.IS_PENDING}=? AND ${MediaStore.Video.Media.OWNER_PACKAGE_NAME}=?", arrayOf("1", context.packageName))
        require(changed == 1)
        require(media(target).let { it is MediaLookup.Owned && !it.pending })
    }
    override fun cleanup(record: ExportRecord, output: ExportOutput): ExportCleanup {
        if (!released(record.operationId)) return ExportCleanup.UNCONFIRMED
        val target = target(record, output)
        if (target.kind == ExportKind.MEDIA_STORE) return when (val found = media(target)) {
            MediaLookup.Absent -> ExportCleanup.REMOVED
            MediaLookup.Unknown -> ExportCleanup.UNCONFIRMED
            is MediaLookup.Owned -> {
                if (!found.pending) ExportCleanup.UNCONFIRMED else {
                    // Provider state guard prevents deleting a row already published by another path.
                    resolver.delete(found.uri, "${MediaStore.Video.Media.IS_PENDING}=? AND ${MediaStore.Video.Media.OWNER_PACKAGE_NAME}=?", arrayOf("1", context.packageName))
                    if (media(target) == MediaLookup.Absent) ExportCleanup.REMOVED else ExportCleanup.PENDING
                }
            }
        }
        val uri = try { requireSaf(target) } catch (_: Exception) { return ExportCleanup.UNCONFIRMED }
        return try {
            if (!DocumentsContract.deleteDocument(resolver, uri)) ExportCleanup.PENDING
            else if (safAbsent(uri)) { synchronized(lock) { liveSaf.remove(record.operationId) }; ExportCleanup.REMOVED }
            else ExportCleanup.PENDING
        } catch (_: Exception) { ExportCleanup.FAILED }
    }
    override fun reconcile(record: ExportRecord): ExportReconciliation {
        val target = journal.target(record.operationId)
        if (target == null) return if (record.request.destination.kind == ExportKind.MEDIA_STORE && released(record.operationId)) ExportReconciliation.Absent else ExportReconciliation.Unknown
        require(target.kind == record.request.destination.kind && target.grantId == record.request.destination.grantId)
        if (target.kind == ExportKind.MEDIA_STORE) return when (val found = media(target)) {
            MediaLookup.Absent -> if (released(record.operationId)) ExportReconciliation.Absent else ExportReconciliation.Unknown
            MediaLookup.Unknown -> ExportReconciliation.Unknown
            is MediaLookup.Owned -> {
                if (target.uri == null) journal.bind(target.operationId, target.grantId, found.uri.toString(), 0)
                ExportReconciliation.Owned(ExportOutput(record.operationId, target.kind), !found.pending, released(record.operationId))
            }
        }
        // Ephemeral document grants are never reconstructed. Remembered-folder work separately
        // revalidates the exact Android persisted tree grant; URI text alone is never authority.
        val uri = try { requireSaf(target) } catch (_: Exception) { return ExportReconciliation.Unknown }
        if (safAbsent(uri)) return if (released(record.operationId)) ExportReconciliation.Absent else ExportReconciliation.Unknown
        return ExportReconciliation.Owned(ExportOutput(record.operationId, target.kind), false, released(record.operationId))
    }
    private fun target(record: ExportRecord, output: ExportOutput): AndroidExportJournal.Target {
        require(output.outputId == record.operationId && output.kind == record.request.destination.kind)
        return requireNotNull(journal.target(record.operationId)).also {
            require(it.kind == output.kind && it.grantId == record.request.destination.grantId)
        }
    }
    private fun requireSaf(target: AndroidExportJournal.Target): Uri {
        require(target.kind == ExportKind.SAF && target.uri != null)
        folderId(target.operationId)?.let { return folders.requireChild(it, Uri.parse(target.uri)) }
        val uri = synchronized(lock) { liveSaf[target.operationId] } ?: throw IOException("SAF_GRANT_UNAVAILABLE")
        require(uri.toString() == target.uri)
        require(context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED)
        return uri
    }
    private fun folderId(operationId: String): String? = storage.destinations.folderGrantForOperation(operationId)
    private fun safAbsent(uri: Uri): Boolean = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { !it.moveToFirst() } ?: false
    } catch (_: java.io.FileNotFoundException) { true } catch (_: Exception) { false }
    private sealed interface MediaLookup {
        data object Absent : MediaLookup
        data object Unknown : MediaLookup
        data class Owned(val uri: Uri, val pending: Boolean) : MediaLookup
    }
    private fun media(target: AndroidExportJournal.Target): MediaLookup {
        if (target.kind != ExportKind.MEDIA_STORE) return MediaLookup.Unknown
        val bound = target.uri?.let(Uri::parse)
        if (bound != null && !validMediaUri(bound)) return MediaLookup.Unknown
        val queryUri = bound ?: collection
        return try {
            resolver.query(queryUri, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.OWNER_PACKAGE_NAME, MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.MIME_TYPE),
                Bundle().apply {
                    // Android collection queries otherwise exclude pending rows, hiding the
                    // exact insert-to-journal crash-gap output from recovery.
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, 2)
                    if (bound == null) {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.RELATIVE_PATH}=?")
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(fileName(target), relativePath))
                    }
                }, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use MediaLookup.Absent
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                val valid = cursor.getString(1) == fileName(target) && cursor.getString(2) == relativePath &&
                    !cursor.isNull(3) && cursor.getString(3) == context.packageName && cursor.getInt(4) in 0..1 && cursor.getString(5) == "video/mp4" &&
                    (bound == null || bound == uri)
                val pending = cursor.getInt(4) == 1
                if (!valid || cursor.moveToNext()) MediaLookup.Unknown else MediaLookup.Owned(uri, pending)
            } ?: MediaLookup.Unknown
        } catch (_: Exception) { MediaLookup.Unknown }
    }
    private fun fileName(target: AndroidExportJournal.Target) = "ESV-${target.nonce}.mp4"
    private fun validMediaUri(uri: Uri): Boolean = try {
        uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY && uri.query == null && uri.fragment == null &&
            ContentUris.parseId(uri) > 0 && ContentUris.withAppendedId(collection, ContentUris.parseId(uri)) == uri
    } catch (_: Exception) { false }
    private fun retain(id: String, value: Any) { synchronized(lock) { handles.getOrPut(id) { mutableSetOf() }.add(value) } }
    private fun release(id: String, value: Any) { synchronized(lock) { handles[id]?.let { it.remove(value); if (it.isEmpty()) handles.remove(id) } } }
    private fun released(id: String): Boolean = synchronized(lock) { handles[id].isNullOrEmpty() && id !in uncertainSources }
    private fun retainOnFailedClose(id: String, descriptor: ParcelFileDescriptor) {
        try { descriptor.close() } catch (_: Exception) { }
        if (try { descriptor.fileDescriptor.valid() } catch (_: Exception) { true }) retain(id, descriptor)
    }
}
