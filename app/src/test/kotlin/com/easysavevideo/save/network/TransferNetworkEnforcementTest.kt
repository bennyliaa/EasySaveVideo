package com.easysavevideo.save.network

import com.easysavevideo.core.model.CancellationSource
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Every endpoint, connection and byte is synthetic; no DNS, device or socket I/O is performed. */
class TransferNetworkEnforcementTest {
    @Test fun disallowedPolicyOpensNoConnectionOrOutputIncludingManifest() = forEachKind { kind ->
        val policy = Policy().also { it.allowed = false }
        val harness = Harness(kind, policy, Response())
        fails(SaveError.WIFI_REQUIRED) { harness.run() }
        assertEquals(0, harness.factory.opens)
        assertEquals(0, harness.output.opens)
        assertFalse(harness.active())
        assertFalse(harness.draining())
    }

    @Test fun unavailablePolicyReaderFailsClosedBeforeAnyRequest() = forEachKind { kind ->
        val policy = TransferNetworkPolicy { throw IllegalStateException("synthetic-private-detail") }
        val harness = Harness(kind, policy, Response())
        val failure = fails(SaveError.WIFI_REQUIRED) { harness.run() }
        assertEquals(0, harness.factory.opens)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    @Test fun cancelledTaskWinsOverDisallowedPolicyWithoutEvenAcquiring() = forEachKind { kind ->
        val policy = Policy().also { it.allowed = false }
        val harness = Harness(kind, policy, Response()).also { it.cancel.cancel() }
        fails(SaveError.CANCELLED) { harness.run() }
        assertEquals(0, policy.acquisitions.get())
        assertEquals(0, harness.factory.opens)
    }

    @Test fun cancellationDuringAcquisitionStillWinsOverWifiFailure() = forEachKind { kind ->
        val cancel = CancellationSource()
        val policy = TransferNetworkPolicy { cancel.cancel(); throw SaveFailure(SaveError.WIFI_REQUIRED) }
        val harness = Harness(kind, policy, Response(), cancel = cancel)
        fails(SaveError.CANCELLED) { harness.run() }
        assertEquals(0, harness.factory.opens)
    }

    @Test fun eachRedirectRequiresNewPermissionBeforeItsConnection() = forEachKind { kind ->
        val policy = Policy().also { it.rejectAcquisition = 2 }
        val first = Response(status = 302, headers = mapOf("Location" to "/second"))
        val harness = Harness(kind, policy, first)
        fails(SaveError.WIFI_REQUIRED) { harness.run() }
        assertEquals(2, policy.acquisitions.get())
        assertEquals(1, harness.factory.opens)
        assertEquals(1, first.disconnects.get())
        assertEquals(0, harness.output.opens)
    }

    @Test fun bindingThatChangesDuringConnectionOpenBlocksResponseAndClosesConnection() = forEachKind { kind ->
        val policy = Policy()
        val response = Response()
        val harness = Harness(kind, policy, response)
        harness.factory.onOpen = { policy.allowed = false }
        fails(SaveError.WIFI_REQUIRED) { harness.run() }
        assertEquals(0, response.responseRequests.get())
        assertEquals(1, response.disconnects.get())
        assertFalse(harness.active())
    }

    @Test fun networkLossInterruptsBlockedBodyAndReleasesBeforeReturning() = forEachKind { kind ->
        val policy = Policy()
        val input = BlockingInput()
        val response = Response(body = input).also { it.onDisconnect = { input.release.countDown() } }
        val harness = Harness(kind, policy, response)
        val run = async { harness.run() }
        try {
            assertTrue(input.entered.await(2, TimeUnit.SECONDS))
            policy.allowed = false
            assertEquals(SaveError.WIFI_REQUIRED, run.failure())
            assertTrue(input.closed)
            if (kind != Kind.MANIFEST) assertTrue(harness.output.closed)
            assertEquals(1, response.disconnects.get())
            assertFalse(harness.active())
            assertFalse(harness.draining())
        } finally { input.release.countDown() }
    }

    @Test fun networkLossInterruptsBlockedResponseBeforeAnyStagingOrBody() = forEachKind { kind ->
        val policy = Policy()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val response = Response().also {
            it.beforeResponse = { entered.countDown(); awaitUninterruptibly(release); throw IOException("synthetic") }
            it.onDisconnect = { release.countDown() }
        }
        val harness = Harness(kind, policy, response)
        val run = async { harness.run() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            policy.allowed = false
            assertEquals(SaveError.WIFI_REQUIRED, run.failure())
            assertEquals(0, harness.output.opens)
            assertEquals(0, response.inputRequests)
            assertEquals(1, response.disconnects.get())
        } finally { release.countDown() }
    }

    @Test fun changedApprovedNetworkInvalidatesExistingBindingInsteadOfReselecting() = forEachKind { kind ->
        val policy = Policy()
        val input = BlockingInput()
        val response = Response(body = input).also { it.onDisconnect = { input.release.countDown() } }
        val harness = Harness(kind, policy, response)
        val run = async { harness.run() }
        try {
            assertTrue(input.entered.await(2, TimeUnit.SECONDS))
            policy.generation.incrementAndGet() // Wi-Fi A -> Wi-Fi B; both remain individually allowed.
            assertEquals(SaveError.WIFI_REQUIRED, run.failure())
            assertEquals(1, policy.acquisitions.get())
            assertEquals(1, harness.factory.opens)
            assertSame(policy.lastBinding, harness.factory.lastBinding)
        } finally { input.release.countDown() }
    }

    @Test fun failedPolicyReaderDuringBlockedIoDisconnectsRatherThanKillingWatchdog() = forEachKind { kind ->
        val policy = Policy()
        val input = BlockingInput()
        val response = Response(body = input).also { it.onDisconnect = { input.release.countDown() } }
        val harness = Harness(kind, policy, response)
        val run = async { harness.run() }
        try {
            assertTrue(input.entered.await(2, TimeUnit.SECONDS))
            policy.readerFailed = true
            assertEquals(SaveError.WIFI_REQUIRED, run.failure())
            assertTrue(input.closed)
            assertFalse(harness.active())
        } finally { input.release.countDown() }
    }

    @Test fun explicitCancellationOverridesLatchedWifiAndBusySlotWaitsForRealRelease() = forEachKind { kind ->
        val policy = Policy()
        val input = BlockingInput()
        val disconnectEntered = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val response = Response(body = input).also { it.onDisconnect = {
            disconnectEntered.countDown()
            awaitUninterruptibly(releaseDisconnect)
            input.release.countDown()
        } }
        val harness = Harness(kind, policy, response)
        val run = async { harness.run() }
        try {
            assertTrue(input.entered.await(2, TimeUnit.SECONDS))
            policy.allowed = false
            assertTrue(disconnectEntered.await(2, TimeUnit.SECONDS))
            assertTrue(harness.active())
            assertFalse(run.finished.await(50, TimeUnit.MILLISECONDS))
            fails(SaveError.BUSY) { harness.run() }
            harness.cancel.cancel()
        } finally { releaseDisconnect.countDown(); input.release.countDown() }
        assertEquals(SaveError.CANCELLED, run.failure())
        assertTrue(input.closed)
        assertFalse(harness.active())
        assertFalse(harness.draining())
    }

    @Test fun failedNetworkCleanupStillRequiresRecoveryRatherThanClaimingWifiWait() = forEachKind { kind ->
        val policy = Policy()
        val input = BlockingInput()
        val response = Response(body = input).also { it.onDisconnect = {
            input.release.countDown()
            throw IOException("synthetic-private-detail")
        } }
        val harness = Harness(kind, policy, response)
        val run = async { harness.run() }
        try {
            assertTrue(input.entered.await(2, TimeUnit.SECONDS))
            policy.allowed = false
            assertEquals(SaveError.RECOVERY_REQUIRED, run.failure())
            assertTrue(harness.active())
            assertTrue(harness.draining())
            fails(SaveError.RECOVERY_REQUIRED) { harness.run() }
        } finally { input.release.countDown() }
    }

    @Test fun conditionChangeDuringFinalDisconnectFencesSuccess() = forEachKind { kind ->
        val policy = Policy()
        val response = Response().also { it.onDisconnect = { policy.allowed = false } }
        val harness = Harness(kind, policy, response)
        fails(SaveError.WIFI_REQUIRED) { harness.run() }
        assertFalse(harness.active())
        assertFalse(harness.draining())
        assertEquals(1, response.disconnects.get())
    }

    @Test fun releaseFailureDuringFactoryOpenLatchesRecoveryEvenWhenNetworkBecomesDisallowed() = forEachKind { kind ->
        for (code in listOf(SaveError.RECOVERY_REQUIRED, SaveError.RESOURCE_RELEASE_UNCONFIRMED)) {
            val policy = Policy()
            val harness = Harness(kind, policy, Response())
            harness.factory.onOpen = { policy.allowed = false; throw SaveFailure(code) }
            fails(code) { harness.run() }
            assertTrue(harness.active())
            assertTrue(harness.draining())
            fails(SaveError.RECOVERY_REQUIRED) { harness.run() }
            assertEquals(1, harness.factory.opens)
        }
    }

    @Test fun wrappedSocketReleaseFailureCannotBeOverwrittenByWifiOrCancellation() = forEachKind { kind ->
        val policy = Policy()
        val cancel = CancellationSource()
        val response = Response().also { it.beforeResponse = {
            policy.allowed = false
            cancel.cancel()
            throw IOException("synthetic-wrapper", SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED))
        } }
        val harness = Harness(kind, policy, response, cancel)
        fails(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { harness.run() }
        assertTrue(harness.active())
        assertTrue(harness.draining())
        assertEquals(1, response.disconnects.get())
        fails(SaveError.RECOVERY_REQUIRED) { harness.run() }
    }

    @Test fun failedInputCloseDuringNetworkAbortRetainsLaneAndReportsUnreleasedResource() = forEachKind { kind ->
        val policy = Policy()
        val input = BlockingInput().also { it.failClose = true }
        val response = Response(body = input).also { it.onDisconnect = { input.release.countDown() } }
        val harness = Harness(kind, policy, response)
        val run = async { harness.run() }
        try {
            assertTrue(input.entered.await(2, TimeUnit.SECONDS))
            policy.allowed = false
            val expected = if (kind == Kind.DIRECT) SaveError.RESOURCE_RELEASE_UNCONFIRMED else SaveError.RECOVERY_REQUIRED
            assertEquals(expected, run.failure())
            assertTrue(harness.active())
            assertTrue(harness.draining())
            fails(SaveError.RECOVERY_REQUIRED) { harness.run() }
        } finally { input.release.countDown() }
    }

    @Test fun failedOutputCloseCannotBeSuppressedBehindNetworkAbort() {
        for (kind in listOf(Kind.DIRECT, Kind.SEGMENT)) {
            val policy = Policy()
            val input = BlockingInput()
            val response = Response(body = input).also { it.onDisconnect = { input.release.countDown() } }
            val harness = Harness(kind, policy, response).also { it.output.failClose = true }
            val run = async { harness.run() }
            try {
                assertTrue(input.entered.await(2, TimeUnit.SECONDS))
                policy.allowed = false
                val expected = if (kind == Kind.DIRECT) SaveError.RESOURCE_RELEASE_UNCONFIRMED else SaveError.STORAGE_FAILURE
                assertEquals(expected, run.failure())
                assertTrue(input.closed)
                assertTrue(harness.active())
                assertTrue(harness.draining())
                fails(SaveError.RECOVERY_REQUIRED) { harness.run() }
            } finally { input.release.countDown() }
        }
    }

    @Test fun bodyLinkageFailureStillDisconnectsAndSanitizesWithoutClaimingReleaseFailure() = forEachKind { kind ->
        val response = Response().also { it.beforeResponse = { throw LinkageError("synthetic-response") } }
        val harness = Harness(kind, Policy(), response)
        fails(SaveError.UNKNOWN_TECHNICAL_ERROR) { harness.run() }
        assertEquals(1, response.disconnects.get())
        assertFalse(harness.active())
        assertFalse(harness.draining())
    }

    @Test fun bodyLinkageWithInputCloseLinkageRetainsLaneInsteadOfBecomingWifiWait() = forEachKind { kind ->
        val policy = Policy()
        val input = BlockingInput().also { it.readLinkage = true; it.closeLinkage = true }
        val response = Response(body = input).also { it.onDisconnect = { input.release.countDown() } }
        val harness = Harness(kind, policy, response)
        val run = async { harness.run() }
        try {
            assertTrue(input.entered.await(2, TimeUnit.SECONDS))
            policy.allowed = false
            val expected = if (kind == Kind.DIRECT) SaveError.RESOURCE_RELEASE_UNCONFIRMED else SaveError.RECOVERY_REQUIRED
            assertEquals(expected, run.failure())
            assertTrue(harness.active())
            assertTrue(harness.draining())
        } finally { input.release.countDown() }
    }

    @Test fun bodyLinkageWithOutputCloseLinkageRetainsLaneInsteadOfBecomingWifiWait() {
        for (kind in listOf(Kind.DIRECT, Kind.SEGMENT)) {
            val policy = Policy()
            val input = BlockingInput().also { it.readLinkage = true }
            val response = Response(body = input).also { it.onDisconnect = { input.release.countDown() } }
            val harness = Harness(kind, policy, response).also { it.output.closeLinkage = true }
            val run = async { harness.run() }
            try {
                assertTrue(input.entered.await(2, TimeUnit.SECONDS))
                policy.allowed = false
                val expected = if (kind == Kind.DIRECT) SaveError.RESOURCE_RELEASE_UNCONFIRMED else SaveError.STORAGE_FAILURE
                assertEquals(expected, run.failure())
                assertTrue(input.closed)
                assertTrue(harness.active())
                assertTrue(harness.draining())
            } finally { input.release.countDown() }
        }
    }

    @Test fun failedGuardInitializationClosesUnclaimedConnection() {
        for (linkage in listOf(false, true)) {
            val response = Response()
            var unreleased = false
            try {
                initializeNetworkOwner(response, { unreleased = true }) {
                    if (linkage) throw LinkageError("synthetic-thread-start")
                    else throw IllegalStateException("synthetic-thread-start")
                }
                fail("Expected initialization failure")
            } catch (failure: SaveFailure) {
                assertEquals(SaveError.UNKNOWN_TECHNICAL_ERROR, failure.code)
            } catch (_: IllegalStateException) { assertFalse(linkage) }
            assertEquals(1, response.disconnects.get())
            assertFalse(unreleased)
        }
    }

    @Test fun failedGuardInitializationAndDisconnectNeverReleaseTheLane() {
        for (linkage in listOf(false, true)) {
            val response = Response().also { it.onDisconnect = {
                if (linkage) throw LinkageError("synthetic-disconnect")
                else throw IOException("synthetic-disconnect")
            } }
            var unreleased = false
            fails(SaveError.RESOURCE_RELEASE_UNCONFIRMED) {
                initializeNetworkOwner(response, { unreleased = true }) { throw LinkageError("synthetic-thread-start") }
            }
            assertTrue(unreleased)
            assertEquals(1, response.disconnects.get())
        }
    }

    @Test fun inputGetterLinkageClosesOutputBeforeReleasingLane() {
        for (kind in listOf(Kind.DIRECT, Kind.SEGMENT)) {
            val response = Response().also { it.inputFailure = LinkageError("synthetic-input-getter") }
            val harness = Harness(kind, Policy(), response)
            fails(SaveError.UNKNOWN_TECHNICAL_ERROR) { harness.run() }
            assertEquals(1, harness.output.opens)
            assertTrue(harness.output.closed)
            assertEquals(1, response.disconnects.get())
            assertFalse(harness.active())
            assertFalse(harness.draining())
        }
    }

    @Test fun inputGetterLinkageAndFailedOutputCloseRetainRecoveryFence() {
        for (kind in listOf(Kind.DIRECT, Kind.SEGMENT)) {
            for (linkage in listOf(false, true)) {
                val response = Response().also { it.inputFailure = LinkageError("synthetic-input-getter") }
                val harness = Harness(kind, Policy(), response).also {
                    if (linkage) it.output.closeLinkage = true else it.output.failClose = true
                }
                fails(SaveError.STORAGE_FAILURE) { harness.run() }
                assertEquals(1, harness.output.opens)
                assertFalse(harness.output.closed)
                assertEquals(1, response.disconnects.get())
                assertTrue(harness.active())
                assertTrue(harness.draining())
                fails(SaveError.RECOVERY_REQUIRED) { harness.run() }
            }
        }
    }

    private enum class Kind { DIRECT, MANIFEST, SEGMENT }
    private fun forEachKind(action: (Kind) -> Unit) = Kind.entries.forEach(action)

    private class Policy : TransferNetworkPolicy {
        @Volatile var allowed = true
        @Volatile var readerFailed = false
        var rejectAcquisition = Int.MAX_VALUE
        val generation = AtomicInteger()
        val acquisitions = AtomicInteger()
        var lastBinding: TransferNetworkBinding? = null
        override fun acquire(): TransferNetworkBinding {
            if (acquisitions.incrementAndGet() >= rejectAcquisition) throw SaveFailure(SaveError.WIFI_REQUIRED)
            val approvedGeneration = generation.get()
            return object : TransferNetworkBinding {
                override fun check() {
                    if (readerFailed) throw IllegalStateException("synthetic-private-detail")
                    if (!allowed || generation.get() != approvedGeneration) throw SaveFailure(SaveError.WIFI_REQUIRED)
                }
                override fun openConnection(uri: URI): URLConnection = throw AssertionError("No real connection")
                override fun resolve(host: String): Array<InetAddress> = throw AssertionError("No real DNS")
                override fun createSocket(): Socket = throw AssertionError("No real socket")
            }.also { lastBinding = it }
        }
    }

    private class Harness(val kind: Kind, policy: TransferNetworkPolicy, response: Response,
                          val cancel: CancellationSource = CancellationSource()) {
        val factory = Factory(response)
        val output = Output()
        private val direct = SystemHttpsDirectTransfer(output, connections = factory, networkPolicy = policy)
        private val hls = SystemHttpsHlsFetcher(connections = factory, networkPolicy = policy)
        private val url = SourceUrl("https://media.example.invalid/selected")
        private val resource = SelectedMediaResource("candidate", "variant", url)
        private val context = OperationContext("network-test", System.currentTimeMillis() + 30_000, cancel)
        private val limits = SaveLimits(1024, 4096, noProgressTimeoutMillis = 5_000)
        private val progress = object : TransferProgress {
            override fun onBytes(delta: Long) = Unit
            override fun onRestart() = Unit
            override fun onCheckpoint(checkpoint: DownloadCheckpoint) = Unit
        }
        fun run() { when (kind) {
            Kind.DIRECT -> direct.transfer(resource, StagingArtifact("owned", "commit"), null, context, limits, progress)
            Kind.MANIFEST -> hls.fetchManifest(resource, url, context, limits, 1024, progress)
            Kind.SEGMENT -> hls.fetchSegment(resource, url, output, context, limits, 1024, progress)
        } }
        fun active() = if (kind == Kind.DIRECT) direct.isTransferActive else hls.isTransferActive
        fun draining() = if (kind == Kind.DIRECT) direct.requiresProcessRecovery() else hls.requiresProcessRecovery()
    }

    private class Output : NetworkStagingAccess, HlsSegmentSink {
        var opens = 0; var size = 0L; var closed = false; var failClose = false; var closeLinkage = false
        override fun length(artifact: StagingArtifact) = size
        override fun availableBytes(artifact: StagingArtifact) = Long.MAX_VALUE
        override fun rewind(artifact: StagingArtifact, length: Long) { size = length }
        override fun sync(artifact: StagingArtifact) = Unit
        override fun open(artifact: StagingArtifact, append: Boolean) = open()
        override fun availableBytes() = Long.MAX_VALUE
        override fun length() = size
        override fun sync() = Unit
        override fun open(): OutputStream {
            opens++
            return object : OutputStream() {
                override fun write(value: Int) { size++ }
                override fun write(value: ByteArray, offset: Int, count: Int) { size += count }
                override fun close() {
                    if (closeLinkage) throw LinkageError("synthetic-output-close")
                    if (failClose) throw IOException("synthetic-output-close")
                    closed = true
                }
            }
        }
    }

    private class Response(private val status: Int = 200,
                           private val body: InputStream = ByteArrayInputStream("#EXTM3U\n".toByteArray()),
                           private val headers: Map<String, String> = emptyMap()) : HttpURLConnection(URL("https://fixture.invalid/")) {
        val disconnects = AtomicInteger()
        val responseRequests = AtomicInteger()
        var inputRequests = 0
        var inputFailure: Throwable? = null
        var onDisconnect: (() -> Unit)? = null
        var beforeResponse: (() -> Unit)? = null
        override fun connect() = Unit
        override fun disconnect() { disconnects.incrementAndGet(); onDisconnect?.invoke() }
        override fun usingProxy() = false
        override fun getResponseCode(): Int { responseRequests.incrementAndGet(); beforeResponse?.invoke(); return status }
        override fun getHeaderFields(): Map<String, List<String>> = headers.mapValues { listOf(it.value) }
        override fun getInputStream(): InputStream { inputRequests++; inputFailure?.let { throw it }; return body }
    }

    private class Factory(private val response: Response) : DirectConnectionFactory {
        var opens = 0
        var onOpen: (() -> Unit)? = null
        var lastBinding: TransferNetworkBinding? = null
        override fun open(uri: URI, connectTimeoutMillis: Int): HttpURLConnection = throw AssertionError("Binding was discarded")
        override fun open(uri: URI, connectTimeoutMillis: Int, network: TransferNetworkBinding): HttpURLConnection {
            opens++; lastBinding = network; onOpen?.invoke(); return response
        }
    }

    private class BlockingInput : InputStream() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var closed = false; var failClose = false; var closeLinkage = false; var readLinkage = false
        override fun read(): Int {
            entered.countDown(); awaitUninterruptibly(release)
            if (readLinkage) throw LinkageError("synthetic-read")
            throw IOException("synthetic-private-detail")
        }
        override fun read(value: ByteArray, offset: Int, count: Int) = read()
        override fun close() {
            if (closeLinkage) throw LinkageError("synthetic-input-close")
            if (failClose) throw IOException("synthetic-input-close")
            closed = true
        }
    }
    private class Run(val finished: CountDownLatch, private val error: AtomicReference<Throwable?>) {
        fun failure(): SaveError {
            assertTrue("Controlled request did not release", finished.await(3, TimeUnit.SECONDS))
            assertTrue("Expected sanitized failure, got ${error.get()?.javaClass?.simpleName}", error.get() is SaveFailure)
            return (error.get() as SaveFailure).code
        }
    }
    private fun async(action: () -> Unit): Run {
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        Thread { try { action() } catch (failure: Throwable) { error.set(failure) } finally { done.countDown() } }
            .apply { isDaemon = true; start() }
        return Run(done, error)
    }
    private fun fails(code: SaveError, action: () -> Unit): SaveFailure {
        try { action(); fail("Expected $code") }
        catch (failure: SaveFailure) { assertEquals(code, failure.code); return failure }
        throw AssertionError()
    }
    companion object {
        private fun awaitUninterruptibly(latch: CountDownLatch) {
            var interrupted = false
            while (true) { try { latch.await(); break } catch (_: InterruptedException) { interrupted = true } }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
