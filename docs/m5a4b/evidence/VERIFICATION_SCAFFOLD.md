# M5-A4b verification entry point

Run only after the production candidate and independent ordinary-folder review inputs are complete. The aggregate is owned by the single designated Gradle owner. No APK/AAB assembly, device/account operations, payment, publishing, Vault review, or blocked M7-D review is included.

## Freeze and verify

1. Determine the exact aggregate JVM count after adding the candidate tests. The historical count is 1,875; do not use that count as a minimum or accept a guessed count.
2. Supply the independently established exact count when freezing:

   `ESV_EXPECTED_JVM_TESTS=<exact total> python3 docs/m5a4b/evidence/generate-frozen.py`

3. Run the single serialized aggregate:

   `scripts/verify-folder-destination-workspace.sh`

`ESV_TOOLCHAIN_ROOT` and the established Java/Android/Gradle overrides are supported for cold restores. `ESV_EVIDENCE_DIR` redirects generated reports; it may not point at historical documentation or evidence. The generator refuses to freeze while any ordinary-folder review hook is absent. The verifier checks exact source bytes, executable modes, newly added input membership, and the fixed expected JVM count.

## Current-only inherited copies

`verify-inherited-wrappers.py` proves that inherited executable bodies match their historical source, allowing only current manifest/output/self paths, script relocation, and Android helper target-v7 assertion substitutions. A4a independent Kotlin tests remain at their original protected path. The two inherited destination SQLite suites still execute their original v1–v5-to-v6 contract models from the unchanged production v6 schema. Their helper-shape assertion now recognizes v7; the new folder suites own v7 migration coverage.

The baseline admission gate protects all 3,270 files and every executable mode from commit `046209b39d932f08910a8b79f33d4d02d6183ccd`, except its exact production integration allowlist and the two exact enum expectation replacements in `OrdinaryDestinationAdmissionTest.kt` (add `FOLDER_COPY` and change preference count 3 to 4). All other bytes of that test and every other historical test, gate, evidence, manifest, dependency-admission, SkinKit, and rule-interpreter file remain protected. New source/tests are restricted to ordinary-destination directories, current evidence, and named scripts.

## Required new review inputs

- `scripts/verify-folder-destination-sqlite.py`
- `docs/m5a4b/evidence/independent-folder-sqlite.py`
- `docs/m5a4b/evidence/run-independent-folder-review.sh`, plus the complete review sources it compiles

The aggregate contains both debug/release compilation and lint, all ten JVM modules with an exact total, exact74 admission, applicable historical SQL/JavaScript/JVM-host suites, inherited independent reviews, existing isolation gates, current folder reviews, and the seven-channel matrix. The matrix ends with the implicit `unconfigured` default; no store/payment path becomes configured.

## Finalization checklist

- Reconcile the admission allowlist with the actual completed integration diff; do not add broad package exemptions or permit baseline test edits silently.
- Confirm the independent runner's compiled inputs are included in the frozen manifest and that its required toolchain hashes match the existing exact-eight host toolchain.
- Confirm any necessary current-only compatibility adaptation is enumerated in `verify-inherited-wrappers.py`, with historical bodies still intact.
- Freeze after all source/review edits, then perform the full aggregate. Re-freeze and rerun after any further included-input edit.
- Distinguish host contract evidence from Android SAF/MediaStore/device acceptance, which remains unrun.
