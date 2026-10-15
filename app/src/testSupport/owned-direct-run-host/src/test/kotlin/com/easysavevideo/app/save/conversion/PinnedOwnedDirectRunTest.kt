package com.easysavevideo.app.save.conversion

import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.*
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.StatsDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.transformer.*
import com.easysavevideo.app.media.transform.*
import com.easysavevideo.app.save.storage.*
import com.easysavevideo.research.webm.OriginalWebmFixtures as F
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import com.google.common.collect.ImmutableMap
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import sun.misc.Unsafe

/**
 * Actual pinned SequenceAssetLoader and ExoPlayerAssetLoader.PlayerListener, actual ESV private
 * listeners, registered input/output boundaries and real runner cleanup. Android native creation
 * is deliberately absent. Reflection installs only captured identities for callback negatives;
 * it never writes failure, outcome, EOF, observer or resource-success fields.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
object PinnedOwnedDirectRunTest {
    private var passed = 0
    private var failed = 0
    private val records = ArrayList<String>()
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    private fun engine() = unsafe.allocateInstance(TransformerEngine::class.java) as TransformerEngine
    @JvmStatic fun main(args: Array<String>) {
        val backgroundFailures = CopyOnWriteArrayList<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> backgroundFailures.add(failure) }
        test("pinned_callback_binary_origins") {
            pinned(Class.forName("androidx.media3.transformer.SequenceAssetLoader"), "transformer")
            pinned(Class.forName("androidx.media3.transformer.ExoPlayerAssetLoader\$PlayerListener"), "transformer")
            pinned(ExportException::class.java, "transformer")
        }
        for (audio in listOf(false, true)) test("actual_sequence_callback_rebuild_audio_$audio") {
            val media = MediaItem.Builder().setUri("esv-webm-owned:host").setMimeType("video/webm").build()
            val initial = EditedMediaItem.Builder(media).build()
            val item = actualSequenceCallback(initial, audio)
            check(item !== initial && item.mediaItem === media)
            check(item.removeAudio == !audio && !item.removeVideo)
            check(item.durationUs == C.TIME_UNSET && item.frameRate == C.RATE_UNSET_INT)
            call<Unit>(engine(), "validateOwnedDirectItem", item, media, audio)
        }
        for (changed in listOf("clone", "foreign", "remove_video", "remove_audio", "duration", "rate", "flatten", "speed"))
            test("actual_callback_shape_rejects_$changed") {
                val media = MediaItem.Builder().setUri("esv-webm-owned:host").setMimeType("video/webm").build()
                val accepted = actualSequenceCallback(EditedMediaItem.Builder(media).build(), false)
                val changedItem = when (changed) {
                    "clone" -> EditedMediaItem.Builder(media.buildUpon().build()).setRemoveAudio(true).build()
                    "foreign" -> EditedMediaItem.Builder(MediaItem.Builder().setUri("esv-webm-owned:host").setMimeType("video/webm").build()).setRemoveAudio(true).build()
                    "remove_video" -> accepted.buildUpon().setRemoveAudio(false).setRemoveVideo(true).build()
                    "remove_audio" -> accepted.buildUpon().setRemoveAudio(false).build()
                    "duration" -> accepted.buildUpon().setDurationUs(100_000).build()
                    "rate" -> accepted.buildUpon().setFrameRate(30).build()
                    "flatten" -> accepted.buildUpon().setFlattenForSlowMotion(true).build()
                    else -> accepted.buildUpon().setSpeed(SpeedParameters(androidx.media3.common.audio.SpeedProvider.DEFAULT, true)).build()
                }
                val error = caught { call<Unit>(engine(), "validateOwnedDirectItem", changedItem, media, false) }
                check(error is TransformerFailure && error.code == TransformerError.INVALID_REQUEST)
            }
        for (code in listOf(SaveError.CANCELLED, SaveError.DEADLINE_EXCEEDED)) {
            test("registered_input_${code.name}_through_async_media3_wrapper") {
                inputFailure(SaveFailure(code), identity = false, lateCompleted = false, cleanupFailed = false)
            }
        }
        test("registered_input_plain_io_original_through_async_media3_wrapper") { inputFailure(IOException("HOST_READ"), false, false, false) }
        for (deadline in listOf(false, true)) for (output in listOf(false, true))
            test("blocked_io_stop_priority_deadline_${deadline}_output_$output") { blockedIoStop(deadline, output) }
        for (kind in listOf("known_io", "cancelled", "deadline", "unknown_open"))
            test("output_open_primary_before_revoked_checkpoint_$kind") { outputOpenFailure(kind) }
        test("native_primary_survives_later_output_finalization_io") { nativeThenFinalizationFailure() }
        for (kind in listOf("raw_io", "output_io", "raw_cancelled", "raw_deadline", "output_cancelled", "output_deadline",
            "output_progress", "unknown_open", "unknown_open_cancelled", "unknown_open_deadline", "raw_close", "native_then_mutation"))
            test("original_precedes_poison_visible_poll_$kind") { poisonWindow(kind) }
        test("actual_direct_completion_dependents_run_outside_attempt_lock") { completionOutsideAttemptLock() }
        test("registered_input_identity_through_async_media3_wrapper") { inputFailure(null, identity = true, lateCompleted = false, cleanupFailed = false) }
        test("late_completed_cannot_erase_registered_input_original") { inputFailure(SaveFailure(SaveError.CANCELLED), false, true, false) }
        test("original_and_unconfirmed_cleanup_are_independent") { inputFailure(SaveFailure(SaveError.DEADLINE_EXCEEDED), false, false, true) }
        for (kind in listOf("storage_full", "identity", "force")) test("registered_output_${kind}_through_async_media3_wrapper") { outputFailure(kind) }
        for (editor in listOf(false, true)) {
            test("native_unsupported_mapping_editor_$editor") { nativeMapping(editor, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, TransformerError.UNSUPPORTED_PROFILE) }
            test("unregistered_cause_is_not_original_editor_$editor") { nativeMapping(editor, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, TransformerError.EXECUTION_FAILED) }
        }
        test("all_host_callbacks_drained") {
            check(HandlerThread.drainAll(5_000)); check(backgroundFailures.isEmpty())
        }
        val out = File(requireNotNull(System.getProperty("esv.webm.output")))
        File(out, "results.json").writeText("{\"passed\":$passed,\"failed\":$failed,\"androidNativeTested\":false,\"successfulDirectRunTested\":false,\"results\":[${records.joinToString(",")}]}")
        println("OWNED_DIRECT_PINNED_HOST passed=$passed failed=$failed androidNativeTested=false successfulDirectRunTested=false")
        check(failed == 0)
    }

    private fun actualSequenceCallback(initial: EditedMediaItem, audio: Boolean): EditedMediaItem {
        val types = if (audio) setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) else setOf(C.TRACK_TYPE_VIDEO)
        val sequence = EditedMediaItemSequence.Builder(types).addItem(initial).build()
        var callback: EditedMediaItem? = null
        val factory = AssetLoader.Factory { item, _, _, _ ->
            check(callback == null); callback = item
            object : AssetLoader {
                override fun start() = Unit
                override fun getProgress(holder: ProgressHolder) = Transformer.PROGRESS_STATE_NOT_STARTED
                override fun getDecoderNames(): ImmutableMap<Int, String> = ImmutableMap.of()
                override fun release() = Unit
            }
        }
        val type = Class.forName("androidx.media3.transformer.SequenceAssetLoader")
        val ctor = type.getDeclaredConstructor(EditedMediaItemSequence::class.java, AssetLoader.Factory::class.java,
            AssetLoader.CompositionSettings::class.java, AssetLoader.Listener::class.java, Clock::class.java, android.os.Looper::class.java)
        ctor.isAccessible = true
        val instance = ctor.newInstance(sequence, factory, AssetLoader.CompositionSettings(0, false), emptyListener(), Clock.DEFAULT, android.os.Looper.getMainLooper()) as AssetLoader
        instance.release()
        return requireNotNull(callback)
    }
    private fun inputFailure(injected: Throwable?, identity: Boolean, lateCompleted: Boolean, cleanupFailed: Boolean) {
        withBound { h, attempt, pair, session, engine ->
            var original: Throwable? = null
            val result = h.entered(session) {
                val source = pair.createMediaSource(session)
                val ds = call<DataSource>(pair, "newDataSource")
                val stats = StatsDataSource(ds)
                val extractor = call<ProgressiveMediaExtractor>(pair, "newExtractor")
                val extent = field<WebmInputBinding>(pair, "shared").extent
                val uri = source.mediaItem.localConfiguration!!.uri
                stats.open(DataSpec.Builder().setUri(uri).setFlags(6).setHttpRequestHeaders(mapOf("Icy-MetaData" to "1")).build())
                if (identity) h.f.io.file(h.f.primary, h.f.io.bytes(h.f.primary))
                else h.f.io.before = { if (it.operation == "pread") throw requireNotNull(injected) }
                try {
                    original = caught { extractor.init(stats, uri, emptyMap(), 0, extent, Discard) }
                    if (injected != null) check(original === injected)
                    check(attempt.directOriginalFailure(engine, session) === original)
                    val listener = call<Transformer.Listener>(engine, "directListener", attempt, session)
                    asyncWrappedError(listener, requireNotNull(original), lateCompleted = lateCompleted)
                    if (cleanupFailed) session.closeFailed.set(true)
                    session.completion.get(5, TimeUnit.SECONDS) // The actual listener/future, not a direct throw of original.
                    error("Expected exceptional completion")
                } finally {
                    h.f.io.before = {}; stats.close(); extractor.release()
                }
            }
            check(result is TransformerOutcome.Failed && result.primary === original)
            check(result.release == if (cleanupFailed) TransformerRelease.UNCONFIRMED else TransformerRelease.CONFIRMED)
        }
    }
    private fun outputFailure(kind: String) {
        withBound { h, attempt, pair, session, engine ->
            var original: Throwable? = null
            val result = h.entered(session) {
                pair.createMediaSource(session)
                // Same actual session owns both the source pin and one output writer.
                session.retain(attempt.ownedSource(pair))
                val target = attempt.ownedTarget(pair)
                val writer = target.openEmptyWriter(); session.retainWriter(target)
                try {
                    when (kind) {
                        "storage_full" -> h.f.available = 0
                        "identity" -> h.f.io.file(h.f.outputFile(attempt.key), byteArrayOf())
                        "force" -> { writer.write(ByteBuffer.wrap(byteArrayOf(1))); writer.beginFinalization()
                            h.f.io.before = { if (it.operation == "fsync") throw IOException("HOST_FORCE") } }
                    }
                    original = caught { if (kind == "force") writer.force() else writer.write(ByteBuffer.wrap(byteArrayOf(1))) }
                    if (kind == "storage_full") check(original is SaveFailure && (original as SaveFailure).code == SaveError.STORAGE_FULL)
                    check(attempt.directOriginalFailure(engine, session) === original)
                    asyncWrappedError(call(engine, "directListener", attempt, session), requireNotNull(original))
                    session.completion.get(5, TimeUnit.SECONDS)
                    error("Expected exceptional completion")
                } finally { h.f.io.before = {}; writer.close() }
            }
            check(result is TransformerOutcome.Failed && result.primary === original)
            check(result.release == TransformerRelease.CONFIRMED)
        }
    }
    private fun nativeMapping(editor: Boolean, code: Int, expected: TransformerError) = withBound { h, attempt, pair, session, engine ->
        val arbitrary = SaveFailure(SaveError.CANCELLED) // Unregistered cause cannot populate owner slot.
        val result = h.entered(session) {
            pair.createMediaSource(session)
            val listener: Transformer.Listener = if (editor) call(engine, "editorListener", session) else call(engine, "directListener", attempt, session)
            asyncWrappedError(listener, arbitrary, code)
            session.completion.get(5, TimeUnit.SECONDS)
        }
        check(result is TransformerOutcome.Failed && result.primary !== arbitrary)
        check(result.primary is TransformerFailure && (result.primary as TransformerFailure).code == expected)
        check(attempt.directOriginalFailure(engine, session) == null)
    }
    private fun blockedIoStop(deadline: Boolean, output: Boolean) = withBound { h, attempt, pair, session, engine ->
        var captured: Throwable? = null
        val ioEntered = CountDownLatch(1); val release = CountDownLatch(1)
        val stopped = CompletableFuture<Unit>()
        val stopper = Thread {
            try {
                check(ioEntered.await(5, TimeUnit.SECONDS))
                if (deadline) h.f.now = h.f.context.deadlineEpochMillis else h.f.cancellation.cancel()
                release.countDown(); stopped.complete(Unit)
            } catch (failure: Throwable) { release.countDown(); stopped.completeExceptionally(failure) }
        }.apply { isDaemon = true; start() }
        val result = h.entered(session) {
            pair.createMediaSource(session)
            val ownedSource = attempt.ownedSource(pair); session.retain(ownedSource)
            val reader = if (!output) session.openInput(ownedSource) else null
            val target = if (output) attempt.ownedTarget(pair) else null
            val writer = target?.openEmptyWriter()
            if (target != null) session.retainWriter(target)
            h.f.io.before = { call -> if (call.operation == if (output) "pwrite" else "pread") {
                ioEntered.countDown(); check(release.await(5, TimeUnit.SECONDS)); throw IOException("HOST_BLOCKED_IO")
            } }
            try {
                val boundary = caught {
                    if (writer != null) writer.write(ByteBuffer.wrap(byteArrayOf(1))) else requireNotNull(reader).readAt(0, ByteArray(1), 0, 1)
                }
                check(boundary is SaveFailure && boundary.code == if (deadline) SaveError.DEADLINE_EXCEEDED else SaveError.CANCELLED)
                captured = attempt.directOriginalFailure(engine, session)
                check(captured is SaveFailure && (captured as SaveFailure).code == boundary.code)
                asyncWrappedError(call(engine, "directListener", attempt, session), boundary)
                session.completion.get(5, TimeUnit.SECONDS)
                error("Expected exceptional completion")
            } finally { h.f.io.before = {}; writer?.close(); if (reader != null) session.closeInput(reader) }
        }
        stopped.get(5, TimeUnit.SECONDS); stopper.join(5_000)
        check(result is TransformerOutcome.Failed && result.primary === captured)
        check(result.release == TransformerRelease.CONFIRMED)
    }
    private fun outputOpenFailure(kind: String) = withBound { h, attempt, pair, session, engine ->
        val ioFailure = IOException("HOST_OUTPUT_OPEN")
        val result = h.entered(session) {
            pair.createMediaSource(session)
            session.retain(attempt.ownedSource(pair))
            h.f.io.before = { operation ->
                if (operation.operation == "open" && operation.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING) {
                    if (kind == "cancelled") h.f.cancellation.cancel()
                    if (kind == "deadline") h.f.now = h.f.context.deadlineEpochMillis
                    // Known rejection has positively no acquired descriptor; unknown open stays fenced.
                    if (kind == "unknown_open") throw ioFailure else throw NormalizationOpenRejected(ioFailure)
                }
            }
            try { attempt.ownedTarget(pair); error("Expected open rejection") }
            finally { h.f.io.before = {} }
        }
        check(result is TransformerOutcome.Failed)
        check(result.primary === attempt.directOriginalFailure(engine, session))
        when (kind) {
            "known_io" -> check(result.primary === ioFailure)
            "cancelled" -> check(result.primary is SaveFailure && (result.primary as SaveFailure).code == SaveError.CANCELLED)
            "deadline" -> check(result.primary is SaveFailure && (result.primary as SaveFailure).code == SaveError.DEADLINE_EXCEEDED)
            else -> {
                check(result.primary is SaveFailure && (result.primary as SaveFailure).code == SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                check(h.f.attempts.releaseUnconfirmed())
            }
        }
    }
    private fun nativeThenFinalizationFailure() = withBound { h, attempt, pair, session, engine ->
        var nativePrimary: Throwable? = null
        val result = h.entered(session) {
            pair.createMediaSource(session)
            session.retain(attempt.ownedSource(pair))
            val target = attempt.ownedTarget(pair); val writer = target.openEmptyWriter(); session.retainWriter(target)
            try {
                writer.write(ByteBuffer.wrap(byteArrayOf(1))); writer.beginFinalization()
                asyncWrappedError(call(engine, "directListener", attempt, session), IOException("HOST_NATIVE_UNSUPPORTED"),
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
                nativePrimary = transformerPrimaryFailure(caught { session.completion.get(5, TimeUnit.SECONDS) })
                check(nativePrimary is TransformerFailure && (nativePrimary as TransformerFailure).code == TransformerError.UNSUPPORTED_PROFILE)
                val cleanup = IOException("HOST_FINALIZATION")
                h.f.io.before = { if (it.operation == "fsync") throw cleanup }
                check(caught { writer.force() } === cleanup)
                check(attempt.directOriginalFailure(engine, session) == null)
                // The real future remains the earlier native failure, even after bounded finalization failed.
                session.completion.get(5, TimeUnit.SECONDS)
                error("Expected exceptional completion")
            } finally { h.f.io.before = {}; writer.close() }
        }
        check(result is TransformerOutcome.Failed && result.primary === nativePrimary)
        check(result.release == TransformerRelease.CONFIRMED)
    }
    /** Observation-only test seam around the real production checkpoint. Never returns a reason. */
    private class CheckpointObservation {
        var before: () -> Unit = {}
        var failed: (Throwable) -> Unit = {}
    }
    private fun poisonWindow(kind: String) {
        val outerThread = Thread.currentThread()
        val gate = AtomicBoolean(false)
        val poisonedWindow = CountDownLatch(1)
        val actualPollingFailure = CompletableFuture<Throwable>()
        val observation = CheckpointObservation().apply {
            before = {
                if (Thread.currentThread() === outerThread && gate.get())
                    check(poisonedWindow.await(5, TimeUnit.SECONDS)) { "POISON_WINDOW_NOT_REACHED" }
            }
            failed = { failure ->
                if (Thread.currentThread() === outerThread && poisonedWindow.count == 0L)
                    actualPollingFailure.complete(failure)
            }
        }
        withBound(observation) { h, attempt, pair, session, engine ->
            val injected = IOException("HOST_POISON_" + kind)
            val firstPoison = AtomicBoolean(false)
            val checkedWindow = AtomicBoolean(false)
            var expected: Throwable? = null
            var nativePrimary: Throwable? = null
            // Replace only this fixture's exact token queue with an observation-preserving subclass.
            // AndroidNormalizationFiles.poison has published files.poisoned, but its outer catch
            // has not resumed. add() retains the exact object before waiting; no fence is fabricated.
            val state = field<Any>(h.token, "state")
            val prior = field<ConcurrentLinkedQueue<Any>>(state, "retained")
            val retained = object : ConcurrentLinkedQueue<Any>() {
                override fun add(value: Any): Boolean {
                    val added = super.add(value)
                    if (firstPoison.compareAndSet(false, true)) {
                        try {
                            check(field<Boolean>(h.f.files, "poisoned"))
                            expected = nativePrimary ?: attempt.directOriginalFailure(engine, session)
                            check(expected != null) { "FIRST_CAUSE_MISSING_BEFORE_POISON" }
                            when {
                                kind == "native_then_mutation" -> {
                                    check(expected === nativePrimary)
                                    check(attempt.directOriginalFailure(engine, session) == null)
                                }
                                kind.startsWith("unknown_open") || kind == "raw_close" ->
                                    check(expected is SaveFailure && (expected as SaveFailure).code == SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                                kind.endsWith("cancelled") -> check(expected is SaveFailure && (expected as SaveFailure).code == SaveError.CANCELLED)
                                kind.endsWith("deadline") -> check(expected is SaveFailure && (expected as SaveFailure).code == SaveError.DEADLINE_EXCEEDED)
                                kind == "output_progress" -> check(expected is IOException)
                                else -> check(expected === injected)
                            }
                            poisonedWindow.countDown()
                            // This is executeTransformerSession's real waiting-thread checkpoint,
                            // while the worker is still inside poison and has not thrown outward.
                            check(actualPollingFailure.get(5, TimeUnit.SECONDS) === expected)
                            checkedWindow.set(true)
                        } finally { poisonedWindow.countDown() }
                    }
                    return added
                }
            }
            retained.addAll(prior)
            set(state, "retained", retained)
            val result = h.entered(session) {
                pair.createMediaSource(session)
                val source = attempt.ownedSource(pair); session.retain(source)
                if (kind.startsWith("unknown_open")) {
                    h.f.io.before = { operation ->
                        if (operation.operation == "open" && operation.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING) {
                            if (kind.endsWith("cancelled")) h.f.cancellation.cancel()
                            if (kind.endsWith("deadline")) h.f.now = h.f.context.deadlineEpochMillis
                            throw injected
                        }
                    }
                    gate.set(true)
                    try { attempt.ownedTarget(pair); error("Expected unknown open") }
                    finally { h.f.io.before = {} }
                } else if (kind == "raw_close") {
                    h.f.io.before = { if (it.operation == "close") throw injected }
                    gate.set(true)
                    // Actual retained-source cleanup owns the final close after this request.
                    attempt.close()
                } else {
                    val output = kind.startsWith("output") || kind == "native_then_mutation"
                    val reader = if (!output) session.openInput(source) else null
                    val target = if (output) attempt.ownedTarget(pair) else null
                    val writer = target?.openEmptyWriter()
                    if (target != null) session.retainWriter(target)
                    if (kind == "native_then_mutation") {
                        requireNotNull(writer).beginFinalization()
                        gate.set(true)
                        asyncWrappedError(call(engine, "directListener", attempt, session), IOException("HOST_NATIVE_FIRST"),
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
                        nativePrimary = transformerPrimaryFailure(caught { session.completion.get(5, TimeUnit.SECONDS) })
                        check(nativePrimary is TransformerFailure && (nativePrimary as TransformerFailure).code == TransformerError.UNSUPPORTED_PROFILE)
                    }
                    h.f.io.before = { operation -> if (operation.operation == if (output) "pwrite" else "pread") {
                        if (kind.endsWith("cancelled")) h.f.cancellation.cancel()
                        if (kind.endsWith("deadline")) h.f.now = h.f.context.deadlineEpochMillis
                        throw injected
                    } }
                    if (kind == "output_progress") {
                        h.f.io.before = {}
                        h.f.io.writeResult = -1
                    }
                    gate.set(true)
                    try {
                        if (writer != null) writer.write(ByteBuffer.wrap(byteArrayOf(1)))
                        else requireNotNull(reader).readAt(0, ByteArray(1), 0, 1)
                        error("Expected syscall failure")
                    } finally {
                        h.f.io.before = {}; writer?.close(); if (reader != null) session.closeInput(reader)
                    }
                }
            }
            h.f.io.before = {}
            check(firstPoison.get() && checkedWindow.get())
            check(result is TransformerOutcome.Failed && result.primary === expected)
            check(result.release == if (kind == "raw_close") TransformerRelease.UNCONFIRMED else TransformerRelease.CONFIRMED)
            check(h.f.attempts.releaseUnconfirmed()) // Storage uncertainty is independent of primary/released native pins.
            check(retained.isNotEmpty())
        }
    }
    private fun completionOutsideAttemptLock() = withBound { h, attempt, pair, session, engine ->
        val observed = CompletableFuture<Unit>()
        val lock = field<Any>(attempt, "lock")
        session.completion.whenComplete { _, _ ->
            try { check(!Thread.holdsLock(lock)); observed.complete(Unit) }
            catch (failure: Throwable) { observed.completeExceptionally(failure) }
        }
        val result = h.entered(session) {
            pair.createMediaSource(session)
            asyncWrappedError(call(engine, "directListener", attempt, session), IOException("HOST_NATIVE"),
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
            session.completion.get(5, TimeUnit.SECONDS)
        }
        observed.get(5, TimeUnit.SECONDS)
        check(result is TransformerOutcome.Failed && result.primary is TransformerFailure &&
            (result.primary as TransformerFailure).code == TransformerError.UNSUPPORTED_PROFILE)
    }
    private fun withBound(observation: CheckpointObservation? = null,
        body: (OwnedTranscodeTestFixture, OwnedDirectTranscodeAttempt, OwnedWebmInputBinding, TransformerSession, TransformerEngine) -> Unit) {
        val bytes = F.declaredTimeline(33_333_333, 100.0, false)
        val fixture = OwnedTranscodeTestFixture(bytes.size)
        fixture.f.io.mutate(fixture.f.primary, bytes, preserveTimes = true)
        fixture.run { h, _ ->
            val attempt = h.prepare(); val pair = attempt.bindWebmInputOnce(); pair.probe()
            val session = TransformerSession(h.session().limits, {
                observation?.before?.invoke()
                try { call<Unit>(attempt, "runCheckpoint") }
                catch (failure: Throwable) { observation?.failed?.invoke(failure); throw failure }
            }, { null })
            val engine = engine()
            // Test-only negative setup. No actualRunOutcome/firstOriginalFailure or success is injected.
            set(attempt, "runEngine", engine); set(attempt, "runSession", session); set(attempt, "runPair", pair)
            val phaseField = attempt.javaClass.getDeclaredField("runPhase").apply { isAccessible = true }
            phaseField.set(attempt, phaseField.type.enumConstants.single { (it as Enum<*>).name == "WORKING" })
            try { body(h, attempt, pair, session, engine) }
            finally { try { attempt.close() } catch (_: SaveFailure) { /* Tested cleanup uncertainty remains owned. */ } }
            check(h.f.record!!.directConversion == null && h.f.ledger.row!!.lifecycle == NormalizationLifecycle.WRITING)
            check(field<Any?>(attempt, "actualRunOutcome") == null)
        }
    }
    private fun asyncWrappedError(listener: Transformer.Listener, cause: Throwable, code: Int = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        lateCompleted: Boolean = false) {
        val item = EditedMediaItem.Builder(MediaItem.Builder().setUri("esv-webm-owned:callback").build()).build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(item).build()).build()
        val result = ExportResult.Builder().build()
        val forwarded = emptyListener { wrapped ->
            check(wrapped.cause is PlaybackException && wrapped.cause!!.cause === cause)
            listener.onError(composition, result, wrapped)
        }
        val outer = unsafe.allocateInstance(ExoPlayerAssetLoader::class.java)
        val type = Class.forName("androidx.media3.transformer.ExoPlayerAssetLoader\$PlayerListener")
        val ctor = type.getDeclaredConstructor(ExoPlayerAssetLoader::class.java, AssetLoader.Listener::class.java).apply { isAccessible = true }
        val playerListener = ctor.newInstance(outer, forwarded) as Player.Listener
        val thread = HandlerThread("esv-direct-host-errors").apply { start() }
        val done = CompletableFuture<Unit>()
        check(Handler(thread.looper).post {
            try {
                if (lateCompleted) listener.onCompleted(composition, result)
                playerListener.onPlayerError(PlaybackException("HOST_NATIVE", cause, code))
                listener.onCompleted(composition, result) // A late callback must not erase failure.
                done.complete(Unit)
            } catch (failure: Throwable) { done.completeExceptionally(failure) }
        })
        try { done.get(5, TimeUnit.SECONDS) }
        finally { thread.quitSafely(); thread.join(5_000); check(!thread.isAlive) }
    }
    private fun emptyListener(error: (ExportException) -> Unit = {}): AssetLoader.Listener = object : AssetLoader.Listener {
        override fun onDurationUs(value: Long) = Unit
        override fun onTrackCount(value: Int) = Unit
        override fun onTrackAdded(format: Format, types: Int) = true
        override fun onOutputFormat(format: Format): SampleConsumer? = null
        override fun onError(exception: ExportException) = error(exception)
    }
    private object Discard : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            private val bytes = ByteArray(4096)
            override fun format(format: Format) = Unit
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) = input.read(bytes, 0, minOf(bytes.size, length))
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
        }
        override fun endTracks() = Unit
        override fun seekMap(map: SeekMap) = Unit
    }
    private fun pinned(type: Class<*>, artifact: String) {
        val expected = File(requireNotNull(System.getProperty("esv.webm.output")), "jars/media3-$artifact-1.11.1.jar").canonicalFile
        check(File(type.protectionDomain.codeSource.location.toURI()).canonicalFile == expected)
    }
    private fun caught(action: () -> Unit): Throwable {
        try { action() } catch (failure: Throwable) { return failure }
        error("Expected rejection")
    }
    private fun set(value: Any, name: String, replacement: Any) { value.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(value, replacement) }
    @Suppress("UNCHECKED_CAST") private fun <T> field(value: Any, name: String): T = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value) as T
    @Suppress("UNCHECKED_CAST") private fun <T> call(value: Any, name: String, vararg args: Any): T = try {
        val method = value.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }
        method.invoke(value, *args) as T
    } catch (failure: InvocationTargetException) { throw requireNotNull(failure.cause) }
    private fun test(name: String, action: () -> Unit) {
        try { action(); passed++; records += "{\"name\":\"$name\",\"passed\":true}"; println("PASS $name") }
        catch (failure: Throwable) { failed++; records += "{\"name\":\"$name\",\"passed\":false}"; println("FAIL $name ${failure.javaClass.simpleName}"); failure.printStackTrace() }
    }
}
