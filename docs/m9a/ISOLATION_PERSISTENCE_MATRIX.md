# M9-A ordinary Browser/Save isolation and host persistence

Date: 2026-10-07. Base: M8-B3 `dfdddbb`; new checks run on the working M9-A sources recorded in the evidence manifests. No production code is changed by this work package.

## Executed component isolation

`app/src/test/kotlin/com/easysavevideo/app/reliability/OrdinaryIsolationTest.kt` contains six JVM tests. The standalone runner compiles the actual production BrowserSessionController, BrowserInputPolicy, BrowserSessionCodec, VideoDiscoveryCoordinator, SaveCoordinator, SaveRecordCodec, and MediaTaskActivityTracker alongside their actual core dependencies. It verifies the eight previously admitted compiler/runtime/JUnit artifact hashes before compiling. No Gradle, dependency downloads, Android SDK/device, external network, or production service is used by this runner.

| Ordinary condition | Test/evidence | Result and exact boundary |
| --- | --- | --- |
| Zero providers | `zeroProvidersLeaveBrowserNavigationAndExplicitSaveAvailable` | PASS. Discovery reports NO_SOURCES and makes zero runtime requests; independent Browser navigation reaches READY; explicit DIRECT Save executes transfer, verification, and commit once through test ports. |
| Every configured source fails | `everyFixedSourceFailureLeavesBrowserAndSaveIndependent` | PASS. The only configured source is a test actor carrying the existing fixed Commons identity/version and cursor schema 1. PROVIDER_FAILURE, DEADLINE_EXCEEDED, SOURCE_CONFIGURATION, RATE_LIMITED and RESPONSE_TOO_LARGE are delivered separately. Each becomes the source health error while Browser remains current and Save completes through its own ports. |
| Discovery durable-store startup failure | `failedDiscoveryPersistenceDoesNotGateIndependentBrowserOrSave` | PASS. The expected STORAGE_UNAVAILABLE startup exception is caught by the test, Discovery remains FAILED with no source calls, and independently owned Browser/Save components still work. This is not an Android startup recovery test. |
| Save/Record owns priority; source callback arrives late | `mediaOwnerPriorityCancelsDiscoveryAndLateFailureCannotFailSave` | PASS. Actual MediaTaskActivityTracker signals feed actual coordinator priority. Save activity cancels the source operation; a late source failure cannot replace Save success; Record priority keeps requests suspended; idle state permits a new request. A throwing optional media observer remains isolated. |
| Browser checkpoint disk close/reopen | `browserDiskCloseReopenUsesFreshIdentityAndNeverRestoresIncognitoOrOldCallbacks` | PASS. Actual codec bytes are written and read through separately closed host file streams. A new controller restores only the normal address with a fresh browser-session identity in IDLE state, rejects the old callback, and does not restore the incognito tab. |
| Succeeded Save record disk close/reopen | `succeededSaveRecordSurvivesCodecDiskReopenAndDiscoveryFailureWithoutReexecution` | PASS. Actual SaveRecordCodec bytes round-trip through disk after the fixed source fails. SUCCEEDED/receipt and durable source reference are preserved, no request lease is restored, and the test transfer/commit counts remain one. |

The initial author-run evidence was explicitly developmental/unfrozen. The build owner has now rerun this host as part of the full aggregate against the 520-input frozen manifest: six tests passed, all frozen source and direct compiled inputs remained unchanged. The four-process SQLite harness also passed in the final aggregate.

Evidence: `evidence/isolation-host.log`, `evidence/isolation-host/results.log`, `evidence/isolation-host/toolchain-manifest.json`, and `evidence/isolation-host/source-manifest.json`.

The browser test executes the platform-neutral controller and real codec, not SystemWebView. Save transfer/verifier/private-store/journal implementations in this test are test actors; SUCCEEDED here proves coordinator isolation and sequencing only. It does not prove a real MP4 download, native decode, MediaStore publication, AndroidSaveStorage transaction, or a usable saved media file.

## Executed host SQLite process-style close/reopen

`evidence/verify-process-reopen.py` starts four separate Python processes against one temporary on-disk database. Each process loads the existing source-guarded host helpers from `scripts/test-discovery-history-sqlite.py`, opens its own connection, closes it, and exits before the next starts. The v3 CREATE/INDEX SQL is extracted from AndroidDiscoveryStore; the retention/control/header logic remains the existing explicitly modelled host implementation.

