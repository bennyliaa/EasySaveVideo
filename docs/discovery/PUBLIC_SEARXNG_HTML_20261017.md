# Public SearXNG HTML candidate pipeline, 2026-10-17

Status: new implementation on exact restored Git baseline 6d2edfa5680d0163038fde1faa58f693513d0ab8. This is not a byte-identical restoration of inaccessible later checkpoints. Current validation receipts live alongside this copy. No live-search effectiveness, APK, or device test is implied by synthetic tests.

## User scope and admission

The user explicitly requested public SearXNG result sets, followed by ESV's own video identification/filtering, and public-provider evaluation before any local server deployment (2026-10-17 22:50–22:53 UTC). The earlier user approval at 07:08 UTC allowed bounded automatic validation of public HTTPS candidates from an explicit user search, with private-network, authentication, CAPTCHA, DRM and stated-access restrictions preserved. This narrowly supersedes the old compiled-origin-only gate for those ephemeral candidate grants. It does not create a global allowlist, background crawler, persistent URL grant, login authority, media rights or save authorization.

## Implemented protocol

- Explicit JSON/videos versus HTML/general setting. Existing v1 configurations read as JSON without rewriting; v2 stores the format. No error-driven format fallback.
- HTML uses only exact public HTTPS origin, default TLS port, /robots.txt followed by the exact configured /search endpoint (optional deployment prefix is documented in SEARXNG_INSTANCE_LIST_20261018.md). Robots must be readable, supported and allow the actual query path. No POST switch, alternate identity, proxy, redirect or challenge handling.
- Original Simple-template parser extracts bounded page URLs and plain source-reported title/description only. It does not execute scripts, fetch images or follow returned form/continuation URLs. Pagination is reconstructed locally and is session-scoped.
- SafeSearch support remains unknown and the enabled filter refuses before I/O. Users must make the existing explicit filter choice; no silent default weakening.
- Same service in multiple slots is rejected. Physical service admission and Retry-After are shared by origin within the process, across slot changes and formats. Full registries fail closed rather than evicting active cooldowns.
- Search reserves its enforced request/response envelope in the same nonrefunding ledger used by B. Default session ceiling: 24 distinct candidate pages, 72 requests, 12 MiB application payload reservations and 60 seconds. HTML source: at most two requests and 2 MiB aggregate response payload per page, including at most32KiB robots. At most two result pages by default. Each validation reserves up to three requests/512KiB; B further narrows to256KiB total payload and its existing deadline.
- These are application payload budgets, not exact HTTP/TLS wire-byte measurements. Canceled, failed or unknown consumption does not refund reservations.
- Original registered provider identity, query/session, source generation, exact candidate object and operation bind an opaque one-use grant. Restore, background/power/source fences and expiry revoke it. Duplicate URLs retain all provenance and do not cause duplicate B fetches; capped/unrun candidates remain unverified.
- B checks all DNS answers and pins a public peer for robots/page, verifies normal TLS, refuses redirects/authentication/challenges/DRM, and honors robots before the page. The existing narrow local static/video evidence parser and inline-only optional renderer remain the actual validation capabilities. Candidate links alone never become video/save evidence.

## Dependencies and operator policies

The HTML client/parser is original ESV code. No SearXNG server/AGPL implementation or template source is embedded. It uses already admitted Jsoup1.21.2 (MIT), SHA256 f05496e255734759f0d4b5632da7b24f81313147c78c69e90ad045d096191344; only an additional module dependency edge/lock membership is needed, with no runtime GAV/version expansion. Existing strict artifact verification and runtime95 admission stay enabled. This is a technical admission record, not a blanket legal guarantee for operators or result content.

Official interface references reviewed 2026-10-17:
- https://docs.searxng.org/dev/search_api.html
- https://docs.searxng.org/dev/templates.html
- https://docs.searxng.org/admin/searx.limiter.html

Search text goes to the selected operator and upstream services. Operator logging varies; no no-log claim is made. Relevant current notices:
- https://ononoki.org/privacy-policy.html
- https://www.tiekoetter.com/en/legal-notice/

## Current public qualification

At23:05UTC a single ordinary robots GET per example instance found:
- searx.ononoki.org:429 without Retry-After. Stopped; no search attempted.
- searx.tiekoetter.com:200 with User-agent:* and Disallow:/*?*q=*. The search query route is disallowed. No search attempted, including no POST/UA/WebView workaround.

Neither instance passed live provider qualification. No public search HTML sample or app effectiveness numbers are available. Directory online status is not ESV compatibility/permission proof. A separately appropriate public instance or operator authorization is required before an actual automatic public-search trial.

## Remaining evaluation

Synthetic tests establish only the finite contracts they exercise. Public access, actual template compatibility, cross-site candidate breadth, first-visible/next-page timing, B video yield and save1/2/3 outcomes require one integrated real session later. Overall unique pages count once; provenance can overlap per-provider attribution. Fixed-plan N remains fixed; unrun/blocked counts and unknown rates must be explicit. Do not infer save rights or success from a candidate, metadata title, robots allow or page-video evidence.
