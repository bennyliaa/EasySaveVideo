# Controlled Vault Playback R4: independent Host/Platform R2 source review

Reviewed: 2026-10-16 09:04 UTC. Scope: the frozen Host R2 and Platform R2 source overlays, pinned Media3 1.11.1 APIs/bytecode, and existing author-produced test evidence. This reviewer did not modify application source, run builds/tests, or operate a device.

## Decision

No remaining source-review blocker in the Host R2 / Platform R4 implementation after the two narrowly reviewed opt-in-only deltas. Final integrated compilation, 13-module JVM tests and both Android lint variants have passed within the evidence limits below. This is not approval to enable the production entry, or evidence of actual Android/native resource retirement. `AndroidVaultRuntimeEntry.ENABLED` remains false.

## Exact reviewed inputs

All paths below are relative to the workspace root. The manifests contain the complete per-file SHA-256 values.

- Approved design: `work/vault-playback-design/DESIGN.md`; SHA-256 `ff2e9e5ac75cf5e1c33931bba046f3c9b8d2738fc47e09c0c8fe0d9fbab8c212`.
- Host: `work/vault-playback-host/HOST_FREEZE_R2.json`; SHA-256 `d0a56ce380c920a560fc8c645d2a744bd6785d809701d1e2cf3becdc7df01bbb`. All 15 current overlay files match.
- Platform: `work/vault-playback-platform/PLATFORM_R2_FREEZE.json`; SHA-256 `507f6b50f71e572c30cb76a3b6928352031b367960972e82c61bc4b5da3d0bd0`. Its four source/test files and six platform evidence/tool files match. The eleventh record is the historical shared-contract input described below.
- Platform adapter `AndroidVaultPlayback.kt`: SHA-256 `9930753f94d6d04c3d5383f6528b72dce9c366fc075c126f37dcffb6af113313`.
- Production retirement ledger `VaultPlaybackRetirement.kt`: SHA-256 `2df47241f28fcb2193f8be139c62b6d8bed76742a273ba3959b806f34db99e75`.
- Integrated input manifest: `esv-runtime-copy-20261015/evidence/vault-playback/INITIAL_SOURCE_MANIFEST.json`; SHA-256 `7f79a98d43f04ae04bbd0a60eeacb1baed6657a0f0ece4afda710df9482cabf2`. Parent reports 25 overlay targets plus Gradle inputs; first integrated run session 39199 started at 09:01 UTC using independent `playback-build-source`.

## Findings closed by the reviewed bytes

- Platform retains resources before fallible ownership/adoption steps, including AudioTrack outputs, renderer wrappers, sink and selector. Unknown cleanup remains retained; ordinary playback/read errors do not falsely become sticky native-cleanup failures.
- Loader snapshot occurs after the actual playback control barrier. Renderer return, exact output state/callback processing, executor termination, reader/file retirement receipt and both looper joins remain separate retirement obligations. Main does not execute blocking Player surface clear/release or thread joins.
- Independent reader cleanup is supported only after irreversible revocation, actual factory return, and zero accepted reads/publication permits under the runtime gate. Late read-port calls reject before borrowing the underlying reader. Native/transport/surface unknowns still prevent overall settlement; both loopers wait for actual reader/file retirement receipt.
- The exact per-open host token is irreversibly revoked synchronously before cover/native retirement, including system Surface destruction. A new open receives a fresh token. Public platform commands check current ownership again on their execution looper.
- Stable Main AndroidView host and retained-slot sizing survive cover and route changes until real detach/removal/holder-destroy evidence. Secure Surface ordering remains cover, off-Main detach, then Main removal. Borrowed Surface ownership is not released by the adapter.
- The fixed local release-warning text is drawn above the existing opaque protected root without revealing content or reopening input. Reservation matching handles retirement before first UI publication, stale terminal messages, and replacement of rendered old A by currently reserved B while rejecting forged same-token tickets.
- No new ordinary continuous-recording stop/cancel/revoke path was introduced. Capture authorization refuses Vault; actual simultaneous-recording behavior still requires device evidence.

