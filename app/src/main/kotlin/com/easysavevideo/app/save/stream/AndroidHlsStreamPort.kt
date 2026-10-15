package com.easysavevideo.app.save.stream

import android.content.Context
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.core.model.*
import com.easysavevideo.hls.*
import com.easysavevideo.save.*
import com.easysavevideo.save.network.*
import java.util.concurrent.atomic.AtomicBoolean

/** Memory-only preflight. Neither this object nor either manifest is serialized. */
class HlsPreparation internal constructor(
    internal val source: SelectedMediaResource,
    internal val operation: OperationContext,
    internal val cancel: CancellationSource,
    internal val fetcher: SystemHttpsHlsFetcher,
    internal val budget: HlsPlanningBudget,
    internal val playlist: HlsPlaylist,
    val expiresAtMillis: Long,
) {
    val variants: List<HlsVariant> get() = (playlist as? HlsMasterPlaylist)?.variants.orEmpty()
    val directMedia: Boolean get() = playlist is HlsMediaPlaylist
    val bytesTransferred: Long get() = fetcher.bytesTransferred()
    internal val privateSelection = source.privateSource?.let { PrivateHlsSelectionBinding(source, operation) }
    internal var selected: HlsSelection? = null
    internal var plan: HlsDurablePlan? = null
    internal val segmentIdentities = mutableMapOf<Int, String>()
    override fun toString() = "HlsPreparation([redacted])"
}

