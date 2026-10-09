# Optional page verification service, protocol v1 (development)

This is a separate, optional Python service and an original Android client. No Python,
Scrapling or browser binary is part of Android, project source backups, or this milestone's distribution.
No service endpoint is bundled. Public source access, hosting, authentication, deployment and binary
redistribution remain unapproved. Only owned isolated fixtures are admitted for runtime evaluation.

POST /v1/verify; UTF-8 application/json; no cookies, credentials, redirects or compression.
JSON must be well-formed UTF-8 with no duplicate or unknown keys, lone surrogates, trailing data or non-integer integer fields (booleans/floats are not integers).
Exactly one candidate per request. Maximum request 8192 bytes; result 32768 bytes; no retry.
Request has exactly these keys:
- version: integer 1
- requestId: 32 lowercase hexadecimal characters, freshly generated per attempt
- generation: 64 lowercase hexadecimal characters, client configuration identity
- candidateUrl: public HTTP(S) page URL, at most 4096 UTF-8 bytes; no userinfo, fragment, controls, backslash, nonstandard ports or local/IP hosts in Android
- budgetMs: integer 1..10000, bounded by remaining Discovery operation deadline
- dynamicAllowed: boolean (client opt-in covers this; server policy still independently restricts it)

Response has exactly these keys:
- version, requestId, generation, candidateUrl: exact request identity
- outcome: PAGE_EVIDENCE, INSUFFICIENT, UNSUPPORTED, REJECTED, TIMEOUT, CANCELLED, BUSY, UNAVAILABLE
- pageUrl: exact candidateUrl (redirects are rejected in v1)
- evidence: HTML_VIDEO, DYNAMIC_VIDEO_XHR, or NONE
- title: string up to 512 UTF-16 code units, with no control characters, or null
- thumbnailUrl: absolute public HTTPS URL up to 4096 UTF-8 bytes, or null
- durationMs: integer 1..86400000, or null
- playbackVerified: false
- downloadable: false

PAGE_EVIDENCE requires HTML_VIDEO or DYNAMIC_VIDEO_XHR; other outcomes require NONE and null
metadata. Transport status, HTTP 200, search-result metadata, standalone API declarations, JSON-LD
alone or an Adaptive match alone never establish page video evidence. PAGE_EVIDENCE means one
primary video element with source evidence, or an admitted rule's unique dynamic video element
cross-checked with exact page-bound XHR identity. It does not prove playback, downloadability,
rights or five-field completeness. Unknown fields stay null. No source-reported title/duration is
used as fallback. No thumbnail or media is downloaded during verification.

The Android client requires HTTPS port 443, a public DNS endpoint at exact /v1/verify, all-public
DNS answers, pinned peer/TLS hostname verification, no ambient auth/cookies/proxy, finite bytes,
monotonic and wall-clock deadlines, bounded physical concurrency and cancellation. The request contains the complete candidate URL (which may itself contain information in its query parameters), never separately supplied search query, browser cookies, credentials or source metadata.
A configuration/navigation cancellation or identity mismatch prevents publication.

Server default has no admitted sources; it returns UNSUPPORTED without DNS or target access.
Its fixture harness provides exact owned loopback origins/paths and reviewed browser runtime;
this exception cannot be selected in Android, HTTP input or a normal server invocation. All target
redirects, credentials, unapproved origins/paths and active/media downloads are denied. Production
public SSRF/DNS policy and per-source/browser admission require a later reviewed scope.

Service failure is isolated: keep source-reported candidates pending and preserve Browser/Save/Record.
The client must not be an application startup precondition. Results remain subject to existing
Discovery cancellation, profile/filter, source-configuration and UI navigation fences.

Android milestone policy: process-session-only opt-in; one validation attempt per provider response, at most 3000 ms and always leaving 1000 ms within the enclosing operation deadline. Others remain pending. All process-attached controllers are fenced synchronously before any endpoint or dynamic-setting change. No configuration action initiates a query.