## Existing test evidence and its limits

- Platform direct compilation against admitted Kotlin 2.2.10, Media3 1.11.1 and Android 36 artifacts passed. `work/vault-playback-platform/test.log` and `compile-and-test.log` each have SHA-256 `5f540fccf62e389baa83242263375f1f724e453a3647169314afd2ae47fdf521` and report `OK (25 tests)`: 21 tests execute the production retirement ledger; four inspect source ordering. They do not execute actual Player, SurfaceView, AudioTrack or Android loopers. The observed Mp4Extractor constructor warning is deprecation only.
- Host `work/vault-playback-host/wiring-tests.log` has SHA-256 `54b4fca4f7fd7c0fcd4653dea3bde48748eea86be35838cc19aa8126f637c641` and reports `OK (20 tests)`: six actual runtime-wiring tests, five production reservation/token predicate tests and nine source-contract tests. These are not Android UI/device tests.
- Platform's historical direct compile used shared API V3 SHA-256 `75858e19b1d3d6fed08976edcf162721895d1ebfdfd84bb8d7c0d943945d407a`. Final Host R2 contracts are SHA-256 `0890ddc80c89b8efdbb9a534cdc838560aacb1a33e39e4eb3ef6fb529ac0d382`, after a pure display-reservation helper correction. Historical evidence is preserved accurately; final cross-overlay compilation must cover the latter bytes.

## Integrated focused evidence verified, 2026-10-16 09:08 UTC

Session 39199 completed with exit 0 and `BUILD SUCCESSFUL in 6m 24s`; all 64 actionable tasks executed. The frozen log explicitly shows actual `:app:compileDebugKotlin`, `:app:compileReleaseKotlin` and `:app:compileDebugUnitTestKotlin` execution. All 26 input-manifest records still match the independent build tree. Its shared contracts have final SHA-256 `0890ddc80c89b8efdbb9a534cdc838560aacb1a33e39e4eb3ef6fb529ac0d382`, closing the final cross-overlay compile gap described above.

Independent parsing of every frozen XML file confirms 482 suites / 4,934 tests, with zero failures, errors or skipped tests. The exact Host 20 and Platform 25 cases are present and all pass; their coverage limits remain as stated above.

Evidence paths below are relative to `esv-runtime-copy-20261015/evidence/vault-playback/`:

- `focused-first.log`: SHA-256 `a9f974df49a589ae7949e76cc1f7f645b490dcb88ef7a8f9af449e12e2fb8b3f`.
- `focused-first.exit`: SHA-256 `9a271f2a916b0b6ee6cecb2426f0b3206ef074578be55d9bc94f6f3fe3ab86aa`.
- `focused-first-TOTALS.json`: SHA-256 `9fc90463754643018a8e26168f44031a63508de699a008c88481aa6a981011d2`.
- `focused-first-xml/app/TEST-com.easysavevideo.app.vault.playback.VaultPlaybackRetirementTest.xml`: 21 tests; SHA-256 `121598b68f7e550629b4fbe520c640260f16bf9fd1efe4dfc491a2d14e3798bc`.
- `focused-first-xml/app/TEST-com.easysavevideo.app.vault.playback.VaultPlaybackPlatformSourceContractTest.xml`: four tests; SHA-256 `371a9e4026a3f5413efcf9821d1d3b800cb867cc754d709df4d096816f39a8ed`.
- `focused-first-xml/app/TEST-com.easysavevideo.app.vault.VaultPlaybackWiringTest.xml`: six tests; SHA-256 `81940abbe617a570d633abe90ac48fd60f2b99b9ead1fd0cb7215c538dc9b784`.
- `focused-first-xml/app/TEST-com.easysavevideo.app.vault.VaultPlaybackPublicationTest.xml`: five tests; SHA-256 `84b51493df560fdb4e3a48d9f149be5f75e771bb70f21818e19d95fe4aedf2ec`.
- `focused-first-xml/app/TEST-com.easysavevideo.app.vault.VaultPlaybackHostSourceContractTest.xml`: nine tests; SHA-256 `bf3ce0b8175267c9e8acb59b6f0d4bd79b64071822036d419e89ca68728db43e`.

