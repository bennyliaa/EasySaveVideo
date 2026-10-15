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
        val receipt: OrdinaryDestinationReceipt)
    var ready by mutableStateOf(false); private set
    var preference by mutableStateOf(OrdinaryDestinationPreference.APP_LIBRARY); private set
    var savingPreference by mutableStateOf(false); private set
    var asking by mutableStateOf(false); private set
    var admitting by mutableStateOf(false); private set
    // A repeated notice code is still a new presentation. Never authorize a queued old button
    // from enum equality, or from a Boolean asking flag which can return to the same value.
    private class NoticePresentation(val code: OrdinaryDestinationNoticeCode)
    private var noticePresentation by mutableStateOf<NoticePresentation?>(null)
    var notice: OrdinaryDestinationNoticeCode?
        get() = noticePresentation?.code
        private set(value) { noticePresentation = value?.let(::NoticePresentation) }
    internal val currentNoticeIdentity: Any? get() = noticePresentation
    internal val currentSelectionIdentity: Any? get() = pending?.takeIf { asking }
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
        select(isCurrent, onTerminal = {}, accepted = accepted)
    }
    /** Optional terminal receipt for callers that latch UI before destination selection completes. */
    internal fun select(isCurrent: () -> Boolean, onTerminal: (OrdinaryDestinationSelectionResult) -> Unit,
        accepted: (OrdinaryDestinationAdmission) -> Unit): OrdinaryDestinationReceipt {
        requireMain()
        val receipt = OrdinaryDestinationReceipt(accepted, onTerminal)
        val source = if (!ready || savingPreference || choosingFolder) OrdinaryDestinationSourceCheck.CHANGED
            else ordinaryDestinationSourceCheck(isCurrent)
        if (source != OrdinaryDestinationSourceCheck.CURRENT) {
            if (pending == null) notice = if (source == OrdinaryDestinationSourceCheck.FAILED)
                OrdinaryDestinationNoticeCode.LOCATION_UNAVAILABLE else OrdinaryDestinationNoticeCode.LOCATION_OR_SOURCE_NOT_READY
            receipt.complete(if (source == OrdinaryDestinationSourceCheck.FAILED)
                OrdinaryDestinationSelectionResult.Failed else OrdinaryDestinationSelectionResult.Rejected)
            return receipt
        }
        val ticket = gate.begin(preference, folderGrantId)
        if (ticket == null) {
            receipt.complete(OrdinaryDestinationSelectionResult.Rejected)
            return receipt
        }
        pending = Pending(ticket, isCurrent, receipt)
        notice = null
        val automatic = automaticDestination(ticket.preference)
        if (automatic == null) asking = true else choose(automatic)
        return receipt
    }
    internal fun choose(expectedIdentity: Any, choice: OrdinaryDestinationChoice) {
        requireMain()
        if (currentSelectionIdentity !== expectedIdentity) return
        choose(choice)
    }
    internal fun cancelSelection(expectedIdentity: Any) {
        requireMain()
        if (currentSelectionIdentity !== expectedIdentity) return
        cancelSelection()
    }
    internal fun clearNotice(expectedIdentity: Any) {
        requireMain()
        if (currentNoticeIdentity !== expectedIdentity) return
        clearNotice()
    }
    fun choose(choice: OrdinaryDestinationChoice) {
        requireMain()
        val selection = pending ?: return
        if (!selectionCurrent(selection, OrdinaryDestinationNoticeCode.SOURCE_CHANGED_BEFORE_SELECTION)) return
        val selectedFolder = if (choice == OrdinaryDestinationChoice.FOLDER_COPY) selection.ticket.folderGrantId else null
        if (choice == OrdinaryDestinationChoice.FOLDER_COPY && selectedFolder == null) {
            if (selection.ticket.preference == OrdinaryDestinationPreference.ASK) {
                // This choice is recoverable: keep the dialog and its caller's latch pending.
                notice = OrdinaryDestinationNoticeCode.REMEMBERED_FOLDER_REQUIRED
            } else completeSelection(selection, OrdinaryDestinationSelectionResult.Rejected,
                OrdinaryDestinationNoticeCode.REMEMBERED_FOLDER_REQUIRED)
            return
        }
        if (!gate.choose(selection.ticket)) return
        asking = false; admitting = true
        val id = UUID.randomUUID().toString()
        enqueueAdmission(onFailure = { failureNotice ->
            completeSelection(selection, OrdinaryDestinationSelectionResult.Failed, failureNotice)
        }) {
            val admitted = storage.destinations.admit(id, choice, selectedFolder)
            main.post {
                if (pending !== selection || !gate.active(selection.ticket)) return@post
                if (!selectionCurrent(selection, OrdinaryDestinationNoticeCode.SOURCE_CHANGED_BEFORE_CREATION)) return@post
                // The ticket contains the durable choice and stable copy identity. Later settings
                // changes cannot affect this job. Callback is consumed before entering creation.
                completeSelection(selection, OrdinaryDestinationSelectionResult.Accepted(admitted))
            }
        }
    }
    fun cancelSelection() {
        requireMain()
        pending?.let { completeSelection(it, OrdinaryDestinationSelectionResult.Cancelled) }
    }
    internal fun cancelSelection(receipt: OrdinaryDestinationReceipt) {
        requireMain()
        val selection = pending ?: return
        if (selection.receipt !== receipt) return
        completeSelection(selection, OrdinaryDestinationSelectionResult.Cancelled)
    }
    private fun selectionCurrent(selection: Pending, changedNotice: OrdinaryDestinationNoticeCode): Boolean {
        return when (ordinaryDestinationSourceCheck(selection.current)) {
            OrdinaryDestinationSourceCheck.CURRENT -> pending === selection && gate.active(selection.ticket)
            OrdinaryDestinationSourceCheck.CHANGED -> {
                completeSelection(selection, OrdinaryDestinationSelectionResult.Rejected, changedNotice); false
            }
            OrdinaryDestinationSourceCheck.FAILED -> {
                completeSelection(selection, OrdinaryDestinationSelectionResult.Failed,
                    OrdinaryDestinationNoticeCode.LOCATION_UNAVAILABLE); false
            }
        }
    }
    private fun completeSelection(selection: Pending, result: OrdinaryDestinationSelectionResult,
        nextNotice: OrdinaryDestinationNoticeCode? = notice): Boolean {
        if (pending !== selection) return false
        val consumed = if (result is OrdinaryDestinationSelectionResult.Accepted) gate.finish(selection.ticket)
            else gate.cancel(selection.ticket)
        if (!consumed) return false
        pending = null; asking = false; admitting = false; notice = nextNotice
        // Nothing below this call may mutate owner state: callbacks may begin another selection.
        return selection.receipt.complete(result)
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
            notice = OrdinaryDestinationNoticeCode.FOLDER_PICKER_DETACHED
        }
    }
    /** This explicit settings action alone launches the system tree-consent flow. */
    fun requestFolder() {
        requireMain()
        if (!ready || savingPreference || choosingFolder || admitting || asking) return
        val host = folderHost ?: run { notice = OrdinaryDestinationNoticeCode.FOLDER_PICKER_NOT_READY; return }
        val ticket = folderGate.begin(host) ?: return
        folderTicket = ticket; choosingFolder = true
        notice = null
        try { host.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }, current = { folderHost === host && folderTicket === ticket && choosingFolder },
            related = { folderHost === host && folderTicket === ticket && choosingFolder }, failed = { folderLaunchFailed(ticket, it) }) { resultCode, data -> receiveFolder(ticket, resultCode, data) } }
        catch (failure: Exception) { folderLaunchFailed(ticket, failure) }
    }
    private fun folderLaunchFailed(ticket: FolderSelectionGate.Ticket, failure: Exception) {
        if (folderGate.cancel(ticket)) {
            folderTicket = null; choosingFolder = false
            notice = when ((failure as? FolderPickerFailure)?.code) {
                FolderPickerError.EXHAUSTED -> OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATIONS_EXHAUSTED
                FolderPickerError.PENDING -> OrdinaryDestinationNoticeCode.FOLDER_PICKER_PENDING
                FolderPickerError.INVALID_STATE -> OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATION_INVALID
                FolderPickerError.STORAGE -> OrdinaryDestinationNoticeCode.FOLDER_PICKER_ASSOCIATION_STORAGE_FAILED
                null -> OrdinaryDestinationNoticeCode.FOLDER_PICKER_LAUNCH_FAILED
            }
        }
    }
    private fun receiveFolder(ticket: FolderSelectionGate.Ticket, resultCode: Int, data: Intent?) {
        requireMain()
        if (!folderGate.receive(ticket)) return
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            if (folderGate.cancel(ticket)) { folderTicket = null; choosingFolder = false; notice = OrdinaryDestinationNoticeCode.FOLDER_SELECTION_CANCELLED }
            return
        }
        enqueueFolder(onFailure = {
            if (folderTicket === ticket) {
                folderGate.cancel(ticket); folderTicket = null; choosingFolder = false
                notice = OrdinaryDestinationNoticeCode.FOLDER_RESULT_UNCONFIRMED
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
                            OrdinaryDestinationNoticeCode.FOLDER_GRANT_CAPACITY_REACHED
                            else OrdinaryDestinationNoticeCode.FOLDER_ADOPTION_FAILED
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
                        OrdinaryDestinationNoticeCode.FOLDER_SELECTED
                        else OrdinaryDestinationNoticeCode.FOLDER_SELECTION_CHANGED
                }
            }
        }
    }
    fun cancelFolderSelection() {
        requireMain()
        val ticket = folderTicket ?: return
        if (folderGate.cancel(ticket)) { folderTicket = null; choosingFolder = false; notice = OrdinaryDestinationNoticeCode.FOLDER_CHANGE_CANCELLED }
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
                notice = OrdinaryDestinationNoticeCode.FOLDER_FORGOTTEN
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
                            notice = OrdinaryDestinationNoticeCode.FOLDER_PERMISSION_INVALIDATED
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
            catch (_: Exception) { main.post { notice = OrdinaryDestinationNoticeCode.COPY_STATUS_UNAVAILABLE } }
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
                OrdinaryDestinationNoticeCode.COPY_CANCELLATION_REQUESTED
                else OrdinaryDestinationNoticeCode.COPY_CANCELLATION_UNAVAILABLE }
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
                notice = OrdinaryDestinationNoticeCode.REMEMBERED_FOLDER_UNAVAILABLE
            statuses = next
        }
    }
    private fun applySelection(saved: OrdinaryDestinationSelectionSnapshot): Boolean {
        requireMain()
        if (!settingsOrder.accept(saved)) return false
        preference = saved.preference; folderGrantId = saved.folderGrantId; hasRememberedFolder = saved.folderGrantId != null
        return true
    }
    private fun enqueueAdmission(onFailure: (OrdinaryDestinationNoticeCode) -> Unit, action: () -> Unit) =
        enqueueOn(worker, onFailure, action)
    private fun enqueueFolder(onFailure: () -> Boolean, action: () -> Unit) =
        enqueueOn(folderWorker, { if (onFailure()) notice = it }, action)
    private fun enqueue(onFailure: () -> Boolean = { true }, action: () -> Unit) =
        enqueueOn(worker, { if (onFailure()) notice = it }, action)
    private fun enqueueOn(lane: ThreadPoolExecutor, onFailure: (OrdinaryDestinationNoticeCode) -> Unit, action: () -> Unit) {
        try { lane.execute {
            try { action() } catch (_: Exception) { main.post {
                onFailure(OrdinaryDestinationNoticeCode.LOCATION_UNAVAILABLE)
            } } finally {
                // A full control queue can defer a refresh, but completing queued work retries it.
                if (lane === worker && settingsRequested.get() != settingsObserved.get()) startSettingsRefresh()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            main.post { onFailure(OrdinaryDestinationNoticeCode.LOCATION_BUSY) }
        }
    }
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
}

data class OrdinaryDestinationStatus(val creationId: String, val internalSaved: Boolean,
    val choice: OrdinaryDestinationChoice, val copyPhase: ExportPhase?) {
    override fun toString() = "OrdinaryDestinationStatus(internalSaved=$internalSaved, choice=$choice, copyPhase=$copyPhase)"
}
internal fun ordinaryCopyCanCancel(status: OrdinaryDestinationStatus): Boolean = status.internalSaved &&
    status.choice != OrdinaryDestinationChoice.APP_LIBRARY &&
    status.copyPhase in setOf(null, ExportPhase.PREPARED, ExportPhase.CREATE_INTENT, ExportPhase.COPYING, ExportPhase.VERIFYING)
