# M2 Discovery development candidate

## Status and acceptance boundary

This milestone implements original framework and local app functionality. It is **not an accepted real-world search service**. Release has zero admitted Providers, and the user-facing empty state explicitly says so. PR-04's requirement for at least one legal, admitted real source is **PENDING**. Search/API/SearXNG/NewPipe/Scrapling implementations, server/account deployment and source-specific terms/license admission have not been invented or bypassed. Browser remains independent and usable by address.

This is a development checkpoint after M1 tag `m1-dev-2026-10-06`. It follows the eight original specifications, with update1 governing milestone order and the later Android 12/API31 user decision taking precedence over API30 text. Save and Record execution remain M3/M4; no fake success or recording occurs in M2.

## Implemented trace

| Area | Implementation | Evidence boundary |
|---|---|---|
| Provider framework | Pure JVM descriptors, registry, request/session/epoch/version context, default empty registry; isolated per-source runtime, deadline, byte/body/candidate limits and cancellation | JVM tests; no production network adapter or source compatibility claim |
| Candidate validation | Bounded URL pool; replaceable validator; no validator means explicit unavailable; original normalization/filtering | Synthetic contracts; no real video-page classifier measured |
| Budget | P=20, L=40, H=100; user P recalculates L=2P and H=min(5P,500); 500 pool cap; 1/2/3 mode concurrency, one request per Provider, six attempts per round, at most two retries, 15-second total deadline, 2MiB response, 100 candidates per response, 30-second cooldown | Deterministic tests; no battery/network benchmark |
| Result pool | Stable materialized PageSnapshot IDs/revision, append-only subsequent inventory, protected materialized pages, cross-source identity aliases, nullable unknown metadata | JVM tests |
| Seen facts | Discovered/SHOWN/OPENED/PLAYED/SAVED/DISMISSED separate; four repeat strategies; profile isolation by normalized query, locale and source-query filters; Seen survives cursor resets | JVM tests; UI does not invent played/saved events |
| Android data | SQLite structured checkpoint header/results/pages/progress/Seen/viewport tables; entire accepted page and cursor commit in one transaction; read validation; unchanged rows not rewritten; Seen updates independent | Android compilation, host SQLite contract smoke tests; Android runtime crash/restore NOT_RUN |
| Lifecycle and priority | Immediate atomic request gate closes without waiting for DB lock; queued lifecycle cleanup; no foreground discovery when Browser is selected/backgrounded; save/record priority port | Core cancellation tests and source review; no actual save/record task implemented |
| Compose UI | SkinKit visuals; default two columns, tablet width adaptation; keyword input; page controls; modes/repeat policy/duration/unknown-duration/safe-search options; explicit errors/retry; Browser click-through | Android compile/lint; no launched APK, screenshot or device visual acceptance |
| Real visibility | Grid viewport intersection on both axes, at least 50% continuously for 500ms; foreground+route+modal gate; page stamp/revision membership fence | Pure presentation/Shown tracker tests; physical device geometry NOT_RUN |
| Debug fixture | User explicitly enables labelled synthetic local demo; uses in-memory store and reserved example.invalid links; no search requests; disabled by default | Both source-set compilation and release bytecode boundary checks |
| Release | Empty production registry, no demo Provider implementation, exact existing 55 admitted runtime artifacts | Runtime bytes gate and source/bytecode checks |

## Persistence and privacy

The adapter is Android platform SQLiteOpenHelper with no new dependency. TR-01 explicitly permits Room/equivalent structured storage; update_1 lists Room. See ADR_006 for the equivalent implementation choice. Primitive display settings use the existing SharedPreferences settings adapter; complex discovery data does not.

- `checkpoints` contains only a small versioned session/profile/pool-counter header. Results and pages are independent bounded rows; a full multi-MiB pool is never a single CursorWindow BLOB.
- Results contain their normalized fields and dedup aliases; `pages` stores ordered result IDs. The in-memory coordinator computes identity indices. Updates, aliases, Seen discovered facts and provider cursor share one SQLite transaction.
- History is keyed by stable profile ID derived from normalized queryKey and fingerprint/aliases. Demo and real profiles cannot mix. Resetting progress does not erase Seen; clearing Seen does not reset progress. Clearing history bumps the live view revision to reject stale exposure callbacks.
- Startup first hydrates settings, then restores the committed checkpoint and page. Restoring advances epoch; in-flight requests are never restored. Current route is saved as a nonsensitive Activity-state boolean. Initial preference hydration does not trigger a fake user view revision that discards restored pages.
- SQLite schema version is 1. Unknown upgrades/downgrades and corruption fail closed, preserve existing files, and display an honest unavailable state. No destructive migration or automatic reset is performed. A future schema change requires a real migration.
- Results/queries/URLs are private functional data. Backups and device transfer remain excluded by the existing manifest/rules. Technical errors contain enum codes only, not search text, URLs, titles, cursor contents or exception strings.
- Store/coordinator calls run off the Android UI thread. The recurring UI poll is cancelled when the app is backgrounded or Browser is selected. Available inventory is cached between pool/Seen changes; viewport persistence occurs only on actual stamp/page changes. The transaction adapter still validates and compares the bounded pool on checkpoint updates. Device I/O, memory, energy and throughput measurements remain pending; no performance acceptance is claimed.

## Remaining gates

1. User-selected real source, verified permission/terms/license and exact dependency admission if any; legal real-video sample set and quality measurements.
2. Android device/emulator acceptance: phone/tablet, dark/light/large fonts, layout/keyboard/accessibility, actual viewport occlusion, process death/rotation, filesystem-full/SQLite integrity and restart.
3. Current UI renders a SkinKit placeholder for optional posters. A future admitted source's thumbnail fetch must add bounded, cancellation-aware URL/network policy before claiming real poster delivery.
4. Real-source transport must enforce declared hosts, redirects/resolved addresses, TLS, five-second connect limit, supplied body/deadline/cancel budgets. The framework contract does not claim an absent adapter enforces these on the network. Java cannot safely force-kill an uncooperative provider or cancellation hook. Cancelled but physically retained runtime slots remain occupied; no new work is admitted beyond the operation limit, and reducing the mode prevents new launches until old work drains. Browser uses independent resources.
5. No APK/AAB assembly, installation, account login, payment, deployment or publication was run.

M3 work that is independent of the unresolved real-source gate may proceed under the user's milestone authorization, without relabelling M2 as accepted.

## Final executed verification

- Fresh `--offline` run with unchanged dependency locks and SHA-256 verification metadata: Debug/Release Kotlin/Java compilation, Debug app tests, Core/Theme/Discovery tests, both lint variants and runtime inventory **PASS**.
- **131 JVM tests**: Discovery 65, App 27, Core Domain 30, Theme 9; zero failures/errors/skips. Core Model has no test sources.
- Both lint variants: zero Errors/Fatals; four warnings and two hints each (existing SDK-version/KTX/primitive-state suggestions). No lint baseline suppression was added.
- Exact 55 admitted external runtime artifact identities/bytes, seven SkinKit getters, platform-neutral module imports, Debug-only Fake/demo and Release empty-source boundary checks **PASS**.
- Host SQLite transaction/independence/unchanged-upsert/500-wide-row smoke tests **PASS**; Android runtime and physical device tests remain **NOT_RUN**.
- Final log: `evidence/final-strict-offline.log`; machine-readable totals: `evidence/verification-summary.json`. Earlier integration failures and pre-budget verification logs are retained but do not replace the final run.
