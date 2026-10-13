# Android acceptance — NOT RUN

No APK was assembled or installed, no device/emulator was operated, and no public webpage, real login/account or credential was used for this slice.

Future authorized owned/synthetic fixtures should verify:

- Alert acknowledgment, confirm true/false, prompt default/edited/empty/null cancellation.
- Same-origin page and same-origin frame support; cross-origin/opaque frame rejection; malformed source rejection with one canceled result.
- UI Back/outside dismissal and duplicate taps; no stale result can complete a newer document even on same-URL reload.
- Selected-tab switch-away-and-back, closing tab, navigation, POST/script/history changes, background/resume, rotation, browser route disposal and JavaScript disabled.
- A background tab redirect/script navigation/timeout/eviction/renderer loss does not cancel another tab's prompt.
- Renderer process failure safely retires results; no WebView/Activity retained after destruction.
- Three-dialog/document and global time-window caps; repeated page navigation cannot bypass global display cap; user block persists for that document.
- Fullscreen, file selection, settings/bookmarks/cookie/save modals and recording preview cannot overlap an active JS dialog.
- Short landscape/split viewport, IME, 1×/2×/3.2× fonts and very long message/source: one scroll owner; source/message start at top for every request; all buttons reachable; screen-reader focus/source announcement.
- Prompt state is not restored after recreation; password suggestions/autocorrection behavior and secure-screen capture policy match platform expectations. No app logs, checkpoints or diagnostics expose input/default/message/source.
- Explicit before-unload cancellation and unchanged HTTP auth/permission boundaries.

Only after an authorized Android run may native timing/rendering assertions be marked passed. Full website/login compatibility requires separate evidence; synthetic JVM tests are insufficient.
