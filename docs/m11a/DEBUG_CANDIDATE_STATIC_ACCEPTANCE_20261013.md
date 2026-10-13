# ESV Debug candidate: build and static integrity

Status: **BUILD/STATIC PASS, LOCAL-ONLY CANDIDATE, NO_DEVICE, NOT_RELEASE**.

Published source head: `c794ef84344d638f20966a1cd05bb5b340ade7e0` (32 published blobs and source evidence archive verified by the publisher).

This candidate binds the 1,399 input hashes in
`artifacts/packaging/debug-candidate-20261013/SOURCE_INPUTS.json`: production baseline
`b0c5647e6759dd212423647742e4923c8d0dce93` plus the exact Save runtime notice outer publication freeze
`esv-save-runtime-notice-frozen-20261013/MANIFEST.json`, SHA-256
`03b679ad32a5ee151d5334db850e44c7e9604231566caa722bbca6251984b6b0`.
That outer 32-file whitelist manifest is distinct from the 1,399-input source manifest,
whose SHA-256 is `f4e03c1d1b911c8a1610477ba372058d0c5c84fc9a6c13a511986803efcecb00`.
No source, project configuration, version, dependencies, signing configuration or system settings
were changed for packaging. This APK is frozen before any subsequent reading-anchor work.


Remote-tree comparison caveat: 1,398 of the 1,399 local input files match the published Git blob bytes exactly. The existing `third_party/current-runtime/poms/org.jetbrains__annotations__23.0.0.pom` is 1,354 bytes locally with 30 CRLF pairs versus 1,324 LF bytes in the published tree; CRLF→LF alone reproduces its published Git blob. The POM was not changed by this build. All 1,399 local before/after hashes match; no runtime artifact or production-code difference was found. This candidate therefore records exact local build inputs rather than claiming every local metadata byte equals the remote tree.

## Actual package

| Field | Verified value |
|---|---|
| Package | com.easysavevideo.app |
| Version | code 8 / 0.8.0-ui-dev, unchanged |
| Minimum / target API | 31 / 36 |
| Debuggable | true |
| APK bytes | 45,988,354 |
| APK SHA-256 | b161a25abf9ed09aa834886cf2be4a8812248e9fe2b4c677dcbec947a1d170da |
| Signer certificate SHA-256 | 18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5 |
| Signature | APK v2 verified, one existing Android Debug signer |
| Install / launch / device | NOT_RUN |
| Upload | NOT_DONE; APK excluded from the small-evidence publication whitelist |

`assembleDebug`, `packageDebug`, `validateSigningDebug`, `signingReport`, the actual project task
`verifyRuntimeAdmission`, and `runtimeInventory` executed successfully. Terminal exit was 0;
54 seconds, 65 actionable tasks, 21 executed and 44 up-to-date dependencies. This packaging run
reused the previously verified Kotlin classes; it does not claim to rerun their compilation/tests.
The immediately preceding unchanged-input Save notice run passed 1,905 App tests and both
compilers/lints, as separately recorded in SAVE_RUNTIME_NOTICE_DISPLAY_20261013.md.

The original toolchain user home had no debug keystore. Read-only signingReport reported that
absence; it was not a successful signing result. No APK was assembled against that missing-key
configuration. After explicit authorization, the same environment initialization and Gradle flock
were used with only a per-process ANDROID_USER_HOME pointing to the existing test-key directory.
The existing key was reused, not created, copied, printed or uploaded. Project and wrapper files
were not modified. Actual APK certificate validation matched the expected previous test signer.

## Static checks and limits

- APK ZIP CRC passes; all 205 members have exact size/SHA-256 inventory. APK contains 29 DEX
  files. Public signing certificate and package metadata were inspected without reading key material.
- Simple SBOM records each resolved coordinate, filename and SHA-256: 90 unique admitted external
  artifacts and 12 project-module artifacts per variant. The raw 103-row inventory includes one
  repeated, byte-identical androidx.core:core:1.13.1 artifact; there are 102 unique artifacts.
  This is an exact inventory, not vulnerability clearance or full CycloneDX/SPDX compliance.
