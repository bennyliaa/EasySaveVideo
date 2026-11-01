# Explicit evaluation session and sanitized export

Update: [Joint and independent recording modes](SAVE_EVALUATION_MODES_20261018.md) adds explicit JOINT and INDEPENDENT_RECORD modes and JSON schema version 2. The original gated behavior described below remains the legacy FALLBACK default only; JOINT allows ③ before either primary result, and INDEPENDENT_RECORD explicitly skips ①/②.

This second slice adds a harness-facing, memory-only collection state machine on top of DiscoverySaveEvaluation. It does not wire production browsing, playback, downloading or recording to a test. Existing app save-origin/history tracking serves production history and must not be repurposed as automatic evaluation consent. No existing Discovery evaluation session/export path was found in the inspected source.

## Lifecycle and actual denominator

1. Construct DiscoverySaveEvaluationSession with an explicit SYNTHETIC_FIXTURE or OBSERVED_SESSION label; call begin exactly once.
2. register normalized PendingVideoCandidate objects. This preserves distinct-page identity and all provider origins without starting any save test. At most 500 unique pages and 32 origins per page; overflow fails without dropping existing data.
3. Call beginPage only when the operator actually begins this page's evaluation. This is the sole N_actual start event. Missing results after it still count in the common denominator.
4. beginMethod returns an opaque attempt ticket bound to this session, page and method. finishMethod accepts only that exact live ticket and a terminal SUCCEEDED, FAILED or BLOCKED result. Unknown/forged/cross-session/duplicate completions, duplicate starts and outcomes without starts are rejected. Failed attempts cannot be retried into a favorable outcome within the same run; define a separate run for retry evaluation.
5. SAVE3 cannot even begin until SAVE1 and SAVE2 have both completed FAILED. Active, blocked, succeeded and untested primaries do not qualify. This enforces event ordering inside the harness. A caller can still misreport events: the harness cannot prove an actual device operation occurred or authenticate its result.
6. snapshot returns current counts. finish seals the run, allowing partial observations; later registrations, starts and completions are rejected. Repeating finish returns the same sealed report. Unfinished attempts remain NOT_TESTED rather than invented failures, with unfinishedMethodAttempts exposed separately. FINISHED means collection closed, not all planned tests passed or completed.

All transitions are synchronized. Registration validates the candidate identity transiently, then retains only its hashed page ID, bounded provider IDs, start state and method outcomes. URL (including query), metadata, title, verification state and candidate object are not retained. finish clears the internal page/attempt maps after constructing the sealed aggregate report. Provider count maps are runtime unmodifiable, including through Java or mutable casts. Events are explicit method calls; there is no background subscription or collector. The first slice's same-denominator shares, source overlap, partial coverage and null unknown rates remain unchanged. Snapshots and reports contain aggregate counts; no raw event log is persisted.

## Local export and source lookup

DiscoverySaveEvaluationJson.export(report) returns a versioned JSON string; it does not write files, upload, or open the network. The caller may explicitly save it locally. Maximum UTF-8 size is 1,048,576 bytes. Oversized reports fail without returning truncated JSON. The exporter writes only fixed ASCII keys/enums, generated source ordinals, integers, booleans and finite numeric/null rates, so untrusted strings never require ad hoc escaping or enter JSON.

Default output omits URL, page ID/hash, title, query, timestamps, original provider IDs and private instance addresses. Providers become source-1, source-2, etc. Use localProviderMapping on the SAME report to obtain a memory-only sourceIdFor(alias) lookup for the operator. Its diagnostic string is redacted; never serialize/log/upload the mapping together with the export. Original provider IDs remain in the local report for this lookup; the caller owns the report/mapping lifetime and should release both when local review ends. They are not persisted by this module. Aliases are stable within that report. They are not cross-run identifiers and may change as later snapshots gain providers. The local original provider ID may be resolved through the evaluator's existing source configuration to identify the actual source; the exporter does not inspect that configuration or fetch an instance URL.

The JSON evidence label remains explicit even for an empty session. OBSERVED_SESSION is a caller declaration, not proof. Synthetic output cannot be presented as real search/save effectiveness. The test fixture written under the module build directory is explicitly SYNTHETIC_FIXTURE and is used only for independent JSON parsing.

## Remaining device integration

No production UI entry point or actual save/record terminal event is connected. A later explicitly authorized device-evaluation adapter must: provide an opt-in run entry, register the real candidate snapshot/source membership, issue beginPage and method tickets at actual starts, correlate final persisted-media results to those tickets, and close/report partial runs on interruption. It must validate real result evidence, distinguish save completion from video detection/record initiation, and ensure method3 starts physically after both primary failures. The user-selected emulator with preserved data remains the future acceptance target; no device acceptance is implied here.

No dependency, Android network policy, source admission, permission, production UI or upload behavior changes in this slice. The previous slice's evidence remains under evidence/metrics-slice-01 outside the source tree.
