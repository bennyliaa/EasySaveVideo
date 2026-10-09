# Windows A/B implementation and evidence — 2026-10-09 continuation

This is a source/host checkpoint, not Android dynamic, physical-phone energy or public-source acceptance.
The original source snapshot was restored to this independent checkout. No original directory or uncommitted work was overwritten. Upstream label is `12f97d43c11fa19ea7dd08ccc0805a3030c71346`; its Git history was NOT recovered. Local import root is `4036e12d614436f907814f8702eee95773eac011`; previous checkpoint is `6b29c1d1abbf9bfcb77bec0e940ca160ed9134e3`. Original backup/bundles remain independent.

## Source provenance

`artifacts/local-windows-20261009/source-provenance.json` records fresh PASS checks for both fragments and the combined 26,522,932-byte archive:
- 001: 16,000,000 bytes, `d554b4f6e1861e620180834c4d8a33365262699be7042e6a7452fddbda1a2674`.
- 002: 10,522,932 bytes, `75c0062ad5bee568c1d3e60d451d15a33d75d2f11bee0eab83e349322b1be923`.
- Combined: `163f77e4c435b90b8a6fdc1dd8217b45b8a03e9e8c4c8224ae2272c1793b3481`.
The archive contained 7,397 entries / 6,498 regular files and 151 executable-mode entries. Those 151 original Unix modes were restored as metadata, not functional code changes. Library transport remains stopped after its earlier HTTP403; no retry or substitute transfer was used.

## Actual A and Windows containment

Official Scrapling 0.4.15 and its 22 fixed Windows wheels are installed in the separate `Scrapling_Service/.venv`. Official download hashes all matched; full wheel notices and exact lock remain beside the runtime. lxml is official 6.1.3, not the cloud private binary. Playwright 1.63.0 installed official Chromium Headless Shell 153.0.8010.12 / revision1243. Its executable is locally pinned; this local hash is not claimed to be an independently published checksum. Browser notices remain with the private runtime. Runtime/binary redistribution is not admitted and no such binary is in ESV source or APK.

Windows uses suspended-before-assignment CreateProcess, an unnamed Job Object, no breakaway permission, explicit three-handle inheritance allowlist and anonymous stdin/stdout/stderr pipes. Input/response are each capped32KiB, stderr4KiB and deadline/cancellation are monitored. Worker environment contains only explicit runtime necessities and a new scratch directory, no inherited HOME/proxy/credentials/startup hooks. Root and every descendant belong to the job. Cleanup requires root termination, closure of its handle, zero job accounting and writer-thread completion. Unproven cleanup quarantines the owning job and poisons the whole service; capacity cannot respawn.

Default limits:12s per-process user CPU,768MiB aggregate committed memory,24 active-process quota. Committed memory is not RSS. Windows CPU enforcement is periodically checked, not an exact wall clock. Failed process associations can temporarily remain in accounting until their handles close; do not claim an instantaneous sampled-count ceiling. The initial limit-test failures are retained. Subsequent tests correctly distinguish direct CPython from venv launchers, prove CPU termination, allocation rejection, extra-process rejection and cleanup. No OS security policy was changed. Windows does not claim Linux RLIMIT_FSIZE/NOFILE equivalence.

15 final Windows tests PASS:9 pipe/cancel/timeout/overflow/environment tests,4 limits tests,1 actual Scrapling static test and1 actual Scrapling+Chromium dynamic test. The dynamic test passed unchanged sandbox/TLS/ordinary automation/resource route checks; only its owned page/API were served, no media/public target. The earlier17 stdlib protocol/default-HTTP checks passed; historical57 real Linux service tests were not rerun.

Development shell is currently running on `127.0.0.1:8765` (managed terminal session95708). Its zero-admission POST probe returns UNSUPPORTED; no HTTP/CLI field enables sources. Source staging is a NEW `Scrapling_Service/source-windows-20261009`, verified by a file-hash receipt; the previous staging remains untouched. Real fixture capabilities were evaluated by constructor-owned harnesses, not enabled on the idle development endpoint.

## Actual B and dependency admission

Discovery calls only the generic registry/scheduler contract. Request/generation IDs, shared deadlines/request/body quotas, explicit remote consent, refusal, cancellation, stale-result fencing and physical drain ownership remain neutral. C may register by ID. App composition registers the process-wide B even if A is off. Default source admission is empty, so B cannot silently contact arbitrary pages. No public source is admitted by this work.

B now uses actual OkHttp4.12.0, Jsoup1.21.2 and Android WebView. OkHttp refuses private/numeric/DNS-rebound addresses in its production path, requires TLS, selects one permitted DNS address, disables cookies/proxies/redirects/retries/compression, limits HTML bodies and cancels calls. It stops an origin after403/401/429 or a finite challenge marker; failures never become video. Body quota consumption is conservatively RESERVED, not measured total network traffic. HTTP loopback is available only through constructor-owned fixture testing, not a Release endpoint toggle.

Jsoup has two compiled bounded primary-player shapes: explicit primary marker, or a sole controlled player occupying the main region/direct single div. Hidden/ambiguous/related-article shapes abstain. This is original compiled matching, not downloaded scripts/rules or an interpreter. V1 all-abstain host records are retained independently; V2 adds the finite structural strategy without changing corpus labels/bodies.

WebView renders only previously fetched inline content under synthetic `https://esv-verification.invalid/`, blocks all network/subresources/mixed content/file/content/storage access, grants no permissions, opens no windows/files, downloads nothing and exposes no JavaScript bridge. Only a fixed compiled DOM observation is evaluated. Timeout, uncompleted JS or cleanup exceptions retain quarantine; destroy alone is not proof of a recovered hung renderer. Actual Android WebView/lifecycle/Activity recreation execution is NOT_RUN. Warm renderer CPU/RAM must be measured on the eventual device.

