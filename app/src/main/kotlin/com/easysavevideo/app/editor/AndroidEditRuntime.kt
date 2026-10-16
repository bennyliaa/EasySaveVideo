package com.easysavevideo.app.editor

import android.content.Context
import com.easysavevideo.app.ui.RuntimeMessageCopy
import com.easysavevideo.app.ui.RuntimeMessageText
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.app.editor.engine.*
import com.easysavevideo.app.editor.ui.*
import com.easysavevideo.app.editor.verification.AndroidEditOutputVerifier
import com.easysavevideo.app.editor.workflow.*
import com.easysavevideo.app.library.thumbnail.OrdinaryMediaNativeResources
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.app.library.destination.AndroidOrdinaryDestinationRuntime
import com.easysavevideo.app.library.destination.OrdinaryDestinationReceipt
import com.easysavevideo.library.destination.OrdinaryDestinationAdmission
import com.easysavevideo.app.entitlements.BuildEntitlements
import com.easysavevideo.core.domain.EntitlementManager
import com.easysavevideo.core.domain.FeatureGate
import com.easysavevideo.core.model.*
import com.easysavevideo.library.*
import com.easysavevideo.library.edit.*
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Application-scoped editor, foreground-only admission and one bounded worker. No paid stop gate. */
class AndroidEditRuntime internal constructor(context: Context, private val storage: AndroidSaveStorage,
    private val destinations: AndroidOrdinaryDestinationRuntime,
    private val text: RuntimeMessageText,
    private val otherMediaIdle: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "esv-edit-work").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val engine = Media3EditorEngine(context.applicationContext, object : EditorInputPort {
        override fun open(reference: MediaReadRef) = storage.openLibraryMedia(reference)
        override fun retainNativeReader(reference: MediaReadRef) = storage.retainNativeMediaReader(reference)
    })
    private val verifier = AndroidEditOutputVerifier(storage.edits::openVerification)
    private val editGate = EditNativeResourceGate()
    private val entitlements = EntitlementManager(BuildEntitlements.source)
    private val featureGate = FeatureGate()
    private var activeCancellation: EditPublicationCancellation? = null
    private var activeOperation: String? = null
    private var admissionGeneration = 0L
    private val backGate = EditorBackGate()
    private val submissionAttempts = EditorSubmissionAttempts()
    private var pendingDestination: OrdinaryDestinationReceipt? = null
    private val deadlines = EditorDeadlineGuard(EditorTimerScheduler { delayMillis, action ->
        val callback = Runnable(action); main.postDelayed(callback, delayMillis)
        AutoCloseable { main.removeCallbacks(callback) }
    }, onDeadline = { cancel() }, onUnconfirmedStop = { identity ->
        val feedback = editorUnconfirmedStopFeedback(identity, activeCancellation)
        if (feedback != null) state = state.copy(
            phase = if (feedback == EditorStopFeedback.CHECKING_RESULT) EditorPhase.PUBLISHING else EditorPhase.WAITING_NATIVE_RELEASE,
            notice = if (feedback == EditorStopFeedback.CHECKING_RESULT) publicationCheckingNotice
                else text.get(RuntimeMessageCopy.EDITOR_STOP_WAITING),
            stopFeedback = feedback)
    })
    private var recovered = false
    private var draftStarted = false
    private val draftRestoreGuard = EditorDraftRestoreGuard()
    private var pendingDraftCheckpoint: String? = null
    private var restoringDraftCheckpoint: String? = null
    private var restoredNeedsExplicitAction = false
    var state by mutableStateOf(EditorViewState(foreground = false)); private set
    init {
        worker.execute {
            val recovery = runCatching { storage.edits.recover() }
            main.post {
                recovered = recovery.isSuccess
                state = state.copy(phase = if (recovered) EditorPhase.IDLE else EditorPhase.BLOCKED,
                    notice = recovery.fold({ if (it.interrupted + it.cleanupPending > 0)
                        text.get(RuntimeMessageCopy.EDITOR_INTERRUPTED_RECOVERY, it.cleanupPending) else null },
                        { text.get(RuntimeMessageCopy.EDITOR_RECOVERY_INCOMPLETE) }))
                val checkpoint = pendingDraftCheckpoint; pendingDraftCheckpoint = null
                if (checkpoint != null) restoreDraftCheckpoint(checkpoint)
                else if (recovered && state.foreground) inspectMissingSources()
            }
        }
    }
    fun open(item: MediaItem, available: List<MediaItem>, settings: EditorSettings) {
        requireMain()
        if (state.busy || state.phase == EditorPhase.BLOCKED) return
        retireSubmission()
        draftRestoreGuard.userChanged(); restoredNeedsExplicitAction = false; pendingDraftCheckpoint = null; restoringDraftCheckpoint = null
        val draft = openEditorDraft(state.draft, item, settings, startNew = !draftStarted || state.phase == EditorPhase.SUCCEEDED)
        draftStarted = true
        state = state.copy(draft = draft, displayGeometries = state.displayGeometries.filterKeys { ref -> draft.segments.any { it.reference == ref } }, availableItems = available.filter { it.lifecycle == MediaLifecycle.READY },
            phase = EditorPhase.IDLE, completedReference = null, executionRanges = emptyList(), draftRestoration = EditorDraftRestoration.NONE)
    }
    fun setForeground(value: Boolean) {
        requireMain(); state = state.copy(foreground = value)
        if (!value) backGate.revoke()
        if (!value) { admissionGeneration++; cancel() }
        else if (recovered && !state.busy && state.phase != EditorPhase.BLOCKED) inspectMissingSources()
    }
    fun changeDraft(draft: EditorDraft) {
        requireMain()
        if (!state.editable || activeCancellation != null) return
        draftStarted = true
        draftRestoreGuard.userChanged(); restoredNeedsExplicitAction = false; pendingDraftCheckpoint = null; restoringDraftCheckpoint = null
        state = state.copy(draft = draft, displayGeometries = state.displayGeometries.filterKeys { ref -> draft.segments.any { it.reference == ref } },
            phase = EditorPhase.IDLE, notice = null, completedReference = null, executionRanges = emptyList(), draftRestoration = EditorDraftRestoration.NONE)
        inspectMissingSources()
    }
    /** Metadata inspection is free; no edit capability is exercised until explicit Submit. */
    private fun inspectMissingSources(selectionAccepted: Boolean = false) {
        if (restoredNeedsExplicitAction || !state.foreground || !recovered || state.busy ||
            (state.submissionPending && !selectionAccepted) || activeCancellation != null) return
        val refs = state.draft.segments.map { it.reference }.distinct().filter { it !in state.displayGeometries }
        if (refs.isEmpty()) return
        val generation = ++admissionGeneration
        val deadline = android.os.SystemClock.elapsedRealtime() + EditLimits.RELEASE_TIMEOUT_MILLIS
        state = state.copy(phase = EditorPhase.PROBING)
        fun attempt() {
            if (generation != admissionGeneration || !state.foreground || activeCancellation != null) return
            if (!otherMediaIdle() || OrdinaryMediaNativeResources.isOccupied) {
                if (android.os.SystemClock.elapsedRealtime() < deadline && !OrdinaryMediaNativeResources.requiresRestart) main.postDelayed({ attempt() }, 50)
                else state = state.copy(phase = EditorPhase.IDLE, notice = text.get(RuntimeMessageCopy.EDITOR_WAIT_MEDIA_TASKS))
                return
            }
            val owner = Any()
            if (!OrdinaryMediaNativeResources.claim(owner)) {
                state = state.copy(phase = EditorPhase.BLOCKED, notice = text.get(RuntimeMessageCopy.EDITOR_PROBE_RELEASE_UNCONFIRMED))
                return
            }
            val cancellation = EditPublicationCancellation(); activeCancellation = cancellation
            val operationId = UUID.randomUUID().toString(); activeOperation = operationId
            val context = operation(operationId, cancellation)
            deadlines.start(cancellation, (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1))
            enqueue(owner) {
                val geometry = linkedMapOf<MediaReadRef, EditorDisplayGeometry>()
                val outcome = runCatching {
                    refs.forEach { ref ->
                        val observed = engine.probe(ref, context)
                        val (w, h) = if (observed.rotationDegrees in setOf(90, 270)) observed.height to observed.width else observed.width to observed.height
                        geometry[ref] = EditorDisplayGeometry(w, h, observed.durationUs)
                    }
                }
                val unknown = engine.requiresProcessRecovery()
                releaseGlobal(owner, unknown)
                main.post {
                    deadlines.completed(cancellation)
                    activeCancellation = null; activeOperation = null
                    val corrected = state.draft.copy(segments = state.draft.segments.map { segment ->
                        val originalDefault = segment.item.durationMillis?.let { editorSeconds(it * 1_000) }
                        val observed = geometry[segment.reference]
                        if (observed != null && (segment.endSeconds.isEmpty() || segment.endSeconds == originalDefault))
                            segment.copy(endSeconds = editorSeconds(observed.durationUs)) else segment
                    })
                    state = state.copy(draft = corrected, displayGeometries = state.displayGeometries + geometry,
                        phase = if (unknown) EditorPhase.BLOCKED else if (cancellation.isCancelled) EditorPhase.CANCELLED else EditorPhase.IDLE,
                        notice = if (unknown) releaseNotice else outcome.exceptionOrNull()?.let { errorText(it, text) })
                }
            }
        }
        main.post { attempt() }
    }
    fun submit(draft: EditorDraft) {
        requireMain()
        // A new composition can tap the same revision; it must not retire the original attempt.
        val attempt = submissionAttempts.begin(state.submissionRevision) ?: return
        if (!recovered || !state.editable || state.draft != draft || activeCancellation != null) {
            finishSubmission(attempt); return
        }
        state = state.copy(submissionPending = true)
        draftRestoreGuard.userChanged(); restoredNeedsExplicitAction = false; pendingDraftCheckpoint = null; restoringDraftCheckpoint = null
        val generation = admissionGeneration
        val receipt = destinations.select(isCurrent = { submissionAttempts.isCurrent(attempt) &&
            state.foreground && !state.busy && state.phase != EditorPhase.BLOCKED &&
            state.draft == draft && admissionGeneration == generation },
            onTerminal = { finishSubmission(attempt) },
            accepted = { admission -> if (submissionAttempts.isCurrent(attempt)) submitSelected(draft, admission) })
        // Rejection may have acknowledged synchronously before select returns its receipt.
        if (submissionAttempts.isCurrent(attempt)) pendingDestination = receipt
    }
    private fun finishSubmission(attempt: EditorSubmissionAttempts.Attempt) {
        val revision = submissionAttempts.finish(attempt) ?: return
        pendingDestination = null
        state = state.copy(submissionRevision = revision, submissionPending = false)
    }
    private fun retireSubmission() {
        val attempt = submissionAttempts.current ?: return
        val receipt = pendingDestination
        finishSubmission(attempt)
        // Retire only this editor selection; another save/record selection is never cancelled.
        receipt?.let(destinations::cancelSelection)
    }
    private fun submitSelected(draft: EditorDraft, admission: OrdinaryDestinationAdmission) {
        requireMain()
        if (!recovered || !state.foreground || state.busy || activeCancellation != null || state.phase == EditorPhase.BLOCKED) return
        val request = try { draft.toEditRequest(admission.creationId) }
        catch (_: Exception) { state = state.copy(notice = editorDraftError(draft, state.displayGeometries) ?: text.get(RuntimeMessageCopy.EDITOR_INVALID_PARAMETERS)); return }
        if (request.segments.any { it.source !in state.displayGeometries }) {
            state = state.copy(notice = text.get(RuntimeMessageCopy.EDITOR_WAIT_SOURCE_CHECK))
            inspectMissingSources(selectionAccepted = true); return
        }
        val required = editorFeatures(request, state.displayGeometries.mapValues { it.value.durationUs })
        if (required.isEmpty()) { state = state.copy(notice = text.get(RuntimeMessageCopy.EDITOR_UNCHANGED_PARAMETERS)); return }
        if (!featuresAllowed(required)) { state = state.copy(notice = entitlementNotice); return }
        if (!otherMediaIdle()) { state = state.copy(notice = text.get(RuntimeMessageCopy.EDITOR_WAIT_EXISTING_RESOURCES)); return }
        val owner = Any()
        if (!OrdinaryMediaNativeResources.claim(owner)) { state = state.copy(notice = text.get(RuntimeMessageCopy.EDITOR_MEDIA_BUSY)); return }
        val cancellation = EditPublicationCancellation(); activeCancellation = cancellation; activeOperation = request.operationId
        state = state.copy(draft = draft, phase = EditorPhase.PREPARING, notice = null, completedReference = null, executionRanges = emptyList())
        val context = operation(request.operationId, cancellation)
        deadlines.start(cancellation, (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1))
        enqueue(owner) {
            var gateRejected = false
            val port = object : EditWorkPort {
                override fun prepare(request: EditRequest) = storage.edits.prepare(request)
                override fun probe(source: MediaReadRef, context: OperationContext) = engine.probe(source, context).also { metadata ->
                    val (w, h) = if (metadata.rotationDegrees in setOf(90, 270)) metadata.height to metadata.width else metadata.width to metadata.height
                    main.post { state = state.copy(displayGeometries = state.displayGeometries + (source to EditorDisplayGeometry(w, h, metadata.durationUs))) }
                }
                override fun start(output: EditOutputRef, plan: EditPlan) {
                    val needed = editorFeatures(plan.request, plan.segments.associate { it.segment.source to it.metadata.durationUs })
                    if (!featuresAllowed(needed)) { gateRejected = true; throw EditFailure(EditError.INVALID_REQUEST) }
                    storage.edits.start(output, plan)
                    val ranges = plan.segments.map { EditorExecutionRange(it.segment.startUs, it.segment.endUsExclusive,
                        it.executionStartUs, it.executionEndUsExclusive, it.frameCount) }
                    main.post { state = state.copy(executionRanges = ranges) }
                }
                override fun execute(output: EditOutputRef, plan: EditPlan, context: OperationContext) { engine.execute(plan, storage.edits.engineOutput(output), context) }
                override fun beginVerification(output: EditOutputRef) = storage.edits.beginVerification(output)
                override fun verify(output: EditOutputRef, expected: EditOutputExpectation, context: OperationContext) = verifier.verify(output, expected, context)
                override fun publish(output: EditOutputRef, evidence: EditVerificationEvidence, context: OperationContext) = storage.edits.publish(output, evidence, context)
                override fun publishedReference(output: EditOutputRef) = storage.edits.publishedReference(output)
                override fun abort(output: EditOutputRef, cancelled: Boolean, error: EditError) = storage.edits.finishUnpublished(output, cancelled, error)
                override fun fence(output: EditOutputRef) = storage.edits.fence(output)
                override fun nativeReleaseUnconfirmed() = engine.requiresProcessRecovery() || verifier.requiresProcessRecovery() || storage.edits.hasUnconfirmedOwners()
            }
            val result = EditWorkflow(port, editGate).run(request, context) { stage -> main.post {
                if (!cancellation.isCancelled) state = state.copy(phase = when (stage) {
                    EditWorkStage.PREPARING -> EditorPhase.PREPARING
                    EditWorkStage.PROBING -> EditorPhase.PROBING
                    EditWorkStage.PROCESSING -> EditorPhase.PROCESSING
                    EditWorkStage.VERIFYING -> EditorPhase.VERIFYING
                    EditWorkStage.PUBLISHING -> EditorPhase.PUBLISHING
                })
            } }
            destinations.refresh()
            val unknown = port.nativeReleaseUnconfirmed() || (result is EditRunResult.Stopped && result.requiresRecovery)
            releaseGlobal(owner, unknown)
            main.post {
                deadlines.completed(cancellation)
                activeCancellation = null; activeOperation = null
                state = when (result) {
                    is EditRunResult.Published -> state.copy(phase = EditorPhase.SUCCEEDED, completedReference = result.reference,
                        notice = text.get(RuntimeMessageCopy.EDITOR_PUBLISHED))
                    is EditRunResult.Stopped -> state.copy(phase = if (unknown) EditorPhase.BLOCKED else if (result.cancelled) EditorPhase.CANCELLED else EditorPhase.FAILED,
                        notice = if (unknown) releaseNotice else if (gateRejected) entitlementNotice else errorText(EditFailure(result.error), text))
                }
            }
        }
    }
    /** Restores editable parameters only. Never calls a reader, probe, destination, task or player. */
    internal fun restoreDraftCheckpoint(encoded: String) {
        requireMain()
        fun ownerPresent() = state.draft.segments.isNotEmpty() || state.busy || state.submissionPending || activeCancellation != null || state.phase == EditorPhase.BLOCKED
        if (state.phase == EditorPhase.BLOCKED) { state = state.copy(draftRestoration = EditorDraftRestoration.UNAVAILABLE); return }
        if (ownerPresent()) { state = state.copy(draftRestoration = EditorDraftRestoration.OWNER_KEPT); return }
        val checkpoint = EditorDraftCheckpointCodec.decode(encoded)
        if (checkpoint == null) { state = state.copy(draftRestoration = EditorDraftRestoration.INVALID); return }
        if (!recovered) { pendingDraftCheckpoint = encoded; state = state.copy(draftRestoration = EditorDraftRestoration.RESTORING); return }
        val token = draftRestoreGuard.begin(ownerPresent())
        if (token == null) { state = state.copy(draftRestoration = EditorDraftRestoration.USER_CHANGED); return }
        restoredNeedsExplicitAction = true
        restoringDraftCheckpoint = encoded
        state = state.copy(draftRestoration = EditorDraftRestoration.RESTORING)
        try { worker.execute {
            val resolution = runCatching { resolveEditorCheckpoint(checkpoint, storage.library::item) }
            main.post {
                restoringDraftCheckpoint = null
                if (!draftRestoreGuard.canApply(token, ownerPresent())) {
                    state = state.copy(draftRestoration = EditorDraftRestoration.USER_CHANGED)
                    return@post
                }
                val draft = resolution.getOrNull()
                if (draft == null) {
                    state = state.copy(draftRestoration = if (resolution.isSuccess) EditorDraftRestoration.STALE else EditorDraftRestoration.UNAVAILABLE)
                    return@post
                }
                draftStarted = true
                // Capability review uses fresh catalog metadata; native profile review waits for user action.
                val request = runCatching { draft.toEditRequest("draft-review") }.getOrNull()
                val required = if (request == null) setOf(Feature.VIDEO_CROP, Feature.VIDEO_MERGE, Feature.VIDEO_COMPRESS)
                    else editorFeatures(request, draft.segments.associate { it.reference to (it.item.durationMillis?.times(1_000) ?: -1L) })
                val status = editorCheckpointRestorationStatus(checkpoint.priorBusy, featuresAllowed(required))
                state = state.copy(draft = draft, availableItems = draft.segments.map { it.item }.distinctBy { it.itemId },
                    phase = EditorPhase.IDLE, displayGeometries = emptyMap(), completedReference = null,
                    executionRanges = emptyList(), draftRestoration = status)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            restoringDraftCheckpoint = null
            state = state.copy(draftRestoration = EditorDraftRestoration.UNAVAILABLE)
        }
    }
    internal fun savedDraftCheckpoint(): String? {
        requireMain()
        return if (state.draft.segments.isNotEmpty()) EditorDraftCheckpointCodec.capture(state)
            else pendingDraftCheckpoint ?: restoringDraftCheckpoint
    }
    internal fun cancelDraftRestoration() {
        requireMain(); draftRestoreGuard.userChanged(); pendingDraftCheckpoint = null; restoringDraftCheckpoint = null
        if (state.draftRestoration == EditorDraftRestoration.RESTORING) state = state.copy(draftRestoration = EditorDraftRestoration.USER_CHANGED)
    }
    internal fun openBackPage(): EditorBackPage { requireMain(); return backGate.openPage() }
    internal fun closeBackPage(page: EditorBackPage) { requireMain(); backGate.closePage(page) }
    internal fun isBackPageCurrent(page: EditorBackPage): Boolean { requireMain(); return backGate.isCurrent(page) }
    internal fun requestBack(page: EditorBackPage): EditorBackRequest {
        requireMain()
        return backGate.request(page, state.foreground, state.phase, activeCancellation, activeOperation)
    }
    internal fun dismissBack(page: EditorBackPage, confirmation: EditorBackConfirmation) {
        requireMain(); backGate.dismiss(page, confirmation)
    }
    internal fun confirmBackStop(page: EditorBackPage, confirmation: EditorBackConfirmation): EditCancelResult? {
        requireMain()
        val cancellation = backGate.consume(page, confirmation, state.foreground, activeCancellation, activeOperation) ?: return null
        // Validate and consume before *any* mutation; an old dialog cannot retire a new submission.
        admissionGeneration++
        retireSubmission()
        return cancelActive(cancellation)
    }
    internal fun consumeLegacyBack(page: EditorBackPage): Boolean {
        requireMain(); return backGate.consumeLegacy(page, state.foreground, state.phase)
    }
    fun cancel() {
        requireMain(); admissionGeneration++
        retireSubmission()
        val cancellation = activeCancellation
        if (cancellation != null) {
            cancelActive(cancellation)
        }
        else if (state.phase == EditorPhase.PROBING) state = state.copy(phase = EditorPhase.IDLE)
    }
    private fun cancelActive(cancellation: EditPublicationCancellation): EditCancelResult {
        val result = cancellation.cancel(); deadlines.cancelled(cancellation)
        if (state.phase != EditorPhase.WAITING_NATIVE_RELEASE) state = if (result == EditCancelResult.TOO_LATE)
            state.copy(phase = EditorPhase.PUBLISHING, notice = publicationCheckingNotice,
                stopFeedback = EditorStopFeedback.CHECKING_RESULT)
        else state.copy(phase = EditorPhase.CANCELLING, notice = text.get(RuntimeMessageCopy.EDITOR_STOP_REQUESTED),
            stopFeedback = EditorStopFeedback.REQUESTED)
        return result
    }
    private fun featuresAllowed(required: Set<Feature>): Boolean = required.all { feature ->
        featureGate.evaluate(FeatureRequest(feature), entitlements.snapshot(), PlatformCapabilities(required),
            BuildFeaturePolicy(), System.currentTimeMillis()).allowed
    }
    private val entitlementNotice get() = text.get(RuntimeMessageCopy.EDITOR_ENTITLEMENT_REQUIRED)
    private fun enqueue(owner: Any, action: () -> Unit) {
        try { worker.execute(action) }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            activeCancellation?.let(deadlines::completed)
            OrdinaryMediaNativeResources.released(owner); activeCancellation = null; activeOperation = null
            state = state.copy(phase = EditorPhase.FAILED, notice = text.get(RuntimeMessageCopy.EDITOR_QUEUE_FULL))
        }
    }
    private fun releaseGlobal(owner: Any, unknown: Boolean) {
        if (unknown) OrdinaryMediaNativeResources.unconfirmed() else OrdinaryMediaNativeResources.released(owner)
    }
    private fun operation(id: String, cancellation: EditPublicationCancellation) = OperationContext(id,
        System.currentTimeMillis() + EditLimits.EXECUTION_TIMEOUT_MILLIS + EditLimits.MAX_VERIFY_TIMEOUT_MILLIS,
        cancellation, resourceBudget = ResourceBudget(1, EditLimits.MAX_OUTPUT_BYTES))
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
    private val releaseNotice get() = text.get(RuntimeMessageCopy.EDITOR_RELEASE_UNCONFIRMED)
    private val publicationCheckingNotice get() = text.get(RuntimeMessageCopy.EDITOR_PUBLICATION_CHECKING)
}

