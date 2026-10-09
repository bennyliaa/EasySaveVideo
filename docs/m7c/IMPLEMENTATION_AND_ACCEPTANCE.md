# M7-C local Provider health and management development checkpoint

This is a bounded extension of the single compiled Commons metadata provider. It does not complete
M7, implement a downloaded rule interpreter, configure a production source or establish device/
release acceptance. See `SCOPE_AND_SEQUENCE.md` and `DEVICE_ACCEPTANCE_CHECKLIST.md`.

## Activation and observation

Existing package/policy signature domains and admitted dependency bytes are unchanged. Production
uses empty independent roots and no update endpoint/runtime/job. Enabled candidates must pass a
compiled synthetic parser exercise before the repository activates them. Fixed inputs exercise a
known video, empty result, continuation and rejected malformed/unsafe structures. The total fixed
input is under 4 KiB and checkpoints are bounded at 2,048. The response budget must hold each
positive vector. This is bounded offline parsing, not live-site testing or execution of package code.

The repository grants one opaque process-local observation token at a time, retaining exact package
version/SHA-256, security-policy version/SHA-256 and authority generation. Reference identity and
single consumption fence duplicates, stale callbacks, other repositories and replacement packages.
Only a fresh completed network exchange can count. Cache, pre-request failures, local server cooldown,
configuration/filter rejection, cancellation and elapsed deadline do not confer health evidence.

The semantic parser marks only strict, recognizable API JSON with incompatible semantic structure.
HTTP status/permission/redirect failures, non-JSON HTML, syntax damage, service errors/warnings,
headers and body extent/transport errors remain inconclusive. Health records contain no query, title,
URL, body, cookie, credential or exception message. Such observations describe the package/environment
combination and cannot prove that a parameter-only package's code is defective or malicious.

Local limits are 3 consecutive fresh successes to promote, 3 consecutive structural failures to
isolate, 12 conclusive observations, 20 total completed observations, and at most 24 hours narrowed
by package and accepted policy expiry. Inconclusive exchanges interrupt both streaks. Exhaustion
suspends without an incompatibility verdict. Policy changes invalidate old tokens and reset streaks,
while preserving spent budgets and never extending the original trial deadline.

## Storage and security

Format 3 persists health and up to 32 version-level isolation records in the existing atomic frame,
alongside signed package/policy anchors, high-water, LKG/history, disable barrier and observed clock.
Each isolation record has exact package and policy identity and a technical reason. Same-version
replacement cannot evade isolation. Full capacity rejects new enabled trials; it never evicts evidence.
Fixed-self-test rejection leaves the prior current/high-water unchanged and records isolation.

No version-only health-promotion API remains in the main JAR. A convenience extension retained solely
in test source creates and completes three real observation tokens for old test setup. Restarted
pending state remains disabled/RECOVERY_REQUIRED even after a policy update. Explicit rejection is
the available conservative recovery; storage uncertainty never offers a reset path.

Health writes use the same intent marker, single owner, bounded no-follow reads, atomic move and
directory force workflow. The repository itself revalidates trial time and authority before final
publication; a slow write cannot silently erase an expired trial while promoting. Cancellation or
uncertain durable publication keeps the current instance disabled and preserves recovery evidence.
Mutator callbacks cannot reenter another mutation or allocate a nested observation. A callback
close revokes authority immediately, fences every subsequent transaction checkpoint and retains
the file owner lock until the active transaction has left its uncertainty marker. Thus a second
owner cannot observe unpublished healthy bytes during close/recovery. Read-only snapshots remain
available to cancellation checks, and ordinary competing threads still serialize on the mutex.
These are host fault-injection contracts, not physical Android power-loss certification.

Isolation may select LKG as a pointer, but effective enabled use still requires the latest signed
exact version+full-envelope grant, safe floor, unrevoked key/version, valid package/policy and disable
barrier checks. Missing authority results in disabled use. Explicit stable rollback additionally
requires a current grant even for a disabled target. No high-water, revocation or disable evidence
is cleared. All prior M7-B trust/time/directory-replay limitations remain.

## Management

