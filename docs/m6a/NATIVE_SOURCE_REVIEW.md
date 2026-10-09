# M6-A ordinary editor: native path source review

## Scope and status

Independent, read-only production-source review of ordinary video-edit execution and output
verification, beginning at WIP commit `a4fe4ba8416f8f8f39019dd7d996de032686f5b4` on 2026-10-06.
The review also inspected the corrective working-tree changes while other implementation workers
were active. Final confirmation is bound to frozen production/test commit
`1c9dc1fcee17383e20787ddd6250ae6fc68ed44a`; see the final freeze section below.

Scope: `app/editor/engine`, `app/editor/verification`, relevant `library/edit` contracts, and the
already-admitted exact Media3 1.11.1 source JARs. No production code was changed by this reviewer.
The separately assigned integration review is in `INDEPENDENT_SOURCE_REVIEW.md`. Vault was not
reviewed or enabled. No Gradle, APK/AAB, device, emulator, account, network media, dependency or
package download was run by this reviewer.

**Final source finding status: all six findings are corrected in the frozen candidate; no unresolved
source blocker was found in the reviewed native path. This is not Android acceptance.**

## Findings and corrective review

### N1 — terminal GL teardown could race native codec release (high impact, fixed)

Initial location: `EditorNativeFactories.kt`, wrapper `VideoFrameProcessor.release()`.
With both an external executor and GL provider supplied, calling the pinned processor's `release`
only queues cleanup. The original wrapper queued its own provider cleanup, called `shutdown`, and
returned without waiting. `VideoSampleExporter.release` immediately releases its encoder, and
`TransformerInternal.endInternal` then releases asset-loader decoders. An already running GL task
could still be using codec buffers or surfaces during those releases.

Exact pinned-source evidence:

- `media3-effect`, `DefaultVideoFrameProcessor.java:440–450`: supplying executor/provider disables
  the upstream-owned executor wait and provider release.
- `media3-effect`, `VideoFrameProcessingTaskExecutor.java:229–246`: release waits for termination
  only when `shouldShutdownExecutorService` is true.
- `media3-transformer`, `VideoSampleExporter.java:170–174`: graph release precedes immediate
  encoder release.
- `media3-transformer`, `TransformerInternal.java:457–475`: explains that the video exporter may
  hold decoder buffers in a surface texture, so it must be released before asset-loader decoders.

A deterministic host test compiled the unmodified pinned task-executor source with minimal
non-native dependency stubs. It observed:

`GL task running -> processor.release returned -> encoder.release permitted -> shader/SurfaceTexture cleanup -> provider cleanup`

This proves the Java control-flow race, not an actual codec or GL failure.

Corrective code reviewed: terminal processor release waits for its actual owned executor to
terminate; the independent caller watchdog remains bounded and retains a stuck owner. A separate
release-order gate prevents actual codec releases after failed/unconfirmed terminal GL teardown.
Normal segment changes remain permitted before terminal teardown begins. Merely throwing from
processor release would not suffice because pinned Transformer continues releasing asset loaders
after an exporter exception; the codec gate addresses that path.

### N2 — ignored GL cleanup Future could conceal an Error (failure-path proof gap, fixed)

The pinned task executor catches `Exception`, not `Error`, around its task. `submit` stores an
`Error` in its Future, which upstream release ignores. The original wrapper's following FIFO
provider task could run and increment the released counter with no error-listener notification,
even though shader cleanup was interrupted.

Exact pinned-source evidence: `VideoFrameProcessingTaskExecutor.java:229–236,276–307` and
`DefaultVideoFrameProcessor.java:1265–1286`. A second host test injected an `AssertionError` into
the actual pinned release task. It confirmed no error-listener event and successful execution of
the following FIFO barrier. No native object was used.

Corrective code reviewed: the owned GL executor's `afterExecute` inspects completed Futures through
`EditorTaskFailureProbe`; any hidden failure poisons the release-order gate before the next provider
task. The silent logger separately captures upstream-caught cleanup exceptions. The wrapper checks
poison after executor termination; counts/termination alone are no longer sufficient proof.

### N3 — legitimate AAC encoder priming timestamps were rejected (high impact, fixed)

