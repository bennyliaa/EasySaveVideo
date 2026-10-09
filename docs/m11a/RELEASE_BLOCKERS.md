# ESV release blockers — one-page decision checklist

**2026-10-07: development source only. No release approval.** Tested code:
`bdf31387ec99c86c25847f5bbadd1eff8908f45d` (M10-A). Later M11-A is docs-only.

1. **ESV commercial/channel decision is pending.** Confirm the intended ESV market
   and commercial model, then choose products/SKUs, prices, free Provider allowance,
   entitlement bundles and purchase/restore/refund/offline-grace rules together.
   Real store SDK review, account access and receipt verification/backend design
   follow that choice. M10's current adapter is UNCONFIGURED, with no products or
   grants. SkinKit's channel choice cannot stand in for ESV's.
2. **ESV package/device work requires its own authorization.** No ESV APK/AAB or
   install has been run. The required next acceptance is a bounded Android 12+
   phone/tablet plan covering WebView, native save/record/editor, audio/codec/GPU,
   lifecycle/process death, permissions and external export/share. SkinKit emulator
   permission does not authorize ESV. Existing host passes cannot close this gate.
3. **Known functional gaps stay explicit.** Vault remains disabled; M7-D remains
   isolated/blocked; native Discovery SAVED/PLAYED identity is UNKNOWN; Release power
   adapter is NOT_CONNECTED with no energy measurements; production Provider roots/
   endpoint are absent. Either finish separately authorized requirements or obtain
   an explicit scoped-release decision; this inventory does neither. Full M8/M9/
   M10/M11 are not complete.
4. **Then-current publication review remains unperformed.** After market/scope is
   known, review current store rules, Provider remote-update policy, actual data
   disclosures, applicable rights/terms and SkinKit external-distribution evidence.
   This code inventory is not an approved privacy policy or legal clearance.
   Commons/WebView/Save make real network requests; never advertise “all offline,”
   “no background operation,” “no server contacted,” or secure deletion of exports.
5. **Final release artifact must be verified.** Release identity/version/signing,
   merged permissions, cleartext WebView navigation, runtime notification behavior,
   packaged notices/resources/shrinking, installed notice reachability and the inherited
   editor hint that still denies M6-C frame selection need final review/correction.
   Current source has 74 mapped dependencies and bundled notice evidence; no new
   missing source text was found. That does not verify a nonexistent final package.
   The root NOTICE's stale 72 count is corrected as documentation only; bundled text
   and all runtime inputs are unchanged.

Only two user decisions are needed to open the next bounded work: (a) ESV market/
commercial direction (already asked, awaiting reply); (b) separate ESV package and
named device/emulator acceptance authorization. Technical release, feature-scope and
legal decisions above remain gates to that work, not additional unanswered prompts
or invitations to start endless independent host rounds.

M11-A stops here. No new feature, candidate review, repeated Gradle phase, production
service, payment, legal policy, device use or publication begins automatically.
