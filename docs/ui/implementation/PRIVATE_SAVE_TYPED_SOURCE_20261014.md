# Private Save typed source checkpoint — 2026-10-14

## Implemented and checked

- Core private network inputs use independently random task/selection identities. Durable `PRIVATE_NETWORK` contains no source URL/origin, source-derived candidate/variant hashes, browser profile, request headers, expiry or server ETag. Private download checkpoints remain process-memory-only and never enter the journal.
- Record codec v8 retains v1–7 reads and unchanged ordinary NETWORK/CAPTURE layouts. Historical QuickTime/MOV proof/evidence decoding uses static invariants, independent of the current live MOV/device capability gate. Existing record capture metadata is already URL-free.
- Output verification, normalization, adoption, commit, Library publication and task success use output-only source metadata without reconstructing a network source.
- Core source detachment follows actual transfer release and exact raw-result persistence. Private exit competes with source detachment; output that already detached is not cancelled merely because browsing exits.
- The tested app source owner keeps a DORMANT registration for a live resumable partial Direct task after actual execution-claim retirement. Fresh Resume atomically takes over that registration with a fresh claim. Late old-claim callbacks cannot retire the new owner. Stop dispatch or reference-retirement uncertainty retains the session drain obligation.
- Direct/HLS request-context live authority checks include credential and anonymous redirects, request/response/body work and cancellation watchdogs. Credentials expire independently from a positively admitted body; explicit revoke/clear still reaches retained bounded private fences. These ports do not themselves assert whole Runtime/session drain.

## Actual evidence

Evidence directory: `/workspace/shared/esv-private-save-integration-20261014`.

- `core-second.log`: complete save-module suite **386 PASS**, no failures/errors/skips; new Resume fixture uses sufficient budget for the existing conservative per-attempt reservations. No production budget semantics were weakened.
- `app-second.log`: debug/release Kotlin compilation, **267 PASS** focused tests and both lint variants succeeded. Debug lint: 66 warnings + 8 notes; release: 67 warnings + 8 notes; zero errors and zero RequiresFeature warnings. XML in `app-second-xml`; SARIF copies carry the matching prefix.
- `app-fourth.log`: **87 PASS** final focused tests, including actual codec 11+23, MOV codec 8, actual adapter composition 11, source owner/core composition 16 and browser source-boundary 18. XML frozen in `app-fourth-xml`.
- The actual source-owner compositions use real Coordinator, admission and execution retirement with a memory journal/fake media ports. App controlled-storage tests use the real bridge/adapter/owners and fake OS/SQL/native/remux. They do not prove Android/provider behavior.
- Independent codec/metadata review passed 14 matching hashes in `CODEC_REVIEW_HASHES.json` (SHA-256 `1af7e4000e875ce0b04c9c345cdf8b7af89a81a23b9e3929de8e58cc25b493ca`). Source-owner review passed final helper SHA `11b856d030343a60191926aad772f527dfea648ac4d49877eace88626f728111` and its 16-test fixture.

## Remaining integration and acceptance

At this checkpoint, the production private entry is still gated off: actual Runtime private Direct/HLS capture/lease/source-owner wiring is in progress, and recording③/window/dialog/recents protection has not been installed. Browser UI/IME additions are a subsequent compile slice. The user explicitly permits private recording③; the implementation must preserve this intent, fresh per-start disclosure/system consent, actual capture ownership, and Stop-and-finalize on private exit while retaining saved media.

Ordinary HLS partial Resume already requires fresh selection; this requirement remains for private HLS. Active-operation retries are separate. No new partial HLS replay protocol is claimed.

Real partition isolation, Android SQLite, actual network/native release, process-crash profile cleanup, window/Dialog autofill/content capture, and independent recents protection during the private recording exception need their own evidence. No APK/device acceptance occurred. Production MOV stays disabled pending its existing Android/native gates. The previous immutable recovery bundles are untouched.

GitHub writes remain paused after an unresponsive connector operation. No new remote checkpoint is claimed; last verified dev head remains `06d3f50884ea88d1de647d212264c6488d3a658d`.

## Next executable work

Connect the tested source owner to actual Direct/HLS Runtime and exact HLS release; compile/review that composition. In parallel, implement the approved private recording/window bridge using the existing Record owner rather than a second capture stack. Then run the all-module regression and preserve a fresh local recovery bundle before any separately resumed remote synchronization.

## 16:00 UTC: actual private network ownership checkpoint

Direct/HLS Runtime wiring now uses the Host partition context and process-local source ownership. Partial Direct resumes retain a dormant source registration and need a fresh exact-document capability; HLS partial resume still requires reselection. The HLS preparation attaches exactly one final private resource/binding and checks task, operation, source tuple and expected audio before transport. Actual source-release evidence permits output-only processing; lease revocation alone does not acknowledge drain.

Both Kotlin variants compiled. The frozen third focused run contains 33 suites / 320 tests, with no failures, errors or skips. Evidence: `esv-private-network-runtime-20261014/network-window-third-xml` and `network-window-third.log`. The independent network code review verified all 15 hashes in `FINAL_NETWORK_REVIEW_HASHES.json` (manifest SHA256 `3fa770a5f9b4c12b58364f5b49750fb3a570e04a0181d51a0c7c43edbb437173`). The first two runs exposed an invalid test retry budget and obsolete Host source-contract assertions; both were corrected without changing production behavior.

This run also executes the new native Activity root's 15 production-state tests and 6 source-wiring contracts. Its independent code review is separate. Host/OS/profile cookie isolation, actual provider windows, retained InputConnection behavior, Android cleanup, and device recording remain unaccepted. The private entry is still disabled until its composition gates are implemented and verified.

Next executable work is the approved BASIC private recording bridge: exact service-ready parking, fresh Continue after a protected consent round trip, pre-acquisition native stop checks, and KEEP_VALID_OUTPUT after actual native release. API31/32 snapshot-risk product choice remains pending; no requirement has been deleted. Recording implementation is currently prepared outside the validated source snapshot. GitHub writes remain paused because of connector blocking; these results are local, not remotely synchronized.

## Final local network/root checkpoint, 16:21 UTC

Final 13-module run: 433 suites, 4,702 test cases = **4,700 PASS + 2 SKIP**, zero failures/errors. Debug/release lint, runtime admission, source boundary checks, both host SQLite scripts and the exact WebKit admission check completed successfully. This includes the reviewed ordinary content-capture restoration fix: the app restores its explicit ordinary opt-in policy, never interprets the effective system/service getter as app policy. Window code review manifest SHA256: `f28a3fe1c0955fb7eab43e4796c68e968c353e5e76bf916571a35c1bce40069c`.

Final evidence: `esv-private-network-runtime-20261014/full-final-xml`, `full-regression-final.log`, `FINAL_TEST_TOTALS.json`; root evidence: `esv-private-window-root-20261014/focused-final-xml`. The earlier 15 Host-contract failures were reconciled with the actual partition-owned implementations; all 52 original methods in the seven affected classes remain and pass. Their independent delta review found no ordinary behavior regression in that scope.

No APK was assembled, no device acceptance was performed, no production private entry/capture exception was enabled, and no remote synchronization is claimed. Recording and protected private-window activation continue as the next original-requirement layers; their external drafts are excluded from this checkpoint.
