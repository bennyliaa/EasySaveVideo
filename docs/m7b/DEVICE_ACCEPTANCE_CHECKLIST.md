# Deferred Android and operational acceptance

All items below are NOT_RUN by this development task:

- API31/API36 JCA fixed RSA/SHA256withRSA behavior and provider failures using fixtures only.
- Android file locking, no-follow checks, full disk, rename/fsync/directory force, process kill,
  power loss and reboot. Host fault stages do not establish Android durability.
- Phone/tablet UI, foreground/background transitions, true process ownership, repeated discovery
  controller recreation, in-flight cancellation and non-blocking user interaction.
- Device wall-clock changes and time-source behavior. This slice's observed-time lower bound is
  local, partly persistent and explicitly not a trusted counter or complete state-rollback defense.
- Installed-state format-1 migration. The current development implementation rejects old state;
  silently resetting or deleting accepted security policy is prohibited.
- Production root custody, policy-root rotation/revocation, package key introduction/recovery,
  irreversible-revocation mistakes, bounded-list capacity and trusted external evidence.
- Actual distribution endpoint/TLS/account/cost/hosting, cadence, rollout, monitoring and emergency
  signed rollback procedures. No production root/endpoint/signer is configured here.
- Full M7 fixed-vector self-tests/probation/failure isolation, management UI, extraction declarations,
  interpreter and multi-provider handling remain separate later work.

No APK/AAB, installation, real account, payment, deployment or publication is authorized by this
checkpoint. Devices and release acceptance cannot be inferred from JVM/host/build evidence.