- Debug BuildConfig is confirmed: Commons contact empty, Commons disabled, distribution unconfigured.
  Expected debug-only classes include the fake entitlement/test controls, opt-in synthetic discovery,
  non-exported OwnedFixtureActivity and the fixed owned-fixture loopback verifier. Instrumentation
  test code is not packaged in this app APK.
- The localhost verifier is consent-gated and constrained to owned fixture candidates. Static URL
  references include this expected localhost endpoint, disabled Commons code and dependency/help/
  namespace constants. Other configured service endpoints remain configuration-owned. No unexplained
  app-owned hardcoded service endpoint was found in the reviewed source/DEX scope. A string inventory
  does not prove runtime reachability or enumerate every possible network destination.
- No listed high-confidence PEM-private-key/AWS/GitHub/Google credential pattern matched the APK;
  no keystore/private-key/.env-named archive member was found. These limited checks are not proof
  that every possible secret is absent and are not a security release approval.
- Vault remains absent from actual platform capabilities and has no activated implementation;
  dynamic rules remain unimplemented. Admitted Tink library bytes do not constitute a Vault feature.
- **Existing global usesCleartextTraffic=true is retained**, not narrowed to debug or localhost.
  This is a documented existing configuration caveat, not a new packaging change. The launcher is
  exported as expected; the library share provider, recording service and owned fixture activity
  are non-exported. The dependency ProfileInstallReceiver is exported with android.permission.DUMP.
- Existing warnings include missing platform-tools (no device operation requested), an unstripped
  libandroidx.graphics.path.so packaged unchanged, and existing analytics/Gradle deprecation warnings.

No installation, emulator/device launch, real WebView/media, runtime permissions, performance,
locale/layout, accessibility or PPT comparison was performed. No release APK or release signing
was produced. Unchanged versionCode 8 must not be mistaken for a new device-upgrade acceptance.
Prior OPPO/Windows failures and other NOT_RUN items remain open.

## Next original-UI requirements, without repeating completed locale work

1. **Discovery reading anchor: smallest cloud implementation candidate.** Original 01 §2 and
   02 §4 require retaining a stable-ID list anchor. DiscoveryScreen.kt:101 creates a restorable
   LazyGridState, but its page-keyed LaunchedEffect at :130 always scrolls to item 0 when entering
   Composition. This can overwrite same-page return/restoration. The existing controller persists
   pageIndex, not a stable result anchor/offset. A bounded fix should distinguish same-page/query
   restoration from a genuinely new page/query, preserve stable result identity and offset, and
   never trigger search, getNext demand or history mutations. This is source evidence; device
   navigation/rotation/column-change restoration is still NOT_RUN.
2. **Real Discovery thumbnail display.** Original 01 §4.1/C-03 and 03 §6 require thumbnails;
   current card :365–367 always draws a theme icon. The existing thumbnail capability permits only
   owned in-memory RGBA fixtures. A fixture-to-card integration can be bounded, but production
   image fetching/reading needs its separate capability/admission review. Do not add a generic
   downloader/cache or treat arbitrary URLs as admitted. Actual rendering/PPT acceptance is separate.
3. **OpenOriginForLogin.** Original 03 §8 and 05 §8 require original-site authentication recovery.
   Current task command intentionally carries no origin and only returns to Browser. A future
   owned task-to-browser-origin correlation needs freshness, lifecycle and privacy review; missing
   or stale correlation must remain unavailable. Login and Resume remain explicit user actions.
   Cloud ownership design/testing is possible; actual WebView/login acceptance needs a device.

History retention, billing, import, Vault/rules and other deferred product decisions remain gates,
not silently resolved by old specification defaults. PPT binary is still unavailable. Browser
microphone consent is not newly labeled a mandatory original-UI feature here: original 00–08's
explicit microphone clauses concern recording.
