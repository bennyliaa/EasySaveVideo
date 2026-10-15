package com.easysavevideo.app.discovery.thumbnail

import java.net.Proxy
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Inspects the real OkHttp client without executing a call or resolving an allowed host. */
class CommonsAnonymousImageClientTest {
    @Test fun imageClientHasNoCookiesCredentialsProxyRedirectRetryCacheOrLoggingHooks() {
        val client = anonymousCommonsImageClient()
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertSame(Proxy.NO_PROXY, client.proxy)
        assertNull(client.cache)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        val call = client.newCall(Request.Builder().url("https://upload.wikimedia.org/owned-fixture").build())
        assertFalse(call.isExecuted())
        assertSame(EventListener.NONE, client.eventListenerFactory.create(call))
    }

    @Test fun cookieJarIgnoresResponseCookiesAndNeverSendsThem() {
        val jar = anonymousCommonsImageClient().cookieJar
        val url = "https://upload.wikimedia.org/owned-fixture".toHttpUrl()
        val cookie = Cookie.Builder().name("owned_fixture").value("not-a-credential")
            .hostOnlyDomain(url.host).path("/").build()
        jar.saveFromResponse(url, listOf(cookie))
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test fun imageClientUsesBoundedTimeoutsAndRejectsNonImageHostsBeforeDns() {
        val client = anonymousCommonsImageClient()
        assertEquals(5_000, client.connectTimeoutMillis)
        assertEquals(8_000, client.readTimeoutMillis)
        assertEquals(8_000, client.callTimeoutMillis)
        assertNotSame(Dns.SYSTEM, client.dns)
        for (host in listOf("localhost", "127.0.0.1", "commons.wikimedia.org", "example.invalid"))
            assertThrows(IllegalStateException::class.java) { client.dns.lookup(host) }
        assertEquals(setOf("upload.wikimedia.org", "thumb.wikimedia.org"), CommonsThumbnailPolicy.hosts)
        assertEquals(setOf("image/jpeg", "image/png", "image/webp"), CommonsThumbnailPolicy.mimeTypes)
    }
}
