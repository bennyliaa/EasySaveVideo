# M6-B deferred device and native acceptance

All rows below are NOT_RUN. The user authorized source changes, host tests and build checks only, not an APK/AAB, installation, actual permission prompt, recording, device/GPU test, account or publication. Host synthetic picture mapping is not an Android driver/pixel acceptance result.

## Explicit capability table

| Capability | Implemented code/host scope | Device acceptance |
|---|---|---|
| Proportion diagram selection | Drag/body, four edges/four corners, keyboard-like incremental controls, full/reset/confirm/cancel; exact inward-even pixels | NOT_RUN phone/tablet touch/accessibility |
| Actual-content preview selection | Not implemented; no screenshot/live-preview visual framing claim | Future separate work |
| API31 Android12 | Explicit legacy default-display geometry branch; does not wait for API34 callback | NOT_RUN real projection, transformation, crop, FGS/audio |
| API32 Android12L | Legacy branch plus platform aspect-preserving projection contract | NOT_RUN letterboxing/multiwindow/foldable |
| API33 Android13 | Legacy branch, notification permission handling unchanged | NOT_RUN real permissions and notification controls |
| API34 Android14+ | Explicit default-display request, actual content-size callback gate, strict mismatch stop | NOT_RUN system scope/OEM override |
| API35/36 | Same explicit geometry/callback gate; no assumed scope identity | NOT_RUN chip/system-stop/lock behavior |
| Crop route | Whole authorized image transiently enters OES; original EGL crops before encoding; no full-screen disk fallback | NOT_RUN physical pixel/orientation/cutout/driver proof |
| Recorded segments | Initial plus at most32 actual resumed segments; independently closed private files and later independent verification | NOT_RUN encoder resets/AAC priming/gaps/native release |
| Recorded postprocessing | Typed private inputs only, measured integer CFR subset, ordered M6-A output verification/publication | NOT_RUN native codec/GL/A/V/quality |

## Geometry and privacy cases

- Phone/tablet portrait and landscape, rotations0/90/180/270, nonzero bars/cutouts/insets; diagram is current display-shaped geometry, not a screenshot.
- API31/32/33 begins without onCapturedContentResize; a real latched frame and accepted transform remain required. Missing or stale frames fail within budget.
- API34+ no callback never enables encoder frames; first callback mismatch stops; exact match permits geometry only, never claims source/application identity.
- OEM overrides default-display opt-out, including a same-sized source: UI must continue describing coordinates on the later authorized image, not an earlier app/screen. No unsupported scope identity inference.
- Positive colored-grid crop: compare exact inward-selected pixel centers and borders with independently captured expected output. Negative oracle: smaller full-screen scaling must differ and never pass as a crop.
- Unsupported producer crop/scale/rotation/perspective transform fails closed. Supported full-image axes flips preserve intended selected coordinates; no hidden extra crop or stretch.
- Requested128-pixel minimum and exact encoder alignment; unsupported dimensions produce an explicit result with no automatic resize/full-screen recording.
- Rotate, resize, unfold, change density/insets or change capture content bounds while recording and while paused: safely stop before applying a stale rectangle to another frame.
- Black/secure/DRM-protected content stays protected; no root/hook/overlay or bypass. Black/silent output is not labelled proof of DRM.
- Optional internal audio may include other allowed playback. No geometric audio isolation claim, microphone fallback or implicit silent restart.

## Lifecycle, resources and content

- One consent result consumed once and one VirtualDisplay created once per overall session, including32 resumes. Process death never persists/reuses a grant.
- Pause during preparation, after first frame, near minimum duration and under disk pressure; close actual muxer/descriptor before next output. Empty/tiny final epoch retains only honest valid prefix.
- Effective duration and displayed ordered segments exclude pause, use actual encoded timestamps; fps request does not establish CFR. Static/VFR/jitter/fractional cadence is rejected if outside current editor profile.
- Resume only on fresh actual encoded keyframe. Repeated/queued controls cannot invent a resume count. Attempt beyond32 safely stops and verifies earlier segments.
- Up to30min effective media,2h wall capture,10min single pause,2GiB total private inputs and2GiB new output; boundaries are resource budgets, not proven device capacity.
- AAC end packets cannot exceed actual measured video end except independent admitted priming tolerance; mixed audio/no-audio, format changes and gaps reject.
- User stop, notification stop, projection chip stop, lock, system revocation, Activity recreation, FGS loss, background/foreground transitions and app process death.
- Normal stop/save and cancel/discard are different. Cancel during queued start, native drain, raw hashing, probing, edit, output verification and commit race never publishes late uncommitted output.
- Stuck/thrown EGL, codec, audio, muxer, duplicated-FD, Java-FD, directory-sync or database operation retains ownership and exposes bounded waiting/recovery. No new lane replaces an uncertain old owner.
- Raw files are app-private and unpublished: no ordinary catalog item, receipt, thumbnail, preview, export or share path.
- Geometry and measured timing provenance survive journal encode/decode. Tampered identity/hash/extent, reordered/cross-session/generation inputs and corrupt trailing rows reject.
- Crash before/after allocation identity, segment closure, raw verification, output promotion, catalog CAS and session publication marker; no duplicate or false publication.
- Explicit private-original cleanup after success preserves the final catalog identity. Partial cleanup remains visible; deleting a file is not secure physical erasure.
- Browse every retained group without restarting. Retrying a group never reacquires capture consent. Basic M4 controls stay visible when a new basic recording follows an advanced one.
- Secondary crop/compress opens only exact current published reference; user originals remain intact, actual visual quality and smaller compressed size are not assumed.
