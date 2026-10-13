# Independent source review

Scope: the Shell visible-label patch, its pure geometry policy and thirteen proposed tests.
Review was read-only and did not run Gradle or devices. It used original UI framework §§2/3/7,
the exact local patch and the existing admitted Material3 class behavior.

The first scratch review identified a real width-boundary error: four48dp items additionally
need three8dp Material3 spacing gaps.192dp would leave42dp per weighted item despite the minimum
modifier. The author corrected the threshold to216dp after actual horizontal insets and added
215/216 and24/25 inset-boundary fixtures. A second review confirmed that issue was closed.

No further blocking static issue was found in finite scroll modifier order, Scaffold inset
consumption, wrapping labels, non-duplicated icon semantics, preserved selected/enabled/click
expressions or the unchanged official navigation owner. Rail width uses safe clamping, including
narrower-than200dp windows where80dp takes priority over the40-percent heuristic budget.

Limits: top status-bar padding lies outside the text viewport, so percentage-only helper tests
cannot prove usable content for every extreme inset/window combination. Hand-made finite
Foundation constraints do not prove a real Compose tree's measurement. No rendered, TalkBack,
keyboard, touch-target, lifecycle/recreation or device acceptance was performed by this review.

The later compile-only repair changes the test source reader from Files.readString to the
project-compatible String(Files.readAllBytes(path), Charsets.UTF_8). It changes no production
logic, assertion scope, geometry or semantic behavior. Build results are recorded separately.

Final live/scratch byte equality was independently checked after the test-reader repair.
- EsvAppShell.kt: b70e627c622a96d834d430a67fe55e43538a0735177e61aeb2954b0e802578a1
- ShellNavigationLayout.kt: f252d7bc4208f19b6146880208e52d04d54b5ba5ab162c10452d741f59b21ebb
- ShellNavigationLayoutTest.kt: 94c31bb1b208d11d535fabef10bb917894d25bc27d559f9c6e47efa8e6b0fa69
The reviewer did not independently rerun tests or lint.
