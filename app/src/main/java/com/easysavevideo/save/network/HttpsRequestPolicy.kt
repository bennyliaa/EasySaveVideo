package com.easysavevideo.save.network

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Stateless policy is shared by the real connection and the scripted, network-free tests. */
object HttpsRequestPolicy {
    fun validate(uri: URI): URI {
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0 || uri.toASCIIString().length > 8192 ||
            uri.toASCIIString().any { it <= ' ' || it == '\u007f' }) fail()
        val host = uri.host.lowercase(Locale.ROOT).trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host == "metadata.google.internal") fail()
        // Numeric literals are checked before opening anything. DNS names are checked against the
        // actual connected socket peer by PublicPeerTlsSocketFactory, preventing DNS rebind leakage.
        if (host.startsWith('[') || host.matches(Regex("[0-9.]+"))) {
            val address = try { InetAddress.getByName(host.removePrefix("[").removeSuffix("]")) }
            catch (_: Exception) { fail() }
            requirePublic(address)
        }
        return uri
    }

    fun redirect(from: URI, location: String): URI {
        if (location.isBlank() || location.length > 8192 || location.any { it <= ' ' || it == '\u007f' }) fail()
        return try { validate(from.resolve(URI(location))) } catch (failure: SaveFailure) { throw failure }
        catch (_: Exception) { fail() }
    }

    fun origin(uri: URI): String = "https://${uri.host.lowercase(Locale.ROOT)}:${if (uri.port == -1) 443 else uri.port}"
    fun canonical(uri: URI): String = origin(uri) + (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") +
        (uri.rawQuery?.let { "?$it" } ?: "")
    fun identity(uri: URI): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(uri).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun strongEtag(value: String?): String? {
        if (value == null || value.length !in 2..1024 || value.first() != '"' || value.last() != '"') return null
        if (value.substring(1, value.length - 1).any { it == '"' || it < '!' || it == '\u007f' }) return null
        return value
    }

    /** Deliberately conservative: only globally routable peers; no cleartext/proxy test exception. */
    fun requirePublic(address: InetAddress) {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) fail()
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 4) {
            val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
            if (a == 0 || a == 10 || a == 127 || a >= 224 || a == 169 && b == 254 ||
                a == 172 && b in 16..31 || a == 192 && b == 168 || a == 100 && b in 64..127 ||
                a == 192 && b == 0 || a == 192 && b == 88 && c == 99 || a == 198 && b in 18..19 ||
                a == 198 && b == 51 && c == 100 || a == 203 && b == 0 && c == 113) fail()
        } else if (bytes.size == 16) {
            // Restrict to global unicast 2000::/3 and reject documentation, Teredo and 6to4.
            if ((bytes[0] and 0xe0) != 0x20 || bytes[0] == 0x20 && bytes[1] == 0x02 ||
                bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0 ||
                bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) fail()
        } else fail()
    }
    private fun fail(): Nothing = throw SaveFailure(SaveError.POLICY_BLOCKED)
}
