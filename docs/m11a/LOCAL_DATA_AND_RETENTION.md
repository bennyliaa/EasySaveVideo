# Local data, retention and cleanup: source inventory

`A/` = `app/src/main/kotlin/com/easysavevideo/app/`;
`C/` = `commons-provider/src/main/kotlin/com/easysavevideo/commons/`;
`D/` = `discovery/src/main/kotlin/com/easysavevideo/discovery/`.
Bounds below are code values, not measured device guarantees or legal retention
promises. App-private ordinary files are plaintext at the app layer; Vault is disabled.

## Categories and lifetime

| Category | Actual location/content | Bound, retention and cleanup |
|---|---|---|
| Settings | SharedPreferences `browser_settings_v1`: JS/cookie/tab-restore and Discovery display/repeat/mode; `discovery_sources`: Commons switch | Persisted until changed/app data removal; no scheduled expiry. `A/storage/AndroidStores.kt:14–33`; `A/discovery/AndroidDiscoveryController.kt:151–152,331` |
| Browser restored tabs | `noBackupFilesDir/browser_session_v1.bin`: selected tab/current addresses; no cookie/history blob | Up to 12 tabs / 128 KiB; disabling restore writes an empty session. `AndroidStores.kt:37–48`; `BrowserSessionCodec.kt:9–24`; `BrowserPersistence.kt:79–85` |
| WebView site state | Platform-managed cookies, cache and DOM storage; not included in tab checkpoint | Separate cookie and cache controls; no app-wide WebStorage deletion found. No universal lifetime or secure-erasure claim. `A/browser/SystemBrowserHost.kt:216–234,287–307` |
| Discovery functional database | `discovery_v1.db`: query/filter/locale checkpoints, result URL/title/description/rights, page order, cursor/progress, Seen aliases/events, viewport | Capacity pruning, not timed expiry: 32 profiles / 65,536 aliases / 512 progress rows / 8 checkpoints; dirty transactions prune and protect current paged evidence. `A/storage/AndroidDiscoveryStore.kt:16–25,111–141`; `DiscoveryCheckpointCodec.kt:18–39`; `D/DiscoveryRetention.kt:3–10` |
| Commons response cache | In-memory parsed/source response pages only | At most 16 entries, 5 min max TTL and shared body cap; no-store/no-cache/private disables caching; no disk persistence. `C/CommonsModels.kt:42–54`; `CommonsMetadataProvider.kt:182–201` |
| Save journal and ordinary catalog | `noBackupFilesDir/save_m3_v1/save_m3_v1.db`; state/failure/source identity, origin-only durable resource, checkpoint/verification/receipt; catalog names/folders/time/geometry/hash/tombstones | Completed provenance/idempotency records are not active-task capacity and no timed purge was found. Headers/live credential leases are not persisted. `A/save/storage/AndroidSaveStorage.kt:45,88–115`; `SaveRecordCodec.kt:8–55`; `A/library/storage/LibrarySchema.kt:14–37` |
| Ordinary media/staging | Under private `save_m3_v1/media/` and `staging/` | Verified publication/recovery and exact-owned deletion; no blanket expiry of completed media; failed cleanup may remain visible. `A/save/storage/PrivateCommitProtocol.kt:62–64`; `A/library/storage/OrdinaryMediaFiles.kt:22–31` |
| Edit operations and staging | Same DB: request/source/proof/verification; private `edit-staging/` | Inputs retained; outputs published as new verified items; pending/cancelled cleanup has own recovery. No completed-provenance TTL found. `A/editor/storage/EditSchema.kt:8–33`; `EditArtifactFiles.kt:18–19` |
| Recording groups/segments | Same DB: timing/geometry/hash/capture/proof/recovery; `recording-segments/` | Raw segments retained until explicit discard, including after final publication. No automatic success-based source deletion. `A/recording/segments/RecordedSegmentSchema.kt:8–24`; `RecordedSegmentFiles.kt:19`; `docs/m6b/SEGMENT_STORAGE_AND_EDITOR_BRIDGE.md` |
| Provider maintenance | `filesDir/provider-maintenance/provider-state.bin` plus intent/next/initialized transactional files | Persisted signed version/high-water/LKG/security state; no arbitrary reset/purge or current live endpoint. `A/discovery/CommonsSourceRegistration.kt:30`; `C/maintenance/ProviderRepository.kt:133–136` |
| Technical error logs | `noBackupFilesDir/technical_errors`; fixed timestamp/module/code/API/status fields | Initialization/write-triggered pruning: 7-day age ceiling, at most 5 daily buckets, roughly 51 KiB per bucket (one bounded line can cross the pre-append check). No background scheduled purge while app is not running. No URLs/titles/cookies/tokens/exception strings. `A/storage/AndroidStores.kt:52–85` |
| Thumbnails and capture preview | Local media descriptors, memory-only thumbnail bitmaps; authorized capture-frame selector memory | No disk thumbnail cache; preview pixels not written as screenshot files; actual Android memory/window/native behavior unverified. `A/library/thumbnail/AndroidLibraryThumbnails.kt:48–50,137–147`; `docs/m6c/IMPLEMENTATION_AND_ACCEPTANCE.md` |
| Credentials/store grants | Save resource cookie leases in memory; store adapter unavailable snapshot | No persisted Save headers/cookies or real payment receipts/account/grants in current adapter. Ordinary WebView cookie store remains separate; “no cookies stored” would be false |

