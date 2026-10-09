# M6-A independent edit-output verification

## Scope and evidence boundary

`AndroidEditOutputVerifier` verifies only a new ordinary-media private staging artifact. The
storage owner supplies `EditOwnedOutputRead` after confirmed writer closure and holds an immutable
file/extent read fence. Neither user paths nor URLs are accepted. Save, Record, HLS transport and
their verification evidence remain separate and unchanged. Vault is not enabled by this work.

The output reference, expectation and verification version travel in edit-specific evidence.
The expectation describes actual inward-snapped execution ranges and exact source-derived video
frame count. Requested trim ranges remain separate provenance. A Transformer completion callback,
reported byte count, MIME label or successful future does not independently authorize publication.
The storage owner revalidates the exact reference, expectation, byte extent and digest before its
atomic ordinary-catalog publication. Originals remain intact.

## What is checked

1. Read the actual owned descriptor size, compare its storage-owned expected extent, and SHA-256
   the complete file. Output is bounded to the plan limit, at most 2 GiB.
2. Walk every top-level atom to exact EOF. Require one supported ISO MP4 `ftyp`, one `moov`, and
   nonempty `mdat`; no fragment, QuickTime or extra trailing unboxed data is admitted. Reconcile
   movie/track durations, track count, identity rotation matrices, self-contained data references,
   one sample description per track, sample sizes/counts, chunk mappings/offsets, and `stts` timing.
   Every chunk lies inside `mdat`; chunk extents cannot overlap. The maximum is 4,096 walked atoms
   and 250,000 samples per track. Raw-table validation is not itself playback evidence.
3. Require physically display-oriented AVC output at the planned even width/height, rotation zero,
   square pixels, progressive 8-bit 4:2:0, and SDR transfer. Container AVC configuration and bounded
   SPS fields are checked independently. Baseline, Main and High are admitted, not a claimed
   encoder-enforced Baseline profile. Custom scaling matrices, B-frame composition reordering,
   HEVC, HDR, encrypted/external tracks and unsupported descriptions fail closed.
4. Require exactly one video track and either no audio or one AAC-LC track with the planned
   sample rate and channel count. Initial AAC support requires an ordinary two-byte LC
   AudioSpecificConfig; HE-AAC, SBR/PS and extension configurations are outside this slice.
5. Use fresh `MediaExtractor` instances over the same descriptor, without approximate seeks, to
   walk every track from its actual beginning to all three EOF indicators plus a failed sample
   read. Compare every sample size with `stsz`, total bytes/count and timestamps with timing, and
   reject encrypted, partial, unknown-flag, duplicate, reordered or missing samples.
6. Decode the complete bounded video and audio tracks. Each video input identity and each output
   PTS must match the exact scanned sequence once, including first, middle and final frames.
   Actual input and output EOS are required. AAC proof counts PCM sample-frames, checks continuous
   interior PCM timestamps, and independently checks decoded rate/channels/PCM16, duration and tail.
   Codec-config buffers, metadata frames, nearby sync frames and empty output cannot supply proof.
7. Check all decoded format changes, rehash the exact descriptor extent, and compare the original
   digest. Evidence is returned only after confirmed codec/extractor release and descriptor/fence
   closure, followed by the pure `EditVerificationEvidence.validFor` publication check.

## Timing policy

- Source admission and planning use the observed source-frame timeline. Non-frame-aligned requests
  snap inward to actual frame boundaries. The expectation uses the resulting execution duration
  and exact frame count, not an assumption that arbitrary joins are globally CFR.
- The supported output is integer 1–60 fps CFR. Both each inter-frame gap and the complete global
  PTS lattice are checked with at most 1,000 microseconds of timestamp/timebase rounding. This is
  an engineering compatibility bound, not a promise of visual frame accuracy on an untested device.
  There is no general VFR or join-discontinuity allowance. Last-frame `stts` duration must also
  match the frame interval within this bound; a zero/near-zero tail duration is not accepted.
