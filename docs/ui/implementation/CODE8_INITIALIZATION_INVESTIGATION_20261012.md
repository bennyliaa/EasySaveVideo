# code8 initialization investigation and bounded retest plan

Read-only investigation of the completed OPPO code7 report/logs and exact installed
APK. No source/test-directory/device modifications during investigation. Base
f3bf215 independently reviewed: no new definite P1/P2; device failures remain open.

Installed APK SHA2567b29555986d8a9132701d28548d78a7e8cc22add899f7986afe71abbe3e1a1d3.
All28 DEX payloads byte-identical to code7 input9efc346. Official SDK dexdump confirms
definitions of AndroidEditRuntime, Media3EditorEngine, AndroidEditStorage,
EditArtifactFiles, AndroidEditOutputVerifier, LibraryShareOwner/Provider,
Transformer, ExportResult, Crop, Presentation, ListenableFuture and
CallbackToFutureAdapter. No definite omitted-class evidence in these entry paths.
This does not prove every transitive linkage/native ABI or actual execution.

APK manifest contains the correct share Provider authority; exported=false and
grantUriPermissions=true. No evidence of a missing provider declaration. The
worker creates ACTION_SEND with video/mp4, EXTRA_STREAM, ClipData/read-only flag,
then the guarded main callback opens the chooser. The observed generic notice
belongs to worker preparation, not its distinct main startActivity failure text.
No exact constructor/preparation exception was retained.

Editor constructor creates Media3EditorEngine but its Transformer/control Handler
are lazy/used during probe/execute. It eagerly evaluates storage.edits for the
verifier method reference; that creates EditArtifactFiles directories and fsyncs
storage root. On constructor exception code7 catches Exception and installs null,
which hides the entry. Later recovery failure instead leaves an editor object
with BLOCKED state; by itself it does not explain the missing detail entry.
No determined codec/GPU incompatibility or initialization-order race. Potential
filesystem/canonical/lock/fsync causes remain candidates, not verified OEM facts.
F3 adds bounded STORAGE_UNAVAILABLE/RUNTIME_UNAVAILABLE versus READY status and
owner-constructor INITIALIZATION_FAILED; it does not fix a known underlying cause.

## Minimum next-package evidence; no personal media or credentials

Use only the already approved9/48-second QA recordings and existing test folder.
No second test system, private media scan, raw UI dump, recording/screenshot of
video content, logs of URLs/titles/filenames/URIs/tokens/account data or recipient
choices. No mic grant, uninstall/data clear or fake catalog insertion. Limit to
one editor admission and one single-share attempt initially; stop on failure.

Capture technical versionCode/source/signing hash and relative step/time only.
1. Cold startup then Library detail: entry enabled yes/no and the f3 initialization
   enum (INITIALIZING/READY/STORAGE_UNAVAILABLE/RUNTIME_UNAVAILABLE), without item
   name. If enabled, explicit edit-copy opens normal route: phase/error enum only.
   Original source/entitlement/resource/verification gates continue to apply.
2. No active preview/native job: single share of the known QA input. Capture only
   typed failure enum or boolean chooser-observed. Cancel chooser; no recipient
   selected/sending. Revoke: confirmed/unconfirmed enum, not a false cleanup pass.
3. Multi-select known QA inputs: open common confirmation from Share/Gallery/Files;
   record dialog-stable boolean under normal/large-font/small-viewport scenarios.
   This is device acceptance of the P1; source measurement tests cannot substitute.
   Actual transfers/cancel/revocation require the previously scoped explicit consent.
4. Restore Debug entitlement source to Unknown and settings to their prior state;
   retain originals. Do not treat a restart-only success as same-process recovery.

If these existing f3 enums still cannot localize failure, a later separately
reviewed Debug-only diagnostic needs only a fixed phase (EDIT_DIRECTORY_SETUP,
EDIT_ROOT_FSYNC, EDIT_RUNTIME_CONSTRUCT, SHARE_DIRECTORY_SETUP, SHARE_LOCK,
SHARE_RECOVERY, SHARE_COPY_VERIFY, SHARE_CHOOSER_BUILD, MAIN_CHOOSER_LAUNCH),
closed error category (IO/ERRNO/ARGUMENT/STATE/SECURITY/OTHER), numeric errno if
available, and booleans canonical-equals-absolute/leaf-is-symlink/owner-lock-held.
Never exception text, actual path, functional identifiers or bytes. Such finer
collector is NOT implemented in this candidate; do not claim those fields exist.
Use UI enums first, request the smallest justified additional probe after failure.

No additional definite implementation defect found from the existing evidence.
Proceed with reviewed f3+4c directory fixes as code8 Debug input, compatible signer
pending original route. No installation by this author; Android12/API31 minimum.

Final local verification:498 supported JVM tests PASS(221 app,277 library),
0 failures/errors/skips in that supported run. Initial all-requested run had
1 FileSystemException at symbolic-link fixture creation, before guard assertions.
The original OS reason is not retained in sanitized evidence. That one method
was excluded from the supported rerun and remains environment setup FAILED /
guard assertions NOT_RUN; no security/privilege change or alternate fixture.
Debug compile executed in initial code8 run; Release compile executed in final
run. Lint59W/8H/0E. Fresh Release classes: no Fake, Store-bound, no-op panel.
Evidence artifacts/ui/code8-final-20261012. No code8 device acceptance claim.
