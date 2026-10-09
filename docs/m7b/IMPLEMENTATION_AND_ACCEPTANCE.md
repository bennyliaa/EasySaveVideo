# M7-B offline repository security policy development checkpoint

This is a single built-in Commons maintenance slice following M7-A, not full M7,
production key/service delivery, an extraction-rule interpreter, or release approval.
See `SCOPE_AND_SEQUENCE.md` for the current contract and next-slice boundaries.

## Trust and wire format

`RepositoryPolicy.kt` adds a closed big-endian ESVS envelope, distinct from ESVP package
signatures and ESVR local framing. Fixed SHA256withRSA authenticates the entire header,
key ID, payload length and payload. RSA public parameters are defensively copied and
limited to 2,048–4,096 bits. Policy/package verifiers have independent injected trust maps;
both application defaults are empty. Signing exists solely in ephemeral JVM test fixtures.

The envelope and payload are capped at 8,192/4,096 bytes. The fixed Commons ID and compiled
provider version are mandatory. Positive policyVersion, issued/expiry, enabled, nonnegative
minimumSafePackageVersion, up to 64 strictly increasing positive revoked versions, up to 32
strictly sorted distinct ASCII package key IDs, and one optional exact positive rollback
version plus 32-byte full-package digest are the complete schema. Invalid booleans, counts,
lengths, order, duplicates, targets, algorithms, signatures, trailing bytes and unknown
schema/format fail closed. No endpoint, key rotation, arbitrary code, script or rule field
can be represented. Authenticated values return detached byte arrays and unmodifiable sets.

## Persistence and authority

`ProviderRepository` atomically retains five bounded signed package slots (current,
last-good, previous-good, disable barrier, high-water), independent signed policy and the
pre-transaction clock observation. Format 2 uses the previous initialization/intent markers,
no-follow bounded reads, process lock, atomic same-directory replacement and fsync workflow.
Format 1 does not automatically migrate or reset: old state disables this development build.
A later actual installed-data migration remains a separate design/acceptance requirement.

Package high-water and policyVersion have separate lifecycles. A later policy must have a
higher version, a nondecreasing floor and supersets of all accepted revocations. Policy is
never rolled back along with a package. Historical signatures remain authentication evidence
after expiry, while effective use still needs both current policy and package time validity.
Anti-replay is relative to locally accepted versions. An offline instance cannot discover a
newer withheld policy or revocation it has never received; signed lifetime and the stated local
time bound are the only current freshness constraints, not proof of latest server state.

Every effective package goes through policy enabled/expiry, safe floor, revoked version/key,
signed disable barrier and exact-target downgrade authority. An enabled package below package
high-water needs the latest signed rollback grant matching both version and complete envelope
SHA-256. Explicit stable rollback needs that grant even for a disabled target. Candidate
rejection to an already disabled LKG preserves shutdown without granting enabled authority.
No unsigned bundled fallback is usable after any signed package or policy is accepted.

The disable barrier persists independently of active/history pointers. A formerly accepted
signed package kill switch cannot be undone by rolling back to an old enabled package, even
with a subsequent grant. Only a newer signed enabling package can cross the barrier, and all
other gates still apply. A higher repository policy can explicitly re-enable its own gate;
it cannot undo a floor/revocation or override the local build/user enable choice.

## Failures and operational consequences

| Scenario | Result |
|---|---|
| Replayed/equivocating policy version | Reject; keep both anchors and old effective authority |
| Lower floor or omitted old revocation | Reject without clearing accepted restrictions |
| Revoked/unsafe proposed package | Reject before package high-water advances |
| New policy disables/revokes current or pending package | Commit policy, increment authority generation, effective Commons disabled |
| Policy expired, future beyond allowed skew, unavailable root after restart | No policy-as-absent or bundled recovery |
| Wrong target digest, revoked/expired target, floor failure | No enabled downgrade, even if called LKG |
| Newer policy withdraws current rollback grant | A lower enabled current package becomes disabled |
| Explicit failed-candidate rejection lacks safe authorized LKG | Clear trial state but keep signed high-water and effective disabled state |
| Reopened pending candidate, then valid new policy | Still RECOVERY_REQUIRED; policy cannot confer health |
| Truncated/corrupt/oversized/wrong-format/missing initialized state | Fail closed; no delete/reset API |
| Cancellation before transaction | Keep prior stable state unchanged |
| Fault/cancel at any exposed durable write stage | Current instance disabled; intent/recovery evidence keeps reopen disabled |
| Concurrent package/policy mutations | One repository mutex serializes; later policy still constrains resulting package |
| Clock negative/read failure or below observed lower bound | Technical clock error and effective disabled state |
| Complete coherent old directory replay | Not prevented: no trusted external monotonic evidence exists |

