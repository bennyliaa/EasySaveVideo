package com.easysavevideo.app.discovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.app.storage.AndroidDiscoveryStore
import com.easysavevideo.core.model.*
import com.easysavevideo.discovery.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** UI never calls SQLite/coordinator locks. Only immutable snapshots cross onto the main thread. */
data class DiscoveryUiState(
    val ready: Boolean = false,
    val profile: SearchProfile = SearchProfile("local", ""),
    val status: DiscoveryStatus? = null,
    val page: PageSnapshot? = null,
    val results: List<VideoSearchResult> = emptyList(),
    val pageIndex: Int = 0,
    val materializedPages: Int = 0,
    val demo: Boolean = false,
    val storageUnavailable: Boolean = false,
    val notice: String? = null,
    val commonsConfigured: Boolean = CommonsSourceRegistration.configured,
    val commonsEnabled: Boolean = false,
    val controlTarget: DiscoveryControlTarget? = null,
    val historyStatistics: DiscoveryHistoryStatistics? = null,
    val historyStatisticsUnavailable: Boolean = false,
    val saveActive: Boolean = false,
    val recordingActive: Boolean = false,
    val admittedProviderDescriptors: List<ProviderDescriptor> = emptyList(),
    val powerScenarioId: String? = null,
    val osPower: DiscoveryOsPowerSnapshot = DiscoveryOsPowerSnapshot(),
    val sourceRevision: Long = -1L,
    val configurationIntent: Long = 0L,
    val pendingCandidates: List<PendingVideoCandidate> = emptyList(),
    val needsExplicitSearch: Boolean = false,
    val verificationSummary: VerificationSummary? = null,
    val verificationGeneration: Long = -1L,
    val localSourceAdmission: SourceAdmissionStatus = SourceAdmissionStatus.UNCONFIGURED,
    val pendingAdmission: Map<String, SourceAdmissionStatus> = emptyMap(),
) { override fun toString() = "DiscoveryUiState([redacted])" }

