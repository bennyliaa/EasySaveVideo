# Owned OUTPUT pinned host

Prepared externally for coordinator execution. This overlay has not been compiled or run by its author. No source checkout, Gradle output, device or GitHub state was changed while preparing it.

## Coverage and boundaries

The runner declares exactly 10 cases:

1. Exact admitted `Mp4Muxer`, `StatsDataSource`, `ProgressiveMediaExtractor` and `MatroskaExtractor` JAR CodeSources, production owner/session/bounded-muxer compiled-directory CodeSources, and original fixture test-directory CodeSource.
2. Normal actual muxing through the registered Direct owner, real core admission/Scope, existing paired input and actual session execution. Before output creation, drive the same pair's actual StatsDataSource/progressive extractor through sniff, peek reset, initial zero seek, complete loading, real EOF, logical source close and completed transport facts. Then use the same still-entered session/attempt for the muxer and retained-file sealing. Verify AVC sample conservation with the existing strict canonical MP4 scanner, original RAW/OUTPUT handles, independent hashes on those same handles, and positive physical closes.
3. The same flow with actual 127-byte maximum physical writes and 31-byte maximum physical reads. Require explicit short-progress continuations on OUTPUT writes and separately on RAW/OUTPUT hash reads; configuring a limit alone cannot pass. The short-I/O MP4 must also equal the preceding successfully completed normal MP4 byte for byte, with identical total length and SHA-256; canonical equivalence alone is insufficient.
4. Cancellation followed by actual successfully returning final-table writes and force. Original cancellation remains primary, release is CONFIRMED, and no seal/proof succeeds.
5. `abandon` freezes the exact session cleanup deadline synchronously. Repeated `postCancel` and later muxer finalization reuse it. This case does not claim an expired-deadline syscall test; the storage-focused suite covers that rejection separately.
6. A caller returns CANCELLED/UNCONFIRMED while the original cleanup worker remains blocked in its one physical OUTPUT close. The same worker can later drain the exact anchors, but the caller result and lane quarantine remain sticky.
7. A confirmed actual OUTPUT fsync hook failure survives in the exception chain, marks content failed, still closes/releases positively, and never becomes resource sealing or false FD uncertainty.
8. Normal Editor regression through the actual `EditorTransformerTarget`, `OwnedFileOutputStream`, real host FileChannel/FD, actual bounded muxer and actual session. Logical FD release occurs before the exact native retention retires in `finish`.
9. The same Editor adapter flow cancelled before real final-table/extent mutations. Final bytes scan canonically, but the actual session result remains cancellation; exact native retention still retires only during real cleanup.
10. A real queued shared-handler barrier, every existing shim HandlerThread drain, and no uncaught background error.

AVC input uses the original I_PCM/P-skip syntax fixture, its existing rounded presentation timestamps, and explicit SDR BT.709 limited-range metadata. The two normal/short happy cases now drive actual StatsDataSource and the pair's production ProgressiveMediaExtractor to EOF on the same registered attempt/session before output muxing. They assert exact loaded extent, three source sample callbacks, matching first/last source timestamps, the same cached owned source, retained original RAW descriptor, and no extra physical open/close while loading. The short case also limits physical input loading reads to 31 bytes. Reflection is restricted to the existing pair's two private factory methods and opaque route, following the paired-input host harness; no production seam, replacement DataSource or extractor is introduced. The manually repeated same-source `session.retain` is idempotent and does not release or replace the loading pin. The other Direct cases retain the prior probe/assembly/manual-retention setup. The separate 37-case paired-input host remains the broader loading/receipt/adversarial evidence. Opaque VP8 packets are discarded after parsing and are not decoded or converted into the separately supplied AVC fixture. Canonical scans and byte/hash checks are structural transport/resource evidence, not native decoding or full media verification.

For the two whole-file equality cases only, the host sets fixed creation/modification timestamps with `Mp4TimestampData(0, 0)` using the public `addMetadataEntry` method on the unique actual `Mp4Muxer` retained by the actual session. This is test clock control before any samples, with an asserted unique instance and no reflection or emitted-byte rewriting. The admitted upstream `media3-muxer-1.11.1-sources.jar`, `androidx/media3/muxer/MetadataCollector.java` constructor, otherwise derives these timestamps from `System.currentTimeMillis()`, so ordinary consecutive runs crossing a second cannot reliably compare whole-file bytes. Production `TransformerBoundedMuxer.addMetadataEntry` filtering is unchanged and remains covered by its separate original contract; these clock-controlled cases do not test that filter.

Direct uses deterministic in-memory syscalls and the existing fake ledger. Editor uses real local temporary FDs but fixture-issued capabilities and only limited host file-key/parent checks. Neither proves Android filesystem/SQLite acceptance, real Editor storage issuance, native codec/GL release, Direct run issuance, successful conversion, independent full-decode verification, adoption, commit or publication. Every Direct case keeps the durable row WRITING and conversion/normalization/output/proof fields absent. `directIssued` and `nativeDecodeTested` remain false.

## Coordinator run contract

Use the final audited source snapshot containing `ownedTarget(binding): TransformerOwnedTarget`, `sealResources(): Pair<NormalizationBoundFile, NormalizationBoundFile>`, exact session writer retention and the existing unchanged Editor adapter. Compile its production and unit-test classes first using the coordinator's normal build. The runner compiles only the external host sources and existing Android/fixture shims; it never invokes Gradle.

Set the same admitted JAVA_HOME, ANDROID_HOME, GRADLE_USER_HOME and project classpath used by paired-host. ESV_MAIN_CLASSES and ESV_TEST_CLASSES must each be one compiled directory corresponding to the source manifest. CodeSource checks establish the chosen loaded directories, not that a compiler produced them from those sources; matching builds are the coordinator's responsibility.

Example (after the audited source build; replace only the manifest and fresh output destination):

```sh
SOURCE=/workspace/shared/esv-cloud-handoff-20fddc9/source
HOST=/workspace/shared/esv-webm-basic-conversion-20261014/output-muxer-host-overlay/app/src/testSupport/webm-owned-output-host
export ESV_MAIN_CLASSES="$SOURCE/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
export ESV_TEST_CLASSES="$SOURCE/app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"
export ESV_PROJECT_CLASSPATH="$(cat /workspace/shared/esv-webm-basic-conversion-20261014/paired-input-checkpoint/host-project-classpath.txt)"
export ESV_SOURCE_MANIFEST=/absolute/path/to/final-audited-source.sha256
(cd "$HOST" && sha256sum --check HOST_INPUTS.sha256)
bash "$HOST/run.sh" "$SOURCE" /absolute/path/to/fresh-external-host-output
```

ESV_SOURCE_MANIFEST must be absolute, with its entry paths resolvable from SOURCE. Preserve the paired-host classpath filtering that excludes transformed Media3 classes. Existing `webm-paired-host/ADMITTED_ARTIFACTS.sha256` pins every reused AAR and Guava artifact. Runtime artifacts are extracted solely from those admitted local files, without network access or new dependencies.

The runner verifies the frozen host inputs and source manifest before execution and again on exit, including failed runs; mutation gives exit 90. It rejects an output path inside the source checkout or host-input tree, and rejects existing classes/jars output directories to prevent stale host classes. Results are `results.log`, `results.json`, and the two real Editor output MP4 files under the external output directory. A successful future execution must report exactly 10 tests, 10 passed, 0 failures. Do not infer success from this prepared overlay or its manifest.
