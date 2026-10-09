package com.easysavevideo.save.network

import com.easysavevideo.core.model.CancellationSource
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** No endpoint is contacted: even HTTPS URLs below are synthetic names consumed by ScriptedFactory. */
class SystemHttpsDirectTransferTest {
    private val url = SourceUrl("https://media.example.invalid/selected.mp4?synthetic=1")
    private val artifact = StagingArtifact("owned-artifact", "commit-1")
    private fun resource(ref: RequestContextRef? = null) = SelectedMediaResource("candidate", "variant", url, requestContextRef = ref)
    private fun limits(max: Long = 64L * 1024 * 1024) = SaveLimits(max, max * 3, noProgressTimeoutMillis = 2_000)
    private fun operation(cancel: CancellationSource = CancellationSource(), bytes: Long? = null) =
        OperationContext("synthetic", System.currentTimeMillis() + 30_000, cancel, retryBudget = 2, resourceBudget = ResourceBudget(maxBytes = bytes))
    private fun checkpoint(offset: Long = 4, total: Long = 10) =
        DownloadCheckpoint(artifact.artifactId, HttpsRequestPolicy.identity(URI(url.value)), offset, "\"version-1\"", total)
    private fun transfer(response: ScriptedResponse, storage: CountingStaging = CountingStaging(),
                         checkpoint: DownloadCheckpoint? = null, limits: SaveLimits = limits(),
                         progress: Progress = Progress(), context: OperationContext = operation()): TransferResult =
        SystemHttpsDirectTransfer(storage, connections = ScriptedFactory(response)).transfer(resource(), artifact, checkpoint, context, limits, progress)

    @Test fun complete200IsOnlyTransferEvidenceAndResourcesClose() {
        val response = ScriptedResponse(body = GeneratedInput(17), headers = headers(17))
        val storage = CountingStaging(99)
        val progress = Progress()
        val result = transfer(response, storage, progress = progress)
        assertEquals(17, result.bytesOnDisk)
        assertEquals(17, progress.bytes)
        assertEquals(listOf(false), storage.openModes)
        assertEquals(1, progress.restarts)
        assertTrue(storage.closed)
        assertTrue(response.closed)
        assertTrue((response.body as GeneratedInput).closed)
        assertFalse(response.instanceFollowRedirects)
        assertFalse(response.useCaches)
        assertEquals("identity", response.requestHeaders["Accept-Encoding"])
        assertTrue(response.connectTimeout in 1..10_000)
        assertTrue(response.readTimeout in 1..2_000)
    }

    @Test fun valid206UsesStrongIdentityAndAppendsExactRange() {
        val response = ScriptedResponse(206, GeneratedInput(6), headers(6, "Content-Range" to "bytes 4-9/10"))
        val storage = CountingStaging(4)
        val progress = Progress()
        assertEquals(10, transfer(response, storage, checkpoint(), progress = progress).bytesOnDisk)
        assertEquals("bytes=4-", response.requestHeaders["Range"])
        assertEquals("\"version-1\"", response.requestHeaders["If-Range"])
        assertEquals(listOf(true), storage.openModes)
        assertEquals(0, progress.restarts)
        assertEquals(6, progress.bytes)
    }

    @Test fun checkpointTailIsRewoundOnlyAfterValidated206() {
        val response = ScriptedResponse(206, GeneratedInput(6), headers(6, "Content-Range" to "bytes 4-9/10"))
        val storage = CountingStaging(7)
        assertEquals(10, transfer(response, storage, checkpoint()).bytesOnDisk)
        assertEquals(listOf(4L), storage.rewinds)
    }

    @Test fun resume200CreatesFreshOwnedGenerationNeverAppends() {
        val storage = CountingStaging(4)
        val progress = Progress()
        val response = ScriptedResponse(body = GeneratedInput(9), headers = headers(9))
        assertEquals(9, transfer(response, storage, checkpoint(), progress = progress).bytesOnDisk)
        assertEquals(listOf(false), storage.openModes)
        assertEquals(1, storage.generation)
        assertEquals(1, progress.restarts)
        assertEquals(9, progress.bytes)
    }

