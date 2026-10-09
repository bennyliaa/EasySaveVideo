# M9-A — Fixed Commons ordinary reliability

Baseline: `dfdddbbba2ea5fc3f810f02fa2652e55bb2ecba0` (M8-B3).

This slice covers only already admitted fixed Commons schema1 transport, cache and
coordinator behavior: unreachable/slow/partial/malformed ordinary responses,
normal configured limits, retry/deadline/cancellation, stale attempt callbacks,
cursor incompatibility/expiry/exhaustion, unavailable storage, one failed source,
and zero sources. Browser + Save remain independent. Existing host persistence
adapters are checked through close/reopen, without claiming Android device tests.

1. Add deterministic bounded fault matrix; correct ordinary findings.
2. Run focused tests and both compile variants; commit a compilable WIP and protect
   full Git/source snapshots.
3. Freeze source for independent ordinary correctness review. Correct and refreeze
   any findings before final aggregate evidence.
4. Strict offline nonincremental full JVM, dual compile/lint, exact74, current scope
   gate and inherited SQL/DOM/media/Provider/Discovery/history/power/filter hosts.
5. Final commit/tag and named ESV-M9A artifacts; verify exact bytes/modes/refs and
   run complete aggregates in independent Git-bundle and source-archive restores.

No M7-D dynamic candidate/interpreter/security review or blocked stress probe,
substitute review, extreme resource/DoS test, Vault, new dependency/provider,
production root/service/account/payment, APK/AAB/device work, SkinKit edit or SDK
change. B1 alias/revision/transaction rules, B2 UNKNOWN native identity and real
Save/Record owner priority, B3 release NOT_CONNECTED and debug-only synthetic
signals remain. No full M9 compatibility or physical energy claim.