class AndroidDiscoveryController(context: Context, private val errors: ErrorSink) : AutoCloseable {
    private val store = AndroidDiscoveryStore(context)
    private val appContext = context.applicationContext
    private var commonsEnabled: Boolean? = null
    private val io = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "esv-discovery-ui").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    @Volatile private var coordinator: VideoDiscoveryCoordinator? = null
    private var timer: JvmDiscoveryTimer? = null
    private var runtime: IsolatedProviderRuntime? = null
    @Volatile private var foreground = false
    @Volatile private var visible = false
    @Volatile private var saveActive = false
    @Volatile private var recordingActive = false
    private var registry = ProviderRegistry()
    private val sourceIntent = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var appliedSourceIntent = 0L
    @Volatile private var sourceConfiguration: SearxngSourceConfiguration? = null
    @Volatile private var sourceConfigurationLoaded = false
    private val sourceOwner = AndroidSearxngConfigurationOwner.get(appContext)
    val sourceSettings = sourceOwner.controller
    private var sourceSubscription: AutoCloseable? = null
    private val verificationOwner = VerificationConfigurationOwner.hub
    val verificationSettings = verificationOwner.controller
    @Volatile private var verificationConfiguration = verificationSettings.state
    private var verificationSubscription: AutoCloseable? = null
    private fun loadedVerificationConfiguration(value: VerificationConfiguration) {
        if (closed.get() || verificationConfiguration.generation == value.generation) return
        // Re-fence delivery too: an owner may have attached reentrantly during change notification.
        fenceSourceConfiguration()
        verificationConfiguration = value
        val intent = sourceIntent.get()
        submit { if (sourceIntent.get() == intent) initialize(demo, intent) }
    }
    private data class PowerInput(val selection: DiscoveryPowerSelection, val os: DiscoveryOsPowerSnapshot)
    private val powerLock = Any()
    private var simulatedPower: DiscoveryPowerSelection? = null
    private val desiredPower = AtomicReference(PowerInput(DiscoveryPowerSelection(null,
        DiscoveryPowerSignals(enabled = true)), DiscoveryOsPowerSnapshot()))
    private data class PendingPowerChange(val coordinator: VideoDiscoveryCoordinator?, val revision: Long?, val input: PowerInput)
    private val powerDelivery = DiscoveryLatestDelivery<PendingPowerChange>(
        enqueue = { action -> if (closed.get()) false else try { io.execute(action); true } catch (_: Exception) { false } },
        consume = { change -> if (!closed.get()) guarded { applyPowerChange(change) } })
    private val signalClock = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "esv-discovery-expiry").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true; setExecuteExistingDelayedTasksAfterShutdownPolicy(false) }
    private val osSignals = DiscoveryOsPowerBridge(AndroidDiscoveryOsSignalSource(appContext), SystemClock::elapsedRealtime,
        DiscoverySignalTimer { delay, action ->
            val task = signalClock.schedule(action, delay, TimeUnit.MILLISECONDS)
            DiscoverySignalCancellation { task.cancel(false) }
        }, ::receiveOsPower)
    private var appliedPowerScenario: String? = null
    private var appliedOsPower = DiscoveryOsPowerSnapshot()
    private var demo = false
    private var settings = DiscoverySettings()
    private var sortOrder = ResultSort.RELEVANCE
    private var pageIndex = 0
    private var notice: String? = null
    private var unavailable = false
    private var initialized = false
    @Volatile private var managementRevision = -1L
    private var savedViewport: Pair<SessionStamp, Int>? = null
    private var polling: ScheduledFuture<*>? = null
    private var controllerGeneration = 0L
    // Polling can fulfill earlier explicit page demand, but local controls never create demand.
    private var pageDemandPending = false
    private var historyStatistics: DiscoveryHistoryStatistics? = null
    private var historyStatisticsUnavailable = false
    var state by mutableStateOf(DiscoveryUiState())
        private set
    // Main-thread UI draft only; persisted applied search remains the sole source-query authority.
    var filterDraft by mutableStateOf<DiscoveryFilterDraft?>(null)
        private set
    var filterSearchPending by mutableStateOf(false)
        private set
    private var filterSourceChangePending by mutableStateOf(false)
    @Volatile private var filterIntentGeneration = 0L
    val filterControlsBusy: Boolean get() = filterSearchPending || filterSourceChangePending || sourceSettings.state.busy ||
        state.sourceRevision != CommonsSourceRegistration.managementRevision ||
        state.configurationIntent != sourceIntent.get()
    /** Main-thread compare-and-set prevents callbacks captured before recomposition erasing newer edits. */
    fun updateFilterDraft(expected: DiscoveryFilterDraft?, value: DiscoveryFilterDraft): Boolean {
        if (closed.get() || filterControlsBusy || filterDraft != expected ||
            !DiscoveryFilterPresentationPolicy.matchesApplied(value, state.profile, state.demo)) return false
        filterDraft = value
        return true
    }
    fun editFilterText(claim: DiscoveryFilterClaim, field: DiscoveryFilterTextField, value: String): Boolean {
        if (closed.get()) return false
        val current = filterDraft
        val next = DiscoveryFilterPresentationPolicy.editText(claim, current, state.controlTarget,
            state.profile, state.demo, state.ready, state.storageUnavailable, filterControlsBusy, field, value) ?: return false
        return updateFilterDraft(current, next)
    }
    fun confirmFilterSafety(claim: DiscoveryFilterClaim): Boolean {
        if (closed.get() || !DiscoveryFilterPresentationPolicy.validClaim(claim, filterDraft, state.controlTarget,
                state.profile, state.demo, state.ready, state.storageUnavailable, filterControlsBusy)) return false
        return updateFilterDraft(claim.draft, claim.draft.copy(safeSearch = false))
    }
    /** Claim once on the main thread, then revalidate the complete target on the serialized executor. */
    fun submitFilterDraft(expected: DiscoveryFilterDraft, target: DiscoveryControlTarget?): Boolean {
        if (target == null || closed.get()) return false
        val claim = DiscoveryFilterClaim(target, expected.demo, expected)
        if (!DiscoveryFilterPresentationPolicy.validClaim(claim, filterDraft, state.controlTarget,
                state.profile, state.demo, state.ready, state.storageUnavailable, filterControlsBusy) ||
            !DiscoveryFilterPresentationPolicy.canSubmit(expected)) return false
        val filters = DiscoveryFilterPresentationPolicy.validate(expected).filters ?: return false
        val intentGeneration = filterIntentGeneration
        filterSearchPending = true
        coordinator?.invalidateVerificationSummary()
        state = state.copy(verificationSummary = null)
        submit {
            var applied: SearchProfile? = null
            try {
                val c = coordinator ?: return@submit
                if (!initialized || unavailable || intentGeneration != filterIntentGeneration ||
                    managementRevision != CommonsSourceRegistration.managementRevision || target != currentControlTarget(c) || demo != expected.demo) return@submit
                pageIndex = 0; pageDemandPending = true; notice = null
                c.start(SESSION, profile(DiscoveryPresentationPolicy.boundedQuery(expected.query.trim()), filters))
                applyLifecycle(c)
                applied = c.currentCheckpoint()?.profile
                refresh()
            } finally {
                // Snapshot publication is queued first. Failed/stale commands never submit a replacement.
                val completed = applied
                main.post {
                    if (!closed.get()) {
                        if (completed != null && filterDraft == expected)
                            filterDraft = DiscoveryFilterPresentationPolicy.fromProfile(completed, expected.demo)
                        filterSearchPending = false
                    }
                }
            }
        }
        return true
    }

    private fun configurationCurrent(claim: DiscoverySourceInitializationClaim,
        verification: VerificationConfiguration = verificationConfiguration): Boolean {
        val shared = sourceSettings.state
        return !verificationSettings.busy && verification.generation == verificationSettings.current.generation &&
            DiscoverySourceUiFence.canInitialize(claim, sourceIntent.get(), shared.config?.generation, shared.busy)
    }
    private fun initialize(enableDemo: Boolean, expectedIntent: Long = sourceIntent.get(),
        expectedConfiguration: SearxngSourceConfiguration? = sourceConfiguration) {
        if (!sourceConfigurationLoaded) { main.post { sourceSettings.reload() }; return }
        val claim = DiscoverySourceInitializationClaim(expectedIntent, expectedConfiguration?.generation)
        val verification = verificationConfiguration
        if (!configurationCurrent(claim, verification)) { pageDemandPending = false; return }
        val recoveringUnavailable = unavailable || appliedSourceIntent != expectedIntent
        controllerGeneration++
        pageDemandPending = true; historyStatistics = null; historyStatisticsUnavailable = false
        coordinator?.close(); runtime?.close(); timer?.close()
        CommonsSourceRegistration.initialize(appContext)
        managementRevision = CommonsSourceRegistration.managementRevision
        demo = enableDemo && BuildDiscovery.supportsDemo
        if (commonsEnabled == null) commonsEnabled = appContext.getSharedPreferences("discovery_sources", Context.MODE_PRIVATE)
            .getBoolean("commons_enabled", true)
        savedViewport = null
        timer = JvmDiscoveryTimer()
        val limits = DiscoveryLimits(pageSize = settings.pageSize)
        val validator = if (demo) null else VerificationEngineRegistration.validator(verification, timer!!,
            localEngines=listOf(VerificationEngineRegistration.localEngine(appContext))) { generation ->
            !closed.get() && !verificationSettings.busy && sourceIntent.get() == expectedIntent &&
                verificationSettings.current.generation == generation && verificationConfiguration.generation == generation
        }
        runtime = IsolatedProviderRuntime(ValidationPipeline(validator, limits = limits,
            retainUnvalidatedCandidates = true, retainFailedValidationCandidates = true,
            maxValidationsPerPage = 1), timer!!)
        registry = BuildDiscovery.registry(demo, commonsEnabled == true, expectedConfiguration)
        coordinator = VideoDiscoveryCoordinator(registry, runtime!!, if (demo) InMemoryDiscoveryStore() else store, timer!!, limits = limits,
            sourceConfigurationKey = if (demo) null else VerificationConfigurationPolicy.configurationKey(
                SearxngSourceRegistration.configurationKey(expectedConfiguration,
                    commonsEnabled == true, registry.all().map { it.descriptor }), verification))
        unavailable = false; pageIndex = 0; initialized = false
        val c = coordinator!!
        // Publish the reference first: a concurrent write now fences this coordinator directly.
        // A write that fenced the previous/null reference is caught by this recheck.
        if (!configurationCurrent(claim, verification)) { c.suspendSourcesImmediately(); return }
        appliedSourceIntent = expectedIntent
        val power = desiredPower.get()
        c.setPowerSignals(power.selection.signals, 0L)
        appliedPowerScenario = power.selection.scenarioId
        appliedOsPower = power.os
        if (!demo) {
            val previous = store.transaction { checkpoint(SESSION) }
            if (previous != null) {
                if (!c.restore(SESSION, requireExplicitSearch = recoveringUnavailable)) {
                    unavailable = true
                    if (configurationCurrent(claim, verification)) {
                        publish(DiscoverySourceUiFence.failure(expectedIntent, demo))
                        main.post { if (!closed.get() && sourceIntent.get() == expectedIntent) filterSourceChangePending = false }
                    }
                    return
                }
                sortOrder = previous.profile.sortOrder
                pageIndex = store.loadViewport(previous.stamp).coerceAtMost(previous.pool.pages.lastIndex.coerceAtLeast(0))
            } else c.start(SESSION, profile("", SearchFilters()))
        } else c.start(SESSION, profile("演示数据", SearchFilters()))
        if (!configurationCurrent(claim, verification)) { c.suspendSourcesImmediately(); return }
        initialized = true
        c.setMode(settings.mode); applyLifecycle(c); refresh()
        main.post { if (!closed.get() && appliedSourceIntent == sourceIntent.get()) filterSourceChangePending = false }
    }
    /** The write intent fences old callbacks before storage or registry work is queued. */
    private fun fenceSourceConfiguration() {
        if (closed.get()) return
        sourceIntent.incrementAndGet(); filterIntentGeneration++; filterSourceChangePending = true
        coordinator?.suspendSourcesImmediately()
        state = state.copy(page = null, results = emptyList(), pendingCandidates = emptyList(), pendingAdmission = emptyMap(), verificationSummary = null,
            controlTarget = null, historyStatistics = null,
            notice = "正在核对来源配置；原查询不会自动发送。")
    }
    private fun loadedSourceConfiguration(value: SearxngConfigurationState) {
        if (closed.get() || value.busy || !value.loaded) return
        val shared = sourceSettings.state
        if (shared.busy || value.config?.generation?.let { it != shared.config?.generation } == true) return
        if (sourceConfigurationLoaded && sourceConfiguration?.generation != value.config?.generation)
            fenceSourceConfiguration()
        applySourceConfiguration(value.config, force = !sourceConfigurationLoaded)
    }
    private fun applySourceConfiguration(value: SearxngSourceConfiguration?, force: Boolean = false) {
        // Bind this queued state to its callback's intent, never a later intent read on the worker.
        val claim = DiscoverySourceInitializationClaim(sourceIntent.get(), value?.generation)
        if (!force && initialized && !unavailable && sourceConfigurationLoaded && sourceConfiguration?.generation == value?.generation &&
            appliedSourceIntent == sourceIntent.get()) return
        submit {
            if (!configurationCurrent(claim)) return@submit
            if (!force && initialized && !unavailable && sourceConfigurationLoaded && sourceConfiguration?.generation == value?.generation &&
                appliedSourceIntent == sourceIntent.get()) return@submit
            sourceConfiguration = value; sourceConfigurationLoaded = true
            pageDemandPending = false
            initialize(demo, claim.intent, value)
        }
    }
    /** Pending cards may open the current original page, without video/Seen/Save identity authority. */
    fun candidateUrl(target: DiscoveryControlTarget?, candidate: PendingVideoCandidate): String? =
        if (!closed.get() && !filterControlsBusy && !state.storageUnavailable && target != null &&
            target == state.controlTarget && state.pendingCandidates.any { it == candidate }) candidate.canonicalPageUrl else null

    fun verifyCandidate(target: DiscoveryControlTarget?, candidate: PendingVideoCandidate) {
        if (candidateUrl(target, candidate) == null || candidate.verificationActive || candidate.verification != null ||
            !foreground || !visible) return
        submit {
            val c = coordinator ?: return@submit
            val checkpoint = c.currentCheckpoint() ?: return@submit
            if (target != state.controlTarget || candidateUrl(target, candidate) == null) return@submit
            if (!c.verifyPending(checkpoint.stamp, candidate.candidateId))
                notice = "当前未启动验证：请确认前台状态、来源及剩余预算；候选仍保留。"
            refresh()
        }
    }
    /** Capture the revision before flags: a newer owner/lifecycle event can only add a veto. */
    private fun applyLifecycle(c: VideoDiscoveryCoordinator) {
        val generation = c.currentSuspensionGeneration()
        val saving = saveActive; val recording = recordingActive
        val active = foreground; val shown = visible
        c.setPriority(saving, recording)
        c.setVisibility(active, shown, generation)
    }
    fun setVisibility(isForeground: Boolean, discoveryVisible: Boolean) {
        if (!isForeground || !discoveryVisible) state = state.copy(verificationSummary = null)
        foreground = isForeground; visible = discoveryVisible
        if (!isForeground || !discoveryVisible) coordinator?.suspendImmediately()
        osSignals.setActive(isForeground && discoveryVisible)
        submit {
            coordinator?.let(::applyLifecycle)
            if (foreground && visible && polling == null) polling = io.scheduleWithFixedDelay({
                if (!closed.get() && foreground && visible) guarded { refresh() }
            }, 300, 300, TimeUnit.MILLISECONDS)
            else if (!foreground || !visible) { polling?.cancel(false); polling = null }
            refresh()
        }
    }
    /** Save/Record adapters call this before starting work; no discovery action starts those tasks. */
    fun setTaskPriority(saveActive: Boolean, recordingActive: Boolean) {
        if (this.saveActive == saveActive && this.recordingActive == recordingActive) return
        this.saveActive = saveActive; this.recordingActive = recordingActive
        if (saveActive || recordingActive) coordinator?.suspendImmediately()
        submit { coordinator?.let(::applyLifecycle); refresh() }
    }
    /** Explicit debug scenarios only. Release has an empty allowlist and always refuses this call. */
    fun selectPowerScenario(id: String) {
        val selection = BuildDiscoveryPower.selection(id) ?: return
        synchronized(powerLock) {
            if (closed.get()) return
            // The historical disconnected factory disables simulation, never the release OS fence.
            simulatedPower = selection.takeIf { it.scenarioId != null }
            offerPowerLocked(desiredPower.get().os)
        }
    }
    fun refreshSystemSignals() { if (!closed.get()) osSignals.refresh() }
    private fun receiveOsPower(snapshot: DiscoveryOsPowerSnapshot) = synchronized(powerLock) {
        if (!closed.get()) offerPowerLocked(snapshot)
    }
    private fun offerPowerLocked(snapshot: DiscoveryOsPowerSnapshot) {
        val input = PowerInput(simulatedPower ?: DiscoveryPowerSelection(null, snapshot.signals), snapshot)
        if (desiredPower.getAndSet(input) == input) return
        val c = coordinator
        val revision = c?.suspendPowerImmediately()
        // Every changed observation fences immediately, before the bounded executor delivery.
        powerDelivery.offer(PendingPowerChange(c, revision, input))
    }
    private fun applyPowerChange(change: PendingPowerChange) {
        val c = coordinator
        if (c != null && change.coordinator === c) {
            pageDemandPending = false
            c.setPowerSignals(change.input.selection.signals, requireNotNull(change.revision))
            // No page materialization, setVisibility, new round, or requestMore on relaxation.
            appliedPowerScenario = change.input.selection.scenarioId
            appliedOsPower = change.input.os
            refresh(readOnly = true)
        }
    }

    fun updateSettings(value: DiscoverySettings) = submit {
        val old = settings; settings = value
        if (coordinator == null) { initialize(false); return@submit }
        coordinator?.setMode(value.mode)
        if (initialized && (old.repeatPolicy != value.repeatPolicy || old.pageSize != value.pageSize)) {
            if (coordinator?.reviseView(value.repeatPolicy, value.pageSize, pageIndex) == true) {
                pageIndex = 0; pageDemandPending = true; notice = "已保留当前页；新的重复策略和每页数量应用于后续页。"
            } else notice = null
        }
        refresh()
    }
    fun search(query: String, filters: SearchFilters) {
        val bounded = DiscoveryPresentationPolicy.boundedQuery(query.trim())
        if (!DiscoveryPresentationPolicy.validQuery(bounded)) {
            submit { notice = "关键词含无效字符，请修改后重试。"; refresh(readOnly = true) }
            return // Invalid local text cannot cancel or suspend the current valid search.
        }
        coordinator?.suspendImmediately()
        state = state.copy(verificationSummary = null)
        submit {
            if (!initialized || unavailable) return@submit
            pageIndex = 0; pageDemandPending = true; notice = null
            coordinator?.start(SESSION, profile(bounded, filters))
            coordinator?.let(::applyLifecycle); refresh()
        }
    }
    fun updateSort(value: ResultSort) = submit {
        if (!initialized || unavailable) return@submit
        if (coordinator?.reviseView(settings.repeatPolicy, settings.pageSize, pageIndex, value) == true) {
            sortOrder = value; pageIndex = 0; pageDemandPending = true
            notice = "已保留当前页；新的本地排序应用于后续页。未知日期或时长排在已知值之后。"
        } else notice = null
        refresh()
    }
    fun movePage(delta: Int) = submit {
        val c = coordinator ?: return@submit
        val target = (pageIndex + delta).coerceAtLeast(0)
        pageDemandPending = true
        val page = c.page(target)
        if (page != null) { pageIndex = target; notice = null }
        else { c.requestMore(); notice = "正在检查下一页；有新结果后可继续。" }
        refresh()
    }
    fun retry() = submit {
        if (unavailable) { notice = "本地发现数据暂不可读，原数据已保留。地址栏仍可使用。"; refresh() }
        else { pageDemandPending = true; coordinator?.requestMore(); notice = null; refresh() }
    }
    /** Reads only local counts; never initializes a source, materializes a page, or creates demand. */
    fun refreshHistoryStatistics(target: DiscoveryControlTarget?) = submit {
        val c = coordinator ?: return@submit
        if (target == null || target != currentControlTarget(c)) return@submit
        updateHistoryStatistics(c)
        refresh(readOnly = true)
    }

    /** The complete target is captured when the dialog opens, then checked on the serialized executor. */
    fun confirmLocalControl(request: DiscoveryControlRequest) = submit {
        val c = coordinator ?: return@submit
        val target = if (managementRevision == CommonsSourceRegistration.managementRevision) currentControlTarget(c) else null
        val outcome = DiscoveryPresentationPolicy.executeControl(request, target,
            c.status().providers.map { it.providerId }.toSet(), c::clearHistory) { profileId, providerId ->
            c.resetProgress(profileId, providerId)
        }
        if (outcome != DiscoveryControlOutcome.STALE) {
            // A later status tick must not turn cleared history or reset progress into new demand.
            pageDemandPending = false
            updateHistoryStatistics(c)
        }
        notice = DiscoveryPresentationPolicy.controlOutcomeText(request.action, outcome)
        refresh(readOnly = true)
    }

    private fun currentControlTarget(c: VideoDiscoveryCoordinator): DiscoveryControlTarget? =
        if (!initialized || unavailable || appliedSourceIntent != sourceIntent.get()) null else DiscoveryPresentationPolicy.controlTarget(c.currentCheckpoint(), controllerGeneration)

    private fun updateHistoryStatistics(c: VideoDiscoveryCoordinator) {
        historyStatistics = null
        val target = currentControlTarget(c) ?: return
        val current: HistoryStatistics? = c.historyStatistics(target.profileId)
        val all: HistoryStatistics? = c.historyStatistics()
        historyStatisticsUnavailable = current == null || all == null
        if (current != null && all != null) historyStatistics = DiscoveryHistoryStatistics(target, current, all)
    }
    private fun changeSources(action: () -> Unit) {
        if (closed.get()) return
        fenceSourceConfiguration()
        val generation = filterIntentGeneration
        submit {
            try { action() } finally {
                main.post { if (!closed.get() && generation == filterIntentGeneration) filterSourceChangePending = false }
            }
        }
    }
    fun enableDemo(enable: Boolean) {
        if (!BuildDiscovery.supportsDemo) return
        changeSources { initialize(enable) }
    }
    fun enableCommons(enable: Boolean) = changeSources {
        check(appContext.getSharedPreferences("discovery_sources", Context.MODE_PRIVATE).edit()
            .putBoolean("commons_enabled", enable).commit())
        commonsEnabled = enable; initialize(demo)
    }
    fun recordSeen(page: PageSnapshot, result: VideoSearchResult, event: SeenEvent) = submit {
        if (appliedSourceIntent != sourceIntent.get()) return@submit
        val c = coordinator ?: return@submit
        val current = c.currentCheckpoint() ?: return@submit
        if (current.stamp == page.stamp && current.pool.viewRevision == page.revision &&
            current.pool.pages.any { it == page && result.resultId in it.orderedResultIds } &&
            (event != SeenEvent.SHOWN || (foreground && visible))) c.recordSeen(page, result.fingerprint, event)
    }
    /** Main-thread immutable UI snapshot capture; executor revalidates this target before writing. */
    fun captureOriginTarget(page: PageSnapshot, result: VideoSearchResult): DiscoveryControlTarget? =
        state.controlTarget?.takeIf { !closed.get() && state.ready && !state.storageUnavailable &&
            state.page == page && state.results.any { it == result } &&
            it.stamp == page.stamp && it.viewRevision == page.revision }

    /** History is deliberately outside the Save transaction and can never change its media outcome. */
    fun completeOriginSave(tracker: DiscoveryOriginTracker, proof: DiscoveryOriginSaveProof, taskId: String,
        media: DiscoveryOriginMediaOutcome, callback: (DiscoveryOriginSaveOutcome) -> Unit) {
        fun complete() {
            val outcome = try {
            val c = coordinator
            val target = if (!closed.get() && !unavailable && c != null &&
                managementRevision == CommonsSourceRegistration.managementRevision) currentControlTarget(c) else null
            // The current DOM candidate detector proves a selected page, not that candidate media
            // equals the provider's video identity. It must never mark that identity as SAVED.
            tracker.completeSave(proof, taskId, media, target,
                identityMatch = DiscoveryOriginMediaIdentityMatch.UNKNOWN) { event ->
                when {
                    closed.get() || c == null || !event.isCurrent() || target != event.target ->
                        DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE
                    c.currentCheckpoint()?.pool?.pages?.none { it == event.page && event.result.resultId in it.orderedResultIds } != false ||
                        c.results(event.page).none { it == event.result } -> DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE
                    c.recordSeen(event.page, event.result.fingerprint, SeenEvent.SAVED,
                        stillCurrent = { !closed.get() && event.isCurrent() }) -> DiscoveryOriginHistoryOutcome.RECORDED
                    c.status().lastError in setOf(DiscoveryError.STORAGE_UNAVAILABLE, DiscoveryError.CAPACITY_REACHED) ->
                        DiscoveryOriginHistoryOutcome.HISTORY_FAILED
                    else -> DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE
                }
            }
            } catch (_: Exception) {
                tracker.abandon(proof)
                DiscoveryOriginSaveOutcome(media, DiscoveryOriginHistoryOutcome.HISTORY_FAILED)
            }
            callback(outcome)
        }
        // A closed/rejected controller still resolves the independent history result. It never
        // silently drops a callback or retries the underlying media operation.
        if (closed.get()) { complete(); return }
        try { io.execute { complete() } } catch (_: java.util.concurrent.RejectedExecutionException) { complete() }
    }
    private fun refresh(readOnly: Boolean = false) {
        if (!readOnly && coordinator != null && managementRevision != CommonsSourceRegistration.managementRevision) {
            initialize(demo); return
        }
        val c = coordinator ?: return
        val checkpoint = c.currentCheckpoint()
        var page = checkpoint?.pool?.pages?.firstOrNull { it.pageIndex == pageIndex }
        if (DiscoveryPresentationPolicy.mayFulfillPageDemand(readOnly, pageDemandPending, page == null, initialized,
                unavailable, foreground, visible) && c.status().availableCount > 0) page = c.page(pageIndex)
        if (!readOnly && page != null) pageDemandPending = false
        val updated = c.currentCheckpoint()
        val status = c.status()
        if (status.lastError == DiscoveryError.STORAGE_UNAVAILABLE) unavailable = true
        if (!readOnly && !demo && page != null && !unavailable && savedViewport != (page.stamp to pageIndex)) {
            store.saveViewport(page.stamp, pageIndex); savedViewport = page.stamp to pageIndex
        }
        val target = currentControlTarget(c)
        publish(DiscoveryUiState(ready = true, profile = updated?.profile ?: SearchProfile("local", ""), status = status, page = page,
            results = page?.let(c::results).orEmpty(), pageIndex = pageIndex, materializedPages = updated?.pool?.pages?.size ?: 0,
            demo = demo, storageUnavailable = unavailable, notice = notice, commonsConfigured = CommonsSourceRegistration.configured,
            commonsEnabled = commonsEnabled == true, controlTarget = target,
            historyStatistics = historyStatistics?.takeIf { it.target == target },
            historyStatisticsUnavailable = historyStatisticsUnavailable, saveActive = saveActive, recordingActive = recordingActive,
            admittedProviderDescriptors = registry.all().map { it.descriptor }, powerScenarioId = appliedPowerScenario,
            osPower = appliedOsPower,
            sourceRevision = managementRevision, configurationIntent = appliedSourceIntent,
            pendingCandidates = c.pendingCandidates(), pendingAdmission = c.pendingCandidates().associate { it.candidateId to verificationConfiguration.localSources.status(CandidateUrl(it.canonicalPageUrl)) },
            localSourceAdmission = verificationConfiguration.localSources.status, verificationGeneration = c.verificationGeneration(), verificationSummary = c.verificationSummary(), needsExplicitSearch = updated?.queryAuthorized == false))
    }
    private fun profile(query: String, filters: SearchFilters): SearchProfile = DiscoveryPresentationPolicy.profile(
        query, Locale.getDefault().toLanguageTag(), filters, settings.repeatPolicy, demo).copy(sortOrder = sortOrder)
    private val uiDelivery = DiscoveryLatestDelivery<DiscoveryUiState>(
        enqueue = { action -> main.post(action) }, consume = { value ->
            if (!closed.get() && DiscoverySourceUiFence.accepts(value.configurationIntent, sourceIntent.get()))
                state = if (coordinator?.verificationGeneration() == value.verificationGeneration) value
                    else value.copy(verificationSummary = null) })
    init {
        // Subscribe after every field is initialized. Pending writes fence replacement owners before any restore.
        verificationSubscription = verificationOwner.subscribe(::fenceSourceConfiguration, ::loadedVerificationConfiguration)
        sourceSubscription = sourceOwner.subscribe(::fenceSourceConfiguration, ::loadedSourceConfiguration)
    }
    private fun publish(value: DiscoveryUiState) { if (!closed.get()) uiDelivery.offer(value) }
    private fun submit(action: () -> Unit) { if (!closed.get()) runCatching { io.execute { if (!closed.get()) { osSignals.revalidate(); guarded(action) } } } }
    private fun guarded(action: () -> Unit) {
        try { action() } catch (_: Exception) {
            unavailable = true; coordinator?.suspendSourcesImmediately()
            errors.report(ErrorEnvelope(ErrorCode.RECOVERY_FAILED, ErrorModule.RECOVERY, true, UserAction.RETRY))
            val failedIntent = sourceIntent.get()
            publish(DiscoverySourceUiFence.failure(failedIntent, demo))
            main.post { if (!closed.get() && sourceIntent.get() == failedIntent) filterSourceChangePending = false }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        verificationSubscription?.close(); verificationSubscription = null
        sourceSubscription?.close(); sourceSubscription = null
        osSignals.close(); signalClock.shutdownNow()
        powerDelivery.close(); uiDelivery.close()
        coordinator?.suspendImmediately(); main.removeCallbacksAndMessages(null)
        io.execute { polling?.cancel(false); coordinator?.close(); runtime?.close(); timer?.close(); store.close() }; io.shutdown()
    }
    private companion object { const val SESSION = "active-discovery" }
}
