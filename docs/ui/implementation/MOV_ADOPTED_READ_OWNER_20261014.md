# MOV adopted canonical read owner

Date: 2026-10-14 UTC. Status: inactive owner implementation; final regression and independent source review passed.

## Implemented scope

The existing Direct local processing gate now issues a lexical, exact-gate permit.
Its issuer, invocation, context and live identity are checked without acquiring a
second lane or invoking external recovery callbacks. Revocation precedes lane
release. This permit alone is not a Coordinator execution or Android batch claim.

The storage-owned canonical reader reserves ownership before potentially blocking
open or directory operations, checks authoritative Save/ledger/catalog bindings,
and returns a single read-only host RAF capability for exact ADOPTED output. Full
normalization proof, operation, revision and generation must agree. Generic legacy
file access remains blocked by the durable normalization fence. The production
registry has no issuing authority by default; the synthetic binding implementation
exists only in tests. AndroidSaveRuntime is not connected to this owner yet.

Revalidation and opening are outside the global storage monitor. Shared root and
staging directories compare stable device/inode/kind, so unrelated Direct files
or SQLite journals do not invalidate the reader. Owned workspace/generation and
raw/canonical files retain strict metadata/layout checks. A late directory-open
return after cancellation is retained and closed without enumerating children.

Native release uncertainty is sticky. The single actual worker closes its RAF;
positive close return plus invalid descriptor are required. Unknown constructor or
close outcomes retain strong ownership, including exceptions retaining partial
resources. Cancellation/revocation does not close another worker's descriptor or
retire its reservation. Existing storage mutations consult canonical reservations;
only constrained stop transitions may proceed, and a confirmed SQL update revokes
new reading authority without claiming actual resource release.

## Evidence boundaries

Focused gate: 33 tests (11 existing and 22 new), zero failures/errors/skips.
Focused app: 121 tests in nine suites, zero failures/errors/skips. This includes
55 binding/owner/race tests, six production mutation-policy tests, five production
remux-conflict tests and five source-wiring contracts, plus existing regression.
Owner tests use real temporary host RAFs with injected metadata/IO and authority.
They include blocked open/validation/close, failure retention, sibling changes and
identity replacement. They are not actual Android Os/SQLite/native execution.
Source-wiring assertions are static contracts, not reconstructed storage tests.

Independent review found and closed the shared-parent metadata false-positive and
late directory-open cancellation gaps. Final forced 13-module regression passed 4,354 tests plus two existing skips
(4,356 registered), with zero failures/errors. App: 2,882 PASS. Debug/release
compilation and fresh SARIF lint passed with zero errors, 60/61 warnings and eight
notes each. Runtime admission remains 90 exact artifacts; original license-byte
checks (99 payloads), seven-getter ABI checks, host SQLite 20 cases and 411 rollback
boundaries passed. These processes all exited 0. Scoped hashes and sanitized
counts are in normalization-adopted-owner-20261014/.

## Remaining activation gates and next work

The previously implemented remux protocol still has temporary hash/fsync/open and
directory-close paths that can hold fileLock indefinitely. This remains a concrete
production activation blocker, not merely a performance limitation. Before a real
production bridge, registry consumption/completion state will also be made private
to its identity map; current internal setters have no external issuance path and
no observed bypass. Real accepted
Coordinator execution/claim/batch binding, controlled normalization/adoption,
typed commit/inspection/atomic publication, terminal cleanup and recovery must be
connected in the next complete pipeline slice. Old escaped File/FD ordering and
all blocked-IO retirement tests must be demonstrated there.

Real Android decoder framing, EOS, duplicated descriptor release, SQLite and crash
recovery remain untested. MOV admission stays disabled. The supported design is
strict silent AVC QuickTime; audio-containing MOV is rejected as a whole. AAC PCM
frame proof and wider original format support remain unfinished requirements.
No APK build, installation or device operation is part of this checkpoint.
