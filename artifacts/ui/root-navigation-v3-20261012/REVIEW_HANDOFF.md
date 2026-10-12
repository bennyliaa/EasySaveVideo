# Independent review / carrier v3 retest

Status: source/model/static PASS; v3 original and fixed runtime NOT_RUN. Author
performed zero device operations. Production ESV APK was not built or installed.
ANR is a separate UNKNOWN defect, not claimed fixed by this navigation change.

Fixed delivery: `delivery-dc69-20261010/nav-diagnostics-v3-local-20261012/`
`esv-nav-diagnostics-v3-debug.apk`, SHA256
`175f768bb72ed777d1afb3f0a90ce09adf05a3608bb9594eb39902233c063825`.
Package `com.easysavevideo.navdiagnostics`, Activity `.NavDiagnosticActivity`,
versionCode3, min31, target36. Signature SHA256
`5e841a04968f3f790f6996ca5e286d8c2734338542486909f9a136717dde808f`
matches the earlier carrier. v2 APK SHA remains
`292d86e3a14a2cb5601f26cdbcc622e7b4ba14bc0b48823350ef121afc99c135`.
The standalone carrier updates its own package; it is not an ESV replacement.

Source `RootNavigation.kt` is compiled unchanged into both production and carrier,
SHA256 `4cdf211f638bf17a16ddc58346a745f0db51dcd487e58c41c37c4945da4e8118`.
Official dexdump proves its definition and all four Future/ProfileVerifier classes
exist. The original v2 function is byte-identical. Only fixed navigation pops known
auxiliaries before saving a root and cleans an old restored auxiliary tail. It does
not clear all root state, disable all restoration, or reset a live production graph.
Only the fixture matrix resets this standalone app's owned graph between cases.

## Retest protocol for the independently authorized reviewer

Preserve existing data and earlier logs. Do not clear data/uninstall/grant permissions,
operate other devices, access ESV private files/media, record screen/audio, or change
security settings. Author does not install or start this package. A failed signature,
identity or launch gate stops that attempt; no bypass is authorized.

1. Verify fixed APK SHA/signature/package/API/version, then use the existing diagnostic
   retry procedure on emulator-5556 only. Record actual outcome, including any blocker.
2. `RUN_ORIGINAL_MATRIX` repeats the v2 six cases. Each `*_SETTLED` row is actual current
   route/callback/presence; v2's known failures are comparison evidence, not a fabricated
   v3 result. Manual `MODE_ORIGINAL` leaves controls on the original options.
3. `RUN_FIXED_MATRIX` runs18 cases. Indices0..5 correspond to v2 header Discover,
   bottom Discover, Settings→Tasks, Sources→Discover, Browser→Discover, Editor→Library.
   Indices6..17 start Tasks→each of Settings/Sources/Browser/Editor, then target
   Discover/Record/Library respectively. Every `FIXED_CASE_n_SETTLED` must show its
   actual target; `FIXED_CASE_n_MARKER_RESTORE` returns to its original root and must
   retain that root's actual `owned_markers[root] == 17`. No algorithm result alone
   establishes PASS. Then inspect same-root repeated navigation and `FIXED_BACK_SETTLED`.
4. In `MODE_FIXED`, exercise actual visible header/footer controls and `BACK_POP` as
   separate UI checks. Matrix calls are not proof that production chrome received a tap.
5. `PREPARE_FIXED_RECREATE_AUX` creates Library marker17 under Settings. Recreate via
   `RECREATE_ACTIVITY`; independently rotate if allowed by the existing procedure.
   Inspect restored callback/current Settings, Library marker17, remembered FIXED mode,
   then bottom Library must pop to that root and retain marker17. Also check root-only
   recreation/rotation and ordinary system Back. Actual rotation is NOT_RUN by author.

Read only this carrier's **`files/nav-observations-v3.jsonl`** when collecting its
evidence. Every row has `carrier_version:3`; v1/v2 `nav-observations.jsonl` is preserved.
Rows are bounded1000/activity, file1MiB; fixed routes/actions and synthetic integers
only. If logs stop, treat missing cases as NOT_RUN, not PASS. Current/previous/presence
is not a complete ordered internal stack. Device runtime outcomes must remain separate
from the17 scoped JVM passes and static checks in RESULT.json.

The default owned graph and animations differ from production initial-route/chrome/
feature-owner lifecycles. Even successful carrier retest needs separate approval and
production ESV acceptance. No production APK increase/install is implied here. ANR
and batch sharing/export/delete remain separate outstanding work.

AGP source-directory configuration follows the official
[built-in Kotlin migration guide](https://developer.android.com/build/migrate-to-built-in-kotlin#kotlin-sourceSets).
Two failed configuration attempts and all exact compile/cache/lint outcomes are
preserved in this directory; final build succeeded. App lint59W/8H/0E, carrier8W/0E.
