# Independent source review: recording notification localization

Date: 2026-10-13 (UTC). Scope: the approved bounded recording-notification plan against the supplied completed source baseline `4f22d43d023b7db6e3051613ed02c144bf3ad75b`.

## Verdict

**Final verdict: PASS for the bounded source change and final build/JVM evidence. Device acceptance remains NOT_RUN.** The source review below records the pre-build snapshot; the final build/evidence addendum records the failed first run, exact test-only correction and successful complete rerun. No unresolved production-source blocker was found.

The reviewer made no production, test, Gradle or dependency edits, and did not run Gradle or operate a device. Only these review artifacts were written.

## Finding fixed before build

The first in-progress `AndroidSaveRuntime.kt` inserted the language mirror/function between `recordingService` and its existing custom setter, leaving `set(value)` detached after a function. This was a Kotlin source blocker and would also lose the intended adjacency of the media-task activity setter. The owner moved the new block below the original setter before starting any build. The reviewer reread the corrected source, checked exact baseline replay, and verified the new wiring test pins the original property/setter adjacency. No compile failure is claimed for this discovery because no compile was attempted on that version.

## Scope and text inventory

- Exactly 15 actual fixed display leaves: six native status groups, one initial foreground status, five existing segment capture statuses, and channel/title/action labels.
- The closed enum has 12 statuses. Native mapping preserves every existing RecordingState grouping, including STOPPING/FINALIZING and the original preparing/verifying fallback. The duplicate resource-release warning uses the same typed status.
- The segment producer still reaches exactly PREPARING, RECORDING, PAUSED, RESUMING and STOPPING. The other eight presentation phases return no notification status, including SUCCEEDED. No new success notice or publication claim is introduced.
- Ten paired English/Chinese resource keys were added. Five segment strings reuse existing `final_status.xml` resource IDs. All 15 Chinese leaves match their legacy text exactly; no request ID, URL, filename, user text or Throwable is interpolated.
- Exactly three existing `showStatus` caller sites remain: two native/runtime sites and one segment site. No new status-emission schedule was added.

## Session and ownership review

- Existing ID validation, pending-recording admission, START_NOT_STICKY, ACTION_START/ACTION_STOP matching, stale-stop rejection, screen-off handling, receiver setup, service-loss/destroy callbacks and worker/main dispatch are preserved.
- The foreground service is promoted before its typed display cache is enabled, and before the existing ready callback reaches projection admission. Capture/projection APIs were not changed.
- `showStatus` rejects mismatched IDs and finishing state, then updates only an already-promoted matching display. Runtime notification failures are caught without claiming capture success.
- Locale/configuration refresh requires the existing request ID, non-finishing state and a live promoted snapshot. It performs channel display-name adjustment and notify only; it does not start, stop, submit, revive or acquire capture ownership.
- The cache retires before finish removes foreground state and before destruction callbacks can synchronously deliver late statuses. A retired service instance cannot be re-promoted by a late display update.
- Notification ID 4301, channel ID `esv_recording`, LOW importance, ongoing/onlyAlertOnce, category, icon, and both PendingIntent identities/flags are preserved. The stop Intent still includes `identifier = id`.
- Channel rename retrieves the existing channel and changes its name only. It does not delete a channel or alter user sound/vibration/importance settings.

## Locale and lifecycle review

- `AndroidSaveRuntime` holds a process-local AppLanguage mirror, not preferences or a new locale persistence owner. Both updates remain main-thread calls.
- MainActivity publishes only an explicit accepted Language edit and the final effective initial settings merge. The existing early-edit-versus-late-load precedence remains unchanged. No temporary onCreate default or composition effect resets the runtime mirror.
- Unchanged language updates are idempotent. The typed status survives language refresh; no already-localized String is cached and no refresh rewinds the status to preparing.
- API31/32 explicit English/Chinese creates a fresh configuration-context copy using the existing host locale policy. SYSTEM and API33+ use the current service platform resources. Compose and Service share the stateless helper.
- Configuration callbacks render from current resources rather than a stored Configuration. Finished/destroyed sessions cannot resurrect a notification through these callbacks.
- API33+ accepted-language mirror updates occur before the existing asynchronous settings save changes platform locales. Their immediate refresh therefore uses the current platform configuration, and the later platform configuration callback is responsible for the new language. This matches the approved single-owner plan; actual callback timing is not proven by JVM fixtures.
- BrowserPersistence's existing closed gate prevents a destroyed Activity's initial settings callback from delivering. Existing Activity foreground/pause behavior is unchanged. In particular, platform locale recreation must not be represented as proven uninterrupted recording.