    @Test fun onRestartFailureDoesNotReplaceStaging() {
        val storage = CountingStaging(4)
        val response = ScriptedResponse(body = GeneratedInput(9), headers = headers(9))
        val port = SystemHttpsDirectTransfer(storage, connections = ScriptedFactory(response))
        fails(SaveError.CANCELLED) { port.transfer(resource(), artifact, checkpoint(), operation(), limits(), object : TransferProgress {
            override fun onBytes(delta: Long) { }
            override fun onCheckpoint(checkpoint: DownloadCheckpoint) { }
            override fun onRestart() { throw SaveFailure(SaveError.CANCELLED) }
        }) }
        assertEquals(4, storage.size)
        assertTrue(storage.openModes.isEmpty())
    }

    @Test fun wrongRangeStartEndTotalAndLengthFailBeforeAppend() {
        listOf("bytes 3-9/10", "bytes 4-8/10", "bytes 4-10/11", "bytes 4-9/*", "bytes 4-3/10", "bytes 4-9/9223372036854775808")
            .forEach { range ->
                val storage = CountingStaging(4)
                fails(SaveError.INVALID_RANGE) { transfer(ScriptedResponse(206, GeneratedInput(6), headers(6, "Content-Range" to range)), storage, checkpoint()) }
                assertTrue(storage.openModes.isEmpty())
            }
        fails(SaveError.INVALID_RANGE) { transfer(ScriptedResponse(206, GeneratedInput(6), headers(5, "Content-Range" to "bytes 4-9/10")), CountingStaging(4), checkpoint()) }
    }

    @Test fun changedOrWeakResumeEtagIsNeverAppended() {
        listOf("W/\"version-1\"", "\"version-2\"").forEach { tag ->
            val storage = CountingStaging(4)
            fails(SaveError.RESOURCE_CHANGED) { transfer(ScriptedResponse(206, GeneratedInput(6), headers(6, "Content-Range" to "bytes 4-9/10", "ETag" to tag)), storage, checkpoint()) }
            assertTrue(storage.openModes.isEmpty())
        }
    }

    @Test fun response206WithoutUsableCheckpointAnd416NeverSucceed() {
        fails(SaveError.INVALID_RANGE) { transfer(ScriptedResponse(206, GeneratedInput(10), headers(10, "Content-Range" to "bytes 0-9/10"))) }
        fails(SaveError.INVALID_RANGE) { transfer(ScriptedResponse(416), CountingStaging(4), checkpoint()) }
    }

    @Test fun wrongArtifactOrTooShortStagingNeverSendsRange() {
        val response = ScriptedResponse(body = GeneratedInput(10), headers = headers(10))
        transfer(response, CountingStaging(2), checkpoint())
        assertNull(response.requestHeaders["Range"])
        val second = ScriptedResponse(body = GeneratedInput(10), headers = headers(10))
        transfer(second, CountingStaging(4), checkpoint().copy(artifactId = "other"))
        assertNull(second.requestHeaders["Range"])
    }

    @Test fun shortAndLongBodiesFailIncludingResumedBodies() {
        fails(SaveError.INCOMPLETE_TRANSFER) { transfer(ScriptedResponse(body = GeneratedInput(9), headers = headers(10))) }
        fails(SaveError.INCOMPLETE_TRANSFER) { transfer(ScriptedResponse(body = GeneratedInput(11), headers = headers(10))) }
        fails(SaveError.INCOMPLETE_TRANSFER) { transfer(ScriptedResponse(206, GeneratedInput(5), headers(6, "Content-Range" to "bytes 4-9/10")), CountingStaging(4), checkpoint()) }
        fails(SaveError.INCOMPLETE_TRANSFER) { transfer(ScriptedResponse(206, GeneratedInput(7), headers(6, "Content-Range" to "bytes 4-9/10")), CountingStaging(4), checkpoint()) }
    }

    @Test fun encodedMultipartHtmlAndAmbiguousFramingFail() {
        fails(SaveError.UNSUPPORTED_MEDIA) { transfer(ScriptedResponse(headers = headers(1, "Content-Encoding" to "gzip"))) }
        fails(SaveError.INVALID_RANGE) { transfer(ScriptedResponse(headers = headers(1, "Content-Type" to "multipart/byteranges; boundary=a"))) }
        fails(SaveError.UNSUPPORTED_MEDIA) { transfer(ScriptedResponse(headers = headers(1, "Content-Type" to "text/html"))) }
        fails(SaveError.INCOMPLETE_TRANSFER) { transfer(ScriptedResponse(headers = headers(1, "Transfer-Encoding" to "chunked"))) }
        fails(SaveError.INCOMPLETE_TRANSFER) { transfer(ScriptedResponse(headers = headers(1, "Content-Length" to "-1"))) }
        val duplicate = ScriptedResponse(headers = headers(1)); duplicate.responseHeaders["Content-Length"] = listOf("1", "1")
        fails(SaveError.HTTP_FAILURE) { transfer(duplicate) }
    }

