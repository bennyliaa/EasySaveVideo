# Coil core-only exact dependency checkpoint

Prepared externally on 2026-10-15. This overlay is not itself a merge or permission change. Adopt only after the central owner verifies the final regression/lint result and integrates the accessible notice asset.

## Exact scope

The full frozen ESV checkpoint includes 2,489 files (source archive SHA256 `000340319ac1774727030f3dc6a369314e4a1ae596758bcdd879a4a3790d9919`) and 91 previously admitted Android runtime artifacts. The candidate adds `io.coil-kt.coil3:coil-core:3.3.0` only, with no Coil network or Compose plugin, and exposes the already-admitted OkHttp4.12.0 bytes to main compilation using the original Kotlin exclusion.

Full strict offline resolution yields 95 artifacts in each Android runtime variant and 72 in each compile classpath: 82 runtime artifacts unchanged, nine reviewed migrations, four additions. Both runtime sets match exactly by coordinate, filename and SHA256, with no old module missing, same-coordinate byte drift, or downgrade. The full 95-artifact independent audit found 21,318 classes and no duplicate runtime class name; new/migrated artifacts add no native binaries. Original WebKit1.17.1, Media3 1.11.1 and SkinKit1.0.0 bytes are retained.

Keep SDK36/Kotlin2.2.10/API31 and all existing strict byte/lock gates. The production verifyRuntimeAdmission task and preBuild dependency remain verbatim; the isolated observed-byte fence is never imported. Exact Maven payloads are projected only under bounded `vendor/coil-maven/` versions; all previous repositories and byte evidence remain.

Original Core/Core-KTX1.13.0 compile versus1.13.1 runtime selection was pre-existing. The candidate runtime migration is to1.15.0, and compile comparison uses its own frozen configuration baseline; it does not falsely classify that existing split as a downgrade.

## License and notice scope

The thirteen changed/new artifacts have exact official binary/POM/source/license evidence. Carry complete Apache text and relevant Coil/AOSP DiskLruCache, AndroidX, Okio/Square, and Coroutines1.10.2 NOTICE attributions. Preserve the entire existing notice asset as labeled source/license provenance and retain the separate SkinKit owner-authorized terms unchanged. The authoritative95-coordinate inventory and supplement supersede old version labels only for the nine actual migrations. `third_party/coil-3.3.0/REPORT.md` states the finite review and remaining release gates. No public ESV or SkinKit license is inferred.

## Verified stages and remaining gates

Central real strict offline full graph resolution succeeded. Central debug/release AAR metadata checks and full-project Kotlin compilation succeeded,42 tasks executed. Test/lint status must be taken from the final paired exit/log and overlay status; it must not be inferred from resolution/compile alone.

Actual thumbnail bounded network fetch, decode/display/cancellation, owner lifecycle and memory behavior remain implementation/runtime checks. R8, APK/AAB, device testing, package notice reachability, external distribution/rights, public target approval and product terms remain separate. This checkpoint does not impose a new partial-image rejection policy or a custom image parser.

## Final isolated current-source regression result

Central full13-module regression passed:508 suites,5636 passed,2 skipped,0 failures/errors; both lint variants75 warnings+8 notes,0 errors. The exact SARIF delta removes only dependency-currency NewerVersionAvailable notices (core-jvm in both variants, coroutines-android additionally in Release); it is not a claim of product-code repairs or a proven path/cache cause.

Final tested locks comprise app dependency/configuration migrations plus recording and searxng-provider test-configuration completion. The latter two only add existing JUnit4.13.2/Hamcrest1.3 and same-version Kotlin test compiler/classpaths, with no version migration and no app-runtime additions. All657 original verification artifact records remain unchanged;44 exact reviewed records were added, total701, with verification configuration unchanged. The observed isolated fence is excluded from this overlay.

The combined new accessible notice asset was statically verified for complete exact text, retained baseline suffix,95 coordinates and reader bound. The full regression above ran the unchanged source checkpoint with the candidate graph; final integrated notice/thumbnail behavior must be validated again after merging the separate feature overlay.
