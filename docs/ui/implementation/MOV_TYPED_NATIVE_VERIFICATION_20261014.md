# MOV normalized output: typed native verification source layer

Date: 2026-10-14 UTC. Status: inactive source layer implemented; host/JVM regression and independent code review passed.

## Scope

AndroidLocalMp4Verifier now has an explicit typed overload sharing its existing
single native lane. Transferred wraps the same original TransferResult and reaches
the unchanged legacy inspector. NormalizedDirect requires a dedicated owner port;
its default absence rejects with RECOVERY_REQUIRED before native allocation.
No real storage owner or runtime MOV admission is added in this layer.

The normalized branch independently scans canonical MP4, walks all native sample
identities/configuration/PTS/sync/EOF, verifies genuine beginning/middle/end frames,
and requires one-to-one final-GOP decoded frames followed by output EOS. The final
proof retains actual nonempty terminal output PTS. Same-FD fixed-extent/hash checks
and local error mapping keep raw HTTP evidence separate from canonical output.

The lease's exact generation, authoritative selectedOutputDescriptor and active
operation are storage-owner obligations. Reference identity and a filename are not
ownership proof. The default-null port and fake-owner tests do not implement those
obligations. See MOV_NORMALIZED_OWNERSHIP_PORT_20261014.md.

## Evidence and boundaries

Pure tail/helper: official shared-lock Gradle run passed 32 tests; independent
read-only review passed exact immutable manifest, clock/EOS/count, failure/cancel,
resource budgets and 5-second no-progress behavior. Final app-focused run passed 141 tests with zero failures/errors/skips, including
43 new tests: 22 native-observation/preflight policy, 10 real host RAF, eight
injected cleanup actions and three source contracts. Independent read-only review
passed all seven Android source/test hashes. The discovered is-encrypted=0 false
protection report was fixed with explicit absent/clear/encrypted/unreadable handling
and three added tests. Final forced 13-module regression passed 4,261 tests with two existing skips
(4,263 registered), zero failures/errors. App: 2,811 PASS. Debug/release compilation
and fresh SARIF lint passed with zero errors, 60/61 warnings and eight notes each.
Runtime admission is unchanged at 90 exact artifacts; 99 original license payloads
and seven-getter ABI checks passed. Host SQLite migration tests passed 20 cases and
411 rollback fault boundaries; actual Android SQLite remains untested. Tests using actual temporary RAFs
prove host IO/extent/hash policy; fake native observations prove adapter policy.
Source-wiring assertions are static contracts. None is real Android decoder,
extractor representation, SQLite/adoption or release acceptance.

Native release uncertainty sets a one-way recovery latch before the owned FD is
closed; successful FD close cannot release that retained owner fence. Ordinary
stop failure with confirmed final release does not itself poison the process.
Timeout with late actual release retains existing temporary draining semantics.

## Remaining work

- Dedicated adopted-generation read owner and controlled Direct execution/local
  binding, preserving the existing durable legacy fence.
- Coordinator normalization/adoption/verification/publication and recovery wiring.
- Blocking storage IO/temporary-close activation gates from the preceding layer.
- Actual Android framing, output geometry, last-frame/EOS and resource release.
- Original AAC AU/PCM-frame proof and wider feasible MOV/WebM conversion support.

Production MOV remains disabled. No APK assembly or device operation in this layer.


## Reproducible validation and backup scope

- Shared locked offline Gradle wrapper: focused save tail 32 and app 141; then all
  13 module test tasks forced via `force-tests.init.gradle`, release Kotlin compile,
  debug/release lint and runtime admission. All terminal processes exited 0.
- Source-boundary/ABI and current host SQLite scripts exited 0.
- `normalization-native-verification-20261014/` contains sanitized test counts,
  current task/lint summaries, scoped review hashes and the source manifest.
- The isolated preliminary tail compilation was not shared-lock evidence and is
  superseded by the successful official-wrapper compile/tests above.
- Source is backed up only as a normal expected-head private development-branch
  increment. Verified commit identity is reported separately; no unobserved CI
  result or device acceptance is claimed.
