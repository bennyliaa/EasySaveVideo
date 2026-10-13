# Semantic theme token slice

See ESV_UI_TOKEN_AND_COMPONENT_MAP.md for defaults, source roles, preserved protocol and all
remaining Token/visual acceptance gaps. This change extends the approved theme rather than
adding a second visual authority. Explicit control/callback/state/permission behavior stays
outside the theme patch.

This slice follows the separately frozen Save display Locale change and intentionally replaces
that slice's SaveTaskScreen with the final combined display+appearance version. The Locale
freeze itself remains immutable; its other6 source/resource/test files remain untouched.
Publish Locale first, then this Token snapshot, using the final combined SaveTaskScreen only.
The earlier 16 Shell and 15 current-contract frozen files are separately verified unchanged.

App 1801 and ui-theme 17 JVM tests actually executed PASS, with 0 failures/errors/skips;
18 are new (10 App + 8 theme). Debug Kotlin executed in build1 and was UP-TO-DATE in final
build2; Release Kotlin and both lint tasks executed PASS in build2. Both lint reports retain
60 warnings / 8 informational hints / 0 errors. Runtime admission verifies 90 exact artifacts.
Final build2: 77 tasks, 23 executed / 54 up-to-date. Initial build1 failure remains in evidence.
All 465 other original production/config files match their pre-slice hashes. Prior contracts15,
Shell16 and Locale20 frozen copies match their manifests; only the expected live SaveTaskScreen
supersedes its Locale version. Independent static review found no blocking defect in the exact
15 source/test files. Evidence: artifacts/ui/semantic-theme-tokens-20261013.

Source and validation hashes identify exact inputs. Initial SDK token-name failure is retained;
final UI/JVM/compile/lint results are recorded independently from unperformed device/visual work.
