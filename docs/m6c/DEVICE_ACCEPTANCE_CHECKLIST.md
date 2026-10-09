# M6-C deferred device acceptance

Every row below is NOT_RUN. Host tests and source guards cannot turn any row into a pass. Test API31/32/33 legacy default-display and API34/35/36 callback scope separately, on representative phones/tablets/OEMs.

- Actual one-consent/one-VirtualDisplay lifecycle from no-encoder preview through recording and32 actual resumes
- Actual authorized pixels from current ESV browser after consent, including static pages, live video, motion, buffering and fullscreen; no arbitrary other-app visibility promise
- Default-display request overridden by system/OEM, same-size wrong source, selected app hidden, callback ordering/missing visibility/resize, black/protected content and user cancellation
- First snapshot excludes old recording/selector/consent frame; post-confirm output excludes secure selector/black transition; source timestamps compatible with System.nanoTime and genuinely new after both barriers
- Hardware frame-commit callback on phone/tablet, lost focus, stopped/destroyed Activity, hardware acceleration absent and interrupted dialog dismissal
- Secure selector before first pixel draw; screenshots/recents/task switch/lock do not expose frozen image; another owner's FLAG_SECURE remains intact
- Background/rotation/inset change/source transform change/lock/system stop at every pre-record stage including readback, queued publication, drag, confirm, disposal and journal creation
- Pixel channel order, vertical orientation, rotation0/90/180/270, producer flips, full-frame scale, letterboxing, near-edge/odd boundaries, inward-even crop and exact encoded physical pixels
- Drag/body/four edges/four corners, small/oversized rectangle error correction, reset/full/cancel, accessibility and large-screen layout with SkinKit
- Preview expiry, delayed callback, synchronous user double action, revoked entitlement, low memory, pbuffer failure/readback hang and uncertain EGL/projection release
- No recording codec/output/audio before explicit confirmed selector retirement; audio permission denial with explicit no-audio fresh retry preserving chosen preview mode
- Existing M4/M6-B pause/resume/stop/discard,32 actual resumes, private input retention, source-reader fencing, publication admission/cancel and M6-A editing unaffected
- Real codec/audio priming, A/V alignment, static/VFR frame cadence, long sessions, memory/thermal limits and interrupted files retain truthful outcomes

Acceptance requires observed evidence. No protected-content bypass, overlay permission, physical memory-erasure guarantee, fabricated frame or full-screen recording fallback is allowed to make a test pass.
