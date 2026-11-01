# Verified public SearXNG HTML candidate slice

This is a NEW implementation derived from exact Git baseline 6d2edfa5680d0163038fde1faa58f693513d0ab8. It is NOT restoration of inaccessible later historical checkpoints. All 2825 source files have exact target bytes/modes recorded; 53 added/modified paths are included in full. Baseline prerequisite has 2801 files. No source deletion. Prior part03 remains an immutable unverified snapshot; this package contains the three corrected test fixtures/contracts and final receipt.

## Actual verification

- 6782 passed, 2 pre-existing skipped, 613 suites, no test failure/error.
- Focused run: 598 passed, 2 existing skips, 53 suites.
- Debug/release Kotlin and release Java compilation; both lint variants have zero errors.
- Exact 95 reviewed external runtime artifacts matched in both variants.
- Final strict dependency verification was offline, serialized, and reran all requested tasks.
- Independent source review and exact baseline+overlay replay passed.
- Target source manifest SHA256: 888e8c0ab63ccffdc80603f7d2d1e69556cf6580a8d57a818d96fc998c3869d1.

These are synthetic/offline and build results. No APK, device test, live search, save success rate, public DNS/TLS proof or content-rights grant is established. ononoki robots returned429; tiekoetter robots prohibited the query route. No query was attempted and no alternative route was used. An eligible endpoint remains required for live acceptance.

## Reconstruction

Use an independently verified checkout/copy of the exact baseline, then run `python3 replay_snapshot.py --baseline <baseline> --package <this-package> --output <new-directory>`. The tool refuses an existing output, verifies baseline/overlay hashes and modes, rejects unsafe paths/symlinks, and verifies the entire resulting source. It does not build or install. The included runner/sealing scripts document this cloud's explicit toolchain and task scope; adapt environment paths deliberately on a different machine rather than assuming those paths exist. SDK/caches/binaries are not embedded. Toolchain receipts record the actual official SDK bytes and local Java module launchers used.

Overlay includes original finite HTML/robots clients, explicit format settings and localized privacy disclosures, ephemeral search-session B candidate admission, bounded robots/public-DNS verification, and composite regression fixtures. No SearXNG server/AGPL source is embedded. Existing Jsoup1.21.2 admission is reused; no external runtime GAV/version expansion. Read source docs/discovery/PUBLIC_SEARXNG_HTML_20261017.md for scope and limitations.
