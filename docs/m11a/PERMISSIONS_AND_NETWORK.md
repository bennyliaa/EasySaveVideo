# Permissions and real network boundaries

All paths below are repository-relative. `A/` abbreviates
`app/src/main/kotlin/com/easysavevideo/app/`; `N/` is
`app/src/main/java/com/easysavevideo/save/network/`; `C/` is
`commons-provider/src/main/kotlin/com/easysavevideo/commons/`.
These are code/build-input facts, not current OS/store-rule advice.

## App-authored manifest: five permissions

| Permission | Actual reason and entry point | Request/use boundary |
|---|---|---|
| INTERNET | SystemWebView, optional Commons metadata HTTPS, user-triggered Direct/HLS HTTPS | Declared at `app/src/main/AndroidManifest.xml:3`; no app runtime grant prompt. A source-free startup is possible, but browser/Save are genuine network clients |
| FOREGROUND_SERVICE | Explicit screen recording's non-exported mediaProjection service | Manifest:4,18; `A/recording/RecordingForegroundService.kt:100–102` starts the service; no Discovery background service |
| FOREGROUND_SERVICE_MEDIA_PROJECTION | MediaProjection recording foreground service type | Manifest:5,18. This declaration does not replace fresh system capture consent |
| RECORD_AUDIO | Optional internal playback-audio capture using AudioPlaybackCaptureConfiguration | Manifest:6; `A/recording/RecordingConsentController.kt:175–212` asks only when internal audio selected and permission missing, then requests fresh projection consent; `AndroidRecordingSession.kt:494–509` uses playback capture, not a microphone source |
| POST_NOTIFICATIONS | Recording foreground-service notification is implemented | Manifest:7; **no runtime request entry was found** in app source. Declaration alone is not notification-user-consent handling or native notification acceptance; device/release behavior remains unverified |

MediaProjection consent is a separate per-session system activity, not a sixth
manifest permission. Denial/cancellation must remain distinct from capture success.
The app declares no broad media/storage-read permission in the inspected manifests;
SAF/user-picked content and owned media have separate URI/capability boundaries.

## Dependency-merged declarations: three more uses-permission entries

The small `evidence/observed-{debug,release}-merged-manifest.xml` snapshots were
copied from existing build outputs without rebuilding. They are **not final package
manifests**, and their full regeneration freshness is not asserted by this slice.
Their additional declarations match the pinned dependency manifests/merge origins:

| Permission | Origin and source implication | When requested/used |
|---|---|---|
| ACCESS_NETWORK_STATE | `androidx.media3:media3-exoplayer:1.11.1`, AAR `AndroidManifest.xml:22` | Library declaration, no app runtime grant prompt; does not connect Discovery's missing OS policy adapter |
| WAKE_LOCK | Same pinned ExoPlayer AAR manifest: 23 | No app `setWakeMode`/WakeLock acquisition found. Editor's ExoPlayerAssetLoader sets stuck timeouts to MAX; pinned ExoPlayerImpl:474–482 chooses WAKE_MODE_NONE for that path. This narrow source chain is not a device-wide no-wakelock guarantee |
| com.easysavevideo.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION | `androidx.core:core:1.13.1` manifest, declared with `protectionLevel="signature"` and then used | AndroidX compatibility receiver protection, not a user-facing runtime consent prompt |

Existing release merge-blame lines 29–45 record these dependency origins. The
exact74 index binds their original artifacts. The recording code separately checks
`PowerManager.isInteractive` (`AndroidRecordingSession.kt:457,656`); that is not a
battery/thermal/network Discovery signal adapter. Release `BuildDiscoveryPower.kt`
has no scenarios or selection: policy state remains **NOT_CONNECTED**.

Application settings: backup disabled and extraction exclusions configured;
`usesCleartextTraffic=true`; non-exported LibraryShareProvider grants read URIs;
non-exported recording service; exported launcher Activity. Dependency startup
provider/profile receiver declarations also exist in the snapshots. Final merged
component and permissions review remains a release-package gate.

## What actually contacts another system

