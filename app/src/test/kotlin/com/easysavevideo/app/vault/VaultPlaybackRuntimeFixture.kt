package com.easysavevideo.app.vault

import com.easysavevideo.app.vault.crypto.VaultControlledMediaReader
import com.easysavevideo.core.model.VaultRetryLedger
import com.easysavevideo.library.export.ExportRelease
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Fixed public bytes and fake platform/store/auth ports. No Android/credentials/Keystore/SQL. */
internal class VaultPlaybackRuntimeFixture(val size: Int = 512) {
    class Lane : Executor {
        private val tasks = ArrayDeque<Runnable>()
        var reject = false
        override fun execute(command: Runnable) { synchronized(tasks) { if (reject) throw java.util.concurrent.RejectedExecutionException(); tasks.add(command) } }
        fun one() { synchronized(tasks) { tasks.removeFirst() }.run() }
        fun drain() { var count = 0; while (synchronized(tasks) { tasks.isNotEmpty() }) { check(++count < 500); one() } }
        fun size() = synchronized(tasks) { tasks.size }
    }
    inner class Timers : VaultWakeScheduler {
        inner class Wake(val deadline: Long, val task: () -> Unit) { var cancelled = false; fun fireEvenIfCancelled() = task() }
        val entries = mutableListOf<Wake>()
        override fun schedule(delayMillis: Long, task: () -> Unit): VaultWakeCancellation {
            val wake = Wake(now + delayMillis, task); entries += wake
            return VaultWakeCancellation { wake.cancelled = true }
        }
        fun at(deadline: Long) = entries.last { it.deadline == deadline }
    }
    val gate = Any()
    val work = Lane(); val ui = Lane(); val timers = Timers()
    var now = 10L
    val startedAt = now
    var sessionLive = true; var hostLive = true; var revealed = true
    var exportBusy = false
    val hostToken = VaultPlaybackHostToken(1) { synchronized(gate) { hostLive } }
    val bytes = ByteArray(size) { (it and 255).toByte() }
    val ref = VaultMediaReadRef("fixed_vault", "fixed_object", 1, 1)
    val root = object : VaultRootCapability { override fun close() = Unit }
    private val cipher = VaultCiphertext(byteArrayOf(7))
    val snapshot = VaultStoredSnapshot(VaultPersistentIdentity("fixed_vault", "fixed_install", "fixed_operation", "fixed_device", "fixed_biometric"),
        VaultStorePhase.READY, 3, VaultStagedData(listOf(VaultSlotRecord("fixed_password", 1, VaultSlotKind.PASSWORD, cipher, true)),
            cipher, cipher, 1, cipher, VaultRetryLedger(0, 0, -1, null), cipher))
    var files = 0; var readers = 0; var factories = 0; var fileCloses = 0; var channelCloses = 0
    var authUnknown = false; var storeUnknown = false
    var fileClosed = true; var readerClosed = true; var failFileClose = false; var failReaderClose = false
    var failFileFactory = false; var failReaderFactory = false; var failPlatformFactory = false
    var beforeFile: (() -> Unit)? = null; var beforeReaderReturn: (() -> Unit)? = null; var beforeFactoryReturn: (() -> Unit)? = null
    var beforeRead: (() -> Unit)? = null
    var operation: VaultOperationContext? = null
    val contexts = mutableListOf<VaultOperationContext>()
    lateinit var controlledReader: VaultMediaReadCapability
    lateinit var port: VaultPlaybackReadPort
    lateinit var callback: (VaultPlaybackView) -> Unit
    lateinit var ticket: VaultPlaybackTicket
    var nativeDrain = VaultPlaybackDrain.LIVE
    var readRetirementReady = true
    var readRetiredSignals = 0
    lateinit var nativeProgress: () -> Unit
    var beforeOffer: (() -> Unit)? = null
    var hostDrain = VaultPlaybackDrain.RELEASED
    var offers = 0; var hostRevokes = 0; var revokes = 0; var plays = 0; var pauses = 0; val seeks = mutableListOf<Long>()
    val views = mutableListOf<VaultPlaybackView>()
    var drains = 0
    var failView = false
    val file = object : VaultOwnedCiphertext {
        override val operationId = "fixed_operation"
        override val identity = ref.identity
        override fun append(bytes: ByteArray, offset: Int, length: Int) = error("no write")
        override fun readAt(position: Long, bytes: ByteArray, offset: Int, length: Int) = error("fake auth owns public plaintext fixture")
        override fun size() = size.toLong()
        override fun forceAndCloseWrite() = error("no write")
        override fun closeAndObserve(): ExportRelease {
            fileCloses++
            if (failFileClose) return ExportRelease.UNCONFIRMED
            fileClosed = true; return ExportRelease.RELEASED
        }
    }
    val player = object : VaultPreparedPlayback {
        override fun play() { if (sessionLive && hostLive) plays++ }
        override fun pause() { if (sessionLive && hostLive) pauses++ }
        override fun seekTo(positionMillis: Long) { if (sessionLive && hostLive) seeks += positionMillis }
        override fun attachSurface(surface: VaultPlaybackSurface) = Unit
        override fun detachSurface(surface: VaultPlaybackSurface, detached: (Boolean) -> Unit) = detached(true)
        override fun revoke() { revokes++; if (nativeDrain == VaultPlaybackDrain.LIVE) nativeDrain = VaultPlaybackDrain.RETIRING }
        override fun readyForReadRetirement() = readRetirementReady
        override fun readOwnerRetired() { readRetiredSignals++ }
        override fun observeDrain() = nativeDrain
    }
    inline fun <reified T> fake(crossinline f: (String, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> f(method.name, args ?: emptyArray()) } as T
    val auth: VaultAuthenticationPort = fake { method, args -> when (method) {
        "observeMediaDrain" -> if (!readerClosed || authUnknown) ExportRelease.UNCONFIRMED else ExportRelease.RELEASED
        "openMediaRead" -> {
            readers++; operation = args[5] as VaultOperationContext
            if (failReaderFactory) throw IllegalStateException("sensitive /secret/reader.mp4")
            readerClosed = false
            val ownerContext = operation!!
            var position = 0
            val channel = object : SeekableByteChannel {
                private var open = true
                override fun isOpen() = open
                override fun close() { channelCloses++; if (failReaderClose) throw IllegalStateException("sensitive close"); open = false }
                override fun size() = size.toLong()
                override fun position() = position.toLong()
                override fun position(newPosition: Long): SeekableByteChannel { position = newPosition.toInt(); return this }
                override fun read(dst: ByteBuffer): Int {
                    beforeRead?.invoke()
                    if (position == size) return -1
                    val count = minOf(dst.remaining(), size - position); dst.put(bytes, position, count); position += count; return count
                }
                override fun write(src: ByteBuffer) = error("no write")
                override fun truncate(size: Long): SeekableByteChannel = error("no write")
            }
            val actual = VaultControlledMediaReader(size.toLong(), channel, args[4] as VaultMediaPublicationGate,
                ownerContext, { ownerContext.checkActive() }, { readerClosed = true })
            actual.authenticateFinalSegment(ownerContext)
            controlledReader = object : VaultMediaReadCapability by actual {
                override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int, context: VaultOperationContext): Int {
                    contexts += context; return actual.readAt(position, destination, offset, length, context)
                }
            }
            beforeReaderReturn?.invoke()
            controlledReader
        }
        else -> error("Unexpected authentication method $method")
    } }
    val store: VaultStorePort = fake { method, _ -> when (method) {
        "observeMediaDrain" -> if (!fileClosed || storeUnknown) ExportRelease.UNCONFIRMED else ExportRelease.RELEASED
        "openMediaFile" -> { files++; beforeFile?.invoke(); if (failFileFactory) throw IllegalStateException("sensitive file path"); fileClosed = false; file }
        else -> error("Unexpected store method $method")
    } }
    val runtime = VaultPlaybackRuntime(gate, auth, store, VaultMonotonicClock { now }, work, ui, timers,
        VaultPlaybackPlatformFactory { value, read, _, update, progress ->
            factories++; ticket = value; port = read; callback = update; nativeProgress = progress
            beforeFactoryReturn?.invoke()
            if (failPlatformFactory) throw IllegalStateException("sensitive player path")
            player
        }, object : VaultPlaybackHostPort {
            override fun offerPrepared(ticket: VaultPlaybackTicket, prepared: VaultPreparedPlayback) { beforeOffer?.invoke(); offers++; hostDrain = VaultPlaybackDrain.LIVE }
            override fun revokePresentation(ticket: VaultPlaybackTicket) { hostRevokes++; if (hostDrain == VaultPlaybackDrain.LIVE) hostDrain = VaultPlaybackDrain.RETIRING }
            override fun observeRetirement(ticket: VaultPlaybackTicket) = hostDrain
        }, { request, host ->
            if (!sessionLive || !revealed || exportBusy || host !== hostToken || request != ref) null
            else VaultPlaybackSession(root, snapshot, ref) { sessionLive && revealed && !exportBusy }
        }, { if (failView) throw IllegalStateException("FIXED_VIEW_FAILURE"); views += it }, { drains++ })

    fun open(): Boolean = runtime.open(ref, hostToken)
    fun pump() { work.drain(); ui.drain(); work.drain(); ui.drain() }
    fun ready() { callback(VaultPlaybackView(ticket, VaultPlaybackStatus.READY, durationMillis = 100_000, seekable = true)); ui.drain() }
    fun completePlatformDrain() { nativeDrain = VaultPlaybackDrain.RELEASED; hostDrain = VaultPlaybackDrain.RELEASED; runtime.hostProgressed(); pump() }
}
