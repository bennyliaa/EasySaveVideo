# Evaluation export device acceptance preparation

Status: **UNEXECUTED on device**. Compiling instrumentation is not device acceptance.
Scope: explicit debug evaluation, sealed synthetic report JSON, SAF document adapter,
owner/lifecycle rejection. No recording method 3 support is introduced.

## Execution gates

Use only an independently authorized test device and compatible preserved-data build.
This preparation does not install, assemble, sign, start instrumentation, or contact a
provider. Reuse the existing platform Instrumentation runner and dependencies. Never
clear application data, change security settings, use accounts/credentials, select an
existing personal document, or use a cloud destination for this acceptance run.

After a separately authorized compatible test APK is installed, the bounded command
for the previously authorized emulator is shown below for the tester; it has **not**
been run during this slice. Confirm the installed runner package before using it.
Do not substitute another device without authorization.

```sh
adb -s emulator-5556 shell am instrument -w -e phase evaluation-export com.easysavevideo.app.test/com.easysavevideo.app.validation.OwnedVerificationInstrumentation
```

The new `evaluation-export` runner phase is opt-in. Existing default `b`, `ab`, and
`opus-technical` phases retain their meanings. Run only the new phase for this scope;
the other phases are not prerequisites. The test-only provider and fixtures must not
appear in the production manifest or APK. Automated fixture results are synthetic,
never observed save success statistics.

## Automated adapter acceptance

The instrumentation phase exercises the production Android document adapter through a
registered test-only DocumentsProvider, ContentResolver, and real file descriptors.
It uses only dedicated temporary files owned by that test run. Check the phase report
and instrumentation result; execution exceptions are failures, not skipped passes.

Required evidence:
- Empty regular document accepts the exact sealed JSON bytes; Android JSONObject
  parses them and the fixture reports FINISHED/SYNTHETIC_FIXTURE.
- Raw fixture provider ID, URL, query, title and token markers are absent. Aliases
  remain usable within the report. No success is counted for untested method 3.
- A nonempty fixture is rejected and its original bytes/hash are unchanged.
- Missing result read/write flags, malformed/non-document URI, provider failure,
  and nonregular/pipe descriptor are refused without claiming success.
- Cleanup touches only the run's known fixture files. A failure to clean is reported;
  no broad recursive deletion of application storage is permitted.

This is real Android adapter I/O against an owned fixture provider. It does not verify
system DocumentsUI, an OEM provider, permission prompts, or user-visible lifecycle.
The unit tests additionally inject unknown size, flush/close failure, extent races,
late callbacks, and owner changes. These are deterministic injected failures, not
proof that a real provider produced those failures.

## Manual system picker and UI acceptance (all initially UNEXECUTED)

Use an empty dedicated local test folder and synthetic candidates only. Record device
model, Android/API, build/source digest, provider package/version, orientation, and
case result. Keep screenshots free of personal filenames and account identities.

1. Start explicit debug evaluation; open the selected candidate using its evaluation
   action. Start only intended save tests, end the session, and preview the sealed
   report. Verify partial/untested values, including method 3. Ordinary browsing and
   saving without this explicit context must not add evaluation observations.
2. Tap export once. Confirm `application/json`, suggested JSON filename, and local
   destination request. Select a newly created empty test document. Success must
   follow flush and confirmed close. Read bytes back independently, parse JSON,
   compare the displayed report's counts and aliases, and scan synthetic raw markers.
3. Open picker then cancel/back. Expect cancellation, no success, and no payload write.
   A provider may have created an empty placeholder; do not delete user files.
4. Open picker then rotate/recreate the activity. Return a result from the old picker.
   It must not write a stale report or show success for a new owner. Start a fresh
   explicit session/report if activity state was intentionally lost.
5. While picker is open, background and return. Picker-owned pause is allowed; an
   actual current result may resume. Background during a queued/writing export must
   cancel publication. A canceled in-flight write may leave a partial test file;
   never label it successful or silently remove it.
6. Change report/start another session or close the evaluation panel/activity while
   picker is open. Return the old result; it must not open/write a new owner's file.
   Repeat export taps; no overlapping export is admitted. Verify a new valid export
   remains possible after confirmed cleanup.
7. If the provider offers replacement of a nonempty test document, use a fixture
   containing a known marker, record its hash first, and confirm the application
   rejects it without altering bytes. If DocumentsUI creates a renamed empty file
   instead, record this case as NOT REPRODUCED and rely only on the owned-provider
   automated nonempty case; do not claim the UI exercised replacement rejection.
8. Revoke only the temporary test grant through a supported controlled test fixture,
   or use the owned provider's error case. Expect failure without success. Do not
   change global device permissions/security settings for this test.
9. Close/flush failure requires a controllable provider or injected test port. Mark
   real-provider close-failure acceptance UNEXECUTED if unavailable. A provider's
   remote close error can be surfaced differently from a local descriptor close;
   do not infer it from the JVM injection. Unconfirmed cleanup must keep the shared
   fence blocked until its actual release contract permits another operation.

## Interpretation and limits

`EXTRA_LOCAL_ONLY` is a request, not a guarantee against a provider's own cloud sync.
The application does not actively upload this report. Nontruncating `rw` plus
`O_APPEND` protects against overwriting through the application's descriptor;
size checks reject observed extent changes. They do not establish exclusive creation,
provider atomicity, detect all same-size external rewrites, or guarantee no external
change after the last check. Unknown/nonregular destinations fail conservatively.

Store each device case as PASS, FAIL, NOT REPRODUCED, or UNEXECUTED with evidence.
Do not combine compiler/JVM PASS with an unrun device case into a device PASS.