- No seek tolerance is used: the verifier does not seek to a nearby frame and call it the target.
- AAC priming and padding use the explicitly planned combined allowance, at most two 1,024-sample
  packets per segment. Interior PCM gaps cannot spend this boundary allowance. Unit-rate MP4
  edit lists are parsed: audio may contain one bounded leading trim, or one bounded empty lead
  followed by unshifted media. Video cannot hide a trim, delay or rate change. The extractor's
  first PTS must match the edit-list mapping. A platform that hides samples such that the complete
  raw-table count cannot be reconciled fails compatibility rather than receiving fabricated proof.
- The planner's duration tolerance and AAC allowance are also checked by the independent evidence
  fence. Packet count and PCM-frame count are different quantities; both are bounded and checked.

## Native ownership and budgets

There is one process-wide verifier lane and one admitted owner. The worker retains the read
capability and actual native resource references. Queued cancellation must be acknowledged by the
worker; running timeout/cancellation marks ownership draining and fences every late result. A
cancelled/done future or an interrupt is not release evidence. Failed native release or descriptor
close retains the owner and storage fence for process recovery. No replacement native thread is
created to work around a stuck parser/decoder. The parent retains the shared ordinary-media gate
across the entire edit job.

After cancellation/deadline, the caller allows the worker up to the separate 10-second release
budget to acknowledge actual cleanup. A cooperative, confirmed close remains an ordinary cancelled
or timed-out edit. It does not permanently fence the process merely because the worker needed a
few milliseconds to close. Missing/failed acknowledgement remains a recovery case; interruption
does not shortcut this bounded cleanup wait, and the caller's interrupt flag is preserved.

The total verifier deadline scales with planned duration (30 seconds plus twice media duration,
capped at 30 minutes and the remaining operation deadline). Native decode must make real input or
output progress within five seconds. Input access units are capped at 4 MiB. These are resource
bounds; 30-minute inputs are not a measured performance guarantee and may fail the device budget.

## Verification status and limits

Host tests use synthetic MP4 boxes and synthetic sample/decoder events. Their fixtures are not
playable media and are never exposed as user results. The build owner records compile/JVM results
separately under the M6-A evidence directory. The tests cover exact/truncated extents, bad sample
tables/chunk references, duration and geometry mismatches, rotation, unsupported profiles/HDR,
edit-list behavior, frame cadence/count/tails, snapped non-frame trims and merges, AAC configuration
and PCM gaps, cancellation and sticky unconfirmed native ownership.

Confirmed host result on 2026-10-06: all **56 verifier JVM tests passed**, zero failures/errors/skips
(21 container, 25 sample/decode policy, five native-gate and five cleanup-acknowledgement tests;
JUnit XML timestamps 22:15:42 UTC). The serialized build owner's
`docs/m6a/evidence/strict-offline-aggregate.log` reports `BUILD SUCCESSFUL`, including the debug and
release compile/lint stages. This result is for host code/contracts, not Android media execution.

Android extraction/decoding, actual Media3 export, hardware codec/GL behavior, physical crop and
rotation correctness, A/V listening/sync, and repeat-edit compatibility: **NOT_RUN**. No APK/device,
real-account, network-media or third-party fixture execution is claimed.

The proof is deliberately not pixel comparison, source/segment-order visual proof, subjective
quality acceptance, exact bitrate achievement or a guarantee that compression reduces file size.
Those device/content acceptance checks remain open. A well-formed wrong-picture output with the
same metrics requires source/output visual testing to detect; the verifier does not invent such
an acceptance result.

## Pinned implementation references

The implementation is original app code. Pinned, reviewed Media3 1.11.1 sources were inspected,
not copied: `media3-muxer` `Boxes.java` (`stts`, `edts`/`elst`, final-sample duration) and `Track.java`
(90 kHz video timebase); `media3-transformer` `MuxerWrapper.java` (AAC encoder-delay timestamp
adjustment and first-video-frame behavior). Their admitted artifact/source provenance stays under
`third_party/candidates/media3-1.11.1`. No new dependency or provider is introduced.
