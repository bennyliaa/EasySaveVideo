# M6-A independent ordinary-editor source review

Status: COMPLETE for the ordinary-editor source/host development checkpoint. Baseline `7d161d54d6616bd4b20feed5a5830c63cef68b8e`; initial integrated WIP `a4fe4ba8416f8f8f39019dd7d996de032686f5b4`; final reviewed production/test candidate `a9917728fc8a4b2980bc2972693876a56e77a43a`.

No unresolved blocking source defect was found after the corrective review, including the final narrow P2 liveness correction. This is not Android/native/GPU/device acceptance, full M6 completion, or release approval. Vault remains disabled; M6-B remains open.

## Scope and limits

Reviewed AGENTS.md, the governing `03_Development_Milestones_update1.md` M6 order, PR-14/15, and TR-09; the platform-neutral edit domain; ordinary-library current-revision capabilities; the v3-to-v4 typed edit publication migration; editor runtime/workflow; Media3 input, clip, crop, merge, encode and resource ownership; independent MP4/demux/decode policy; SkinKit controls, entitlement routing, cancellation, recovery, and logging.

The review is restricted to conventional editing of ordinary library media. Vault remains disabled and is outside this review. No Vault source was inspected or integrated. No Gradle, APK/AAB assembly, device operation, real media download, new dependency, account action, network research, publishing, or deployment was performed by this reviewer.

Precise Media3 behavior was checked against the already admitted 1.11.1 source jars under `third_party/candidates/media3-1.11.1/sources`. Host checks below are synthetic policy/source evidence. They are not evidence of native codec operation, Android storage behavior, playable output, crop pixels, GL release, audio/video synchronization, or OEM compatibility.

## Findings reported immediately

### R1 — P1: trim/merge timing admission conflicts with verification

Initial affected source:

- `app/src/main/kotlin/com/easysavevideo/app/editor/engine/Media3EditorEngine.kt`, clip construction around lines 176–184
- `app/src/main/kotlin/com/easysavevideo/app/editor/verification/EditVerificationPolicy.kt`, `EditSampleLedger.sample` and `finish`
- `app/src/main/kotlin/com/easysavevideo/app/editor/verification/EditMp4Probe.kt`, STTS validation around lines 223–225

The planner and UI accept arbitrary microsecond trim boundaries with one-frame-per-segment tolerance. The engine supplies those boundaries unchanged to Media3. The independent verifier requires every interior video sample delta to be CFR within 1,000 microseconds and first video PTS within 1,000 microseconds of zero.

Pinned `ExoAssetLoaderVideoRenderer.java:191–217` subtracts the requested start timestamp from decoded source PTS; it drops earlier frames rather than rebasing remaining frame times onto a new CFR clock. `SequenceAssetLoader` advances the sequence offset by the requested clipped duration. Thus two 250,000-us CFR30 clips join with a roughly 16,667-us delta. This is a legitimate one-frame boundary quantization under the advertised contract, but is rejected by both the sample ledger and MP4 STTS check.

Pinned `Boxes.edts:875–878` subtracts the minimum positive first PTS across tracks. Consequently a single silent clip can be normalized to zero; the nonzero-first-video failure applies to an A/V clip when audio begins before its first kept video frame. An A/V trim at 10,000 us can keep video beginning at about 23,333 us while AAC begins at zero. The first-video check rejects it.

Reproduction: the independent host runner at `/workspace/scratch/df6c1284d5ab/esv-m6a-review/run-timeline-repro.sh` compiles copied WIP production verifier policy and executes those two synthetic sample histories. Both produced `VERIFICATION_FAILED`; console evidence is `timeline-repro.log` beside the runner. This tests the policy contradiction, not a real encode.

Fix criteria: explicitly quantize/rebase the admitted timeline, or verify exact source/segment boundary phases within the declared frame allowance. Do not globally weaken CFR checks or permit unexplained missing interior samples. Show nonframe start/end, repeated-source merge, differing segment phases, and AAC offset cases in regression tests. Preserve the user's requested interval in provenance and disclose any snapping.

