# Direct/Stream evaluation binding foundation

Scope: an optional app-side adapter between explicit evaluation-session tickets and the existing real Direct/Stream execution completion. No evaluation UI is enabled, no page/source capability bridge is supplied, and no ordinary browsing/download/recording is automatically an evaluation. Recording ③ and private-network saves are excluded.

## Callable seam and exact identity

AndroidSaveRuntime owns an initially empty SaveEvaluationTaskLinks. A future explicit test controller may call armSaveEvaluation with the exact destination-admission object that will be used by the selected save, the intended method, the active evaluation session and a previously registered/explicitly begun page ID. This is not wired from current UI. Merely possessing a matching URL, equal copied admission or persisted task ID cannot create a live link.

The actual DIRECT and STREAM coordinator acceptance branches call accepted. Only the same admission object, matching creation/task ID and method, a non-private fresh QUEUED revision-0 record with no artifact/receipt, and existing=false may issue a session method ticket. Accepted commitId and operationId are retained as additional final-result fences. Attempts are not created for resumed/restored/idempotent-existing rows.

The real runSaveExecution finally path calls afterExecution. With no armed/bound context it does not read a record or allocate an evaluation event. A bound completion checks the exact task/method/commit/operation and uses the existing strict verified-commit predicate. Notifications cannot control Save submission/execution/cleanup, retry a media task, change its state or propagate observer exceptions. Positive no-worker-handoff paths only record BLOCKED. Recording uses a different execution path and has no new callback.

The binder is memory-only and capped at 100 entries. Exact opaque handles may abandon one association; finishSaveEvaluation clears all associations for that session before sealing its report. Unknown/stale/closed/duplicate callbacks cannot change a sealed report. Pending arms that fail earlier than acceptance require the future controller to abandon them or finish the run; there is no fabricated result for a preflight refusal. New operations cannot reconstruct a binding from prior state.

## Typed result classification

- VERIFIED_COMMIT: final SUCCEEDED, CLEANUP, no error, and matching commit/artifact/verification receipts, bytes/hash and current selected-output validation. MediaTaskActivitySaveOutcome remains the strict Direct/Stream predicate; the classifier does not broaden it to RECORD.
- DEFINITIVE_FAILURE: final FAILED, no receipt or ambiguous COMMITTING phase, EXECUTING or VERIFYING phase, and an explicit allowlisted error: INVALID_MEDIA, INCOMPLETE_TRANSFER, HTTP_NOT_FOUND, NETWORK_TIMEOUT, HTTP_FAILURE, RESOURCE_CHANGED or INVALID_RANGE. The run's actual attempt failed; this does not assert a site's permanent incapability.
- BLOCKED: cancellation/interruption/waiting, supported typed policy/auth/storage/network-precondition refusals, or positive evidence no worker was launched. These never unlock fallback.
- UNKNOWN: absent/unreadable/mismatched records, active/queued states, provisional receipts, commit/recovery/ownership ambiguity, preflight failures or other/new error kinds. UNKNOWN does not finish the method ticket; it stays untested/unfinished and the report is partial.

An accepted method ticket must still be tied to a real explicitly selected page by the future browser/evaluation controller. The adapter cannot prove that caller-supplied page ID is the actual viewed page. No URL guessing or production-history substitution is introduced. Without that next explicit opt-in/source-capability integration, no real evaluation report is collected by the current app.

## Tests and remaining acceptance

JVM tests exercise real SaveRecord contracts (including conserved Stream output), exact admission identity, commit and operation mismatch, mutable-proof invalidation, no-context/no-read, duplicate and unrelated completions, closed sessions, worker refusal, narrow error classification, bounded pending arms and actual source-level runtime call sites. Application compilation checks ensure the seam is callable in existing runtime code, rather than an isolated classifier.

Still required separately: debug opt-in UI, exact candidate/browser-document capability bridge, explicit real task/attempt start policy, device tests of pauses/resume/commit races, and any recording-③ binding or output completeness acceptance. Prefix recording publication is not a proven full-output evaluation success. No device, network, upload, deployment or actual success-rate observation belongs to this slice.

Worker handoff carries the exact opaque binding capability through launch and finally. A task ID alone cannot consume an entry; close/abandon and rearm with the same ID produces a new capability. Old callbacks cannot read or remove the new owner. Duplicate acceptance cannot erase an already-issued attempt.
