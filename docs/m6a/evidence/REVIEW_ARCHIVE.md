# Independent host-review evidence archive

`independent-host-review.tar.gz` contains the original small reproduction programs, the exact WIP verifier snapshots used by the initial counterexample, host-only shims, relevant unmodified pinned task-executor source with its existing Apache notice, and observed logs. Compiled classes and the redundant extraction of entire Media3 source jars are excluded. The exact official full source jars remain in the previously admitted repository evidence.

`independent-review-files.sha256` covers each archived input/output. These are synthetic policy/source-control-flow reproductions, not Android/native codec/GPU or playable-media acceptance. The original review runners preserve their recorded workspace/toolchain paths; they document the independent run rather than replacing the portable project tests. The separately persisted `app/src/testSupport/editor-muxer-release/run.sh` is the portable pinned-muxer replay and is also checked in cold restoration.
