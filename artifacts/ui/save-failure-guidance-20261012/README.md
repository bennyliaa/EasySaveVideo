# Verification evidence

- VERIFICATION.json: final actual test counts and verification boundaries.
- SOURCE_SHA256.tsv: 13 final code/resource/test files, independently verified.
- INDEPENDENT_SOURCE_REVIEW.md: read-only review and its limits.
- build1-focused.log: both Kotlin variants and first 17 focused tests passed.
- build2-full.log: both final business-source compiles passed; full App test run
  caught one test-only resource-count assertion (29 expected, 28 actual).
- build3-full.log: complete corrected run passed, including App/save JVM and lint.
- junit/: final full App and save XML results, 1596 tests total, none failed/skipped.
- lint-results-debug.xml / .txt: actual Debug lint, 0 errors, 60 warnings, 8 hints.
- build4-lint-release.log and lint-results-release.xml / .txt: separately executed
  Release lint, 0 errors, 60 warnings, 8 hints.

Environment-specific absolute source/home roots in delivery logs/reports are replaced
with ESV_SOURCE / USER_HOME. All 175 JUnit XML hostnames are cloud-jvm. Tests use isolated model/fixture data; no user media,
site credentials or authenticated browsing data was collected. Artifacts do not
claim Android rendering, device, emulator, network or recording acceptance.