Revocations are deliberately irreversible through this protocol. Mistakes require an already
safe/authorized new package or key path, or a future reviewed recovery/application update.
The bounded lists cannot be shortened when full. Policy-root rotation, ownership and break-glass
recovery are not implemented. Deleting state is not an accepted operational remedy.

All repository time decisions use one recorded sampling route; each snapshot uses a single
sample across its gates. The lower bound persists only at the pre-transaction sample and in
memory for subsequent reads. Later observations, including a post-commit snapshot, are not
made durable until another mutation. This does not provide a trusted clock, Android secure
counter, or resistance to local malicious rewriting/full-state restore. If all later writes
and fsync calls fail, re-creating a poison marker is best effort; the current instance remains
disabled. Host fault injection is not physical Android power-loss evidence.

## Runtime and application boundary

The existing bounded source worker accepts an explicit PACKAGE or REPOSITORY_POLICY kind;
it does not infer one from untrusted data. Policy processing uses the same sticky cancellation,
short no-I/O publication gate, bounded body/deadline handling and retained physical slot as
package updates. An absent source returns SOURCE_UNAVAILABLE. Production does not instantiate
an updater, network source, endpoint or background job. Repository policy commits can revoke
an in-flight Commons lease; stale generation checks fence result/health publication and caches.

All app/Android, dependency/license, free Library, entitlements and unrelated feature files
remain byte-identical to M7-A. The app's default roots remain empty, build contact remains
absent by default and zero-provider Browser behavior remains independent. Vault is disabled,
untouched and excluded from this work and its independent review.

## Verification status

Final results are recorded below only after the full strict-offline aggregate and independent
frozen-source run finish. Focused/WIP logs are diagnostic evidence, not final acceptance.
`scripts/verify-repository-policy-workspace.sh` runs all ordinary JVM suites, both Android
Kotlin compile/lint variants, exact74 admission, ten source guards, SQLite/DOM regressions,
existing pinned Media3 host checks and the current independent Provider policy harness.

The development tag's commit, full bundle/source hashes and separate full cold-tree reruns
are external `RESTORE_RECEIPT.json` evidence. A bundle verification alone is not a cold build.

## Final main-worktree host results (2026-10-07 UTC)

- Strict offline dependency verification and all **87** requested Gradle tasks: PASS,
  including all JVM modules and both debug/release Android Kotlin compilation/lint variants.
- Ordinary JUnit XML: **1,448 tests**, zero failures/errors/skips. Commons: **177**, including
  59 added M7-B tests (30 protocol and 29 repository/security/runtime). Prior tests are retained,
  with obsolete unsigned-fallback expectations tightened to the current signed semantics.
- Lint per variant: **0 errors, 14 warnings, 6 hints**. Totals are unchanged; this is not a
  warning-free build. Existing lint/SDK/deprecation notices remain visible in preserved logs.
- Admitted external runtime artifacts: exactly **74** in both variants, with coordinates,
  filenames and SHA-256 identical to M7-A. Dependency, vendor, admission and license inputs
  are unchanged. The project's own Commons JAR changes with this implementation as expected.
- Synthetic contact-configured app JVM/dual-compile/dual-lint path: PASS without networking.
  The final full aggregate restored contact-absent build outputs.
- Ten static guards, production-extracted discovery/library/export/editor/recorded-segment
  SQLite checks and 11 DOM-detector checks: PASS.
- Existing pinned Media3 host checks: HLS EOF **81**, mux conservation **15**, local reader
  release and editor muxer release: PASS. These unchanged regressions are not media/device QA.
- Independent frozen ordinary-Provider source review: **211/211** host tests, comprising
  177 ordinary Commons tests and **34** independent adversarial cases. These overlap the
  ordinary aggregate and are not added to its total. All 48 compiled inputs remained unchanged.
- **65-entry** reviewed manifest SHA-256:
  `00a6b932db556aba4dae5992e33df2cd606c9f3b36e2f5b52d99aa3b642539cb`.
  Every extent/hash was independently rechecked, and matched again after the full aggregate.
  The secondary-time-sampling finding and explicit disabled-target rollback interpretation
  were corrected before this frozen run. No scoped blocking source finding remains.
- Compiled Commons main JAR: **107** classes scanned; no fixture, key generator, private-key
  or signing-operation reference. This supplements source inspection, not APK inspection.

Git/tag and both cold restored-tree full reruns are sealed separately in the final recovery
receipt. No device, Android power-loss, operational signing/delivery, full M7 or release
acceptance is implied by these development results.
