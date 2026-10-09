# M6-B region recording and recorded-segment development checkpoint

## Status and scope

This checkpoint implements a deliberately bounded **ratio-diagram region selector**, genuine pre-encoder crop code, recoverable ordered private recording segments, and their typed connection to the existing M6-A editor/verifier/publication path. It is not a screenshot/live-content visual selector, complete M6 acceptance, or Android release acceptance. The exact ratio-coordinate/scope boundary is frozen in `SCOPE_AND_SEQUENCE.md`. Actual-content preview framing remains separate future work.

The user authorized advancing M6 while Vault stays disabled. No isolated Vault source, blocked independent Vault review, new dependency, copied external implementation, APK/AAB, device operation, actual recording/permission prompt, account, payment or public publishing is included. API31 minimum, phone/tablet layouts, existing SkinKit, admitted notices/artifacts and entitlements remain.

## Implemented path

1. User chooses audio and a proportion rectangle in a clearly labelled diagram. Whole captured-frame coordinates, current display geometry, rotation, insets, transform version and inward-even execution pixels are explicit. The user confirms that coordinates apply to the whole image subsequently authorized by the system, not a previous app, webpage or screenshot.
2. One per-session ephemeral consent handoff, foreground service, projection and VirtualDisplay own capture. API31/32/33 do not wait for the API34 size callback; their actual default-display/latched-transform branch is separate. API34+ requires an actual matching capture-size callback. Geometry is not content-identity authentication.
3. Original EGL/SurfaceTexture code draws selected coordinates to an exact-size encoder surface before encoding. There is no full-screen temporary-file fallback, implicit scaling or stale-geometry adaptation. Unsupported producer transforms/codec dimensions stop safely. Full authorized content may transiently exist in GPU memory.
4. Pause closes the current independent MP4 and all its native/file writers before resume opens the next. A single projection/VirtualDisplay remains. Actual encoded video PTS and measured following-sample endpoints define timing; AAC output is bounded to measured video endpoints. A rate-admission request never creates CFR evidence. Geometry/system stop preserves only honestly closed portions.
5. Private random segment identities and journals retain exact byte extent/digest, file identity, ordered effective time, configuration, actual geometry and timing provenance. No raw segment becomes a catalog item, Save receipt, URL, preview/thumbnail/share/export source, or successful merged result.
6. After overall capture closure, every raw independently passes the existing full verification profile before receiving a recorded input capability. The real editor then probes observed PTS, plans an ordered result, runs Media3 and independently verifies the new output. Strict integer-CFR/AVC/SDR/AAC constraints are not relaxed; VFR or incompatible raws remain private/recoverable with an unsupported result.
7. One final catalog item, representation, edit success and recording-session result marker commit atomically. Ordinary and recorded editing share a bounded cancel/publication admission token immediately before final visibility, after long IO. Before admission, cancellation excludes publication; after admission, cancellation is explicitly too late and the catalog decides the actual result. Uncertainty never licenses duplicate publication or a false discarded/success label.
8. Raw private segments remain after success or post-processing failure until explicit discard. Cleanup has exact ownership and native-release fences, bounded waiting notices and truthful removed/pending row status. It preserves a published final result. Deletion is not secure physical erasure. Retained groups are individually reachable without restarting; process recovery cannot reuse projection authority.

## Limits and compatibility

- Ordinary editor: existing eight-source limit and codec/profile subset retained.
- Recorded editing: only unique, contiguous, same-session/generation recorded refs permit initial plus 32 actual resumes. No mixed catalog/raw recipe.
- Advanced recording:30min effective media,2h wall resource limit,10min maximum single pause,2GiB group input,2GiB output and108,000-frame group bound. These are engineering limits, not proven device capacities.
- Exact region: at least128×128 even pixels and the editor's1920-edge/1920×1080-pixel output ceiling. No implicit downscaling if unsupported.
- AAC allowance stays at most two 1024-sample packets per segment; recorded count is explicit in expectation codec v3. Ordinary expectations cannot use the 33-segment allowance. Interior audio continuity/video phase tolerances remain strict.
- Database v5 adds owned segment journals and typed edit sources with checked v4 migration. Save task codec v6 retains actual v1–v5 compatibility; genuine frozen v5 binary records are included. Genuine historical edit codecs v1/v2 were executed unchanged to generate regression fixtures.
- Region start: existing RECORD_SAVE+REGION_RECORDING. Pause/resume: existing RECORD_SAVE+SEGMENT_RECORDING. Safe stop/discard and finalization/retry of already-owned segments stay free. Later manual edit capabilities retain their separate gates. Release fake grants remain absent.

