# Ordinary Commons JSON Unicode validation correction

Baseline: `19e51aa50705782dd4087dd3bfd2e7b8cfa19453` (SearXNG first-layer candidate milestone).

The inherited original Commons reader accepted malformed `\u+041` and `\u-001` escapes because numeric conversion permits a sign. It also accepted some non-ASCII numeric/hex characters. `StrictJson.unicode()` now requires exactly four ASCII hexadecimal digits before the existing conversion. Offset advancement, surrogate-pair handling, UTF-8 strictness, limits, provider error types and APIs are unchanged.

Nine new tests cover positive BMP/control/mixed-case digits and surrogate pairs; signs, non-ASCII lookalikes and invalid characters in each position; truncation; nested values and keys; duplicate decoded keys; exact string/depth/byte bounds; cancellation; fragmented provider streams; response closure; sanitized parser-level failure; fresh same-query recovery; and exact/one-excess-byte stream budgets. Before the correction, four of nine regression tests failed. The same tests pass after it.

Verification command: `docs/commons-unicode/verify.sh`. This reuses the established strict offline full JVM, debug/release compile and lint, exact74 runtime admission, source/ABI/stream/recording/library gates, and all-channel compile/identity matrix. It requires exactly 2,184 JVM tests (the prior 2,175 plus nine), no failures/errors/skips, unchanged per-module counts except Commons, zero lint errors, and unchanged build inputs throughout. Generated results are under `evidence/final/`; the red reproduction is preserved under `evidence/`.

An initial full aggregate passed, then the inherited distribution host wrapper correctly refused the changed Commons file against its old frozen baseline. That attempt is preserved under `evidence/first-run-historical-gate/`. The corrected verifier keeps the historical wrapper untouched and repeats the same channel compile/admission commands with actual generated BuildConfig identity checks; it does not claim the old standalone distribution host suites were rerun.

The previous SearXNG frozen milestone and its gap report remain historical and byte-identical. Its Commons parser gap is closed by this correction; this does not rewrite its earlier acceptance claim. The previous milestone's unrelated standalone historical host and SQLite suites remain its evidence and are not represented as newly rerun here.

This is a bounded production parser correction, with no dependency, API, provider-endpoint, configuration or authority change. No APK/AAB, Android device execution, live query, deployment, parked rule-runtime review or Vault work is included. M0 and two-layer discovery acceptance remain unfinished.
