# ESV Tink 1.23.0 candidate import evidence

Status: license-compatible under documented obligations; **NOT ADMITTED** into ESV. No main-repository, Gradle or device changes are authorized by this archive.

- Read RECOMMENDATION.md and LICENSE_REVIEW.md first.
- CANDIDATE_GATE.json separates identified applicable licenses from remaining technical/provenance gates.
- candidate-maven/ contains exactly three added/replacement artifact triplets, each binary/source/POM stored once.
- projected-runtime-74.tsv contains all 74 projected coordinates, filenames and SHA-256 values. baseline-admitted-runtime-72.tsv preserves the compared baseline. Actual Gradle checks wait for the main owner to freeze A1/A2.
- evidence/ and licenses/ hold official metadata, source archive, embedded protobuf and upstream-derived license evidence. Full extracted trees and compiled harness classes are excluded.
- DISTRIBUTION_NOTICES_CANDIDATE.txt is an additional notice candidate, not a replacement for the current application's full third-party notices.
- StreamingAudit.java and test-host.log retain the 30-check synthetic host feasibility evidence, including two important EOF/close observations. No production keys or user media are included.
- Run python3 verify_bundle.py for read-only verification. IMPORT_MANIFEST.json hashes every payload file; HASHES.sha256 also hashes the manifest. The enclosing archive SHA-256 covers the checksum file as well, avoiding an impossible self-hash cycle.

The reports deliberately do not certify Android providers, real media seeking, release shrinking/packaging, or lifecycle security. Do not import full Tink Java, a separate protobuf runtime, KMS plugins or a crypto provider as an unreviewed workaround.
