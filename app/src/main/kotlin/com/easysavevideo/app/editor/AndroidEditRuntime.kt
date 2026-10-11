package com.easysavevideo.app.editor

import android.content.Context
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
    private val deadlines = EditorDeadlineGuard(EditorTimerScheduler { delayMillis, action ->
        val callback = Runnable(action); main.postDelayed(callback, delayMillis)
        AutoCloseable { main.removeCallbacks(callback) }
    }, onDeadline = { cancel() }, onUnconfirmedStop = {
        state = state.copy(phase = EditorPhase.WAITING_NATIVE_RELEASE,
            notice = "已请求停止，但后台处理或文件资源仍未确认退出。相关文件保留，不能开始新编辑。可继续等待，或完全退出应用后重新打开进行恢复检查。")
    })
    private var recovered = false
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
                        "上次编辑被中断，原件保留。未验证的新文件不会自动入库；待清理项 ${it.cleanupPending} 个。" else null },
                        { "编辑恢复检查未完成。原件保留，编辑暂不可用。" }))
                val checkpoint = pendingDraftCheckpoint; pendingDraftCheckpoint = null
                if (checkpoint != null) restoreDraftCheckpoint(checkpoint)
                else if (recovered && state.foreground) inspectMissingSources()
            }
        }
    }
    fun open(item: MediaItem, available: List<MediaItem>) {
        requireMain()
        if (state.busy || state.phase == EditorPhase.BLOCKED) return
        draftRestoreGuard.userChanged(); restoredNeedsExplicitAction = false; pendingDraftCheckpoint = null; restoringDraftCheckpoint = null
        val draft = if (state.draft.segments.isEmpty() || state.phase == EditorPhase.SUCCEEDED) addEditorSegment(EditorDraft(), item)
            else if (state.draft.segments.any { it.reference == item.readRef() }) state.draft else addEditorSegment(state.draft, item)
        state = state.copy(draft = draft, displayGeometries = state.displayGeometries.filterKeys { ref -> draft.segments.any { it.reference == ref } }, availableItems = available.filter { it.lifecycle == MediaLifecycle.READY },
            phase = EditorPhase.IDLE, completedReference = null, executionRanges = emptyList(), draftRestoration = EditorDraftRestoration.NONE)
    }
    fun setForeground(value: Boolean) {
        requireMain(); state = state.copy(foreground = value)
        if (!value) { admissionGeneration++; cancel() }
        else if (recovered && !state.busy && state.phase != EditorPhase.BLOCKED) inspectMissingSources()
    }
    fun changeDraft(draft: EditorDraft) {
        requireMain()
        if (!state.editable || activeCancellation != null) return
        draftRestoreGuard.userChanged(); restoredNeedsExplicitAction = false; pendingDraftCheckpoint = null; restoringDraftCheckpoint = null
        state = state.copy(draft = draft, displayGeometries = state.displayGeometries.filterKeys { ref -> draft.segments.any { it.reference == ref } },
            phase = EditorPhase.IDLE, notice = null, completedReference = null, executionRanges = emptyList(), draftRestoration = EditorDraftRestoration.NONE)
        inspectMissingSources()
    }
    /** Metadata inspection is free; no edit capability is exercised until explicit Submit. */
    private fun inspectMissingSources() {
        if (restoredNeedsExplicitAction || !state.foreground || !recovered || state.busy || activeCancellation != null) return
        val refs = state.draft.segments.map { it.reference }.distinct().filter { it !in state.displayGeometries }
        if (refs.isEmpty()) return
        val generation = ++admissionGeneration
        val deadline = android.os.SystemClock.elapsedRealtime() + EditLimits.RELEASE_TIMEOUT_MILLIS
        state = state.copy(phase = EditorPhase.PROBING)
        fun attempt() {
            if (generation != admissionGeneration || !state.foreground || activeCancellation != null) return
            if (!otherMediaIdle() || OrdinaryMediaNativeResources.isOccupied) {
                if (android.os.SystemClock.elapsedRealtime() < deadline && !OrdinaryMediaNativeResources.requiresRestart) main.postDelayed({ attempt() }, 50)
                else state = state.copy(phase = EditorPhase.IDLE, notice = "请等待预览、缩略图或其他媒体任务退出，再调整选择或提交。原件保留。")
                return
            }
            val owner = Any()
            if (!OrdinaryMediaNativeResources.claim(owner)) {
                state = state.copy(phase = EditorPhase.BLOCKED, notice = "媒体资源退出尚未确认，请完全退出应用后重试。")
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
                        notice = if (unknown) releaseNotice else outcome.exceptionOrNull()?.let(::errorText))
                }
            }
        }
        main.post { attempt() }
    }
    fun submit(draft: EditorDraft) {
        requireMain()
        if (!state.editable || state.draft != draft) return
        draftRestoreGuard.userChanged(); restoredNeedsExplicitAction = false; pendingDraftCheckpoint = null; restoringDraftCheckpoint = null
        val generation = admissionGeneration
        destinations.select(isCurrent = { state.foreground && state.editable && state.draft == draft && admissionGeneration == generation },
            accepted = { admission -> submitSelected(draft, admission) })
    }
    private fun submitSelected(draft: EditorDraft, admission: OrdinaryDestinationAdmission) {
        requireMain()
        state = state.copy(submissionRevision = state.submissionRevision + 1)
        if (!recovered || !state.foreground || state.busy || activeCancellation != null || state.phase == EditorPhase.BLOCKED) return
        val request = try { draft.toEditRequest(admission.creationId) }
        catch (_: Exception) { state = state.copy(notice = editorDraftError(draft, state.displayGeometries) ?: "编辑参数无效。"); return }
        if (request.segments.any { it.source !in state.displayGeometries }) {
            state = state.copy(notice = "请先等待来源格式检查完成，再提交编辑。")
            inspectMissingSources(); return
        }
        val required = editorFeatures(request, state.displayGeometries.mapValues { it.value.durationUs })
        if (required.isEmpty()) { state = state.copy(notice = "参数未改变时间、画面或压缩方式，可直接预览原件，无需重复转码。"); return }
        if (!featuresAllowed(required)) { state = state.copy(notice = entitlementNotice); return }
        if (!otherMediaIdle()) { state = state.copy(notice = "请等待现有保存、录制或预览资源实际退出后再提交。"); return }
        val owner = Any()
        if (!OrdinaryMediaNativeResources.claim(owner)) { state = state.copy(notice = "媒体资源忙，请关闭预览并稍后重试。"); return }
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
                        notice = "新视频已独立验证并入库。原件保留；压缩后的大小可能大于原件。")
                    is EditRunResult.Stopped -> state.copy(phase = if (unknown) EditorPhase.BLOCKED else if (result.cancelled) EditorPhase.CANCELLED else EditorPhase.FAILED,
                        notice = if (unknown) releaseNotice else if (gateRejected) entitlementNotice else errorText(EditFailure(result.error)))
                }
            }
        }
    }
    /** Restores editable parameters only. Never calls a reader, probe, destination, task or player. */
    internal fun restoreDraftCheckpoint(encoded: String) {
        requireMain()
        fun ownerPresent() = state.draft.segments.isNotEmpty() || state.busy || activeCancellation != null || state.phase == EditorPhase.BLOCKED
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
    fun cancel() {
        requireMain(); admissionGeneration++
        val cancellation = activeCancellation
        if (cancellation != null) {
            val result = cancellation.cancel(); deadlines.cancelled(cancellation)
            if (state.phase != EditorPhase.WAITING_NATIVE_RELEASE) state = if (result == EditCancelResult.TOO_LATE)
                state.copy(phase = EditorPhase.PUBLISHING, notice = "最终入库提交已开始，不能再确认取消。正在核对提交结果；原件保留。")
            else state.copy(phase = EditorPhase.CANCELLING, notice = "已请求安全停止，等待媒体资源实际退出；原件保留。")
        }
        else if (state.phase == EditorPhase.PROBING) state = state.copy(phase = EditorPhase.IDLE)
    }
    private fun featuresAllowed(required: Set<Feature>): Boolean = required.all { feature ->
        featureGate.evaluate(FeatureRequest(feature), entitlements.snapshot(), PlatformCapabilities(required),
            BuildFeaturePolicy(), System.currentTimeMillis()).allowed
    }
    private val entitlementNotice = "此编辑需要 Video Tools Pro 对应能力。开发版可使用设置中的现有 Fake Entitlement；正式版未接入购买。"
    private fun enqueue(owner: Any, action: () -> Unit) {
        try { worker.execute(action) }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            activeCancellation?.let(deadlines::completed)
            OrdinaryMediaNativeResources.released(owner); activeCancellation = null; activeOperation = null
            state = state.copy(phase = EditorPhase.FAILED, notice = "编辑队列已满，请稍后重试。")
        }
    }
    private fun releaseGlobal(owner: Any, unknown: Boolean) {
        if (unknown) OrdinaryMediaNativeResources.unconfirmed() else OrdinaryMediaNativeResources.released(owner)
    }
    private fun operation(id: String, cancellation: EditPublicationCancellation) = OperationContext(id,
        System.currentTimeMillis() + EditLimits.EXECUTION_TIMEOUT_MILLIS + EditLimits.MAX_VERIFY_TIMEOUT_MILLIS,
        cancellation, resourceBudget = ResourceBudget(1, EditLimits.MAX_OUTPUT_BYTES))
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
    private val releaseNotice = "媒体资源退出或提交状态尚未确认，已停止新编辑且保留相关文件。请完全退出应用后重新打开进行恢复检查。"
}

