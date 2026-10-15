# Planned acceptance matrix

Status of every test row in **this task: NOT_RUN**. These are implementation acceptance requirements, not test results. No compile, host fixture, device fixture or media output was executed here. The coordinator's separately reported output-resource regression is a prerequisite baseline, not evidence for the new run path.

## Stage A: declaration observations and deterministic candidate, no run issuance

| ID | Layer / concrete case | Required observation | Gate |
|---|---|---|---|
| A01 | Actual pinned Matroska: 24/25/30/60 fps DefaultDuration | Public Track value captured at existing TrackEntry end; Format.frameRate remains unset | Fact capture |
| A02 | DefaultDuration absent, zero, duplicate, out of range, wrong context | Unsupported/malformed before native entry; no fabricated rate | Fact capture |
| A03 | Floor/ceil 1 ns declarations, noninteger 29.97 declaration, 1 and 60 fps bounds | Exact integer policy accepts only defined rounding; noninteger profile unsupported | Pure policy |
| A04 | Fractional Duration ticks at default scale | Preserve declaration value and official parser SeekMap value separately; validate documented rounding | Actual pinned extractor |
| A05 | Missing/unknown/conflicting seekMap duration, multiple callbacks | No native run; reject conflicting value; no guessed compressed-tail duration | Actual pinned extractor |
| A06 | D/M difference 1000 and 1001 us, N residual boundary, 2/3/108000 frames | Exact inclusive policy bounds, checked overflow and no sample-count-derived frame result | Pure policy |
| A07 | Same frame declaration with deliberately changed transport sample count | Candidate count unchanged; displayed agreement deferred to actual decoder | No conservation confusion |
| A08 | Probe/load new-field mismatch (DefaultDuration or parser duration), same geometry | Exact registered pair fails before completion; no fresh probe/budget | Same binding |
| A09 | Probe with real audio and expectedAudio false/unknown/true | Audio inventory always retained; silent candidate may reject but never remove audio | Audio preservation |
| A10 | Current guard known/unknown-size Segment/Cluster and actual EOF regressions | Existing complete guard, metadata/sample limits, no fallback and transport equality unchanged | Regression |
| A11 | Candidate spec tolerance/bitrate/free-space exact boundaries, fractional estimated byte and checked arithmetic overflow | Video ceil(1e6/r)+1000, silent audio/priming zero, estimate ceil(bitMicroseconds/8e6), exact capped reserve; no Stage B reselection | Frozen A/B policy |

## Stage B: owner-private real-run issuance and callback shape

