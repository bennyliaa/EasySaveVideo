# Generated save-candidate labels and denied website-permission notices

Original UI 01 §7 requires application-authored copy to resolve through the current locale.
This group closes two bounded display paths; it does not translate user-authored text.

## Save candidate numbering

PageSaveCandidate is an app-local transient selection object with one detector constructor and
one label consumer. It has no persisted or provider wire encoding. Its previous label was a
Chinese sentence generated from the existing bounded video number and source-array index.
The replacement keeps these numeric values as PageSaveCandidateIndices and resolves the full
sentence through current Composition resources with locale-formatted numbers. The JavaScript
result schema and script are unchanged; no webpage title or filename is read or parsed.

The original video-number clamp and source-array numbering are preserved, including gaps
caused by invalid/duplicate candidates. Existing HLS copy upgrades retain the DTO, source URL
and opaque identity. Source bytes, detection deadlines, staleness checks, supported/protected
flags, command selection and admission gates are unchanged. No localized string is split to
recover state. The existing task/source metadata display remains intact.

## Denied webpage permissions

SystemBrowserHost receives the raw Activity context, which is not the API31/32 EsvLocaleHost
resource context. The two denied-permission notices therefore carry an app-local typed value
until BrowserScreen resolves their resources in its current Composition. Other notice strings
remain equal raw Text values; this slice does not migrate their content or expand their inputs.
Only the already-sanitized origin is carried; it is never translated. The unknown-origin fallback
is product copy and is resource-backed. No extra page content is included.

Camera/microphone request denial and the geolocation denial callback remain before notice
publication, exactly as before. No permission grant, new permission prompt, website authorization,
recording consent, automatic retry or policy change is introduced. Existing dismissal semantics
and notice replacement remain. Typed permission values are not flattened at emission time,
allowing display to use the current UI locale without a second locale provider.

App 1886 JVM tests actually executed PASS, including 16 new tests (six candidate numbering
and ten permission-notice contracts), with zero failures, errors or skips. Debug/Release Kotlin
and both lint tasks executed PASS in build1; each lint retains 60 warnings / 8 hints / 0 errors.
Runtime admission verifies 90 exact artifacts. Theme module tests were not rerun. All
486 other original production/config inputs match pre-slice hashes. Independent source
reviews cover each bounded slice and the final contract-input registrations. This source-verified
group follows the Destination/Discovery role freezes and awaits publication.
See artifacts/ui/candidate-permission-display-20261013 for sanitized logs, actual JVM census,
new-test XML, lint reports, source pins, static equivalence checks and independent reviews.

NOT_RUN: APK, emulator/phone, actual WebView permission events, live locale switching, Compose
rendering, accessibility, screenshots or PPT. JVM/source fixtures do not establish device or
website authorization acceptance. The original PPT binary remains unavailable.
