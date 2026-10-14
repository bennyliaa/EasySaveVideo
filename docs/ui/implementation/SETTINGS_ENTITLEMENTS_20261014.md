# Read-only feature and entitlement explanation

Date: 2026-10-14 UTC. Base development commit:
`af5dd42fa9e4a76cb32035275a49b3619985ccc1`.
Original UI framework sections4.6/4.7, PPT24, interface contract section11 and
Product PR-19 require a feature/entitlement explanation independent of payment
integration. The previous central settings showed only Store unavailable in
About and had no explanation of the three specified planned modules.

## Implemented scope

A new stable Settings category explains Browser, Direct and ordinary media-library
free entitlement policy, plus the planned Provider Plus, Save Pro and Video Tools
Pro modules. Vault is explicitly described as planned free but disabled in this
build. The original product labels are explanatory planning names, not invented
SKUs, prices, purchase terms or final store products.

Eight paid-feature rows display one of: entitlement requirements satisfied,
requirements not satisfied, or unknown. They do not display bought/owned or claim
that a device/source/format can actually perform the operation. Advanced recording
requires both the tool and base RECORD_SAVE entitlement; editing existing media
only requires its appropriate tool grant. Unknown Store state never paywalls the
free entitlement group. Platform/protection/system-consent/implementation/resource
checks remain with the actual operation.

MainActivity passes the existing EntitlementManager.snapshot read and the actual
BuildConfig.DEBUG identity. Debug simulated grants have a prominent explanation
that they are not purchases or production grants. Release has no new unlock
control or Fake source. The real store remains unconfigured; no purchase,
restoration, account, receipt, final product identifier or price is introduced.

## Read-only model and refresh

The projection delegates entitlement requirements to the existing FeatureGate.
Its platform argument is deliberately limited to the row feature solely to
project entitlement requirements; it is never an operational capability report
or an authorization passed to a save/record/edit command. The screen explains
this distinction. No gate or grant is modified.

Unvalidated/unavailable sources, invalid timestamps and expired trust windows
project UNKNOWN even if raw grants say ACTIVE. Revoked/expired grants cannot
satisfy requirements; missing or UNKNOWN individual grants are handled separately.
Valid offline evidence uses the same bounded trust-window requirements as Core.
The output retains no grant origin/account/receipt data, only feature/status,
source-state and next relevant time boundary.

Opening the current foreground category reads the existing local snapshot. The
explicit Read local status again action does the same, with no Store refresh or
network. A future validation/expiry boundary schedules only a local read; each
wait is bounded to one minute to notice wall-clock changes. With no future
boundary, no recurring polling is scheduled. The composition-scoped effect stops
on route/generation/foreground loss and is disposed with the page; it does not
persist or replay a purchase. Inactive observations are displayed as unknown.
Snapshot exceptions and linkage failures produce unavailable/unknown explanation,
not raw messages or a fake grant.

## Test and review evidence

20 production-model tests cover unknown/unavailable evidence, empty and complete
grants, missing/future validation, snapshot/grant expiry at exact boundaries,
offline-cache TTL, revoked/expired/unknown grants, partial Save grants, advanced
recording dependency, Debug labeling without changed evaluation, no grant-origin
retention, single read/failure handling, bounded clock arithmetic and every paid
grant combination checked against the actual FeatureGate.

Five app contract tests cover category/back behavior, all label mappings, the 27
paired EN/ZH resources, explicit planned/free/disabled/Debug text, existing-manager
and build identity wiring, foreground/local expiry-refresh behavior, and absence
of purchase/restore/grant-mutation calls. These are not Android rendering or real
store tests. Focused 36-test run passed on its first execution.

Independent read-only review found no blocking issue, inspected the actual model/
FeatureGate wiring, timer/foreground behavior, final bilingual resources, and both
new XML test reports. It did not run Gradle or device tests.

Final strict-offline aggregate exited 0: all 13 module test tasks freshly executed;
3,749 registered tests, 3,747 PASS, two existing private-corpus SKIP, zero failures
or errors. App: 2,459 passes. Final source Debug Kotlin compiled in the focused
run and was UP-TO-DATE in the aggregate; Release Kotlin executed successfully in
the aggregate. Both lint variants passed with zero Error/Fatal: Debug 60 warnings/
8 hints and Release 61 warnings/8 hints, unchanged from the preceding baseline.
The exact 90-artifact runtime gate, core/import boundaries, Debug-only fake/demo
exclusion, original license bytes and seven-getter ABI checks passed. Aggregate:
102 tasks, 33 executed and 69 up-to-date. Sanitized source hashes, task statuses,
lint and test summaries are in adjacent `settings-entitlements-20261014/`.
No intermediate test failure occurred in this slice.

## Remaining acceptance and continuation

No billing SDK, dependency, product mapping, price, API level, SkinKit asset,
permission, grant or production store setting changed. No APK/device/real media,
account/purchase/restore or external network action was performed. Native layout,
TalkBack/font200 and lifecycle rendering remain NOT_RUN; fake-source scenarios
are not evidence of real purchase verification. Prior candidate APKs remain
unchanged. Real billing remains a separate explicit product/security gate.

Next original requirement: central saving destination / browser settings access,
reusing existing owners and controls. See the original-requirement continuation
queue. A completed explanatory page does not close payment or ESV acceptance.
