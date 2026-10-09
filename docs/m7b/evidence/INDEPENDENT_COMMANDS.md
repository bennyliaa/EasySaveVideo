# Independent M7-B host commands

Working directory: EasySaveVideo checkout supplied by the implementation owner.
No Gradle, dependency fetch, Git mutation, Android build/install or live service call was used.

Diagnostic run:

```sh
bash docs/m7b/evidence/run-independent-provider-host.sh /tmp/esv-m7b-independent-diagnostic-1 > docs/m7b/evidence/independent-diagnostic-1-console.log 2>&1
```

Result: 208/208, unchanged compiled source hashes. Superseded by final additional fixtures
and explicit disabled-target rollback contract tightening.

Final run:

```sh
bash docs/m7b/evidence/run-independent-provider-host.sh /tmp/esv-m7b-independent-final > docs/m7b/evidence/independent-final-host-console.log 2>&1
```

Exit code: 0. Result: 211/211, 177 ordinary tests and 34 independent tests.
All 48 compiled source/fixture inputs passed the script's after-run SHA-256 check.
Compiler/runtime/test JAR hashes are in independent-final-toolchain-sha256.txt.
The runner calls the previously provisioned JDK21 launcher and cached Kotlin 2.2.10 directly.

The independent 65-entry source manifest was frozen at 2026-10-07 00:55:04 UTC.
A separate Python read of every listed file confirmed its byte extent and SHA-256 at
2026-10-07 00:55:44 UTC. Verification JSON contains all_match=true and an empty mismatch list.
The manifest does not recursively include this report, logs, the review report or itself.

Temporary compilation output is not a deliverable; retained console, JUnit, input-hash,
toolchain-hash, source-unchanged and manifest-verification evidence is under this directory.
Android execution, installation and device/power-loss acceptance remain NOT_RUN.
