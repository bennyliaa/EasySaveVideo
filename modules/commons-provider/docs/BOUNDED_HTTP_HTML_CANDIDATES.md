# Bounded HTTP templates and HTML candidate projection

This increment connects two explicitly admitted formats to the existing signed-package, Repository, lease, health, isolated runtime, ValidationPipeline and Coordinator chain. Production target mappings and trust roots remain empty. Tests use owned loopback servers and ephemeral signing keys. Actual execution results belong to the central build report; this document is not a claim that unrun tests passed.

## Signed plan and host endpoint

ESVP v2 adds candidate kind 2. Its closed schema contains only a positive rule version, one fixed GET request template and either a JSON or finite HTML response projection. Existing Commons v1 and pure-JSON kind 1 bytes, digest/signature order and public compile APIs stay unchanged. ESVS v2 remains the independent target-bound policy owner. The complete signed rule bytes, rule digest, target, compatibility and actual compiled plan are verified before installation.

The host supplies one immutable HTTPS endpoint per target: exact ASCII host, port 443, path, response format, finite locale set and finite query admissions. Its full canonical SHA-256 endpoint ID is signed into the rule. Request query values are either an admitted input slot (query, locale or opaque cursor) or a finite admitted literal. They are independently encoded by HttpUrl; no raw query concatenation, headers, method, body, credentials, interpolated paths or executable expressions are accepted. Changing the endpoint's host, path, format or query permissions changes its identity.

Host-owned self-test vectors check the actual encoded path/query and projection against expected candidate outputs. HTTP targets require positive, empty and cursor-input coverage. These tests are pure: compile, install, self-test and restored read-only authority never execute the HTTP source. They grant no live-site compatibility proof.

## One authority and execution lifecycle

CandidateRulePlan is a small closed data hierarchy. SignedJsonVideoSearchProvider preserves the old kind-1 constructor and behavior as a facade over SignedRuleVideoSearchProvider. Both candidates use the original RepositoryRuleExecutionLease, exact descriptor acquisition, operation cancellation, health observations and worker/callback close sequencing. One immutable HTTP binding is constructed from that leased plan and is passed unchanged to both transport and projection. A bare plan or source cannot issue repository authority.

HTTP candidate repositories use only record format 4 with the same persisted local incarnation and original atomic mutation protocol. Old JSON format-3 migration and Commons format-3 bytes stay unchanged. JSON descriptor framing retains its existing domain and bytes. HTTP uses the same complete frame-v2 fields with domain `esv-signed-http-authority`; the package digest covers kind, raw rule and content-addressed endpoint ID. Existing durable progress, exact profile/query/version/schema checks, owner expiry and five-minute cursor bound apply. No extra cursor database or authority owner is added.

Candidate output remains SOURCE_REPORTED only. ProviderPage.results is empty; no RawVideoResult, playback evidence, download rights or independent verification is inferred from JSON or HTML. Existing before-commit and after-commit authority publication gates remain responsible for pending/session data. Old pending candidates cannot borrow a newer same-ID package for explicit verification.

## Anonymous HTTP boundary

The dedicated OkHttp client has no cookies, cache, proxy, authentication or browser state; uses ordinary trusted TLS/hostname verification, HTTP/1.1 and no shared idle connections; and disables redirects and retries. DNS admits only the exact configured host, rejects any mixed or nonpublic answer and selects one checked address. The connected peer is checked against that selection before an HTTP request is written. Non-200 responses are rejected inside the network interceptor before OkHttp follow-up logic, including its 503 Retry-After:0 behavior. A call permits at most one exchange.

Only a single matching JSON/HTML MIME type, absent or UTF-8 charset, and absent or identity content encoding are accepted. Transfer-Encoding and Trailer are rejected in this first slice; only validated Content-Length or EOF-delimited bodies are admitted. The entity cap is 64 KiB intersected with signed and operation budgets. The reader probes through real EOF and rejects excess, truncation and zero progress. A rejected or incompletely read body is cancelled before close.

Header limits (64 fields, 16 KiB) are acceptance checks after OkHttp has parsed them, not a preallocation or exact wire-byte bound. Body quotas count application entity bytes, not TLS/network traffic. Call timeouts are bounded by signed/local/operation remaining time. Synchronous DNS, TLS, close and HTML initial parse may not be forcibly interruptible; the existing runtime reports logical termination while retaining the physical source slot until worker and cancellation drain actually finish. No replacement timer, thread pool or hard CPU-preemption claim is introduced.

## Finite HTML projection

Strict UTF-8 is parsed with Jsoup without loading resources or running script. A selector is a short typed sequence of child/descendant steps with a whitelisted tag and optional finite id/class/exact attribute predicate. No downloaded CSS selector, regex, XPath, function or script is interpreted. There must be exactly one explicit list container. Items are counted before filtering or deduplication; field ambiguity is rejected.

Projection uses iterative bounded traversal, tree depth/node/attribute checks, and a shared visit/comparison budget. Text skips script, style, template, iframe and other excluded domains, and is bounded while collected. Relative page links resolve against the original logical request URL; HTML base tags cannot change that origin. Candidate links are normalized without following them. A next token comes only from data-cursor, never a next-page URL. Parse-time peak allocation and worst-case initial Jsoup CPU are not proven by the later tree limits or by a timeout.

## Evidence scope and remaining gates

Integration cases exercise actual signed update installation, two real OkHttp loopback providers, two pages of pending candidates, isolated health/LKG, cancellation with a retained physical slot, policy revocation during response and around store commit, old pending rejection, and file-backed Repository reopen with a new Coordinator and public-record-rebuilt InMemory store. That recovery case validates HTTP identity/cursor policy; it does not validate Android SQLite persistence or device process death. Prior pure-JSON recovery and legacy tests remain enabled.

The test-only loopback helper reroutes a fixed logical HTTPS endpoint to an owned physical loopback port while using the same actual HTTP implementation. It does not prove public TLS, real Android DNS, production service compatibility or device performance. Live sources still require host configuration and real trusted roots. Downloaded regex remains disabled. Full TR3.4 hard parser CPU/peak-memory isolation and the remaining production/device acceptance gates are not completed by this bounded slice.
