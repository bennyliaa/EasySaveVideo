# ESV UI token and component map — bounded semantic foundation

Status: partial UI-B source implementation. This is not full SkinKit/PPT/UI-E acceptance.
All nine original UI Markdown documents were read. Original01§5/03§3/08§5 define the
single theme/registry/adapter boundary;07 maps this work principally toPPT4/5/29.
The PPT binary and imagery were not viewed; no pixel comparison is claimed.

## One theme authority

Existing EsvTheme TokenRegistry, AppDefaultTheme and single source.pin().use read are
extended. Every added value is detached in the same EsvVisualSnapshot generation and
converted by the existing Compose adapter. No new provider, pack loader, resource decoder,
image/cache engine, dynamic palette or dependency is introduced. Existing Subscription
release, generation remember and configuration SideEffect remain unchanged. All new values
are in-memory Color/Dimension/Typography contract values, so no async resource lease is added.

Pinned artifact coordinates remain Contract/Default1.0.0. Their declared contract protocol
is0.0.1; the protocol assertion is deliberately preserved. The existing app-default theme
version0.1.0 remains; source/release hashes identify this incremental default-token revision.
No persisted user theme choice or setting is migrated or overwritten.

## New required tokens and current defaults

| Token | Light default | Dark default | Compose model |
|---|---|---|---|
| color.surface.elevated | #EDF4F6 | #1C3440 | colors.elevated |
| dimension.corner.card | 16dp | 16dp | cardCorner |
| dimension.corner.sheet | 24dp | 24dp | sheetCorner |
| typography.section | 20sp / 28sp line / 600 | same logical values | sectionTextStyle |
| dimension.spacing.extra.small | 4dp | 4dp | spacingExtraSmall |
| dimension.spacing.compact | 12dp | 12dp | spacingCompact |
| dimension.spacing.extra.large | 32dp | 32dp | spacingExtraLarge |

Existing control12dp, spacing8/16/24, title24/body16/caption12 and component-specific
button styles remain independent. New values are default data, not forced feature constants.
Non-default source overrides are explicitly tested through pin→snapshot→Compose→selector.
The existing accessible Light primary#087582 remains instead of the initial conceptual
#087F8C, whose contrast with elevated/canvas is below4.5. Elevated is included in the formal
foreground contrast pairs and the existing4.5:1 JVM assertions for text/muted/accent/error.
This does not claim rendered alpha/state or image-background contrast acceptance.

Material mapping: surfaceContainer/high/highest use elevated; ordinary surface remains surface.
titleMedium uses section while titleLarge stays title. Extra-small/small shapes use control,
medium uses card, large/extra-large use sheet. EsvButton explicitly retains its component
corner and state visuals; custom inputs retain control corners and48dp host minima.

## Actual consumers in this slice

- Card corners: Discovery result cards, PendingCandidateCards, task rows/details, LibraryCard
  and selected detail, EditorCard, RecordingSegmentsCard, ProviderSection.
- Elevated sheet corners: existing Browser EsvDialog and LibraryDialog, explicit Discovery
  safety/rights/local-action confirmations, HLS selection, Provider confirmation and crop-modal
  outer surface. No command, control or preview/input inner corner is mechanically replaced.
- Section type: task detail headings/card title, selected Library folder/video/detail sections,
  editor sequence/source/inspector sections, recording card phase, provider and settings details.
  Main page and dialog headings retain their existing title role.
- SaveSelectionPanel receives explicit INLINE or FLOATING presentation from its actual host.
  Inline uses surface/card/padding16; floating uses elevated/sheet/padding32. The one existing
  Browser movableContent receives presentation alongside Modifier, retaining its selection,
  runtime and rememberScrollState ownership. Settings/bookmark fallback is still a floating
  host even on a wide window. HLS keeps its separate existing modal path. Candidate inner
  spacing4 and panel spacing12 now consume the remaining added spacing tokens.

The earlier bounded token slice deliberately left Discovery's shared filter presentation unchanged.
The later Discovery filter-role slice now supplies explicit INLINE (surface/card) and FLOATING
(elevated/sheet) from the actual hosts, retaining spacingLarge padding and one movableContent.
Its separate verification is recorded in DISCOVERY_FILTER_PANEL_ROLE_20261013.md. The floating
host remains the existing Dialog; this does not implement a bottom Sheet or claim visual parity.
Separate success/warning, selected/loading, scrim/system-bar token coverage, all-component state
coverage, full image/lease scenarios and the complete original Token acceptance remain future
work; this map is not a declaration that all original required tokens are complete.

## Tests and evidence

App 1801 and ui-theme 17 JVM tests actually executed PASS, with 0 failures/errors/skips;
18 are new (10 App + 8 theme). Debug Kotlin executed in build1 and was UP-TO-DATE in final
build2; Release Kotlin and both lint tasks executed PASS in build2. Both lint reports retain
60 warnings / 8 informational hints / 0 errors. Runtime admission verifies 90 exact artifacts.
Final build2: 77 tasks, 23 executed / 54 up-to-date. Initial build1 failure remains in evidence.
All 465 other original production/config files match their pre-slice hashes. Prior contracts15,
Shell16 and Locale20 frozen copies match their manifests; only the expected live SaveTaskScreen
supersedes its Locale version. Independent static review found no blocking defect in the exact
15 source/test files. Evidence: artifacts/ui/semantic-theme-tokens-20261013.

New tests assert exact legal token IDs/types, mode/scale/motion completeness, all new values
read under one pin, close after a new-token failure, old pinned/detached generation stability,
logical rather than pre-scaled text, non-default13/21dp and23sp overrides, Compose Material
mapping, real selector presentation and source consumption. They complement the existing
foreground and button-state contrast checks. Source assertions do not establish actual pixels.

Initial build1 is retained: SDK rejected underscore spacing IDs before any device deployment.
The IDs were corrected to its required lowercase dot-separated format, with exact-ID tests;
no SDK, contract-version, dependency or hash gate was weakened to pass.

NOT_RUN: APK assembly, device/emulator, actual Light/Dark/System and200% rendering, TalkBack,
keyboard/focus, window transition scroll/state behavior, PPT screenshots or full34-page parity.
No native save/record/edit or private-media acceptance is implied by this appearance slice.
