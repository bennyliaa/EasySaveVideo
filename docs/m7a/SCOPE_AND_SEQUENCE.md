# M7-A: signed built-in Commons parameter maintenance

Baseline: `0d1bae2dbbb4212c5fc43b5ed536df447974a221` (M6-C). This is a bounded first slice of M7 Provider Maintainability, not completion of the full M7 milestone.

## Implemented scope

The only admitted provider remains the compiled Wikimedia Commons metadata implementation. A versioned, closed declaration can disable that implementation or tighten its existing page size, response size, request deadline and cache budgets. It cannot change endpoints, hosts, queries, extraction logic, parsers, permissions, app entitlements, Save/Record behavior, files or executable code. Unknown schema/target/version/fields fail closed. Extraction-rule updates, additional providers and Scrapling maintenance are not implemented.

The local development pipeline is package bytes → strict bounded canonical parsing → pinned-algorithm signature verification against injected trust roots → monotonic version admission → atomic repository candidate activation → successful-query health promotion or explicit failed-candidate rollback. Previous healthy versions support explicit local rollback without lowering the signed high-water version. Restart rechecks the authenticated envelopes. Any unconfirmed enabling candidate becomes disabled/RECOVERY_REQUIRED; explicit rejection is required before another higher-version install. A signed disabling package is stable immediately because it needs no successful network query to be safe. Uncertain/corrupt storage disables the provider rather than resetting anti-replay state.

An injected, bounded update runtime supports cancellation, deadline and source failure tests. There is no production update URL, download job, account, production signing identity or production trust root. Empty production trust roots are deliberate; test fixture keys have no deployment value. The app uses its private local repository and continues the existing operator-configured Commons opt-in. Remote data cannot override absent Commons contact configuration or the user's local disable choice.

## Non-negotiable boundaries

- API31 minimum, phone/tablet, SkinKit, closed-source licensing, approved dependency bytes only.
- No downloaded code, dex, JS evaluation, classloader, endpoint redirection or arbitrary rule interpreter.
- Remote `enabled=true` is permission only inside existing local build/user admission, never an independent grant.
- Disabling/changing active policy cancels old provider work; results are checked again before return. Physical transport slots, cooldowns and cache budgets remain bounded across policy changes.
- Package signatures establish package origin/integrity within an injected test trust policy; they do not establish legal download rights or release readiness.
- High-water survives candidate rejection, explicit rollback and restart. Expired historical signed envelopes may prove high-water but cannot reactivate a provider.
- Malformed/unavailable/uncertain storage preserves evidence and fails closed. Atomic-file durability still requires actual filesystem/device testing; local fixtures are not power-loss certification.
- No URLs, titles, queries, cookies, tokens, filenames, paths or exception text are logged by the new pipeline.
- No APK/AAB, device install, production trust installation, server/account setup, deployment or publication.
- Free Library and debug-only fake entitlements remain unchanged. Vault remains disabled, untouched, and excluded from this work and its ordinary-provider review.

## Deferred full-M7 work

Production key ownership/rotation/revocation and trust-root decisions; real update service/TLS endpoint and operational delivery; multi-provider package resolution; supported extraction-rule schema/interpreter; production rollout/health thresholds and monitoring; Android filesystem/process interruption and device acceptance. None is silently enabled by this development checkpoint.
