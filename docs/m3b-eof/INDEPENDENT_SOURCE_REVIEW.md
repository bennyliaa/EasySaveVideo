# Independent source review, 2026-10-06

No unresolved source-review blocker remains in the frozen integration.

The confirmed absolute-PTS regression was fixed: StreamConservation now bounds relative duration separately from overflow-safe absolute timestamps. Both the high-origin unit case and pinned-mux regression cover it.

Reviewed properties:

- LocalAssetReadSession and PermitDataSourceFactory preserve ordered whole-resource receipts, captured hashes, input identity, consumed EOF, sticky cancellation, and rejection of retry/range/reopen/foreign receipts.
- Pinned HlsMediaChunk's ID3 peeking, same-input extraction, sequential extractor reuse and close-after-read lifecycle fit those receipts. Quiet framework close failures still invalidate completion; underlying handle/release accounting remains authoritative.
- Inspection and export use the same reader adapter. Export receives HLS's shared TimestampAdjuster, and final AVC commitment occurs before final END_OF_INPUT.
- Existing preflight/mux whole-payload, relative-PTS, sync and AV-offset checks remain. Durable canonical evidence derives from actual mux inputs, matching pinned clock conversion.
- AndroidLocalMp4Verifier independently scans every video/audio sample through EOF, checks exact sequence evidence and audio channels, then rereads the proven final GOP and requires every expected nonempty decoded output plus output EOS.
- Tail handling rejects wrong seek identity, changed/trailing inputs, missing or duplicate outputs, early/config-only EOS, format changes, cancellation and no progress.
- NativeVerificationLease correctly closes the queued-start/cancellation race and retains abandoned ownership until native cleanup actually returns.
- Endpoint evidence includes final display duration through established CFR or bounded VFR; existing manifest tolerances remain bounded.
- Codec v3 preserves genuine v1/v2 records and legacy standalone evidence without fabricating proof. New Stream output requires conservation and tail proof. The added legacy COMMITTING regression retains old verification/receipt while asserting no transfer, verifier, committed inspection, commit, cleanup or publication.

Fingerprint: 16 changed/new production Kotlin files relative to 4f96f5d4fea00eb86353b4df71034ec0663118ce. SHA-256 of sorted SHA256SUMS-format manifest: `a4a832220a05eb4a4b83d0b173f7ab70228fa33aacdc9449bb5aae8a2898726b`. See `evidence/reviewed-production.sha256`.

Review was read-only; no edits, Gradle, package/device execution or media downloads. Build/test execution is recorded separately by the serialized build owner.

Actual Android HlsMediaSource/Transformer loading and entropy-valid native decode/release acceptance remain NOT RUN. Source review and synthetic host regressions do not establish those gates or production HLS acceptance.