/** User-triggered preparation, then a fixed selected stream using the ordinary Save commit pipeline. */
class AndroidHlsStreamPort(context: Context, private val storage: AndroidSaveStorage,
    private val leases: RequestContextLeaseStore, private val limits: SaveLimits,
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
    private val networkPolicy: com.easysavevideo.save.network.TransferNetworkPolicy = com.easysavevideo.save.network.UnrestrictedTransferNetworkPolicy) : StreamSavePort {
    private val engine = Media3LocalHlsEngine(context.applicationContext)
    private val active = AtomicBoolean(false)
    private val sessions = HlsSessionSlot<HlsPreparation>()
    private var session: HlsPreparation?
        get() = sessions.get()
        set(value) { sessions.set(value) }
    @Volatile private var recoveryRequired = false
    @Volatile private var preparationCancel: CancellationSource? = null
    val isDraining: Boolean get() = recoveryRequired || engine.requiresProcessRecovery() ||
        session?.fetcher?.requiresProcessRecovery() == true || Media3LocalHlsEngine.isDraining
    override fun requiresProcessRecovery(): Boolean = isDraining
    override fun availability() = SaveMethodAvailability(true, null)

    /** Call only after the UI AND command entitlement checks and an explicit source click. */
    fun prepare(source: SelectedMediaResource, operation: OperationContext): HlsPreparation {
        if (isDraining) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        if (source.privateSource != null && source.privateSource !is PrivateNetworkSourceSeed)
            throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
        if (!active.compareAndSet(false, true)) throw SaveFailure(SaveError.BUSY)
        try {
            session?.let(::discard)
            val cancel = CancellationSource()
            preparationCancel = cancel
            val combined = operation.copy(cancelToken = object : CancellationToken {
                override val isCancelled get() = cancel.isCancelled || operation.cancelToken.isCancelled
            })
            val fetcher = SystemHttpsHlsFetcher(leases, clock, networkPolicy = networkPolicy)
            val planning = HlsPlanningBudget(HLS_LIMITS)
            try {
                val manifest = fetcher.fetchManifest(source, source.url, combined, limits, HLS_LIMITS.maxManifestBytes, PREFLIGHT_PROGRESS)
                val playlist = parse(manifest, planning)
                val value = HlsPreparation(source, combined, cancel, fetcher, planning, playlist,
                    minOf(operation.deadlineEpochMillis, clock.nowMillis() + 60_000))
                session = value
                return value
            } catch (failure: Exception) {
                if (fetcher.requiresProcessRecovery()) recoveryRequired = true
                cancel.cancel()
                throw sanitize(failure)
            }
        } finally { preparationCancel = null; active.set(false) }
    }

    /** Returns a live resource for the exact chosen variant, never an automatic adaptive choice. */
    fun select(value: HlsPreparation, variantIdentity: String?, binding: PrivateNetworkSourceBinding? = null): SelectedMediaResource {
        if (session !== value || clock.nowMillis() >= value.expiresAtMillis || value.operation.stopReason(clock.nowMillis()) != null)
            throw SaveFailure(SaveError.EXPIRED_RESOURCE)
        val seed = value.source.privateSource
        if (seed != null && (seed !is PrivateNetworkSourceSeed || binding == null || !binding.isFrom(seed) || !binding.isLive()) ||
            seed == null && binding != null) throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
        val selected = when (val playlist = value.playlist) {
            is HlsMasterPlaylist -> playlist.selectVariant(variantIdentity ?: throw SaveFailure(SaveError.INVALID_REQUEST))
            is HlsMediaPlaylist -> {
                if (variantIdentity != null) throw SaveFailure(SaveError.INVALID_REQUEST)
                HlsSelection.directMedia(playlist.location.requestedUri)
            }
        }
        if (value.selected != null) throw SaveFailure(SaveError.INVALID_REQUEST)
        val resource = value.source.copy(variantId = selected.identity, privateSource = binding,
            expectedAudio = value.source.expectedAudio || selected.variantMetadata?.codecs?.declaresAacLc == true)
        value.privateSelection?.attach(resource)
        value.selected = selected
        return resource
    }
    fun operation(value: HlsPreparation): OperationContext {
        if (session !== value) throw SaveFailure(SaveError.EXPIRED_RESOURCE)
        return value.operation
    }
    fun canContinue(resource: SelectedMediaResource): Boolean = session?.let {
        (it.privateSelection?.matches(resource) ?: (resource.privateSource == null &&
            it.source.sourceIdentity == resource.sourceIdentity && it.selected?.identity == resource.variantId)) &&
            it.operation.stopReason(clock.nowMillis()) == null
    } == true
    /** Cancels immediately. Keep the occupied session until the worker has actually released it. */
    /** A late callback may discard only its own session, never a newer preparation. */
    fun discard(value: HlsPreparation) {
        value.cancel.cancel(); value.source.requestContextRef?.let(leases::revoke)
        if (!active.get() && !isDraining) sessions.clear(value)
    }
    /**
     * Drop this exact already-released session only after the Core positive source-detach event or
     * actual execution-claim retirement. Idle/draining flags are additional checks, never proof.
     * This is deliberately not discard(): it never cancels the output OperationContext or does IO.
     */
    internal fun detachReleased(value: HlsPreparation): Boolean {
        if (active.get() || isDraining || value.fetcher.isTransferActive || value.fetcher.requiresProcessRecovery()) return false
        sessions.clear(value)
        return session !== value
    }
    fun cancelPreparation() {
        val current = session
        preparationCancel?.cancel()
        current?.let(::discard)
    }
    fun discardIdle() {
        if (active.get() && session != null) throw SaveFailure(SaveError.BUSY)
        session?.let(::discard)
    }
    fun expireIdle() {
        val value = session ?: return
        if (!active.get() && (value.selected == null && clock.nowMillis() >= value.expiresAtMillis ||
                value.operation.stopReason(clock.nowMillis()) != null)) discard(value)
    }

    override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
        context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
        if (isDraining) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        if (!active.compareAndSet(false, true)) throw SaveFailure(SaveError.BUSY)
        var ownsWorkspace = false
        try {
            val value = session ?: throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            val selection = value.selected ?: throw SaveFailure(SaveError.INVALID_REQUEST)
            if (value.privateSelection != null) value.privateSelection.requireTransfer(resource, artifact, context, clock.nowMillis())
            else if (resource.privateSource != null) throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            if (resource.sourceIdentity != value.source.sourceIdentity || resource.variantId != selection.identity ||
                context.operationId != value.operation.operationId || context.deadlineEpochMillis != value.operation.deadlineEpochMillis ||
                checkpoint != null) throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            checkContext(context)
            // Every attempt uses the same planning/network budgets; changed manifests do not switch variants.
            val media = if (context.attempt == 0 && value.playlist is HlsMediaPlaylist) value.playlist else
                parse(value.fetcher.fetchManifest(resource, SourceUrl(selection.playlistUri.value), context, limits,
                    HLS_LIMITS.maxManifestBytes, progress), value.budget)
            val plan = HlsPlanBuilder.build(selection, media)
            value.plan?.requireSameIdentity(plan)
            value.plan = plan.durable
            storage.cleanupStream(artifact)
            val workspace = OwnedHlsWorkspace(storage.streamDirectory(artifact), limits.maxArtifactBytes) { storage.availableBytes(artifact) }
            ownsWorkspace = true
            val segments = ArrayList<LocalHlsSegment>(plan.segments.size)
            var inputBytes = 0L
            progress.onRestart()
            for (part in plan.segments) {
                checkContext(context)
                val sink = workspace.newSegment()
                val remaining = limits.maxArtifactBytes - inputBytes
                if (remaining <= 0) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
                val result = value.fetcher.fetchSegment(resource, SourceUrl(part.uri.value), sink,
                    context, limits, minOf(remaining, MAX_SEGMENT_BYTES), progress)
                inputBytes += result.bytes
                val previous = value.segmentIdentities.putIfAbsent(part.index, result.sha256)
                if (previous != null && previous != result.sha256) throw SaveFailure(SaveError.RESOURCE_CHANGED)
                segments.add(LocalHlsSegment(sink.id, sink.file, result.bytes, result.sha256, part.durationUs, part.startUs))
            }
            checkContext(context)
            val output = workspace.newOutput()
            val evidence = engine.remux(LocalHlsInput(segments, plan.media.totalDurationUs, resource.expectedAudio), output, context, limits)
            checkContext(context)
            if (isDraining) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            if (evidence.bytesOnDisk <= 0 || evidence.bytesOnDisk != output.length() || evidence.bytesOnDisk > limits.maxArtifactBytes ||
                evidence.videoTrackCount != 1 || evidence.audioTrackCount !in 0..1 || resource.expectedAudio && evidence.audioTrackCount != 1)
                throw SaveFailure(SaveError.INVALID_MEDIA)
            storage.adoptStreamOutput(artifact, output, context)
            checkContext(context)
            return TransferResult(artifact.artifactId, evidence.bytesOnDisk, "video/mp4", evidence.bytesOnDisk,
                StreamArtifactExpectation(evidence.durationUs / 1_000, evidence.videoTrackCount, evidence.audioTrackCount, evidence.width, evidence.height, evidence.conservation))
        } catch (failure: Exception) { throw sanitize(failure) }
        finally {
            if (session?.fetcher?.requiresProcessRecovery() == true || engine.requiresProcessRecovery()) recoveryRequired = true
            if (ownsWorkspace && !isDraining) {
                try { storage.cleanupStream(artifact) } catch (_: Exception) {
                    recoveryRequired = true
                    throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                }
            }
            if (!isDraining) active.set(false)
        }
    }
    private fun parse(response: HlsManifestResponse, budget: HlsPlanningBudget) = HlsParser().parse(response.bytes,
        HlsPlaylistLocation(HlsUri.parse(response.requestedUrl.value, HLS_LIMITS.maxUriBytes),
            HlsUri.parse(response.finalUrl.value, HLS_LIMITS.maxUriBytes)), budget)
    private fun checkContext(context: OperationContext) {
        when (context.stopReason(clock.nowMillis())) {
            OperationStopReason.CANCELLED -> throw SaveFailure(SaveError.CANCELLED)
            OperationStopReason.DEADLINE_EXCEEDED -> throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
            OperationStopReason.RETRY_EXHAUSTED -> throw SaveFailure(SaveError.RETRY_EXHAUSTED)
            null -> Unit
        }
    }
    private fun sanitize(failure: Exception): SaveFailure = when (failure) {
        is SaveFailure -> SaveFailure(failure.code)
        is HlsFailure -> SaveFailure(when (failure.code) {
            HlsError.PROTECTED_MEDIA -> SaveError.PROTECTED_MEDIA
            HlsError.LIMIT_EXCEEDED -> SaveError.BYTE_LIMIT_EXCEEDED
            HlsError.IDENTITY_MISMATCH -> SaveError.RESOURCE_CHANGED
            else -> SaveError.UNSUPPORTED_MEDIA
        })
        else -> SaveFailure(SaveError.INVALID_MEDIA)
    }
    companion object {
        val HLS_LIMITS = HlsLimits(2 * 1024 * 1024, 8L * 1024 * 1024, 8, 16 * 1024,
            100_000, 8 * 1024, 32, 64, 10_000, 24L * 60 * 60 * 1_000_000)
        private const val MAX_SEGMENT_BYTES = 128L * 1024 * 1024
        private val PREFLIGHT_PROGRESS = object : TransferProgress {
            override fun onBytes(delta: Long) = Unit // Fetcher owns the strict cumulative preflight budget.
            override fun onCheckpoint(checkpoint: DownloadCheckpoint) = throw SaveFailure(SaveError.INVALID_REQUEST)
            override fun onRestart() = Unit
        }
    }
}

