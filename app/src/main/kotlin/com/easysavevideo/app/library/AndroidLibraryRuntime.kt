package com.easysavevideo.app.library

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.library.*
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Immutable UI snapshots contain catalog identities, never storage paths or file descriptors. */
data class LibraryViewState(
    val query: LibraryQuery = LibraryQuery(pageSize = 50),
    val folder: LibraryFolder? = null,
    val folders: List<LibraryFolder> = emptyList(),
    val folderPage: Int = 0,
    val items: List<MediaItem> = emptyList(),
    val nextCursor: LibraryCursor? = null,
    val loading: Boolean = false,
    val ready: Boolean = false,
    val foreground: Boolean = false,
    val busy: Boolean = false,
    val selected: LibrarySelection? = null,
    val editor: LibraryNameEditor? = null,
    val move: LibraryMoveSelection? = null,
    val deletion: LibraryDeleteSelection? = null,
    val pendingDeletes: Set<String> = emptySet(),
    val pendingDeleteCount: Int = 0,
    val notice: String? = null,
)

sealed interface LibrarySelection {
    val id: String
    val revision: Long
    val name: String
    data class Media(val item: MediaItem) : LibrarySelection {
        override val id get() = item.itemId
        override val revision get() = item.revision
        override val name get() = item.displayName
    }
    data class Folder(val folder: LibraryFolder) : LibrarySelection {
        override val id get() = folder.folderId
        override val revision get() = folder.revision
        override val name get() = folder.displayName
    }
}

data class LibraryNameEditor(val target: LibrarySelection?, val parentId: String?, val name: String)
data class LibraryMoveSelection(val target: LibrarySelection, val originFolderId: String?)
data class LibraryDeleteSelection(val target: LibrarySelection) {
    /** Identity and exact revision are part of consent, including the representation generation. */
    fun matches(selection: LibrarySelection?): Boolean = target == selection
}

/**
 * One bounded lane owns all repository calls. A new query removes its queued predecessor; an
 * already-running bounded repository read may finish, but only its exact UI generation can publish.
 * Accepted writes are not interrupted during durable mutation. Leaving/disposing clears consent,
 * prevents new work, and closes the lane orderly without closing the injected shared storage owner.
 */
