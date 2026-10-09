# M3-A review changes

All entries describe implemented source/contract changes. They do not imply Android device acceptance.

- Replaced metadata-only/three-non-null-frame reasoning with bounded MediaCodec decoded-output PTS evidence for start/middle/end, including present audio tracks, plus actual MMR frame retrieval.
- Fenced late native results and retained a single process-wide occupied lane after timeout. Native release/FD close uncertainty is sticky and blocks new execution until process recovery. Constructed native handles are immediately protected by finally cleanup.
- Rejected stale selected-page results by tab/session/epoch/View/URL/interaction revision, and rechecked selection before submission. Session capture is same-origin and exact-resource, with bounded memory-only leases.
- Removed full and signed resource URLs from durable records. Private storage retains origin, source hash, offset and strong ETag. Source eligibility has an absolute 15-minute lifetime; ordinary resume does not extend it. Missing source after expiry/process death requires explicit re-selection.
- Moved long provisional file/hash/inspection work outside the coordinator monitor. Explicit cancellation signals its token before storage access; only the short journal/index publication CAS arbitrates the terminal result. Snapshot reads are memory-only.
- Corrected a cancellation test's ordering: releasing the provisional-commit latch immediately after starting a canceller thread did not prove the cancel had run. The revised test waits for cancellation return before releasing commit, explicitly proving cancellation is nonblocking. The 31-test core suite was repeated ten times successfully before aggregate verification.
- Preserved all uncertain-intent and committed media. Added a process-exclusive directory lease, non-overwriting same-filesystem rename, no-follow path checks, directory fsync, provisional index and final visibility CAS. Owned empty restart siblings are recoverable across interrupted restart windows; unknown or nonempty siblings are preserved and rejected.
- Added `RESOURCE_RELEASE_UNCONFIRMED` ownership hooks. Neither repeated resume nor cancellation cleans an uncertain still-open resource. Task UI distinguishes a draining native operation from a restart-required release failure.
- Guarded lease creation errors on the UI thread. Task state lives in an application-scoped owner; Activity recreation and tab closure do not recreate task executors. Task information and controls share one scrollable list for constrained screens.
- Kept the existing 55-artifact runtime admission set. An all-project `--write-locks` run removed Kotlin common-metadata runtime/lint configuration entries from the existing app lock, then strict resolution still required those entries. Restored the previously verified app lock unchanged and kept the newly generated save-module lock. Final verification must use ordinary strict `--offline`, not `--write-locks` as a substitute.

## Remaining limits

No real Android TLS, WebView, MediaCodec/MMR, SQLite, SELinux, process-kill, power-loss, UI or performance run has been performed. Native parser/decoder interruption is not guaranteed. HTTP transport remains deliberately conservative about redirect credentials, global CookieHandler, proxies and private peers. A real-device test may reveal a supported-platform incompatibility and must be fixed before calling the Direct flow accepted. M3-A does not implement HLS/Stream, recording, conversion or a complete media library.
