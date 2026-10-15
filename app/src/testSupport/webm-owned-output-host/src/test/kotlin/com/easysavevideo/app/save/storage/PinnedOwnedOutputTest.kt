package com.easysavevideo.app.save.storage

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.StatsDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.common.Format
import androidx.media3.common.ColorInfo
import androidx.media3.muxer.BufferInfo
import androidx.media3.container.Mp4TimestampData
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import com.easysavevideo.app.media.transform.*
import com.easysavevideo.app.save.conversion.OwnedWebmInputBinding
import com.easysavevideo.app.save.conversion.WebmTransportFacts
import com.easysavevideo.app.editor.engine.EditorOwnedOutput
import com.easysavevideo.app.editor.engine.EditorTransformerTarget
import com.easysavevideo.app.storage.OwnedFileOutputStream
import com.easysavevideo.app.save.normalization.OriginalMovFixtures
import com.easysavevideo.app.save.normalization.MovProfileProbe
import com.easysavevideo.save.*
import com.easysavevideo.core.model.OperationStopReason
import com.easysavevideo.research.webm.OriginalWebmFixtures
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Pinned Java muxer + production registered storage/session. Fake syscalls, no native decoder. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object PinnedOwnedOutputTest {
    private var passed = 0
    private var failed = 0
    private val results = ArrayList<String>()
    @JvmStatic fun main(args: Array<String>) {
        val background = CopyOnWriteArrayList<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> background.add(error) }
        test("actual_admitted_muxer_code_source") {
            val base = File(requireNotNull(System.getProperty("esv.webm.output")))
            check(File(requireNotNull(requireNotNull(Mp4Muxer::class.java.protectionDomain).codeSource).location.toURI()).canonicalFile ==
                File(base, "jars/media3-muxer-1.11.1.jar").canonicalFile)
            listOf(StatsDataSource::class.java to "datasource", ProgressiveMediaExtractor::class.java to "exoplayer",
                MatroskaExtractor::class.java to "extractor").forEach { (type, artifact) ->
                check(File(requireNotNull(requireNotNull(type.protectionDomain).codeSource).location.toURI()).canonicalFile ==
                    File(base, "jars/media3-$artifact-1.11.1.jar").canonicalFile)
            }
            val main = File(requireNotNull(System.getProperty("esv.webm.mainClasses"))).canonicalFile
            listOf(OwnedDirectTranscodeAttempt::class.java, TransformerSession::class.java,
                TransformerBoundedMuxer::class.java).forEach { production ->
                check(File(requireNotNull(requireNotNull(production.protectionDomain).codeSource).location.toURI()).canonicalFile == main)
            }
            val tests = File(requireNotNull(System.getProperty("esv.webm.testClasses"))).canonicalFile
            check(File(requireNotNull(requireNotNull(OwnedTranscodeTestFixture::class.java.protectionDomain).codeSource).location.toURI()).canonicalFile == tests)
        }
        var normalEmittedBytes: ByteArray? = null
        for (short in listOf(false, true)) test("actual_muxer_same_owned_anchors_short_$short") {
            fixture { h ->
                val a = h.prepare()
                val originalRaw = h.f.io.handles.single { it.active }
                val pair = a.bindWebmInputOnce(); val probed = pair.probe(); val session = h.session()
                var observedBytes = 0L
                val result = h.entered(session) {
                    pair.createMediaSource(session)
                    val source = a.ownedSource(pair)
                    check(a.ownedSource(pair) === source)
                    session.retain(source)
                    if (short) h.f.io.maxRead = 31
                    val loadingBoundary = h.f.io.calls.size
                    val loaded = loadPaired(pair, probed.inputExtent)
                    check(loaded.inputExtent == probed.inputExtent && loaded.tracks.size == 1)
                    check(loaded.tracks.single().sourceSampleCallbacks == 3L)
                    check(loaded.tracks.single().firstSourceTimestampUs == probed.tracks.single().firstSourceTimestampUs)
                    check(loaded.tracks.single().lastSourceTimestampUs == probed.tracks.single().lastSourceTimestampUs)
                    check(a.ownedSource(pair) === source)
                    session.retain(source) // Idempotent: loader and muxer still share this exact registered session/source.
                    val loadingCalls = h.f.io.calls.drop(loadingBoundary)
                    check(loadingCalls.none { it.operation == "open" || it.operation == "close" })
                    check(loadingCalls.any { it.operation == "pread" && it.descriptor === originalRaw })
                    check(loadingCalls.filter { it.operation == "pread" }.all { it.descriptor === originalRaw })
                    check(originalRaw.active && !session.outcomeReleased)
                    val target = a.ownedTarget(pair)
                    check(a.ownedTarget(pair) === target)
                    if (short) h.f.io.maxWrite = 127
                    val factory = TransformerBoundedMuxer(spec(a.outputMaximum), target, session)
                    val muxer = factory.create(target.muxerToken)
                    // Host-only clock control: actual emitted files must be byte-comparable across seconds.
                    // Use the public admitted muxer API; production metadata filtering remains unchanged.
                    val actual = synchronized(session.retainedObjects) {
                        session.retainedObjects.filterIsInstance<Mp4Muxer>().single()
                    }
                    actual.addMetadataEntry(Mp4TimestampData(0L, 0L))
                    writeVideo(muxer)
                    muxer.close()
                    observedBytes = factory.evidence().bytesWritten
                    check(session.muxerReleased)
                    check(h.f.io.handles.count { it.active } == 2) // Logical close did not close either anchor.
                }
                if (result is TransformerOutcome.Failed) throw result.primary
                check(result is TransformerOutcome.Completed)
                check(session.outcomeReleased)
                val original = h.f.io.handles.filter { it.active }
                check(original.size == 2)
                val output = original.single { it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING }
                val raw = original.single { it.mode == NormalizationDescriptorIo.Open.READ }
                check(raw === originalRaw)
                check(output.path == h.f.outputFile(a.key))
                check(h.f.io.calls.count { it.operation == "open" && it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING } == 1)
                check(h.f.io.calls.filter { it.operation == "pwrite" }.all { it.descriptor === output })
                check(h.f.io.calls.any { it.operation == "fsync" && it.descriptor === output })
                if (short) requireShortContinuation(h.f.io.calls, "pwrite", output, 127)
                val bytes = h.f.io.bytes(output.path)
                check(bytes.size.toLong() == observedBytes)
                if (short) {
                    val normal = checkNotNull(normalEmittedBytes) { "Normal actual muxing must succeed before short-I/O comparison" }
                    check(bytes.size == normal.size && sha(bytes) == sha(normal) && bytes.contentEquals(normal)) {
                        "Short physical I/O must preserve the entire emitted MP4, including headers and tables"
                    }
                }
                val scanned = MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(bytes))
                val fixtureInput = OriginalMovFixtures.input(OriginalMovFixtures.build())
                val expected = MovProfileProbe.probeQuickTime(fixtureInput)
                check(scanned.samples.size == 3 && scanned.width == 16 && scanned.height == 16)
                check(scanned.audio == null && scanned.frameRate == 30)
                check(scanned.conservation == expected.conservation)
                scanned.samples.indices.forEach { index ->
                    check(MovProfileProbe.readCanonicalSample(OriginalMovFixtures.input(bytes), scanned, index)
                        .contentEquals(MovProfileProbe.readCanonicalSample(fixtureInput, expected, index)))
                }
                h.f.io.calls.clear()
                val sealed = a.sealResources()
                check(sealed.first.fingerprint.sha256 == sha(h.f.io.bytes(raw.path)))
                check(sealed.second.fingerprint.sha256 == sha(bytes))
                check(sealed.second.fingerprint.size == bytes.size.toLong())
                check(h.f.io.calls.filter { it.operation == "pread" }.all { it.descriptor === raw || it.descriptor === output })
                check(h.f.io.calls.none { it.operation == "open" })
                if (short) {
                    requireShortContinuation(h.f.io.calls, "pread", raw, 31)
                    requireShortContinuation(h.f.io.calls, "pread", output, 31)
                }
                check(h.f.io.calls.count { it.operation == "close" && it.descriptor === raw } == 1)
                check(h.f.io.calls.count { it.operation == "close" && it.descriptor === output } == 1)
                check(!raw.active && !output.active)
                check(h.token.stage == NormalizationIoReservations.Stage.TRANSCODE)
                checkNoProof(h)
                check(h.token.registered && !h.f.files.hasLeases(a.key))
                check(runCatching { a.sealResources() }.isFailure)
                a.close(); check(!h.f.attempts.hasAnyLeases())
                if (!short) normalEmittedBytes = bytes.copyOf()
            }
        }
        test("actual_muxer_cancel_finalizes_but_never_seals_success") {
            fixture { h ->
                val a = h.prepare(); val pair = a.bindWebmInputOnce(); pair.probe(); val session = h.session()
                var finalWrites = 0
                var finalForces = 0
                var finalizationChecked = false
                val result = h.entered(session) {
                    pair.createMediaSource(session); session.retain(a.ownedSource(pair))
                    val target = a.ownedTarget(pair)
                    val muxer = TransformerBoundedMuxer(spec(a.outputMaximum), target, session).create(target.muxerToken)
                    writeVideo(muxer)
                    h.f.cancellation.cancel(); a.close()
                    check(h.f.io.handles.count { it.active } == 2)
                    val output = h.f.io.handles.single { it.active && it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING }
                    h.f.io.after = { call ->
                        check(!Thread.holdsLock(h.f.lock))
                        if (call.descriptor === output && call.operation == "pwrite") finalWrites++
                        if (call.descriptor === output && call.operation == "fsync") finalForces++
                    }
                    val stopped = runCatching { muxer.close() }.exceptionOrNull()
                    check(stopped != null && cancellation(stopped))
                    check(session.muxerReleased && session.muxerContentFailed.get())
                    check(!session.cleanupFailed())
                    check(finalWrites > 0 && finalForces > 0)
                    check(runCatching { a.sealResources() }.isFailure)
                    finalizationChecked = true
                }
                check(finalizationChecked) { "Cancelled caller must not hide failed worker assertions" }
                check(result is TransformerOutcome.Failed)
                check(cancellation(result.primary) && result.release == TransformerRelease.CONFIRMED)
                a.close(); check(!h.f.attempts.hasAnyLeases())
                check(h.f.io.handles.none { it.active })
                checkNoProof(h)
            }
        }
        test("abandon_first_freezes_exact_finalization_deadline") {
            fixture { h ->
                val a = h.prepare(); val pair = a.bindWebmInputOnce(); pair.probe(); val session = h.session()
                var deadlineChecked = false
                val result = h.entered(session) {
                    pair.createMediaSource(session); session.retain(a.ownedSource(pair))
                    val target = a.ownedTarget(pair)
                    val muxer = TransformerBoundedMuxer(spec(a.outputMaximum), target, session).create(target.muxerToken)
                    writeVideo(muxer)
                    val beforeAbandon = System.nanoTime()
                    session.abandon() // Actual postCancel through the pinned host handler, no native Transformer.
                    val afterAbandon = System.nanoTime()
                    val first = requireNotNull(session.requireWriterFinalization(target))
                    val allowance = TimeUnit.MILLISECONDS.toNanos(session.limits.releaseTimeoutMillis)
                    check(first - beforeAbandon >= allowance && first - afterAbandon <= allowance)
                    Thread.sleep(5)
                    session.postCancel()
                    check(session.requireWriterFinalization(target) == first)
                    runCatching { muxer.close() }
                    check(session.muxerReleased)
                    check(session.requireWriterFinalization(target) == first)
                    deadlineChecked = true
                }
                check(deadlineChecked) { "Cancelled caller must not hide failed deadline assertions" }
                check(result is TransformerOutcome.Failed)
                check(cancellation(result.primary) && result.release == TransformerRelease.CONFIRMED)
                check(runCatching { a.sealResources() }.isFailure)
                a.close(); check(!h.f.attempts.hasAnyLeases())
                checkNoProof(h)
            }
        }
        test("caller_timeout_does_not_retire_original_cleaning_worker_or_fd") {
            fixture { h ->
                val a = h.prepare(); val pair = a.bindWebmInputOnce(); pair.probe()
                val session = TransformerSession(h.session().limits.copy(releaseTimeoutMillis = 50),
                    { h.f.checkOperation(h.f.context) }, { null })
                val lane = TransformerLaneOwner()
                val closing = CountDownLatch(1)
                val permitClose = CountDownLatch(1)
                val callerResult = CompletableFuture<TransformerOutcome<Unit>>()
                val originalWorker = AtomicReference<Thread>()
                val caller = Thread {
                    try {
                        callerResult.complete(executeTransformerSession(lane, session) {
                            originalWorker.set(Thread.currentThread())
                            pair.createMediaSource(session); session.retain(a.ownedSource(pair))
                            val target = a.ownedTarget(pair)
                            val muxer = TransformerBoundedMuxer(spec(a.outputMaximum), target, session).create(target.muxerToken)
                            writeVideo(muxer); muxer.close()
                            h.f.io.before = { call ->
                                check(!Thread.holdsLock(h.f.lock))
                                if (call.operation == "close" && call.path == h.f.outputFile(a.key)) {
                                    check(Thread.currentThread() === originalWorker.get())
                                    closing.countDown()
                                    check(permitClose.await(5, TimeUnit.SECONDS))
                                }
                            }
                            h.f.cancellation.cancel(); a.close()
                        })
                    } catch (failure: Throwable) { callerResult.completeExceptionally(failure) }
                }.apply { isDaemon = true; start() }
                try {
                    check(closing.await(5, TimeUnit.SECONDS))
                    val stopped = callerResult.get(5, TimeUnit.SECONDS)
                    check(stopped is TransformerOutcome.Failed && stopped.release == TransformerRelease.UNCONFIRMED)
                    check(cancellation(stopped.primary))
                    check(lane.fence.requiresRestart && lane.fence.occupied)
                    val output = h.f.io.handles.single { it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING }
                    check(output.active)
                    check(h.f.io.calls.count { it.operation == "close" && it.descriptor === output } == 1)
                    check(!session.outcomeReleased)
                    check(h.f.attempts.hasAnyLeases())
                    permitClose.countDown()
                    lane.lane.shutdown()
                    check(lane.lane.awaitTermination(5, TimeUnit.SECONDS))
                    caller.join(1000); check(!caller.isAlive)
                    check(callerResult.get() === stopped) // Late drain cannot rewrite the returned failed outcome.
                    check(session.outcomeReleased)
                    check(lane.fence.requiresRestart && lane.fence.occupied)
                    check(h.f.io.handles.none { it.active })
                    check(h.f.io.calls.count { it.operation == "close" && it.descriptor === output } == 1)
                    check(runCatching { a.sealResources() }.isFailure)
                    a.close(); check(!h.f.attempts.hasAnyLeases())
                    checkNoProof(h)
                } finally {
                    permitClose.countDown(); lane.lane.shutdown()
                    check(lane.lane.awaitTermination(5, TimeUnit.SECONDS))
                    caller.join(1000)
                }
            }
        }
        test("actual_muxer_force_error_does_not_become_success_or_fake_fd_uncertainty") {
            fixture { h ->
                val a = h.prepare(); val pair = a.bindWebmInputOnce(); pair.probe(); val session = h.session()
                val failure = IOException("HOST_OUTPUT_FORCE")
                var forceCalls = 0
                val result = h.entered(session) {
                    pair.createMediaSource(session); session.retain(a.ownedSource(pair))
                    val target = a.ownedTarget(pair)
                    val muxer = TransformerBoundedMuxer(spec(a.outputMaximum), target, session).create(target.muxerToken)
                    writeVideo(muxer)
                    h.f.io.before = {
                        check(!Thread.holdsLock(h.f.lock))
                        if (it.operation == "fsync" && it.path == h.f.outputFile(a.key)) { forceCalls++; throw failure }
                    }
                    val closeFailure = try { runCatching { muxer.close() }.exceptionOrNull() }
                    finally { h.f.io.before = { check(!Thread.holdsLock(h.f.lock)) } }
                    check(forceCalls == 1 && closeFailure != null && contains(closeFailure, failure))
                    check(session.muxerReleased && session.muxerContentFailed.get())
                    check(!session.cleanupFailed())
                    throw requireNotNull(closeFailure)
                }
                check(result is TransformerOutcome.Failed && result.release == TransformerRelease.CONFIRMED)
                check(contains(result.primary, failure) && forceCalls == 1)
                check(runCatching { a.sealResources() }.isFailure)
                a.close(); check(!h.f.attempts.hasAnyLeases())
                check(!h.f.attempts.releaseUnconfirmed())
                check(h.f.io.handles.none { it.active })
                checkNoProof(h)
            }
        }
        for (cancel in listOf(false, true)) test("editor_real_fd_adapter_muxer_cancel_$cancel") {
            editor(cancel)
        }
        test("background_handler_drained_without_uncaught_failure") {
            val drained = CountDownLatch(1)
            check(SharedTransformerRuntime.handler.post { drained.countDown() })
            check(drained.await(5, TimeUnit.SECONDS))
            check(android.os.HandlerThread.drainAll(5_000))
            background.forEach { it.printStackTrace() }
            check(background.isEmpty()) { background.toString() }
        }
        val out = File(requireNotNull(System.getProperty("esv.webm.output")))
        File(out, "results.json").writeText("{\"tests\":${passed + failed},\"passed\":$passed,\"failures\":$failed,\"nativeDecodeTested\":false,\"directIssued\":false,\"results\":[${results.joinToString(",")}]}")
        println("RESULT tests=${passed + failed} failures=$failed nativeDecodeTested=false directIssued=false")
        check(failed == 0)
    }
    /** Existing private pair factories and opaque route only; no replacement reader or parser. */
    private fun loadPaired(pair: OwnedWebmInputBinding, extent: Long): WebmTransportFacts {
        val uri = Uri.parse(pair.javaClass.getDeclaredField("route").apply { isAccessible = true }.get(pair) as String)
        val source = pairFactory<DataSource>(pair, "newDataSource")
        val stats = StatsDataSource(source)
        val adapter = pairFactory<ProgressiveMediaExtractor>(pair, "newExtractor")
        val spec = DataSpec.Builder().setUri(uri).setFlags(6)
            .setHttpRequestHeaders(mapOf("Icy-MetaData" to "1")).build()
        try {
            check(stats.open(spec) == extent)
            adapter.init(stats, uri, emptyMap(), 0, extent, DiscardLoadedPackets)
            check(stats.bytesRead > 0 && adapter.currentInputPosition == 0L) // Actual sniff reads, then peek reset.
            adapter.seek(0, 0)
            val holder = PositionHolder()
            while (true) {
                val result = adapter.read(holder)
                if (result == Extractor.RESULT_END_OF_INPUT) break
                check(result == Extractor.RESULT_CONTINUE)
            }
            check(stats.bytesRead == extent && adapter.currentInputPosition == extent)
            stats.close()
            return pair.completedTransportFacts().also { check(it.inputExtent == extent) }
        } finally {
            try { stats.close() } finally { adapter.release() }
        }
    }
    @Suppress("UNCHECKED_CAST") private fun <T> pairFactory(pair: OwnedWebmInputBinding, name: String): T = try {
        pair.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(pair) as T
    } catch (failure: InvocationTargetException) { throw requireNotNull(failure.cause) }
    private object DiscardLoadedPackets : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            private val scratch = ByteArray(4096)
            override fun format(format: Format) = Unit
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
                input.read(scratch, 0, minOf(scratch.size, length))
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }
    /** Actual Editor adapter and host FD; fixture-issued output is not Android storage admission. */
    private fun editor(cancel: Boolean) {
        val base = File(requireNotNull(System.getProperty("esv.webm.output"))).canonicalFile
        val file = File(base, "editor-cancel-$cancel.mp4")
        check(file.createNewFile())
        fun attributes() = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val originalKey = requireNotNull(attributes().fileKey())
        val stopped = AtomicBoolean(false)
        val limits = TransformerSessionLimits(30_000, 30_000, 2_000, 8, 4, 32L * 1024 * 1024 * 1024, 1, 2, 1)
        val session = TransformerSession(limits,
            { if (stopped.get()) throw TransformerStopped(OperationStopReason.CANCELLED) }, { null })
        val lane = TransformerLaneOwner()
        val opens = AtomicInteger()
        val logicalReleases = AtomicInteger()
        val nativePins = AtomicInteger()
        val nativeReleases = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val ownershipLock = Any()
        var stream: OwnedFileOutputStream? = null
        lateinit var target: EditorTransformerTarget
        val issued = EditorOwnedOutput(file, openWriter = {
            check(opens.getAndIncrement() == 0 && file.length() == 0L)
            OwnedFileOutputStream(file, ownershipLock = ownershipLock, onReleased = {
                check(!requireNotNull(stream).fd.valid())
                check(logicalReleases.incrementAndGet() == 1)
            }, onUnconfirmed = { error("HOST_EDITOR_FD_RELEASE_UNCONFIRMED") }).also { stream = it }
        }, retainNativeWriter = {
            session.requireEntered()
            check(nativePins.incrementAndGet() == 1)
            val released = AtomicBoolean()
            AutoCloseable {
                session.requireWriterRelease(target)
                check(Thread.currentThread() === worker.get())
                check(released.compareAndSet(false, true))
                check(nativePins.decrementAndGet() == 0)
                check(nativeReleases.incrementAndGet() == 1)
            }
        }, verifyIdentity = {
            val observed = attributes()
            check(file.canonicalFile.parentFile == base && observed.isRegularFile && observed.fileKey() == originalKey)
        }, availableBytes = { 64L * 1024 * 1024 })
        target = EditorTransformerTarget(issued)
        var finalizationChecked = false
        try {
            val result = executeTransformerSession(lane, session) {
                worker.set(Thread.currentThread())
                val muxer = TransformerBoundedMuxer(spec(64L * 1024 * 1024), target, session).create(target.muxerToken)
                writeVideo(muxer)
                val beforeTables = file.readBytes()
                check(nativePins.get() == 1 && requireNotNull(stream).fd.valid())
                if (cancel) { stopped.set(true); session.abandon() }
                muxer.close()
                check(!file.readBytes().contentEquals(beforeTables)) // Real final table/extent changes, after stop when cancelled.
                check(session.muxerReleased && !session.cleanupFailed())
                check(!requireNotNull(stream).fd.valid() && !requireNotNull(stream).channel.isOpen)
                check(logicalReleases.get() == 1 && nativePins.get() == 1 && nativeReleases.get() == 0)
                check(runCatching { session.requireWriterRelease(target) }.isFailure)
                finalizationChecked = true
                session.checkpoint() // Cancellation must remain the actual worker's failure.
            }
            check(finalizationChecked) { "Stopped caller must not hide failed Editor finalization assertions" }
            if (cancel) {
                check(result is TransformerOutcome.Failed && result.release == TransformerRelease.CONFIRMED)
                check(cancellation(result.primary))
            } else if (result is TransformerOutcome.Failed) throw result.primary
            else check(result is TransformerOutcome.Completed)
            check(session.outcomeReleased && !session.cleanupFailed())
            check(opens.get() == 1 && logicalReleases.get() == 1 && nativePins.get() == 0 && nativeReleases.get() == 1)
            check(!requireNotNull(stream).fd.valid() && !requireNotNull(stream).channel.isOpen)
            val expected = MovProfileProbe.probeQuickTime(OriginalMovFixtures.input(OriginalMovFixtures.build()))
            val scanned = MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(file.readBytes()))
            check(scanned.conservation == expected.conservation)
        } finally {
            lane.lane.shutdown()
            check(lane.lane.awaitTermination(5, TimeUnit.SECONDS))
            stream?.close()
        }
    }
    private fun fixture(action: (OwnedTranscodeTestFixture) -> Unit) {
        val raw = OriginalWebmFixtures.file(false, 8, 0, 3)
        val fixture = OwnedTranscodeTestFixture(raw.size)
        fixture.f.io.mutate(fixture.f.primary, raw, preserveTimes = true)
        fixture.run { h, _ -> action(h) }
    }
    private fun spec(maximum: Long) = TransformerOutputSpec(16, 16, 30, 100_000L,
        33_334, 0, 3, null, maximum, 0, 256_000, 0)
    private fun writeVideo(muxer: Muxer) {
        val bytes = OriginalMovFixtures.build()
        val raw = OriginalMovFixtures.input(bytes)
        val profile = MovProfileProbe.probeQuickTime(raw)
        val prefix = byteArrayOf(0, 0, 0, 1)
        val track = muxer.addTrack(Format.Builder().setSampleMimeType("video/avc").setWidth(16).setHeight(16)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .setInitializationData(listOf(prefix + profile.configuration.sps, prefix + profile.configuration.pps)).build())
        profile.samples.indices.forEach { index ->
            val sample = DirectMovCanonical.avcNals(MovProfileProbe.readCanonicalSample(raw, profile, index), DirectMovNalFraming.AVCC4)
                .fold(byteArrayOf()) { acc, nal -> acc + prefix + nal }
            muxer.writeSampleData(track, ByteBuffer.wrap(sample), BufferInfo(profile.samples[index].presentationTimeUs,
                sample.size, if (index == 0) C.BUFFER_FLAG_KEY_FRAME else 0))
        }
    }
    private fun requireShortContinuation(calls: List<MemoryNormalizationDescriptorIo.Call>, operation: String,
        descriptor: MemoryNormalizationDescriptorIo.Descriptor, maximum: Int) {
        val selected = calls.filter { it.operation == operation && it.descriptor === descriptor }
        check(selected.zipWithNext().any { (first, next) ->
            first.count > maximum && next.position == first.position + maximum && next.count == first.count - maximum
        }) { "Expected actual $operation short-progress continuation on the same retained descriptor" }
    }
    private fun cancellation(failure: Throwable): Boolean =
        (failure is SaveFailure && failure.code == SaveError.CANCELLED) ||
            (failure is TransformerStopped && failure.reason == OperationStopReason.CANCELLED) ||
            (failure.cause?.takeIf { it !== failure }?.let(::cancellation) == true)
    private fun contains(failure: Throwable, exact: Throwable): Boolean =
        failure === exact || (failure.cause?.takeIf { it !== failure }?.let { contains(it, exact) } == true) ||
            failure.suppressed.any { it !== failure && contains(it, exact) }
    private fun checkNoProof(h: OwnedTranscodeTestFixture) {
        check(h.f.ledger.row!!.lifecycle == NormalizationLifecycle.WRITING)
        check(h.f.ledger.row!!.output == null && h.f.ledger.row!!.proofSha256 == null)
        check(h.f.record!!.directConversion == null && h.f.record!!.directNormalization == null)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun test(name: String, block: () -> Unit) {
        try { block(); passed++; results.add("{\"name\":\"$name\",\"passed\":true}"); println("PASS $name") }
        catch (error: Throwable) { failed++; results.add("{\"name\":\"$name\",\"passed\":false}"); System.err.println("FAIL $name"); error.printStackTrace() }
    }
}
