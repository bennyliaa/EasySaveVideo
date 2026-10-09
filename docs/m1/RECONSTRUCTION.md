# Reconstruction baseline — 2026-10-06

The executor snapshot did not contain the previous EasySaveVideo checkout or its Git objects. Historical reported M0 commit `39076188ed6e45109e2be65b88f21716551aa36e` could not be recovered; it is not an ancestor of this repository. No old implementation, test output, dependency hash, or Git tag is claimed to have survived.

Recovered inputs:
- All eight original specification Markdown files, restored from the user's original Library archive with hashes. Raw bytes remain in docs/reference, including UTF-16LE Architecture update_1.
- The user's internally reusable SkinKit 0.0.1 Contract and Default binary/source/POM/module artifacts. They match the original Library release hashes (verification JSON alongside this document).
- Earlier audit facts for the exact 53 official runtime coordinates. Original evidence package bytes are being reacquired; this baseline does not claim recovery of that package or approval of any additional dependency.

Fresh work:
- Independent Git history and clean Android/Kotlin/Compose project with platform-neutral modules.
- Current minimum is Android 12/API31, overriding the historical API30 specification at the user's later direction.
- Update1 milestone order M0–M12 governs. M1 is foundation/browser/settings/fake entitlements; no media-save or recorder success is represented.
- SystemWebView adapter is original work, with zero Provider dependencies. There is no Yue, NewPipe, Captord or Scrapling source integration.

Build recovery uses the workspace-only official Android platform36 r02 and build-tools36.0.0 downloads, checked against the freshly fetched official repository XML. Existing Gradle9.6.0/AGP9.4.0/Kotlin2.2.10 and unmodified default JDK21 trust are used. SDKs, Gradle caches and toolchain downloads are excluded from Git and recovery backups.

This foundation checkpoint is **development/incomplete**, not a release or milestone acceptance. Android devices, emulator, package assembly and installation have not been run. Subsequent M1 verification reports distinguish compile/JVM/lint evidence from device acceptance.
