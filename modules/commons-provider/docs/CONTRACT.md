# Candidate contract and integration notes

Reviewed 2026-10-06. This is an engineering contract, not a conclusion that any particular reuse is legally cleared.

## Official API contract

Endpoint: `https://commons.wikimedia.org/w/api.php`, anonymous GET, `action=query`, `format=json`, `formatversion=2`.

Search uses `generator=search`, `gsrnamespace=6`, `gsrsearch=(user query) filetype:video`, `gsrlimit=5` by default. File metadata uses `prop=videoinfo|info`, `inprop=url`, `viprop=mime|mediatype|size|url|extmetadata`, `vilimit=1`, `viurlwidth=320`, requested metadata language and an explicit extended-metadata filter. `maxlag=5` is included. The current API may return more extended fields than the filter requests; the parser ignores descriptions, categories and every unneeded field.

The response must have file namespace 6, a positive page ID, matching Commons file-title/page URL, `mediatype=VIDEO`, and video MIME. Audio/image/page matches are not video results. Generator ranking uses the returned page `index`. The three successful real responses verify the `query.pages[]/videoinfo[]` layout and `videoinfo.duration` in seconds. Milliseconds are rounded from a positive bounded decimal; missing, invalid and implausible values remain null.

Continuation preserves all values from the supported keys `continue`, `gsroffset`, and `vicontinue`. Values are not incremented, reformatted numerically, or treated as URLs. Unknown keys require review rather than silently dropping continuation data. An absent continuation means exhaustion; an empty result set with continuation is not exhaustion.

Sources:
- [Action API Search](https://www.mediawiki.org/wiki/API:Search)
- [CirrusSearch file-property search](https://www.mediawiki.org/wiki/Help:CirrusSearch#File_properties_search)
- [TimedMediaHandler API](https://www.mediawiki.org/wiki/Extension:TimedMediaHandler/API)
- [Action API continuation](https://www.mediawiki.org/wiki/API:Continue)

## Rights and source identity

The author is `Artist`, not the uploader. `Attribution` is a custom credit instruction and must remain available. Extended metadata can contain HTML, and multi-license summaries are not reliable enough for automatic clearance. Every parsed field keeps its reported source, text-conversion/truncation markers, and an explicitly unstructured-data classification. Long descriptions are not copied into the result model. [CommonsMetadata returned-data contract](https://www.mediawiki.org/wiki/Extension:CommonsMetadata/en#Returned_data)

File media, structured file data, and unstructured page text have different terms. This API client does not fetch Wikibase Structured Data on Commons or label all returned metadata CC0. It retains the file/source page and metadata-terms reference so the display and persistence layer can preserve attribution. Actual reuse still requires per-file verification and consideration of restrictions beyond copyright. [Commons reuse guidance](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia), [Structured Data on Commons](https://commons.wikimedia.org/wiki/Commons:Structured_data)

The Big Buck Bunny test item reports Blender Foundation and CC BY 3.0, with its own custom credit. The file page includes additional credit instructions and trademark exclusions. A metadata label alone cannot replace those details. [File page, revision 1212972787](https://commons.wikimedia.org/w/index.php?title=File:Big_Buck_Bunny_4K.webm&oldid=1212972787), [original project credit instructions](https://peach.blender.org/about/)

All results must show Wikimedia Commons as the source. The API must not be white-labeled to hide Wikimedia, and cached data must keep applicable content-license information. Honor rate/slow-down responses and use an informative, truthful application User-Agent with an approved contact. [API usage guidelines](https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_API_Usage_Guidelines), [User-Agent policy](https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy), [Terms of Use, API terms](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use#12._API_Terms)

## Minimal stable model for the integrator

`CommonsMetadataPage`: results, next opaque cursor, cursor expiry, exhausted, actual response bytes and cache marker.

`CommonsVideoMetadata`: page ID, file title, canonical file page, validated thumbnail URL, nullable duration/dimensions, MIME, explicit source name, and `CommonsRights`.

A generic Discovery `Attribution`/`LicenseProvenance` extension should preserve, at minimum:

- source/provider name and namespace; source item ID and page URL
- author, credit and custom attribution as separate nullable plain-text values
- license name, verified-safe license link, usage-terms summary
- copyrighted / attribution-required / non-free tri-state values
- restriction labels, deletion warning, review/incomplete status
- source page revision, observation timestamp, metadata-terms URL
- field name, API-reported origin, metadata class and transformation/truncation information

These are observations, not executable content or authorization. A minimal UI should show source, author/unknown, license/unknown and details link; the details view carries the rest. Missing/truncated/flagged rights should not be silently rendered as a complete attribution. Thumbnail display remains subject to the final rights/UI review.

SQLite migration should add optional versioned provenance without fabricating it for old rows. Round-trip unknown tri-state values, nullable durations, source revisions, Unicode authors, custom credits and restriction arrays. Old cursor schemas and source versions should be rejected or restarted explicitly; do not rewrite them into current offsets.

## Network and runtime boundaries

The only request authority is the exact Commons API endpoint. All redirects fail. Display-only license links are limited to HTTPS Creative Commons license/public-domain paths, GNU license paths, or Commons rights/help paths; they are never fetched by this provider. Thumbnail links are raster formats under Commons thumbnail paths on `thumb.wikimedia.org` or `upload.wikimedia.org`. The two exact official imageinfo tracking parameters in the captured API responses are permitted; arbitrary query parameters are rejected.

The transport port must resolve/check/pin the actual public peer, verify TLS normally, use no ambient cookies/authenticator, and have no automatic redirect, proxy, retry, cache or decompression behavior. No User-Agent contact is forwarded to image or license hosts. It must obey the supplied absolute deadline, connect limit and response/header limits and cancel the actual resource promptly.

The backend enforces at most one physical in-flight operation, max 2 MiB response bytes and max 15 seconds including parsing. It takes the smaller upstream resource/deadline budget. Runtime cancellation and deadline completion do not wait for a stuck adapter, but the worker slot stays reserved until work and cancellation cleanup finish. Rejected runtime calls return immediately; admitted callbacks are background callbacks requiring UI dispatch.

There are no automatic retries. HTTP 429/503 and API maxlag/rate-limited responses set a not-before time, accepting delta-seconds or HTTP-date `Retry-After`; malformed/missing values use a conservative 30-second cooldown. Overflowing delays never become immediate retries. The integrator must respect the typed retry time. Successful metadata cache is in-memory only, at most 16 pages and a combined 2 MiB raw-response weight, TTL at most five minutes, honoring no-store/no-cache/private and shorter max-age. Cache does not refresh provenance time.

No safe-search guarantee is available. Required safe search is explicitly rejected, including the default request. The UI cannot switch it off for the user or quietly reinterpret it as a best-effort filter.
