# Save failure guidance and fresh inspection boundary (2026-10-12)

## Scope and baseline

Original requirements: Product §7.1/7.3, PR-12; Technical TR-07 and §§5, 7.
The real remote baseline supplied by the publisher is
`development/cloud-followup-20261013` at
`b6032e8bce62b631572b98de8b2cce82d6a387af`. This worktree has no recovered Git
history. This document is a source implementation report, not a commit or push claim.

This slice supplies truthful terminal failure advice and a user-directed fresh
inspection loop. It does not claim complete PR-12 fallback capability, a trusted
webpage-recordability verdict, additional protocols, or a functioning ②→③ resource
adapter. No new dependency, schema, entitlement, destination, permission, Vault,
payment, logging/export policy or existing task execution behavior is introduced.

## User flow and authority

1. Task detail displays classified next-step advice only for FAILED or CANCELLED.
   Existing waiting/resume/cancel controls remain unchanged for unfinished work.
2. Selected network failures offer “Return to browser to inspect again”. A fresh
   one-use view-lifetime command captures only task ID, revision, state, method and
   error. At click time the current record must match. A present, newer coordinator
   snapshot rejects the command; old terminal rows absent from that nonblocking
   memory cache after restart remain eligible through the current durable UI list.
3. Runtime readiness, foreground, normal access gate, execution/resource release,
   pending commands/recording and idle HLS preparation are checked. Refused, expired,
   duplicate and temporarily unavailable commands show translated feedback.
4. Acceptance retires only the inspection UI. It never invokes task cancel/resume,
   submit, record start, journal modification, source access or media deletion.
   Active/prepared HLS is refused rather than cancelled by this command. The normal
   root navigation opens the browser without navigating to a failed-task URL.
5. The browser reminder tells the user to confirm the actual current page and tap
   the existing “Inspect and choose” control. Navigation itself performs no scan.
   The fresh scan captures selected tab/session, navigation epoch, READY load state,
   actual View identity, address/View URL agreement and inspection revision. The
   original checks are factored into `SaveInspectionPage`, not weakened.
6. The existing detector still fences late responses, has a five-second deadline,
   and creates candidates valid for at most 60 seconds. Existing explicit
   candidate/variant and destination admission, same-origin lease, protection,
   entitlement, resource/capacity and media validation gates are reused unchanged.
   A new task starts only through that existing explicit selection path.

The old failed task is immutable throughout. There is no transition of that task
to success and no use of its persisted origin-only reference as a new source URL.
No page history store or copied provider capability is assumed. Recording remains
an independent user-entry flow requiring its existing setup and fresh consent.

## Error / status / action matrix

| Classification | Errors | Next step | New task-detail action |
|---|---|---|---|
| Cancellation | CANCELLED error or CANCELLED state | Stop; only a separate deliberate future task | None |
| Protected/policy | PROTECTED_MEDIA, POLICY_BLOCKED | No bypass; use a permitted source | None |
| TLS | TLS_REJECTED | Verify site security; do not bypass certificate checks | None |
| Storage | STORAGE_FULL, STORAGE_FAILURE, JOURNAL_UNAVAILABLE | Check space/access yourself; no automatic deletion | None |
| Recovery | COMMIT_MISMATCH, RECOVERY_REQUIRED, VERIFICATION_UNAVAILABLE | Wait/reopen and review real recovery status; retained file is not success | None |
| Unreleased | RESOURCE_RELEASE_UNCONFIRMED | Reopen and review recovery; no alternate method while release is unknown | None |
| Busy | BUSY | Wait for media owner to finish/stop safely | None |
| Authentication | AUTHENTICATION_REQUIRED, HTTP_REJECTED | Normal access/login check, then fresh inspection; no bypass | Browser only |
| Source identity | EXPIRED_RESOURCE, HTTP_NOT_FOUND, INVALID_RANGE, RESOURCE_CHANGED, SOURCE_RESELECTION_REQUIRED | Fresh detection and explicit selection; new task from start | Browser only |
| Network | NETWORK_UNAVAILABLE, NETWORK_TIMEOUT, HTTP_FAILURE, RETRY_EXHAUSTED, DEADLINE_EXCEEDED, INCOMPLETE_TRANSFER | Check network/site first; no inferred alternative capability | Browser only |
| Rate limit | RATE_LIMITED | Wait first, then manual detection | Browser only |
| Unsupported method/media | METHOD_UNSUPPORTED, UNSUPPORTED_MEDIA | Reassess newly detected ①/② resources; no guarantee or protected-content recording bypass | Browser only |
| Invalid output | INVALID_MEDIA | No saved video; select a fresh supported resource if present | Browser only |
| Budget | BYTE_LIMIT_EXCEEDED | Review limits; another method cannot remove them | None |
| Recording consent | CONSENT_DENIED | User may independently revisit setup; fresh consent, no reprompt loop | None |
| Recording audio | AUDIO_PERMISSION_DENIED, AUDIO_FAILED | Explicit audio/silent choice; no microphone substitution | None |
| Recording setup | CAPTURE_UNAVAILABLE, FGS_START_REJECTED, PROFILE_UNSUPPORTED | Review setup/system availability, separate explicit session | None |
| Recording output | CAPTURE_EMPTY, CAPTURE_TOO_SHORT, KEYFRAME_TIMEOUT, TIMESTAMP_INVALID, ENCODER_FAILED, FINALIZATION_FAILED | Review recording/retained segments; no fake successful artifact | None |
| Unknown | INVALID_REQUEST, UNKNOWN_TECHNICAL_ERROR, absent error | Review error; existing Settings redacted diagnostics if persistent | None |

