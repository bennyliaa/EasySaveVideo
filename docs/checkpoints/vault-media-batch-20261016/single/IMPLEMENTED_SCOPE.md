# Single-media Vault connection checkpoint

Baseline: 92627321c1c41af54504d83eedbb4e850407926e. Production entry remains disabled.

## Implemented code
- Existing READY Library MP4 reference and exclusive source retirement reservation connect to the existing authenticated Vault owner.
- Staged encrypted object, full authenticated readback, encrypted catalog/journal CAS, and exact source cleanup receipt form the single-item move protocol.
- Registered bounded controlled reads obey lock/revocation and real resource-drain obligations.
- Single-item copy export uses ACTION_CREATE_DOCUMENT and a finite local primary-volume ExternalStorageProvider profile. It is a phone filesystem destination, not a MediaStore substitute. Vault original is retained.
- Export verifies the complete source before copying, verifies target readback, retains exact late picker/removed-target cleanup authority, and reconciles interrupted operations without replaying unknown writes.
- Existing root, optional recovery slots, media catalog and journal are preserved together. No additional authentication authority or database was introduced.

## Evidence and limits
- focused-first: compile failure (publish return type); non-app 416 tests pass; app tests not run.
- focused-second: actual dual compile pass; 4775 results, two old source-contract failures. Those failures are retained in the evidence.
- focused-third: actual dual compile and app 4360 pass; core/library 416 same-byte results reused. Total 4776, zero failures/errors/skips.
- Full 13-module forced regression: 568 suites, 6284 items = 6282 PASS + 2 existing SKIP; zero failures/errors, exit 0, SUCCESS 5m4. Both lint variants: 77 warnings + 10 notes, zero errors.
- Runtime coordination and ownership tests use fixed public fixtures and fake platforms. Static source checks and compilation do not prove Android SQL, SAF provider behavior, streaming primitive success, crash durability or UI privacy.
- No real user credentials, persistent key generation, device authentication, installation, or legacy denied probe was executed.

## Still unavailable / follow-on original scope
- Production Vault activation requires platform security, real Tink/SQLite/SAF and protected-display acceptance.
- Batch filesystem export with one chosen directory remains a separate requirement; this checkpoint implements single-item selection only.
- Vault playback/thumbnails and broader provider/OEM support are not included.
- No claim is made that rejected/unsupported filesystem providers are broken; this implementation accepts a finite verified profile.