class AndroidLibraryRuntime internal constructor(
    private val repository: LibraryRepository,
    private val ui: LibraryUiDispatcher,
    private val work: LibraryWorkLane,
) : AutoCloseable {
    constructor(repository: LibraryRepository) : this(repository, AndroidLibraryUiDispatcher(), BoundedLibraryWorkLane())

    var state by mutableStateOf(LibraryViewState()); private set
    private var disposed = false
    private var generation = 0L
    private var queuedRead: Runnable? = null
    private var delayedSearch: Runnable? = null
    private var deletePoll: Runnable? = null

    fun setForeground(value: Boolean) {
        ui.requireCurrentThread()
        if (disposed || state.foreground == value) return
        invalidateRead()
        state = state.copy(foreground = value, loading = false, selected = null, editor = null, deletion = null, move = null)
        if (value) refresh() else cancelDeletePoll()
    }

    fun refresh() {
        ui.requireCurrentThread()
        if (!available() || state.busy) return
        load(state.query.copy(cursor = null), retryPending = true)
    }

    fun search(text: String) {
        ui.requireCurrentThread()
        if (!available() || state.busy || state.move != null) return
        invalidateRead()
        val bounded = boundedLibraryText(text, LibraryLimits.MAX_SEARCH_UTF8_BYTES)
        val query = try { state.query.copy(text = bounded, cursor = null) } catch (_: LibraryFailure) {
            state = state.copy(notice = "搜索内容包含不支持的字符。", loading = false); return
        }
        state = state.copy(query = query, selected = null, deletion = null, editor = null, items = emptyList(), nextCursor = null, loading = true)
        val expected = generation
        val task = Runnable {
            delayedSearch = null
            if (!disposed && state.foreground && generation == expected) load(query)
        }
        delayedSearch = task
        ui.later(task, 250)
    }

    fun sort(sort: LibrarySort) {
        ui.requireCurrentThread()
        if (!available() || state.busy || state.move != null) return
        load(state.query.copy(sort = sort, cursor = null))
    }

    fun reverseSort() {
        ui.requireCurrentThread()
        if (!available() || state.busy || state.move != null) return
        val direction = if (state.query.direction == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING
        load(state.query.copy(direction = direction, cursor = null))
    }

    fun nextPage() {
        ui.requireCurrentThread()
        if (!available() || state.busy || state.loading) return
        state.nextCursor?.let { load(state.query.copy(cursor = it)) }
    }

    fun previousPage() {
        ui.requireCurrentThread()
        if (!available() || state.busy || state.loading) return
        val cursor = state.query.cursor ?: return
        val previous = if (cursor.offset <= state.query.pageSize) null else cursor.copy(offset = cursor.offset - state.query.pageSize)
        load(state.query.copy(cursor = previous))
    }

    fun folderPage(page: Int) {
        ui.requireCurrentThread()
        if (!available() || state.busy) return
        state = state.copy(folderPage = page.coerceIn(0, ((state.folders.size - 1).coerceAtLeast(0) / FOLDER_PAGE_SIZE)))
    }

    fun enterFolder(folderId: String?) {
        ui.requireCurrentThread()
        if (!available() || state.busy) return
        load(state.query.copy(folderId = folderId, text = "", cursor = null))
    }

    fun up() { enterFolder(state.folder?.parentId) }

    fun select(item: MediaItem) {
        ui.requireCurrentThread()
        if (!available() || state.busy || state.loading || state.move != null || item.lifecycle != MediaLifecycle.READY) return
        if (state.items.none { it == item }) return
        state = state.copy(selected = LibrarySelection.Media(item), editor = null, deletion = null)
    }

    fun select(folder: LibraryFolder) {
        ui.requireCurrentThread()
        if (!available() || state.busy || state.loading || state.move != null) return
        if (state.folders.none { it == folder }) return
        state = state.copy(selected = LibrarySelection.Folder(folder), editor = null, deletion = null)
    }

    /** Consume only the exact current, foreground selection before handing its reference to a route. */
    fun takeSelectedReadRef(selection: LibrarySelection.Media): MediaReadRef? {
        ui.requireCurrentThread()
        if (!available() || state.busy || state.loading || !state.ready || state.selected != selection ||
            state.items.none { it == selection.item }) return null
        val ref = selection.item.readRef()
        state = state.copy(selected = null, editor = null, deletion = null)
        return ref
    }

    fun dismissSelection() {
        ui.requireCurrentThread()
        state = state.copy(selected = null, editor = null, deletion = null)
    }

    fun createFolder() {
        ui.requireCurrentThread()
        if (!available() || state.busy || state.loading || state.move != null) return
        state = state.copy(selected = null, editor = LibraryNameEditor(null, state.query.folderId, ""), deletion = null)
    }

    fun rename() {
        ui.requireCurrentThread()
        if (!available() || state.busy) return
        val selected = state.selected ?: return
        state = state.copy(editor = LibraryNameEditor(selected, state.query.folderId, selected.name), deletion = null)
    }

    fun editName(name: String) {
        ui.requireCurrentThread()
        if (!available() || state.busy) return
        state.editor?.let { state = state.copy(editor = it.copy(name = boundedLibraryText(name, LibraryLimits.MAX_NAME_UTF8_BYTES))) }
    }

    fun cancelEditor() {
        ui.requireCurrentThread()
        if (state.busy) return
        state = state.copy(editor = null)
    }

    fun submitName() {
        ui.requireCurrentThread()
        if (!available() || state.busy) return
        val editor = state.editor ?: return
        libraryNameError(editor.name)?.let { state = state.copy(notice = it); return }
        val validName = LibraryNames.validate(editor.name)
        val target = editor.target
        if (target != null && target != state.selected) { staleSelection(); return }
        val operationId = UUID.randomUUID().toString()
        mutate("名称已保存。") {
            when (target) {
                null -> repository.createFolder(validName, editor.parentId, operationId)
                is LibrarySelection.Media -> repository.renameItem(target.id, target.revision, validName, operationId)
                is LibrarySelection.Folder -> repository.renameFolder(target.id, target.revision, validName, operationId)
            }
            MutationResult()
        }
    }

    fun startMove() {
        ui.requireCurrentThread()
        if (!available() || state.busy) return
        val selected = state.selected ?: return
        state = state.copy(move = LibraryMoveSelection(selected, state.query.folderId), selected = null, editor = null, deletion = null)
        load(state.query.copy(folderId = null, text = "", cursor = null))
    }

    fun cancelMove() {
        ui.requireCurrentThread()
        if (!available() || state.busy) return
        val move = state.move ?: return
        state = state.copy(move = null)
        enterFolder(move.originFolderId)
    }

    fun confirmMove() {
        ui.requireCurrentThread()
        if (!available() || state.busy || state.loading || !state.ready) return
        val move = state.move ?: return
        val destination = state.query.folderId
        if (move.target is LibrarySelection.Folder && move.target.id == destination) {
            state = state.copy(notice = "不能将文件夹移动到其自身。")
            return
        }
        val operationId = UUID.randomUUID().toString()
        mutate("已移动到所选文件夹。") {
            when (val target = move.target) {
                is LibrarySelection.Media -> repository.moveItem(target.id, target.revision, destination, operationId)
                is LibrarySelection.Folder -> repository.moveFolder(target.id, target.revision, destination, operationId)
            }
            MutationResult()
        }
    }

    fun requestDelete() {
        ui.requireCurrentThread()
        if (!available() || state.busy) return
        val selected = state.selected ?: return
        if (state.pendingDeletes.size >= LibraryLimits.MAX_DELETE_BATCH) {
            state = state.copy(notice = "已有较多删除等待清理，请等待完成后再删除其他项目。")
            return
        }
        if (selected is LibrarySelection.Folder && (selected.folder.itemCount != 0 || selected.folder.childFolderCount != 0)) {
            state = state.copy(notice = "此文件夹仍有 ${selected.folder.itemCount} 个视频、${selected.folder.childFolderCount} 个子文件夹。请先逐项移动内容；本版本只删除空文件夹。")
            return
        }
        state = state.copy(deletion = LibraryDeleteSelection(selected), editor = null)
    }

    fun cancelDelete() {
        ui.requireCurrentThread()
        if (!state.busy) state = state.copy(deletion = null)
    }

    fun confirmDelete(confirmation: LibraryDeleteSelection) {
        ui.requireCurrentThread()
        if (!available() || state.busy) return
        if (state.deletion != confirmation || !confirmation.matches(state.selected)) { staleSelection(); return }
        val operationId = UUID.randomUUID().toString()
        mutate("已删除。") {
            when (val target = confirmation.target) {
                is LibrarySelection.Media -> {
                    val outcome = repository.requestDelete(ConfirmedDeleteRequest(target.item.readRef(), operationId, confirmed = true))
                    MutationResult(pendingDeleteId = target.id.takeIf { outcome == DeleteOutcome.DELETE_PENDING })
                }
                is LibrarySelection.Folder -> {
                    repository.deleteEmptyFolder(target.id, target.revision, operationId)
                    MutationResult()
                }
            }
        }
    }

    fun dismissNotice() { ui.requireCurrentThread(); state = state.copy(notice = null) }
    fun unavailableVault() { ui.requireCurrentThread(); state = state.copy(notice = "Vault 将保持免费。此开发阶段尚未提供加密存储或解锁，当前普通媒体库不具有 Vault 保护。") }
    fun unavailableExport() { ui.requireCurrentThread(); state = state.copy(notice = "导出服务尚未就绪。原视频仍在应用普通媒体库中。") }

    private data class Loaded(val page: LibraryPage, val folder: LibraryFolder?, val folders: List<LibraryFolder>, val pending: Set<String>, val deleteReport: PendingDeleteReport?, val deleteUnknown: Boolean)
    private data class MutationResult(val pendingDeleteId: String? = null)

    private fun load(query: LibraryQuery, retryPending: Boolean = false) {
        invalidateRead()
        if (!available()) return
        val token = generation
        val pending = state.pendingDeletes
        val sameQuery = query == state.query
        val sameFolder = query.folderId == state.query.folderId
        state = state.copy(query = query, loading = true, ready = sameQuery && state.ready, selected = null, editor = null, deletion = null,
            items = if (sameQuery) state.items else emptyList(), nextCursor = if (sameQuery) state.nextCursor else null,
            folders = if (sameFolder) state.folders else emptyList(), folder = if (sameFolder) state.folder else null,
            folderPage = if (sameFolder) state.folderPage else 0)
        val job = Runnable {
            val loaded = try {
                val report = if (retryPending) repository.processPendingDeletes(LibraryLimits.MAX_DELETE_BATCH) else null
                val folder = query.folderId?.let { repository.folder(it) ?: throw LibraryFailure(LibraryError.NOT_FOUND) }
                val folders = repository.folders(query.folderId)
                if (folders.size > LibraryLimits.MAX_FOLDERS) throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
                var deleteUnknown = false
                val stillPending = pending.filterTo(linkedSetOf()) {
                    when (repository.item(it)?.lifecycle) {
                        MediaLifecycle.DELETED -> false
                        MediaLifecycle.DELETE_PENDING -> true
                        else -> { deleteUnknown = true; true }
                    }
                }
                Result.success(Loaded(repository.query(query), folder, folders.toList(), stillPending, report, deleteUnknown))
            } catch (failure: LibraryFailure) { Result.failure(failure) }
            catch (_: Exception) { Result.failure(LibraryFailure(LibraryError.STORAGE_FAILURE)) }
            ui.post {
                if (disposed || !state.foreground || generation != token) return@post
                queuedRead = null
                loaded.fold(onSuccess = { result ->
                    state = state.copy(items = result.page.items.toList(), folders = result.folders,
                        folder = result.folder, nextCursor = result.page.nextCursor, loading = false, ready = true,
                        folderPage = state.folderPage.coerceAtMost((result.folders.size - 1).coerceAtLeast(0) / FOLDER_PAGE_SIZE),
                        pendingDeletes = result.pending,
                        pendingDeleteCount = result.deleteReport?.pending?.coerceAtLeast(result.pending.size) ?: state.pendingDeleteCount.coerceAtLeast(result.pending.size),
                        notice = when {
                            result.deleteUnknown -> "部分已确认删除的项目状态暂不可核实，未确认完成。请刷新重试。"
                            (result.deleteReport?.failed ?: 0) > 0 -> "部分已确认删除仍待清理，尚未完成。可稍后刷新重试。"
                            (pending.isNotEmpty() && result.pending.isEmpty()) ||
                                (state.pendingDeleteCount > 0 && result.deleteReport?.pending == 0 && result.pending.isEmpty()) -> "已确认的删除已完成。"
                            (result.deleteReport?.pending ?: 0) > 0 -> "已确认的删除仍在等待清理，尚未完成。"
                            else -> state.notice
                        })
                    scheduleDeletePoll()
                }, onFailure = { throwable ->
                    val failure = throwable as LibraryFailure
                    state = state.copy(loading = false, ready = false, items = emptyList(), folders = emptyList(), nextCursor = null,
                        notice = libraryErrorText(failure.code))
                    // The obsolete page is never appended or presented as a current snapshot.
                    if (failure.code == LibraryError.STALE_PAGE && query.cursor != null) load(query.copy(cursor = null))
                    else if (failure.code == LibraryError.NOT_FOUND && query.folderId != null) load(query.copy(folderId = null, cursor = null))
                    else scheduleDeletePoll()
                })
            }
        }
        queuedRead = job
        if (!work.submit(job)) {
            queuedRead = null
            state = state.copy(loading = false, ready = false, items = emptyList(), folders = emptyList(), nextCursor = null, notice = libraryErrorText(LibraryError.BUSY))
        }
    }

    private fun mutate(successText: String, operation: () -> MutationResult) {
        invalidateRead()
        cancelDeletePoll()
        state = state.copy(busy = true, loading = false, editor = null, deletion = null, selected = null, notice = "正在处理操作，尚未确认完成。")
        val job = Runnable {
            // Once accepted on the UI thread, a mutation finishes/reconciles its durable intent even
            // after backgrounding. It cannot be duplicated by a second tap or a recreated dialog.
            val result = try { Result.success(operation()) }
            catch (failure: LibraryFailure) { Result.failure(failure) }
            catch (_: Exception) { Result.failure(LibraryFailure(LibraryError.STORAGE_FAILURE)) }
            ui.post {
                if (disposed) return@post
                result.fold(onSuccess = { outcome ->
                    val pending = outcome.pendingDeleteId
                    state = state.copy(busy = false, move = null,
                        pendingDeletes = if (pending == null) state.pendingDeletes else state.pendingDeletes + pending,
                        pendingDeleteCount = if (pending == null) state.pendingDeleteCount else state.pendingDeleteCount + 1,
                        notice = if (pending == null) successText else "已从媒体库移除，文件仍待现有读取退出并完成清理。删除尚未完成。")
                }, onFailure = { throwable ->
                    state = state.copy(busy = false, move = null, notice = libraryErrorText((throwable as LibraryFailure).code))
                })
                if (state.foreground) load(state.query.copy(cursor = null))
            }
        }
        if (!work.submit(job)) state = state.copy(busy = false, notice = libraryErrorText(LibraryError.BUSY))
    }

    private fun scheduleDeletePoll() {
        cancelDeletePoll()
        if (!available() || state.pendingDeleteCount == 0 || state.busy) return
        val poll = Runnable {
            deletePoll = null
            if (available() && !state.busy && state.pendingDeleteCount > 0) {
                if (state.selected != null || state.editor != null || state.deletion != null || state.move != null || state.loading) scheduleDeletePoll()
                else load(state.query, retryPending = true)
            }
        }
        deletePoll = poll
        ui.later(poll, 2_000)
    }

    private fun cancelDeletePoll() { deletePoll?.let(ui::remove); deletePoll = null }
    private fun staleSelection() { state = state.copy(selected = null, editor = null, deletion = null, notice = "所选项目已变化，请重新打开并确认。") }
    private fun available() = !disposed && state.foreground
    private fun invalidateRead() {
        generation++
        queuedRead?.let(work::remove); queuedRead = null
        delayedSearch?.let(ui::remove); delayedSearch = null
    }

    override fun close() {
        ui.requireCurrentThread()
        if (disposed) return
        disposed = true
        invalidateRead(); cancelDeletePoll()
        state = state.copy(foreground = false, loading = false, selected = null, editor = null, deletion = null, move = null)
        work.close()
    }

    companion object { const val FOLDER_PAGE_SIZE = 50 }
}

internal interface LibraryUiDispatcher {
    fun requireCurrentThread()
    fun post(block: () -> Unit)
    fun later(task: Runnable, delayMillis: Long)
    fun remove(task: Runnable)
}
private class AndroidLibraryUiDispatcher : LibraryUiDispatcher {
    private val main = Handler(Looper.getMainLooper())
    override fun requireCurrentThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    override fun post(block: () -> Unit) { main.post(block) }
    override fun later(task: Runnable, delayMillis: Long) { main.postDelayed(task, delayMillis) }
    override fun remove(task: Runnable) { main.removeCallbacks(task) }
}
internal interface LibraryWorkLane : AutoCloseable {
    fun submit(task: Runnable): Boolean
    fun remove(task: Runnable)
}
private class BoundedLibraryWorkLane : LibraryWorkLane {
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "esv-library").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    override fun submit(task: Runnable): Boolean = try { executor.execute(task); true } catch (_: java.util.concurrent.RejectedExecutionException) { false }
    override fun remove(task: Runnable) { executor.remove(task) }
    override fun close() { executor.shutdown() }
}

