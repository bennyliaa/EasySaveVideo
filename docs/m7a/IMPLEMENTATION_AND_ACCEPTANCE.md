# M7-A Provider parameter maintenance development checkpoint

## What this slice does

This is a closed versioned maintenance contract for the already-compiled Commons metadata provider. It implements package validation, signature verification, local repository/version management, bounded update execution and the runtime Commons policy gate. It is not full M7, an extraction-rule updater, a new provider loader or production remote delivery.

The v1 envelope is canonical big-endian data, capped at 8,192 bytes with a 4,096-byte payload. Fixed `SHA256withRSA` verifies all header/key-ID/payload bytes against copied injected RSA 2,048–4,096-bit public keys. Algorithm, target provider ID/version, schema, lifetime and every bound are closed. Neither bytecode nor code/URL/host/query/extraction fields are representable. Production ships no trust key, signer or update endpoint; ephemeral JVM fixture keys have no deployment value. The only operational source still needs the existing operator-approved Commons contact and user's local enable choice.

Package time checks currently trust the injected/system wall clock; resistant clock-rollback policy is not implemented and is a production decision.

The payload can disable Commons or reduce defaults: at most 5 results per response, 2 MiB response bytes, 15 seconds, 5-minute cache TTL and 16 cache entries. The request also retains all existing operation and local limits. Cache content cannot cross policy generation; active changes revoke old requests and checkpoint checks discard late results. Safe-search refusal, strict API URL/transport policy, rights/provenance and lack of Save/playback authority remain unchanged.

## Version, health and rollback semantics

- A newly accepted enabling package is a trial candidate. The successful metadata request must still match its exact policy/version/generation and meet its effective deadline through cleanup and health persistence. An old request cannot promote a newer candidate.
- Transient transport failure does not silently broaden the active policy or roll it back. Explicit failed-candidate rejection returns to the last-known-good policy. Expired fallback stays disabled.
- A disabling package is stable immediately and survives restart; no network health event is needed for a kill switch. A higher signed version can later be admitted, while local user/build admission still applies.
- Stable manual rollback consumes the previous-good slot, keeps the highest signed accepted-version envelope, and refuses expired/future signed targets. Returning to a bundled default is an explicit local rollback/rejection outcome, never a corrupt-state reset.
- An unconfirmed candidate is never active after reopening the repository. It is disabled with `RECOVERY_REQUIRED`; the signed high-water and candidate remain, and explicit rejection is needed before further installs. This prevents an interrupted update's durable bytes from later becoming an unnoticed usable policy.
- The local repository is single-owner locked, fixed-size, checksum-framed and individually signature-revalidated. Atomic replacement, initialization and intent markers, bounded reads/writes and directory fsync distinguish clean state from uncertain transactions. No reset/delete-to-bundled recovery API exists.

This protects monotonic versions through the supported repository workflow and ordinary interrupted/corrupt writes. It does not resist an attacker who can coherently replace the entire app-private directory. If the filesystem rejects every later write/fsync, durable poison-marker recovery is best-effort; the current instance remains disabled. Host fault injection is not a real Android power-loss guarantee.

## Runtime and cancellation

`ProviderUpdateSource` is an injected interface only. There is no HTTP implementation or production network job. One admitted operation retains its physical slot until its worker, resource cancellation and callback all exit. A hung create/read/close/cancel/callback therefore blocks retries instead of leaking new threads. Deadline/cancel response does not wait for source or filesystem work. A sticky cancellation flag and tiny no-I/O final publication gate prevent a reported cancellation from becoming a later usable candidate. Inactive signed recovery bytes can remain on disk. Zero-progress, empty and oversized input fail explicitly.

The Android registration owns a process-scoped repository opened on discovery's IO executor only if Commons was configured at build time. A missing contact keeps zero providers and does not need the repository. No Android startup, Browser, Save, Record or Library authority depends on successful maintenance initialization. The pre-initialization/unavailable path remains source-free. Real Android filesystem and UI execution remain untested here.

## Verification

The authoritative final counts, source fingerprints, lint totals and restore hashes are populated only after completion. `WIP_CHECKPOINT.md` and earlier focused logs are diagnostic/WIP evidence, not final acceptance. The independent report describes corrected findings and includes adversarial cancellation/late-publication tests. `scripts/verify-provider-maintenance-workspace.sh` reruns strict offline JVM, both Android compile/lint variants, exact74 admission, nine static boundary guards, SQLite/DOM regressions and existing pinned Media3 host harnesses.

## Not implemented or not run

Full provider extraction-rule updates/interpreter and multi-provider package resolution; production trust ownership/rotation/revocation/clock policy; actual update endpoint and distribution; production health/rollout monitoring; Android filesystem power interruption and process lifecycle, devices/phone/tablet validation; APK/AAB/install, real account, payment, publishing or deployment. No claim of full M7 or release readiness is made. Free Library and fake-entitlement boundaries remain unchanged. Vault is disabled, untouched and excluded from review.

## Final main-worktree host result (2026-10-07 UTC)

- Strict offline dependency verification, all 87 requested Gradle tasks, debug/release Kotlin compilation and both lint variants: PASS.
- Normal JUnit XML: **1,389 tests**, zero failures/errors/skips. Commons now has **118** tests (baseline 48, 70 added). No independent-host overlap is added to this count.
- Lint: **0 errors, 14 warnings, 6 hints per variant**, unchanged totals. This is not a warning-free build; existing SDK/deprecation/style/context notices remain visible.
- Exact **74** admitted runtime artifacts in both variants; original dependency/license/vendor/manifest/entitlement inputs unchanged.
- Synthetic contact-configured app JVM/dual-compile/dual-lint branch passed without network use. Final full aggregate restored contact-absent build artifacts.
- Nine static admission/boundary checks, production-extracted discovery/library/export/editor/recorded-segment SQLite checks and 11 DOM-detector checks: PASS.
- Existing pinned Media3 host checks: HLS EOF **81**, mux conservation **15**, reader release and editor muxer release: PASS. These are unchanged-regression host checks, not device/media-quality acceptance.
- Independent ordinary-provider review: **132/132** host tests (118 Commons plus 14 independent adversarial cases), 43 compiled inputs unchanged. The **57-entry** source/test/script/resource manifest is SHA-256 `085d9b18ddb6644d1f6fc4f5cbc37bc08602cc9b22108d319fb86619f14f1afd`; all bytes independently rechecked. Four findings were corrected; none remains blocking within this scope.
- Compiled Commons main jar scan: 89 classes, no test fixture, key generator, private-key or signing-operation references. This supplements the empty-root source review; it is not APK inspection.

Final Git HEAD/tag, source/bundle hashes, two cold restored-tree aggregates and restoration instructions are recorded in the accompanying external `RESTORE_RECEIPT.json` and cold-verification archive. They are not implied by the WIP receipt. No Android, production-key/delivery, release or full-M7 acceptance is implied by the development tag.
