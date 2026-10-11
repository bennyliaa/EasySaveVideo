package com.easysavevideo.app.save

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.easysavevideo.app.theme.*
import com.easysavevideo.save.MediaCommitReceipt
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.app.library.thumbnail.OrdinaryMediaNativeResources
import kotlinx.coroutines.delay
import java.io.FileInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Shared exclusion with recording. A hung native close keeps its owner and descriptor alive. */
object VerifiedMediaPreviewResources {
    private val owner = AtomicReference<PublishedPreviewSession?>(null)
    @Volatile var requiresRestart: Boolean = false
        private set
    val isOccupied: Boolean get() = owner.get() != null
    internal fun claim(session: PublishedPreviewSession): Boolean {
        if (requiresRestart || !OrdinaryMediaNativeResources.claim(session)) return false
        if (owner.compareAndSet(null, session)) return true
        OrdinaryMediaNativeResources.released(session)
        return false
    }
    internal fun released(session: PublishedPreviewSession) {
        if (owner.compareAndSet(session, null)) OrdinaryMediaNativeResources.released(session)
    }
    internal fun unconfirmed() { requiresRestart = true; OrdinaryMediaNativeResources.unconfirmed() }
}

/** Opens only through the published-receipt storage API; no path, URL, staging, or provisional ID. */
@Composable
fun VerifiedMediaPreview(
    receipt: MediaCommitReceipt,
    isForeground: Boolean,
    openPublishedMedia: (MediaCommitReceipt) -> FileInputStream,
    onBack: () -> Unit,
    onReleaseUnconfirmed: () -> Unit,
) {
    PublishedMediaPreviewScreen(receipt, isForeground, { openPublishedMedia(receipt) }, onBack, onReleaseUnconfirmed)
}

/** An exact current catalog read capability. Never accepts caller-controlled paths or URIs. */
@Composable
fun LibraryMediaPreview(
    reference: MediaReadRef,
    isForeground: Boolean,
    openPublishedMedia: (MediaReadRef) -> FileInputStream,
    onBack: () -> Unit,
    onReleaseUnconfirmed: () -> Unit,
) {
    PublishedMediaPreviewScreen(reference, isForeground, { openPublishedMedia(reference) }, onBack, onReleaseUnconfirmed)
}

/** Caller must supply an explicit current published reference and retire this body when hidden. */
@Composable
fun InlineLibraryMediaPreview(
    reference: MediaReadRef,
    isForeground: Boolean,
    openPublishedMedia: (MediaReadRef) -> FileInputStream,
    onReleaseUnconfirmed: () -> Unit,
) {
    Column {
        if (isForeground) AwaitPublishedPreviewBody(reference, { openPublishedMedia(reference) }, onReleaseUnconfirmed)
    }
}

@Composable
private fun PublishedMediaPreviewScreen(
    identity: Any,
    isForeground: Boolean,
    openPublishedMedia: () -> FileInputStream,
    onBack: () -> Unit,
    onReleaseUnconfirmed: () -> Unit,
) {
    val v = LocalEsvVisuals.current
    BackHandler(onBack = onBack)
    EsvBackdrop(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 960.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(v.spacingMedium), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                EsvText("已验证成片预览", style = v.titleTextStyle)
                EsvButton("关闭预览", onClick = onBack)
                EsvText("只读播放已发布到应用私有索引的 MP4。预览期间不能开始录制；离开页面或应用进入后台会关闭播放器。", style = v.captionTextStyle, color = v.colors.muted)
                if (isForeground) AwaitPublishedPreviewBody(identity, openPublishedMedia, onReleaseUnconfirmed)
                else EsvText("应用已离开前台，预览已关闭。返回后需手动播放。", style = v.captionTextStyle)
                EsvText("只读播放不会更改原文件。可播放、暂停、前后跳转或从头播放；关闭后返回原页面。", style = v.captionTextStyle, color = v.colors.muted)
            }
        }
    }
}

