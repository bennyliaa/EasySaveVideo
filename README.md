# EasySaveVideo

Private Android 12+ (API31) phone/tablet project. Kotlin + Compose Foundation + SystemWebView; unconfigured builds have zero search Providers; an explicitly configured Wikimedia Commons metadata source is available. SkinKit Contract/Default 0.0.1 supplies every visual category.

## Project folders
- `app/`: Android composition, SystemWebView browser, settings and bounded startup recovery
- `core-model/`, `core-domain/`: platform-independent contracts/state machines/tests
- `discovery/`: original JVM Provider/Validation/Pool/Seen/Coordinator/Scheduler framework
- `commons-provider/`: original anonymous Wikimedia Commons metadata client, continuation, rights, Discovery bridge and signed built-in parameter maintenance
- `save/`: platform-neutral Direct/Stream coordinator, journal and media-verification contracts
- `hls/`: original strict static-HLS parser and immutable fixed-selection plans
- `recording/`: platform-neutral capture lifecycle, bounded timeline and resume policy
- `library/`: platform-neutral ordinary catalog, naming/paging/folder rules and per-item leases
- `ui-theme/`: app-owned complete SkinKit defaults and immutable snapshots
- [docs/requirement and design/](docs/requirement%20and%20design/): original specifications (Architecture update_1 retains UTF-16LE original)
- `docs/m1/`: reconstruction and foundation verification
- `docs/m2/`: historical Discovery framework development evidence
- `docs/m2-commons/`: first real metadata-source integration, migration, rights UI and development evidence
- `docs/m3a/`: Direct HTTPS MP4 development slice; Stream and device acceptance remain pending
- `docs/m3b/`: historical restricted local-only Media3 HLS checkpoint
- `docs/m3b-eof/`: current guarded EOF compatibility, conserved samples, terminal decode contract and separate device gates
- `docs/m4/`: bounded Record development checkpoint and explicit native/device gates
- `docs/m5a/`: ordinary-library A1/A2 foundation checkpoint, lifecycle review and remaining features
- `docs/m7a/`: historical signed Commons parameter maintenance baseline
- `docs/m7b/`: current offline signed repository security policy, independent review and explicit operational/device limits
- `third_party/`: preserved baseline plus fixed Media3 license evidence; exact current 74-artifact admission
- `vendor/maven/`: exact internally authorized SkinKit artifacts, not a public relicensing
- `vendor/media3-maven/`: exact reviewed Media3/transitive binaries and original metadata
- `artifacts/`: reserved for explicitly requested future installation packages; no APK is generated now
- `scripts/`: repeatable checks and build entry points

Each milestone gets a verified commit and a clearly named development/accepted tag. Complete Git bundle and source backups are kept privately outside this checkout; build caches and SDKs are not project backups.

## Verification
`./scripts/gradle-workspace.sh --offline --dependency-verification=strict :core-model:test :core-domain:test :ui-theme:test :discovery:test :commons-provider:test :save:test :recording:test :library:test :hls:test :app:compileDebugKotlin :app:compileReleaseKotlin :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:runtimeInventory`

Set Android SDK and Gradle cache environment paths in another environment. This convenience script only derives its invocation's existing proxy; it never edits trust stores or disables TLS. Avoid concurrent Gradle invocations.

Read `docs/m1/RECONSTRUCTION.md` before interpreting this fresh Git history. Historical M0 commits are not represented as recovered. No public license is assigned to the user's app or SkinKit code.

## M2 boundary

Both variants may register Wikimedia Commons when the already-approved service contact is injected at build time. Without that contact, or when the source is disabled, there are zero production Providers and browser navigation remains available. Default safe search is on: Commons reports that it cannot support it and makes no query until the user explicitly confirms disabling that filter. Debug's opt-in synthetic fixture remains local-only and excluded from Release.

The actual anonymous HTTPS adapter checks and pins public peers, keeps normal system TLS/hostname checks, rejects redirects/cookies/auth/proxies and bounds/cancels socket work. It searches only metadata for videos in file namespace 6. It does not fetch media, thumbnails or confer reuse/download permission. Cards and details retain author, license, custom credit, warnings and source provenance as inert text.

The existing SQLite database has an additive v1→v2 rights migration and bounded per-row snapshots. Feature rollback disables registration while retaining the v2 reader. Old v1-only binaries are not a compatible database rollback. Full Android SQLite/device/UI/network acceptance and packages remain NOT RUN. See `docs/m2-commons/IMPLEMENTATION_AND_ACCEPTANCE.md`, including contact injection instructions, for exact limits.

## M3-A boundary

Explicit Save scans only the current main document and requires resource selection. Original system HTTPS transport streams compatible MP4 into owned private storage; local Android extraction, timestamped decoding, frame checks, and a durable file/SQLite protocol gate success. Direct remains free and independent of Providers. Stream, recording, conversion, media-library playback and export are not implemented by this slice.

