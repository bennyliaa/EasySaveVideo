# M10a app-side store boundary

This is purchase/restore contract preparation only. It does not implement real IAP or complete
the Real IAP milestone. The governing requirements are Technical Requirements TR-13
(`docs/reference/04_Technical_Requirements.md`, lines 241–257), acceptance T-E01–T-E06
(`docs/reference/05_Test_and_Acceptance_Strategy.md`, lines 176–181), and M10 in
`docs/reference/03_Development_Milestones_update1.md`. The updated milestone order places real
store integration in M10; older M8 wording is not evidence of completion here.

## Boundary and ownership

- `StorePurchaseBoundary` is in the app module. Purchase input product IDs and the product set
  remain app-only. Core `StoreAdapter` retains its existing snapshot-only refresh/restore API;
  no SDK, product, receipt or token type is added to Core.
- `purchase` and `restorePurchases` return an operation-local `StoreOperationStatus`.
  `UNCONFIGURED`, `UNAVAILABLE`, `CANCELLED`, `PENDING`, `FAILED` and `TIMED_OUT` are distinct.
  These fixed enum values have no raw error, account, receipt, token or user-data payload.
- `VERIFICATION_REQUIRED` reserves a future unverified store-completion signal. It is not a
  purchase verification, success claim, entitlement or authorization. It carries no snapshot.
  Any future implementation must separately verify store results and map them before publishing
  a Core entitlement snapshot; UI must never grant from an operation status.
- The default adapter has no mapped products, purchase capability, SDK, network, account,
  persistence or grant-writing path. It cannot produce `VERIFICATION_REQUIRED` or `PENDING`.

## Fail-closed default and assembly

`UnconfiguredStoreAdapter` implements the app port, Core `StoreAdapter`, and `EntitlementSource`.
Its product set and grant list are immutable empty collections. Every snapshot, refresh and
Core restore returns the same unavailable revision-0 snapshot with no validation timestamps.
Repeated calls do not invent revisions or validated state. The snapshot API remains unavailable
even for a stopped operation because it cannot express an operation-local cancellation status.

The app purchase and restore operations consult `OperationContext.stopReason` using an injected
clock. Cancellation takes precedence over a deadline, a reached deadline returns `TIMED_OUT`,
and otherwise the result is `UNCONFIGURED`. No retry extends the deadline or changes the context.
The existing retry-exhausted stop value is mapped to `FAILED`; the current `stopReason` function
does not produce that value. No raw exception text or product identifiers are logged.

Release `BuildEntitlements.source` explicitly aliases `BuildEntitlements.store`, the default
unconfigured adapter. Debug exposes the same unconfigured store port while retaining its
separate existing debug-only fake entitlement source and controls. This does not add a purchase
screen or change the UI's statement that no real purchase is available.

Free entitlement gates remain independent of the store. Platform and build-policy gates are
unchanged: in particular a free entitlement does not make an unimplemented capability available.
No SKU, pricing, product type, subscription, bundle, cross-store or offline grace policy is chosen.

## Verification scope

`StorePurchaseBoundaryTest` contains ordinary deterministic JVM cases for the empty/default
state, unavailable snapshots, purchase/restore outcomes, cancellation, exact deadline boundary,
cancellation priority, repeated calls and stable revision, free/paid gate separation, and build
assembly. Debug runs through the standard Gradle unit-test task. The current Android plugin
does not expose a release unit-test task, so the exact same test source is separately compiled
against actual release Kotlin/Java outputs and the generated DEBUG=false BuildConfig with the
admitted standalone toolchain. Its eight-test host result is recorded separately from Gradle
JVM totals. This exercises the release source-alias assertion without changing build settings.

These tests are not real-store verification, restore, pending-purchase, callback-deduplication,
refund/revocation, Android-device, receipt-security or release-distribution acceptance. Real
store integration, SDK review, backend/account design and business policy remain separate work.
