# M6-C reproducible verification

Single integration owner; approved existing JDK21/Android36/Kotlin/Gradle cache; strict offline mode. No package, assemble, APK/AAB, device or real permission/capture task.

Aggregate:

`./scripts/gradle-workspace.sh --offline --dependency-verification=strict --rerun-tasks :core-model:test :core-domain:test :ui-theme:test :discovery:test :commons-provider:test :save:test :recording:test :library:test :hls:test :app:testDebugUnitTest :app:compileDebugKotlin :app:compileReleaseKotlin :app:lintDebug :app:lintRelease :app:runtimeInventory :app:verifyRuntimeAdmission :app:mergeDebugAssets :app:mergeReleaseAssets`

With `ESV_EVIDENCE_DIR` pointing to this evidence directory (or an external cold-verification directory), run all eight `scripts/check-*-admission.py`/boundary guards listed in the saved logs: boundaries, stream, recording, library, Tink dependency-only, editor, region recording and capture preview. The region-recording guard remains a regression for proportional/segment behavior; the separate capture-preview guard describes the added authorized-frame route. Static guards are not native execution.

Run production-extracted SQLite harnesses: `test-discovery-sqlite.py`, `verify-library-sqlite.py`, `verify-export-sqlite.py`, `verify-editor-sqlite.py`, `verify-recorded-segments-sqlite.py`, plus `node scripts/test-save-detector.mjs`.

Four pinned standalone host harnesses:
- `app/src/testSupport/hls-eof-compat/run.sh "$PWD" "$PWD/build/m6c-hls-eof"`
- `app/src/testSupport/hls-mux-conservation/run.sh "$PWD" "$PWD/build/m6c-hls-mux"`
- `app/src/testSupport/local-hls-reader/run.sh "$PWD" "$PWD/build/m6c-hls-reader"`
- `app/src/testSupport/editor-muxer-release/run.sh "$PWD/build/m6c-editor-muxer"`

Export the approved absolute `JAVA_HOME`, its `bin` on `PATH`, `JAVA_BIN`, `JAVAC_BIN`, `GRADLE_USER_HOME`, `ANDROID_HOME`, `ANDROID_USER_HOME`, `ANDROID_JAR`, `KOTLIN_CACHE` and build `HOME` for cold restore outside the normal workspace parent. No trust/network/dependency-write changes.

Independent source review runs `evidence/run-independent-host.sh` with an explicit output directory and records source hashes before/after. SDK36 signatures prove pinned compile availability only. Final normal JVM count is read from actual JUnit XML, and lint totals from both XML reports. Bundle cold clone/fsck plus full tracked source restoration/byte-mode comparison and repeated aggregate/host checks are in the external restore receipt and cold-verification archive.
