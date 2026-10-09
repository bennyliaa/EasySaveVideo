# M9 remaining gaps and M10 decisions

This handoff does not start another milestone. M9-A covers ordinary reliability of
fixed Commons and zero-source host isolation; it is not complete M9 acceptance.

## Original M9 acceptance still requiring authorization

- Authorized real Android API31+ phone/tablet compatibility and lifecycle checks:
  WebView navigation, direct/HLS Save, MediaStore publication, Record permission,
  background/foreground, rotation/process recreation, and real storage pressure.
  Device permission is required separately; SkinKit-only emulator permission does
  not authorize ESV assembly, installation or tests.
  This includes existing fixed-source Android integration with bounded timeout,
  cancellation and Browser + Save fallback. Offline socket actors and host SQLite
  tests do not prove device TLS, Looper, Compose, SQLiteOpenHelper, process-kill or
  power-loss behavior.
- Original Chromium/JS/XHR/worker/adaptive-rule fault cases are not applicable to
  the current fixed schema1 implementation. They do not create a new obligation to
  adopt those components or authorize blocked dynamic-rule work.
- Separate M8 follow-through still includes trusted native video identity and OS
  battery/thermal/network observation. SAVED/PLAYED remain UNKNOWN and release
  power policy remains NOT_CONNECTED. Real energy measurement requires separate
  authorization and never follows from debug simulation.

## Decisions before M10 real IAP

1. Confirm the desired commercial product model, products/entitlements and intended
   markets. Existing debug fake entitlement is not payment or production access.
2. Select the real store integration and approve exact official artifacts after
   dependency/license review. No Billing SDK is admitted by this slice.
3. Provide an authorized Play app/testing account route and define test-only vs
   production actions, financial/legal ownership and approval boundaries.
4. Decide validation/backend needs and approve any service, account, credentials,
   privacy disclosure, budget or terms separately. No new service is assumed.
5. Define purchase, restore, pending/cancel/refund/acknowledgement, offline and
   entitlement revocation acceptance. Payment logic stays outside Provider code.

M7-D blocked candidate/review/probes or substitutes, Vault, new providers/libraries,
production services/accounts/payment, ESV APK/AAB/device tests and SkinKit/SDK edits
remain outside this handoff. Independent progress never waives those gates.

Ordinary fixed-source host reliability is closed by this slice. No further
repetitive host-test phase is proposed. When the remaining device or commercial
decisions are needed, report the specific authorization/decision blocker rather
than expanding scope, adding services, or beginning real payments.