/** Existing independent capabilities; cancellation and original viewing never call this function. */
internal fun editorFeatures(request: EditRequest, durationUs: Map<out com.easysavevideo.library.EditInputRef, Long>): Set<Feature> = buildSet {
    if (request.segments.any { it.crop != NormalizedCrop.FULL || it.startUs != 0L || it.endUsExclusive != durationUs[it.source] }) add(Feature.VIDEO_CROP)
    if (request.segments.size > 1) add(Feature.VIDEO_MERGE)
    if (request.compression == EditCompression.SAVE_SPACE) add(Feature.VIDEO_COMPRESS)
}
/** Error classification is unchanged; display never reads an exception message or changes its code. */
internal fun errorText(failure: Throwable, text: RuntimeMessageText): String = text.get(editorErrorCopy(failure))

internal fun editorErrorCopy(failure: Throwable): RuntimeMessageCopy = when ((failure as? EditFailure)?.code) {
    EditError.UNSUPPORTED_PROFILE -> RuntimeMessageCopy.EDITOR_ERROR_UNSUPPORTED_PROFILE
    EditError.INCOMPATIBLE_SEGMENTS -> RuntimeMessageCopy.EDITOR_ERROR_INCOMPATIBLE_SEGMENTS
    EditError.STALE_SOURCE, EditError.SOURCE_MISSING -> RuntimeMessageCopy.EDITOR_ERROR_SOURCE_UNAVAILABLE
    EditError.INSUFFICIENT_SPACE -> RuntimeMessageCopy.EDITOR_ERROR_INSUFFICIENT_SPACE
    EditError.CANCELLED -> RuntimeMessageCopy.EDITOR_ERROR_CANCELLED
    EditError.RELEASE_UNCONFIRMED -> RuntimeMessageCopy.EDITOR_ERROR_RELEASE_UNCONFIRMED
    EditError.TIMEOUT -> RuntimeMessageCopy.EDITOR_ERROR_TIMEOUT
    EditError.VERIFICATION_FAILED -> RuntimeMessageCopy.EDITOR_ERROR_VERIFICATION_FAILED
    EditError.SOURCE_LIMIT, EditError.DURATION_LIMIT, EditError.PIXEL_LIMIT -> RuntimeMessageCopy.EDITOR_ERROR_LIMIT
    else -> RuntimeMessageCopy.EDITOR_ERROR_INCOMPLETE
}
