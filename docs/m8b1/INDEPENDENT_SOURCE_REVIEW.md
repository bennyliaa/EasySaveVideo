# M8-B1 independent ordinary source review

## Result

**PASS within the stated ordinary source/JVM review boundary.** No unresolved
correctness finding was identified in the frozen B1 history changes. The final
independently authored suite ran **16 tests, 0 failures, 0 skipped**, against the
actual Kotlin retention/store/coordinator, presentation policy and checkpoint
codec. Android store/controller/Compose behavior received source review, not
Android runtime execution.

This is a review of the bounded local Discovery-history slice relative to M8-A
`0762843aa22bf1907cedf5aeed84224fdb335f0f`; it does not complete M8, B2 or B3.

## Frozen input and toolchain evidence

- Read `AGENTS.md`, `docs/m8b1/SCOPE_AND_SEQUENCE.md`, and
  `docs/m8b1/IMPLEMENTATION_AND_ACCEPTANCE.md` before the focused review.
- The initial 40-file manifest matched exactly. The build owner then explicitly
  paused the freeze to fix automatic SQL checkpoint eviction refreshing a surviving
  profile's recency, and to add the memory-store 500-entry/page boundary. The review
  resumed only after the owner issued a refreeze signal.
- Final manifest: `docs/m8b1/evidence/frozen-review-source-manifest.json`.
  SHA-256: `97c4cff59a4cc25d0fbf7f37a245e2a3eccee5783a74184fdc6d1002bde81101`.
  All **40 file sizes and SHA-256 hashes** matched before and after the final run.
- The standalone Kotlin compiler/JUnit pattern follows the existing M8-A review
  runner. All **8 artifact basenames and SHA-256 hashes** match
  `docs/m7c/evidence/independent-final-toolchain-sha256.txt` exactly.
- The final run also independently hashes and rechecks **26 compilation/evidence
  inputs**, including both genuine legacy binary fixtures and the new test/runner.
- The reviewer changed no production or existing test source and ran no Gradle.

## Focused source review

### Retention and alias facts

Reviewed `DiscoveryRetention.kt`, `DiscoveryStore.kt`, and supporting
`SeenHistory.kt`, `DiscoveryModels.kt`, and `ResultPool.kt`.

The planner orders profile eviction, historical checkpoint eviction, whole Seen
identity-group eviction and progress eviction deterministically. Materialized
checkpoints and newly written checkpoints protect their profiles; materialized
result aliases protect the entire already-linked historical group. All query
progress associated with a protected checkpoint is protected. Insufficient
unprotected capacity throws instead of changing materialized pages.

Alias linking uses existing rows only and unions transitive groups. Independent
facts use minimum first-discovered and maximum observed timestamps. Neither
linking nor pruning promotes discovery/opening to a visibility/playback event.
The memory transaction stages data, recency and identities together and publishes
only after retention and commit guards succeed. Reads do not allocate recency.
The refrozen Android automatic checkpoint deletion now avoids `touchProfile`,
matching the memory planner's recency behavior.

### Android schema 3 and transactional boundaries

Reviewed `AndroidDiscoveryStore.kt` and `DiscoveryCheckpointCodec.kt`:

- The existing database filename is retained. Only database versions 1 and 2
  upgrade to 3; v1 receives the nullable rights column first. Unsupported upgrades
  and downgrades throw, with no delete/recreate fallback.
- The `SQLiteOpenHelper` upgrade path performs metadata initialization,
  checkpoint decode/validation, proven-alias linking and capacity planning in the
  helper's upgrade transaction. Every legacy checkpoint is validated before the
  upgrade finishes, including its detached result/page rows and rights payloads.
- Existing aliases begin as singleton identities. Persisted result aliases are
  the evidence used for linking; identical event values are not used as evidence.
- Bad/unsupported checkpoint payloads, invalid rights representation and protected
  capacity failures throw rather than recover by discarding the old database.
- Normal writes invoke `finish()`, then the final guard, then mark the transaction
  successful; `endTransaction()` supplies the commit/rollback boundary. Recency,
  alias merges, checkpoint writes and pruning are in that transaction.
- Count-only reads leave `dirty` false and bypass pruning/sequence updates.
- Automatic retention removes checkpoint/results/pages/viewport rows together
  without refreshing a retained profile's recency.

The real `SQLiteOpenHelper`, `CursorWindow`, Android SQLite dialect/runtime and
power-loss behavior were not executed by this reviewer. A Kotlin legacy-codec
bridge below validates the genuine old fixture/profile/query-key assumptions used
by the separate host-SQLite work; it is not a substitute for Android execution.

### Coordinator and local confirmation UI

Reviewed `VideoDiscoveryCoordinator.kt`, `AndroidDiscoveryController.kt`,
`DiscoveryPresentationPolicy.kt`, and the local controls in `DiscoveryScreen.kt`.

- Provider acceptance checks the persisted session/revision and registers a final
  cancellation guard that runs after retention. Failed guards do not publish the
  staged checkpoint, cursor or Seen facts.
- Current/all Seen clearing revises every matching checkpoint even when Seen is
  empty. Current coordinator requests are retired before callbacks can return;
  old viewport/event and provider revisions cannot repopulate cleared facts.
