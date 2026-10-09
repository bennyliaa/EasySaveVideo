package com.easysavevideo.save.network

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URI

class HttpsRequestPolicyTest {
    @Test fun httpsOnlyAndAllSpecialUseAddressesAreBlocked() {
        listOf("http://example.invalid/a", "https://user:pass@example.invalid/a", "https://localhost/a",
            "https://example.local/a", "https://2130706433/a", "https://127.1/a", "https://10.0.0.1/a",
            "https://192.168.1.1/a", "https://100.64.0.1/a", "https://169.254.169.254/a", "https://[::1]/a",
            "https://[fd00::1]/a", "https://[::ffff:127.0.0.1]/a", "https://[2001:db8::1]/a",
            "https://[2002:7f00:1::]/a", "https://example.invalid:0/a", "https://example.invalid/a#frag").forEach {
            try { HttpsRequestPolicy.validate(URI(it)); fail("Unblocked synthetic URL") } catch (failure: SaveFailure) { assertEquals(SaveError.POLICY_BLOCKED, failure.code) }
        }
        HttpsRequestPolicy.requirePublic(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
    }

    @Test fun strongEtagCannotContainWeakSyntaxWhitespaceOrControl() {
        listOf(null, "W/\"tag\"", "tag", "\"a b\"", "\"a\r\nb\"", "\"a\"b\"", "\"a\u007fb\"").forEach { assertNull(HttpsRequestPolicy.strongEtag(it)) }
        assertEquals("\"version-1\"", HttpsRequestPolicy.strongEtag("\"version-1\""))
    }

    @Test fun leaseIsExactResourceExpiringBoundedRevocableAndRedacted() {
        var now = 100L
        val store = RequestContextLeaseStore(EpochClock { now })
        val url = SourceUrl("https://public.example.invalid/private?token=secret")
        val target = URI(url.value)
        val ref = store.put(url, cookie = "session=secret", authorization = "Bearer secret", ttlMillis = 10)
        assertEquals("session=secret", store.headers(ref, target, target, true)["Cookie"])
        assertTrue(store.headers(ref, target, target, false).isEmpty())
        try { store.headers(ref, target, URI("https://public.example.invalid/other"), true); fail() } catch (failure: SaveFailure) { assertEquals(SaveError.POLICY_BLOCKED, failure.code) }
        now = 110
        try { store.headers(ref, target, target, true); fail() } catch (failure: SaveFailure) { assertEquals(SaveError.AUTHENTICATION_REQUIRED, failure.code) }
        val other = store.put(url, cookie = "another=secret")
        store.revoke(other)
        try { store.headers(other, target, target, true); fail() } catch (failure: SaveFailure) { assertEquals(SaveError.AUTHENTICATION_REQUIRED, failure.code) }
        assertFalse(store.toString().contains("secret")); assertFalse(ref.toString().contains("secret"))
    }

    @Test fun headerInjectionAndOversizedOrUnboundedLeaseAreRejected() {
        val store = RequestContextLeaseStore()
        val url = SourceUrl("https://example.invalid/a")
        listOf("a\r\nAuthorization: leak", "a\u0000b", "a".repeat(16385)).forEach {
            try { store.put(url, cookie = it); fail() } catch (failure: SaveFailure) { assertEquals(SaveError.INVALID_REQUEST, failure.code) }
        }
        repeat(32) { store.put(url) }
        try { store.put(url); fail() } catch (failure: SaveFailure) { assertEquals(SaveError.BUSY, failure.code) }
        store.clear(); store.put(url)
    }
}
