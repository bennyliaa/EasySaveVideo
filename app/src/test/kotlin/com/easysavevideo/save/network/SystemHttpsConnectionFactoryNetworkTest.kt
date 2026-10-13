package com.easysavevideo.save.network

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.security.cert.Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/** No real DNS/socket calls: this verifies route identity and system-TLS delegation separately. */
class SystemHttpsConnectionFactoryNetworkTest {
    private val uri = URI("https://selected.example.invalid/video")

    @Test fun suppliedRouteOpensConnectionResolvesAndCreatesSocketWhileTlsAndHostnameStaySystemOwned() {
        val network = Network()
        val tls = network.connection.sslSocketFactory
        val verifier = network.connection.hostnameVerifier
        val connection = SystemHttpsConnectionFactory().open(uri, 1234, network) as HttpsURLConnection
        assertSame(network.connection, connection)
        assertSame(verifier, connection.hostnameVerifier)
        assertNotSame(tls, connection.sslSocketFactory)
        assertArrayEquals(tls.defaultCipherSuites, connection.sslSocketFactory.defaultCipherSuites)
        val socket = connection.sslSocketFactory.createSocket("selected.example.invalid", 443)
        assertSame(network.socket, socket)
        assertEquals(listOf(uri), network.opens)
        assertEquals(listOf("selected.example.invalid"), network.lookups)
        assertEquals(1, network.socketRequests)
        assertEquals(1234, network.socket.connectTimeout)
        assertEquals(network.publicPeer, (network.socket.endpoint as InetSocketAddress).address)
        assertEquals("selected.example.invalid", network.tls.host)
        assertEquals(1, network.tls.calls)
        assertEquals(0, network.connection.disconnects)
    }

    @Test fun disallowedRoutePerformsZeroOpeningDnsOrSocketWork() {
        val network = Network().also { it.allowed = false }
        fails(SaveError.WIFI_REQUIRED) { SystemHttpsConnectionFactory().open(uri, 10, network) }
        assertTrue(network.opens.isEmpty())
        assertTrue(network.lookups.isEmpty())
        assertEquals(0, network.socketRequests)
    }

    @Test fun bindingRejectedAfterOpeningIsDisconnectedBeforeReturning() {
        val network = Network().also { it.afterOpen = { it.allowed = false } }
        fails(SaveError.WIFI_REQUIRED) { SystemHttpsConnectionFactory().open(uri, 10, network) }
        assertEquals(1, network.connection.disconnects)
        assertSame(network.tls, network.connection.sslSocketFactory)
    }

    @Test fun failedDisconnectAfterOpeningReturnsRecoveryInsteadOfNetworkWait() {
        val network = Network().also {
            it.afterOpen = { it.allowed = false }
            it.connection.failDisconnect = true
        }
        fails(SaveError.RECOVERY_REQUIRED) { SystemHttpsConnectionFactory().open(uri, 10, network) }
    }

    @Test fun noDnsFallbackAfterBoundResolverFails() {
        val network = Network().also { it.resolveFailure = IOException("synthetic-private-detail") }
        val connection = SystemHttpsConnectionFactory().open(uri, 10, network) as HttpsURLConnection
        try { connection.sslSocketFactory.createSocket("selected.example.invalid", 443); fail() }
        catch (_: IOException) { }
        assertEquals(1, network.lookups.size)
        assertEquals(0, network.socketRequests)
        assertEquals(0, network.tls.calls)
    }

    @Test fun policyChangeAfterDnsRejectsBeforeSocketCreation() {
        val network = Network().also { it.afterResolve = { it.allowed = false } }
        val connection = SystemHttpsConnectionFactory().open(uri, 10, network) as HttpsURLConnection
        fails(SaveError.WIFI_REQUIRED) { connection.sslSocketFactory.createSocket("selected.example.invalid", 443) }
        assertEquals(0, network.socketRequests)
        assertEquals(0, network.tls.calls)
    }

    @Test fun policyChangeAfterSocketCreationClosesBeforeTcpConnect() {
        val network = Network().also { it.afterSocket = { it.allowed = false } }
        val connection = SystemHttpsConnectionFactory().open(uri, 10, network) as HttpsURLConnection
        fails(SaveError.WIFI_REQUIRED) { connection.sslSocketFactory.createSocket("selected.example.invalid", 443) }
        assertTrue(network.socket.closed)
        assertEquals(0, network.socket.connects)
        assertEquals(0, network.tls.calls)
    }