/** Existing independent capabilities; cancellation and original viewing never call this function. */
internal fun editorFeatures(request: EditRequest, durationUs: Map<out com.easysavevideo.library.EditInputRef, Long>): Set<Feature> = buildSet {
    if (request.segments.any { it.crop != NormalizedCrop.FULL || it.startUs != 0L || it.endUsExclusive != durationUs[it.source] }) add(Feature.VIDEO_CROP)
    if (request.segments.size > 1) add(Feature.VIDEO_MERGE)
    if (request.compression == EditCompression.SAVE_SPACE) add(Feature.VIDEO_COMPRESS)
}
internal fun errorText(failure: Throwable): String = when ((failure as? EditFailure)?.code) {
    EditError.UNSUPPORTED_PROFILE -> "此来源不在 M6-A 支持范围：8 位 SDR、方形像素、整数恒定帧率 AVC，静音或一致的 AAC-LC 音轨。原件保留。"
    EditError.INCOMPATIBLE_SEGMENTS -> "片段的裁后宽高比、帧率或音轨配置不一致，当前无法合并。原件保留。"
    EditError.STALE_SOURCE, EditError.SOURCE_MISSING -> "来源已变化或不可读取，请返回媒体库重新选择。"
    EditError.INSUFFICIENT_SPACE -> "空间不足，未覆盖原件。请释放空间后重试。"
    EditError.CANCELLED -> "编辑已安全停止，原件保留。"
    EditError.RELEASE_UNCONFIRMED -> "媒体资源尚未确认退出，请完全退出应用后重新打开。"
    EditError.TIMEOUT -> "处理超过当前预算，已请求安全停止。原件保留。"
    EditError.VERIFICATION_FAILED -> "新视频未通过独立验证，未作为成功视频入库。原件保留。"
    EditError.SOURCE_LIMIT, EditError.DURATION_LIMIT, EditError.PIXEL_LIMIT -> "来源或输出超出 M6-A 的数量、时长、尺寸或大小限制。原件保留。"
    else -> "此次编辑未完成，请检查来源与可用空间后重试。原件保留。"
}
