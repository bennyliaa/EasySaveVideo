# Independent read-only source reviews

2026-10-13: review_auth_browser_navigation reviewed all nine authentication source/resource/test
inputs. Final PASS after two refinements: cold-recovered WAITING_USER rows may lack coordinator
memory cache, so absent cache permits navigation-only subject to current unique row and all live
gates; present stale cache rejects. Existing mediaTaskActivity is observed for availability while
clicks recheck actual owners. This is explicit return to the current Browser, not automatic
OpenOriginForLogin, login or resume. No reviewer Gradle execution.

review_destination_copy_slice reviewed five destination source/resource/test files and the shared
app/build.gradle.kts. Final PASS: all 26 English/Chinese pairs preserve semantics. Reversing only
copy substitutions recovers the original UI bytes: 12 click callbacks, 2 dismiss callbacks,
4 enabled expressions and filtering/layout unchanged. AndroidOrdinaryDestinationRuntime unchanged.
Dynamic runtime notice/status strings remain outside this slice. Gradle changes only register
contract source/resource inputs (39 source inputs unique and present), with no dependencies or
runtime-admission changes. Final reviewed inputs are pinned in SOURCE_SHA256.tsv.

Source-contract fixtures do not establish device, Compose event-ordering, recovered Android
process, WebView authentication, native destination output, visual or accessibility acceptance.
