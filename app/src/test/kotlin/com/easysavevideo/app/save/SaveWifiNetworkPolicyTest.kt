package com.easysavevideo.app.save

import com.easysavevideo.core.model.SaveSettings
import com.easysavevideo.save.*
import com.easysavevideo.save.network.TransferNetworkBinding
import org.junit.Assert.*
import org.junit.Test
import java.net.*

class SaveWifiNetworkPolicyTest {
    private class Backend : SaveNetworkBackend<String> {
        var snapshot: SaveDefaultNetwork<String>? = SaveDefaultNetwork("wifi-a", true)
        var failRead = false
        var readFailure: Throwable? = null
        var reads = 0; var bindCalls = 0; var defaultCalls = 0
        var onOpen: (() -> Unit)? = null
        var onSocket: (() -> Unit)? = null
        var disconnected = 0; var socketClosed = 0
        var closeFailure: Throwable? = null
        var checkFailure: Throwable? = null
        override fun current(): SaveDefaultNetwork<String>? {
            reads++; readFailure?.let { throw it }; if (failRead) throw IllegalStateException(); return snapshot
        }
        override fun bind(network: String) = delegate().also { bindCalls++ }
        override fun unrestricted() = delegate().also { defaultCalls++ }
        private fun delegate() = object : TransferNetworkBinding {
            override fun check() { checkFailure?.let { throw it } }
            override fun openConnection(uri: URI): URLConnection {
                onOpen?.invoke()
                return object : HttpURLConnection(uri.toURL()) {
                    override fun connect() = Unit
                    override fun usingProxy() = false
                    override fun disconnect() { disconnected++; closeFailure?.let { throw it } }
                }
            }
            override fun resolve(host: String) = arrayOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
            override fun createSocket(): Socket {
                onSocket?.invoke()
                return object : Socket() { override fun close() { socketClosed++; closeFailure?.let { throw it } } }
            }
        }
    }
    private fun blocked(action: () -> Unit) {
        try { action(); fail("Expected Wi-Fi wait") } catch (failure: SaveFailure) { assertEquals(SaveError.WIFI_REQUIRED, failure.code) }
    }
    @Test fun unloadedPreferenceFailsClosedBeforeAnyRouteIsObtained() {
        val backend = Backend(); val policy = SaveWifiNetworkPolicy(backend)
        assertFalse(policy.initialized); blocked { policy.acquire() }
        assertEquals(0, backend.bindCalls); assertEquals(0, backend.defaultCalls)
    }
    @Test fun staleInitializationCannotReplaceExplicitOrAlreadyLoadedSettings() {
        val policy = SaveWifiNetworkPolicy(Backend())
        assertEquals(SaveSettings(true), policy.initialize(SaveSettings(true)))
        assertEquals(SaveSettings(true), policy.initialize(SaveSettings(false)))
        policy.update(SaveSettings(false))
        assertEquals(SaveSettings(false), policy.initialize(SaveSettings(true)))
    }
    @Test fun disabledPreferencePreservesDefaultRouteWithoutInspectingWifi() {
        val backend = Backend().apply { failRead = true }
        val policy = SaveWifiNetworkPolicy(backend); policy.update(SaveSettings())
        policy.acquire().check()
        assertTrue(policy.initialized); assertEquals(0, backend.reads)
        assertEquals(1, backend.defaultCalls); assertEquals(0, backend.bindCalls)
    }
    @Test fun unknownNonWifiAndReadFailureNeverCreateConnections() {
        val backend = Backend(); val policy = SaveWifiNetworkPolicy(backend); policy.update(SaveSettings(true))
        backend.snapshot = null; blocked { policy.acquire() }
        backend.snapshot = SaveDefaultNetwork("cellular", false); blocked { policy.acquire() }
        backend.failRead = true; blocked { policy.acquire() }
        backend.failRead = false; backend.readFailure = LinkageError(); blocked { policy.acquire() }
        assertEquals(0, backend.bindCalls); assertEquals(0, backend.defaultCalls)
    }
    @Test fun wifiIsTransportNotMeteringAndVpnAmbiguityFailsClosed() {
        val wifi = 1
        assertTrue(isSaveWifiRoute(setOf(wifi), wifi, true, false)) // Metering is deliberately not a factor.
        for (other in listOf(0, 2, 3, 4, 5, 6, 7, 8, 999)) {
            assertFalse(isSaveWifiRoute(setOf(other), wifi, true, false))
            assertFalse(isSaveWifiRoute(setOf(wifi, other), wifi, true, false))
        }
        assertFalse(isSaveWifiRoute(emptySet(), wifi, true, false))
        assertFalse(isSaveWifiRoute(setOf(wifi), wifi, false, false))
        assertFalse(isSaveWifiRoute(setOf(wifi), wifi, true, true))
    }
    @Test fun publicTransportProbeIsBoundedAndKeepsUnknownBitsForRejection() {
        val probed = mutableListOf<Int>()
        val transports = reportedSaveTransports { probed.add(it); it == 1 || it == 37 }
        assertEquals((0 until 64).toList(), probed)
        assertEquals(setOf(1, 37), transports)
        assertFalse(isSaveWifiRoute(transports, 1, true, false))
    }
    @Test fun bindingRetainsOneDefaultNetworkAndNeverFallsBack() {
        val backend = Backend(); val policy = SaveWifiNetworkPolicy(backend); policy.update(SaveSettings(true))
        val binding = policy.acquire(); binding.check()
        backend.snapshot = SaveDefaultNetwork("wifi-b", true)
        blocked { binding.check() }; blocked { binding.resolve("fixture.invalid") }
        policy.acquire().check()
        assertEquals(2, backend.bindCalls); assertEquals(0, backend.defaultCalls)
    }
    @Test fun enablingRetiresExistingUnboundConnectionEvenOnWifi() {
        val backend = Backend(); val policy = SaveWifiNetworkPolicy(backend); policy.update(SaveSettings(false))
        val old = policy.acquire(); policy.update(SaveSettings(true)); blocked { old.check() }
        policy.acquire().check(); assertEquals(1, backend.bindCalls)
    }
    @Test fun disablingRestrictionDoesNotRebindAnExistingPinnedSocket() {
        val backend = Backend(); val policy = SaveWifiNetworkPolicy(backend); policy.update(SaveSettings(true))
        val binding = policy.acquire(); backend.snapshot = null
        blocked { binding.check() }; policy.update(SaveSettings(false)); binding.check()
        assertEquals(1, backend.bindCalls); assertEquals(0, backend.defaultCalls)
        policy.acquire(); assertEquals(1, backend.defaultCalls)
    }
    @Test fun changedRouteDuringPlatformOpenClosesReturnedConnection() {
        val backend = Backend(); val policy = SaveWifiNetworkPolicy(backend); policy.update(SaveSettings(true))
        val binding = policy.acquire(); backend.onOpen = { backend.snapshot = null }
        blocked { binding.openConnection(URI("https://fixture.invalid/file")) }
        assertEquals(1, backend.disconnected)
    }
    @Test fun changedRouteDuringSocketCreationClosesReturnedSocket() {
        val backend = Backend(); val policy = SaveWifiNetworkPolicy(backend); policy.update(SaveSettings(true))
        val binding = policy.acquire(); backend.onSocket = { backend.snapshot = null }
        blocked { binding.createSocket() }
        assertEquals(1, backend.socketClosed)
    }
    @Test fun rejectedPlatformConnectionCloseFailureIsRecoveryNotWifiWait() {
        for (failure in listOf(IllegalStateException(), LinkageError())) {
            val backend = Backend(); val policy = SaveWifiNetworkPolicy(backend); policy.update(SaveSettings(true))
            val binding = policy.acquire(); backend.onOpen = { backend.snapshot = null }; backend.closeFailure = failure
            try { binding.openConnection(URI("https://fixture.invalid/file")); fail() }
            catch (error: SaveFailure) { assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, error.code) }
            assertEquals(1, backend.disconnected)
        }
    }
    @Test fun rejectedPlatformSocketCloseFailureIsRecoveryNotWifiWait() {
        for (failure in listOf(IllegalStateException(), LinkageError())) {
            val backend = Backend(); val policy = SaveWifiNetworkPolicy(backend); policy.update(SaveSettings(true))
            val binding = policy.acquire(); backend.onSocket = { backend.snapshot = null }; backend.closeFailure = failure
            try { binding.createSocket(); fail() }
            catch (error: SaveFailure) { assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, error.code) }
            assertEquals(1, backend.socketClosed)
        }
    }

    @Test fun postConnectionCheckLinkageFailureStillClosesItsOwnedObject() {
        for (closeFails in listOf(false, true)) {
            val backend = Backend(); val policy = SaveWifiNetworkPolicy(backend); policy.update(SaveSettings(true))
            val binding = policy.acquire(); backend.onOpen = { backend.checkFailure = LinkageError() }
            if (closeFails) backend.closeFailure = LinkageError()
            try { binding.openConnection(URI("https://fixture.invalid/file")); fail() }
            catch (error: SaveFailure) {
                assertEquals(if (closeFails) SaveError.RESOURCE_RELEASE_UNCONFIRMED else SaveError.WIFI_REQUIRED, error.code)
            }
            assertEquals(1, backend.disconnected)
        }
    }
    @Test fun postSocketCheckLinkageFailureStillClosesItsOwnedObject() {
        for (closeFails in listOf(false, true)) {
            val backend = Backend(); val policy = SaveWifiNetworkPolicy(backend); policy.update(SaveSettings(true))
            val binding = policy.acquire(); backend.onSocket = { backend.checkFailure = LinkageError() }
            if (closeFails) backend.closeFailure = LinkageError()
            try { binding.createSocket(); fail() }
            catch (error: SaveFailure) {
                assertEquals(if (closeFails) SaveError.RESOURCE_RELEASE_UNCONFIRMED else SaveError.WIFI_REQUIRED, error.code)
            }
            assertEquals(1, backend.socketClosed)
        }
    }

}
