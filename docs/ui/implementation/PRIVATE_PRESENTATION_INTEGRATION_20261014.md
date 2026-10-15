# Private presentation and capture-window integration — 2026-10-14

## Verified checkpoint

The current source passes **4958 tests + 2 explicit skips**, across 458 suites and all 13 modules, with zero failures/errors. The app contributes 3397 passing cases. The final forced-test build exited 0 (`full-regression-final.log`, BUILD SUCCESSFUL in 3m 15s). Both Kotlin variants compile; debug/release lint reports zero errors, 71/72 warnings and 8 notes each. Runtime admission remains exactly 91 reviewed artifacts.

Evidence is frozen under `/workspace/shared/esv-private-presentation-checkpoint-20261014`: final XML, totals, lint SARIF, runtime build log, boundaries, host SQLite and WebKit license/byte checks. The two skips are the existing verification-local frozen-corpus/component cases; they are not counted as passes. No APK assembly, emulator/device execution or new remote synchronization was performed.

## Implemented integration

- Real Main Activity root/Host/input/Dialog bindings and exact ordinary/private presentation ownership, including typed empty and error shells.
- SDK-isolated EditorInfo sanitization and retained InputConnection generation/delegate ownership. Cleanup cannot retire a newer reused provider delegate.
- Protected independent dialogs retain their registration until actual teardown is confirmed. Failure remains quarantined; late completion is handled.
- Actual NavHost/Browser child composition acknowledgements, exact route-entry tickets and production scheduler tests. Late attachment, detachment or dialog retirement requests a fresh composition rather than manufacturing frame evidence.
- Captured Tasks/chrome actions preserve root admission, route, mode and input epoch. Old private callbacks and ordinary-to-private-to-ordinary callbacks cannot act on a replacement. Existing live ordinary business guards remain.
- Only fully protected Browser/Tasks routes retain the private presentation. Other routes or saved-output previews switch display to ordinary mode, preserving the isolated private session and accepted Save work, then wait for exact departure before composing ordinary dialogs.
- Main now constructs the actual Owned recording presentation port and binds exact Bridge owner/readback/KEEP invalidation. Capture composition and normal browsing reveal are mutually exclusive while the capture owner retains its obligation.
- One-shot capture evidence binds the actual owned Activity/Window/Decor/root/WebView/document/route/geometry and a hardware draw followed by its matching submitted-frame callback. Independent protection/mute actions run even after an earlier failure. Native stop never waits for a protected frame; source release and protected-frame retirement remain separate.
- The completed CAPTURE_POLICY_ADAPTER installation gate is removed. PendingPrivateRecordingPresentationPort remains an unused fail-closed compatibility implementation; Main does not construct it.

## Approval and acceptance boundaries

The user approved private browsing and all three Save methods, including recording. Successful explicitly saved media remains in the library when the private session exits. Website camera, microphone and location access remains denied.

Two recording-specific product choices remain unresolved: the API31/32 snapshot-risk tradeoff when temporarily clearing FLAG_SECURE, and the proposed mode that locks webpage input during capture. The latter is **not** an accepted product restriction; the implementation defaults to PRODUCT_INTERACTION_POLICY_PENDING. If later approved, it requires starting playback before recording and prevents webpage pause/seek while retaining trusted Stop/Save and Discard controls.

These recording-specific choices do **not** apply to secure private browsing or network Save methods 1/2. API31/32 can use those secure paths independently. Separately, the actual private entry remains conservatively closed by the outstanding RETAINED_INPUT_CONNECTION, NATIVE_PRESENTATIONS and ANDROID_DEVICE_VALIDATION acceptance gates. That current platform-validation gate is not a decision to drop private browsing or any Save method.

Host/pure tests, source contracts and compiled API isolation are not real Android privacy acceptance. Still required: actual Compose/View ordering and first-frame behavior; API31/32/R8 loading; provider-internal IME work, popup/native-window behavior; WebKit profile cookies/isolation and abnormal-exit cleanup; real Android SQLite/Os; API33 Recents suppression; API34+ projection scope/OEM behavior; real audio/video/projection release, KEEP/discard, rotation and destruction. A submitted hardware frame does not prove compositor display or erase previous snapshots.

## Review corrections included

Independent review closed the dialog premature-unregister and reused-input-delegate cleanup defects; actual EditorInfo API introduction levels; Main modal protection short-circuiting mute; outer-before-child composition acknowledgement; and queued Tasks/chrome authority reuse. Source-contract adaptations retain original method counts, actions and guards and add exact admission checks. The first failed runs and their XML are preserved separately; only final successful evidence is aggregated.

## Continuation and backup

The immutable earlier 4746 PASS + 2 SKIP source package is preserved. This checkpoint gets a separate local source manifest/archive after this report. GitHub writes remain paused; the last verified private dev head remains `06d3f50884ea88d1de647d212264c6488d3a658d`. Do not infer a remote upload from the existence of a local archive.

Next unblocked original-requirement work is a narrow design for AAC-bearing MOV normalization with actual PCM-frame and clock evidence. The current MOV audio branch remains explicitly unsupported and the production MOV admission gate stays closed pending its own Android acceptance. No new dependency or permission is introduced by this next design. Paid/Vault/interpreter and production Discovery-source decisions remain in the original requirements queue.

## Per-module executed totals

- core-model: 7 pass, 0 skip, 3 suites.
- core-domain: 44 pass, 0 skip, 6 suites.
- ui-theme: 17 pass, 0 skip, 2 suites.
- discovery: 312 pass, 0 skip, 23 suites.
- save: 386 pass, 0 skip, 25 suites.
- recording: 127 pass, 0 skip, 6 suites.
- library: 280 pass, 0 skip, 24 suites.
- hls: 40 pass, 0 skip, 1 suites.
- commons-provider: 247 pass, 0 skip, 16 suites.
- searxng-provider: 25 pass, 0 skip, 2 suites.
- verification-client: 44 pass, 0 skip, 2 suites.
- verification-local: 32 pass, 2 skip, 6 suites.
- app: 3397 pass, 0 skip, 342 suites.
