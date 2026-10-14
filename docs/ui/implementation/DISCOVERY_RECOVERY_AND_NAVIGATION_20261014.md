# Discovery recovery actions and original-page navigation

Date: 2026-10-14 UTC. Base: development/cloud-followup-20261013 at
8e9dc9faaa4d2f07513c2379af18b4e138f5fde3. The preceding library increment
is preserved. This is source/cloud validation, not complete device acceptance.

## Requirement and observed gaps

Original UI03 UI-R06/UI-R09/UI-R13 and UI05 section 6 require query ownership,
opening a result's original page, and meaningful recovery exits. The existing
empty/error body offered only the browser fallback, even when existing source
management or query/filter controls were a more direct next step.

The card-open callback previously switched to Browser before acceptance. A null
captureOriginTarget did not prevent SystemBrowserHost from navigating the URL.
Additionally, submitting a new query synchronously marks filterSearchPending,
but retained old results/page/target can remain displayed until queued work is
published. Those old fields alone are insufficient authority to open a card.

## Implemented behavior

- NO_SOURCES and SOURCE_CONFIGURATION offer the existing source-management UI.
  INVALID_QUERY, UNSUPPORTED_FILTER and EXHAUSTED offer query/filter review.
  A finished round is not described as a filter error. Other states keep their
  existing honest waiting/error messages and browser fallback.
- Recovery actions only open existing UI. They do not submit a query, retry,
  enable a source, change filters, or issue network work. The displayed snapshot,
  existing page lifecycle and current interaction gate are checked on click.
- A rendered card captures its actual target, page, complete result and existing
  controller page lifecycle. No new navigation stack, coordinator or lease system.
- The production dispatcher reads current state, lifecycle, route/foreground and
  filterControlsBusy at execution, before any native navigation. This includes the
  pending-new-query window where all old result fields still match.
- Native acceptance happens before switching the UI to Browser. Current ownership
  is rechecked after potentially reentrant native callbacks; a newer route, owner,
  lifecycle or pending query is not overwritten. Only then is the existing OPENED
  event recorded. No PLAYED/SAVED inference or Save protocol change.
- The host checks target/page membership and its existing command/foreground gate
  before navigate. Incognito is rejected before navigation, with no implicit mode
  switch. A rejected open keeps the current page and shows a localized neutral
  failure message. Ordinary successful cards still open their exact original URL.
- All controls reuse the existing SkinKit-backed UI and bilingual resources.

## Verification and independent review

Final strict-offline aggregate completed successfully:

    :core-model:test :core-domain:test :ui-theme:test :discovery:test
    :save:test :recording:test :library:test :hls:test :commons-provider:test
    :searxng-provider:test :verification-client:test :verification-local:test
    :app:testDebugUnitTest :app:compileReleaseKotlin :app:lintDebug
    :app:lintRelease :app:runtimeInventory

- 3,611 registered tests: 3,609 passed, 2 pre-existing private-corpus skips,
  0 failures/errors. App: 2,323 passed. Affected app tests executed; unchanged
  module tasks may be UP-TO-DATE, not freshly rerun.
- 35 new tests: production card dispatcher 20, recovery mapping/dispatch/resources
  10, and actual-source wiring contracts 5. The earlier broader Discovery/Browser
  run passed 690 tests before the final three additional dispatcher regressions.
- Coverage includes exact valid result opening, native refusal without routing,
  new query/session/controller/view/profile, changed same-ID URL/metadata,
  different page, unready/unavailable state, current-route/foreground gating,
  same-route lifecycle return, repeated clicks, the unchanged-old-snapshot pending
  window, and native callback changes to current route/owner/lifecycle/pending.
- Independent read-only review confirmed the pending-window and pre-acceptance
  routing issues were closed. Final three-class XML review: 20 + 10 + 5 passed;
  no remaining confirmed defect in this bounded scope.
- Both Debug and Release Kotlin compilers executed successfully. Both lint
  variants have zero Error/Fatal: Debug 58 warnings/8 hints; Release 59/8.
  Existing warnings are not claimed fixed.
- Exact 90-artifact runtime admission remains unchanged. Existing boundary,
  release fake/demo exclusion, reviewed-byte and seven-getter ABI checks passed.
  No dependency or SkinKit change.

## Evidence limits and scope

Dispatcher tests execute the actual production dispatcher with controlled state
suppliers and callback effects. Source contracts confirm real Activity/controller/
host wiring. They do not instantiate AndroidDiscoveryController, WebView, or a
complete Compose screen. Actual native navigation, device touch/focus/TalkBack,
rendering, source access and native-media acceptance remain NOT_RUN. No APK/AAB
was built or installed. No real site or user media was used in these tests.

The backup contains twelve source/test/resource/build files and this sanitized
ESV report only. Existing disabled Vault/rules/payment/unconfigured-Provider gates,
media ownership, minimum Android version, repository visibility and main remain
unchanged. No credentials, private content, dependency bundle or unrelated project
file is included. The development-branch backup is not a release or merge claim.