    @Test fun octetStreamRequiresLaterVerifierAndWeakFreshEtagMakesNoCheckpoint() {
        val response = ScriptedResponse(body = GeneratedInput(5L * 1024 * 1024), headers = headers(5L * 1024 * 1024,
            "Content-Type" to "application/octet-stream", "ETag" to "W/\"weak\""))
        val progress = Progress()
        assertEquals("application/octet-stream", transfer(response, progress = progress).responseMimeType)
        assertTrue(progress.checkpoints.isEmpty())
    }

    @Test fun declaredAndActualByteLimitsAreEnforced() {
        fails(SaveError.BYTE_LIMIT_EXCEEDED) { transfer(ScriptedResponse(body = GeneratedInput(11), headers = headers(11)), limits = limits(10)) }
        fails(SaveError.BYTE_LIMIT_EXCEEDED) { transfer(ScriptedResponse(body = GeneratedInput(11), headers = mapOf("Content-Type" to "video/mp4")), limits = limits(10)) }
        fails(SaveError.BYTE_LIMIT_EXCEEDED) { transfer(ScriptedResponse(body = GeneratedInput(11), headers = headers(11)), context = operation(bytes = 10)) }
    }

    @Test fun diskBudgetPreflightAndChunkChecksFailBeforeWrite() {
        val storage = CountingStaging().also { it.free = 9 }
        fails(SaveError.STORAGE_FULL) { transfer(ScriptedResponse(body = GeneratedInput(10), headers = headers(10)), storage) }
        assertTrue(storage.openModes.isEmpty())
        fails(SaveError.STORAGE_FULL) { transfer(ScriptedResponse(body = GeneratedInput(10), headers = mapOf("Content-Type" to "video/mp4")), storage) }
        assertEquals(0, storage.size)
    }

    @Test fun greaterThanOneGiBIsGeneratedStreamWith64KiBMaximumBuffer() {
        val size = 1024L * 1024 * 1024 + 137
        val input = GeneratedInput(size)
        val response = ScriptedResponse(body = input, headers = headers(size))
        val progress = Progress()
        val storage = CountingStaging()
        val result = transfer(response, storage, limits = limits(size + 10).copy(copyBufferBytes = 1024 * 1024), progress = progress)
        assertEquals(size, result.bytesOnDisk)
        assertEquals(size, progress.bytes)
        assertTrue(input.maxRead <= 64 * 1024)
        assertTrue(storage.maxWrite <= 64 * 1024)
        assertTrue(progress.checkpoints.size in 250..260)
        assertEquals(progress.checkpoints.size + 1, storage.syncs)
    }

    @Test fun redirectsStripAllLeaseHeadersIncludingSameOrigin() {
        for (location in listOf("https://other.example.invalid/other.mp4", "/same-origin-other.mp4")) {
            val leases = RequestContextLeaseStore()
            val ref = leases.put(url, cookie = "sid=private", userAgent = "synthetic-agent", authorization = "Bearer private")
            val first = ScriptedResponse(302, headers = mapOf("Location" to location))
            val second = ScriptedResponse(body = GeneratedInput(10), headers = headers(10))
            val factory = ScriptedFactory(first, second)
            val port = SystemHttpsDirectTransfer(CountingStaging(), leases, connections = factory)
            port.transfer(resource(ref), artifact, null, operation(), limits(), Progress())
            assertEquals("sid=private", first.requestHeaders["Cookie"])
            assertEquals("Bearer private", first.requestHeaders["Authorization"])
            listOf("Cookie", "Authorization", "Referer", "User-Agent").forEach { assertNull(second.requestHeaders[it]) }
            assertTrue(first.closed && second.closed)
        }
    }

