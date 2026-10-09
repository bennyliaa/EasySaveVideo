# M8-B2 independent origin, navigation and history review

Baseline: `3ca09f768bf255b066e00b82209e7d6821c3aa7e`.

## Current disposition

Final disposition: **PASS for the reviewed fail-closed origin infrastructure and ordinary host regressions after both origin findings were corrected**. The final independent run passed **34 tests**: the original unchanged M8-B1 independent fixture's 16 tests plus 18 independently authored B2 tests. All 155 frozen inputs retained exact bytes before and after the run, and all eight standalone toolchain artifacts matched their admitted SHA-256 values.

**This is not completion of native video-SAVED or PLAYED integration.** The current application has no trustworthy candidate-to-Discovery-video identity proof and explicitly supplies UNKNOWN; it therefore does not record a Discovery video's SAVED event from a page-origin Save. Positive VERIFIED cases below are synthetic host injections only. This is ordinary source review and JVM host injection, **not Android/native WebView, SQLite, MediaCodec or device acceptance**.

## Findings and scope boundaries

### O1: first history callback after READY is not distinguished from original-load history

The initially reviewed `DiscoveryNavigationFence` accepted the first exact-address history callback at any point. `SystemBrowserHost.doUpdateVisitedHistory` did not consult whether the original navigation had already reached READY, and `accepts` required only a start. A synthetic trace of start → finish/READY → Save selection → first same-address history mutation could therefore leave a correlation current when the initial history observation was absent or delayed. This depends on callback ordering that is not proven by these JVM tests. Recommended correction: conservatively invalidate any history observation after original-load completion and add a focused regression. This finding was reported before final freeze and is **corrected**: an accepted `SystemBrowserHost.onPageFinished` calls `DiscoveryNavigationFence.onFinished` before publishing READY; the fence rejects every history observation after that point, including the first same-address observation. An added independent test exercises both absent and present initial-history observations. This is conservative rejection and does not require an unverified native callback-order guarantee.

### O2: result-page provenance does not establish selected media identity

The capability identifies an exact Discovery result/page and its native browser navigation. It does not compare a selected DOM candidate's bytes or remote identity with the Discovery result's `sourceVideoId`/alias identity. A page with several videos can yield a successfully saved candidate other than its main Discovery result. Page-selection provenance was **not accepted as a weaker meaning of SAVED**. The finding is **corrected by refusing the event**: `DiscoveryOriginMediaIdentityMatch` defaults to UNKNOWN; the current Android controller explicitly supplies UNKNOWN; the tracker consumes the matching task capability with IDENTITY_UNVERIFIED and never enters the history callback. Only a future trusted identity adapter, not yet implemented, could legitimately assert VERIFIED. A saved media receipt remains COMMITTED independently. The UI explicitly says the Discovery video's save/playback state remains unknown. Independent tests cover default UNKNOWN, explicit UNKNOWN and the handle-dispatch path, including rejection of a later attempted upgrade. No guessed URL match or DOM/fullscreen playback inference is endorsed here.

### SAVED and PLAYED remain unavailable from the current native bridge

No inspected app source dispatches a `SeenEvent.PLAYED` event, and the current UNKNOWN identity gate prevents the latent SAVED callback from executing. DOM detection, fullscreen custom views, opening a URL, and successful media publication are not player-identity evidence. The columns remain independent and unknown unless they contained older explicit facts. The host tests deliberately assert that SAVED never manufactures PLAYED.

## Reviewed source boundaries

The origin review inspected:

- `app/.../discovery/DiscoveryOriginTracker.kt`: bounded process-local capabilities, one selection/task, TTL/clock rollback, detached payload, invalidation and terminal callback lock.
- `app/.../discovery/DiscoverySaveOriginHandle.kt`: one terminal dispatch and one outcome delivery, fail-closed rejection, no media operation retry.
- `app/.../browser/DiscoveryNavigationFence.kt`, `SystemBrowserHost.kt`, `NavigationAdmission.kt`: selected tab/context, browser epoch, exact original address, redirects/repeated starts/history, tab change/close/restore/renderer failure and host lifetime.
- `app/.../discovery/AndroidDiscoveryController.kt`, `DiscoveryPresentationPolicy.kt`: immutable target capture; serialized complete-target, full page and result validation; generation/management revision; stale/failed history separated from media.
- `app/.../MainActivity.kt`, affected `app/.../ui/DiscoveryScreen.kt` flows: accepted navigation before OPENED, lifecycle close, priority observer detach and truthful history/playback wording. Detailed media activity/Save-bridge review belongs to the separate media reviewer.
- `core-domain/.../BrowserSessionController.kt`: request-object/context/epoch fencing and READY transition.
- `discovery/.../VideoDiscoveryCoordinator.kt`, `DiscoveryStore.kt`, `SeenHistory.kt`, `DiscoveryRetention.kt`: persisted page/revision/profile guards, post-retention commit guard, clear transaction revision, alias grouping and independent monotonic event columns.
- `app/.../storage/AndroidDiscoveryStore.kt`, `DiscoveryCheckpointCodec.kt`: ordinary source inspection of transaction/retention/guard ordering and persisted page validation; Android SQLite execution is not claimed.
- Existing origin/navigation/correlated-event tests and the unchanged B1 independent-history fixture and runner were read as regression/reference inputs.

