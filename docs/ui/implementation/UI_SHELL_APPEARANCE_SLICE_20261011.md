# UI shell and appearance slice — 2026-10-11

Scope: the four main entries (Discover, Tasks, Record, Library), auxiliary Settings,
and secondary Browser, Sources and Editor use AndroidX Navigation Compose. Existing
feature screens still call their original runtime owners and their verified media
references. No replacement task, recording, catalog, provider or browser engine is
introduced. Navigation stores only finite compile-time route IDs. No page URL,
private media, WebView or credential becomes a navigation argument.

The width/height input is Material3 WindowSizeClass calculated once at the Activity
boundary. Compact windows use the four-item bottom bar; medium/expanded windows
and compact-height windows use a rail. Detailed adaptive panes and all 34 PPT
screen layouts are further work. Navigation is disabled during confirmation
selections and editor work; fullscreen and media/recording previews hide chrome.
Root switching does not cancel background save or recording tasks.

SkinKit 1.0.0 remains the sole theme provider. One immutable, released snapshot
feeds both existing Foundation controls and every Material3 color, type and shape
role. Secondary/tertiary colors currently alias existing ESV accent tokens and
container/shape roles alias existing surface/control tokens; separate semantic
card/sheet tokens are not yet claimed complete. Existing EsvButton state priority
is preserved. Material interaction-state mapping remains a later visual review.

The default palette uses the UI design's teal family. Light accent is calibrated
from #087F8C to #087582: its contrast on #F5F8FA improves from 4.4499 to 5.0784.
Dark palette uses #0E1B22 / #152832 / #62D9DF. Title/body/caption are logical
24/16/12 sp and still follow system font scale. Appearance preferences support
system/light/dark and system/Chinese/English. API31/32 use the existing preference
store and a localized Compose context; API33+ uses platform per-app locales.
Existing schema-v1 browser/discovery preferences remain additive and pageSize=20.
New navigation/settings resources are bilingual; legacy feature text is not yet
fully migrated. Settings exposes original offline third-party notices, with a
bounded 1 MiB character reader so the added attribution remains accessible.

## Exact dependency admission

- Material3 1.3.2, window-size-class 1.3.2 and Navigation Compose 2.8.9.
- Existing Compose BOM 2025.06.01, Kotlin 2.2.10, AGP 9.4.0 and Lifecycle 2.8.7
  remain unchanged. Earlier Java-only desktop resolution is invalid evidence.
- Final independent Android candidate had 93 rows / 92 unique byte identities;
  the repeated Android Core row was byte-identical. App resolution omits two
  redundant Kotlin JDK bridge candidates. Final gate is **90 exact external
  runtime files**, adding 13 files to the previous 77 without replacing baseline
  bytes. Both variants must satisfy the unchanged equality/hash gate.
- Each new binary and POM was read back from official Google Maven or Maven
  Central using normal TLS. POM licenses are Apache-2.0. Reviewed new archives
  add no manifest permission/native executable; bytecode is at most Java17.
- Exact official metadata hashes are added, including evicted-version and lint
  metadata. Desktop metadata needed by lint does not admit a desktop App binary.
  No permissive trust pattern, dependency verification disabling or runtime gate
  exclusion was introduced. Locks are regenerated under strict verification.
- Candidate review: `third_party/ui-framework-20261011/admission-review.json`.
  Original accessible licenses/notices stay in App assets. No SkinKit sources or
  evaluation binary is imported.

## Validation and boundaries

Final scoped validation: **PASS**. Debug/Release Kotlin compilation executed
after the final source changes; strict runtime equality verified 90 files in both
variants. Appearance (3), SkinKit (9), routes (2), and bounded notices (3): **17
tests, 0 failures/errors/skips**. Appearance/SkinKit were freshly executed in the
first validation and reused unchanged in the final run; they are not described as
freshly rerun in the final command. Debug/Release lint completed with zero
Error/Fatal; warnings remain in reports. Evidence: `artifacts/ui/shell-appearance-20261011`.
Intermediate metadata/resource/file-lock failures are retained and classified as
failed attempts. The full Windows test suite was not rerun; historical unrelated
115 failing tests are not erased by these scoped passes.

Device UI, rotation/process recreation, font scale 200%, tablet geometry,
navigation resource release and recording consent transitions: **NOT RUN** for
this slice. No new APK is assembled/installed. The handed-back OPPO must not be
touched. emulator-5556 retains its current code3 package and data; its existing
signer differs from the local debug signer. A future installation needs code>3
and the existing compatible signing process. No new key or key transfer.

No real-public-source benchmark or true-phone power acceptance is claimed.
CPU/RAM/battery/network/100-sample results for this UI slice are **NOT MEASURED**.
Vault, remote/dynamic rules and payment remain disabled/unavailable. Minimum
Android stays **Android 12 / API31**, target/compile36.
