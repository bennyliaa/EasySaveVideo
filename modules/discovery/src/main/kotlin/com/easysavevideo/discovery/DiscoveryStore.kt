package com.easysavevideo.discovery

data class DiscoveryCheckpoint(val stamp: SessionStamp, val profile: SearchProfile, val pool: PoolState = PoolState(),
    val sourceConfigurationKey: String? = null,
    val queryAuthorized: Boolean = true,
) {
    init { require(sourceConfigurationKey == null || sourceConfigurationKey.matches(Regex("[a-f0-9]{64}"))) }
    override fun toString(): String = "DiscoveryCheckpoint([redacted])"
}

/**
 * A durable adapter must atomically commit the checkpoint (results + dedup aliases), progress and Seen facts.
 * On any thrown exception all writes roll back. The block is synchronous and must not call network/UI work.
 * Values returned from an implementation must be detached snapshots, never live mutable database views.
 * One live VideoDiscoveryCoordinator is supported per store. History revisions additionally fence stale
 * restored readers, but independent concurrent coordinators are not a supported provider-reset topology.
 */
interface DiscoveryStore {
    fun <T> transaction(block: DiscoveryTransaction.() -> T): T
}
interface DiscoveryTransaction {
    /** Non-mutating commit guard, evaluated after retention and immediately before publication. */
    fun beforeCommit(check: () -> Unit)
    fun checkpoint(sessionId: String): DiscoveryCheckpoint?
    fun putCheckpoint(checkpoint: DiscoveryCheckpoint)
    fun removeCheckpoint(sessionId: String)
    fun progress(profileId: String, providerId: String, queryKey: String): ProviderProgress?
    fun putProgress(progress: ProviderProgress)
    fun resetProgress(profileId: String, providerId: String? = null, queryKey: String? = null)
    fun seen(profileId: String, fingerprint: String): SeenRecord?
    /** Merge independent event facts monotonically across every explicitly linked alias. */
    fun putSeen(record: SeenRecord)
    /** Link existing facts only. Unknown aliases do not become discovered or Seen records. */
    fun linkSeenAliases(profileId: String, aliases: Set<String>)
    /** A count-only read. It never prunes, updates recency, or initiates provider work. */
    fun historyStatistics(profileId: String? = null): HistoryStatistics
    /** Clear facts and atomically revise every matching checkpoint, fencing old page/provider callbacks. */
    fun clearSeen(profileId: String? = null)
}

/** Non-durable implementation for local contracts and isolated incognito sessions. Not a persistence claim. */
class InMemoryDiscoveryStore(private val retentionPolicy: DiscoveryRetentionPolicy = DiscoveryRetentionPolicy()) : DiscoveryStore {
    private var checkpoints = mutableMapOf<String, DiscoveryCheckpoint>()
    private var progress = mutableMapOf<RetentionProgressKey, ProviderProgress>()
    private var seen = mutableMapOf<RetentionSeenKey, SeenRecord>()
    private var identities = mutableMapOf<RetentionSeenKey, String>()
    private var profileSequences = mutableMapOf<String, Long>()
    private var checkpointSequences = mutableMapOf<String, Long>()
    private var progressSequences = mutableMapOf<RetentionProgressKey, Long>()
    private var seenSequences = mutableMapOf<RetentionSeenKey, Long>()
    private var mutationSequence = 0L