## Evidence and test review

The six modified existing files are exact replay of the documented bounded substitutions over the supplied before snapshots. All six before snapshots match the completed previous slice's full 1,409-file manifest. Of those 1,409 existing files, only the six expected files differ and none are missing. Three production helpers, two resource files and three tests are new, yielding 14 reviewed source/resource/build/test files.

The baseline manifest records the previous slice's parent `55c51cd71763159b203e52ece44f09da8cab1507`; it is not incorrectly treated as a direct commit label. The prior publication `SUMMARY.json` identifies final commit `4f22d43d023b7db6e3051613ed02c144bf3ad75b`, its parent, 31 read-back published files and exact byte equality. The relevant modified baseline bytes agree with that slice's frozen manifest. This review relies on that recorded publication receipt rather than claiming a fresh remote readback.

Reviewed 28 new test methods:
- 6 Status tests: exhaustive native and segment mappings, 15 fixed leaves, ten new paired keys/five reused keys, no variable display parameters, closed typed tokens.
- 12 Display tests: pre-promotion, invalid/repeated promotion, latest-status retention, wrong IDs, retirement/late callbacks, synthetic rendering failure, separate instances, idempotent language changes, API31/32/33+ locale decisions and initial-settings precedence fixtures.
- 10 Wiring tests: actual production caller count/dispatch, phase reachability, foreground ordering, stop/screen-off identity, retirement guards, repaint-only boundaries, render-time/config text lookup, single locale owner, authoritative MainActivity feeds and contract input registration.

The wiring test’s initial generic forbidden substring `NotificationChannel(` would also match `getNotificationChannel(`. The owner narrowed it before the build pin to `NotificationChannel(CHANNEL,`; the reviewed final hash includes that correction. This was a test-only false-positive correction before execution, not a failed test run.

These are pure fixtures and source-text contracts. They do not execute Android Service, NotificationManager, platform configuration dispatch, FGS permissions or real MediaProjection. The source tests appropriately state this limit. Build configuration registers the new resources and relevant production files as contract test inputs; no dependency was added.

## Required unexecuted acceptance

All device/UI checks remain **NOT_RUN**: API31/32 first-frame notification language and live repaint; API33+ platform locale/configuration lifecycle; System locale changes; Activity recreation/background transitions; channel/action labels; real pause/resume/stop and Stop & Save; permission-denied visibility; stale stop actions; screen-off; and verified output accounting. No APK/device/publication result is established by this source review.

## Initial source/build1 hash binding

The reviewer compared all 14 hashes below against `INPUT_MANIFEST.json` and independently verified all 1,417 pinned input files still match the current tree. The manifest SHA-256 is `ad12e48d69b17f24bed78fbdde4e8d94243958ef827a11ac7c72bb941d0395cf`.

The verdict applies only to the following exact bytes. Later edits require rereview and revalidation. The retained pre-build `INDEPENDENT_SOURCE_CHECKS.build1.json` records these initial baseline/replay checks and has SHA-256 `e47936c19c09f8f045313f45d3769cd2ebf58c5ff2bfe0be77a3ddb79c7359c2`. The final manifest and corrected test hash are identified in the addendum.

