# Authorized Android compilation / lint checkpoint

User explicitly authorized restoring the five Android checks on this Windows PC. Baseline d9a56e743b01707a6caa536cea1e5b9283ae5100; JBR21.0.10, SDK36/BuildTools36.0.0, Android12/API31 minimum. Both invocations retain --dependency-verification strict, existing official repositories, fixed verification metadata and exact77 runtime gate. No APK build/install, signing, device operation, security setting or new dependency admission.

| Requested task | Initial run | Final run |
| --- | --- | --- |
| :app:verifyRuntimeAdmission | PASS, exact77 executed | PASS, exact77 executed |
| :app:compileDebugKotlin | PASS, executed | PASS, UP-TO-DATE reused |
| :app:compileReleaseKotlin | PASS, executed | PASS, UP-TO-DATE reused |
| :app:lintDebug | FAILED via lintAnalyzeDebug dependency lock mismatch | PASS, executed; 30 warnings, zero errors |
| :app:lintRelease | NOT_RUN | PASS, executed; 30 warnings, zero errors |

Initial Gradle exit1; final exit0. The initial compile warnings concern existing deprecated permission callback APIs. Lint warnings include API/upgrade suggestions, JavaScript setting, static reference, boxed state, usable-space and KTX suggestions. None were silenced or removed, and warnings are not an assertion of device safety or production acceptance.

The initial lint configuration resolved four already-admitted fixed GAVs absent from its lock configuration membership. Fix only app/gradle.lockfile: add debugLintChecksClasspath and releaseLintChecksClasspath for org.jsoup:jsoup:1.21.2, com.squareup.okhttp3:okhttp:4.12.0, com.squareup.okio:okio:3.6.0 and com.squareup.okio:okio-jvm:3.6.0. No GAV/version was added; no --write-locks/verification generation, trusted pattern, version override or runtime graph change. Exact diff checks confirm all other lock memberships unchanged and verification-metadata.xml/settings.gradle.kts/admitted-runtime.tsv byte-identical. Compilation success and resource processing also resolve the previous Windows AAPT2 cache blockage with the reviewed SHA256; no alternate binary was injected.

Evidence: artifacts/android-five-task-validation-20261011 includes both original logs/exit codes, final HTML/SARIF reports and task receipt. Both logs also remain in their unique delivery directories. Lint creates analysis models for tests but no App unit/instrumentation tests were executed. No new JVM regression is needed for a lint-only lock membership change; prior Discovery288/B34 is not presented as rerun. Old Windows115 filesystem test failures remain open/historical.

Environment restoration evidence is reported honestly: the final raw check says False. A separate process-local probe reproduced PowerShell coercing a saved null into an empty string when calling the .NET setter; original absent variables became empty within that temporary shell. The shell ended; there were no User/Machine environment writes, PATH edits or lasting changes to subsequent tool processes. The corrected guide uses [NullString]::Value for a previously absent value; the probe confirms actual absence is restored. Original False evidence is retained, not overwritten or relabelled PASS.

This closes the requested host dependency/Debug+Release Kotlin/lint checks only. Runtime behaviour on Android/API31 phones/tablets, real-source A/B, access rights, true video identity, performance/energy, APK signature/installation and publication remain separate/unmeasured. Production B catalogue remains empty; Commons experiment stays test-only/default-disabled. Vault/M7-D unchanged and suspended. No GitHub push.
