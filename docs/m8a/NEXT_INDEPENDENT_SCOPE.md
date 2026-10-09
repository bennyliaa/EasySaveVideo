# Independent follow-on development options

These are proposed bounded follow-on slices, not work implemented or accepted by
M8-A. The parent owner chooses the next slice. They require no blocked rule-runtime
or Vault admission, new dependency, production service, payment or ESV installation.

## M8-B1 — Bounded long-term history and local controls

Implement a documented capacity/retention policy for profiles, Seen identity/alias
facts and provider progress, with deterministic transactional pruning. Protect
current materialized data and make any possibility of old results reappearing clear.
Add current-profile/all-history controls and per-provider progress reset, keeping
history, progress, media files and Vault independent. Preserve monotonic independent
SHOWN/OPENED/PLAYED/SAVED events; never infer an event from retention or migration.

Closed-loop local acceptance: genuine old SQLite state migration; bounded-size
property tests; alias/history consistency; transaction failure and recovery;
clearing one profile/provider leaves others intact; no media deletion; truthful UI
outcomes and zero extra provider requests from statistics/status reads. No live
service or blocked interpreter is needed. Actual Android disk/power-loss behavior
remains later device acceptance.

## M8-B2 — Reliable event correlation and task priority

The current app emits SHOWN from measured grid visibility and OPENED on navigation.
No actual call to AndroidDiscoveryController.setTaskPriority exists in the current
main source. Add an explicit short-lived origin token when opening a discovery
result, keyed to identity/profile/session and navigation generation. Connect SAVED
only to a verified committed Save outcome, never request submission. Emit PLAYED
only if an existing trusted player/browser bridge supplies defensible playback
identity; otherwise keep it unknown. Redirect, new navigation, close, incognito,
late callbacks and unrelated saves must invalidate or refuse the association.

Wire real active Save/Record state into the existing priority API through the app
orchestration layer, including work continuing after leaving its screen. Do not
change codecs/muxing or infer active work from which screen is shown.

Closed-loop local acceptance: fake event-source tests for genuine completion,
failed/cancelled/late/unrelated operations, navigation races, no double marking,
incognito persistence refusal and discovery suspension/resume without unbounded
retries. Existing Save/Record suites and strict compile/lint must remain green.
Real playback signal fidelity and physical capture still require device acceptance.

## M8-B3 — Power policy and available filters

Build a pure bounded policy for battery/thermal/network inputs before adding any
Android adapter. Existing foreground/visibility/round/watermark limits remain hard
upper bounds; input changes may only tighten them. No background worker or permanent
poller. Add explicit source-namespace choices from already admitted providers and
explain unsupported/unknown metadata; never broaden source queries silently.

Closed-loop local acceptance: table-driven and transition tests for budget changes,
Save/Record priority, foreground restoration, rapid input fluctuations and no-demand
status reads; unknown input defaults conservatively. Tests use injected signals and
current compiled providers. Permission/OS wiring and measured energy consumption
must be scoped separately; host policy tests do not establish device savings.

## M9-A — Existing-provider reliability regression matrix

Extend ordinary fault injection around the existing fixed Commons metadata source,
its transport/cache and coordinator: unreachable/slow source, malformed/oversized
response, partial metadata, cancellation at commit, stale callback, exhausted and
incompatible cursor, storage unavailable, one source failure and all sources absent.
Assert Browser/Save route availability independently of Discovery registration.
Exercise process-style close/recreate with the existing host persistence adapters.

Closed-loop local acceptance: deterministic bounded fault matrix, unchanged licensed
runtime set, all current SQL/DOM/media/Provider regressions, frozen independent review
and full source/bundle cold restore. This is not Scrapling/Chromium/rule-runtime stress,
physical Android compatibility, or a substitute for either blocked review.

## Still excluded

M7-D candidate integration or blocked interpreter tests; Vault cryptography/review;
new extraction providers, SearXNG/Scrapling services, GPL NewPipe; real billing/accounts;
production roots/endpoints; release or store submission; APK/AAB/device work; automatic
SkinKit SDK9 upgrade. Those need their own cleared scope and applicable authorization.
