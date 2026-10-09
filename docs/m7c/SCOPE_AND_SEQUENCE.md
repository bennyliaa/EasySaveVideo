# M7-C: local Provider probation and management

Baseline `13ab2b09e1ba33b1e8312b52ba42f78939071c3d` (M7-B). The authoritative milestone
order is `docs/reference/03_Development_Milestones_update1.md`. This closes a bounded
local health/management slice of M7, not the full milestone or production readiness.

## Intended contract

- Authenticate the existing fixed Commons parameter envelope and apply M7-B security gates.
  Run compiled synthetic positive/negative parser vectors before activating an enabled candidate.
- Bind each one-use observation to exact package version/full-envelope digest, policy version/
  digest and generation. Only fresh completed exchanges may provide health evidence. Cache hits,
  local cooldown/filter/configuration rejections, cancellation and deadline completion cannot
  promote or quarantine a package. A single physical observation token is retained at a time.
- Three consecutive fresh parsing successes promote. Three consecutive recognizable API JSON
  semantic failures isolate the package/environment combination. This does not prove defective
  or malicious package code: the package contains only parameters. HTTP errors, 429/5xx,
  permission denials, HTML/challenge bodies, invalid JSON, headers, truncated bodies and transport
  failures are inconclusive. All local thresholds bound exposure, not statistical confidence.
- At most 12 conclusive/20 total completed observations and a 24-hour maximum candidate window,
  narrowed by package/policy expiry. Budget/window exhaustion suspends without a bad-package
  verdict. A new policy invalidates tokens/streaks but cannot reset spent budgets or extend time.
- Atomically persist health and at most 32 isolation entries alongside the existing signed
  anchors, disable barrier and clock bound. Isolation capacity never silently evicts evidence.
  Restarted pending candidates remain disabled/RECOVERY_REQUIRED until explicit rejection.
- Automatic rejection and manual rollback retain the current M7-B authority checks. An enabled
  lower version needs the latest exact signed grant, still-valid package/policy, safe floor and
  unrevoked key/version. Otherwise effective use stays disabled. No state-reset recovery exists.
- SkinKit management shows actual local pointers, candidate, LKG, health, isolation and missing
  contact/roots/endpoint configuration. Only repository-admitted recovery/rollback actions exist;
  a successful local transaction is not necessarily an enabled provider or a working website.

## Unchanged boundaries

API31 minimum and phone/tablet support; zero-provider Browser and ordinary free Library/Save/
Record; debug-only fake entitlements; private technical errors; exact74 dependency/license inputs.
No external source, library, downloaded code/dex/eval, production key, account, endpoint, signing
identity, operational authority, network update service, APK/AAB, installation or deployment.
Vault stays disabled and untouched; its previously blocked review is not retried.

State format 3 deliberately fails closed on undeployed development formats 1/2. Migration for
an actual installed release remains separate work. Checksums are damage detection, not protection
against malicious local state edits or coherent directory replay. Only the committed pre-write
wall-clock sample and this process's later observations are bounded; no trusted external clock,
secure monotonic counter or device power-loss certification is claimed.

## Remaining M7 gaps after this slice

Closed extraction-rule declarations/interpreter, multi-provider resolution, stable-version
long-term health monitoring, production root ownership/rotation, trustworthy offline freshness/
rollback evidence, real update endpoint/service and rollout operations, installed-state migration,
and Android/API31/API36 phone/tablet lifecycle, filesystem and process/power-loss acceptance.
These are not automatically started by this checkpoint.
