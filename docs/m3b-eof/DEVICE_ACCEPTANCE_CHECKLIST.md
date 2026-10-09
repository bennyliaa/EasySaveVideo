# Separate Android acceptance gate: NOT RUN

This list does not authorize packaging, device work or media downloads. Obtain the user's explicit authorization for any such stage.

- Use owned entropy-valid AVC/AAC fixtures. Run the actual generated local playlist through Android HlsMediaSource/HlsMediaChunk/Transformer, then independent MediaExtractor and native decoder to output EOS.
- Positive sources: no terminal AUD/EOS; existing terminal AUD+EOS without phantom sample; multislice; inherited SPS/PPS/non-IDR segment start; bounded and unbounded PES; video-only and AVC/AAC; CFR and bounded VFR; earlier intentional valid ending with matching manifest.
- Confirm every MP4 sample's count/ticks/flags/canonical payload; initial AV offset, final sample identity, preceding sync, representative duration/endpoint rule and actual terminal decoded PTS plus output EOS.
- Negatives: dropped last AU; valid metadata but truncated payload; final-GOP dependency damage; near-tail frame succeeds while actual terminal frame fails; extra/config-only samples; audio-only loss and AV-tail offset; partial transfer/packet/PES/ADTS; changed snapshot; unsupported no-AUD/B-frame/config changes.
- Exercise final EOS twice, cancellation before/after EOF, blocked dequeue/no progress, timeout during native parse/stop/release, failed release, and recovery. No caller timeout may free ownership or permit private publication/deletion while native resources remain live.
- Verify v1/v2 Direct recovery; unproven legacy Stream remains blocked; v3 Stream proof survives process restart and private commit/index atomic recovery.
- Test Android 12+ phone and tablet UI/lifecycle, foreground/background, rotation, repeated action, and entitlement boundaries. Keep no-contact zero-provider and configured Commons behavior intact without embedding personal contact in test artifacts.
- Successful decode can include device-specific concealment. Describe results as practical verified completeness of the received bytes; do not claim unknown origin author's intent or universal codec correctness.
