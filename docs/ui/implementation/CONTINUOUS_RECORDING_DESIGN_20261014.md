# Continuous user-started BASIC screen recording: correction design

Date: 2026-10-14. Revision 2: independent review corrections incorporated. Status: DESIGN ONLY; source inspected, no source edits or build/device operations. Source root: `/workspace/shared/esv-cloud-handoff-20fddc9/source`. Read `AGENTS.md`. This design supersedes the old exact-page/locked-page/autostop requirements in the private-recording design documents, not the native ownership, permission, output-verification, browser isolation, or Region requirements.

## 1. Product contract

The latest user request is continuous recording of the Android-authorized screen scope. Normal interactions, seeking, navigation, new/closed tabs, ordinary app routes, dialogs, full-screen video, focus changes, Home/return and Activity recreation must not be app-invented Stop commands. Applies to BASIC recording started in ordinary or incognito browsing. Back navigates or dismisses controls; it is not Stop.

- Stop requests finalization and preservation of a valid verified result. It does not guarantee publication of an empty, damaged or unverifiable file.
- Discard requests cancellation of the exact unpublished recording. Existing published media stays untouched. A late Discard monotonically overrides an earlier KEEP only while the output is unpublished.
- Android projection revocation, process/service termination, real codec/audio/storage/resource failures and existing disclosed engineering budgets are exceptions. Protected/DRM content may be black; eligible audio may be absent. Do not promise impossible uninterrupted recording.
- The user chooses the scope offered by Android. API34+ BASIC must use the system user-choice consent flow. A size or visibility callback is not an attestation of the selected app or of full-display scope.
- API31/32 Recents-thumbnail residual risk has already been accepted by the user. Do not ask again, claim thumbnail erasure, or label acceptance as platform isolation validation.
- No website progress-bar recognition is needed. Normal WebView input lets the user seek; ESV need not know whether a gesture was a slider, link, player control or something else.

Suggested compact Chinese disclosure:

“录制会持续记录你在 Android 授权页选择的范围；拖动进度条、切换页面、弹窗和其他操作不会自动停止。范围内的网页、输入内容、控件、通知或其他可见信息都可能入镜。无痕录制期间会临时解除相关 ESV 窗口的防截图保护。受保护内容可能显示黑屏，部分声音可能无法录入。点击‘停止并保存’或‘放弃’结束；系统中止或资源不足也可能结束录制。”

API31/32 addition: “录制期间，最近任务列表可能留下页面缩略图，ESV 无法保证删除系统已保存的缩略图。” Retain the existing separate internal-audio/no-microphone disclosure. Show current engineering limits without calling them measured capacity.

## 2. Verified source problems

The current source is a deliberately fail-closed exact-scene implementation, not merely a missing input toggle:

1. `OwnedPrivateRecordingPresentationPort` defaults to `PRODUCT_INTERACTION_POLICY_PENDING`, requires API33+, freezes route/document/View/geometry and invalidates on focus/lifecycle/detach/layout changes.
2. `PrivateCaptureExceptionPolicy.valid` requires the original private session and selected target for the recording lifetime. `revalidate` and `drainSession` revoke the owner.
3. `PrivateRecordingBridge` registers `RECORD_STOP_FINALIZE` with `PrivateSessionLease`; leaving the private session therefore stops/finalizes projection.
4. Main's transition/modal/lifecycle hooks and browser integration invoke `protectAndStop`.
5. `PrivateContentRoot.revealCapture` deliberately blocks page touch, keys and IME. `BrowserScreen` separately hides its chrome, JS/file dialogs and full-screen view while recording, disables navigation/input predicates, and maps Back to Stop.
6. Native `AndroidRecordingSession` stops on rotation and subsequent capture-size changes. This affects ordinary BASIC too.
7. `AndroidSaveRuntime.requiresDefaultDisplayRecording` forces private BASIC onto the default-display-only factory on API34+.
8. `PrivateMainPresentationOwner`, ordinary-departure proof and app-dialog admission require all capture to drain. Removing Stop alone would leave routes/dialogs covered or blocked forever.
9. Visible private playback is tied to the initial recording View and DEVICE_AUDIO. SILENT can remain muted under the current capture-ownership classification. Browser playback and recording audio-track choice need separate decisions.

