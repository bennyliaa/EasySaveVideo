# M9-A fixed Commons transport and cache reliability

## Scope and execution

This matrix covers the existing fixed Commons metadata path only. Tests use the
existing recorded Commons response and small synthetic ordinary failures,
in-memory streams, fake sockets, manual clocks, timers, and bounded latch waits.
They make no network calls and add no dependency, provider, account, or device
requirement. Declared-size rejection uses a header only; it does not allocate an
oversized body. The only streamed limit probe is a 96-byte fixture with a 64-byte
operation budget.

No product source was changed for this matrix. Runtime artifact admission,
fixed schema-1 handling, and sanitized technical-only error behavior are unchanged.
Gradle execution belongs exclusively to the integrating build owner. At authoring,
the new tests have been statically checked; executed results must come from that
owner's verification report, not from this matrix's expected outcomes.

New provider coverage is in
`commons-provider/src/test/kotlin/com/easysavevideo/commons/CommonsFailureRecoveryTest.kt`
(15 test methods). Seven adapter recovery tests extend
`app/src/test/kotlin/com/easysavevideo/app/discovery/network/SystemCommonsTransportTest.kt`.

## Fault and recovery matrix

| Ordinary condition | New deterministic coverage | Required result |
| --- | --- | --- |
| DNS unavailable, connection refused, socket timeout | `unreachableAndTimeoutErrorsAreSanitizedSingleAttemptsAndAllowExplicitRecovery`; adapter `unreachableDnsIsOneSanitizedAttemptAndExplicitNextRequestCanRecover` and `refusedConnectionIsClosedWithoutTlsOrAutomaticRetry` | One failed attempt, `SERVICE_ERROR` without original diagnostic/cause, cancellation/closure, no internal retry; an explicit next request can succeed. DNS failure opens no socket; refused TCP reaches neither TLS nor HTTP. |
| Body returns a short prefix then throws I/O error | `partialReadIOExceptionClosesResponseAndNeverCachesPartialMetadata`; adapter `interruptedBodyIoDoesNotPublishPartialResponseAndFreshConnectionRecovers` | No partial publication/cache entry, closed response/socket, sanitized failure, successful next exchange on distinct fake sockets. |
| Truncated or malformed JSON | `ordinaryMalformedOrTruncatedJsonDoesNotPoisonSameQueryRecovery` | `INVALID_RESPONSE`, body closed, same query's next complete response is fresh, later reads can use its valid cache entry. |
| Declared length exceeds received bytes | `truncatedDeclaredBodyIsRejectedAndSubsequentCompletePageIsFresh` | `INVALID_RESPONSE`; received prefix cannot become cached metadata. |
| Partial status, headers, fixed body, or chunk terminator; bare LF; duplicate length | Adapter `ordinaryPartialAndMalformedWireResponsesReleaseForOneExplicitFreshConnection` | `INVALID_RESPONSE`, sockets closed, no automatic second exchange; explicit next request uses a new socket pair and succeeds. |
| Declared response exceeds existing 2 MiB cap by one byte | `normalDeclaredResponseLimitRejectsBeforeReadingAndReleasesForRecovery`; adapter `ordinaryDeclaredLimitRejectionDoesNotConsumeBodyAndNextConnectionRecovers` | `RESPONSE_TOO_LARGE` before any body read, cleanup and subsequent admission; no large payload allocation. |
| Missing length with a smaller operation byte budget | `smallOperationBudgetReadsOnlyOneExcessByteAndFailedBodyIsNotCached` | At most 65 bytes consumed for budget 64, then `RESPONSE_TOO_LARGE`; failed page not cached. |
| Broken stream returns zero progress | `zeroProgressBodyFailsAfterOneReadAndDoesNotBlockFollowingRequest` | One read, `INVALID_RESPONSE`, no loop or false EOF, next request succeeds. |
| Cache refresh fails at exact TTL expiry | `expiredCacheDoesNotBecomeStaleSuccessWhenRefreshFails` | Cache usable until expiry, rejected at equality; refresh failure remains visible instead of returning stale success. Recovery creates new retrieval provenance timestamp. |
| Different query fails when cache holds its one entry | `failedOtherQueryDoesNotEvictTheOnlyValidCacheEntry` | Good cached result/provenance preserved; failure does not consume the only cache entry. |
| Cached page exceeds new operation's smaller budget | `smallerBudgetRejectsCachedPageWithoutIoOrDestroyingExistingEntry` | `RESPONSE_TOO_LARGE` with zero additional exchanges; exact sufficient budget still reads the original cache. |
| `Cache-Control: no-store`, then request failure | `noStoreResponseDoesNotHideFailureOfFollowingRequest` | First success is not reused, next failure is visible, subsequent successful response can populate cache. |
| Read completes exactly at operation deadline | `slowReadAtDeadlineCannotPublishOrCacheAndNextOperationGetsItsOwnBudget` | `DEADLINE_EXCEEDED` before parsing/publication; 500ms first deadline/connect budget recorded, timers canceled, next operation receives its own 15s budget. |
| User cancels while body read returns bytes | `cancellationDuringBodyReadRejectsReturnedBytesAndAllowsExplicitRecovery` | `CANCELLED`, returned bytes not cached, exactly one call cancellation, duplicate cancel harmless, explicit next operation succeeds. |
| Deadline signal races ordinary socket timeout | `deadlineSignalWinsOverIoFailureAndReleasesCallWithoutAnInternalRetry` | Deadline classification preserved, exactly one exchange/cancellation; next explicit request succeeds. |
| Slow header/body read; socket close temporarily held | Adapter `slowHeadersAndBodyDeadlineRetainSlotUntilCleanupThenReleaseWithoutRetry` | Manual 800ms watchdog yields `DEADLINE_EXCEEDED`; extra work receives `BUSY` until read and close exit, duplicate cancellation cannot replace outcome; timers canceled after release. |
| Wall clock rolls backward during admitted body read | Adapter `elapsedMonotonicDeadlineCannotBeExtendedByWallClockRollbackDuringBodyRead` | Original elapsed 800ms monotonic deadline wins; no refreshed budget, sockets close, one attempt. |
| 429/503 with ordinary positive, zero, or malformed Retry-After | `serverCooldownRejectsEarlyRetryWithoutIoAndAllowsRequestAtExactBoundary` | Exact stored retry time, no I/O one millisecond early; positive 2s, zero minimum 1s, malformed fallback 30s; explicit request admitted at equality. |

