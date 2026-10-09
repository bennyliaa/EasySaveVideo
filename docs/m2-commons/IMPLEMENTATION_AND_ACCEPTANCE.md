# Wikimedia Commons metadata development integration

Status: verified **metadata development checkpoint**, tag `m2-commons-dev-2026-10-06`. Android device acceptance and package assembly are NOT RUN. The previous restricted HLS baseline is `a3e4f0397ade9ee61b9941f1d5bac69972944b26`; HLS/Save implementations are unchanged by this work.

## What is connected

The original `commons-provider` JVM module is bridged into Discovery with coherent generic rights snapshots. Both app variants can register one named Wikimedia Commons metadata source when its approved service contact is injected. This is an open-media library, not whole-web video search. It exposes file pages and metadata, never a remote video URL or permission to save, play, download or reuse media.

Default safe search remains enabled. Commons cannot provide the required filter, so the coordinator shows an unsupported state and makes zero source/runtime requests. The user must explicitly confirm disabling it. That change creates a new query identity/epoch and cancels stale work. The active filter persists in the existing checkpoint; dismissing the confirmation never changes it. The source can also be disabled, with the browser and zero-provider path remaining available. Debug-only synthetic data remains excluded from Release.

A source singleton preserves physical HTTP admission and server cooldown across controller/query changes. A normalized query and language match generic progress identity; the complete query/filter identity also binds the metadata cache and continuation. Raw supported MediaWiki continuation values are retained verbatim. Server `Retry-After` survives scheduled retry cancellation, query changes and visibility transitions; it cannot be shortened by ordinary backoff. No automatic fetch occurs for a blank query.

## Rights, persistence and presentation

Every result retains a bounded generic `ResultRights` container: up to four coherent source-item observations, with a combined 32 KiB encoded cap. Author, credit, custom attribution, license name/link, usage terms, tri-state copyright/attribution/non-free flags, restrictions, deletion warning, revision, retrieval time, source identity, metadata terms and per-field origin/conversion/truncation survive the bridge and codec. Missing fields remain unknown. API extmetadata is explicitly unstructured extracted text, never blanket CC0 metadata or legal clearance.

Whole-observation merging uses one stable total order: observation time, then revision, then canonical payload. Conflicting reports, differing revisions and prior restrictive warnings remain visible. The latest observed report is not a claim that it is the newest remote revision. A thumbnail is suppressed whenever competing complete rights records differ, rather than mixing stale imagery with another observation. All nested collections are detached. Over-cap merges fail atomically instead of dropping attribution.

The same `discovery_v1.db` moves from schema 1 to 2 by one additive nullable `rights BLOB` column in `results`. Existing rows remain rights-unknown. Rights stay on their result rows, participate in unchanged-row UPSERTs, are evicted with results, and share the transaction with checkpoint, progress and Seen. Oversized/corrupt/future rights fail closed before overwrite; original bytes remain. SQLite checkpoint wire format 2 reads a genuine frozen format-1 fixture. Unknown future database/wire versions fail without destructive recreation. Identity and cursor versions remain separate.

Feature rollback disables the Commons source while retaining the v2-compatible reader and database. Installing the old v1-only binary is not a compatible rollback; its downgrade handler rejects the database. No reset, deletion or silent downgrade is part of this work.

Cards show source, author/unknown, license/unknown and custom attribution. The full details dialog shows every retained field, observation/revision and provenance, plus dedicated validated HTTPS file/license/terms actions. Metadata is inert `BasicText` through SkinKit defaults, never WebView/HTML/Markdown execution or clickable author content. Encoded and raw bidi/control characters receive the same sanitation. Thumbnails remain placeholders; no image or video is fetched by this integration.

## Actual HTTPS adapter

The Android app contains an original raw TCP + system `SSLSocket` HTTP/1.1 adapter. It admits only exact anonymous GET requests to `https://commons.wikimedia.org/w/api.php`, using the five explicitly supplied headers (Host, Connection, User-Agent, Accept, Accept-Encoding). Every DNS answer must be public; the selected IP is pinned and the actual TCP/TLS peer rechecked. System CA validation, HTTPS hostname verification and SNI remain enabled. There is no global cookie/authenticator/proxy/TLS-default consultation, client key, redirect, hidden retry, decompression, connection cache, or credential logging. No process security setting is changed.

