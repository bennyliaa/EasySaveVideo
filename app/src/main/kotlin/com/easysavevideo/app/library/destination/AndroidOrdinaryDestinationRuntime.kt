package com.easysavevideo.app.library.destination

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.app.library.export.AndroidExportRuntime
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.library.destination.*
import com.easysavevideo.library.export.ExportPhase
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Ordinary media only. The application owns admission and routes durable publication outbox rows.
 * UI observes this owner; no composition callback creates or discovers a copy operation. */
class AndroidOrdinaryDestinationRuntime internal constructor(
    context: Context,
    private val storage: AndroidSaveStorage,
    private val exports: AndroidExportRuntime?,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(8),
        { task -> Thread(task, "esv-ordinary-destination").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    // A stalled copy/provider must never hold preference writes or fresh creation admission.
    private val copyWorker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { task -> Thread(task, "esv-destination-copy").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    // Picker adoption can call a slow document provider. Cancelling its ticket frees admission,
    // but never spawns a replacement provider worker or pretends that the blocked call ended.
    private val folderWorker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { task -> Thread(task, "esv-folder-grant").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val gate = OrdinaryDestinationSelection()
    private val settingsOrder = FolderSettingsOrder()
    private val folderGate = FolderSelectionGate()
    private val folders = AndroidFolderGrantRegistry(context, storage, ::refreshSettings)
    private var folderHost: AndroidFolderPickerHost? = null
    private var folderTicket: FolderSelectionGate.Ticket? = null
    private var folderGrantId: String? = null
    var hasRememberedFolder by mutableStateOf(false); private set
    var choosingFolder by mutableStateOf(false); private set
    private val refreshing = AtomicBoolean(false)
    private val settingsRefreshing = AtomicBoolean(false)
    private val settingsRequested = AtomicLong(0)
    private val settingsObserved = AtomicLong(0)
    private var pending: Pending? = null
    private class Pending(val ticket: OrdinaryDestinationSelection.Ticket, val current: () -> Boolean,
        val accepted: (OrdinaryDestinationAdmission) -> Unit)
    var ready by mutableStateOf(false); private set
    var preference by mutableStateOf(OrdinaryDestinationPreference.APP_LIBRARY); private set
    var savingPreference by mutableStateOf(false); private set
    var asking by mutableStateOf(false); private set
    var admitting by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var statuses by mutableStateOf<List<OrdinaryDestinationStatus>>(emptyList()); private set
    init {
        exports?.observeDestinationState(::refreshSettings)
        enqueue {
            val saved = storage.destinations.selection()
            main.post { applySelection(saved); ready = true }
            refresh()
        }
    }
    /** Called for a fresh explicit creation, with a source/version fence captured by that caller. */
    fun select(isCurrent: () -> Boolean, accepted: (OrdinaryDestinationAdmission) -> Unit) {
        requireMain()
        if (!ready || savingPreference || choosingFolder || !isCurrent()) { notice = "保存位置或来源尚未就绪，请重新选择。"; return }
        val ticket = gate.begin(preference, folderGrantId) ?: return
        pending = Pending(ticket, isCurrent, accepted)
        notice = null
        val automatic = automaticDestination(ticket.preference)
        if (automatic == null) asking = true else choose(automatic)
    }
    fun choose(choice: OrdinaryDestinationChoice) {
        requireMain()
        val selection = pending ?: return
        if (!selection.current()) { cancelSelection(); notice = "来源或页面已变化，请重新选择保存位置。"; return }
        val selectedFolder = if (choice == OrdinaryDestinationChoice.FOLDER_COPY) selection.ticket.folderGrantId else null
        if (choice == OrdinaryDestinationChoice.FOLDER_COPY && selectedFolder == null) {
            notice = "尚未记住可用文件夹，请先在普通媒体保存位置设置中明确选择文件夹。"; return
        }
        if (!gate.choose(selection.ticket)) return
        asking = false; admitting = true
        val id = UUID.randomUUID().toString()
        enqueue(onFailure = {
            if (gate.cancel(selection.ticket)) { pending = null; asking = false; admitting = false; true } else false
        }) {
            val admitted = storage.destinations.admit(id, choice, selectedFolder)
            main.post {
                if (!gate.active(selection.ticket)) return@post
                if (!selection.current()) { cancelSelection(); notice = "来源或页面已变化，尚未创建媒体；请重新选择。"; return@post }
                if (!gate.finish(selection.ticket)) return@post
                pending = null; admitting = false
                // The ticket contains the durable choice and stable copy identity. Later settings
                // changes cannot affect this job. Callback is consumed before entering creation.
                selection.accepted(admitted)
            }
        }
    }
    fun cancelSelection() {
        requireMain(); gate.cancel(); pending = null; asking = false; admitting = false
    }
    fun updatePreference(value: OrdinaryDestinationPreference) {
        requireMain()
        if (!ready || savingPreference || choosingFolder) return
        if (value == OrdinaryDestinationPreference.FOLDER_COPY && folderGrantId == null) {
            requestFolder(); return
        }
        savingPreference = true
        enqueue(onFailure = { savingPreference = false; true }) {
            storage.destinations.setPreference(value)
            val saved = storage.destinations.selection()
            main.post { applySelection(saved); savingPreference = false; notice = null }
        }
    }
    internal fun attachFolderPicker(host: AndroidFolderPickerHost) {
        requireMain(); if (folderHost === host) return
        folderHost?.let(::detachFolderPicker)
        folderHost = host
    }
    internal fun detachFolderPicker(host: AndroidFolderPickerHost) {
        requireMain()
        if (folderHost !== host) return
        folderHost = null
        if (folderGate.detach(host)) {
            folderTicket = null; choosingFolder = false
            notice = "文件夹选择页面已变化，本次结果不会设为保存位置。请重新选择；系统授权可在系统设置中管理。"
        }
    }
    /** This explicit settings action alone launches the system tree-consent flow. */
    fun requestFolder() {
        requireMain()
        if (!ready || savingPreference || choosingFolder || admitting || asking) return
        val host = folderHost ?: run { notice = "文件夹选择器尚未就绪，请稍后重试。"; return }
        val ticket = folderGate.begin(host) ?: return
        folderTicket = ticket; choosingFolder = true
        notice = null
        try { host.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }) { resultCode, data -> receiveFolder(ticket, resultCode, data) } }
        catch (failure: Exception) {
            if (folderGate.cancel(ticket)) {
                folderTicket = null; choosingFolder = false
                notice = when ((failure as? FolderPickerFailure)?.code) {
                    FolderPickerError.EXHAUSTED -> "本机文件夹选择关联次数已达安全上限，不能通过重试恢复。已有任务保留；可继续使用 App媒体库、相册或媒体库中的单次另存。"
                    FolderPickerError.PENDING -> "上一次系统文件夹选择尚未返回。请先关闭或完成那个选择器；本次没有打开新的选择器，App媒体库仍可使用。"
                    FolderPickerError.INVALID_STATE -> "文件夹选择关联记录不可用，未重置或重复使用旧关联。可继续使用 App媒体库、相册或媒体库中的单次另存。"
                    FolderPickerError.STORAGE -> "本次文件夹选择关联未能安全保存，尚未打开系统选择器。请检查本机存储后重试。"
                    null -> "系统文件夹选择器未能打开，请稍后重试。"
                }
            }
        }
    }
    private fun receiveFolder(ticket: FolderSelectionGate.Ticket, resultCode: Int, data: Intent?) {
        requireMain()
        if (!folderGate.receive(ticket)) return
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            if (folderGate.cancel(ticket)) { folderTicket = null; choosingFolder = false; notice = "已取消文件夹选择，原保存位置未改变。" }
            return
        }
        enqueueFolder(onFailure = {
            if (folderTicket === ticket) {
                folderGate.cancel(ticket); folderTicket = null; choosingFolder = false
                notice = "文件夹选择结果未能确认，保存位置需要重新核对。系统可能已记住授权，可在系统设置中管理；原件不会被删除。"
                enqueue { val saved = storage.destinations.selection(); main.post { applySelection(saved) } }
            }
            false
        }) {
            if (!folderGate.active(ticket)) return@enqueueFolder
            val id = try { folders.accept(uri, data.flags) { folderGate.active(ticket) } }
            catch (failure: Exception) {
                val saved = try { storage.destinations.selection() } catch (_: Exception) { null }
                main.post {
                    saved?.let(::applySelection)
                    if (folderGate.cancel(ticket)) {
                        folderTicket = null; choosingFolder = false
                        notice = if (failure is FolderGrantCapacityFailure)
                            "文件夹授权记录已达到本机安全上限，暂不能记住新文件夹；已有任务和记录保留。系统撤销授权不会清除此记录，可继续使用 App媒体库、相册或媒体库中的单次另存。"
                            else "未能采用本次文件夹选择。系统可能已记住授权，可在系统设置中核对；检测到失效的旧授权不会随再次选择而复活，原件保留。"
                    }
                }
                return@enqueueFolder
            }
            val committed = folderGate.commit(ticket) { storage.destinations.setFolderPreference(id) }
            val saved = storage.destinations.selection()
            main.post {
                applySelection(saved)
                if (committed && folderTicket === ticket) {
                    folderTicket = null; choosingFolder = false
                    notice = if (preference == OrdinaryDestinationPreference.FOLDER_COPY && folderGrantId == id)
                        "之后新建媒体会保留 App媒体库原件，并向所选文件夹另存一份。该文件提供方可能同步到云端；副本在校验前就可能可见。"
                        else "文件夹选择后授权或设置已有变化，后续新建媒体将使用当前显示的保存位置；原件保留。"
                }
            }
        }
    }
    fun cancelFolderSelection() {
        requireMain()
        val ticket = folderTicket ?: return
        if (folderGate.cancel(ticket)) { folderTicket = null; choosingFolder = false; notice = "已取消更改保存位置，迟到结果不会被采用。系统授权可在系统设置中管理。" }
    }
    fun forgetFolder() {
        requireMain()
        if (!ready || savingPreference || choosingFolder) return
        savingPreference = true
        enqueue(onFailure = { savingPreference = false; true }) {
            storage.folderGrants.forgetCurrent()
            val saved = storage.destinations.selection()
            main.post {
                applySelection(saved); savingPreference = false
                notice = "已停止记住此文件夹，后续默认保存到 App媒体库。已接受任务和未确认副本仍使用原选择；为保留它们的处理或清理能力，未撤销系统授权。可在系统设置手动管理，撤销后可能需要自行清理遗留副本。"
            }
        }
    }
    fun clearNotice() { requireMain(); notice = null }
    /** Every registry invalidation signals this independently of provider recovery completion. */
    private fun refreshSettings() {
        settingsRequested.incrementAndGet()
        startSettingsRefresh()
    }
    private fun startSettingsRefresh() {
        if (!settingsRefreshing.compareAndSet(false, true)) return
        try { worker.execute {
            var observed = settingsRequested.get()
            try {
                do {
                    observed = settingsRequested.get()
                    val saved = storage.destinations.selection()
                    main.post {
                        val lost = hasRememberedFolder && saved.folderGrantId == null
                        if (applySelection(saved) && lost && !savingPreference && !choosingFolder)
                            notice = "文件夹授权已失效，后续默认保存到 App媒体库。请明确重新选择文件夹；旧操作不会自动重建副本，原件保留。"
                    }
                } while (settingsRequested.get() != observed)
            } catch (_: Exception) { /* Normal admission/settings actions surface storage unavailability. */ }
            finally {
                settingsObserved.set(observed)
                settingsRefreshing.set(false)
                // A signal arriving between the final read and release is not lost.
                if (settingsRequested.get() != observed) startSettingsRefresh()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { settingsRefreshing.set(false) }
    }
    /** Safe to signal from any publication callback. Reads only bounded admitted rows, never history. */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        try { copyWorker.execute {
            try { refreshOwned() }
            catch (_: Exception) { main.post { notice = "额外副本状态暂不可读取，App媒体库原件保留。可稍后恢复核对。" } }
            finally { refreshing.set(false) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { refreshing.set(false) }
    }
    fun cancelCopy(creationId: String) {
        requireMain()
        enqueue {
            val admitted = storage.destinations.get(creationId) ?: return@enqueue
            val request = admitted.exportRequest() ?: return@enqueue
            val result = exports?.cancelForCreation(request)
            main.post { notice = if (result == com.easysavevideo.library.export.ExportCancellation.ACCEPTED)
                "已请求取消本次额外副本。正在等待读写退出并核对清理；App媒体库原件保留。"
                else "本次副本已完成、已进入最终提交或暂不可取消，请恢复核对。App媒体库原件保留。" }
            refresh()
        }
    }
    fun reconcile() { requireMain(); exports?.retry(); refresh() }
    private fun refreshOwned() {
        publishStatuses()
        if (exports != null) {
            val router = OrdinaryDestinationRouter(storage.destinations, object : OrdinaryDestinationCopyPort {
                override fun existing(operationId: String) = storage.exportJournal.get(operationId)
                override fun run(request: com.easysavevideo.library.export.ExportRequest) = exports.copyForCreation(request)
            })
            try { router.route(16) } catch (_: Exception) {
                // Keep the durable request for a later pass; never replace its identity.
            }
        }
        publishStatuses()
    }
    private fun publishStatuses() {
        val saved = storage.destinations.selection()
        val next = storage.destinations.recent(32).map { admitted ->
            val phase = try {
                val record = admitted.exportRequest()?.let { storage.exportJournal.get(it.operationId) }
                admitted.copyStatus(record) // Reject a conflicting journal identity before displaying success.
                record?.phase
            } catch (_: Exception) { ExportPhase.RECONCILIATION_REQUIRED }
            OrdinaryDestinationStatus(admitted.creationId, admitted.source != null, admitted.choice, phase)
        }
        main.post {
            val lostFolder = hasRememberedFolder && saved.folderGrantId == null
            if (applySelection(saved) && lostFolder && !savingPreference && !choosingFolder)
                notice = "已记住的文件夹选择不再可用，后续默认保存到 App媒体库。需要文件夹副本时请明确重新选择；旧失效授权的任务不会自动重建副本，原件保留。"
            statuses = next
        }
    }
    private fun applySelection(saved: OrdinaryDestinationSelectionSnapshot): Boolean {
        requireMain()
        if (!settingsOrder.accept(saved)) return false
        preference = saved.preference; folderGrantId = saved.folderGrantId; hasRememberedFolder = saved.folderGrantId != null
        return true
    }
    private fun enqueueFolder(onFailure: () -> Boolean, action: () -> Unit) = enqueueOn(folderWorker, onFailure, action)
    private fun enqueue(onFailure: () -> Boolean = { true }, action: () -> Unit) = enqueueOn(worker, onFailure, action)
    private fun enqueueOn(lane: ThreadPoolExecutor, onFailure: () -> Boolean, action: () -> Unit) {
        try { lane.execute {
            try { action() } catch (_: Exception) { main.post {
                if (onFailure()) notice = "普通媒体保存位置暂不可用。已有原件保留，未确认的额外副本请恢复核对。"
            } } finally {
                // A full control queue can defer a refresh, but completing queued work retries it.
                if (lane === worker && settingsRequested.get() != settingsObserved.get()) startSettingsRefresh()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            main.post { if (onFailure()) notice = "保存位置操作暂忙，请稍后重试。" }
        }
    }
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
}

data class OrdinaryDestinationStatus(val creationId: String, val internalSaved: Boolean,
    val choice: OrdinaryDestinationChoice, val copyPhase: ExportPhase?) {
    override fun toString() = "OrdinaryDestinationStatus(internalSaved=$internalSaved, choice=$choice, copyPhase=$copyPhase)"
}
internal fun ordinaryDestinationLabel(preference: OrdinaryDestinationPreference): String = when (preference) {
    OrdinaryDestinationPreference.APP_LIBRARY -> "App媒体库"
    OrdinaryDestinationPreference.ASK -> "每次询问"
    OrdinaryDestinationPreference.ALBUM_COPY -> "另存一份到相册"
    OrdinaryDestinationPreference.FOLDER_COPY -> "App媒体库并另存一份到所选文件夹"
}
internal fun ordinaryCopyStatus(status: OrdinaryDestinationStatus): String {
    val kind = if (status.choice == OrdinaryDestinationChoice.FOLDER_COPY) "文件夹副本" else "相册副本"
    return when {
    status.choice == OrdinaryDestinationChoice.APP_LIBRARY -> "$kind：本次未选择"
    !status.internalSaved -> "$kind：等待本次成片验证入库"
    status.copyPhase == null || status.copyPhase == ExportPhase.PREPARED -> "$kind：等待复制"
    status.copyPhase == ExportPhase.SUCCEEDED -> "$kind：已完整校验并保存"
    status.copyPhase == ExportPhase.FAILED || status.copyPhase == ExportPhase.CANCELLED -> "$kind：未完成。本次复制不会删除 App媒体库原件；请在媒体库核对来源后重新明确选择另存一份"
    else -> "$kind：处理中或结果待核对，请恢复检查。不会自动新建重复副本"
}

}

internal fun ordinaryCopyCanCancel(status: OrdinaryDestinationStatus): Boolean = status.internalSaved &&
    status.choice != OrdinaryDestinationChoice.APP_LIBRARY &&
    status.copyPhase in setOf(null, ExportPhase.PREPARED, ExportPhase.CREATE_INTENT, ExportPhase.COPYING, ExportPhase.VERIFYING)
