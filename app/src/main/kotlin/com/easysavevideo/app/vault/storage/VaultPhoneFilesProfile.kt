package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.VaultCopyBatchLimits

/** Finite platform-fact predicate. These DTOs are not capabilities and authorize no IO. */
internal object VaultPhoneFilesProfile {
    const val AUTHORITY = "com.android.externalstorage.documents"
    const val PACKAGE = "com.android.externalstorage"
    const val CLASS = "com.android.externalstorage.ExternalStorageProvider"
    const val MANAGE_DOCUMENTS = "android.permission.MANAGE_DOCUMENTS"
    const val DIRECTORY_MIME = "vnd.android.document/directory"
    const val MAX_ID_BYTES = 8192
    data class Provider(val authority: String?, val packageName: String?, val className: String?,
        val system: Boolean, val androidSignature: Boolean, val enabled: Boolean,
        val exported: Boolean, val grantUriPermissions: Boolean, val readPermission: String?, val writePermission: String?)
    data class Document(val id: String, val mime: String?, val virtual: Boolean, val directoryCreate: Boolean,
        val supportsWrite: Boolean = false, val supportsDelete: Boolean = false) {
        override fun toString() = "PhoneDocument(REDACTED)"
    }
    fun acceptsProvider(provider: Provider): Boolean =
        provider.authority == AUTHORITY && provider.packageName == PACKAGE && provider.className == CLASS &&
            provider.system && provider.androidSignature && provider.enabled && provider.exported && provider.grantUriPermissions &&
            provider.readPermission == MANAGE_DOCUMENTS && provider.writePermission == MANAGE_DOCUMENTS
    fun primaryId(id: String): Boolean = id.startsWith("primary:") && id.length > "primary:".length &&
        id.toByteArray(Charsets.UTF_8).size <= MAX_ID_BYTES &&
        id.removePrefix("primary:").split('/').all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it && '\u0000' !in it }
    fun accepts(provider: Provider, documentId: String, mime: String?, virtual: Boolean): Boolean =
        acceptsProvider(provider) && primaryId(documentId) && mime == "video/mp4" && !virtual
    fun acceptsDirectory(provider: Provider, document: Document): Boolean = acceptsProvider(provider) &&
        primaryId(document.id) && document.mime == DIRECTORY_MIME && !document.virtual && document.directoryCreate
    fun directChild(parent: String, child: String): Boolean = primaryId(parent) && primaryId(child) &&
        child.startsWith("$parent/") && '/' !in child.removePrefix("$parent/")
    /** Complete immediate-child snapshot; duplicate, partial and over-budget snapshots fail closed. */
    fun boundedChildren(parent: String, ids: List<String>): Set<String> {
        require(ids.size <= VaultCopyBatchLimits.MAX_DIRECTORY_ENTRIES)
        var bytes = 0L
        val result = LinkedHashSet<String>()
        ids.forEach { id ->
            require(directChild(parent, id) && result.add(id))
            bytes += id.toByteArray(Charsets.UTF_8).size
            require(bytes <= VaultCopyBatchLimits.MAX_DIRECTORY_ID_BYTES)
        }
        return result
    }
}
