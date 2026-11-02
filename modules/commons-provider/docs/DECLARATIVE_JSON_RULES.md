# Finite JSON candidate rules: first offline execution slice

## Status and authority boundary

This is a new original, standalone data mapper in `commons-provider`, not an installed provider or a trusted rule-distribution format. It interprets one caller-supplied JSON response. It does not register a `VideoSearchProvider`, send a request, follow a cursor, resolve DNS, read a file, load code, or access credentials. It has no script, regex, template, expression, recursion, reflection, or plugin execution facility.

Every output remains a `CandidateUrl` with `SourceReportedCandidateMetadata` and `SOURCE_REPORTED` metadata level. `ProviderPage.results` is always empty. The mapper never constructs `RawVideoResult`, sets `videoConfirmed`, creates a rights snapshot, grants download authorization, or supplies playback/media evidence. Existing `ValidationPipeline` can retain these as pending candidates; without a validator or explicit pending retention it still fails closed.

The caller fixes `ProviderDescriptor`, display `sourceName`, limits, clock, and operation. None can be supplied or overridden by the rule or response. The executor is not an independent provider/signature/version-installation owner. `ruleVersion` is a declared positive revision only; it does not prove authenticity, freshness, monotonic installation, or package compatibility.

The existing `ProviderPackageProtocol` format/schema v1, canonical bytes/digest, verifier, repository, installed Commons parser, transport and built-in Commons behavior are untouched. The rule's independent 16 KiB offline input ceiling does not fit the existing 4 KiB signed payload contract. Trusted distribution and runtime registration are explicitly **not connected**. A future reviewed schema migration must reuse the existing package/repository/isolated runtime owners rather than adding another trust system.

## API

- `DeclarativeJsonRule.compile(bytes, checkCancelled)` validates and defensively copies a bounded rule into private immutable paths. Compilation is not trust verification.
- `DeclarativeJsonRuleExecutor(rule, descriptor, sourceName, limits, clock).execute(responseBytes, operation)` returns one candidates-only `ProviderPage`. The descriptor's collection is detached and unmodifiable; source names are caller-owned strings of 1–128 UTF-16 units with valid Unicode and no control characters.
- The executor has no cache, retained response, shared mutable iteration state, automatic retry, or transport. A compiled rule/executor can be reused concurrently with separate operations.

## Exact schema 1

```json
{
  "schemaVersion": 1,
  "ruleVersion": 1,
  "itemsPath": ["items"],
  "fields": {
    "pageUrl": ["url"],
    "title": ["title"],
    "description": ["description"],
    "reportedDurationMs": ["duration"],
    "reportedPublishedAtEpochMillis": ["published"]
  },
  "nextCursorPath": ["next"]
}
```

`schemaVersion`, `ruleVersion`, `itemsPath` and `fields` are required. `fields.pageUrl` is required. The other four field mappings and `nextCursorPath` are optional; omit their mappings rather than assigning JSON null. No other schema keys are permitted, including source identity, request, headers, limits, code, rights, and verification claims. Duplicate JSON keys at any level are rejected. `schemaVersion` must use integer token `1`; `ruleVersion` must be an exact positive integer token fitting `Long`, not a string, decimal or exponent form.

Each path is a JSON array of at most eight literal segments:

- A string selects that exact object key, including literal punctuation. It has 1–128 UTF-16 units and no control characters.
- An integer token selects exactly index 0–99 in an array. No negative, fractional, exponent, wildcard, filter, dotted-path or recursive semantics exist.
- The empty path selects its root unchanged. `itemsPath` starts from the response root, fields start from each item, and `nextCursorPath` starts from the response root.
- A missing/null branch or out-of-range index resolves to missing. A non-null intermediate value with the wrong container type rejects the page.

The response may contain unknown, unmapped fields. They remain subject to the strict JSON parser's global bounds but have no execution or authority semantics.

## Mapping, ordering and pagination

`itemsPath` must resolve to an array. Missing/null/non-array items, non-object rows, or wrong mapped types reject the complete page. The original row count is checked before URL filtering and deduplication. Rows remain in response order; equal normalized URLs keep the first candidate and its metadata.

- `pageUrl`: missing/null, overlength or invalid HTTP(S) URL drops that candidate. A provided non-string URL rejects the page. URLs use the existing `ResultNormalizer.canonicalUrl` and a 4,096 UTF-16-unit input/output ceiling. This is syntactic normalization, **not DNS/public-address/SSRF verification or permission to fetch**. Any later network operation still requires the existing network and source-admission boundaries.
- `title` and `description`: absent/null becomes unknown. Present values must be strings no longer than 512 and 2,048 UTF-16 units respectively; overlength values reject the page rather than silently claiming success. Existing `CommonsText.plain` produces inert plain text and strips markup/control content. The output must never be rendered as executable HTML.
- `reportedDurationMs`: absent/null becomes unknown; present JSON number must be an exact positive `Long`. No string coercion or unit conversion is performed. Zero is rejected to preserve the existing pending-candidate contract.
- `reportedPublishedAtEpochMillis`: absent/null becomes unknown; present JSON number must be an exact nonnegative `Long`. No date guessing, string conversion or timezone conversion is performed.

