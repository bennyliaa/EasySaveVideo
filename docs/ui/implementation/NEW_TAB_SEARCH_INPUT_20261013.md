# New-tab video search input (2026-10-13)

## Scope and baseline

Original Product Requirements §5.1 first bullet: a distinct video-search input on
new tabs, with correctable address/keyword interpretation. Based on private-repo
commit `08ce295972f4b6740ea479d4e6a8e73d9402e823` (JS dialogs); this cloud source is
an extracted snapshot without `.git`. This report does not assert a new commit,
push, APK or installation. Frozen JS dialog code and effects are preserved.

## Implemented behavior

- New blank tabs retain the permanent address bar and also show “Search videos” /
  “搜索视频”. Auto, Web address and Video keywords modes are explicitly selectable;
  the selected mode has an accent outline and selected accessibility semantics.
- Auto routes admitted HTTP(S) addresses and ordinary bare domains to the existing
  browser. Ordinary keywords (including punctuated phrases such as “Dr. Who”)
  become a Discovery draft. Unsafe/malformed address-like input is rejected rather
  than silently sent as a search. Explicit Video keywords mode can correct a
  domain-like keyword; Address mode can correct a single-label host.
- Address actions say “Open address” / “打开网址”. Keyword actions say “Go to video
  search” / “前往视频搜索”. Help explicitly says the user must press Search in
  Discovery. This slice does not promise one-click Provider execution.
- The handoff preserves existing Discovery source/filter/readiness/safety gates.
  Existing applied results stay labelled under existing draft-vs-applied UX.
  Zero sources remains an honest no-sources state. No Provider is added or invoked
  by the draft-handoff method itself. Existing Discovery foreground resumption
  behavior is unchanged; this is not a claim that navigating there performs no IO.
- Query handoff is ephemeral, bounded and not saved/logged. Waiting handoffs are
  discarded on leaving, backgrounding and close; a newer accepted edit, reset or
  explicit Search wins. Busy/unready state does not bypass query validation.
- Tab context, navigation epoch, live address, current route/lifecycle generation,
  disposal and a one-use claim fence stale or repeated actions. Pausing/leaving
  synchronously advances the generation, so resume/return cannot revive old input
  callbacks. Ordinary direct-browser safety validation is reused unchanged.
- No dependency, runtime admission, Provider, remote policy, permission grant,
  history-retention default, Vault, interpreter, versionCode, signing or APK change.

## Verification

- `app`: ACTUAL, 1632 cases, 0 failures, 0 errors, 0 skipped.
- `discovery`: ACTUAL, 307 cases, 0 failures, 0 errors, 0 skipped.
- `core-domain`: ACTUAL, 44 cases, 0 failures, 0 errors, 0 skipped.
- `core-model`: ACTUAL, 3 cases, 0 failures, 0 errors, 0 skipped.
- `commons-provider`: ACTUAL, 247 cases, 0 failures, 0 errors, 0 skipped.
- `searxng-provider`: ACTUAL, 25 cases, 0 failures, 0 errors, 0 skipped.
- `verification-client`: ACTUAL, 44 cases, 0 failures, 0 errors, 0 skipped.
- `verification-local`: ACTUAL, 34 cases, 0 failures, 0 errors, 2 skipped.
- Debug and Release Kotlin compilation: ACTUAL PASS.
- Debug and Release lint: ACTUAL PASS; each 0 errors/fatals, 60 warnings and 8 informational findings (unchanged baseline).
- Runtime admission: ACTUAL PASS, exact 90 reviewed artifacts for each variant.
- 91 actionable tasks: 91 executed. No requested verification task was UP-TO-DATE in the final run.
- Two verification-local frozen-corpus tests require ESV_OWNED_CORPUS; it is unset. No corpus evidence was fabricated or reacquired.
- Final source hashes match the independently reviewed frozen code.

The new 17 pure-policy tests cover address/query classification and correction,
unsafe schemes/userinfo/whitespace/control characters, bounds/surrogates,
redacted representation, tab/epoch/address/background fences, repeated claims
and disposal. Eight source/resource contracts cover both locales, input wiring,
foreground/route/generation checks, pending-draft lifecycle and newer-edit/reset/
Search precedence. Source-string contracts are not Android runtime tests.

Independent static review found and closed stale-address/repeated/disposed action
and deferred-draft overwrite races. The reviewer verified all 10 frozen code
files against their declared hashes and live source. Frozen snapshots and the
final evidence manifest define the exact publication whitelist; do not upload a
whole reconstructed source tree or unrelated build files.

## Explicit remaining acceptance

Android/Compose rendering, real WebView, IME scheduling, rotation/process restore,
TalkBack and 200-percent font: NOT_RUN. APK/AAB assembly, emulator/device install,
public-site/real-provider search and upload: NOT_RUN. No on-device evidence is
claimed. Existing browser input policy still requires an explicit HTTP(S) scheme
for bare host:port inputs; this slice does not widen browser admission.

## Next independent original requirement

Product §5.1 third bullet and Technical TR-05 separate lightweight video-presence
hints from explicit full media inspection. Current BrowserScreen exposes the
inspection action for READY pages; PageSaveDetector performs the complete scan
only on the native click. A bounded current-foreground-tab boolean/UNKNOWN hint
could be implemented independently, leaving full inspection user-triggered and
retaining tab/session/epoch/view/lifecycle fences. No iframe enumeration, media
URL/credential collection, new Provider/dependency, or background polling should
be inferred. History TTL, Vault, dynamic interpreter and real-device acceptance
remain separate scopes.
