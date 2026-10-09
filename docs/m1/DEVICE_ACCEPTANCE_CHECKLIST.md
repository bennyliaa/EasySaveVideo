# M1 device acceptance checklist — all NOT_RUN

Use authorized test pages/media and a documented WebView version. No real account, payment or production upload is required. No package/device work was authorized in this implementation turn.

- [ ] Android12/API31 and a current API device launch with zero Providers and offline network
- [ ] Phone and tablet widths, portrait/landscape, large font, TalkBack focus, keyboard and system Back
- [ ] HTTP/HTTPS open, redirect, slow load, DNS timeout, main-frame HTTP errors and SSL cancel
- [ ] Rapid A→B and same-URL reload/stop; old progress/title/finish/error cannot overwrite new request
- [ ] Back/forward after repeated View-generation replacement, failed loads and max-live-view eviction
- [ ] Add/close/switch 12 tabs; cap 1–4 live Views; close selected/background/loading tabs
- [ ] Legitimate inline video and fullscreen entry/exit/Back; no unexpected background playback
- [ ] File input single/multiple/mime, Cancel, repeat, navigate while prompt/picker active, Activity interruption
- [ ] Camera/microphone/geolocation denied; file/content/intent/javascript navigation blocked
- [ ] Shared first-party cookies, default third-party denial, explicit global cookie clear
- [ ] Renderer death per affected View, shared renderer callbacks, healthy tab survival and explicit rebuild
- [ ] Restart address recovery, disabled restore reset, corrupt/truncated/oversized checkpoint; startup timeout
- [ ] Full storage/failed preferences: visible warning, previous recovery bytes preserved until explicit reset
- [ ] SafeERROR retention and bounded queue; console canary/URL/cookie/title never reaches app logs
- [ ] Release has no Fake controls and free Browser works with unavailable entitlement source

Record device/model/API/WebView/fixture IDs, exact app commit and actual outcomes. Device failures must remain open; do not infer PASS from the source or JVM tests.