    @Test fun downgradePrivateLoopAndRedirectLimitAreBlocked() {
        for (location in listOf("http://public.example.invalid/a", "https://127.0.0.1/a", "https://[::1]/a", url.value)) {
            val first = ScriptedResponse(302, headers = mapOf("Location" to location))
            val factory = ScriptedFactory(first)
            val port = SystemHttpsDirectTransfer(CountingStaging(), connections = factory)
            fails(SaveError.POLICY_BLOCKED) { port.transfer(resource(), artifact, null, operation(), limits(), Progress()) }
            assertEquals(1, factory.requests.size)
            assertTrue(first.closed)
        }
        fails(SaveError.POLICY_BLOCKED) { transfer(ScriptedResponse(302, headers = mapOf("Location" to "/next")), limits = limits().copy(maxRedirects = 0)) }
    }

    @Test fun rangeIdentityNeverFollowsChangedResourceRedirect() {
        val first = ScriptedResponse(302, headers = mapOf("Location" to "https://other.example.invalid/resource"))
        val second = ScriptedResponse(body = GeneratedInput(10), headers = headers(10))
        val port = SystemHttpsDirectTransfer(CountingStaging(4), connections = ScriptedFactory(first, second))
        port.transfer(resource(), artifact, checkpoint(), operation(), limits(), Progress())
        assertEquals("bytes=4-", first.requestHeaders["Range"])
        assertNull(second.requestHeaders["Range"])
        assertNull(second.requestHeaders["If-Range"])
    }

    @Test fun authenticationExpiryAndStatusClassificationAreExplicit() {
        listOf(401 to SaveError.AUTHENTICATION_REQUIRED, 403 to SaveError.AUTHENTICATION_REQUIRED,
            404 to SaveError.HTTP_NOT_FOUND, 410 to SaveError.HTTP_NOT_FOUND, 400 to SaveError.HTTP_REJECTED, 429 to SaveError.RATE_LIMITED,
            408 to SaveError.NETWORK_TIMEOUT, 500 to SaveError.HTTP_FAILURE).forEach { (status, code) ->
            fails(code) { transfer(ScriptedResponse(status)) }
        }
    }

    @Test fun globalCookieHandlerFailsClosedWithoutModifyingIt() {
        val original = CookieHandler.getDefault()
        val handler = CookieManager()
        try {
            CookieHandler.setDefault(handler)
            val factory = ScriptedFactory(ScriptedResponse())
            fails(SaveError.POLICY_BLOCKED) {
                SystemHttpsDirectTransfer(CountingStaging(), connections = factory).transfer(resource(), artifact, null, operation(), limits(), Progress())
            }
            assertSame(handler, CookieHandler.getDefault())
            assertTrue(factory.requests.isEmpty())
        } finally { CookieHandler.setDefault(original) }
    }

    @Test fun cancelledBlockedReadActivelyDisconnectsAndClosesBeforeReturn() {
        val blocked = BlockingInput()
        val response = ScriptedResponse(body = blocked, headers = headers(10)).also { it.onDisconnect = { blocked.release.countDown() } }
        val cancel = CancellationSource()
        val storage = CountingStaging()
        val run = async { transfer(response, storage, context = operation(cancel)) }
        assertTrue(blocked.entered.await(2, TimeUnit.SECONDS))
        cancel.cancel()
        assertEquals(SaveError.CANCELLED, run.awaitFailure())
        assertTrue(response.closed && blocked.closed && storage.closed)
    }

    @Test fun deadlineActivelyDisconnectsBlockedRead() {
        val blocked = BlockingInput()
        val response = ScriptedResponse(body = blocked, headers = headers(10)).also { it.onDisconnect = { blocked.release.countDown() } }
        val now = java.util.concurrent.atomic.AtomicLong(1)
        val port = SystemHttpsDirectTransfer(CountingStaging(), clock = EpochClock(now::get), connections = ScriptedFactory(response))
        val run = async { port.transfer(resource(), artifact, null, OperationContext("deadline", 10, CancellationSource()), limits(), Progress()) }
        assertTrue(blocked.entered.await(2, TimeUnit.SECONDS))
        now.set(10)
        assertEquals(SaveError.DEADLINE_EXCEEDED, run.awaitFailure())
        assertTrue(response.closed)
    }

