package com.easysavevideo.save.network

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.*
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/**
 * One active transfer, bounded streaming buffer, no internal retries, no automatic redirect/cookie
 * handling, and no media-success decision. The coordinator owns retries, validation and publication.
 */
class SystemHttpsDirectTransfer(
    private val staging: NetworkStagingAccess,
    private val requestContexts: RequestContextLeaseStore = RequestContextLeaseStore(),
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
    private val connections: DirectConnectionFactory = SystemHttpsConnectionFactory(),
) : DirectTransferPort {
    private val active = AtomicBoolean(false)
    private val cleanupUncertain = AtomicBoolean(false)
    /** Native cleanup failed; this instance cannot safely accept another task in this process. */
    val isDraining: Boolean get() = cleanupUncertain.get()
    val isTransferActive: Boolean get() = active.get()
    override fun requiresProcessRecovery(): Boolean = cleanupUncertain.get()

    override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact,
                          checkpoint: DownloadCheckpoint?, context: OperationContext,
                          limits: SaveLimits, progress: TransferProgress): TransferResult {
        if (cleanupUncertain.get()) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        if (!active.compareAndSet(false, true)) throw SaveFailure(SaveError.BUSY)
        try {
            checkContext(context)
            if (resource.durableReference) throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            if (resource.expiresAtEpochMillis?.let { clock.nowMillis() >= it } == true)
                throw SaveFailure(SaveError.EXPIRED_RESOURCE)
            val selected = HttpsRequestPolicy.validate(URI(resource.url.value))
            var target = selected
            val visited = mutableSetOf<String>()
            val usableCheckpoint = checkpoint?.takeIf {
                it.artifactId == artifact.artifactId && HttpsRequestPolicy.strongEtag(it.strongEtag) != null &&
                    it.offset < it.totalBytes && it.totalBytes <= limits.maxArtifactBytes &&
                    storage { staging.length(artifact) } >= it.offset
            }
            for (hop in 0..limits.maxRedirects) {
                checkContext(context)
                HttpsRequestPolicy.validate(target)
                if (!visited.add(HttpsRequestPolicy.canonical(target))) throw SaveFailure(SaveError.POLICY_BLOCKED)
                // Both checks precede responseCode: never let a process-global cookie handler read or
                // store another origin's cookies. The app does not install a global CookieHandler.
                if (CookieHandler.getDefault() != null) throw SaveFailure(SaveError.POLICY_BLOCKED)
                val timeout = boundedTimeout(limits.connectTimeoutMillis, context)
                val connection = connections.open(target, timeout)
                val guard = TransferGuard(connection, context, limits.noProgressTimeoutMillis, clock)
                try {
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.doInput = true
                    connection.doOutput = false
                    connection.requestMethod = "GET"
                    connection.connectTimeout = timeout
                    connection.readTimeout = boundedTimeout(limits.noProgressTimeoutMillis, context)
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("Accept", "video/mp4, application/mp4, application/octet-stream")
                    connection.setRequestProperty("Connection", "close")
                    requestContexts.headers(resource.requestContextRef, selected, target, hop == 0)
                        .forEach { (name, value) -> connection.setRequestProperty(name, value) }
                    val resume = usableCheckpoint?.takeIf { it.resourceIdentity == HttpsRequestPolicy.identity(target) }
                    if (resume != null) {
                        connection.setRequestProperty("Range", "bytes=${resume.offset}-")
                        connection.setRequestProperty("If-Range", resume.strongEtag)
                    }
                    guard.check()
                    if (CookieHandler.getDefault() != null) throw SaveFailure(SaveError.POLICY_BLOCKED)
                    val status = connection.responseCode
                    guard.check()
                    if (status in REDIRECTS) {
                        if (hop == limits.maxRedirects) throw SaveFailure(SaveError.POLICY_BLOCKED)
                        target = HttpsRequestPolicy.redirect(target, header(connection, "Location")
                            ?: throw SaveFailure(SaveError.HTTP_FAILURE))
                        continue
                    }
                    when (status) {
                        200, 206 -> return consume(connection, status, target, artifact, resume, context, limits, progress, guard)
                        401, 403 -> throw SaveFailure(SaveError.AUTHENTICATION_REQUIRED)
                        404, 410 -> throw SaveFailure(SaveError.HTTP_NOT_FOUND)
                        408, 504 -> throw SaveFailure(SaveError.NETWORK_TIMEOUT)
                        416 -> throw SaveFailure(SaveError.INVALID_RANGE)
                        429 -> throw SaveFailure(SaveError.RATE_LIMITED)
                        else -> throw SaveFailure(if (status in 400..499) SaveError.HTTP_REJECTED else SaveError.HTTP_FAILURE)
                    }
                } catch (failure: Exception) {
                    guard.check() // Cancellation/deadline takes precedence over its resulting socket error.
                    throw sanitize(failure)
                } finally {
                    // A hung disconnect or native close retains the slot and never pretends to be stopped.
                    try { guard.finish() } catch (failure: Exception) { cleanupUncertain.set(true); throw failure }
                }
            }
            throw SaveFailure(SaveError.POLICY_BLOCKED)
        } catch (failure: Exception) {
            throw sanitize(failure)
        } finally {
            if (!cleanupUncertain.get()) active.set(false)
        }
    }

    private fun consume(connection: HttpURLConnection, status: Int, target: URI, artifact: StagingArtifact,
                        checkpoint: DownloadCheckpoint?, context: OperationContext, limits: SaveLimits,
                        progress: TransferProgress, guard: TransferGuard): TransferResult {
        val encoding = header(connection, "Content-Encoding")
        if (encoding != null && !encoding.equals("identity", true)) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        val mime = (header(connection, "Content-Type") ?: "application/octet-stream")
            .substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime !in setOf("video/mp4", "application/mp4", "application/octet-stream"))
            throw SaveFailure(if (mime.startsWith("multipart/")) SaveError.INVALID_RANGE else SaveError.UNSUPPORTED_MEDIA)
        val transferEncoding = header(connection, "Transfer-Encoding")
        if (transferEncoding != null && !transferEncoding.equals("chunked", true)) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
        val length = header(connection, "Content-Length")?.let(::positiveLength)
        if (length != null && transferEncoding != null) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
        val etag = HttpsRequestPolicy.strongEtag(header(connection, "ETag"))
        val start: Long
        val total: Long?
        if (status == 206) {
            val old = checkpoint ?: throw SaveFailure(SaveError.INVALID_RANGE)
            if (etag == null || etag != old.strongEtag) throw SaveFailure(SaveError.RESOURCE_CHANGED)
            val range = parseRange(header(connection, "Content-Range"))
            if (range.start != old.offset || range.total != old.totalBytes || range.end != range.total - 1 ||
                (length != null && length != range.end - range.start + 1)) throw SaveFailure(SaveError.INVALID_RANGE)
            if (storage { staging.length(artifact) } < old.offset) throw SaveFailure(SaveError.INVALID_RANGE)
            start = old.offset
            total = range.total
        } else {
            if (header(connection, "Content-Range") != null) throw SaveFailure(SaveError.INVALID_RANGE)
            start = 0
            total = length
        }
        val totalBudget = minOf(limits.maxTotalTransferBytes, context.resourceBudget.maxBytes ?: Long.MAX_VALUE)
        if (total != null && (total > limits.maxArtifactBytes || total - start > totalBudget))
            throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
        if (total != null && total - start > storage { staging.availableBytes(artifact) })
            throw SaveFailure(SaveError.STORAGE_FULL)
        guard.check()
        if (status == 200) progress.onRestart()
        else if (storage { staging.length(artifact) } > start) storage { staging.rewind(artifact, start) }
        var bytes = start
        var transferred = 0L
        var lastCheckpoint = start
        // Hard upper bound independent of hostile file sizes or a caller's oversized preference.
        val buffer = ByteArray(minOf(limits.copyBufferBytes, 64 * 1024))
        val rawOutput = storage { staging.open(artifact, status == 206) }
        val output = object : OutputStream() {
            private var closed = false
            override fun write(value: Int) = rawOutput.write(value)
            override fun write(value: ByteArray, offset: Int, count: Int) = rawOutput.write(value, offset, count)
            override fun flush() = rawOutput.flush()
            override fun close() {
                if (closed) return
                closed = true
                try { rawOutput.close() } catch (_: Exception) {
                    cleanupUncertain.set(true); throw SaveFailure(SaveError.STORAGE_FAILURE)
                }
            }
        }
        try {
            connection.inputStream.use { input ->
                output.use { sink ->
                    while (true) {
                        guard.check()
                        val readCap = minOf(buffer.size.toLong(), (totalBudget - transferred).coerceAtLeast(0) +
                            if (totalBudget - transferred < Long.MAX_VALUE) 1L else 0L,
                            (limits.maxArtifactBytes - bytes).coerceAtLeast(0) +
                            if (limits.maxArtifactBytes - bytes < Long.MAX_VALUE) 1L else 0L).coerceAtLeast(1).toInt()
                        val count = input.read(buffer, 0, readCap)
                        guard.check()
                        if (count < 0) break
                        if (count == 0) {
                            try { Thread.sleep(1) } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt(); throw SaveFailure(SaveError.CANCELLED)
                            }
                            continue
                        }
                        progress.onBytes(count.toLong())
                        if (count.toLong() > totalBudget - transferred || count.toLong() > limits.maxArtifactBytes - bytes)
                            throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
                        if (total != null && count.toLong() > total - bytes) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
                        transferred += count
                        if (storage { staging.availableBytes(artifact) } < count.toLong()) throw SaveFailure(SaveError.STORAGE_FULL)
                        storageWrite(artifact, count.toLong()) { sink.write(buffer, 0, count) }
                        bytes += count
                        guard.progressed()
                        if (etag != null && total != null && bytes < total && bytes - lastCheckpoint >= CHECKPOINT_BYTES) {
                            checkpoint(sink, artifact, target, bytes, etag, total, progress, guard)
                            lastCheckpoint = bytes
                        }
                    }
                    guard.check()
                    if (bytes == 0L || total != null && bytes != total) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
                    storageWrite(artifact, 1) { sink.flush(); staging.sync(artifact) }
                    guard.check()
                }
            }
        } catch (failure: Exception) {
            // inputStream can fail before entering output.use; close still must finish before return.
            try { output.close() } catch (_: Exception) { }
            throw failure
        }
        if (storage { staging.length(artifact) } != bytes) throw SaveFailure(SaveError.STORAGE_FAILURE)
        guard.check()
        return TransferResult(artifact.artifactId, bytes, mime, total)
    }

    private fun checkpoint(sink: OutputStream, artifact: StagingArtifact, target: URI, bytes: Long,
                           etag: String, total: Long, progress: TransferProgress, guard: TransferGuard) {
        storageWrite(artifact, 1) { sink.flush(); staging.sync(artifact) }
        guard.check()
        progress.onCheckpoint(DownloadCheckpoint(artifact.artifactId, HttpsRequestPolicy.identity(target), bytes, etag, total))
    }

    private fun checkContext(context: OperationContext) {
        if (context.cancelToken.isCancelled || Thread.currentThread().isInterrupted) throw SaveFailure(SaveError.CANCELLED)
        if (clock.nowMillis() >= context.deadlineEpochMillis) throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
    }
    private fun boundedTimeout(value: Int, context: OperationContext): Int {
        checkContext(context)
        return minOf(value.toLong(), context.deadlineEpochMillis - clock.nowMillis()).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }
    private inline fun <T> storage(action: () -> T): T = try { action() }
        catch (failure: SaveFailure) { throw failure }
        catch (_: Exception) { throw SaveFailure(SaveError.STORAGE_FAILURE) }
    private inline fun <T> storageWrite(artifact: StagingArtifact, required: Long, action: () -> T): T = try { action() }
        catch (failure: SaveFailure) { throw failure }
        catch (_: Exception) {
            val free = try { staging.availableBytes(artifact) } catch (_: Exception) { Long.MAX_VALUE }
            throw SaveFailure(if (free < required) SaveError.STORAGE_FULL else SaveError.STORAGE_FAILURE)
        }
    private fun sanitize(failure: Exception): SaveFailure = when (failure) {
        is SaveFailure -> SaveFailure(failure.code)
        is SSLException -> SaveFailure(SaveError.TLS_REJECTED)
        is SocketTimeoutException -> SaveFailure(SaveError.NETWORK_TIMEOUT)
        is UnknownHostException, is ConnectException -> SaveFailure(SaveError.NETWORK_UNAVAILABLE)
        is IOException -> SaveFailure(SaveError.NETWORK_UNAVAILABLE)
        else -> SaveFailure(SaveError.UNKNOWN_TECHNICAL_ERROR)
    }

    private fun header(connection: HttpURLConnection, name: String): String? {
        val values = connection.headerFields.entries.filter { it.key.equals(name, true) }.flatMap { it.value }
        if (values.isEmpty()) return null
        if (values.size != 1 || values.single().length > 8192 || values.single().any { it < ' ' || it == '\u007f' })
            throw SaveFailure(SaveError.HTTP_FAILURE)
        return values.single().trim()
    }
    private fun positiveLength(value: String): Long {
        if (!value.matches(Regex("[0-9]{1,19}"))) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
        return value.toLongOrNull()?.takeIf { it >= 0 } ?: throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
    }
    private class Range(val start: Long, val end: Long, val total: Long)
    private fun parseRange(value: String?): Range {
        val match = value?.let { Regex("bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})").matchEntire(it) }
            ?: throw SaveFailure(SaveError.INVALID_RANGE)
        val (start, end, total) = match.groupValues.drop(1).map { it.toLongOrNull() ?: throw SaveFailure(SaveError.INVALID_RANGE) }
        if (start < 0 || end < start || total <= end) throw SaveFailure(SaveError.INVALID_RANGE)
        return Range(start, end, total)
    }
    override fun toString() = "SystemHttpsDirectTransfer([redacted])"
    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val CHECKPOINT_BYTES = 4L * 1024 * 1024
    }
}

