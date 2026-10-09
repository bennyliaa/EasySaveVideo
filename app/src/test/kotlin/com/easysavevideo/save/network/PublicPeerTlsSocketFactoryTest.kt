package com.easysavevideo.save.network

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/** Verifies the actual-peer gate without opening a network socket or replacing system trust. */
class PublicPeerTlsSocketFactoryTest {
    @Test fun privateActualPeerIsClosedBeforeTlsOrCredentialBytes() {
        val platform = RecordingFactory()
        val socket = SyntheticSocket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        try { PublicPeerTlsSocketFactory(platform, 10).createSocket(socket, "public.example.invalid", 443, true); fail() }
        catch (failure: SaveFailure) { assertEquals(SaveError.POLICY_BLOCKED, failure.code) }
        assertTrue(socket.closed)
        assertEquals(0, platform.calls)
    }

    @Test fun publicPeerPreservesOriginalHostAndDelegatesToPlatformTls() {
        val platform = RecordingFactory()
        val socket = SyntheticSocket(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
        val result = PublicPeerTlsSocketFactory(platform, 10).createSocket(socket, "selected.example.invalid", 443, true)
        assertSame(socket, result)
        assertEquals("selected.example.invalid", platform.host)
        assertEquals(1, platform.calls)
        assertFalse(socket.closed)
    }

    @Test fun unconnectedSocketCannotBypassLaterPeerValidation() {
        val platform = RecordingFactory()
        val factory = PublicPeerTlsSocketFactory(platform, 10)
        try { factory.createSocket(); fail() } catch (failure: SaveFailure) { assertEquals(SaveError.POLICY_BLOCKED, failure.code) }
        val socket = SyntheticSocket(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)), connected = false)
        try { factory.createSocket(socket, "selected.example.invalid", 443, true); fail() }
        catch (failure: SaveFailure) { assertEquals(SaveError.POLICY_BLOCKED, failure.code) }
        assertTrue(socket.closed)
        assertEquals(0, platform.calls)
    }

    private class SyntheticSocket(private val address: InetAddress, private val connected: Boolean = true) : Socket() {
        var closed = false
        override fun getInetAddress() = address
        override fun isConnected() = connected
        override fun close() { closed = true }
    }
    private class RecordingFactory : SSLSocketFactory() {
        var host: String? = null; var calls = 0
        override fun getDefaultCipherSuites(): Array<String> = emptyArray()
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket { calls++; this.host = host; return socket }
        override fun createSocket(host: String, port: Int): Socket = throw AssertionError("No real sockets")
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = throw AssertionError("No real sockets")
        override fun createSocket(host: InetAddress, port: Int): Socket = throw AssertionError("No real sockets")
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = throw AssertionError("No real sockets")
    }
}