/** Navigation cancels thumbnails first, then waits for their actual native ownership release. */
@Composable
private fun AwaitPublishedPreviewBody(identity: Any, openPublishedMedia: () -> FileInputStream, onReleaseUnconfirmed: () -> Unit) {
    val v = LocalEsvVisuals.current
    var admitted by remember(identity) { mutableStateOf(false) }
    var fenced by remember(identity) { mutableStateOf(false) }
    LaunchedEffect(identity) {
        while (OrdinaryMediaNativeResources.isOccupied && !OrdinaryMediaNativeResources.requiresRestart) delay(50)
        fenced = OrdinaryMediaNativeResources.requiresRestart
        admitted = !fenced
    }
    when {
        fenced -> EsvText("现有媒体资源尚未确认退出，已保留文件所有权。请完全退出应用后重新打开。", color = v.colors.error, style = v.captionTextStyle)
        admitted -> PublishedPreviewBody(identity, openPublishedMedia, onReleaseUnconfirmed)
        else -> EsvText("正在等待缩略图或上一预览实际退出，随后打开只读预览…", style = v.captionTextStyle, color = v.colors.muted)
    }
}

internal enum class PreviewPlayback { OPENING, READY, PLAYING, PAUSED, ENDED, CLOSED, FAILED }

@Composable
private fun PublishedPreviewBody(
    identity: Any,
    openPublishedMedia: () -> FileInputStream,
    onReleaseUnconfirmed: () -> Unit,
) {
    val v = LocalEsvVisuals.current
    var playback by remember(identity) { mutableStateOf(PreviewPlayback.OPENING) }
    var aspectRatio by remember(identity) { mutableStateOf(16f / 9f) }
    var releaseUnconfirmed by remember(identity) { mutableStateOf(false) }
    val currentReleaseFailure by rememberUpdatedState(onReleaseUnconfirmed)
    val session = remember(identity) {
        PublishedPreviewSession(openPublishedMedia,
            onState = { playback = it }, onSize = { width, height ->
                if (width > 0 && height > 0) aspectRatio = width.toFloat() / height
            }, onReleaseUnconfirmed = { releaseUnconfirmed = true; currentReleaseFailure() })
    }
    DisposableEffect(session) { onDispose { session.close() } }
    AndroidView(factory = { context ->
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { session.attach(holder) }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) { session.close() }
            })
        }
    }, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).aspectRatio(aspectRatio.coerceIn(0.25f, 4f))
        .background(v.colors.surface))
    EsvText(when (playback) {
        PreviewPlayback.OPENING -> "正在打开已发布成片…"
        PreviewPlayback.READY -> "已就绪，请手动播放"
        PreviewPlayback.PLAYING -> "播放中"
        PreviewPlayback.PAUSED -> "已暂停播放"
        PreviewPlayback.ENDED -> "播放结束"
        PreviewPlayback.CLOSED -> "预览已关闭；返回任务后可重新打开"
        PreviewPlayback.FAILED -> "此成片暂时无法预览，可能已移动或删除；预览不会执行删除操作"
    }, style = v.captionTextStyle)
    if (releaseUnconfirmed) EsvText("系统播放器尚未确认退出，已保留文件与资源所有权。请完全退出应用后重新打开；此时不能开始新的录制。",
        color = v.colors.error, style = v.captionTextStyle)
    EsvButton(if (playback == PreviewPlayback.PLAYING) "暂停播放" else "播放",
        onClick = { if (playback == PreviewPlayback.PLAYING) session.pause() else session.play() },
        enabled = playback in setOf(PreviewPlayback.READY, PreviewPlayback.PLAYING, PreviewPlayback.PAUSED, PreviewPlayback.ENDED))
    EsvButton("从头播放", onClick = session::restart,
        enabled = playback in setOf(PreviewPlayback.READY, PreviewPlayback.PLAYING, PreviewPlayback.PAUSED, PreviewPlayback.ENDED))
    Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvButton("后退 15 秒", onClick = { session.seekBy(-15_000) },
            enabled = playback in setOf(PreviewPlayback.READY, PreviewPlayback.PLAYING, PreviewPlayback.PAUSED, PreviewPlayback.ENDED))
        EsvButton("前进 15 秒", onClick = { session.seekBy(15_000) },
            enabled = playback in setOf(PreviewPlayback.READY, PreviewPlayback.PLAYING, PreviewPlayback.PAUSED, PreviewPlayback.ENDED))
    }
}

