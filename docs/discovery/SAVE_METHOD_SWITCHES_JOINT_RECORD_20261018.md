# Independent method switches and joint/independent recording evaluation

This slice follows the user's 2026-10-18 clarification: ③ may be tested alone;
①②③ together are a JOINT test. In JOINT, ③ does not wait for failed primary methods.
The “only ③ succeeds” share is derived from pages with explicitly FAILED ① and ②
and SUCCEEDED ③. SKIPPED, BLOCKED and NOT_TESTED are never primary failures.

## Persistent operator settings

System Settings offers independent DIRECT/STREAM/RECORD switches, all initially on.
Missing or malformed legacy boolean keys use the enabled default. A successful
settings load is required before new-save admission. The application-lifetime owner
preserves accepted bits across stale activity snapshots; writes resolve those bits
inside the existing checked settings-write boundary. A failed persistence commit is
reported through the existing settings warning. No entitlement or safety gate is
replaced by these switches.

Switches affect new task admission and its pre-execution handoff, including pending
recording consent and segmented recording's queued native start. They are not polled
to terminate active downloads or recording. Existing Stop, Discard, pause/resume and
recovery processing retain their existing authority checks. Turning all three off
leaves browsing available. Native/ongoing entitlement policy remains separate from
new-task method preference checks.

## Explicit evaluation modes and source ownership

The debug evaluation panel defaults to JOINT and separately offers INDEPENDENT_RECORD.
The latter marks ①② SKIPPED when the page test starts and reports independent ③
shares/coverage separately. Legacy constructor default FALLBACK is retained solely
for old callers and recognizable old semantics; it is not the new UI default.
New reports use schemaVersion 2 and explicitly identify their mode.

Use an existing Wikimedia candidate and “Open as an evaluation page.” No new source
or external service is enabled. The actual browser request/view/document identity
creates the page capability; URL matching never assigns an evaluation task. Clicking
Save or Record on that explicitly enrolled page begins its unique-page denominator.
Opening the candidate alone does not. A cancelled setup therefore can leave a
truthful partial report. Ordinary pages/actions without an issued capability collect
no evaluation result.

For BASIC recording, the explicit browser Record action carries that capability into
recording setup. It is revalidated at destination admission/preparation, then transferred
once to the exact capture descriptor/session/generation before MediaProjection consent
can pause the activity. A stale page, expired capability, navigation, old activity or
finished session cannot supply a new binding. If a destination picker invalidates the
page before preparation, the stale evaluation start is refused; the report remains partial.
Configure the local destination first, then reopen the evaluation candidate to test it.
After preparation, the exact capture capability survives the expected permission UI;
worker completion uses that capability rather than a task-ID lookup alone.

The tester still selects permitted recording audio, grants the system capture prompt,
returns to the intended browser video as needed, and explicitly stops the tested
interval. No automatic playback, authorization grant, recording, or device operation
is performed by this slice.

## Success evidence and exclusions

Only BASIC ordinary recording is connected to evaluation. Private and segmented
recording are not evaluated by this binding. Production segmented recording remains
available subject to its settings/entitlements, but it must not be claimed as tested ③.

Success requires an observed native capture start, confirmed native release, the same
completed recording expectation, USER_STOP rather than a budget/system prefix, and a
verified final committed artifact/receipt/hash/size with exact operation and commit
identity. Unknown/interrupted internal audio is not counted successful. This proves
the committed user-stopped recording interval, not completeness of the source video
or that every captured frame depicts that candidate. Visual/content/device acceptance
is still required. Cancel, denied consent, unsupported preflight and recovery ambiguity
remain BLOCKED/unknown, never invented definitive failures. Default absent context
causes no evaluation record read or event.

## Validation boundary

Offline unit tests, source wiring contracts, JSON parser checks and debug/release
compilation are recorded in the slice receipt. AndroidTest compilation is preparation
only. Device capture, MediaProjection UI, playback/audio quality, OEM storage and real
Wikimedia recording acceptance remain UNEXECUTED. The earlier slice06 SAF checklist
still applies; its fixture assertion is updated to JSON schemaVersion 2.
