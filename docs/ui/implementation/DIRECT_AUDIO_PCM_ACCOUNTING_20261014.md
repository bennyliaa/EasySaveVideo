# Direct audio PCM accounting slice — 2026-10-14

## Verified result

Two new platform-neutral production/test files implement aggregate PCM accounting. Independent design and source review passed. Focused JVM tests: 92 passed (48 new PCM, 14 canonical, 6 clock, 24 video tail). Entire Save module: 26 suites, 434 passed, zero failures/errors/skips. Debug and release Kotlin compilation passed. Runtime admission verified the unchanged exact 91 artifacts. No Android/device/native decoding test ran.

The helper checks successfully queued input credit, cumulative PCM frames/bytes, absolute timestamps, observed format/extent, split/coalesced output, both EOS events, bounded reads, cancellation and sticky failure. A later queue cannot repair premature output. All PCM may legally arrive before input EOS when all compressed input has already been queued.

## Scope and limits

No existing production files, model, codec/schema/version, dependency or admission setting changed. Results are ephemeral and have no publication authority. Six exact ASC configurations, complete raw AAC AU syntax, source-byte conservation and exact decoder session ownership remain caller obligations. Aggregate matching cannot distinguish stale or substituted PCM within available credit or prove each AU's independent frame count. MOV audio remains unadmitted.

## Evidence

External evidence directory: esv-mov-audio-proof-20261014/verification.
- focused-first.log/exit and focused-first-xml: SUCCESS 20s, exit 0, 92 passed.
- module-regression.log/exit and module-regression-xml: SUCCESS 16s, exit 0, 434 passed and both app Kotlin variants compiled.
- SOURCE_REVIEW_HASHES.json binds the independently reviewed two-file snapshot.
- Approved design SHA256: 9678b717d2ab3d299256566cfdc1fca21312d24b31d6a38412a578326c845adb.

The previous full-project checkpoint remains 4958 PASS + 2 SKIP; this slice's 434 module tests are not a new full-project aggregate. GitHub synchronization remains paused. No APK was assembled or installed.

## Next executable scope

Design the smallest actual Android decoder integration using the existing normalized output owner, native lane, byte conservation, PCM helper and real release protocol. Determine raw AU syntax/equivalent executable proof requirements before permitting audio. Preserve current publication and device gates; do not introduce a schema bump without a demonstrated compatibility requirement.

## Full regression follow-through

All 13 modules were forcibly re-executed: 459 suites, 5008 items = 5006 PASS + 2 existing SKIP, zero failures/errors. Full run SUCCESS 3m51s, exit 0. Both app variants compile; lint has zero errors (debug 71 warnings + 8 notes, release 72 warnings + 8 notes). Evidence is verification/full-regression-xml and FULL_TEST_TOTALS.json. This replaces the prior aggregate only for this source snapshot; device/native acceptance remains absent.

Next-design calibration: the original PR section 7, TR-06 and T-S10 require detected and playable MP4 conversion with honest unsupported outcomes. Complete AAC bit-consumption/formal grammar was an extra design constraint, not original product wording. A later explicitly reviewed trust-boundary replacement may rely on admitted extractors/Android decoder plus full byte/time/PCM conservation and exact ownership/release. Current source and admission are unchanged by that proposed replacement.
