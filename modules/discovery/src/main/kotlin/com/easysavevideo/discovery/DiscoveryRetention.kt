package com.easysavevideo.discovery

/** Capacity-only retention. No wall-clock expiry and no access-time writes. */
data class DiscoveryRetentionPolicy(
    val maxProfiles: Int = 32,
    val maxSeenAliases: Int = 65_536,
    val maxProgress: Int = 512,
    val maxCheckpoints: Int = 8,
) {
    init { require(maxProfiles > 0); require(maxSeenAliases > 0); require(maxProgress > 0); require(maxCheckpoints > 0) }
}

/** Local counts only: never contains queries, cursor values, titles, URLs, or event timestamps. */
data class HistoryStatistics(
    val profileCount: Int,
    val checkpointCount: Int,
    val seenIdentityCount: Int,
    val seenAliasCount: Int,
    val progressCount: Int,
)

data class RetentionProfile(val profileId: String, val lastMutationSequence: Long)
data class RetentionSeenKey(val profileId: String, val fingerprint: String)
data class RetentionSeenAlias(
    val profileId: String,
    val fingerprint: String,
    val identityId: String,
    val lastMutationSequence: Long,
)
data class RetentionProgressKey(val profileId: String, val providerId: String, val queryKey: String)
data class RetentionProgress(val key: RetentionProgressKey, val lastMutationSequence: Long)
data class RetentionCheckpoint(
    val sessionId: String,
    val profileId: String,
    val queryKey: String,
    val lastMutationSequence: Long,
    val protectedAliases: Set<String> = emptySet(),
    /** Materialized checkpoints and checkpoints written in this transaction cannot be evicted. */
    val isProtected: Boolean = false,
)
data class DiscoveryRetentionSnapshot(
    val profiles: List<RetentionProfile>,
    val seenAliases: List<RetentionSeenAlias>,
    val progress: List<RetentionProgress>,
    val checkpoints: List<RetentionCheckpoint>,
)
data class DiscoveryRetentionPlan(
    val removeProfiles: Set<String>,
    val removeSeenAliases: Set<RetentionSeenKey>,
    val removeProgress: Set<RetentionProgressKey>,
    val removeCheckpoints: Set<String>,
)

/**
 * Shared by durable and isolated stores. Apply every deletion within the writing transaction.
 * Profile LRU runs first, then checkpoints, whole explicitly linked identities, then progress.
 * Recency is an increasing mutation sequence; reads cannot change it. Lexical keys settle ties.
 * Every materialized checkpoint's profile, alias-group closure and query progress is protected.
 * No protected fact is sacrificed: impossible capacity throws and the entire write rolls back.
 * Eviction may allow an old result to reappear. It never manufactures or upgrades a Seen event.
 */
object DiscoveryRetentionPlanner {
    fun plan(snapshot: DiscoveryRetentionSnapshot, policy: DiscoveryRetentionPolicy): DiscoveryRetentionPlan {
        val protectedCheckpoints = snapshot.checkpoints.filter { it.isProtected }
        val protectedProfiles = protectedCheckpoints.map { it.profileId }.toSet()
        val profileIds = (snapshot.checkpoints.map { it.profileId } + snapshot.seenAliases.map { it.profileId } +
            snapshot.progress.map { it.key.profileId }).toSet()
        val profileSequence = snapshot.profiles.associate { it.profileId to it.lastMutationSequence }
        val removeProfiles = linkedSetOf<String>()
        for (id in profileIds.sortedWith(compareBy<String> { profileSequence[it] ?: 0L }.thenBy { it })) {
            if (profileIds.size - removeProfiles.size <= policy.maxProfiles) break
            if (id !in protectedProfiles) removeProfiles += id
        }
        requireCapacity(profileIds.size - removeProfiles.size <= policy.maxProfiles)

        val checkpoints = snapshot.checkpoints.filter { it.profileId !in removeProfiles }
        val removeCheckpoints = snapshot.checkpoints.filter { it.profileId in removeProfiles }.mapTo(linkedSetOf()) { it.sessionId }
        var checkpointCount = checkpoints.size
        for (checkpoint in checkpoints.sortedWith(compareBy<RetentionCheckpoint> { it.lastMutationSequence }.thenBy { it.sessionId })) {
            if (checkpointCount <= policy.maxCheckpoints) break
            if (!checkpoint.isProtected) { removeCheckpoints += checkpoint.sessionId; checkpointCount-- }
        }
        requireCapacity(checkpointCount <= policy.maxCheckpoints)

        val protectedAliases = protectedCheckpoints.flatMap { checkpoint ->
            checkpoint.protectedAliases.map { RetentionSeenKey(checkpoint.profileId, it) }
        }.toSet()
        val seenAliases = snapshot.seenAliases.filter { it.profileId !in removeProfiles }
        val groups = seenAliases.groupBy { it.profileId to it.identityId }
        val groupOrder = groups.entries.sortedWith(compareBy<Map.Entry<Pair<String, String>, List<RetentionSeenAlias>>> {
            it.value.maxOf { alias -> alias.lastMutationSequence }
        }.thenBy { it.key.first }.thenBy { it.key.second })
        val removeSeenAliases = snapshot.seenAliases.filter { it.profileId in removeProfiles }
            .mapTo(linkedSetOf()) { RetentionSeenKey(it.profileId, it.fingerprint) }
        var aliasCount = seenAliases.size
        for ((_, aliases) in groupOrder) {
            if (aliasCount <= policy.maxSeenAliases) break
            if (aliases.none { RetentionSeenKey(it.profileId, it.fingerprint) in protectedAliases }) {
                aliases.mapTo(removeSeenAliases) { RetentionSeenKey(it.profileId, it.fingerprint) }
                aliasCount -= aliases.size
            }
        }
        requireCapacity(aliasCount <= policy.maxSeenAliases)

        val protectedQueries = protectedCheckpoints.map { it.profileId to it.queryKey }.toSet()
        val progress = snapshot.progress.filter { it.key.profileId !in removeProfiles }
        val removeProgress = snapshot.progress.filter { it.key.profileId in removeProfiles }.mapTo(linkedSetOf()) { it.key }
        var progressCount = progress.size
        for (row in progress.sortedWith(compareBy<RetentionProgress> { it.lastMutationSequence }
            .thenBy { it.key.profileId }.thenBy { it.key.providerId }.thenBy { it.key.queryKey })) {
            if (progressCount <= policy.maxProgress) break
            if ((row.key.profileId to row.key.queryKey) !in protectedQueries) { removeProgress += row.key; progressCount-- }
        }
        requireCapacity(progressCount <= policy.maxProgress)
        return DiscoveryRetentionPlan(removeProfiles, removeSeenAliases, removeProgress, removeCheckpoints)
    }

    private fun requireCapacity(value: Boolean) {
        if (!value) throw DiscoveryFailure(DiscoveryError.CAPACITY_REACHED)
    }
}
