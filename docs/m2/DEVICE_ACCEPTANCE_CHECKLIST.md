# M2 device acceptance: NOT_RUN

No APK/AAB or installation is authorized in this checkpoint. These are pending tests, not pass claims.

- Phone two-column defaults, tablet adaptive grid, light/dark SkinKit, system font scaling, narrow widths, landscape and keyboard visibility; semantic controls remain reachable.
- Explicit demo enabled/disabled, persistent real store untouched, empty real-source state honest; Browser always opens independently.
- First visible card at <50%, exactly 50%, interrupted dwell, scroll, clipping, options/clear dialogs, background, Browser switch. SHOWN only after continuous 500ms actual exposure.
- Clear Seen in an open dialog: no immediate hidden re-mark while the dialog remains; old page/revision callbacks rejected.
- Change query/filter while provider is slow, switch Browser/background, return; stale result/cursor/Seen events never cross session epoch/queryKey.
- Current page remains stable when new results arrive; previous pages remain navigable; current anchor survives explicit repeat-policy/page-size change.
- Save settings P100/HIDE_SHOWN, materialize three pages, select page three, restart: all three pages remain and selection restores. Browser route remains Browser after rotation/process recreation.
- Real SQLite commit interrupted before/after cursor/result writes: either complete prior or complete new state; no forward cursor with absent results. Full disk and corrupt/unknown schema preserve data and isolate Discovery.
- Maximum bounded 500-item pool with long Unicode fields; individual rows fit CursorWindow. No whole-pool BLOB reads; monitor memory/I/O/energy on device.
- Repeated empty/retry/cooldown states: no automatic unbounded refill; six-attempt round and 1/2/3 limits; source failure never blocks Browser.
- Start/end a future real save/record via priority interface: active discovery cancels promptly and resumes when foreground Discovery becomes eligible. No save/record execution exists yet.
- Release source/class inspection and runtime: zero Providers, no demo or fake entitlement source; actual package review only when packaging is authorized.
