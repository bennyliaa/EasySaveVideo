# Capability and milestone status at the tested base

The table uses Update1 ordering. A source implementation or host pass never completes
an entire milestone. `DEVICE_NOT_RUN` applies to every Android feature below: ESV
has never been assembled as APK/AAB or installed under this work.

| Milestone / capability | Implemented code and bounded host evidence | Missing / blocked / decision boundary |
|---|---|---|
| M0 / dependency and architecture admission | Reconstructed exact-version evidence; platform-neutral Core, original SystemWebView, internally reused SkinKit; exact74 enforced | Original lost M0 not recovered; candidate audits do not authorize unreviewed integrations or release. `docs/m0/ADR_005_LICENSE_ADMISSION_GATE_RECONSTRUCTED.md`; `docs/m1/RECONSTRUCTION.md` |
| M1 / foundation, browser and settings | `SOURCE_IMPLEMENTED`; browser tabs/session, back/forward navigation and basic clearing; `HOST_PASS` controller/codec/DOM and dual compile/lint | Bookmarks and full incognito/site-permission management are not established by this slice; native WebView/login/cookie/phone/tablet/lifecycle acceptance incomplete; debug fake entitlements excluded from Release. `docs/m1/` |
| M2 / Discovery and real Commons metadata | `SOURCE_IMPLEMENTED`; bounded framework, explicit contact-gated Commons, no-provider fallback, source rights/provenance; `HOST_PASS` | No universal search/extractor; safe search unsupported by Commons and requires explicit user confirmation to disable. Native network/UI/SQLite acceptance unrun. `docs/m2-commons/` |
| M3 / Direct and restricted Stream | `SOURCE_IMPLEMENTED`; direct HTTPS MP4; narrow static AVC/AAC HLS with local-only Media3 remux and independent publication gates; `HOST_PASS` including 81 EOF and 15 mux cases | No universal HLS/DRM support; actual media decode, AV sync, native transfer/background recovery unrun. `docs/m3a/`, `docs/m3b-eof/` |
| M4 / whole-screen recording | `SOURCE_IMPLEMENTED`; fresh system consent, internal playback-audio option, foreground service, pause/resume and verification; `HOST_PASS` | Android permission/projection/codec/audio/device behavior unrun. Source support is not measured capture acceptance. `docs/m4/` |
| M5 / ordinary library and exports | `SOURCE_IMPLEMENTED`; catalog, folders/search, playback, delete, MediaStore/SAF export, temporary share, thumbnail pipeline; `HOST_PASS` SQL/leases/workflows | Native provider/share/thumbnail/migration behavior unrun; configurable save destinations and bulk folder deletion incomplete. Ordinary media is plaintext. `docs/m5a/`, `docs/m5a3/` |
| M5 / Vault | `BLOCKED_ISOLATED`; Tink dependency admission only; free entitlement contract exists | Encryption/authentication/Keystore/recovery/import/play/export absent from active app. Vault is disabled. No candidate reopened. `docs/m5-vault-dependencies/`; `AGENTS.md` |
| M6 / edit, region, segments, authorized-frame preview | `SOURCE_IMPLEMENTED`; guarded trim/merge/crop/compress, crop-before-encode, retained segments and memory-only actual-frame selection; `HOST_PASS` | GPU/codec/visual/OEM/audio/real source identity/device acceptance unrun. Preview does not authenticate arbitrary app identity. `docs/m6a/`, `docs/m6b/`, `docs/m6c/` |
| M7 / fixed Commons maintenance | `SOURCE_IMPLEMENTED`; A/B/C signed parameter/policy, monotonic state, bounded runtime and health gates; `HOST_PASS` provider 342 on current base | Production trust roots empty; update endpoint absent; no root rotation/live service. Full dynamic extraction-rule maintenance not delivered. `docs/m7a/`, `docs/m7b/`, `docs/m7c/` |
| M7-D / dynamic candidate | `BLOCKED_ISOLATED` | Not integrated, reviewed, retried, probed or replaced in this slice. No runtime claim from isolated branch evidence |
| M8 / quality, bounded history, origins and filters | `SOURCE_IMPLEMENTED`; A/B1/B2/B3 ordinary policies, persistence and immediate Save/Record priority; `HOST_PASS` discovery 12 / origin 34 / media 47 / filter 29 | Native video `SAVED` and `PLAYED` remain `UNKNOWN` without trustworthy video identity/signal. Opening a page is not playing or saving a video. Full M8 incomplete. `docs/m8a/` through `docs/m8b3/` |
| M8 / power policy | Pure/injected policy `SOURCE_IMPLEMENTED`; `HOST_PASS` 24 per debug/release variant | Release OS adapter `NOT_CONNECTED`; debug transient simulations only; no actual battery/thermal/network policy observation or energy measurement. Recording screen-interactive checks do not close this gate |
| M9 / reliability and compatibility | M9-A fixed Commons ordinary fault isolation/recovery `HOST_PASS`; 49 independent cases, 6 isolation cases, SQLite 4 fresh processes | Host close/reopen is not Android process kill, power loss or native recovery. Android 12+ phone/tablet/OEM compatibility and device gates unrun. Full M9 incomplete. `docs/m9a/` |
| M10 / purchase preparation | M10-A immutable snapshots/mappings and unconfigured channel-neutral app boundary `SOURCE_IMPLEMENTED`; 24 new JVM cases, separate independent 4 and actual release-bytecode 8 host checks | `BUSINESS_DECISION_PENDING`: ESV market, SKU/products/pricing/free allowance/grace/refund and verification/backend/account design; no real SDK/account/payment/network/persistence/grants. Real M10 not implemented/accepted. `docs/m10a/` |
| M11 / Android release | M11-A code-fact and blocker documentation only | Current market rules/license/privacy/Provider update review, release configuration/signing/package/notices and device acceptance all pending. No legal policy drafted and no release pass |
| M12 / iOS | Shared specification only | Native implementation/authorization not in scope |

## Core metadata compatibility note (preserved)

M10-A changed `EntitlementSnapshot` from Kotlin `data class` to a final ordinary
class to snapshot aliases safely. Constructor defaults, equality/hashCode, copy,
components/destructuring and existing source-call behavior are preserved by explicit
methods. Reflection `isData` changes to false; binary-client/serialization compatibility
is **not** newly promised. ProductMapping also snapshots nested sets. This note is
part of the current release inventory, not a request to undo the fix.

## Base evidence versus this documentation checkpoint

At the tested base: 1,822 Gradle JVM tests, zero failures/errors/skips, 87 rerun Gradle
tasks, dual compile/lint, exact74 and two separate full cold aggregate passes. Each
lint variant: 0 errors / 14 warnings / 6 hints. See the exact copied original receipt.
Current documentation-only checks verify unchanged old bytes/modes (except root
NOTICE), fact-index consistency and recoverability. They do not add host cases or
retest Android behavior, and must never be summed into or relabeled as that base run.
