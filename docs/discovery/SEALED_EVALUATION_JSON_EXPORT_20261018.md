# Sealed evaluation report export (slice 05)

## Explicit operator flow

Only the debug evaluation panel can initiate this export. End the current evaluation run, refresh/view its sealed report, then explicitly choose Export sealed JSON report. The exact displayed report must still be the owner's current FINISHED report. Its JSON is synchronously serialized once into an immutable detached ASCII string before any worker or picker starts. The request never substitutes a later report's bytes.

A fresh ACTION_CREATE_DOCUMENT picker requests application/json, CATEGORY_OPENABLE, EXTRA_LOCAL_ONLY and a generated per-attempt filename. Temporary read/write grants support the nontruncating rw descriptor; no persistent grant, remembered folder, automatic retry, share action or network request is introduced. The returned current one-shot URI must be a content/document URI with returned and actual permissions, no query/fragment, tree URI or ambiguous ClipData. File paths are not accepted.

EXTRA_LOCAL_ONLY is a picker/provider hint, not proof that another application or storage provider will never synchronize a selected location. This feature makes no app network upload calls. The UI warns that a provider may synchronize and that cancellation/failure can leave an empty or partial file. It never deletes such a file automatically.

## Snapshot, owner and lifecycle isolation

SealedEvaluationExport reuses DiagnosticExportController's existing bounded preparation/write/timeout/cancel lifecycle and DiagnosticPickerDelivery's first-current-result rule. The DiagnosticSnapshot default remains 256 KiB for technical logs; only an explicit internal report payload selects the existing report's maximum 1 MiB bound. There are no new dependencies.

The report's exact object identity is checked at preparation, picker dispatch, each admitted I/O call and terminal completion after close. Changing/starting a report cancels its export. UI state notifications capture their frozen request at dispatch time, so delayed success from A cannot appear on report B. A process-wide export fence prevents a recreated Activity or concurrent owner admitting a second write before the prior worker and close are confirmed. Every picker registration has a new UUID key and is never restored.

Opening the system picker pauses the Activity; CHOOSING alone survives that pause and its result waits for resume. Backgrounding during preparation/writing cancels future I/O admissions. Leaving the panel or closing the Activity cancels and unregisters. Cancellation cannot roll back the single provider call already admitted; cleanup still runs and late completion cannot claim success for another owner. Deadline and uncertain-close behavior remain bounded by the reused controller.

## Redaction and local mapping

The existing DiscoverySaveEvaluationJson fixed-schema serializer emits overall and per-source counts/shares, evidence kind, FINISHED state and partial/unfinished coverage. Provider names are stable per-report source-N aliases. It emits no raw provider ID, private instance address, page URL/query/title, cookie, token, candidate ID or exception text.

The same sealed report's local preview now labels source-N next to the local provider identity for operator lookup. That mapping is never added to JSON. Preview remains limited to 32 provider rows; the bounded JSON includes every provider in the report. ③ stays unsupported/untested and is not automatically recorded as failure. SYNTHETIC_FIXTURE remains explicitly labeled; export does not turn a fixture into observed results or partial results into complete acceptance.

## No app-side overwrite and precise storage limits

The destination opens once with nontruncating rw mode. The same descriptor must be a regular file with an exactly known zero extent before the first byte. Lazy descriptor validation happens after the writer owns its closeable output, so rejection still reaches tracked cleanup. Unknown sizes, pipes, unsupported descriptors, nonempty documents, revoked grants and failed metadata/syscalls are failures, never assumed empty.

O_APPEND is enforced and checked on that descriptor. Before and after each chunk, and around flush/final close validation, the observed extent must equal the number of bytes this operation wrote. This avoids app-side overwriting if another writer appends between the size check and write; an observed extent change fails the export. No reopen-as-w/wt, truncate, replace, delete or overwrite fallback exists.

These checks are not exclusive creation or provider-level atomic publication. They cannot certify a same-size external rewrite, a change after the final check, or a provider's own semantics/sync. Failure may leave the bytes already appended. Final content mismatch with positively completed close is reported failed but releases the work fence; a close exception retains releaseUnconfirmed and blocks further exports. Success requires writing all frozen bytes, flushing, confirmed close and the original report still current.

## Verification and outstanding acceptance

JVM tests cover sealed/ACTIVE/stale/release-off reports; frozen bytes despite mutable caller DTO maps; duplicate/old/recreated pickers; owner changes during preparation, queued write and close; queued old-success delivery; cancellation/background/timeout; process-fence isolation; nonempty/unknown/invalid descriptors; observed append races; flush/close failures; close-confirmed content mismatch; byte caps and default diagnostic cap; and actual runtime/picker source hooks. A fixture written through the production export controller is independently parsed with Python's standard JSON parser. Existing diagnostic tests run in the full app suite.

This is offline JVM/source-integration verification, not device SAF/WebView/UI acceptance. Device tests must check local and OEM document providers, grants, unsupported/pipe descriptors, picker rotation/background/cancel, duplicate filenames and observable write failures. No device installation or actual user document export occurred in this development slice.

Next bounded work: assemble a concrete on-device acceptance checklist/owned fixture matrix for the now-connected evaluation and export path. Recording③ remains a separate ownership/completeness task; no new background collection or external provider activation is included.
