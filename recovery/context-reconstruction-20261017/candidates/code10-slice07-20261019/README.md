# ESV slice07 Debug code10 candidate

This directory transfers one signed test APK using deterministic gzip split into three parts of at most 8 MiB, within the connector payload limit. It contains no keystore, credentials, dependency cache, or device/user data. The APK contains its public signing certificate as required for Android signature verification.

## Candidate identity
- com.easysavevideo.app; versionCode 10; versionName 0.9.0-ui-dev; Debug; minSdk 31; targetSdk 36.
- APK bytes: 53,300,642.
- APK SHA256: e21952c29f8f5481611511d6b9fb47ec5db84bf7368db59c01b77313596fc262.
- Public signing certificate SHA256: 18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5.
- Based on frozen slice07 T0647, whose complete source handoff is in this branch at part07, commit ac299997df061fc37347e534c21c144abd807e67.
- T0647 archive SHA256: fca4b4359844f51735aceb3925241a89d10d7a8a4f4a46cafe43ce06f80a0859.
- Original source manifest SHA256: c5f525f5c8ed6dd946814902fece15664ed8720fa5925a73a5a02cef518ab805.
- Candidate source manifest SHA256: 3a4605ea154c51670e11fa008d7af9c3e2fa13acd283ea24450d7440cef336c6 (2872 files).
- Only source change is the attached Debug output versionCode 10 override. Frozen source is unchanged; release retains code9. This candidate does not include later slice08 development.

## Verification and limitations
- 7,021 offline JVM tests passed, 2 pre-existing skips, zero failures/errors. Debug/Release and Debug androidTest code compiled. Both lint variants have zero errors; Debug has 87 warnings, Release 97 warnings, plus 10 Hint entries in each XML. Both runtime inventories matched 95 admitted external artifacts under strict dependency verification.
- VERIFY_PASSED.json is the immutable pre-packaging receipt; its apk_built=false records that stage. FINAL_APK_VERIFICATION.json is the subsequent completed APK receipt. APK v2 signature, ZIP CRC and zipalign passed. Debug evaluation class definitions were verified in the actual DEX files.
- Only offline/static checks have completed. Runtime UI, real searches, save/record results and installation remain unexecuted for this APK. No real-network success rates are claimed.
- Commons is compiled disabled with an empty contact. It cannot be enabled only through this APK's app settings. No SearXNG default instance was added or certified usable. This candidate is intended for offline/settings acceptance.
- Original existing signing identity was used without creating, moving, exporting, or uploading a key. Its certificate matches the reported phone code9 certificate, but installation/data preservation still require device-side verification.

## Receive on Windows or another Python 3 host
Download every file in this directory from the same fixed commit into a fresh directory. Then run:

    python verify_and_extract.py

The script checks all manifest file sizes and SHA256 values, exact part order, joined gzip identity, bounded decompression and the final APK identity. It refuses to overwrite an existing APK and performs no network request or installation. The produced file is esv-slice07-code10-debug.apk. Verify its public signer with Android apksigner before an authorized device installation. A browser's display of a Git LFS pointer or partial download is not a valid part and will fail verification.

Git object SHA/size and small-file byte readback can be checked by the publishing connector. Its binary readback capability is limited; full downloaded-part SHA256, reconstruction and APK checks must also be performed by the receiving computer. A successful commit alone is not a runtime or device test.
