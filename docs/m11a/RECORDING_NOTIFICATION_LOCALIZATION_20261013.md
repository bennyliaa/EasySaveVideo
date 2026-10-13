# Recording foreground notification localization

Source baseline: published development head `4f22d43d023b7db6e3051613ed02c144bf3ad75b`. This slice is pending publication. Requirement: original UI 03 §7 and tester 04 T-05, translated notifications with one locale preference owner and no task resubmission.

## Complete bounded text channel

The three existing `showStatus` callers now carry a parameter-free, app-only `RecordingNotificationStatus` instead of localized strings. A closed twelve-case enum represents initial preparing, the six existing basic-recording groups, and the five segment-capture phases actually emitted today. The service also localizes its existing channel name, title and Stop & Save action: **15 actual display leaves** total.

Ten new bilingual resource keys preserve the original Chinese text. Five segment status IDs reuse the already admitted `final_status.xml` translations. The original native `RecordingState` grouping/default is unchanged, as are the duplicate recovery-warning branch and the segment producer's five-phase mapping. The other eight segment-presentation phases produce no new notification. Processing/publication notifications and success claims have not been added. Session ID remains a control/PendingIntent identity; no user filename, page title, URL, grant, error text or ID is included in notification copy.

## Locale and lifecycle

A display-only language mirror in the existing AndroidSaveRuntime singleton receives values from exactly two existing MainActivity owner delivery points: an applied language edit, and final startup settings after merge/early-edit precedence. It is not reset from a recreated Activity's temporary default AppSettings. No additional preference, LocaleManager writer, global locale or synchronous service-side settings IO was introduced.

The stateless resource-context helper is shared with EsvLocaleHost. API31/32 explicit English/Chinese uses a copied current Configuration; SYSTEM and API33+ use current platform resources. The platform/existing AndroidSettingsStore remain the locale persistence owners. Typed status is resolved at each render, so an active paused/no-progress notification can change language without awaiting a recording status transition.

The service caches its typed display status only after the existing `startForeground` succeeds. Locale/configuration updates can only repaint an already-promoted, matching, unfinished session with the latest status. Finish and destroy retire that display snapshot before native cleanup/callbacks. No active recording, old ID, pre-promotion callback or retired instance can recreate a notification through this path. Repeated same-language owner updates are no-ops. A failed notification update does not alter capture truth or ownership.

Refresh uses notification ID 4301 and the existing `esv_recording` channel. Only the existing channel object's name changes; no new channel ID, channel deletion, importance/sound/vibration/permission edit or new service start is added. Start/Stop action identities, immutable PendingIntent flags and session identifier, START_NOT_STICKY, pending-recording admission, screen-off safety stop, foreground-before-projection ordering, receiver lifecycle and finish/destroy callbacks retain their prior behavior. No Save/recording domain, wire/storage, projection grant, native engine, worker scheduling, dependency or APK configuration changed.

Independent source review caught a misplaced insertion that separated the existing recordingService property from its custom setter before any build. It was corrected immediately; setter adjacency is now an explicit source contract. The complete edits to six existing production/config files reverse byte-for-byte to baseline, including that unchanged setter.

## Validation

App **2,026 tests actually executed**, including **28 new tests** (6 exhaustive status/resource tests, 12 pure display/language fixtures and 10 source-wiring contracts), with zero failures/errors/skips. Debug/Release Kotlin compilation and each variant’s lint analysis/report/entry tasks were forced and passed. Each lint retains **60 warnings, 8 hints, 0 errors**. Runtime admission reverified **90 exact artifacts**, and the unchanged boundary checker passed seven checks. All ten requested Gradle tasks actually executed. All **1,417 frozen inputs remained unchanged**; 1,403 baseline inputs outside the six scoped modifications retained identical bytes. Other module tests were not rerun in this slice.

Evidence: `artifacts/ui/recording-notification-20261013/`. The complete evidence ZIP contains every App JUnit XML, both lint reports and the full build log.

The first forced run executed 2,026 tests with one failure in a newly authored source contract: it incorrectly banned `startForeground` across the whole pre-existing Service, rather than only the new locale/refresh paths. Only that assertion scope was corrected; the service-wide preference/locale-writer bans, new-path start bans and original-startup count checks remain. The first failed log, all 223 first-run JUnit XMLs, census, input pin and exact test correction are retained. No production source changed between runs.

The new tests include six exhaustive status/resource tests, twelve pure display/language/lifecycle fixtures and ten actual-source wiring contracts. They cover every native state, every segment phase, all fifteen legacy Chinese leaves, EN/ZH parity, latest-status re-render, promotion/exit/old-session fences, API31/32 versus API33+ locale selection, effective-settings precedence and all three real status producers. Pure fake render failures and source contracts are not NotificationManager/WebView/Service device tests.

Evidence is under `artifacts/ui/recording-notification-20261013/`, including the full App JUnit census and all XMLs, both lint XMLs, complete build log, source input manifest, inverse substitutions and independent review.

## Explicit acceptance limits

NO_DEVICE. API31/32 first-frame language with delayed/failed settings load is unverified: recording start already need not await settingsReady, and this slice adds no new capture/start gate. Cold start can initially use SYSTEM fallback until the existing settings owner delivers its value. An existing runtime's language mirror survives Activity recreation until a new owner value is delivered.

API33+ platform locale changes may trigger Activity lifecycle callbacks. Existing onPause behavior and unencoded preview invalidation on destruction were not changed. Actual active-recording/paused-notification continuity, consent/preview behavior, system-locale changes, channel/action rendering, notification-permission visibility, stale Stop action rejection and real no-resurrection after finish require owned-device testing. No uninterrupted-recording claim is made from JVM evidence. Original long-text/200%/RTL/TalkBack and PPT/pixel checks remain NOT_RUN.

Other module tests were not rerun for this slice. The full thirteen-module regression remains bound to b0c5647. The previous local-only code8 debug APK still binds pre-anchor c794ef and remains unchanged, uninstalled, unuploaded and excluded; no APK was assembled here. Known OPPO editing STORAGE_UNAVAILABLE, sharing INITIALIZATION_FAILED, ANR/second-recording and Windows symlink/setup-guard issues remain open.

This completes the current foreground-service notification text channel, not all recording runtime notices or all App internationalization. It does not enable import, Vault, interpreter, payments or decide history retention.
