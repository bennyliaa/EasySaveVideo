# Independent read-only source review

Reviewed 2026-10-12. Scope: original Product §7.1/7.3, Technical TR-07;
five changed existing-file baselines, three new source files, three new test files,
and paired resources. No Gradle, device, APK, upload or real-network operations by
the reviewer. Baseline comparisons are file comparisons, not recovered Git history.

## Finding

No newly introduced P1/P2 blocker identified.

- Navigation bridge checks the current terminal task and one-use command. It does
  not retry, switch execution methods, restore a failed URL/token, inspect a page,
  start recording, or modify a failed task.
- Cancellation, protection, TLS, storage, recovery and unreleased-resource advice
  is conservative. Authentication advice permits normal login and fresh inspection,
  not bypass by another method.
- Command validates ID/revision/state/method/error. Duplicate accepted use and stale
  context are rejected. Lifetime is the current detail view; returning later can
  create another navigation command, not another save request.
- Restarted historical terminal rows may lack coordinator memory cache; fallback to
  the current terminal list is consistent with recover scanning unfinished tasks.
  A present conflicting cached snapshot is rejected.
- `dismissSelection` operates on inspection/unused preparation. Runtime gates reject
  active/prepared HLS, and accepted HLS has already detached preparation ownership;
  no new cancellation path reaches an accepted task.
- Extracted current-page fence preserves tab/session/epoch/READY/View identity/URL/
  inspection revision semantics.
- Independently parsed resources: English 28, Chinese 28; identical keys and nonempty
  values. No false-success, automatic-fallback or authority-bypass promise found.

## Nonblocking verification limits

Three test files contain 22 tests (11 policy/command, 6 page/selection, 5 contracts).
Source-string assertions do not establish runtime Compose or navigation behavior.
Later device validation should cover restart cache absence, cached-newer/UI-older,
double click, leaving/returning to detail, clicking while media is busy, real WebView
callbacks, rotation and translated rendering. These remain NOT_RUN.

## Correction retained

An initial manual reviewer count said 29 resource pairs. XML parsing confirmed 28;
the report was corrected. The first full App test run similarly exposed its newly
written contract expectation 29 vs actual 28. Only that test expectation was fixed;
final rerun evidence is reported separately. No business-source change was needed
for this correction.

Final follow-up: 13/13 hashes in SOURCE_SHA256.tsv matched the reviewed files;
ContractTest expects 28. Review conclusion unchanged.
