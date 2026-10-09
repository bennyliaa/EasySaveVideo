package com.easysavevideo.save.network

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/** Injection seam for deterministic response scripts. Release always constructs SystemHttpsConnectionFactory. */
fun interface DirectConnectionFactory {
    fun open(uri: URI, connectTimeoutMillis: Int): HttpURLConnection
}

/** Default system trust/hostname validation are retained. No user certificates or TLS bypass exists here. */
class SystemHttpsConnectionFactory : DirectConnectionFactory {
    override fun open(uri: URI, connectTimeoutMillis: Int): HttpURLConnection {
        HttpsRequestPolicy.validate(uri)
        // URLConnection otherwise consults the process-global CookieHandler behind our explicit headers.
        // Never install, replace or temporarily disable a global handler, which would race other requests.
        if (CookieHandler.getDefault() != null) throw SaveFailure(SaveError.POLICY_BLOCKED)
        val connection = uri.toURL().openConnection(Proxy.NO_PROXY) as? HttpsURLConnection
            ?: throw SaveFailure(SaveError.POLICY_BLOCKED)
        connection.sslSocketFactory = PublicPeerTlsSocketFactory(connection.sslSocketFactory, connectTimeoutMillis)
        return connection
    }
}

/**
 * Check the actual TCP peer BEFORE TLS wrapping or any HTTP/credential bytes. This also checks a
 * platform-created raw socket, so a second DNS resolution cannot bypass a preflight address check.
 * The layered platform SSLSocket preserves original-host SNI and the connection's hostname verifier.
 */
internal class PublicPeerTlsSocketFactory(private val delegate: SSLSocketFactory, private val timeout: Int) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        try {
            if (!socket.isConnected) throw SaveFailure(SaveError.POLICY_BLOCKED)
            HttpsRequestPolicy.requirePublic(socket.inetAddress)
            return delegate.createSocket(socket, host, port, autoClose)
        } catch (failure: Exception) {
            try { socket.close() } catch (_: Exception) { }
            throw failure
        }
    }
    override fun createSocket(host: String, port: Int): Socket = connect(host, port, null, 0)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connect(host, port, localHost, localPort)
    override fun createSocket(host: InetAddress, port: Int): Socket = connect(host.hostAddress!!, port, null, 0)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        connect(address.hostAddress!!, port, localAddress, localPort)
    // An unconnected socket would let a caller bypass the peer check later. Fail closed on that API.
    override fun createSocket(): Socket = throw SaveFailure(SaveError.POLICY_BLOCKED)
    private fun connect(host: String, port: Int, local: InetAddress?, localPort: Int): Socket {
        val addresses = InetAddress.getAllByName(host)
        if (addresses.isEmpty()) throw SaveFailure(SaveError.POLICY_BLOCKED)
        addresses.forEach(HttpsRequestPolicy::requirePublic)
        val socket = Socket()
        try {
            if (local != null) socket.bind(InetSocketAddress(local, localPort))
            socket.connect(InetSocketAddress(addresses.first(), port), timeout)
            HttpsRequestPolicy.requirePublic(socket.inetAddress)
            return delegate.createSocket(socket, host, port, true)
        } catch (failure: Exception) {
            try { socket.close() } catch (_: Exception) { }
            throw failure
        }
    }
}
