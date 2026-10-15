# Exact unadmitted Save recovery — 2026-10-13

## Defect and bounded scope
An explicit Resume can persist its owned RUNNING record before AndroidSaveRuntime submits the worker. A later journal read or a failed scheduling deferral may throw; existing cleanup releases the command, execution reservation and request lease while leaving a RUNNING task with no admitted worker. Finalized Recording intentionally skips ordinary background pause, so merely returning to foreground is not a complete repair. The preceding Resume29 report correctly disclosed this limitation and remains immutable.

This slice addresses only proven-unadmitted operations. It does not claim recovery for every active/accepted-worker fault or persistent database corruption. Original UI03 §8/UI-R13 requires truthful recoverable actions and explicit idempotent commands. UI04 T-16 covers interruption recovery and repeated cancellation.

## Approved plan
Use a narrow coordinator conditional transition under its existing lock plus an actual production admission/repair helper. Compare the complete owned record and exact operation/runtime; only the current unadmitted operation can return to existing WAITING_USER. Worker successful submission or Runnable entry permanently revokes compensation authority. Do not use executing=false as proof that no worker was admitted.

Attempt immediate compensation once. If an authoritative read or CAS is unavailable/ambiguous, preserve the true durable state and an ephemeral exact recovery capability. A separate explicit local recovery action performs one read/conditional state repair only, without transfer, capture, verification, commit or publication. Only after confirmed WAITING_USER can the user invoke the existing Resume/Reverify separately. Poll and foreground remain free of automatic repair writes.

Cancel synchronously retires the capability; two legal orderings are tested. Cancel winning before the repair linearization means no repair write. A repair that already crossed that point can complete first and normal Cancel follows. A late result cannot resurrect a ticket or clear a newer command owner. Do not promise to reverse an already-committed database transaction at the moment of the click.

Finalized Recording keeps its exact accepted input, artifact, transfer, verification, commit identity and zero retry budget. Local repair does not reconstruct capture consent or acquire source grants. Existing save protocol, serialization, database schema, dependencies and network/authentication policies are unchanged.

## Lineage
Direct predecessor: ExportRecovery37, manifest 47333eaabe848697ea5f22b94295167a7de442b953f2b23a73e2fc83c8a79524; it follows Resume29 (f3f87a7bdacaf3df43d406707f219c20e8b297117d4b25ec611fdacf1c61a955), which follows Picker29 (461ef7ecfae37b7267feb06fb1069431b483b9871a27b22468eaad0a). Last verified published ancestor is dbff3f4e5452fa3470de77ae1eb974f43a221d90. Publication parent is pending the actual direct predecessor, not fabricated from that older ancestor. Old frozen files and historical test reports must remain unchanged.

## Validation
Final third attempt passed in 3m5s: 77 actionable tasks, 18 executed and 59 up-to-date; all eleven requested targets actually executed. Fresh XML confirms Save120 across6 suites and App2250 across242 suites, with zero failures/errors/skips in each module. This includes new Save25 and App49, not added again to the totals. Debug/Release compile and lint passed; Debug has54 warnings/8 hints, Release55 warnings/8 hints, both zero errors. The only differences from the predecessor lint are six/five GradleDependency notices not emitted this run; no new issues and all other semantic issues are unchanged. No dependency was updated and historical warnings are not claimed fixed. Runtime90 and seven boundary checks passed. All1453 pinned inputs remain unchanged after execution. Independent source review is hash-bound; the final evidence review record accompanies the freeze. Baselines Save95 and App2201 remain separate historical stages. Tests must use the production handoff/repair helper plus real SaveCoordinator, including durable state assertions, not a copied launch algorithm or the earlier resource-cleanup-only assertions.

## Platform acceptance
Actual Android Handler/Compose click/lifecycle behavior, real SQLite/provider fault timing, physical device/emulator and APK assembly/installation remain NOT_RUN. No fresh APK is generated. Earlier OPPO, Windows, private corpus, historical missing/stale gate and PPT boundaries remain open. Only executed stages may be called PASS.

## Implementation review decisions
A caller Resume operation identity is retained only in the in-memory coordinator Runtime, separately from Recording's normalized zero-retry context. Cached WAITING proposals must equal a legal metadata-only transformation of the exact original record; arbitrary artifact, transfer, phase, commit or revision changes are rejected. The app retains the first actual proposal, so reconciliation after an ambiguous write does not extend its deadline.

An arbitrary executor exception is not proof of rejection. The helper revokes compensation and uses an atomic Runnable-entry gate: a queued wrapper that has not entered can be suppressed before resource release, while an entered worker retains ownership. This branch does not create repair authority. Unknown admission outcomes may therefore still leave RUNNING with only the existing Cancel/restart fallback; no broad executor recovery claim is made.

A failed Cancel after retiring a recovery ticket retains only a small Cancel-only task-ID fallback until terminal confirmation or a separately admitted new Resume. It never restores the retired repair capability. This preserves an explicit cancellation route even if the task-list snapshot failed.

The recovery UI has a distinct live Tasks host predicate checked on the main thread at click admission. Queued work uses a permanently retired page token plus runtime and command identity, without reading NavController on a worker thread. A repair already admitted before navigation may finish; this is not an automatic retry on navigation or foreground. Actual platform timing remains untested.

Repair-specific snapshot reads skip both destination refresh/outbox work and expiry scheduling. Ordinary snapshot paths retain their previous behavior. This prevents a local repair action from indirectly creating a media copy or scheduling unrelated cleanup.

## Targeted regression coverage
Production helper and real SaveCoordinator fixtures cover the twelve planned cases: post-RUNNING read faults; rejected worker admission; background/network deferral read or CAS failure; persistent read/CAS failure; explicit repeated repair; write-then-throw reconciliation and fixed expiry; submitted/entered/unknown worker outcomes; old/current task and operation identities; both Cancel latch orderings; exact resource release; finalized Recording and completed local continuations; missing list, page/foreground and restart fallback. Old source contracts were redirected to the real shared helper call site rather than deleted.

Fresh executed deltas are Save +25 and App +49; module counts above are verified from final XML, not inferred from annotations.

## Preserved failed attempts
Attempt 1 failed App test compilation after Save120 passed. A self-referential fixture property needed an explicit SaveResumeHandoff type; no App tests ran in that attempt, and previous App XML was excluded. Its log, input pin, original fixture and fresh Save XML are retained.

Attempt 2 executed Save120 successfully and App2250 with two failed fixture assertions. One expected an incidental duplicate network-policy check despite the actual correct denial/NETWORK result. The other counted a deliberately rejected setup submission as if no submission had occurred before local repair. Independent review confirmed the fixture mismatch. The revised checks prove an actual consulted policy denial and zero worker/media work, and compare submission counts before/after repair; only the separate explicit Resume adds one submission and one execution. No production call was added to satisfy a count, and no behavioral invariant was removed.

All production, resource and build inputs are identical across the attempts. Only SaveResumeHandoffTest changed: first its explicit type, then the two strengthened semantic assertions. Both complete failed-attempt evidence sets and exact input hashes remain included. Final passing results must refer only to the last fresh XML, never copied from a predecessor or failed attempt.

## Evidence
artifacts/ui/unadmitted-save-recovery-20261013 contains complete final App and Save XML, both lint reports, all three build logs, input pins, two complete failed-attempt archives, exact fixture corrections, production invariance checks, runtime/boundary results and independent review. COMPLETE_APP_EVIDENCE.zip includes both current modules, despite retaining the conventional archive filename. No thirteen-module rerun is claimed; the previous3383 census belongs to its earlier input chain.
