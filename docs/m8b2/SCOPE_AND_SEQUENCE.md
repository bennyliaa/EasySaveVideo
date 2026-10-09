# M8-B2 — Reliable Discovery origin events and media-task priority

Baseline: `3ca09f768bf255b066e00b82209e7d6821c3aa7e` (M8-B1).

This development slice binds an opened Discovery result to a bounded, short-lived,
process-local native-browser origin capability. Only a newly selected Save task,
subsequently verified and committed by the existing Save coordinator, can consume
that capability. A separate trustworthy video identity match is required before
SAVED could be recorded. The present native page-candidate bridge has no such match:
SAVED and PLAYED both remain unknown. Page provenance is not video identity, and
neither candidate DOM inspection nor WebView fullscreen proves playback identity.

Navigation, redirects (including same-address redirects), tab selection/close,
repeated starts, observed history mutations, incognito, process/controller changes,
expiry and persisted history revision changes reject old associations. A separate
history outcome never changes committed media or retries publication. The existing
B1 alias-group, independent monotonic columns, transaction capacity rollback and
post-retention guard remain authoritative.

Application-owned Save/Record activity drives Discovery's existing priority API.
Actual preparation, pending commands, capture owner, paused capture, finalization
and resource draining are distinct from screen selection and nonterminal journal
counts. UI subscription destruction detaches only the observer; media work is not
cancelled or declared idle by leaving a screen.

## Acceptance sequence

1. Pure origin/state, native app orchestration and truthful UI outcomes; ordinary
   JVM tests and both Kotlin compilation variants.
2. Recoverable compilable WIP commit with full Git bundle and tracked-source backup.
3. Freeze changed and protected relevant source; independent ordinary source and
   injected-host regression review, correcting findings before the final freeze.
4. Strict offline/nonincremental whole JVM aggregate, both lint variants, exact74,
   unchanged ordinary Provider/SQL/DOM/media suites and new B2 regression scope.
5. Final commit/tag, full source and Git bundle; two separate full cold restorations
   and strict aggregate reruns, exact source/refs/mode verification and receipt.

The new B2 scope gate preserves the historical M8-B1 and M7-A scope scripts byte
for byte. Their historical whole-directory freeze is not run as a current-source
claim: it predates this explicitly authorized app orchestration change. B2 verifies
all original Commons/provider, media algorithms, storage, dependency, license and
SkinKit inputs unchanged and permits only enumerated event/priority app changes.

## Exclusions

No blocked M7-D candidate/interpreter inspection, review, replacement or probe;
no Vault work; no added provider/site/dependency/GPL code; fixed Commons schema1
and zero-provider startup remain supported. No production root/service/account,
payment, deployment, APK/AAB, emulator/device installation or automatic SDK/SkinKit
upgrade. Host injections and source review are not Android/native device results.
B3/M9 are handed off as separate future slices, not automatically started.