    @Test fun noProgressActivelyDisconnectsAndZeroReadsDoNotFakeEof() {
        val blocked = BlockingInput()
        val response = ScriptedResponse(body = blocked, headers = headers(10)).also { it.onDisconnect = { blocked.release.countDown() } }
        val run = async { transfer(response, limits = limits().copy(noProgressTimeoutMillis = 80)) }
        assertEquals(SaveError.NETWORK_TIMEOUT, run.awaitFailure())
        val zero = object : InputStream() { override fun read() = 0; override fun read(b: ByteArray, off: Int, len: Int) = 0 }
        fails(SaveError.NETWORK_TIMEOUT) { transfer(ScriptedResponse(body = zero, headers = headers(10)), limits = limits().copy(noProgressTimeoutMillis = 40)) }
    }

    @Test fun stuckDisconnectRetainsBusySlotUntilResourcesActuallyRelease() {
        val blocked = BlockingInput()
        val disconnectEntered = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val response = ScriptedResponse(body = blocked, headers = headers(10)).also { it.onDisconnect = {
            disconnectEntered.countDown()
            awaitUninterruptibly(releaseDisconnect)
            blocked.release.countDown()
        } }
        val cancel = CancellationSource()
        val port = SystemHttpsDirectTransfer(CountingStaging(), connections = ScriptedFactory(response))
        val run = async { port.transfer(resource(), artifact, null, operation(cancel), limits(), Progress()) }
        try {
            assertTrue(blocked.entered.await(2, TimeUnit.SECONDS)); cancel.cancel()
            assertTrue(disconnectEntered.await(2, TimeUnit.SECONDS))
            assertFalse(run.finished.await(100, TimeUnit.MILLISECONDS))
            fails(SaveError.BUSY) { port.transfer(resource(), artifact, null, operation(), limits(), Progress()) }
        } finally { releaseDisconnect.countDown(); blocked.release.countDown() }
        assertEquals(SaveError.CANCELLED, run.awaitFailure())
    }

    @Test fun disconnectFailureRetainsSlotAndReturnsRecoveryRequired() {
        val response = ScriptedResponse(body = GeneratedInput(1), headers = headers(1)).also { it.onDisconnect = { throw IOException("secret") } }
        val port = SystemHttpsDirectTransfer(CountingStaging(), connections = ScriptedFactory(response))
        fails(SaveError.RECOVERY_REQUIRED) { port.transfer(resource(), artifact, null, operation(), limits(), Progress()) }
        assertTrue(port.isDraining)
        assertTrue(port.requiresProcessRecovery())
        fails(SaveError.RECOVERY_REQUIRED) { port.transfer(resource(), artifact, null, operation(), limits(), Progress()) }
    }

    @Test fun durableOriginOnlyReferenceNeverIssuesRequest() {
        val factory = ScriptedFactory(ScriptedResponse())
        val port = SystemHttpsDirectTransfer(CountingStaging(), connections = factory)
        fails(SaveError.SOURCE_RESELECTION_REQUIRED) {
            port.transfer(resource().asDurableReference(), artifact, null, operation(), limits(), Progress())
        }
        assertTrue(factory.requests.isEmpty())
    }

    @Test fun failedOutputCloseFencesAllFurtherWorkAndLeaksNoExceptionText() {
        val storage = CountingStaging().also { it.failClose = true }
        val response = ScriptedResponse(body = GeneratedInput(1), headers = headers(1))
        val port = SystemHttpsDirectTransfer(storage, connections = ScriptedFactory(response))
        val failure = fails(SaveError.STORAGE_FAILURE) { port.transfer(resource(), artifact, null, operation(), limits(), Progress()) }
        assertTrue(port.requiresProcessRecovery())
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        fails(SaveError.RECOVERY_REQUIRED) { port.transfer(resource(), artifact, null, operation(), limits(), Progress()) }
    }

    @Test fun outputOpenAndSyncFailureAreSanitizedAndCloseNetwork() {
        val response = ScriptedResponse(body = GeneratedInput(1), headers = headers(1))
        val storage = CountingStaging().also { it.failSync = true }
        val failure = fails(SaveError.STORAGE_FAILURE) { transfer(response, storage) }
        assertFalse(failure.toString().contains("private-path"))
        assertNull(failure.cause)
        assertTrue(response.closed && storage.closed)
    }

    private fun headers(size: Long, vararg overrides: Pair<String, String>) = linkedMapOf("Content-Length" to "$size", "Content-Type" to "video/mp4", "ETag" to "\"version-1\"").apply { putAll(overrides) }
    private fun fails(code: SaveError, action: () -> Any?): SaveFailure {
        try { action(); fail("Expected $code") } catch (failure: SaveFailure) { assertEquals(code, failure.code); return failure }
        throw AssertionError()
    }

