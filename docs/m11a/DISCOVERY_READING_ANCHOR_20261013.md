# Discovery stable reading anchor: bounded UI restoration

2026-10-13. Source baseline `c794ef84344d638f20966a1cd05bb5b340ade7e0`.
Original UI 01 §2 and 02 §4 require root-navigation reading-position retention by stable identity.
This slice replaces DiscoveryScreen's unconditional page-effect `scrollToItem(0)` with one
saveable stable-result anchor. It does not change page demand, history policy or controller scheduling.

## Verification

App **1,953 tests actually executed**, including **48 new tests** (41 policy/codec/provenance and 7 coroutine/source-wiring contracts); zero failures, errors or skips. Both Debug/Release Kotlin compilers and each variant’s lint analysis/report/entry tasks were forced and passed. Each lint retains **60 warnings, 8 hints, 0 errors**. Runtime admission reverified **90 exact artifacts**; the unchanged compiled-boundary script then passed its seven reported checks. All ten requested tasks actually executed. All **1,403 frozen inputs remained unchanged**; 1,396 prior inputs outside the three scoped changes match baseline bytes. Other module tests were not rerun in this slice. These results include real network-free coordinator restore/start tests, but no actual Android/Compose restoration.

Evidence: `artifacts/ui/discovery-reading-anchor-20261013/`. COMPLETE_APP_EVIDENCE.zip retains all App XMLs, both lint reports and the complete build log.

## Display behavior and identity

- Saved anchor contains only a bounded session identifier, epoch, view revision, page index,
  applied query-key hash, demo flag, stable result-ID hash and nonnegative pixel offset. Versioned
  codec stores nine primitive values; malformed/wrong-type/out-of-range state fails closed.
  It stores no raw query, URL, title, result metadata, list, credential or runtime command.
- Same materialized page/root return resolves its stable result ID to the current index, preserving
  the offset. Missing/duplicate IDs or different applied query/page/revision/demo identity reset
  to top. Settings column changes do not create a new page identity.
- Editable draft clearing does not change the applied page's queryKey, so it cannot reset the
  current reading anchor or trigger search. Existing history controls that preserve materialized
  pages retain their existing behavior. An actual new search/session/query or new page resets.
- Capture runs only for the active content grid with the existing modal/pending-content visibility
  exclusions. The normal visibility tracker, SHOWN events, pager handlers and foreground budgets
  remain at their original locations and retain their original code.
- Restoration runs before snapshotFlow begins observing the position. Every restore/capture checks
  the latest actual controller page key. A retired claim or changed current epoch cannot write a
  newer anchor. Identity/result-order/visibility/provenance changes dispose the old claim.
- A user drag may cancel the inner scroll mutation. That cancellation does not disable subsequent
  capture, and the adapter does not retry or fight the drag. Parent effect cancellation is checked
  with ensureActive and still propagates; unrelated exceptions are not swallowed.

## Exact restore provenance, not access authority

The existing domain coordinator deliberately increments epoch when restoring a committed checkpoint.
Simply requiring the saved epoch would reject a genuine Activity recreation; omitting epoch would
conflate it with a new same-query search. This slice retains strict callback epochs and adds only a
read-only display proof at the existing successful restore path:

1. Initialization clears the prior in-memory proof. After existing `c.restore` succeeds, a pure
   factory compares the previous and returned checkpoints. It accepts only the same session,
   strictly newer epoch, exact profile/query-authorization/source-configuration equality and the
   complete same nonempty retained pool after changing only page stamps.
2. UI publication exposes the previous stamp only when both the current checkpoint and displayed
   page strictly match the proof's new stamp, with page membership verified and storage available.
   Failures clear the proof. New search/later epoch/provider configuration changes cannot reuse it.
3. Anchor resolution accepts the exact proven previous stamp only if every other page/query/view/
   demo identity field still matches and the stable result remains present. Successful capture
   then records the current stamp. The proof itself is never serialized.

No epoch ranges, guesses or proof chains are accepted. Multiple recreations while the screen never
reappears may lack a direct old→new proof; that case deliberately falls back to top. This bounded
behavior is not a claim of complete lifecycle restoration in every possible sequence.

The proof is display provenance only. It grants no source, login, media, storage, provider, network
or background-work authority. No database, codec, wire format, domain coordinator, source registry,
permission, task or history rule changed. Controller edits are six display-only insertions; reversing
them reproduces the entire original file byte-for-byte. Replacing the one screen adapter call with
its original effect likewise restores the entire original screen. The original visibility/demand
code is unchanged.

## Test meaning and pending device acceptance

The test set covers first view, repeated same-page resolution, codec recreation, stable-ID reorder,
real page/query/revision/demo changes, draft versus applied-query clear, missing/duplicate/invalid
IDs, malformed codec values, retirement and stale callbacks. Network-free tests invoke the real
VideoDiscoveryCoordinator restore/start paths using an in-memory store, no registered providers and
inert timer; they distinguish retained-page restamping from a same-query new search and source/
recovery pool invalidation. Coroutine tests distinguish an interrupted scroll mutation from a
cancelled parent effect. Source contracts verify the actual adapter, live-key callback and successful
restore-only proof path.

These tests do not execute Android navigation, LazyGridState layout, actual saved-instance-state
recreation, UI drag delivery, screenshots, TalkBack or native lifecycle behavior. Device/Compose
restoration, 200% text, column/window changes and PPT comparison remain **NOT_RUN**. No new APK was
assembled or installed in this slice. The previously frozen code8 Debug APK remains bound to its
pre-anchor c794ef source and must not be represented as containing this feature.

Pending device cases: same-page root leave/return; one actual rotation/process recreation; direct
proof unavailable across repeated hidden recreations; drag interrupting restoration; change page,
query and source during a pending restore; clear only draft/history versus start a new search;
background/foreground; compact/expanded columns; ensure no duplicate search/getNext/SHOWN events.
Prior OPPO/Windows failures and other outstanding acceptance requirements remain open.
