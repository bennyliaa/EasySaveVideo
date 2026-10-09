# Candidate evidence only

This directory is a fixed-version license and dependency audit. It is not an active Gradle repository or admission file, does not modify EasySaveVideo, and does not authorize publication.

Start with AUDIT_REPORT_CN.md and CANDIDATE_GATE.json. CANDIDATE_COMPONENTS.json contains exact official binary/source/POM/module URLs and hashes. Binary paths in that index refer to the original isolated audit workspace; source and metadata evidence_path fields are relative to this directory. This import bundle intentionally contains no runtime AAR/JAR binaries other than source archives and a small historical attribution source archive. The original official runtime bytes remain in esv-media3-audit/official-maven.

PROPOSED_RUNTIME_DELTA.tsv is a proposal, never the live allowlist. Merge only the precise reviewed versions/hashes when adoption is authorized, implement the notices/CC rights conditions, then run the actual App Debug/Release graph and build tests. The 4 replaced old GAVs are not an alternate approved Media3 closure.

resolution/reproducer preserves the exact audit build scripts as provenance. To replay, use their original layout: esv-media3-audit/{gradle-project,official-maven,baseline}, sibling android-build-home with the recorded Gradle/Kotlin plugin API, and isolated GRADLE_USER_HOME. Do not run the scripts from their nested bundle location without restoring the layout. No Android plugin/build was run in the audit.

This is an engineering open-source license audit, not a full patent, private contributor-contract, content-rights, store, or final application distribution approval.
