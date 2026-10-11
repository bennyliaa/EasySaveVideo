# UI capability integration ledger

REAL below means an existing callable implementation, not fresh end-to-end acceptance.

| Flow / PPT | Existing binding | UI work / boundary |
|---|---|---|
| Discovery6–8,25 | AndroidDiscoveryController, VideoDiscoveryCoordinator, filter/power/history controls; REAL | Reuse revision/visibility/Seen gates and materialized pages; zero-provider/address state. No fake confirmed video or entity PLAYED/SAVED |
| Browser9,11 | SystemBrowserHost, BrowserPersistence, AndroidBookmarkRuntime; REAL | Single selected-tab host, address/history/bookmarks/incognito; sensitive session data stays local |
| Detect/select/save10–14 | AndroidSaveRuntime, SaveSelectionDialog, SaveTaskScreen, OrdinaryDestinationDialog; REAL with capability gates | Reuse scanning, exact candidate revision, entitlement/consent and destination commands. Unknown progress remains unknown; successful MP4/download not freshly tested |
| Recording15–16 | RecordingConsentController, RecordingScreen, segment runtime/region setup; REAL with capability gates | Show preparation and existing session controls; no test recording or microphone action this task |
| Editor17 | EditorScreen and existing runtime; REAL with Tools gate | Secondary route, non-destructive output and exact MediaReadRef; no encoder rewrite |
| Library18,20 | AndroidLibraryRuntime, LibraryScreen, existing preview/export/thumbnail adapters; REAL | Stable IDs and existing operations; user media never becomes screenshots/test fixtures uploaded to Git |
| Vault19 | Disabled/unreviewed | UNAVAILABLE explanation; no unlock boolean, private reader or simulated security success |
| Settings21–23 | AndroidSettingsStore + BrowserPersistence, ProviderManagementController, source configuration screens; REAL/PARTIAL | Reuse settings persistence; add appearance/language through one owner; source updates use existing gates |
| Entitlements24 | BuildEntitlements/FeatureGate; REAL gating, Store unavailable | No prices/SKUs invented; no real purchase or arbitrary release unlock |
| A/B source verification22 | Existing optional service client and bounded local engine; UNCONFIGURED by default | Explain configuration/consent; no new site/service activation |

Existing MainActivity booleans are legacy switching, not an official navigation implementation. The navigation milestone must replace their authority, preserve task/runtime owners and independently prove return/restoration behavior. Theme upgrade alone does not close UI-B–E.