/** Reference-identity CAS prevents stale UI callbacks from clearing a newer session. */
internal class HlsSessionSlot<T : Any> {
    private val value = java.util.concurrent.atomic.AtomicReference<T?>(null)
    fun get(): T? = value.get()
    fun set(next: T?) { value.set(next) }
    fun clear(expected: T): Boolean = value.compareAndSet(expected, null)
}

/** One immutable final selection attached to the original private preparation, never a copied identity. */
internal class PrivateHlsSelectionBinding(
    private val preparedSource: SelectedMediaResource,
    private val preparationOperation: OperationContext,
) {
    private val seed = preparedSource.privateSource as? PrivateNetworkSourceSeed
        ?: throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
    private class Attached(val resource: SelectedMediaResource, val binding: PrivateNetworkSourceBinding)
    private val attached = java.util.concurrent.atomic.AtomicReference<Attached?>(null)

    fun attach(resource: SelectedMediaResource) {
        val binding = resource.privateSource as? PrivateNetworkSourceBinding
            ?: throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
        if (!binding.isFrom(seed) || !binding.isLive() || resource.url != preparedSource.url ||
            resource.candidateId != preparedSource.candidateId || resource.sourceIdentity != preparedSource.sourceIdentity ||
            resource.requestContextRef != preparedSource.requestContextRef || resource.expiresAtEpochMillis != preparedSource.expiresAtEpochMillis ||
            resource.durableReference || preparedSource.expectedAudio && !resource.expectedAudio ||
            !attached.compareAndSet(null, Attached(resource, binding)))
            throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
    }

    fun matches(resource: SelectedMediaResource): Boolean {
        val selected = attached.get() ?: return false
        return selected.resource === resource && selected.binding === resource.privateSource &&
            selected.binding.isFrom(seed) && selected.binding.isLive() &&
            resource.url == preparedSource.url && resource.candidateId == preparedSource.candidateId &&
            resource.sourceIdentity == preparedSource.sourceIdentity &&
            resource.variantId == selected.resource.variantId && resource.expectedAudio == selected.resource.expectedAudio &&
            resource.requestContextRef == preparedSource.requestContextRef && resource.expiresAtEpochMillis == preparedSource.expiresAtEpochMillis
    }

    fun requireTransfer(resource: SelectedMediaResource, artifact: StagingArtifact,
        context: OperationContext, nowMillis: Long) {
        val selected = attached.get() ?: throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
        // Core issues commitId == taskId. Its private cancellation wrapper is expected; do not
        // require context/token object equality or reject the existing bounded retry progression.
        if (!matches(resource) || selected.binding.taskId != artifact.commitId ||
            preparationOperation.stopReason(nowMillis) != null || context.stopReason(nowMillis) != null ||
            context.operationId != preparationOperation.operationId ||
            context.deadlineEpochMillis != preparationOperation.deadlineEpochMillis ||
            context.retryBudget != preparationOperation.retryBudget || context.resourceBudget != preparationOperation.resourceBudget ||
            context.attempt !in preparationOperation.attempt..preparationOperation.retryBudget)
            throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
    }
    override fun toString() = "PrivateHlsSelectionBinding([redacted])"
}