## Verification record

Final frozen-source host build completed on 2026-10-06 at 23:23 UTC; cold restoration is reported separately by the verified external restore receipt. Independent reviews are `INDEPENDENT_SOURCE_REVIEW.md` and `RUNTIME_LIFECYCLE_REVIEW.md`; both verify the415-entry source manifest `073eff4a2da2ec532380cf545061cda6ff7207465be456f0bb322199b4a97f73`. Neither found a remaining blocking source defect in its scoped final review. The source/runtime review independently ran47 host SQL and53 focused JVM contracts respectively.

Host evidence includes positive/negative synthetic crop-grid mapping (including a full-frame-downscale counterexample), source-admission branches31/32/33 and34+, frame selection/cadence/tails,33-input exact planning, AAC tails, descriptor/codec migration, file identity/mutation/symlink/close failures, cancellation/admission races in both ordinary/recorded workflows, queued work, late callbacks, cleanup warnings, native owner retention, service admission and recovery presentations. Synthetic fixtures are not playable media or device proof.

Earlier failed WIP/rerun logs are retained. They include the added consent-correlation boolean assertion, a non-null generic constraint, and two mixed-source/stale presentation-test passes during active review corrections. Later success does not rewrite those failures. The final frozen-manifest rerun is the acceptance build, not a previously passing intermediate snapshot.

## Required separate device acceptance

Every actual Android projection/permission/FGS test; GPU/SurfaceTexture/driver transform and physical pixel crop; codec/MediaMuxer/AAC priming/AV-sync; real static/VFR content; locks/rotations/OEM behavior; phone/tablet touch/accessibility; long-duration/low-storage performance; APK/AAB and distribution: **NOT_RUN**. See `DEVICE_ACCEPTANCE_CHECKLIST.md` for the explicit API31–36 matrix and fault cases. Full M6 remains incomplete, including actual-content visual framing and deferred native/device acceptance.

## Final frozen-source host result

- Strict offline dependency verification, debug/release Kotlin compilation and both lint variants: **PASS**, all 89 requested tasks executed; `evidence/strict-offline-final-r2.log`.
- All normal-module JVM suites: **1,238 tests passed**, zero failures/errors/skips; per-module counts in `evidence/final-junit-summary.json`.
- Lint: zero errors; 14 warnings and 6 hints per variant. Existing SDK/deprecation/style/retained-context warnings remain. New conservative usable-space and state-boxing notices are disclosed, not suppressed. This is not warning-free acceptance.
- Exact runtime admission: **74 approved artifacts in both variants**, all licenses/notices and dependency inputs unchanged. Debug-only fake grants and provider controls remain absent from release bytecode.
- Seven static admission guards: **PASS**. These are source/bytecode/license checks, not native behavior proof.
- SQLite: existing ordinary-library 14, export 14, editor 37 and new recorded-segment 10 tests pass; discovery SQL regression and bounded DOM detector 11 checks pass.
- Pinned HLS EOF host 81 and mux-conservation 15 tests pass; actual pinned reader and editor-muxer cancellation/finalization harnesses pass with synthetic content.
- Independent native/storage source review: all 415 manifest entries verified, 47 host SQL checks pass. Independent runtime/UI/publication review: 53 focused host tests pass and the same 415 hashes match.
- Protected dependency/license/manifest/entitlement inputs are byte-identical to M6-A; no APK or AAB is present or claimed.

The complete Git bundle and tracked-source archive are restored into a separate clean directory. Their exact bytes, HEAD/tag, tracked-file comparisons, repeated strict aggregate and host regression outcomes belong to the accompanying `RESTORE_RECEIPT.json` and cold-verification archive. The development tag alone is not an Android/device acceptance result.
