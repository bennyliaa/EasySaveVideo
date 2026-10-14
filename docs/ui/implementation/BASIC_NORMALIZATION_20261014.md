# Free basic format normalization — layer 1 checkpoint

2026-10-14 UTC. Source baseline:
`eb1ec3e1216c9bae655655906afb73c023024818`.

## Result and remaining boundary

The reviewed contracts, persistence and exact-owned workspace layer is source
implemented and tested. Production MOV admission is still OFF. No normalizer is
wired into SaveCoordinator, AndroidSaveRuntime or AndroidLocalMp4Verifier. This
checkpoint does not claim MOV output, device decode, playback or complete PR-10/11.
No APK was rebuilt/installed; the preceding frozen candidate is unchanged.

## Implemented

- Raw TransferResult keeps original HTTP bytes/MIME/expected length. Independent
  immutable DirectNormalizationProof binds task/source/artifact/commit/generation,
  raw/output SHA-256 and sizes, configuration/sample/timing/tail conservation.
- Typed ArtifactOutputDescriptor selects canonical output for SaveRecord validation,
  Library publication and final committed/Discovery success feedback. Ordinary
  verifier adapters retain compatibility; normalized descriptors fail closed until
  explicitly implemented. Stream/Record proofs cannot substitute for Direct proof.
- SaveRecord codec v7 reads versions 1–6; standalone evidence v4 reads 0–3. Legacy
  records receive no invented normalization proof. Limits remain 32 KiB/4096 bytes.
- DB9 adds an exact-owned workspace ledger. New creation and every supported
  1–8 upgrade retain prior migrations/rows/foreign keys. Terminal Save state wins
  over stale CONTINUE disposition; successful publication cannot become DISCARD_ONLY.
- File protocol preserves separate raw/output roles; durable creation/adoption
  intents, two no-replace renames, passive tri-state inspection and exact terminal
  cleanup. Pending keyset queries include terminal/DISCARDED parents. No arbitrary
  path, unknown file, user media, generic recursive deletion or production cleanup
  action is introduced. OwnedNormalizationFiles is a reference/fixture adapter;
  actual runtime muxer descriptors remain unconnected.
- DirectLocalProcessingGate serializes a whole future local pipeline on an existing
  worker, without new scheduling or deadlines. A permit release is not native or
  descriptor release evidence. Late cancellation does not reinterpret committed
  success. NORMALIZING/ADOPTING labels exist but no production stage enters them.

WRITING with partial output is deliberately inspect/preserve or terminal exact
cleanup only in this layer. Returning its ledger row does not authorize truncation
or restart. A later adapter must supply an explicit safe user-visible continuation
or cancellation/new-task path; it must not loop on an unusable Resume action.

## Independent review and corrections

Design review accepted contracts/storage against design checkpoint SHA-256
`ee7980d442d35fb27ac22fd12e172ecc37d9af482e686b0886dd4d239a01e8eb`.
Later adapter refinement is recorded separately in that design and the clock
contract; no source admission is implied by a design hash.

Code review found two real crash-durability gaps: mkdir-completed-then-throw retry
could skip parent directory fsync, and primary-only unlink-completed-then-throw
retry could acknowledge cleanup without staging fsync. Both were corrected:
WRITING acknowledgement follows unconditional generation/workspace/staging
barriers; CLEANED acknowledgement follows staging sync and fresh complete absence.
New ordered fault assertions passed; independent review closed both findings.

## Actual evidence

- Final fresh run: 13 modules, **4075 registered = 4073 PASS + 2 existing corpus
  SKIP**, zero failures/errors. App 2675 PASS; Save 226 PASS. All module test tasks
  executed, rather than relying on cached XML. BUILD SUCCESSFUL in 2m53s.
- New tests: Direct contracts 9; local gate 11; typed verifier compatibility 2;
  codec/publication/Discovery 7; workspace protocol 16. Existing verifier total21,
  codec22 and Library publication5 also passed.
- Production-extracted host SQLite gate: 20 tests including all eight migration
  baselines, 411 injected rollback boundaries, exact-parent/live-generation,
  immutable source/proof, terminal authority, bounded pending and retained history.
  This uses owned temporary SQLite files and explicit helper-dispatch modeling;
  it is not Android SQLiteOpenHelper execution or real legacy media backfill.
- Debug/Release Kotlin and lint succeeded. Lint unchanged: Debug60 warnings/8 hints,
  Release61 warnings/8 hints, zero errors/fatals. Exact90 runtime inventory,
  platform/provider boundaries, original99 license payloads and seven-getter ABI
  checks passed. No dependency, manifest, permission or SkinKit change.
- Initial compile attempts encountered incomplete parallel edits and two test
  expression-return visibility errors, both repaired. One diagnostic full run
  used old production class10:11:26 with new test source10:13:22 and failed the
  two new fsync assertions. Stable recompilation passed16 at10:14:54; the final
  fresh run supersedes these diagnostic attempts. No assertion was weakened.

Sanitized machine summaries and source manifest are in
`basic-normalization-layer1-20261014/`. Raw local XML/logs are retained separately.

## Private backup and continuation

Normal incremental backup targets only the existing private development branch,
using expected-head CAS and whitelist/tree verification. No main, visibility,
credentials, personal media or other project materials are included. Publication
receipt records the actual commit separately; absent remote CI contexts are not
reported as successful CI.

Next: implement the reviewed silent-only MOV host adapter under
`MOV_CLOCK_AND_CANONICAL_CONTRACT_20261014.md`, prove actual pinned bytecode sample/
clock/config conservation and the rejection matrix, then integrate guarded runtime
normalization and strict final Android verification. Audio admission remains
closed until bounded AU validation and independently tested PCM-frame/timestamp
proof exist. Any audio track rejects the whole file, never drops audio. This
first profile helps already-silent compatible sources only; common camera MOV,
AAC completion, WebM and broader clocks remain explicit original-scope work.