## Source findings and existing protections

- `CommonsMetadataProvider.search` admits one physical request with `inFlight`,
  checks cancellation/deadline around each read and parsing, closes the response,
  and waits for transport cancellation completion before releasing admission.
  Only fully validated success reaches `store`; there is no stale-on-error fallback.
- `CommonsMetadataProvider.cached` expires entries at `expires <= now`. Cache use
  is still checked against the new operation budget; returned cached bytes are
  reported as zero network bytes without refreshing rights retrieval timestamps.
  Default cache limits remain 16 entries, five minutes, and aggregate bytes no
  greater than the configured response cap.
- `SystemCommonsTransport.Call` caps admitted duration at 15 seconds, bounds
  connect/TLS to at most five seconds, and uses both the supplied wall-clock
  deadline and a monotonic deadline. It performs one DNS resolution and one socket
  exchange; cancellation/timeout can complete logically while physical admission
  remains held until worker and socket cleanup both exit.
- `StrictCommonsHttp.Reader` counts status, headers, framing, and body against the
  wire budget. The provider separately caps metadata body bytes. These quantities
  intentionally differ: wire overhead can reject a response before its decoded
  JSON body alone reaches the provider cap.
- `CommonsRetryAfter.retryAt` and the provider/adapter not-before state preserve
  server cooldown without sleeps or hidden retries. Existing tests also cover
  authoritative denial/rate-limit headers whose error body or close is slow.
- Exception text from DNS, sockets, and streams is not propagated through
  `CommonsFailure`: callers receive the technical enum name with no original cause.
  The new tests assert this for ordinary unavailable/partial transport failures.

Existing `StrictCommonsHttpTest` complements this matrix with fixed/chunked framing,
compression/redirect rejection, exact header/field/wire caps, one-extra-byte probe,
trailer restrictions, and cancellation at EOF. Existing runtime tests cover
off-caller exactly-once callbacks, blocked transport cancellation, deadline
completion, and admission retained while a worker or cleanup remains occupied.

## Verification boundary

These are deterministic JVM contract/recovery checks, not a claim of Android
device, live TLS/network interoperability, throughput, resource-stress, or release
readiness. See the integrating M9-A verification report for final executed counts
and aggregate checks against the final workspace.