/** All platform calls run off the UI thread, with one bounded owner lane and no release timeout lie. */
internal class PublishedPreviewSession(
    private val openPublishedMedia: () -> FileInputStream,
    onState: (PreviewPlayback) -> Unit,
    onSize: (Int, Int) -> Unit,
    onReleaseUnconfirmed: () -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val closing = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private var stateListener: ((PreviewPlayback) -> Unit)? = onState
    private var sizeListener: ((Int, Int) -> Unit)? = onSize
    private var releaseFailureListener: (() -> Unit)? = onReleaseUnconfirmed
    private var stream: FileInputStream? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private val claimed = VerifiedMediaPreviewResources.claim(this)
    private val lane = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(8),
        { command -> Thread(command, "esv-published-preview").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    fun attach(holder: SurfaceHolder) {
        if (!claimed || closing.get()) { emit(PreviewPlayback.FAILED); return }
        if (!started.compareAndSet(false, true)) return
        submit {
            try {
                val input = openPublishedMedia()
                stream = input
                if (closing.get()) return@submit
                val media = MediaPlayer()
                player = media
                media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
                media.setDataSource(input.fd, 0, input.channel.size())
                media.setDisplay(holder)
                media.setOnPreparedListener { ready -> submit {
                    if (!closing.get()) {
                        prepared = true
                        val width = ready.videoWidth; val height = ready.videoHeight
                        main.post { sizeListener?.invoke(width, height) }
                        emit(PreviewPlayback.READY)
                    }
                } }
                media.setOnCompletionListener { emit(PreviewPlayback.ENDED) }
                media.setOnErrorListener { _, _, _ -> fail(); true }
                media.prepareAsync()
            } catch (_: Exception) { fail() }
        }
    }
    fun play() = submit { if (prepared && !closing.get()) try { player?.start(); emit(PreviewPlayback.PLAYING) } catch (_: Exception) { fail() } }
    fun pause() = submit { if (prepared && !closing.get()) try { player?.pause(); emit(PreviewPlayback.PAUSED) } catch (_: Exception) { fail() } }
    fun restart() = submit { if (prepared && !closing.get()) try {
        player?.seekTo(0L, MediaPlayer.SEEK_CLOSEST_SYNC); player?.start(); emit(PreviewPlayback.PLAYING)
    } catch (_: Exception) { fail() } }

    fun seekBy(deltaMillis: Int) = submit { if (prepared && !closing.get()) try {
        player?.let { media ->
            media.seekTo(PreviewSeekPolicy.target(media.currentPosition, media.duration, deltaMillis), MediaPlayer.SEEK_CLOSEST_SYNC)
        }
    } catch (_: Exception) { fail() } }

    private fun fail() { failed.set(true); emit(PreviewPlayback.FAILED); close() }
    private fun emit(state: PreviewPlayback) { main.post { stateListener?.invoke(state) } }
    private fun submit(action: () -> Unit) {
        if (closing.get()) return
        try { lane.execute(action) }
        catch (_: java.util.concurrent.RejectedExecutionException) { fail() }
    }
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        if (!failed.get()) emit(PreviewPlayback.CLOSED)
        // Close may sit behind a hung platform call. The timeout only reports ownership uncertainty.
        main.postDelayed({ if (!released.get()) reportUnconfirmed() }, 3_000L)
        lane.queue.clear() // Discard only queued playback commands; the active native call keeps ownership.
        try { lane.execute {
            try {
                player?.release() // Descriptor remains owned until this actually returns.
                player = null
                stream?.close()
                stream = null
                released.set(true)
                if (claimed) VerifiedMediaPreviewResources.released(this)
                main.post { stateListener = null; sizeListener = null; releaseFailureListener = null }
            } catch (_: Exception) { reportUnconfirmed() }
            finally { lane.shutdown() }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { reportUnconfirmed() }
    }
    private fun reportUnconfirmed() {
        if (released.get() || !failureReported.compareAndSet(false, true)) return
        if (claimed) VerifiedMediaPreviewResources.unconfirmed()
        main.post {
            releaseFailureListener?.invoke()
            stateListener = null; sizeListener = null; releaseFailureListener = null
        }
    }
}
