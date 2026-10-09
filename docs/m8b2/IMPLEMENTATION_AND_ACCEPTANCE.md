# M8-B2 — Origin capability and actual Save/Record priority

Baseline: `3ca09f768bf255b066e00b82209e7d6821c3aa7e` (M8-B1).
This is bounded app orchestration and ordinary host verification, not complete M8,
physical Android acceptance, a new extraction source, or a trustworthy playback bridge.

## Truthful event boundary

The existing Browser can prove which Discovery result's page a user opened. It can
also bind one explicit native Save selection on that unchanged page to one newly
accepted task, then independently inspect the existing Save coordinator's final
SUCCEEDED record, verified artifact, transfer, receipt, size and digest.

That evidence does **not** establish that an arbitrary media candidate on the page
is the same video as the provider's result identity. A page may contain several
videos. Therefore the current Android completion bridge explicitly supplies UNKNOWN
video identity. It never writes SAVED for the Discovery video, never changes a
skip-saved decision and never infers PLAYED. No URL guessing, extra page observers,
JS interfaces, provider metadata expansion or persisted tracking is introduced.

The generic pure API has a separate explicit VERIFIED identity input for a future
trustworthy bridge. Only VERIFIED plus genuinely COMMITTED media may reach the
history callback. Positive host fixtures inject this input; they do not establish
that a corresponding Android/native identity bridge exists. Real SAVED and PLAYED
emission remain unimplemented pending defensible native evidence.

Save task UI keeps media completion separate from optional Discovery attribution:
identity unknown, expired/stale association, or failed history persistence cannot
change the media result, remove its file, or rerun publication. Page provenance is
only short-lived memory and UI explanation, not a new durable page-history event.

## Origin fences and privacy

- Opaque in-process object capabilities, default 120-second non-renewable monotonic
  lifetime and hard maximum 12 records. Existing browser timeout ticks prune expired
  payloads; no new poller, worker, network demand, identifier or telemetry is added.
- Binding includes result/aliases, profile, Discovery session epoch, view/history
  revision, controller generation, browser tab/session, navigation epoch and native
  navigation generation. The selected candidate is bound to one newly accepted task.
- Redirects including same-address redirects, new navigation, repeated starts,
  tab selection/close, stop/error/renderer loss, incognito, expiry, controller/browser
  destruction and process restart invalidate or refuse the association.
- After an accepted page finish, even the first same-address history callback
  invalidates the proof. It might be a history mutation; no undocumented WebView
  ordering assumption is used to classify it as initial-document history.
- New navigation and the final optional history callback use one capability lock.
  The callback never waits for the UI. The store's final after-retention guard
  rechecks expiry/cancellation; persisted profile/stamp/page revision and complete
  page membership are checked again in the transaction. Old clear callbacks cannot
  reconstruct cleared Seen facts. B1 alias groups and independent monotonic event
  columns remain unchanged.
- No URL, title, search, cookie, path, token, exception message or media identity is
  added to logs. Diagnostic representations are redacted.

Conservative false negatives are intentional: long saves, recreation, navigation
and ambiguous callback sequences may preserve the media while omitting attribution.

## Actual application activity

Application-owned signals drive the existing Discovery priority API, not screen
selection or the count of all nonterminal journal rows. Signals include initialization
handoff, active execution, HLS preparation and selected prepared input, pending media
commands, basic/segment/preview recording owners, paused recording, service/native
ownership, segment recovery and known draining/restart fences.

The initialization owner remains active from construction through worker return
until the main thread installs continuing owners. Actual due WAITING_USER cleanup
is queued through main-thread priority admission before the control worker cleans
media. Empty/undue scans do not create priority transitions or renew Discovery's
bounded source-demand round. Existing short-lived in-memory source expiry remains.

Listener registration replays application state; destroying an Activity/screen only
removes that observer. A new Activity observes work still running. Media cleanup
and owner release, not leaving the screen, determine resumption. No codec, muxer,
Save publication, capture algorithm or recording timeline is changed.

Shared preview/thumbnail/editor native gates are sampled by the existing foreground
refresh or another activity signal. This slice does not add immediate subscriptions
for every unrelated shared-resource owner and does not claim those paths are all
instantaneously observed. Save/Record work itself has explicit owner coverage.

## Verification and review boundaries

Ordinary JVM fixtures execute the pure capability, transaction, priority, handoff,
expiry and association components. Independent injected tests also exercise the
existing SaveCoordinator's provisional commit, inspection failure, cancellation,
recovery and blocked cleanup. Source review checks Android/WebView/Compose wiring.
None runs Android SQLiteOpenHelper, real WebView, system capture, physical codecs,
power-loss storage or actual phone/tablet lifecycle behavior.

The final aggregate retains the historical SQL, DOM, media and ordinary Provider
host fixtures. B1's independent history test body is rerun against this B2 source
under a new B2 frozen manifest, rather than rewriting its historical freeze hashes.
Original M7-A/B1 scope scripts remain unchanged; the B2 scope gate permits only the
explicit origin/lifecycle surface and byte-checks protected media/provider/license/
SkinKit inputs. Final test counts, independent hashes, Git/tag and cold restoration
results are recorded after those gates complete, not assumed here.

## Final main-worktree results — 2026-10-07 UTC

- Strict offline/dependency verification, all **87** Gradle tasks rerun with Kotlin
  incremental compilation disabled: PASS. Both compile and lint variants passed.
- **1,698 JVM tests**, zero failures/errors/skips. Discovery **165**, app **814**;
  all other module totals unchanged. This adds **96** ordinary JVM tests to B1.
- Each lint variant: **0 errors, 14 warnings, 6 hints**, matching the prior baseline.
  Existing deprecation/SDK notices are retained; this is not warning-free.
- Exactly **74** admitted runtime artifacts in both variants. Current B2 scope
  verifies **777** protected files byte-for-byte and the fixed Commons main JAR's
  **127** classes. Media review separately checks **79** ordinary media algorithm
  sources unchanged outside explicitly scoped orchestration edits.
- Nine current source/admission gates, all inherited Discovery/history/Library/
  export/editor/recorded-segment host SQLite suites, **11** DOM checks, HLS EOF
  **81**, mux conservation **15**, local-reader and editor-muxer release checks pass.
  The historical ordinary Provider **327/327** and M8-A **12/12** host suites pass.
- Independent origin review: **34/34** (unchanged B1 fixture16 + new injected18).
  Independent media review: **47/47** (new injected16 + ordinary helper31). Those
  host counts are separate, overlapping test selections, not added to the JVM total.
- All **155** shared frozen inputs and all **8** standalone toolchain artifact hashes
  matched. Final manifest SHA-256:
  `4d149074b291ea448dc46e93d4a4f86d05fb3c080b368f3b7375dd3b9170cdaa`.
- Review corrections cover original-history callback ordering, rejecting unverified
  video identity, initialization handoff and actually due expiry-cleanup ownership.
  The first overlapping-source compile diagnostic and the media reviewer's initial
  incomplete-transfer fixture diagnostic remain separate from passing final evidence.

The final Git/tag, bundle/source hashes, exact tracked byte/mode/reference equality
and **two complete cold-restored aggregate reruns** are sealed in the external
recovery receipt. This document does not claim a cold pass before that receipt
exists. Video-SAVED/PLAYED emission, B3/M9 and actual Android device behavior remain
explicitly deferred as described above and in NEXT_INDEPENDENT_SCOPE.md.
