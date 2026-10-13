# Independent static review: ESV debug candidate

Reviewed 2026-10-13 UTC. Scope: read-only source, merged APK metadata, class/string inventory, and packaging checks. No Gradle, installation, emulator, device, remote service, or network-runtime operation was performed by this reviewer. Project inputs and signing-key content were not edited or read.

## Result

The inspected artifact is consistent with the expected existing debug configuration. No unexplained app-owned hardcoded service endpoint, newly activated Vault/dynamic-rule/billing implementation, or unexpected bundled test runner was identified in this bounded inspection. This is a debug packaging review, not security clearance, functional acceptance, a release build, or approval for distribution or device installation.

## Exact artifact identity

- Source-relative artifact: `app/build/outputs/apk/debug/app-debug.apk`
- SHA-256: `b161a25abf9ed09aa834886cf2be4a8812248e9fe2b4c677dcbec947a1d170da`
- Size: 45,988,354 bytes
- Application ID: `com.easysavevideo.app`
- Version code/name: `8` / `0.8.0-ui-dev`
- Minimum SDK 31; target/compile SDK 36
- `android:debuggable=true`; DEX `BuildConfig.BUILD_TYPE=debug`
- `apksigner verify --verbose --print-certs` exits successfully: v2 signature verified; one signer; v1/v3/v3.1/v4/SourceStamp not present or verified. V2 is compatible with the declared API31 minimum.
- Certificate SHA-256: `18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5`, exactly matching the supplied expected certificate. This does not independently establish the currently installed emulator package's identity or upgrade eligibility.
- DEX constants independently confirm `COMMONS_CONTACT=""`, `COMMONS_ENABLED=false`, and `DISTRIBUTION_CHANNEL="unconfigured"`. `BuildConfig.DEBUG` is initialized by `Boolean.parseBoolean("true")`; its pre-initialization static encoded default of false is not its runtime value.

## Expected debug-only functionality

1. `FakeEntitlementSource` and the development entitlement panel are in `app/src/debug/.../entitlements/BuildEntitlements.kt`. Grants begin UNKNOWN; presets are explicit, in-memory test choices. The panel explicitly labels itself debug testing. The release counterpart uses the store adapter and has a no-op panel. The debug APK necessarily contains the Fake class; this is expected and prevents describing this artifact as a production release.
2. Synthetic discovery (`DemoVideoProvider`, `https://example.invalid/esv-demo/`) and power scenarios are debug-source-set features. Demo search returns in-memory synthetic rows; it is opt-in. Release source sets disable synthetic selection.
3. `OwnedFixtureActivity` is declared only in the debug manifest, is packaged with `exported=false`, and only creates an empty FrameLayout with cancellation on destruction. The APK manifest has no instrumentation declaration and the APK class inventory does not contain `OwnedVerificationInstrumentation`.
4. `DebugLoopbackVerificationEngine` is expected debug code. It uses only `http://127.0.0.1:8765/v1/verify`, rejects non-admitted fixture candidate strings, requires process consent and request remote consent, and defaults to consent=false. Its candidate allowlist is capped at 101 and restricted to the owned `fixture.example.org` fixture pattern. Redirects, cookies, proxy use, and automatic connection retries are disabled; response reads are bounded. Construction references are in JVM/instrumentation tests, not the normal app composition. This is a local test client, not an embedded Scrapling/Python service or a TLS security claim.

## Network endpoint accounting

- The app-owned public literal service is Commons (`https://commons.wikimedia.org/w/api.php`) with its attribution/terms link. It is disabled in this exact APK by the DEX constants above and source registration also requires maintenance availability.
- SearxNG uses explicit user-configured endpoints. Empty configuration has three disabled slots; no hardcoded public instance was identified. Existing persisted user configuration can still affect an upgrade install.
- Optional remote verification is session-only, starts with no endpoint and dynamic=false, and requires acknowledgement when enabled. The compiled local source catalog defaults empty. User-configured remote verification is distinct from the debug localhost fixture verifier.
- `https://esv-verification.invalid/` is the isolated HTML renderer base URL; demo and fixture domains are synthetic labels. Generic HTTP/HTTPS prefix strings and localhost origin construction are policies, not a newly configured external service.
- `assets/browser-fixture.html` includes a manual `https://example.com` navigation link and a relative `fixture.mp4` source. The fixture HTML contains no credential. No `fixture.mp4` is packaged and the asset alone proves no playback.
- Additional DEX URL strings include dependency documentation links, XML/DRM namespace identifiers, and dummy/parser values. A string's presence does not establish an active network path. No traffic capture or runtime endpoint observation was performed.

