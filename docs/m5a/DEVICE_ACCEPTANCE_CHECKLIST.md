# M5-A device and remaining feature gates

All items below are NOT_RUN. Synthetic host tests cannot establish these outcomes.

- API31–36 phone/tablet layout, scrolling, keyboard/insets, accessibility, rotation and theme adaptation
- Actual SQLiteOpenHelper v1→v2 upgrade, interrupted upgrade rollback and low-space recovery on Android filesystems
- Save Direct/HLS/Record completion publishes exactly one ordinary-library item in the same DB transaction
- Playing a verified current item through MediaPlayer, pause/restart/seek, foreground loss, surface loss, rotation and native-release failure
- Delete confirmation cancel/back/background/recreation; repeated confirmations; stale selected revision; actual reader closure and deferred unlink
- Process kill at every durable-delete phase, pending cleanup visibility and retry without resurrecting deleted media
- Actual hardware/native file descriptor ownership and error cases, including a hung release
- Large library latency/memory, 1000-folder bounds, search pagination invalidation and locale/unicode display
- A3 MediaStore/SAF/export/share output and grants: NOT_IMPLEMENTED in this slice
- Thumbnail extraction/cache/invalidation: NOT_IMPLEMENTED in this slice
- Vault authentication, encryption, private metadata and recovery: NOT_IMPLEMENTED in this slice
- APK/AAB build, install, distribution and store/release acceptance: NOT_RUN
