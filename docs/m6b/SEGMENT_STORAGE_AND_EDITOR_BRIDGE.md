# M6-B private segment ownership and editor bridge

## Data and authority

`recording_session` and `recording_segment` are separate private ownership journals in the existing
storage database (version 5). Random UUID segment names live only in `recording-segments`; they are
never ordinary Library representations, Save tasks/receipts, preview/share/export entries, network
requests, or user-controlled paths. Existing M4 Save/Record publication is separate.

A session freezes its descriptor, generation, capture geometry and optional region recipe. A closed
segment persists its contiguous ordinal, cumulative effective start/end, actual codec/audio profile,
actual mux sample counts and first/last/tail timestamps, exact byte extent and SHA-256, inode identity,
actual geometry observation, and SHA-256 over every observed native video PTS followed by its end.
The timeline digest is provenance, not a replacement for rescanning the actual MP4. It makes no claim
that device capture is CFR. No reusable system consent or grant is persisted.

The private group budget is one initial segment plus at most 32 effective resumes, 30 minutes,
2 GiB total, and 108,000 video frames. Ordinary M6-A requests remain limited to eight inputs.
Recorded edit requests use `RecordedSegmentReadRef`, require the same session and generation,
contiguous ordered ordinals, and unique segment identities; mixed catalog/recorded requests reject.
Request validation examines one bounded immutable snapshot, never a second read of a mutable list.

## Ownership and closure

Every output retains independent Java-writer, native-writer and reader fences. A closed callback
must find all three counts zero and no unknown release. The original output capability cannot
reopen or reacquire a native fence after `ALLOCATED`/`CAPTURING` ends. A timeout or cancellation does
not decrement a fence. Verification descriptors close only after the independent native verifier
acknowledges release. Helper hashing/sync descriptors also remain strongly retained if actual close
cannot be established. Such ownership blocks deletion and storage-owner shutdown until restart.

Private file operations admit generated journal identities only. They reject traversal, symlinks,
inode replacement, wrong extent and wrong digest. File deletion is followed by directory sync.
Unknown creation-to-identity crash state is retained as cleanup pending; it never licenses a guessed
unlink. No directory scan is used for recovery.

## Closed, verified, and published are distinct

1. Native closure produces `CLOSED_UNVERIFIED`. Closure journals/hashes bytes but does not launch
   native verification while capture continues.
2. After capture stops, the dedicated bridge gives each closed raw an actual owned descriptor and
   a private verifier identity. The unchanged M6-A full MP4/sample/decode verifier must prove the
   candidate profile, complete extent/digest, every admitted sample, decode EOS and actual release.
   A configured frame rate is merely a candidate to test; observed VFR is not normalized or excused.
3. Only independently verified, current raw identities can become recorded edit inputs. The existing
   source probe rescans actual timestamps and enforces the M6-A CFR lattice before planning. Merge,
   optional post-crop and optional compression use the ordinary bounded Media3 execution and a fresh
   independent final-output verifier. Invalid/incompatible sources never fall back to raw publication.
4. The ordinary catalog item, representation, edit success and exact recording-session publication
   marker commit in one SQLite transaction. A pre-recorded pending operation binds the session before
   editing. Restart/retry recognizes the already-published result rather than creating a duplicate.

AAC remains limited to at most two 1,024-sample packets per planned segment. The output expectation
explicitly carries the recorded segment count. Default/legacy ordinary expectations retain an
eight-segment maximum and cannot spend the 33-recorded-segment allowance. Neither interior AAC
continuity checks nor video timing tolerances were relaxed. The 33-CFR-input pure planner regression
checks 990 frames and exactly 33 seconds at 30 fps with the explicit AAC budget.

## Failure, cancellation, and recovery

Stop-and-save invokes processing; discard is a separate action. Processing failure or cancellation
retains closed raw segments, including independently unverifiable/VFR inputs, for accurate recovery
or explicit user discard. Successful publication also retains raw segments until explicit discard;
there is no automatic raw-source cleanup policy.

A tiny/empty abandoned tail can reuse its ordinal only after actual writer/native closure, exact
owned unlink and directory sync, and removal of the unreferenced incomplete ownership row. It gets
a fresh random identity. Cleanup failure blocks retry. Restart uses at most eight journal sessions
and 33 rows per session, never resumes capture and never auto-publishes. It cleans owned incomplete
outputs and retains valid closed prefixes. Bounded blob queries compare their result with the total
row count, so a corrupt oversized tail cannot masquerade as a shorter accepted recipe.

Partial explicit discard preserves each original ordinal and reports `DISCARDED` versus
`CLEANUP_PENDING`. Snapshot rows expose `retained` and `cleanupPending`; a historical duration is not
proof that a raw file remains. Only confirmed complete cleanup permits `CANCELLED`. Editor-output
cleanup failure reports recovery required instead of silently reporting ordinary completion.

## Host evidence and limits

- Filesystem tests cover actual synthetic bytes, inode replacement, truncation/appending/mutation,
  symlink/traversal rejection, unknown creation identity, directory-sync failure and unknown close.
- Pure contracts cover ordinary-versus-recorded limits, ordering/generation/type checks, immutable
  snapshots, exact 33-input CFR/AAC planning and observed-VFR rejection.
- Codec tests cover non-full region descriptors, selection time, actual geometry and actual timing.
  Legacy request/expectation/evidence binaries were produced by running unchanged committed Kotlin
  writers and matching original models from `a4fe4ba8416f8f8f39019dd7d996de032686f5b4` (v1) and
  `1c9dc1fcee17383e20787ddd6250ae6fc68ed44a` (v2). Generator and source/file hashes are in
  `app/src/test/resources/edit-codec-legacy/PROVENANCE.json`; current writers did not manufacture them.
- `verify-editor-sqlite.py`: 37 synthetic host SQL contracts pass against current v5 DDL with the
  independently frozen v3 baseline. `verify-recorded-segments-sqlite.py`: 10 tests pass, including
  exact frozen v4 source-row migration, per-statement rollback, private FK separation, closure/proof
  gates, 33-input bound, incomplete-tail retry, partial discard and atomic publication source anchor.

SQL host models and synthetic bytes are not Android storage integration or playable-media evidence.
The parent build owner records final compile/JVM/lint results. Actual MediaProjection, GPU crop,
codec/MediaMuxer behavior, full raw/final decode performance, pixel correctness, A/V quality and all
API31+ phone/tablet device tests remain NOT_RUN. No APK/AAB, device execution, external dependency,
Vault work, public publication, or full-screen file fallback is introduced here.