There is no `PrivateRecordingBrowserBridge` source file. The actual bridge is `PrivateRecordingBridge`, with `PrivateBrowserIntegration` declared in `browser/privacy/BrowserSaveRequestContext.kt`.

## 3. Minimum ownership refactor

Keep one existing recorder, one `RecordingForegroundService`, one `ProjectionGrantHandoff`, one execution claim, one native lease and the existing Save/output pipeline. No dependency, new framework, second service, system overlay or second MediaProjection.

### 3.1 Recording ownership belongs to the existing app runtime

`AndroidSaveRuntime` already owns pending/current descriptor, service, submitted state and native binding. Extend that existing owner to retain the continuous session's authorization/phase and expose removable UI observation. Do not keep Activity, browser, WebView, private profile, URL or document closures in the ongoing owner.

`PrivateRecordingBridge` becomes the Activity attachment/setup/controller. It may hold the initial private target during configuration, destination selection and the existing protected consent round-trip. Before native submission, validate the exact current explicit attempt and OS grant, but active ownership must not depend on the initiating page. Move ongoing native-start/drain handling to the runtime; the UI observes it while attached. The authorization exists for all continuous BASIC sessions, including ordinary starts with no private binding; an ordinary-started session may later display an authorized incognito session without replacing projection. On Activity destruction, remove the observer and retire that window attachment; do not stop an already submitted recording. A new Activity attaches to the same in-memory runtime session. Process restart never reconstructs authorization or restarts capture.

Retain the existing two-step private permission/Continue UI if desired; this change does not require redesigning consent. The pre-start consent timeout, deny/cancel handling and owner-ID correlation remain. A detached, never-submitted setup may be cancelled safely. That behavior is not an ACTIVE recording stop.

### 3.1.1 Submission must sever the entire Activity/profile retention chain

Moving only native binding observers is insufficient. The current `PrivateCaptureStartPermit.registrationFailed` closure reaches `policy.deliver -> revoke(attempt)`, and that policy/attempt reaches target/session/View plus `surface`, `scheduler`, `changed` and other Activity-bound callbacks. The native binding retains the permit throughout capture, so this chain otherwise keeps the old Activity/profile alive after UI detachment.

Use the smallest callback-free permit change: retain only the immutable capture descriptor, exact owner identity and revocation/one-use bits. Remove its stored `registrationFailed` closure and `notifyRegistrationFailure`. `consumeDeferred` returns the existing Registration result; `PrivateRecordingNativeBinding`/runtime handles the distinct FAILED and REJECTED results for that exact owner after all permit/owner locks are released. Consult the actual native-lease acquisition state on partial registration; FAILED must not manufacture DRAINED or a never-queued success. Its owner stop/drain path remains the authority.

At the successful submission handoff, runtime owns the permit and phase; release every initial-target/attempt/evidence and Activity-bound policy/surface callback link from the ongoing owner. The UI has only a removable subscription. Retire the setup policy's `surface`, `targetCurrent`, `surfaceCurrent`, `changed`, owner-thread dispatcher and timer closures rather than retaining it behind an apparently runtime-only wrapper. If an ongoing deadline is still needed, transfer its existing deadline to a runtime handler task carrying only descriptor/session generation, not the previous policy or Activity. Do not quietly remove real start/acquisition watchdogs.

The handoff boundary is accepted submission, not receipt of `nativeStarted`: rotation, detach, delayed first native event or an old ARMING timeout after submission must not invoke the previous target-bound Stop path. Tests must cover this submitted-but-not-yet-native-started interval and retain the native permit while checking that no profile/Activity/View is reachable through its fields/closures.

### 3.2 Projection is no longer a private browser source

Remove BASIC projection's `PrivateSessionLease.register(RECORD_STOP_FINALIZE)` dependency, the `privateCaptureLease` requirement at recording start and its on-exit stop callback. `PrivateRecordingNativeBinding` retains descriptor/identity, one-shot permit, monotonic disposition, queue/native ownership, actual-drain callbacks and uncertainty quarantine. Queue registration consumes the one-shot permit and acquires the existing native lease directly; it no longer acquires a browser source lease. `PrivateCaptureStartPermit.consumeDeferred` already provides the one-shot result primitive; use it with the callback-free ownership change above.

