# M6-B reproducible verification commands

Only the integration owner runs Gradle. Use the existing approved workspace toolchain/cache and strict offline dependency verification; no assemble/bundle/package/device task.

Aggregate: `./scripts/gradle-workspace.sh --offline --dependency-verification=strict --rerun-tasks :core-model:test :core-domain:test :ui-theme:test :discovery:test :commons-provider:test :save:test :recording:test :library:test :hls:test :app:testDebugUnitTest :app:compileDebugKotlin :app:compileReleaseKotlin :app:lintDebug :app:lintRelease :app:runtimeInventory :app:verifyRuntimeAdmission :app:mergeDebugAssets :app:mergeReleaseAssets`.

Set `ESV_EVIDENCE_DIR=docs/m6b/evidence` and run the existing check-boundaries, check-stream-admission, check-recording-admission, check-library-admission, check-tink-admission and updated check-editor-admission Python scripts. Run the new region admission guard too. Static guard assertions are source checks, not behavioral or device proof.

Run `scripts/test-discovery-sqlite.py`, `verify-library-sqlite.py`, `verify-export-sqlite.py`, `verify-editor-sqlite.py`, `verify-recorded-segments-sqlite.py`, and `node scripts/test-save-detector.mjs`. SQL harnesses execute extracted production SQL on host SQLite, not Android SQLiteOpenHelper.

Preserve existing HLS EOF, mux-conservation, reader and pinned editor-muxer-release harnesses, with the approved JDK/Android stubs/Kotlin cache explicitly configured. Repeat all aggregate and host checks after cold bundle/source restoration. Count actual JUnit XML and inspect both lint reports; do not call warnings absent.
