# Discovery callback timing slice08 delta

This separate development delta is NOT included in the signed slice07 code10 APK. It adds opt-in bounded monotonic provider callback timing; it does not claim network/first-visible performance results or real-device acceptance.

Archive: discovery-latency-slice08-delta-20261019T010239Z.tar.gz
SHA256: d42936099ec4270b92fd914c0fc69c87ac9383a643869dd10476962b80530171
Bytes: 330873
Required base: esv-slice07-handoff-20261018T0647Z.tar.gz
Base SHA256: fca4b4359844f51735aceb3925241a89d10d7a8a4f4a46cafe43ce06f80a0859
Base source manifest SHA256: c5f525f5c8ed6dd946814902fece15664ed8720fa5925a73a5a02cef518ab805
Result source manifest SHA256: b76fe7d9ce43da5e0400fd30a068ccc3171092b5495bed4be6f7008c7990b871
Result source files: 2876; changed paths: 5 (four new plus one modified).

Author verification: 444 Discovery JVM tests passed, including 23 new focused tests; Debug and Release Kotlin compilation passed. Independent source review and XML test evidence are retained in the archive. This is not a fresh full-project test run. Live network performance, first-visible performance and device tests remain UNMEASURED/UNEXECUTED.

Publication preflight rechecked all 47 archive member paths/types, 46 recovery-manifest file hashes, all 2876 base-plus-delta source hashes, and the 444-test XML totals. No source files were overwritten during this verification. The archive is the unchanged author-sealed delta.

Receive all files at one fixed commit and verify SHA256SUMS. Restore the exact T0647 base to a NEW empty development directory, then apply only the archive source overlay and verify every result hash against its evidence/SOURCE_MANIFEST.json. Do not overwrite frozen source or code10. Read RECOVERY_MANIFEST.json, FINAL_VERIFICATION.json and the timing design document before use. The base handoff is available on this recovery branch at part07, commit ac299997df061fc37347e534c21c144abd807e67.

This delta does not include the sibling proposed four-metadata checksum repair. If that separate repair is later applied, it creates a further source state requiring its own manifest/checks. No key, credential, APK, dependency cache or user/device data is included; technical cloud paths, container hostname and synthetic test metadata remain in original build/test evidence.
