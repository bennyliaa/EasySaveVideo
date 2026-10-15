package com.easysavevideo.app.browser.privacy

import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.WeakHashMap

/** Application/process-owned. Never instantiate another backend to pretend a loaded profile is unloaded. */
internal class AndroidPrivateProfileBackend : PrivateProfileBackend {
    private val loaded = mutableMapOf<PrivateProfileName, Profile>()
    private val boundViews = WeakHashMap<WebView, PrivateProfileName>()
    private val unreleasedProfiles = mutableSetOf<PrivateProfileName>()
    private fun main() { check(Looper.myLooper() == Looper.getMainLooper()) }
    override fun capabilities(): PrivateProfileCapabilities {
        main()
        return try { PrivateProfileCapabilities(
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA),
            WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO),
        ) } catch (_: RuntimeException) { PrivateProfileCapabilities(false, false, false) }
    }
    override fun create(name: PrivateProfileName) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
                main()
                if (name !in loaded) loaded[name] = ProfileStore.getInstance().getOrCreateProfile(name.value)
            } else throw UnsupportedOperationException()
        } else throw UnsupportedOperationException()
    }
    override fun fence(name: PrivateProfileName) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            main()
            val profile = checkNotNull(loaded[name])
            profile.serviceWorkerController.serviceWorkerWebSettings.apply {
                blockNetworkLoads = true; allowFileAccess = false; allowContentAccess = false
            }
            profile.serviceWorkerController.setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse =
                    WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            })
            check(name !in unreleasedProfiles)
        } else throw UnsupportedOperationException()
    }
    fun unpublishedReleaseFailed(session: PrivateBrowsingSession) {
        main(); unreleasedProfiles += session.name
    }
    override fun clearData(name: PrivateProfileName, done: () -> Unit) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
                main()
                check(name !in unreleasedProfiles)
                WebStorageCompat.deleteBrowsingData(checkNotNull(loaded[name]).webStorage) { done() }
            } else throw UnsupportedOperationException()
        } else throw UnsupportedOperationException()
    }
    override fun retireUnloadedMetadata(name: PrivateProfileName) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
                main()
                check(name !in loaded)
                // Do not call getProfile/getOrCreateProfile here: loading would make deletion illegal.
                ProfileStore.getInstance().deleteProfile(name.value)
                // This is metadata retirement after a PRIOR data-deletion callback, not a fresh disk-clear barrier.
            } else throw UnsupportedOperationException()
        } else throw UnsupportedOperationException()
    }
    /** Caller must invoke immediately after new WebView(), before settings/clients/navigation. */
    fun bindNewView(session: PrivateBrowsingSession, view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
                    main(); check(session.isLive())
                    check(session.name in loaded)
                    check(view !in boundViews)
                    WebViewCompat.setProfile(view, session.name.value)
                    boundViews[view] = session.name
                    WebViewCompat.setAudioMuted(view, true)
                    check(WebViewCompat.isAudioMuted(view))
                    check(session.isLive())
                } else throw UnsupportedOperationException()
            } else throw UnsupportedOperationException()
        } else throw UnsupportedOperationException()
    }
    fun mute(view: WebView, muted: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
            main(); check(view in boundViews)
            WebViewCompat.setAudioMuted(view, muted)
            check(WebViewCompat.isAudioMuted(view) == muted)
        } else throw UnsupportedOperationException()
    }
    fun requireBound(session: PrivateBrowsingSession, view: WebView) {
        main(); check(session.isLive()); check(boundViews[view] == session.name)
    }
    /** Never returns the default CookieManager, including when an entry has retired. */
    fun cookieManager(session: PrivateBrowsingSession): CookieManager {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            main(); check(session.isLive())
            return checkNotNull(loaded[session.name]).cookieManager
        } else throw UnsupportedOperationException()
    }
}
