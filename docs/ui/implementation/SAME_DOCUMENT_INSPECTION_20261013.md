# Explicit inspection after same-document navigation

## Original requirement and correction

PR-09 and Technical §4/TR-05 require user-triggered full media detection, explicit
candidate choice, and isolated browser/session ownership. After a READY document used
History API or hash navigation, SystemBrowserHost accepted the live same-origin URL for
bookmarks while AndroidSaveRuntime still captured the old tab.address. SaveInspectionPage
correctly rejected pageUrl != viewUrl, leaving manual inspection unavailable.

This slice changes the source of inspection identity rather than relaxing URL equality.
Each Entry has an ephemeral BrowserInspectionDocument. An accepted page-finished event
establishes its document origin/address; page-start clears it. A native history observation
accepts the exact current WebView URL only for a live, unsuppressed, started, READY request
and the same accepted document origin. Invalid/reload observations clear the address.
Every observed history event advances an Entry-local revision, including same-address
replaceState and A→B→A. A background tab does not advance the selected Entry's revision.

The host captures the selected context, epoch, READY state, accepted document address,
current native URL, exact View identity, interaction revision and document revision.
AndroidSaveRuntime obtains that snapshot on the explicit click and through its existing
stillCurrent closure. The detector checks exact URL/current identity before JS execution
and on callback, verifies the script's page value, and retains generation/cancellation
checks. Candidate selection, destination selection and HLS variant acceptance continue
to revalidate the closure. None of these observations starts full inspection automatically.

## Boundaries retained

- Full scanning remains explicit; the independent lightweight presence budget is unchanged.
- URL equality, same-origin document acceptance, context/epoch/View ownership, foreground,
  tab-switch revision, cancellation and existing 60-second candidate/session TTLs remain.
- No permissions, downloads, credential access, entitlement or task authority are added.
- The existing leaseFor credential rule still requires tab.address == view.url. Thus an
  authenticated same-document URL change may detect candidates but remain without Cookie
  forwarding. This patch deliberately does not widen credential authority.
- NavigationAdmission still governs new documents. URL rewrites during initial LOADING
  before an accepted finish are outside this fix and may remain unavailable.
- Correlation with discovery results remains conservatively invalidated on history changes.
- No source/dependency admission, Provider, parser, Vault, persistence or logging changes.

## Validation and limits

Fourteen pure JVM state/ownership tests cover accepted READY hash/push/replace paths,
exact equality, absent finish, invalid/native-stale history, reload/loading/retired request,
scheme/host/port/credential rejection, new-document reset, background-entry isolation,
context/session/epoch/View/foreground fences, same-address and ABA candidate invalidation.
Three source contracts verify host/runtime/detector/candidate wiring and no automatic full
scan. Six existing SaveInspectionPage tests and six failure-guidance contracts also pass.
Final full App tests, related JVM suites, dual compile/lint and boundary results are in
artifacts/ui/same-document-inspection-20261013/validation.json.

These are synthetic JVM and source-wiring tests, not Android/WebView runtime acceptance.
Native same-document callback delivery/order, especially same-URL replaceState, has not
been measured on an emulator/device. The revision guarantee applies to observed callbacks;
no claim is made that host code can identify an unreported in-renderer URL ABA mutation.
No network, public sites, device, emulator, APK assembly/install or permission flow ran.

## Next independently scoped original item

TR-05 browser/session consistency after READY same-document navigation: investigate and
separately review whether the existing credential lease can safely consume a host-accepted
inspection identity. Preserve strict same-origin forwarding, explicit candidate selection,
short-lived lease revocation and all existing task boundaries. This remains unimplemented.
Alternatively, initial-LOADING history rewrites need their own navigation-admission design;
do not solve them by broadening accepted start/finish URLs in this patch.
