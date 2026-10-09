# M1-B1 verification infrastructure

This directory contains current-slice wrappers only. Historical scripts, test
bodies, fixtures, evidence, dependencies, manifests and Git executable modes are
protected against the exact `0c178320cd78391bfedd5f9846e3ee873f0f5fce` baseline.
This is the approved media-close hotfix source baseline. The baseline contains
4,070 tracked files, including the hotfix implementation, tests and evidence. Only `MainActivity.kt`,
`ui/BrowserScreen.kt` and `browser/SystemBrowserHost.kt` have edit permission;
new app sources/tests must appear individually in `scope-allowlist.json` and
remain in the ordinary browser bookmark packages. A missing/empty allowlist
fails closed and is never inferred from whatever happens to be in the worktree.

## Final candidate procedure

1. Finish production code, ordinary bookmark tests, current-scope integration
   review and all independent review scripts/fixtures. Set the explicit new
   production/test file list in `scope-allowlist.json`.
2. Run `python3 docs/browser-bookmarks/evidence/verify-inherited-wrappers.py`.
   `--write` is only a deliberate pre-freeze preparation operation. It constructs
   current wrappers from immutable M8-B4 sources using enumerated path/baseline/
   membership changes; it must not run during acceptance.
3. Run `python3 docs/browser-bookmarks/evidence/freeze-inputs.py --write
   --expected-jvm-tests 2035`, requiring the exact combined total:
   1,959 hotfix-inclusive baseline cases plus 76 bookmark cases. The freezer
   checks full baseline immutability before writing the current manifest.
4. The single Gradle owner runs `scripts/verify-bookmarks-workspace.sh`.
   No Gradle, packaging or Android execution is performed by infrastructure
   preparation or its self-tests.

The aggregate fails before Gradle if any required independent hook, explicit
allowlist or final freeze is absent. Its default output is this evidence
directory. `ESV_EVIDENCE_DIR` may point outside historical documentation; output
inside another milestone's documentation is rejected. Provisional freeze
bypasses are disabled for the aggregate. Each current independent bookmark
runner must accept its absolute output directory as argument 1; the standard
path is `docs/browser-bookmarks/evidence/independent-bookmark-run.sh`.

The previous bookmark-only 2,020-case checkpoint and its interrupted aggregate
remain historical evidence. Updating this infrastructure does not turn that
checkpoint into a completed channel-matrix or combined-verification pass. Final
combined freeze and aggregate execution are separate owner-controlled steps.

## Preserved aggregate coverage

The complete main-worktree Gradle JVM set (debug app unit-test variant), actual
debug/release Kotlin compilation and lint, strict offline dependency admission,
exact 74 external runtime artifacts, runtime inventories and release Java
compilation remain required. Source/platform/ABI/media admission and all inherited
SQLite, detector, HLS, editor, provider, discovery, origin, media-priority,
power/filter, OS-signal, destination/folder, process-reopen, ordinary isolation,
reliability and entitlement regressions remain present. The seven-channel
actual debug/release compilation/host matrix and five rejected channel values
remain present and end in the unconfigured default. Missing provider-host gates
are fail-closed rather than optional.

No inherited behavioral test body is weakened. The only non-path adaptation is
replacing old milestone scope/membership metadata with the current complete
baseline byte/mode and exact current-input membership checks. The M8-B4 OS
wiring, effective permission, compiled-class and release-no-simulation checks
remain byte-exact after their scope prefix. Historical Kotlin review fixtures
and API doubles remain in their immutable original paths.

## Freeze and negative controls

The interrupted archive is checked against its exact pinned manifest SHA-256 and
immutable original bookmark checkpoint `53ee1863a5b60f44752d246e936b599ca1ca7628`.
Every archived file must match both its recorded size/SHA-256/Git executable mode
and the original checkpoint blob/mode. Exact file and directory membership,
regular-file checks and a fixed 203-row count reject missing/new/renamed inputs,
symlinks and extra empty directories. Neither a later candidate freeze nor
rewriting the manifest can legitimize altered historical bytes. This guard
only verifies retained evidence; it does not reclassify the interrupted run.


The current manifest contains every baseline tracked file plus the explicit
new bookmark source/test set, aggregate runner, explicit allowlist and current
nested executable review inputs (`.py`, `.sh`, `.kt`, `.java`, `.js`, `.mjs`, `.sql`, `.bin`). It deliberately
excludes itself and generated current logs/reports/bytecode. The 203 archived
interrupted-2020 outputs and their PRESERVED_OUTPUTS.json are included as
immutable historical provenance, regardless of their output-format suffixes. It verifies exact
set equality, byte count, SHA-256, regular-file identity and Git executable mode.
Current documentation prose and generated results are outputs rather than
executable verification inputs. The aggregate pins the manifest identity before
work and compares it after all gates, rejecting a mid-run refreeze.

`test-verification-infrastructure.py` uses fresh synthetic Git repositories in
temporary directories. Its 30 tests cover positive scope/freeze cases and
negative controls for historical source/gate/fixture edits, deletion, symlinks,
mode changes, missing/unlisted/new unrelated sources, scope expansion, changed
frozen bytes, newly introduced unfrozen Kotlin/Java review inputs, mid-run replacement of
the manifest, superseded JVM totals, altered/deleted/added archived evidence,
archive mode changes, manifest rewriting and accidental overwrite of a frozen input by an identity record.
These are verification-infrastructure tests, separate from the product JVM and
independent bookmark review counts.

Android runtime behavior, installation, accessibility/device UI, WebView/OS
semantics and process/lifecycle performance remain separate NOT RUN gates.
No APK/AAB or device acceptance is authorized by this runner. Vault and the
blocked M7-D candidate remain untouched, excluded and disabled; only their
previously authorized ordinary-isolation regressions are included.
