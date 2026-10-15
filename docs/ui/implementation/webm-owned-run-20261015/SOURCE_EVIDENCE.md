# Exact inspected source findings

All Java paths in this file are under `inspected-pinned-source/`; their archive path, whole-archive SHA-256 and exact member SHA-256 are in PINNED_SOURCE_SHA256.json. Copies were read from the ESV checkout's existing `third_party/candidates/media3-1.11.1/sources/` only. ESV's prior `docs/m6a/NATIVE_SOURCE_REVIEW.md` identifies this source set as already admitted. No sources/artifacts were fetched or substituted. This report does not re-admit a dependency or establish Android execution.

## 1. DefaultDuration is real, but not Format.frameRate

`androidx/media3/extractor/mkv/MatroskaExtractor.java`:

- 238: `ID_DEFAULT_DURATION = 0x23E383`.
- 1230–1232: official integer callback sets `getCurrentTrack(id).defaultSampleDurationNs = (int)value`.
- 2417: the Track field is public.
- 1020 and 1668–1670: it participates in timestamps within multi-sample blocks. This use is not a declaration that each compressed sample is a displayed frame.
- 2737–2851: official audio/video Format construction; video sets dimensions/pixel ratio/rotation/projection/color. There is no `setFrameRate` call or frameRate identifier anywhere in this file.

ESV `BoundedWebmExtractor.kt` already sees 0x23E383, enforces uniqueness and a positive Int bound, and calls super. Its TrackEntry end callback already inspects this exact public Track before super; store the field there. The existing `WebmTrackTransport` omits it and `OwnedWebmInputBinding.sameFacts` therefore cannot currently compare it. These are small extensions of existing observations.

`androidx/media3/transformer/EditedMediaItem.java:198–225`: setting frameRate limits video output by dropping frames above that rate. It is not a metadata-only correction. Leave it unset for this no-drop conversion profile. Encoder format frameRate can still be the owner-fixed rate, with actual source/output counts independently checked.

## 2. Declared Duration and actual parser Duration differ in truncation order

`MatroskaExtractor.java:1418–1420` casts floating duration ticks to long. Lines 900–907 then use `scaleTimecodeToUs`; lines 2226–2233 scale that integer by TimecodeScale/1000. In contrast ESV `Policy.endMasterElement(INFO)` computes `(doubleTicks * timeScaleNs / 1000).toLong()`.

Example of the distinction, not a test result: duration 1000.5 ticks at 1,000,000 ns/tick gives 1,000,500 us in the current ESV declaration observation and 1,000,000 us in the pinned parser. A plain equality check would reject this ordinary fractional declaration even though no mutation occurred.

Capture the official `SeekMap.durationUs` alongside the existing declaration observation. Fix the run from this actual bounded parser duration and separately bound the difference to the declaration. `ExoPlayerAssetLoader.java:433–449` reports `Timeline.Window.durationUs` directly, not `getDuration()` rounded through milliseconds. Validate that callback against the captured actual duration. Missing or substantially differing declarations are unsupported in the initial finite profile.

## 3. Actual factory callback item is rebuilt, underlying MediaItem is retained

- `androidx/media3/transformer/SequenceAssetLoader.java:134–160`: applies sequence track constraints, then passes `editedMediaItems.get(0)` to AssetLoader.Factory.
- Same file, 409–426: for a non-gap it calls buildUpon, changes removeAudio/removeVideo based on sequence track types, and builds a new EditedMediaItem.
- `EditedMediaItem.java:94–103`: copy builder reuses the original MediaItem reference and copies effects, speed and preprocessing fields. The latter is package-private at 362; `isGap` is package-private at 487. Do not propose accessing these through ESV's public API.
- `ExoPlayerAssetLoader.java:214–232`: Factory passes the actual EditedMediaItem and supplied MediaSource.Factory into the real loader; 318–320 start calls `createMediaSourceForEditedMediaItem`; 362–372 calls that factory with the actual item's MediaItem, wrapping only when speedProvider is not DEFAULT.

Therefore original EditedMediaItem reference equality is wrong. Original exact MediaItem identity + valid effective public settings + one actual callback-item capture is appropriate for this inspected trusted path. The subsequent MediaSource callback must be exactly that captured item's MediaItem and return only the previously paired source. URI equality alone is insufficient. No Android proof is claimed for this identity path until tested against the actual pinned classes.

## 4. Opus metadata → Android decoder → S → graph → E

### Extractor metadata and its limits

`MatroskaExtractor.java:1239–1246` stores CodecDelay, SeekPreRoll and block-group DiscardPadding. Lines 2568–2576 build Opus initializationData as the original codec-private header plus two 8-byte little-endian nanosecond values for codec delay and preroll. It does not populate Format.encoderDelay/encoderPadding from those declarations in the inspected format-building path.

Lines 1005–1011 prepare positive DiscardPadding as 8-byte little-endian supplemental data. Lines 1967–1980 add supplemental framing; 1808–1824 append it when a block contains one sample, but intentionally omit that payload for multi-sample blocks. The existing guard preserves supplemental parts and forbids encryption; supplemental bytes must not be discarded as a generic unsupported flag.

This supports a finite metadata policy and ordinary trusted Opus decode. It is not a source-audible boundary measurement. The existing guard's positive 120 ms metadata cap does not authorize losing 120 ms of sound.

