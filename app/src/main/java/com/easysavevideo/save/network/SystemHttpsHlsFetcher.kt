package com.easysavevideo.save.network

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.CancellationToken
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.*
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/** Memory only. Signed locations and the manifest body must never enter a journal or a raw file. */
class HlsManifestResponse(val requestedUrl: SourceUrl, val finalUrl: SourceUrl, val bytes: ByteArray) {
    override fun toString() = "HlsManifestResponse([redacted])"
}

/** Transfer evidence only: the caller still has to validate actual TS, codecs and timeline. */
class HlsSegmentResponse(val bytes: Long, val sha256: String) {
    override fun toString() = "HlsSegmentResponse(bytes=$bytes, identity=[redacted])"
}

/**
 * Adapter for ONE randomly named, app-owned private staging file. No URL/server filename is a path.
 * open must create a fresh owned inode, never append or follow a link. availableBytes must retain
 * the caller's output/remux reservation. sync must fsync the open file after the stream is flushed.
 * The task owner removes partial segments only after this fetcher has proved resource release.
 */
interface HlsSegmentSink {
    fun open(): OutputStream
    fun availableBytes(): Long
    fun sync()
    fun length(): Long
}

/**
 * A single stream task's serial, no-retry HTTPS transport. Create once and retain across that task's
 * manifests, segments and attempts: the first call binds source, deadline, token and lifetime budget.
 * There is no Range/resume API. A partial segment is not a checkpoint or a valid media resource.
 * SystemHttpsConnectionFactory retains system TLS/hostname checks and validates the actual peer.
 */
