# Save Resume runtime replay protection — 2026-10-13

## Defect and bounded scope
The actual Resume callback could enqueue twice before UI recomposition disabled it. The second command received an already RUNNING record from SaveCoordinator.resume, failed the existing execution reservation, and paused the first command's active download through scheduling deferral. This violates UI03 §8 idempotent explicit commands and UI04 T-16 recoverable interruption behavior.

This slice adds in-memory command ownership, not a new scheduling or product policy. The original esv-save-transfer worker already has core/max=1, queue=1 and a global executing.compareAndSet(false,true) slot. The same reservation moves before the domain WAITING_USER transition; a competing task stays waiting rather than being mutated and deferred. Network, credentials scope, domain/storage/wire and copy/publication policy remain unchanged.

## Required invariants
Synchronous per-task Resume admission before lease/queue work; exact task snapshot and newly allocated operation identity; explicit Cancel supersedes pending Resume; background retirement does not reinterpret lifecycle pause as user cancellation; completion/rejection only releases its own command; execution-slot cleanup exactly once, including exceptions after early release and replacement by another owner. No automatic retry or foreground replay.

Independent pre-build review identified a potential double-release window in the initial handoff design. It was resolved before the pinned build with a Resume-scoped exact-once release capability shared by the command finally and all launch release paths. The production default callback remains unchanged for other callers. The reviewer accepted the corrected source, not the early design.

## Lineage
Direct code predecessor: immutable Picker page return29, manifest461ef7ecfae37b7267feb06fb1069431b4837578f483b9871a27b22468eaad0a, still awaiting publication. Parent verified EditorBack c26df3f338ed6b183438960ea1beb7b5d6dad300 then A/B dbff3f4e5452fa3470de77ae1eb974f43a221d90; dbff is a published ancestor, not permission to omit Picker. Three earlier code freezes and comprehensive18 evidence remain unchanged. No invented direct parent SHA.

## Validation
First full Gradle attempt passed in 3m11s: App 2156 tests, zero failures/errors/skips, 237 fresh XML suites. All ten requested App test/Debug+Release compiler/lint/runtime tasks actually executed. 36 newly added tests are included, not added again to the total. Both lint variants retain the same 60 warnings and 8 hints, zero errors; semantic issues match the preceding Picker freeze ignoring line shifts. Exact runtime90 and seven boundary checks passed. All 1441 pinned inputs were unchanged after execution. Independent source and evidence reviews passed; the hash-bound review records accompany the freeze. Tests must invoke the production command gate and actual SaveCoordinator with fake ports/controlled executors; this remains JVM evidence, not actual Android handlers, UI timing or network traffic.

No APK assembly/install/device/upload. Old code8 c794ef candidate is unchanged and excludes later fixes. Existing private-corpus, historical Git/anchor gates, OPPO, Windows and PPT limitations remain open. Separate Library recovery-retry visibility finding is not mixed into this slice.

## Exact failure/retry boundaries
Lease creation, control queue rejection, journal read before transition, a failed RUNNING journal write that retains WAITING_USER, and worker queue rejection each release their exact command and permit a fresh explicit retry in the tested recoverable state. Cancel supersedes a queued Resume and fences its operation cancellation token even if its durable Cancel queue rejects. Background retires pending Resume authority and retains the existing background-pause mechanism; foreground does not replay the click.

An already-existing exception boundary remains: after coordinator.resume has durably written RUNNING but before worker admission, a launch journal exception can leave a RUNNING record without an admitted worker. The new test verifies slot, lease and command cleanup only. It does not prove immediate Resume availability for that RUNNING record; normal recovery or Cancel may be required. This slice does not broaden journal recovery policy.

## Diagnostic attempts preserved
Standalone Kotlin/JUnit run 1 compiled and executed 36 tests but reported eight failures caused by the incomplete fake TestStore commit inspection. The fixture was corrected without weakening production behavior; run 2 passed all 36. Both compile logs, JUnit logs, exact commands/source hashes and runner are retained in COMPLETE_APP_EVIDENCE.zip under diagnostic-smoke/. This diagnostic is distinct from the full Gradle App run.

## Targeted regression coverage
12 production command gate/reservation tests; 17 controlled-queue tests using the real SaveCoordinator and fake ports; 7 runtime source contracts. Includes a reproduction of the former double-Resume pause, same-task repeat, different waiting tasks sharing the existing slot, operationId/revision freshness, Cancel at multiple admission gaps, old callbacks not releasing newer owners, lease/queue/journal exceptions, exact-once release after replacement, and background/foreground retirement. The queue fixture mirrors the thin Android adapter around production gate/domain logic; it does not instantiate AndroidSaveRuntime or execute real Android Handler callbacks. Source contracts verify the actual adapter wiring.

## Device acceptance still required
- Rapidly tap Resume on one waiting task and then two different waiting tasks; ensure only the currently admitted transfer runs and waiting task can be explicitly retried.
- Tap Cancel during admission and while transfer starts; verify no later stale Resume starts or changes the new task state.
- Background/foreground during queued Resume; verify no automatic replay, normal pause and fresh explicit Resume.
- Observe unavailable network/storage and lifecycle callbacks on API31+ with actual handlers. No device/network/Compose timing claim follows from JVM tests.
- All earlier device, private corpus, missing Git/stale historical gate and PPT limitations remain independently open.

## Evidence and scope
Evidence: artifacts/ui/save-resume-replay-20261013/. INPUT_MANIFEST binds all 1441 inputs; the six changed files are two modifications and four additions. COMPLETE_APP_EVIDENCE.zip contains all 237 App JUnit suites, both lint XMLs, full build log and preserved diagnostic attempts. The source diff includes modified files; complete added production/test files are included in the freeze whitelist.

No thirteen-module rerun occurred for this slice. The previous comprehensive input chain had 3383 registered tests (3381 passed, 2 private-corpus skips), and remains separate historical evidence for its older inputs. The earlier exact gate BLOCKED/FAIL results are not relabeled by this App run.
