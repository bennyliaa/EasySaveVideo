# M8-B4 independent ordinary OS-signal review

Status: **PASS for the corrected frozen host, Android API-double, and source-wiring scope**, verified on 2026-10-07. No unresolved serious finding remains within this reviewed scope. This is not Android-device acceptance, temperature or battery-threshold calibration, measured energy saving, or completion of all Discovery work.

## Scope and separation of evidence

Independently reviewed the new `DiscoveryOsPowerBridge`, `AndroidDiscoveryOsSignalSource`, and `DiscoveryLatestDelivery`, plus their wiring in `AndroidDiscoveryController`, power presentation, controls, and debug/release scenario factories. The host tests compile the actual bridge, coalescer, Android adapter, and platform-neutral Discovery implementation. The Android adapter is compiled against explicitly local API-shape doubles. The actual controller and Compose UI are source-inspected and statically checked, not executed by these fixtures.

The implementation owner alone edited production sources and ran Gradle. This reviewer created only the independent evidence and this report. No dependency was downloaded or added for the review; the standalone compiler/runtime/test artifacts were checked against the historical admitted hashes.

## Findings and corrections before freeze

1. **Boundedness defect reproduced and fixed.** The initial reconciliation function recursively called itself after each open/close. A deterministic fixture that invalidated the generation during each of 10,000 opens reproduced `StackOverflowError`. The owner replaced recursion with an iterative pump while retaining the one-registration-operation gate. The exact regression now passes and verifies 9,999 superseded leases are closed before the final lease remains active.
   - Preserved failing evidence: `evidence/independent-os-run4/independent-results.log`.
   - That run is superseded by the corrected exploratory run and the final frozen run below; it is not the current verdict.
2. **Delayed expiry was a design limitation requiring a second check.** A cached snapshot cannot age itself while its timer has not dispatched. The owner uses a dedicated one-shot expiry executor, separate from the UI and Discovery executor, and added monotonic revalidation before serialized controller commands. The independent regression verifies that revalidation immediately fences stale policy before explicit core demand and preserves spent attempts. This does not establish hard real-time Android scheduling.
3. **Startup observation ordering was reviewed before integration.** Returned battery sticky data and the thermal initial getter must not overwrite callbacks already observed during registration. The corrected source uses channel-specific arbitration. Tests verify callback-during-registration and callback-during-initial-thermal-read behavior. No device timing claim is inferred from these hostile API doubles.

## Independent coverage

The final suite contains 43 distinct test cases:

- **22 bridge host cases:** lifecycle idempotence; stopped/closed operation; all required channels; publication only after registration succeeds; missing/invalid battery values and overflow-safe 5%/20% boundaries; saver intersection; all 32 network usability/metering/blocking combinations; new-default identity and stale-network callbacks; exact freshness boundary; independent capabilities/unblock expiry; same-value renewal; old generation and cancelled timer delivery; 10,000-event expiry boundedness; known-clean retry and uncertain-cleanup poisoning; synchronous startup followed by failure; timer failure; stop/refresh while open is blocked; delayed/backward/unrepresentable monotonic time; revalidation without source reads; and the 10,000-open bounded-stack regression.
- **6 coalescer host cases:** 10,000-value latest-only bursts, yielding after each delivery, offers during a blocked consumer, close/late-drain handling, initial rejection/exception, rejected follow-up without retry growth, and recovery of pending delivery after a throwing consumer. The burst checks record at most one queued drain.
- **5 real-core integration cases:** restrictive observations cancel in-flight work before queued policy application; late results cannot commit; relaxation and explicit signal refresh create no demand and refund no spent attempt; rejected delivery retains the immediate fence; independent Save/visibility vetoes remain effective; and stale-snapshot revalidation fences before explicit demand. These compose the actual bridge and coalescer with the actual platform-neutral coordinator and an injected host provider runtime. They are not execution of the Android controller.
- **10 Android API-double cases:** API 31/33/35 dynamic receiver overload and flag selection; expected registration APIs; reverse-order/idempotent cleanup; old callbacks after close; startup seed arbitration; all supported thermal mappings and invalid values; missing battery extras and throwing reads; callback network arguments; every partially acquired registration failure; cleanup continuing after failures and reporting uncertainty; and missing services before any registration.

Source-only integration checks separately cover enabled-unknown startup, generation-zero coordinator initialization, lifecycle generation capture before flags, foreground-and-Discovery lease ownership, the immediate power fence before delivery, coordinator/generation matching, retired pending page demand, no demand calls in policy apply or signal refresh, monotonic command revalidation, removable one-shot expiry, coalesced power/UI publication, close cleanup, release synthetic refusal, callback-only connectivity data, no new bridge polling/persistence, and unchanged unknown native media identity.

## Final frozen run

