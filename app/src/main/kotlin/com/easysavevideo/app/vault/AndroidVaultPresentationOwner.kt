package com.easysavevideo.app.vault

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App-scoped state; only an attached exact protected Main host can deliver a cover receipt. */
internal class AndroidVaultPresentationOwner : VaultPresentationPort, VaultPlaybackHostPort {
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()
    private var latest = VaultRenderedState(VaultRuntimeStatus.LOADING)
    private var pending: Pair<Long, () -> Unit>? = null
    // Strongly retain the exact old Host, including thumbnail jobs/Main handoffs/frames,
    // until its actual root retirement and app-owned resource retirement have both completed.
    private var host: VaultProtectedHost? = null
    private var unknownRetirement = false
    private val playbackRetirement = java.util.IdentityHashMap<VaultPlaybackTicket, VaultPlaybackDrain>()
    var rendered by mutableStateOf(latest); private set

    private fun main(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else check(main.post(action))
    }
    fun attach(value: VaultProtectedHost): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronized(gate) {
            if (unknownRetirement || host != null && host !== value) return false
            host = value
        }
        synchronized(gate) { pending }?.let { (generation, _) -> requestCover(value, generation) }
        return true
    }
    fun detachAfterActualRetirement(value: VaultProtectedHost) {
        check(Looper.myLooper() == Looper.getMainLooper())
        // Never hold presentation.gate while observing runtime.gate through the host.
        val retired = value.actualHostRetired()
        synchronized(gate) { if (host === value && retired) { host = null; unknownRetirement = false } }
    }
    private fun requestCover(value: VaultProtectedHost, generation: Long) {
        value.cover(generation) {
            val receipt = synchronized(gate) {
                val exact = pending
                if (host === value && exact?.first == generation) { pending = null; exact.second } else null
            }
            receipt?.invoke()
        }
    }
    override fun cover(generation: Long, onActuallyHidden: () -> Unit) {
        val value = synchronized(gate) {
            if (generation < latest.coverGeneration) return
            latest = VaultRenderedState(VaultRuntimeStatus.LOCKED, null, generation, media = latest.media.copy(batch = null, actionToken = null, canKeepOutput = false),
                playback = latest.playback.copy(positionMillis = 0, durationMillis = null, seekable = false))
            pending = generation to onActuallyHidden
            host?.invalidateActionsFromAnyThread()
            latest
        }
        main {
            val target = synchronized(gate) {
                if (latest.coverGeneration != generation) return@main
                rendered = value; host
            }
            if (target != null) requestCover(target, generation)
            // No host is not evidence that a previously attached host/native input was released.
        }
    }
    fun retireOrdinarySource(ref: com.easysavevideo.library.MediaReadRef, retired: () -> Unit) = main {
        synchronized(gate) { host }?.retireOrdinarySource(ref, retired)
    }
    override fun presentPlayback(view: VaultPlaybackView) = main {
        val target = synchronized(gate) { host }
        // Capture the exact Main-owned reservation outside presentation.gate, including a not-yet-rendered ticket.
        val reserved = target?.playbackReservation()
        var accepted = view
        synchronized(gate) {
            if (host !== target) return@main
            val ticket = view.ticket
            if (ticket != null) {
                if (!vaultPlaybackViewMatchesReservation(ticket, latest.playback.ticket, reserved,
                        latest.coverGeneration, view.status)) return@main
                if (view.status in setOf(VaultPlaybackStatus.RETIRING, VaultPlaybackStatus.UNAVAILABLE))
                    accepted = view.copy(positionMillis = 0, durationMillis = null, seekable = false)
            }
            latest = latest.copy(playback = accepted); rendered = latest
        }
        target?.playbackStateChanged(accepted)
    }
    override fun offerPrepared(ticket: VaultPlaybackTicket, prepared: VaultPreparedPlayback) {
        // Reserve the queued Main handoff before a cancellation can observe no surface work.
        synchronized(gate) { playbackRetirement[ticket] = VaultPlaybackDrain.LIVE }
        main {
            val target = synchronized(gate) { host }
            if (target == null || !target.offerPreparedPlayback(ticket, prepared)) {
                playbackRetired(ticket, VaultPlaybackDrain.RELEASED)
                prepared.revoke()
            }
        }
    }
    override fun revokePresentation(ticket: VaultPlaybackTicket) = main {
        synchronized(gate) { host }?.revokePlaybackPresentation(ticket)
    }
    override fun observeRetirement(ticket: VaultPlaybackTicket): VaultPlaybackDrain = synchronized(gate) {
        playbackRetirement[ticket] ?: VaultPlaybackDrain.RELEASED
    }
    fun playbackRetired(ticket: VaultPlaybackTicket, drain: VaultPlaybackDrain) {
        synchronized(gate) {
            if (drain == VaultPlaybackDrain.RELEASED) playbackRetirement.remove(ticket)
            else playbackRetirement[ticket] = drain
        }
    }
    override fun presentMedia(view: VaultMediaView) = main {
        synchronized(gate) { latest = latest.copy(media = if (latest.status in setOf(VaultRuntimeStatus.CATALOG, VaultRuntimeStatus.EMPTY)) view
                else view.copy(batch = null, actionToken = null, canKeepOutput = false)); rendered = latest }
    }
    override fun requestPhoneFileSelection(request: VaultPhoneFileRequest) = main {
        synchronized(gate) { host }?.requestPhoneFileSelection(request)
    }
    override fun presentCatalog(revision: Long, items: List<VaultMediaRow>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val target = synchronized(gate) { host } ?: return
        if (!target.publicationAllowed()) return
        synchronized(gate) {
            if (host !== target || !target.publicationAllowed()) return
            latest = latest.copy(status = VaultRuntimeStatus.CATALOG, revision = revision, items = items.toList())
            rendered = latest
        }
        target.presentationChanged()
    }
    override fun presentEmpty(catalogRevision: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val target = synchronized(gate) { host } ?: return
        if (!target.publicationAllowed()) return
        synchronized(gate) {
            if (host !== target || !target.publicationAllowed()) return
            latest = latest.copy(status = VaultRuntimeStatus.EMPTY, revision = catalogRevision, items = emptyList())
            rendered = latest
        }
        target.presentationChanged()
    }
    override fun presentRecoveryState(state: VaultRecoveryState, pendingPassword: Boolean, notice: VaultRecoveryNotice) = main {
        synchronized(gate) {
            latest = latest.copy(recoveryState = state, pendingPassword = pendingPassword, recoveryNotice = notice)
            rendered = latest
        }
    }
    override fun presentStatus(status: VaultRuntimeStatus) = main {
        val target = synchronized(gate) {
            latest = latest.copy(status = status, revision = null, items = emptyList())
            rendered = latest; host
        }
        target?.presentationChanged()
    }
}
