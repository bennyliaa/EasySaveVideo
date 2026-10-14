# Central privacy entry: ordinary history and webpage cache

Date: 2026-10-14 UTC. Base: `ca3e22160fdaaf3d4df19d1f6772b5948a69aa6f`.
Original Product PR-20/21/25, section 10, UI settings PPT21–23 and contract
section 11 require distinct privacy/data-cleanup controls. Existing commands were
available only from Browser; this slice gives two of them a central entry.

## Scope

- Settings now has a stable Privacy category using the existing compact/detail
  and wide category/detail composition. It offers ordinary browsing-history
  clearing and webpage-cache clearing as separate, explicit actions.
- History uses the existing process-scoped AndroidHistoryRuntime and its
  requestClear/cancel/confirm protocol. No second store, writer, history list,
  retention policy or database is created. This page displays counts and status,
  never stored titles or addresses. The ordinary history policy remains 200
  entries/30 days.
- Cache reuses the existing SystemBrowserHost WebView operation through a typed
  REQUESTED/FAILED adapter. WebView.clearCache has no completion/freed-space
  callback, so the UI explicitly says requested, not verified cleanup success.
  The detached temporary WebView path retains its existing finally-release.
- Neither action is combined with cookies, site storage, Discovery history,
  provider progress, bookmarks or saved media. No actual user data was read or
  cleared in this development task.

## Command lifecycle

Both actions open their own confirmation with exact affected data and a Cancel
exit. History explains irreversibility. Cache explains the shared browser cache,
possible slower next loads and lack of completion/space reporting. Merely opening
the category or either prompt submits no clear operation.

A view-lifetime command owner consumes a prompt before invoking a port. Prompt
identity rejects repeated and stale callbacks. It checks the current foreground
Settings route/generation and live Activity/platform-dialog admission. Category
exit/disposal or navigation/background loss retires unsubmitted prompts; those
prompts never enter SavedState or automatically replay after recreation. Retry
and notice-dismiss actions use the same retired-owner guard.

History confirmations additionally retain the exact rendered controller-state
identity; a later list read/write invalidates the pending confirmation. The
existing controller owns accepted async work, including delayed delivery after
UI exit. Busy/slow/error/clear completion comes from that controller, not from
command acceptance. Failed writes retain the prior displayed rows, mark state
unavailable and offer the existing explicit reload. Explicit clear of an
unreadable store still requires confirmation, as in the original history panel.

Cache is a main-thread platform request. A narrow execution guard prevents a
reentrant second command. A native navigation/disposal prevents the old view from
publishing a result into a newer category. A failed request can be retried only
through a fresh confirmation. No timer, unattended retry or background job is
introduced.

## Tests and review

The new PrivacySettingsActionsTest has 16 cases against the production adapter
and real HistoryController using synthetic storage and separately delayed work
and delivery. It covers explicit confirmation, cancel, repeated click/confirm,
background/disposal/return, late same-value reload, slow operation, failed write,
read recovery, explicit corrupt-store reset, accepted work after UI exit, native
reentrance and separate cache/history effects.

Eight additional tests cover the native cache adapter results/failures,
category/back policy, paired EN/ZH resources/format arguments and source wiring
of guards/dialogs/ports and non-disclosure of history payload. These source
contracts are distinct from executable controller tests and are not Android UI
or WebView tests. Existing Settings navigation/scroll tests remain included.

Initial focused failures were fixture/contract errors: a non-UUID synthetic ID
was correctly rejected by HistoryPolicy, and a broad source substring check for
`.title` matched the theme's `titleTextStyle`. Fixtures now validate a fixed UUID
and assert successful initialization; the payload check names actual item fields.
The production implementation was not weakened to accommodate either failure.

Independent read-only review found no blocking issue in the final implementation
and directly inspected the two new test XML files. It did not run Android tests.

Final strict-offline aggregate exited 0: all 13 module test tasks freshly executed;
3,724 registered tests, 3,722 PASS, two existing private-corpus SKIP, zero failures
or errors. App: 2,434 passes. Both Kotlin variants compiled and both lint variants
passed with zero Error/Fatal. Debug retains 60 warnings/8 hints; Release retains
61 warnings/8 hints. The exact 90-artifact runtime gate, core/import boundaries,
Debug-only fake/demo exclusion, original license bytes and seven-getter ABI
checks passed. Aggregate: 102 tasks, 33 executed and 69 up-to-date; the claim of
fresh execution applies to all 13 test tasks, not unchanged build tasks.

Sanitized source hashes, task statuses, lint and test summaries are in the
adjacent `settings-privacy-20261014/` directory. Intermediate failed tests/logs
remain retained locally and are not counted as passes.

## Remaining acceptance and next work

No APK/AAB, signing, device installation, real browsing/cache operation or media
operation was run. Actual WebView/native cleanup, Android disk failure injection,
Compose rendering, rotation/back interactions, TalkBack/font200 and device timing
remain NOT_RUN. Prior frozen candidate APKs remain unchanged.

Central privacy is deliberately bounded to history/cache here. Cookie/session
controls and Discovery history/progress remain separate existing controls. The
next unblocked original requirement is the read-only entitlement explanation
page (original framework section4.7 / PPT24), using current snapshot state,
explicit unknown/unavailable state and planned module labels. It does not enable
payment or invent prices/SKUs. Central browser/destination access remains next
in the queue after that. See the original-requirement queue for gates and
later source scopes. Completing this slice is not product completion.
