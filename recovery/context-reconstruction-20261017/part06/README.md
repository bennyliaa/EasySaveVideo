# Verified configurable SearXNG instance list

This is a NEW implementation derived from exact Git baseline 6d2edfa5680d0163038fde1faa58f693513d0ab8. It is NOT restoration of inaccessible later historical checkpoints. All 2828 source files have exact target bytes/modes recorded; 60 added/modified paths are included in full. Baseline prerequisite has 2801 files. No source deletion. Prior part05 remains an immutable unverified snapshot; this package contains the fully checked dynamic-list implementation and final receipt.

## Actual verification

- 6822 passed, 2 pre-existing skipped, 615 suites, no test failure/error.
- Preliminary focused run: 438 passed, no skips, 32 suites (Discovery and SearXNG).
- Debug/release Kotlin and release Java compilation; both lint variants have zero errors.
- Exact 95 reviewed external runtime artifacts matched in both variants.
- Final strict dependency verification was offline, serialized, and reran all requested tasks.
- Independent source review and exact baseline+overlay replay passed.
- Target source manifest SHA256: 4d6c9b505aa2dd7e8bd316165d9fcc63fdd201c5336414c55705d0d82e9fa20f.

These are synthetic/offline and build results. No APK, device test, live search, save success rate, public DNS/TLS proof or content-rights grant is established. Twelve public examples were blocked by recorded robots429 or disallow responses. No query was attempted and no alternative route was used. An eligible endpoint remains required for live acceptance.

## Reconstruction

Use an independently verified checkout/copy of the exact baseline, then run `python3 replay_snapshot.py --baseline <baseline> --package <this-package> --output <new-directory>`. The tool refuses an existing output, verifies baseline/overlay hashes and modes, rejects unsafe paths/symlinks, and verifies the entire resulting source. It does not build or install. The included runner/sealing scripts document this cloud's explicit toolchain and task scope; adapt environment paths deliberately on a different machine rather than assuming those paths exist. SDK/caches/binaries are not embedded. Toolchain receipts record the actual official SDK bytes and local Java module launchers used.

Overlay includes the prior verified HTML/B slice plus a dynamic0–16 instance list, action-specific add/edit/delete/reset claims, v3 codec with pure v1/v2 migration, monotonic IDs, exact deployment-prefix paths, EN/ZH disclosures, and regression fixtures. Configuration reuses the original process-wide three physical transports; logical concurrency and shared session budgets are not expanded. No SearXNG server/AGPL source is embedded. Existing Jsoup1.21.2 admission is reused; no external runtime GAV/version expansion. Read source docs/discovery/SEARXNG_INSTANCE_LIST_20261018.md and PUBLIC_SEARXNG_HTML_20261017.md for scope and limitations.
