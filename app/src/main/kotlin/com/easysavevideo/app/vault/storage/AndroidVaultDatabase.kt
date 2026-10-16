package com.easysavevideo.app.vault.storage

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import com.easysavevideo.app.vault.VaultOperationContext

/**
 * The sole app-level SQLite owner. Callers cannot select a path. No helper, migration, fallback,
 * deletion, alias creation, credential, or plaintext catalog is admitted here. This does not claim
 * one native platform connection or prove storage hardware's response to physical power loss.
 */
internal class AndroidVaultDatabase(context: Context) {
    private val parent = context.applicationContext.noBackupFilesDir
    private val directory = File(parent, "vault_v1")
    private val file = File(directory, "vault.db")
    private val lockFile = File(directory, "owner.lock")
    private var lockChannel: FileChannel? = null
    private var lifetimeLock: FileLock? = null
    private var db: SQLiteDatabase? = null
    private var quarantined = false
    private var creationAttempted = false
    private var pendingUnknown: VaultSqliteMutation? = null
    private var closed = false
    private val ownershipKey = parent.canonicalPath

    init { synchronized(owners) {
        if (owners.containsKey(ownershipKey)) throw VaultStoreFault()
        owners[ownershipKey] = this
    } }

    /** Subordinate file access shares this exact database owner's lifetime lock. */
    @Synchronized fun mediaDirectory(): File {
        rejectQuarantine()
        openExisting()
        require(lifetimeLock?.isValid == true)
        return directory.absoluteFile
    }

    fun syncMediaDirectory(value: File) {
        require(value.absoluteFile == directory.absoluteFile)
        syncDirectory(value)
    }

