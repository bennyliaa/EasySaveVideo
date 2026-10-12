# Isolated actual NavController diagnostic carrier

Package `com.easysavevideo.navdiagnostics`, activity `.NavDiagnosticActivity`,
version1/min31/target36. Opt-in module only (`-Pesv.navdiagnostics=true`); no production
dependency, shared UID, ESV classes, database, media, networking, service or permission
requests. This own-source platform Activity is not the old A/B instrumentation runner
and introduces no third-party runner/test library. All58 runtime artifacts must be
exact subsets of the existing90 admitted bytes/versions; strict verification stays on.
Notice text is the shared admitted inventory superset, not a claim that every
inventory component ships in this diagnostic APK.

Local preparation, not execution:

```powershell
.\gradlew.bat --offline --no-daemon --dependency-verification strict -Pesv.navdiagnostics=true :nav-diagnostics:compileDebugKotlin :nav-diagnostics:assembleDebug :nav-diagnostics:lintDebug
```

No JVM harness with a real Android runtime was found in the admitted project;
plain Android unit stubs are not treated as actual NavController execution.
Android NavHostController/ComposeNavigator2.8.9 are instantiated on a future
authorized launch. Navigation options/equality replicate the original c18cb37
root behavior. No production repair is applied. Runtime outcomes remain NOT_RUN.

## Future acceptance, after separate installation/start authorization

1. Confirm target emulator-5556, inspect this separate package's presence/version/
   public certificate; do not replace ESV/code5 or unknown existing data. Install
   only the verified diagnostic artifact. No grants, security/display changes or server.
2. Launch `.NavDiagnosticActivity`. It displays fixed owned routes, not business
   pages. Initial entry may write bounded own-file telemetry. It never opens ESV.
3. Manually use OPEN_SETTINGS then HEADER_DISCOVER; repeat with BOTTOM_DISCOVER.
   Observe current/previous route, actual destination callback and present route
   membership in own `files/nav-observations.jsonl`. These are public NavController
   observations, not a full ordered internal stack dump or ESV click-hook proof.
4. RUN_ORIGINAL_MATRIX covers Discover/Settings via both action labels,
   Tasks/Settings, Sources/Browser/Editor return-root and same-root repeats. Reset
   only this graph between cases. Synthetic root saved-state marker17 is not media
   admission, user data or a product success. Inspect route_matches; do not assume PASS.
5. Use RECREATE_ACTIVITY at root and auxiliary route, record callback/instance and
   owned marker before/after. If separately allowed, physical rotation also exercises
   own Activity/NavHost restoration; preserve original device settings. Standalone
   restoration does not prove real ESV initial-route/feature-owner/private-state behavior.
6. Retrieve only this debuggable package's own JSONL, e.g. authorized adb run-as of
   `com.easysavevideo.navdiagnostics`. Do not run-as/read ESV or system logs with user
   input. No URL/title/query/cookie/token/media ID logging. No exception messages.

Install creates a separate app/UID/data directory; launch changes foreground and
may write only its own bounded JSONL/standard Android profiles. Backup/device
transfer of this app's data is excluded. It does not change device settings or
touch ESV data. Recreate affects only this Activity. Never enable diagnostics in
the production package or interpret its fixed graph as playback/editor acceptance.

Minimum fixes are considered only after actual route/marker/callback evidence;
auxiliary resurrection and legitimate root-state retention must both be checked.
The independent compile/static review is not root-cause proof.
