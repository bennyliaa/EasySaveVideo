# BASIC recording ③ evaluation binding (slice 07)

This optional, bounded, memory-only app adapter connects an explicitly selected evaluation page to the existing ordinary BASIC recording owner. It does not collect ordinary production recordings, infer a page from a URL, change capture permission, or replace media success UI. Private recording, preview and segmented recording are not enrolled. No device result or real-world success rate is claimed here.

## Exact admission, consent and completion

1. An explicit debug evaluation action arms the same `OrdinaryDestinationAdmission` object used to prepare BASIC recording. It supplies an active evaluation session and the current exact page capability.
2. `prepared` validates that capability once before the system-consent UI opens, checks the admission's session ID, and freezes the exact newly allocated `CaptureDescriptor` object and generation. It drops the page callback immediately. An equal copied admission cannot take ownership. Expected activity pause during consent does not revoke this already-prepared recording owner.
3. Only fresh coordinator acceptance, with a clean QUEUED revision-0 recording record, can issue the session's SAVE3 attempt. Existing or recovered tasks cannot enroll. The accepted commit ID and operation ID are frozen. Native callbacks and completion additionally require the original opaque handle and exact prepared descriptor object; persisted descriptors are compared by their full value because storage legitimately decodes them.
4. The actual `AndroidRecordSavePort` carries an optional binding into its one native lane. The witness observes real mux-generated RECORDING progress, native-worker return with confirmed resource release, and the actual completed output after file-size/publication checks. Controller/UI statuses cannot supply those facts.
5. The real BASIC worker's finally path reads its final coordinator journal record only when an issued live evaluation binding exists. Remove-before-read makes the result one-shot. Finish/close, wrong descriptors, duplicate callbacks, old generations and old handles across equal-ID runs cannot affect another or sealed evaluation. An evaluation observer never admits or rejects recording, Stop, Discard, output cleanup or recovery.

## Truthful classification

VERIFIED_COMMIT requires final SUCCEEDED/CLEANUP without an error; exact task/session/generation/commit/operation; matching artifact and commit receipt; matching bytes and SHA-256; and independently verified full output against the exact native `RecordingArtifactExpectation`. This includes track/sample counts, timestamps, decoded terminal video EOS, resume-boundary proof and actual resource release. The accepted native output and final-record expectation must be equal. Legacy missing boundary proofs, mutable invalidated verifier sets and missing expected byte totals remain UNKNOWN.

Success is scoped to the complete user-stopped recorded interval. It does not prove that every frame of the original source video was captured, that the video played to its original end, or that the user selected the intended projection source. Verified system/budget/error-stop prefixes remain BLOCKED rather than successful evaluation. Interrupted internal audio and internal-audio silence/unknown are conservatively BLOCKED; explicitly requested silent recording is eligible.

A DEFINITIVE_FAILURE requires both witnessed actual capture and confirmed native release, a final FAILED record without any provisional receipt, EXECUTING/VERIFYING phase, and one of INVALID_MEDIA, TIMESTAMP_INVALID, AUDIO_FAILED, ENCODER_FAILED or FINALIZATION_FAILED. Preflight rejection is not a recording failure. Cancellation, consent/permission refusal, unsupported profile, unavailable capture, insufficient storage, limits and too-short/empty operator capture are BLOCKED. Recovery, ambiguous publication, unconfirmed release, unknown errors and timeout ambiguity remain UNKNOWN. UNKNOWN leaves its issued attempt unfinished and the report partial.

## Evaluation mode separation

The binder calls only `session.beginMethod(SAVE3)` and imposes no fallback prerequisite. JOINT allows ③ regardless of ①/② results; only the derived fallback subset requires two explicit primary failures. INDEPENDENT_RECORD skips ①/②, permits ③ directly and uses the separate independent-record count/denominator. It cannot fabricate primary failures or populate fallback counts. Legacy FALLBACK remains a separate compatibility policy owned by the session.

## Verification and remaining acceptance

New JVM tests cover exact/copy/ABA ownership, consent-pause handoff, retired/closed sessions, bounded storage, no-context/no-read, real native witness requirements, strict final output proof, legacy/mutable proof rejection, explicit terminal classifications, source-level runtime calls, independent and joint statistics and unsupported segmented hooks. They are synthetic contract tests, not Android recording evidence.

Still required separately: real emulator BASIC consent and stop/discard tests with preserved app data; verified final MP4/audio output; callback ordering and final journal evidence; genuine observed evaluation JSON; and independent preview/segmented output completeness design before extending coverage. No network, APK build/install, upload or device operation is part of this source change.
