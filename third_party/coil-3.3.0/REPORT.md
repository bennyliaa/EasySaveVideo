# Coil 3.3.0 core-only: current-graph license and byte review

2026-10-15. Read-only production inputs; only this evidence directory was written. No Gradle, network, download, APK, device or GitHub operation was performed by this reviewer. Parent supplied the separately recorded exact official coroutines license/NOTICE readback. No production admission or legal/store guarantee is asserted.

## Current decision

The **actual complete current candidate graph passes this finite byte/license review**. Both Debug and Release contain 95 runtime artifacts and 72 compile artifacts, with no unresolved dependency. Against the current 91-artifact baseline, **82 are unchanged, 9 are upgraded and 4 are new**. All 13 changed/new artifacts have exact official binary/POM/source evidence; local rehashes match their cached or newly supplied official-readback pins. Their declared license is Apache-2.0. Source/header and embedded-notice examination found no newly identified non-Apache exception within these 13 archives. This is bounded artifact evidence, not a legal warranty or exhaustive source-code audit.

The production baseline remains **91 runtime artifacts**, including already-admitted AndroidX WebKit 1.17.1; it was not modified. The actual isolated 95-artifact graph retains WebKit and both SkinKit 1.0.0 binaries byte-for-byte, has no unchanged-byte drift, no missing baseline module, and no downgrade. The ninth upgrade is actual BOM-aligned **kotlinx-coroutines-guava 1.9.0 → 1.10.2**. Central retrieval supplied its exact official binary and source plus official SHA256 sidecars. They match both local rehashes and the resolved runtime bytes. No unresolved changed artifact bytes or required license text remain in this finite review.

## Exact known migration

The authoritative current 95-artifact result is `FULL_GRAPH_REVIEW.json` and `full-runtime-difference.tsv`. It includes the actual resolved filename, length, SHA256, baseline identity and changed/new classification. `known-runtime-delta.tsv`/`.json` preserve the initial 12-item cached review; `additional-coroutines-guava-review.json` closes the thirteenth item. Complete source/POM/license pins are in these files and `FULL_INPUT_PINS.json`. `actual-resolved-graph/` snapshots the successful central 03 resolution, both variants and all empty unresolved lists.

Additions:
- `io.coil-kt.coil3:coil-core-android:3.3.0`
- `androidx.appcompat:appcompat-resources:1.7.1`
- `androidx.vectordrawable:vectordrawable:1.1.0`
- `androidx.vectordrawable:vectordrawable-animated:1.1.0`

Actual upgrades:
- Core and Core KTX 1.13.1 → 1.15.0
- ExifInterface 1.3.6 → 1.4.1
- ProfileInstaller 1.4.0 → 1.4.1
- Tracing 1.0.0 → 1.2.0
- Okio JVM 3.6.0 → 3.15.0
- Coroutines Android, Core JVM and Guava 1.9.0 → 1.10.2

Coil AAR is 471,284 bytes, SHA256 `b57fcf001178db5ff84683e7d11818bcda6c115d9d530443d382b3a8b3f6806b`. Source JAR is 134,847 bytes, SHA256 `58bf55460e62be9608819ec72ced3138cedba6d05216e0136048e05a970cd23d`. The exact upstream 3.3.0 LICENSE is 11,347 bytes, SHA256 `5ade6484903351f6661e791504c50ad1a24cab3a75950c1b2a7be3eb336b6ba9`. Sources are pinned as exact published Maven source artifacts and content hashes; no unverified Git commit identity or source-to-binary reproducible-build claim is invented.

The added fullgraph-only coroutines-guava binary is 17,969 bytes, SHA256 `d54c4c9f9f2d3612afa5801daf70a7b68687f78d8936360341f1a89b535abfee`; source is 7,526 bytes, SHA256 `988ad4424350cfc88e92e06dd9a5ec0420c6f663872fe7bf455bcd8169e3ebd6`. Its source archive contains `ListenableFuture.kt` and `module-info.java`; no distinct source-header exception or embedded notice was found. The exact project-level Apache LICENSE and 1.10.2 NOTICE apply. Guava itself remains the admitted 33.3.1-android bytes, with the 9999 empty ListenableFuture artifact unchanged.

Coil publishes the official URL filename `coil-core-android-3.3.0.aar` but its official `.module` names that artifact `coil-core-release.aar`. Gradle resolves the latter name with the same exact hash. Any candidate runtime admission row must use the actual resolved filename; these are not two different binaries.

## License and notice treatment

`CANDIDATE_NOTICES_SUPPLEMENT.txt` is a reviewable candidate supplement, not an instruction to replace existing production assets. `licenses/`, `sources/`, `poms/`, `metadata/` retain exact readable evidence. Full copyright lines and source locations are in the JSON.