Status: corrected source reviewed. The planner now retains exact bounded source-frame timelines, snaps both requested edges inward to observed boundaries, records distinct requested/execution ranges and exact frame count, and bounds accumulated join phase drift. Media3 receives those execution ranges without the lossy per-item maximum-frame-rate filter. Independent current-production host checks passed the original counterexamples plus 600 deterministic cases over 3/5/24/25/30/60 fps, floor/ceil source clocks, arbitrary requested boundaries, 1–8 segments, and simulated 90 kHz MP4 rounding. The exact-count sample ledger and publication-evidence fence accepted those modeled planned outputs. The same independent check was rerun successfully against the frozen candidate. Native execution remains NOT_RUN.

### R2 — P1: new reverse lock order can deadlock Save refresh with editor preparation

Initial affected source:

- `app/src/main/kotlin/com/easysavevideo/app/editor/storage/AndroidEditStorage.kt:49–50,284–287`
- `app/src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt:51,219–224,328–336` in WIP

`AndroidEditStorage.prepare` enters the shared file lock, then invokes `retainSource`/`openSource` callbacks bound to `@Synchronized` AndroidSaveStorage methods. This creates file-lock → storage-instance-monitor ordering. Existing `@Synchronized` read/list/close methods use instance-monitor → file-lock ordering. The Save refresh worker remains active while editing, so two ordinary workers can wait permanently on each other's JVM monitors. Native cancellation deadlines cannot release those locks.

Fix criteria: use the same monitor or a single consistent lock order across every ordinary Save/Library/Edit access. Include concurrent edit preparation and Save refresh/close coverage.

Status: source correction reviewed. All redundant AndroidSaveStorage `@Synchronized` annotations were removed; all twelve affected public wrappers still enter `dbCall`/`fileCall` (the same file lock), and close explicitly enters that file lock. The introduced inverse lock order is removed without dropping ordinary shared-state serialization. The correction is confirmed in the frozen candidate.

### R3 — P1: ordinary cancellation is mistaken for unconfirmed native release during muxer close

Initial affected source:

- `app/src/main/kotlin/com/easysavevideo/app/editor/engine/EditorBoundedMuxer.kt:89–92,107–110`
- `app/src/main/kotlin/com/easysavevideo/app/editor/engine/EditorSession.kt:55–57`

The output's write method checks the active cancellation token even while `Mp4Muxer.close` flushes final sample data and MP4 tables. Once the user cancels, these close-time writes throw `CANCELLED`. The wrapper catches that expected content-finalization failure as `closeFailed=true`, although its `finally` closes the actual output stream. Every actual codec/loader/GL/descriptor could release successfully and the application would still force a process restart and retain cleanup work.

Pinned `MuxerWrapper.java:678–680` calls `muxer.close()` for cancellation too. Pinned Mp4Muxer performs final MP4 writing before closing its output. Reproduction condition: begin a valid export, allow at least one muxed sample, then cancel before normal completion.

Fix criteria: separate bounded close/finalization writes from active-operation cancellation, keep hard byte/range/zero-progress bounds and the outer actual-close deadline, and distinguish invalid output content from unconfirmed resource destruction. Cancelled output must never publish. A confirmed close should permit owned-output cleanup and a later new edit without requiring restart.

Status: corrected source reviewed. `EditorOutputIoPolicy` distinguishes active writes from bounded finalization. Finalization retains size/range/zero-progress guards while actual stream/FD/channel close is tracked separately from invalid content. The engine worker also exercised the admitted 1.11.1 Mp4Muxer after writing three synthetic AAC samples, then cancelled, finalized MP4 tables, and confirmed FD closure in a host harness. Reviewer inspected the harness and log; it uses production I/O policy plus a host output adapter, not the complete Android engine. The correction is confirmed in the frozen candidate.

### R4 — P2: stalled storage can leave cancellation status without a bounded response

