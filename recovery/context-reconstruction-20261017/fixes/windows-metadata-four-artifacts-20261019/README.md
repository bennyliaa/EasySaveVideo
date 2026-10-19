# Proposed four-artifact Windows metadata repair

Backup only. This is an independently reviewed minimal patch and source/byte/license evidence for four missing Gradle metadata checksums. It has NOT been applied to frozen T0647, signed code10, or any Windows source by this publishing task. No amended Gradle build or cold-cache test has run. The archive's internal NOT_UPLOADED label records its preparation stage before this backup commit.

Archive: windows-metadata-four-artifact-fix-20261019.tar.gz
SHA256: bae3d14a6323a885f26bfc6dba2f7591a30fa68447c4eea66b3c1b7fbaf000c9
Bytes: 73899
Patch SHA256: 1d89a3334d5eca58067996e4cf8bf8399e05140a8ca950137e99bd5fb8bd49ea
Before XML SHA256: 29b76573f3b03e4dab2257092454eb203c32d9517688991e8ae858cbcfd3b5d2
After XML SHA256: 5a94b42c6d27955fd4386dc30f1ee5a8966e0f0e14d5d7c3328f9ca51d6888af

The standalone patch is byte-identical to the reviewed archive copy. All 701 previous artifact records and verification settings are preserved; only four exact metadata artifact records are added. Official downloads and existing cloud/Windows hashes match. Guava and coroutines metadata declare Apache-2.0; JUnit declares EPL-2.0. This is metadata-only audit scope, not JUnit/runtime code admission or a closed-source legal guarantee.

Receive all files at one fixed commit, verify SHA256SUMS, and extract the archive only into a new empty directory. Review its README, PATCH_VERIFICATION.json and INDEPENDENT_REVIEW.json before applying to a separately authorized Windows development copy. Keep strict and verify-metadata=true. Retry the actual failing resolution and relevant compilation/tests, then compare all 95 admitted runtime bytes and lockfiles. A warm parsed-cache success alone cannot establish reproducibility; use a separately coordinated disposable cold metadata cache, preserving the known-good cache. No blanket trust or version/repository change is proposed.

This repair is independent from the sibling slice08 delta and is not already present in code10. No key, credential, APK or build cache is included. Public upstream license notices and technical ESV build paths are retained.
