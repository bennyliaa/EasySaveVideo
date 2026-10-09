# Deferred Android acceptance — not an authorization or device result

No ESV APK/AAB, installation, emulator or physical-device run was performed here.
Host source/fixture evidence cannot close the following:

- Android 12/API31+ phone/tablet rendering and current SkinKit default adaptation.
- Real WebView start/finish/redirect/history ordering, same-URL reload, tab close,
  switch-away/back, renderer loss and Activity recreation during a pending Save.
- Long/late successful Save remains successful while Discovery attribution expires.
- Same-page unrelated media cannot earn SAVED; current bridge remains UNKNOWN.
- Incognito has no persistent origin/history association; restart creates no token.
- Native Save/HLS prepare/reject/cancel/drain and recording consent/pause/finalize/
  foreground-service teardown maintain application priority across screens.
- Main queue delay during initialization and segment recovery never advertises idle.
- Actual due cleanup waits for priority admission; ordinary idle statistics do not
  repeatedly restart source demand.
- A final history failure never retries/reports failure of committed media.
- SQLite power-loss/disk-full behavior, platform event fidelity and actual resource
  release timing remain device-specific work outside this slice.

SAVED/PLAYED trustworthy video identity needs its own separately scoped design and
review. It must not be synthesized from DOM detection, fullscreen or page identity.
