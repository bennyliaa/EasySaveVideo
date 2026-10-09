package com.easysavevideo.app.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.easysavevideo.discovery.*

/**
 * Platform SQLite implements the spec's equivalent structured store without a new runtime dependency.
 * Call only off the main thread. All core mutations share one transaction; Seen and progress remain
 * separate tables so resetting a cursor never deletes history. Unknown/corrupt schemas are preserved.
 */
class AndroidDiscoveryStore(context: Context, private val retention: DiscoveryRetentionPolicy = DiscoveryRetentionPolicy()) : DiscoveryStore, AutoCloseable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "discovery_v1.db", null, 3,
        android.database.DatabaseErrorHandler { throw IllegalStateException("DISCOVERY_DATABASE_UNAVAILABLE") }) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE checkpoints (session_id TEXT PRIMARY KEY NOT NULL, epoch INTEGER NOT NULL CHECK(epoch>0), payload BLOB NOT NULL, profile_id TEXT NOT NULL DEFAULT '', query_key TEXT NOT NULL DEFAULT '', mutation_seq INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE results (session_id TEXT NOT NULL, result_id TEXT NOT NULL, page_url TEXT NOT NULL, title TEXT, source_name TEXT NOT NULL, source_namespace TEXT, source_video_id TEXT, thumbnail TEXT, duration INTEGER, description TEXT, published_at INTEGER, provenance TEXT NOT NULL, aliases TEXT NOT NULL, identity_version INTEGER NOT NULL, discovered_at INTEGER NOT NULL, entry_order INTEGER NOT NULL, rights BLOB, PRIMARY KEY(session_id,result_id))")
            db.execSQL("CREATE TABLE pages (session_id TEXT NOT NULL, page_index INTEGER NOT NULL, revision INTEGER NOT NULL, page_size INTEGER NOT NULL, result_ids TEXT NOT NULL, PRIMARY KEY(session_id,page_index))")
            db.execSQL("CREATE TABLE progress (profile_id TEXT NOT NULL, provider_id TEXT NOT NULL, query_key TEXT NOT NULL, provider_version TEXT NOT NULL, cursor_schema INTEGER NOT NULL, cursor TEXT, expires_at INTEGER, exhausted INTEGER NOT NULL, last_success INTEGER, mutation_seq INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(profile_id,provider_id,query_key))")
            db.execSQL("CREATE TABLE seen (profile_id TEXT NOT NULL, fingerprint TEXT NOT NULL, first_discovered INTEGER NOT NULL, shown_at INTEGER, opened_at INTEGER, played_at INTEGER, saved_at INTEGER, dismissed_at INTEGER, identity_id TEXT NOT NULL DEFAULT '', mutation_seq INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(profile_id,fingerprint))")
            createHistoryTables(db)
            db.execSQL("CREATE TABLE viewport (session_id TEXT PRIMARY KEY NOT NULL, epoch INTEGER NOT NULL, page_index INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // SQLiteOpenHelper owns one transaction for DDL, metadata, pruning and user_version.
            // Unknown/corrupt data throws, preserving the old database instead of resetting it.
            if (oldVersion !in 1..2 || newVersion != 3) throw IllegalStateException("DISCOVERY_MIGRATION_REQUIRED")
            if (oldVersion == 1) db.execSQL("ALTER TABLE results ADD COLUMN rights BLOB")
            db.execSQL("ALTER TABLE checkpoints ADD COLUMN profile_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE checkpoints ADD COLUMN query_key TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE checkpoints ADD COLUMN mutation_seq INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE progress ADD COLUMN mutation_seq INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE seen ADD COLUMN identity_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE seen ADD COLUMN mutation_seq INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE seen SET identity_id=fingerprint")
            createHistoryTables(db)
            Transaction(db, retention, migrating = true).apply { migrateLegacyHistory(); finish() }
        }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw IllegalStateException("DISCOVERY_MIGRATION_REQUIRED") }
    }
    @Synchronized override fun <T> transaction(block: DiscoveryTransaction.() -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val transaction = Transaction(db, retention)
            val result = transaction.block()
            transaction.finish()
            transaction.checkBeforeCommit()
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
    @Synchronized fun saveViewport(stamp: SessionStamp, pageIndex: Int) {
        require(pageIndex >= 0)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val current = Transaction(db, retention).checkpoint(stamp.sessionId)
            if (current?.stamp == stamp && (pageIndex == 0 || current.pool.pages.any { it.pageIndex == pageIndex })) {
                db.insertOrThrow("viewport", null, ContentValues().apply { put("session_id", stamp.sessionId); put("epoch", stamp.epoch); put("page_index", pageIndex) }.also {
                    db.delete("viewport", "session_id=?", arrayOf(stamp.sessionId))
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun loadViewport(stamp: SessionStamp): Int = helper.readableDatabase.query("viewport", arrayOf("page_index"), "session_id=? AND epoch=?", arrayOf(stamp.sessionId, stamp.epoch.toString()), null, null, null).use {
        if (it.moveToFirst()) it.getInt(0).coerceIn(0, 499) else 0
    }
    @Synchronized override fun close() { helper.close() }
    private fun createHistoryTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history_profiles (profile_id TEXT PRIMARY KEY NOT NULL, mutation_seq INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history_meta (singleton INTEGER PRIMARY KEY NOT NULL CHECK(singleton=1), mutation_seq INTEGER NOT NULL CHECK(mutation_seq>=0))")
        db.execSQL("INSERT INTO history_meta(singleton,mutation_seq) VALUES (1,0)")
        db.execSQL("CREATE INDEX seen_identity ON seen(profile_id,identity_id)")
    }
    private class Transaction(private val db: SQLiteDatabase, private val retention: DiscoveryRetentionPolicy, private val migrating: Boolean = false) : DiscoveryTransaction {
        private var dirty = false
        private var sequence: Long? = null
        private val writtenCheckpoints = mutableSetOf<String>()
        private val commitGuards = mutableListOf<() -> Unit>()
        override fun beforeCommit(check: () -> Unit) { commitGuards += check }
        fun checkBeforeCommit() { commitGuards.forEach { it() } }
        private fun mutationSequence(): Long {
            dirty = true
            return sequence ?: db.rawQuery("SELECT mutation_seq FROM history_meta WHERE singleton=1", null).use {
                check(it.moveToFirst()); val old = it.getLong(0); check(old in 0 until Long.MAX_VALUE)
                (old + 1).also { value -> db.execSQL("UPDATE history_meta SET mutation_seq=? WHERE singleton=1", arrayOf(value)); sequence = value }
            }
        }
        private fun touchProfile(profileId: String): Long = mutationSequence().also { value ->
            db.execSQL("INSERT INTO history_profiles(profile_id,mutation_seq) VALUES (?,?) ON CONFLICT(profile_id) DO UPDATE SET mutation_seq=excluded.mutation_seq", arrayOf<Any>(profileId, value))
        }
        fun migrateLegacyHistory() {
            dirty = true
            // Stable zero recency for old rows. Only explicit persisted alias evidence is linked.
            db.execSQL("INSERT INTO history_profiles(profile_id,mutation_seq) SELECT profile_id,0 FROM seen UNION SELECT profile_id,0 FROM progress")
            val sessions = db.rawQuery("SELECT session_id FROM checkpoints ORDER BY session_id", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            for (session in sessions) {
                val checkpoint = checkNotNull(checkpoint(session))
                db.execSQL("UPDATE checkpoints SET profile_id=?,query_key=? WHERE session_id=?", arrayOf(checkpoint.profile.profileId, checkpoint.profile.queryKey, session))
                db.execSQL("INSERT OR IGNORE INTO history_profiles(profile_id,mutation_seq) VALUES (?,0)", arrayOf(checkpoint.profile.profileId))
                checkpoint.pool.entries.forEach { linkSeenAliases(checkpoint.profile.profileId, it.result.identityAliases + it.result.fingerprint) }
            }
        }
        fun finish() {
            if (!dirty) return // Status/statistics reads do not prune, touch recency, or write a WAL row.
            db.execSQL("DELETE FROM history_profiles WHERE profile_id NOT IN (SELECT profile_id FROM checkpoints UNION SELECT profile_id FROM seen UNION SELECT profile_id FROM progress)")
            val counts = historyStatistics()
            if (counts.profileCount <= retention.maxProfiles && counts.checkpointCount <= retention.maxCheckpoints &&
                counts.seenAliasCount <= retention.maxSeenAliases && counts.progressCount <= retention.maxProgress) return
            val profiles = db.rawQuery("SELECT profile_id,mutation_seq FROM history_profiles", null).use { c ->
                buildList { while (c.moveToNext()) add(RetentionProfile(c.getString(0), c.getLong(1))) }
            }
            val aliases = db.rawQuery("SELECT profile_id,fingerprint,identity_id,mutation_seq FROM seen", null).use { c ->
                buildList { while (c.moveToNext()) add(RetentionSeenAlias(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) }
            }
            val progress = db.rawQuery("SELECT profile_id,provider_id,query_key,mutation_seq FROM progress", null).use { c ->
                buildList { while (c.moveToNext()) add(RetentionProgress(RetentionProgressKey(c.getString(0), c.getString(1), c.getString(2)), c.getLong(3))) }
            }
            val checkpoints = db.rawQuery("SELECT session_id,profile_id,query_key,mutation_seq FROM checkpoints", null).use { c ->
                buildList { while (c.moveToNext()) add(RetentionCheckpoint(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) }
            }.map { metadata ->
                val checkpoint = checkNotNull(checkpoint(metadata.sessionId))
                val pinned = checkpoint.pool.pages.flatMap { it.orderedResultIds }.toSet()
                metadata.copy(isProtected = checkpoint.pool.pages.isNotEmpty() || metadata.sessionId in writtenCheckpoints,
                    protectedAliases = checkpoint.pool.entries.filter { it.result.resultId in pinned }
                        .flatMap { it.result.identityAliases + it.result.fingerprint }.toSet())
            }
            val plan = DiscoveryRetentionPlanner.plan(DiscoveryRetentionSnapshot(profiles, aliases, progress, checkpoints), retention)
            // Automatic pruning must not refresh LRU recency of a surviving profile.
            plan.removeCheckpoints.forEach { session ->
                listOf("checkpoints", "results", "pages", "viewport").forEach { db.delete(it, "session_id=?", arrayOf(session)) }
            }
            plan.removeSeenAliases.forEach { db.delete("seen", "profile_id=? AND fingerprint=?", arrayOf(it.profileId, it.fingerprint)) }
            plan.removeProgress.forEach { db.delete("progress", "profile_id=? AND provider_id=? AND query_key=?", arrayOf(it.profileId, it.providerId, it.queryKey)) }
            db.execSQL("DELETE FROM history_profiles WHERE profile_id NOT IN (SELECT profile_id FROM checkpoints UNION SELECT profile_id FROM seen UNION SELECT profile_id FROM progress)")
        }
        override fun historyStatistics(profileId: String?): HistoryStatistics {
            val where = if (profileId == null) "" else " WHERE profile_id=?"
            val args = profileId?.let { arrayOf(it) }
            fun count(sql: String): Int = db.rawQuery(sql, args).use { check(it.moveToFirst()); it.getLong(0).also { n -> check(n <= Int.MAX_VALUE) }.toInt() }
            return HistoryStatistics(count("SELECT COUNT(*) FROM (SELECT profile_id FROM checkpoints UNION SELECT profile_id FROM seen UNION SELECT profile_id FROM progress)$where"), count("SELECT COUNT(*) FROM checkpoints$where"),
                count("SELECT COUNT(*) FROM (SELECT profile_id,identity_id FROM seen$where GROUP BY profile_id,identity_id)"),
                count("SELECT COUNT(*) FROM seen$where"), count("SELECT COUNT(*) FROM progress$where"))
        }

        override fun checkpoint(sessionId: String): DiscoveryCheckpoint? {
            val header = db.query("checkpoints", arrayOf("epoch", "payload", "profile_id", "query_key"), "session_id=?", arrayOf(sessionId), null, null, null).use { c ->
                if (!c.moveToFirst()) null else DiscoveryCheckpointCodec.decode(c.getBlob(1)).also {
                    require(it.stamp.sessionId == sessionId && it.stamp.epoch == c.getLong(0) && it.pool.entries.isEmpty() && it.pool.pages.isEmpty())
                    if (!migrating) require(it.profile.profileId == c.getString(2) && it.profile.queryKey == c.getString(3))
                }
            } ?: return null
            val resultColumns = arrayOf("result_id", "page_url", "title", "source_name", "source_namespace", "source_video_id",
                "thumbnail", "duration", "description", "published_at", "provenance", "aliases", "identity_version", "discovered_at", "entry_order",
                "length(CAST(rights AS BLOB)) AS rights_bytes", "CASE WHEN typeof(rights)='blob' AND length(rights)<=32768 THEN rights ELSE NULL END AS rights")
            val entries = db.query("results", resultColumns, "session_id=?", arrayOf(sessionId), null, null, "entry_order", "501").use { c ->
                buildList { while (c.moveToNext()) {
                    require(size < 500)
                    add(PoolEntry(VideoSearchResult(c.string("result_id"), c.string("page_url"), c.nullText("title"), c.string("source_name"),
                        c.nullText("source_namespace"), c.nullText("source_video_id"), c.nullText("thumbnail"), c.nullLong("duration"),
                        c.nullText("description"), c.nullLong("published_at"), c.string("provenance").split('\n').toSet(),
                        c.string("aliases").split('\n').toSet(), c.int("identity_version"), rights = c.rights()), c.long("discovered_at"), c.long("entry_order")))
                } }
            }
            val pages = db.query("pages", null, "session_id=?", arrayOf(sessionId), null, null, "page_index", "501").use { c ->
                buildList { while (c.moveToNext()) {
                    require(size < 500)
                    add(PageSnapshot(header.stamp, c.long("revision"), c.int("page_index"), c.string("result_ids").split('\n'), c.int("page_size")))
                } }
            }
            return header.copy(pool = header.pool.copy(entries = entries, pages = pages)).also { DiscoveryCheckpointCodec.validate(it) }
        }
        override fun putCheckpoint(checkpoint: DiscoveryCheckpoint) {
            DiscoveryCheckpointCodec.validate(checkpoint)
            val session = checkpoint.stamp.sessionId
            // Refuse to replace a corrupt/unknown stored rights payload, even if a caller skipped restore.
            // The exception also rolls back any progress/Seen writes earlier in this transaction.
            this.checkpoint(session)
            upsert("checkpoints", ContentValues().apply {
                put("session_id", session); put("epoch", checkpoint.stamp.epoch)
                put("profile_id", checkpoint.profile.profileId); put("query_key", checkpoint.profile.queryKey)
                put("mutation_seq", touchProfile(checkpoint.profile.profileId)); writtenCheckpoints += session
                put("payload", DiscoveryCheckpointCodec.encode(checkpoint.copy(pool = checkpoint.pool.copy(entries = emptyList(), pages = emptyList()))))
            }, listOf("session_id"))
            checkpoint.pool.entries.forEach { entry ->
                val r = entry.result
                upsert("results", ContentValues().apply {
                    put("session_id", session); put("result_id", r.resultId); put("page_url", r.canonicalPageUrl); put("title", r.title)
                    put("source_name", r.sourceName); put("source_namespace", r.sourceNamespace); put("source_video_id", r.sourceVideoId)
                    put("thumbnail", r.thumbnailUrl); put("duration", r.durationMs); put("description", r.description); put("published_at", r.publishedAtEpochMillis)
                    put("provenance", r.provenance.sorted().joinToString("\n")); put("aliases", r.identityAliases.sorted().joinToString("\n"))
                    if (r.rights.snapshots.isEmpty() && r.rights.warnings.isEmpty()) putNull("rights") else put("rights", RightsCodec.encode(r.rights))
                    put("identity_version", r.identityVersion); put("discovered_at", entry.discoveredAtEpochMillis); put("entry_order", entry.order)
                }, listOf("session_id", "result_id"))
            }
            val ids = checkpoint.pool.entries.map { it.result.resultId }
            db.delete("results", "session_id=?" + if (ids.isEmpty()) "" else " AND result_id NOT IN (${ids.joinToString(",") { "?" }})", (listOf(session) + ids).toTypedArray())
            checkpoint.pool.pages.forEach { page ->
                upsert("pages", ContentValues().apply {
                    put("session_id", session); put("page_index", page.pageIndex); put("revision", page.revision)
                    put("page_size", page.pageSize); put("result_ids", page.orderedResultIds.joinToString("\n"))
                }, listOf("session_id", "page_index"))
            }
            db.delete("pages", "session_id=? AND page_index>=?", arrayOf(session, checkpoint.pool.pages.size.toString()))
            checkpoint.pool.entries.forEach { linkSeenAliases(checkpoint.profile.profileId, it.result.identityAliases + it.result.fingerprint) }
        }
        override fun removeCheckpoint(sessionId: String) {
            val profileId = db.rawQuery("SELECT profile_id FROM checkpoints WHERE session_id=?", arrayOf(sessionId)).use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: return
            touchProfile(profileId)
            listOf("checkpoints", "results", "pages", "viewport").forEach { db.delete(it, "session_id=?", arrayOf(sessionId)) }
        }
        /** SQLite 3.32+ on API31: unchanged rows do not trigger a write/WAL update. */
        private fun upsert(table: String, values: ContentValues, keys: List<String>) {
            val columns = values.keySet().sorted()
            val changed = columns.filterNot { it in keys }
            val sql = "INSERT INTO $table (${columns.joinToString(",")}) VALUES (${columns.joinToString(",") { "?" }}) " +
                "ON CONFLICT(${keys.joinToString(",")}) DO UPDATE SET " + changed.joinToString(",") { "$it=excluded.$it" } +
                " WHERE " + changed.joinToString(" OR ") { "$it IS NOT excluded.$it" }
            db.execSQL(sql, columns.map { values[it] }.toTypedArray())
        }
        override fun progress(profileId: String, providerId: String, queryKey: String): ProviderProgress? = db.query("progress", null,
            "profile_id=? AND provider_id=? AND query_key=?", arrayOf(profileId, providerId, queryKey), null, null, null).use { c ->
            if (!c.moveToFirst()) null else ProviderProgress(c.string("profile_id"), c.string("provider_id"), c.string("query_key"), c.string("provider_version"),
                c.int("cursor_schema"), c.nullText("cursor"), c.nullLong("expires_at"), c.int("exhausted").also { require(it in 0..1) } == 1, c.nullLong("last_success"))
        }
        override fun putProgress(progress: ProviderProgress) {
            require(progress.queryKey.matches(Regex("[a-f0-9]{64}")))
            db.insertWithOnConflict("progress", null, ContentValues().apply {
                put("profile_id", progress.profileId); put("provider_id", progress.providerId); put("query_key", progress.queryKey)
                put("mutation_seq", touchProfile(progress.profileId))
                put("provider_version", progress.providerVersion); put("cursor_schema", progress.cursorSchemaVersion); put("cursor", progress.cursor)
                put("expires_at", progress.expiresAtEpochMillis); put("exhausted", if (progress.exhausted) 1 else 0); put("last_success", progress.lastSuccessAtEpochMillis)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        }
        override fun resetProgress(profileId: String, providerId: String?, queryKey: String?) {
            val args = mutableListOf(profileId); val where = StringBuilder("profile_id=?")
            providerId?.let { where.append(" AND provider_id=?"); args += it }; queryKey?.let { where.append(" AND query_key=?"); args += it }
            if (db.delete("progress", where.toString(), args.toTypedArray()) > 0) touchProfile(profileId)
        }
        override fun seen(profileId: String, fingerprint: String): SeenRecord? = db.query("seen", null,
            "profile_id=? AND fingerprint=?", arrayOf(profileId, fingerprint), null, null, null).use { c ->
            if (!c.moveToFirst()) null else SeenRecord(c.string("profile_id"), c.string("fingerprint"), c.long("first_discovered"),
                c.nullLong("shown_at"), c.nullLong("opened_at"), c.nullLong("played_at"), c.nullLong("saved_at"), c.nullLong("dismissed_at"))
        }
        override fun putSeen(record: SeenRecord) {
            val group = db.rawQuery("SELECT identity_id FROM seen WHERE profile_id=? AND fingerprint=?", arrayOf(record.profileId, record.fingerprint)).use {
                if (it.moveToFirst()) it.getString(0) else record.fingerprint
            }
            val existing = groupFacts(record.profileId, setOf(group))
            val merged = mergeFacts(record.profileId, record.fingerprint, listOfNotNull(existing, record))
            val seq = touchProfile(record.profileId)
            upsert("seen", factsValues(merged).apply { put("identity_id", group); put("mutation_seq", seq) }, listOf("profile_id", "fingerprint"))
            updateGroup(record.profileId, setOf(group), group, merged, seq)
        }
        override fun linkSeenAliases(profileId: String, aliases: Set<String>) {
            if (aliases.isEmpty()) return
            require(aliases.size <= 128)
            val groups = db.rawQuery("SELECT DISTINCT identity_id FROM seen WHERE profile_id=? AND fingerprint IN (${aliases.joinToString(",") { "?" }})",
                (listOf(profileId) + aliases.sorted()).toTypedArray()).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            if (groups.size < 2) return
            val group = groups.minOrNull()!!
            val facts = checkNotNull(groupFacts(profileId, groups))
            updateGroup(profileId, groups, group, facts, touchProfile(profileId))
        }
        private fun groupFacts(profileId: String, groups: Set<String>): SeenRecord? = db.rawQuery(
            "SELECT MIN(first_discovered),MAX(shown_at),MAX(opened_at),MAX(played_at),MAX(saved_at),MAX(dismissed_at) FROM seen WHERE profile_id=? AND identity_id IN (${groups.joinToString(",") { "?" }})",
            (listOf(profileId) + groups.sorted()).toTypedArray()).use { c ->
            if (!c.moveToFirst() || c.isNull(0)) null else SeenRecord(profileId, groups.minOrNull()!!, c.getLong(0),
                c.optionalLong(1), c.optionalLong(2), c.optionalLong(3), c.optionalLong(4), c.optionalLong(5))
        }
        private fun mergeFacts(profileId: String, fingerprint: String, facts: List<SeenRecord>): SeenRecord = SeenRecord(profileId, fingerprint,
            facts.minOf { it.firstDiscoveredAt }, facts.mapNotNull { it.shownAt }.maxOrNull(), facts.mapNotNull { it.openedAt }.maxOrNull(),
            facts.mapNotNull { it.playedAt }.maxOrNull(), facts.mapNotNull { it.savedAt }.maxOrNull(), facts.mapNotNull { it.dismissedAt }.maxOrNull())
        private fun factsValues(record: SeenRecord) = ContentValues().apply {
            put("profile_id", record.profileId); put("fingerprint", record.fingerprint); put("first_discovered", record.firstDiscoveredAt)
            put("shown_at", record.shownAt); put("opened_at", record.openedAt); put("played_at", record.playedAt); put("saved_at", record.savedAt); put("dismissed_at", record.dismissedAt)
        }
        private fun updateGroup(profileId: String, groups: Set<String>, identityId: String, facts: SeenRecord, seq: Long) {
            val values = factsValues(facts).apply { remove("profile_id"); remove("fingerprint"); put("identity_id", identityId); put("mutation_seq", seq) }
            db.update("seen", values, "profile_id=? AND identity_id IN (${groups.joinToString(",") { "?" }})", (listOf(profileId) + groups.sorted()).toTypedArray())
        }
        override fun clearSeen(profileId: String?) {
            val where = if (profileId == null) null else "profile_id=?"
            val args = profileId?.let { arrayOf(it) }
            val affected = db.query(true, "seen", arrayOf("profile_id"), where, args, null, null, null, null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            affected.forEach(::touchProfile)
            db.delete("seen", where, args)
            // Every matching session gets a barrier, including empty history. Old callbacks cannot
            // recreate cleared facts through another coordinator that still holds an old revision.
            val sessions = db.query("checkpoints", arrayOf("session_id"), where, args, null, null, "session_id").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            for (session in sessions) {
                val checkpoint = checkNotNull(checkpoint(session))
                check(checkpoint.pool.viewRevision < Long.MAX_VALUE)
                val revision = checkpoint.pool.viewRevision + 1
                putCheckpoint(checkpoint.copy(pool = checkpoint.pool.copy(viewRevision = revision,
                    pages = checkpoint.pool.pages.map { it.copy(revision = revision) })))
            }
        }
        private fun Cursor.optionalLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
        private fun Cursor.rights(): ResultRights {
            val size = nullLong("rights_bytes") ?: return ResultRights()
            require(size in 1..RightsCodec.MAX_BYTES.toLong())
            val index = getColumnIndexOrThrow("rights")
            require(!isNull(index)) // A non-BLOB value in SQLite's dynamic type system is corruption.
            return RightsCodec.decode(getBlob(index))
        }
        private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
        private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
        private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
        private fun Cursor.nullLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
        private fun Cursor.nullText(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    }
}
