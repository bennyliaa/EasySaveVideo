# WebM preparation: shared Transformer runtime

Date: 2026-10-14 UTC

## Implemented and verified

The existing Editor now uses `SharedTransformerRuntime` for its process-wide executor, release fence and lazy control handler. The original fence implementation moved without semantic changes; the Editor name remains a typealias. The execution/session/cleanup/error bodies remain unchanged. Single worker, one queued task, abort-on-rejection, daemon thread name and lazy Android HandlerThread initialization are preserved.

Dual debug/release Kotlin compilation and eight JVM suites completed successfully: **94 passed, no failures/errors/skips**. Eight new tests exercise the production owner/fence/executor; two inspect source wiring. The existing 84 Editor policy/probe/cleanup tests passed. Independent review checked all six frozen implementation/test hashes and the final XML.

Evidence: `/workspace/shared/esv-webm-basic-conversion-20261014/shared-runtime/` (`focused-first.log`, `.exit=0`, `focused-first-xml`, `FROZEN_SHA256.json`, manifest SHA256 `8e53afddeba0dba29a89b3a1f21726d12ae6e001201371276cc6c8bd38a953d6`). Source-contract input files are declared in app Gradle inputs.

## Limits and next work

This is a mechanical reuse prerequisite, not a completed Direct conversion. The new tests use isolated production owners and identity tokens; they do not demonstrate actual Editor/Direct native interaction. No APK, Android/native execution, device acceptance or production WebM admission occurred. The prior AAC checkpoint remains the latest full regression: 5060 PASS + 2 existing SKIP.

Next: reuse the actual pinned WebM extractor with fixed-extent framing/inventory guards; extract the native verifier's synchronous borrowed-FD core while keeping each caller the sole FD closer; then connect the exact Direct storage/execution path. The approved conversion design retains separate device and product gates.
