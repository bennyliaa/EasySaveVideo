# M6-C authorized-frame preview development checkpoint

## What this checkpoint adds

The code now has an actual, bounded, memory-only authorized-frame selector in addition to the existing proportional schematic. It keeps the current ESV browser visible, obtains one system consent, reads one bounded frame from the resulting OES projection stream on the native worker, and shows those pixels in a secure frozen selector. The user explicitly confirms the source-sized inward-even crop. Actual selector disposal, a separate uncovered-browser renderer submission and a newer same-source frame precede recording encoder/output/audio creation. The original projection, VirtualDisplay and OES source remain owned through M6-B segment capture.

A default-display request, callback dimensions and preview pixels do not authenticate source identity. The user can cancel wrong or black content. Arbitrary other-app/background preview and protected-content bypass are not implemented or promised. No preview bytes enter disk/cache/saved state/logging/export/sharing/catalog paths. Browser content may change after confirmation; the snapshot is a framing reference, not a continuing promise about content. Normal video frame changes do not invalidate its source/geometry binding.

## M6 update1 feature map

The current milestone definition is `docs/reference/03_Development_Milestones_update1.md`: region selection, recording segments, multiple-clip merge, secondary crop and compression. This checkpoint does not start M7 or Vault review.

- **Region selection:** M6-C actual frozen authorized-frame mode plus explicitly limited M6-B proportional mode. Both reuse physical pre-encoder cropping. Source geometry/transform change or stale confirmation fails closed. Real pixel correctness and visual usability remain NOT_RUN.
- **Recording segments:** M6-B one projection/VirtualDisplay across pause/resume; each closed MP4 has exact private ownership and timing evidence. Initial segment plus at most 32 actual resumed segments. No raw fragment is treated as a published media item.
- **Multiple-clip merge:** M6-A ordinary editor accepts up to eight ordered catalog segments; M6-B's typed same-session recorded path accepts up to 33. Independent source/output verification and atomic publication apply. Mixed raw/catalog inputs and unsupported cadence/audio/aspect combinations fail visibly.
- **Secondary crop:** M6-A non-destructive temporal and display-oriented crop remains. Its editor crop canvas is still a labelled schematic, not decoded live crop framing. Original read-only playback and verified-result playback are separate. M6-C does not silently redefine that editor UI.
- **Compression:** M6-A original-resolution/higher-rate and space-saving strategies remain. Actual edits transcode; “original quality” is not lossless/bit-identical. Space-saving is a lower-rate strategy capped at a 1280-pixel edge, not a guaranteed final size or visual-quality promise.

The admitted editor subset remains progressive SDR AVC, even dimensions, integer observed CFR, compatible AAC or all-silent inputs, longest edge 1920 and at most 1920×1080 pixels. Advanced group limits remain 30 minutes effective video, 2 hours resource wall time, 10 minutes per pause and 2 GiB. These are engineering limits, not measured device capacity. Existing fake entitlements are debug-only; no billing/SKU/dependency changes were introduced.

## Ownership, lifetime and review boundaries

See `SCOPE_AND_SEQUENCE.md` for exact freshness, state, focus, resource budgets and source restrictions. A frozen snapshot ID/sequence is distinct from changing ordinary source-frame timestamps. Session/geometry generation, actual observation and producer transform bind the selection. Subsequent compatible video frames keep it confirmable; changed session/geometry/transform and expiry revoke it.

The secure dialog validates its own flag, attachment, focus and resumed lifecycle before pixel acquisition/draw/confirm. It masks/cancels on focus loss/background and never clears another owner's window flags. It releases bounded drawing leases after disposal without manually recycling shared RenderThread pixels. Source-only native cleanup tolerates no encoder/writer/audio/journal. UI timeout revokes authority but cannot certify GL/projection cleanup. Sticky uncertain release keeps native/global fences and recovery controls reachable.

No physical RAM/GPU erasure guarantee is made. Bounded snapshot RAM includes up to three 3,686,400-byte payload buffers plus pbuffer/platform/OES storage; it is not a claim of a 3.69 MB total capture footprint. Snapshot refresh is not offered.

## Validation status

The compilable WIP is `b7a36c42ded9b48eecd8d120fb199847b87f0324`. A first strict offline full aggregate passed 1,311 JVM tests, dual Kotlin compilation, dual lint and exact74 admission. Later final callback-revocation hardening requires the separately recorded final rerun; do not treat the interim count alone as its verification.

Final counts, lint issue totals, frozen-source review and restoration are recorded below only after completion. `INDEPENDENT_SOURCE_REVIEW.md` distinguishes source defects corrected during implementation, ordinary JVM tests, pinned SDK/API inspection and actual device evidence. Earlier failed/intermediate logs remain identifiable; no failed source guard is rewritten as a behavioral pass.

## Explicitly NOT_RUN

Every real MediaProjection consent/foreground-service/permission operation; actual Android/GPU readback/physical crop/color/orientation; secure-window/screenshots/recents/OEM enforcement; static/live video timing and renderer/focus behavior; native codecs/audio capture/AAC priming/AV sync; phone/tablet touch/accessibility/performance and process interruption; APK/AAB assembly, install, accounts, user media or public publishing.

`DEVICE_ACCEPTANCE_CHECKLIST.md` lists these separate acceptance gates. Host fake ports, synthetic pixel arrays, SQL models and pinned native-facing API signatures are not GPU execution or installed-app evidence. This is an M6 development baseline, not full Android/device or release acceptance. Vault remains disabled and unchanged.

## Final main-worktree host result (2026-10-07 UTC)

- Strict offline dependency verification, debug/release Kotlin compile, both lint variants and all 89 requested tasks: PASS in `evidence/strict-offline-final.log`.
- Actual normal-module JUnit XML: **1,319 tests**, zero failures/errors/skips. The recording suite has 127 tests; the app suite has 681. These are JVM contracts/source-policy tests, not actual capture/GPU execution.
- Lint: zero errors, 14 warnings and 6 hints per variant, unchanged totals from M6-B. Existing SDK/deprecation/style/retained-context notices are not suppressed or called warning-free.
- Runtime admission: exact **74** reviewed artifacts in both variants. Dependency/license/notice/manifest/entitlement inputs remain unchanged; no new library or copied external code.
- All eight static admission/boundary guards, production-extracted discovery/ordinary-library/export/editor/recorded-segment SQLite checks and 11 DOM detector checks: PASS.
- Actual pinned Media3 host fixtures: HLS EOF 81 and mux-conservation 15 tests PASS; reader and editor muxer release/finalization harnesses PASS. Synthetic host fixtures do not establish playable media, Android/GPU or audio quality.
- Final source review covers the 434-entry manifest `690f6ded6208fc6fc123f613e2381cb46a6d0e69e03b3bd54341db213f572c8d`; see its signed-off report/evidence for independent counts and scope.
- The initial nullable-receiver compilation failure and a guard-name assertion typo are retained in clearly named earlier logs. Later successful builds do not retroactively mark those attempts as passed.

The complete Git bundle and tracked-source archive are cold-restored and reverified separately. Exact final HEAD/tag, file/mode comparison, archive hashes and repeated host results are recorded in the accompanying external `RESTORE_RECEIPT.json` and cold-verification archive. No package/device/media or Vault acceptance is implied by the development tag.

Independent final review: **224/224** preview/core/native-policy/UI/renderer tests plus **53/53** existing recording/runtime/publication regressions passed on separately hash-checked source inputs. These overlap normal regression coverage and are not added to the 1,319 count. All nine reviewed source findings were corrected; no remaining scoped source blocker was found. This source review is not installed-app approval.
