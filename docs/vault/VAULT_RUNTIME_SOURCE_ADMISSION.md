# Vault R2 runtime source scope (entry disabled)

> Historical R2 scope record. Subsequent protected-host, optional recovery-code, media move/copy-export and controlled-playback source work is tracked in [CURRENT_IMPLEMENTATION_STATUS.md](CURRENT_IMPLEMENTATION_STATUS.md). The earlier limited scope below is preserved; it is not a current statement that those later code paths are absent. Production remains disabled and native/device acceptance remains pending.

This is a new source-integration scope. It does not replace the historical dependency-only Tink admission or claim that its no-production-import condition remains applicable to the new runtime. `scripts/check-tink-admission.py` is preserved byte for byte and is not executed by the new check.

The fixed dependency graph, notices and licenses remain subject to the existing Gradle runtime admission. The new `scripts/check-vault-runtime-scope.py` checks the exact listed Tink source/FQN references, rejects wildcard imports, checks the historical gate hash, and checks the disabled construction entry. It is a finite text boundary check, not AST security analysis or proof of cryptographic safety.

Run the new static check with `python3 scripts/check-vault-runtime-scope.py`; its six synthetic negative/positive cases are in `scripts/test-vault-runtime-scope.py`. Neither script executes Tink, SQLite, Android, key generation, credentials, authentication, or any historical probe.

## Scope of source integration

- Internal fixed-profile crypto adapters, private proof/root registration and bounded ciphertext formats.
- Separate Vault SQLite owner, initialization and exact proposal CAS/readback.
- Runtime effects connecting password setup/unlock, retry reservation/reset, authenticated empty catalog and revocation.
- A disabled application construction gate and real empty-catalog display consumer.

The user-operation chain is **not complete**: protected setup/unlock input and Main root/presence ownership must be connected before the page is reachable. The current private root is bound to the private browser owner; it cannot be reused by pretending the ordinary Vault route is private browsing. Optional recovery/biometric leaf implementations do not establish their complete user workflow.

## Execution limits

Cloud tests in this change use pure state/codec/lifecycle cases, synthetic ciphertext bytes and fixed public cryptographic vectors only. Compilation of platform adapters does not execute them. Actual user key generation, Keystore operations, platform authentication, Android SQLite persistence, hardware-backed security, physical power-loss behavior and secure visual/input lifecycle remain untested. Production entry is false. Existing ordinary storage and the historical dependency evidence are not upgraded by this source work.
