# Transformer observer public-wrapper host contracts

Runs the actual production TransformerSourceObserver against the admitted Media3 1.11.1 Codec/SampleConsumer interfaces, Format/DecoderInputBuffer implementations and original deterministic delegates. Tiny Android data carriers are host-only; they do not implement a codec or decoder. No dependency addition, network, Gradle, APK, device or native use.

From a prepared approved local toolchain: `bash app/src/testSupport/transformer-observer-host/run.sh /absolute/repo /external/output`. `SOURCE_OVERLAY` can point to an external staged source root for isolated pre-integration review. The script refuses output inside the repository and checks each pinned Media3 AAR against the admitted runtime manifest before loading bytes.

This checks repeated mutable output info lifetimes, format polling, zero-size Surface buffers, successful long releases, discarded frames, unexpected true overload, throwing release/no retry, pending EOS, common-origin/video endpoint checks, source-consumer null/backpressure/true/throw behavior, pre-call snapshots despite mutable reset, graph/encoder queues, independent terminal acknowledgments, source start and PCM continuity, graph trim/padding count mismatch and short audio under one-fps video.

It does not establish real Android rendering, codec/GL release, Opus pre-skip/preroll/discard-padding handling, actual audible boundaries, output quality, exact source marker preservation, or a usable Direct storage/Transformer route. Those remain NOT_RUN; Direct is unissued and rejected before IO.
