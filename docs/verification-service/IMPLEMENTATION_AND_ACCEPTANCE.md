# Optional verification service development milestone

Baseline: `32747b7c9e1be81ac5df1cc4a9a1de81650c33c4` (2,184 JVM tests; 74 external Android runtime artifacts).
This continues Architecture update_1 sections 6/10/12/13 and Milestones update1's optional
`Search/API → validation → Core` direction. The user's later API31 minimum overrides API30 in
original references; those original files are unchanged.

## Scope and user control

- Original independent Python HTTP service adapter; original Android-neutral v1 client and Android
  strict TLS POST adapter. No Python, Scrapling, Node, Chromium or private evaluation wheel enters
  the Android dependency graph or source backup.
- No default endpoint. Android opts in separately from SearXNG, explicitly disclosing transmission
  of each complete candidate URL, possible query-parameter data, the operator's page read, and the
  optional JavaScript step. No separate query text, browser cookies or credentials are attached.
- Consent and endpoint are shared only within the current app process; process restart starts
  disabled. Every edit/reset fences attached owners immediately and changes generation. No edit
  initiates a search; old result pools/cursors become ineligible until explicit search.
- Host integration policy tries at most one candidate per provider response, with a 3-second
  sub-budget and at least 1 second reserved inside the enclosing Discovery deadline. This is a
  development bound, not a throughput or real-site accuracy claim. Untested, rejected, incomplete
  and unavailable candidates remain explicitly unverified, never verified-negative evidence.
- Verified fields come exclusively from a strictly validated service response. Unknown title,
  thumbnail or duration remains unknown. Source-reported fallback cannot create confirmed cards.
  Evidence says a page presents a video, not that a media resource plays, can be saved, or is licensed.
- Browser, Save and Record retain their independent paths. Existing priority/navigation/configuration
  cancellation excludes late service results. The service is not a startup requirement.

## Boundaries

The ordinary service admits no target sources and performs no target DNS, HTTP or browser work.
Only in-process test capabilities admit exact owned fixtures. HTTP input and ordinary invocation
cannot select that capability. The server does not grant itself permission to crawl public sites.
All target redirects and undeclared resources are denied; no media is downloaded.

The Android service endpoint is HTTPS port443 on a public DNS name at `/v1/verify`; all DNS answers
must be public, one peer is pinned, TLS hostname verification remains active, and ambient cookies,
authentication, proxies and decompression are absent. Request, response, deadlines and physical
concurrency are bounded; admission is held until physical I/O/cleanup exit. Default source search
remains SearXNG/Commons as configured; a public SearXNG endpoint remains unqualified.

See CONTRACT.md for exact schema and evidence semantics, service documentation for fixture rules,
and INDEPENDENT_REVIEW.md for findings and corrective tests. The current service's restricted
fixture rules are not a general extractor; Adaptive and public-source admission remain separate
future work even though actual upstream Adaptive was already evaluated in the prior prototype.

## License and runtime status

The actual upstream 0.4.15 evaluation previously passed 39 isolated checks. Its 12 public NASA L1
samples only found page evidence for 4 of 7 source-labelled video pages; all four lacked duration.
That result is not full M0 acceptance or a live Dynamic/XHR success claim.

Runtime evaluation uses the separately admitted private environment: its modified
`lxml 6.1.3+esv.eval1` excludes optional unlicensed isoschematron files. MPL/LGPL/native corresponding
source, relinking/build provenance and final redistribution notices remain unresolved shipping
obligations. The official Google browser's prior terms approval applies only to isolated fixture
tests. No runtime binary, wheel, browser, Node, .so, evaluation venv or credentials is in this source
milestone. Original service code may be backed up; this is not production license approval.

## Acceptance interpretation

Final evidence is written only by the frozen-input verifier and scoped scripts. Distinguish:
1. Kotlin/JVM protocol, transport, configuration and Discovery tests plus both Android compile/lint
   and exact74 artifact checks.
2. Actual Python/Scrapling fixture tests, process/time/failure cleanup and cross-language fixture proof.
3. Seven distribution-channel compile identities; no store purchase or package assembly.
4. Clean source and Git-bundle cold restores of this exact milestone, followed by private Library
   recovery checks.

Real device UI, Android network/native behavior, arbitrary/public dynamic pages, broad M0 accuracy,
production SSRF public admission, operator policy, authentication, deployment, cost, scaling,
redistribution and APK/AAB delivery remain NOT_RUN/unapproved. Vault and M7-D stay parked and were
not modified or retried. No device acceptance or complete two-layer production acceptance is implied.

Cross-language proof uses the real Kotlin protocol/client and Python HTTP service, with a test-only
HTTP destination adapter fixed to `127.0.0.1`. Logical `https://fixture.example.org` candidate identities
map only inside an in-process fixture capability to exact owned paths; they never cause public DNS
or network access. This does not constitute an Android production-TLS/device-to-service test. The
production strict HTTPS transport has separate injected-socket/DNS/cleanup JVM tests.

### Startup-pipe hardening follow-up

The original `33fab7d` development baseline and its completed cold gates are preserved. A late
manual review found blocking worker startup writes before supervision. The follow-up adds a
32,768-byte envelope cap and unbuffered/nonblocking pipe transfer under the same cancellation,
deadline and resource loop. The expanded 57-test service suite includes real small-pipe stalled-reader
regressions. The latest default verifier writes `evidence/runs/ipc-final/`; no Android behavior or
runtime dependencies changed in this follow-up. All full scoped gates are rerun before its new tag.
