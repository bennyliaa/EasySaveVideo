# Continuous BASIC screen recording integration — 2026-10-14
Status: source integrated and independently reviewed. Final thirteen-module regression passed 5230 tests + 2 existing skips, zero failures/errors, with both Kotlin variants and both lint tasks successful (3m50s, exit 0).

## User-visible behavior implemented
- One runtime-owned BASIC recording survives normal page input, seeking, navigation, popups, route changes and Activity replacement; ordinary and private initiation use the same independent projection owner.
- Browser input is not locked. Submitted capture no longer depends on the starting WebView/document/private session.
- Global exact-session controls remain available through recording, stopping, verification and committing. Stop keeps valid output. Discard signals the existing coordinator immediately and persists cancellation, including after native capture has drained. A publication that already won is retained.
- Android 34+ uses the system user's chosen sharing scope. Start disclosures describe other visible information and the accepted Android 12 Recents-thumbnail residual risk. Existing bounded duration/output limits remain disclosed.
- BASIC resizing keeps one VirtualDisplay, encoder canvas and output surface. Android 34 callback sizes retain selected-app semantics; earlier platforms use the existing full-display geometry path. Region/preview behavior is preserved.
- Actual system revocation, real resource failures, process termination and existing bounded limits are exceptional termination conditions. BASIC no longer synthesizes a stop from the screen-off broadcast or keyguard/interactive polling. Android 15 QPR1+ may revoke projection on lock through the actual system callback; older devices may output blank/frozen/protected content. Region/preview/segment screen-state policy is unchanged. A stop request revokes pixel authorization before native cleanup, while actual release remains separately proven.
- Uncertain native release stays BLOCKED. It cannot be dismissed as completed or replaced by a fresh recording.

## Review and executable evidence
Main eight-file integration, runtime seventeen-file layer, window/UI/Host twenty-seven files, and geometry four files have independent code reviews. Findings fixed before validation include submitted-state ambiguity, callback retention, late Discard publication races, sticky window-restoration failure and best-effort cleanup of all independent dialogs.
- Dual debug/release Kotlin compilation succeeded.
- Focused app: 265/265; real SaveCoordinator recording suite: 31/31; unchanged recording module: 127/127.
- SaveCoordinator tests exercise blocked verification, commit and final inspection, immediate/durable cancellation, KEEP, and publication winning before a late Discard.
- Window's eleven suites: 142/142 in the first full run.
- Initial full run: 5230 items with seven outdated source/resource contracts failing; their minimal updates were independently reviewed. They cover global controls, unchanged browser layout, new exact-session notification Discard and immediate signal-before-queue cancellation. The first corrected full rerun passed 5228 tests + 2 existing skips, both lint variants with zero errors. The separately reviewed screen-off correction then passed a new final run: 476 suites / 5232 items / 5230 PASS / 2 existing SKIP / zero failures or errors. Debug SARIF has 76 warnings and 8 notes; release has 77 warnings and 8 notes; neither has errors. Boundary/license/ABI checks passed. The 60-file final implementation manifest was rechecked against source.

## Remaining acceptance
JVM production state-machine tests, source contracts and successful Kotlin builds do not establish actual Android screen content, API31 scaling, projection resizing, Activity recreation, OEM dialogs, MediaCodec/audio release, real IME behavior or device privacy cleanup.
The three existing private-window platform gates (retained input connections, native presentations, Android device validation) remain pending. The obsolete product assumptions of whole-page input locking, scene-triggered stop and Android33-only capture were removed; platform acceptance was not fabricated.
No device run, new APK delivery or remote backup is included. GitHub synchronization remains paused.

## Next work
Freeze exact source/evidence packages, then continue the existing WebM attempt's owned OUTPUT and actual muxer release/hash loop. No new owner system or schema is needed.
