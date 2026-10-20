# ESV offline receiver hardening candidate, 2026-10-20

## Result

Fifteen focused Linux/Python 3.12.14 tests pass on the separate candidate. The exact published receiver baseline has six passing cases, seven failures, one error and one candidate-only skipped case under the same regression suite. Python byte-compilation passes. No APK, Android build, emulator, physical device, native Windows run or live network search was tested.

## Recovered authoritative input

Repository: bennyliaa/EasySaveVideo
Commit: 0fc5dcfd78d5e74ddb4d4dd8502f81cd828ae3e9
Path: recovery/context-reconstruction-20261017/candidates/code13-slice10-budget-diagnostics-20261019/verify_and_restore.py
Git blob: 7b1067df8aa9156485073ab96aca67556bb9b205
Baseline SHA256: 18770ce64ba5bd567d86af8907f76cda4e013e09067c2db34600d4493472aa84
Baseline size: 10,063 bytes. The test verifies Git blob identity independently.

The old local Android/source directory was absent. This task recovered only the pinned standalone receiver, not an Android baseline. A separate source-reconstruction effort is independent of this patch. Published code13 delivery is unchanged.

## Reproductions and fixes

1. During final output copying, another writer can replace the output pathname with a symlink to matching bytes. Baseline follows the replacement during its final hash check and returns success. The same problem occurs with a different regular file containing matching bytes. Candidate keeps the exclusive-created file handle open, verifies its content through that descriptor with a bounded read, and checks that the pathname remains the same regular-file identity before and after verification. The open handle prevents inode reuse during this verification interval. It rejects both injected replacements and leaves their files untouched.
2. A cleanup unlink error can hide the original copy interruption. Baseline replaces an injected KeyboardInterrupt with PermissionError. Candidate never automatically deletes the final output pathname on failure. This removes the final-path cleanup lstat-to-unlink replacement race and avoids that cleanup masking the original publication exception. Standard temporary-directory cleanup can independently fail; no universal exception-preservation guarantee is made. Partial output remains unverified, and a subsequent invocation still refuses to overwrite it. Use a fresh directory for a retry; inspect/discard partial output manually.

No APK hashes, source hashes, delta identities, parser/recipe rules, file limits or production constants changed. The tests assert those constants match the pinned baseline. The failure-output retention behavior deliberately replaces the old best-effort cleanup convenience. No dependency, shell external command, hardlink, xattr, network call or signing operation was added.

## Test coverage and exact limits

The 15 cases cover: pinned identity and constants; successful byte publication/base preservation; existing output preservation; own partial-file retention; symlink replacement rejection/preservation; regular replacement rejection/preservation; disappearance during interruption; same-size corruption; replacement followed by interruption; cleanup failure and no-overwrite retry; oversized output; short output; multi-block content; unavailable/zero file identity; replacement after hashing but before final identity check.

Publication tests use tiny synthetic bytes, overriding only base/target identities and upstream patch read/decode functions. They directly exercise the actual restore and publication logic. They do NOT revalidate production ZIP/manifest parsing, actual APK restoration or Android signatures. This focused suite is the applicable aggregate for the new standalone test harness, not the prior 7,107-test Android aggregate.

The candidate requires nonzero file identity and fails closed if the runtime cannot provide it. Actual Windows file sharing, unlink semantics, file IDs and Python-version compatibility remain untested. Documentation on platform-dependent file identity: https://docs.python.org/3/library/os.html#os.stat_result.st_ino . This is NOT a Windows acceptance result.

This is not a guarantee against an adversary continuously mutating a writable directory. A pathname can change after the final check/handle close. Final-path automatic deletion is intentionally absent; temporary-directory cleanup remains the standard-library operation. Use a private, trusted, exclusively controlled fresh directory and verify the final APK identity/signature before any installation. Existing input-path race surfaces were not broadly redesigned.

## Reproduce

From the workspace root:

    python3 -m unittest discover -s esv-offline-20261020/receiver-tests -p 'test_*.py' -v
    ESV_RECEIVER=$PWD/esv-offline-20261020/receiver-tests/baseline/verify_and_restore.py python3 -m unittest discover -s esv-offline-20261020/receiver-tests -p 'test_*.py' -v
    python3 -m py_compile esv-offline-20261020/receiver-tests/candidate/verify_and_restore.py esv-offline-20261020/receiver-tests/test_receiver.py

The second command intentionally returns nonzero: two identity acceptance failures and one cleanup exception are demonstrated baseline defects; four retention assertions differ intentionally from the old cleanup policy; zero-identity rejection is a new fail-closed requirement. The late-check seam exists only in the candidate and is skipped on baseline. These are not eight separate discovered bugs.

## Delivery status

Separate development backup only, not integrated into the immutable seven-file code13 handoff. Do NOT overwrite its verify_and_restore.py: its existing transfer manifest hashes the old receiver. Publishing this candidate requires a separately versioned, consistently hashed handoff and subsequent actual Windows/production-artifact checks. No signing key, credentials, user trace or personal data are present in these deliverables.
