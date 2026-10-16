package com.easysavevideo.app.vault

import com.easysavevideo.library.export.ExportRelease
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import com.easysavevideo.library.thumbnail.ThumbnailDecodeResult
import com.easysavevideo.library.thumbnail.ThumbnailFrame
import com.easysavevideo.library.thumbnail.ThumbnailUnavailable

/** A leaf of the existing VaultRuntime, sharing its gate, root, store and authentication owner.
 * Cancellation removes authority immediately. Only actual returns settle acquired resources.
 */
internal class VaultPlaybackRuntime(
    private val gate: Any,
    private val authentication: VaultAuthenticationPort,
    private val store: VaultStorePort,
    private val clock: VaultMonotonicClock,
    private val work: Executor,
    private val ui: Executor,
    private val scheduler: VaultWakeScheduler,
    private val factory: VaultPlaybackPlatformFactory,
    private val host: VaultPlaybackHostPort,
    private val session: (VaultMediaReadRef, VaultPlaybackHostToken) -> VaultPlaybackSession?,
    private val changed: (VaultPlaybackView) -> Unit,
    private val drained: () -> Unit,
    private val thumbnailFactory: VaultThumbnailPlatformFactory? = null,
) {
    private enum class Purpose { PLAYBACK, THUMBNAIL }
    private enum class Acquisition { NONE, ACQUIRING, OWNED, RELEASED, UNKNOWN }
    private class Entry(val ticket: VaultPlaybackTicket, val session: VaultPlaybackSession,
        val context: VaultOperationContext, val purpose: Purpose = Purpose.PLAYBACK,
        val thumbnailTicket: VaultThumbnailTicket? = null, val completion: VaultThumbnailCompletion? = null) {
        var thumbnail: VaultPreparedThumbnail? = null
        var thumbnailResult: VaultThumbnailResult? = null
        var resultClaimed = false
        var resultTransferred = false
        var resultSettled = false
        var resultConsumerReturned = false
        var resultReceiptSent = false
        var thumbnailDeliveryStarted = false
        var nativeStarted = false
        var callbackCount = 0
        var callbacks = 0
        var byteCallback = false
        var returnedBytes = 0L
        var frameReserved = purpose == Purpose.THUMBNAIL
        var displayRecord: DisplayRecord? = null
        var successfulReadRetirement = false
        var thumbnailCovered = false
        var thumbnailCancelled = false
        var revoked = false
        var preparing = true
        var prepared = false
        var jobs = 0
        var handoffs = 0
        var retirementUiState = Acquisition.NONE
        var permits = 0
        var reads = 0
        var readContext: VaultOperationContext? = null
        val readWakes = mutableSetOf<Any>()
        var transports = 0
        var fileState = Acquisition.NONE
        var readerState = Acquisition.NONE
        var playerState = Acquisition.NONE
        var file: VaultOwnedCiphertext? = null
        var reader: VaultMediaReadCapability? = null
        var player: VaultPreparedPlayback? = null
        var closeQueued = false
        var readerCloseStarted = false
        var fileCloseStarted = false
        var playerRevokeSent = false
        var readRetirementSent = false
        var failure = false
        var uiFailed = false
        var timedOut = false
        var commandQueued = false
        var intent: Boolean? = null
        var seek: Long? = null
        var view = VaultPlaybackView(ticket, VaultPlaybackStatus.PREPARING)
        var lastPositionAt = Long.MIN_VALUE
        var lifetimeWake: VaultWakeCancellation? = null
        var prepareWake: VaultWakeCancellation? = null
        var retirementWake: VaultWakeCancellation? = null
    }
    private class DisplayRecord(val ticket: VaultThumbnailTicket, val token: VaultThumbnailDisplayToken,
        val source: ThumbnailFrame) {
        var closeStarted = false
        var closeRequested = false
        var draws = 0
        var closed = false
        var visualRetired = false
        var drawn = false
    }
    private var current: Entry? = null
    private val displays = mutableListOf<DisplayRecord>()
    private var revealIdentity: Any? = null
    private val attempted = linkedSetOf<VaultMediaReadRef>()
    private val terminal = linkedSetOf<VaultMediaReadRef>()
    private var thumbnailFence = false

    fun occupied(): Boolean = synchronized(gate) {
        current != null || thumbnailFence || displays.any { !it.closed && !it.token.isCurrent() }
    }
    fun cleanupComplete(): Boolean = synchronized(gate) { current == null && displays.isEmpty() && !thumbnailFence }
    fun playbackCleanupComplete(): Boolean = synchronized(gate) { current?.purpose != Purpose.PLAYBACK }
    fun thumbnailCleanupComplete(): Boolean = synchronized(gate) {
        current?.purpose != Purpose.THUMBNAIL && displays.isEmpty() && !thumbnailFence
    }

    fun open(ref: VaultMediaReadRef, hostToken: VaultPlaybackHostToken): Boolean {
        val entry = synchronized(gate) {
            if (current != null || thumbnailFence || displays.any { !it.closed && !it.token.isCurrent() } || !hostToken.isCurrent() ||
                authentication.observeMediaDrain() != ExportRelease.RELEASED ||
                store.observeMediaDrain() != ExportRelease.RELEASED) return false
            val captured = session(ref, hostToken) ?: return false
            if (captured.ref != ref || !captured.isCurrent()) return false
            val now = clock.elapsedMillis()
            if (now < 0 || now > Long.MAX_VALUE - VaultPlaybackLimits.LIFETIME_MILLIS) return false
            Entry(VaultPlaybackTicket(hostToken), captured,
                VaultOperationContext(now + VaultPlaybackLimits.LIFETIME_MILLIS, clock)).also { current = it }
        }
        publish(entry)
        try {
            installWake(entry, lifetime = true, VaultPlaybackLimits.LIFETIME_MILLIS)
            installWake(entry, lifetime = false, VaultPlaybackLimits.PREPARE_MILLIS)
            execute(entry, onRejected = { synchronized(gate) { entry.preparing = false }; revoke(entry, true) }) {
                try { prepare(entry) }
                catch (_: Exception) { revoke(entry, true) }
                finally {
                    synchronized(gate) { entry.preparing = false }
                    progress(entry)
                }
            }
        } catch (_: Exception) {
            synchronized(gate) { entry.preparing = false }
            revoke(entry, true)
        }
        return true // Reservation was accepted, even if asynchronous preparation failed.
    }

    private fun installWake(entry: Entry, lifetime: Boolean, delay: Long) {
        val deadline = if (lifetime) entry.context.deadlineElapsedMillis
            else entry.context.deadlineElapsedMillis - VaultPlaybackLimits.LIFETIME_MILLIS + VaultPlaybackLimits.PREPARE_MILLIS
        val wake = scheduler.schedule(delay) {
            val expire = synchronized(gate) {
                if (current !== entry || entry.revoked || !lifetime && entry.prepared || clock.elapsedMillis() < deadline) false
                else { entry.context.cancel(); true }
            }
            if (expire) revoke(entry, true)
        }
        synchronized(gate) {
            if (entry.revoked || !lifetime && entry.prepared) wake.cancel()
            else if (lifetime) entry.lifetimeWake = wake else entry.prepareWake = wake
        }
    }

    private fun live(entry: Entry) {
        if (current !== entry || entry.revoked || !entry.ticket.host.isCurrent() || !entry.session.isCurrent())
            throw VaultOperationException(VaultFailure.CANCELLED)
        entry.context.checkActive()
    }
    private fun stillLive(entry: Entry): Boolean = synchronized(gate) {
        try { live(entry); true } catch (_: Exception) { false }
    }

    private fun prepare(entry: Entry) {
        synchronized(gate) { live(entry); entry.fileState = Acquisition.ACQUIRING }
        val file = try { store.openMediaFile(entry.session.ref.identity, entry.context) }
        catch (error: Exception) {
            synchronized(gate) { entry.fileState = if (store.observeMediaDrain() == ExportRelease.RELEASED)
                Acquisition.RELEASED else Acquisition.UNKNOWN }
            throw error
        }
        synchronized(gate) { entry.file = file; entry.fileState = Acquisition.OWNED }
        synchronized(gate) { live(entry); entry.readerState = Acquisition.ACQUIRING }
        val reader = try { authentication.openMediaRead(entry.session.root, entry.session.snapshot,
            entry.session.ref, file, publicationGate(entry), entry.context) }
        catch (error: Exception) {
            synchronized(gate) { entry.readerState = if (authentication.observeMediaDrain() == ExportRelease.RELEASED)
                Acquisition.RELEASED else Acquisition.UNKNOWN }
            throw error
        }
        synchronized(gate) { entry.reader = reader; entry.readerState = Acquisition.OWNED }
        synchronized(gate) {
            live(entry)
            if (reader.authenticatedSize !in 1..VaultMediaLimits.MAX_BYTES) throw VaultOperationException(VaultFailure.CORRUPT)
            entry.playerState = Acquisition.ACQUIRING
        }
        if (entry.purpose == Purpose.THUMBNAIL) { prepareThumbnail(entry); return }
        val player = try { factory.create(entry.ticket, readPort(entry), { stillLive(entry) },
            { view -> platformChanged(entry, view) }, { progress(entry) }) }
        catch (error: Exception) {
            // No platform handle/receipt exists to prove that its factory acquired nothing.
            synchronized(gate) { entry.playerState = Acquisition.UNKNOWN; entry.failure = true }
            throw error
        }
        synchronized(gate) { entry.player = player; entry.playerState = Acquisition.OWNED }
        if (!stillLive(entry)) { revoke(entry); return }
        dispatchPrepared(entry, player)
        publish(entry)
        progress(entry)
    }

    private fun readPort(entry: Entry): VaultThumbnailReadPort = object : VaultThumbnailReadPort {
        override fun beginCallback(byteRead: Boolean): VaultThumbnailReadCallback = beginThumbnailCallback(entry, byteRead)
        override val authenticatedSize: Long get() = synchronized(gate) {
            live(entry); checkNotNull(entry.reader).authenticatedSize
        }
        override fun requireCurrent() {
            try { synchronized(gate) { live(entry) } }
            catch (_: Exception) { revoke(entry); throw safePlaybackIo("REVOKED") }
        }
        override fun transportOpened() = synchronized(gate) {
            live(entry)
            if (entry.transports != 0) throw safePlaybackIo("TRANSPORT_BUSY")
            entry.transports = 1
        }
        override fun transportClosed() {
            synchronized(gate) { check(entry.transports == 1); entry.transports = 0 }
            progress(entry)
        }
        override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
            val readToken = Any()
            val captured = synchronized(gate) {
                live(entry)
                if (entry.transports != 1 || entry.reads != 0 || position < 0 || offset < 0 || length !in 0..VaultMediaLimits.BUFFER_BYTES ||
                    entry.purpose == Purpose.THUMBNAIL && (entry.callbacks == 0 || !entry.byteCallback || length.toLong() > VaultThumbnailLimits.MAX_BYTES - entry.returnedBytes) ||
                    offset > destination.size - length) throw safePlaybackIo("RANGE_REJECTED")
                val child = entry.context.bounded(VaultPlaybackLimits.READ_MILLIS)
                entry.reads++; entry.readContext = child; entry.readWakes.add(readToken)
                checkNotNull(entry.reader) to child
            }
            var wake: VaultWakeCancellation? = null
            try {
                wake = scheduler.schedule(VaultPlaybackLimits.READ_MILLIS) {
                    val expire = synchronized(gate) {
                        if (current !== entry || entry.revoked || readToken !in entry.readWakes ||
                            clock.elapsedMillis() < captured.second.deadlineElapsedMillis) false
                        else { entry.context.cancel(); true }
                    }
                    if (expire) revoke(entry, true)
                }
                val result = captured.first.readAt(position, destination, offset, length, captured.second)
                val size = captured.first.authenticatedSize
                val valid = if (length == 0) result == 0 else if (position == size) result == -1
                    else position < size && result in 1..minOf(length.toLong(), size - position).toInt()
                if (!valid) throw VaultOperationException(VaultFailure.CORRUPT)
                if (entry.purpose == Purpose.THUMBNAIL) synchronized(gate) {
                    live(entry)
                    if (result > 0) entry.returnedBytes += result
                }
                return result
            } catch (_: Exception) {
                revoke(entry, true)
                throw safePlaybackIo("READ_FAILED")
            } finally {
                synchronized(gate) { check(entry.reads > 0); entry.readWakes.remove(readToken); entry.readContext = null; entry.reads-- }
                wake?.cancel()
                progress(entry)
            }
        }
    }

    private fun publicationGate(entry: Entry) = object : VaultMediaPublicationGate {
        override fun requireCurrent() = synchronized(gate) { live(entry) }
        override fun admit(): VaultMediaIoPermit = synchronized(gate) {
            live(entry)
            val publicationContext = entry.readContext ?: entry.context
            publicationContext.checkActive()
            entry.permits++
            object : VaultMediaIoPermit {
                private var closed = false
                override fun publish(scratch: ByteArray, sourceOffset: Int, destination: ByteArray,
                    destinationOffset: Int, length: Int) = synchronized(gate) {
                    if (closed) throw VaultOperationException(VaultFailure.CANCELLED)
                    live(entry)
                    try { publicationContext.checkActive() } catch (error: Exception) { entry.context.cancel(); throw error }
                    require(length in 0..VaultMediaLimits.BUFFER_BYTES && sourceOffset >= 0 && destinationOffset >= 0 &&
                        sourceOffset <= scratch.size - length && destinationOffset <= destination.size - length)
                    scratch.copyInto(destination, destinationOffset, sourceOffset, sourceOffset + length)
                    Unit
                }
                override fun close() {
                    synchronized(gate) { if (closed) return; closed = true; check(entry.permits > 0); entry.permits-- }
                    progress(entry)
                }
            }
        }
    }

    fun play(ticket: VaultPlaybackTicket) = command(ticket, true, null)
    fun pause(ticket: VaultPlaybackTicket) = command(ticket, false, null)
    fun seek(ticket: VaultPlaybackTicket, targetMillis: Long) = command(ticket, null, targetMillis)
    private fun command(ticket: VaultPlaybackTicket, intent: Boolean?, seek: Long?) {
        val entry = synchronized(gate) {
            val value = current?.takeIf { it.ticket === ticket } ?: return
            if (!stillLive(value) || value.player == null || value.view.status !in setOf(VaultPlaybackStatus.READY, VaultPlaybackStatus.PLAYING, VaultPlaybackStatus.PAUSED, VaultPlaybackStatus.ENDED)) return
            if (seek != null) {
                val duration = value.view.durationMillis ?: return
                if (!value.view.seekable || duration < 0) return
                value.seek = seek.coerceIn(0L, duration)
            }
            if (intent != null) value.intent = intent
            if (value.commandQueued) return
            value.commandQueued = true
            value
        }
        execute(entry, onRejected = { synchronized(gate) { entry.commandQueued = false }; revoke(entry, true) }) {
            try {
                // One runner and at most one latest seek/intent; revoke always clears both first.
                while (true) {
                    val next = synchronized(gate) {
                        if (!stillLive(entry)) { entry.intent = null; entry.seek = null; null }
                        else {
                            val seekValue = entry.seek; val intentValue = entry.intent
                            entry.seek = null; entry.intent = null
                            if (seekValue == null && intentValue == null) null else seekValue to intentValue
                        }
                    } ?: break
                    val player = synchronized(gate) { entry.player } ?: break
                    if (next.first != null && stillLive(entry)) player.seekTo(next.first!!)
                    if (next.second != null && stillLive(entry)) {
                        if (next.second == true) player.play() else player.pause()
                    }
                }
            } catch (_: Exception) { revoke(entry, true) }
            finally {
                val again = synchronized(gate) { entry.commandQueued = false; !entry.revoked && (entry.seek != null || entry.intent != null) }
                if (again) command(entry.ticket, null, null)
                progress(entry)
            }
        }
    }

    fun close(ticket: VaultPlaybackTicket) {
        val entry = synchronized(gate) { current?.takeIf { it.ticket === ticket } } ?: return
        revoke(entry)
    }
    fun revokeAll() {
        revokeThumbnails()
        synchronized(gate) { current }?.let { revoke(it) }
    }

    private fun revoke(entry: Entry, failure: Boolean = false) {
        val first = synchronized(gate) {
            if (current !== entry) return
            if (failure) {
                entry.failure = true
                if (entry.purpose == Purpose.THUMBNAIL && entry.fileState != Acquisition.NONE)
                    terminal.add(entry.session.ref)
            }
            val first = !entry.revoked
            entry.revoked = true; entry.context.cancel(); entry.intent = null; entry.seek = null
            entry.lifetimeWake?.cancel(); entry.lifetimeWake = null
            entry.prepareWake?.cancel(); entry.prepareWake = null
            entry.view = entry.view.copy(status = if (entry.failure) VaultPlaybackStatus.UNAVAILABLE else VaultPlaybackStatus.RETIRING,
                positionMillis = 0, durationMillis = null, seekable = false)
            first
        }
        try { synchronized(gate) { entry.reader }?.revoke() }
        catch (_: Exception) { synchronized(gate) { entry.failure = true } }
        if (first) {
            if (entry.purpose == Purpose.PLAYBACK) dispatchRetirement(entry)
            else synchronized(gate) { entry.retirementUiState = Acquisition.RELEASED }
            try {
                val wake = scheduler.schedule(VaultPlaybackLimits.RETIREMENT_MILLIS) {
                    synchronized(gate) { if (current === entry) { entry.timedOut = true; entry.view = entry.view.copy(status = VaultPlaybackStatus.UNAVAILABLE, positionMillis = 0, durationMillis = null, seekable = false) } }
                    publish(entry); progress(entry)
                }
                synchronized(gate) { if (current !== entry) wake.cancel() else entry.retirementWake = wake }
            } catch (_: Exception) { synchronized(gate) { entry.failure = true } }
        }
        requestPlayerRevoke(entry)
        publish(entry)
        progress(entry)
    }

    private fun requestPlayerRevoke(entry: Entry) {
        if (entry.purpose == Purpose.THUMBNAIL) {
            val thumbnail = synchronized(gate) {
                if (!entry.revoked || entry.playerRevokeSent) return
                entry.thumbnail?.also { entry.playerRevokeSent = true }
            } ?: return
            try { thumbnail.revoke() } catch (_: Exception) { synchronized(gate) { entry.playerState = Acquisition.UNKNOWN; entry.failure = true } }
            return
        }
        val player = synchronized(gate) {
            if (!entry.revoked || entry.playerRevokeSent) return
            entry.player?.also { entry.playerRevokeSent = true }
        } ?: return
        try { player.revoke() } catch (_: Exception) { synchronized(gate) { entry.failure = true; entry.playerState = Acquisition.UNKNOWN } }
    }

    fun hostProgressed() { synchronized(gate) { current }?.let { progress(it) } }
    private fun progress(entry: Entry) {
        if (entry.purpose == Purpose.THUMBNAIL && !entry.revoked) {
            val stopped = synchronized(gate) { entry.thumbnail != null &&
                try { entry.thumbnail!!.observeDrain() == VaultPlaybackDrain.UNCONFIRMED } catch (_: Exception) { true } }
            if (stopped) { revoke(entry, !entry.successfulReadRetirement); return }
        }
        val platformRetired = synchronized(gate) {
            current === entry && !entry.revoked && entry.player != null &&
                try { entry.player!!.observeDrain() != VaultPlaybackDrain.LIVE } catch (_: Exception) { true }
        }
        if (platformRetired) { revoke(entry, true); return }
        if (!synchronized(gate) { current === entry && entry.revoked }) return
        requestPlayerRevoke(entry)
        val close = synchronized(gate) {
            // Revocation prevents every future publication. Native failure must not prevent
            // independent scratch/reader/file retirement after all actual reads return.
            if (entry.preparing || entry.closeQueued || entry.reads != 0 || entry.permits != 0 || entry.callbacks != 0) false
            else {
                if (entry.readerState == Acquisition.UNKNOWN && entry.reader == null && authentication.observeMediaDrain() == ExportRelease.RELEASED)
                    entry.readerState = Acquisition.RELEASED
                if (entry.fileState == Acquisition.UNKNOWN && entry.file == null && store.observeMediaDrain() == ExportRelease.RELEASED)
                    entry.fileState = Acquisition.RELEASED
                val readerNeedsClose = entry.reader != null && !entry.readerCloseStarted
                val fileNeedsClose = entry.file != null && !entry.fileCloseStarted &&
                    entry.readerState in setOf(Acquisition.NONE, Acquisition.RELEASED)
                if (readerNeedsClose || fileNeedsClose) { entry.closeQueued = true; true } else false
            }
        }
        if (close) execute(entry, onRejected = {
            synchronized(gate) { entry.closeQueued = false; entry.failure = true }
        }) {
            try { closeReadResources(entry) }
            finally { synchronized(gate) { entry.closeQueued = false }; signalReadRetired(entry); settle(entry) }
        }
        signalReadRetired(entry)
        settle(entry)
    }

    private fun signalReadRetired(entry: Entry) {
        if (entry.purpose == Purpose.THUMBNAIL) {
            val thumbnail = synchronized(gate) {
                if (entry.readRetirementSent || !readResourcesReleased(entry)) null
                else entry.thumbnail?.also { entry.readRetirementSent = true }
            }
            if (thumbnail != null) try { thumbnail.readOwnerRetired() }
                catch (_: Exception) { synchronized(gate) { entry.playerState = Acquisition.UNKNOWN; entry.failure = true } }
            deliverThumbnail(entry)
            return
        }
        val player = synchronized(gate) {
            if (entry.readRetirementSent || !entry.revoked || entry.preparing || entry.reads != 0 || entry.permits != 0 ||
                entry.fileState !in setOf(Acquisition.NONE, Acquisition.RELEASED) ||
                entry.readerState !in setOf(Acquisition.NONE, Acquisition.RELEASED)) return
            entry.player?.also { entry.readRetirementSent = true }
        } ?: return
        try { player.readOwnerRetired() }
        catch (_: Exception) { synchronized(gate) { entry.failure = true; entry.playerState = Acquisition.UNKNOWN } }
    }

    private fun closeReadResources(entry: Entry) {
        val reader = synchronized(gate) {
            entry.reader?.takeIf { !entry.readerCloseStarted }?.also { entry.readerCloseStarted = true }
        }
        if (reader != null) {
            val released = try { reader.revoke(); reader.closeAndObserve() == ExportRelease.RELEASED } catch (_: Exception) { false }
            synchronized(gate) {
                entry.readerState = if (released) Acquisition.RELEASED else Acquisition.UNKNOWN
                if (!released) entry.failure = true
            }
        }
        val file = synchronized(gate) {
            entry.file?.takeIf { !entry.fileCloseStarted && entry.readerState in setOf(Acquisition.NONE, Acquisition.RELEASED) }
                ?.also { entry.fileCloseStarted = true }
        }
        if (file != null) {
            val released = try { file.closeAndObserve() == ExportRelease.RELEASED } catch (_: Exception) { false }
            synchronized(gate) {
                entry.fileState = if (released) Acquisition.RELEASED else Acquisition.UNKNOWN
                if (!released) entry.failure = true
            }
        }
    }

    private fun settle(entry: Entry) {
        if (entry.purpose == Purpose.THUMBNAIL) { settleThumbnail(entry); return }
        val released = synchronized(gate) {
            if (current !== entry || !entry.revoked || entry.preparing || entry.jobs != 0 || entry.handoffs != 0 || entry.permits != 0 ||
                entry.reads != 0 || entry.transports != 0 || entry.closeQueued || entry.commandQueued ||
                entry.retirementUiState != Acquisition.RELEASED) return
            val playerDrain = try { entry.player?.observeDrain() } catch (_: Exception) { VaultPlaybackDrain.UNCONFIRMED }
            if (entry.playerState == Acquisition.OWNED && playerDrain == VaultPlaybackDrain.RELEASED) entry.playerState = Acquisition.RELEASED
            val hostDrain = try { host.observeRetirement(entry.ticket) } catch (_: Exception) { VaultPlaybackDrain.UNCONFIRMED }
            if (entry.fileState !in setOf(Acquisition.NONE, Acquisition.RELEASED) ||
                entry.readerState !in setOf(Acquisition.NONE, Acquisition.RELEASED) ||
                entry.playerState !in setOf(Acquisition.NONE, Acquisition.RELEASED) ||
                hostDrain != VaultPlaybackDrain.RELEASED || authentication.observeMediaDrain() != ExportRelease.RELEASED ||
                store.observeMediaDrain() != ExportRelease.RELEASED) return
            entry.retirementWake?.cancel(); entry.retirementWake = null
            current = null
            true
        }
        if (released) {
            if (!synchronized(gate) { entry.uiFailed }) try {
                ui.execute { try { if (synchronized(gate) { current == null }) changed(VaultPlaybackView()) } catch (_: Exception) { } }
            } catch (_: Exception) { }
            drained()
        }
    }

    private fun platformChanged(entry: Entry, view: VaultPlaybackView) {
        if (view.ticket === entry.ticket && view.status == VaultPlaybackStatus.UNAVAILABLE && stillLive(entry)) { revoke(entry, true); return }
        synchronized(gate) {
            if (view.ticket !== entry.ticket || !stillLive(entry)) return
            if (view.status in setOf(VaultPlaybackStatus.READY, VaultPlaybackStatus.PLAYING, VaultPlaybackStatus.PAUSED, VaultPlaybackStatus.ENDED)) {
                entry.prepared = true; entry.prepareWake?.cancel(); entry.prepareWake = null
            }
            val now = clock.elapsedMillis()
            val statusChanged = view.status != entry.view.status || view.durationMillis != entry.view.durationMillis || view.seekable != entry.view.seekable
            if (!statusChanged && entry.lastPositionAt != Long.MIN_VALUE && now - entry.lastPositionAt < VaultPlaybackLimits.POSITION_UPDATE_MILLIS) return
            val duration = view.durationMillis?.takeIf { it >= 0 }
            entry.view = view.copy(positionMillis = view.positionMillis.coerceAtLeast(0).let { if (duration != null) minOf(it, duration) else it },
                durationMillis = duration, seekable = view.seekable && duration != null)
            entry.lastPositionAt = now
        }
        publish(entry)
    }

    /** Retirement is mandatory even when rendering a status view failed. Never retry an uncertain Main handoff. */
    private fun dispatchRetirement(entry: Entry) {
        val claimed = AtomicBoolean(false)
        synchronized(gate) { entry.retirementUiState = Acquisition.ACQUIRING; entry.handoffs++ }
        try {
            ui.execute {
                if (!claimed.compareAndSet(false, true)) return@execute
                try {
                    host.revokePresentation(entry.ticket)
                    synchronized(gate) { entry.retirementUiState = Acquisition.RELEASED }
                } catch (_: Exception) {
                    synchronized(gate) { entry.retirementUiState = Acquisition.UNKNOWN; entry.failure = true }
                } finally { synchronized(gate) { entry.handoffs-- }; progress(entry) }
            }
        } catch (_: Exception) {
            if (claimed.compareAndSet(false, true)) synchronized(gate) {
                entry.handoffs--; entry.retirementUiState = Acquisition.UNKNOWN; entry.failure = true
            }
        }
    }

    /** Main handoff remains an obligation even after its current check, until external host return. */
    private fun dispatchPrepared(entry: Entry, player: VaultPreparedPlayback) {
        val claimed = AtomicBoolean(false)
        synchronized(gate) { entry.handoffs++ }
        try {
            ui.execute {
                if (!claimed.compareAndSet(false, true)) return@execute
                try {
                    if (stillLive(entry)) host.offerPrepared(entry.ticket, player) else revoke(entry)
                } catch (_: Exception) { revoke(entry, true) }
                finally { synchronized(gate) { entry.handoffs-- }; progress(entry) }
            }
        } catch (_: Exception) {
            if (claimed.compareAndSet(false, true)) {
                synchronized(gate) { entry.handoffs-- }
                revoke(entry, true)
            }
        }
    }

    private fun publish(entry: Entry) {
        if (entry.purpose == Purpose.THUMBNAIL) return
        dispatchUi(entry) {
        val view = synchronized(gate) {
            if (current !== entry) return@dispatchUi
            if (!entry.revoked && !stillLive(entry)) return@dispatchUi
            entry.view
        }
        changed(view)
        }
    }
    private fun dispatchUi(entry: Entry, task: () -> Unit) {
        if (synchronized(gate) { entry.uiFailed }) return
        fun rejected() {
            val first = synchronized(gate) { val first = !entry.uiFailed; entry.uiFailed = true; entry.failure = true; first }
            if (first) revoke(entry, true)
        }
        try { ui.execute { try { task() } catch (_: Exception) { rejected() } } }
        catch (_: Exception) { rejected() }
    }

    /** A rejected/late executor wrapper cannot start twice or settle a still-running task. */
    private fun execute(entry: Entry, onRejected: () -> Unit, task: () -> Unit) {
        val claimed = AtomicBoolean(false)
        synchronized(gate) { entry.jobs++ }
        try {
            work.execute {
                if (!claimed.compareAndSet(false, true)) return@execute
                try { task() }
                finally { synchronized(gate) { check(entry.jobs > 0); entry.jobs-- }; progress(entry) }
            }
        } catch (_: Exception) {
            if (claimed.compareAndSet(false, true)) {
                synchronized(gate) { check(entry.jobs > 0); entry.jobs-- }
                onRejected()
            }
        }
    }
    /** Same Entry reservation and authenticated read owner as playback; no player is constructed. */
    fun openThumbnail(ref: VaultMediaReadRef, hostToken: VaultThumbnailHostToken,
        completed: VaultThumbnailCompletion): VaultThumbnailTicket? {
        val entry = synchronized(gate) {
            if (thumbnailFactory == null || current != null || thumbnailFence || !hostToken.isCurrent() ||
                displays.any { !it.closed && !it.token.isCurrent() } ||
                displays.count { !it.closed } >= VaultThumbnailLimits.MAX_FRAMES ||
                displays.size >= VaultThumbnailLimits.MAX_IDENTITIES ||
                authentication.observeMediaDrain() != ExportRelease.RELEASED ||
                store.observeMediaDrain() != ExportRelease.RELEASED) return null
            val captured = session(ref, hostToken) ?: return null
            if (captured.ref != ref || !captured.isCurrent()) return null
            if (revealIdentity !== captured.revealIdentity) {
                // A replacement reveal cannot clear an old unknown or live presentation fence.
                if (displays.any { !it.visualRetired }) return null
                revealIdentity = captured.revealIdentity; attempted.clear(); terminal.clear()
            }
            if (ref in attempted || ref in terminal || (attempted + terminal).size >= VaultThumbnailLimits.MAX_IDENTITIES) return null
            val now = clock.elapsedMillis()
            if (now < 0 || now > Long.MAX_VALUE - VaultThumbnailLimits.LIFETIME_MILLIS) return null
            val ticket = VaultThumbnailTicket(hostToken, ref)
            Entry(VaultPlaybackTicket(hostToken), captured,
                VaultOperationContext(now + VaultThumbnailLimits.LIFETIME_MILLIS, clock),
                Purpose.THUMBNAIL, ticket, completed).also { current = it }
        }
        try {
            installWake(entry, lifetime = true, VaultThumbnailLimits.LIFETIME_MILLIS)
            execute(entry, onRejected = { synchronized(gate) { entry.preparing = false }; revoke(entry) }) {
                try { prepare(entry) }
                catch (_: Exception) { revoke(entry, true) }
                finally { synchronized(gate) { entry.preparing = false }; progress(entry) }
            }
        } catch (_: Exception) {
            synchronized(gate) { entry.preparing = false }
            revoke(entry, true)
        }
        return entry.thumbnailTicket
    }

    fun closeThumbnail(ticket: VaultThumbnailTicket) {
        synchronized(gate) { current?.takeIf { it.thumbnailTicket === ticket }?.also {
            it.thumbnailCancelled = true; it.displayRecord?.token?.revoke()
        } }?.let { revoke(it) }
    }

    fun revokeThumbnails(hostToken: VaultThumbnailHostToken? = null) {
        val retiring = synchronized(gate) {
            displays.filter { hostToken == null || it.ticket.host === hostToken }.also { values -> values.forEach { it.token.revoke() } }
        }
        synchronized(gate) { current?.takeIf { it.purpose == Purpose.THUMBNAIL &&
            (hostToken == null || it.thumbnailTicket?.host === hostToken) }?.also {
            it.thumbnailCancelled = true; it.displayRecord?.token?.revoke()
        } }?.let { revoke(it) }
        retiring.forEach(::closeDisplay)
    }

    /** The caller supplies an already-observed exact root cover/detach fact, never aggregate cleanup. */
    fun thumbnailHostCovered(hostToken: VaultThumbnailHostToken) {
        synchronized(gate) {
            current?.takeIf { it.thumbnailTicket?.host === hostToken }?.thumbnailCovered = true
        }
        val retiring = synchronized(gate) {
            displays.filter { it.ticket.host === hostToken }.also { values ->
                values.forEach { it.token.revoke(); it.visualRetired = true }
            }
        }
        retiring.forEach(::closeDisplay)
        synchronized(gate) { displays.removeAll { it.closed && it.visualRetired } }
        drained()
    }

    fun thumbnailWorkComplete(): Boolean = synchronized(gate) {
        current?.purpose != Purpose.THUMBNAIL && !thumbnailFence && displays.all { it.closed }
    }

    private fun prepareThumbnail(entry: Entry) {
        val owner = try {
            checkNotNull(thumbnailFactory).create(checkNotNull(entry.thumbnailTicket), readPort(entry),
                { stillLive(entry) }, { nativeStarting(entry) }, { result -> nativeCompleted(entry, result) }, { progress(entry) })
        } catch (error: Exception) {
            synchronized(gate) { entry.playerState = Acquisition.UNKNOWN; entry.failure = true }
            throw error
        }
        synchronized(gate) { entry.thumbnail = owner; entry.playerState = Acquisition.OWNED }
        if (!stillLive(entry)) { revoke(entry); return }
        // Main admission is shared with ordinary recording's check-and-admit operation.
        // The exact owner and handoff are retained before a direct/execute-then-throw UI lane.
        val claimed = AtomicBoolean(false)
        synchronized(gate) { entry.handoffs++ }
        try {
            ui.execute {
                if (!claimed.compareAndSet(false, true)) return@execute
                try {
                    if (stillLive(entry)) owner.start() else revoke(entry)
                } catch (_: Exception) {
                    synchronized(gate) { entry.playerState = Acquisition.UNKNOWN }
                    revoke(entry, true)
                } finally {
                    synchronized(gate) { check(entry.handoffs > 0); entry.handoffs-- }
                    progress(entry)
                }
            }
        } catch (_: Exception) {
            if (claimed.compareAndSet(false, true)) {
                synchronized(gate) { check(entry.handoffs > 0); entry.handoffs-- }
                revoke(entry, true)
            }
        }
        progress(entry)
    }

    private fun nativeStarting(entry: Entry): Boolean = synchronized(gate) {
        if (!stillLive(entry) || entry.thumbnail == null || entry.nativeStarted ||
            entry.session.ref in attempted || entry.session.ref in terminal ||
            (attempted + terminal).size >= VaultThumbnailLimits.MAX_IDENTITIES) return false
        entry.nativeStarted = true
        attempted.add(entry.session.ref)
        true
    }

    private fun beginThumbnailCallback(entry: Entry, byteRead: Boolean): VaultThumbnailReadCallback = synchronized(gate) {
        live(entry)
        if (entry.purpose != Purpose.THUMBNAIL || entry.callbackCount >= VaultThumbnailLimits.MAX_CALLBACKS ||
            byteRead && entry.byteCallback || entry.returnedBytes >= VaultThumbnailLimits.MAX_BYTES)
            throw safePlaybackIo("CALLBACK_REJECTED")
        entry.callbackCount++; entry.callbacks++
        if (byteRead) entry.byteCallback = true
        object : VaultThumbnailReadCallback {
            private var closed = false
            override val remainingBytes: Long = VaultThumbnailLimits.MAX_BYTES - entry.returnedBytes
            override fun close() {
                synchronized(gate) {
                    if (closed) return
                    closed = true; check(entry.callbacks > 0); entry.callbacks--
                    if (byteRead) entry.byteCallback = false
                }
                progress(entry)
            }
        }
    }

    private fun nativeCompleted(entry: Entry, result: VaultThumbnailResult) {
        var duplicate = false
        val orphan = synchronized(gate) {
            if (entry.resultClaimed) {
                duplicate = true
                if (result === entry.thumbnailResult || result.frame === entry.thumbnailResult?.frame) return
                result.frame
            } else {
                // Claim before calling close, posting Main, or any other external callback.
                entry.resultClaimed = true; entry.thumbnailResult = result
                entry.successfulReadRetirement = result.nativeReleased && !entry.revoked && !entry.failure && !entry.thumbnailCancelled
                val source = result.frame
                if (source != null) {
                    val token = VaultThumbnailDisplayToken { synchronized(gate) {
                        entry.session.isCurrent() && entry.ticket.host.isCurrent()
                    } }
                    DisplayRecord(checkNotNull(entry.thumbnailTicket), token, source).also {
                        it.visualRetired = entry.thumbnailCovered
                        if (entry.thumbnailCovered) it.token.revoke()
                        displays.add(it); entry.displayRecord = it; entry.frameReserved = false
                    }
                }
                null
            }
        }
        if (orphan != null) {
            // Only a never-adopted foreign frame is an orphan. An adopted duplicate is a no-op.
            try { orphan.close() } catch (_: Exception) {
                synchronized(gate) {
                    thumbnailFence = true
                    val token = VaultThumbnailDisplayToken { false }
                    displays.add(DisplayRecord(checkNotNull(entry.thumbnailTicket), token, orphan).also { it.closeStarted = true })
                }
            }
            return
        }
        if (duplicate) return
        if (!result.nativeReleased) synchronized(gate) { entry.failure = true }
        revoke(entry, !result.nativeReleased)
    }

    private fun readResourcesReleased(entry: Entry): Boolean = entry.revoked && !entry.preparing &&
        entry.reads == 0 && entry.permits == 0 && entry.callbacks == 0 &&
        entry.readerState in setOf(Acquisition.NONE, Acquisition.RELEASED) &&
        entry.fileState in setOf(Acquisition.NONE, Acquisition.RELEASED)

    private fun deliverThumbnail(entry: Entry) {
        val record = synchronized(gate) {
            if (current !== entry || entry.thumbnailDeliveryStarted || !readResourcesReleased(entry) || entry.transports != 0) return
            if (entry.playerState == Acquisition.UNKNOWN) return
            if (entry.thumbnail != null && !entry.resultClaimed) return
            val nativeDrain = try { entry.thumbnail?.observeDrain() } catch (_: Exception) { VaultPlaybackDrain.UNCONFIRMED }
            val nativeReleased = try { entry.thumbnail?.nativeReleaseObserved() ?: true } catch (_: Exception) { false }
            if (!nativeReleased || nativeDrain == VaultPlaybackDrain.UNCONFIRMED || entry.thumbnailResult?.nativeReleased == false) return
            entry.thumbnailDeliveryStarted = true; entry.handoffs++
            entry.displayRecord
        }
        val claimed = AtomicBoolean(false)
        try {
            ui.execute {
                if (!claimed.compareAndSet(false, true)) return@execute
                val valid = synchronized(gate) {
                    record != null && entry.successfulReadRetirement && !entry.failure && !entry.thumbnailCancelled && record.token.isCurrent()
                } && try {
                    record!!.source.width in 1..256 && record.source.height in 1..144 &&
                        record.source.width.toLong() * record.source.height <= 36_864L &&
                        record.source.allocationBytes in 1..VaultThumbnailLimits.MAX_FRAME_BYTES
                } catch (_: Exception) { false }
                if (!valid && record != null) closeDisplay(record)
                val frame = if (valid) RegisteredFrame(checkNotNull(record)) else null
                val result = if (record != null && !valid && !synchronized(gate) { record.closed })
                    ThumbnailDecodeResult.ReleaseUnconfirmed
                    else ThumbnailDecodeResult.Released(frame, ThumbnailUnavailable.DECODE_FAILED)
                val acknowledged = AtomicBoolean(false)
                val settled: () -> Unit = {
                    if (acknowledged.compareAndSet(false, true)) {
                        synchronized(gate) {
                            entry.resultSettled = true
                            if (record == null) entry.frameReserved = false
                        }
                        finishResultHandoff(entry)
                    }
                }
                try {
                    synchronized(gate) { entry.resultTransferred = true }
                    checkNotNull(entry.completion).invoke(result, settled)
                } catch (_: Exception) {
                    // An uncertain consumer may have adopted the frame. Retain its exact owner.
                    if (!acknowledged.get()) synchronized(gate) { thumbnailFence = true; entry.failure = true }
                } finally {
                    synchronized(gate) { entry.resultConsumerReturned = true }
                    finishResultHandoff(entry)
                }
            }
        } catch (_: Exception) {
            // A post that threw may still have queued its runnable. It may not run later as new work.
            if (claimed.compareAndSet(false, true)) synchronized(gate) { thumbnailFence = true; entry.failure = true }
        }
    }

    private fun finishResultHandoff(entry: Entry) {
        synchronized(gate) {
            if (!entry.resultSettled || !entry.resultConsumerReturned || entry.resultReceiptSent) return
            entry.resultReceiptSent = true
            check(entry.handoffs > 0); entry.handoffs--
        }
        try { entry.thumbnail?.resultHandoffSettled() }
        catch (_: Exception) { synchronized(gate) { entry.playerState = Acquisition.UNKNOWN; entry.failure = true } }
        progress(entry)
    }

    private inner class RegisteredFrame(private val record: DisplayRecord) : VaultThumbnailFrame {
        override val display: VaultThumbnailDisplayToken get() = record.token
        override val width: Int get() = record.source.width
        override val height: Int get() = record.source.height
        override val allocationBytes: Long get() = record.source.allocationBytes
        override fun drawIfCurrent(draw: (ThumbnailFrame) -> Unit) {
            synchronized(gate) {
                if (record.closed || record.closeStarted || !record.token.isCurrent()) return
                record.drawn = true; record.draws++
            }
            try {
                if (record.token.isCurrent()) draw(record.source)
            } finally {
                val close = synchronized(gate) {
                    check(record.draws > 0); record.draws--
                    record.draws == 0 && record.closeRequested
                }
                if (close) closeDisplay(record)
            }
        }
        override fun close() = closeDisplay(record)
    }

    private fun closeDisplay(record: DisplayRecord) {
        synchronized(gate) {
            record.token.revoke(); record.closeRequested = true
            if (record.closeStarted || record.draws != 0) return
            record.closeStarted = true
        }
        val closed = try { record.source.close(); true } catch (_: Exception) { false }
        synchronized(gate) {
            record.closed = closed
            if (!closed) thumbnailFence = true
            // Never-drawn frames have no submitted presentation obligation.
            if (closed && (!record.drawn || record.visualRetired)) displays.remove(record)
        }
        drained()
    }

    private fun settleThumbnail(entry: Entry) {
        val released = synchronized(gate) {
            if (current !== entry || !readResourcesReleased(entry) || entry.jobs != 0 || entry.handoffs != 0 ||
                entry.transports != 0 || entry.closeQueued || !entry.resultSettled || entry.frameReserved ||
                entry.playerState == Acquisition.UNKNOWN || thumbnailFence) return
            val nativeDrain = try { entry.thumbnail?.observeDrain() } catch (_: Exception) { VaultPlaybackDrain.UNCONFIRMED }
            if (entry.thumbnail != null && nativeDrain != VaultPlaybackDrain.RELEASED) return
            if (authentication.observeMediaDrain() != ExportRelease.RELEASED || store.observeMediaDrain() != ExportRelease.RELEASED) return
            entry.retirementWake?.cancel(); entry.retirementWake = null
            current = null
            true
        }
        if (released) drained()
    }

}