    private class Progress : TransferProgress {
        var bytes = 0L; var restarts = 0; val checkpoints = mutableListOf<DownloadCheckpoint>()
        override fun onBytes(delta: Long) { bytes += delta }
        override fun onRestart() { restarts++ }
        override fun onCheckpoint(checkpoint: DownloadCheckpoint) { checkpoints += checkpoint }
    }
    private class CountingStaging(var size: Long = 0) : NetworkStagingAccess {
        var free = Long.MAX_VALUE; var closed = false; var maxWrite = 0; var syncs = 0; var generation = 0; var failSync = false; var failClose = false
        val openModes = mutableListOf<Boolean>(); val rewinds = mutableListOf<Long>()
        override fun length(artifact: StagingArtifact) = size
        override fun availableBytes(artifact: StagingArtifact) = free
        override fun rewind(artifact: StagingArtifact, length: Long) { require(length <= size); size = length; rewinds += length }
        override fun sync(artifact: StagingArtifact) { if (failSync) throw IOException("private-path"); syncs++ }
        override fun open(artifact: StagingArtifact, append: Boolean): OutputStream {
            openModes += append
            if (!append) { size = 0; generation++ }
            return object : OutputStream() {
                override fun write(b: Int) { size++ }
                override fun write(b: ByteArray, off: Int, len: Int) { size += len; maxWrite = maxOf(maxWrite, len) }
                override fun close() { if (failClose) throw IOException("private-path-cookie-secret"); closed = true }
            }
        }
    }
    private class GeneratedInput(private var remaining: Long) : InputStream() {
        var maxRead = 0; var closed = false
        override fun read(): Int = if (remaining-- > 0) 7 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            maxRead = maxOf(maxRead, len)
            if (remaining <= 0) return -1
            val count = minOf(remaining, len.toLong()).toInt()
            java.util.Arrays.fill(b, off, off + count, 7.toByte())
            remaining -= count
            return count
        }
        override fun close() { closed = true }
    }
    private class BlockingInput : InputStream() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); var closed = false
        override fun read(): Int { entered.countDown(); awaitUninterruptibly(release); throw IOException("socket closed synthetic-secret") }
        override fun read(b: ByteArray, off: Int, len: Int) = read()
        override fun close() { closed = true }
    }
    private class ScriptedResponse(private val status: Int = 200, val body: InputStream = ByteArrayInputStream(byteArrayOf(1)), headers: Map<String, String> = emptyMap()) : HttpURLConnection(URL("https://script.invalid/")) {
        var closed = false; var onDisconnect: (() -> Unit)? = null
        val requestHeaders = linkedMapOf<String, String>()
        val responseHeaders = headers.mapValues { listOf(it.value) }.toMutableMap()
        override fun connect() { }
        override fun disconnect() { closed = true; onDisconnect?.invoke() }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderFields(): Map<String, List<String>> = responseHeaders
        override fun getInputStream() = body
        override fun setRequestProperty(key: String, value: String) { requestHeaders[key] = value }
    }
    private class ScriptedFactory(vararg responses: ScriptedResponse) : DirectConnectionFactory {
        val requests = mutableListOf<URI>(); private val responses = ArrayDeque(responses.toList())
        override fun open(uri: URI, connectTimeoutMillis: Int): HttpURLConnection { requests += uri; return responses.removeFirst() }
    }
    private class AsyncResult(val finished: CountDownLatch, val result: AtomicReference<Throwable?>) {
        fun awaitFailure(): SaveError {
            assertTrue("Worker failed to release within controlled test timeout", finished.await(3, TimeUnit.SECONDS))
            val error = result.get()
            assertTrue("Expected sanitized failure, got ${error?.javaClass?.simpleName}", error is SaveFailure)
            return (error as SaveFailure).code
        }
    }
    private fun async(action: () -> Unit): AsyncResult {
        val finished = CountDownLatch(1); val result = AtomicReference<Throwable?>()
        Thread { try { action() } catch (failure: Throwable) { result.set(failure) } finally { finished.countDown() } }.apply { isDaemon = true; start() }
        return AsyncResult(finished, result)
    }
    companion object {
        private fun awaitUninterruptibly(latch: CountDownLatch) {
            var interrupted = false
            while (true) { try { latch.await(); break } catch (_: InterruptedException) { interrupted = true } }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