Initial location: `EditorBoundedMuxer.writeSampleData`, nonnegative timestamp check and `-1`
previous-PTS sentinel. The pinned `DefaultCodec` sets encoder delay to 1,600 samples for
`c2.android.aac.encoder`. The pinned muxer wrapper subtracts that delay from encoded audio PTS.
A zero-origin first encoded access unit at 48 kHz consequently reaches the app muxer at
`-33,333 us`; the original muxer rejected it before the independent verifier.

Exact pinned-source evidence:

- `media3-transformer`, `DefaultCodec.java:421–427`: codec-specific 1,600-sample encoder delay.
- `media3-transformer`, `MuxerWrapper.java:475,545–554,698–706`: calculate and subtract the offset.
- `media3-transformer`, `AudioSampleExporter.java:219–223`: encoder PCM timestamps start from the
  accumulated output-audio duration.
- `media3-muxer`, `Boxes.java:195–198,868–931`: negative initial PTS is represented using an edit
  list rather than treated as an invalid MP4 timestamp.

The source evidence establishes a supported timestamp path; this review did not run the named
Android codec. The factory's optional negative-edit-list-trimming capability flag does not suppress
this encoder-delay adjustment.

Corrective code reviewed: audio admits only the plan's bounded negative priming interval, video
remains nonnegative, and the first-PTS state is nullable rather than `-1`. Subsequent PTS must be
strictly increasing. Source probing likewise permits bounded negative AAC priming. The verifier
independently parses the bounded audio edit-list mapping, reconciles all demux samples and raw
sample-table counts, and checks continuous decoded PCM and its tail. Platforms hiding priming
samples still fail compatibility rather than receive fabricated decode evidence.

### N4 — item max-fps filtering contradicted admitted timestamp rounding (fixed)

Initial location: `Media3EditorEngine`, `EditedMediaItem.Builder.setFrameRate`.
The pinned `ExoAssetLoaderVideoRenderer.java:64–68,205–239` drops any decoded timestamp before its
next integer frame threshold. Source cadence admission deliberately tolerates small integer-clock
rounding. At 25 fps, `[0, 39999, 80000, ...]` is admitted, but the item-level filter drops `39999`
because it is less than `40000`, creating a verifier-rejected output gap.

Corrective code reviewed: the unnecessary item-level maximum-rate filter was removed. Explicit
encoder frame-rate configuration remains. Exact observed-frame clipping, total expected frame
count, and independent output cadence checks are preserved.

### N5 — two-frame output cannot use the pinned default tail-duration policy (fixed)

The WIP engine's evidence allowed two video samples. The pinned
`media3-muxer/Boxes.java:1434–1449` deliberately writes a zero last duration when the track has fewer
than three samples and no explicit EOS timestamp. The Transformer muxer wrapper writes ordinary
samples rather than an EOS sample (`MuxerWrapper.java:578–585`). Therefore a two-frame selection
could reach execution but always fail the positive final-duration verifier.

Current `EditPlanner` rejects snapped selections with fewer than three frames before execution;
engine evidence independently enforces at least three and the exact expected frame count. This
narrows the supported subset consistently, rather than weakening final-frame verification.

### N6 — interruption could skip the engine's cleanup budget (P2 availability, fixed)

Initial location: `Media3EditorEngine.bounded`, outer cancellation/error cleanup. An already-set
caller interrupt is detected by `session.checkpoint()` without clearing the flag. Its cleanup
`CompletableFuture.get(RELEASE_TIMEOUT)` then immediately throws `InterruptedException`; the catch
ignores that exception and tests `outcomeReleased` without giving the actual worker a chance to
close. A new interrupt during the cleanup wait has the same effect. A healthy, cooperative close
can therefore become permanent process-recovery state. The ordinary UI cancellation token does
not itself interrupt, so this is an interrupted-caller path rather than a normal-token claim.

