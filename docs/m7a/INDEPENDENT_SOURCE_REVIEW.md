# M7-A independent ordinary-provider source review

Review date: 2026-10-07 UTC. Baseline: M6-C
`0d1bae2dbbb4212c5fc43b5ed536df447974a221`.

## Status

**COMPLETE for the scoped ordinary-provider source/host review. No unresolved
blocking source defect was found after the R1–R4 corrections below.** The final
frozen-source run passed **132/132 tests**, including **14 independent adversarial
tests**, with all compiled inputs unchanged. This includes the strict existence
helper correction. The prior 130-test revision is superseded; its exact artifacts
remain separately preserved as `independent-prior130-*`.

The exact final reviewed source/test/script/resource manifest is
`evidence/review-source-manifest.json`: **57 entries**, SHA-256
`085d9b18ddb6644d1f6fc4f5cbc37bc08602cc9b22108d319fb86619f14f1afd`.
Every extent/hash independently matched at **2026-10-07 00:34:07 UTC**. This binds
the review to those exact bytes, including the final immediate-stable disable and
timer-failure cleanup and strict existence corrections, rather than to a moving
working tree.

This review is only the built-in Commons enable/budget maintenance slice. It is not
full M7 completion, release approval, Android/device acceptance, update-service
deployment, extraction-rule implementation or a claim of legal download rights.
Vault remains disabled, untouched and explicitly excluded. No Vault review was
attempted or recreated.

The reviewer owns this report and independent evidence/fixtures only. No production
source edits, Gradle invocation, Git commit, APK/AAB build, installation, device use,
real signing identity, production endpoint, network update or publication was
performed by the reviewer.

## Reviewed boundaries

- The binary protocol has one pinned format/schema, a fixed built-in provider ID
  and installed version, positive package version, bounded lifetime and exactly
  five narrowing budgets plus an enabled flag. It has no executable, endpoint,
  extraction-rule, filename, permission or entitlement field.
- Envelope/payload lengths are bounded before allocations, integers have fixed
  encodings, tokens have a closed ASCII alphabet, flags must be 0/1 and trailing
  bytes are rejected. The pinned SHA256withRSA signature covers envelope identity,
  lengths and the complete payload. Root parameters are defensively pinned; no
  trust-on-first-use or algorithm negotiation is present.
- Signed high-water evidence is stored separately from current/healthy rollback
  candidates. Explicit rejection and rollback retain it. Historical signatures
  can prove the high-water after expiration; expiry/future-time checks still gate
  effective authority. Corrupt/missing/uncertain state disables the repository and
  does not create a writable fresh anti-replay state.
- A restored pending candidate requires explicit rejection and cannot serve or be
  promoted as healthy after restart. Its authenticated high-water remains intact.
  This is deliberately more conservative than resuming an unconfirmed candidate.
  A signed disabling package is stable immediately; it does not require a network
  health event, remains disabled after reopening and permits a later higher version.
- The update source is an injected port only. The app installs no production trust
  root, endpoint, update client or background download job. Local build contact and
  user choice remain necessary for Commons registration; zero-provider startup is
  supported. App repository initialization is called from the discovery I/O lane.
- Policy leases fence existing metadata requests, trigger cancellation, narrow
  rather than expand local budgets and isolate cache entries by authority generation.
  Physical transport ownership and retry cooldown remain on the single compiled
  provider instance. No new query, title, URL, cookie, token or exception logging
  route was introduced by the maintenance code.

## Findings raised and corrected

### R1 — High: cancellation after rename could still activate a package

The first repository implementation checked cancellation before its transaction
and before atomic rename, but not after rename/directory synchronization. A cancel
at those boundaries could still unlink its uncertainty marker and publish the new
policy. The owner added checkpoints through final publication, recreated uncertainty
evidence after post-unlink failure and made restored pending candidates disabled.

Independent fixtures cancel or inject failure at every exposed durability stage,
then reopen the repository. They reject publication and observe disabled recovery.
Failure to recreate a marker when all subsequent filesystem I/O also fails remains
a stated durability limitation, not a tested power-loss guarantee.

### R2 — High: the managed deadline ended before cleanup and health promotion

The first metadata wrapper promoted health before its final broader operation
deadline check; it no longer retained the narrower managed deadline through body
close, transport cleanup and health persistence. A response close crossing that
deadline could promote and return a successful request.

The owner now carries one effective deadline through cleanup, health persistence
and final return, and passes the same check into repository health publication.
Independent fixtures advance the clock in response close and after health commit
synchronization. Neither can produce successful publication/return; uncertain
health persistence remains disabled after reopening.

### R3 — High: runtime cancellation waited behind filesystem I/O

