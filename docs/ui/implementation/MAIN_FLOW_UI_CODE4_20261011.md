# Main-flow UI slice and code4 preparation

Historical code4 preparation record. Current status is maintained in
ESV_UI_FEATURE_INTEGRATION_LEDGER.md; later source work is not in code4.

Base: b22ae74 (the independently committed repeated-root P1 fix). Four existing
feature pages are refined: Discovery → Browser inspection/explicit selection →
Save tasks → ordinary Library. This is a callable real implementation chain,
not newly executed Android saving/playback acceptance.

- Browser and discovery input now use standard Material3 OutlinedTextField with
  existing edit/search/navigation callbacks and typed keyboard actions. Browser
  inspection remains disabled until the selected page is READY and while
  scanning. It opens the existing revision-fenced selection workflow; it does
  not imply a confirmed downloadable video or automatically start another method.
- Browser primary actions remain visible; bookmark/history navigation controls
  are under More controls. Source-free address browsing remains reachable from
  the actual NO_SOURCES empty state and remains independent of discovery readiness.
- Task capabilities/budgets are expandable. Persisted task state/phase, output
  disposition, unknown progress, retry and failure labels are localized. Completed
  task history does not imply a deleted/unavailable output still exists. Preview
  retains the existing exact-reference/READY/native-resource gate. The domain
  coordinator, progress accounting and recording consent are unchanged.
- Library retains stable item IDs, read revisions, preview/edit/export fences,
  explicit irreversible-delete confirmation, and local operation commands. Grid
  preference is saveable. Vault is explicitly unavailable/disabled.
- 217 English/Chinese resource pairs cover key main-flow operations and state
  messages. Static XML checks verify matching keys, placeholder arguments and
  absence of replacement characters/duplicates. Existing runtime notices, rights
  summaries, advanced recording/editor text and some dynamic metadata still need
  migration; full application bilingual completeness is not claimed.
- EsvText/EsvButton adapt standard Material3 Text/Button. SkinKit still supplies
  the detached typography/color/shape/padding snapshot, including disabled >
  pressed > focused state priority. Button elevation is disabled. No new theme,
  dependency or runtime owner is introduced; exact admission remains 90 files.
- Four migrated pages consume shell system insets once. Headers wrap actions;
  large-font navigation retains accessible icon names while omitting labels
  beyond 150% font scale. Actual 200% layout/TalkBack remains NOT_RUN.

## Validation

Scoped PASS: Debug/Release compilation executed after final changes; strict
runtime admission verified 90 files. 82 focused JVM tests: 0 failures/errors/skips.
App/discovery70 tests executed freshly; unchanged SkinKit/appearance12 tests reused
their prior passing results. Discovery coordinator/priority51 tests cover leaving,
background, task priority, cancellation and stale results; app P1/route/notice/save
policy19 tests passed. Both lint variants completed with zero Error/Fatal; warnings
are retained. Evidence: artifacts/ui/main-flow-code4-20261011.
The full Windows test suite and historical 115 failures remain outside this
focused group; no global pass claim is made.

## APK and device handoff

App versionCode=4, versionName=0.4.0-ui-dev, minSdk31 (Android12), target/compile36.
An unsigned Release APK was built from exact source d9bdf34567bf2d138e796a50560419d359385cf4, with
Commons disabled/contact empty and distribution channel unconfigured. These
build flags create a zero-source, no-account/no-billing device UI acceptance
package. Normal TLS, original source/rights/feature gates and recording consent
remain. It is an internal development artifact, not production/store acceptance.

Subsequent signed return d44be6f9cfd01a35529d1fb47e19bf0dabd6bb73 passed official
Windows signature/alignment checks. One compatible code3→code4 install-r on
emulator-5556 preserved data. No OPPO operation, microphone, recording or private
media content read occurred. Future packages need a higher versionCode through
the existing compatible signing process. The expected existing public signer
certificate SHA256 is 18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5;
no private key is read, exported or created. Unsigned payload and manifest/hash
receipt are handed off through the user-authorized private ESV repository in a
separate delivery branch. Main remains source/docs/test evidence only.

The bounded result has13 PASS and3 initially unaccepted scopes; see
artifacts/ui/code4-bounded-device-acceptance-20261012/RESULT.json. Subsequent normal
signal-refresh/P1 evidence is separate; the old budget0 snapshot cannot identify
a particular signal cause. Subsequent natural-refresh evidence records P1 PASS
without provider/gate/system changes, separate from that historical result.
Source-free address draft, route returns, theme/language and rotation/process
checks are bounded passes. Full acceptance still needs phone/tablet/compact-height and
font200%/TalkBack. Save/record background work and preview/resource release need
their existing authorized fixture/capability boundaries; do not start recording
or read private media. Device power/100-sample/public-web acceptance is NOT_RUN /
NOT_MEASURED. Private content/raw device UI is never uploaded.
