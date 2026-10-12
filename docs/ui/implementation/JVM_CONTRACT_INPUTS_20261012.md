# JVM direct-file contract inputs and deterministic date fixture

Source baseline: published `development/cloud-followup-20261013` commit
`c45a18faa65cee0b1e466e59adb8efdfb4078e1a`; the cloud snapshot has no local Git
history. These changes concern build/test reliability only.

## Missing inputs

Some App JVM tests open project files directly rather than through their runtime
classpath. XML-only edits previously left `testDebugUnitTest` UP-TO-DATE.
`app/build.gradle.kts` now declares two named input collections on App `Test`
tasks, using `PathSensitivity.RELATIVE`. No task filtering/discovery, caching
switch, dependency, working directory, production source or license gate changed.
There is no permanent rerun/up-to-date override.

Static inventory of direct project reads (all paths below are under `app/src/main`):

- `DiscoveryPowerResourcesTest`: `res/{values,values-zh-rCN}/discovery_power.xml`.
- `DiscoveryResponsiveLayoutTest`: paired `discovery_layout.xml`.
- `SourceProviderDialogResourcesTest`: paired `provider_copy.xml`,
  `provider_dialog.xml`, `region_validation.xml`, `discovery_copy.xml`, `filter_copy.xml`.
- `FinalResourceContractTest`: paired `final_*.xml`. This must remain a file tree
  rather than a snapshot of four filenames: the test enumerates matching files.
- `FiniteI18nTest`: paired `finite_i18n.xml`.
- `ResponsivePaneStateTest`: paired `responsive_panes.xml`.
- `LibraryMultiSelectionTest`: paired `library_multi.xml`.
- `LibraryBatchSelectionTest`: paired `library_batch_export.xml`.
- `DirectoryBatchResourcesTest`: paired `directory_batch.xml`.
- `SettingsScrollStructureTest`: `kotlin/com/easysavevideo/app/ui/shell/EsvSettingsScreen.kt`.
- `LibraryBatchDialogScrollTest`: `kotlin/com/easysavevideo/app/library/LibraryScreen.kt`
  and `LibraryBatchExportDialog.kt` in the same directory.
- `NoticeViewportContractTest`: `kotlin/com/easysavevideo/app/licenses/ThirdPartyNotices.kt`.
- `NoticeSectionsTest`: `assets/third-party-notices.txt`.

Current inventory: 13 classes / 67 tests, 32 XML files, four Kotlin files and one
asset. Only the two locales actually read are included. Temp files produced within
tests are not external inputs. Classpath fixtures read by `getResourceAsStream`
remain tracked through the normal test classpath; no duplicate input declaration
was added. No build outputs, vendor trees or archived evidence are inputs. Future
new direct-file tests must update this explicit list. All App Test variants receive
the declaration; execution evidence here is for Debug, not Release.

## Reproducible incremental experiment

All five UTC steps use the same command, serially through the existing cloud
wrapper (which acquires the shared build lock):

`TZ=UTC <serialized-cloud-wrapper> --offline :app:testDebugUnitTest --info`

1. UTC baseline: test executor ran; 165 suites / 1432 tests passed.
2. Repeat with unchanged inputs: `:app:testDebugUnitTest UP-TO-DATE`.
3. Append one LF byte after the final newline of English `discovery_power.xml`:
   test executor ran; 1432 passed. Gradle's explicit reason was
   `Input property 'contractResourceInputs' ... discovery_power.xml has changed`.
   Both `compileDebugKotlin` and `compileDebugUnitTestKotlin` stayed UP-TO-DATE.
4. Restore the XML byte-for-byte from the original: test executor ran; 1432 passed,
   again because `contractResourceInputs` changed.
5. Repeat unchanged: test task UP-TO-DATE.

There were no `--tests`, `--rerun` or cache overrides in this experiment. Existing
build caching was disabled by the environment, so this proves local incremental
invalidation, not remote build-cache reuse/relocation. UP-TO-DATE stages reuse prior
JUnit output and are not reported as fresh executions. All 1115 files inventoried
under app/src, vendor and third_party matched their pre-experiment SHA256 afterward.
The existing local vendored POM CRLF restoration and toolchain were untouched.
The XML, source and build-script hashes captured before subsequent parallel feature
work are in `EXPERIMENT_INPUT_SHA256.json`.

## Existing time-zone-sensitive assertion found and corrected

The first unfiltered full App run in the cloud's original PDT environment actually
ran 1432 tests: 1431 passed, one failed at `LibraryPresentationTest.kt:38`.
The test constructed an item at epoch 0 and required its English date to contain
`Jan`. Production `libraryDate` uses locale-specific `DateFormat` with the default
system time zone; epoch 0 is December 31 in PDT. The test checks locale formatting
and metadata semantics, not a UTC date requirement. Earlier 468-test evidence was
a filtered selection and did not cover this class.

The minimal separate fix changes only that test fixture to
`1970-01-15T12:00:00Z`, safely away from any month boundary. All existing assertions
remain. Production formatting and global/default time-zone state are unchanged.
The original failure remains recorded in the evidence. The full input experiment
above predates this fixture fix and was explicitly UTC-only; it is not a claim that
the original suite passed across time zones.

An intermediate fixture edit put the timestamp in the wrong positional field
(`sizeBytes`), which the PDT check caught with the same assertion failure. This
was corrected to the actual `createdAtMillis` constructor field; the intermediate
UTC pass/PDT failure are retained, not treated as evidence of the final fix.

Two subsequent final-fixture attempts did not reach tests because unrelated
parallel feature source was mid-write and failed `compileDebugKotlin`. Their old
JUnit files are deliberately excluded from execution counts. Final checks resume
only after that source is complete.

Separate final fixture checks actually ran `LibraryPresentationTest` in UTC and
`America/Los_Angeles` (the original PDT zone): each passed all four tests, zero
failures/errors/skips. The second targeted invocation uses
the one-off task option `--rerun` to ensure an actual second process rather than
reuse a result across an undeclared environment variable. This is only for the
time-zone verification and is not used to establish the input fix.

Final fixture commands (same serialized wrapper as above):

- `TZ=UTC ... --offline :app:testDebugUnitTest --tests com.easysavevideo.app.library.LibraryPresentationTest --info`
- `TZ=America/Los_Angeles ... --offline :app:testDebugUnitTest --rerun --tests com.easysavevideo.app.library.LibraryPresentationTest --info`

No device, emulator, APK/AAB assembly or instrumentation was run. No dependency
or security gate was relaxed. Scoped machine-readable evidence is under
`artifacts/ui/jvm-contract-inputs-20261012/`.