The first update runtime held its publication monitor around the entire repository
install, including signature verification and filesystem synchronization. Cancel
and deadline processing needed the same monitor, so a hung filesystem could block
logical cancellation and conceal the cancellation signal from install checks.

An independent fixture reproduced this failure by holding a write-stage latch while
ignoring interruption. The revised runtime signals cancellation before coordination
and uses a no-I/O final publication gate. Physical worker/cancel/callback ownership
still retains the slot. The same fixture now receives prompt cancellation and a
BUSY retry while the blocked write remains outstanding, with no restart activation.

### R4 — High: a file-existence probe could mistake inaccessible state for absence

The implementation owner identified a further issue after the first frozen pass:
`Files.exists` returns false for some access/I/O failures as well as genuine absence.
If both initialized/state probes reported that uncertainty as false, startup could
select fresh bundled defaults. The reviewer independently inspected the correction:
all six probes now use `readAttributes` with `NOFOLLOW_LINKS`, and only a
`NoSuchFileException` returns false. Other failures reach the repository's fail-closed
initialization or transaction handling.

The added tests cover dangling state/initialization/intent/temporary links and
wrong-type state evidence. A real access-denied metadata lookup was not induced:
its failure path is source-reviewed, **runtime NOT_RUN**. No filesystem permission
change or artificial production I/O abstraction was introduced solely for this test.

## Independent host verification

`evidence/run-independent-provider-host.sh` invokes the already-cached Kotlin 2.2.10
compiler and JUnit 4.13.2 directly. It does not invoke Gradle or fetch dependencies.
It compiles platform-neutral core-model/discovery/Commons source, Commons unit tests
and the standalone reviewer fixture. It records the exact compiler/JUnit jar hashes,
source hashes and a post-run source-unchanged check.

The reviewer fixture independently challenges:

- Every single-byte envelope mutation and every truncation; correctly signed unknown
  targets/schemas/trailing fields/wider budgets; empty trust roots.
- Rejection, rollback, restart and expired signed high-water evidence without replay.
- Corrupt/missing state; all exposed write-stage faults and cancellation boundaries.
- Recovery-required pending candidates, explicit rejection and preserved high-water.
- Effective deadline after response close and during health synchronization.
- Revocation of a blocked existing Commons transport and suppression of its result.
- Logical deadline while source creation, execute or body close is hung.
- Physical slot retention while cancellation hooks or result callbacks are hung.
- Runtime close refusing further source work and cancellation during blocked storage.

The diagnostic pre-correction run reported 91 tests with one failure in R3. Sources
changed during that run; it is diagnostic evidence only and must not be read as a
frozen test result. Subsequent unchanged-input runs passed 117 and then 120 tests.
The definitive frozen-input run passed **132/132**, comprising **118 ordinary
Commons tests and 14 reviewer tests**. Evidence:

- `evidence/independent-final-host-console.log` and
  `evidence/independent-final-host-results.log`: compiler/JUnit output and result.
- `evidence/independent-final-source-sha256.txt` and
  `evidence/independent-final-source-unchanged.log`: all 43 compiled source/test
  inputs and their successful post-run hash check.
- `evidence/independent-final-toolchain-sha256.txt`: the exact cached compiler,
  standard library, annotation, coroutine and JUnit/Hamcrest artifacts used.
- `evidence/independent-final-manifest-verification.json`: independent match of all
  57 reviewed file extents and SHA-256 values, including app files not host-compiled.

The Android registration/controller changes and both debug/release registry routes
were source-reviewed only by this reviewer. The host result does not include those
Android classes or claim an Android app execution.

## Explicit limits and NOT_RUN

- Android filesystem/process/power-loss behavior and Java provider behavior on the
  actual minimum API31 and current target devices: **NOT_RUN** by this reviewer.
- Actual app Context/files directory initialization, process recreation, device UI
  and configured/unconfigured live Commons behavior: **NOT_RUN** by this reviewer.
- APK/AAB, install, real update download, production key ownership/rotation/revocation,
  real-account operation, deployment and publication: **NOT_RUN / out of scope**.
- Extraction-rule maintenance, arbitrary scripts/code and additional providers:
  **not implemented and not admitted by this review**.
- Rooted/hostile OS rollback of the whole private directory is not resisted by this
  design. A checksum protects accidental manifest damage; package signatures do not
  make the surrounding filesystem an OS-compromise-resistant monotonic store.
- Host fault hooks exercise orderly in-process failure handling, not actual torn
  writes, sudden power loss, adversarial storage, or simultaneous total I/O failure.
- Actual access-denied or failed metadata lookup through the new strict existence
  helper: **NOT_RUN**. Exception propagation is source-reviewed; dangling links and
  wrong file types are host-tested and are not represented as access-denied tests.
- Aggregate builds/lint, Android compile tests and cold-restoration gates are owned
  by the parent build owner and must be reported separately.
