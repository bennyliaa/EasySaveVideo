# AndroidX WebKit 1.17.1 exact-candidate evaluation

2026-10-14 UTC. Evaluation only. No production sources, production dependencies, production admission/verification/lock files, SDK/toolchain, Android permissions or devices were changed. Nothing was published. Evaluation files and Gradle caches were written only in the cloud evaluation workspace/shared build cache. The current ESV tree was read into an isolated snapshot while another worker was developing unrelated MOV handling; this is dependency and API compilation evidence, not a full ESV regression pass.

## Decision

**Recommend exact AndroidX WebKit 1.17.1 for the next dependency-admission decision. Technical dependency/compile evaluation passes. Integration remains unapproved/unperformed.** No dependency replacement or SDK/toolchain upgrade is necessary. The existing strict 90-artifact production admission correctly remains unchanged. If integration is authorized, its expected exact runtime set is 91, with one new artifact and no modified old bytes. Preserve both Apache and bundled Chromium BSD notices as described below. Incognito itself remains unimplemented and has substantial runtime/privacy acceptance gates.

The official release table was rechecked on this date: stable 1.17.1 (2026-09-23), while 1.18.0-rc01 is a release candidate. The earlier suggested 1.15.0 is not the preferred current candidate. No older-version fallback is needed on the measured toolchain. [Official release page](https://developer.android.com/jetpack/androidx/releases/webkit#1.17.1).

## Exact bytes and dependency result

Downloaded directly from https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.17.1/ . Local SHA-256:

| File | SHA-256 |
|---|---|
| webkit-1.17.1.aar | 29dc7a9a32614828c97cf85951eb43c9e455157ab411e5e77d2ca4e72da28a36 |
| webkit-1.17.1.pom | b5294d03f04cf806e975afe48a67e70180e6a1e74086e7e62a1fe64c4a636bae |
| webkit-1.17.1.module | fee069430a913a43fa694ad466682f132d21251b62cd03655142ddafe15b52eb |
| webkit-1.17.1-sources.jar | 1fc3abdfcc179717f0a1b0b8634175afe4d84ba5938eba1df75128397663ebea |

AAR size 292,669 bytes; sources 297,508 bytes. Both downloaded hashes and lengths match the hashes/lengths in official module metadata. This validates consistency with the official published metadata, not an independent signature chain. URLs and complete measured hashes are preserved in `official-artifact-urls.json` and `artifact-manifest.json`.

POM and both API/runtime module variants declare the same four direct dependencies:

| Declared minimum coordinate | Selected in isolated ESV snapshot |
|---|---|
| androidx.annotation:annotation:1.8.1 | annotation:1.9.1 metadata → annotation-jvm:1.9.1 bytes |
| androidx.annotation:annotation-experimental:1.3.0 | 1.4.1 |
| androidx.core:core:1.1.0 | 1.13.1 |
| org.jspecify:jspecify:1.0.0 | 1.0.0 |

All four selected families and their transitive bytes are already in ESV's admitted graph. WebKit itself introduces no Kotlin runtime dependency; existing annotation/core transitive Kotlin resolves to the admitted 2.2.10. AndroidX metadata variants, existing constraints and locks matter: do not infer a complete graph from the four POM entries alone.

`debugRuntimeClasspath-evaluation.tsv` and `releaseRuntimeClasspath-evaluation.tsv` contain the complete actual 91-unique-artifact runtime sets (92 resolver rows because one identical artifact occurs twice; equality uses the same set semantics as production admission), filenames, SHA-256 values and resolved file locations. `*-edges.txt` record every resolved dependency/constraint edge, including selected versions; metadata-only platforms/variant redirects can appear without runtime payload. `webkit-reachable-including-constraints.txt` is a reachability aid including constraints, not a list of 84 new required binaries. `graph-diff.json` compares both sets to the preserved original admission: exactly one added AAR above; zero removals/replacements/hash changes. The already admitted 90 artifacts retain their existing license review and notices; this review does not relicense them or reopen unrelated SkinKit/distribution approvals.

The isolated full-graph run retained strict Gradle verification. Its first run correctly rejected the unlisted WebKit module; only the isolated verification XML was then extended using the independently downloaded exact hashes. Production metadata was untouched. `--write-locks` wrote only the isolated snapshot. The production equality gate was neither disabled nor edited.

## Actual compatibility verification

- AAR manifest: minSdk24, no uses-permission, no application component. ESV minSdk31 satisfies it.
- AAR metadata: minCompileSdk33, extension0, minAGP7.2.0, core-library-desugaring not required. ESV compile/target36 and AGP9.4.0 satisfy it.
- All WebKit classes.jar classfile versions are 55 (Java11), compatible with the existing JDK21 compiler/runtime and Java17 target.
- Actual toolchain: Temurin21.0.12.1+1; project Gradle9.6.0; AGP9.4.0; root Kotlin/Compose plugin coordinates2.2.10. Isolated probe matched these plugin declarations and Android compile options, compile36/min31/target36/build-tools36.0.0. Its resulting ProfileProbeKt has classfile major61 (Java17).
- `compile-exact-plugins.log`: forced offline `compileDebugKotlin`, `compileReleaseKotlin`, `checkDebugAarMetadata`, `checkReleaseAarMetadata` succeeded, 14 tasks executed. Probe actually imports/calls Profile, ProfileStore, WebViewCompat.setProfile, both feature constants, profile cookie/storage managers and WebStorageCompat.deleteBrowsingData with executor/callback. It is a compile fixture, not production lifecycle code.
- `exact-graph.log`: isolated ESV runtime graph resolution succeeded for both variants with strict artifact verification, 91 hashes measured independently.
- The first simpler compile fixture represented all admitted coordinates as direct dependencies and introduced two old empty Kotlin jdk7/jdk8 shims via its altered root structure. This was not accepted as the production graph; the full ESV snapshot above corrected the methodology and confirms neither shim enters ESV. Its historical log is retained for audit transparency.
- Nonfatal existing environment warnings about unavailable platform-tools/analytics directory occurred. No platform-tools installation, license acceptance, system permission or environment change was performed. No APK/AAB was assembled, installed, signed or tested on a device. No full-app build or lint/privacy behavior pass is claimed.

## License and closed-source compatibility

The exact POM and AAR license file identify Apache-2.0. Important extra inspection: exact sources and bundled classes also contain org.chromium.support_lib_boundary interfaces/utilities; their own source headers identify Chromium copyright and BSD-style terms. Therefore simply listing the POM license is incomplete. The AAR/sources archives include Apache LICENSE.txt but no separately named NOTICE file and no bundled BSD license text. The canonical Chromium boundary source location and Chromium root BSD three-clause license were checked; the fetched license blob is `2249a28657f86c645f8978b75babc1bd52aca318`. Individual exact-source copyright headers are preserved in `chromium-boundary-attributions.txt`.

Engineering licensing assessment: Apache-2.0 plus BSD three-clause permit proprietary binary distribution without a source-disclosure obligation, subject to their conditions. Include the full Apache license and applicable attribution notices, mark any modified upstream files if modified/distributed, preserve source notices if distributing sources, and retain the Chromium copyright, BSD conditions and disclaimer in binary-accompanying material. Do not imply trademark permission or endorsement; Apache patent terms still apply. `PROPOSED-THIRD-PARTY-NOTICE.txt` supplies both texts and exact source-header attribution inventory for a later authorized notice integration. No separate upstream NOTICE was found inside these exact archives; that is not permission to omit existing attribution/license obligations. Keep ESV's third-party-rights carve-outs and accessible offline notices; validate packaged notice access later. A Maven notice URL alone is insufficient for the offline requirement.

This compatibility assessment is not legal advice, app-store approval, a license for the WebView engine as a redistributed standalone package, or release authorization. ESV would use the device's separately supplied WebView provider; this AAR does not bundle a browser engine. Existing product EULA/DRM, SkinKit distribution and store-policy gates remain separate.

Official references: [Apache terms](https://www.apache.org/licenses/LICENSE-2.0), [Chromium license](https://chromium.googlesource.com/chromium/src/+/main/LICENSE), [canonical boundary interfaces](https://chromium.googlesource.com/chromium/src/android_webview/support_library/boundary_interfaces/), exact official sources JAR linked in the provenance file.

## Profiles, isolation and safe disablement

Exact 1.17.1 sources, not only latest online API documentation, were inspected:

1. Profile provides its own cookie manager, WebStorage, geolocation permissions and service-worker controller. Use a random, non-URL private-session name; all private tabs in one session may intentionally share it. Keep normal tabs on the default profile. Named profiles are durable storage partitions, not a promise of memory-only browsing or forensic secure erasure.
2. On the UI thread, require both `WebViewFeature.isFeatureSupported(MULTI_PROFILE)` AND `isFeatureSupported(DELETE_BROWSING_DATA)` before offering private mode. Both are provider capabilities with no framework-only fallback. Exact MULTI_PROFILE internal implementation additionally requires WebView multi-process support/enabled state. API31/36 alone does not establish support; do not hard-code a WebView version number as a substitute for feature checks.
3. Construct a new WebView, set its private profile before any other use except attaching to hierarchy, then configure/use it. No late toggling of an existing tab. Prior navigation, JS evaluation, profile access or previous setProfile can make assignment illegal. If checks/assignment fail, destroy the uninitialized private view and report private browsing unavailable. Never navigate it using default storage as a fallback.
4. Every cookie/session helper, including Save authorization capture, must use the assigned Profile. Global CookieManager/WebStorage/service-worker access would cross the boundary. Keep private history, discovery, permissions, URLs and session restoration out of normal persistence. Profile partitions do not isolate app-owned SQLite, logs, task snapshots, clipboard, explicit external sharing, or media outputs by themselves.
5. Exit must first revoke the private generation and fence new tabs/navigation/async work; stop session-bound requests and prevent further writes, destroy private Views and clear application-owned memory. Invoke deleteBrowsingData on that profile's WebStorage and wait for its callback. This API promises removal of prior website data including network cache, cookies and JavaScript-readable storage. Concurrent writes are explicitly non-atomic: the callback does not cover writes made after deletion started. Test service-worker behavior rather than assuming destroyed Views stop it.
6. Missing callback, exception, provider failure or crash cannot become cleanup success. Retain only an opaque pending-cleanup marker, quarantine the generation, never reopen its pages or reuse its name, retry controlled cleanup on cold start. It has no detailed success/error result beyond completion notification; a timeout must be an incomplete state, not an erasure claim.
7. `ProfileStore.deleteProfile` is not the completion barrier: default profile cannot be deleted, live Views and a profile already loaded by getProfile/getOrCreateProfile can cause IllegalStateException, and some deletion remains asynchronous after return. Retired-profile metadata removal needs a cold-start path before loading that profile; returning true is not proof all disk content disappeared. Do not call getProfile first and then assume deleteProfile is legal.
8. No site camera/microphone/location grant is part of this evaluation or profile integration. Existing conservative denial remains. No new permission or purpose is authorized.

Reference APIs: [Profile](https://developer.android.com/reference/androidx/webkit/Profile), [WebViewCompat](https://developer.android.com/reference/androidx/webkit/WebViewCompat), [WebStorageCompat](https://developer.android.com/reference/androidx/webkit/WebStorageCompat), [ProfileStore](https://developer.android.com/reference/androidx/webkit/ProfileStore). Exact candidate sources are archived here and take precedence if latest docs describe newer APIs.

## Remaining acceptance and decisions

No SDK/dependency-upgrade decision is needed beyond admitting the exact one new WebKit artifact and its notice/verification/lock updates. The user's present approval is assessment only; ask for integration permission before implementing it.

After authorized implementation, validate on supported and unsupported WebView providers, including the minimum API31/32 and modern APIs: normal/private bidirectional cookie, localStorage, IndexedDB, cache and service-worker separation; private-to-private intended sharing; Save's profile-correct cookies; final-tab close/exit; callback loss, late callbacks and repeated exit; renderer crash/process kill/reboot; cold-start cleanup without loading retired profiles; no private restoration; default-profile survival; signed URL/task/log/screenshot/autofill/backup leaks. Check both feature-negative combinations and setProfile failure with zero ordinary-profile fallback navigation. Validate package offline notices and release shrinker boundary-interface preservation.

Real-device/provider privacy behavior, complete cleanup, crash recovery and release-package notices remain unverified. Only emulator-5556 is potentially authorized for later routine preserved-data testing; this task performed no device action. API31/32 private-page recording versus screenshot/recents protection remains the explicit product privacy decision already identified in PR25_RECONCILIATION; this dependency does not solve it.
