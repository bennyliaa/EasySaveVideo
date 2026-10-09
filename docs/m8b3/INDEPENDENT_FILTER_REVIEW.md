# Independent M8-B3 filter, draft and restore review

## Result

PASS within this ordinary review's bounded scope. The independent standalone host run completed on 2026-10-07 with **29 tests, zero failures**. No unresolved finding remains from this review of the frozen source.

- Frozen manifest: `evidence/frozen-review-source-manifest.json`
- Manifest SHA-256: `cd97d5a2f347489b1b138fd1dae065e81ab6c6d5ac9e4d7ab255e4883d143d4c`
- All **504** frozen inputs matched before and after execution.
- Exactly **eight** compiler/runtime/JUnit artifacts matched `docs/m7c/evidence/independent-final-toolchain-sha256.txt` by basename and SHA-256.
- All **47** compiled or separately inspected runner inputs remained unchanged.
- Results: `evidence/independent-filter-review-run/independent-results.log`
- Toolchain comparison: `evidence/independent-filter-review-run/independent-toolchain-match.log`
- Frozen-source check: `evidence/independent-filter-review-run/independent-filter-frozen-after.log`
- Complete input checks: `evidence/independent-filter-review-run/independent-compiled-source-sha256.txt` and `independent-source-unchanged.log`

The reviewer authored only the review document, independent tests and standalone runner. No product edits, Gradle invocation, commit, network request or device operation were performed by this reviewer.

## Corrected findings and final integration review

1. **Repeated search callbacks and stale whole-draft edits.** The initial captured Compose `canSearch`/draft gate could enqueue duplicate starts before recomposition. `AndroidDiscoveryController.kt:90–141` now checks current state and exact claims, uses compare-and-set for whole-draft updates, and sets `filterSearchPending` before enqueueing. The serialized command revalidates controller target, source revision, demo and intent generation. Its completion clears pending after its result publication is queued. Invalid or stale commands do not launch a replacement search.

2. **Safety confirmations surviving source changes.** `DiscoveryFilterClaim` binds the exact draft, profile, locale, filters, demo, session stamp, view revision and controller generation. The UI invokes `confirmFilterSafety`, which checks the actual controller state at the click. Explicit Commons/demo changes set an immediate busy flag and increment the intent generation before enqueueing (`AndroidDiscoveryController.kt:315–333`). Repeated old confirmation callbacks cannot change a different draft.

3. **Heuristic pending acknowledgement.** The former query-plus-filters acknowledgement could confuse an unrelated profile or locale with the requested search. That heuristic and draft-level pending submission were removed. The controller's acknowledged applied profile is now the source of completed state, while pure claim validation rejects same-query/same-filter substitutions with a different identity or locale.

4. **Rapid IME input.** Whole-draft CAS alone could discard the newest text event before recomposition. `DiscoveryFilterPresentationPolicy.kt:70–84` and `AndroidDiscoveryController.kt:96–101` now update only the requested text field on the current draft, under the unchanged applied-identity and target fences. Query, minimum-duration and maximum-duration callbacks use this path. Independent tests retain `ab` after captured callbacks `a` then `ab`, preserve changes to other fields, and keep search/safety exact-draft checks.

5. **Unavailable placeholder publication.** An unreadable controller may publish a default placeholder profile. `reconcile(..., appliedReadable = false)` preserves the existing draft, and `DiscoveryScreen.kt:54–63` avoids writing that placeholder back. Applied summaries explicitly state that the saved profile cannot be verified. Tests preserve the exact known filters and unsaved text across that state.

6. **Provider-management publication window.** Comparing only the IO-side management revision could unblock old main-thread state before the new registry was published. `filterControlsBusy` now compares the revision in the published UI snapshot with the live management revision (`AndroidDiscoveryController.kt:87–88`). Registry descriptors, target and source revision are published together (`:399–407`). Explicit source changes additionally retain the immediate busy/intent-generation fence until their serialized publication completes.

The asynchronous Android/Compose call sites above were statically reviewed. The independent executable tests exercise the actual pure policy, codec, normalizer, Commons bridge and coordinator with injected clocks, counters and in-memory stores; they do not simulate an Android Looper or claim device-level race acceptance.

## Admitted filters and restore behavior

- The selectable mapping is limited to the proven existing Commons bridge's exact descriptor ID and result namespace. An arbitrary descriptor ID is never assumed to be its result namespace. Demo choices require demo mode; the release `BuildDiscovery` still exposes no demo provider.
- Commons safe-search refusal occurs before transport creation. Tests count zero transport calls and zero timer schedules. A retained unrelated namespace also returns no results without fetching or silently broadening the search.
- Commons duration can remain unknown, and the bridge does not manufacture a publication date. Inclusive known-duration bounds and separately controlled unknown duration are tested. Decimal display/validation round-trips `Long.MAX_VALUE`, boundary values and 512 deterministic additional millisecond values without truncation; malformed text and overflow cannot be applied.
- Obsolete, unavailable and unknown namespace selections remain exact, including their stored spelling, until explicit removal. Case-insensitive selection does not rewrite the stored set. Empty selection is clearly described as all currently enabled sources, including zero when none are enabled.
- Applied codec bytes, query, locale, profile ID and all filters survive round-trip and zero-provider restore exactly. Draft text is not silently normalized. Existing bounded text entry and explicit submit trim remain; no new query rewrite was introduced by this slice. Existing Commons query canonicalization is unchanged.
- The draft is owned by the controller rather than the navigation-local composable. Ordinary navigation and sort/history ticks retain unsaved edits; explicit applied-identity or demo changes reconcile to the new applied profile. Reconstruction restores only persisted applied state, as described by the UI.
- Opening filter presentation and reading local history counts neither initialize nor demand a provider. The tests count zero runtime, transport and timer calls and preserve the checkpoint. The controller count path remains `refresh(readOnly = true)` (`:279–284`).
- Native video `SAVED` and `PLAYED` remain explicitly `UNKNOWN`; page provenance is not treated as proof of the selected video's save/play identity.

## Reproduction and limits

From the repository root, with the admitted cached toolchain and the frozen manifest present:

```sh
docs/m8b3/evidence/independent-filter-run.sh "$(pwd)/docs/m8b3/evidence/independent-filter-review-run"
```

`ESV_REVIEW_REPO`, `ESV_TOOLCHAIN_ROOT`, `ESV_REVIEW_CACHE` and `ESV_REVIEW_JAVA` support an independent restored workspace without changing the pinned artifact comparison. The runner uses the standalone Kotlin compiler and JUnit, not Gradle, and verifies frozen inputs on both sides of the run.

This result is not the parent's strict aggregate, cold-restored aggregate, Android UI/device acceptance, OS-signal integration or measured-power acceptance. Those results must be established separately by their owners. This review adds no providers, libraries, synthetic release configuration or authority to excluded candidate, substitute or Vault work. Any relevant source or runner correction requires a new freeze and rerun.
