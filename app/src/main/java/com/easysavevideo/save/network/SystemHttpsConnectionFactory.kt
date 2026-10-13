package com.easysavevideo.save.network

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/** Injection seam for deterministic response scripts. Release always constructs SystemHttpsConnectionFactory. */
fun interface DirectConnectionFactory {
    fun open(uri: URI, connectTimeoutMillis: Int): HttpURLConnection

    /** Scripted factories retain the existing SAM; the release factory consumes the selected route. */
    fun open(uri: URI, connectTimeoutMillis: Int, network: TransferNetworkBinding): HttpURLConnection =
        open(uri, connectTimeoutMillis)
}

/** Default system trust/hostname validation are retained. No user certificates or TLS bypass exists here. */
class SystemHttpsConnectionFactory : DirectConnectionFactory {
    override fun open(uri: URI, connectTimeoutMillis: Int): HttpURLConnection =
        open(uri, connectTimeoutMillis, UnrestrictedTransferNetworkBinding)

    override fun open(uri: URI, connectTimeoutMillis: Int, network: TransferNetworkBinding): HttpURLConnection {
        network.requireApproved()
        HttpsRequestPolicy.validate(uri)
        // URLConnection otherwise consults the process-global CookieHandler behind our explicit headers.
        // Never install, replace or temporarily disable a global handler, which would race other requests.
        if (CookieHandler.getDefault() != null) throw SaveFailure(SaveError.POLICY_BLOCKED)
        network.requireApproved()
        val opened = network.openConnection(uri)
        val connection = opened as? HttpsURLConnection
        if (connection == null) {
            if (opened is HttpURLConnection) closeRejected(opened)
            throw SaveFailure(SaveError.POLICY_BLOCKED)
        }
        try {
            network.requireApproved()
            connection.sslSocketFactory = PublicPeerTlsSocketFactory(connection.sslSocketFactory, connectTimeoutMillis, network)
            network.requireApproved()
            return connection
        } catch (failure: Exception) {
            closeRejected(connection)
            throw failure
        } catch (_: LinkageError) {
            closeRejected(connection)
            throw SaveFailure(SaveError.UNKNOWN_TECHNICAL_ERROR)
        }
    }

    private fun closeRejected(connection: HttpURLConnection) {
        try { connection.disconnect() }
        catch (_: Exception) { throw SaveFailure(SaveError.RECOVERY_REQUIRED) }
        catch (_: LinkageError) { throw SaveFailure(SaveError.RECOVERY_REQUIRED) }
    }
}

/**
 * Check the actual TCP peer BEFORE TLS wrapping or any HTTP/credential bytes. This also checks a
 * platform-created raw socket, so a second DNS resolution cannot bypass a preflight address check.
 * The layered platform SSLSocket preserves original-host SNI and the connection's hostname verifier.
 */
internal class PublicPeerTlsSocketFactory(private val delegate: SSLSocketFactory, private val timeout: Int,
                                          private val network: TransferNetworkBinding = UnrestrictedTransferNetworkBinding) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        try {
            network.requireApproved()
            if (!socket.isConnected) throw SaveFailure(SaveError.POLICY_BLOCKED)
            HttpsRequestPolicy.requirePublic(socket.inetAddress)
            network.requireApproved()
            return delegate.createSocket(socket, host, port, autoClose)
        } catch (failure: Exception) {
            closeRejected(socket)
            throw failure
        } catch (_: LinkageError) {
            closeRejected(socket)
            throw SaveFailure(SaveError.UNKNOWN_TECHNICAL_ERROR)
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
    private fun closeRejected(socket: Socket) {
        try { socket.close() }
        catch (_: Exception) { throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
        catch (_: LinkageError) { throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
    }

    private fun connect(host: String, port: Int, local: InetAddress?, localPort: Int): Socket {
        network.requireApproved()
        val addresses = network.resolve(host)
        network.requireApproved()
        if (addresses.isEmpty()) throw SaveFailure(SaveError.POLICY_BLOCKED)
        addresses.forEach(HttpsRequestPolicy::requirePublic)
        network.requireApproved()
        val socket = network.createSocket()
        try {
            network.requireApproved()
            if (local != null) socket.bind(InetSocketAddress(local, localPort))
            network.requireApproved()
            socket.connect(InetSocketAddress(addresses.first(), port), timeout)
            network.requireApproved()
            HttpsRequestPolicy.requirePublic(socket.inetAddress)
            return delegate.createSocket(socket, host, port, true)
        } catch (failure: Exception) {
            closeRejected(socket)
            throw failure
        } catch (_: LinkageError) {
            closeRejected(socket)
            throw SaveFailure(SaveError.UNKNOWN_TECHNICAL_ERROR)
        }
    }
}
