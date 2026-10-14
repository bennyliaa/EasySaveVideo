# Free basic normalization: silent MOV host adapter layer

Date: 2026-10-14 UTC. Continues original PR-10/11, product §7.1 and technical
§5.1/5.3. This is layer 2 after `BASIC_NORMALIZATION_20261014.md`.

## Result and exact scope

Original local-only source code now performs strict silent QuickTime AVC → MP4
remux with the already-admitted Media3 1.11.1 extractor/muxer. No dependency,
permission, encoder, network client, paid capability or runtime inventory changed.
The real pinned bytecode host suite passes **25 tests**, including **138** finite
rate/sample-count/layout combinations. Clock/canonical JVM tests pass **20** and
raw profile JVM tests pass **16**. These counts are distinct; fixture combinations
are not additional registered JUnit tests.

**Production MOV admission remains disabled.** The existing Direct/runtime/native
verifier still follows its admitted MP4 path. Host success does not prove Android
decoding, real descriptor ownership, durable adoption, device codec support or
native release. No APK was assembled or installed; previous frozen APK is unchanged.

## Implemented behavior

- `DirectMovClock` implements the reviewed 90 kHz/10 kHz integer rounding and exact
  endpoints for the 23 finite rates. The audio clock model is future proof work,
  not audio admission. The finite arithmetic test visits 1,094,770 clock positions.
- `DirectMovCanonical` enforces AVCC4/Annex-B byte identity, single SPS/PPS,
  versioned configuration/payload/flags/timing digests, exact callback flags and
  independent empty EOS. Byte loops check cancellation at most every 4 KiB;
  interrupted partial accumulator state cannot be reused.
- `MovProfileProbe` and `MovAvcProfile` independently inspect bounded raw source and
  output tables, extents, clocks, parameter sets and every slice header. Inputs
  and canonical outputs have separately named container admission. Unknown critical
  boxes fail closed; recognized protection boxes are specifically protected.
  Unknown structure never becomes proof that media is unprotected.
- `MovRemuxFiles` defines exact-owned capabilities. Opening failures must close
  any handles not returned. Source/output identity, extent, actual close confirmation
  and free-space probes belong to the future persistent adapter.
- `MovRemuxEngine` compares raw source bytes/tables, real source extractor callbacks,
  raw finalized output bytes/tables and real output extractor callbacks. Output
  independently agrees on every admitted sample/configuration/flag/time and endpoint.
  No retiming, fallback, dropped NAL, dropped track or silent repair is accepted.
- The engine retains separate source/output hashes and never overwrites raw input.
  Limits include 2 GiB per artifact, operation raw-plus-output byte allowance, one
  local operation, finite extraction steps, bounded sample buffering and live disk
  checks. Disk admission is conservative, not a physical quota against external writes.
- Cancellation, deadline and byte/storage errors remain typed even when the muxer
  wraps sink exceptions. Explicit independent sink/input closes run even if pinned
  mux finalization throws unchecked. Unconfirmed release suppresses evidence and
  blocks subsequent admission in this engine; ordinary failure with confirmed
  release does not poison another job. The outer task/native claim is not retired
  or published by this engine.

## Coverage limitations and value

The first profile covers only **already-silent**, exact-clock, progressive Baseline
AVC QuickTime. Any audio track rejects the entire file before output creation.
It does not convert typical camera MOV with audio, rotation, edit lists, different
clocks, B pictures, HDR or arbitrary AVC behavior. Additional conservative gates
include explicit BT709 limited SDR, square pixels, a single short-term reference,
sequential frame_num/POC, no MMCO/reference-list modifications/FMO or unknown NALs.
SPS/PPS and slice-header checks are not entropy decoding or device capability proof.

Completing executable AAC AU/per-AU and total PCM frame/timestamp verification is
an open original requirement. No successful ASC/stts or synthetic mux case permits
silently removing audio or advertising general MOV conversion. WebM/transcoding
also remains open.

## Real bytecode evidence

`app/src/testSupport/mov-remux-host/run.sh` checks the exact admitted muxer/extractor
AAR hashes, compiles the actual production adapter/probe sources and runs original
owned fixtures using existing Android host shims. It does not replace the muxer
with a fake. Memory input/output ports exercise failure and release contracts;
those ports are not Android filesystem/decoder verification.

Final host run: `pinned-host-r4/results.log`, **25 PASS**, process exit 0.
The host suite proves:

- 23 rates × counts 2/3/7 × metadata-before/after-media, with co64/chunk variation;
  independent output stts/mdhd/tkhd/mvhd checks, including two-sample EOS tail.
- AUD, matching in-band SPS/PPS, all-IDR, POC2 and multi-slice byte preservation.
- Audio/unsupported profiles rejected before output opens; low disk, exhaustion
  during writes, zero-progress output, wrapped I/O and aggregate byte limits fail.
- Real callback fragmentation, both data APIs, nonzero trailing offset, last-sample
  flag, forbidden data part, mismatched offset and unconsumed tail handling.
- Valid finalized slice_data mutation and a structurally valid alternate frame rate
  still fail conservation; valid-framing callback payload mutation also fails.
- Cancellation during actual writes, reentrant BUSY isolation, independent close
  after unchecked mux finalization, and source/finalized-input/sink unconfirmed
  close suppression. Tests inject finalization failure specifically inside the
  pinned finishWritingSamplesAndFinalizeMoovBox call.

Focused XML evidence: 20 helper tests at 10:42:26 UTC; 16 probe tests at 10:42:32 UTC,
all zero failures/errors/skips. A preliminary worker report of 18 probe tests was
corrected by reading the actual XML. Earlier 5/22-test host runs are superseded by
r4; no earlier result is presented as the final source result.

Independent read-only review accepted the host transport layer after the three
additional conservation-negative cases. Reviewed engine SHA-256:
`4bcfe73d0f28b07099ec99a977eb87e1858dba9c5d5caf0f6a7a4a02e2e64cf0`.

Final 13-module fresh regression: **4,109 PASS + 2 existing SKIP**, zero failures/errors
(4,111 registered; app 2,691, save 246). Debug/release compilation and both lint
tasks completed successfully. Lint retains the baseline 60/61 warnings and 8 hints
per variant, zero errors/fatal findings. Exact runtime inventory remains 90; all
99 original license payloads and the seven-getter ABI pass. Current host SQLite
20 tests, including 411 injected rollback boundaries, also pass; not Android SQLite.
Full regression and boundary summaries are in adjacent
`basic-normalization-adapter-20261014/`. Private backup is verified separately.
No empty remote status/check list is claimed as green CI.

## Immediate next development layer

Implement the exact-owned persistent raw/output file capabilities and protocol
integration, followed by typed final Android verification and Coordinator/runtime
admission fencing. Preserve ledger fsync order, terminal Save authority, actual
release evidence, shared Direct local/native exclusion and original MP4 behavior.
WRITING partial output currently has no restart/truncate permission: define an
explicit safe recovery disposition before integration. Keep production MOV closed
until final verification and remaining native/device acceptance gates are satisfied.
