package com.easysavevideo.app.vault.storage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import com.easysavevideo.app.library.export.AndroidExportHandles
import com.easysavevideo.app.library.export.FolderCreateIdentity
import com.easysavevideo.app.vault.*
import com.easysavevideo.library.export.*
import java.io.FileNotFoundException
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** One picker and one destination handle owner, shared by scalar and finite batch copy. */
internal class AndroidVaultPhoneFiles internal constructor(
    private val store: VaultStorePort, private val platform: Platform,
) : VaultPhoneFilesPort {
    constructor(context: Context, store: VaultStorePort) : this(store, AndroidPlatform(context))

    /** Fixed adapter test seam, not a selectable provider, engine, grant registry or persistence API. */
    internal interface Platform {
        fun uriFacts(uri: String): UriFacts
        fun provider(): VaultPhoneFilesProfile.Provider
        fun permission(uri: String, flags: Int): Boolean
        fun document(uri: String): VaultPhoneFilesProfile.Document?
        fun children(tree: String): List<String>
        fun isChild(tree: String, child: String): Boolean
        fun create(tree: String, name: String): String?
        fun openWrite(operationId: String, uri: String): ExportWrite
        fun openReadback(operationId: String, uri: String): ExportReadback
        fun released(operationId: String): Boolean
        fun observeDrain(): ExportRelease
    }
    internal data class UriFacts(val canonical: Boolean, val tree: Boolean, val documentId: String?, val treeId: String?) {
        override fun toString() = "PhoneUriFacts(REDACTED)"
    }
    internal enum class Purpose { SINGLE_CREATE, SINGLE_RECOVERY, BATCH_TREE, BATCH_TREE_RECOVERY }
    private class Request(val purpose: Purpose, val operation: VaultMediaOperation? = null,
        val record: VaultMediaTargetRecord? = null, val batchId: String? = null,
        val tree: VaultPhoneTreeRecord? = null) : VaultPhoneFileRequest { var launched = false }
    private class Directory(val record: VaultPhoneTreeRecord, val treeId: String) : VaultPhoneDirectoryTarget {
        var current = true
        val reservations = mutableMapOf<String, VaultMediaTargetRecord>()
        val issuedOperations = mutableSetOf<String>()
    }
    private class Target(var record: VaultMediaTargetRecord, val reservation: VaultMediaTargetRecord,
        var uri: String?, val cleanupOnly: Boolean, val directory: Directory? = null) : VaultPhoneFileTarget {
        var validated = false
        var writeIssued = false
        var ioBeginning = false
        var cleanupStarted = false
        var published = false
        var removed = false
        var retired = false
    }
    private val lock = Any()
    private val ioAdmissions = AtomicInteger()
    private var outstanding: Request? = null
    private val targets = IdentityHashMap<VaultPhoneFileTarget, Target>()
    private val directories = IdentityHashMap<VaultPhoneDirectoryTarget, Directory>()

    override fun observeMediaDrain(): ExportRelease =
        if (ioAdmissions.get() == 0) platform.observeDrain() else ExportRelease.UNCONFIRMED

    override fun prepareSelection(operation: VaultMediaOperation): VaultPhoneFileRequest = synchronized(lock) {
        require(operation.kind == VaultMediaKind.COPY_OUT && outstanding == null)
        requireLaneAvailable()
        Request(Purpose.SINGLE_CREATE, operation, VaultMediaTargetRecord(operation.operationId, id(), id(), null, 0))
            .also { outstanding = it }
    }
    override fun prepareCleanupSelection(record: VaultMediaTargetRecord): VaultPhoneFileRequest = synchronized(lock) {
        require(outstanding == null && record.uri != null && store.validateBoundPhoneTarget(record))
        require(targets.values.none { !it.retired && it.record.operationId == record.operationId })
        // This is a fresh absence-only witness. It never adopts a present replacement file.
        Request(Purpose.SINGLE_RECOVERY, record = record).also { outstanding = it }
    }
    override fun prepareDirectorySelection(batchId: String): VaultPhoneFileRequest = synchronized(lock) {
        require(batchId.matches(Regex("[a-f0-9]{32}")) && outstanding == null)
        requireLaneAvailable()
        Request(Purpose.BATCH_TREE, batchId = batchId).also { outstanding = it }
    }
    override fun prepareDirectoryRecoverySelection(record: VaultPhoneTreeRecord): VaultPhoneFileRequest = synchronized(lock) {
        require(outstanding == null && store.validateBoundPhoneTree(record))
        Request(Purpose.BATCH_TREE_RECOVERY, batchId = record.batchId, tree = record).also { outstanding = it }
    }
    override fun retireSelection(request: VaultPhoneFileRequest): Boolean = synchronized(lock) {
        val owned = outstanding ?: return@synchronized false
        if (owned !== request || owned.launched) return@synchronized false
        outstanding = null
        true
    }
    /** Shared exact request transition, also exercised by fixed fake-platform tests. */
    internal fun markLaunched(request: VaultPhoneFileRequest): Purpose = synchronized(lock) {
        val owned = outstanding
        require(owned === request && !owned.launched)
        owned.launched = true
        owned.purpose
    }
    fun createIntent(request: VaultPhoneFileRequest): Intent {
        val purpose = markLaunched(request)
        val tree = purpose == Purpose.BATCH_TREE || purpose == Purpose.BATCH_TREE_RECOVERY
        return Intent(when (purpose) {
            Purpose.SINGLE_CREATE -> Intent.ACTION_CREATE_DOCUMENT
            Purpose.SINGLE_RECOVERY -> Intent.ACTION_OPEN_DOCUMENT
            else -> Intent.ACTION_OPEN_DOCUMENT_TREE
        }).apply {
            if (!tree) { addCategory(Intent.CATEGORY_OPENABLE); type = "video/mp4" }
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            if (purpose == Purpose.SINGLE_CREATE) putExtra(Intent.EXTRA_TITLE, "ESV-${(request as Request).record!!.nonce}.mp4")
            addFlags(READ_WRITE or if (tree) Intent.FLAG_GRANT_PREFIX_URI_PERMISSION else 0) // Deliberately no persistable grant request or takePersistableUriPermission.
        }
    }
    /** Only Activity's result for this exact launched request is routed here. */
    fun acceptResult(request: VaultPhoneFileRequest, resultCode: Int, data: Intent?): VaultPhoneSelection? =
        acceptSelection(request, resultCode == Activity.RESULT_OK, data?.data?.toString(), data?.flags ?: 0,
            data?.clipData?.let { clip -> clip.itemCount == 1 && clip.getItemAt(0).uri == data.data } ?: true)

    internal fun acceptSelection(request: VaultPhoneFileRequest, ok: Boolean, uri: String?, flags: Int,
        exactSingleResult: Boolean = true): VaultPhoneSelection? {
        val owned = synchronized(lock) {
            val pending = outstanding
            require(pending === request && pending.launched)
            outstanding = null
            pending
        }
        if (!ok) return null
        val value = requireNotNull(uri)
        val observedFlags = flags and READ_WRITE
        return admitted {
            when (owned.purpose) {
                Purpose.BATCH_TREE, Purpose.BATCH_TREE_RECOVERY -> {
                    require(exactSingleResult && observedFlags == READ_WRITE)
                    val facts = requireTree(value)
                    val original = owned.tree
                    if (original != null) require(original.uri == value && store.validateBoundPhoneTree(original))
                    val record = original ?: VaultPhoneTreeRecord(requireNotNull(owned.batchId), id(), id(), value, observedFlags)
                    val directory = Directory(record, requireNotNull(facts.treeId))
                    requireDirectory(directory)
                    synchronized(lock) {
                        directories.values.forEach { it.current = false }
                        directories.clear()
                        directories[directory] = directory
                    }
                    VaultPhoneSelection.Directory(directory)
                }
                else -> {
                    val original = requireNotNull(owned.record)
                    val recovery = owned.purpose == Purpose.SINGLE_RECOVERY
                    if (recovery) require(exactSingleResult && value == original.uri && observedFlags == READ_WRITE)
                    val reservation = if (recovery) original else original.copy(flags = observedFlags)
                    val target = Target(reservation, reservation, value, recovery)
                    // Return candidate even on profile rejection, so the runtime can persist its
                    // exact reservation and offer explicit keep after known CAS and actual drain.
                    synchronized(lock) { targets[target] = target }
                    if (!recovery) try {
                        require(exactSingleResult)
                        requireSingle(value)
                        requireFile(target)
                        target.record = reservation.copy(uri = value)
                        target.validated = true
                    } catch (_: Exception) { /* Candidate stays opaque and cannot open or clean. */ }
                    VaultPhoneSelection.File(target)
                }
            }
        }
    }
    override fun directoryRecord(target: VaultPhoneDirectoryTarget, batchId: String): VaultPhoneTreeRecord =
        directory(target).also { require(it.record.batchId == batchId) }.record
    override fun validateDirectory(target: VaultPhoneDirectoryTarget) = admitted { requireDirectory(directory(target)) }

    override fun prepareBatchChild(target: VaultPhoneDirectoryTarget, operation: VaultMediaOperation): VaultMediaTargetRecord {
        val directory = directory(target)
        require(operation.kind == VaultMediaKind.COPY_OUT && operation.operationId.matches(Regex("[a-f0-9]{32}")))
        return admitted {
            require(store.validateBoundPhoneTree(directory.record))
            requireDirectory(directory)
            synchronized(lock) {
                requireLaneAvailable(expectedAdmissions = 1)
                require(directory.reservations[operation.operationId] == null && directory.reservations.size < VaultCopyBatchLimits.MAX_ITEMS)
                VaultMediaTargetRecord(operation.operationId, id(), id(), null, directory.record.flags).also {
                    directory.reservations[operation.operationId] = it
                }
            }
        }
    }
    override fun createBatchChild(directory: VaultPhoneDirectoryTarget, reservation: VaultMediaTargetRecord,
        operation: VaultMediaOperation, gate: VaultMediaPublicationGate): VaultPhoneFileTarget {
        val parent = directory(directory)
        val target = synchronized(lock) {
            require(parent.reservations[operation.operationId] == reservation && reservation.uri == null)
            require(reservation.operationId == operation.operationId && operation.kind == VaultMediaKind.COPY_OUT)
            requireLaneAvailable()
            require(parent.issuedOperations.add(operation.operationId))
            // Register even a null/throw create attempt. Never replay this reservation.
            Target(reservation, reservation, null, false, parent).also {
                targets[it] = it; it.ioBeginning = true; ioAdmissions.incrementAndGet()
            }
        }
        try {
            require(store.validateBatchChildCreate(parent.record, reservation, operation))
            requireDirectory(parent)
            val before = VaultPhoneFilesProfile.boundedChildren(parent.treeId, platform.children(parent.record.uri))
            require(before.size < VaultCopyBatchLimits.MAX_DIRECTORY_ENTRIES)
            val freshness = FolderCreateIdentity(before)
            // Final fixed-owner check is immediately before the one provider mutation.
            require(store.validateBatchChildCreate(parent.record, reservation, operation))
            gate.requireCurrent()
            val returned = gate.admit().use { platform.create(parent.record.uri, "ESV-${reservation.nonce}.mp4") }
            synchronized(lock) { target.uri = returned } // Capture before any validation or journal binding.
            requireNotNull(returned)
            require(returned.toByteArray(Charsets.UTF_8).size <= VaultCopyBatchLimits.MAX_URI_UTF8_BYTES)
            requireFile(target)
            val facts = platform.uriFacts(returned)
            val childId = requireNotNull(facts.documentId)
            freshness.requireFresh(childId)
            val after = VaultPhoneFilesProfile.boundedChildren(parent.treeId, platform.children(parent.record.uri))
            require(childId in after && after == before + childId)
            synchronized(lock) {
                require(targets.values.none { it !== target && !it.retired && it.uri == returned })
                target.record = reservation.copy(uri = returned)
                target.validated = true
            }
            return target
        } finally {
            synchronized(lock) { target.ioBeginning = false; ioAdmissions.decrementAndGet() }
        }
    }
    override fun targetRecord(target: VaultPhoneFileTarget, operation: VaultMediaOperation): VaultMediaTargetRecord {
        val owned = owned(target)
        require(owned.record.operationId == operation.operationId && operation.kind == VaultMediaKind.COPY_OUT)
        return owned.record
    }
    override fun openWrite(target: VaultPhoneFileTarget): ExportWrite {
        val owned = beginTargetIo(target) {
            require(it.validated && !it.cleanupOnly && !it.published && !it.removed && !it.writeIssued && !it.cleanupStarted)
            it.writeIssued = true // Failed or uncertain opens are never reopened/truncated.
        }
        try {
            requireBound(owned, VaultPhoneTargetUse.WRITE); requireFile(owned)
            return platform.openWrite(owned.record.operationId, requireNotNull(owned.uri))
        } finally { finishTargetIo(owned) }
    }
    override fun openReadback(target: VaultPhoneFileTarget): ExportReadback {
        val owned = beginTargetIo(target) {
            require(it.validated && !it.cleanupOnly && !it.removed && !it.published && !it.cleanupStarted && platform.released(it.record.operationId))
        }
        try {
            requireBound(owned, VaultPhoneTargetUse.READBACK); requireFile(owned)
            return platform.openReadback(owned.record.operationId, requireNotNull(owned.uri))
        } finally { finishTargetIo(owned) }
    }
    override fun publish(target: VaultPhoneFileTarget) {
        val owned = beginTargetIo(target) {
            require(it.validated && it.writeIssued && !it.cleanupOnly && !it.removed && !it.cleanupStarted && platform.released(it.record.operationId))
        }
        try {
            requireBound(owned, VaultPhoneTargetUse.PUBLISH); requireFile(owned)
            synchronized(lock) { owned.published = true }
        } finally { finishTargetIo(owned) }
    }
    override fun cleanup(target: VaultPhoneFileTarget): ExportCleanup {
        val owned = owned(target)
        synchronized(lock) {
            if (owned.retired || owned.published || owned.ioBeginning || !platform.released(owned.record.operationId)) return ExportCleanup.UNCONFIRMED
            if (owned.removed) return ExportCleanup.REMOVED
            if ((!owned.validated && !owned.cleanupOnly) || owned.uri == null) return ExportCleanup.UNCONFIRMED
            owned.ioBeginning = true; owned.cleanupStarted = true; ioAdmissions.incrementAndGet()
        }
        return try {
            if (!store.canCleanupPhoneTarget(owned.record)) return ExportCleanup.UNCONFIRMED
            requireProviderAndGrant(requireNotNull(owned.uri), owned.record.flags)
            // URI, registry token, metadata and fresh grants do NOT prove current file incarnation.
            // There is deliberately no deleteDocument path for either single or batch targets.
            if (platform.document(requireNotNull(owned.uri)) == null) {
                synchronized(lock) { owned.removed = true }
                ExportCleanup.REMOVED
            } else ExportCleanup.UNCONFIRMED
        } catch (_: Exception) { ExportCleanup.UNCONFIRMED }
        finally { finishTargetIo(owned) }
    }
    override fun recoverTarget(record: VaultMediaTargetRecord): VaultPhoneFileTarget? = synchronized(lock) {
        targets.values.singleOrNull { !it.retired && (it.record == record || it.reservation == record) }
    }
    override fun resourcesReleased(target: VaultPhoneFileTarget): Boolean = synchronized(lock) {
        val owned = targets[target] ?: return@synchronized false
        !owned.ioBeginning && platform.released(owned.record.operationId)
    }
    override fun retirePhoneTarget(target: VaultPhoneFileTarget, operationId: String, batchId: String?): Boolean {
        val owned = synchronized(lock) {
            val found = targets[target] ?: return false
            if (found.retired || found.record.operationId != operationId || found.directory?.record?.batchId != batchId ||
                found.ioBeginning || ioAdmissions.get() != 0 || !platform.released(operationId)) return false
            found
        }
        val exactDurable = store.validatePhoneTargetRetirement(operationId, owned.record, batchId) ||
            (owned.reservation != owned.record && store.validatePhoneTargetRetirement(operationId, owned.reservation, batchId))
        // A confirmed-old bind CAS leaves the original null-URI reservation durable. Only
        // this same private candidate's immutable reservation may prove its retirement.
        if (!exactDurable) return false
        return synchronized(lock) {
            if (owned.retired || owned.ioBeginning || ioAdmissions.get() != 0 || !platform.released(operationId)) return@synchronized false
            owned.retired = true // Revocation never hides live/uncertain handles or resurrects on recover.
            true
        }
    }
    private fun requireLaneAvailable(expectedAdmissions: Int = 0) {
        require(ioAdmissions.get() == expectedAdmissions)
        targets.entries.removeAll { entry ->
            val target = entry.value
            val knownSingleTerminal = target.directory == null && (target.removed || target.published) &&
                !target.ioBeginning && platform.released(target.record.operationId) &&
                store.validatePhoneTargetRetirement(target.record.operationId, target.record, null)
            if (knownSingleTerminal && !target.ioBeginning && ioAdmissions.get() == expectedAdmissions &&
                platform.released(target.record.operationId)) target.retired = true
            target.retired
        }
        require(targets.values.none { !it.retired })
        require(platform.observeDrain() == ExportRelease.RELEASED)
    }
    private fun owned(target: VaultPhoneFileTarget): Target = synchronized(lock) {
        targets[target]?.takeUnless { it.retired } ?: throw VaultOperationException(VaultFailure.CONFLICT)
    }
    private fun directory(target: VaultPhoneDirectoryTarget): Directory = synchronized(lock) {
        directories[target]?.takeIf { it.current } ?: throw VaultOperationException(VaultFailure.CONFLICT)
    }
    private fun beginTargetIo(target: VaultPhoneFileTarget, check: (Target) -> Unit): Target = synchronized(lock) {
        val owned = owned(target)
        require(!owned.ioBeginning)
        check(owned)
        owned.ioBeginning = true; ioAdmissions.incrementAndGet()
        owned
    }
    private fun finishTargetIo(target: Target) = synchronized(lock) { target.ioBeginning = false; ioAdmissions.decrementAndGet(); Unit }
    private inline fun <T> admitted(block: () -> T): T {
        ioAdmissions.incrementAndGet()
        try { return block() } finally { ioAdmissions.decrementAndGet() }
    }
    private fun requireBound(target: Target, use: VaultPhoneTargetUse) {
        if (!store.validateBoundPhoneTarget(target.record, use)) throw VaultOperationException(VaultFailure.INCOMPLETE)
    }
    private fun requireProviderAndGrant(uri: String, flags: Int) {
        require(flags == READ_WRITE && platform.permission(uri, READ_WRITE) && VaultPhoneFilesProfile.acceptsProvider(platform.provider()))
    }
    private fun requireSingle(uri: String) {
        require(uri.toByteArray(Charsets.UTF_8).size in 1..8192)
        val facts = platform.uriFacts(uri)
        require(facts.canonical && !facts.tree && facts.treeId == null && facts.documentId != null)
    }
    private fun requireTree(uri: String): UriFacts {
        require(uri.toByteArray(Charsets.UTF_8).size in 1..VaultCopyBatchLimits.MAX_URI_UTF8_BYTES)
        return platform.uriFacts(uri).also { require(it.canonical && it.tree && it.documentId == null && it.treeId != null) }
    }
    private fun requireDirectory(directory: Directory) {
        require(directory.current)
        requireTree(directory.record.uri)
        requireProviderAndGrant(directory.record.uri, directory.record.flags)
        val document = requireNotNull(platform.document(directory.record.uri))
        require(document.id == directory.treeId && VaultPhoneFilesProfile.acceptsDirectory(platform.provider(), document))
    }
    private fun requireFile(target: Target) {
        val uri = requireNotNull(target.uri)
        val parent = target.directory
        if (parent == null) requireSingle(uri) else {
            requireDirectory(parent)
            val facts = platform.uriFacts(uri)
            require(facts.canonical && !facts.tree && facts.treeId == parent.treeId &&
                facts.documentId?.let { VaultPhoneFilesProfile.directChild(parent.treeId, it) } == true)
        }
        requireProviderAndGrant(uri, target.record.flags)
        val document = requireNotNull(platform.document(uri))
        require(document.id == platform.uriFacts(uri).documentId &&
            VaultPhoneFilesProfile.accepts(platform.provider(), document.id, document.mime, document.virtual))
        if (parent != null) require(document.supportsWrite && document.supportsDelete && platform.isChild(parent.record.uri, uri))
    }
    private class AndroidPlatform(private val context: Context) : Platform {
        private val resolver = context.contentResolver
        private val handles = AndroidExportHandles(resolver, preserveUncertainOpen = true)
        override fun uriFacts(uri: String): UriFacts {
            val parsed = Uri.parse(uri)
            if (parsed.scheme != "content" || parsed.authority != VaultPhoneFilesProfile.AUTHORITY || parsed.query != null || parsed.fragment != null)
                return UriFacts(false, false, null, null)
            val isDocument = DocumentsContract.isDocumentUri(context, parsed)
            val treeId = if (DocumentsContract.isTreeUri(parsed)) DocumentsContract.getTreeDocumentId(parsed) else null
            val documentId = if (isDocument) DocumentsContract.getDocumentId(parsed) else null
            val canonical = when {
                treeId != null && documentId != null -> DocumentsContract.buildDocumentUriUsingTree(
                    DocumentsContract.buildTreeDocumentUri(VaultPhoneFilesProfile.AUTHORITY, treeId), documentId).toString()
                treeId != null -> DocumentsContract.buildTreeDocumentUri(VaultPhoneFilesProfile.AUTHORITY, treeId).toString()
                documentId != null -> DocumentsContract.buildDocumentUri(VaultPhoneFilesProfile.AUTHORITY, documentId).toString()
                else -> null
            }
            return UriFacts(canonical == uri, treeId != null && documentId == null, documentId, treeId)
        }
        override fun provider(): VaultPhoneFilesProfile.Provider {
            val provider = context.packageManager.resolveContentProvider(VaultPhoneFilesProfile.AUTHORITY, 0)
                ?: throw VaultOperationException(VaultFailure.UNSUPPORTED)
            val app = provider.applicationInfo ?: throw VaultOperationException(VaultFailure.UNSUPPORTED)
            return VaultPhoneFilesProfile.Provider(provider.authority, provider.packageName, provider.name,
                app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                context.packageManager.checkSignatures(provider.packageName, "android") == PackageManager.SIGNATURE_MATCH,
                provider.enabled && app.enabled, provider.exported, provider.grantUriPermissions, provider.readPermission, provider.writePermission)
        }
        override fun permission(uri: String, flags: Int) =
            context.checkUriPermission(Uri.parse(uri), Process.myPid(), Process.myUid(), flags) == PackageManager.PERMISSION_GRANTED
        private fun queryUri(uri: String): Uri {
            val parsed = Uri.parse(uri)
            return if (DocumentsContract.isTreeUri(parsed) && !DocumentsContract.isDocumentUri(context, parsed))
                DocumentsContract.buildDocumentUriUsingTree(parsed, DocumentsContract.getTreeDocumentId(parsed)) else parsed
        }
        override fun document(uri: String): VaultPhoneFilesProfile.Document? = try {
            val cursor = resolver.query(queryUri(uri), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
                ?: throw VaultOperationException(VaultFailure.UNAVAILABLE)
            cursor.use {
                require(!it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) && it.extras.getString(DocumentsContract.EXTRA_ERROR) == null)
                if (!it.moveToFirst()) return@use null
                val flags = it.getInt(2)
                val document = VaultPhoneFilesProfile.Document(requireNotNull(it.getString(0)), it.getString(1),
                    flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0,
                    flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0,
                    flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0,
                    flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0)
                require(!it.moveToNext())
                document
            }
        } catch (_: FileNotFoundException) { null }
        override fun children(tree: String): List<String> {
            val parsed = Uri.parse(tree)
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parsed, DocumentsContract.getTreeDocumentId(parsed))
            return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                require(!cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) && cursor.extras.getString(DocumentsContract.EXTRA_ERROR) == null)
                val result = ArrayList<String>()
                var bytes = 0L
                while (cursor.moveToNext()) {
                    require(result.size < VaultCopyBatchLimits.MAX_DIRECTORY_ENTRIES)
                    val id = requireNotNull(cursor.getString(0))
                    require(id.length <= VaultPhoneFilesProfile.MAX_ID_BYTES)
                    val idBytes = id.toByteArray(Charsets.UTF_8).size
                    require(idBytes <= VaultPhoneFilesProfile.MAX_ID_BYTES)
                    bytes += idBytes
                    require(bytes <= VaultCopyBatchLimits.MAX_DIRECTORY_ID_BYTES)
                    result.add(id)
                }
                result
            } ?: throw VaultOperationException(VaultFailure.UNAVAILABLE)
        }
        override fun isChild(tree: String, child: String): Boolean =
            DocumentsContract.isChildDocument(resolver, queryUri(tree), Uri.parse(child))
        override fun create(tree: String, name: String): String? =
            DocumentsContract.createDocument(resolver, queryUri(tree), "video/mp4", name)?.toString()
        override fun openWrite(operationId: String, uri: String) = handles.openWrite(operationId, Uri.parse(uri))
        override fun openReadback(operationId: String, uri: String) = ExportReadback.Opened(handles.openReadback(operationId, Uri.parse(uri)))
        override fun released(operationId: String) = handles.released(operationId)
        override fun observeDrain() = handles.observeDrain()
    }
    private companion object {
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        fun id() = UUID.randomUUID().toString().replace("-", "")
    }
}
