# Default compression for new editor drafts

## Scope and original requirement

Product Requirements §7.2 keeps original-quality and save-space choices, and §10 lists a
default compression setting. This slice implements the editor portion of that requirement.
It does not add recording compression, automatically edit a recording, or claim the full
recording/editing settings category is complete. Baseline: dc2f421891c558ad2921eaa0882e89d4249cff79.

## Behavior

- Settings → Editor offers Original quality / Save space for new edits. The default is
  original quality; a missing or unknown preference key safely resolves to original quality.
- AppSettings schema remains v1. The additive `editor_default_compression` string preference
  uses stable `original_quality` / `save_space` keys in the existing settings store. No enum
  ordinal, library type, or Android type enters core-model.
- Both media-library and verified-recording-to-editor entry points pass the latest in-memory
  editor settings. This avoids reading stale disk values while the coalesced settings write
  is still pending. The new-entry buttons wait for the existing bounded startup settings
  load/recovery callback, so an initial placeholder AppSettings cannot freeze the wrong mode.
- A fresh runtime draft, or a new draft opened after successful publication, consumes the
  preference once. Additional/repeated source selections, manual compression choices,
  failures/cancellation, and removing all segments retain the current draft's choice.
- A successfully restored checkpoint owns its saved compression. Existing restoration
  generation/user-change guards still reject late restoration after a new user action.
  Settings changes do not mutate active/restored drafts or trigger any media work.
- Save space remains the existing EditCompression.SAVE_SPACE request consumed by EditPlanner.
  It requires the existing VIDEO_COMPRESS capability at submission and before execution;
  changing a preference grants no entitlement. Original-quality no-op behavior is unchanged.
- Settings copy operations preserve browser, discovery, appearance and schema fields. The UI
  reuses the existing responsive settings panes, theme visuals and radio semantics, with
  English/Chinese resources and a 48 dp minimum touch target. Copy explicitly says existing
  drafts are retained, the paid compression capability is still required, and smaller output
  is not guaranteed.

## Review and verification

Independent static review identified an asynchronous startup race before settings recovery
finished. It was corrected by gating both fresh entry points on settingsReady; resumed editor
screens and checkpoint restoration remain usable. The final review result and exact checks
are recorded in artifacts/ui/editor-default-compression-20261013/validation.json.

Pure JVM tests cover legacy/missing/unknown keys, stable-key round trips, new draft selection,
new draft after success, repeated/appended sources, manual overrides, clearing all sources,
restoration under opposite defaults, unchanged settings siblings and denied compression
without entitlement. Source contracts check additive persistence wiring, both entry points,
startup readiness gates, restoration ownership, existing submit/execution entitlement checks,
localized resource completeness and accessibility semantics. Source-read contract files are
registered as Gradle inputs.

Limits: synthetic JVM/source contracts and Android compilation/lint do not prove SharedPreferences
restart behavior, Compose touch/layout behavior or Media3 compression on a device. No APK was
assembled/installed, no emulator/device or real network was used, and no dependency was added.
The repository does not expose testReleaseUnitTest; release is covered by compile/lint only.
Any pre-existing unavailable local corpus cases remain reported as skipped, never counted as
executed acceptance. See validation.json for actual execution versus up-to-date tasks.

## Next original setting

Wi-Fi-only saving is a separate next scope. This slice leaves save, transport and recording
network policy untouched. The settings files are released only after the reviewed source
and evidence have been frozen for publication.
