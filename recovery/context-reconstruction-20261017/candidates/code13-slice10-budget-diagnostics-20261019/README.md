# ESV code13 exact-delta candidate handoff

This seven-file directory transfers the already signed code13 APK as a dependency-free binary delta on the exact code12 APK, plus a direct six-file source overlay and compact verification evidence. No key, credentials, cache or collected user trace is included. The previously approved public Commons service contact remains intentionally present in source/APK configuration.

## Exact identities

- Base APK: 53,408,774 bytes; SHA256 b41ff7c237218922ab367d2743e2ae131e9185a7fa3ca54913dc60a49466fc02.
- Target APK: 53,408,774 bytes; SHA256 6bcf764fb2faf7fd5f104d8f960a0498037917cc0178faa57af2b7b3d41e3c11.
- Target: com.easysavevideo.app; Debug code13; versionName 0.9.0-ui-dev; minSdk31, targetSdk36.
- Target public signer SHA256: 18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5.
- Range delta: 953,938 bytes; SHA256 51ac2a0a3a962ad5c258480d4d6fc594470fe659a3c35775a754b99c48d078a1.
- Source archive: 327,027 bytes; SHA256 4179e442c1b3fb96e947e32cb4bd1ba2ec6386c04a923ba40fd72cf9b6268813.

The delta has 12 sequential operations: exact old-file range copies plus literal new bytes. It reuses 50,791,110 bytes and supplies 2,617,664 literal bytes in a standard ZIP envelope. Independent reconstruction compared every output byte with the original signed target and reran public signature, badging and alignment tools. Restoration preserves the existing APK signature; it never resigns, patches installed software or invokes Android tools itself. Initial offset-wise XOR compression was larger and is not part of this handoff.

## Receive on Windows or another Python 3 computer

Download all seven files from one fixed Git commit into a fresh directory. Use the already verified code12 APK as the base; it is not modified. Then run:

    python verify_and_restore.py --base-apk "C:\path\to\esv-slice09-code12-diagnostics-debug.apk"

The command performs no network request, source application, signing or installation. It verifies file hashes, the exact base identity, bounded ZIP/JSON structure, byte ranges, and complete target identity before publishing a new output. It refuses existing output and symlink inputs. It uses no external Python package, hardlink or extended file attributes. The output is esv-slice10-code13-budget-diagnostics-debug.apk. An interrupted final output copy is cleaned up when the script still owns that file; a process/OS crash may leave a partial file, which must not be installed and must not be overwritten automatically.

Verify the resulting SHA256 and public signer using Android apksigner before a separately authorized installation. If the exact base APK is unavailable, obtain it from the preserved code12 handoff first:
https://github.com/bennyliaa/EasySaveVideo/tree/29eb2a4f835786855a26e27319cd3f5a29a332f0/recovery/context-reconstruction-20261017/candidates/code12-slice09-diagnostics-20261019

Do not use an APK rebuilt locally with a different signing identity or arbitrary package bytes as the base. Exact SHA verification will reject it. A commit or successful restoration is not device acceptance.

## Source reconstruction

code13-source-delta-on-code12.tar.gz contains six changed/added source files (149,040 source bytes before compression), both complete pinned manifests, a textual diff and a machine-readable change list. There are no deletions. This is a direct overlay on exact code12 source, not on code11/T0647 or a bare old development checkout.

- Exact code12 source: 2,892 files, manifest SHA256 76f33eadb01902dfe0024f1bb21501f66b48429037dff2650e87edcab69c6b32.
- Target code13 source: 2,893 files, manifest SHA256 c5670131a7334e4910f07cd92dae2496b3e7e2a83b032170b1443f0850df8ffc.

Use a separate working copy, verify every baseline path/hash first, copy replacement bytes without newline or encoding conversion, and verify every final path/hash and preserved mode. The receiver does not extract or apply source/evidence archives. The source change incorporates the sealed slice10 budget attribution correction and Debug12 to13. Release9, approved Commons configuration, strict dependency verification and security gates remain unchanged. Windows compilation alone cannot resolve a mismatched local signing identity; no key migration is proposed.

## Completed verification and limits

All 13 default modules: 7,107 passed, two exact existing skips, 636 suites, no failures/errors. Debug/Release Kotlin+Java and Debug androidTest compilation passed. Dual lint has zero Error/Fatal (Debug87 warnings plus10 hints; Release97 plus10). Both runtime graphs exactly match the95 admitted external artifact hashes. This is a warm-cache cloud result, not Windows cold-cache reproducibility proof.

Actual APK: signature, package/version/SDK, ZIP CRC and4-byte/16KiB alignment verified;175 entries and30 DEX inspected. Independent review confirmed encoded BuildConfig,21 required class definitions, declared budget enum fields and actual transport diagnostic call-site instructions. Existing Release activation/UI guards are preserved; shared inert classes may remain.

The diagnostic correction makes an observed local connection-budget boundary explicit as CONNECT_BUDGET_SIGNAL / CONNECT_BUDGET_EXCEEDED. Shared5s TCP+TLS and15s overall limits, first-address selection, TLS trust/hostname/public-peer checks and retry policy are unchanged. It does not establish the remote network cause or claim a connectivity fix. No fabricated elapsed duration is assigned to the signal.

Diagnostics default OFF. Start only arms one next explicit Search; Start/refresh alone makes no request. Preview is bounded, memory-only and revocable, with no query/URL/host/IP/body/exception text. Existing source, safeSearch and save/recording consent gates still apply. No code13 device installation or live search was performed in this cloud work.

code13-verification-evidence.tar.gz contains build/test manifests and receipts, independent source/APK/DEX/delta reviews, receiver fault-test results, and original public signer evidence. Original local checkpoint no-upload fields describe their timestamp before this later repository handoff; they are not claims that the current directory was never published. Internal relative log paths in historical receipts identify local evidence, not additional files promised in this seven-file delivery.