Backup declarations set `allowBackup=false`, `fullBackupContent=false` and exclude
root/file/database/sharedpref/external domains from cloud backup and device transfer
(`AndroidManifest.xml:9–11`; `res/xml/data_extraction_rules.xml:3–11`). This is source
configuration, not tested OEM backup/uninstall behavior or a guarantee against other
copying. App data clearing/uninstall and flash secure erasure were not executed.

## What the user's current clearing controls do

- **Disable restore:** empties the saved tab checkpoint, not cookies or all site data.
- **Clear Cookie:** requests removeAllCookies/flush across ordinary shared WebView
  tabs. **Clear cache:** separately requests WebView disk-cache clearing. Neither
  is an implemented “delete every website's DOM storage/history” feature.
- **Clear Seen:** removes target Seen event rows while preserving checkpoints/current
  result pages and Provider progress. **Reset progress:** removes progress only.
  It does not erase stored query/result snapshots or media.
  `AndroidDiscoveryStore.kt:244–247,291–309`.
- **Delete library item:** durably marks DELETE_PENDING, waits for readers, then
  unlinks the exact owned media. Current display fields are blanked, but logical
  tombstones/operation records and separate historical Save provenance remain.
  This is neither immediate guaranteed deletion, secure overwrite nor full metadata
  erasure. `AndroidLibraryRepository.kt:138–159,269–296`.
- **Recorded-segment discard:** is separate from successful assembly and ordinary
  final-item deletion. Original segment retention is not controlled by the Seen,
  cookie, cache or log controls.

## Export/share copies and residuals

Gallery export creates a separate MP4 in `Movies/EasySaveVideo`, initially pending,
then published after copy verification (`A/library/export/AndroidExportPort.kt:
31–36,110–120,184–194`). Deleting the internal original does not recall this copy.

SAF export records target URI and temporary grant flags; persisted URI text is not
recreated permission after process death (`ExportSchema.kt:8–16`;
`AndroidExportPort.kt:38–50,229–245`). Provider cleanup can be pending/unknown/failed,
and partial/external copies may persist (:196–215). `LibraryExportDialog.kt:39–44`
warns that the selected service may sync to cloud. Do not promise local-only exports.

Share copies use `noBackupFilesDir/library_share_v1`, read-only grants, 10 min expiry,
at most 4 copies / 256 MiB each / 512 MiB total (`LibraryShareOwner.kt:60,151–157`;
`SharePolicy.kt:5–19`). Expiry/revocation denies further access, but unlink/cleanup
waits for actual reader release; failed cleanup retains capacity and retries later
or at startup (`LibraryShareOwner.kt:268–309`). Expiry is not proof the file vanished
at that instant. Recipients' copied bytes cannot be recalled, and chooser launch
is not delivery or proof that any chosen recipient received the file.

No app source TTL/purge was found for all completed Save/library/export/editor
provenance. Do not apply the bounded Discovery/log/share lifetimes to every database,
metadata field or external copy. A future privacy statement must be approved against
the final market, source configuration and implemented clearing behavior.