Actual Media3/Surface/AudioTrack execution, fixed Android media cases, device security and real recording coexistence remain NOT_RUN. Production remains disabled. The central paused-Git source baseline was not changed by this reviewer.

## Platform R3 lint-only delta reviewed, 2026-10-16 09:18 UTC

The subsequent full/lint attempt exposed 158 `UnsafeOptInUsageError` diagnostics: 121 in the platform adapter and 37 in the runtime-owned data source. This is the rule-specific count; the complete SARIF contains 254 diagnostics. The observed SARIF SHA-256 is `4dd66d9c05e7e935a71e59c1cd964f9249d778fe0571e4633396283df52b62b3`.

Platform R3 adds exactly one class-level `androidx.annotation.OptIn` for Media3 `UnstableApi` on the private AndroidVaultPlayback owner. Removing that unique line produces the exact reviewed R2 hash `9930753f94d6d04c3d5383f6528b72dce9c366fc075c126f37dcffb6af113313`. No behavioral, dependency, suppression or lint-baseline change is present. This narrow delta has no source-review blocker to rerunning the integrated build/lint; it is not yet a successful rerun result.

- Updated adapter SHA-256: `10b935c99f6f2c0fb289093b58efe2f9eb0d17c0be8fa0795bd4e685a4f5b751`.
- `work/vault-playback-platform/PLATFORM_R3_OPTIN_ONLY.patch`: SHA-256 `5ab4f6cb0a8df41682559ecaeffcbda5ef92ec530f1e0c64ca833964ee76e019`.
- `work/vault-playback-platform/PLATFORM_R3_OPTIN_FREEZE.json`: SHA-256 `d9fcbc459b61ba0b3ae33a1cb1214406df759e7eab0e096504b6a16f120f53ed`.

Earlier R2 source-match and focused-test claims above describe their exact historical snapshot. Final changed source and complete 13-module/lint acceptance await fresh integrated evidence.

## Documentation-only status correction reviewed, 2026-10-16 09:22 UTC

`work/vault-playback-docs/MANIFEST.json` SHA-256 `5cf45bbe18ced2c9d9219b8474a20bb0ce7ed5f4510005c3e071ea6ff78aab63` names two documents; both target hashes match. The older R2 admission body remains intact beneath an explicit historical-scope notice. The current-status page distinguishes source/JVM/compile evidence from real authentication, native retirement and device/privacy acceptance, keeps production disabled, and lists remaining code work separately. Its capture wording is accurate: an active capture exception refuses Vault; ordinary continuous recording is not stopped to allow Vault display. Playback stop is not presented as an account auto-lock rule. No documentation-scope blocker found.

## Platform R4 lint-only delta reviewed, 2026-10-16 09:29 UTC

The second full/lint attempt ended with one remaining `UnsafeOptInUsageError`: the existing `experimentalSetEnableMediaCodecVideoRendererPrewarming(false)` call requires Media3 `ExperimentalApi` opt-in in addition to `UnstableApi`. R4 adds exactly one standard AndroidX opt-in annotation on the private inner `TrackingRenderers` class. Removing it reproduces R3 SHA-256 `10b935c99f6f2c0fb289093b58efe2f9eb0d17c0be8fa0795bd4e685a4f5b751`. The prewarming-disabled behavior is unchanged, with no suppression, baseline, dependency or other behavioral change. No source-review blocker to the third integrated full/lint run.

- Updated adapter SHA-256: `ff69a88bd694ae3ae2f6afbbbe5b3607e897bb4faea7ca327a78378154acddda`.
- `work/vault-playback-platform/PLATFORM_R4_EXPERIMENTAL_OPTIN_ONLY.patch`: SHA-256 `3564d5506b2cade9ec55cf67d237a432a43048b506926c1a4c1741dd1a8c94e9`.
- `work/vault-playback-platform/PLATFORM_R4_EXPERIMENTAL_OPTIN_FREEZE.json`: SHA-256 `06ea8002d38c7cee565a56ba7351233830834ff17477f5f4ef6d503494242292`.

