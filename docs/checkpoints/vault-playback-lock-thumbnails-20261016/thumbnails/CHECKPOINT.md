# Private Vault memory thumbnails

Source is connected to the protected Vault host, same Vault read owner, and the admitted MediaMetadataRetriever single-frame extraction path. Production admission remains false.

- Explicitly load currently visible cards; exact viewport and reveal identity govern delivery and drawing.
- No plaintext temporary file or ordinary shared image cache. Frames remain memory-only.
- One bounded native extraction at a time; encoded read budgets, image dimensions/allocation, total retained/retiring frames, watchdog cancellation and exact release evidence are enforced.
- Locking, leaving, loss of protection and offscreen changes revoke display. Unknown native or frame cleanup retains obligations and blocks reuse; cancellation does not imply native drain.
- Ordinary continuous screen recording is not stopped by this feature. Protected Vault presentation is refused while its required protection is unavailable.

## Execution evidence

The first focused run compiled both variants but had one obsolete constructor-string contract failure (5,053 pass / 1 fail). That evidence is retained unchanged.

The second focused run completed successfully in 3m45s: 494 suites, 5,055 tests, zero failures/errors/skips. It includes the additional real runtime-seam queued-start revocation regression and repaired constructor wiring assertion. Some unchanged module tests were reused by Gradle; application tests and both Kotlin variants executed on the revised source.

The final full run completed successfully in 8m23s: 13 modules, 591 suites and 6,563 tests (6,561 pass, two existing skips, zero failures/errors). Both lint variants have zero errors, 86 warnings and 10 notes each; warning counts are unchanged from the prior playback/auto-lock checkpoint. Final source manifest covers 2,788 files.

## Remaining acceptance

Android MediaMetadataRetriever decode, MediaDataSource callbacks, device memory behaviour, protected Compose drawing, real Handler timing and physical native release have not been executed on a device. Host/fake port tests do not establish those facts. No real user key, credential, media operation or platform authentication was performed. Production enablement requires its separate acceptance gates.
