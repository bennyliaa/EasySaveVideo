package com.easysavevideo.app.library.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** No broad root, path resolution, persisted grants, writable mode or original-media access. */
class LibraryShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    private fun owner() = LibraryShareOwner.get(requireNotNull(context))
    override fun getType(uri: Uri): String? = try { owner().metadata(uri)?.let { "video/mp4" } } catch (_: Exception) { null }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = readOnlyCall {
        if (projection != null && projection.size > 8) throw FileNotFoundException("INVALID_PROJECTION")
        val bytes = owner().metadata(uri) ?: throw FileNotFoundException("SHARE_UNAVAILABLE")
        val names = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        MatrixCursor(names, 1).apply { addRow(names.map { if (it == OpenableColumns.SIZE) bytes else "Shared video.mp4" }) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = readOnlyCall {
        if (!SharePolicy.readOnly(mode)) throw FileNotFoundException("READ_ONLY")
        owner().open(uri)
    }
    private inline fun <T> readOnlyCall(block: () -> T): T = try { block() }
        catch (_: Exception) { throw FileNotFoundException("SHARE_UNAVAILABLE") }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("READ_ONLY")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("READ_ONLY")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("READ_ONLY")
}
