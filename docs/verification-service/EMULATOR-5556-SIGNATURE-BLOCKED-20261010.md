# Emulator 5556 preflight: signature gate blocked

Received both explicit user approvals: start/test emulator-5556 and Debug-only fixed HTTP loopback/ADB reverse. Approval is no longer pending. The current blocker is original signing material.

DeviceA12 was started on port5556 with cold boot, no snapshot load/save, no wipe and no audio. Actual Android release12/API31 and existing App versionCode2/UID10146 were confirmed. Emulator5554 was not operated. No microphone, recording, private picture/video, permission grant, uninstall or clear was performed.

Prepared Debug App versionCode3 and an instrumentation APK containing the same frozen 100 authored HTML fixtures and manifest hashes. No fixture media binary is packaged. Android minimum remains API31. The existing Debug keystore was created in September and was not modified or newly generated.

**Installation was stopped before invoking adb install:**

| Certificate SHA256 | Value |
|---|---|
| Installed code2 | `18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5` |
| Existing local Debug key/code3 | `5e841a04968f3f790f6996ca5e286d8c2734338542486909f9a136717dde808f` |

The installed App remains code2 with the same UID, first-install/update timestamps and APK path. Preferences and database fingerprint reports match before/after; no media content was read to check preservation. No test APK was installed. No reverse/forward mapping was created, so no existing mapping was removed. The original signing key or an APK with versionCode>2 signed by the installed certificate is required. Do not send a key password in chat. No alternative package, uninstall, signature bypass or instrumentation workaround was used.

Debug source preparation: default-off per-process consent, exact http://127.0.0.1:8765/v1/verify, compiled owned fixture URL scope only, bounded protocol codec, no credentials/cookies/redirects, cancellation and cleanup. The strict production endpoint/client policy and main Manifest security bytes are unchanged. Debug adapter/fixture Activity are absent from actual compiled Release classes. Production registration does not automatically enable the Debug adapter.

B source preparation: fixed DOM observation series, at most16 observations/800ms, within the request deadline with100ms cleanup reserve; no downloaded selector or script interpreter. Existing synthetic origin, network/file/content/permission/download protections and conservative quarantine of unproven renderer cleanup remain. This compiles but **has not run on Android**. Prepared instrumentation covers the same100 bodies, actual device-local HTTP/Jsoup/WebView, negative/unknown abstention, timeout/cancel/concurrency, active Activity recreation cancellation, source-switch stale-publication fences, and temporary A-to-B fallback. These are prepared scenarios, not passes.

The temporary owned fixture server was stopped and its constructor admission revoked. The standard computer service was restored on127.0.0.1:8765 with zero admitted targets; a strict bounded HTTP probe returned UNSUPPORTED/NONE. Public sources remain zero.

Build preparation encountered missing official Windows AAPT2 9.4.0-15978811. It was fetched from the official Google Maven endpoint, matched Google's published SHA1, has local SHA256 receipts, and preserves its full NOTICE. It is Apache2/internal build tooling, outside the APK/Git binaries, with no new explicit agreement. The exact same-version local executable is supplied using the supported android.aapt2FromMavenOverride property; dependency verification remains strict. Debug compilation/JVM configurations add only already-reviewed OkHttp4.12.0, Okio3.6.0 and Jsoup1.21.2 lock memberships. Actual Android runtime remains the77 admitted artifacts.

Pass: Debug App/test APK assembly; Debug/Release Kotlin compile;320 scoped JVM tests (249 discovery,44 client,24 local,3 new Debug gates), zero failures/errors/skips; source/notice/ABI guards; Debug class absence in Release; default-zero service probe. The initial class-boundary script used an obsolete output directory; corrected checks use AGP9's actual built_in_kotlinc directories.

Fail: full existing App JVM suite on Windows:1206 tests,41 failures, zero skips/errors. Most failures involve filesystem identity, directory sync, symlink privileges and ownership-release behavior; one configuration-read test also failed. Full detailed symbolic failures are preserved. These have not been fixed, escalated, bypassed or counted as passes. No Vault or interpreter review classes were present; suspended reviews were not resumed. A system Get-NetTCPConnection diagnostic was denied in the restricted execution environment and was not retried by another method.

NOT_RUN: installation, new Android B/A calls, Android lifecycle/resource/fallback acceptance and same-device100 comparison. Emulator CPU/RAM/network/time are NOT_MEASURED. Phone performance/energy are NOT_MEASURED. Prior Windows host45 A page-evidence results are historical, not emulator results. Media identity/extraction/playback/rights remain unverified.

Evidence is under artifacts/local-windows-20261009: emulator-installation-blocked.json, emulator-code2-signer.txt, emulator-final-code3-signer.txt, emulator-build-boundaries.json, emulator-scoped-jvm-summary.json, emulator-full-app-jvm-failures.json and emulator-final-test-apk-build.log. Local APK binaries remain in app/build/outputs/apk, excluded from Git. Official build-tool binary is excluded; its receipt/POM/published checksum/NOTICE are retained for recovery.
