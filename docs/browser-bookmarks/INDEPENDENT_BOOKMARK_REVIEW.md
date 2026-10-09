# Independent ordinary-bookmark review

## Result

**PASS for the reviewed host contracts and source wiring. No remaining blocker was found in this limited review.** This is not native Android, WebView, device UI, process-kill, power-loss, or complete PR-25 acceptance. The main owner runs the separate full Gradle/frozen aggregate; this reviewer did not run Gradle.

Original bookmark-review baseline: `f78d7cc46c92bdd461e701ac3c39f62e12cb0ce5`.

Current hotfix-inclusive comparison baseline: `0c178320cd78391bfedd5f9846e3ee873f0f5fce`. This reviewed source commit is a direct child of the original baseline. The combined integration commit is `c3792eac3f98c87c4ea55331a271124d89e64829`, a preserving-history cherry-pick onto the bookmark checkpoint `53ee1863a5b60f44752d246e936b599ca1ca7628`. The combined commit already contains the bookmarks and is therefore not used to exempt them from scope comparison.

Scope: ordinary local bookmark add/list/open/remove, bounded private persistence, page-selection fences, and UI integration. No new dependency, APK/AAB, device, account, deployment, or external-page action was performed. Restricted parked features were not changed or executed.

## Independent checks

- 37 independently authored Kotlin/JUnit host tests pass.
- 23 source-wiring assertions pass.
- All reviewed source hashes remain unchanged across the final provisional run.
- The eight compiler/test-support JAR hashes match the previously admitted standalone toolchain exactly.
- Exact production/reviewer source hashes for the final provisional run are recorded in [source-sha256.txt](evidence/independent-pre-freeze/source-sha256.txt), with [unchanged-after-run verification](evidence/independent-pre-freeze/source-unchanged.log). This run includes the final candidate-only main-thread validation optimization.
- Supporting final provisional results: [37 host tests](evidence/independent-pre-freeze/results.log), [23 source assertions](evidence/independent-pre-freeze/source-review.json), and [admitted toolchain identity](evidence/independent-pre-freeze/toolchain-sha256.txt). The freeze-mode file labels this exploratory evidence; final frozen aggregate evidence must be established separately.
- Relative to the hotfix-inclusive source baseline, only the three authorized existing bookmark integration files differ. All 14 approved hotfix files retain exactly the source commit bytes and Git modes. The hotfix itself includes its separately reviewed build-version/admission changes; this bookmark review does not overwrite or reinterpret them. Original bookmark provisional evidence remains preserved.

The executable reviewer inputs are under `docs/browser-bookmarks/evidence/`:

- `independent-bookmark-run.sh`
- `IndependentBookmarkTest.kt`
- `independent-bookmark-source.py`
- `independent-combined-source.py`
- `android-doubles/Context.kt`
- `android-doubles/AtomicFile.kt`

The runner requires the shared frozen candidate before and after execution. `ESV_INDEPENDENT_PROVISIONAL=1` is an explicit exploratory exception only; the full acceptance aggregate disallows it. It accepts an absolute evidence-output directory as its first argument and honors the established toolchain/checkout environment overrides. It generates logs, JSON, SHA-256 records and temporary bytecode only. It does not create new source/fixture inputs during acceptance.

## Authorized media-close integration: source identity only

The following is a separate **source-only** addition to this review. It does not repeat the hotfix algorithm review or grant a native/device runtime pass.

- The source hotfix and cherry-pick have exactly the same 14-file scope, per-file blob bytes and executable modes. Live files also match that reviewed source exactly.
- The complete hotfix patch has SHA-256 `11b053992a672fff53c59653008a6438a18bf133f3b23dc958e26a850b11238d` in both histories.
- The three pre-existing bookmark integration diffs retain identical bytes before/after the hotfix, SHA-256 `28a12e6cc6d5f64fd5622265da74f95ea114a173ba4f9774c9bb429ae10e2d06`.
- All six bookmark implementation files, three bookmark unit-test files and three integration files remain byte-for-byte at the independently reviewed bookmark checkpoint.
- The combined expected full JVM count is **2035 = 1959 hotfix-inclusive baseline + 76 bookmark tests**. This is the required future aggregate count, not a result established by this source-only check. The earlier 2020-count interrupted verification/provisional record is distinct and is not promoted to combined acceptance.
- Hotfix device QA remains pending. Native media-close, preview and recording runtime success is not claimed here. No concrete bookmark interaction was found in the source identity/delta check.

