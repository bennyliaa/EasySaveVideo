# Explicit task authentication: return to a live original page

Source baseline: development head `55c51cd71763159b203e52ece44f09da8cab1507`. This slice is pending publication. Original UI 03 §8 and 05 §8 describe `OpenOriginForLogin` independently of task resume.

## Implemented boundary

Authentication-wait Direct/Stream task details now offer a separate explicit original-page selection in addition to the existing ordinary Browser fallback. The action succeeds only when its newly accepted task has a process-local association to the exact still-live original document, and the original HTTPS document is same-origin with the final selected network resource. A successful selection returns to that already-existing tab, then uses the existing Browser route. It does not sign in, reload, recreate a View, load a URL, read Cookie/source context, submit, retry or resume a task.

All unavailable cases remain explicit: no captured association, recovered task, idempotent-existing submission without a prior link, cross-origin/CDN resource, final HLS variant at another origin, incognito, closed/evicted/recreated/changed document, expired token, cleared background generation, or insufficient live-host evidence. Ordinary Browser navigation remains available separately. Stream tasks may still require explicit reselection according to the existing runtime; selecting a page does not make any task resumable.

## Identity and privacy

The host issues opaque in-memory tokens from an already-accepted inspection page and fresh explicit selection. Its bounded registry (100 maximum, 15-minute monotonic maximum age) retains weak Entry/View identity plus exact BrowserContextRef/session, epoch, document revision and host-accepted page address. The page address remains host-local and redacted from diagnostics. The token contains only private canonical HTTPS origin, expiry and revocation state; it carries no URL path/query, title, Cookie, credentials, View, Activity or navigation closure into runtime/UI.

The separate synchronized task registry binds only `SaveSubmission.Accepted(existing=false)` to the final task ID. Direct uses its selected candidate resource; HLS captures at preparation and rechecks the final selected resource origin. The registry never learns an association from records, persisted origin, `sourceForTask`, task refresh, resume or recovery. Existing acceptance cannot replace an established association. A capture is one-shot, registry-owned and lifecycle-generation-bound; late worker acceptance cannot restore a cleared background token. Rejected/preparation-discarded/failed-launch/cancelled/terminal/expired associations retire.

Backgrounding clears both runtime associations and browser document tokens. Closing the host or evicting/destroying an entry invalidates tokens. Tab switching alone may preserve a live exact document; navigation/reload/history revision including same-URL ABA does not. New tokens cannot match a restored tab or a recreated View, even if the tab ID/URL appears equal.

## Command and real wiring

The existing authentication task identity/revision/wait-state and runtime readiness/idle checks are reused. Origin selection has its own view-lifetime command and captured browser interaction revision. The existing MainActivity route/focus/foreground/modal/preview gates run at the explicit click. A changed browser revision rejects the old callback before consuming the task command. The host checks capability, foreground, file chooser, JS dialog and fullscreen state, resolves the exact registered entry, and selects only that live entry. It does not call the ordinary `selectTab` path that may `ensureEntry` from saved history. The host rechecks after native dismissal callbacks and rejects further interaction changes.

The prior generic Browser action remains unchanged. New labels and unavailable guidance use current Compose `stringResource` in English and Simplified Chinese. Shared EsvButton retains its existing skin-independent 48dp minimum target. No SaveCoordinator, storage/DTO/wire, provider scheduler, Cookie lease, source expiry, recording/service, or dependency behavior was edited. Exact inverse substitution evidence reconstructs each modified existing production/config file.

## Verification

App **1,998 tests actually executed**, including **45 new tests** (36 pure policy/concurrency tests and 9 source-wiring/resource contracts), with zero failures/errors/skips. Debug/Release Kotlin compilation and each variant’s lint analysis/report/entry tasks were forced and passed. Each lint retains **60 warnings, 8 hints, 0 errors**. Runtime admission reverified **90 exact artifacts**, and the unchanged boundary checker passed seven checks. All ten requested Gradle tasks actually executed. All **1,409 frozen inputs remained unchanged**; 1,399 baseline inputs outside the four scoped modifications retained identical bytes. Other module tests were not rerun in this slice.

Evidence: `artifacts/ui/authentication-origin-20261013/`. The complete evidence ZIP contains every App JUnit XML, both lint reports and the full build log.

The first run failed while compiling a new source-wiring test because `Files.readString` is unavailable in this Android unit-test compilation/toolchain. Only that test reader was changed to `Files.readAllBytes` with UTF-8. The original failed log/input manifest is retained; a complete second forced run supplies the final results. No production code changed between runs.

Tests include pure document/token/task-registry identity and revocation cases plus source-wiring/resource contracts for real Direct/HLS branches, cleanup, task freshness, browser revision, existing-entry selection and ordinary fallback. Source-wiring assertions do not execute platform WebView. Independent review is retained with final file hashes.

## Unexecuted acceptance and historical limits

NO_DEVICE: actual WebView tab selection/no-reload behavior, touch/focus/background races, native callback ordering, actual renderer death, Activity/process recreation, manual login and manual task resume were not run. Device checks require an owned same-origin fixture and inspection of actual requests. CDN and unavailable cases must retain the explanatory guidance. Real Compose 200%/RTL/TalkBack and PPT/pixel inspection also remain NOT_RUN.

The previous local-only code8 debug APK still binds pre-anchor `c794ef84344d638f20966a1cd05bb5b340ade7e0`; it was neither rebuilt, installed nor uploaded for this slice. Its 46MB file remains excluded. No current production APK is claimed. The full thirteen-module regression remains bound to `b0c5647`, not this slice; unrelated module tests are not represented as rerun. Known OPPO editing `STORAGE_UNAVAILABLE`, sharing `INITIALIZATION_FAILED`, ANR/second-recording and Windows symlink/setup guard issues remain open. This narrow live-page subset does not claim universal recovery after background/process death or general cross-origin login routing.
