# Configurable SearXNG instances, 2026-10-18

This slice implements the user's 00:46 UTC request to add/delete arbitrary SearXNG instance addresses, followed by qualification of other public instances. It is derived from the verified 2825-file public-HTML slice (source manifest 888e8c0ab63ccffdc80603f7d2d1e69556cf6580a8d57a818d96fc998c3869d1, recovery commit e6c0257a6eb979eb0957787f729acd51a49672e4). It does not reconstruct inaccessible historical increments. Current verification receipts are stored alongside the source copy.

## Functional configuration

- The previous three fixed placeholders become a dynamic list with add, edit, enable/disable, delete and explicit reset. A fresh configuration is empty. New entries are disabled until explicitly enabled; changed address, format or enable state retains the query-transmission acknowledgement requirement.
- There is no fixed operator/domain allowlist. At most16 configured entries bounds UI, storage and physical resources. This is not a promise to contact all enabled entries in each search.
- Each entry has a canonical monotonic `searxng-N` identity. Delete/reset does not recycle IDs. Revision and next ordinal both bind the configuration generation; exhausted counters fail closed.
- Codec v3 bounds its complete file to36KiB. Legacy v1/v2 reads retain configured URLs, enabled states and original IDs; v1 is explicitly JSON, v2 retains its format. Empty legacy placeholders disappear. Ordinals1–3 remain reserved after legacy import. Reading does not rewrite storage or send queries. Malformed/unreadable files are preserved.
- Mutation uses the existing CAS store and owner fences. Add/edit/delete/reset claims are action-specific and generation-bound. Changes retire current Discovery authority before I/O. A saved setting does not automatically search, restore a query or qualify an instance.
- Delete/re-add receives a fresh ID; prior rows, callbacks, cursors and grants cannot acquire authority through URL equality. Configured, enabled, available and actually exercised remain distinct states.

## Exact addresses and security

The accepted input is the exact public HTTPS search endpoint: `https://host/search` or `https://host/prefix/search`. Prefix segments are nonempty ASCII unreserved characters, excluding dot segments. No implicit `/search` suffix, percent decoding, normalization, alternate path discovery or redirect is performed. Credentials, query strings, fragments, IP literals, private/local names, nondefault ports, escapes, backslashes and ambiguous separators are rejected. Public-address checks and ordinary TLS validation remain on the actual connection.

JSON/videos and HTML/general are explicit formats. An error does not switch formats, identity, endpoint, method or client. HTML's automatic adapter checks origin `/robots.txt` against the actual prefixed query path; returned pagination form actions are finite same-endpoint hints only and never become fetch destinations. Ordinary browser/address-bar navigation is unchanged and is not gated by this adapter's robots policy.

One service origin may occupy only one entry, including disabled rows or another format/prefix. Same-origin physical admission/cooldown remains shared across edits and slots. The production registration owns a fixed process-lived3-transport pool, selecting configured-list index modulo3 before filtering enabled rows. Deleting a row can move another row to that index, but an old call keeps its immutable target and occupied physical slot until worker and cleanup both finish. No unbounded deleted-ID map or active-slot eviction is used. Configured entries sharing a busy physical slot receive explicit BUSY until that slot is actually released; the old process-wide physical ceiling of3 is preserved.

## One shared search budget

The runtime continues to limit logical source concurrency to at most3, or a smaller current resource cap. All sources plus generic B validation share one nonrefunding session ledger:72 requests,12MiB reserved application payload,24 unique candidate pages and60seconds. A typical HTML source reserves2MiB per page before I/O; the shared byte limit may therefore stop before all configured sources are exercised. Configuration capacity does not multiply these budgets or grant background activity. Failed/cancelled/unknown use does not refund reservations.

An instance is a search service, not an independent video website or proof of independent upstream engines. Results remain source-reported candidates until ESV's own validation supplies technical evidence. Robots permission, configuration and video evidence do not establish content download rights or save1/2/3 success.

## Public qualification status

The normal bounded qualification at00:47 UTC found no eligible instance among the five newly inspected operators: `search.jeremyh.xyz` returned robots200 with `Disallow:/`; `priv.au`, `www.isci.si`, `search.femboy.ad` and `searxng.site` returned429 without Retry-After. No search query was sent. Earlier `searx.ononoki.org` robots429 and `searx.tiekoetter.com` query disallow also remain unqualified in their recorded observations. These are point-in-time observations, not permanent availability claims.

No blocked instance is silently enabled or called through another method/client. The dynamic configuration feature can be tested with synthetic fixtures; live public effectiveness, template compatibility, breadth, timings and integrated save outcomes require an eligible public instance or appropriate operator authorization. No live effectiveness number is inferred from unit tests.