- `app/build.gradle.kts`: `216bfd8659d393ff29f42bd09f401c29bf65c15d1b1148fb3d4c36b818200783` (12674 bytes)
- `app/src/main/kotlin/com/easysavevideo/app/MainActivity.kt`: `6a065403a409e89a786eee9346a18e265837812fb7b3bd65da853de7027d29ff` (49085 bytes)
- `app/src/main/kotlin/com/easysavevideo/app/recording/AndroidSegmentRecordingRuntime.kt`: `c39e3791dc9244f1f378aebb5e8f9be70f8dae3a928708f017b702353d544044` (49312 bytes)
- `app/src/main/kotlin/com/easysavevideo/app/recording/RecordingForegroundService.kt`: `cb285da9310dd77c76d644c070d16dd3ae7b3fe5663c82c499a3068d26e0012d` (7608 bytes)
- `app/src/main/kotlin/com/easysavevideo/app/recording/RecordingNotificationDisplay.kt`: `4fe9f0289d7582a1a6db7639df394d12eccd593d77351d704d2adfcae3e58840` (1625 bytes)
- `app/src/main/kotlin/com/easysavevideo/app/recording/RecordingNotificationStatus.kt`: `0524aedb5cd0413d9a9814572b6c22c1bdfc7610556b5be68e21023273d7635d` (3482 bytes)
- `app/src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt`: `1fc97a9b98e0d98b2751f32a9ec67be03d6525a0dbe5916cb292ed1181a07bb9` (62726 bytes)
- `app/src/main/kotlin/com/easysavevideo/app/ui/shell/EsvLocaleContext.kt`: `bd933b1673278f250f828c3b2e2e40977dfb90cd604b6fceaee0e9d8b5dc2461` (849 bytes)
- `app/src/main/kotlin/com/easysavevideo/app/ui/shell/EsvLocaleHost.kt`: `71acd38ce6956e407ad5a8a8faac4b25ef39db94780ba052a9ebad3fcab800b1` (924 bytes)
- `app/src/main/res/values-zh-rCN/recording_notification.xml`: `ff43d31d6c8fc50e3c7978c48f0fc2b5c67fc8240f52f99b57b575ab84f84a47` (952 bytes)
- `app/src/main/res/values/recording_notification.xml`: `743979595a28feea84ebd0f88cd77d039fd1d73bc96ab41b84af2f67e8420d42` (1007 bytes)
- `app/src/test/kotlin/com/easysavevideo/app/recording/RecordingNotificationDisplayTest.kt`: `ae213866d83040395bb32506665c70ef6484ef885c8240f049efff8d036ad577` (6783 bytes)
- `app/src/test/kotlin/com/easysavevideo/app/recording/RecordingNotificationStatusTest.kt`: `e0a88d834721bfb2300df8c6f88c9b0049aac9b84e2d6b4e2c26bfd3439628bd` (11979 bytes)
- `app/src/test/kotlin/com/easysavevideo/app/recording/RecordingNotificationWiringTest.kt`: `6a1059c8c4cb18958d1d3f54a4b1f9548344e02269bc16eddc1cc54aba88baee` (11015 bytes)

## Final build/evidence addendum

Reviewed independently on 2026-10-13 after the second Gradle run reached its successful terminal state. **PASS_SOURCE_JVM_EVIDENCE_ONLY; device/UI acceptance remains NOT_RUN.** No Gradle invocation, production edit, device operation or APK build was performed by this reviewer.

### Initial failure retained and exact correction verified

Build1 terminated FAILED after 2m 56s and actually ran 2,026 App tests: one failure, zero errors and zero skips. The single failure was `RecordingNotificationWiringTest.localeHelperIsSharedStatelessAndDoesNotCreateAnotherPreferenceOwner`, with `AssertionError: startForeground` at the original line 100. The test incorrectly applied its task-start ban to the entire pre-existing foreground Service. The earlier source review did not catch this fixture-scope defect; the failed execution exposed it.

The sole input difference between the two 1,417-file pins is `RecordingNotificationWiringTest.kt`. The correction removes `startForeground` from the whole-Service forbidden list and asserts it separately against the new locale helper. Whole-Service preference/locale-writer bans, refresh-specific start bans, and the original foreground-start call-count/order contracts remain intact. Applying the documented inverse correction reproduces the exact first-run test hash:

- Build1 test SHA-256: `6a1059c8c4cb18958d1d3f54a4b1f9548344e02269bc16eddc1cc54aba88baee`
- Final test SHA-256: `ccd750e9b857bd9ac1e7796513502820dea696a36d908ac89696458f375eb32c`

All production/resource/build inputs are identical between attempts. The other 13 originally reviewed changed/new files retain their initial hashes. All 1,417 final pinned input bytes still match the current tree. The final input-manifest SHA-256 is `cc5495ec67c45059792608bad2519a6dbaf08a155c1f8fb0d425929a1818ee07`.