Error safety classifications take priority over method. Other network-only advice
cannot create a browser action for RECORD, BROWSER or EDIT. QUEUED, RUNNING,
WAITING_USER, INTERRUPTED and SUCCEEDED have no new failure-guidance action. A
CANCELLED state wins even if an inconsistent row carries a different error.

## Verification design and limits

- Exhaustive current SaveError × TaskState × TaskMethod with null-error handling;
  explicit error partition detects a newly added unclassified enum.
- Exact action whitelist; cancellation, DRM/policy/TLS, storage, release, recovery,
  recording consent/audio denial and nonterminal refusal.
- One-use command, missing task, changed ID/revision/state/method/error, unavailable
  retry without consumption, and original identity unchanged.
- Current page equality, tab/session/epoch/View/revision/address changes, absent or
  loading page, backgrounding, and existing candidate expiration.
- Paired English/Chinese resources and critical no-bypass/no-fake-success copy;
  source integration checks ensure navigation is not an execution port and retains
  actual candidate/variant fences. These source checks are explicitly not Android
  execution or permission tests.
- Both Kotlin variants, all App JVM tests, save-module tests and Debug lint run via
  the shared serialized Gradle owner. Final results and source hashes are in
  `artifacts/ui/save-failure-guidance-20261012`.

No APK assembled, installed or uploaded. Device/emulator, actual WebView callback,
Compose rendering, Android recreation, accessibility, 200% font, and real network/
MediaProjection/media publication acceptance are NOT_RUN. No phone performance or
ANR conclusion is made. This source slice does not alter previously delivered APKs.

## Results

Final check build3: BUILD SUCCESSFUL (69 tasks, 12 executed / 57 up-to-date).
App: 1510 tests, 171 suites, 0 failures/errors/skipped. Save: 86 tests, 4 suites,
0 failures/errors/skipped. New tests: 22 included in the App total. Both Kotlin
variants executed successfully on final business source in build2; build3 correctly
reported them up-to-date after the one test-only count fix. Debug lint: 0 errors,
60 warnings and 8 hints (existing warnings remain; not a warning-free claim).

First focused build passed 17 policy/page tests and both Kotlin variants. First
full App run had 1510 tests with one new contract assertion failure (expected 29
resource entries, actual 28), corrected and rerun. Final paired resources: 28 EN/ZH.
Intermediate logs are retained rather than hiding the failed check.

Independent read-only source review found no new P1/P2 blocker and verified all
13 final source/resource/test hashes in SOURCE_SHA256.tsv. The reviewer did not
rerun Gradle or claim device validation. Source-string contracts are not runtime
integration tests. This slice is frozen for parent-controlled remote publication;
no upload or commit was performed by its author.


### Release lint follow-through

Separately executed `:app:lintRelease` after production freeze: BUILD SUCCESSFUL,
0 errors, 60 warnings, 8 hints. Debug lint remains
0 errors, 60 warnings, 8 hints; these are separate actual variant results.
No production file changed. All 13 SOURCE_SHA256 hashes rechecked unchanged.
Final delivery JUnit XML hostnames are `cloud-jvm`; absolute environment source/home
roots are redacted before evidence hashes and publication whitelist are generated.
