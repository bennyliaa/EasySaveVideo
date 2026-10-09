# Android acceptance gates, not run

No APK/AAB, device install or emulator run was authorized for this checkpoint. These checks remain open before device or full M2 acceptance:

- API31 phone and tablet: cold startup with no contact, source disabled, and configured Commons; browser remains usable in each state
- Safe search defaults on; zero query traffic until explicit confirmation; cancellation/back/dismissal never turns it off; persisted choice survives rotation/background/process restart accurately
- Query edit, repeated lookup, equivalent normalized query, stale result and cursor expiration paths; source disable during DNS/connect/TLS/body/parse
- Real Android system trust and hostname checks, selected public peer pinning, ordinary network failures and actual socket cancellation; no ambient cookies/authenticator/proxy or redirected contact
- Actual 429/503 Retry-After with stalled body and slow close, then new query/visibility/source toggle; no early network retry or unbounded physical worker growth
- Actual SQLiteOpenHelper v1→v2 migration on copied fixtures; interruption and rollback; malformed/future rights rows preserved; CursorWindow behavior near bounded 500-row pool; Seen/progress independence
- Author/unknown, license/unknown, custom attribution, multi-source conflicts, revision/UTC observation and full provenance legibility with long Unicode text; no HTML/Markdown/author-link execution
- Dedicated file/license/terms buttons navigate only on user action; resulting source-page navigation is not automatic media-save approval
- Phone/tablet layout, accessibility, font scaling, dialog back/dismissal/repeated clicks and obscured-card Seen tracking
- Debug synthetic controls absent from Release; in-package dependency notices and proprietary distribution gates remain intact

The three prior real metadata fixtures establish API response shape. Offline injected socket/JVM and source-extracted host SQL tests do not satisfy these Android gates. No thumbnail/video download or general HLS/device acceptance is implied.
