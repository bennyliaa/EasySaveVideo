# Wikimedia Commons metadata provider candidate

Status: **verified development integration; Android device acceptance pending**. The original candidate is now a JVM module bridged into Discovery with generic rights-preserving storage and UI. The strict platform transport lives in `app/discovery/network`. No new runtime dependency or copied implementation was added. See [current integration status](../docs/m2-commons/IMPLEMENTATION_AND_ACCEPTANCE.md).

## What is implemented

- Anonymous Action API metadata search restricted to Commons file namespace 6 and `filetype:video`, with `videoinfo` and `info` in a single request.
- Small batches (default 5, maximum 10) because extended metadata is expensive.
- Canonical source page, title, validated raster-thumbnail link, MIME, dimensions and nullable duration. Original/derivative video URLs are not exposed.
- Author, custom attribution, credit, license name/link, usage terms, restrictions, deletion flag, source page revision, observation time and per-field provenance are retained in typed rights metadata. All values remain source claims requiring review, never download authority.
- Complete supported continuation objects are replayed without invented offsets. Opaque local cursors are query/version-bound, bounded and expire after five minutes. An unrecognized continuation key fails closed for contract review.
- Strict UTF-8/JSON parsing, plain-text HTML conversion, explicit URL allowlists, 2 MiB response limit, at most 15 seconds overall and a 5-second connect budget, cancellation, serial calls, finite in-memory cache and `Retry-After` cooldown.
- An isolated one-worker runtime reports cancel/deadline results without blocking a UI thread. An uncooperative transport/cancellation hook retains its physical slot rather than allowing retry to leak more workers.

## Defaults and integration gates

`CommonsClientConfig()` is disabled and has no contact or application-version fallback. The actual build version and a real approved public operator contact must be injected; the user-approved contact used for the three public probes is deliberately not hardcoded, repeated in documentation, or copied into test data. Tests use an `.invalid` contact only with an in-memory fake transport.

`CommonsSearchRequest.safeSearchRequired` defaults to `true`. Commons has no verified equivalent filter in this implementation. A true value returns `UNSUPPORTED_FILTER` before I/O. An integrating UI must preserve the existing safe-search default and explain the limitation. Only an explicit user choice to turn off that filter may send a request with `false`; never silently downgrade it.

The production adapter uses exact anonymous Commons HTTPS requests, checked/pinned public peers, system TLS, bounded HTTP parsing and real resource cancellation. It is tested through offline adversarial seams; Android and live endpoint interoperability remain NOT RUN. Source registration is conditional on an injected approved service contact, and the default safe-search refusal remains visible before any query. The integrated UI and version-2 database preserve complete rights. [The integration plan](docs/INTEGRATION_PLAN.md) is retained as historical design evidence, not the current implementation status.

This candidate does not download thumbnails or videos, use user accounts/tokens, subscribe, register, publish, or deploy anything. It is an open-media-library source, not web-wide search.

## Verification

Run from the repository root:

```sh
commons-provider/scripts/check-standalone.sh
```

This script uses the already-cached Kotlin 2.2.10 compiler, existing core/discovery sources, and cached JUnit 4.13.2. It compiles into a temporary directory and runs only this candidate's JVM tests. It does not invoke Gradle or download artifacts. Set `ESV_GRADLE_CACHE` if the existing approved cache is elsewhere.

Recorded evidence and source terms: [protocol and rights contract](docs/CONTRACT.md), [capture provenance](docs/evidence/README.md), and [deferred integration plan](docs/INTEGRATION_PLAN.md). Tests using modified responses are explicitly synthetic mutations of recorded JSON and are never described as captures.
