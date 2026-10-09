# SearXNG first-layer candidate discovery increment

## Status and boundaries

This is an original compiled Android/JVM HTTPS/JSON client and an explicit local source configuration UI. It is the first-layer increment in the user-approved architecture. It does not complete M0, cross-site production search acceptance, Scrapling integration, or the full two-layer discovery design.

The retained target is:

Search/API/SearXNG plus site-specific providers → bounded candidates → actual Scrapling L1 static validation → conditional L2 Dynamic/XHR and carefully checked Adaptive relocation → confirmed VideoSearchResult → normalization/filtering/deduplication/Seen/ResultPool → cards → original browser page.

The current registered validator is still absent. SearXNG results remain a distinct SOURCE_REPORTED pending type and appear only under “待验证网页”. They never become confirmed just because the search category is videos. Commons remains registered when its existing configuration permits it, and its confirmed-result contract is unchanged. Zero-source browser use still works.

No default public instance is bundled, no public instance was randomly probed, and no real query was sent during this increment. A usable endpoint must separately permit anonymous JSON API access and the intended usage. The existence of a public HTML search page is not API permission or availability. Current official SearXNG documentation says JSON must be enabled by the instance and many public instances disable it. No self-hosting or paid service is presumed.

## Implemented behavior

- Three fixed stable source IDs, searxng-1 through searxng-3, with no configured URL by default
- User-configured credential-free public DNS HTTPS endpoints at exactly /search; no query, fragment, custom port, IP literal, local/private endpoint, secret header or authentication mechanism
- Explicit disclosure/acknowledgment that queries go to the selected service and its upstream search engines
- One process-lived configuration controller/store worker survives Activity recreation. Activities subscribe and detach; accepted writes synchronously fence all current owners, and replacement owners observe pending writes before initialization. Separate claim and operation generations retire stale dialogs without dropping an accepted write's completion
- Configuration writes do not initiate or repeat a query. An irreversible source-write latch fences coordinators independently of lifecycle and power callbacks. Queued configuration snapshots retain their captured intent; coordinator installation is rechecked against current intent/configuration after publishing its reference and again before opening the lifecycle gate
- Durable configuration generation includes revision, so disable/re-enable and edit-away/edit-back retire compatibility for every saved profile while preserving stable source IDs
- Restored checkpoints from a different configuration discard old result pools and cursors, persist the need for a fresh explicit search, and remain unable to dispatch from mode/lifecycle/next-page/retry actions alone
- Existing requested SafeSearch is never silently changed. All current SearXNG service capabilities are UNKNOWN; SafeSearch=true is refused before I/O. Configuring an endpoint does not assert filtering support
- Fixed GET JSON protocol: q, format=json, categories=videos, pageno, language and explicitly requested safesearch=0; no remote expressions, scripts, packages or DSL execution
- Returned item count comes from the actual results array. The legacy number_of_results field is not required or treated as truth
- Endpoint/query/version-bound five-minute session-only cursors, a default maximum of20 search pages, at most100 response candidates and a separate ephemeral pending pool capped at500. A fresh explicit search or process restore cannot reuse an exhausted candidate-only cursor whose cards were discarded
- Pending records are URL-deduplicated, source-provenanced and excluded from confirmed ResultPool/Seen history/checkpoints. Confirmed records suppress matching pending URLs
- Pending metadata is minimal source-reported text only. Missing duration/date/license remain unknown. Reported duration never proves a video duration; excluding unknown durations excludes pending cards
- No automatic fetching of candidate pages, images, thumbnails or media. Explicit “打开候选原网页” uses ordinary browsing with no claimed video identity or Save authority

## Network and parser limits

The new transport follows the existing original strict Commons socket pattern without modifying its implementation. Each fixed source slot retains the same process-owned transport across endpoint edits and enable/disable cycles.

All DNS answers must be public. The actual connected peer is pinned and checked before and after TLS; platform trust, hostname verification and SNI are used. No ambient cookies, authenticators, client keys, proxy selector, browser state, redirects, decompression, pooling or internal retry are used. Requests contain only fixed non-secret metadata headers.

Connection establishment is bounded by5 seconds and each operation by15 seconds, including DNS/TLS/headers/body. Cancellation reports promptly but physical admission remains occupied until worker and cleanup really exit. A stuck close cannot create an unbounded replacement stream. 429/503 Retry-After is preserved before error body/cleanup handling and persists at the retained transport boundary.

Headers are limited to32KiB/100 fields and the entire HTTP wire/body budget to2MiB. JSON is UTF-8 strict, rejects duplicate keys/malformed escapes, limits depth/nodes/string lengths, and checks cancellation during parsing. Candidate page links are display-only HTTP(S) and undergo separate credential/public-host/length validation.

## Second-layer work that remains

Actual Scrapling integration is not replaced with a hand-written parser. The separate Scrapling admission evaluation must establish its deployment placement and complete legal/runtime/network admission. A backend, account, fee or deployment is not authorized by this client increment.

The future validation adapter must distinguish verified page evidence, source-reported candidates, insufficient evidence needing L2, unsupported/unavailable, rejection, deadline and cancellation. Adaptive relocation is only a hint: scope it by provider/page/rule version, validate semantic uniqueness before accepting it, and update baselines only after successful checking. Source metadata, a title match, an Adaptive match, or an unresponsive worker may never promote a candidate to confirmed video evidence.

M0 still requires realistic lawful-page evaluation, false-positive/negative analysis, field completeness, L1/L2/XHR effectiveness, adaptive recovery and resource/concurrency measurement. The prior finite synthetic assessment is not a live-page or Android acceptance claim.

## Verification and delivery

The current milestone's independent review, exact source freeze, strict offline aggregate and inherited regression evidence live under evidence/. The final generated summary is under evidence/runs/final/. Historical tests/fixtures/evidence are preserved. Any identity-only verification adaptation for the new milestone is documented explicitly; behavior assertions are not silently weakened.

No external dependency is added. The new module adds one local project row; the external runtime closure must remain the exact74 already reviewed artifacts. No APK/AAB, emulator/device run, actual endpoint query, server deployment, store/payment, signing, M7D rule runtime or Vault work is included.

Protocol references:
- https://docs.searxng.org/dev/search_api.html
- https://docs.searxng.org/dev/result_types/main/video.html

These references inform an original client implementation, not incorporation of SearXNG AGPL server code. App/client license policy and the service operator's terms remain separate concerns.
