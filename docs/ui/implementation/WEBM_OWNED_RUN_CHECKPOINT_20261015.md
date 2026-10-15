# WebM owner-run implementation checkpoint — 2026-10-15 UTC

## Verified result
- Formal final full 13-module run: 480 suites, 5,296 items = 5,294 PASS + 2 existing SKIP; zero failures/errors. Exit 0, BUILD SUCCESSFUL in 3m57s; debug/release Kotlin compilation and lint completed.
- Lint: debug 76 warnings + 8 notes, release 77 warnings + 8 notes; zero errors.
- Stage A focused 80 PASS including 20 declaration-candidate tests. Actual pinned probe 124 PASS and paired input 42 PASS.
- Stage B final focused 154 PASS; actual pinned Direct callback/error host 47 PASS, paired input 42 PASS, same-session input EOF → owned muxer/seal output host 10 PASS, current MOV owned-file host 7 PASS. Focused results overlap full tests and are not added to the full total.
- Both implementation scopes independently reviewed. The first and second diagnostic snapshots are preserved separately; revision2 had one failing open-cancellation host case. That case and the lower-level poison/polling races are corrected and covered in the final run.

## Actual code added
Stage A records validated DefaultDuration and official parser SeekMap duration separately, compares both probe/loading passes and selects a finite silent CFR candidate without treating compressed sample callbacks as displayed frames. Detected audio remains present and is rejected by the silent staging policy, never silently removed.

Stage B uses the existing registered attempt as the private one-shot real Transformer run capability. It creates its actual session and calls the process-shared engine/runner, checks exact MediaItem identity through Media3's EditedMediaItem rebuilding, captures real observer/EOF completion while entry is active, stores only the runner's actual return and performs same-FD sealing after confirmed release. Freely constructed Direct specs remain unissued. Editor keeps its previous mapping and shared resource mechanisms.

Exact boundary failures and the chosen terminal failure are recorded before poison becomes visible to a polling runner. Cancellation/deadline precedence is selected once; later cleanup does not replace an earlier native/content failure. Future completion occurs outside the attempt lock. The new deterministic host cases pause real poison publication before the outer catch returns and let the real waiting-thread checkpoint observe the same primary object; no success outcome is injected.

## Evidence limits and remaining work
The 47-case host uses real pinned SequenceAssetLoader/PlayerListener wrapping and production listeners, with controlled syscall fixtures and reflected negative setup. It does not execute a successful Android-native owner run. The output host uses synthetic AVC samples and real Java Media3 muxing; it does not demonstrate VP8 decode/AVC encode. Android MediaCodec/GL, real pixels/FD/SQLite, successful native conversion and playback remain device gates.

Owner-private run code is now implemented; public selector/runtime admission and TRANSCODE→FINISH remain closed. The next slice consumes the same private SEALED actual result into typed conversion completion, existing OUTPUT_READY and proof/checkpoint CAS, together with safe REVIEW recovery and unpublished cancellation cleanup. Independent converted-output decode/adoption/commit and optional Opus audible-boundary verification remain required. Silent staging is not completion of the requested optional-audio WebM feature.

## Recovery and delivery
Source/evidence archives are local, member-hash verified. No GitHub synchronization, new APK or device acceptance is reported. Previous OUTPUT recovery archives remain intact.