    @Test fun policyChangeAfterTcpConnectClosesBeforeTls() {
        val network = Network().also { it.socket.afterConnect = { it.allowed = false } }
        val connection = SystemHttpsConnectionFactory().open(uri, 10, network) as HttpsURLConnection
        fails(SaveError.WIFI_REQUIRED) { connection.sslSocketFactory.createSocket("selected.example.invalid", 443) }
        assertTrue(network.socket.closed)
        assertEquals(0, network.tls.calls)
    }

    @Test fun boundDnsApprovalStillCannotBypassActualPrivatePeerCheck() {
        val network = Network().also { it.socket.actualPeer = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)) }
        val connection = SystemHttpsConnectionFactory().open(uri, 10, network) as HttpsURLConnection
        fails(SaveError.POLICY_BLOCKED) { connection.sslSocketFactory.createSocket("selected.example.invalid", 443) }
        assertTrue(network.socket.closed)
        assertEquals(0, network.tls.calls)
    }

    @Test fun boundPrivateDnsFailsBeforeSocketCreation() {
        val network = Network().also { it.dnsAddresses = arrayOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))) }
        val connection = SystemHttpsConnectionFactory().open(uri, 10, network) as HttpsURLConnection
        fails(SaveError.POLICY_BLOCKED) { connection.sslSocketFactory.createSocket("selected.example.invalid", 443) }
        assertEquals(0, network.socketRequests)
    }

    @Test fun layeredPlatformSocketIsClosedWhenRouteChangesBeforeTls() {
        val network = Network()
        val connection = SystemHttpsConnectionFactory().open(uri, 10, network) as HttpsURLConnection
        network.allowed = false
        fails(SaveError.WIFI_REQUIRED) {
            connection.sslSocketFactory.createSocket(network.socket, "selected.example.invalid", 443, true)
        }
        assertTrue(network.socket.closed)
        assertEquals(0, network.tls.calls)
    }

    @Test fun failedRawSocketCloseIsNeverSuppressedByWifiFailure() {
        val network = Network().also {
            it.afterSocket = { it.allowed = false }
            it.socket.failClose = true
        }
        val connection = SystemHttpsConnectionFactory().open(uri, 10, network) as HttpsURLConnection
        fails(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { connection.sslSocketFactory.createSocket("selected.example.invalid", 443) }
        assertEquals(0, network.tls.calls)
    }

    @Test fun failedLayeredSocketCloseIsNeverSuppressedByWifiFailure() {
        val network = Network().also { it.socket.failClose = true }
        val connection = SystemHttpsConnectionFactory().open(uri, 10, network) as HttpsURLConnection
        network.allowed = false
        fails(SaveError.RESOURCE_RELEASE_UNCONFIRMED) {
            connection.sslSocketFactory.createSocket(network.socket, "selected.example.invalid", 443, true)
        }
    }

    @Test fun sslSetupLinkageFailureClosesOpenedConnectionAndSanitizes() {
        val network = Network().also { it.connection.failTlsSetup = true }
        fails(SaveError.UNKNOWN_TECHNICAL_ERROR) { SystemHttpsConnectionFactory().open(uri, 10, network) }
        assertEquals(1, network.connection.disconnects)
    }

    @Test fun sslSetupAndDisconnectLinkageFailureRequiresRecovery() {
        val network = Network().also {
            it.connection.failTlsSetup = true
            it.connection.disconnectLinkage = true
        }
        fails(SaveError.RECOVERY_REQUIRED) { SystemHttpsConnectionFactory().open(uri, 10, network) }
    }

    @Test fun rawAndLayeredTlsLinkageFailuresCloseOwnedSocket() {
        for (layered in listOf(false, true)) {
            val network = Network().also { it.tls.failLinkage = true }
            val connection = SystemHttpsConnectionFactory().open(uri, 10, network) as HttpsURLConnection
            fails(SaveError.UNKNOWN_TECHNICAL_ERROR) {
                if (layered) connection.sslSocketFactory.createSocket(network.socket, "selected.example.invalid", 443, true)
                else connection.sslSocketFactory.createSocket("selected.example.invalid", 443)
            }
            assertTrue(network.socket.closed)
        }
    }

    @Test fun tlsLinkageWithSocketCloseLinkageAlwaysReportsReleaseUnconfirmed() {
        for (layered in listOf(false, true)) {
            val network = Network().also { it.tls.failLinkage = true; it.socket.closeLinkage = true }
            val connection = SystemHttpsConnectionFactory().open(uri, 10, network) as HttpsURLConnection
            fails(SaveError.RESOURCE_RELEASE_UNCONFIRMED) {
                if (layered) connection.sslSocketFactory.createSocket(network.socket, "selected.example.invalid", 443, true)
                else connection.sslSocketFactory.createSocket("selected.example.invalid", 443)
            }
        }
    }

    private class Network : TransferNetworkBinding {
        val publicPeer = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val tls = Tls()
        val connection = Connection().also {
            it.sslSocketFactory = tls
            it.hostnameVerifier = HostnameVerifier { _, _ -> false }
        }
        val socket = RawSocket(publicPeer)
        val opens = mutableListOf<URI>()
        val lookups = mutableListOf<String>()
        var allowed = true; var socketRequests = 0
        var dnsAddresses = arrayOf(publicPeer)
        var afterOpen: (() -> Unit)? = null
        var afterResolve: (() -> Unit)? = null
        var afterSocket: (() -> Unit)? = null
        var resolveFailure: Exception? = null
        override fun check() { if (!allowed) throw SaveFailure(SaveError.WIFI_REQUIRED) }
        override fun openConnection(uri: URI): URLConnection { opens += uri; afterOpen?.invoke(); return connection }
        override fun resolve(host: String): Array<InetAddress> {
            lookups += host; resolveFailure?.let { throw it }; afterResolve?.invoke(); return dnsAddresses
        }
        override fun createSocket(): Socket { socketRequests++; afterSocket?.invoke(); return socket }
    }
    private class Connection : HttpsURLConnection(URL("https://fixture.invalid/")) {
        var disconnects = 0; var failDisconnect = false; var disconnectLinkage = false; var failTlsSetup = false
        override fun connect() = Unit
        override fun disconnect() {
            disconnects++
            if (disconnectLinkage) throw LinkageError("synthetic-disconnect")
            if (failDisconnect) throw IOException("synthetic")
        }
        override fun getSSLSocketFactory(): SSLSocketFactory {
            if (failTlsSetup) throw LinkageError("synthetic-ssl-setup")
            return super.getSSLSocketFactory()
        }
        override fun usingProxy() = false
        override fun getCipherSuite() = "synthetic-cipher"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private class RawSocket(var actualPeer: InetAddress) : Socket() {
        var closed = false; var failClose = false; var closeLinkage = false; var connects = 0
        var endpoint: SocketAddress? = null; var connectTimeout = -1
        var afterConnect: (() -> Unit)? = null
        override fun isConnected() = true
        override fun getInetAddress() = actualPeer
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            connects++; this.endpoint = endpoint; connectTimeout = timeout; afterConnect?.invoke()
        }
        override fun close() {
            if (closeLinkage) throw LinkageError("synthetic-socket-close")
            if (failClose) throw IOException("synthetic")
            closed = true
        }
    }
    private class Tls : SSLSocketFactory() {
        var calls = 0; var host: String? = null; var failLinkage = false
        override fun getDefaultCipherSuites() = arrayOf("synthetic-default")
        override fun getSupportedCipherSuites() = arrayOf("synthetic-supported")
        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            calls++; this.host = host
            if (failLinkage) throw LinkageError("synthetic-tls-wrap")
            return socket
        }
        override fun createSocket(host: String, port: Int): Socket = throw AssertionError("No real sockets")
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = throw AssertionError("No real sockets")
        override fun createSocket(host: InetAddress, port: Int): Socket = throw AssertionError("No real sockets")
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = throw AssertionError("No real sockets")
    }
    private fun fails(code: SaveError, action: () -> Any?): SaveFailure {
        try { action(); fail("Expected $code") }
        catch (failure: SaveFailure) { assertEquals(code, failure.code); return failure }
        throw AssertionError()
    }
}
