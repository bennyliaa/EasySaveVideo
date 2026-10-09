# ADR 005 — reconstructed license admission gate

This document re-records the approved gate behavior after loss of the old checkout. It is not a byte-for-byte recovery of the old ADR or commit.

1. Exact coordinates, artifacts, transitive runtime code, native libraries and copied source/assets must have review evidence before integration.
2. UNKNOWN or unreviewed candidates may not enter implementation or a merge. Wrapping GPL/AGPL code in an Adapter does not remove obligations.
3. The current newly recovered graph is exactly 53 official components conditionally permitted with the recorded obligations, plus two user-owned SkinKit artifacts authorized for internal reuse. Material/Material3 is not in this set.
4. SkinKit/app public licensing is not invented. SkinKit external-distribution ownership evidence remains unresolved; it does not revoke previously authorized internal reuse.
5. This source checkpoint does not accept final Release APK/AAB notices, legal compliance or store approval. Final package/notice accessibility and release policy review remain separately unverified.
6. The new evidence bundle contains 100 files (manifest plus 99 payloads), not the lost original 73-file bundle. Official text/POM bytes are preserved; fresh artifact hashes and limited comparisons with surviving historical hashes are labeled distinctly.

See `third_party/current-runtime/IMPORT_MANIFEST.json`, `ACTUAL_RUNTIME_GRAPH_VERIFICATION.json`, `OBLIGATIONS_CN.md`, `SOURCE_INDEX.json`, and `RECOVERY_STATUS.json`.