Development budgets are visible in the task center: one transfer, 2 GiB artifact cap, 4 GiB cumulative transfer budget, two-hour explicit execution window, 60-second validation window. These are injectable engineering limits, not final product limits or measured promises. Backgrounding checkpoints and waits for a manual foreground resume; no FGS, WorkManager or wake lock is added.


## M3-B boundary

The latest development slice adds explicit, entitlement-gated static HLS preparation and fixed-variant selection. Guarded HTTPS downloads bounded owned segments; the pinned Media3 extractor/Transformer path receives only allowlisted local input IDs, rejects decoding/encoding and DRM requests, and produces a real independently verified MP4 before the existing commit protocol can publish it. Stream source context remains memory-only; interrupted incomplete streams require reselection. Direct remains free.

This is a deliberately narrow AVC Baseline / optional multiplexed AAC profile with strict TS and timeline boundaries, not universal HLS support. The current EOF adapter admits ordinary no-tail AUD streams, multislice pictures, inherited parameter sets and unbounded PES under that profile, with the same capability-gated reader used in inspection and export. Stream completion additionally requires full independent MP4 sample conservation and actual final-GOP decoder output EOS, with a versioned fail-closed recovery contract. Host tests execute admitted reader and MP4 muxer/extractor bytecode using synthetic entropy; actual Android HLS/Transformer/MediaExtractor/native decoder/AV-sync, package assembly and device acceptance are NOT RUN. Full M3 and distribution remain gated. Read `docs/m3b-eof/IMPLEMENTATION_AND_ACCEPTANCE.md` before interpreting the development tag or test counts.


## M4 and M5-A boundaries

M4 adds explicit per-session system-consented recording, playback-only audio capture, bounded pause/resume, finalization, independent B-policy verification and published read-only playback. First/middle/final/effective-resume GOPs are actually decoded; AAC uses sparse readability checks. The actual-resume limit remains 32. Task codec v6 preserves genuine v1–v5 records; evidence-v3 compatibility remains unchanged. These are development code and host checks, not measured device duration or permission/native acceptance.

M5-A adds the ordinary-library foundation: one shared Save/catalog database, additive schema migration, exact current read capabilities, per-item leases, folders, local search/sort, logical rename/move, details, manual playback/seek and explicitly confirmed recoverable deletion. Active Save tasks remain reachable independently of the bounded recent history; successful/deleted history cannot exhaust the 100 unfinished-task limit or resurrect media. Library and future Vault are free.

M5-A3 adds user-triggered MediaStore/SAF export, bounded temporary read-only system sharing, and bounded native thumbnails. Each copy has its own result and cleanup lifecycle; the original remains intact. Public output is never a substitute for app-private saving, and chooser launch is not recipient delivery. See `docs/m5a3/IMPLEMENTATION_AND_ACCEPTANCE.md` for the current development status and exact verification limits.

M5-A is not a completed ordinary library or full M5. Configurable capture/save destinations, bulk folder deletion and Vault encryption/authentication remain outside these slices. Normal library files are app-private plaintext, not a Vault. Deletion clears the ordinary catalog's display metadata and file representation after readers release; historical Save provenance remains, so this is not secure metadata erasure. Earlier A1/A2 evidence and migration context remain in `docs/m5a/IMPLEMENTATION_AND_ACCEPTANCE.md`. Android/OEM, permissions, actual provider/recipient compatibility and real-media acceptance remain NOT_RUN.

## M6-A video-tools boundary

The ordinary-library editor has a real pinned Media3 trim/merge/display-crop/compression path with independent full MP4/sample/decode verification, typed edit provenance, new-item atomic publication and bounded native ownership. Original media is preserved. Time cuts snap inward to observed frame boundaries; the supported integer-CFR/AVC/SDR/AAC subset and resource limits are explicit. “Original quality” is not a lossless claim, no-op duplicates are avoided, and compression never promises a smaller file. Existing development-only entitlements remain unchanged.

This is an M6-A development checkpoint, not full M6 or Android release acceptance. Crop UI is a labeled schematic, native codec/GPU/OEM/visual quality acceptance remains NOT_RUN, and no APK/AAB was built. M6-B pre-record region capture and recording-segment orchestration remain separate. Vault stays disabled. See `docs/m6a/IMPLEMENTATION_AND_ACCEPTANCE.md` and its acceptance checklist before interpreting the development tag or test counts.

## M6-B region/segment development boundary

M6-B adds an explicitly limited ratio-diagram selector, original SurfaceTexture/EGL crop before encoding, and ordered app-private recorded segments. It does not show a captured screenshot for visual framing: chosen coordinates apply to the complete image the user subsequently authorizes in the system. A default-display request or matching callback dimensions never authenticates app/content identity. The complete authorized image can transiently enter GPU memory, but no full-screen temporary recording is written. Exact unsupported geometry or producer transforms fail closed; there is no full-screen-file fallback.

API31/32/33 use an explicit default-display/latched-surface branch without waiting for API34-only callbacks. API34+ additionally requires matching actual capture-size callbacks. Pause closes independent files under one projection/VirtualDisplay; only independently verified, actual-CFR-compatible sources enter typed recorded editing. Ordinary editor inputs remain limited to eight; one recording group permits initial plus32 actual resumes,30min effective media,2h wall time and2GiB total private input. Raw segments remain private and are retained until explicit discard, including after successful final publication. Native uncertainty and partial cleanup remain visible.

