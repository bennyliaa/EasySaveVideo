# Finite Direct MOV AAC integration — 2026-10-14

## Result and exact scope

The controlled Direct normalization chain now implements one AVC video plus optional finite AAC audio: bounded owned-file container/config/sample inspection, real admitted Media3 extraction and muxing, independent per-track endpoints, raw/callback/output conservation, exact adopted-output ownership, fresh native AAC decoding with full PCM accounting, and typed verification/commit/recovery. The native function is called by the existing verifier; there is no diagnostic bypass or separate unowned route.

Production `movAdmissionEnabled=false` remains. This is source implementation with host/compile evidence, not real Android MediaCodec, Os, SQLite or device playback acceptance. No APK was assembled or installed and no GitHub synchronization occurred.

## Final verification

- All 13 modules forcibly re-executed: **5060 PASS + 2 existing SKIP**, 461 suites, zero failures/errors; `full-final.log`, SUCCESS 3m58s, exit 0. Exact XML is `full-final-xml`, totals in `FINAL_TEST_TOTALS.json`.
- Debug/release Kotlin compile passed. Lint has zero errors: debug71 warnings+8notes, release72 warnings+8notes. SARIF frozen as `lint-final-{debug,release}.sarif`.
- Focused save116 passed; first app159 passed. Final evidence-assembly focused96 passed, including actual production assembler audio/silent/expected-audio/hash/digest cases.
- Pinned Media3 1.11.1 transport:31 tests passed, including36 actual two-track round-trip parameter combinations. AAC bytes are opaque transport fixtures; they do not prove AAC decoding.
- Pinned Media3 plus actual owned-file protocol:7 tests passed, including audio policy2 finish/adoption and failure/uncertain-release paths. Descriptor IO is a host seam, not Android Os execution.
- Current and typed normalized-commit host SQLite scripts passed. They execute extracted SQL with synthetic records, not Android SQLiteOpenHelper.
- Runtime admission retained exactly91 reviewed artifacts. Core boundaries, Debug-only fake/demo isolation, recovered license bytes and SkinKit ABI checks passed.

Independent reviews approved core6, transport5, app/storage12 and native8 files. Their manifests and logs live in the local evidence directory `esv-mov-audio-integration-20261014`; prior failed/pre-fix runs remain separately labeled.

## Findings fixed before final acceptance

The first pinned negative test incorrectly expected Media3's builder NO_VALUE(-1) delay to remain -1. Exact admitted Format construction converts it to exposed0. The corrected test checks this actual mapping and rejects genuine positive/negative nonzero delay/padding; production was not weakened.

Independent native review caught positional VerificationEvidence construction setting `encrypted=true` for successful audio. The final code uses a small real production assembler with named `encrypted=false` and observed audio readability. Three behavior tests exercise this mapping, typed validation, missing audio and mismatched proof/hash. The earlier green full run is retained as PRE_FIX and is not final evidence. All final tests were rerun after the fix.

## Policy and compatibility

Original PR section7.1, TR-06 and T-S10 require genuine compatible playable MP4 or an explicit unsupported outcome. They do not require a newly implemented full AAC grammar parser. The explicitly reviewed design `MOV_AAC_TRUSTED_DECODER_INTEGRATION_20261014.md` accepts admitted Media3/Android elementary-syntax interpretation while ESV retains exact bounded container/ASC/byte/time conservation, full real PCM/EOS observation, fixed-FD/session ownership and actual-release checks.

Support is limited to the declared six LC ASC/rate/channel combinations, existing finite no-trim/zero-origin video/audio profile and tested platform capability. It does not prove individual packet frame attribution, every-bit consumption, impossibility of in-band features, or absence of decoder concealment. Unknown/protected/unpreservable tracks fail; audio is never dropped to manufacture a silent success.

The existing serialized policyRevision integer distinguishes new audio policy2 from silent policy1; recordv8/evidencev4 field layouts and database schema are unchanged. Historical audio policy1 still decodes and its successful terminal data remains readable. Unfinished old-audio rows become recovery-required without rewriting proof, ledger or source and without network/remux attempts. New policy2 COMMIT recovery requires exact prior verification/ledger/catalog/file-role binding; video-only policy1 behavior remains.

## Remaining gates and continuation

Actual MediaCodec input/output format, framing, PCM count/PTS, error concealment behavior, release timing, Android file/SQLite durability, and real playable saved outputs require authorized device validation. Thirty-minute resource limits are ceilings, not performance promises under the unchanged absolute verification timeout. Production remains closed until that separate acceptance succeeds.

Private browsing/platform gates and the two recording product decisions are unchanged by this audio work. Those recording decisions do not apply to secure browsing/network saves.

Next original-requirement work is a bounded WebM/basic compatibility design using already admitted Transformer and reusable native/output mechanisms. It must distinguish transcode semantics from this remux-only proof, preserve free basic saving, and avoid new formal parsers or unrelated dependencies. No WebM source change is included in this checkpoint.
