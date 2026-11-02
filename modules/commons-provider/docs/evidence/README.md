# Real capture provenance and limitations

Exactly three anonymous, fixed, public metadata GETs succeeded on 2026-10-06. User-Agent was the real app/version plus a user-approved contact. The contact is redacted from manifests and absent from source/test fixtures. There was no Cookie or Authorization header, no redirect follow, no media/thumbnail fetch, no account action and no endpoint/identity switching after a denial. The preliminary generic web-reader API attempt produced no usable JSON and is not a fixture.

Capture method: Python standard-library HTTPS client, 15-second socket timeout, a 20/40-second outer process limit, explicit redirect rejection, `Accept: application/json`, `Accept-Encoding: identity`, at most 2 MiB plus one overflow-detection byte. This small protocol probe is **not** the candidate's future Android HTTP adapter and does not prove that adapter's cancellation, DNS or TLS implementation.

- `big-buck-bunny-live.json`: exact response body, HTTP 200, request started 17:05:55.614418 UTC, 3416 bytes. Request manifest: `live-attempt.json`.
- `search-live-1.json`: exact response body, HTTP 200, request started 17:07:54.639864 UTC, 3478 bytes. Fixed phrase search, namespace 6, video file type, limit 1.
- `search-live-2.json`: exact response body, HTTP 200, request started 17:07:59.018886 UTC, 3054 bytes. Replayed the first response's full continuation object without advancing an offset locally. Search manifests: `search-live-attempts.json`.

The first lookup and first search identify page ID 40108642, page revision 1212972787, duration 634.553 seconds, 4000×2250 and the reported author/license/custom attribution. The next search identifies page ID 158250589. Both contain actual page and thumbnail URLs. No long description is surfaced by the client despite additional fields in the capture.

SHA-256 values are in the manifests and asserted by `CommonsProtocolTest`. The test-resource copies are byte-for-byte identical. Negative tests mutate parsed copies or construct obviously synthetic protocol bodies; they are not represented as HTTP captures. These fixed observations do not guarantee future availability, content stability, or arbitrary-query success.

## Third-party metadata attribution

These JSON fixtures reproduce public metadata from Wikimedia Commons contributors. The source pages and revision histories are:

- [Big Buck Bunny 4K file](https://commons.wikimedia.org/w/index.php?title=File:Big_Buck_Bunny_4K.webm&oldid=1212972787), [contributors/history](https://commons.wikimedia.org/w/index.php?title=File:Big_Buck_Bunny_4K.webm&action=history)
- [Big buck bunny 720p 5mb file](https://commons.wikimedia.org/w/index.php?title=File:Big_buck_bunny_720p_5mb.webm&oldid=1071660601), [contributors/history](https://commons.wikimedia.org/w/index.php?title=File:Big_buck_bunny_720p_5mb.webm&action=history)

The API payloads remain unmodified; only request-manifest contact information is redacted. Unstructured source text retains the [Commons text terms](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia#Requirements_of_frequently-used_licenses), including [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). These fixture terms do not relicense the original client code. No underlying media file is included; each media item's rights remain those described on its file page. No blanket CC0 or blanket download-right claim is made.
