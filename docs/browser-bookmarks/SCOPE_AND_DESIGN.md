# M1-B1: ordinary browser bookmarks

## Requirement and boundary

This bounded continuation implements the ordinary bookmark portion of PR-25 and
Product Requirements §5.1, originally based on `f78d7cc46c92bdd461e701ac3c39f62e12cb0ce5`.
The source checkpoint `53ee1863a5b60f44752d246e936b599ca1ca7628` was paused to
prioritize the preview's media-close defect. The independently reviewed hotfix
source `0c178320cd78391bfedd5f9846e3ee873f0f5fce` was then cherry-picked without
conflicts as `c3792eac3f98c87c4ea55331a271124d89e64829`. Combined bookmark admission
compares against that exact hotfix source baseline, preserving every hotfix
source/test/evidence byte. Bookmark scope still permits only its original three
integration files and nine new production/test files. No Commons contact is
embedded; default source/payment configuration remains fail-closed. The hotfix's
separate device retest is not implied by this source integration.
Tabs, the WebView's in-memory back/forward list, restored tab addresses and
Discovery Seen History are separate features and are not re-labelled bookmarks.
This does not complete PR-25: incognito, comprehensive browser history and
per-site permission management remain outside this slice. Existing ordinary
Cookie/session behavior is unchanged. Both separately blocked feature reviews
remain untouched and disabled; this work neither retries nor substitutes them.

## User-visible behavior

- A successfully loaded ordinary page has a **收藏此页** action. It saves the exact
  currently admitted page address and a sanitized current title.
- **收藏列表** shows at most 200 text-only records, newest first. Each record has
  explicit **打开** and **删除** actions. No favicon, thumbnail or prefetch is used.
- Opening the list, restoring the process or rotating the Activity does not
  navigate. Only an explicit **打开** click resolves a still-current item, closes
  the panel, clears input focus and navigates the current normal browser tab.
- Canonically duplicate addresses report already saved; no second record is
  added and the existing title/order/id are left intact. The capacity limit
  refuses an additional record; it never silently evicts a saved bookmark.
- Add/remove success appears only after the background atomic write, explicit
  file sync and bounded readback of the committed list. A failed or uncertain
  operation disables more mutations and opening until an explicit reload
  establishes the actual committed contents. The UI never promises that an
  uncertain operation preserved the old bytes or failed to commit.
- A slow operation is reported after three seconds while retaining its single
  in-flight ownership. Repeated taps/retry cannot start another writer; eventual
  completion publishes that exact outcome. A stalled disk operation is not
  declared cancelled merely because the UI timer fired.
- Closing tabs, disabling tab restoration or clearing Cookie/cache do not delete
  bookmarks. There is no automatic collection of browsing history.

## Data and admission

`BookmarkPolicy` conservatively canonicalizes validated HTTP(S): lowercase
scheme/host using the root locale, omit default 80/443 ports and add `/` to an
empty path. It preserves path, query, fragment, ordering and percent-escape
spelling; it does not conflate server-significant URLs or fetch a canonical link.
Credentials, unsupported schemes, literal controls/format/bidi characters,
backslashes, whitespace, scoped IPv6 hosts, malformed ports/URIs and addresses
above 8,192 UTF-8 bytes are refused. Non-ASCII host conversion is not added;
only the existing browser's accepted host forms enter this store.

Titles are bounded to 160 Unicode code points, with control/format/bidi and
isolated-surrogate removal, whitespace folding and a bounded host fallback.
HTML-looking text is rendered as plain Compose text. Addresses remain visible
with left-to-right text direction. Functional record/token stringification is
redacted. No URL, title, exception message or user data enters application logs.
No bookmark grants a site capability, request context, Provider authority or
media-save authorization.

Add captures a normal READY tab, exact BrowserContextRef and navigation epoch,
selection revision, same-document revision and an admitted live WebView address.
The host rechecks these identities synchronously on the click. Title changes
without navigation may supply the fresh sanitized title without borrowing a new
page's authority. Verified same-origin History API/hash updates may update the
admitted address only on the current READY WebView; reload history callbacks
remain ineligible until page finish. Switching away/back, renderer replacement,
new navigation, stale callbacks and unverified addresses invalidate old captures.
Once the explicit click is accepted, later navigation does not change which
captured page the already-accepted background save commits.

## Persistence and lifecycle

The process-owned Android runtime has one serial worker and one operation in
flight. It retains only application context and outlives an Activity rotation;
old and new Activities cannot create competing writers. Compose holds observable
state on the main looper. Store construction does not resolve/create the private
directory until the worker performs its first read.

The new file is `noBackupFilesDir/browser-bookmarks.bin`; existing application
backup/device-transfer exclusions are unchanged. It contains only bounded
ordinary bookmark IDs, addresses and sanitized titles. Schema 1 has an exact
header, count, length-delimited strict UTF-8 records and a trailing SHA-256
corruption checksum. It is not encryption or an authentication guarantee.
At most 200 entries and 2 MiB can be decoded; length/count limits precede
allocation, trailing bytes and duplicate identities/addresses are rejected.

There is no previous bookmark schema to migrate. A positively verified absent
base and backup in an accessible directory starts empty. An AtomicFile backup
can recover before decode. Unknown future/old versions, truncation, checksum
failure, malformed UTF-8 and inaccessible/broken paths stay unavailable rather
than being converted to an empty writable store. No session/history import,
implicit migration, reset, destructive repair or unsupported downgrade exists.
Future schemas require an explicit reviewed migration.

AtomicFile's finish API can swallow rename/sync errors. The adapter therefore
explicitly syncs the descriptor before finish and reads back the bounded decoded
list after finish. Failure is uncertain and requires reconciliation, never a
false success. Process interruption before publication leaves prior committed
data recoverable under platform AtomicFile behavior; final Android filesystem
and crash behavior still requires device acceptance.

## Layout and remaining acceptance

The action row scrolls horizontally; narrow displays use a dismissible dialog,
wide displays (840 dp and above) use a 360 dp side panel. All panel content is in
a lazy vertical scroller, including notices and retry controls, so short or
keyboard-constrained windows do not hide non-scrollable controls. Opening,
closing and selecting a bookmark clears address focus; the browser respects IME
insets. Only panel-open state is saveable across Activity recreation, not page
credentials or a pending navigation. SkinKit-backed text/buttons/spacing/colors
are used throughout. These are source/layout design properties, not a claim of
phone, tablet, keyboard, font-scale, TalkBack or rotation acceptance on Android.

No new dependency, SDK, service, permission, account or payment integration is
introduced. minSdk 31, target/compile SDK 36 and the exact admitted 74 external
runtime artifacts remain unchanged.
