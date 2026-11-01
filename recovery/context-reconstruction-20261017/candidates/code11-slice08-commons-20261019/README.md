# ESV slice08 Commons Debug code11 candidate

This is one locally verified signed test APK plus its exact two-file source overlay on the separately backed-up slice08. The APK uses the already authorized public Commons service contact. No signing key, credentials, build cache or user/device data is included. Its public signing certificate is part of normal Android APK verification.

## Artifact and source identity
- Package com.easysavevideo.app; Debug versionCode 11; versionName 0.9.0-ui-dev; minSdk 31; targetSdk 36.
- APK: 53,333,410 bytes; SHA256 38f09f2dc752fc69cef9b2108c704c3e44e3b21d43134ee602f8b6e773c71988.
- Public certificate SHA256: 18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5. It matches the reported installed code10 identity. Actual code11 installation has not been tested.
- Frozen slice08 source: 2,876 files; manifest b76fe7d9ce43da5e0400fd30a068ccc3171092b5495bed4be6f7008c7990b871.
- Candidate source manifest: 8c875841f8bee0aa4262a84ad1c801ef28303293db9de04d6389a4e0ad1d02f3.
- Only two changed paths: app/build.gradle.kts adds Debug-only code11/Commons contact and enable overrides; gradle/verification-metadata.xml adds exactly four independently reviewed metadata SHA256 entries. Release remains code9 with Commons disabled and an empty contact. All dependency versions, repositories, strict verification and production gates remain unchanged.
- Slice08 delta and the metadata audit are backed up at commit 469c1d6f76779e07f08c80eb44d95bad9a2f8104 under recovery/context-reconstruction-20261017/slices/discovery-latency-slice08-20261019 and fixes/windows-metadata-four-artifacts-20261019. Slice08 is based on T0647, backed up at commit ac299997df061fc37347e534c21c144abd807e67. This directory is a candidate handoff, not a replacement for those source baselines or a claim of restored lost history.
- The source-delta archive contains exact replacement bytes for those two files, both source manifests, a diff and DELTA.json. Apply only to a separate source copy whose entire baseline manifest matches; verify each before/after hash and the complete final candidate manifest. Use binary copying; do not change encoding/newlines. Preserve file modes. Do not apply to code10, T0647 or frozen slice08 in place.

## Verification and remaining runtime work
- 7,044 offline JVM tests passed, 2 existing skips, 630 suites across all 13 default modules, zero failures/errors. Debug, Release and Debug androidTest code compiled. Both lint variants have zero errors/fatals; Debug has 87 warnings and Release 97, plus 10 Hint entries each.
- Both runtime inventories exactly match 95 admitted external artifact hashes. Independent review also recomputed all 108 resolved artifact-file hashes. This warm-cache cloud build does not establish Windows cold-metadata reproducibility.
- APK signature v2, package/version/SDK, ZIP CRC and 4-byte/16KiB alignment were checked directly on the exact artifact. Independent review checked 175 ZIP entries, 30 DEX files and 12 feature class definitions, and parsed the packaged BuildConfig constants to confirm code11/Commons enabled/approved contact.
- VERIFY_PASSED.json is the preassembly receipt, and FINAL_APK_VERIFICATION.json records the later local APK checks. Their no-upload/no-install statements describe their verification timestamps. Subsequent repository transfer does not imply installation or runtime acceptance.
- Commons is configured only in Debug. Real use still needs the existing source toggle, maintenance initialization, user confirmations and safeSearch handling. SafeSearch's existing default is unchanged; the provider rejects unsupported filtering until the user explicitly chooses otherwise. Debug fixtures must be off for a real-provider run. No SearXNG address was added or certified usable.
- Method 1/2/3 default switches and all confirmation/security/privacy gates are unchanged. The candidate can support the planned user-directed method3 test, but no real Commons search, playback, recording, save or network-quality result is claimed here.
- Existing signing identity was used locally without creating/exporting/moving a key. Frozen T0647/source/archive, code10 source/APK and slice08 source were rechecked unchanged.

## Receive without installation
Download all files from this same fixed commit into a fresh directory. Run Python 3:

    python verify_and_extract.py

This performs no download or installation. It verifies file sizes and hashes, exact chunk order, the joined deterministic gzip, bounded decompression and final APK SHA. It refuses existing output and unsafe file names. The output is esv-slice08-code11-commons-debug.apk. Confirm the public certificate with Android apksigner before any separately authorized installation. Source-delta bytes are checked but never applied by this script.

The connector can verify Git object hashes/sizes and byte-read back small files. The receiving computer must verify all downloaded APK chunks and reconstruction; a commit is not a device test. No key, cache, local user file or unrelated project is needed for receipt.
