# Code12 request-stage audit

Device evidence supplied by task owner: three attempts; no request write, HTTP or JSON stage. Attempt 1 DNS 2.48 ms, TCP 3172.84 ms, TLS 1827.55 ms IO_FAILURE. Attempt 2 DNS 4.64 ms, TCP 1166.47 ms, TLS incomplete SERVICE_FAILURE. Attempt 3 DNS 4.49 ms, TCP incomplete DEADLINE_EXCEEDED. 14 records, not truncated, incomplete=true, ignored=10. No raw traffic or exception was supplied.

## Confirmed implementation

SystemCommonsTransport:163–165 starts a shared TCP+TLS budget after DNS; at most 5000 ms and limited by remaining overall time. TLS receives the remainder (:183–187). Thus attempt 1's TCP+TLS sum 5000.39 ms is consistent with the local watchdog boundary. This correlation does not establish the remote cause or prove the timer won that particular race.

TLS setup: layered socket is created with exact commons.wikimedia.org host; HTTPS endpoint identification, exact SNI, client mode and HTTP/1.1 ALPN are set before handshake (:173–188). Instance-owned TLS SSLContext uses platform trust and no client keys (:345–353). Default enabled TLS protocol/cipher configuration is not explicitly weakened. Connected peer and public-address policy are checked repeatedly. These source checks and fake-socket tests do not validate the device trust store, negotiated protocol, SNI wire bytes or certificate chain.

DNS returns an address array; all answers must be public and only answers.first() is attempted (:157–162). There is no address-family fallback/Happy Eyeballs inside an exchange. We cannot identify the selected family/address from the deliberately redacted trace and should not infer it. Changing address selection would be a separate transport behavior change.

## Proven observation gap

The shared connection timer calls stop(SERVICE_ERROR), without a distinct budget observation. It closes the raw socket. A subsequent handshake IOException can be reported as TLS IO_FAILURE; if operation retirement wins first, TLS is sealed INCOMPLETE and the late exception is rejected. Existing trace cannot distinguish an observed local budget expiration from generic SERVICE_FAILURE. Proposed correction is diagnostic attribution only, preserving existing timer lengths, business error/retry mapping and all TLS/security checks.

## ignored / incomplete interpretation

CommonsStageDiagnosticsRecorder increments ignored for duplicate stage begin/event/status, completion whose span is no longer active, and same-session callbacks after operation removal or a cancellation/deadline fence. Old-generation callbacks do not mutate even these counters (:171–182). These rejected callbacks are intentionally excluded from current evidence. A count of 10 alone cannot identify which reasons occurred or prove all were harmless cleanup; it is not the droppedSamples counter. incomplete=true follows incomplete stage outcomes or pending operations/capacity/clock anomalies (:49–51); with known incomplete TLS/TCP it does not imply truncation. Absence of HTTP/JSON observations is consistent with no completed handshake in the retained evidence and does not establish an HTTP status or response.

No production TLS fix is established. Device network cause remains unknown. No network, APK or device action is part of this audit.

## Offline characterization before correction

The complete SystemCommonsTransportTest class passed 34 tests, zero failures/errors/skips, in characterization-02. The new stageDiagnosticsCharacterizeSharedConnectBudgetAsTlsIoFailureWithoutBudgetSignal holds fake raw-socket closure, fires the existing shared connection watchdog, verifies the unchanged SERVICE_ERROR result, then releases closure and observes TLS IO_FAILURE and provider IO_FAILURE without a budget-specific signal. This establishes a diagnostic attribution gap under controlled local scheduling. It does not establish which event won on the physical device.

## Bounded correction scope

An explicit closed-vocabulary CONNECT_BUDGET_SIGNAL / CONNECT_BUDGET_EXCEEDED is emitted for actual timer or synchronous connection-budget exhaustion, only when that termination wins. It carries no fabricated elapsed duration. Active phases become INCOMPLETE rather than attributing cleanup-induced I/O to a remote TLS failure; earlier successful phases remain intact. A late cancelled timer, prior cancellation/overall deadline or earlier terminal result must not produce a false budget cause. Existing timeout values, HTTP/retry policy, first-address selection, TLS trust/hostname/SNI and peer checks remain unchanged. No network trial, APK assembly, install or upload is performed.

The change also rejects an already-cancelled connection watchdog callback after the connect phase has ended; it cannot convert a completed handshake/body exchange into a new connection-budget failure. A short terminal-state gate orders the winning reason and safe trace before logical publication or cleanup admission. Physical socket close and timer-handle close remain outside that gate. The existing business outcome remains SERVICE_ERROR and existing retry policy is unchanged. The diagnostic signal establishes only a local connection budget boundary, never the remote reason for slow progress.

The transport terminal gate orders its own competing stops; it does not establish an absolute first cause across the whole system. A genuine outer operation cancellation flag or observed deadline can take precedence before the budget signal is admitted. A later explicit cancellation/deadline remains the OPERATION outcome, while an already recorded budget observation remains historical evidence. Ordinary cleanup callbacks cannot rewrite it as remote TLS I/O failure. Synchronous budget checks preserve their original worker-and-cleanup completion wait; they do not use the watchdog early-return path. A real subsequent cancellation/deadline may still release that logical wait as before.
