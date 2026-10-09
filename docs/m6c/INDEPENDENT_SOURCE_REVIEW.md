# M6-C independent authorized-frame preview source review

Review dates: 2026-10-06–07 UTC. Review baseline: M6-B `f3253eadaf8f2d50acc6e64c02aa610f5f87bda2`.

## Status

**COMPLETE for the scoped ordinary-media source/host review. No unresolved blocking
source defect was found after the R1–R9 corrections below.** This is not Android/device
acceptance, release approval or full M6 completion. Build-owner aggregate/lint and
cold-restoration evidence are separate gates.

The final reviewed manifest is `evidence/review-source-manifest.json`, **434 entries**,
SHA-256 `690f6ded6208fc6fc123f613e2381cb46a6d0e69e03b3bd54341db213f572c8d`.
Every extent/hash independently matched at 2026-10-07 00:01:54 UTC. The final focused
host run passed **224/224 tests**, and a separate M6-B runtime/publication regression
run passed **53/53 tests**. All compiled source/test inputs were unchanged during each
run. The final port-header clarification changed documentation only and is included
in the verified final manifest.

The reviewer owns only this report and independent review evidence. No production
source changes, Gradle invocation, APK/AAB, device use, real MediaProjection consent,
recording, playback or publication are authorized or performed here. Vault is disabled
and excluded; this is ordinary-media preview/recording source review only.

## Scope and required evidence

The intended source route is one fresh system consent, one projection and virtual
display, encoder-free OES/EGL capture, bounded readback of one authorized real frame,
a secure frozen selector, explicit identity-bound confirmation, selector retirement,
a new browser-host readiness edge and then selected-pixel encoding on the retained
source. The M6-B proportion schematic remains a separately disclosed mode.

Review challenges include:

- First publication occurs only after the recording/selector UI is removed, browser
  host readiness is established and a new authorized source frame is latched.
- Confirm binds exact session, snapshot, geometry, accepted texture transform and
  generation; late callbacks cannot replace current authority. Background, lock,
  rotation, projection stop, geometry change and expiry invalidate pending authority.
- Secure selector pixels cannot enter the subsequent recording. Confirmation is not
  permission to encode before actual selector retirement and a new readiness edge.
- Crop coordinates use the same aspect-fit image extent, full display origin and
  producer transform as encoding. GL bottom-up bytes must be converted deliberately.
- Preview allocations, source lifetime and UI readers are bounded. Retirement revokes
  new readers without recycling pixels still used by Compose/RenderThread.
- Preview bytes have no disk, persistence/saved-state, logs, export/share or catalog
  route. No encoder/audio/output file is created before explicit confirmation.
- Native release uncertainty retains the global/native owner. Cancellation and UI
  timeout do not stand in for actual native closure.
- M4 recording, M6-B schematic/segments, consent tombstones, catalog cancellation
  admission and dependency/entitlement boundaries remain intact.

## API contracts independently checked

Official sources were opened on 2026-10-06. These are design references, not copied
implementation or runtime/device evidence.