Final post-R4 integrated test/lint evidence remains pending; no earlier failed lint attempt is treated as a pass.

## Final integrated evidence verified, 2026-10-16 09:41 UTC

The third full run completed with exit 0 and `BUILD SUCCESSFUL in 10m 9s` (101 actionable tasks: 35 executed, 66 up-to-date). Its log records actual app Debug/Release Kotlin compilation, Debug unit-test compilation/execution and both lint variants. All 26 tested-code input records in `EXPERIMENTAL_OPTIN_SOURCE_MANIFEST.json` match the independent build tree; manifest SHA-256 is `27584d1eefa5dac1baefed35958ff59d7cf6c015a9bb89e5268ea6d6bd78e5d7`.

Independent frozen-XML recount: 579 suites / 6,442 total cases across 13 modules, with 6,440 passed, two skipped, zero failures and zero errors. The skips are `verification-local` cases `FrozenCorpusHostTest.same100FrozenBodiesActualStaticB` and `FrozenRecoveryComponentTest.authored80MutationsActualJsoupComponent`; neither belongs to Host/Platform playback coverage. All Host 20 and Platform 25 tests pass without skip. SARIF effective levels (including rule defaults) show each variant has zero errors, 86 warnings and ten notes, and no `UnsafeOptInUsageError` remains.

All following paths are relative to `esv-runtime-copy-20261015/evidence/vault-playback/`:

- `full-third.log`: SHA-256 `9e52fabd5640c1bc920be3a219bd470e686ef159131f7e72e3dc23c5b250934b`.
- `full-third.exit`: SHA-256 `9a271f2a916b0b6ee6cecb2426f0b3206ef074578be55d9bc94f6f3fe3ab86aa`.
- `full-third-TOTALS.json`: SHA-256 `5eeab0afcc07b4a628384fb90f15aa5c2373ba7f6fcd44232e451bf08defda73`.
- `full-third-lint/lint-results-debug.sarif` and `full-third-lint/lint-results-release.sarif`: each SHA-256 `78e4c0cff57f347c1014216dda832d4771247bd49783943a2db44702ad5b3b11`.
- `full-third-xml/app/TEST-com.easysavevideo.app.vault.playback.VaultPlaybackRetirementTest.xml`: 21 cases; SHA-256 `c5eff65f5b8abd2139ac69a10c59416e3d316a664df3f138c6ea1e8166874b05`.
- `full-third-xml/app/TEST-com.easysavevideo.app.vault.playback.VaultPlaybackPlatformSourceContractTest.xml`: four cases; SHA-256 `cea058ad58bc8a49f5c7b185ea029810d3c9eb57ccecb3551580d2eff56eade2`.
- `full-third-xml/app/TEST-com.easysavevideo.app.vault.VaultPlaybackWiringTest.xml`: six cases; SHA-256 `f7746ec0974305ef1e218b7f066185285b0ed64676fe6a4ba2d328a20abf3095`.
- `full-third-xml/app/TEST-com.easysavevideo.app.vault.VaultPlaybackPublicationTest.xml`: five cases; SHA-256 `172fa1facb22253c06d21916c16900c3cc5264728747a8f0f62fdc14583bbd8b`.
- `full-third-xml/app/TEST-com.easysavevideo.app.vault.VaultPlaybackHostSourceContractTest.xml`: nine cases; SHA-256 `acb4c356d47e4c15fbee1d080c92906ea8c43d11a32bd2bdc80a86ebdd055254`.

The approved two-file docs-only merge is recorded in `FINAL_SOURCE_MANIFEST.json`, SHA-256 `431f709588b28e89896085b360321bfafd282631d20ca517f8720aa738d22b80`. All 28 final records match the build tree, including the unchanged 26 tested inputs and the two reviewed document hashes. Actual native/media/security/device acceptance remains NOT_RUN. JVM ledger tests and source contracts have not become proof of codec, AudioTrack, Surface, Android Looper or continuous-recording behavior. Production remains disabled.