| ID | Layer / concrete case | Required observation | Gate |
|---|---|---|---|
| B01 | Foreign/unregistered attempt, wrong Scope, stale Save/WRITING snapshot | Reject before output/native creation; same maps only | Issuance |
| B02 | Freely constructed DirectVp8Opus spec/Completed/EngineFacts | Still no issuance, finish or output authority | No caller outcome |
| B03 | Duplicate concurrent run, failure then rerun, queued cancellation | Exactly one run claim; no second probe/session/source/target/re-encode | One-shot |
| B04 | Real pinned SequenceAssetLoader copy semantics | Callback EditedMediaItem differs; its MediaItem is exact original reference; correct effective remove flags | Callback identity |
| B05 | Same-URI MediaItem clone, same-fields foreign item, changed removeVideo/audio/effects/speed | Reject before real loader creates/opens resources | Callback identity |
| B06 | Second AssetLoader callback or second MediaSource request | Denial; first source/reader remains owned until exact release | Single route |
| B07 | Accepted paired source via ExoPlayerAssetLoader | Exact media source returned; no stock extractor; actual prepare/download/playback threads tracked | Real factory path |
| B08 | Actual pinned ordinary fixture requests zero-open, reopened input or nonzero seek | Zero-open never completes; supported one-pass succeeds; unsupported reopen is explicit compatibility failure, never relaxed guard | Paired open semantics |
| B09 | Captured official duration vs onDurationUs, duplicate/changed formats, wrong track inventory | Mismatch fails; no reselect/rebase; Opus CSD unchanged | Native metadata |
| B10 | Engine configuration hooks | Direct uses mandatory VP8_OPUS observer factory; Editor default remains AVC_AAC; no effects/frame drops/transmux/fallback | Shared engine |
| B11 | Active completion getter timing | observer.finish and completedTransportFacts run before beginCleanup/entry retirement, only once | Critical ordering |
| B12 | Export completion but incomplete raw EOF, DataSource close unacknowledged, missing observer EOS or pending frame | Reject tentative success; actual cleanup still runs | Completion |
| B13 | Actual source count differs from declaration N; encoded mux count differs from observed displayed count | Reject output; compressed sample count cannot substitute | Displayed agreement |
| B14 | Codec failure/fallback, muxer final-table failure, loader factory failure | Preserve content failure and exact real release provenance; no seal success | Failure |
| B15 | Cancellation/deadline at queued work, exporter callback, capture, native release and seal | Original context/stop preserved; late successful callback cannot create proof | Lifecycle |
| B16 | Real runner release UNCONFIRMED, stale successful session or fabricated Completed | No seal/finish; existing process recovery fence remains sticky | Release |
| B17 | Genuine Completed plus exact source/writer release | Actual private outcome retained; same-FD raw/output hashes and both closes once | Seal |
| B18 | Mutation/hash/close failure, output-size mismatch after actual run | No completed result/finish; existing ownership and poison retained appropriately | Seal |
| B19 | Editor full mechanism/session/muxer and MOV regression | Existing behavior/test coverage unchanged; one shared lane | Regression |
| B20 | Real registered DataSource/checkpoint throws original CANCELLED/DEADLINE/identity failure, delivered through Media3 asynchronous ExportException and actual Direct listener | Returned Failed.primary retains the exact original object; no EXECUTION_FAILED overwrite | Async original-error contract |
| B21 | Real registered output write/force fails ENOSPC/STORAGE_FULL or identity check, received through actual asynchronous listener/wrapper | Exact original storage error retained; release outcome remains independently correct | Async output error |
| B22 | Native unsupported error with no registered original; foreign/same-message throwable; onCompleted races after recorded original | Only no-original native case uses technical mapping; no arbitrary-cause authority or late success | Async mapping boundary |
| B23 | Same native wrapper path on Editor; original failure followed by cleanup timeout/failure | Editor's prior mapping unchanged; Direct retains original reason plus UNCONFIRMED/sticky recovery when applicable | Error/release separation |

A host fake may test scheduling/negative identity rules, but B07/B08/B09/B12/B14–B18 and B20–B23 native claims require actual pinned/Android paths at the appropriate boundary. Tests that only manufacture private-looking DTOs must not be reported as successful conversion.

## Stage C: Android silent execution and playback, authorized emulator only

| ID | Fixture / action | Required measured result | Status here |
|---|---|---|---|
| C01 | Original complete silent VP8, integer rates 24/25/30/60, landscape/portrait | Real VP8 decode → AVC encode, every displayed frame CFR/continuity, final expected count, complete MP4, positive native/FD release | NOT_RUN |
| C02 | Visible first/last image markers, motion/text/timecode, ≥3 frames | Expected pictures survive, no clipped head/tail, generated MP4 plays after close | NOT_RUN |
| C03 | Browser-produced complete WebM fitting finite declaration policy | Realistic loader/open sequence works; unsupported declarations reported honestly | NOT_RUN |
| C04 | Invisible/reference-only VP8 compressed sample fixture | Actual displayed count controls result; compressed count may differ | NOT_RUN |
| C05 | VFR, false duration/default rate, missing tail/truncation | Fails unsupported/incomplete; no normalization of the bad source into apparent success | NOT_RUN |
| C06 | Cancel during decode/write/finalize; missing VP8 decoder or AVC encoder; low free space | Bounded cleanup and no publication; unsupported codec is not false success | NOT_RUN |

