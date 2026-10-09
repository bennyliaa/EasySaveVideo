package com.easysavevideo.app.library.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.app.library.share.LibraryShareOwner
import com.easysavevideo.app.library.share.SharePolicy
import com.easysavevideo.app.library.share.ShareFailure
import com.easysavevideo.app.library.share.ShareError
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class UnverifiedExportView(
    val operationId: String,
    val source: MediaReadRef,
    val label: String,
    val canAttemptCleanup: Boolean,
    val status: String,
) {
    override fun toString() = "UnverifiedExportView([redacted])"
}

/** UI snapshots expose no URI, private locator, stream or provider capability. */
data class ExportViewState(
    val busy: Boolean = false,
    val canCancel: Boolean = false,
    val notice: String? = null,
    val recoverable: Long = 0,
    val canCleanupUnverified: Boolean = false,
    val unverifiedOperationId: String? = null,
    val unverified: List<UnverifiedExportView> = emptyList(),
    val unverifiedCount: Long = 0,
    val unverifiedPage: Long = 0,
    val unverifiedPages: Long = 0,
)

/** One app-scoped runtime; never reconstruct picker/grant authority from Activity saved state. */
class AndroidExportRuntime(context: Context, private val storage: AndroidSaveStorage) : AutoCloseable {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val journal = storage.exportJournal
    @Volatile private var destinationStateObserver: (() -> Unit)? = null
    private val port = AndroidExportPort(application, storage, journal) { destinationStateObserver?.invoke() }
    private val coordinator = ExportCoordinator(journal, port)
    private val worker = lane("esv-library-export", 2)
    private val controls = lane("esv-export-control", 4)
    @Volatile private var cancelled = AtomicBoolean(false)
    @Volatile private var sharing = false
    @Volatile private var operationId: String? = null
    @Volatile private var closed = false
    private var pendingSaf: ExportRequest? = null
    private var pendingPickerResult = false
    var state by mutableStateOf(ExportViewState()); private set
    init {
        worker.execute {
            // Previous-process recipients held virtual proxy descriptors; reclaim private copies now.
            try { LibraryShareOwner.get(application) } catch (_: Exception) { /* Sharing stays unavailable; ordinary exports may proceed. */ }
            try {
                val found = coordinator.recoverPending()
                found.forEach(port::releaseFinishedGrant)
                refreshReviews()
                main.post { if (!closed) state = state.copy(notice = if (found.any { !it.phase.terminal }) "有导出结果需要核对。本次操作不会删除媒体库原件，可重试恢复检查；文件选择器中的遗留文件可能需要手动清理。" else null) }
            } catch (_: Exception) { main.post { if (!closed) state = state.copy(notice = "导出恢复检查未完成；本次操作不会删除媒体库原件。") } }
        }
    }
    internal fun observeDestinationState(observer: () -> Unit) {
        check(destinationStateObserver == null)
        destinationStateObserver = observer
        observer() // Also covers startup invalidation before this observer was attached.
    }
    /** Stable ordinary-creation route. Only the durable destination owner calls this off-main.
     * Existing non-PREPARED records are observed, never replaced or blindly copied again. */
    internal fun copyForCreation(request: ExportRequest): ExportRecord {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(request.destination.kind == ExportKind.MEDIA_STORE ||
            (request.destination.kind == ExportKind.SAF && storage.destinations.folderGrantForOperation(request.operationId) != null))
        val found = journal.get(request.operationId)
        if (found != null) {
            require(found.request == request)
            if (found.phase != ExportPhase.PREPARED) return found
        }
        val result = coordinator.run(request)
        port.releaseFinishedGrant(result)
        refreshReviews()
        return result
    }
    /** Cancels the same admitted extra copy, including before the router has created its row. */
    internal fun cancelForCreation(request: ExportRequest): ExportCancellation {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(request.destination.kind == ExportKind.MEDIA_STORE ||
            (request.destination.kind == ExportKind.SAF && storage.destinations.folderGrantForOperation(request.operationId) != null))
        val record = journal.create(request)
        require(record.request == request)
        val result = coordinator.cancel(request.operationId)
        if (result == ExportCancellation.ACCEPTED) {
            try { worker.execute {
                try {
                    val recovered = coordinator.recover(request.operationId)
                    port.releaseFinishedGrant(recovered); refreshReviews()
                } catch (_: Exception) { main.post { if (!closed) state = state.copy(notice = "本次额外副本已请求取消，清理仍需恢复核对。媒体库原件保留。") } }
            } } catch (_: java.util.concurrent.RejectedExecutionException) {
                main.post { if (!closed) state = state.copy(notice = "取消已记录，清理队列暂忙，请稍后恢复核对。媒体库原件保留。") }
            }
        }
        return result
    }
    fun exportToMediaStore(ref: MediaReadRef) {
        requireMain(); if (!admit()) return
        val request = request(ref, ExportKind.MEDIA_STORE); operationId = request.operationId
        val cancelled = this.cancelled
        submit {
            journal.create(request)
            if (cancelled.get()) coordinator.cancel(request.operationId)
            val result = coordinator.run(request)
            finish(result)
        }
    }
    fun createDocument(ref: MediaReadRef, onLaunch: (Intent) -> Unit) {
        requireMain(); if (!admit()) return
        val request = request(ref, ExportKind.SAF); operationId = request.operationId
        val cancelled = this.cancelled
        pendingSaf = request
        submit {
            journal.create(request); journal.reserve(request)
            if (cancelled.get()) { cancelPicker(request); return@submit }
            // The system picker can create a document before returning its URI. Persist that
            // possible side effect before launch, so process-death recovery never infers absence.
            journal.get(request.operationId)?.let { journal.put(it.copy(phase = ExportPhase.CREATE_INTENT)) }
            main.post {
                if (closed || pendingSaf != request || cancelled.get()) {
                    cancelPrepared(request); return@post
                }
                pendingPickerResult = true
                state = state.copy(notice = "请选择新建文档的位置。外部文件提供方可能无法完整校验或清理失败的副本。")
                try {
                    onLaunch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "video/mp4"
                        putExtra(Intent.EXTRA_TITLE, "EasySaveVideo.mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    })
                } catch (_: Exception) { pendingPickerResult = false; pendingSaf = null; cancelPrepared(request) }
            }
        }
    }
    fun onCreateDocumentResult(resultCode: Int, data: Intent?) {
        requireMain()
        val request = pendingSaf
        if (request == null || !pendingPickerResult) {
            // A provider may already have created a document before a lost/recreated callback.
            state = state.copy(notice = "无法关联此文件选择结果，未写入视频。所选位置可能已有空文件，请在系统文件应用中核对。")
            return
        }
        pendingSaf = null; pendingPickerResult = false
        val uri = data?.data
        val cancelled = this.cancelled
        submit {
            if (resultCode != Activity.RESULT_OK || uri == null) {
                cancelPicker(request); return@submit
            }
            try {
                port.acceptSafResult(request, uri, data?.flags ?: 0)
            } catch (_: Exception) {
                journal.get(request.operationId)?.let { journal.put(it.copy(phase = ExportPhase.RECONCILIATION_REQUIRED, error = ExportError.RECOVERY_REQUIRED)) }
                refreshReviews()
                main.post { if (!closed) state = state.copy(busy = false, canCancel = false,
                    notice = "未获得可用的新建文档授权，未写入视频。外部位置可能已有空文件，请手动核对；本次操作不会删除媒体库原件。") }
                return@submit
            }
            if (cancelled.get()) coordinator.cancel(request.operationId)
            finish(coordinator.run(request))
        }
    }
    fun share(ref: MediaReadRef, onChooser: (Intent) -> Unit) {
        requireMain(); if (!admit()) return
        val shareId = UUID.randomUUID().toString()
        operationId = shareId; sharing = true
        val cancelled = this.cancelled
        submit {
            val owner = LibraryShareOwner.get(application)
            val intent = owner.prepare(storage, ref, shareId, cancelled::get)
            main.post {
                if (closed || cancelled.get()) {
                    submitControl { owner.revokeOperation(shareId) }
                    if (!closed) state = state.copy(busy = false, canCancel = false, notice = "分享已取消，临时副本会在读取实际退出后清理。")
                    return@post
                }
                try {
                    onChooser(intent)
                    state = state.copy(busy = false, canCancel = false,
                        notice = "已打开系统分享选择器。读取授权最长 10 分钟，最多 ${SharePolicy.MAX_OPENS} 次打开；接收方可以保留已收到的副本。")
                } catch (_: Exception) {
                    submitControl { owner.revokeOperation(shareId) }
                    state = state.copy(busy = false, canCancel = false, notice = "无法打开分享选择器，已撤销新的读取授权；等待实际读取退出后清理临时副本。")
                }
            }
        }
    }
    fun cancel() {
        requireMain(); if (closed || !state.busy) return
        cancelled.set(true)
        state = state.copy(canCancel = false, notice = if (pendingPickerResult) "取消已请求。请关闭文件选择器；如已创建文档，会尝试清理并报告结果。" else "取消已请求；等待当前读写实际退出并核对外部副本。")
        val id = operationId
        val share = sharing
        submitControl {
            if (id != null) {
                if (share) LibraryShareOwner.get(application).revokeOperation(id) else coordinator.cancel(id)
            }
        }
    }
    fun retry() {
        requireMain(); if (!admit()) return
        state = state.copy(canCancel = false)
        submit {
            val results = coordinator.recoverPending()
            results.forEach(port::releaseFinishedGrant)
            refreshReviews()
            main.post { if (!closed) state = state.copy(busy = false, canCancel = false,
                notice = if (results.any { !it.phase.terminal }) "仍有外部副本或授权需要核对。未创建重复副本，本次操作不会删除媒体库原件；文件提供方无法确认的结果需手动检查。" else "本批自动恢复检查已结束；仍需核对的文件副本请查看列表。") }
        }
    }
    fun unverifiedPage(page: Long) {
        requireMain(); if (closed || state.busy || page < 0) return
        if (!admit()) return
        state = state.copy(canCancel = false)
        submit {
            refreshReviews(page)
            main.post { if (!closed) state = state.copy(busy = false, canCancel = false) }
        }
    }
    /** Exact-operation cleanup consent; changing pages/new exports never substitutes another row. */
    fun cleanupUnverified(expectedOperationId: String) {
        requireMain()
        val selected = state.unverified.singleOrNull { it.operationId == expectedOperationId }
        if (selected == null || !selected.canAttemptCleanup) {
            state = state.copy(notice = "此副本不能自动清理，请核对对应文件提供方中的文档；不会恢复旧授权或删除其他副本。")
            return
        }
        if (!admit()) return
        operationId = expectedOperationId
        submit {
            val record = journal.get(expectedOperationId) ?: throw ExportFailure(ExportError.INVALID_STATE)
            require(record.request.source == selected.source && record.verification == ExportVerification.READBACK_UNSUPPORTED)
            val result = if (record.phase == ExportPhase.COPIED_UNVERIFIED || record.phase == ExportPhase.CLEANUP_PENDING)
                coordinator.cleanupUnverified(expectedOperationId)
            else { coordinator.cancel(expectedOperationId); coordinator.recover(expectedOperationId) }
            finish(result)
        }
    }
    fun revokeShares() {
        requireMain(); if (closed) return
        submitControl {
            val removed = LibraryShareOwner.get(application).revokeAll()
            main.post { if (!closed) state = state.copy(notice = if (removed) "已撤销后续分享读取并清理临时副本。接收方已取得的数据无法收回。" else "已请求撤销后续读取；临时副本在实际读写退出后清理，仍有清理待确认。接收方已取得的数据无法收回。") }
        }
    }
    fun clearNotice() { requireMain(); state = state.copy(notice = null) }
    override fun close() {
        requireMain(); if (closed) return
        if (state.busy) cancel()
        closed = true
        submitControl { LibraryShareOwner.get(application).revokeAll() }
        worker.shutdown(); controls.shutdown()
    }
    private fun admit(): Boolean {
        if (closed || state.busy) return false
        cancelled = AtomicBoolean(false)
        sharing = false
        state = state.copy(busy = true, canCancel = true, notice = null)
        return true
    }
    private fun request(ref: MediaReadRef, kind: ExportKind) = ExportRequest(UUID.randomUUID().toString(), ref, ExportDestination(kind, UUID.randomUUID().toString()))
    private fun cancelPrepared(request: ExportRequest) {
        pendingSaf = null
        submit { cancelPicker(request) }
    }
    private fun cancelPicker(request: ExportRequest) {
        coordinator.cancel(request.operationId)
        val record = journal.get(request.operationId) ?: return
        // No returned URI means no authority to clean an external document. Do not invent it.
        val result = record.copy(phase = ExportPhase.CANCELLED, error = ExportError.CANCELLED)
        journal.put(result); finish(result)
    }
    private fun finish(result: ExportRecord) {
        port.releaseFinishedGrant(result)
        refreshReviews()
        main.post { if (!closed) state = state.copy(busy = false, canCancel = false, notice = exportNotice(result)) }
    }
    /** Called only on bounded worker lane; never opens the source or reconstructs a SAF grant. */
    private fun refreshReviews(page: Long = 0) {
        val reviewed = journal.listUnverified(page)
        val counts = journal.counts()
        val views = reviewed.records.map { entry ->
            val record = entry.record
            val source = record.request.source
            val item = try { storage.library.item(source.itemId) } catch (_: Exception) { null }
            val label = if (item != null && item.lifecycle == com.easysavevideo.library.MediaLifecycle.READY)
                item.displayName else "媒体库项目 ${source.itemId.take(8)}"
            val supported = record.verification == ExportVerification.READBACK_UNSUPPORTED && port.hasLiveSafGrant(record.operationId)
            val destination = port.destinationLabel(record.operationId)
            UnverifiedExportView(record.operationId, source, "$label → $destination · 导出记录 #${entry.order}", supported,
                if (!port.hasLiveSafGrant(record.operationId)) "授权已不可用，请在文件提供方中手动核对。"
                else if (record.verification == ExportVerification.READBACK_UNSUPPORTED) "副本未完整校验，可明确选择清理。"
                else "导出或清理结果待核对，请重试恢复检查。")
        }
        main.post { if (!closed) state = state.copy(unverified = views, unverifiedCount = reviewed.total, recoverable = counts.unfinished,
            unverifiedPage = reviewed.page, unverifiedPages = reviewed.total / AndroidExportJournal.REVIEW_PAGE_SIZE + if (reviewed.total % AndroidExportJournal.REVIEW_PAGE_SIZE == 0L) 0 else 1,
            canCleanupUnverified = views.any { it.canAttemptCleanup }, unverifiedOperationId = views.firstOrNull { it.canAttemptCleanup }?.operationId) }
    }
    private fun submit(block: () -> Unit) {
        try { worker.execute {
            try { block() } catch (failure: Exception) {
                val counts = try { journal.counts() } catch (_: Exception) { null }
                try { refreshReviews() } catch (_: Exception) { }
                val notice = if (failure is ShareFailure) shareFailureNotice(failure.code)
                else if (failure is ExportFailure && failure.code == ExportError.CAPACITY) {
                    "已有 ${ExportLimits.MAX_ACTIVE} 个导出操作未完成，请先恢复检查并处理待确认的副本。本次未创建新副本，已完成的历史记录不占用此上限。"
                } else "导出或分享未确认完成。本次操作不会删除媒体库原件；可重试恢复检查，不会重建同一操作的副本。"
                main.post { if (!closed) {
                    pendingSaf = null; pendingPickerResult = false
                    state = state.copy(busy = false, canCancel = false, recoverable = counts?.unfinished ?: maxOf(1L, state.recoverable), notice = notice)
                } }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            state = state.copy(busy = false, canCancel = false, notice = "导出队列已满，请稍后重试。")
        }
    }
    private fun submitControl(block: () -> Unit) {
        try { controls.execute { try { block() } catch (_: Exception) { main.post { if (!closed) state = state.copy(notice = "取消或清理尚未确认，请稍后重试恢复检查。") } } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { if (!closed) state = state.copy(notice = "控制队列暂忙，请稍后重试。") }
    }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private fun lane(name: String, capacity: Int) = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(capacity),
        { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
}

internal fun exportNotice(record: ExportRecord): String = when (record.phase) {
    ExportPhase.SUCCEEDED -> if (record.request.destination.kind == ExportKind.MEDIA_STORE) "已校验并导出到系统视频库。本次操作不会删除媒体库原件。" else "已完整回读校验并导出到所选文档。外部提供方负责后续存储；本次操作不会删除媒体库原件。"
    ExportPhase.COPIED_UNVERIFIED -> "已写入所选文档，但提供方不支持完整回读校验，不能确认副本完整性。请手动检查；本次操作不会删除媒体库原件。"
    ExportPhase.CANCELLED -> if (record.request.destination.kind == ExportKind.SAF && record.output == null)
        "文件选择已取消，未写入视频。文件提供方可能留下空文档，请在所选位置核对；本次操作不会删除媒体库原件。"
        else "导出已取消。已确认可清理的外部副本已移除，本次操作不会删除媒体库原件。"
    ExportPhase.FAILED -> "导出失败，本次操作不会删除媒体库原件。已确认属于本操作的待发布副本已清理。"
    ExportPhase.CLEANUP_PENDING -> "导出未完成，外部副本清理或读写释放仍待确认。本次操作不会删除媒体库原件，可重试恢复检查。"
    else -> "导出结果需要恢复核对，可能已有完整或部分外部副本。不会自动新建重复副本，本次操作不会删除媒体库原件。"
}

internal fun shareFailureNotice(error: ShareError): String = when (error) {
    ShareError.SOURCE_TOO_LARGE -> "该视频超过临时分享副本的 256 MiB 上限。可改用导出到系统视频库或文件。"
    ShareError.COPY_CAPACITY -> "临时分享副本空间已满（最多 ${SharePolicy.MAX_COPIES} 份、共 512 MiB）。请撤销已有分享，或等待接收方实际关闭文件后再试；本次未打开分享选择器。"
    ShareError.PROCESS_CAPACITY -> "本次应用会话的分享读取次数已达到安全上限。请先关闭接收方正在读取的文件，再重新启动应用；已有媒体不会因此被删除。"
    ShareError.CLEANUP_PENDING -> "之前的临时分享副本尚未确认清理，请先尝试撤销分享。若仍失败，请重新启动应用后核对；不会扩大存储上限。"
    ShareError.RELEASE_UNCONFIRMED -> "分享文件读取尚未确认退出，临时副本仍被保留。请关闭接收方读取后重试撤销；若仍无法释放，需要重新启动应用。"
    ShareError.CANCELLED -> "分享已取消。临时副本会在实际读取退出后清理，本次操作不会删除媒体库原件。"
    ShareError.IO -> "分享副本未确认完成。可撤销临时分享后重试；本次操作不会删除媒体库原件。"
}
