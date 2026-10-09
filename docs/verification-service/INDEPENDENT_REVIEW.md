# Independent review record

Read-only review is separate from implementation and Gradle ownership. Baseline remains 32747b7.

## Findings corrected before final freeze

- Optional validation returned null and dropped candidates. An explicit optional pipeline mode now
  retains failures/unattempted candidates; legacy callers retain their prior behavior. One attempted
  candidate consumes the page budget even on failure. Outer cancellation/deadline/interruption is
  still terminal and cannot publish a stale pending or confirmed result.
- Endpoint edits could admit a newly attached owner during the initial fence. Reentrant subscriptions
  are now fenced, changed delivery fences again, and volatile busy/current publication blocks an
  old configuration being initialized under a new intent. Initialization pins both source and
  verification generations and rechecks after publishing its coordinator reference.
- Failed owner fences could throw through settings actions. They now produce a disabled terminal
  generation, while remaining owners receive a consistent state. Detachment excludes later delivery.
- Confirmed evidence excluded by duration/source filters could reappear as unknown pending metadata.
  Confirmed-but-filtered evidence is now excluded rather than downgraded to bypass that filter.
- A selected SearXNG source filter rejected valid service results with no namespace. Optional pipeline
  mapping preserves the actual requesting provider namespace without inventing a source video ID.
- Explicit empty/zero-padded port spellings were ambiguous. Strict URL policy checks exact authority.
- Transport-thrown InterruptedException could clear the interrupt bit. The client now restores it
  and propagates Discovery cancellation; no remote outcome can forge enclosing-operation success.

Strict protocol/POST review covers UTF-8/surrogates, duplicate/unknown keys, integer tokens, identities,
512 UTF-16 titles, dynamic opt-in, response bounds, pinned public peers/TLS, redirect rejection,
no ambient authentication/cookies/proxy, physical admission and late response/cleanup handling.

Python process ownership, browser routing and fixture evidence received a separate read-only review.
The final test evidence identifies what actually ran. This document does not confer production,
public crawling, redistribution, legal or device acceptance.

## Python corrections and explicit limit

Read-only review identified and requested fixes for process-ID reuse during worker cleanup,
whitespace-only media sources, exact dynamic opt-in response enforcement, public-thumbnail policy
alignment and primary-video semantics. The worker uses immutable process identities with pidfd signaling rather than an unverified process
group. Cleanup waits for two fully stopped snapshots, drains descendants before the worker root,
and permanently withholds capacity when cleanup is unproven or throws. PID reuse, fork-during-stop
and cleanup-exception tests cover the corrections.
Browser resources are default-denied and replayed only from bounded owned fixture reads. Aggregate
RSS and descendant-count budgets are watchdog observations, not kernel-enforced container/cgroup
containment. This limitation blocks general production/public-source admission, not authorized
isolated fixture evaluation.

## Final read-only conclusion

The independent reviewer reported no open source-review blocker for this default-disabled,
source-only owned-fixture milestone on 2026-10-08, after re-reading the final client/wiring and
service cleanup. Reviewed service.py SHA-256:
`f595c0685e5df04e83e22d1079000011ac0b577dd049319d79f967b37aa6b727`.
The reviewer did not run Gradle or runtime tests; build-owner evidence remains a separate gate.

The first full build-owner attempt passed the Android/JVM/channel stages but failed two clean Python
subprocess import checks because the test wrapper did not export its local module root. The wrapper
now sets that explicit test-only PYTHONPATH; the service worker still strips inherited Python paths.
The incomplete attempt is preserved separately and is not counted as final acceptance. Final gates
rerun against the corrected frozen source.

## Late worker-input backpressure finding

After the initial 33fab7d development tag and its main/bundle/source gates passed, a further manual
read found blocking startup-pipe writes before deadline/cancellation supervision. The independent
reviewer confirmed that an unresponsive child with pipe backpressure could strand a fixture handler.
The earlier source conclusion is superseded on this specific point. The 33fab7d tag and its results
remain preserved; an IPC-hardening follow-up requires new source review and fresh frozen gates.

The IPC follow-up now caps serialization at 32,768 bytes before spawning and uses unbuffered,
nonblocking pipes. The existing supervised loop covers partial writes, EINTR/EAGAIN, timeout,
cancellation and shutdown; EOF is sent only after the complete envelope, and partial-input child
output is never accepted. Pinned cleanup/poisoning remains mandatory on every abort.

Independent re-review found no open source blocker in the IPC fix. Reviewed service.py SHA-256:
`ea7d12017473836860318117a2a25e8932943bb606460ee70037aa214eb13397`.
Nine additional regressions use real 4 KiB pipes/non-reading children and exercise deadline,
cancellation, shutdown, cleanup admission, EPIPE, zero progress, oversized input and short writes.
The new frozen gate expects 57 actual-service tests. The earlier main/bundle/source run's 48-test
results describe the preserved initial tag only; `evidence/runs/ipc-final/` is the follow-up main run.
