# About/offline notices completeness and read position

Independent continuation after73807af in the same Settings group. Original docs/ui
require bilingual settings and existing offline license/attribution access; real
552250-byte asset and reader already implemented. No new source endpoint, clearing
command, privacy/security change, agreement acceptance, dependency or disabled feature.

Four reader controls now use paired EN/ZH resources: title, Back to Settings,
loading and unavailable. The existing unavailable meaning "do not distribute
this build" is retained in both languages. The license正文 is never translated,
trimmed, rewritten or fetched remotely; asset SHA remains unchanged.

noticeSections replaces raw UTF-16 chunking: prefers existing newlines, never
cuts a valid surrogate pair or CRLF, keeps every character including whitespace,
bounds display blocks4096 chars and guarantees forward progress. Exact concat is
verified on the real bundled file, long lines, supplementary Unicode and1MiB text.
The original bounded reader still rejects oversize/zero progress. Empty content
is unavailable, not permanently loading or fabricated attribution.

Settings retains one rememberLazyListState outside the notice branch and passes
it to the viewer, preserving scroll index/offset across close/reopen in the same
host and through the standard saver. Category key and notice Back priority remain
unchanged. Browser's original trailing-lambda API retained by an overload; its
source is not edited. Initial compilation exposed the signature compatibility
issue, corrected with that overload before acceptance.

Reader still loads on its existing background worker, disposes delivery and
interrupts on exit. LazyColumn remains bounded under weighted body, no nested
scroll. No full rendering/OEM/SavedState/TalkBack/large-font device claim from
source checks. Code8 package and device evidence directory remain immutable.

Evidence artifacts/ui/settings-offline-notices-20261012, final test/build/lint
counts recorded after completion. Source and evidence are a separate local commit
for unified review after device testing, not an installed feature.

Remaining original Settings capability work is not silently filled: provider
update/rollback acceptance, all save/record/privacy controls and the four-strategy
mapping must be checked against actual interfaces and product scope. This slice
only completes real About content/control reading, not those other capabilities.

Final24 actual tests PASS/0 failure/error/skipped (11 new notice tests,3 existing
reader tests,10 Settings tests). Dual Kotlin executed, lint59W/8H/0E;67 tasks15
executed/52 up-to-date. No empty list is composed while loading/error, preventing
reset of the retained state before text arrives. Asset unchanged,552250 bytes,
SHA27df093ea522ae12733b400e54856dbadd68b695b258ce0b7e31a778d5101a5e.
Code8 input hash rechecked unchanged. Device restoration/pixels NOT_RUN.

Read-only code8 stage snapshot: final OPPO-CODE8-DEVICE-RESULT.md absent. Receipt
15:01UTC still installation_or_launch NOT_RUN (old transfer receipt, not current
install proof). Later17:46 editor UI says storage unavailable;17:47 share UI says
component initialization incomplete.17:50 gallery and17:54/17:58 directory UI show
two items externally verified.17:56 background-before-confirm shows stopped.
18:05 font160-named file contains end controls but actual font_scale is not proved
by a filename.18:09:26UTC move UI says moved2/unconfirmed0. These are intermediate
UI observations, not complete installation/version/device acceptance. No phone
operation, second test harness or copying of functional media/URIs/credentials.
