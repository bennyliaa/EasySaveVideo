# Exact Tink dependency admission, not Vault activation

Baseline: `f184cc91c7f3e31d7b9b39219ee8b9c9d2139fab`, tag `m5a3-exports-dev-2026-10-06-r1`.

## Scope and decision

The project owner accepted the exact reviewed official Tink Android 1.23.0 / Gson 2.13.2 / Error Prone annotations 2.41.0 scope, including embedded protobuf 4.33.6 BSD-3-Clause and the identified source-derived obligations. There is no substantive unresolved license UNKNOWN in that scope. Unrecorded historical donna/ref10 import commits are explained provenance limits, not unidentified applicable licenses. No independently trusted-key PGP verification or reproducible binary build is claimed.

Actual offline Debug and Release graphs each contain 74 external artifacts: the prior 72 minus Error Prone 2.28.0, plus Tink Android 1.23.0, Gson 2.13.2 and replacement Error Prone 2.41.0. All other runtime coordinates and bytes are unchanged. This count is the measured result, not a graph forced to a target. Existing jsr305 3.0.2, AndroidX annotation-jvm 1.9.1 and Guava remain. No full Tink Java, unshaded protobuf, KMS extension, alternate cryptographic/codec provider or new source provider is introduced. Build-tool dependencies are not application runtime components.

This checkpoint adds the dependency, exact lock/hash gate and offline notices only. No Vault core module, Android storage/Keystore wrapper, private import/playback/export UI or runtime activation is included. Existing ordinary-library production code is unchanged. The sole new app test uses explicitly public fixed bytes as its test-only key, synthetic data and the public StreamingAead API; it does not handle user data or establish security acceptance.

## Immutable input layout

The sealed candidate archive SHA-256 is `83e851a35a957f273953725915c4d442e348cfe9e5a6e67f73b6f4e23563801e`. Every one of its 66 files was verified against its archive, original payload manifest and checksums before import. The candidate's reports, status files, source metadata, license evidence, manifest and verifier remain unmodified. Their historical “candidate / not admitted” statements describe that audit's scope, not this later engineering checkpoint.

The three official binary/source/POM triplets and necessary parent POMs are stored once in `vendor/tink-maven/`. All remaining audit/source/license evidence is under `third_party/candidates/tink-1.23.0/`. `evidence/candidate-import-map.json` maps original archive paths to these destinations and binds exact size/hash for every file. Relative paths inside historical reports refer to the original archive layout. Use `scripts/check-tink-admission.py` in this repository; the preserved `verify_bundle.py` applies to the original intact archive layout. No extracted upstream trees, compiled audit classes or duplicate candidate binary copies were committed. Standalone protobuf source/binary evidence is outside Maven repositories and never a runtime dependency.

## License and resource handling

The existing offline notice asset is retained byte-for-byte as a prefix. The supplement includes full Apache-2.0, protobuf BSD-3-Clause, Curve25519-donna BSD-3-Clause, source copyright attributions, Jackson NOTICE, Square/Moshi credit, AOSP and public-domain ancestry. Historical Error Prone 2.28.0 attribution is retained; the supplement explicitly identifies its 2.41.0 replacement. The text is within the existing offline viewer's 512 KiB character bound. Existing Media3, jsr305 CC BY 2.5 / rights exceptions, Guava and SkinKit terms remain intact.

Official `META-INF/proguard/protobuf.pro` and `META-INF/proguard/gson.pro` are preserved with exact binary bytes. No blanket META-INF exclusion or security/TLS change is made. Debug/Release merged assets are checked for exact notice bytes. This is not verification of final DEX/R8 output, installation-package reachability, product EULA/DRM compatibility, external SkinKit distribution rights or publication approval.

## Mandatory future Vault adapter conditions

- **Do not use AndroidKeysetManager.** Its opportunistic Keystore implementation permits cleartext keyset fallback. ESV wrappers must be explicit and fail closed; never persist plaintext keysets, root keys or object keys.
- Tink's `size()` is unverified. Authenticate bounded ESV metadata, exact ciphertext extent and the final segment before trusting length; bare EOF seeking does not authenticate the tail. Valid empty/direct-EOF reads can return zero in this release. Reject empty video and prevent zero-progress loops.
- Closing the public wrapper does not revoke cached plaintext reads. Atomic ESV lease revocation and a publication fence are required around all reads/seek/output; use private bounded scratch buffers and discard all scratch output on exceptions.
- Random access authenticates visited segments, not the complete unvisited object. Whole-object-before-export policy requires a complete authenticated discard pass followed by the export pass over the same immutable generation. No disk plaintext cache or custom streaming-crypto fallback.
- Retain approximately **2 MiB per simultaneous seekable reader**, plus caller/key/provider state, in future memory/concurrency budgets. No secure JVM heap, decoder or already-published plaintext erasure guarantee.
- Bind identity, generation, profile, exact size and epoch through authenticated metadata/AAD. Require transactional staging/finalization, immutable committed ciphertext, explicit durability, and bounded parser/keyset input.

## Still unaccepted

Dependency admission is not Vault security acceptance or Android acceptance. Real API31 JCA/Keystore behavior, authentication/invalidation, optional offline recovery lifecycle, Media3/proxy-FD integration, cancellation/revocation races, low-memory/device/codec behavior, phone/tablet UX, real-media seeking, D8/R8 and final packaged notices remain NOT_RUN and must be separately reviewed/authorized. No APK/AAB, device/install, Android Keystore action, real user secrets/media, accounts, payments, publication or deployment was performed.

See `evidence/verification-summary.json` for measured build, JVM, SQL, host-regression and static-audit results. Restore evidence is produced separately against the frozen commit/tag using an existing approved offline toolchain/cache; it does not imply a fully vendored SDK/toolchain.

## Measured host/engineering results

The complete strict offline rerun executed 89 tasks. Both compile/lint variants passed with 0 errors/fatals and the unchanged 11 warnings plus 5 hints per variant. There were 930 passing JVM tests (929 inherited plus one public-fixed-key Tink smoke), 14 catalog SQL contracts, 14 export SQL/static contracts, the discovery SQL suite, 11 synthetic DOM checks, 81 pinned HLS EOF fixtures, 15 pinned mux round trips and the legacy pinned reader check. Both exact 74-artifact runtime graphs contain 17,188 external runtime class names and zero duplicate classes. All 147 existing production source/resource files outside the amended notice asset remain byte-identical to the baseline.

A final strict offline invocation after the narrowed lock update passed again with no write/update flags. App lock changes are limited to the three reviewed coordinates, removal of old Error Prone, and the already-admitted jsr305 artifact's additional compile scopes. Lock configuration names, other modules' lock files and verification metadata were checked not to change during that strict run. All inherited warnings and unavailable platform-tools/metrics notices remain visible rather than suppressed.

The independent Error Prone classfile audit verifies the actual final graph's 17,607 classfiles including nine project JARs, 3,651 Error Prone type references, 21 member/enum references and 3,567 annotation instances (838 explicit elements, 463 defaulted elements). All resolve. All 27 prior Error Prone package classes preserve complete structural API and annotation/default metadata; 26 annotation classes are byte-identical. The enum's reflection-visible parameter flags change but descriptors/instructions remain unchanged. Five mutated-model negative controls are correctly rejected. This is static linkage evidence, not Android, reflection-behavior or general dependency certification; see the independent report for full limits.