internal fun boundedLibraryText(value: String, maxUtf8Bytes: Int): String {
    require(maxUtf8Bytes >= 0)
    var end = 0
    var bytes = 0
    while (end < value.length) {
        val codePoint = value.codePointAt(end)
        val nextBytes = when { codePoint <= 0x7f -> 1; codePoint <= 0x7ff -> 2; codePoint <= 0xffff -> 3; else -> 4 }
        if (bytes + nextBytes > maxUtf8Bytes) break
        bytes += nextBytes
        end += Character.charCount(codePoint)
    }
    return value.substring(0, end)
}

internal fun libraryNameError(value: String): String? = try {
    LibraryNames.validate(value); null
} catch (_: LibraryFailure) { "名称不能为空，不能包含路径分隔符、控制字符、无效 Unicode 或 . / ..，且 UTF-8 最多 255 字节。" }

internal fun libraryErrorText(error: LibraryError): String = when (error) {
    LibraryError.INVALID_ID, LibraryError.INVALID_QUERY -> "项目或搜索无效，请刷新后重试。"
    LibraryError.INVALID_NAME -> "名称无效，请检查字符与长度。"
    LibraryError.NOT_FOUND -> "项目已不存在或不再可用，已保留其他媒体。"
    LibraryError.STALE_REVISION, LibraryError.STALE_PAGE -> "媒体库已变化，请查看刷新后的内容并重新操作。"
    LibraryError.ALREADY_EXISTS -> "此位置已有同名项目，请换一个名称。"
    LibraryError.FOLDER_NOT_EMPTY -> "文件夹仍有内容。请先移动其中的视频和子文件夹；不会递归删除。"
    LibraryError.FOLDER_CYCLE -> "不能移入自身或自己的子文件夹。"
    LibraryError.DEPTH_LIMIT -> "超过支持的文件夹深度，请选择较上层的位置。"
    LibraryError.FOLDER_LIMIT -> "已达到本版本的文件夹数量上限。"
    LibraryError.CONFIRMATION_REQUIRED -> "请重新核对当前项目并确认删除。"
    LibraryError.BUSY -> "媒体正在被读取或操作队列已满，请关闭预览并稍后重试。"
    LibraryError.STORAGE_FAILURE -> "本地存储暂不可用，操作未确认完成。可刷新核对。"
    LibraryError.RESOURCE_RELEASE_UNCONFIRMED -> "现有媒体读取尚未确认退出，文件保留等待清理。"
    LibraryError.IDEMPOTENCY_CONFLICT -> "此操作与已有记录不一致，请刷新核对。"
    LibraryError.RECOVERY_REQUIRED -> "媒体库需要恢复检查；文件已保留，不会假报完成。"
}