Use only the separately authorized emulator-5556, preserved app data, existing signing and increasing versionCode when the coordinator schedules actual tests. This design performed none of those actions.

## Stage D: Opus retained acceptance work

| ID | Original fixture / variation | Required evidence | Status here |
|---|---|---|---|
| D01 | VP8+mono Opus and VP8+stereo Opus, ordinary 48 kHz | Sound present, unchanged channel count, actual S/E PCM16 rate/count/EOS and AAC output | NOT_RUN |
| D02 | Zero/nonzero codec delay and seek preroll, negative raw prefix | Known audible head preserved; common source-zero mapping demonstrated, not assumed from raw PTS | NOT_RUN |
| D03 | Positive final discard padding, known audible tail, exactly/over 120 ms metadata limit | Source S tail relation and independent AAC tail measured; bound enforced; no automatic padding subtraction | NOT_RUN |
| D04 | Padded laced/multiple/nonterminal block cases | Supported mapping explicitly measured or precise unsupported subcase; no invented single final padding value | NOT_RUN |
| D05 | Realistic browser-produced Opus WebM with beginning/end sound markers | Ordinary trusted Media3/Android behavior measured end-to-end | NOT_RUN |
| D06 | Short, delayed and overlong audio with otherwise correct 1 fps video | Fail source endpoint/S–E equality rule; graph silence/rebase cannot hide discrepancy | NOT_RUN |
| D07 | Consumer false-queue retry/throw/repeated input, decoder/encoder EOS ordering | Existing counters commit only accepted operations; original error preserved | NOT_RUN |
| D08 | AAC priming/edit-list mapping including named c2 encoder | Actual effective independently decoded output start/end/PCM count compared with source S | NOT_RUN |
| D09 | AAC head/tail/effective-duration error at 42667/42668 us | Exact separate boundary, no added frame tolerance or 120 ms allowance | NOT_RUN |
| D10 | Audio-source requirement vs temporary silent-only staging | Real audio input never silently loses its track; silent stage never advertised as completed VP8/Opus support | NOT_RUN |

Do not block Stage A/B on unbounded audio investigation. Implement the known official path and execute this finite corpus. A failure produces a concrete compatibility boundary and, if needed, one focused public-boundary fix; it does not justify an Opus parser or blanket assertion that the format cannot work.

## Stage E: next durable handoff and complete product gate

| ID | Case | Required observation | Gate |
|---|---|---|---|
| E01 | finishOwnedTranscode with no real run, failed run, unsealed output, foreign attempt or copied proof | No TRANSCODE→FINISH; no proof CAS | Owner-kept state |
| E02 | Genuine sealed run, exact immutable original Save/WRITING/context/Scope | Consume once; existing advance zero-call/zero-obligation conditions | Typed finish |
| E03 | Conversion proof construction | Existing fields derived from actual EOF/observer/muxer/release/hash facts; no MOV conservation/noEncoding | Existing schema |
| E04 | Conversion proof CAS success, false, throw-after-commit and repeated call | Exact completion registry/revision; inspect uncertainty; never re-encode | Journal |
| E05 | Crash at OUTPUT_READY and each no-replace adoption move | Existing exact recovery stages; no overwritten file; recovered WRITING never runs again | Recovery |
| E06 | Independent full AVC/AAC decode mismatch/incomplete EOS/mutation | No verification or publication; same canonical retained FD pre/post hashes | Independent verification |
| E07 | Commit digest, invisible index and final Library+Save atomic transaction | Existing typed commit/publication rules; arbitrary durable DTO cannot act as handle | Publication |
| E08 | Full current regression/lint/dependency hash gates; Android C/D corpus | Exact final source and runtime artifacts; production WebM admission only after all required gates | Product admission |

## Scope boundaries for reviewers

The first implementation PR/slice can finish A without altering runtime admission. The next completes B's owner path and obtains C evidence when the authorized environment is available. D stays explicit retained work and must be accepted before audio production admission. E is the already-defined next typed durable handoff, not optional if the user is to receive a playable Library conversion.