1. **Browser:** `A/browser/BrowserInputPolicy.kt:9–18` admits HTTP and HTTPS. Manifest
   cleartext permission is intentional current code, so “all traffic encrypted” is
   false. `SystemBrowserHost.kt:287–307` enables DOM storage, preference-controlled
   JavaScript, first-party cookies and configurable third-party cookies; mixed
   content denied and Safe Browsing enabled. TLS errors cancel (:450–455).
   Websites, their subresources and WebView/platform services are not controlled
   by the app's own no-telemetry finding. Camera/microphone requests are denied and
   geolocation disabled/denied (:303,479–485). A user-picked file URI can be passed
   to a site's file chooser (:256–265,487–496; `MainActivity.kt:429`), so “never
   transmits files” is also false.
2. **Commons metadata:** exact endpoint `https://commons.wikimedia.org/w/api.php`
   (`C/CommonsModels.kt:8`). `A/discovery/CommonsSourceRegistration.kt:18,48–54`
   admits it only with approved build contact/configuration, user/source enablement
   and maintenance availability. Missing configuration can yield zero Providers;
   it does not mean all possible Release builds contain zero Providers.
   Requests contain query text, language, continuation and an app-version/operator-
   contact User-Agent (`CommonsMetadataProvider.kt:95–100,226–232`,
   `CommonsModels.kt:33–37`). Transport uses no browser cookie/auth state, checks
   public peers and rejects redirects (`SystemCommonsTransport.kt:29–31,51–69,
   139–155`). This path fetches metadata, not thumbnails or video bodies. Displayed
   metadata/rights links are not automatically fetched by the Provider.
3. **Direct/Stream Save:** `N/SystemHttpsDirectTransfer.kt:54–89` and
   `SystemHttpsHlsFetcher.kt:125–158` execute actual HTTPS GETs to selected media,
   playlists and segments. `HttpsRequestPolicy.kt:12–32,47–63` rejects HTTP/private
   targets and rechecks redirects. `SystemHttpsConnectionFactory.kt:22–29,42–69`
   has no proxy and verifies connected peers using ordinary TLS validation.
   Same-origin, ready WebView cookies may be leased after explicit selection
   (`A/save/AndroidSaveRuntime.kt:486–491`). `N/RequestContextLeaseStore.kt:18–54`
   keeps credentials in memory, normally 60 s, exact-resource and first-hop only.
   HLS never forwards the playlist credential to another playlist/segment/redirect
   (`SystemHttpsHlsFetcher.kt:147–150`). Source URLs/request metadata are exposed to
   their destinations as necessary for these requests; this is not “no data sent.”
4. **System-browser fallback:** when WebView is unavailable, its explicit UI action
   passes the current full page URL to an external browser/ACTION_VIEW handler
   (`A/ui/BrowserScreen.kt:149`; `A/MainActivity.kt:440–443`). Subsequent network,
   sharing and retention behavior belongs to that handler and is outside ESV control.
5. **Explicit export/share/file choices:** SAF or a receiving application can copy
   or sync output remotely. The app's export dialog warns that a chosen service
   may sync to cloud. Recipient behavior cannot be certified from ESV source.

## “No backend” and background claims must stay narrow

No app-owned account/telemetry-upload/video-hosting/business backend was found in
inspected current main/release source. `CommonsSourceRegistration.kt:19–21` has
empty production package/policy trust roots and no Provider update endpoint;
`ProviderManagementPresentation.kt:12–16` says so. M10's adapter has no payment
SDK/network/account/backend. This is a source/configuration finding, not a promise
that websites, Commons, OS/WebView infrastructure or export providers contact no
servers, collect nothing, or have any particular retention policy.

Discovery stops/suspends off-route/background and for media owners
(`AndroidDiscoveryController.kt:175–200`). Save backgrounding requests pause and
clears credential leases (`AndroidSaveRuntime.kt:180–196`), while explicit recording
uses a real foreground service. Therefore only the bounded Discovery lifecycle rule
is supported; whole-app “no background activity” is incorrect. Actual Android
scheduling/cancellation/timing remain DEVICE_NOT_RUN. Likewise local-first means
local coordination/persistence, not offline internet search.
