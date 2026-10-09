# Deferred M7-A Android and operational acceptance

All checks below are NOT_RUN by this task. They are separate from host/JVM/source evidence.

- Verify API31 and target API36 platform JCA `SHA256withRSA` behavior for admitted public-key sizes, including unsupported/provider-failure paths. Fixtures must remain test-only, with no production key generation or trust installation.
- Exercise app-private FileChannel locks, no-follow checks, atomic same-directory replacement, file/directory force, full disk, interrupted write, process kill and reboot. Verify no corrupt or unconfirmed candidate becomes active; no deletion/reset recovery is implicit. Host faults are not power-loss proof.
- Confirm repository initialization and health commits stay off the UI thread. A source-free build/contact absence must leave Browser, ordinary Library, Save and Record usable without repository work.
- On phone and tablet, exercise local Commons disable/re-enable plus managed disable/expiry and policy changes during DNS/TLS/read/cleanup; old results must not publish, resource ownership remains bounded and cancellation must not block the UI.
- Observe long-lived process callbacks, application context ownership, background/foreground transitions and repeated discovery-controller recreation. No production remote updater is wired in this slice.
- Before adding live delivery, decide operator ownership of signing keys, production roots, key rotation/revocation and recovery, trusted clock/rollback policy, audited TLS endpoint and delivery cadence. Empty roots and absent endpoint are intentional now.
- Before extraction-rule updates or more providers, define and separately review a closed schema, target compatibility, parser resource bounds and operational rollback. v1 cannot carry a host, query, script, executable or extraction expression.

Do not infer installed-app, store, distribution or production-security acceptance from the development tag. No APK/AAB, real account, deployment, publishing or Vault work is included.