1. [Android media projection guide](https://developer.android.com/media/grow/media-projection)
   requires per-session consent and one virtual-display creation for a token. API34+
   rejects reuse of a consent Intent or another virtual display on the same projection.
   Register callbacks before creating the display. Default-display requests do not
   establish selected application identity.
2. [MediaProjection.Callback](https://developer.android.com/reference/android/media/projection/MediaProjection.Callback)
   defines API34 resize and visibility observations at initial capture and subsequent
   changes. Visibility false denotes entirely hidden captured content. Matching width
   and height are dimension evidence only.
3. [SurfaceTexture](https://developer.android.com/reference/android/graphics/SurfaceTexture)
   requires updateTexImage on the owning current GL context; it latches the most recent
   available image and its transform. Timestamps have source-dependent meaning/zero
   points, so matching the monotonic host clock is a fail-closed compatibility condition,
   not a universal API guarantee or evidence of visible application identity.
4. [WebView.postVisualStateCallback](https://developer.android.com/reference/android/webkit/WebView#postVisualStateCallback(long,%20android.webkit.WebView.VisualStateCallback))
   supplies DOM readiness for a following draw and excludes the video-tag state. It
   does not prove a video frame is playing or compositor visibility.
5. [ViewTreeObserver.registerFrameCommitCallback](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable))
   reports rendering/submission to the swap chain only on hardware rendering. A submitted
   frame may not yet be visible. Combined host and fresh captured-frame checks therefore
   still require device acceptance, with no arbitrary-other-app visibility promise.
6. [FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)
   protects a window against screenshots/non-secure displays. Source review must establish
   its installation before showing the preview and lifetime through selector disposal;
   actual OEM enforcement remains untested.
7. [OpenGL ES 2.0 specification, §4.3.1](https://registry.khronos.org/OpenGL/specs/es/2.0/es_full_spec_2.0.pdf)
   defines RGBA/unsigned-byte readback and a lower-left framebuffer origin. Display-oriented
   bitmap conversion needs row reversal and explicit component interpretation, avoiding
   native-endian packed-color assumptions. Shader sampling and crop edges must match the
   actual recording route.

Local pinned API36 stub signatures were inspected using the existing JDK launcher, with
no Gradle invocation. Evidence: `evidence/independent-sdk36-signatures.txt`. Android jar
SHA-256: `d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a`.
This proves API availability in the pinned compile SDK only.

## Findings raised during implementation

### R1 — High: repeated preview-target cleanup could erase native release uncertainty

The first implementation destroyed the readback EGL surface before returning, then
retried that destruction in `finally`. A failed first destroy followed by a successful
retry could make final `release()` appear confirmed. The owner added a sticky
`releaseUncertain` flag and `glFinish`/GL error validation before preview-target
teardown. Later cleanup may release resources, but cannot change the enclosing native
lease from an uncertain outcome to reusable admission. Correction confirmed in the final frozen manifest.

### R2 — High: native uncertainty could hide all recovery controls

`previewActive` originally stayed true until the first recorded frame or final owner
release. Cancellation or uncertain GL closure removed the image but could leave the
user permanently on the restricted browser branch, where task/navigation actions are
no-ops. Back changed a lower-priority route that this branch still obscured. The
runtime now separately retires preview routing and raises a one-shot return-to-controls
signal on invalidation, denial, preparation watchdog and native-finally paths. This
exposes cancellation/recovery without claiming native closure or releasing ownership.
Correction confirmed in the final frozen manifest.

### R3 — Medium: stale host callback could cancel a newer valid readiness stage

The first runtime invalidated the current session when a callback's request generation
or stage differed from the current request. That stale callback should be inert. The
corrected runtime rejects request/session/stage/generation mismatch before evaluating
current authority. A pure renderer callback fence limits pending/delivered callbacks,
allows focus-loss retry and invalidates disposed callbacks. Correction confirmed in the
final frozen manifest. Nine renderer/request/focus-policy tests are included in the passing final host run.

### R4 — Medium: bounded allocations still need an explicit low-memory failure path

ByteBuffer/ARGB-array/Bitmap allocations can fail despite the size cap. The native
readback and transfer scope now map `OutOfMemoryError` to a generic capture failure and
run cleanup, without broadly suppressing unrelated VM errors. Staging arrays/buffers
are cleared on the worker. Once publication might have occurred, the bitmap is never
recycled: reader/reference retirement and garbage collection own reclamation. The
pixel lease now has two explicit reader slots for disposal/recomposition overlap.
Correction confirmed in the final frozen manifest.

### R5 — Boundary hardening: strictly post-readiness source timestamps

A source timestamp equal to a readiness edge after microsecond truncation could be
slightly older than the actual host callback. The core gate now requires a strictly
later source microsecond. Native preview and post-retirement source-observation cutoffs
now start at the next microsecond, matching the stricter boundary. This is an exact
ordering safeguard, not evidence of a general SurfaceTexture-to-monotonic-clock guarantee.

### R6 — Medium: browser Back handling could shadow pending-preview cancellation

The preview BackHandler was composed before BrowserScreen, whose later handler wins
when browser history, fullscreen or settings can consume Back. Before the secure
selector appeared, Back could navigate browser history rather than cancel the pending
preview. The corrected MainActivity registers preview cancellation after the browser branch
and before the authorized dialog, retaining that dialog's own dismissal handling.
Correction confirmed in the final frozen manifest.

### R7 — Medium: readiness must remain focused, not merely resumed

After a host-readiness callback, runtime admission initially used the Activity's resumed
state without continuing to track browser focus. API29+ multi-resume permits multiple
resumed activities; a notification shade can also remove focus without changing the
Activity's top-resumed status. Therefore a ready edge alone must not remain valid after
the relevant window loses focus. Source publication and post-retirement admission need
current browser-host focus; the known secure selector must check its own window focus
and not mistake the expected Activity-to-dialog focus transfer for backgrounding.
The runtime now requires and continuously tracks host focus during first-frame and
post-retirement admission, while exempting the known secure-selector transition. The
selector separately requires its own attached, resumed, focused, secure window before
acquiring/drawing/confirming pixels. Losing that focus after first acquisition masks and
cancels even without lifecycle pause. Its initial no-pixel acquisition wait has a
coroutine timeout, so an absent frame tick cannot extend the five-second budget.
Dedicated pure runtime/UI focus tests were added. Correction confirmed in the final frozen manifest.

The independent API reference for this finding is
[Android 10 multi-resume behavior](https://developer.android.com/about/versions/10/behavior-changes-10#multi-resume),
opened 2026-10-06. It distinguishes lifecycle resume, top-resumed position and actual
window focus; none of those alone proves the captured application's identity.

### R8 — High: projection stop could precede queued preview publication

The final joint check identified that native `stop` initially changed its atomic stop
reason immediately, but did not invalidate the preview gate until worker unwinding.
A main-thread publication runnable already queued by `offer` could therefore display
the now-invalid preview before the worker observed system stop/resize/visibility loss.
Recording could not begin, but publication authority needed the same synchronous
revocation boundary. Native stop/cancel must synchronously invalidate the pending
preview metadata gate and revoke new pixel readers, without performing native cleanup
or releasing global/native ownership. The existing worker still owns actual closure.
The correction is implemented through `RegionPreviewStopSignal`, used by native
stop/cancel; five deterministic host tests cover queued publication, queued UI access,
concurrent/reentrant signals and reason mapping. Corrected source is confirmed in the
final manifest. The earlier 430-entry/216-test evidence is superseded.

### R9 — Medium: source-frame handoff deadline must be checked after native wait

The native fresh-frame loop checked snapshot and capture-ready deadlines before
`observeFrame`. That GL operation can block, and the core gate is already ADMITTED,
so the following environment check alone no longer enforces those deadlines. The
source-frame handoff needs a new monotonic observation after successful GL return,
before descriptor adoption. This preserves the intended expiry boundary without
expiring an active recording or consuming the separate encoder-setup budget.
`RegionPreviewHandoffPolicy` now runs before and after the native observation; three
host tests cover deadline crossing, expiry after admission and an invalid early clock.
Readback also revalidates source timestamp after potentially blocking GL read/close.
Corrected source is confirmed in the final manifest.

## Memory and pixel interpretation

At 921,600 pixels, each four-byte raster has a maximum payload of 3,686,400 bytes.
Readback can temporarily hold a direct RGBA buffer, ARGB IntArray and immutable Bitmap,
for 11,059,200 payload bytes before object overhead, plus approximately one preview
pbuffer of 3,686,400 color bytes and the separately bounded full source/platform/GPU
buffers. `MAX_RASTER_BYTES` describes one raster, not total process/GPU allocation.
One snapshot is read back per native session. No physical-erasure guarantee is made.

The original full-frame OES shader renders into the preview pbuffer. Byte-wise RGBA
conversion reverses rows into display-oriented ARGB, independent of native endianness.
The selector maps all raster edges to the original full display extent; any sub-pixel
rounding is confined to preview scaling. Source inset metadata does not shift the crop
origin. Encoding uses the same producer transform and exact inward-even source crop.

## Expiry handoff interpretation

The snapshot lifetime limits selection and the one-shot handoff. `takeReady` can only
admit an unexpired exact snapshot after confirmation, UI retirement and new host
readiness. Native code then still enforces expiry while awaiting a fresh source frame
with the same session/geometry/transform binding. Only after that source frame is
accepted does the separate 15-second encoder-setup budget apply. This is a bounded
transfer to confirmed recording authority, not continued authority to display/replace
the frozen image. The snapshot is not a live-content guarantee; normal video motion
changes later frames without changing the selected geometry. Active recording must
not expire merely because the earlier selection image reached its former deadline.
Cancellation, focus before first recording, lock and source-change checks remain
independent. A setup-deadline checkpoint is present immediately before opening output resources
after the potentially slow journal call. The post-grant host/frame wait is 15 seconds;
the earlier reserved host/permission/system-consent request has its separate 120-second
timeout.

## Independent host checks and final source evidence

Final evidence under `docs/m6c/evidence/`:

- `independent-host-results.log`: **224/224 passed**, compiled directly with the existing
  cached Kotlin/JUnit artifacts, without Gradle. Covers all recording-module tests,
  native pure policies, UI presentation/focus policies, renderer request fences and
  seven independently written adversarial review tests.
- `independent-host-source-sha256.txt` and `independent-host-source-unchanged.log`:
  all **62** compiled source/test inputs unchanged during that run.
- `independent-runtime-regression-results.log`: **53/53 passed**, independently rerunning
  the existing M6-B runtime/publication workflow tests on current sources.
- `independent-runtime-regression-source-sha256.txt` and
  `independent-runtime-regression-source-unchanged.log`: all **41** inputs unchanged.
- `independent-manifest-verification.json`: **434/434** source/test/build-contract entries
  match the final manifest above, including the reviewed port-threading clarification.
- `independent-regression-boundaries.json`: source-delta check against M6-B confirms no
  changes in admitted dependencies/notices, manifest, entitlement grants, Vault paths,
  editor publication/storage, recorded-segment storage, consent controller or grant handoff.
  This is an unchanged-source observation, not behavior/device acceptance.
- `IndependentPreviewAdversarialTest.kt`: independent byte-order/row-origin/sliced-buffer,
  dimension/aspect bounds, exact metadata substitution, terminal invalidation, concurrent
  confirmation, expired handoff and pixel-reader-lifetime checks. Synthetic CPU policy
  tests do not execute GL or infer physical pixel accuracy.
- `run-independent-host.sh`: reproducible focused runner. Pass an absolute output
  directory; optional `ESV_REVIEW_REPO`, `ESV_REVIEW_CACHE`, `ESV_REVIEW_JAVA` select
  the checkout and already-present approved compiler cache/JDK. It installs nothing.

Earlier moving-tree and 430-entry results are not substituted for these final checks.
The separate build owner ran compile/lint/aggregate checks; their result is not asserted
by this independent source report. No Gradle invocation or Android/device operation was
performed by this reviewer.

## Frozen-source anchors

All paths below are relative to the checkout and refer to the final manifest above.

- Immutable snapshot/source binding and one-shot state machine:
  `recording/src/main/kotlin/com/easysavevideo/recording/RegionPreviewContracts.kt:33-247`.
  Dynamic later frames do not replace the frozen confirmation token; source geometry,
  provenance, session/generation and transform changes fail matching.
- Runtime focus, exact request identity, confirmation and retirement:
  `app/src/main/kotlin/com/easysavevideo/app/recording/AndroidSegmentRecordingRuntime.kt:127-239,377-437`.
- Renderer submission/request fence:
  `app/src/main/kotlin/com/easysavevideo/app/recording/RecordingPreviewHostDraw.kt:13-59`
  and `RecordingPreviewHostRequest.kt:6-37`. `MainActivity.kt:137-159,350-365,412-419`
  ties it to browser routing, Back priority, dialog callbacks and actual Activity focus.
- Secure owned-window acquisition, loss masking, expiry and disposal:
  `app/src/main/kotlin/com/easysavevideo/app/recording/ui/AuthorizedRecordingRegionDialog.kt:73-145`;
  exact confirmation and aspect-fit bitmap/selection rendering: `:196-285`.
- Memory leases and bounded byte-wise top-down conversion:
  `app/src/main/kotlin/com/easysavevideo/app/recording/nativeregion/NativeRegionPreviewPort.kt:47-61`,
  `RegionPreviewPixelLease.kt`, and `RegionPreviewRasterPolicy.kt`.
- Shared original OES shader, one readback, freshness, worker-only raster allocation,
  staging clearing and sticky EGL close uncertainty:
  `app/src/main/kotlin/com/easysavevideo/app/recording/nativeregion/RegionGlCapture.kt:137-293`.
- Synchronous pending preview revocation:
  `app/src/main/kotlin/com/easysavevideo/app/recording/AndroidRecordingSession.kt:192-218`
  and `nativeregion/RegionPreviewStopSignal.kt`.
- Encoder-free source setup, fresh-frame handoff, deferred resource start, sole VD,
  source lifecycle checks and ongoing source binding:
  `app/src/main/kotlin/com/easysavevideo/app/recording/AndroidRecordingSession.kt:287-463,631-647`
  and `nativeregion/RegionPreviewHandoffPolicy.kt`.
- Native release and existing output fences remain in
  `app/src/main/kotlin/com/easysavevideo/app/recording/AndroidRecordingSession.kt:943-999`;
  runtime `finishOwner` still retains ordinary-media ownership on uncertainty.

The manifest establishes checkpoint identity, not semantic review of every unrelated
file. Any change to a reviewed production/test anchor requires a refreshed manifest
and affected source/test checks.

## Device gates still NOT_RUN

API31–36 phone/tablet/OEM consent and callback ordering; genuine browser/selected-source
visibility; static versus live-video producer timing; actual draw/compositor ordering;
physical pixel location, channel/color/orientation and producer transforms; EGL/codec
allocation and real uncertain release; screenshot/recents/secure-window enforcement;
lock/multi-window/focus changes; audio synchronization; actual touch/accessibility and
memory/thermal behavior. Host tests cannot establish these properties. No protected
content bypass, extra grant/display, recording-before-confirm, fake preview, full-screen
file fallback or relaxed publication verification is admitted by this review.
