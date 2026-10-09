# Ordinary bookmarks: combined source milestone

The complete combined main-worktree verification passed on 2026-10-07 UTC.
This implements the ordinary bookmark portion of PR-25 only; it does not
complete browser history, incognito or per-site permission management.

Implemented: add/list/explicit-open/remove, conservative HTTP(S) canonicalization,
duplicate protection, 200-entry cap without eviction, safe bounded titles,
process-owned background private persistence, corruption/version/failure
handling, exact page/record identity fences, and scrollable phone/tablet UI
structure using SkinKit defaults. See `SCOPE_AND_DESIGN.md`.

Verified main source:

- 2,035 JVM cases, zero failures/errors/skips: 1,959 hotfix-inclusive baseline
  cases plus 76 bookmark cases.
- Strict offline nonincremental aggregate: 87/87 tasks executed.
- Both application variants compile and lint: zero errors, 17 warnings and
  seven hints each. This is not a warning-free result.
- Exact 74 external runtime artifacts; API31 minimum, API36 target/compile and
  SkinKit contract unchanged.
- 37 independent bookmark host cases, 23 source wiring checks, exact 14-file
  hotfix integration/patch identity and unchanged bookmark source identity.
- 30 infrastructure tests; all inherited source/host/SQLite/media/discovery/
  provider/reliability/entitlement/isolation gates passed.
- All seven actual compiled distribution identities, each debug/release with
  15 host cases; five invalid values rejected. Final default/payment remain
  unconfigured; no Commons contact was embedded.
- All 4,317 frozen inputs retain exact bytes/modes, including 203 outputs from
  the intentionally interrupted earlier aggregate and their preservation
  manifest. Scope protects all 4,070 hotfix-baseline files with only three
  bookmark integration edits allowed.

The earlier `53ee1863` source checkpoint and its interrupted 2,020-test run
remain preserved in Git and `evidence/interrupted-2020/`. Reviewed media-close
source `0c178320` was cherry-picked as `c3792eac` with identical bytes/modes;
the combined run establishes fresh evidence rather than relabelling old results.

Recovery bundle/source archives, both full cold restores and private Library
roundtrip are finalized in the separate recovery receipt after this source
commit. This document describes main-worktree verification, not a substitute
for that receipt.

No bookmark APK/AAB was assembled. Future bookmark APK versionCode must exceed
2 and use its actual source identity. Bookmark native WebView/AtomicFile behavior,
phone/tablet/keyboard/accessibility, rotation and process-kill acceptance remain
NOT RUN. Blocked features remain untouched and disabled.

## Separate limited hotfix device evidence

At 23:50 UTC the parent reported code2 retesting on emulator5556: existing lion
media remained, thumbnails/playback were no longer blocked by the resource-exit
state, and play/back/close/reopen plus the direct-save library-playback flow
passed. That is evidence for the separate media-close hotfix only. It does not
test bookmarks, HLS compatibility, recording, search, physical devices or all
media lifecycle cases. Independent source-review files retain their earlier
“pending” label from before this dated device report.