One physical slot stays reserved until worker and cleanup actually exit, even if DNS or close is stuck. Cancellation/deadline wakes the caller promptly and closes owned sockets off UI/timer threads. Connect/TLS are bounded to five seconds and the original absolute/monotonic deadline covers DNS through response parsing. Successful response wire plaintext, headers, chunk framing and body share a conservative 2 MiB cap; headers additionally have a 32 KiB / 100-field cap. Length, duplicate framing, truncation, compressed responses, chunk extensions, unsafe trailers and unexpected trailing bytes fail closed. One excess-byte probe can detect a false length/overflow. 429/503 and 401/403 publish only bounded definitive headers without consuming an arbitrary error body. Logical delivery does not wait on stuck cleanup, but the physical slot remains occupied until worker and close finish. The transport records server not-before independently, so even a discarded response or outer cancellation cannot bypass its observed cooldown.

Offline adversarial tests execute this actual adapter with injected DNS/socket seams. They are not Android TLS interoperability, live endpoint success or device cancellation proof. The three previously authorized HTTP-200 metadata captures validate API layout only. No further live metadata request, video/thumbnail transfer or account action was performed for this integration.

## Contact configuration and rollback switch

No private contact is hardcoded in tracked source, fixtures, documentation or reports. Set `ESV_COMMONS_CONTACT` in the build invocation's environment, or use the `esv.commons.contact` Gradle property. Supply the already-approved contact for this API only; the source owner must arrange injection for a future authorized test package. Do not place the value in command logs or general-purpose headers. `BuildConfig.VERSION_NAME` supplies the actual app version.

Absent contact means `COMMONS_ENABLED=false` and zero production providers. `-Pesv.commons.enabled=false` disables registration even with contact, for a feature rollback retaining the v2 reader. The on-screen source switch persists separately. App/browser startup remains usable in all these states. Compile/unit checks must cover contact-absent and contact-present registration without printing the contact; the build-time environment is not a secure credential vault, and this contact is not a secret credential.

## Verification and remaining gates

Final strict offline verification, without writing locks, passed: **493 JVM tests**, zero failures/errors/skips; Debug and Release Kotlin compilation; both lints with zero errors, eight warnings and two hints each; exact 72-artifact/hash admission; zero duplicate runtime implementation classes; Debug-only fixture/fake boundaries; original license bytes and SkinKit seven-getter ABI. Contact-present and contact-absent registration states were checked without retaining the contact in source or reports. Independent review closed with no known blockers and a matching 55-file source manifest. Source-extracted host SQLite migration/rollback/corruption checks, browser detector regression and the unchanged actual pinned HLS reader/raw-profile harness also passed.

`evidence/verification-summary.json`, preserved JUnit XML, lint reports, runtime inventories, `reviewed-source.sha256` and adjacent logs record exact evidence. The additional lint warning is a KTX convenience suggestion for the source preference write; no lint suppression/baseline or dependency change was introduced. Initial failed assertions/compilation and two verification setup mistakes are explicitly preserved as intermediate evidence.

As with the HLS baseline, a lock-write run omitted the already-admitted Kotlin stdlib-common 2.2.10 metadata coordinate from app configurations. The original baseline App lock was restored byte-for-byte; the final strict run passed against it. This adds no runtime binary or version. Only the new original project module has a new lockfile.

Repeat the final command with the approved contact injected only into the environment:

    ./scripts/gradle-workspace.sh --offline :core-model:test :core-domain:test :ui-theme:test :discovery:test :commons-provider:test :save:test :hls:test :app:compileDebugKotlin :app:compileReleaseKotlin :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:runtimeInventory

Use `ESV_EVIDENCE_DIR=docs/m2-commons/evidence` (prefer an absolute path) for boundary/admission scripts so historical milestone evidence is not overwritten. Current runtime dependencies remain the exact existing 72 admitted binaries; the new module contains original project code and adds no third-party dependency or public license.

Remaining NOT RUN: APK/AAB assembly; Android device/emulator launch; actual Android SQLite migration/CursorWindow/crash/power-loss; UI rotation/back/repeated-dialog exercise; actual Android DNS/TLS interoperability and socket cancellation; phone/tablet layout/accessibility; real metadata search through the Android app; image/video fetching; installation, account/payment, publishing or deployment. These gates prevent claiming complete M2/device or production acceptance.
