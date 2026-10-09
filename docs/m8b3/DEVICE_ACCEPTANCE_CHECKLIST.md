# Future device acceptance — NOT RUN

This is a future checklist, not authorization for ESV APK/AAB assembly, install,
emulator or physical-device work. B3 was verified only by compilation, JVM/host
fixtures and source review; Android SQLiteOpenHelper, Compose, WebView and real OS
sensors were not executed in this environment.

With separate authorization, inspect phone/tablet Android API31+ behavior:
- Rapid query and duration IME edits, repeated submit, source switches, stale safety
  confirmation, navigation away/back, rotation and process restoration.
- Unavailable checkpoint storage retains draft and labels applied values unknown.
- Production has no synthetic power controls or OS monitoring claims. Existing
  static mode remains visible with explicit no-measurement disclaimer.
- Actual Save/Record owners and background/hidden Discovery cancel work immediately;
  paused/draining owners do not resume source work on screen changes.
- An independently scoped trusted OS adapter must be tested for freshness, callback
  revocation, unknown inputs and no-background-work before any production activation.
- Real energy assessment needs documented devices, workloads and baseline measurements;
  host request limits alone are not proof of battery savings.