If `nextCursorPath` is absent or resolves to missing/null, `nextCursor` is null and `exhausted` is true. Otherwise the value must be a nonblank string with no ISO control characters and at most 2,048 UTF-8 bytes. Its content is opaque, even if it resembles a URL. It is returned unchanged, `exhausted` is false, and `cursorExpiresAtEpochMillis` remains null. An empty candidate page with a valid cursor is allowed and does not cause another request.

This slice only extracts cursor data. It does not bind a cursor to provider/query/rule identity, encode a recoverable cursor, enforce TTL, detect a repeated cursor, or advance pages. Existing outer scheduling limits (including request/no-new-result limits) are not a substitute for the missing future adapter binding. Cross-rule resumable pagination is **not implemented** and must not be enabled by directly persisting/forwarding this raw token.

## Resource and failure contract

- Rule input: at most 16,384 bytes, checked before a defensive input copy.
- Response input: at most the minimum of 256 KiB, `DiscoveryLimits.maxResponseBytes`, and `operation.context.resourceBudget.maxBytes` when present, checked before copying/parsing.
- Original item array: at most the minimum of 100 and `DiscoveryLimits.maxCandidatesPerResponse`, before filtering/deduplication.
- Existing unchanged `StrictJson`: strict UTF-8 and paired Unicode surrogates; maximum depth 16, 20,000 nodes, object key length 128, 1,024 fields per object, string length 65,536, and numeric token length 64. No duplicate keys or trailing tokens.
- Parse, path traversal, row execution and final output check interruption/cancellation/deadline through the existing operation. The clock is caller-owned; fixed-clock tests make output deterministic. A stopped operation never returns a partial success page.

Failures use existing `DiscoveryFailure` technical codes only:

| Condition | Code |
| --- | --- |
| Invalid/unknown schema, invalid version/path, malformed or oversized rule | `SOURCE_CONFIGURATION` |
| Malformed response, mapped type/range/structure/cursor error | `INVALID_RESULT` |
| Any effective response byte ceiling exceeded | `RESPONSE_TOO_LARGE` |
| Original item array over effective count ceiling | `TOO_MANY_CANDIDATES` |
| Cancellation or interrupted thread | `CANCELLED` |
| Operation deadline reached | `DEADLINE_EXCEEDED` |

Cancellation/deadline errors are not relabeled as malformed data. Compiler cancellation-hook exceptions are reduced to `CANCELLED`; existing typed `DiscoveryFailure` values are preserved. Failure messages contain only the technical code, without response/rule bytes, URLs, titles, cursor tokens, source strings, credentials, or exception messages. Rule and executor descriptions are redacted. There is no logging.

## Second independent fixture shape

A caller-fixed `fixture-beta` / `Fixture Beta` can use this different rule without changing the first source:

```json
{
  "schemaVersion": 1,
  "ruleVersion": 7,
  "itemsPath": ["data", "groups", 0, "entries"],
  "fields": {
    "pageUrl": ["links", 0, "href"],
    "title": ["name"],
    "reportedDurationMs": ["timing", "ms"]
  },
  "nextCursorPath": ["paging", "token"]
}
```

```json
{
  "data": {
    "groups": [{"entries": [{
      "links": [{"href": "https://example.org/two"}],
      "name": "Two",
      "timing": {"ms": 1250}
    }]}]
  },
  "paging": {"token": "beta-page-two"}
}
```

The tests send both caller-fixed source shapes through `ValidationPipeline(retainUnvalidatedCandidates = true)` and assert pending candidates, empty verified results, correct separate provenance and no verification evidence. All URLs are inert fixture data; no network or live services are used.

## Tests and remaining integration gates

New tests are `DeclarativeJsonRuleTest` (schema, paths, bounds, copying, cancellation, redaction) and `DeclarativeJsonRuleExecutorTest` (two-source mapping through pending validation, URL/metadata/cursor contracts, effective byte/count budgets, parser limits, cancellation/deadline final fences and concurrent reuse). The centralized build owner runs these and the existing Commons/package suites under the build lock; this document does not itself claim that tests have run.

Next integration work requires a separately reviewed existing-owner schema migration, trusted-key/package/version/recovery compatibility, authenticated rule identity, source/query/cursor binding and expiry, fixed safe request construction, source and filter capabilities, isolated runtime admission, and end-to-end fake-transport tests before any real service or credentials. No downloaded executable rule or arbitrary JavaScript/regex engine is part of this contract.