Parent self-review identified that native stages have watchdogs, while owned storage/hash/fsync calls outside those stages can remain blocked. `AndroidEditRuntime.cancel()` initially only cancels the token and enters CANCELLING. If the worker cannot reach another checkpoint, that phase can remain indefinitely; an overall context deadline alone is also cooperative. Ownership and files remain retained correctly, so this is a response/liveness issue rather than premature release.

Fix criteria: a main-handler operation-scoped overall deadline cancels the same token, and after a separate 10-second cancellation window without actual worker completion the UI reports WAITING_NATIVE_RELEASE with retained-files/process-recovery guidance. It must never infer closure, free capacity, delete files, or replace the worker. Timers must bind to the captured operation identity and cannot affect a later task. Repeated cancel requests must not defer the original warning deadline. Only actual worker completion can clear the active ownership state.

Status: corrected and reviewed in the final frozen candidate. `EditorDeadlineGuard` owns at most two main-handler timer tickets bound to the unique cancellation-token identity. It adds an overall cancellation deadline and a nonrenewable 10-second stop notice. Stale callbacks and old completion calls cannot affect a successor. WAITING_NATIVE_RELEASE stays busy; repeated cancellation preserves the warning. Actual completion or pre-work queue rejection removes tickets. No callback releases resources or changes files. All five focused guard tests passed in the parent-owned final build.

## Follow-through corrections and remaining verification limits

- Pre-allocation codec rejection (P2, corrected source reviewed): the original wrapper marked every creation exception as unknown release. The correction rolls back the attempted count only for its own pre-delegate policy failures or the pinned unsupported-format exception carrying a non-null codec-info object with a null codec name. Named or unknown creation failures remain strongly retained and sticky. Pinned DefaultEncoderFactory resolves/rejects unsupported formats before constructing DefaultCodec; no exception message text is parsed.
- Verification cancellation (P2, corrected source reviewed): the original caller abandoned the verifier and returned immediately, allowing a brief DRAINING phase to permanently fence the outer owner. The correction adds an explicit actual-worker cleanup acknowledgment and a separate bounded 10-second wait. Successful cooperative cleanup remains ordinary cancellation; missing/failed acknowledgment requires recovery. Interrupted waits preserve the caller interrupt flag without treating interruption as proof of close.
- Exact source pinning, post-transform source hashes, output byte limits, strong native owner retention, and transactional publication are source-level protections. Hardware/GL/native release claims still require separately authorized tests.
- Compile/lint/JVM/SQLite/admission and restoration execution belongs to the parent build owner. The reviewer inspected the frozen candidate's records as summarized below. The independent host repro is not a substitute for those aggregate checks or cold restoration.

## Source protections examined without new unresolved defects

- Input authority comes only from exact current ordinary-library identity/revision/representation capabilities. Generated internal Media3 addresses are indices into those refs; no network, caller path or URL fallback exists. Source leases survive native reads, and catalog-derived extent/hash are rechecked.
- The editor has an independent typed request/evidence/journal path. It does not manufacture Save/HLS/recording receipts. Publication revalidates immutable request/source snapshots, exact output evidence, no-current-writer/readers, and owned names, then commits item/representation/job success atomically. Restart never auto-publishes unfinished edits.
- Source mutation/deletion leases, exact cleanup ownership and collision preservation were traced. Migration uses the helper's version transaction, full FK graph checks before commit and FK enforcement verification on open. The reviewer independently ran the then-current 31-test host SQLite contract suite successfully. Android SQLiteOpenHelper and filesystem durability remain unrun.
- Core edit code has no Android/provider/store imports. UI uses the existing SkinKit layer and independent existing feature identifiers. Original viewing and safe cancellation do not require paid grants; source probing does not execute an edit. No-op single-source original-quality requests now avoid unnecessary encoding.
- Crop coordinates are display-oriented with fit-center viewport exclusion, inverse rotation mathematics, inward even-pixel alignment, and explicit schematic labeling. The UI does not claim it is showing an unimplemented processed preview. Pre-record region selection and recording orchestration remain M6-B, not delivered by this post-editor.
- Queues, source/sample counts, pixels, durations, output bytes and executor ownership have explicit bounds. The review traced strong resource retention and the separation between completion/cancellation and actual close; native/GPU correctness is not established by these source observations.
- Error presentation uses technical enums and safe fixed user messages. New source does not log filenames, media titles, URLs, cookies/tokens or exception messages.

