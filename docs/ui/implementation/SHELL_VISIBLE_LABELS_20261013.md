# Shell visible navigation labels — source slice

## Original requirement and scope

The exact original UI framework §3 requires large-font adaptation without reducing text;
§7 requires visible text on important controls. UI-B/UI-C implementation is separate from
UI-E rendered acceptance. The previous shell removed all four root labels above fontScale1.5.

This slice keeps the existing four root destinations, selected-root expression, enabled gate,
onNavigate callback and NavHost. It does not touch navigation dispatch, route restoration,
settings storage, browser, save, capture, editor, library, entitlements or dependency admission.
API31 remains minimum. No payment, import, Vault, history-TTL or source-policy decision changes.

## Reuse and presentation

Existing admitted Material3 NavigationBar/NavigationRail and items remain the control authority.
Both always supply wrapping Text labels and alwaysShowLabel=true. Decorative icons have no
separate contentDescription; official selection/disabled/click semantics remain authoritative.
48dp is a minimum size, not a fixed height. Text scale is never reduced and there is no maxLines
or ellipsis override. Label typography remains supplied by the existing SkinKit MaterialTheme.

Rail width grows from80dp with font scale, bounded by available width. Its viewport has finite
Scaffold content height and vertical scrolling, so tall items do not permanently hide the last
root on short windows. Top controls and bottom navigation have separate finite scroll viewports;
Scaffold padding is consumed before Rail content to avoid duplicate inset handling. Each viewport
has separate saved scroll state; these states hold only lightweight offsets, no business state.

For exceptionally narrow windows the four-item bottom bar needs216dp after horizontal insets:
four48dp targets plus three8dp gaps in admitted Material3 1.3.2. Below that width it uses the same
Rail fallback as low-height windows. Actual NavigationBarDefaults horizontal insets are read with
current density/direction. Normal Compact/Medium/Expanded selection remains from official inputs.
The80dp rail floor takes priority over its40% width budget, but never exceeds available width.

The helper's font-scaled rail width is a presentation heuristic, not proof that every translation
fits without wrapping. Small windows may require scrolling. A viewport physically smaller than
48dp cannot establish a fully visible48dp target; no such acceptance is claimed. Header status-bar
padding lies outside its text viewport, so pure percentage fixtures do not prove a physical
nonzero content area for every extreme system-inset combination.

## Verification plan and boundaries

The13 new JVM tests cover geometry and source integration: ordinary compact sizes/text scales,
official medium/expanded/low-height input,216dp boundary, horizontal insets, rail width bounds,
finite/invalid geometry, the actual Foundation finite-constraint check, unconditional labels,
standard semantic/callback preservation and viewport modifier ordering. Manually constructed
Foundation Constraints validate that specific contract, not the measured Compose hierarchy.

App1774 JVM tests executed PASS (13 new), no failures/errors/skips. Debug/Release production
Kotlin compiled in build1; final build2 reused those unchanged production outputs. Both final
lint tasks passed with60 warnings/8 hints/0 errors each; strict runtime admission remained90.
Build2 completed75 tasks:12 executed/63 up-to-date. Initial test-reader compile failure is
retained in build1; Files.readString was replaced by the existing Files.readAllBytes pattern.
Independent static review found and closed the216dp boundary defect; final source hashes and
verification details are in artifacts/ui/shell-visible-labels-20261013.
No APK/device operation, screenshot comparison, pixel parity, TalkBack, keyboard traversal,
font200 actual rendering, last-item click or native media acceptance has run for this slice.

## Independent review correction

The first scratch draft used192dp and missed Material3's3×8dp item gaps. Independent review found
that explicit48dp minima cannot override the Row's weighted42dp constraints at that width. The
scratch patch changed to216dp using actual horizontalInsets and added boundary fixtures before
live integration. No failing artifact or device was produced by the superseded draft.

## Next separate work

SaveTaskScreen still has reachable hardcoded task verification/record duration/candidate/HLS text
and uses old Chinese recording outcome/stop helpers despite existing resource mappers. A separate
locale slice can reuse those mappers, parameterize EN/ZH resources and use the active Compose
resource locale for display formatting. This navigation slice does not implement that work.
