# M3-B HLS development work-in-progress checkpoint

2026-10-06 UTC. This is a recoverable source checkpoint, **not complete M3 acceptance or release approval**.

- Fixed Media3 1.11.1 candidate license package: 184 payloads verified against its original manifest; 23 candidate binaries verified. Apache/MIT, CC-BY-2.5 attribution/URI/recipient rights, coroutines NOTICE and Guava special attributions are retained. Offline third-party notice text and settings viewer exist; no new product EULA or paid commitment was authored.
- Actual App Debug/Release runtime resolution: 72 exact reviewed GAV/bytes. The isolated candidate graph had 74; the App omits two old empty Kotlin jdk7/jdk8 1.8.20 compatibility artifacts. No unexpected artifact is admitted. Selection explanation and replacement ABI analysis are being finalized.
- Pure HLS parser/plan, guarded HTTPS preflight/segments, explicit fixed-variant selection, private owned scratch, local-only Media3 inspection/transmux, shared original budgets/deadline, Stream command/UI entitlement gating and original MP4 verifier/commit integration are present.
- New stream persistence stores only technical output expectations. Save codec v2 reads previous v1 records. Source URLs and manifest bodies remain memory-only.
- First offline Debug and Release Kotlin compilation plus App/save/HLS JVM tests passed in `evidence/wip-full-compile.log`. Earlier core/discovery/theme regressions and exact runtime admission passed in `evidence/admission-core-regression.log`.
- Final independent integration review, full lint/static boundary checks, final aggregate regression, final ABI analysis and cold restore are still pending at this checkpoint. Engine profile tests are still being finalized.
- Device/emulator, Android media execution, real-site video, APK/AAB assembly, installation, accounts, payment, publication and deployment: **NOT RUN**. No production HLS-success claim is made from host tests or compilation.

Commons provider is a separate untracked task and is excluded from this checkpoint.

Independent review raised a pending blocker during checkpoint creation: Stream scratch-cleanup failure currently sets the release fence in a finally block without overriding a pending success return, and the coordinator needs a post-transfer release-fence recheck. This checkpoint is intentionally WIP; it must not be treated as the reviewed final candidate. The next commit will fix and test that path before final verification.