Reproducible source identity is enforced by `evidence/independent-combined-source.py`, with explicit source/checkpoint/integration commits, all 14 paths and pinned patch hashes. It runs within the independent bookmark hook. Exact combined checks and hashes are recorded in [combined source identity](evidence/independent-combined-pre-freeze/combined-source-identity.json), [source SHA-256](evidence/independent-combined-pre-freeze/source-sha256.txt) and [post-run unchanged checks](evidence/independent-combined-pre-freeze/source-unchanged.log). The fresh combined provisional bookmark results remain 37 host tests plus 23 source wiring assertions, separately labeled provisional pending the new 2035-count frozen aggregate.

## Coverage

Policy tests cover conservative authority normalization, root-locale behavior, preserved signed/path/query/fragment components, unsafe schemes/credentials/controls/format characters, malformed UTF-16, byte limits, code-point-safe titles, long-host fallback, redacted model strings, and ordinary live READY-page capture. Fresh live-title changes are accepted only when context, session, epoch, selection revision, navigation revision and canonical address remain exact.

Codec tests cover empty and maximum-count round trips, 4-byte Unicode titles, every truncated prefix and single-bit corruption of a representative record, correctly checksummed unknown formats/counts, trailing payload, malformed UTF-8, hostile lengths, oversized files, duplicate identity/destination, and unsafe stored content. Version 1 is a new isolated store; no browser session/history import is performed. Unknown versions are unavailable without a destructive reset.

Controller tests cover background-only execution, publication only after completed storage plus UI delivery, repeated add/load taps, duplicate and full-capacity handling without eviction, stale/altered remove/open and remove/re-add replacement, failed reads/writes, uncertain committed writes reconciled by read-only reload, slow operations retaining their physical worker, late completion, close suppression, worker rejection, invalid loaded data, defensive list copying, repeated delivery and old timer fences.

The actual `AndroidBookmarkStorage` source is compiled and exercised with minimal, independently written Android API doubles and real temporary host files. These tests cover cold re-instantiation, backup-only recovery, unknown-format preservation, silent rename failure on first and subsequent writes, corrupt readback, bounded oversized-file reads, zero-progress reads, unreadable existing data, failed start-write, a private-root path that is a regular file, and an inaccessible temporary directory under the unprivileged host user. **These doubles are limited contract models, not an implementation or emulation of Android `AtomicFile`.**

Source checks cover the process singleton and serial worker, main-thread delivery, lazy no-backup path lookup, explicit sync and bounded readback, live-entry capture/revalidation, same-document admission and reload exclusion, explicit current-entry Open in both layouts, saved list-open state without navigation, scrollable phone/tablet presentation, bounded text, keyboard/back/dismiss handling, no bookmark network/WebView primitives, and independence from settings/session/Cookie persistence.

## Review findings resolved

1. Blank-title fallback is bounded even for a long valid hostname and remains a sanitizer fixed point.
2. Live page titles can change after the original load deadline without permanently trapping the Add action in a stale-token loop. Title refresh never relaxes page identity/generation checks.
3. Ambiguous `FileNotFoundException` handling no longer treats inaccessible/non-directory storage as a writable empty list. The new non-directory regression failed on the pre-fix candidate and passes after correction.
4. Same-document admission excludes reload callbacks until normal page completion.
5. Functional record/token/state `toString()` output is redacted.
6. Failure text no longer promises that old disk bytes necessarily survived an uncertain already-committed write.

The adapter explicitly synchronizes and verifies readback because the platform helper may return after logging a sync/rename failure. This behavior was checked against the [AOSP AtomicFile source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/util/AtomicFile.java); no external implementation code was incorporated into the candidate or doubles.

## Remaining acceptance limits

Native `AtomicFile`/filesystem semantics, WebView callback ordering, actual Activity rotation and process death, phone/tablet rendering, soft-keyboard transitions, accessibility/font scaling and frame-time behavior are **NOT RUN**. Background I/O, lifecycle ownership and UI behavior have source/host evidence only. No implicit-network property is inferred for unrelated existing browser behavior; this review establishes that bookmark startup/list/add/remove introduce no page fetch or WebView-load call, and bookmark Open is explicit and current-entry checked. Full browser/incognito/site-permission requirements and overall product completion are not claimed.