## Disabled and constrained features

- Vault remains absent from real platform-capability sets and has no app implementation. UI still states it is unavailable; ordinary app-private media is not encrypted Vault storage. `FeatureGate` checks platform availability before entitlement, so granting Fake entitlements does not add an unavailable platform implementation. The free Vault entitlement policy alone is not capability activation.
- Tink is a bundled admitted dependency, not Vault implementation or security acceptance. This review does not approve Tink key management, including any cleartext-fallback behavior.
- No dynamic extraction-rule executor, dynamic code loader, or new rule update endpoint was identified in inspected app/core/discovery wiring. Existing provider-maintenance configuration has empty independent roots and no production update endpoint. This does not remove or newly admit the pre-existing bounded provider-maintenance implementation.
- Distribution channels all use `UnconfiguredStoreAdapter`; no billing SDK class was found in the class-name inventory. Fake grants do not implement payments.
- No classes matching NewPipe, Captord, Scrapling, billingclient, or Vault implementation names were found among 27,192 class definitions. This is a name/inventory check only, not proof about all transitive code provenance or semantic equivalence.

## Manifest and payload observations

- App-owned exported entry point: launcher `MainActivity`. `OwnedFixtureActivity`, library share provider, and recording foreground service are not exported. The library provider permits explicit URI grants by design.
- Additional exported receiver: AndroidX `ProfileInstallReceiver`, protected by `android.permission.DUMP`. Manifest merge provenance traces this to the existing `androidx.profileinstaller:profileinstaller:1.4.0` dependency.
- Permission set: INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION, RECORD_AUDIO, POST_NOTIFICATIONS, ACCESS_NETWORK_STATE, WAKE_LOCK, and app-specific signature `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. The last three derive from existing Media3/Core dependencies in the merge report. No camera, contacts, location, broad external-storage, or package-install permission is declared.
- Backup is disabled; full backup is false; extraction rules exclude ordinary app domains for cloud backup and device transfer.
- Important existing caveat: `android:usesCleartextTraffic=true` is in the main manifest and the packaged APK, not just the debug overlay. The app-wide Android policy therefore does not restrict cleartext allowance to localhost. Browser navigation supports HTTP. Individual stricter transport policies do not turn this global setting into a localhost-only policy. No hardening change was made.
- WebView debugging is explicitly disabled by the current browser adapter, although the whole artifact remains an Android-debuggable build.
- Archive inspection found three app assets (browser fixture, SkinKit license, third-party notices), each byte-equal to the source asset, and four `libandroidx.graphics.path.so` ABI variants. No APK/AAB, keystore/key-container, Python script/runtime, or other nested application archive appeared in the inspected archive-entry names.
- A limited byte screen across all uncompressed entries for common PEM private-key, GitHub PAT, AWS access-key, Stripe live-key, and OpenAI-style key signatures yielded zero matches. Source keyword hits inspected were validation/documentation/UI text, not an embedded credential. This screen cannot rule out obfuscated secrets, credentials with other formats, personal data, dependency vulnerabilities, or undiscovered exfiltration behavior.

## Boundaries and next acceptance steps

- No release artifact was built or reviewed; release separation conclusions here are based on source sets only.
- No install, upgrade, data migration, UI rendering, network request, recording, saving, filesystem recovery, native media behavior, or device permission flow was tested by this review.
- No emulator state or installed version/signature was read. A data-preserving install still needs the build owner to verify the allowed emulator target and compatible installed package state. Do not infer increasing versionCode from this report.
- No independent fresh dependency admission audit was performed. The build's existing strict hash/runtime equality gate and its execution results belong to the build owner's evidence.
- The source-integrity check before/after building belongs to the build owner. This reviewer only wrote evidence outside the source tree.

## Evidence files alongside this report

`independent-apksigner.txt`, `independent-badging.txt`, `independent-manifest.txt`, `independent-dex-buildconfig-disassembly.txt`, `independent-apk-entry-inventory.json`, `independent-apk-classes.txt`, `independent-dex-url-strings.txt`, `runtime-url-review.txt`, `independent-assets.json`, `independent-limited-secret-signature-screen.json`.

## Small evidence addendum

Final packaging-evidence review, 2026-10-13 UTC: **READY FOR LOCAL CANDIDATE FREEZE; NO UPLOAD, NO INSTALL, NOT RELEASE.** This closes the small-document/inventory reconciliation for the existing artifact only. It does not authorize or perform publication, device work, subsequent UI implementation, or security acceptance.

Reviewed `docs/m11a/DEBUG_CANDIDATE_STATIC_ACCEPTANCE_20261013.md` and `artifacts/packaging/debug-candidate-20261013/{VERIFICATION.json,SIMPLE_SBOM.json,APK_SCAN.json,APK_CONTENTS.json,SOURCE_INPUTS.json}`. Their packaging-only pass wording, debug-only features, zero-provider/default-off configuration, cleartext caveat, unchanged versionCode, local-only artifact status, and explicit NOT_RUN/release limits are consistent with the checks above. The document's original-UI follow-on items are proposals; this addendum does not constitute implementation, visual/PPT acceptance, or a new product-scope decision.

Evidence reconciliation:

- `SOURCE_INPUTS.json` has 1,399 entries. All 1,399 current files independently match their recorded byte sizes and SHA-256 hashes. No packaging-time input changes were found.
- The two manifest hashes have distinct, now explicit meanings. `03b679ad32a5ee151d5334db850e44c7e9604231566caa722bbca6251984b6b0` is the outer 32-file Save notice publication whitelist `esv-save-runtime-notice-frozen-20261013/MANIFEST.json`. `f4e03c1d1b911c8a1610477ba372058d0c5c84fc9a6c13a511986803efcecb00` is the inner 1,399-input `INPUT_MANIFEST.json`, byte-equal to packaging `SOURCE_INPUTS.json`. Both were independently hashed; an initial ambiguous-manifest comparison is resolved by these roles.
- The publisher's local receipt records source head `c794ef84344d638f20966a1cd05bb5b340ade7e0`, parent `ef5d6fc35328e8881db26df3982c0e675c37dd4d`, 32/32 exact blob readbacks, verified source evidence ZIP, private repository and unchanged main. This review checked that recorded receipt and saved published tree, not a new live GitHub response.
- Exact Git-blob comparison against that saved tree matched 1,398 of 1,399 local inputs. The sole pre-existing difference is `third_party/current-runtime/poms/org.jetbrains__annotations__23.0.0.pom`: local 1,354 bytes contain 30 CRLF endings; saved published blob has 1,324 bytes with LF. Replacing only CRLF with LF produces exactly published Git blob `f6b1377e504ba71cbc39afcb45b9ffe4ceb97ac6`. This file matches the local frozen SHA-256 manifest and was not changed for packaging. The source-head claim must not be interpreted as raw-byte equality of every local file with Git.
- Debug and release resolved inventories each have 103 raw rows, 102 unique artifacts: 90 admitted external artifacts plus 12 project-module artifacts. The sole duplicate is byte-identical `androidx.core:core:1.13.1` / `core-1.13.1.aar`, SHA-256 `2c27de199535675005553066597a4b20fa1eea7c228ab4ef6b32b5fe39ca1f59`. All 206 referenced artifact-row byte hashes independently match. Both 90-item external sets equal the admission file exactly. A release runtime inventory is not a release APK.
- ZIP CRC passes. Every one of the 205 APK members matches `APK_CONTENTS.json` exactly; all 29 DEX lengths, hashes and string counts match `APK_SCAN.json`. The actual APK remains SHA-256 `b161a25abf9ed09aa834886cf2be4a8812248e9fe2b4c677dcbec947a1d170da`.
- Independently repeated the five documented common credential-signature families (PEM private key, AWS access key ID, GitHub classic/fine-grained token and Google API key) across uncompressed APK entries: zero matching signatures. This has the same narrow limits as the original screen and is not absence-of-secrets proof.
- The actual build log confirms `BUILD SUCCESSFUL in 54s`, 65 actionable tasks, 21 executed and 44 up-to-date. The report distinguishes this packaging run's reused compilation outputs from the earlier 1,905-test/compile/lint evidence; it does not claim these were rerun during packaging.

Reconciliation details are saved in `independent-final-evidence-reconciliation.json`. No production input, project/wrapper/signing setting, APK, or publication state was modified by this reviewer. The reviewed APK may now be frozen locally with its exact identity and these qualifications; later small-evidence grouping must keep the binary excluded.