class SystemHttpsHlsFetcher(
    private val requestContexts: RequestContextLeaseStore = RequestContextLeaseStore(),
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
    private val connections: DirectConnectionFactory = SystemHttpsConnectionFactory(),
) {
    private val active = AtomicBoolean(false)
    private val cleanupUncertain = AtomicBoolean(false)
    private var session: Session? = null
    @Volatile private var consumed = 0L
    private var exhausted = false
    private var cancellationObserved = false
    val isDraining: Boolean get() = cleanupUncertain.get()
    val isTransferActive: Boolean get() = active.get()
    fun requiresProcessRecovery(): Boolean = cleanupUncertain.get()
    fun bytesTransferred(): Long = consumed

    /** All returned bytes require the strict HLS parser, including text/plain/octet-stream responses. */
    fun fetchManifest(resource: SelectedMediaResource, target: SourceUrl, context: OperationContext,
                      limits: SaveLimits, maxBytes: Int, progress: TransferProgress): HlsManifestResponse =
        request(resource, target, context, limits, ResourceType.PLAYLIST, maxBytes.toLong()) { connection, final, length, guard ->
            // Deliberately bounded even if a caller accidentally supplies an oversized preference.
            if (maxBytes !in 1..MAX_MANIFEST_BYTES) throw SaveFailure(SaveError.INVALID_REQUEST)
            val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
            readBody(connection, output, null, length, context, limits, maxBytes.toLong(), progress, guard)
            val body = output.toByteArray()
            val marker = "#EXTM3U".toByteArray(Charsets.US_ASCII)
            if (body.size < marker.size || marker.indices.any { body[it] != marker[it] } ||
                (body.size > marker.size && body[marker.size] != '\n'.code.toByte() &&
                    !(body[marker.size] == '\r'.code.toByte() && body.size > marker.size + 1 && body[marker.size + 1] == '\n'.code.toByte())))
                throw SaveFailure(SaveError.INVALID_MEDIA)
            HlsManifestResponse(target, SourceUrl(final.toASCIIString()), body)
        }

    fun fetchSegment(resource: SelectedMediaResource, target: SourceUrl, sink: HlsSegmentSink,
                     context: OperationContext, limits: SaveLimits, maxBytes: Long,
                     progress: TransferProgress): HlsSegmentResponse =
        request(resource, target, context, limits, ResourceType.MPEG_TS, maxBytes) { connection, _, length, guard ->
            if (length != null && length > storage { sink.availableBytes() }) throw SaveFailure(SaveError.STORAGE_FULL)
            guard.check()
            val output = storage { sink.open() }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val bytes = readBody(connection, output, sink, length, context, limits, maxBytes, progress, guard, digest)
                if (storage { sink.length() } != bytes) throw SaveFailure(SaveError.STORAGE_FAILURE)
                guard.check()
                HlsSegmentResponse(bytes, digest.digest().joinToString("") { "%02x".format(it) })
            } finally {
                closeOwned(output, SaveError.STORAGE_FAILURE)
            }
        }

    private inline fun <T> request(resource: SelectedMediaResource, requested: SourceUrl, context: OperationContext,
                                  limits: SaveLimits, type: ResourceType, maxBytes: Long,
                                  consume: (HttpURLConnection, URI, Long?, HlsTransferGuard) -> T): T {
        if (cleanupUncertain.get()) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        if (!active.compareAndSet(false, true)) throw SaveFailure(SaveError.BUSY)
        try {
            checkContext(context)
            if (maxBytes <= 0 || type == ResourceType.PLAYLIST && maxBytes > MAX_MANIFEST_BYTES)
                throw SaveFailure(SaveError.INVALID_REQUEST)
            if (resource.durableReference) throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            if (resource.expiresAtEpochMillis?.let { clock.nowMillis() >= it } == true)
                throw SaveFailure(SaveError.EXPIRED_RESOURCE)
            bind(resource, context, limits)
            // The coordinator may wrap cancellation. Retain the original token as an additional OR
            // condition so a replacement token cannot revive a cancelled preparation/session.
            val originalToken = session!!.context.cancelToken
            val guardedContext = context.copy(cancelToken = object : CancellationToken {
                override val isCancelled: Boolean get() = originalToken.isCancelled || context.cancelToken.isCancelled
            })
            if (exhausted || remaining() <= 0) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
            val selected = validate(URI(resource.url.value))
            var target = validate(URI(requested.value))
            val visited = mutableSetOf<String>()
            for (hop in 0..limits.maxRedirects) {
                checkContext(context)
                validate(target)
                if (!visited.add(HttpsRequestPolicy.canonical(target))) throw SaveFailure(SaveError.POLICY_BLOCKED)
                if (CookieHandler.getDefault() != null) throw SaveFailure(SaveError.POLICY_BLOCKED)
                val timeout = boundedTimeout(limits.connectTimeoutMillis, context)
                val connection = connections.open(target, timeout)
                val guard = HlsTransferGuard(connection, guardedContext, limits.noProgressTimeoutMillis, clock)
                try {
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.doInput = true
                    connection.doOutput = false
                    connection.requestMethod = "GET"
                    connection.connectTimeout = timeout
                    connection.readTimeout = boundedTimeout(limits.noProgressTimeoutMillis, context)
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("Accept", type.accept)
                    connection.setRequestProperty("Connection", "close")
                    // Leases are exact-source-URL only, and NEVER follow a redirect or a playlist URI.
                    if (type == ResourceType.PLAYLIST && hop == 0 && HttpsRequestPolicy.canonical(selected) == HttpsRequestPolicy.canonical(target)) {
                        requestContexts.headers(resource.requestContextRef, selected, target, true)
                            .forEach { (name, value) -> connection.setRequestProperty(name, value) }
                    }
                    guard.check()
                    if (CookieHandler.getDefault() != null) throw SaveFailure(SaveError.POLICY_BLOCKED)
                    val status = connection.responseCode
                    guard.check()
                    if (status in REDIRECTS) {
                        if (hop == limits.maxRedirects) throw SaveFailure(SaveError.POLICY_BLOCKED)
                        target = redirect(target, header(connection, "Location") ?: throw SaveFailure(SaveError.HTTP_FAILURE))
                        continue
                    }
                    if (status != 200) throw SaveFailure(when (status) {
                        206, 416 -> SaveError.INVALID_RANGE
                        401, 403 -> SaveError.AUTHENTICATION_REQUIRED
                        404, 410 -> SaveError.HTTP_NOT_FOUND
                        408, 504 -> SaveError.NETWORK_TIMEOUT
                        429 -> SaveError.RATE_LIMITED
                        else -> if (status in 400..499) SaveError.HTTP_REJECTED else SaveError.HTTP_FAILURE
                    })
                    val length = validateHeaders(connection, type)
                    val perRequest = minOf(maxBytes, limits.maxArtifactBytes)
                    if (length != null && (length > perRequest || length > remaining()))
                        throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
                    val result = consume(connection, target, length, guard)
                    guard.check()
                    return result
                } catch (failure: Exception) {
                    guard.check()
                    throw sanitize(failure)
                } finally {
                    // Blocking/failed close retains this single lane, rather than inventing cancellation.
                    try { guard.finish() } catch (_: Exception) {
                        cleanupUncertain.set(true)
                        throw SaveFailure(SaveError.RECOVERY_REQUIRED)
                    }
                    // disconnect itself may block: a late cancel/deadline must fence the prepared
                    // result after cleanup actually returns, without falsely marking release failed.
                    guard.check()
                }
            }
            throw SaveFailure(SaveError.POLICY_BLOCKED)
        } catch (failure: Exception) {
            val sanitized = sanitize(failure)
            if (session != null && sanitized.code == SaveError.CANCELLED) cancellationObserved = true
            throw sanitized
        } finally {
            if (!cleanupUncertain.get()) active.set(false)
        }
    }

    private fun readBody(connection: HttpURLConnection, output: OutputStream, sink: HlsSegmentSink?,
                         length: Long?, context: OperationContext, limits: SaveLimits, maxBytes: Long,
                         progress: TransferProgress, guard: HlsTransferGuard, digest: MessageDigest? = null): Long {
        val perRequest = minOf(maxBytes, limits.maxArtifactBytes)
        val buffer = ByteArray(minOf(limits.copyBufferBytes, 64 * 1024))
        val input = connection.inputStream
        try {
            var bytes = 0L
            while (true) {
                guard.check()
                val allowance = minOf(remaining(), perRequest - bytes, length?.minus(bytes) ?: Long.MAX_VALUE).coerceAtLeast(0)
                // One bounded sentinel byte detects an undeclared overrun without allocating/reading it all.
                val cap = minOf(buffer.size.toLong(), if (allowance == Long.MAX_VALUE) allowance else allowance + 1).toInt()
                val count = input.read(buffer, 0, cap)
                if (count < 0) { guard.check(); break }
                if (count == 0) {
                    guard.check()
                    try { Thread.sleep(1) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt(); throw SaveFailure(SaveError.CANCELLED)
                    }
                    continue
                }
                val available = remaining()
                if (count.toLong() > available) exhausted = true
                consumed = if (count.toLong() > Long.MAX_VALUE - consumed) Long.MAX_VALUE else consumed + count
                // Charge even bytes from rejected or incomplete bodies before notifying task accounting.
                progress.onBytes(count.toLong())
                guard.check()
                if (exhausted || count.toLong() > perRequest - bytes) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
                if (length != null && count.toLong() > length - bytes) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
                if (sink != null) {
                    if (storage { sink.availableBytes() } < count) throw SaveFailure(SaveError.STORAGE_FULL)
                    storageWrite(sink, count.toLong()) { output.write(buffer, 0, count) }
                } else output.write(buffer, 0, count)
                digest?.update(buffer, 0, count)
                bytes += count
                guard.progressed()
            }
            guard.check()
            checkContext(context)
            if (bytes == 0L || length != null && length != bytes) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
            if (sink != null) storageWrite(sink, 1) { output.flush(); sink.sync() }
            guard.check()
            return bytes
        } finally {
            closeOwned(input, SaveError.RECOVERY_REQUIRED)
        }
    }

    private fun bind(resource: SelectedMediaResource, context: OperationContext, limits: SaveLimits) {
        val old = session
        if (old == null) {
            session = Session(resource.sourceIdentity, context, limits,
                minOf(limits.maxTotalTransferBytes, context.resourceBudget.maxBytes ?: Long.MAX_VALUE))
        } else if (old.sourceIdentity != resource.sourceIdentity || old.context.operationId != context.operationId ||
            old.context.deadlineEpochMillis != context.deadlineEpochMillis ||
            old.context.resourceBudget != context.resourceBudget || old.limits != limits) {
            throw SaveFailure(SaveError.INVALID_REQUEST)
        }
    }
    private fun remaining(): Long = (session!!.totalBudget - consumed).coerceAtLeast(0)
    private class Session(val sourceIdentity: String, val context: OperationContext, val limits: SaveLimits, val totalBudget: Long)

    private fun validateHeaders(connection: HttpURLConnection, type: ResourceType): Long? {
        if (header(connection, "Content-Range") != null) throw SaveFailure(SaveError.INVALID_RANGE)
        val encoding = header(connection, "Content-Encoding")
        if (encoding != null && !encoding.equals("identity", true)) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        val mime = (header(connection, "Content-Type") ?: "application/octet-stream")
            .substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime !in type.mimeTypes) throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        val transferEncoding = header(connection, "Transfer-Encoding")
        if (transferEncoding != null && !transferEncoding.equals("chunked", true))
            throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
        val length = header(connection, "Content-Length")?.let {
            if (!it.matches(Regex("[0-9]{1,19}"))) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
            it.toLongOrNull() ?: throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
        }
        if (length != null && transferEncoding != null) throw SaveFailure(SaveError.INCOMPLETE_TRANSFER)
        return length
    }
    private fun header(connection: HttpURLConnection, name: String): String? {
        val values = connection.headerFields.entries.filter { it.key.equals(name, true) }.flatMap { it.value }
        if (values.isEmpty()) return null
        if (values.size != 1 || values.single().length > 8192 || values.single().any { it < ' ' || it == '\u007f' })
            throw SaveFailure(SaveError.HTTP_FAILURE)
        return values.single().trim()
    }
    private fun checkContext(context: OperationContext) {
        if (cancellationObserved || context.cancelToken.isCancelled || session?.context?.cancelToken?.isCancelled == true || Thread.currentThread().isInterrupted)
            throw SaveFailure(SaveError.CANCELLED)
        if (clock.nowMillis() >= context.deadlineEpochMillis) throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
    }
    private fun boundedTimeout(value: Int, context: OperationContext): Int {
        checkContext(context)
        return minOf(value.toLong(), context.deadlineEpochMillis - clock.nowMillis()).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }
    private fun closeOwned(resource: Closeable, code: SaveError) {
        try { resource.close() } catch (_: Exception) {
            cleanupUncertain.set(true); throw SaveFailure(code)
        } catch (_: LinkageError) {
            cleanupUncertain.set(true); throw SaveFailure(code)
        }
    }
    private inline fun <T> storage(action: () -> T): T = try { action() }
        catch (failure: SaveFailure) { throw failure }
        catch (_: Exception) { throw SaveFailure(SaveError.STORAGE_FAILURE) }
    private inline fun <T> storageWrite(sink: HlsSegmentSink, required: Long, action: () -> T): T = try { action() }
        catch (failure: SaveFailure) { throw failure }
        catch (_: Exception) {
            val free = try { sink.availableBytes() } catch (_: Exception) { Long.MAX_VALUE }
            throw SaveFailure(if (free < required) SaveError.STORAGE_FULL else SaveError.STORAGE_FAILURE)
        }
    private fun sanitize(failure: Exception): SaveFailure = when (failure) {
        is SaveFailure -> SaveFailure(failure.code)
        is SSLException -> SaveFailure(SaveError.TLS_REJECTED)
        is SocketTimeoutException -> SaveFailure(SaveError.NETWORK_TIMEOUT)
        is UnknownHostException, is ConnectException, is IOException -> SaveFailure(SaveError.NETWORK_UNAVAILABLE)
        else -> SaveFailure(SaveError.UNKNOWN_TECHNICAL_ERROR)
    }
    private fun validate(uri: URI): URI {
        // Reject malformed Unicode rather than allowing replacement-byte identities in signed URLs.
        try { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(uri.toString())) }
        catch (_: Exception) { throw SaveFailure(SaveError.POLICY_BLOCKED) }
        return HttpsRequestPolicy.validate(uri)
    }

    /** Original RFC3986 raw-component resolution: preserve signed query and repeated path slashes. */
    private fun redirect(base: URI, reference: String): URI {
        if (reference.isBlank() || reference.length > 8192 || reference.any { it.isWhitespace() || it.code < 32 || it.code in 127..159 })
            throw SaveFailure(SaveError.POLICY_BLOCKED)
        val ref = try { URI(reference) } catch (_: Exception) { throw SaveFailure(SaveError.POLICY_BLOCKED) }
        if (ref.rawFragment != null || ref.isOpaque || reference.startsWith("//") && ref.rawAuthority == null)
            throw SaveFailure(SaveError.POLICY_BLOCKED)
        val own = ref.isAbsolute || ref.rawAuthority != null
        val authority = if (own) ref.rawAuthority else base.rawAuthority
        val rawPath = ref.rawPath.orEmpty()
        val basePath = base.rawPath.orEmpty()
        val path = when {
            own -> removeDots(rawPath)
            rawPath.isEmpty() -> basePath
            rawPath.startsWith('/') -> removeDots(rawPath)
            else -> removeDots((if (basePath.isEmpty()) "/" else basePath.substring(0, basePath.lastIndexOf('/') + 1)) + rawPath)
        }
        val query = if (!own && rawPath.isEmpty()) ref.rawQuery ?: base.rawQuery else ref.rawQuery
        val result = buildString {
            append(ref.scheme ?: base.scheme); append(':')
            if (authority != null) { append("//"); append(authority) }
            append(path)
            if (query != null) { append('?'); append(query) }
        }
        return validate(URI(result))
    }
    private fun removeDots(path: String): String {
        if (path.isEmpty()) return path
        if (!path.startsWith('/')) throw SaveFailure(SaveError.POLICY_BLOCKED)
        val input = path.substring(1).split('/')
        val output = ArrayList<String>()
        input.forEachIndexed { index, segment ->
            when (segment) {
                "." -> if (index == input.lastIndex) output.add("")
                ".." -> { if (output.isNotEmpty()) output.removeAt(output.lastIndex); if (index == input.lastIndex) output.add("") }
                else -> output.add(segment)
            }
        }
        return "/" + output.joinToString("/")
    }
    private enum class ResourceType(val mimeTypes: Set<String>, val accept: String) {
        PLAYLIST(setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl", "text/plain", "application/octet-stream"),
            "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, text/plain, application/octet-stream"),
        MPEG_TS(setOf("video/mp2t", "application/octet-stream"), "video/mp2t, application/octet-stream")
    }
    override fun toString() = "SystemHttpsHlsFetcher([redacted])"
    private companion object {
        const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

/** One bounded watchdog per active call. The caller's lane stays occupied until disconnect returns. */
private class HlsTransferGuard(private val connection: HttpURLConnection, private val context: OperationContext,
                               private val timeout: Int, private val clock: EpochClock) {
    private val finished = AtomicBoolean(false)
    private val disconnectStarted = AtomicBoolean(false)
    private val disconnectFailed = AtomicBoolean(false)
    private val reason = AtomicReference<SaveError?>(null)
    @Volatile private var lastProgress = System.nanoTime()
    private val watchdog = Thread({
        try {
            while (!finished.get()) {
                currentReason()?.let { reason.compareAndSet(null, it); disconnect(); return@Thread }
                Thread.sleep(minOf(50L, timeout.toLong()))
            }
        } catch (_: InterruptedException) { }
    }, "esv-hls-watchdog").apply { isDaemon = true; start() }
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
        var interrupted = false
        while (watchdog.isAlive) {
            try { watchdog.join() } catch (_: InterruptedException) { interrupted = true }
        }
        disconnect()
        if (interrupted) Thread.currentThread().interrupt()
        if (disconnectFailed.get()) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    }
}
