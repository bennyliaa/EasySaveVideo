# M6-A commands and evidence interpretation

Use `scripts/gradle-workspace.sh`, the approved existing toolchain/cache, `--offline` and `--dependency-verification=strict`. The integration owner serializes all Gradle invocation. No assemble/bundle/package/device task was invoked.

1. `--rerun-tasks :core-model:test :core-domain:test :ui-theme:test :discovery:test :commons-provider:test :save:test :recording:test :library:test :hls:test :app:testDebugUnitTest :app:compileDebugKotlin :app:compileReleaseKotlin :app:lintDebug :app:lintRelease :app:runtimeInventory :app:verifyRuntimeAdmission :app:mergeDebugAssets :app:mergeReleaseAssets`.
2. After final fixes, repeat the same aggregate without write/update flags. Check all saved lockfiles and verification metadata hashes, exact external runtime artifacts, both lint XMLs and actual JUnit XML counts. Existing warnings are not silently suppressed.
3. With `ESV_EVIDENCE_DIR=docs/m6a/evidence`, run `check-boundaries.py`, `check-stream-admission.py`, `check-recording-admission.py`, `check-library-admission.py`, `check-tink-admission.py` and `check-editor-admission.py`.
4. Run `test-discovery-sqlite.py`, `verify-library-sqlite.py`, `verify-export-sqlite.py`, `verify-editor-sqlite.py` and `node test-save-detector.mjs`. SQLite tests execute production-extracted SQL against synthetic host databases; they do not execute Android SQLiteOpenHelper or the entire Kotlin storage adapter.
5. Run `app/src/testSupport/hls-eof-compat/run.sh`, `hls-mux-conservation/run.sh` and `local-hls-reader/run.sh`, with the approved Android compile stubs and cached Kotlin compiler. These execute pinned bytecode over synthetic host fixtures, not Android/native playback or real media downloads.
6. Run `app/src/testSupport/editor-muxer-release/run.sh` for the exact admitted Mp4Muxer cancellation/finalization case. This uses synthetic sample payloads and host value shims, not a playable-codec/GPU proof.
7. Preserve the independent review source hashes, reports and host repros, including the failed WIP timeline/GL-order examples and corrected planning/native policies. The review archive is supplemental evidence, not new app runtime code.
8. Freeze the final production/tests/scripts manifest and review the exact snapshot. Build full Git bundle and full tracked source archive, cold-clone/fsck/HEAD/file-compare, and run the strict aggregate and host regressions again against the restored source with the approved external caches. Record exact archive checksums and restored snapshot in a separate recovery receipt.

Initial WIP failures and review findings remain visible in their logs. The later proof never retroactively turns an initial failure into a pass. Device/codec/GPU/visual quality acceptance is NOT_RUN throughout.
