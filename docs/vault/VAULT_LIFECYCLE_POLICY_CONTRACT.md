# Vault lifecycle auto-lock policy

Date: 2026-10-16 UTC. This is a pure connection inside the existing metadata reducer. Production Vault remains disabled.

## Confirmed rule and minimal integration

The user approved leaving the Vault page or entering the background as the auto-lock anchor, cancellation when returning before expiry, and a locked state after process restart on 2026-10-16. PR-18's durations remain exactly immediate / 1 / 5 / 15 minutes. The earlier product-trigger question in `VAULT_PURE_STATE_RECOVERY_CONTRACT.md` is resolved by this contract.

The implementation adds one `VaultEvent.LifecycleObserved(inVaultRoute, foreground)` and one nullable `VaultState.inVaultRoute` field to the existing contract/reducer. It reuses the existing foreground flag, monotonic time validation, authentication and presentation epochs, window ID/policy revision/anchor/deadline, cleanup binding, retry ledger, and descriptive wake effects. It adds no owner, timer, scheduler, platform type, I/O, authentication, key, credential, effect executor, or production registration.

`inVaultRoute == null` preserves the earlier low-level explicit-window API for isolated compatibility callers. A lifecycle-aware caller must send a full current `LifecycleObserved` before unlocking or presenting. Once observed, no event returns the route field to null. `start` always returns a new locked, covered state with required cleanup; it does not restore route/window/session metadata from an old process. The nullable compatibility path is not production lifecycle enforcement.

## State and event mapping

Presence means `inVaultRoute && foreground`. Only the transition from presence to absence starts protection and a window. A first lifecycle observation already absent is handled as a departure, including if it is attached to a previously open low-level session.

- First departure from an open session: cover/stop/clear and require exact new cleanup, then retain the chosen session grace window. Immediate policy performs one full lock/cleanup. An active authentication attempt is revoked instead and its pending retry write remains available for trusted acknowledgement.
- Repeated absent snapshots and the second condition becoming absent update the relevant booleans only. They do not refresh the window, its ID/anchor/deadline, or cleanup binding/deadline.
- Returning only one condition leaves the window in place. Returning both conditions cancels only the exact current window, after the reducer's pre-event expiry checks. It never automatically shows content or resumes playback. Current cleanup and a later explicit `SurfaceVisible` are still required.
- `ForegroundEntered` / `BackgroundEntered`, after lifecycle activation, update only the foreground part through the same mapping. They cannot bypass it. Before activation, they retain the low-level behavior covered by the original tests.
- `SurfaceCovered` / `SurfaceVisible` remain presentation events, never route changes. A popup/occlusion is not page departure. `SurfaceVisible`, unlock requests, successful authentication and retry-reset acknowledgement cannot authorize presentation/opening while the known route is absent.
- After activation, old explicit `AutoLockWindowStarted` calls are ignored while present and cannot replace an existing absent window, including with a larger ID or newer policy revision. An old cancellation cannot cancel while either condition is absent. The lifecycle path still uses the same window start/cancel primitives.
- A policy change recalculates the deadline from the original departure anchor, as before; it does not move that anchor. No event may revive an expired session.

## Time, ordering and failure

The existing `reduce` validates process/tick/revision then calls `expire()` before handling every event. Therefore `now >= deadline` locks first, including return, cleanup, authentication, policy-change or stale callback at that tick. Invalid negative/backward/foreign ticks fail closed without replacing the observation watermark. Window-counter and time-addition exhaustion fail closed without wraparound.

No independent clock or deadline domain is introduced. Existing wake effects remain descriptions and a future owner must serialize transitions, execute authority revocation/publication atomically, and supply current monotonic time. These pure tests do not verify an Android lifecycle adapter, recents protection, real cleanup, crypto, storage durability, or device behavior.

## Verification plan

Retain the existing 40 pure reducer/recovery/invariant tests unchanged. Add deterministic tests for all four policies; both departure and return orders; duplicate snapshots/legacy signals; first already-absent snapshot; original anchors across policy changes; exact deadline boundaries; old explicit-window bypass attempts; surface occlusion; cleanup completion/failure/timeout and old binding rejection; authentication reservation/authentication/reset departure with late callbacks; stricter retry cooldown retention; restart; invalid clock/counter failure; and bounded event sequences. Build owner runs central Gradle serially. This worker uses cached Kotlin/JVM17 and JUnit only, with real captured logs and base/target hashes.

## Isolated verification result

The cached Kotlin 2.2.10 compiler compiled the model, domain, and tests separately with JVM target 17 on OpenJDK 21.0.12.1. JUnit 4.13.2 passed all 58 tests: the original 40 unchanged tests plus 18 lifecycle tests, including 729 mixed three-event sequences. The first run compiled both main modules but rejected two cross-module nullable-property smart casts in the new test; the test now uses a local non-null window identity. The second run exited 0 for compilation and JUnit. These are JVM contract results, not Android/device/real storage/security acceptance. Central Gradle validation remains the build owner's separate step.