- Coil AAR has no embedded LICENSE/NOTICE. Carry the exact 3.3.0 upstream Apache text, **Copyright 2025 Coil Contributors**, and **Copyright (C) 2011 The Android Open Source Project** from its AOSP-derived `DiskLruCache.kt`. Disabling disk cache does not remove those classes from the admitted artifact or erase their attribution.
- AndroidX changed artifacts retain AOSP attribution and Apache text. Embedded full Apache texts exist for Core, Core KTX, ExifInterface and ProfileInstaller; their exact extracted bytes are preserved. Other archives' missing embedded text is not evidence of missing obligations.
- Okio 3.15.0 source headers identify Square, Inc., including the exact “Square, Inc. and others.” line. Preserve those lines and Apache text; do not collapse their ownership into Coil.
- Coroutines 1.10.2 published sources omit repeated copyright headers and binary/source JARs omit NOTICE. Its exact tagged `LICENSE.txt` and **`license/NOTICE.txt`** have now been read by the parent from official upstream and are preserved under `official-coroutines-1.10.2/`. The NOTICE is 452 bytes, SHA256 `1ab6de67ef738bffae63f9aa6a8798a35e87341c83ea28d09c8dc8ca6993232a`; LICENSE is 11,398 bytes, SHA256 `b1febe6399dffb10d19d35e7663ab16300c93cb0476a94115df1cc0097a8ffd8`. Root `NOTICE`/`NOTICE.txt` 404s were only wrong-location results, not proof of no NOTICE. Carry the exact new NOTICE; do not relabel the 1.9.0 notice as 1.10.2.
- Preserve the **existing whole notice set and its applicable rights**, including WebKit Apache + Chromium BSD, unchanged Kotlin 2.2.10 supplementary BSD/Boost/Apache provenance, OkHttp Public Suffix List MPL-2.0, Guava/JSR305/other baseline notices, and the separate SkinKit 1.0.0 owner-authorized terms. The input preservation manifest pins the WebKit texts and current notices plus SkinKit terms. No public license is assigned to ESV or SkinKit.
- Before production adoption, reconcile changed coordinate labels and source URLs and include the reviewed supplement in the accessible notice asset. Do not silently overwrite other licenses, leave a false final runtime inventory, or treat a source archive as a substitute for an accessible license/NOTICE text. Final distribution/viewer/package accessibility and product terms remain release checks.

The ordinary Apache-2.0 path includes supplying the license, preserving relevant copyright/attribution and any supplied NOTICE, marking modifications if any, and not implying trademark grants. This review identifies an established closed-source distribution path subject to those requirements; it is not an individualized legal opinion, license compatibility guarantee for future product terms, or content-rights grant for downloaded images.

## Artifact compatibility observations

The existing isolated SDK36/Kotlin2.2.10/API31 3.3.0 check/compile PASS is reusable but is not a full-ESV or R8 result. The known changed AAR metadata has minCompileSdk at most 35 and manifest minSdk at most 21; preserve the actual current SDK36/API31 toolchain and test the full selected graph. Java9 `module-info` markers in coroutines are not by themselves an Android API requirement.

A new scan of all **95 actual resolved runtime artifacts**, including nested runtime JARs and excluding lint tooling and module-info, found **21,318 runtime classes and zero duplicate class names**. All 95 outer binary lengths and hashes were rechecked. Native files occur only in the existing unchanged Compose UI 1.8.3 and graphics-path 1.0.1 artifacts (four ABI files each); no changed/new artifact introduces native entries or native magic. Detailed per-artifact results are in `FULL_GRAPH_REVIEW.json`. This fullgraph result supersedes the narrow prior 27-artifact scan for this question.

Direct comparison against admitted cached old bytes confirms:
- Core 1.13.1 → 1.15.0 manifest changes minSdk 19 → 21 only; signature-level dynamic-receiver permission and app component factory were already present.
- ProfileInstaller 1.4.0 → 1.4.1 AndroidManifest is byte-identical; the existing initializer and DUMP-protected exported profile receiver are not newly introduced by this upgrade.

See `manifest-component-comparison.json`. Okio and coroutines upgrades affect existing consumers (including OkHttp and coroutines-guava), so full-project compile/test/minification remain engineering evidence to establish. No unexplained binary or license blocker remains among the 13 actual changed/new artifacts. The successful central 03 run establishes full resolution only; compile, tests, R8 and production admission remain distinct engineering/project decisions.

## Not in this review

No production source, locks, verification metadata, runtime admission gate or notice asset was changed. No source endpoint/contact approval, network request, decoder policy or thumbnail implementation is claimed. No renewed partial-image parser requirement or custom decoder design is imposed here. The task is exact dependency/notice review.

## Completion and preservation

`PRODUCTION_INPUT_PRESERVATION.json` verifies that the production inputs pinned at the start still match, including the 91-artifact admission file, current notice asset, SkinKit terms and WebKit Apache/Chromium BSD attribution texts. `OUTPUT_MANIFEST.json` pins this completed evidence package. This review is complete; it does not require an additional license fetch or renewed approval for the already-authorized read-only study.

## Prepared external notice overlay

At the parent's request, `notice-overlay/` is ready for the graph worker's external admission overlay; no production file was edited. Its intended destinations are:
- `app/src/main/assets/third-party-notices.txt`
- `third_party/coil-3.3.0/RUNTIME_NOTICES_SUPPLEMENT.txt`
- `third_party/coil-3.3.0/NOTICE_SOURCE_MANIFEST.json`
- `third_party/coil-3.3.0/runtime-95.tsv`
- `third_party/coil-3.3.0/licenses/`

The accessible asset prepends the authoritative 95-coordinate inventory, all 9 old→new migrations, 4 additions, all 13 changed official source/POM URLs and complete new license/NOTICE text. It then explicitly labels the old inventory/version references as historical provenance and preserves the entire prior notice asset byte-for-byte as a suffix. This avoids relabeling old source URLs as new-version evidence and retains all WebKit, Kotlin, PSL and other baseline text. The separate SkinKit asset is not changed.

`NOTICE_OVERLAY_VALIDATION.json` verifies these content/preservation properties. The new notice asset is 619,318 UTF-8 bytes, 616,389 UTF-16 code units, below the unchanged reader's 1,048,576-unit bound. Its SHA256 is `c124394e222d0e4a317f60ba3d97b60666fd6cfe17188a6773316bfcc307f2d5`. This is static exact-text and bound validation; central JVM tests/lint, final packaged notice accessibility, native image decoding and device rendering remain separate checks. A complete license review does not establish any image/runtime/device acceptance.
