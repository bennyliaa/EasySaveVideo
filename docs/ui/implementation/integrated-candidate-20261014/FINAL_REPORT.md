# ESV integrated Debug candidate, 2026-10-14

## Outcome

Four completed cloud-only slices are integrated: media-library display controls,
Discovery navigation/recovery, bookmark reading/feedback, and ordinary browsing
history. History retains at most 200 entries for 30 days with explicit manual
clear. Existing controller/navigation gates and local noBackup storage remain the
foundation. No real browsing records or user-device data were read or uploaded.

Source commits on `development/cloud-followup-20261013`:
- Library: `8e9dc9faaa4d2f07513c2379af18b4e138f5fde3`
- Discovery: `821dc8ed4673407ee979761e2e7d133df87e8061`
- Bookmarks: `a5859d0217df32302b251b5289573fe51b4e6181`
- History: `e5ad2a696b3dde72bc07a3ad55f3676b8aa4dc28`

The history commit adds/updates exactly 17 source/test/resource/build files plus
its implementation report. All 18 were fetched at that exact SHA and compared
with complete local UTF-8 content. All 8,590 unrelated existing leaf entries
were preserved. The branch update used an expected-head lease without force.
`main` was not changed.

## Candidate identity

File: `EasySaveVideo-debug-code8-20261014-0a5dd621168b.apk`

SHA-256: `16449658d017a64456ebf921aca0c138e85e9b7dc43ed20dda0b4aa05d2f29d8`

Size: 49,303,976 bytes. Package `com.easysavevideo.app`, version code 8,
version `0.8.0-ui-dev`, min SDK 31, target SDK 36. Existing Debug certificate
SHA-256 `18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5`.
APK signature validation and zip alignment (4-byte and 16-KiB native pages)
passed. This is a local Debug candidate, not installed, not uploaded as an APK,
and not a store/release build. Previous candidate and source/report originals
remain retained.

Frozen source input manifest SHA-256:
`0a5dd621168bfffd760b1c299ee13421859179aa7daefb9e133c9f968ba07135`.
All 1,481 local input hashes remained unchanged after assemble and inspection.
Remote tree verification matched 1,480 input Git blob hashes byte-for-byte.
The one existing difference is `third_party/current-runtime/poms/org.jetbrains__annotations__23.0.0.pom`:
local upstream-preserved CRLF versus remote LF; normalizing only CRLF to LF
produces the exact remote Git blob `f6b1377e504ba71cbc39afcb45b9ffe4ceb97ac6`.
This existing line-ending discrepancy was disclosed and neither original was
rewritten; a byte-identical whole-tree backup is not claimed.

## Validation evidence

The final strict-offline regression exited 0. All 13 module test tasks and
runtime-admission task were EXECUTED, not merely UP-TO-DATE. 3,682 registered
unit tests: 3,680 passed, two existing private-corpus skips, zero failures/errors.
App: 2,394 passed. History scope: 102 passed, with 59 added tests in this slice.
Debug and Release Kotlin compilation passed. Debug and Release lint each have
zero Error/Fatal; Debug has 60 warnings/8 hints, Release 61 warnings/8 hints.
Warnings are not represented as fixed. Runtime dependency admission covers 90
artifacts. Assemble completed successfully; no replacement build was needed.

An intermediate full regression had a BrowserJsDialog source-contract mismatch
following the history integration. The contract was aligned with actual wiring,
then the final 13-module regression was freshly run successfully. Intermediate
failure evidence remains preserved locally.

Independent history review found no blocking defect. JVM tests exercise the
production recording adapter and core with in-memory storage, plus source wiring
contracts. They do not instantiate a real WebView or Android AtomicFile storage.

Core/platform/provider/storage import boundaries, Debug-only fake entitlement
and synthetic Discovery boundaries, original license/recovery bytes (99 payloads
plus manifest), original SkinKit release JAR hashes and seven-getter ABI passed
existing checks. No new dependency, SkinKit replacement, billing/Vault/dynamic
rule activation, or native device acceptance is implied.

## Difference from previous frozen candidate

Previous APK SHA-256:
`fd8af644ae532f8241ea830a869995360139732089aee9c918a1da5e6c05480e`.
Size increased from 47,803,784 to 49,303,976 bytes (+1,500,192).
Of 205 ZIP members, 198 are byte-identical; only six DEX files and
`resources.arsc` changed. No member was added or removed. AndroidManifest and
native library members are unchanged. Source inputs increased from 1,453 to
1,481: 28 added, 11 changed, none removed. Detailed inventories accompany this
report. ZIP CRC passed; listed high-confidence credential patterns had no hits
and no private-key/environment-named members were found. Static inventory is
not proof of absence of every secret, all possible endpoint, or runtime behavior.
The Debug candidate intentionally retains owned fixtures/fake entitlement and
the loopback verification surface. No external endpoint was contacted in this
inspection.

## Remaining acceptance

Device/emulator installation, real WebView callbacks and Android AtomicFile
failure paths: NOT_RUN. Native save/verification/library/playback,
recording/editor output, SAF interruptions, rotation/window changes, short
landscape, 200% fonts, TalkBack, performance and reference-layout device
acceptance remain NOT_RUN. Installation is deferred per the user. Thumbnail
loader/source rights, reference imagery rights and deferred feature gates retain
their existing separate constraints.

For the exact history SHA, GitHub reported zero workflow runs, check runs and
statuses. The aggregate status was pending with no contexts, so remote CI PASS
is not claimed. Cloud-local regression evidence is the validation basis.