/** One daemon per active transfer, no unbounded cancel executor or false stopped callback. */
private class TransferGuard(private val connection: HttpURLConnection, private val context: OperationContext,
                            private val timeout: Int, private val clock: EpochClock) {
    private val finished = AtomicBoolean(false)
    private val disconnectStarted = AtomicBoolean(false)
    private val disconnectFailed = AtomicBoolean(false)
    private val reason = AtomicReference<SaveError?>(null)
    @Volatile private var lastProgress = System.nanoTime()
    private val watchdog = Thread({
        try {
            while (!finished.get()) {
                currentReason()?.let { code -> reason.compareAndSet(null, code); disconnect(); return@Thread }
                Thread.sleep(minOf(50L, timeout.toLong()))
            }
        } catch (_: InterruptedException) { /* finish interrupts only this idle wait */ }
    }, "esv-direct-watchdog").apply { isDaemon = true; start() }

    fun progressed() { lastProgress = System.nanoTime() }
    private fun currentReason(): SaveError? = reason.get() ?: when {
        context.cancelToken.isCancelled -> SaveError.CANCELLED
        clock.nowMillis() >= context.deadlineEpochMillis -> SaveError.DEADLINE_EXCEEDED
        System.nanoTime() - lastProgress >= timeout.toLong() * 1_000_000 -> SaveError.NETWORK_TIMEOUT
        else -> null
    }
    fun check() {
        if (Thread.currentThread().isInterrupted) throw SaveFailure(SaveError.CANCELLED)
        currentReason()?.let { reason.compareAndSet(null, it); throw SaveFailure(it) }
    }
    private fun disconnect() {
        if (disconnectStarted.compareAndSet(false, true)) {
            try { connection.disconnect() } catch (_: Exception) { disconnectFailed.set(true) }
            catch (_: LinkageError) { disconnectFailed.set(true) }
        }
    }
    fun finish() {
        finished.set(true)
        watchdog.interrupt()
        // Interrupted callers still wait for native cleanup. Restore the flag only after the join.
        var interrupted = false
        while (watchdog.isAlive) {
            try { watchdog.join() } catch (_: InterruptedException) { interrupted = true }
        }
        disconnect()
        if (interrupted) Thread.currentThread().interrupt()
        if (disconnectFailed.get()) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    }
}