Final publication in ordinary editing and recorded assembly now has a shared bounded cancellation/admission point after long I/O. Cancellation before admission excludes publication; afterward UI says the commit/result is pending. Genuine historical codecs and checked database migration preserve prior records. See `docs/m6b/SCOPE_AND_SEQUENCE.md`, `IMPLEMENTATION_AND_ACCEPTANCE.md` and the device checklist for exact evidence and remaining limits. Actual-content preview framing, physical crop/native codec/GPU/OEM/AV-sync acceptance and package/device execution remain NOT_RUN or unimplemented as specifically identified. This is not full M6 or Android-release acceptance. Vault remains disabled.

## M6-C authorized-frame development boundary

M6-C adds a separate actual-authorized-frame selector while preserving the labelled proportional mode. It leaves ESV's current browser visible, obtains one system consent, and reads one bounded frame from that projection into memory. The secure frozen selector shows the supplied pixels for explicit region confirmation. Only after selector disposal and a fresh uncovered-browser/source frame can the same projection/VirtualDisplay begin the existing pre-encoder cropped recording pipeline. No preview file, saved-state bitmap, disk cache, export or audio capture is created before that confirmation.

This closes the M6-B code-level actual-preview gap within a deliberately visible-ESV-browser route; arbitrary other-app preview is not promised. Source identity cannot be proved from a default-display request or matching dimensions, and wrong/black frames can be cancelled. Actual Android/GPU/security-window/codec/OEM/phone/tablet visual behavior remains NOT_RUN, so this is not full M6 or release acceptance. See `docs/m6c/SCOPE_AND_SEQUENCE.md` and `IMPLEMENTATION_AND_ACCEPTANCE.md`. Vault remains disabled.


## M7-A built-in Commons parameter maintenance boundary

M7-A adds a closed signed package contract for the existing compiled Commons provider, monotonic version/high-water persistence, last-known-good/explicit rollback, a bounded cancelable injected update runtime and active-request policy fencing. Declarations only disable or tighten existing request/cache budgets; they cannot supply hosts, extraction logic or code. Signed disable is stable immediately. Enabling candidates require exact-request health evidence; any unconfirmed candidate is disabled after restart while its high-water remains.

Production trust roots and update endpoints are intentionally absent. This is local code/host verification, not a live remote service or full M7 extraction-rule maintenance. Existing Commons contact/user opt-in and zero-provider fallback remain. See `docs/m7a/IMPLEMENTATION_AND_ACCEPTANCE.md` and its independent/device checklists. The historical M7-A aggregate is `scripts/verify-provider-maintenance-workspace.sh` at its M7-A tag; the current checkout uses the M7-B command below. Free Library is unchanged, and Vault remains disabled and untouched.

## M7-B offline signed repository-policy boundary

M7-B adds independent policy signatures/version anti-replay, a nondecreasing safety floor,
cumulative package-version/key revocations, global disable and exact version plus full-envelope
SHA-256 rollback authorization. Every enabled downgrade/LKG path passes the current policy;
explicit rollback requires a grant even for a disabled target. Accepted package kill switches
remain signed barriers. Bundled defaults cannot restore authority after signed enrollment.

Policy/package roots remain independently empty in production, and there is no update endpoint,
root rotation, downloaded code or extraction interpreter. Revocations cannot be cleared; bounded
list exhaustion needs later reviewed work. Old format-1 state fails closed without reset/migration.
Clock protection is limited to the persisted pre-transaction bound and process-local observations;
coherent full-directory rollback is not prevented. No device or production acceptance is implied.

Run `scripts/verify-repository-policy-workspace.sh` for the current strict-offline aggregate.
See `docs/m7b/IMPLEMENTATION_AND_ACCEPTANCE.md`, `INDEPENDENT_SOURCE_REVIEW.md` and the device
checklist. This remains a development slice, not full M7. Free Library is unchanged; Vault is
still disabled, untouched and excluded from this work.

## Optional page-verification service development boundary

`verification-client/` and the independent source-only `verification-service/` add a versioned optional
second-layer Discovery path. Android has no default service and requires explicit, process-session-only
consent before sending complete candidate URLs; restart disables it. At most one candidate per source
response is attempted under a separate 3-second budget. Failed or insufficient evidence stays pending.
A service result is page-video evidence only, never proof of playback, downloadability or rights.

The service has no admitted public targets. Actual Scrapling/browser execution is confined to owned
isolated fixtures in the separately approved private evaluation runtime, which is excluded from
Android and backups. Deployment, public dynamic testing, binary redistribution/legal acceptance and
device/APK acceptance remain separate gates. Read `docs/verification-service/IMPLEMENTATION_AND_ACCEPTANCE.md`
and run `scripts/verify-verification-service-workspace.sh` only with its documented existing runtime.
