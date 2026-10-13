# Discovery bounded next-page demand

## Original requirement and observed gap

Product Requirements §5.2 requires stable already-displayed pages, consumption of
ResultPool first, partial pages without waiting for the slowest source, truthful
materialized page numbers, and explicit source exhaustion. §5.3 and Technical
TR-02/TR-15 require foreground-only bounded scheduling, cancellation, and terminal
states. This slice does not change a product decision.

Previously AndroidDiscoveryController.movePage set a boolean demand but retained
the current pageIndex when the next page had no inventory. refresh then cleared
that boolean because the old page existed. Results arriving later could not
fulfill that explicit Next action. The UI enabled Next whenever the old page had
results, including after every source had ended, and showed page 1 before any page
existed.

## Connected implementation

- One pending next-page intent captures the current profile/session epoch/view
  revision/controller generation, current page and lifecycle epoch. Repeated Next
  commands cannot queue several pages or continually reset the request budget.
- Current cards remain visible while waiting. Existing local inventory is used
  first; a delayed partial page fulfills the explicit intent once. This never
  inserts or reorders cards in the existing materialized page.
- A user can stay on the current page. This cancels only the navigation demand;
  it does not claim to stop the normal foreground low-watermark scheduler.
- Back, new search/view/source owner, explicit local history/progress controls,
  and hide/background retire the pending intent. The lifecycle epoch is advanced
  synchronously before queued executor work. It is checked again after potentially
  slow status reads and after materialization before changing the displayed page.
- Read-only refreshes never materialize a page. A finished bounded request round
  retires the intent rather than automatically beginning endless request rounds.
- The pager shows actual loaded pages and no fake page 1. Confirmed all-provider
  exhaustion with zero inventory disables further Next/retry; existing loaded
  pages remain navigable. A bounded round ending is not mistaken for all-provider
  exhaustion. Unknown totals are never represented as a final page count.
- Five paired EN/ZH resources use the existing SkinKit-driven UI. The existing
  resource-parity test count changes from276 to280 (five added pairs, one obsolete page-label pair removed) without relaxing parity checks.

## Review and verification

Independent read-only review found a hide/resume race. Synchronous lifecycle epoch
invalidation, entry/re-entry checks and regression tests close that finding. Final
review found no remaining definite P1/P2 in the seven implementation/test/resource
files. The resource-count-only correction is separately recorded.

Final build/test evidence is in artifacts/ui/discovery-page-demand-20261013.
The verification JSON is authoritative about actual execution versus UP-TO-DATE.
Initial test-only failures (DiscoveryTimer anonymous object and new resource-count
expectation) are retained, not represented as passing runs.

No APK, install, emulator/phone, public-site, network thumbnail, real source, real
media, permission, purchase, history-retention decision or new dependency work.
Actual Compose rendering, Android lifecycle/SQLite, accessibility, focus and
font200/device transitions remain NOT_RUN. This is source/JVM integration coverage,
not device acceptance. Installed APKs are unchanged.

## Next independent candidate and remaining gates

Product §5.1 requires usable basic browsing including JavaScript and web login.
SystemBrowserHost currently immediately cancels onJsAlert/onJsConfirm/onJsPrompt.
A bounded native dialog owner tied to the exact current tab/navigation can improve
that real flow with explicit origin, bounded strings, one-shot result resolution,
and cancellation on navigation/tab change/background. It can be developed against
owned fixtures without visiting public sites, granting site permissions or adding
an interpreter/dependency. Actual website/Android acceptance would remain separate.

Other real gates remain: ordinary history retention policy choice, real thumbnail
source/rights admission, postponed video import, disabled Vault/rules, undecided
billing, unsynchronized external PC visual reference, and deferred authorized
Android/native media/render testing. They do not block the independent source work
above and none is silently resolved by this slice.

Final run5: app1573 actual JVM passes, including20 new tests; discovery307 cached
UP-TO-DATE. Both Kotlin compilers and both lint tasks actually executed PASS;
60 warnings/8 hints/0 errors/fatals per variant. Runtime admission90 unchanged.
77 tasks:32 executed/45 up-to-date. Reviewed eight final hashes match the frozen
source; no remaining definite P1/P2. No actual device acceptance is implied.
