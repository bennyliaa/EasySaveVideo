package com.easysavevideo.discovery

import com.easysavevideo.core.model.DiscoveryMode
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.RepeatPolicy
import com.easysavevideo.core.model.ResourceBudget
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Serialized coordinator. Runtime.execute and cancellation hooks must return promptly; provider search
 * and parsing run in the isolated runtime. A durable store is injected separately by Android's data layer.
 * No Provider, candidate, or discovery event can start a save or recording.
 */
class VideoDiscoveryCoordinator(
    private val registry: ProviderRegistry,
    private val runtime: ProviderRuntime,
    private val durableStore: DiscoveryStore,
    private val timer: DiscoveryTimer,
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
    private var limits: DiscoveryLimits = DiscoveryLimits(),
    private val sourceConfigurationKey: String? = null,
    private val retryDelayMillis: (Int) -> Long = { attempt -> (if (attempt == 1) 1000L else 3000L) + ThreadLocalRandom.current().nextLong(251) },
) : AutoCloseable {
    private data class ActiveRequest(
        val provider: VideoSearchProvider,
        val descriptor: ProviderDescriptor,
        val request: SearchRequest,
        val viewRevision: Long,
        var operation: DiscoveryOperation,
        var handle: CancellationHandle? = null,
        var retry: CancellationHandle? = null,
        var inFlight: Boolean = false,
        val explicitCandidateId: String? = null,
        val summaryGeneration: Long = 0,
    )
    private val summaryFence = VerificationSummaryFence()
    private val lock = Any()
    private var store: DiscoveryStore = durableStore
    private var incognitoStore: InMemoryDiscoveryStore? = null
    private var cachedAvailable: Int? = null
    private var cachedProgress: Map<String, ProviderProgress?>? = null
    private var current: DiscoveryCheckpoint? = null
        set(value) { field = value; cachedAvailable = null; cachedProgress = null }
    private var epoch = 0L
    private var operationSequence = 0L
    private val scheduler = DiscoveryScheduler(limits)
    private val pool = ResultPool(limits.poolCapacity)
    private val pendingPool = PendingCandidatePool(limits.poolCapacity)
    private val sessionProgress = mutableMapOf<String, ProviderProgress>()
    init { require(sourceConfigurationKey == null || sourceConfigurationKey.matches(Regex("[a-f0-9]{64}"))) }
    private val active = ConcurrentHashMap<String, ActiveRequest>()
    private val lifecycleSuspensionGeneration = AtomicLong(0)
    private val appliedLifecycleSuspensionGeneration = AtomicLong(0)
    private val lifecycleUpdatePending: Boolean get() = lifecycleSuspensionGeneration.get() != appliedLifecycleSuspensionGeneration.get()
    private val powerSuspensionGeneration = AtomicLong(0)
    private val appliedPowerSuspensionGeneration = AtomicLong(0)
    private val powerPolicyUpdatePending: Boolean get() = powerSuspensionGeneration.get() != appliedPowerSuspensionGeneration.get()
    private val sourceConfigurationSuspended = java.util.concurrent.atomic.AtomicBoolean(false)
    private val gateOpen: Boolean get() = scheduler.gateOpen && !lifecycleUpdatePending && !powerPolicyUpdatePending &&
        !sourceConfigurationSuspended.get()
    private val health = mutableMapOf<String, ProviderHealth>()
    private data class NoNewPages(val queryKey: String, val providerVersion: String, val count: Int)
    private val noNewPages = mutableMapOf<String, NoNewPages>()
    private var pumping = false
    private var roundRobin = 0
    private var terminalState: DiscoveryState? = null
    private var lastError: DiscoveryError? = null
    private var closed = false

    fun start(sessionId: String, profile: SearchProfile, incognito: Boolean = false): SessionStamp = synchronized(lock) {
        check(!closed)
        cancelAll(); pendingPool.clear(); explicitlyAttempted.clear(); sessionProgress.clear()
        incognitoStore?.clear()
        incognitoStore = if (incognito) InMemoryDiscoveryStore() else null
        store = incognitoStore ?: durableStore
        val previousEpoch = try { store.transaction { checkpoint(sessionId)?.stamp?.epoch ?: 0 } }
        catch (_: Exception) { current = null; failStorage(); throw DiscoveryFailure(DiscoveryError.STORAGE_UNAVAILABLE) }
        epoch = maxOf(epoch, previousEpoch) + 1
        val stamp = SessionStamp(sessionId, epoch)
        val checkpoint = DiscoveryCheckpoint(stamp, profile.copy(filters = profile.filters.copy(sourceNamespaces = profile.filters.sourceNamespaces.toSet())),
            sourceConfigurationKey = sourceConfigurationKey)
        try { store.transaction { putCheckpoint(checkpoint) }; current = checkpoint; terminalState = null; lastError = null
        } catch (failure: Exception) { current = null; failStoreOperation(failure) }
        noNewPages.clear()
        if (current != null) { beginRound(); pump() }
        stamp
    }

    /** Resume only a complete committed checkpoint. In-flight operations are never restored. */
    fun restore(sessionId: String, requireExplicitSearch: Boolean = false): Boolean = synchronized(lock) {
        check(!closed); cancelAll(); pendingPool.clear(); explicitlyAttempted.clear(); sessionProgress.clear(); current = null; incognitoStore?.clear(); incognitoStore = null; store = durableStore
        try {
            val previous = store.transaction { checkpoint(sessionId) } ?: return false
            epoch = maxOf(epoch, previous.stamp.epoch) + 1
            val changed = previous.sourceConfigurationKey != sourceConfigurationKey || requireExplicitSearch
            current = previous.copy(stamp = SessionStamp(sessionId, epoch),
                pool = if (changed) PoolState() else previous.pool.copy(
                    pages = previous.pool.pages.map { it.copy(stamp = SessionStamp(sessionId, epoch)) }),
                sourceConfigurationKey = sourceConfigurationKey,
                queryAuthorized = previous.queryAuthorized && !changed)
            store.transaction {
                if (changed) resetProgress(previous.profile.profileId)
                putCheckpoint(current!!)
            }
            terminalState = null; lastError = null; noNewPages.clear(); beginRound(); pump(); true
        } catch (failure: Exception) { current = null; failStoreOperation(failure); false }
    }

    /** Capture this generation before reading adapter foreground/visibility/media-priority flags. */
    fun currentSuspensionGeneration(): Long = lifecycleSuspensionGeneration.get()

    fun suspendImmediately(): Long {
        summaryFence.invalidate()
        val generation = lifecycleSuspensionGeneration.incrementAndGet()
        active.values.forEach { it.operation.cancellation.cancel() }
        return generation
    }

    /** Nonblocking fence for adapters about to enqueue a policy change, even during a store commit.
     * Pass the returned generation with its snapshot if the adapter does not coalesce to latest state.
     * This latch is separate from lifecycle and cannot reopen foreground/visibility/Save/Record gates.
     */
    fun suspendPowerImmediately(): Long {
        summaryFence.invalidate()
        val generation = powerSuspensionGeneration.incrementAndGet()
        active.values.forEach { it.operation.cancellation.cancel() }
        return generation
    }

    /** A changed observation (or an immediate fence) retires automatic demand, without refunding
     * attempts. Relaxation never launches work. Another explicit user-demand action is required.
     * Generation fencing keeps a newer immediate suspension closed if an older update was queued.
     */
    fun setPowerSignals(signals: DiscoveryPowerSignals, suspensionGeneration: Long = powerSuspensionGeneration.get()) = synchronized(lock) {
        require(suspensionGeneration >= 0 && suspensionGeneration <= powerSuspensionGeneration.get())
        if (closed || suspensionGeneration < appliedPowerSuspensionGeneration.get()) return
        val fenced = suspensionGeneration != appliedPowerSuspensionGeneration.get()
        if (scheduler.powerSignals == signals && !fenced) return
        scheduler.powerSignals(signals)
        cancelAll()
        appliedPowerSuspensionGeneration.set(suspensionGeneration)
    }

    fun currentCheckpoint(): DiscoveryCheckpoint? = synchronized(lock) { current?.detached() }
    fun pendingCandidates(): List<PendingVideoCandidate> = synchronized(lock) {
        if (closed || current == null) emptyList() else pendingPool.snapshot()
    }
    /** Explicit demand uses the existing runtime/pipeline, scheduler budget and commit fences.
     * One attempt per candidate per session; repeated clicks never refund or resend requests. */
    private val explicitlyAttempted = mutableSetOf<String>()
    fun invalidateVerificationSummary() { summaryFence.invalidate() }
    fun verificationGeneration(): Long = summaryFence.generation
    fun verificationSummary(): VerificationSummary? = summaryFence.snapshot()
    fun verifyPending(stamp: SessionStamp, candidateId: String): Boolean = synchronized(lock) {
        val checkpoint = current ?: return false
        if (closed || !gateOpen || checkpoint.stamp != stamp || !checkpoint.queryAuthorized ||
            candidateId in explicitlyAttempted || explicitlyAttempted.size >= limits.poolCapacity) return false
        val candidate = pendingPool.snapshot().find { it.candidateId == candidateId } ?: return false
        val source = registry.all().find { it.descriptor.id in candidate.provenance && !active.containsKey(it.descriptor.id) && it.refusal(checkpoint.profile) == null } ?: return false
        if (!scheduler.roundActive) scheduler.beginRound(availableCount())
        if (!scheduler.canLaunch(source.descriptor.id, availableCount())) return false
        val provider = object : VideoSearchProvider {
            override val descriptor = source.descriptor
            override fun refusal(profile: SearchProfile): DiscoveryError? = source.refusal(profile)
            override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage =
                ProviderPage(candidates = listOf(CandidateUrl(candidate.canonicalPageUrl, candidate.sourceReported)), exhausted = true)
        }
        val cancellation = DiscoveryCancellation()
        val operation = DiscoveryOperation(OperationContext("discovery-${++operationSequence}",
            clock.nowMillis() + minOf(4000L, limits.requestDeadlineMillis), cancellation, 0,
            resourceBudget = ResourceBudget(1, limits.maxResponseBytes)), cancellation)
        val request = ActiveRequest(provider, provider.descriptor, SearchRequest(checkpoint.stamp, checkpoint.profile, null),
            checkpoint.pool.viewRevision, operation, explicitCandidateId = candidateId, summaryGeneration = summaryFence.generation)
        explicitlyAttempted += candidateId
        pendingPool.mark(candidateId, true)
        active[provider.descriptor.id] = request
        launch(request, availableCount())
        if (!request.inFlight) pendingPool.mark(candidateId, false)
        true
    }
    /** Irreversible for this coordinator: lifecycle/power callbacks cannot reopen an edited source set. */
    fun suspendSourcesImmediately() {
        summaryFence.invalidate()
        sourceConfigurationSuspended.set(true)
        active.values.forEach { it.operation.cancellation.cancel() }
    }

    /** A queued resume cannot clear a newer immediate foreground or Save/Record suspension. */
    fun setVisibility(foreground: Boolean, discoveryVisible: Boolean,
        suspensionGeneration: Long = currentSuspensionGeneration()) = synchronized(lock) {
        require(suspensionGeneration >= 0 && suspensionGeneration <= lifecycleSuspensionGeneration.get())
        if (suspensionGeneration < appliedLifecycleSuspensionGeneration.get()) return
        val wasOpen = gateOpen
        scheduler.visibility(foreground, discoveryVisible)
        appliedLifecycleSuspensionGeneration.set(suspensionGeneration)
        if (!gateOpen) cancelAll(endRound = false)
        else if (!wasOpen && !closed) {
            // Immediate suspension may precede a coalesced UI resume. Retire the cancelled
            // operation/accounting before reopening; its late failure is not source health.
            cancelAll(endRound = false)
            if (scheduler.roundActive) terminalState = null
            pump()
        }
    }
    fun setPriority(saveActive: Boolean, recordingActive: Boolean) = synchronized(lock) {
        val wasOpen = gateOpen
        scheduler.priority(saveActive, recordingActive)
        if (!gateOpen) cancelAll(endRound = false)
        else if (!wasOpen && !closed) {
            // Immediate suspension may precede a coalesced UI resume. Retire the cancelled
            // operation/accounting before reopening; its late failure is not source health.
            cancelAll(endRound = false)
            if (scheduler.roundActive) terminalState = null
            pump()
        }
    }
    fun setMode(mode: DiscoveryMode) = synchronized(lock) {
        val previous = scheduler.mode
        if (previous == mode) return
        scheduler.mode(mode)
        // Invalidate every old retry context when lowering the budget. Uncooperative old workers
        // cannot be forcibly killed; the runtime refuses new work until physical slots are available.
        if (limits.concurrency(mode) < limits.concurrency(previous)) cancelAll(endRound = false)
        beginRound(); pump()
    }

    /** Explicit new demand, such as the next-page action. A exhausted six-request round is not auto-refilled. */
    fun requestMore() = synchronized(lock) {
        if (closed || current == null) return
        terminalState = null; beginRound(); pump()
    }

    fun page(pageIndex: Int): PageSnapshot? = synchronized(lock) {
        val checkpoint = current ?: return null
        checkpoint.pool.pages.firstOrNull { it.pageIndex == pageIndex }?.let { return it.copy(orderedResultIds = it.orderedResultIds.toList()) }
        try {
            val result = store.transaction {
                val persisted = checkpoint(checkpoint.stamp.sessionId) ?: return@transaction null
                if (persisted.stamp != checkpoint.stamp) return@transaction null
                val (updated, page) = pool.materialize(persisted.pool, persisted.stamp, pageIndex, limits.pageSize,
                    persisted.profile.historyPolicy, persisted.profile.sortOrder) { seen(persisted.profile.profileId, it) }
                val next = persisted.copy(pool = updated); putCheckpoint(next); next to page
            }
            if (result != null) { current = result.first; if (result.second != null) { beginRound(); pump() } }
            result?.second?.let { it.copy(orderedResultIds = it.orderedResultIds.toList()) }
        } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid page index")
        } catch (failure: Exception) { failStoreOperation(failure); null }
    }
    fun results(page: PageSnapshot): List<VideoSearchResult> = synchronized(lock) {
        val checkpoint = current ?: return emptyList()
        if (page.stamp != checkpoint.stamp || page.revision != checkpoint.pool.viewRevision) return emptyList()
        val byId = checkpoint.pool.entries.associateBy { it.result.resultId }
        page.orderedResultIds.mapNotNull { byId[it]?.result?.let { result -> result.copy(provenance = result.provenance.toSet(), identityAliases = result.identityAliases.toSet(), rights = result.rights.detached()) } }
    }

    fun reviseView(policy: RepeatPolicy, pageSize: Int = limits.pageSize, currentPageIndex: Int = 0, sortOrder: ResultSort? = null): Boolean = synchronized(lock) {
        val checkpoint = current ?: return false
        val newLimits = limits.copy(pageSize = pageSize)
        cancelAll()
        try {
            val updated = store.transaction {
                val persisted = checkpoint(checkpoint.stamp.sessionId) ?: return@transaction null
                val next = persisted.copy(profile = persisted.profile.copy(historyPolicy = policy, sortOrder = sortOrder ?: persisted.profile.sortOrder),
                    pool = pool.revise(persisted.pool, persisted.stamp, currentPageIndex, pageSize))
                putCheckpoint(next); next
            } ?: return false
            current = updated
            limits = newLimits; scheduler.limits(newLimits)
            terminalState = null; beginRound(); pump(); terminalState != DiscoveryState.FAILED
        } catch (failure: Exception) { failStoreOperation(failure); false }
    }

    fun recordSeen(page: PageSnapshot, fingerprint: String, event: SeenEvent, atEpochMillis: Long = clock.nowMillis(),
        stillCurrent: () -> Boolean = { true }): Boolean = synchronized(lock) {
        val checkpoint = current ?: return false
        if (closed || sourceConfigurationSuspended.get() || !stillCurrent() || page.stamp != checkpoint.stamp || page.revision != checkpoint.pool.viewRevision ||
            checkpoint.pool.pages.none { it == page && fingerprint in it.orderedResultIds } ||
            event == SeenEvent.SHOWN && !gateOpen) return false
        val result = checkpoint.pool.entries.firstOrNull { it.result.fingerprint == fingerprint }?.result ?: return false
        try {
            val recorded = store.transaction {
                val persisted = checkpoint(checkpoint.stamp.sessionId) ?: return@transaction false
                if (!stillCurrent() || persisted.stamp != page.stamp || persisted.pool.viewRevision != page.revision ||
                    persisted.profile.profileId != checkpoint.profile.profileId ||
                    persisted.pool.pages.none { it == page && fingerprint in it.orderedResultIds }) return@transaction false
                beforeCommit { if (!stillCurrent()) throw DiscoveryFailure(DiscoveryError.CANCELLED) }
                beforeCommit { if (sourceConfigurationSuspended.get()) throw DiscoveryFailure(DiscoveryError.CANCELLED) }
                val old = mergeSeenRecords(checkpoint.profile.profileId, fingerprint,
                    (result.identityAliases + fingerprint).mapNotNull { seen(checkpoint.profile.profileId, it) })
                    ?: SeenRecord(checkpoint.profile.profileId, fingerprint, atEpochMillis)
                val updated = old.record(event, atEpochMillis)
                (result.identityAliases + fingerprint).forEach { putSeen(updated.copy(fingerprint = it)) }
                linkSeenAliases(checkpoint.profile.profileId, result.identityAliases + fingerprint)
                true
            }; if (recorded) cachedAvailable = null; recorded
        } catch (failure: Exception) {
            if (failure !is DiscoveryFailure || failure.code != DiscoveryError.CANCELLED) failStoreOperation(failure)
            false
        }
    }
    /** Count-only local read. A failed read is unavailable, never a fabricated zero or new demand. */
    fun historyStatistics(profileId: String? = null): HistoryStatistics? = synchronized(lock) {
        try { store.transaction { historyStatistics(profileId) } } catch (_: Exception) { null }
    }
    fun clearHistory(profileId: String? = null): Boolean = synchronized(lock) {
        if (sourceConfigurationSuspended.get()) return false
        val checkpoint = current
        val affectsCurrent = checkpoint != null && (profileId == null || checkpoint.profile.profileId == profileId)
        if (affectsCurrent) cancelAll()
        try {
            val updated = store.transaction {
                beforeCommit { if (sourceConfigurationSuspended.get()) throw DiscoveryFailure(DiscoveryError.CANCELLED) }
                clearSeen(profileId)
                if (affectsCurrent) checkpoint(checkpoint!!.stamp.sessionId) else null
            }
            if (updated != null) {
                current = updated
                if (terminalState != DiscoveryState.FAILED && terminalState != DiscoveryState.CAPACITY_REACHED) terminalState = null
            }
            true
        } catch (failure: Exception) {
            if (failure !is DiscoveryFailure || failure.code != DiscoveryError.CANCELLED) failStoreOperation(failure)
            false
        }
    }
    fun resetProgress(profileId: String, providerId: String? = null): Boolean = synchronized(lock) {
        if (sourceConfigurationSuspended.get()) return false
        val affectsCurrent = current?.profile?.profileId == profileId
        if (affectsCurrent) {
            pendingPool.removeProvider(providerId)
            // End automatic demand, but do not cancel unrelated providers already in flight.
            scheduler.finishRound()
            cancelRequests { it.request.profile.profileId == profileId && (providerId == null || it.descriptor.id == providerId) }
        }
        try {
            store.transaction {
                beforeCommit { if (sourceConfigurationSuspended.get()) throw DiscoveryFailure(DiscoveryError.CANCELLED) }
                resetProgress(profileId, providerId)
            }
            if (affectsCurrent) {
                if (providerId == null) sessionProgress.clear() else sessionProgress.remove(providerId)
                cachedProgress = null; terminalState = null
                val resetIds = registry.all().map { it.descriptor.id }.filter { providerId == null || it == providerId }
                resetIds.forEach { id ->
                    noNewPages.remove(id)
                    if (health[id]?.cooldownQueryKey != null) health.remove(id)
                }
            }
            true
        } catch (failure: Exception) {
            if (failure !is DiscoveryFailure || failure.code != DiscoveryError.CANCELLED) failStoreOperation(failure)
            false
        }
    }
    fun status(): DiscoveryStatus = synchronized(lock) {
        val available = availableCount()
        val state = when {
            closed || !gateOpen -> DiscoveryState.SUSPENDED
            registry.all().isEmpty() -> DiscoveryState.NO_SOURCES
            current?.queryAuthorized == false -> DiscoveryState.IDLE
            terminalState == DiscoveryState.FAILED || terminalState == DiscoveryState.CAPACITY_REACHED -> terminalState!!
            active.values.any { it.inFlight } -> DiscoveryState.FILLING
            active.values.any { it.retry != null } -> DiscoveryState.BACKOFF
            available >= scheduler.powerPolicy.lowWatermark -> DiscoveryState.BUFFERED
            terminalState == DiscoveryState.BACKOFF -> if (registry.all().any {
                (relevantHealth(it.descriptor)?.cooldownUntilEpochMillis ?: 0) > clock.nowMillis()
            }) DiscoveryState.BACKOFF else DiscoveryState.EXHAUSTED
            terminalState != null -> terminalState!!
            current == null || current?.profile?.query?.isBlank() == true -> DiscoveryState.IDLE
            else -> DiscoveryState.EXHAUSTED
        }
        val checkpoint = current
        val now = clock.nowMillis()
        val providers = registry.all().map { provider ->
            val id = provider.descriptor.id
            providerDiscoveryProgress(provider.descriptor, cachedProgress?.get(id), relevantHealth(provider.descriptor),
                checkpoint?.profile?.queryKey.orEmpty(), now, gateOpen, active[id]?.inFlight == true,
                active[id]?.retry != null, lastError == DiscoveryError.STORAGE_UNAVAILABLE,
                checkpoint?.profile?.query?.isNotBlank() == true)
        }
        DiscoveryStatus(state, available, current?.pool?.pages?.size ?: 0, scheduler.requestsThisRound,
            scheduler.inFlightCount, registry.all().associate { it.descriptor.id to (relevantHealth(it.descriptor) ?: ProviderHealth()) }, lastError, providers,
            scheduler.powerPolicy, scheduler.roundActive, powerPolicyUpdatePending)
    }

    private fun availableCount(): Int {
        val checkpoint = current ?: return 0
        val providers = registry.all()
        if (cachedProgress?.keys != providers.map { it.descriptor.id }.toSet()) cachedProgress = null
        if (cachedAvailable != null && cachedProgress != null) return cachedAvailable!!
        return try {
            val snapshot = store.transaction {
                val cache = mutableMapOf<String, SeenRecord?>()
                val count = cachedAvailable ?: pool.available(checkpoint.pool, checkpoint.profile.historyPolicy,
                    checkpoint.profile.sortOrder) { fingerprint ->
                    if (cache.containsKey(fingerprint)) cache[fingerprint] else seen(checkpoint.profile.profileId, fingerprint).also { cache[fingerprint] = it }
                }.size
                count to (cachedProgress ?: providers.associate { provider -> provider.descriptor.id to
                    if (provider.descriptor.progressScope == ProviderProgressScope.SESSION) sessionProgress[provider.descriptor.id]
                    else progress(checkpoint.profile.profileId, provider.descriptor.id, checkpoint.profile.queryKey) })
            }
            cachedAvailable = snapshot.first; cachedProgress = snapshot.second; snapshot.first
        }
        catch (_: Exception) { failStorage(); 0 }
    }

    private fun beginRound() {
        // Pending injected signals have not been evaluated yet. Even deferred page materialization
        // must not renew accounting between the immediate fence and its serialized policy update.
        if (closed || current == null || current?.queryAuthorized == false || powerPolicyUpdatePending) return
        val count = availableCount()
        if (terminalState == DiscoveryState.FAILED) return
        if (scheduler.beginRound(count)) terminalState = null
    }

    private fun relevantHealth(descriptor: ProviderDescriptor): ProviderHealth? {
        val value = health[descriptor.id] ?: return null
        val profile = current?.profile ?: return null
        if (value.blockedQueryKey != null && value.blockedQueryKey != profile.queryKey) return null
        if (value.cooldownQueryKey != null &&
            (value.cooldownQueryKey != profile.queryKey || value.cooldownProviderVersion != descriptor.version)) return null
        return value
    }

    private fun pump() {
        if (pumping || closed || current == null || terminalState == DiscoveryState.FAILED || !gateOpen) return
        if (current?.profile?.query?.isBlank() == true) { scheduler.finishRound(); return }
        pumping = true
        try {
            while (scheduler.roundActive && gateOpen) {
                val available = availableCount()
                if (available >= scheduler.powerPolicy.highWatermark || scheduler.requestsThisRound >= scheduler.powerPolicy.maxRequestsPerRound) { scheduler.finishRound(); break }
                if (scheduler.inFlightCount >= scheduler.powerPolicy.concurrency) break
                val checkpointPool = current?.pool
                if (checkpointPool != null && checkpointPool.entries.size >= limits.poolCapacity) {
                    val pinned = checkpointPool.pages.flatMap { it.orderedResultIds }.toSet()
                    if (checkpointPool.entries.all { it.result.resultId in pinned }) {
                        terminalState = DiscoveryState.CAPACITY_REACHED; lastError = DiscoveryError.CAPACITY_REACHED
                        cancelAll(); break
                    }
                }
                val providers = registry.all()
                if (providers.isEmpty()) { scheduler.finishRound(); break }
                var chosen: Pair<VideoSearchProvider, ProviderProgress?>? = null
                for (offset in providers.indices) {
                    val index = (roundRobin + offset) % providers.size
                    val provider = providers[index]
                    val checkpoint = current ?: break
                    val sourceHealth = relevantHealth(provider.descriptor)
                    if (active.containsKey(provider.descriptor.id) || (sourceHealth?.cooldownUntilEpochMillis ?: 0) > clock.nowMillis() ||
                        sourceHealth?.blockedQueryKey == checkpoint.profile.queryKey) continue
                    if (pendingPool.size >= limits.poolCapacity &&
                        pendingPool.snapshot().any { provider.descriptor.id in it.provenance }) continue
                    val refusal = provider.refusal(checkpoint.profile)
                    if (refusal != null) {
                        health[provider.descriptor.id] = ProviderHealth(lastError = refusal, blockedQueryKey = checkpoint.profile.queryKey)
                        lastError = refusal
                        continue
                    }
                    val progress = readProgress(checkpoint.profile, provider.descriptor)
                    if (terminalState == DiscoveryState.FAILED) return
                    if (progress?.exhausted == true) continue
                    chosen = provider to progress; roundRobin = (index + 1) % providers.size; break
                }
                if (chosen == null) {
                    if (active.isEmpty()) {
                        scheduler.finishRound()
                        terminalState = when {
                            providers.any { (relevantHealth(it.descriptor)?.cooldownUntilEpochMillis ?: 0) > clock.nowMillis() } -> DiscoveryState.BACKOFF
                            providers.all { relevantHealth(it.descriptor)?.blockedQueryKey == current?.profile?.queryKey } -> DiscoveryState.UNSUPPORTED_FILTER
                            else -> DiscoveryState.EXHAUSTED
                        }
                    }
                    break
                }
                val (provider, progress) = chosen
                val checkpoint = current ?: break
                val cancellation = DiscoveryCancellation()
                val operation = DiscoveryOperation(OperationContext("discovery-${++operationSequence}",
                    clock.nowMillis() + limits.requestDeadlineMillis, cancellation, limits.maxRetries,
                    resourceBudget = ResourceBudget(scheduler.powerPolicy.concurrency, limits.maxResponseBytes)), cancellation)
                val request = ActiveRequest(provider, provider.descriptor, SearchRequest(checkpoint.stamp, checkpoint.profile, progress), checkpoint.pool.viewRevision, operation, summaryGeneration = summaryFence.generation)
                active[provider.descriptor.id] = request
                launch(request, available)
            }
        } finally { pumping = false }
    }

    private fun readProgress(profile: SearchProfile, descriptor: ProviderDescriptor): ProviderProgress? = try {
        if (descriptor.progressScope == ProviderProgressScope.SESSION) {
            sessionProgress[descriptor.id]?.takeIf { it.profileId == profile.profileId && it.queryKey == profile.queryKey &&
                it.isCompatible(descriptor, clock.nowMillis()) }
        } else store.transaction {
            val old = progress(profile.profileId, descriptor.id, profile.queryKey)
            if (old != null && !old.isCompatible(descriptor, clock.nowMillis())) {
                resetProgress(profile.profileId, descriptor.id, profile.queryKey)
                cachedProgress = cachedProgress?.plus(descriptor.id to null); null
            } else old
        }
    } catch (failure: Exception) { failStoreOperation(failure); null }

    private fun launch(request: ActiveRequest, available: Int) {
        val id = request.descriptor.id
        // readProgress may have blocked since pump's earlier lifecycle check. Register first,
        // then recheck so suspendImmediately can either see/cancel this request or prevent launch.
        if (!gateOpen || request.operation.cancellation.isCancelled) { active.remove(id, request); return }
        if (!scheduler.reserve(id, available)) { active.remove(id); return }
        request.inFlight = true
        // Each retry has a new operation object while retaining the round cancellation token and
        // absolute deadline. A late duplicate callback from an older attempt has no authority over
        // the newer attempt, including its slot accounting, retry timer, health or committed cursor.
        val launchedOperation = request.operation
        val handle = try { runtime.execute(request.provider, request.request, launchedOperation) { outcome ->
            synchronized(lock) { completed(request, launchedOperation, outcome) }
        } } catch (_: Exception) { completed(request, launchedOperation, ProviderOutcome.Failure(DiscoveryError.PROVIDER_FAILURE)); CancellationHandle {} }
        if (active[id] === request && request.operation === launchedOperation && request.inFlight && gateOpen && !request.operation.cancellation.isCancelled) request.handle = handle
        else if (request.operation.cancellation.isCancelled || current?.stamp != request.request.stamp || !gateOpen) handle.cancel()
    }

    private fun completed(request: ActiveRequest, launchedOperation: DiscoveryOperation, outcome: ProviderOutcome) {
        val id = request.descriptor.id
        if (active[id] !== request || request.operation !== launchedOperation || !request.inFlight) return
        request.inFlight = false; request.handle = null; scheduler.release(id)
        val checkpoint = current
        if (checkpoint == null || checkpoint.stamp != request.request.stamp || !gateOpen ||
            (request.operation.cancellation.isCancelled && outcome is ProviderOutcome.Success) || registry.find(id)?.descriptor?.version != request.descriptor.version) {
            request.explicitCandidateId?.let { pendingPool.mark(it, false, VerificationSummary("scheduler", EngineOutcome.CANCELLED, EngineEvidence.NONE)) }
            active.remove(id); pump(); return
        }
        if (outcome is ProviderOutcome.Failure) outcome.retryAtEpochMillis?.let { retryAt ->
            val previous = health[id] ?: ProviderHealth()
            health[id] = previous.copy(cooldownUntilEpochMillis = maxOf(
                if (previous.cooldownQueryKey == null) previous.cooldownUntilEpochMillis else 0, retryAt),
                lastError = outcome.code, blockedQueryKey = null, cooldownQueryKey = null, cooldownProviderVersion = null)
            lastError = outcome.code
        }
        if (clock.nowMillis() >= request.operation.context.deadlineEpochMillis) {
            finishFailure(request, DiscoveryError.DEADLINE_EXCEEDED); pump(); return
        }
        when (outcome) {
            is ProviderOutcome.Success -> acceptPage(request, outcome.page)
            is ProviderOutcome.Failure -> {
                val next = request.operation.context.nextAttempt(clock.nowMillis())
                val now = clock.nowMillis()
                val localDelay = if (next != null) retryDelayMillis(next.attempt).coerceIn(0, 4000) else 0
                val serverDelay = outcome.retryAtEpochMillis?.let { if (it <= now) 0 else it - now } ?: 0
                val delay = maxOf(localDelay, serverDelay)
                if (outcome.retryable && next != null && scheduler.requestsThisRound < scheduler.powerPolicy.maxRequestsPerRound &&
                    delay < (next.deadlineEpochMillis - now).coerceAtLeast(0)) {
                    request.operation = request.operation.copy(context = next)
                    request.retry = timer.schedule(delay) {
                        synchronized(lock) {
                            request.retry = null
                            if (active[id] === request && gateOpen && current?.stamp == request.request.stamp &&
                                !request.operation.cancellation.isCancelled && clock.nowMillis() < next.deadlineEpochMillis &&
                                clock.nowMillis() >= (outcome.retryAtEpochMillis ?: 0)) {
                                launch(request, availableCount()); pump()
                            } else if (active[id] === request) {
                                if (clock.nowMillis() < (outcome.retryAtEpochMillis ?: 0)) finishFailure(request, outcome.code, outcome.retryAtEpochMillis)
                                else finishFailure(request, DiscoveryError.DEADLINE_EXCEEDED)
                                pump()
                            }
                        }
                    }
                } else finishFailure(request, outcome.code, outcome.retryAtEpochMillis)
            }
        }
        pump()
    }

    private fun acceptPage(request: ActiveRequest, page: ValidatedProviderPage) {
        val id = request.descriptor.id
        val checkpoint = current ?: return
        try {
            val accepted = store.transaction {
                beforeCommit {
                    if (!gateOpen || request.operation.cancellation.isCancelled) throw DiscoveryFailure(DiscoveryError.CANCELLED)
                    // Runtime delivery ends its I/O timer; persistence may still take time. The
                    // original absolute deadline also fences the final atomic publication.
                    request.operation.check(clock.nowMillis())
                }
                val persisted = checkpoint(checkpoint.stamp.sessionId) ?: return@transaction null
                if (persisted.stamp != request.request.stamp || persisted.pool.viewRevision != request.viewRevision ||
                    !gateOpen || request.operation.cancellation.isCancelled) return@transaction null
                val admitted = pool.admit(persisted.pool, page.results, clock.nowMillis())
                val incomingAliases = page.results.flatMap { it.identityAliases }.toSet()
                for (entry in admitted.state.entries.filter { it.result.identityAliases.any(incomingAliases::contains) }) {
                    val result = entry.result
                    val previous = mergeSeenRecords(persisted.profile.profileId, result.fingerprint,
                        (result.identityAliases + result.fingerprint).mapNotNull { seen(persisted.profile.profileId, it) })
                    val facts = previous ?: SeenRecord(persisted.profile.profileId, result.fingerprint, clock.nowMillis())
                    (result.identityAliases + result.fingerprint).forEach { putSeen(facts.copy(fingerprint = it)) }
                    linkSeenAliases(persisted.profile.profileId, result.identityAliases + result.fingerprint)
                }
                val next = persisted.copy(pool = admitted.state)
                putCheckpoint(next)
                if (request.explicitCandidateId == null && request.descriptor.progressScope == ProviderProgressScope.DURABLE)
                    putProgress(ProviderProgress(persisted.profile.profileId, id, persisted.profile.queryKey, request.descriptor.version,
                        request.descriptor.cursorSchemaVersion, page.nextCursor, page.cursorExpiresAtEpochMillis, page.exhausted, clock.nowMillis()))
                val newIds = admitted.added.map { it.resultId }.toSet()
                val eligibleNew = pool.available(admitted.state, persisted.profile.historyPolicy, persisted.profile.sortOrder) {
                    seen(persisted.profile.profileId, it)
                }.count { it.result.resultId in newIds }
                if (!gateOpen || request.operation.cancellation.isCancelled) throw DiscoveryFailure(DiscoveryError.CANCELLED)
                next to eligibleNew
            }
            active.remove(id)
            if (accepted == null) { request.explicitCandidateId?.let { pendingPool.mark(it, false) }; return }
            if (request.explicitCandidateId == null && request.descriptor.progressScope == ProviderProgressScope.SESSION)
                sessionProgress[id] = ProviderProgress(checkpoint.profile.profileId, id, checkpoint.profile.queryKey, request.descriptor.version,
                    request.descriptor.cursorSchemaVersion, page.nextCursor, page.cursorExpiresAtEpochMillis, page.exhausted, clock.nowMillis())
            current = accepted.first
            page.verificationReports.lastOrNull()?.let { summaryFence.publish(request.summaryGeneration, it.summary) }
            val candidateAdds = pendingPool.add(page.pendingCandidates,
                accepted.first.pool.entries.map { it.result.canonicalPageUrl }.toSet())
            request.explicitCandidateId?.let { candidateId ->
                pendingPool.mark(candidateId, false, page.verificationReports.find { it.candidateId == candidateId }?.summary
                    ?: VerificationSummary("scheduler", EngineOutcome.UNKNOWN, EngineEvidence.NONE))
                return
            }
            val previousEmpty = noNewPages[id]?.takeIf {
                it.queryKey == checkpoint.profile.queryKey && it.providerVersion == request.descriptor.version
            }?.count ?: 0
            val noNew = if (accepted.second == 0 && candidateAdds == 0) minOf(previousEmpty + 1, 2) else 0
            noNewPages[id] = NoNewPages(checkpoint.profile.queryKey, request.descriptor.version, noNew)
            val repeated = page.nextCursor != null && page.nextCursor == request.request.progress?.cursor && !page.exhausted
            health[id] = if (!page.exhausted && (repeated || noNew >= 2)) ProviderHealth(0, clock.nowMillis() + limits.cooldownMillis,
                if (repeated) DiscoveryError.REPEATED_CURSOR else DiscoveryError.NO_NEW_RESULTS,
                cooldownQueryKey = checkpoint.profile.queryKey, cooldownProviderVersion = request.descriptor.version) else ProviderHealth()
            lastError = health[id]?.lastError ?: registry.all().mapNotNull { relevantHealth(it.descriptor)?.lastError }.firstOrNull()
        } catch (failure: DiscoveryFailure) {
            active.remove(id)
            if (failure.code == DiscoveryError.CAPACITY_REACHED) {
                terminalState = DiscoveryState.CAPACITY_REACHED; lastError = failure.code; cancelAll()
            } else if (failure.code != DiscoveryError.CANCELLED) finishFailure(request, failure.code)
        } catch (_: Exception) { active.remove(id); failStorage() }
        finally {
            // The request may already have left active when a final commit guard throws.
            // Always finish its candidate directly; cancellation cleanup cannot recover that identity.
            request.explicitCandidateId?.let { candidateId ->
                val candidate = pendingPool.snapshot().find { it.candidateId == candidateId }
                val summary = if (request.operation.cancellation.isCancelled || !gateOpen)
                    VerificationSummary("scheduler", EngineOutcome.CANCELLED, EngineEvidence.NONE)
                else candidate?.verification ?: VerificationSummary("scheduler", EngineOutcome.UNKNOWN, EngineEvidence.NONE)
                pendingPool.mark(candidateId, false, summary)
            }
        }
    }

    private fun finishFailure(request: ActiveRequest, error: DiscoveryError, retryAtEpochMillis: Long? = null) {
        active.remove(request.descriptor.id)
        request.retry?.cancel()
        request.explicitCandidateId?.let {
            pendingPool.mark(it, false, VerificationSummary("scheduler", EngineOutcome.UNKNOWN, EngineEvidence.NONE))
            return
        }
        val previous = health[request.descriptor.id] ?: ProviderHealth()
        val blocked = error in setOf(DiscoveryError.UNSUPPORTED_FILTER, DiscoveryError.SOURCE_CONFIGURATION, DiscoveryError.INVALID_QUERY)
        health[request.descriptor.id] = ProviderHealth(previous.failureCount + 1,
            if (blocked) 0 else maxOf(clock.nowMillis() + limits.cooldownMillis, previous.cooldownUntilEpochMillis, retryAtEpochMillis ?: 0), error,
            if (blocked) request.request.profile.queryKey else null)
        lastError = error
    }
    private fun cancelRequests(predicate: (ActiveRequest) -> Boolean) {
        val cancelled = active.values.filter(predicate)
        // Remove identities before invoking cancellation: callbacks may be synchronous.
        cancelled.forEach {
            active.remove(it.descriptor.id, it); scheduler.release(it.descriptor.id)
            it.explicitCandidateId?.let { id -> pendingPool.mark(id, false, VerificationSummary("scheduler", EngineOutcome.CANCELLED, EngineEvidence.NONE)) }
        }
        cancelled.forEach { it.operation.cancellation.cancel(); it.retry?.cancel(); it.handle?.cancel() }
    }
    private fun cancelAll(endRound: Boolean = true) {
        summaryFence.invalidate()
        // Remove identities before callbacks. Lifecycle and media priority pause the existing round;
        // they never refund spent requests. Policy changes retire it and need fresh explicit demand.
        if (endRound) scheduler.suspend() else scheduler.pause()
        val cancelled = active.values.toList(); active.clear()
        cancelled.forEach { request ->
            request.explicitCandidateId?.let { pendingPool.mark(it, false, VerificationSummary("scheduler", EngineOutcome.CANCELLED, EngineEvidence.NONE)) }
        }
        cancelled.forEach { it.operation.cancellation.cancel(); it.retry?.cancel(); it.handle?.cancel() }
    }
    private fun failStoreOperation(failure: Exception) {
        if (failure is DiscoveryFailure && failure.code == DiscoveryError.CAPACITY_REACHED) {
            terminalState = DiscoveryState.CAPACITY_REACHED; lastError = failure.code; cancelAll()
        } else failStorage()
    }
    private fun failStorage() { terminalState = DiscoveryState.FAILED; lastError = DiscoveryError.STORAGE_UNAVAILABLE; cancelAll() }
    override fun close() = synchronized(lock) {
        closed = true; cancelAll(); pendingPool.clear(); explicitlyAttempted.clear(); sessionProgress.clear(); incognitoStore?.clear(); incognitoStore = null; current = null
    }
}
