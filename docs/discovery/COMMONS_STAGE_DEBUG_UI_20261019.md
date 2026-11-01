# Commons stage Debug controls — slice09

## Scope

The existing Discovery evaluation area contains a separate Debug-only Commons request-stage panel. The Save/Record evaluation controls and their behavior are unchanged. Release compiles an empty panel, has no diagnostic strings, and the controller and activity independently enforce `BuildConfig.DEBUG`. A release owner cannot allocate a recorder. Collection starts disabled; it has no persistence, logging, export, upload or additional network request.

Start only arms a one-use capture. The existing valid Search action, including an actual filter-panel Search submission, captures that exact opaque capability before its command is queued. The coordinator consumes it only for the corresponding real start. Editing a draft, changing filters/settings, restoring, initializing, paging or retrying cannot consume the arm. Enabling diagnostics alone neither starts nor restarts a search. An old queued command cannot adopt a later arm. Stop followed by Start creates a different capability; stale button callbacks cannot stop or reset the replacement because control revisions are checked.

Leaving Discovery, backgrounding, source/configuration fencing, coordinator replacement and controller close synchronously revoke collection. Panel disposal also requests a revision-checked stop. Stop seals and retains only bounded in-memory observations. Later callbacks cannot contaminate a replacement or a sealed trace. Returning to the page does not rearm diagnostics. Starting a new capture clears the prior trace.

## Preview interpretation

Refresh reads only the recorder's immutable bounded in-memory snapshot. It does not call the controller refresh/executor, database, provider, network or clock. The preview displays at most 32 rows, with active stages following completed samples. It gives retained/capacity, active-stage, displayed, pending, dropped-operation, dropped-sample, ignored-event and clock-anomaly counts, plus truncation and partial flags.

Rows contain only numeric local session/operation aliases, attempt index, closed stage/outcome/timing/error enums, actual elapsed nanoseconds when measured, and the validated numeric HTTP status when observed. No query, URL, hostname/IP, title, raw provider identity, exception text/class/stack, credential, response body/header or service contact is displayed.

Active stages display IN_PROGRESS and NOT_MEASURED, rather than fabricated elapsed time or NOT_OBSERVED. The unobserved-stage list refers to the entire retained trace, including its active stages. It does not describe every attempt separately. Dropped or UI-omitted rows can limit what is visible; absence cannot prove a phase never happened or establish a network fault. HTTP_EXCHANGE includes bounded body processing only when the original response policy reads it; denial/slowdown may stop after headers. It is not time to first byte. OPERATION records the runtime callback outcome, not proof that candidates were accepted into the pool or shown. Cancellation observation does not prove cleanup completion.

## Verification and subsequent device checklist

Source/resource contract tests and pure owner/presentation tests cover default-off/release refusal, arm without session, repeated/stale actions, revocation, snapshot read behavior, exact click-time capture wiring, localized resource parity, actual HTTP status display, live-stage labeling, preview bounds and truncation. These tests do not replace app compilation or real device execution.

Device UI acceptance: NOT EXECUTED in this source-only implementation task. A later authorized run should cover:

1. Default disabled, no request when Start or Refresh is clicked, then one explicit Search.
2. Filter Search submission versus unsent draft edits, settings, paging, restore and retry.
3. Stop retaining the visible bounded trace; Stop → Start with a stale queued search or button callback.
4. Navigation, Back, background/foreground, recreation, source changes and controller close while a stage is active.
5. A late callback after Stop/new capture cannot change the new preview.
6. Cache/denial/slowdown paths, active stages, absent phases, safe HTTP status, missing timing, bounded/truncated and incomplete traces.
7. Release has no panel or diagnostic strings and cannot arm the runtime owner.

No network result, Android build result, APK installation or emulator acceptance is claimed by this document.
