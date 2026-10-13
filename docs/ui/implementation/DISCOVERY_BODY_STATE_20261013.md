# Discovery empty-body state presentation

Original UI 01 §4.7 / §6 C-12, 03 UI-R13 and 05 §3 require distinct loading,
empty, waiting and error feedback. Before this slice, DiscoveryScreen's empty-content body
only distinguished storage failure and NO_SOURCES. Restoration, FILLING, BACKOFF and FAILED
could display their correct header alongside an incorrect central “no results” heading.

The new pure DiscoveryBodyPresentation projection consumes existing state. It preserves the
old pending-candidate versus materialized-result branch exactly, then checks storage failure,
initial restoration, pending filter submission and actual domain status. A pending page only
specializes IDLE/null status, never hides explicit failure/backoff/suspension/exhaustion.
Unsupported query/configuration reasons are scoped to the existing UNSUPPORTED_FILTER state.
No offline/network diagnosis is inferred. No request, retry, source, scheduler, task, history,
permission, or current page is mutated. Existing browser, retry and cancel-page actions and gates
are unchanged. No automatic transition creates a request.

DiscoveryScreen resolves resource IDs during composition. Eleven paired English/Simplified Chinese
strings explain the new body states; known headings and recovery guidance reuse existing resources.
No new framework, provider, dependency, navigation path or fake progress is added. Existing
results stay visible during new work or errors; this is not an error-overlay replacement.

App 1813 JVM tests actually executed PASS, including 12 new tests; zero failures,
errors or skips. Debug/Release Kotlin and both lint tasks executed PASS in final build2.
Both lint reports retain 60 warnings / 8 hints / 0 errors; runtime admission verifies 90 exact
artifacts. All 475 other original production/config files match pre-slice hashes.
Independent review corrected EXHAUSTED copy: a bounded round ending is not proof all sources
are exhausted. The new regression covers both READY and EXHAUSTED provider details; the body
now reports only this round, while existing footer exhaustion gating remains intact.
Both build logs are retained. See artifacts/ui/discovery-body-state-20261013 for exact evidence.

Source assertions and JVM precedence tests are not Compose device evidence. NOT_RUN: APK,
emulator/phone, actual loading/rendering/timing, 200% text, TalkBack, screenshots/PPT comparison.
The original PPT binary has not been viewed. This is one bounded UI-state repair, not full C-12
or all original UI acceptance.
