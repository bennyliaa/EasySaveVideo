package com.easysavevideo.app.vault

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App-scoped state; only an attached exact protected Main host can deliver a cover receipt. */
internal class AndroidVaultPresentationOwner : VaultPresentationPort {
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()
    private var latest = VaultRenderedState(VaultRuntimeStatus.LOADING)
    private var pending: Pair<Long, () -> Unit>? = null
    private var host: VaultProtectedHost? = null
    private var unknownRetirement = false
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
        synchronized(gate) { if (host === value && value.actualHostRetired()) { host = null; unknownRetirement = false } }
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
            latest = VaultRenderedState(VaultRuntimeStatus.LOCKED, null, generation)
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
    override fun presentEmpty(catalogRevision: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val target = synchronized(gate) { host } ?: return
        if (!target.publicationAllowed()) return
        synchronized(gate) {
            if (host !== target || !target.publicationAllowed()) return
            latest = latest.copy(status = VaultRuntimeStatus.EMPTY, revision = catalogRevision)
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
            latest = latest.copy(status = status, revision = null)
            rendered = latest; host
        }
        target?.presentationChanged()
    }
}
