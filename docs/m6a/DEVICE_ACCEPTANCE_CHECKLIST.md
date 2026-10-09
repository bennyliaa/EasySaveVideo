# M6-A Android/device acceptance: NOT_RUN

No device, emulator, APK/AAB, real recording, real media download, account or permission prompt was used. All rows below remain NOT_RUN and are required before release acceptance.

## Functional and visual matrix

- API31 through current target, phone/tablet, portrait/landscape, large font and system insets; responsive SkinKit layout, TalkBack controls, Back/Cancel and Activity recreation.
- Synthetic lawful MP4 ruler/timecode sources at rotations 0/90/180/270, fit-center letterboxing, square pixels, corners/edges, even-pixel inward rounding. Compare actual output pixels to selected display coordinates; target <=2 output pixels after documented alignment.
- Read-only original playback, schematic labels, exact item replacement/delete/revision races, return from preview without draft loss, output-only preview after publication.
- Non-frame-aligned temporal cuts at 24/25/30/60 fps and low rates, minimum three frames, beginning/end, repeated source clips, up to eight clips. Compare actual inward execution ranges to original request; no implicit keyframe-only trim.
- Twenty-plus-thirty-second merge and repeated ordered segments. Check decoded picture order, no introduced black/pause frames, tail duration and duration target <=0.5 seconds where applicable. Check every join and re-edit derived output.
- AAC-LC silent/mono/stereo variants, negative encoder-delay PTS and edit lists, 32/44.1/48 kHz, source A/V start offsets, trim beginning/middle/tail, audible discontinuities and first/middle/last A/V synchronization target <=100 ms.
- Original-quality and space-saving actual rate-control output, no-op avoidance, file size larger than source, texture/text/motion quality. No guaranteed compression ratio.
- Normal unsupported codec/size/profile rejection with no automatic fallback, recoverable pre-allocation failure, mixed audio, fractional/VFR, HDR, non-square pixels and incompatible aspect ratios.

## Resource and failure matrix

- Cancel in queued/preparation/probe, decode, GL transform, AAC encoding, mux finalization, independent video/AAC verification, and before publication. Normal confirmed cleanup must permit a new job; cancellation is always ungated.
- Codec/GL/loader/muxer slow or hung release. Verify no decoder release before terminal GL cleanup, no source/final cleanup while native readers/writers live, no replacement worker bypass, visible retained/restart fence.
- Low storage before start, during write and at fsync/rename/SQL commit; output byte cap, stale source tombstone, size/hash mutation, duplicate submit and final-name collision.
- Real SQLiteOpenHelper v1/v2/v3 upgrades, database constraints and FK mode after reopen, process kills at prepare/intent/rename/fsync/atomic visibility windows, interrupted cleanup and exact source preservation.
- Thirty-minute/1920×1080 profile thermal/memory behavior, native peak resource measurements, output/probe/full-decode deadline appropriateness. Host caps are not measurements.
- Ordinary derived-item rename/move/play/share/MediaStore/SAF export/delete regressions and unchanged M4 system-consent recording behavior. No Vault activation.