The Browser settings and Discovery options open a SkinKit page with responsive phone/tablet sections.
It displays actual local state, current/candidate/LKG pointers, high-water/policy/floor, trial limits,
isolation reasons and missing contact, package roots, policy roots and endpoint. The only maintenance
actions are repository-admitted candidate rejection and stable rollback; local Commons opt-in remains
the existing control. A returned transaction may still be disabled and is described accordingly.

One process-wide no-queue worker serializes management I/O. Closing/backgrounding invalidates old
display results and requests cancellation, without pretending an already committed operation was
undone. Reopening does not replace an occupied physical worker. Initialization is attempted once
per process, including failure, and does not recreate a corrupt store. Actions recheck repository
authority rather than trusting stale button state. No remote update check or fake-success control exists.

## Verification

The reusable strict-offline aggregate is `scripts/verify-provider-health-workspace.sh`: all ordinary
JVM suites, both Android Kotlin compilation/lint variants, exact74 dependency admission, source
guards, production-extracted SQLite/DOM checks, existing pinned Media3 host regressions and the
independent ordinary-Provider/management harness. Focused logs are diagnostic, not final acceptance.

There are ten static guards. The M7-B repository-policy guard required all app files to remain
unchanged and storage format 2, so the M7-C health guard replaces it for this new scope. It checks
the complete package protocol, security-policy protocol and update runtime bytes against M7-B,
retains the security gate/single-clock/empty-root/no-signing assertions, and adds the format-3,
health-token, self-test and new UI boundaries. All unrelated dependency/media/entitlement inputs
stay protected; the prior M7-B guard and evidence remain preserved unchanged for its own tag.

Git/tag and full bundle/source cold-tree reruns are sealed separately in the external recovery
receipt. All hardware, production and full-M7 gaps stay explicit rather than being inferred from
local compilation and host tests.

## Final main-worktree host results (2026-10-07 UTC)

- Strict offline dependency verification, all **87** requested Gradle tasks rerun: **PASS**.
  All ordinary JVM modules and both debug/release Android Kotlin compile/lint variants passed.
- Ordinary JUnit: **1,531 tests**, zero failures/errors/skips. Commons **223**, app **718**.
  This adds 46 Commons and 37 app tests over M7-B while retaining prior behavioral coverage.
- Lint in each variant: **0 errors, 14 warnings, 6 hints**, unchanged totals. Existing SDK,
  lint and deprecation notices remain in the raw logs; this is not a warning-free build.
- Runtime admission: exactly **74** external artifacts in each variant. Coordinates, filenames
  and SHA-256 match M7-B; dependency/vendor/license inputs are unchanged. The project's own
  Commons JAR changes as expected.
- Synthetic contact-configured Commons/app tests and dual compile/lint: **PASS**, with no live
  requests. The final full aggregate restored the default contact-absent build outputs.
- Ten source guards, production-extracted Discovery/Library/export/editor/recorded-segment
  SQLite checks and 11 DOM-detector checks: **PASS**.
- Existing pinned Media3 regressions: HLS EOF **81**, mux conservation **15**, local reader
  release and editor muxer release: **PASS**. No new hardware or media-quality acceptance.
- Independent frozen host run: **327/327**, consisting of 223 ordinary Commons, 37 ordinary
  management/owner tests, 34 prior independent cases and 33 new independent cases. These
  overlap the ordinary aggregate and are not added to 1,531. All **61** compiled inputs were
  unchanged during the run; the full aggregate repeated the same host suite successfully.
- The **95-entry** reviewed source manifest matched every extent/hash after independent
  verification and again after the main aggregate. SHA-256:
  `5467d271820eae5da4253e6722942cbd2eeb9e49005f47764af0ae8ad5b3a265`.
  No scoped source blocker remains. Diagnostic failures and corrections are preserved in
  `INDEPENDENT_SOURCE_REVIEW.md` and the evidence folder.
- Main Commons JAR: **127** classes scanned, with no fixture/signing/private-key reference or
  version-only confirmHealthy API. This is compiled-JAR evidence, not APK inspection.

No ESV APK/AAB, device install, production roots/endpoint, operational signing/delivery or full
M7/release acceptance is implied. The external final receipt records the commit/tag, complete
bundle/source hashes, byte/mode comparison and two independent restored-tree full reruns.