    @Synchronized override fun <T> transaction(block: DiscoveryTransaction.() -> T): T {
        val nextCheckpoints = checkpoints.toMutableMap()
        val nextProgress = progress.toMutableMap()
        val nextSeen = seen.toMutableMap()
        val nextIdentities = identities.toMutableMap()
        val nextProfileSequences = profileSequences.toMutableMap()
        val nextCheckpointSequences = checkpointSequences.toMutableMap()
        val nextProgressSequences = progressSequences.toMutableMap()
        val nextSeenSequences = seenSequences.toMutableMap()
        val writtenCheckpoints = mutableSetOf<String>()
        val commitGuards = mutableListOf<() -> Unit>()
        var sequence: Long? = null
        fun touch(profileId: String): Long {
            val value = sequence ?: Math.addExact(mutationSequence, 1).also { sequence = it }
            nextProfileSequences[profileId] = value
            return value
        }
        fun occupiedProfiles(): Set<String> = (nextCheckpoints.values.map { it.profile.profileId } +
            nextProgress.keys.map { it.profileId } + nextSeen.keys.map { it.profileId }).toSet()
        val tx = object : DiscoveryTransaction {
            override fun beforeCommit(check: () -> Unit) { commitGuards += check }
            override fun checkpoint(sessionId: String) = nextCheckpoints[sessionId]?.detached()
            override fun putCheckpoint(checkpoint: DiscoveryCheckpoint) {
                require(checkpoint.pool.entries.size <= 500 && checkpoint.pool.pages.size <= 500)
                checkpoint.pool.entries.forEach { RightsCodec.validate(it.result.rights) }
                nextCheckpointSequences[checkpoint.stamp.sessionId] = touch(checkpoint.profile.profileId)
                nextCheckpoints[checkpoint.stamp.sessionId] = checkpoint.detached()
                writtenCheckpoints += checkpoint.stamp.sessionId
                checkpoint.pool.entries.forEach { linkSeenAliases(checkpoint.profile.profileId, it.result.identityAliases + it.result.fingerprint) }
            }
            override fun removeCheckpoint(sessionId: String) {
                val old = nextCheckpoints.remove(sessionId) ?: return
                touch(old.profile.profileId); nextCheckpointSequences.remove(sessionId)
            }
            override fun progress(profileId: String, providerId: String, queryKey: String) =
                nextProgress[RetentionProgressKey(profileId, providerId, queryKey)]
            override fun putProgress(progress: ProviderProgress) {
                val key = RetentionProgressKey(progress.profileId, progress.providerId, progress.queryKey)
                nextProgressSequences[key] = touch(progress.profileId); nextProgress[key] = progress
            }
            override fun resetProgress(profileId: String, providerId: String?, queryKey: String?) {
                val keys = nextProgress.keys.filter { it.profileId == profileId &&
                    (providerId == null || it.providerId == providerId) && (queryKey == null || it.queryKey == queryKey) }
                if (keys.isEmpty()) return
                touch(profileId); keys.forEach { nextProgress.remove(it); nextProgressSequences.remove(it) }
            }
            override fun seen(profileId: String, fingerprint: String) = nextSeen[RetentionSeenKey(profileId, fingerprint)]
            override fun putSeen(record: SeenRecord) {
                val key = RetentionSeenKey(record.profileId, record.fingerprint)
                val identity = nextIdentities[key] ?: record.fingerprint
                val group = nextIdentities.filter { it.key.profileId == record.profileId && it.value == identity }.keys + key
                val merged = mergeSeenRecords(record.profileId, record.fingerprint, group.mapNotNull(nextSeen::get) + record)!!
                val touched = touch(record.profileId)
                group.forEach {
                    nextSeen[it] = merged.copy(fingerprint = it.fingerprint)
                    nextIdentities[it] = identity; nextSeenSequences[it] = touched
                }
            }
            override fun linkSeenAliases(profileId: String, aliases: Set<String>) {
                require(aliases.size <= 128)
                val oldIdentities = aliases.mapNotNull { nextIdentities[RetentionSeenKey(profileId, it)] }.toSet()
                if (oldIdentities.size < 2) return
                val group = nextIdentities.filter { it.key.profileId == profileId && it.value in oldIdentities }.keys
                val identity = group.minOf { it.fingerprint }
                val merged = mergeSeenRecords(profileId, identity, group.mapNotNull(nextSeen::get))!!
                val touched = touch(profileId)
                group.forEach {
                    nextIdentities[it] = identity; nextSeen[it] = merged.copy(fingerprint = it.fingerprint)
                    nextSeenSequences[it] = touched
                }
            }
            override fun clearSeen(profileId: String?) {
                val keys = nextSeen.keys.filter { profileId == null || it.profileId == profileId }
                keys.map { it.profileId }.distinct().forEach(::touch)
                keys.forEach { nextSeen.remove(it); nextIdentities.remove(it); nextSeenSequences.remove(it) }
                nextCheckpoints.values.filter { profileId == null || it.profile.profileId == profileId }.toList().forEach { checkpoint ->
                    val revision = Math.addExact(checkpoint.pool.viewRevision, 1)
                    putCheckpoint(checkpoint.copy(pool = checkpoint.pool.copy(viewRevision = revision,
                        pages = checkpoint.pool.pages.map { it.copy(revision = revision) })))
                }
            }
            override fun historyStatistics(profileId: String?): HistoryStatistics {
                val scopedSeen = nextSeen.keys.filter { profileId == null || it.profileId == profileId }
                return HistoryStatistics(occupiedProfiles().count { profileId == null || it == profileId },
                    nextCheckpoints.values.count { profileId == null || it.profile.profileId == profileId },
                    scopedSeen.map { it.profileId to nextIdentities.getValue(it) }.toSet().size, scopedSeen.size,
                    nextProgress.keys.count { profileId == null || it.profileId == profileId })
            }
        }
        val result = tx.block()
        if (sequence == null) { commitGuards.forEach { it() }; return result }
        val snapshot = DiscoveryRetentionSnapshot(
            nextProfileSequences.map { RetentionProfile(it.key, it.value) },
            nextSeen.keys.map { RetentionSeenAlias(it.profileId, it.fingerprint, nextIdentities.getValue(it), nextSeenSequences.getValue(it)) },
            nextProgress.keys.map { RetentionProgress(it, nextProgressSequences.getValue(it)) },
            nextCheckpoints.values.map { checkpoint ->
                val materialized = checkpoint.pool.pages.flatMap { it.orderedResultIds }.toSet()
                RetentionCheckpoint(checkpoint.stamp.sessionId, checkpoint.profile.profileId, checkpoint.profile.queryKey,
                    nextCheckpointSequences.getValue(checkpoint.stamp.sessionId),
                    checkpoint.pool.entries.filter { it.result.resultId in materialized }
                        .flatMap { it.result.identityAliases + it.result.fingerprint }.toSet(),
                    checkpoint.pool.pages.isNotEmpty() || checkpoint.stamp.sessionId in writtenCheckpoints)
            })
        val plan = DiscoveryRetentionPlanner.plan(snapshot, retentionPolicy)
        plan.removeCheckpoints.forEach { nextCheckpoints.remove(it); nextCheckpointSequences.remove(it) }
        plan.removeProgress.forEach { nextProgress.remove(it); nextProgressSequences.remove(it) }
        plan.removeSeenAliases.forEach { nextSeen.remove(it); nextIdentities.remove(it); nextSeenSequences.remove(it) }
        nextProfileSequences.keys.retainAll(occupiedProfiles())
        commitGuards.forEach { it() }
        checkpoints = nextCheckpoints; progress = nextProgress; seen = nextSeen; identities = nextIdentities
        profileSequences = nextProfileSequences; checkpointSequences = nextCheckpointSequences
        progressSequences = nextProgressSequences; seenSequences = nextSeenSequences; mutationSequence = sequence!!
        return result
    }
    @Synchronized fun clear() {
        checkpoints.clear(); progress.clear(); seen.clear(); identities.clear(); profileSequences.clear()
        checkpointSequences.clear(); progressSequences.clear(); seenSequences.clear(); mutationSequence = 0
    }
}

internal fun DiscoveryCheckpoint.detached() = copy(
    profile = profile.copy(filters = profile.filters.copy(sourceNamespaces = profile.filters.sourceNamespaces.toSet())),
    pool = pool.copy(entries = pool.entries.map { it.copy(result = it.result.copy(provenance = it.result.provenance.toSet(), identityAliases = it.result.identityAliases.toSet(), rights = it.result.rights.detached())) },
        pages = pool.pages.map { it.copy(orderedResultIds = it.orderedResultIds.toList()) }))