The ordinary fixture contains two profiles, two checkpoints, two linked identity groups/four aliases, and four progress rows for the fixed Commons provider (`wikimedia-commons`, version `0.1.0`, cursor schema 1). It uses bounded synthetic functional data only. No second provider is introduced.

| Fresh process | Assertions | Result |
| --- | --- | --- |
| Seed and close | v3 schema; both profile alias groups merged; independent SHOWN/OPENED facts retained; PLAYED/SAVED remain absent; rights payloads retained; fixed Commons schema-1 progress | PASS |
| Reopen, cancel/fault, rollback, close | Baseline survives reopen; cancellation between Seen deletion and revision barrier rolls back; later fault after progress reset plus revision change also rolls back; all rows/schema remain identical | PASS |
| Reopen, commit, close | Prior rollback persisted; clear only alpha Seen; alpha checkpoint/page revisions increment together from 1 to 2; beta state, rights/result rows, viewport and progress remain unchanged by clear; query-specific Commons reset deletes exactly one progress row | PASS |
| Reopen committed state | Exact committed database snapshot survives fresh process; alpha revision 2/beta revision 1; remaining aliases retain independent facts; bounded statistics reads cause zero row changes | PASS |

Evidence: `evidence/process-reopen.log`. Expected terminal report: four PASS process stages, 2 profiles/2 checkpoints, 2 remaining aliases and 3 progress rows.

This is real host SQLite disk persistence across normal process exit/restart, with explicit transaction rollback injections. It does not execute AndroidDiscoveryStore, SQLiteOpenHelper, CursorWindow, Android AtomicFile/SharedPreferences, Android lifecycle termination, force-stop, process-kill, power-loss or filesystem crash recovery. The independent Python checkpoint headers are not represented as Kotlin codec execution; those codecs have separate JVM checks above and inherited suites.

## Source wiring observations, not runtime acceptance

- MainActivity creates the Save runtime independently and subscribes media-owner priority to Discovery; the Browser command gate depends on the browser feature entitlement/platform policy, not provider availability.
- The normal BrowserScreen's Save action calls `saves.inspectFromUserClick(browser)` directly. Its button eligibility is a READY selected tab and no active scan; provider count and Discovery health are absent from that condition.
- These are inspected source properties. MainActivity, Compose, AndroidSaveRuntime, SystemBrowserHost and system WebView were not executed by these host tests.

## Reproduction

From the repository root:

```sh
bash docs/m9a/evidence/run-isolation-host.sh
python3 docs/m9a/evidence/verify-process-reopen.py
```

A missing shared frozen manifest fails closed by default. The compiled inputs plus the runner must be a subset of that manifest; every frozen file and the manifest itself are checked before/after. For an explicitly non-acceptance development run only, `ESV_ALLOW_UNFROZEN_REVIEW=1` permits a missing manifest and marks the evidence accordingly.

The runner uses the existing recovered JDK and admitted Kotlin/JUnit cache, accepts the same explicit `ESV_REVIEW_REPO`, `ESV_TOOLCHAIN_ROOT`, `ESV_REVIEW_CACHE` and `ESV_REVIEW_JAVA` locations as the inherited host checks, and neither downloads nor starts Gradle. Its result logs contain technical result codes/counts only; temporary compiler/JUnit diagnostics are captured and removed, never printed verbatim. Source/artifact identities are recorded separately in JSON manifests. The build owner separately owns full app JVM, lint and aggregate integration evidence.

## Preserved scope and remaining gates

M8-B1 revision/alias/transaction-rollback rules are explicitly exercised in the source-guarded SQLite host model. M8-B2 Save/Record priority is exercised through the real tracker/coordinator. The M8-B2 identity UNKNOWN and M8-B3 Release NOT_CONNECTED implementations are unmodified and remain covered by the inherited suites; this matrix does not claim new execution of those two gates.

Only fixed Commons schema 1 participates. No dynamic/M7-D blocked provider review/probe, Vault, new library, real account, payment, SDK/SkinKit change, APK/AAB, device test, installation or external publication is part of this work. No extreme resource attack was performed.
