# M6-A ordinary video tools: development checkpoint

## Status

This is the ordinary-library editing sub-checkpoint of M6, not completion of the whole milestone or release acceptance. The implementation uses real Android/Media3 paths; host tests do not execute Android codecs, GPU rendering, system media APIs, a device, or real downloaded/captured media. Device/native/visual acceptance is **NOT_RUN**.

The updated milestone order governs. API31 is the minimum. No APK/AAB, installation, account, permission prompt, payment, new runtime dependency, publishing or real recording was performed. The exact admitted 74-artifact runtime is unchanged. Vault remains disabled and outside this work.

## Implemented paths

- Select exact current ordinary-catalog items, build up to eight ordered segments, edit temporal in/out points and display-oriented crop, choose original-resolution/high-rate or space-saving output.
- Probe catalog-issued descriptors and hashes, inspect actual AVC/AAC/rotation/sample timestamps, and create an immutable bounded source-frame timeline.
- Snap each requested temporal boundary inward to an observed video-frame boundary. Store requested ranges and actual execution ranges separately. A boundary can lose at most one actual source-frame interval; no arbitrary-microsecond precision claim is made. Each executed segment must retain at least three frames.
- Media3 Transformer 1.11.1 decodes, applies display-oriented crop and a common aspect-preserving output size, and re-encodes the ordered sequence into a bounded private MP4 sink. No FFmpeg, external codec provider, arbitrary input path or network fallback is present.
- Independently verify exact closed output extent/hash, MP4 tables, sample conservation, actual complete video/AAC decoding and EOS, output geometry/rotation and explicit timing expectations. Encoder completion alone cannot publish.
- Synchronize and promote only a job-owned random candidate, then publish a new library identity plus its successful edit journal in one SQLite transaction. Originals and ordered revision/hash provenance are retained. The existing preview/export/share/delete paths operate on the new current catalog reference.
- Foreground admission and one worker/native owner; cancel and safe stop bypass paid feature checks. An operation-identity-bound UI watchdog requests cancellation at the overall deadline and shows retained-resource guidance after ten seconds without actual completion, including blocked storage calls; it never releases ownership or declares a cancelled result. Leaving the editor or backgrounding requests a safe stop. No background editor service or silent restart is introduced.

## Product and UI boundaries

The crop canvas is visibly labeled a schematic, with actual display geometry, corner/edge handles, dragging, reset/full/confirm/cancel and accessible nudges. It is not a decoded live crop preview. Original read-only playback remains available outside an active native job; successful output playback is enabled only after independent verification and catalog publication.

“Original quality” retains feasible crop resolution at a higher configured bitrate; it is not bit-identical/lossless. Actual edits transcode. A full-duration, full-frame single input with no compression change is rejected as an unnecessary no-op, with original preview available. “Save space” uses a lower-rate strategy and a maximum 1280-pixel edge; no smaller-file or quality guarantee is made. The storage preflight allowance is not displayed as a final-size estimate.

Existing independent entitlements are preserved: actual crop/trim uses VIDEO_CROP, ordering multiple clips uses VIDEO_MERGE, and requested space-saving compression uses VIDEO_COMPRESS. No new product or price decision is introduced. Existing debug-only Fake Entitlement controls remain the only development grants; Release receives no fake paid grants. Cancellation, safe stop and original viewing do not evaluate these paid gates.

## Admitted engineering subset and budgets