## Independent host evidence

- `run-timeline-repro.sh`: executes copied WIP verifier policy and demonstrates both original R1 contradictions.
- `run-planned-timeline-check.sh`: compiles current production ordinary core and verifier policies directly with already present Kotlin tooling; 600 modeled plans plus the original counterexamples pass.
- The original runners, fixtures and logs were created in the separate review directory identified above. Their relevant source/logs are now persisted in `evidence/independent-host-review.tar.gz`, indexed by `evidence/independent-review-files.sha256` and explained in `evidence/REVIEW_ARCHIVE.md`. Compiled classes and redundant full-source extractions are excluded. No Gradle command was run by this reviewer.
- The engine worker's pinned muxer cancellation host harness was inspected separately. Its Android shims/synthetic compressed bytes do not prove playable output or native release.

## Companion native review

The focused pinned-native review is recorded separately in `docs/m6a/NATIVE_SOURCE_REVIEW.md`. Its checks cover terminal GL-before-codec release ordering, hidden executor Future errors, bounded AAC priming/edit-list timestamps, the per-item frame-rate filter, minimum verified video-tail sample count, and interrupted cleanup waits. That reviewer confirmed N1–N6 corrected against the preceding production freeze and verified its 22 reviewed source hashes plus the full supplied manifest. The final replacement changes only the separate runtime deadline guard and its presentation/tests; the reviewed native engine/verification source is unchanged. This general review does not replace the detailed native report or the separately required hardware tests.

## Frozen-candidate closing check

On 2026-10-06 the reviewer confirmed:

- HEAD is `a9917728fc8a4b2980bc2972693876a56e77a43a`. All 360 entries in `evidence/frozen-production-tests.sha256` match. The manifest itself hashes to `7d45a47e8458f461d1e7ec765d3950c0ba81911eb3d0a9f26b03975ccc849003`. Subsequent review-report edits are documentation-only and are not changes to that frozen production/test set.
- The final UI/runtime delta defensively stores at most eight immutable requested-versus-executed ranges with exact frame counts, shows their comparison and total duration, and clears stale results on opening/changing/submitting a draft. The source-geometry cache is restricted to current selections. The screen discloses inward alignment and the minimum three executed frames. The corresponding defensive-copy/copy/clear test was inspected.
- The frozen planner also checks adjacent intervals and the pinned muxer's duplicated final-sample interval, with an explicit clock-conversion reserve. The independent 600-case check plus original counterexamples passes when compiled directly from the first frozen source; those production core/verifier files are byte-identical in the final replacement freeze.
- Parent-owned final strict offline debug/release compile/lint/JVM aggregate logs report success. Independently counted existing JUnit XML results total 1,124 tests, zero failures, zero errors and zero skips. Both persisted lint XML files contain zero Error/Fatal entries, 13 Warning entries and five Hint entries. These are successful lint tasks, not warning-free reports.
- The recorded final host SQLite results are library 14, export 14 and editor 37 tests. The exact runtime record is 74 external artifacts per variant; lock/verification metadata integrity checks pass. Build/settings/verification metadata, runtime admission catalog and manifest have no changes from the stated baseline. Existing Save/Record/HLS proof boundaries remain separate.
- This closing check changed only this report. It did not run Gradle, assemble an APK/AAB, test on Android, or perform network/device/media operations. Cold-restoration execution and archive availability are the parent owner's subsequent evidence gate, not a claim made by this source review.

Remaining acceptance: actual Android extraction/encoding/decoding, GPU/native scheduling and release under real failure, crop/rotation pixels, visual segment order, perceptual quality, A/V sync and OEM behavior. A structurally valid but wrong-picture output is not disproved by the independent verifier or these host tests. See `DEVICE_ACCEPTANCE_CHECKLIST.md`.