The earlier frozen independent runs at `evidence/independent-os-final/` and `evidence/independent-os-final2/` also passed, but are superseded as final freeze identities. The owner corrected only the current inherited-folder SQL wrapper's baseline/membership adaptation and its explicit transformation record, preserving the SQL case bodies and exact membership comparison. Comparing manifest rows against the second frozen run confirmed unchanged membership and exactly those two current wrapper files changed; production sources and independent harness inputs were unchanged. After the owner's complete aggregate passed, the full independent suite was rerun against the settled freeze below.

Command:

```text
bash docs/m8b4/evidence/independent-os-run.sh /workspace/scratch/df6c1284d5ab/EasySaveVideo/docs/m8b4/evidence/independent-os-final3
```

Exit status: **0**.

- Standalone compilation: PASS; compiler log empty.
- JUnit: **43 tests, zero failures**.
- Source-wiring checks: PASS. The JSON groups them under 15 names; the retained console banner is a cosmetic count mismatch, not a separate coverage total.
- Exact eight historical compiler/runtime/JUnit/Hamcrest artifact hashes: PASS.
- Every compiled or statically inspected input, fixture, support script, API review record, historical toolchain-hash reference, and runner is a member of the shared frozen manifest: PASS.
- All **684** manifest rows have matching byte sizes, SHA-256 values, regular-file/no-symlink status, and Git executable bits before and after the run: PASS.
- Shared manifest bytes unchanged before/after: PASS.
- Supplemental direct input SHA-256 check: PASS.

The runner requires the frozen manifest by default and always requires the API-double suite. The explicit `ESV_INDEPENDENT_PROVISIONAL=1` option is for exploratory pre-freeze runs only. Final evidence did not use it.

Frozen manifest SHA-256:
`b4e94cdeb78c9ad2b393ac9cb66abec3e7e542ed249c40d806b2921ed3b422a7`

Runner SHA-256:
`4b12a52533bdeb9e5da29f29a921b0208f69cce1a43f638506f272254c4f5c67`

Principal final evidence under `evidence/independent-os-final3/`:

- `independent-results.log`
- `independent-compile.log`
- `independent-source-review.json` and `independent-source-review.log`
- `independent-toolchain-match.log` and `independent-toolchain-sha256.txt`
- `independent-frozen-before.json`, `independent-frozen-before.log`, and `independent-frozen-after.log`
- `independent-input-sha256.txt` and `independent-input-unchanged.log`

The 684-file integrity check covers more than the manually examined source scope. It is not a claim of manual review of all 684 files. The owner's strict Gradle/JVM/lint, runtime admission, inherited hosts, SQLite checks, and cold-restored aggregate results remain separate evidence and are not claimed as this reviewer's execution.

## Owner aggregate outputs independently inspected

After the final aggregate finished, this reviewer read its result summaries and ordinary host/SQL logs. These were executed by the implementation owner, not rerun by this reviewer:

- `strict-offline-aggregate.log`: successful strict offline build, **87 actionable tasks executed**, and exact **74** reviewed runtime artifacts verified for both variants.
- `junit-summary.json`: **1,944 JVM tests**, zero failures, errors, or skipped cases.
- `lint-summary.json`: debug and release each have zero errors, 17 warnings, and 6 hints. This is not a warning-free result.
- `frozen-inputs-before.log` and `frozen-inputs-after.log`: all 684 frozen inputs verified.
- Ordinary inherited discovery/power/filter/origin/media/reliability/provider/folder/destination/entitlement/isolation host logs and corresponding SQL logs show successful completion, including the corrected full-membership folder SQL wrapper's 26 tests. Synthetic DOM and admitted media host checks also report success while retaining their Android/native-device exclusions.
- `channel-matrix/matrix-summary.json`: all seven configured identity rows, in both debug and release, passed 15 host tests each; five invalid build values were rejected. The final default remains unconfigured and all payment implementations remain unconfigured. These are build identity/host results, not store or payment acceptance.

Cold-restored reruns and remote publication verification remain the owner's separate subsequent work and are not represented as part of this pre-commit independent execution.

## API basis and remaining acceptance boundaries

Official reference links and review targets are in `evidence/independent-api-review.md`, including Android's [network callback ordering guidance](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback), [battery broadcast values](https://developer.android.com/reference/android/os/BatteryManager), [thermal and saver APIs](https://developer.android.com/reference/android/os/PowerManager), and [elapsed realtime semantics](https://developer.android.com/reference/android/os/SystemClock).

Freshness is age since application receipt/read, not a hardware sensor timestamp. Stable Android thermal/network state may generate no update for five minutes; expiry is therefore a normal conservative condition. Explicit signal refresh renews observation leases but creates no Discovery demand. A validated network does not prove that any particular provider endpoint is reachable. The 5%/20% battery thresholds and five-minute expiry are local deterministic policy choices, not calibrated device or energy claims.

No device, APK/AAB, installation, actual OS callback acceptance, account, payment, external service, new library/provider, blocked candidate or substitute, Vault implementation, or measured energy work was performed. Browser/Save/Record authorization and native media identity scope remain unchanged. Device callback/cleanup behavior, OEM thermal semantics, and actual energy effects require separately authorized device acceptance.