Do not replace the old lease with a fake completed registration or `OUTPUT_ONLY` registration. That would falsely assert browser-source release. Delete the now-unused recording-specific registration helper if no other caller remains; keep network source registration and its live-session checks unchanged.

Incognito exit still revokes input, cancels or detaches private network sources under their existing rules, releases WebViews/dialog/file callbacks and clears/deletes the profile only after its real obligations finish. An independently authorized projection can continue showing the new ordinary route, another app, a covered transition or black protected pixels. Profile cleanup neither stops projection nor waits for it. Already recorded pixels and saved media are not erased by profile cleanup; the recording disclosure explicitly covers that.

### 3.3 Two separate states, not exact-scene policing

Keep recording state and window exposure state separate in existing classes:

- Recording: pending -> armed/submitted -> recording -> stopping/finalizing -> terminal, owned by exact descriptor/session generation.
- Each owned window attachment: protected -> authorized for this live recording session -> protected/retired. A route, focus or content change may update its presentation; it is not projection revocation.

Refactor `PrivateCaptureExceptionPolicy` so its active-validity check is exact live recording ownership/stop disposition, not `PrivateCaptureTarget.session.isLive`, selected document, route, layout or popup absence. Its revocable permit and real-drain invariants remain. Separate window restoration completion from native release completion: neither substitutes for the other, and missing old-window draw confirmation cannot make a live independent recording appear stopped or drained.

Refactor `OwnedPrivateRecordingPresentationPort`/`PrivateCaptureWindowState` into a narrow owned-window exception adapter. Check the actual owned Window/root, explicit runtime authorization, accepted risk mode and restoration capability immediately before clearing that Window's FLAG_SECURE. Preserve stale/foreign attachment rejection and independent restoration attempts. Remove ongoing immutable-frame comparison and focus/layout/page/IME/popup watchers that issue Stop. The capture mode no longer needs proof that the entire page is isolated or that no provider popup exists. Existing normal private-browsing frame/input protections remain useful for real presentation transitions; they are not recording admission proofs.

For API31/32 use an accurately named accepted-legacy-risk capability; never issue `LEGACY_VALIDATED_ISOLATION`. API33+ retain `setRecentsScreenshotEnabled(false)` while appropriate. Autofill/Assist/content-capture suppression and generic labels remain; user-authorized pixel capture does not authorize logging browsing data.

### 3.4 Browser input, routes and dialogs remain usable

Normal private input lifetime fencing remains: stale callbacks to a closed/replaced View must still fail. Remove only recording-specific whole-page gating. `PRIVATE_CAPTURE_VISIBLE` needs a normal current input admission path, rather than keeping the lifetime revoked. Maintain nonrecording private protections unchanged.

Replace `protectAndStopCapture` at browser/UI transition call sites with a presentation-transition operation that can retire/rebind current presentation under an active recording authorization without issuing native Stop. Do not make it always return true: failed local protection still blocks that private presentation and reports the real fault. A temporary cover can be recorded without ending projection.

`PrivateMainPresentationOwner`, `PrivateWindowGuard`, `PrivateRootProtectionState` and `PrivateAppDialog` must accept the exact authorized continuous session as a separate presentation case. Ordinary departure still proves private Views/callbacks detached and relevant private windows retired; it no longer requires independent projection drain. Keep the actual `recordingSourceObligationsDrained` function truthful for new recording admission and native cleanup. Do not redefine it as true while recording.

Reuse the existing app-dialog registry. For active-session ESV-owned dialogs, install the registered window's pixel-exposure policy before sensitive content, preserve input/structure controls and restore protection on session end. Existing hardcoded dialog FLAG_SECURE and capture-drained prerequisites otherwise cause black dialogs or blocked actions; choose the explicit active-session path. Unowned Android/provider windows are governed by Android's selected scope and their own secure flags. No global window scanner or all-popup absence proof is needed for this product contract.