- Eight segments, one running edit, at most eight unfinished/cleanup journal entries.
- Each source and output at most 30 minutes and 2 GiB; unique source bytes at most 4 GiB.
- Eight-bit progressive AVC Baseline/Main/High, explicitly supported SDR, square pixels, even dimensions, at most 1920 pixels on either edge and 1920×1080 total pixels.
- Integer 1–60 fps with a bounded observed CFR lattice. General VFR and 29.97/23.976-style fractional rates are not admitted by this initial profile. Unknown profile/color evidence fails visibly.
- All-silent clips, or consistent AAC-LC mono/stereo at 32, 44.1 or 48 kHz. Mixed silent/audio or incompatible audio layouts are rejected. No microphone or audio replacement is introduced.
- Merge clips must share frame rate, audio layout and exact post-crop aspect ratio. They normalize to the smallest selected crop; unsupported profile/dimensions fail without silently enabling a fallback.
- Source frame arrays contain at most 108,000 timestamps each, copied and never exposed mutably. Decoder/source handles, workers, queues, read volume, sample/table sizes, in-flight encoder frames, native release and output writes are bounded in the adapters.
- Observed source/planned/output timelines use explicit clock rounding budgets. The planner checks every stitched frame, adjacent interval and predicted muxer tail, preserving a 24 µs margin for the pinned 90 kHz mux clock. Verification permits at most 1 ms timebase drift; it does not generally permit arbitrary merge discontinuities.
- AAC encoder delay may produce bounded negative encoded PTS. The muxer and independent edit-list/PCM checks account for explicit priming/padding; video remains nonnegative. No timestamp normalization is invented from a successful callback.
- Source preparation: 30 seconds per probe. Encoding: one hour maximum with a 60-second no-progress watchdog. Full-decode verification scales with output duration up to 30 minutes. Actual cleanup acknowledgement gets a separate 10-second caller wait. These are development budgets, not measured speed promises.

## Durable storage and recovery

The existing shared Save/catalog database advances from v3 to v4. Save journal/evidence codecs and source rows are unchanged. A distinct edit_job and ordered edit_source record carries original requests, exact source revisions/generations/hash/size, and snapped execution bounds/frame counts. Library items have exactly one origin: original Save commit or typed edit operation. No synthetic URL/Save/Record/HLS request or fabricated receipt is used.

Parent-table migration preserves old rows and references. Legacy FK enforcement is disabled only in SQLiteOpenHelper.onConfigure before the upgrade transaction; the full graph is checked before commit and enforcement is restored and verified in onOpen. Merely deferring foreign keys while rebuilding the circular parent is not used: the host review reproduced a deferred-counter commit failure despite an empty foreign_key_check. The accepted migration target is the prior v3 checkpoint; the earlier M6-A WIP was source-only, never an installed database release.

The storage owner now uses one shared monitor, eliminating the introduced instance-monitor/shared-lock inversion. Exact source capabilities and extra native leases retain inputs. Mutation/deletion cannot turn stale selection into another input. Publication rechecks current source references, source evidence and independent output evidence.

File intent precedes random same-filesystem promotion; final visibility and SUCCEEDED commit together. Name reservation, catalog ownership checks, absent staging after rename, and matching durable verification proof distinguish a promoted candidate from a colliding file. Cleanup never scans directories or unlinks unknown/colliding final bytes. Uncertain final catalog acknowledgement is reconciled, not treated as permission to delete a possibly published result.

After process death, bounded recovery never auto-resumes editing or auto-publishes an unverified candidate. It retains original media and recipes, cleans only owned unpublished candidates and reports interrupted/cleanup-pending work. Any uncertain native release retains the owner and file/native fences until actual process recovery; timeout or Future completion is never closure evidence.

## Verification and remaining gates

See evidence/verification-summary.json for final actual counts and commands, INDEPENDENT_SOURCE_REVIEW.md and NATIVE_SOURCE_REVIEW.md for review scope/fixes, OUTPUT_VERIFICATION.md for exact evidence limitations, and STORAGE_TEST_PLAN.md for host SQL modeling boundaries. Intermediate failed tests/builds are retained; no check was disabled to force a pass.

Host policies can verify stage order, reference binding, timestamp math, codec-wrapper release policy, SQL/protocol failure windows and synthetic pinned muxer behavior. They do not establish visual crop correctness, perceptual quality, actual segment picture order, codec availability, GPU/native scheduling correctness, A/V synchronization or real OEM behavior. Those remain device gates in DEVICE_ACCEPTANCE_CHECKLIST.md.

M6-B remains open: pre-record selection/capture and recording-segment orchestration. A future full-screen-then-crop flow must disclose temporary full-screen data before capture and must never publish an uncropped full-screen file as a successful selected-region result after crop failure. This checkpoint neither captures a selected region nor claims to have implemented that path.
