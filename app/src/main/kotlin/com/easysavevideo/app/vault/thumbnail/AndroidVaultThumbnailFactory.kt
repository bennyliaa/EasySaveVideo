package com.easysavevideo.app.vault.thumbnail


import com.easysavevideo.app.library.thumbnail.AndroidSingleFrameRetriever
import com.easysavevideo.app.library.thumbnail.OrdinaryMediaNativeResources
import com.easysavevideo.app.library.thumbnail.SharedThumbnailExtraction
import com.easysavevideo.app.library.thumbnail.SharedThumbnailNativeLane
import com.easysavevideo.app.library.thumbnail.SharedThumbnailRetriever
import com.easysavevideo.app.vault.*
import com.easysavevideo.library.thumbnail.ThumbnailWorkLane
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Same process lane, gate and exact one-frame operation as ordinary thumbnails. */
internal class AndroidVaultThumbnailFactory(
    private val lane: ThumbnailWorkLane = ThumbnailWorkLane { task ->
        try { SharedThumbnailNativeLane.execute(task); true }
        catch (_: RejectedExecutionException) { false } // The actual bounded executor did not enqueue.
    },
    private val retriever: () -> SharedThumbnailRetriever = { AndroidSingleFrameRetriever(requireArgb = true) },
    private val input: (VaultThumbnailReadPort) -> VaultThumbnailControlledInput = { AndroidVaultThumbnailDataSource(it) },
    private val claimNative: (Any) -> Boolean = OrdinaryMediaNativeResources::claim,
    private val releaseNative: (Any) -> Unit = OrdinaryMediaNativeResources::released,
    private val retainUnknown: () -> Unit = OrdinaryMediaNativeResources::unconfirmed,
) : VaultThumbnailPlatformFactory {
    override fun create(ticket: VaultThumbnailTicket, read: VaultThumbnailReadPort, isCurrent: () -> Boolean,
        nativeStarting: () -> Boolean, completed: (VaultThumbnailResult) -> Unit,
        progressed: () -> Unit): VaultPreparedThumbnail = Prepared(read, isCurrent, nativeStarting, completed, progressed)

    private inner class Prepared(
        private val read: VaultThumbnailReadPort,
        private val isCurrent: () -> Boolean,
        private val nativeStarting: () -> Boolean,
        private val completed: (VaultThumbnailResult) -> Unit,
        private val progressed: () -> Unit,
    ) : VaultPreparedThumbnail {
        private val lock = Any()
        private val revoked = AtomicBoolean()
        private val started = AtomicBoolean()
        private val workerStarted = AtomicBoolean()
        private val completionClaimed = AtomicBoolean()
        private val extraction = SharedThumbnailExtraction(retriever)
        private var source: VaultThumbnailControlledInput? = null
        private var result: VaultThumbnailResult? = null
        private var claimed = false
        private var nativeReleased = false
        private var readRetired = false
        private var handoffSettled = false
        private var unknown = false
        private var gateReleased = false

        /** Runtime invokes this through its pre-registered existing Main handoff. */
        override fun start() {
            if (!started.compareAndSet(false, true)) return
            if (cancelled()) { finish(true); return }
            synchronized(lock) { claimed = claimNative(this) }
            if (!claimed) { finish(true); return }
            val task = Runnable {
                if (!workerStarted.compareAndSet(false, true)) return@Runnable
                runNative()
            }
            val accepted = try { lane.submit(task) } catch (_: Exception) {
                markUnknown() // Enqueued/execute-then-throw is not a rejection receipt.
                return
            }
            if (!accepted) {
                if (!workerStarted.get()) finish(true) else markUnknown()
            }
        }

        private fun cancelled(): Boolean = revoked.get() || synchronized(lock) { unknown } || !isCurrent()
        private fun runNative() {
            var released = false
            try {
                if (!cancelled()) {
                    // Strongly own the transport before handing it to any native constructor/setup.
                    source = try { input(read) } catch (error: Exception) { markUnknown(); throw error }
                    if (!cancelled() && nativeStarting()) {
                        extraction.decode(::cancelled) { requireNotNull(source).configure(it) }
                    }
                }
            } catch (_: Exception) {
                // Fixed unavailable result; no private identifiers/paths/causes leave this adapter.
            } finally {
                try { extraction.close(); released = true } catch (_: Exception) { markUnknown() }
                // Transport close only revokes callbacks. It does not claim native/reader/file release.
                try { source?.close() } catch (_: Exception) { markUnknown() }
            }
            finish(released)
        }

        private fun finish(released: Boolean) {
            if (!completionClaimed.compareAndSet(false, true)) return
            val delivery = synchronized(lock) {
                nativeReleased = released
                VaultThumbnailResult(if (released && extraction.released) extraction.takeFrame() else null,
                    released && !unknown).also { result = it }
            }
            try { completed(delivery) } catch (_: Exception) { markUnknown() }
            progress()
        }

        override fun revoke() {
            revoked.set(true)
            // A suppressed/rejected Main admission cannot ever acquire later.
            if (started.compareAndSet(false, true)) finish(true) else progress()
        }
        override fun readOwnerRetired() { synchronized(lock) { readRetired = true; releaseIfSettled() }; progress() }
        override fun resultHandoffSettled() { synchronized(lock) { handoffSettled = true; releaseIfSettled() }; progress() }

        private fun releaseIfSettled() {
            if (!unknown && nativeReleased && readRetired && handoffSettled && !gateReleased) {
                if (claimed) releaseNative(this)
                gateReleased = true
                result = null
                source = null
            }
        }
        override fun nativeReleaseObserved(): Boolean = synchronized(lock) { nativeReleased && !unknown }
        override fun observeDrain(): VaultPlaybackDrain = synchronized(lock) {
            when {
                unknown -> VaultPlaybackDrain.UNCONFIRMED
                gateReleased -> VaultPlaybackDrain.RELEASED
                nativeReleased || revoked.get() -> VaultPlaybackDrain.RETIRING
                else -> VaultPlaybackDrain.LIVE
            }
        }
        private fun markUnknown() {
            synchronized(lock) { unknown = true }
            if (claimed) retainUnknown()
            progress()
        }
        private fun progress() {
            try { progressed() } catch (_: Exception) {
                synchronized(lock) { unknown = true }
                if (claimed) retainUnknown()
            }
        }
    }
}
