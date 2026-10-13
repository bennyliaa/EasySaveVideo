# Browser settings, origin and failure copy: bounded bilingual wiring

Date: 2026-10-13. Source baseline: Wi-Fi-only saving frozen handoff at
`esv-wifi-only-frozen-20261013` (parent publication pending when this work began).

## Scope

`BrowserScreen.kt` now consumes 13 exact paired Android string resources from
`values/browser_copy.xml` and `values-zh-rCN/browser_copy.xml`:

- File selection dialog origin label: `%1$s`, preserving the original origin value.
- Third-party Cookie and tab-address-restoration labels.
- Active webpage limit: `%1$d`; neutral wording covers the existing 1–4 range
  without adding an English singular/plural claim.
- Toggle label/state template: `%1$s`, `%2$s`, plus separate On and Off values.
- Six finite error messages: TLS rejected, renderer stopped, timeout (two existing
  codes), network unavailable, HTTP failure, and fallback for all other codes.

The mapping is now `browserErrorResource(ErrorCode)`, consumed at the existing
error UI call site with `stringResource`. This is not a runtime translation table.
JavaScript remains a technical name. Website titles, addresses, notices passed by
other owners and progress percentages remain unchanged. No enum, protocol, log,
website text, third-party license or source example has been translated. No policy,
callback, layout, limit, dependency, permission, history TTL, Vault or device changes.

## Contracts and evidence

`BrowserCopyContractTest` covers the exact 13-key set in both locales, independently
specified positional placeholder types, raw-origin preservation, all 1–4 limit values,
both toggle states, exhaustive current ErrorCode mapping including fallback, actual
source call sites, unchanged narrow/wide settings paths and scrolling, file prompt
callbacks, raw title/address pass-through and TLS/renderer failure semantics.
The new resource filename is an explicit Gradle unit-test input. Existing resource
counts and tests are untouched. Source-contract checks are not rendering tests.

App JVM: 1747 tests passed, 0 failures/errors/skips (1742 baseline + 5 new).
Debug/Release Kotlin compile and Debug/Release lint passed; each lint report retains
60 warnings and 8 hints, no errors and no issue in this slice’s changed UI/resources.
Independent read-only review passed without blocking findings. Evidence lives in
`artifacts/ui/browser-copy-20261013`; actual/up-to-date task distinctions are recorded there. No APK assembly, install,
network request, new dependency, emulator or real-device test is performed.

## Remaining acceptance

This removes the remaining Chinese literals in this one screen, not across the app.
Other owned pages and runtime notice producers still have unmigrated copy. Full
bilingual UI, device font scaling/TalkBack/layout and live WebView acceptance are
not claimed. Next work should select another bounded original-flow evidence gap,
not add languages or broaden business scope.