### Decoder construction and input

- `androidx/media3/transformer/DefaultDecoderFactory.java:243–252`: audio decoder uses `MediaFormatUtil.createMediaFormatFromFormat` and then the actual DefaultCodec.
- `androidx/media3/common/util/MediaFormatUtil.java:168–190,223–227`: passes all initialization data as csd buffers and separately sets Format.encoderDelay/encoderPadding keys. Both paths must be considered; the mere existence of delay/padding keys is not evidence that this particular WebM declared values became those fields.
- `androidx/media3/transformer/ExoAssetLoaderBaseRenderer.java:127–132` retains renderer stream start; 303–319 reads input, invokes shouldDropInputBuffer and passes it to the decoder.
- `ExoAssetLoaderAudioRenderer.java:70–82` subtracts `streamStartPositionUs`, then drops negative decoder input. This cannot be interpreted by treating raw Matroska timestamps as already local renderer timestamps. Local exact BaseRenderer source was not present in this source set; no additional fetch was made. The older overall design records the official-tag BaseRenderer source interpretation. Native negative-prefix fixtures remain necessary.
- `DefaultCodec.java:215–249` forwards primary data position/length, PTS and EOS to Android MediaCodec; no supplementalData access appears in this method or elsewhere in this class. This is a concrete local observation, **not proof that every other layer/Android implementation fails to trim**. Sample queue internals and Android codec implementation were not newly inspected/executed here.

### What S actually measures

`ExoAssetLoaderAudioRenderer.java:85–125` obtains the actual decoder's output format, delivers it to the SampleConsumer, copies decoder output bytes, assigns decoder BufferInfo PTS/flags, releases that decoder output, and queues the consumer. Thus ESV's `sourcePcm` wrapper measures successful delivery of decoded PCM to the exact downstream consumer. It does not directly observe an Opus packet-to-audible-frame trim map. Source decoder EOS remains separately observed.

### Graph behavior and what E measures

- `AudioGraphInput.java:138–158`: initializes an empty/default user preprocessing path plus the configured graph path.
- 250–267: accepted input captures its actual first timestamp; 524–552 sets expected duration; 619–669 adds SilenceAppendingAudioProcessor and any requested rate/channel normalization.
- `SilenceAppendingAudioProcessor.java:28–38`: explicitly appends silence when input is short, does not truncate overlong input. Do not conflate it with an Opus trim processor.
- `AudioGraph.java:367–389` moves PCM through mixer then the graph processing pipeline. The graph uses source time offsets; it does not make the encoder's zero timestamp an independent source-time observation.
- `AudioSampleExporter.java:198–240,254–256`: pulls graph bytes into AAC encoder input, sets each input timestamp from cumulative previous input bytes, then increments cumulative bytes; EOS uses that cumulative duration. E therefore starts at zero by construction.

No explicit source Opus supplemental-padding consumer or source Opus-specific TrimmingAudioProcessor was found in these inspected renderer/DefaultCodec/graph classes. That finding narrows the fixture risk; it does not establish final device trimming or its absence. Android may apply CSD codec delay; other admitted framework internals may handle mapping. Existing no-effects, same-rate/channel S/E equality plus complete terminal observations are useful checks, but waveform/head/tail fixtures are still the source-audible acceptance evidence.

### Output AAC is separate

`DefaultCodec.java:420–428` sets encoderDelay=1600 for the named c2.android.aac.encoder output. That is **output AAC encoder behavior**, not a source Opus padding solution. Independent MP4 verification must inspect actual AAC priming/padding/edit-list presentation and compare its effective endpoints/duration with actual S-source expectations. Preserve the separate 42667 us AAC boundary cap; do not add video or Opus slack.

## 5. Completion/release facts in ESV

- `TransformerSessionExecution.kt`: work returns before beginCleanup; actual cleanup outcome is constructed; entry retires in finally; only then is the result future completed. Therefore worker-retired pair/observer lookups cannot be the design's success path.
- `OwnedWebmInputBinding.kt`: completedTransportFacts requires exact entered session, completed guarded EOF and positively closed logical DataSource.
- `TransformerSourceObserver.kt`: finish is one-shot and checks source terminal/visible frames plus S/E terminal/continuity/count relationships. It is currently unconnected to Engine's production Direct execution.
- `TransformerEngine.kt`: current execute returns muxer evidence without observer finish and `requireIssuedTransformerRun` admits Editor only.
- `OwnedDirectTranscodeAttempt.kt`: sealResources already requires actual session release, exact native source/writer pin release, drained logical calls and bounded retained-FD hashes; it stores resource observations only. It does not store an actual run outcome.
- `NormalizationIoReservations.kt:165–173`: TRANSCODE → FINISH is absent. This design retains that gate through the run-only stage; subsequent typed owner finish opens it only with the owner-held actual result.

## Evidence classification

Established here: exact local source behavior and available API/access boundaries, current ESV ownership/closed-transition state, concrete proposed implementation and test plan.

NOT_RUN here: compilation, JVM/host tests, actual Java muxer/extractor runs, Android decoder/encoder/GL/native release, audible/picture fixture measurements, generated MP4 playback, independent canonical verification, durable finish/adoption/commit/publication, device admission. Prior parent-reported output-resource test results are baseline context, not results produced by this task.
