# Vault batch filesystem copy export

Production entry remains disabled. This source checkpoint extends the verified single-item engine without a new database, authentication owner or copy engine.

## Connected behavior
- Select 1–8 Vault MP4 objects, choose one local phone filesystem directory, explicitly confirm ordinary readable copies, and process sequentially.
- Exact frozen plan, bounded encrypted journal v2, one active scalar COPY_OUT, per-item outcomes and shared run budget. Existing v1 journal bytes and allowed phases remain compatible.
- Already verified copies are retained; cancellation and later failure do not delete them or any Vault original. Retry/continue is explicit, never an automatic replay of completed or uncertain creation.
- Lock/restart requires fresh authentication and exact directory authorization. Unknown output can be explicitly retained only after the original CAS outcome and all real resource drain are established. Unknown final decision cannot be skipped.
- Same single/batch adapter refuses automatic deletion of a present target when only URI/token/grant evidence exists. Confirmed absence is distinct from retained/manual-cleanup output. Single OUTPUT_RETAINED is a non-success terminal outcome.
- Credential recovery remains available for inactive durable batches; active work and unresolved resources still block conflicting operations.

## Limits
- 8 items and 8 explicit execution runs per batch are bounded implementation limits, not original product maxima. Each run shares a 30-minute deadline across its items.
- Only the finite local primary ExternalStorageProvider directory profile is supported. No persistent grant is taken, no arbitrary/cloud provider is admitted, and no directory name scan is used to claim old output.
- URI identity does not establish file incarnation. Tests prove absence-only cleanup does not delete a same-URI replacement; they do not prove every external replacement is detectable before write/open. Real provider/OS concurrency behavior remains unverified.
- No real user media, persistent credentials, new keys, device authentication, install or old denied probe was executed.

## Evidence
- First central build failed from the 2 GiB Kotlin compiler heap; app tests did not run.
- Second build used temporary 3 GiB heap and one worker without changing project gradle.properties. Actual dual compilation and app 4440 tests passed; 416 same-byte core/library results were reused. Total 4856, zero failures/errors/skips.
- Independent author tests are subsets/separate executions, not added to the central total. Runtime 36 and journal 61 use fixed/fake inputs; SAF 29 uses fake platform/profile fixtures.
- Full forced 13-module regression: 572 suites / 6366 items = 6364 PASS + 2 SKIP, zero failures/errors; exit 0, BUILD SUCCESSFUL in 6m55s. Both lint variants: zero errors, 85 warnings and 10 notes. The eight added warnings are seven UseKtx suggestions and one PluralsCandidate heuristic; no lint suppression was added.
- Android SAF UI, true process death, real Tink/SQLite and protected-display/device acceptance remain NOT_RUN. No production enablement is claimed.

- Final small delta bounds document-ID character count before a single UTF-8 allocation, then checks per-ID and accumulated bytes. Two new source/resource tests passed; the folder label is quantity-neutral. Final focused: 4858 passed, with 4442 app tests and dual compile actually executed. The final full result includes this delta; the earlier full-first run is retained separately.
