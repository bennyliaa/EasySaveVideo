package com.easysavevideo.save.network

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

class RequestContextLiveAuthorityTest {
    private val url = SourceUrl("https://source.example.invalid/selected?synthetic=secret")
    private fun resource(ref: RequestContextRef?, seed: PrivateNetworkSourceSeed = PrivateNetworkSourceSeed.create()) =
        SelectedMediaResource("candidate", "variant", url, requestContextRef = ref,
            sourceIdentity = seed.sourceIdentity.encoded, privateSource = seed)
    private fun fails(action: () -> Any?): SaveFailure = assertThrows(SaveFailure::class.java) { action() }
        .also { assertEquals(SaveError.SOURCE_RESELECTION_REQUIRED, it.code); assertNull(it.cause); assertTrue(it.suppressed.isEmpty()) }

    @Test fun seedAndBoundTaskRequireRealAppAuthorityEvenWithoutCookies() {
        val store = RequestContextLeaseStore()
        fails { store.captureAuthority(resource(null)) }
        fails { store.captureAuthority(resource(store.put(url))) }
        val seed = PrivateNetworkSourceSeed.create()
        val binding = seed.bindTask("task")
        val bound = resource(null, seed).copy(privateSource = binding)
        fails { store.captureAuthority(bound) }
        val ref = store.put(url, liveAuthority = RequestContextLiveAuthority { true })
        assertNotNull(store.captureAuthority(bound.copy(requestContextRef = ref)))
        assertNotNull(store.captureAuthority(resource(ref, seed)))
        seed.revoke()
        fails { store.captureAuthority(bound.copy(requestContextRef = ref)) }
    }

    @Test fun expiryDoesNotRevokeCapturedAuthorityButExplicitRevokeStillDoesAfterPruning() {
        var now = 10L
        val store = RequestContextLeaseStore(EpochClock { now })
        val ref = store.put(url, cookie = "synthetic=secret", ttlMillis = 10,
            liveAuthority = RequestContextLiveAuthority { true })
        val source = resource(ref)
        val captured = store.captureAuthority(source)!!
        now = 20
        store.put(url) // Pruning must retain the issued private fence, never reinterpret eviction as drain.
        captured.check()
        fails { store.captureAuthority(source) }
        assertEquals(SaveError.AUTHENTICATION_REQUIRED, assertThrows(SaveFailure::class.java) {
            store.headers(ref, URI(url.value), URI(url.value), true)
        }.code)
        store.revoke(ref)
        fails { captured.check() }
    }

    @Test fun clearRevokesEveryCapturedFenceAndCallbackCannotReviveAfterFalseOrThrow() {
        val live = AtomicBoolean(true)
        val store = RequestContextLeaseStore()
        val first = store.captureAuthority(resource(store.put(url, liveAuthority = RequestContextLiveAuthority(live::get))))!!
        val second = store.captureAuthority(resource(store.put(url, liveAuthority = RequestContextLiveAuthority { true })))!!
        live.set(false)
        fails { first.check() }
        live.set(true)
        fails { first.check() }
        store.clear()
        fails { second.check() }
        fails { store.put(url, liveAuthority = RequestContextLiveAuthority { throw IllegalStateException("synthetic-secret") }) }
        fails { store.put(url, liveAuthority = RequestContextLiveAuthority { throw UnsatisfiedLinkError("synthetic-secret") }) }
        assertFalse(first.toString().contains("secret"))
    }

    @Test fun callbackRunsOutsideRegistryLockAndWrongSelectedSourceIsRejected() {
        val store = RequestContextLeaseStore()
        val ref = store.put(url, cookie = "synthetic=secret", liveAuthority = RequestContextLiveAuthority {
            assertFalse(Thread.holdsLock(store))
            true
        })
        store.captureAuthority(resource(ref))!!.check()
        assertEquals("synthetic=secret", store.headers(ref, URI(url.value), URI(url.value), true)["Cookie"])
        fails { store.captureAuthority(resource(ref).copy(url = SourceUrl("https://source.example.invalid/other"))) }
    }

    @Test fun revokedCapturedAuthorityFencesFactoryDnsAndSocketEntryPoints() {
        val authority = RequestContextAuthority { true }
        var calls = 0
        val network = object : TransferNetworkBinding {
            override fun check() { calls++ }
            override fun openConnection(uri: URI): java.net.URLConnection { calls++; throw AssertionError("No network") }
            override fun resolve(host: String): Array<java.net.InetAddress> { calls++; throw AssertionError("No DNS") }
            override fun createSocket(): java.net.Socket { calls++; throw AssertionError("No socket") }
        }
        val guarded = authority.guard(network)
        authority.revoke()
        fails { guarded.check() }
        fails { guarded.openConnection(URI(url.value)) }
        fails { guarded.resolve("source.example.invalid") }
        fails { guarded.createSocket() }
        assertEquals(0, calls)
    }

    @Test fun issuedPrivateFencesRemainBoundedUntilExplicitRetirement() {
        var now = 1L
        val store = RequestContextLeaseStore(EpochClock { now })
        val refs = List(32) {
            store.put(url, ttlMillis = 1, liveAuthority = RequestContextLiveAuthority { true }).also {
                store.captureAuthority(resource(it))!!.check()
            }
        }
        now = 2
        assertEquals(SaveError.BUSY, assertThrows(SaveFailure::class.java) { store.put(url) }.code)
        refs.forEach(store::revoke)
        store.put(url)
    }
}
