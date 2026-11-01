package com.easysavevideo.verification.local

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Synthetic address lists only; no DNS, sockets, TLS or public target is contacted. */
class LocalPublicDnsTest {
    private fun address(a: Int, b: Int, c: Int, d: Int) = InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
    @Test fun everyAnswerScreenedAndOnePublicAddressPinnedOnce() {
        val resolves = AtomicInteger(); val fences = AtomicInteger()
        val dns = LocalPublicDns("public.example", false, { fences.incrementAndGet() }) {
            resolves.incrementAndGet(); listOf(address(93, 184, 216, 34), address(8, 8, 8, 8))
        }
        assertEquals(listOf(address(93, 184, 216, 34)), dns.lookup("public.example"))
        assertEquals(listOf(address(93, 184, 216, 34)), dns.lookup("public.example"))
        assertEquals(1, resolves.get()); assertEquals(3, fences.get())
    }
    @Test fun mixedPrivateEmptyAndChangedHostNeverYieldAPin() {
        for (answers in listOf(emptyList(), listOf(address(127, 0, 0, 1)),
            listOf(address(8, 8, 8, 8), address(10, 0, 0, 1)), listOf(address(169, 254, 169, 254)),
            listOf(address(192, 168, 0, 1)), listOf(address(100, 64, 0, 1)))) {
            val calls = AtomicInteger()
            val dns = LocalPublicDns("public.example", false, {}) { calls.incrementAndGet(); answers }
            repeat(2) { try { dns.lookup("public.example"); fail("Address must fail closed") } catch (_: UnknownHostException) { } }
            assertEquals(1, calls.get())
        }
        val dns = LocalPublicDns("public.example", false, {}) { error("Unexpected resolution") }
        try { dns.lookup("other.example"); fail("Origin changed") } catch (_: UnknownHostException) { }
    }
    @Test fun revocationAfterResolveStopsPinAndLoopbackFixtureCannotBecomePublic() {
        var current = true
        val dns = LocalPublicDns("public.example", false, { check(current) }) { current = false; listOf(address(8, 8, 8, 8)) }
        try { dns.lookup("public.example"); fail("Revoked during DNS") } catch (_: IllegalStateException) { }
        val fixture = LocalPublicDns("127.0.0.1", true, {}) { listOf(address(127, 0, 0, 1), address(8, 8, 8, 8)) }
        try { fixture.lookup("127.0.0.1"); fail("Mixed fixture answers") } catch (_: UnknownHostException) { }
    }
}