The machine-readable `evidence/frozen-review-source-manifest.json` contains full repository-relative input paths, sizes and SHA-256 values. It intentionally also includes compilation dependencies and the separate media review's inputs; inclusion is not a claim that this reviewer manually reviewed every media implementation.

## Host regression evidence

`evidence/independent-origin-run.sh` uses the same offline standalone Kotlin/JUnit contract as prior reviews. It checks all eight compiler/runtime/JUnit artifact SHA-256 values against the admitted M7-C reference by exact basename and digest. It compiles fresh classes, executes the original unchanged `docs/m8b1/evidence/independent-history-review.kt` plus `independent-origin-review.kt`, and checks all frozen input bytes before and after. It does not edit, weaken or execute the obsolete B1 whole-tree frozen manifest; its new B2 manifest acknowledges the expressly authorized current core change. The M8a runner remains a separate aggregate obligation.

The 18 independent new tests cover (every positive identity case explicitly injects VERIFIED and makes no native media-identity claim):

1. SAVED merges linked aliases without altering independently monotonic event columns or another profile.
2. TTL at selection, binding and completion does not renew.
3. Expiry at the final post-retention guard rolls back facts, pruning, checkpoint/progress and status.
4. Reentrant navigation at that same guard performs the same complete rollback.
5. Navigation winning before callback leaves committed media with no history write.
6. Completion winning the tracker lock commits before contending navigation, with no check/write gap.
7. Persisted clear defeats stale coordinator memory and the unchanged old controller target; old OPENED/PLAYED/SAVED cannot restore facts.
8. Controller clear consumes an old proof, including a later replay of the old target.
9. New controller generation or process owner cannot borrow an old proof.
10. Navigation fence handles original callback order and rejects repeated/changed observations.
11. Altered page size, order/identity, index or revision cannot write against a materialized page.
12. History callback exception consumes one proof and leaves media COMMITTED.
13. Rejected dispatch and a throwing observer cannot retry history or change committed media.
14. Browser generation mismatch and clock rollback revoke capabilities permanently.
15. Default and explicit UNKNOWN identity preserve committed media and every existing history fact, and consume the old proof.
16. The handle-dispatch path with UNKNOWN cannot promote SAVED even after a verified media-commit receipt.
17. A first same-address history observation after accepted page finish invalidates the fence, whether or not an initial history callback was seen.
18. A non-committed receipt cannot invent SAVED, PLAYED or other event facts.

Final log: `evidence/independent-origin-final-execution.log`; per-run exact freeze, toolchain hashes and results: `evidence/independent-origin-final/`. The current shared manifest is `evidence/frozen-review-source-manifest.json` (155 inputs; SHA-256 `4d149074b291ea448dc46e93d4a4f86d05fb3c080b368f3b7375dd3b9170cdaa`). The report's `origin_manual_source_review` manifest section identifies the 16 manually inspected production source paths, separately from compilation/test inputs and the separate media review's inputs.

The initial 31-test run is retained in `evidence/independent-origin-initial-execution.log` and `evidence/independent-origin-initial/`. Its initial 135-input manifest is preserved as `evidence/independent-origin-precorrection-freeze.json`; it is historical diagnostic evidence, not a claim that the corrected current tree matches the earlier bytes. The original M8-B1 fixture, runner and manifest and the M8a runner remain byte-for-byte unchanged against the baseline. The main aggregate now calls the new B2 origin runner and separately retains the M8a runner.

After the first corrected freeze, the media reviewer fixed only an independent synthetic transfer fixture to report its emitted byte count. Every production and other test input hash was first checked against the prior freeze and retained; only that single independent media-fixture hash was refreshed. The origin 34-test suite was then rerun against the updated shared manifest. Its earlier successful freeze/run is retained under `evidence/independent-origin-pre-media-fixture-adjustment/`.

Full strict aggregate, lint, runtime admission, protected media/provider suites and both cold-restoration reruns are owned by the build coordinator; this report does not substitute the independent 34-test run for those gates.

## Restrictions observed

No Gradle process was started by this reviewer. No production or existing test source was edited. No network, new library, APK/AAB, installation, emulator/device, blocked M7-D candidate inspection/execution/review, or Vault implementation work was performed. Excluded archive paths were not read.
