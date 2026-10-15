package com.easysavevideo.app.save.storage

import androidx.media3.common.C
import androidx.media3.exoplayer.source.MediaSource
import com.easysavevideo.app.media.transform.*
import com.easysavevideo.app.save.conversion.WebmDirectCandidatePolicy
import com.easysavevideo.app.save.conversion.WebmTransportFacts
import com.easysavevideo.app.media.transform.TransformerOwnedTarget
import com.easysavevideo.app.media.transform.TransformerWriter
import com.easysavevideo.app.media.transform.TransformerMechanismPolicy
import com.easysavevideo.app.media.transform.TransformerOutputIoPolicy
import com.easysavevideo.app.media.transform.TransformerOwnedSource
import com.easysavevideo.app.media.transform.TransformerReadHandle
import com.easysavevideo.app.media.transform.TransformerSession
import com.easysavevideo.app.save.AndroidDirectExecutionBridge
import com.easysavevideo.app.save.conversion.OwnedWebmInputBinding
import com.easysavevideo.app.save.conversion.WebmAttemptBudget
import com.easysavevideo.app.save.conversion.WebmOwnedInput
import com.easysavevideo.app.save.conversion.WebmProbeLimits
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.DirectNormalizationLimits
import com.easysavevideo.save.DirectConversionProof
import com.easysavevideo.save.DirectConversionVideoExpectation
import com.easysavevideo.save.DirectConversionAudioExpectation
import com.easysavevideo.save.DirectConversionExpectation
import com.easysavevideo.save.DirectConversionEncodingSettings
import com.easysavevideo.save.DirectConversionCompletionFacts
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import com.easysavevideo.save.SaveRecord
import com.easysavevideo.save.sourceTransferFingerprint
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registered Direct resource attempt. Neither its constructor nor a facts object grants authority.
 * Original PRIMARY and one existing OUTPUT remain pinned across logical and exact native release.
 * The one-shot run keeps its actual outcome privately; resource sealing still grants no
 * durable finish authority without the exact owner handoff; adoption and publication remain closed.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class OwnedDirectTranscodeAttempt private constructor(
    private val owner: NormalizationAttemptOwner,
    private val files: AndroidNormalizationFiles,
    internal val writingRow: NormalizationWorkspace,
    internal val context: OperationContext,
    private val raw: NormalizationBoundFile,
    private val emptyOutput: NormalizationFileIdentity,
    internal val outputMaximum: Long,
    internal val reservation: NormalizationIoReservations.Token,
    internal val expectedRecord: SaveRecord,
    internal val scope: AndroidDirectExecutionBridge.Scope,
) : AutoCloseable {
    internal val key: NormalizationKey get() = writingRow.key
    private val lock = Any()
    private val readers = Collections.newSetFromMap(IdentityHashMap<Reader, Boolean>())
    private var sourceView: TransformerOwnedSource? = null
    private var borrowedView: WebmOwnedInput? = null
    private var budgetView: WebmAttemptBudget? = null
    private var anchor: AndroidNormalizationFiles.AsyncRawDescriptor? = null
    private var anchorStarted = false
    private var anchorOpening = false
    private var calls = 0
    private var nativeRetentions = 0
    private var nativeSourceReleased = false
    private var outputAnchor: AndroidNormalizationFiles.AsyncOutputDescriptor? = null
    private var outputOpeningOperation: AndroidNormalizationFiles.Operation? = null
    private var outputAnchorStarted = false
    private var outputOpenClaimed = false
    private val outputOpeningClaim = Any()
    private var outputOpening = false
    private var outputAbortRequested = false
    private var target: Target? = null
    private var writer: Writer? = null
    private var writerStarted = false
    private var writerRetentionStarted = false
    private var writerRetaining = false
    private var nativeWriterRetentions = 0
    private var nativeWriterReleased = false
    private var outputCalls = 0
    private var sealing = false
    private var sealingStarted = false
    private var sealed = false
    private var sealClosing = false
    private var outputContentFailed = false
    private var finalizationFailed = false
    private var outputResourceUncertain = false
    private var finalizationDeadlineNanos: Long? = null
    private var resourceObservations: Pair<NormalizationBoundFile, NormalizationBoundFile>? = null
    private var cumulativeOpens = 0
    private var physicalBytes = 0L
    private var requestBytes = 0L
    private var events = 0L
    @Volatile private var closeRequested = false
    @Volatile private var failed = false
    private val extent = raw.fingerprint.size.also {
        require(it in 1..DirectNormalizationLimits.MAX_BYTES)
        require(raw.identity.stat.size == it && emptyOutput.stat.size == 0L)
    }
    // Independent, fixed local input-work limits. These do not alter network or storage budgets.
    private val physicalLimit = minOf(MAX_LOCAL_BYTES, Math.addExact(Math.multiplyExact(extent, 8L), 4L * 1024 * 1024))
    private val requestLimit = minOf(MAX_LOCAL_BYTES, Math.addExact(Math.multiplyExact(extent, 32L), 16L * 1024 * 1024))
    private val limits = WebmProbeLimits(maxIoBytes = requestLimit)

    private val outputWorkLimit = Math.addExact(Math.multiplyExact(outputMaximum, 2L), TransformerMechanismPolicy.OUTPUT_RESERVE)
    private val activeOutputBudget = WorkBudget(outputWorkLimit,
        Math.addExact(Math.multiplyExact(TransformerMechanismPolicy.MAX_SOURCE_SAMPLES.toLong(), 8L), 8192L))
    private val finalOutputBudget = WorkBudget(minOf(outputWorkLimit, 128L * 1024 * 1024), 65536L)
    private val rawSealBudget = hashBudget(extent)
    private val outputSealBudget = hashBudget(outputMaximum)

    private enum class RunPhase { NEW, CLAIMED, BOUND, WORKING, OBSERVED, RELEASED, SEALED, FINISHING, HANDED_OFF, FAILED }
    private var runPhase = RunPhase.NEW
    private var finishClaimed = false
    private var runEngine: TransformerEngine? = null
    private var runSession: TransformerSession? = null
    private var runPair: OwnedWebmInputBinding? = null
    private var runSource: MediaSource? = null
    private var runTarget: TransformerOwnedTarget? = null
    private var runOutput: TransformerOutputSpec? = null
    private var runTimeline: TransformerSourceTimeline? = null
    private var runTransport: WebmTransportFacts? = null
    private var runCompletedTransport: WebmTransportFacts? = null
    private var runCompletedTransportStarted = false
    private var runWorker: Thread? = null
    private var actualRunOutcome: TransformerOutcome<TransformerEngineFacts>? = null
    // Only the registered input/output/checkpoint catches below can populate this bounded slot.
    // It never retains payloads or logs messages, and cleanup/retry can never reset it.
    private var firstOriginalFailure: Throwable? = null
    private var originalCaptureClosed = false
    private var terminalRunFailure: Throwable? = null

    /** Same owner, original Scope/context and immutable Save/WRITING snapshots; no caller data. */
    internal fun runOwnedDirect() {
        owner.checkTranscodeSnapshot(this)
        synchronized(lock) {
            accepting()
            if (runPhase != RunPhase.NEW) reject()
            runPhase = RunPhase.CLAIMED // A rejection/failure consumes the one attempt permanently.
        }
        try {
            val engine = owner.transcodeEngine(this)
            synchronized(lock) { runEngine = engine }
            val pair = bindWebmInputOnce()
            synchronized(lock) { runPair = pair }
            OwnedDirectTechnicalDiagnostics.record(context.operationId, OwnedDirectTechnicalDiagnostics.Stage.PREFLIGHT_STARTED)
            val transport = pair.probe()
            // Inventory remains intact; Opus is never silently removed to obtain a video candidate.
            val output = WebmDirectCandidatePolicy.select(transport, outputMaximum)
            OwnedDirectTechnicalDiagnostics.record(context.operationId, OwnedDirectTechnicalDiagnostics.Stage.CANDIDATE_ADMITTED)
            val video = transport.tracks.single { it.trackType == C.TRACK_TYPE_VIDEO }
            val timeline = TransformerSourceTimeline(output.durationUs, output.frameRate, video.firstSourceTimestampUs)
            runCheckpoint()
            val remaining = context.deadlineEpochMillis - System.currentTimeMillis()
            if (remaining <= 0) throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
            val session = TransformerSession(TransformerSessionLimits(
                timeoutMillis = minOf(remaining, 3_600_000L), stallTimeoutMillis = 60_000,
                releaseTimeoutMillis = 10_000, maxInputOpens = 1, maxOpenInputs = 1,
                maxTransferredBytes = physicalLimit, maxLoaders = 1,
                maxCodecs = if (output.audio == null) 2 else 4, maxExecutors = 3),
                ::runCheckpoint, { context.stopReason(System.currentTimeMillis()) })
            synchronized(lock) {
                runSession = session; runOutput = output; runTimeline = timeline; runTransport = transport
            }
            val returned = runTransformerSession(session) { entered ->
                try {
                    if (entered !== session) reject()
                    runCheckpoint()
                    val source = pair.createMediaSource(session)
                    val destination = ownedTarget(pair)
                    synchronized(lock) {
                        if (runPhase != RunPhase.CLAIMED) reject()
                        runSource = source; runTarget = destination; runWorker = Thread.currentThread(); runPhase = RunPhase.BOUND
                    }
                    val facts = engine.executeOwnedDirect(this)
                    throwOriginalFailure()
                    synchronized(lock) {
                        // executeOwnedDirect already passed this exact capture to observer.finish.
                        if (runPhase != RunPhase.WORKING || facts.sourceObservation == null || runCompletedTransport == null) reject()
                        runPhase = RunPhase.OBSERVED
                    }
                    OwnedDirectTechnicalDiagnostics.record(context.operationId, OwnedDirectTechnicalDiagnostics.Stage.SOURCE_EOS_OBSERVED, facts.sourceObservation)
                    facts
                } catch (failure: Throwable) {
                    // Once active work has failed, later cleanup cannot replace that primary.
                    val chosen = synchronized(lock) {
                        originalCaptureClosed = true
                        (terminalRunFailure ?: firstOriginalFailure ?: transformerPrimaryFailure(failure)).also { terminalRunFailure = it }
                    }
                    throw chosen
                }
            }
            // This assignment has exactly one producer: the actual shared runner's return.
            synchronized(lock) { actualRunOutcome = returned }
            // Failed.primary is the real runner's already selected reason; late cleanup IO
            // cannot reorder it. A nominal Completed still must survive the post-cleanup slot.
            when (returned) {
                is TransformerOutcome.Failed -> throw returned.primary
                is TransformerOutcome.Completed -> {
                    throwOriginalFailure()
                    owner.checkTranscodeSnapshot(this)
                    val observed = returned.value.sourceObservation ?: reject()
                    synchronized(lock) {
                        if (runPhase != RunPhase.OBSERVED || runCompletedTransport == null) reject()
                        if (returned.value.videoSamples != observed.video.displayedFrames ||
                            observed.video.displayedFrames != output.expectedVideoFrames ||
                            !matchesAudioObservation(output, observed.audio, returned.value.audioSamples)) reject()
                        runPhase = RunPhase.RELEASED
                    }
                    throwOriginalFailure()
                    OwnedDirectTechnicalDiagnostics.record(context.operationId, OwnedDirectTechnicalDiagnostics.Stage.RESOURCES_RELEASED)
                    val resources = sealResources()
                    if (resources.second.fingerprint.size != returned.value.bytesWritten ||
                        resources.first.fingerprint != raw.fingerprint) reject()
                    owner.checkTranscodeSnapshot(this)
                    throwOriginalFailure()
                    synchronized(lock) { if (runPhase != RunPhase.RELEASED) reject(); runPhase = RunPhase.SEALED }
                    OwnedDirectTechnicalDiagnostics.record(context.operationId, OwnedDirectTechnicalDiagnostics.Stage.SEALED)
                }
            }
        } catch (failure: Throwable) {
            val primary = synchronized(lock) {
                runPhase = RunPhase.FAILED; failed = true
                (actualRunOutcome as? TransformerOutcome.Failed)?.primary ?: firstOriginalFailure ?: failure
            }
            try { close() } catch (cleanup: Throwable) { if (cleanup !== primary) primary.addSuppressed(cleanup) }
            throw primary
        }
    }

    private fun runCheckpoint() {
        synchronized(lock) { terminalRunFailure }?.let { throw it }
        throwOriginalFailure()
        try { requireRegistered() }
        catch (failure: Throwable) {
            if (owner.matchesTranscodeFailureBoundary(this, synchronized(lock) { runPair }, synchronized(lock) { runSession }))
                rememberOriginalFailure(failure)
            synchronized(lock) { terminalRunFailure }?.let { throw it }
            throwOriginalFailure()
            throw failure
        }
    }
    private fun rememberOriginalFailure(failure: Throwable) = synchronized(lock) {
        if (runPhase != RunPhase.NEW && !originalCaptureClosed && firstOriginalFailure == null) firstOriginalFailure = failure
    }
    private fun throwOriginalFailure() { synchronized(lock) { firstOriginalFailure }?.let { throw it } }
    /** Pull only the registered pair's privately captured catch; never accept a Throwable argument. */
    internal fun captureInputBoundaryFailure(binding: OwnedWebmInputBinding) {
        val session = synchronized(lock) { if (runPair !== binding) return; runSession }
        if (!owner.matchesTranscodeFailureBoundary(this, binding, session)) return
        binding.boundaryFailureFor(this)?.let(::rememberOriginalFailure)
    }
    private fun captureInputOperationFailure(binding: OwnedWebmInputBinding, failure: Throwable) {
        val session = synchronized(lock) { if (runPair !== binding) return; runSession }
        if (owner.matchesTranscodeFailureBoundary(this, binding, session)) rememberOriginalFailure(failure)
    }
    private fun captureOutputBoundaryFailure(exact: Target, failure: Throwable) {
        synchronized(lock) { if (target !== exact || runPair !== exact.binding || runSession !== exact.session) return }
        if (owner.matchesTranscodeFailureBoundary(this, exact.binding, exact.session)) rememberOriginalFailure(failure)
    }
    /** First engine operation: exact captured concrete engine, accepted worker and one claim. */
    internal fun claimOwnedDirect(engine: TransformerEngine) {
        val session = synchronized(lock) {
            if (runEngine !== engine || runPhase != RunPhase.BOUND || runWorker !== Thread.currentThread()) reject()
            runSession ?: reject()
        }
        session.requireEntered()
        requireRegisteredSession(synchronized(lock) { runPair ?: reject() }, session)
        synchronized(lock) { if (runPhase != RunPhase.BOUND) reject(); runPhase = RunPhase.WORKING }
    }
    private fun requireEngine(engine: TransformerEngine): TransformerSession {
        val pair: OwnedWebmInputBinding
        val session: TransformerSession
        synchronized(lock) {
            if (runEngine !== engine || runPhase != RunPhase.WORKING) reject()
            pair = runPair ?: reject(); session = runSession ?: reject()
        }
        requireRegisteredSession(pair, session); session.requireEntered(); throwOriginalFailure()
        return session
    }
    internal fun directSession(engine: TransformerEngine): TransformerSession = requireEngine(engine)
    internal fun directOutput(engine: TransformerEngine): TransformerOutputSpec { requireEngine(engine); return synchronized(lock) { runOutput ?: reject() } }
    internal fun directTimeline(engine: TransformerEngine): TransformerSourceTimeline { requireEngine(engine); return synchronized(lock) { runTimeline ?: reject() } }
    internal fun directTransport(engine: TransformerEngine): WebmTransportFacts { requireEngine(engine); return synchronized(lock) { runTransport ?: reject() } }
    /** Active-only, one-shot capture from this exact loaded pair, before observer finalization. */
    internal fun directCompletedTransport(engine: TransformerEngine): WebmTransportFacts {
        requireEngine(engine)
        val pair = synchronized(lock) {
            if (runCompletedTransportStarted) reject()
            runCompletedTransportStarted = true
            runPair ?: reject()
        }
        val completed = pair.completedTransportFacts()
        throwOriginalFailure()
        requireEngine(engine)
        return synchronized(lock) {
            if (runCompletedTransport != null || !sameFinishedTransport(runTransport ?: reject(), completed)) reject()
            completed.also { runCompletedTransport = it }
        }
    }
    internal fun directMediaSource(engine: TransformerEngine): MediaSource { requireEngine(engine); return synchronized(lock) { runSource ?: reject() } }
    internal fun directTarget(engine: TransformerEngine): TransformerOwnedTarget { requireEngine(engine); return synchronized(lock) { runTarget ?: reject() } }
    /** Callback/cleanup read, identity-only: revoked liveness must not replace the first reason. */
    internal fun directOriginalFailure(engine: TransformerEngine, session: TransformerSession): Throwable? = synchronized(lock) {
        if (runEngine !== engine || runSession !== session) reject()
        firstOriginalFailure
    }
    /** Only bounded native classification enters here, never an arbitrary Throwable or success.
     * The actual listener chooses and publishes one terminal object before any cleanup can race. */
    internal fun completeDirectNativeFailure(engine: TransformerEngine, session: TransformerSession, errorCode: Int) {
        val chosen = synchronized(lock) {
            if (runEngine !== engine || runSession !== session) reject()
            (terminalRunFailure ?: firstOriginalFailure ?: TransformerFailure(
                if (errorCode == androidx.media3.transformer.ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ||
                    errorCode == androidx.media3.transformer.ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
                    TransformerError.UNSUPPORTED_PROFILE else TransformerError.EXECUTION_FAILED)).also {
                terminalRunFailure = it; originalCaptureClosed = true
            }
        }
        // CompletableFuture may synchronously invoke dependents. Never run them under attempt lock.
        session.completion.completeExceptionally(chosen)
    }

    /** Exact low-level descriptor catches pull their own private pending object before poison. */
    internal fun captureDescriptorFailure(descriptor: AndroidNormalizationFiles.AsyncAnchor): Throwable? {
        val opening = synchronized(lock) {
            if (descriptor === anchor || descriptor === outputAnchor) null else outputOpeningOperation
        }
        val exact = synchronized(lock) { descriptor === anchor || descriptor === outputAnchor } ||
            opening?.ownsOpeningDescriptor(this, outputOpeningClaim, descriptor) == true
        if (!exact) return null
        val original = descriptor.pendingFailureFor(this) ?: return null
        val chosen = chooseBoundaryFailure(original, descriptor is AndroidNormalizationFiles.AsyncOutputDescriptor)
        val pair = synchronized(lock) { runPair }
        val session = synchronized(lock) { runSession }
        if (owner.matchesTranscodeFailureBoundary(this, pair, session)) rememberOriginalFailure(chosen)
        return chosen
    }
    internal fun captureOpeningFailure(operation: AndroidNormalizationFiles.Operation, claim: Any): Throwable? {
        synchronized(lock) { if (operation !== outputOpeningOperation || claim !== outputOpeningClaim || !outputOpening) return null }
        val original = operation.pendingOpeningFailureFor(this, claim) ?: return null
        val chosen = chooseBoundaryFailure(original, output = true)
        if (owner.matchesTranscodeFailureBoundary(this, synchronized(lock) { runPair }, synchronized(lock) { runSession }))
            rememberOriginalFailure(chosen)
        return chosen
    }
    private fun chooseBoundaryFailure(failure: Throwable, output: Boolean): Throwable {
        if ((failure is IOException || output) && (failure !is SaveFailure || failure.code != SaveError.RESOURCE_RELEASE_UNCONFIRMED)) {
            try { owner.checkStop(context) } catch (stop: Throwable) { return stop }
        }
        return failure
    }
    private fun boundaryAlreadySelected(failure: Throwable): Boolean {
        val (raw, output, opening) = synchronized(lock) { Triple(anchor, outputAnchor, outputOpeningOperation) }
        return raw?.selectedFailureFor(this) === failure || output?.selectedFailureFor(this) === failure ||
            opening?.selectedOpeningFailureFor(this, outputOpeningClaim) === failure
    }

    internal fun requireRegistered() {
        owner.checkStop(context)
        if (closeRequested || failed || sealingStarted) reject()
        owner.checkTranscode(this)
        if (closeRequested || failed || sealingStarted) reject()
    }
    internal fun bindWebmInputOnce(): OwnedWebmInputBinding = owner.bindWebmInputOnce(this)
    internal fun requireRegisteredBinding(binding: OwnedWebmInputBinding) {
        requireRegistered(); owner.requireInputBinding(this, binding)
    }
    internal fun bindSession(binding: OwnedWebmInputBinding, session: TransformerSession) {
        requireRegisteredBinding(binding); owner.bindInputSession(this, binding, session)
    }
    internal fun requireRegisteredSession(binding: OwnedWebmInputBinding, session: TransformerSession) {
        requireRegisteredBinding(binding); owner.requireInputSession(this, binding, session)
    }
    internal fun webmLimits(binding: OwnedWebmInputBinding): WebmProbeLimits {
        requireRegisteredBinding(binding); return limits
    }
    internal fun expectedAudio(binding: OwnedWebmInputBinding): Boolean? {
        requireRegisteredBinding(binding); return expectedRecord.request.networkSourceMetadata!!.expectedAudio
    }
    internal fun webmBudget(binding: OwnedWebmInputBinding): WebmAttemptBudget {
        requireRegisteredBinding(binding)
        return synchronized(lock) {
            budgetView ?: object : WebmAttemptBudget {
                override fun request(bytes: Int) {
                    requireRegisteredBinding(binding)
                    synchronized(lock) {
                        accepting()
                        if (bytes < 0 || bytes.toLong() > requestLimit - requestBytes) limit()
                        requestBytes += bytes
                    }
                    requireRegisteredBinding(binding)
                }
                override fun event() {
                    requireRegisteredBinding(binding)
                    synchronized(lock) { accepting(); if (events == MAX_EVENTS) limit(); events++ }
                    requireRegisteredBinding(binding)
                }
            }.also { budgetView = it }
        }
    }
    internal fun openAnchor() {
        requireRegistered()
        synchronized(lock) { accepting(); if (anchorStarted) reject(); anchorStarted = true; anchorOpening = true }
        var primary: Throwable? = null
        try {
            val opened = files.forOperation(::requireRegistered, reservation)
                .openAsyncRaw(key, raw.identity, this, ::released)
            synchronized(lock) { anchor = opened }
            requireRegistered()
        } catch (failure: Throwable) { primary = failure; failed = true; throw failure }
        finally {
            synchronized(lock) { anchorOpening = false }
            try { drain() } catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) }
        }
    }
    internal fun ownedSource(binding: OwnedWebmInputBinding): TransformerOwnedSource {
        requireRegisteredBinding(binding)
        return synchronized(lock) {
            sourceView ?: object : TransformerOwnedSource {
                override fun retainNativeReader(): AutoCloseable {
                    val session = entered(binding)
                    synchronized(lock) { accepting(); if (nativeRetentions != 0) reject(); nativeRetentions++ }
                    try { entered(binding); return NativeRetention(session, this) }
                    catch (failure: Throwable) {
                        synchronized(lock) { nativeRetentions-- }
                        try { drain() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                        throw failure
                    }
                }
                override fun openReader(): TransformerReadHandle {
                    val session = entered(binding)
                    val opened = synchronized(lock) {
                        accepting()
                        if (nativeRetentions != 1 || readers.size >= MAX_READERS || cumulativeOpens >= MAX_OPENS) limit()
                        cumulativeOpens++
                        Reader(binding, session).also { readers.add(it) }
                    }
                    try { entered(binding); return opened }
                    catch (failure: Throwable) {
                        try { opened.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                        throw failure
                    }
                }
            }.also { sourceView = it }
        }
    }
    internal fun borrowedWebmInput(binding: OwnedWebmInputBinding): WebmOwnedInput {
        requireRegisteredBinding(binding)
        return synchronized(lock) {
            borrowedView ?: object : WebmOwnedInput {
                override val length: Long get() { requireRegisteredBinding(binding); return extent }
                override fun validateIdentity() = operation(binding) { descriptor().verifyIdentity() }
                override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int): Int =
                    operation(binding) { read(offset, target, targetOffset, length) }
            }.also { borrowedView = it }
        }
    }
    /** Issued only after the real pair has bound its existing entered session. */
    internal fun ownedTarget(binding: OwnedWebmInputBinding): TransformerOwnedTarget {
        val session = entered(binding)
        synchronized(lock) {
            accepting()
            target?.let { if (it.binding !== binding || it.session !== session) reject(); return it }
            if (outputAnchorStarted) reject()
            activeOutputBudget.event(); outputAnchorStarted = true; outputOpening = true
        }
        var primary: Throwable? = null
        try {
            val operation = files.forOperation(::requireRegistered, reservation)
            synchronized(lock) { outputOpeningOperation = operation }
            val opened = operation.openAsyncOutput(key, emptyOutput, this, outputOpeningClaim, ::released)
            synchronized(lock) { outputAnchor = opened }
            requireRegisteredSession(binding, session); session.requireEntered()
            val issued = Target(binding, session)
            synchronized(lock) { accepting(); target = issued }
            return issued
        } catch (failure: Throwable) {
            primary = failure
            if (failure is IOException && !boundaryAlreadySelected(failure)) {
                try { owner.checkStop(context) } catch (stop: Throwable) { primary = stop }
            }
            val chosen = requireNotNull(primary)
            // The exact output-open boundary precedes failed=true, so a polling checkpoint
            // cannot replace this IO/stop reason with a derivative revoked-state rejection.
            if (synchronized(lock) { runPair === binding && runSession === session } &&
                owner.matchesTranscodeFailureBoundary(this, binding, session)) rememberOriginalFailure(chosen)
            failed = true
            synchronized(lock) { outputAbortRequested = true }
            throw chosen
        }
        finally {
            synchronized(lock) { outputOpening = false }
            try { drain() } catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) }
        }
    }
    /** The low-level opener cannot acquire another OUTPUT merely from a genuine token. */
    internal fun claimOutputOpen(candidateFiles: AndroidNormalizationFiles, candidateToken: NormalizationIoReservations.Token,
        identity: NormalizationFileIdentity, openingClaim: Any) {
        requireRegistered()
        synchronized(lock) {
            accepting()
            if (candidateFiles !== files || candidateToken !== reservation || identity !== emptyOutput || openingClaim !== outputOpeningClaim ||
                !outputAnchorStarted || !outputOpening || outputOpenClaimed || outputAnchor != null || target != null) reject()
            outputOpenClaimed = true
        }
    }
    internal enum class OutputPhase { ACTIVE, WRITING, FINALIZING, SEALING }
    /** Immutable limits, monotonically consumed. Close/revoke/reopen never recreates a reserve. */
    private class WorkBudget(val bytesLimit: Long, val eventsLimit: Long) {
        var requestBytes = 0L
        var physicalBytes = 0L
        var events = 0L
        fun request(value: Long) { requestBytes = charge(requestBytes, value, bytesLimit) }
        fun physical(value: Long) { physicalBytes = charge(physicalBytes, value, bytesLimit) }
        fun event() { events = charge(events, 1L, eventsLimit) }
        private fun charge(used: Long, value: Long, maximum: Long): Long {
            if (value < 0 || used < 0 || value > maximum - used) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
            return used + value
        }
    }
    private fun hashBudget(size: Long) = WorkBudget(
        Math.addExact(Math.multiplyExact(size, 2L), TransformerMechanismPolicy.OUTPUT_RESERVE),
        Math.addExact(Math.multiplyExact(Math.addExact(size, CHUNK - 1L) / CHUNK, 64L), 65536L))
    private fun outputDescriptor() = synchronized(lock) { outputAnchor ?: reject() }
    private fun exactTarget(): Target = synchronized(lock) { target ?: reject() }
    private fun exactOutput(descriptor: AndroidNormalizationFiles.AsyncOutputDescriptor) {
        synchronized(lock) { if (outputAnchor !== descriptor || outputResourceUncertain) reject() }
    }
    internal fun outputRange(position: Long, bytes: Long) {
        if (!TransformerMechanismPolicy.outputRangeAllowed(position, bytes, outputMaximum)) limit()
    }
    internal fun requireOutputAccess(descriptor: AndroidNormalizationFiles.AsyncOutputDescriptor, phase: OutputPhase) {
        exactOutput(descriptor)
        if (phase == OutputPhase.SEALING) { requireSealingAnchor(descriptor); return }
        val exact = exactTarget()
        if (phase == OutputPhase.ACTIVE || phase == OutputPhase.WRITING) {
            requireRegisteredSession(exact.binding, exact.session); exact.session.requireEntered()
            synchronized(lock) {
                accepting(); if (outputContentFailed) reject()
                if (phase == OutputPhase.WRITING && (writer == null || writer?.closed != false || writer?.finalizing != false)) reject()
            }
        } else {
            owner.requireTranscodeSessionIdentity(this, exact.binding, exact.session)
            val sessionDeadline = exact.session.requireWriterFinalization(exact)
            val ownDeadline = synchronized(lock) {
                if (sealingStarted || outputResourceUncertain || finalizationFailed || nativeWriterRetentions != 1 ||
                    writer?.finalizing != true || writer?.closed != false) reject()
                finalizationDeadlineNanos ?: reject()
            }
            val deadline = if (sessionDeadline == null) ownDeadline else minOf(ownDeadline, sessionDeadline)
            if (System.nanoTime() - deadline >= 0) throw IOException("TRANSCODE_FINALIZATION_DEADLINE")
        }
    }
    private fun budget(descriptor: AndroidNormalizationFiles.AsyncOutputDescriptor, phase: OutputPhase): WorkBudget {
        exactOutput(descriptor)
        return when (phase) { OutputPhase.ACTIVE, OutputPhase.WRITING -> activeOutputBudget; OutputPhase.FINALIZING -> finalOutputBudget
            OutputPhase.SEALING -> { requireSealingAnchor(descriptor); outputSealBudget } }
    }
    internal fun outputEvent(descriptor: AndroidNormalizationFiles.AsyncOutputDescriptor, phase: OutputPhase) {
        val chosen = budget(descriptor, phase); synchronized(lock) { chosen.event() }
    }
    internal fun outputRequest(descriptor: AndroidNormalizationFiles.AsyncOutputDescriptor, phase: OutputPhase, bytes: Long) {
        val chosen = budget(descriptor, phase); synchronized(lock) { chosen.request(bytes) }
    }
    internal fun outputPhysical(descriptor: AndroidNormalizationFiles.AsyncOutputDescriptor, phase: OutputPhase, bytes: Long) {
        val chosen = budget(descriptor, phase); synchronized(lock) { chosen.physical(bytes) }
    }
    internal fun outputUncertain(descriptor: AndroidNormalizationFiles.AsyncOutputDescriptor) {
        synchronized(lock) { if (outputAnchor !== descriptor) reject(); outputResourceUncertain = true; outputContentFailed = true }
    }
    private fun <T> outputOperation(exact: Target, logical: Writer? = null,
        block: (AndroidNormalizationFiles.AsyncOutputDescriptor, OutputPhase) -> T): T = try {
        checkedOutputOperation(exact, logical, block)
    } catch (failure: Throwable) {
        // A concurrent operation may only be observing a prior content/uncertainty flag. Its
        // derived rejection cannot win over the syscall failure still unwinding its own catch.
        if (synchronized(lock) { !outputContentFailed && !outputResourceUncertain }) captureOutputBoundaryFailure(exact, failure)
        throw failure
    }
    private fun <T> checkedOutputOperation(exact: Target, logical: Writer?,
        block: (AndroidNormalizationFiles.AsyncOutputDescriptor, OutputPhase) -> T): T {
        val descriptor = outputDescriptor()
        val phase = synchronized(lock) {
            if (target !== exact || (logical != null && (writer !== logical || logical.closed))) reject()
            if (logical?.finalizing == true) OutputPhase.FINALIZING else if (logical != null) OutputPhase.WRITING else OutputPhase.ACTIVE
        }
        requireOutputAccess(descriptor, phase)
        synchronized(lock) {
            if (target !== exact || (logical != null && (writer !== logical || logical.closed))) reject()
            if (phase == OutputPhase.ACTIVE || phase == OutputPhase.WRITING) accepting()
            outputCalls++; if (logical != null) logical.calls++
        }
        var primary: Throwable? = null
        try {
            outputEvent(descriptor, phase)
            val result = block(descriptor, phase)
            requireOutputAccess(descriptor, phase)
            synchronized(lock) { if (logical?.closed == true) throw LogicalWriterClosed() }
            return result
        } catch (failure: Throwable) {
            // Select the established boundary reason before publishing failed state or recording it.
            // Positive release uncertainty is independent and must not be relabelled as a stop.
            primary = failure
            if (!boundaryAlreadySelected(failure) && (failure !is SaveFailure || failure.code != SaveError.RESOURCE_RELEASE_UNCONFIRMED)) {
                try { owner.checkStop(context) } catch (stop: Throwable) { primary = stop }
            }
            val chosen = requireNotNull(primary)
            captureOutputBoundaryFailure(exact, chosen)
            synchronized(lock) {
                outputContentFailed = true
                if (phase == OutputPhase.FINALIZING) finalizationFailed = true
            }
            throw chosen
        } finally {
            synchronized(lock) {
                outputCalls--
                if (logical != null) {
                    logical.calls--
                    if (logical.closed && logical.calls == 0) logical.drained = true
                }
            }
            try { drain() } catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) }
        }
    }
    private inner class Target(val binding: OwnedWebmInputBinding, val session: TransformerSession) : TransformerOwnedTarget {
        override val muxerToken = "owned-transcode-" + UUID.randomUUID().toString()
        override fun verifyIdentity() = outputOperation(this) { descriptor, phase -> descriptor.verifyIdentity(phase) }
        override fun sizeBytes(): Long = outputOperation(this) { descriptor, phase -> descriptor.size(phase) }
        override fun availableBytes(): Long = outputOperation(this) { descriptor, phase -> descriptor.available(phase) }
        override fun openEmptyWriter(): TransformerWriter = outputOperation(this) { descriptor, phase ->
            synchronized(lock) { if (writerStarted) reject(); writerStarted = true }
            if (descriptor.size(phase) != 0L) reject()
            val opened = Writer(this)
            synchronized(lock) { accepting(); writer = opened }
            opened
        }
        override fun retainNativeWriter(): AutoCloseable = try { retainWriterChecked() }
        catch (failure: Throwable) { captureOutputBoundaryFailure(this, failure); throw failure }
        private fun retainWriterChecked(): AutoCloseable {
            requireRegisteredSession(binding, session); session.requireEntered()
            synchronized(lock) {
                accepting()
                if (target !== this || writerRetentionStarted || writer == null || writer?.closed != false || outputContentFailed) reject()
                activeOutputBudget.event(); writerRetentionStarted = true; writerRetaining = true
            }
            var acquired = false
            var primary: Throwable? = null
            try {
                // Finish throwing construction before publishing the irreversible native pin.
                val retention = NativeWriterRetention(session, this)
                synchronized(lock) {
                    accepting()
                    if (writer?.closed != false) reject()
                    acquired = true; nativeWriterRetentions++
                }
                // Session stores the positive return before its next stop check.
                return retention
            } catch (failure: Throwable) {
                val chosen = chooseBoundaryFailure(failure, output = true)
                primary = chosen
                captureOutputBoundaryFailure(this, chosen)
                if (acquired) outputDescriptor().markAcquisitionUncertain()
                throw chosen
            } finally {
                synchronized(lock) { writerRetaining = false }
                // Never run a throwing cleanup after a positive pin return; its same session owns release.
                if (!acquired) try { drain() }
                catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) }
            }
        }
    }

    private inner class Writer(val target: Target) : TransformerWriter {
        var calls = 0
        var closed = false
        var finalizing = false
        @Volatile var drained = false
        private val range = TransformerOutputIoPolicy(outputMaximum)
        override val releaseConfirmed: Boolean get() = drained
        override fun isOpen(): Boolean = synchronized(lock) { !closed }
        override fun write(source: ByteBuffer): Int = outputOperation(target, this) { descriptor, phase ->
            // Precharge the complete request before metadata, allocation or physical IO.
            outputRequest(descriptor, phase, source.remaining().toLong())
            if (!source.hasRemaining()) 0 else {
                // Same lifetime policy, independently from the muxer's own bounded view.
                range.range(descriptor.position(phase), source.remaining().toLong())
                descriptor.write(source, phase)
            }
        }
        override fun getPosition(): Long = outputOperation(target, this) { descriptor, phase -> descriptor.position(phase) }
        override fun setPosition(position: Long) = outputOperation(target, this) { descriptor, phase ->
            range.range(position, 0); descriptor.seek(position, phase)
        }
        override fun getSize(): Long = outputOperation(target, this) { descriptor, phase -> descriptor.size(phase) }
        override fun truncate(size: Long) = outputOperation(target, this) { descriptor, phase ->
            range.range(size, 0); descriptor.truncate(size, phase)
        }
        override fun force() = outputOperation(target, this) { descriptor, phase -> descriptor.force(phase) }
        override fun beginFinalization() = try { beginFinalizationChecked() }
        catch (failure: Throwable) { captureOutputBoundaryFailure(target, failure); throw failure }
        private fun beginFinalizationChecked() {
            owner.requireTranscodeSessionIdentity(this@OwnedDirectTranscodeAttempt, target.binding, target.session)
            target.session.requireWriterFinalization(target)
            synchronized(lock) {
                if (writer !== this || closed || calls != 0 || outputResourceUncertain || finalizationFailed || sealingStarted) reject()
                if (!finalizing) {
                    finalizing = true
                    finalizationDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(minOf(5000L, target.session.limits.releaseTimeoutMillis))
                    range.beginFinalization()
                }
            }
            outputOperation(target, this) { _, _ -> Unit }
        }
        override fun close() {
            if (!owner.requireTranscodeReleaseOwner(this@OwnedDirectTranscodeAttempt)) return
            synchronized(lock) {
                if (writer !== this) reject()
                closed = true
                if (calls == 0) drained = true
            }
            // Range closure is bookkeeping; physical FD close waits for native release and calls.
            range.confirmedClosed(); drain()
        }
    }
    private inner class NativeWriterRetention(private val session: TransformerSession, private val target: Target) : AutoCloseable {
        private val ended = AtomicBoolean()
        override fun close() {
            if (ended.get()) return
            session.requireWriterRelease(target)
            if (!ended.compareAndSet(false, true)) return
            synchronized(lock) { check(nativeWriterRetentions == 1); nativeWriterRetentions--; nativeWriterReleased = true }
            drain()
        }
    }
    private class LogicalWriterClosed : IllegalStateException("TRANSCODE_WRITER_CLOSED")

    internal fun requireSealingAnchor(descriptor: AndroidNormalizationFiles.AsyncAnchor) {
        owner.checkStop(context); owner.checkTranscode(this)
        val boundSession = exactTarget().session
        if (boundSession.contentFailed() || boundSession.cleanupFailed() || boundSession.abandoned.get() || !boundSession.releaseAcknowledged()) reject()
        synchronized(lock) {
            if (!sealing || !sealingStarted || sealed || closeRequested || failed || outputContentFailed || outputResourceUncertain ||
                descriptor !== anchor && descriptor !== outputAnchor || nativeRetentions != 0 || nativeWriterRetentions != 0 ||
                !nativeSourceReleased || !nativeWriterReleased) reject()
        }
    }
    private fun sealBudget(descriptor: AndroidNormalizationFiles.AsyncAnchor): WorkBudget {
        requireSealingAnchor(descriptor)
        return synchronized(lock) { if (descriptor === anchor) rawSealBudget else if (descriptor === outputAnchor) outputSealBudget else reject() }
    }
    internal fun sealEvent(descriptor: AndroidNormalizationFiles.AsyncAnchor) { val budget = sealBudget(descriptor); synchronized(lock) { budget.event() } }
    internal fun sealRequest(descriptor: AndroidNormalizationFiles.AsyncAnchor, bytes: Long) { val budget = sealBudget(descriptor); synchronized(lock) { budget.request(bytes) } }
    internal fun sealPhysical(descriptor: AndroidNormalizationFiles.AsyncAnchor, bytes: Long) { val budget = sealBudget(descriptor); synchronized(lock) { budget.physical(bytes) } }
    /** Resource observations only. No caller outcome/facts, and no TRANSCODE -> FINISH transition. */
    internal fun sealResources(): Pair<NormalizationBoundFile, NormalizationBoundFile> {
        requireRegistered()
        val boundSession = exactTarget().session
        if (boundSession.contentFailed() || boundSession.cleanupFailed() || boundSession.abandoned.get() || !boundSession.releaseAcknowledged()) reject()
        val rawAnchor: AndroidNormalizationFiles.AsyncRawDescriptor
        val output: AndroidNormalizationFiles.AsyncOutputDescriptor
        synchronized(lock) {
            accepting()
            if (sealingStarted || target == null || writer == null || writer?.finalizing != true || writer?.drained != true ||
                calls != 0 || outputCalls != 0 || anchorOpening || outputOpening || writerRetaining || readers.isNotEmpty() ||
                nativeRetentions != 0 || nativeWriterRetentions != 0 || !nativeSourceReleased || !nativeWriterReleased ||
                outputContentFailed || outputResourceUncertain) reject()
            sealingStarted = true; sealing = true
            rawAnchor = anchor ?: reject(); output = outputAnchor ?: reject()
        }
        var primary: Throwable? = null
        try {
            output.requireForced()
            rawAnchor.verifySealing(this); output.verifySealing()
            val observedRaw = rawAnchor.hashRetained(this)
            if (observedRaw.fingerprint != raw.fingerprint || observedRaw.fingerprint != writingRow.raw ||
                observedRaw.fingerprint.size != expectedRecord.transfer?.bytesOnDisk ||
                sourceTransferFingerprint(expectedRecord.transfer ?: reject()) != writingRow.sourceTransferSha256) reject()
            val observedOutput = output.hashRetained()
            rawAnchor.verifySealing(this); output.verifySealing()
            requireSealingAnchor(rawAnchor); requireSealingAnchor(output)
            synchronized(lock) { sealClosing = true }
            closeBoth(rawAnchor, output)
            if (!rawAnchor.releaseConfirmed || !output.releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            owner.checkStop(context); owner.checkTranscode(this)
            if (boundSession.contentFailed() || boundSession.cleanupFailed() || boundSession.abandoned.get() || !boundSession.releaseAcknowledged()) reject()
            return synchronized(lock) {
                if (closeRequested || failed || outputContentFailed || outputResourceUncertain) reject()
                (observedRaw to observedOutput).also { resourceObservations = it; sealed = true; sealing = false }
            }
        } catch (failure: Throwable) {
            primary = failure; failed = true; synchronized(lock) { resourceObservations = null; sealClosing = true }
            throw failure
        } finally {
            if (primary != null) try { closeBoth(rawAnchor, output) }
            catch (cleanup: Throwable) { primary.addSuppressed(cleanup) }
        }
    }
    /** Memory only, under owner -> attempt lock order. No retired pair/observer calls. */
    internal fun matchesFinishRegistration(binding: OwnedWebmInputBinding?, session: TransformerSession?): Boolean = synchronized(lock) {
        binding != null && session != null && runPair === binding && runSession === session &&
            target?.binding === binding && target?.session === session
    }
    private fun requireFinishResources() {
        terminalRunFailure?.let { throw it }
        firstOriginalFailure?.let { throw it }
        val session = runSession ?: reject()
        if (failed || closeRequested || outputAbortRequested || outputContentFailed || finalizationFailed || outputResourceUncertain ||
            !sealed || !sealingStarted || sealing || !sealClosing || anchorOpening || outputOpening || writerRetaining ||
            calls != 0 || outputCalls != 0 || readers.isNotEmpty() || nativeRetentions != 0 || nativeWriterRetentions != 0 ||
            !nativeSourceReleased || !nativeWriterReleased || writer?.finalizing != true || writer?.drained != true ||
            writer?.closed != true || writer?.calls != 0 || anchor?.releaseConfirmed != true || outputAnchor?.releaseConfirmed != true ||
            session.contentFailed() || session.cleanupFailed() || session.abandoned.get() || !session.releaseAcknowledged() ||
            runEngine == null || runPair == null || runSource == null || runTarget !== target) reject()
    }
    /** A rejected concurrent caller never acquires cleanup authority over the first finisher. */
    internal fun claimFinish() = synchronized(lock) {
        if (finishClaimed || runPhase == RunPhase.HANDED_OFF) reject()
        finishClaimed = true
    }
    /** The sole proof producer consumes only this attempt's private actual successful run. */
    internal fun consumeForFinish(): DirectConversionProof = synchronized(lock) {
        if (!finishClaimed || runPhase != RunPhase.SEALED) { terminalRunFailure?.let { throw it }; firstOriginalFailure?.let { throw it }; reject() }
        requireFinishResources()
        runPhase = RunPhase.FINISHING // Never reset even if subsequent validation fails.
        val facts = when (val outcome = actualRunOutcome) {
            is TransformerOutcome.Completed -> outcome.value
            else -> reject()
        }
        val observed = facts.sourceObservation ?: reject()
        val output = runOutput ?: reject()
        val timeline = runTimeline ?: reject()
        val transport = runTransport ?: reject()
        val completed = runCompletedTransport ?: reject()
        val resources = resourceObservations ?: reject()
        val transfer = expectedRecord.transfer ?: reject()
        val metadata = expectedRecord.request.networkSourceMetadata ?: reject()
        val video = observed.video
        val sourceVideo = transport.tracks.singleOrNull { it.trackType == C.TRACK_TYPE_VIDEO } ?: reject()
        if ((metadata.expectedAudio == true && observed.audio == null) ||
            !matchesAudioObservation(output, observed.audio, facts.audioSamples) ||
            !sameFinishedTransport(transport, completed) || transport.inputExtent != extent ||
            output != WebmDirectCandidatePolicy.select(transport, outputMaximum) ||
            timeline.durationUs != output.durationUs || timeline.frameRate != output.frameRate ||
            timeline.demuxedVideoStartUs != sourceVideo.firstSourceTimestampUs ||
            video.sourceDurationUs != output.durationUs || video.frameRate != output.frameRate ||
            facts.videoSamples != video.displayedFrames || video.displayedFrames != output.expectedVideoFrames ||
            resources.first != raw || resources.first.fingerprint != writingRow.raw || transfer.bytesOnDisk != extent ||
            sourceTransferFingerprint(transfer) != writingRow.sourceTransferSha256 ||
            resources.second.fingerprint.size != facts.bytesWritten || facts.bytesWritten !in 1..outputMaximum ||
            !resources.second.identity.stat.sameInode(emptyOutput.stat) ||
            resources.second.identity.parents.size != emptyOutput.parents.size ||
            resources.second.identity.parents.zip(emptyOutput.parents).any { (a, b) -> a.first != b.first || !a.second.sameInode(b.second) }) reject()
        val heard = observed.audio
        // Technical PCM observations permit the actual chain to run and release resources, but
        // cannot certify audible head preservation without the separate native marker acceptance.
        if (heard != null) requireVerifiedDirectOpusMapping(heard)
        val audioExpectation = heard?.let {
            DirectConversionAudioExpectation(it.sourceFirstLocalPtsUs, it.sourceEndLocalPtsUs,
                it.sourcePcmFrames, it.encoderPcmFrames, it.channels, it.codecDelayUs,
                it.seekPrerollUs, it.discardPaddingUs, it.sampleRate)
        }
        val expectation = DirectConversionExpectation(DirectConversionVideoExpectation(output.width, output.height,
            video.frameRate, video.sourceDurationUs, video.displayedFrames, video.firstLocalPtsUs,
            video.lastLocalPtsUs, video.maximumFrameDeviationUs), audioExpectation)
        val settings = DirectConversionEncodingSettings(output.videoBitrate, output.audio?.let { output.audioBitrate })
        if (!settings.matches(expectation)) reject()
        DirectConversionProof(key.normalizationId, key.owner.taskId, metadata.sourceIdentity, key.owner.artifactId,
            key.owner.commitId, raw.fingerprint.size, raw.fingerprint.sha256, writingRow.sourceTransferSha256,
            resources.second.fingerprint.size, resources.second.fingerprint.sha256, expectation, settings,
            DirectConversionCompletionFacts(true, true, true, true, true, true,
                heard?.let { true }, heard?.let { true }, heard?.let { true }, heard?.let { true },
                heard?.let { true }, heard?.let { true }), policyRevision = if (heard == null) 1 else 2).also {
                if (!it.matchesSource(expectedRecord.task.taskId, metadata.sourceIdentity, expectedRecord.artifact ?: reject(), transfer)) reject()
            }
    }
    private fun requireVerifiedDirectOpusMapping(heard: TransformerAudioObservation) {
        // Exhaustive finite policy: there is deliberately no accepted device profile or caller override.
        when (heard.headMapping) {
            TransformerOpusHeadMapping.UNVERIFIED_HEAD_REMOVED_ONCE_ZERO_PCM_ORIGIN -> {
                OwnedDirectTechnicalDiagnostics.record(context.operationId, OwnedDirectTechnicalDiagnostics.Stage.UNVERIFIED_MAPPING_REJECTED)
                throw TransformerFailure(TransformerError.UNSUPPORTED_PROFILE)
            }
        }
    }
    private fun matchesAudioObservation(output: TransformerOutputSpec, heard: TransformerAudioObservation?, encodedSamples: Long): Boolean =
        if (output.audio == null) heard == null && encodedSamples == 0L && output.audioBitrate == 0
        else heard != null && encodedSamples > 0 && heard.sampleRate == output.audio.sampleRate &&
            heard.channels == output.audio.channels && heard.sourcePcmFrames in 1..86_400_000L &&
            heard.encoderPcmFrames in heard.sourcePcmFrames..86_400_000L

    private fun sameFinishedTransport(a: WebmTransportFacts, b: WebmTransportFacts): Boolean =
        a.inputExtent == b.inputExtent && a.declaredContainerDurationUs == b.declaredContainerDurationUs &&
            a.demuxerDurationUs == b.demuxerDurationUs && a.timecodeScaleNs == b.timecodeScaleNs &&
            a.maximumPhysicalMasterDepth == b.maximumPhysicalMasterDepth &&
            a.outstandingMastersAtEof == b.outstandingMastersAtEof && a.actualUnknownEndCallbacks == b.actualUnknownEndCallbacks &&
            a.checkedHeaders == b.checkedHeaders && a.delegatedPayloads == b.delegatedPayloads &&
            a.tracks.size == b.tracks.size && a.tracks.zip(b.tracks).all { (x, y) ->
                x.trackNumber == y.trackNumber && x.trackType == y.trackType && x.mimeType == y.mimeType &&
                    x.width == y.width && x.height == y.height && x.sampleRate == y.sampleRate && x.channelCount == y.channelCount &&
                    x.initializationData.size == y.initializationData.size && x.initializationData.zip(y.initializationData).all { (u, v) -> u.contentEquals(v) } &&
                    x.sourceSampleCallbacks == y.sourceSampleCallbacks && x.firstSourceTimestampUs == y.firstSourceTimestampUs &&
                    x.lastSourceTimestampUs == y.lastSourceTimestampUs && x.supplementalBytes == y.supplementalBytes &&
                    x.declaredDefaultDurationNs == y.declaredDefaultDurationNs
            }
    internal fun requireFinishing() = synchronized(lock) {
        if (runPhase != RunPhase.FINISHING) reject()
        requireFinishResources()
    }
    /** The short memory snapshot is complete before any filesystem call. */
    internal fun validateFinishResources(operation: AndroidNormalizationFiles.Operation) {
        val resources = synchronized(lock) { requireFinishing(); resourceObservations ?: reject() }
        operation.requireReleased(key)
        operation.validate(key, NormalizationRole.PRIMARY, resources.first.identity)
        operation.validate(key, NormalizationRole.OUTPUT, resources.second.identity)
    }
    internal fun markHandedOff() = synchronized(lock) { requireFinishing(); runPhase = RunPhase.HANDED_OFF }
    internal fun rejectFinish(failure: Throwable): Throwable = synchronized(lock) {
        failed = true; runPhase = RunPhase.FAILED
        terminalRunFailure ?: (actualRunOutcome as? TransformerOutcome.Failed)?.primary ?: firstOriginalFailure ?: failure
    }
    /** Called only by the owner while holding the same lock used for completion handoff. */
    internal fun claimClose() = synchronized(lock) { if (runPhase == RunPhase.HANDED_OFF) reject(); closeRequested = true }

    private fun closeBoth(raw: AndroidNormalizationFiles.AsyncRawDescriptor?, output: AndroidNormalizationFiles.AsyncOutputDescriptor?) {
        var failure: Throwable? = null
        try { raw?.requestClose() } catch (caught: Throwable) { failure = caught }
        try { output?.requestClose() } catch (caught: Throwable) { if (failure == null) failure = caught else failure.addSuppressed(caught) }
        failure?.let { throw it }
    }

    private fun entered(binding: OwnedWebmInputBinding): TransformerSession {
        requireRegisteredBinding(binding)
        val session = owner.inputSession(this, binding)
        requireRegisteredSession(binding, session); session.requireEntered()
        return session
    }
    private fun accepting() { if (closeRequested || failed || sealingStarted) reject() }
    private fun descriptor(): AndroidNormalizationFiles.AsyncRawDescriptor = synchronized(lock) { anchor ?: reject() }
    private fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position > extent || length < 0 || length.toLong() > extent - position ||
            offset < 0 || offset > target.size || length > target.size - offset) reject()
        if (length == 0) return 0
        val requested = minOf(CHUNK, length)
        synchronized(lock) {
            accepting()
            if (requested.toLong() > physicalLimit - physicalBytes) limit()
            physicalBytes += requested // Reserved before pread; short/failed calls never refund.
        }
        return descriptor().readAt(position, target, offset, requested)
    }
    private fun <T> operation(binding: OwnedWebmInputBinding, reader: Reader? = null, block: () -> T): T {
        requireRegisteredBinding(binding)
        if (reader != null) { requireRegisteredSession(binding, reader.session); reader.session.requireEntered() }
        synchronized(lock) {
            accepting()
            if (reader != null) {
                if (reader !in readers || reader.closed || reader.busy) reject()
                reader.busy = true
            }
            calls++
        }
        var primary: Throwable? = null
        try {
            val result = block()
            requireRegisteredBinding(binding)
            if (reader != null) { requireRegisteredSession(binding, reader.session); reader.session.requireEntered() }
            synchronized(lock) { accepting(); if (reader?.closed == true) throw LogicalReaderClosed() }
            return result
        } catch (failure: Throwable) {
            primary = failure
            if (failure is IOException && !boundaryAlreadySelected(failure)) {
                try { owner.checkStop(context) } catch (stop: Throwable) { primary = stop }
            }
            val chosen = requireNotNull(primary)
            // Preserve the pre-existing blocked-IO stop policy, then publish its exact object.
            captureInputOperationFailure(binding, chosen)
            if (failure !is LogicalReaderClosed) failed = true
            throw chosen
        } finally {
            synchronized(lock) {
                calls--
                if (reader != null) {
                    reader.busy = false
                    if (reader.closed) { readers.remove(reader); reader.drained = true }
                }
            }
            try { drain() } catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) }
        }
    }
    private inner class Reader(val binding: OwnedWebmInputBinding, val session: TransformerSession) : TransformerReadHandle {
        var busy = false
        var closed = false
        @Volatile var drained = false
        override val extent: Long get() { requireRegisteredBinding(binding); return this@OwnedDirectTranscodeAttempt.extent }
        override val releaseConfirmed: Boolean get() = drained
        override fun verifyIdentity() = operation(binding, this) { descriptor().verifyIdentity() }
        override fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int =
            operation(binding, this) { read(position, target, offset, length) }
        override fun close() {
            synchronized(lock) {
                closed = true
                if (!busy) { readers.remove(this); drained = true }
            }
            drain()
        }
    }
    /** Held only by the exact paired session; closing is irreversible release-only cleanup. */
    private inner class NativeRetention(private val session: TransformerSession, private val source: TransformerOwnedSource) : AutoCloseable {
        private val ended = AtomicBoolean()
        override fun close() {
            if (ended.get()) return
            session.requireSourceRelease(source)
            if (!ended.compareAndSet(false, true)) return
            synchronized(lock) {
                // No live/entered check: the real session releases after cancellation and worker exit.
                check(nativeRetentions == 1)
                nativeRetentions--; nativeSourceReleased = true
            }
            drain()
        }
    }
    private fun released() { owner.retireTranscode(this) }
    private fun drain() {
        val closing = synchronized(lock) {
            val abort = closeRequested || outputAbortRequested
            val rawClosing = if ((abort || sealClosing) && !anchorOpening && calls == 0 && nativeRetentions == 0 && readers.isEmpty()) anchor else null
            if (abort && nativeWriterRetentions == 0 && !outputOpening && !writerRetaining) writer?.let {
                it.closed = true; if (it.calls == 0) it.drained = true
            }
            val outputClosing = if ((abort || sealClosing) && !outputOpening && !writerRetaining && outputCalls == 0 && nativeWriterRetentions == 0 &&
                (writer == null || writer?.drained == true)) outputAnchor else null
            rawClosing to outputClosing
        }
        closeBoth(closing.first, closing.second)
        owner.retireTranscode(this)
    }
    internal fun isRetirable(): Boolean = synchronized(lock) {
        closeRequested && !anchorOpening && !outputOpening && !writerRetaining && calls == 0 && outputCalls == 0 && nativeRetentions == 0 &&
            nativeWriterRetentions == 0 && readers.isEmpty() && (writer == null || writer?.drained == true) &&
            (anchor?.releaseConfirmed != false) && (outputAnchor?.releaseConfirmed != false)
    }
    override fun close() {
        if (!owner.claimTranscodeClose(this)) return
        val closing = synchronized(lock) { readers.toList() }
        var primary: Throwable? = null
        closing.forEach { try { it.close() } catch (failure: Throwable) { if (primary == null) primary = failure else primary!!.addSuppressed(failure) } }
        try { drain() } catch (failure: Throwable) { if (primary == null) primary = failure else primary!!.addSuppressed(failure) }
        primary?.let { throw it }
        if (files.releaseUnconfirmed()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    }
    private class LogicalReaderClosed : IllegalStateException("TRANSCODE_READER_CLOSED")
    private fun reject(): Nothing = throw NormalizationRecovery()
    private fun limit(): Nothing = throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
    internal companion object {
        private const val CHUNK = 64 * 1024
        private const val MAX_READERS = 4
        private const val MAX_OPENS = 8
        private const val MAX_EVENTS = 2_000_000L
        private const val MAX_LOCAL_BYTES = 32L * 1024 * 1024 * 1024
        internal fun createUnregistered(owner: NormalizationAttemptOwner, files: AndroidNormalizationFiles,
            row: NormalizationWorkspace, context: OperationContext, raw: NormalizationBoundFile,
            empty: NormalizationFileIdentity, maximum: Long, reservation: NormalizationIoReservations.Token,
            record: SaveRecord, scope: AndroidDirectExecutionBridge.Scope) =
            OwnedDirectTranscodeAttempt(owner, files, row, context, raw, empty, maximum, reservation, record, scope)
    }
}
