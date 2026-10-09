# HLS EOF compatibility integration, work in progress

2026-10-06. Starts from Commons development checkpoint `4f96f5d4fea00eb86353b4df71034ec0663118ce`. Reviewed frozen candidate manifest SHA-256: `bc25c9b295e141c60ac0cf3642b03f9a1076a05f604948d4a17827f2891048be`.

Initial checkpoint installs the same local capability-gated public-API reader in inspection and actual HLS export. It wraps AVC and AAC readers, suppresses intermediate EOF mutation, and finalizes only a verified real pending final VCL AU once. Original source-tail-delimited streams skip finalization. Reopen/range/retry/recreation/cancellation fails closed. Existing release owner, local-only source, rejected codecs, strict transfer budgets, raw sample/mux proofs and post-read hashes remain.

This is unfinished development work. Independent full output sample conservation, actual terminal GOP decode-through-EOS and versioned recovery proof are still being implemented. It is not device-accepted or universally compatible HLS. No APK/AAB, install, device, real media download or publication is authorized by this checkpoint.

## Completed development integration

The initial WIP is preserved in commit `3347333f0188da14736150c93fa79ead8c3ec493`. The guarded sample/tail evidence, independent output and terminal decoder implementation, v3 recovery behavior, review and host verification are now complete at the development tag `m3b-hls-eof-dev-2026-10-06`. See IMPLEMENTATION_AND_ACCEPTANCE.md for the final scope and still-unrun Android acceptance gates.