    /** An existing directory, even empty, is evidence. It is never a first-use success. */
    @Synchronized fun hasFilesystemEvidence(): Boolean = Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)

    @Synchronized fun readExisting(): VaultSqliteRecord {
        rejectQuarantine()
        return readRecord(openExisting()) ?: throw VaultStoreFault()
    }

    /**
     * Only the internal setup path calls this after bounded identity and real alias inventory
     * checks in AndroidVaultStore. A filesystem absence is not user permission or root authority.
     * Failures preserve the directory/DB and never retry CREATE with a new identity.
     */
    @Synchronized fun initialize(mutation: VaultSqliteMutation, context: VaultOperationContext): VaultSqliteWriteResult {
        rejectQuarantine()
        require(mutation.expected == null)
        if (inactive(context)) return VaultSqliteWriteResult.DEADLINE
        if (creationAttempted || directory.exists() || db != null || pendingUnknown != null)
            return VaultSqliteWriteResult.CONFLICT
        creationAttempted = true
        try {
            if (!parent.isDirectory) throw VaultStoreFault()
            if (!directory.mkdir()) throw VaultStoreFault()
            syncDirectory(parent)
            syncDirectory(directory)
            acquireLifetimeLock(create = true)
            if (inactive(context)) return VaultSqliteWriteResult.DEADLINE
            db = open(create = true)
        } catch (_: Exception) {
            quarantined = true
            return VaultSqliteWriteResult.UNAVAILABLE
        }
        val database = db ?: return VaultSqliteWriteResult.UNAVAILABLE
        return transact(mutation, context, createSchema = true, database = database)
    }

    @Synchronized fun compareAndSwap(mutation: VaultSqliteMutation, context: VaultOperationContext): VaultSqliteWriteResult {
        rejectQuarantine()
        require(mutation.expected != null)
        if (inactive(context)) return VaultSqliteWriteResult.DEADLINE
        val pending = pendingUnknown
        if (pending != null && !sameMutation(pending, mutation)) return VaultSqliteWriteResult.UNKNOWN
        val database = try { openExisting() } catch (_: Exception) { return VaultSqliteWriteResult.UNAVAILABLE }
        return transact(mutation, context, createSchema = false, database = database)
    }

    /** Read-only application-level reconciliation: never creates schema, aliases, or new operation. */
    @Synchronized fun reconcile(mutation: VaultSqliteMutation): VaultSqliteWriteResult {
        rejectQuarantine()
        val pending = pendingUnknown
        if (pending != null && !sameMutation(pending, mutation)) return VaultSqliteWriteResult.UNKNOWN
        return exactReopenReadback(mutation)
    }

    private fun transact(mutation: VaultSqliteMutation, context: VaultOperationContext, createSchema: Boolean,
                         database: SQLiteDatabase): VaultSqliteWriteResult {
        var transaction = false
        var changed = false
        var noWrite: VaultSqliteWriteResult? = null
        try {
            requireConfiguration(database)
            if (inactive(context)) return VaultSqliteWriteResult.DEADLINE
            database.beginTransaction()
            transaction = true
            if (inactive(context)) {
                noWrite = VaultSqliteWriteResult.DEADLINE
            } else if (createSchema) {
                database.execSQL(SCHEMA)
                database.version = SCHEMA_VERSION
                database.execSQL("PRAGMA application_id=$APPLICATION_ID")
                insert(database, mutation.proposed)
                changed = true
            } else {
                val current = readRecord(database)
                when {
                    current != null && current.sameBytes(mutation.proposed) -> noWrite = VaultSqliteWriteResult.DURABLE_EXACT
                    current == null || !current.sameBytes(mutation.expected!!) -> noWrite = VaultSqliteWriteResult.CONFLICT
                    else -> {
                        changed = update(database, mutation) == 1
                        if (!changed) noWrite = VaultSqliteWriteResult.CONFLICT
                    }
                }
            }
            if (changed && !inactive(context)) database.setTransactionSuccessful()
            else if (changed) noWrite = VaultSqliteWriteResult.DEADLINE
        } catch (_: Exception) {
            // No exception here establishes whether the transaction persisted.
        } finally {
            if (transaction) {
                try { database.endTransaction() } catch (_: Exception) {
                    noWrite = null
                }
            }
        }
        if (noWrite == VaultSqliteWriteResult.CONFLICT && !changed) return VaultSqliteWriteResult.CONFLICT
        if (noWrite == VaultSqliteWriteResult.DEADLINE && !changed) return VaultSqliteWriteResult.DEADLINE
        pendingUnknown = mutation
        // A long platform call never extends the immutable owner deadline or produces a late ACK.
        if (inactive(context)) return VaultSqliteWriteResult.UNKNOWN
        return exactReopenReadback(mutation)
    }

    private fun exactReopenReadback(mutation: VaultSqliteMutation): VaultSqliteWriteResult {
        return try {
            closeForReadback()
            val observed = readRecord(openExisting())
            when (classifyVaultReadback(mutation, observed)) {
                VaultSqliteReadback.EXACT_PROPOSED -> {
                    syncDirectory(directory)
                    syncDirectory(parent)
                    pendingUnknown = null
                    VaultSqliteWriteResult.DURABLE_EXACT
                }
                VaultSqliteReadback.EXACT_EXPECTED -> {
                    pendingUnknown = null
                    VaultSqliteWriteResult.CONFIRMED_OLD
                }
                VaultSqliteReadback.INCONSISTENT -> {
                    quarantined = true
                    VaultSqliteWriteResult.UNKNOWN
                }
            }
        } catch (_: Exception) {
            pendingUnknown = mutation
            VaultSqliteWriteResult.UNKNOWN
        }
    }

    private fun openExisting(): SQLiteDatabase {
        rejectQuarantine()
        if (!directory.isDirectory || isSymlink(directory) || !file.isFile || isSymlink(file) ||
            file.length() !in 1..MAX_DATABASE_BYTES) throw VaultStoreFault()
        // Preserve unsupported WAL evidence. Opening must not checkpoint it into a new truth.
        if (Files.exists(File(directory, "vault.db-wal").toPath(), LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(File(directory, "vault.db-shm").toPath(), LinkOption.NOFOLLOW_LINKS)) throw VaultStoreFault()
        val journal = File(directory, "vault.db-journal")
        if (Files.exists(journal.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            (!journal.isFile || isSymlink(journal) || journal.length() > MAX_DATABASE_BYTES)) throw VaultStoreFault()
        acquireLifetimeLock(create = false)
        requireRollbackHeader()
        val current = db
        if (current != null) {
            requireConfiguration(current)
            return current
        }
        return open(create = false).also { db = it }
    }

    private fun requireRollbackHeader() {
        val header = ByteArray(VaultSqliteHeaderPolicy.HEADER_BYTES)
        FileInputStream(file).use { stream ->
            var offset = 0
            while (offset < header.size) {
                val read = stream.read(header, offset, header.size - offset)
                if (read <= 0) throw VaultStoreFault()
                offset += read
            }
        }
        VaultSqliteHeaderPolicy.requireRollbackHeader(header)
    }

    private fun open(create: Boolean): SQLiteDatabase {
        val params = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.OPEN_READWRITE or if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0)
            .setJournalMode("DELETE")
            .setSynchronousMode("EXTRA") // API28 setter; do not use API33 SYNC_MODE_EXTRA on min31.
            .setErrorHandler(DatabaseErrorHandler {
                // DefaultDatabaseErrorHandler deletes the DB. This owner only fences and preserves.
                quarantined = true
                throw VaultStoreFault()
            })
            .build()
        val opened = SQLiteDatabase.openDatabase(file, params)
        try {
            requireConfiguration(opened)
            if (!create && opened.version != SCHEMA_VERSION) throw VaultStoreFault()
            if (!create) opened.rawQuery("PRAGMA application_id", null).use { c ->
                if (!c.moveToFirst() || c.getInt(0) != APPLICATION_ID) throw VaultStoreFault()
            }
            if (!create) opened.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok" || cursor.moveToNext()) throw VaultStoreFault()
            }
            return opened
        } catch (_: Exception) {
            db = opened
            try {
                opened.close()
                if (opened.isOpen) throw VaultStoreFault()
                db = null
            } catch (_: Exception) { quarantined = true }
            throw VaultStoreFault()
        }
    }

    private fun requireConfiguration(database: SQLiteDatabase) {
        if (database.isWriteAheadLoggingEnabled) throw VaultStoreFault()
        database.rawQuery("PRAGMA journal_mode", null).use { c ->
            if (!c.moveToFirst() || !c.getString(0).equals("delete", ignoreCase = true) || c.moveToNext()) throw VaultStoreFault()
        }
        database.rawQuery("PRAGMA synchronous", null).use { c ->
            if (!c.moveToFirst() || c.getInt(0) != 3 || c.moveToNext()) throw VaultStoreFault()
        }
    }

    private fun readRecord(database: SQLiteDatabase): VaultSqliteRecord? {
        // Query lengths first. Neither a corrupt oversized blob nor an unbounded text is copied.
        database.rawQuery("SELECT id,length(operation_id),length(fingerprint),length(payload) FROM vault_state LIMIT 2", null).use { c ->
            if (!c.moveToFirst()) return null
            if (c.getLong(0) != 1L || c.getLong(1) !in 1..128 || c.getLong(2) != 32L ||
                c.getLong(3) !in 1..VaultSqliteRecord.MAX_PAYLOAD_BYTES.toLong() || c.moveToNext()) throw VaultStoreFault()
        }
        return database.rawQuery("""SELECT revision,operation_id,phase,fingerprint,payload FROM vault_state
            WHERE id=1 AND typeof(id)='integer' AND typeof(revision)='integer'
            AND revision BETWEEN 1 AND 9223372036854775806 AND typeof(phase)='integer' AND phase BETWEEN 1 AND 3
            AND typeof(operation_id)='text' AND length(operation_id) BETWEEN 1 AND 128
            AND typeof(fingerprint)='blob' AND length(fingerprint)=32 AND typeof(payload)='blob'
            AND length(payload) BETWEEN 1 AND ${VaultSqliteRecord.MAX_PAYLOAD_BYTES}""", null).use { c ->
            if (!c.moveToFirst()) throw VaultStoreFault()
            val row = VaultSqliteRecord(c.getLong(0), c.getString(1), c.getInt(2), c.getBlob(4))
            if (!MessageDigest.isEqual(c.getBlob(3), row.fingerprint()) || c.moveToNext()) throw VaultStoreFault()
            row
        }
    }

    private fun insert(database: SQLiteDatabase, row: VaultSqliteRecord) {
        database.compileStatement("INSERT INTO vault_state(id,revision,operation_id,phase,fingerprint,payload) VALUES(1,?,?,?,?,?)").use { s ->
            s.bindLong(1, row.revision); s.bindString(2, row.operationId); s.bindLong(3, row.phase.toLong())
            s.bindBlob(4, row.fingerprint()); s.bindBlob(5, row.payload())
            if (s.executeInsert() != 1L) throw VaultStoreFault()
        }
    }

    private fun update(database: SQLiteDatabase, mutation: VaultSqliteMutation): Int {
        val old = mutation.expected ?: throw VaultStoreFault()
        val row = mutation.proposed
        return database.compileStatement("""UPDATE vault_state SET revision=?,operation_id=?,phase=?,fingerprint=?,payload=?
            WHERE id=1 AND revision=? AND operation_id=? AND phase=? AND fingerprint=? AND payload=?""").use { s ->
            s.bindLong(1, row.revision); s.bindString(2, row.operationId); s.bindLong(3, row.phase.toLong())
            s.bindBlob(4, row.fingerprint()); s.bindBlob(5, row.payload())
            s.bindLong(6, old.revision); s.bindString(7, old.operationId); s.bindLong(8, old.phase.toLong())
            s.bindBlob(9, old.fingerprint()); s.bindBlob(10, old.payload())
            s.executeUpdateDelete()
        }
    }

    private fun closeForReadback() {
        val current = db ?: return
        // Keep owner reference if close is unconfirmed. Never create a replacement around it.
        current.close()
        if (current.isOpen) throw VaultStoreFault()
        db = null
    }

    private fun syncDirectory(value: File) {
        val fd = Os.open(value.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw VaultStoreFault()
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }

    private fun acquireLifetimeLock(create: Boolean) {
        val held = lifetimeLock
        if (held != null) {
            if (!held.isValid || lockChannel?.isOpen != true) throw VaultStoreFault()
            return
        }
        if (lockChannel != null) throw VaultStoreFault()
        if (!create && (!lockFile.isFile || isSymlink(lockFile))) throw VaultStoreFault()
        val channel = if (create) FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            else FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE)
        lockChannel = channel
        lifetimeLock = channel.tryLock() ?: throw VaultStoreFault()
        if (create) { channel.force(true); syncDirectory(directory) }
    }

    private fun rejectQuarantine() { if (quarantined || closed) throw VaultStoreFault() }
    private fun inactive(context: VaultOperationContext): Boolean = try { context.checkActive(); false } catch (_: Exception) { true }
    private fun isSymlink(value: File): Boolean = OsConstants.S_ISLNK(Os.lstat(value.absolutePath).st_mode)

    @Synchronized fun close() {
        if (closed) return
        closeForReadback()
        val held = lifetimeLock
        if (held != null) {
            held.release()
            if (held.isValid) throw VaultStoreFault()
            lifetimeLock = null
        }
        val channel = lockChannel
        if (channel != null) {
            channel.close()
            if (channel.isOpen) throw VaultStoreFault()
            lockChannel = null
        }
        closed = true
        synchronized(owners) { owners.remove(ownershipKey) }
    }
    private fun sameMutation(a: VaultSqliteMutation, b: VaultSqliteMutation): Boolean =
        a.proposed.sameBytes(b.proposed) && if (a.expected == null) b.expected == null
        else b.expected != null && a.expected.sameBytes(b.expected)

    companion object {
        private val owners = mutableMapOf<String, AndroidVaultDatabase>()
        private const val SCHEMA_VERSION = 1
        private const val APPLICATION_ID = 0x45535631
        private const val MAX_DATABASE_BYTES = 2L * 1024 * 1024
        private const val SCHEMA = """CREATE TABLE vault_state(
            id INTEGER PRIMARY KEY NOT NULL CHECK(id=1),
            revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision BETWEEN 1 AND 9223372036854775806),
            operation_id TEXT NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),
            phase INTEGER NOT NULL CHECK(typeof(phase)='integer' AND phase BETWEEN 1 AND 3),
            fingerprint BLOB NOT NULL CHECK(length(fingerprint)=32),
            payload BLOB NOT NULL CHECK(length(payload) BETWEEN 1 AND 262144))"""
    }
}