The resolved Android runtime has exactly77 reviewed bytes: original74 plus Jsoup,OkHttp,Okio JVM3.6.0. Original dependency versions were preserved; only new dependency locks were updated. Square's old Kotlin bridge edges are excluded and existing reviewed Kotlin2.2.10 supplies stdlib. Official checksums, POM/module metadata and graph inventories are retained. Full MIT/Apache/MPL text, notice and exact preferred PSL source are accessible offline in the existing notice UI (<512KiB characters). PSL commit `ab0530155af1fe4b58194fe6792c4ec9731666bf` reproduces all9,499 ordinary and8 exception rule bytes. MPL covered materials remain MPL; ESV original code remains independently closed-source. No new explicit agreement/account/paid service was accepted.

## Same-source host records and limits

Both engines actually evaluated the SAME100 authored local fixtures (70 positive,20 negative,10 unknown); every body hash and independent label matches the frozen manifest. Public cases:0. A's adapter:90 INSUFFICIENT,10 REJECTED,0 positive evidence; only91 physical reads because first refusal stops its source. This reflects its strict marker/XHR admission gap, not a general Scrapling capability ranking. B JVM static V2:25 positive page evidence,65 UNKNOWN,10 DENIED;0 false positive page evidence,precision25/25,positive-page-evidence coverage25/70 (35.7%). A is NOT the ground truth. Neither has independently verified media identity, extractability, playback or rights on this corpus.

The100-page corpus's delayed scripts have no admitted A XHR rule. Android WebView was not run, so this is NOT a complete dynamic A/B comparison. The separate real Chromium smoke is not substituted for those20 cases. B's80 controlled mutations are recorded separately as actual Jsoup COMPONENT recovery, not full network/WebView recovery. All Windows/JVM timing fields are HOST values, never phone performance. CPU/RAM/energy/total network/phone end-to-end time are N/A; Windows and Android resource costs must not be directly ranked.

Final module counts are authoritative in summary.json (Discovery249,verification-client43,verification-local23). All reported executed JVM failures/errors/skips are0. Debug and Release Kotlin compile plus exact77 runtime admission PASS; source/Release bytecode/notice/SkinKit seven-getter guard PASS through the retained Windows long-path wrapper. Historical2309 JVM/full-App/device/lint acceptance is not claimed.

## Required next acceptance / approval

Minimum Android remains12/API31; versionCode remains2 because no APK/AAB was assembled or installed. No emulator was started or polled. No private media/microphone/recording operation occurred. Vault and M7-D remain excluded.

`DEBUG-LOOPBACK-PROPOSAL.md` specifies the NOT_ENABLED debug-only fixed-loopback transport exception and device-specific adb reverse. Approval is required before implementing that verifier transport exception; user notification and matching signer/higher versionCode are required before any5556 installation. Release verification stays public HTTPS443, no new trust/firewall/LAN policy. Existing main Manifest globally permits HTTP for other app/browser behavior; it was not changed. The minimal debug proposal needs no new Manifest exception. Actual Android dynamic parsing, renderer lifecycle/cancel/rebuild/source-switch acceptance, same-device resources/energy, full80 engine recovery and per-source public robots/access/rate admission remain pending. No safety review rejected any completed action.

Recovery component result:20/80 variants produced page evidence (class/id changes); wrapper/reorder, similar decoy and deleted-target variants abstained. Full-engine recovery remains pending. Contract transport-call/body reservations do not measure total headers/TLS or server target traffic; end-to-end network accounting needs a later admitted protocol/telemetry extension.

## Additional frozen-contract diagnosis

All100 actual A requests matched constructor-injected fixture rules; protocol identifiers and original body hashes validate. Observed90 INSUFFICIENT/10 REJECTED include no TIMEOUT/BUSY/UNAVAILABLE or identity failures. The zero-admission running endpoint was never used for this benchmark.

Reasons:25 static players lack required data-esv-primary marker;20 delayed-DOM pages have no initial video and no admitted XHR rule;15 metadata-only pages do not prove video;10 embedded frames are outside adapter coverage;20 negatives abstain;10 authored403 cases reject (first fetch then source stop). Actual Chromium corpus execution is0/20: dynamicAllowed=true alone does not authorize an XHR rule. This is a contract/coverage mismatch with the existing corpus, not a generic Scrapling capability ranking. No production gate was relaxed.

A paired REAL worker control uses the same frozen static body plus a separately authored in-memory marker-only variant: INSUFFICIENT versus PAGE_EVIDENCE under the same admission/identity/budget. Both completed cleanly; original body/hash and100 labels remain untouched. The two controls are not inserted into the100 corpus or scored as added successes. Prior dynamic smoke additionally contains the explicit primary/video ID, admitted API and observed page/API identity match which the20 corpus dynamic pages lack.

B independent truth:70 positive/20 negative/10 unknown, page evidence25 true positives and0 false positives; precision25/25, positive-page-evidence coverage25/70, known-label decision coverage25/90. Actual Jsoup declaration paths match the independently authored resource reference in25 cases; owned synthetic asset hash also matches the frozen manifest. This is NOT runtime media-content identity verification. Engine interface does not return a verified extracted resource; extraction/playback/rights remain unimplemented/unmeasured, not claimed as successful. Metadata-only pages never promoted.25/100 is not reported as success rate.

Artifacts frozen-a-contract-diagnosis.json, a-marker-control.json and b-independent-identity-diagnosis.json distinguish these scopes. Additional host regression:23 local-module JVM tests pass;315 scoped JVM total unchanged. One additional paired-control test (two real A workers) passes. No new source, network-security setting,5556 operation or debug transport exception was introduced.