Recording controls belong in the app shell, not only the original private BrowserScreen. Keep a compact indicator and Stop/Discard reachable on ordinary routes and after mode switch. The existing notification Stop remains available when outside ESV; add a same-session Discard action through the existing service or a clear return-to-controls path. An in-app route-global control and notification actions need no overlay permission. Remove Back-to-Stop and recording-driven hiding of browser chrome/fullscreen/dialogs.

Normal selected visible WebView playback follows browser presentation/lifecycle policy irrespective of SILENT vs DEVICE_AUDIO. SILENT means no captured audio track; it does not itself pause or mute the video being watched. Hidden/retiring private tabs remain muted. Device-audio capture remains the existing Android eligibility-based capture, not a claim of per-page audio isolation.

## 4. BASIC geometry: smallest executable candidate

There is currently no BASIC resize implementation. Its fixed codec input surface is created in `prepareSegmentOutput`, then directly passed to the one VirtualDisplay. Existing `RegionGlCapture` provides original EGL/OES rendering, but freezes recipe/source dimensions and rejects non-full-image transforms; it is not a ready-made generic resizer.

First implement and validate the smaller fixed-canvas path:

1. Keep the current selected encoder dimensions, input Surface, muxer format and one VirtualDisplay for the entire BASIC recording. Letterboxing is an acceptable disclosed representation; changing the canvas mid-MP4 is unnecessary.
2. API34+ `onCapturedContentResize`: store the latest valid geometry in an atomic/coalesced slot for the native owner loop. Do not perform media lifecycle work from the callback. Visibility changes update observation/UI only. Do not stop BASIC when its selected app is temporarily hidden; the OS owns actual stop semantics.
3. API31–33: use the existing default-display/window-context geometry observation to detect rotation/size changes. API31/32 have no API34 callbacks; do not wait for them. Geometry is an observation, not a new grant or selected-source proof.
4. For BASIC, remove `SIZE_CHANGED` as the ordinary resize/rotation response. The executable candidate is: callback/observer publishes `(width, height, density, generation)`; the native loop takes the newest pending generation, checks the exact live owner and existing source resource bounds (each dimension in 1..8192, plus any actual platform allocation limit; these are not the initial portrait width/height), and calls `virtualDisplay.resize(width, height, density)` only if geometry differs from the last applied generation. Keep the existing codec input Surface and encoder canvas unchanged, deliberately accepting system fitting/letterboxing. A geometry observed before VirtualDisplay creation is applied immediately after that one creation. Latest-wins coalescing and exact-owner checks prevent resize after Stop/release. Do not create another display or request another grant per rotation. This is a candidate requiring the output tests below, not a claim that fixed encoder buffers already prove correct scaling on all versions.
5. Android documents aspect-preserving centered scaling to the capture Surface beginning at API32. This supports the fixed-canvas/letterboxed candidate; it does not prove ESV's actual encoder/device output. API31 behavior must be checked separately. Device evidence must establish orientation, no unintended crop/stretch, continued moving video, valid timestamps and final playback before claiming adaptation works.
6. If this candidate fails API31 (or a supported provider/device), add only the needed full-frame fit/resize operation by reusing existing EGL/OES mechanics. Keep that as a bounded implementation follow-on with real GPU validation; do not silently use an all-screen Region recipe as source-identity proof, weaken Region transform/crop checks, or rebuild the projection/codec on every rotation.

For Region and authorized-frame preview, keep existing frozen crop/source checks and current owner. This correction must not globally delete their `SIZE_CHANGED` handling or pretend their UX has become BASIC. Region continuity under geometry changes is a separate requirement/implementation decision.

The Activity's manifest currently does not intercept rotation configuration changes. Runtime ownership/observer reattachment is therefore necessary even if the encoder survives rotation. Do not avoid this by locking orientation or adding broad `configChanges` flags as a hidden product restriction.

## 5. Keep real stop/failure and output behavior

Retain `MediaProjection.Callback.onStop`, screen-lock/system-stop handling, FGS start/service-loss checks, codec/audio/storage failures, exact native lease release and output verification. A callback or UI state is not release evidence. `UNCERTAIN` still blocks conflicting ownership and requires recovery; never synthesize a positive drain.

