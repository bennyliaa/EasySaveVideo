# Discovery media identity / Save receipt evidence audit

Baseline 0de400e14d08489971227c83618b751684399a25. Parent reports independent **source-only** review of that pending-alias repair passed with no finding; a rollback/pending retention integration test was suggested as nonblocking. This audit does not claim the reviewer executed tests and does not redo the already passed repair. The previous executed Discovery288/B34 results remain historical evidence for that baseline.

## Outcome

**No existing trustworthy provider-video-to-selected-media identity bridge was found.** Current adapter must continue supplying UNKNOWN; no SAVED/PLAYED history emission is implemented by this audit. The product feature remains blocked on an evidence contract, even though the audit deliverable is complete. No runtime behavior or identity policy is changed.

This matches docs/m8b2/IMPLEMENTATION_AND_ACCEPTANCE.md:8-26, docs/m8b2/NEXT_INDEPENDENT_SCOPE.md:5-12 and docs/m8b4/CURRENT_MILESTONE_STATUS.md:7. Product Requirements §5.4 (:177) distinguishes OPENED/PLAYED/SAVED facts; PR-06 (:99) and M8 milestone (:424-429) require truthful history/repeat behavior. Treating a page as the video would change that established semantic contract and is not authorized here.

## Actual evidence flow

| Stage and exact source | What existing evidence establishes | What it does not establish |
| --- | --- | --- |
| CommonsVideoSearchProvider.kt:34-40 | Namespaced provider result; Commons pageId becomes sourceVideoId; file-page metadata/rights are preserved | Saved bytes belong to that video's revision/variant; no media URL/save authority crosses this SPI |
| discovery/ResultNormalizer.kt:42-46 | url-v1 and source-v1 hashes for conservative deduplication; ResultPool retains aliases | Media identity, playback or reuse permission; these are hashes of identifiers, not an authenticated content relationship |
| SystemBrowserHost.kt:155-164 / DiscoveryOriginTracker.kt:96-137 | Native accepted Discovery navigation; same context/epoch/navigation; one selection and newly accepted task; short-lived object capability | Selecting one of possibly several page media candidates proves the Provider video's identity |
| PageSaveDetector.kt:14-36, :114 | DOM-derived media URL/shape and a new random UUID per candidate; current selection expires and checks page context | UUID is stable Provider identity; DOM attributes are authoritative identity evidence |
| AndroidSaveRuntime.kt:261-273 / save/SaveModels.kt:31-48 | User-selected media resource, task/request binding; sourceIdentity is the selected source URL's hash; durable copy retains origin/hash | sourceIdentity matches provider sourceVideoId or immutable video content; URL hash is not such proof |
| SaveCoordinator.kt:280-298 / SaveModels.kt:203-221 | Verified staged artifact and commit receipt; fresh file/index/content inspection; guarded successful publication | The valid saved artifact is the Discovery result's video |
| MediaTaskActivitySaveOutcome.kt:10-21 / MediaTaskActivitySaveLinks.kt:31-37 | Matching final SUCCEEDED Direct/Stream record, task, transfer/artifact/evidence/receipt IDs, size/digest; one-shot optional callback | COMMITTED implies VERIFIED identity; final status alone suffices |
| AndroidDiscoveryController.kt:483-506 / DiscoveryOriginTracker.kt:140-186 | Current target/page/result/expiry fences; storage guard; UNKNOWN blocks history callback and consumes once | Current Android adapter supplies any independently verified media identity (it explicitly supplies UNKNOWN) |

Source paths are relative to their modules: commons-provider/src/main/kotlin/com/easysavevideo/commons; discovery/src/main/kotlin/com/easysavevideo/discovery; app/src/main/kotlin/com/easysavevideo/app/browser, /save and /discovery; save/src/main/kotlin/com/easysavevideo/save. Exact audited file hashes are frozen in artifacts/media-identity-evidence-audit-20261011/source-manifest.json. No user's private media, URLs, contacts or device state was read.

Important distinctions: successful media persistence remains successful even when optional Discovery history is unavailable, stale or identity-unverified. UNKNOWN must not turn a true media success into failure; conversely a valid receipt must not turn an unknown Provider association into SAVED. This separation is already implemented by DiscoveryOriginSaveOutcome and the completion label, so no duplicate state or new enum is needed.

## Minimum conservative contract

The presently implementable contract is the existing **COMMITTED + UNKNOWN => IDENTITY_UNVERIFIED, no SAVED callback**. It is already active; adding another same-result API or positive test injecting VERIFIED would not implement a bridge.

A future real bridge requires all of these, without substituting page/DOM/URL/hash facts:

1. A trusted existing/native source of a Provider-specific video identity, with unambiguous revision/variant relation to the exact selected media. It must not be user/DOM/source-report supplied data elevated to authority. There is currently no such issuer or relation in the app.
2. Binding to the already captured result/profile/session/page revision/controller generation and native browser context/epoch/navigation, explicit selection and exactly one newly accepted task. No persisted/resumed token or retroactive task-list association.
3. A final verified COMMITTED receipt through the existing Save coordinator, not a boolean supplied by a webpage, UI row or task acceptance. Receipt size/digest prove artifact integrity only.
4. One-shot history delivery with expiry and before-commit fences. Missing/ambiguous/stale evidence keeps UNKNOWN; history failure cannot change media publication or retry it.

Items2-4 exist. Item1 is absent. Therefore no new class, configurable VERIFIED input, source-specific URL matcher, signed-looking token, JS bridge, remote lookup or persistent state is added. No original tests are relabelled as native proof.

## Required decision and remaining independent work

Before implementation, identify **which concrete trusted native/Provider identity issuer and mapping contract** can establish item1 for the chosen source. Which exact video revision/variant is meant, how the issued identity accompanies the selected transfer, and what authenticates the relationship must be specified. Current Commons pageId plus DOM candidate, A/B HTML_VIDEO, SearXNG URL and final receipt provide no such mapping. If no existing issuer qualifies, introducing source-specific evidence acquisition/fields requires a separate source admission and design decision; the current broad request to continue does not supply it.

An alternative “some media saved from this opened page” history event would change product semantics and repeat filtering, and would need an explicit product decision. It is **not** silently implemented as SAVED here. PLAYED needs an additional trustworthy playback signal and stays separate. Source permission, media reuse rights and video identity are three distinct facts.

The independent evidence audit and code/source-hash consistency check are complete. No additional pure JVM tests were run, because runtime code has not changed and existing tests already cover UNKNOWN/one-shot/stale/commit separation. This avoids manufacturing progress through duplicate fake-positive tests. Independent reviewers can examine this doc and its source manifest against 0de400e; there is no functional diff for a claimed bridge.

Android online results remain missing; no Android build/gate/device/public-source action or dependency/service/permission change was attempted. Production catalogue empty, Commons static development strategy test-only/default-disabled, Android12/API31/SkinKit/closed-source constraints and suspended Vault/M7-D remain intact. Old Windows115 regression failures are not cleared. The group stops here until a defensible identity contract is chosen.
