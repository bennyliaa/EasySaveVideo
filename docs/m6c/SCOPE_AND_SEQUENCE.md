# M6-C: actual authorized-frame region selection

Baseline: M6-B `f3253eadaf8f2d50acc6e64c02aa610f5f87bda2`. WIP: `b7a36c42ded9b48eecd8d120fb199847b87f0324`. User-authorized M6 ordinary-media code/host work only. Vault remains disabled; its blocked review was neither touched nor retried. API31 minimum, phone/tablet, SkinKit, existing entitlements and exact74 admitted dependencies stay unchanged.

## Bounded visible-source flow

1. The user explicitly chooses **authorized-frame mode** and audio choice. The existing **proportional schematic mode** remains separately labelled and works as before.
2. The app removes recording/selector controls and keeps its current ESV browser visible. A focused, resumed hardware-renderer host draw must be submitted before system consent starts. There is no overlay permission, other-app launcher, notification handoff or claim to capture an arbitrary background app.
3. One system grant is transferred to the existing foreground service. One projection, one VirtualDisplay and one full-size OES surface are created, initially without any recording codec, audio capture or encoder output file. The runtime owns one native lane throughout. A distinct post-consent browser draw generation and a strictly newer source timestamp are required; source geometry/transform and API34+ actual size and visibility callbacks are checked.
4. One bounded full authorized frame is read on the native GL worker, display-oriented, into an immutable RAM bitmap. Full requested/callback dimensions do not identify the selected app or content. The user sees the actual supplied image, including any black/protected/wrong content, and can cancel. No screenshot cache, saved state, log, network, sharing, export, thumbnail, file or media-library entry is created for that frame.
5. A secure selector window is established before acquiring the drawing lease. Source coordinates, preview scaling, fit-centred letterboxing, drag/body/edges/corners and incremental controls use one full-source origin; insets are metadata, not a second origin. The effective crop is inward-even aligned and must meet existing 128-pixel minimum and M6-A output limits. No silent downscale, geometry reinterpretation or full-screen-file fallback.
6. Confirmation requires the exact session, snapshot metadata/sequence, geometry generation, actual source raster/transform, unexpired lifetime and active authority. The frame is masked and its UI actually disposed. A third, separately correlated uncovered browser renderer submission and a newer latched source frame must then pass. Only afterward is the crop recipe placed in the ordinary private segment journal, and only then may recording encoder/output/audio resources begin. The same projection/VirtualDisplay/OES continues into M6-B pre-encoder crop and segment recording.

`registerFrameCommitCallback` establishes renderer submission, not on-screen visibility or a decoded video-frame guarantee. The later projection timestamp gate and visual confirmation are separate. SurfaceTexture timestamp clocks must be compatible with the monotonic freshness checks; future/stale/incompatible timestamps fail closed. Static producers that supply no sufficiently new post-transition frame may time out. No stale frame, duplicated video frame or fake CFR is encoded to manufacture success.

## Invalidation and native ownership

- Background/lock, Activity destruction, geometry/transform changes, system stop, expiry and explicit cancellation invalidate pending publication/confirmation/recording admission. Ordinary consent's expected foreground transition happens before preview eligibility; no preview can publish until the post-grant host returns and draws.
- Stale generations and other-session callbacks are inert. No old snapshot or process state can restore capture authority. Audio-denial retry retains the selected mode and requires fresh consent/selection.
- A snapshot/selection timeout revokes authority but never releases native/global ownership. The actual worker must finish all native cleanup. Uncertain GL, projection, codec or descriptor release remains fenced and visibly recoverable. Cancelling the preview returns to recording controls even if cleanup is stuck.
- Secure flags belong to the selector dialog. The app never clears another owner's secure flags. Shared bitmaps are never manually recycled while a Compose/RenderThread reference might exist. Dropping references/staging-buffer clearing is not a physical RAM/GPU erasure guarantee.

## Resource bounds

- One offered snapshot, one readback attempt, one coalesced pending frame, and one active preview session.
- Preview raster at most 1280 pixels on either edge and 921,600 pixels (3,686,400 bytes per RGBA/ARGB buffer); never larger than the actual source.
- Readback peak explicitly includes a direct RGBA buffer, ARGB integer array and immutable bitmap, up to 11,059,200 bytes together, plus a bounded preview pbuffer and separately platform-managed full-source OES/VirtualDisplay buffers. Driver/compositor memory is not claimed to equal the CPU budget.
- Drawing acquisitions are capped at two. One-shot renderer requests have one pending callback, explicit unregister/late-result guards, and no image retention in saved state.
- Pre-consent browser/permission reservation budget 120 seconds. After the granted service creates the preview gate, initial host/frame budget is 15 seconds; snapshot lifetime at most 60 seconds; confirm/disposal/uncovered-host transition at most 5 seconds. Native encoder setup then has the existing 15-second budget; the outer preview preparation watchdog is 100 seconds and retains fences on timeout.
- Successful unexpired gate admission still waits for a newly latched same-source frame within snapshot lifetime. That completed handoff becomes recording authority with its own 15-second encoder setup budget; the frozen image lifetime is not an ongoing recording timeout. Cancellation, focus, geometry and transform checks continue. A setup checkpoint runs after journal preparation and before output resources start.
- No refresh is offered in this slice. A new snapshot requires cancellation, actual resource release, a fresh attempt and fresh system consent.

## Unchanged behavior and limits

M4 raw recording, M6-B pause/32 actual resumes, ordered private segment ownership, retained-source recovery, postprocessing admission/cancellation and M6-A editor workflows remain. Requested display scope, observed dimensions and preview pixels cannot independently establish legal ownership/content identity. System bars, notifications and later content changes may be recorded. Audio is not geometrically cropped and never falls back to microphone.

This is a development implementation checkpoint. All real Android permissions, actual projection, GPU/readback/crop, secure-window/recents behavior, codec/audio synchronization, OEM/static/video timing and phone/tablet touch/accessibility remain NOT_RUN. No APK/AAB or device capture is built/performed by this task.