Current BASIC engineering ceilings are 2 hours wall/session duration, 2 GiB file, 64 MiB free-space reserve, at most 10 minutes per explicit recording pause and 32 effective resume boundaries. Preparation/finalization are bounded (15 seconds each), with a bounded verification window. `checkEnvironment` stops before the file cap using `min(16 MiB, maxFileBytes / 8)` reserve, so an observed byte-limit stop can precede 2 GiB. These are existing `RecordingLimits` / M4 ADR limits, not measured supported duration and not final pricing/product promises. No need to invent unlimited recording or silently remove bounds. Web-video pause/seek is not an ESV recording pause/resume boundary.

Stop/Discard, notification/system callbacks and runtime shutdown all reach the same exact owner. Native drain and output publication are separately reported. On output verification failure show failure/recovery truthfully instead of calling a Stop request “saved.”

### 5.1 Discard remains effective after native drain

The current `stopPrivateRecording(...DISCARD...)` calls `recorder.cancel(id)` only. That is insufficient after transfer/native finalization has returned and SaveCoordinator is VERIFYING or COMMITTING: cancelling an already-drained recorder alone does not cancel the active Save publication operation.

- Before accepted Save submission, latch DISCARD on the exact binding/permit and close only with genuine never-queued proof, discarding the pending grant through the existing path. Submission must recheck that latch; a race cannot create uncancelled work later.
- After submission, including the submitted-before-task-registration gap, route Discard through existing `AndroidSaveRuntime.cancel(taskId)` / coordinator cancellation rather than `recorder.cancel` alone. Preserve the exact runtime owner's monotonic DISCARD latch (including the binding latch when present); ordinary sessions without a private binding need the same submission fence. Runtime cancellation synchronously calls `coordinator.signalUserCancel(taskId)` before asynchronously queued durable `coordinator.cancel(taskId)`; if the task is not yet registered, the binding/submission fence still prevents it escaping the request. Keep the existing task-command admission/rejection handling observable; do not claim cancellation completed merely because a command was attempted.
- Do not send KEEP/Stop through this cancellation path: it allows valid output verification/publication to finish.
- Reuse the current coordinator's cancel-versus-publication linearization. If cancellation wins before the final active-check/publication decision, the provisional output cannot become SUCCEEDED/published; cleanup remains owned by the existing transaction. If publication/SUCCEEDED already won, leave media intact and explicitly report “已保存，已无法放弃本次文件”; a separate deletion would require its own action. Do not invent a second publication protocol, assume button-click wall-clock ordering beats the actual publication decision, or treat all COMMITTING as already published.
- Keep task controls/owner identity through VERIFYING and COMMITTING after native drain. “捕获已停止，正在验证/保存” is separate from terminal saved/discarded status. Discard remains available until publication terminal; merely closing the private surface or finishing native drain must not destroy that control model. A later terminal result updates it accurately.

Exact existing anchors: `AndroidSaveRuntime.cancel` 784–805; `SaveCoordinator.signalUserCancel` 1070–1076; `SaveCoordinator.cancel` 1124–1144; `runTransferredLocal` 689–729, especially the final cancel-versus-publish decision at 720–727. Verify with blocked verifier and blocked provisional commit/inspection, plus publication-wins tests.

## 6. Platform basis checked for this design

- [Android media projection](https://developer.android.com/media/grow/media-projection): API34 user-choice scope, one-use grant/display, resize/visibility observations, API32 centered aspect-preserving scaling, and real system stop callbacks. The fixed-canvas candidate is an implementation inference requiring device verification.
- [VirtualDisplay.resize](https://developer.android.com/reference/android/hardware/display/VirtualDisplay#resize(int,%20int,%20int)): resizing the existing display is supported from API21; it avoids recreating the display.
- [Activity.setRecentsScreenshotEnabled](https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean)): available from API33. API31/32 risk acceptance is not equivalent API evidence.
- [Capture video/audio playback](https://developer.android.com/media/platform/av-capture): audio capture requires the actual permission and projection grant and is limited by player policy/usage/profile. Preserve those limits.

Implementation sequence and exact source anchors are in `TOUCH_MAP.md`; required evidence and obsolete test expectations are in `ACCEPTANCE.md`. All new implementation and device rows remain NOT_RUN.
