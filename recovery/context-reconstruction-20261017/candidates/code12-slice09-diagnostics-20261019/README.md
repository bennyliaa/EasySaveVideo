# ESV Commons diagnostics Debug code12 candidate

This directory transfers one signed code12 APK, the exact one-file code12 overlay on sealed slice09, and the original immutable slice09 source/evidence archive. The approved public Commons contact is intentionally present in project configuration/APK. No signing key, credentials, build cache, device data or collected user trace is included.

## Exact candidate
- com.easysavevideo.app; Debug versionCode 12; versionName 0.9.0-ui-dev; minSdk31; targetSdk36.
- APK: 53,408,774 bytes; SHA256 b41ff7c237218922ab367d2743e2ae131e9185a7fa3ca54913dc60a49466fc02.
- Public signer SHA256: 18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5. It matches the existing reported phone identity; installation/data preservation for this APK remain untested here.
- Sealed slice09: 2,892 source files; manifest 44708ef8843e13b840840f3ec704dbe6d739b1cdbfd2d1c9f31205ee27ef946a.
- Candidate source manifest: 76f33eadb01902dfe0024f1bb21501f66b48429037dff2650e87edcab69c6b32.
- Only candidate change after slice09: app/build.gradle.kts Debug versionCode11 to12 and its adjacent explanatory comment. Commons remains configured in Debug using the already approved contact; Release remains code9 with Commons disabled/contact empty. No SearXNG address, dependency, trust hash, repository, TLS/public-address/robots policy or production gate was newly changed by this candidate.

## Source reconstruction order
The source archive in this directory is commons-stage-diagnostics-slice09-delta-20261019T0353Z.tar.gz: 888,546 bytes; SHA256 d3013dd6f299b52e4094dd3cb366b7f718db6044c82e2440a1f8ce353d60cb20. It has 28 changed/added source paths on exact code11, producing 2,892 files. Its original author receipts record 5,827 focused tests and no APK/upload at that time; those immutable staging statements do not describe this later candidate build and backup.

1. Start from the exact complete code11 source (2,876 files; manifest 8c875841f8bee0aa4262a84ad1c801ef28303293db9de04d6389a4e0ad1d02f3). Its handoff is at commit e6495a1eec313ae89461ddf43708845f4e16f547, recovery/context-reconstruction-20261017/candidates/code11-slice08-commons-20261019. That directory describes the earlier T0647/slice08 reconstruction prerequisites; it is not itself a full source archive.
2. On a new independent copy only, verify the slice09 archive's BASELINE_MANIFEST, then apply its source/ overlay. There are no deletions. Verify every FINAL_SOURCE_MANIFEST entry and file mode.
3. Apply code12-source-delta-on-slice09.tar.gz only to this exact matching slice09 copy. It contains one exact replacement file, both pinned source manifests, a diff and DELTA.json. Check the before-file SHA, copy bytes without encoding/newline conversion, check the after-file SHA, then verify the entire final 2,892-file manifest.
4. Never apply the overlay to a different baseline, code10, bare T0647, a frozen copy or an old Git development checkout. Preserve original files and strict dependency verification. This is a candidate handoff, not a claim that all lost historical increments were restored.

## Completed offline/static verification
- All 13 default modules: 7,093 passed, 2 existing skips, 636 suites, zero failures/errors. Debug, Release and Debug androidTest Kotlin/Java compiled. Both lint variants have zero Error/Fatal; Debug87 and Release97 warnings, plus10 Hint entries each.
- Both runtime inventories exactly match 95 admitted external artifact hashes under strict verification. This warm-cache cloud result does not establish Windows cold-metadata reproducibility.
- APK v2 signature, manifest identity/SDK, ZIP CRC, 4-byte/16KiB alignment and18 actual feature DEX class definitions verified against these exact APK bytes. Tools run directly and bind their output hashes to the APK SHA. Independent review checks the actual artifact.
- Independent compiled Release check: no diagnostic resource keys; activation methods compile to refusal/null; the false owner branch allocates no recorder; Release has no diagnostic UI entry. Shared inert classes may still exist, so physical class exclusion is not claimed.
- Frozen code10/code11/slice09 sources and prior APK/archive identities were rechecked unchanged. No key was created, moved, exported or uploaded.
- VERIFY_PASSED is preassembly; FINAL_APK_VERIFICATION records subsequent local static verification. Their no-upload/no-install fields are historical as-of receipts. Repository transfer is not installation or a network/recording result.

## Diagnostic behavior and future device check
Stage collection defaults OFF, has no persistence/export/upload, and does not initiate a request. In the existing Debug evaluation area, the panel is titled “Commons 请求阶段诊断（Debug）”. Press “开始：记录下一次显式搜索” to arm one next valid explicit Search; that includes the filter panel's Search button. Pressing Start alone or “刷新阶段预览” makes no network request. Draft edits, ordinary settings changes, initialization, restore, paging or retry cannot consume a later arm. An old queued search cannot adopt a new arm.

“停止并保留本地预览”, leaving Discovery, backgrounding, source/configuration changes or session replacement revoke collection and seal the bounded in-memory preview. Returning does not rearm. A new Start clears the prior trace; stale controls and late callbacks cannot contaminate it. At most32 rows are previewed from bounded capture. Fields are numeric aliases, attempt, closed stage/outcome/error enums, observed duration and safe HTTP status. Query/URL/host/IP/title/contact/body/exception text are not recorded by this diagnostic model.

NOT_OBSERVED is missing retained evidence, not zero-time success or proof of a network fault. Active phases are IN_PROGRESS/NOT_MEASURED. Truncation and incomplete flags matter. HTTP_EXCHANGE is not time to first byte; OPERATION SUCCESS is a runtime callback outcome, not proof that candidates appeared. Cancellation observation does not prove cleanup completion.

Real Commons use still requires the existing source toggle, maintenance readiness and user confirmations. SafeSearch defaults are unchanged; unsupported filtering must be explicitly handled by the user. Debug fixtures must be off for a live source run. Save1/2/3 switches, recording consent and all existing safety gates are unchanged. No live search, device installation, recording or actual Commons failure cause is established by these offline checks.

## Receive without installation
Download every file in this directory from the same fixed commit into a fresh directory, then run Python3:

    python verify_and_extract.py

The script downloads nothing, installs nothing and applies no source archive. It checks the exact transfer inventory, file sizes/hashes, ordered APK chunks, joined deterministic gzip, bounded expansion and final APK identity. It refuses existing output and unsafe paths. The output is esv-slice09-code12-diagnostics-debug.apk. Verify the public signer with Android apksigner before a separately authorized installation.

The publishing connector can confirm all Git object hashes/sizes and byte-read back each small file/archive. The receiving computer must verify every downloaded APK chunk and final reconstruction. A successful commit is not a device or network acceptance result.
