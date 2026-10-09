# M10-A — Channel-neutral purchase preparation

Baseline: 7731c32da8ee821087605c5b9f9097a659b71ab5 (M9-A).

This finite slice repairs mutable entitlement value aliases and prepares an app-only
purchase/restore port. It is **channel-neutral preparation, not real M10 purchase
acceptance**. Milestones update1 places real IAP at M10; the earlier TR-13/M8 wording
is a requirements reference, not a completed real-payment claim.

1. Freeze snapshot constructor/copy/component/output values and product mappings.
   Preserve current monotonic revision behavior, existing validation, equality,
   copy and destructuring call sites. Add bounded ordinary regression fixtures.
2. Add typed app store states and a default unconfigured adapter with no products,
   purchase capability, entitlement grants, SDK, network or persistence. Keep SKU
   references at the app boundary and Core consuming only immutable snapshots.
3. Keep debug entitlement simulation source-set isolated. Release explicitly uses
   the fail-closed adapter. Existing UI already states purchase is not connected;
   no new empty purchase page or simulated purchase success is warranted.
4. Checkpoint compilable source, review ordinary correctness independently, freeze
   relevant inputs, run strict offline full inherited/new verification, then
   commit/tag and repeat the same aggregate from independent Git and source restores.

No market, SKU, price, product type, subscription, bundle, free Provider allowance,
offline grace/refund/acknowledgment/backend policy is chosen. No Billing or WeChat
SDK, accounts, keys, permissions or network service is introduced. Production
purchase/restore verification, persistence and refunds remain unimplemented.

API31 phone/tablet, Compose and the SkinKit contract/default jars, exact74 approved
runtime artifacts and free capability gate requirements remain unchanged. No ESV
APK/AAB/device work; no SkinKit edits; no blocked M7-D or Vault integration, review,
retry, probe or substitute. Existing user media is untouched by entitlement state.

Historical evidence stays byte-for-byte unchanged. M10-A copies inherited host
harnesses only to bind their frozen-input checks to this slice. Three media-source
gates are copied with the exact entitlement-file exclusions now reviewed by the
M10-A allowlist and new tests; all other media guards remain unchanged.
