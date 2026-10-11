# P1 repeated-root visibility regression

The c4e4c86 review found that repeated Discover/app-name taps closed discovery's
visibility gate even though Navigation singleTop kept the same destination. Its
visibility effect therefore did not restart.

MainActivity now requires an attached controller and delegates to a finite
production dispatcher. Same-route intents return before any visibility/source
gate or export selection is changed. Actual route changes close source gates
before navigation. No save/record runtime command is added.

Three JVM regressions run the actual dispatcher against VideoDiscoveryCoordinator
with an owned in-memory provider runtime. Repeated Discover and app-name intents
cause no extra cancellation; the next explicit query executes. Leaving for
Browser still cancels discovery before navigation. Zero providers retains the
explicit NO_SOURCES state. PASS: 3 tests, 0 failures/errors/skips. The first test
fixture compile attempt failed and is retained; the corrected run passed.

Evidence: artifacts/ui/navigation-p1-20261011. These are coordinator/intent tests,
not Android click, rotation/process restoration, large-font or TalkBack evidence.
Those remain NOT_RUN. VersionCode is prepared as 4 for the next authorized UI
package; minimum API31 is unchanged. No phone operation or APK installation.