- A provider reset is scoped to the captured current profile and chosen provider.
  It ends automatic demand, retires only matching requests, retains other source
  requests and Seen facts, and preserves source-wide Retry-After constraints.
- Statistics use detached counts and do not request sources. Controller read-only
  refresh skips source reinitialization, page materialization and viewport writes.
  Controls disable pending automatic materialization before later polling.
- Confirmation requests capture profile, session/epoch, view revision and controller
  generation. UI invalidation and serialized execution both check those values;
  provider resets also require the selected provider still to exist. Return values
  determine committed/failed text, while stale requests execute no mutation.
- Demo isolation and destructive consequences are explicitly disclosed. Statistics
  describe local records, and unavailable counts are not shown as zero.

## Independent executable coverage

The suite in `evidence/independent-history-review.kt` adds these ordinary bounded
fixtures, independently of the production tests:

1. Transitive alias union, all independent event extrema, and cross-profile isolation.
2. An unknown alias does not gain a fact or an implied future relationship.
3. A materialized result protects historical group members outside its own alias list.
4. Automatic checkpoint eviction does not refresh remaining profile history recency.
5. A final guard observes already-pruned staged state; rejection restores facts,
   recency ordering and checkpoints.
6. Protected progress overflow rolls back earlier Seen clearing/new facts/revisions.
7. Empty/populated scoped clearing and all clearing preserve pages and progress,
   revising only the intended checkpoints.
8. Provider reset rejects matching late success/failure, allows another provider's
   completion, retains another profile and waits for explicit new demand.
9. Source-wide Retry-After survives progress reset through the fake-clock boundary.
10. All-history clearing fences old viewport events and late callbacks without
    automatic source demand; a fresh actual event retains its independent meaning.
11. Provider cancellation in the final post-pruning guard preserves the old committed
    checkpoint/history and publishes no new cursor.
12. Statistics do not refresh history recency or create source demand.
13. Every target-field mismatch and missing provider is stale, failed mutations cannot
    display success, and read-only/no-pending refresh cannot materialize a page.
14. Codec round-trip preserves materialized content; unknown version and trailing
    bytes are rejected.
15. Genuine frozen codec-v1 payload and extracted v1 database header preserve profile,
    stamp, query key, default sort and empty legacy rights.
16. The same genuine codec-v2 bridge preserves profile/header semantics and rights
    warning data.

Both legacy fixture tests verify their exact existing binary SHA-256, then decode
through the production Kotlin codec. Both actual profile IDs are `legacy-profile`,
and both production-derived query keys are:
`71d0f995f734ca916d68cb7b43a2f8d1d9c02ef7b2309f71ce14c13fabc932a2`.
The old header keeps the original versioned prefix and appends zero result/page
counts; it is not a new-version payload with its version patched.

The first legacy-bridge run had two assertion failures because the new test
incorrectly expected the literal profile ID `oldprofile`. Inspection of the genuine
fixture showed `legacy-profile`; only this test expectation was corrected. The
initial failure log is preserved separately. The final 16-test run is green, and
no production edit was made for that test-authoring error.

## Reproduction and evidence

From the repository root:

```sh
bash docs/m8b1/evidence/independent-history-run.sh
```

The runner uses only the existing approved local compiler/runtime/JUnit artifacts,
creates temporary classes within its chosen output directory, removes those classes
on exit, verifies the frozen 40-file manifest, and fails on any digest mismatch.
`ESV_REVIEW_REPO`, `ESV_TOOLCHAIN_ROOT`, `ESV_REVIEW_CACHE`, and `ESV_REVIEW_JAVA`
retain the established standalone-runner overrides for another authorized checkout.

Artifacts:

- `docs/m8b1/evidence/independent-history-review.kt`
- `docs/m8b1/evidence/independent-history-run.sh`
- `docs/m8b1/evidence/independent-history-execution.log`
- `docs/m8b1/evidence/independent-history-legacy-expectation-diagnostic.log`
- `docs/m8b1/evidence/independent-history-run/independent-results.log`
- `docs/m8b1/evidence/independent-history-run/independent-history-frozen-before.json`
- `docs/m8b1/evidence/independent-history-run/independent-history-frozen-after.log`
- `docs/m8b1/evidence/independent-history-run/independent-compiled-source-sha256.txt`
- `docs/m8b1/evidence/independent-history-run/independent-source-unchanged.log`
- `docs/m8b1/evidence/independent-history-run/independent-toolchain-sha256.txt`
- `docs/m8b1/evidence/independent-history-run/independent-toolchain-match.log`

## Pass limits

This is a focused ordinary source review and 16 JVM fixtures with fake clocks,
fake providers and the actual in-memory transaction/planner/coordinator. The
standalone runner does not execute Android store/controller/Compose code. It does
not certify rendering, controller lifecycle races on a device, actual Android
migration/rollback, large-database resource limits, disk reclamation or power-loss
recovery. The builder's full regression/compile/lint and separate host-SQLite gates
remain distinct evidence and are not counted as tests run by this reviewer.

No APK/AAB assembly or device/emulator installation, real accounts, network/services,
payment, new dependency/external implementation, SkinKit change, or B2/B3 work was
performed. No M7-D candidate or Vault implementation was opened or executed; no
blocked review/probe was retried or replaced by stress work. Vault remains disabled.
