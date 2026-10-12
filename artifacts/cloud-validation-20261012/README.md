# ESV cloud validation — 2026-10-12

Base: `20fddc9c33b399ab75f8b8eee0f21e41e6f5b06f` plus the two scoped feature slices.

- 544 targeted/adjacent JVM tests passed, zero failures/errors/skips: app 468, core-model 3, core-domain 44, discovery policy/coordinator 29.
- Debug/release Kotlin compilation passed. The resource-only final revision reused unchanged compiled Kotlin outputs.
- Final debug/release lint: zero errors, 60 warnings and 8 hints each. Warnings remain; they are not all claimed to be pre-existing.
- Exact runtime admission remained enabled: 90 approved artifacts verified for both variants.
- No APK/AAB, emulator, device installation, instrumentation or visual acceptance was performed.

The final app tests were explicitly rerun after the last English resource change. The prior incremental invocation considered tests up-to-date because their direct XML file reads are not declared Gradle task inputs. `gradlew :app:testDebugUnitTest --rerun` was used, with the exact filters below. No dependency verification bypass, hash update or version change occurred.

Filters: `com.easysavevideo.app.licenses.*`, `com.easysavevideo.app.discovery.*`, `com.easysavevideo.app.diagnostics.InitializationProbeTest`, `com.easysavevideo.app.editor.EditorInitializationTest`, `com.easysavevideo.app.editor.storage.*`, `com.easysavevideo.app.library.share.*`.

Additional core policy command: `gradlew :discovery:test --tests '*DiscoveryPowerPolicyTest' --tests '*DiscoveryPowerCoordinatorTest'`.

Full feature command adds `:app:compileDebugKotlin :app:compileReleaseKotlin :app:lintDebug :app:lintRelease` to the app filtered test command. Final resource/lint and forced-test runs used `--offline`.

Evidence: SUMMARY.json, sanitized JUnit XML, lint XML with project paths replaced, selected task-only logs, and final app source/resource hashes. JUnit hostnames and output streams were omitted; no test failures were removed (all counts are zero).

Build portability note: one pre-existing vendored jsr305 POM is Git-normalized LF while its approved checksum describes CRLF Windows checkout bytes. The cloud checkout was restored to that exact approved checksum without editing verification metadata. That local byte restoration is excluded from the feature upload. A later explicit cross-platform checkout policy would avoid repeating this portability adjustment.
