# M3-A remaining acceptance gates

All checks below are NOT_RUN. No APK/device permission was requested or assumed.

- API31 phone and current-target phone/tablet: cold startup, offline/zero Provider, rotation, font scaling, short/narrow screens, all task controls reachable.
- Explicit Save only: no scans during ordinary browsing; same-URL reload, redirect, back/forward, tab switch away/back, renderer crash, 60-second stale selection and cancellation reject late candidates.
- HTTPS synthetic authorized test origin: TLS rejection, redirects, actual peer checks, exact-URL cookies, CookieHandler fail-closed, DNS stall, remote close/cancel, credential expiry and public resume.
- 100 MiB versus 1 GiB on-device memory and storage budget; no linear buffering. Transfer throughput with actual SQLite/fsync checkpoint cadence.
- Real MP4 corpus: AVC/AAC, silent video, multiple tracks, rotation, audio expectation, malformed moov/mdat, truncation at tail, forged duration, sparse/long GOP, encrypted samples, unsupported codecs, MOV/WebM, HTML and zero bytes.
- Android MediaCodec output-PTS and MMR frame checks at head/middle/tail. Decoder stalls retain the one native lane, block new work, show draining and never publish late results.
- Activity recreation, switch/close source tab, actual background, process kill and manual resume. Incomplete tasks after process death require re-selection; no persisted signed URL or credentials.
- Android SQLite/full disk/FS faults at intent, fsync, non-overwriting rename, directory sync, provisional index, SUCCEEDED CAS and cleanup; repeated recovery creates one media item and preserves all old media.
- Cancellation racing commit: single terminal result and no cancelled media publication. UI acknowledgement under slow storage, no main-thread ANR; measure rather than assume the 1-second target.
- App no-backup boundaries; no token/URL/title/header/filename leakage through runtime/logcat/error paths. Protected media produces a refusal and never switches to recording.
- Stream/HLS independent MP4 remains a separate implementation gate; Record and media-library/export require their own milestones.
