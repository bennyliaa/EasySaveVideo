# Review-driven corrections

The independent M1 safety review caused these corrections before the development baseline:

- Bounded the ERROR writer queue and switched retention to immutable day buckets, with startup pruning; old lines cannot survive forever through updated mtime.
- Added a WebView-entry fence for every controlled navigation; old Views cannot borrow the newer Core request. Added expected-start/redirect admission and regression tests. onPageStarted uses its callback URL rather than assuming getUrl has already changed.
- Bound fullscreen show/hide ownership to the exact current Entry.
- Processed each renderer-gone callback for its exact View, rather than guessing that healthy tabs share the dead renderer; destroy is idempotent.
- Resumed global WebView timers when a new foreground View is created.
- Preserved a pending file-choice callback through the system picker pause; tab/epoch/origin are checked on return. Camera, microphone, geolocation, foreign schemes and TLS failures remain fail-closed.
- Discarded WebChrome console messages and disabled WebContents debugging for both variants.
- Prevented startup settings application from writing an initial blank session over recovered tabs; respected an early user change to restore preferences.
- Paused checkpoint writes after failed/timed-out recovery, keeping unread/corrupt state until the user explicitly resets saved addresses.
- Surfaced asynchronous persistence failures with content-free UI warnings.
- Removed the interactive AndroidView after Stop/FAILED and rejected its fullscreen/file callbacks, so suppressed late navigation cannot silently change a visible page.
- Tightened MIME validation after the real negative test caught a punctuation-only type accepted by the first version.

These are source/compiled/JVM evidence. Real renderer timing, system document provider, Android lifecycle, shared cookies and playback remain device acceptance items; the review is not a substitute for platform execution.