A host Java reproduction confirmed that the interrupted get returns before an ordinary worker's
50 ms successful close. The verifier's new cleanup acknowledgement already preserves interruption
while waiting to its deadline. The corrective `EditorCleanupWait` provides the equivalent monotonic, deadline-bounded wait
and restores interruption afterwards. The engine still independently checks `outcomeReleased`
after the wait; completion or elapsed time is not treated as release proof. Four implementation
regressions cover initial/midwait interrupts, true timeout, and an already-cleared original
`InterruptedException` flag. The reviewer also directly host-checked initial interruption and
true timeout against this helper.

## Other source checks

- Input addresses remain synthetic indices into catalog-owned read references, without network/path
  fallback. Native reader/writer fences remain held until actual cleanup; timeout is not closure.
- Loader/codec/processor counters and owned executor termination are checked, with retained objects
  on uncertain release. Input/byte/sample/queue/duration limits are explicit and finite.
- The pinned decoder path applies source display rotation. Crop coordinates use display-oriented
  width/height and one top-down-to-NDC Y inversion. `Crop` precedes `Presentation`; portrait encoding
  restricts output rotation to zero. This is source-semantic agreement, not rendered-pixel proof.
- The output verifier checks MP4 extent/chunk overlap, sample counts and sizes, admitted AVC/AAC
  configuration, full sample traversal, decode PTS/EOS, decoded geometry/audio format, byte hashes,
  and actual native cleanup. It does not establish subjective quality, source-picture identity or
  correct segment order by pixel comparison. These limits are documented in `OUTPUT_VERIFICATION.md`.
- The new verifier cancellation acknowledgement waits for real worker cleanup before treating an
  ordinary cancellation as released; it does not cancel a Future and infer FD/native closure.

## Reviewer-run host evidence

Reviewer evidence is archived in `evidence/independent-host-review.tar.gz`, under `review/native/`.
The original scratch evidence is under `../esv-m6a-review/native/` relative to this checkout:

- `gl-harness/`: actual pinned task-executor source, minimal stubs and two deterministic Java
  control-flow repros. `gl-release-repro.log` and `gl-release-error-repro.log` record the observations.
- `policy-harness/NativePolicyCheck.kt`: current app release/timestamp policy checks compiled
  directly with the already present Kotlin compiler, without Gradle. `native-policy-check.log`
  records PASS for bounded negative audio, rejected negative video, teardown gating, hidden-Future
  failure detection, admission of the original CFR-rounding example, preserved interrupted cleanup
  acknowledgement, and rejection of genuine cleanup timeout.
- `interrupt-cleanup-repro.log`: Java reproduction of the original interrupt-shortened wait.
- `run-host-checks.sh`: reproducible direct compiler/host commands for all reviewer checks.
- `pinned-source-hashes.txt`: SHA-256 of the exact inspected task-executor, codec, muxer-wrapper
  and MP4-box source entries.

No source was fetched from the network. The pinned source JARs remain under
`third_party/candidates/media3-1.11.1/sources/`. Build-owner compilation/JVM evidence is recorded
separately; it is not represented as independently rerun here.

## Final freeze confirmation

On 2026-10-06, independently compared the frozen candidate
`1c9dc1fcee17383e20787ddd6250ae6fc68ed44a` to the reviewer's final 22-file engine/verifier/contracts
SHA-256 manifest. **All 22 source hashes matched**, including the last interruption-cleanup fix.
No native-path production delta remained after the directly rerun host policy checks.

Also verified the supplied 358-file production/test manifest against the checkout. Its SHA-256 is:
`3462750081f664462566d79b46b17d2f72002ccc1ad51722d06f9ae1040b5038`
(`evidence/frozen-production-tests.sha256`). The reviewer manifest is preserved in the host-review
archive as `review/native/reviewed-working-tree-sha256.txt`.

The build owner separately reports 1,119 JVM tests, debug/release compile and strict lint, and exact
74-entry runtime dependency inventories passing. Those aggregate runs were not rerun by this
reviewer; this report independently establishes source/hash continuity and the specifically listed
host checks. Later documentation-only commits do not change the production/test candidate.

## Acceptance still not run

Actual Android extraction, Media3 export, AAC/AVC codec behavior, GPU/GL behavior, rendered
rotation/crop accuracy, A/V listening/synchronization, long-media performance, OEM cancellation and
repeated-edit compatibility are **NOT_RUN**. No device-ready or security acceptance is claimed.
