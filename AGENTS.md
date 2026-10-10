# EasySaveVideo implementation constraints

- This checkout is reconstructed on 2026-10-06 because the earlier M0 checkout was absent. Do not represent this Git history as a recovery of 3907618 or prior commits.
- Original eight specifications are preserved under docs/requirement and design; Architecture update_1 is UTF-16LE. Milestones update1 defines M0–M12 and supersedes old milestone ordering.
- Latest user decision overrides original minSdk30: Android 12 / API31 minimum, phone and tablet, Kotlin/Compose, package com.easysavevideo.app.
- Core is platform-neutral. Android/Store/Provider types may not enter core modules.
- All unreviewed source/library candidates are prohibited from integration. No Yue/NewPipe/Captord/Scrapling copied code or dependencies. Browser is original SystemWebView adapter. Reuse only approved official artifacts and internally authorized SkinKit 0.0.1 Contract/Default; no public license is invented for user code.
- Browser, startup and build must work with zero Providers. Real Save and Record belong to M3/M4; no fake success UI. Fake entitlement source and unlock controls are debug-only, never release.
- SkinKit owns the seven visual categories with complete defaults and snapshot adaptation. Avoid an independent hardcoded business theme.
- Never log URLs, titles, search terms, cookies, tokens, filenames, credentials or exception messages. Logs are technical errors only and bounded.
- No APK/AAB assembly, device install, real accounts, payment, publishing or deployment unless separately authorized. Compile, lint, JVM tests and ABI checks are permitted.
- Build owner serializes Gradle work. Default commands use --offline once all approved artifacts have been recovered. No system trust/certificate changes or unrecognized mirrors.
- Exact Tink Android 1.23.0/Gson 2.13.2/Error Prone 2.41.0 admission is dependency-only. Vault remains disabled and no unfinished core is integrated. Preserve immutable mapped candidate evidence and full accessible notices; new scope requires review.
- Future Vault must never use AndroidKeysetManager cleartext fallback. Enforce authenticated size/extent/final segment, zero-progress EOF handling, and outer atomic lease revocation/publication fencing because cached reads can succeed after close. Budget approximately 2 MiB per seekable Tink reader. Dependency admission is not Android or Vault security acceptance.

- 2026-10-08 scoped clarification: the user approved an independent optional Scrapling verification service architecture. Original service/client source and protocol may be developed and backed up. Android must still contain no Scrapling/Python/browser dependency or copied upstream code. Actual service runtime evaluation is limited to the already admitted exact Scrapling 0.4.15 internal environment and owned isolated fixtures. Public target/browser access, deployment, credentials, costs, runtime/binary redistribution and production license acceptance are not authorized. Preserve all existing candidate/license gates; this is not general dependency admission.
