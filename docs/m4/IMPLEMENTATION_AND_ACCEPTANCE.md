# M4 Record development checkpoint

Date: 2026-10-06. Final tag: `m4-record-dev-2026-10-06`.

Status: implemented and locally checked development code. **Not device acceptance and not a release.** Base `1caa0d8d858ffbdb9144ad42f3cc266242008ce6`; early recoverable WIP `548844f6f2fad50b2116bdaa2e034c7cc9d778ee`.

## Implemented

Original pure recording controller/timebase, typed Capture requests, explicit per-session system consent, process-only one-use grant handoff, non-exported mediaProjection foreground service, platform AVC/AAC playback-only pipeline, same-session pause/resume with actual keyframe gating, distinct stop-and-save/cancel, bounded owned staging and native release fences, independent verification and existing atomic publication, SkinKit recording controls, and published-only read-only preview. No microphone fallback, automatic ①/②→③ fallback, protected-content bypass, new dependency, full Library/Vault or advanced editing.

The selected B policy scans the complete MP4 sample/timing structure and actually decodes complete first/middle/final and every effective-resume GOP. AAC has bounded readability samples. It does not claim every frame/packet is decoded. All verification stays within 60 seconds. Effective resume count is at most 32 and increments only after actual mux write; the next resume safely ends recording. The UI discloses the count/cap, approaching limit, outcome, current audio status and actual encoder canvas. The 2-hour/2-GiB ceilings are not a measured supported duration.

Task writer v5 and owned-evidence v3 read genuine older formats. Older RECORD state lacking explicit resume policy remains readable but cannot newly publish. The original HLS conservation and terminal decode contracts remain separate and unchanged.

Start keeps RECORD_SAVE; pause/resume keep the existing combined RECORD_SAVE + SEGMENT_RECORDING contract. Safe stop/cancel, finalized local recovery and existing-media preview are not paid-gated. Release still has no real store source or unlock.

## Verified locally

- 701 JVM tests, 0 failed/error/skipped: core-domain 30; recording 71; save 86; HLS 40; theme 9; discovery 94; Commons 48; app 323. core-model has no standalone tests.
- Both Debug and Release compilation pass.
- Strict Debug and Release lint pass with 0 errors/fatal findings; each reports 9 warnings and 3 hints. Existing upgrade/KTX/free-space suggestions and state-boxing hints were not hidden; no experimental detector was disabled.
- Exactly 72 admitted external runtime artifacts in each variant, byte-for-byte matching admission. No new external dependencies, no duplicate runtime classes, no Fake/debug unlock leakage into Release.
- Existing HLS host compatibility 81 tests and actual pinned MP4 mux/extract round-trip 15 tests pass. These synthetic host tests are not Android decode acceptance.
- Legacy pinned-reader probe, SQLite transaction/migration host checks, 11 synthetic browser-detector checks, source/privacy/dependency boundaries and seven SkinKit getter ABI pass.
- Independent source reviews closed clock/stop-cause races, preserved FGS ownership until actual native release, fenced both late-release orderings, scoped late cleanup to the correct session, fixed finalized-recovery scheduling, bounded immutable marker snapshots and retained cross-GOP format identity. The final review's production file hashes match this source.
- Two late tests specifically confirm legacy RECORD COMMITTING/unknown-boundary records cannot invoke capture, verification or commit to republish. They are included in the final 701 total.

Evidence: `evidence/verification-summary.json`, `strict-offline-aggregate.log`, `strict-offline-final.log`, `independent-policy-review.json`, both lint XMLs, runtime inventories, source guard logs and HLS host logs. Repository log copies normalize trailing whitespace only; original raw build logs are retained with the external recovery materials. Intermediate logs are retained as diagnostic history; failed earlier builds and the superseded full-decode experiment are not acceptance evidence.

## Explicit remaining gates

See `COVERAGE_AND_DEVICE_GATES.md` for all 53 planned scenarios and the exact resource/decoding limits. API31–36 phones/tablets, system consent, actual FGS behavior, hardware encoding, allowed/disallowed internal audio, actual pause-gap removal, 20+120+30 timing, 30-minute AV drift, rotation/resize/lock/OS stop, and native cleanup all remain **NOT_RUN**. Device verification must also determine usable duration/quality within the bounded 60-second verification window. Playback-only mediaProjection FGS permission behavior remains an OEM/platform check.

No permission dialog, actual recording, APK/AAB assembly, device install, real account, payment, distribution or deployment was performed. Source and Git archives plus a cold-restore receipt are prepared outside this repository after freezing the tag; private off-machine delivery is handled separately.