The initial failure remains inspectable in build1.log, INPUT_MANIFEST.build1.json, FIRST_ATTEMPT_CENSUS.json, FIRST_ATTEMPT_JUNIT.zip and TEST_ASSERTION_CORRECTION.json. The first-attempt ZIP preserves all 223 JUnit suites and the same sole failure. Archived XMLs redact the machine hostname and are re-serialized; they are not misrepresented as byte-identical to raw executor XML.

### Terminal build and full App test results

The reviewer parsed the final build log and matched its task observations to TASK_STATUS.json. Build2 reports `BUILD SUCCESSFUL in 3m 4s`, with 75 actionable tasks: 17 executed and 58 up-to-date. All ten requested target tasks have an actual EXECUTED observation, not an UP-TO-DATE-only observation:

- Complete App `testDebugUnitTest`
- Debug and Release Kotlin compilation
- Debug and Release lint analysis, report and entry tasks
- Exact runtime admission verification

The final archive and current raw reports independently total **2,026 tests, zero failures, zero errors and zero skips**, in 223 suites. The 28 new tests are all included (6 Status, 12 Display, 10 Wiring). Every final suite timestamp is later than the final input pin. Each of the 223 archived JUnit XMLs is semantically identical to the current raw report after the documented hostname/path redaction; both full censuses agree with their respective archives. This is a complete App rerun, not a focused-test substitute. Other modules' tests were not rerun for this slice.

Runtime admission reports **90 exact reviewed artifacts for both variants**. The seven recorded boundary checks all pass and their JSON agrees with boundaries.log. These boundary results are reviewed build-owner evidence, not a claim that the reviewer separately executed the checker.

### Lint comparison

Both current lint reports contain **60 warnings, 8 hints and 0 errors**. Their complete `(id, severity, message)` multisets are identical to the authentication-origin baseline whose evidence archive is bound to its frozen publication manifest (SHA-256 `57e2f0cb16f0442893e44debd9e3f9b856401d9937838586ac597afcfee06442`).

The existing AppBundleLocaleChanges warning moves from EsvLocaleHost.kt to the extracted EsvLocaleContext.kt in both variants. This is the only location-file multiset change; line/column offsets are not treated as new diagnostics. No new diagnostic identity/message was added. Existing warnings remain open, including that app-bundle locale warning.

### Archive integrity and evidence completeness

- Final COMPLETE_APP_EVIDENCE.zip: 198,319 bytes; SHA-256 `49a350937500f0d49b38e7b5948e31bc7fcd1c1f268db4dcb79c25cdf117f277`; 228 entries, comprising 223 App JUnit XMLs, two lint reports, both complete build logs and one manifest. CRC verification passed, all 227 payload byte sizes/hashes match the manifest, no duplicate/unmanifested payload exists, and archived logs/lint agree with the current evidence and documented redaction.
- FIRST_ATTEMPT_JUNIT.zip: 175,229 bytes; SHA-256 `21c4ac431f2946f7be2fde8be67fe2353937fcd10dd5c92d62ec034e9f2a4ae8`; 224 entries, comprising all 223 failed-run App XMLs and one manifest. CRC and all 223 payload byte sizes/hashes passed.
- The published-evidence directory and both archives contain no APK/AAB/DEX/JAR/AAR payload. The final Gradle task log contains no assemble or install task. This review does not advance the older local-only APK's source binding or claim a new device artifact.

The implementation report accurately keeps API31/32 cold-start first-frame locale races, API33+ platform-locale/Activity lifecycle and recording continuity, real Service/NotificationManager behavior, stop/stale-action/permission/screen-off behavior, device/emulator operations, accessibility/200%/RTL/TalkBack and PPT/pixel acceptance as unexecuted. The existing OPPO and Windows issues remain open. No actual on-device no-resurrection or uninterrupted-recording guarantee is inferred from the JVM/source fixtures.

Detailed independent evidence-check SHA-256: `d822ac1bf1baaf2bbe95479d61fc349713ffc4b0142f4fcef0b4085aeabffcd2` (`INDEPENDENT_EVIDENCE_CHECKS.json`). Updated source/hash-check SHA-256: `c6abf2cce453dd58fe47994a5216218d7a65eca0025f2f13148f3e83bad4e74d` (`INDEPENDENT_SOURCE_CHECKS.json`), which preserves initial hashes separately and identifies the one final test correction. No unresolved source or evidence blocker remains within this bounded source/JVM slice; publication/readback and device acceptance are separate gates.
