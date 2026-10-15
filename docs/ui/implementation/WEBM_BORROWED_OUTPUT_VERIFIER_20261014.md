# Borrowed AVC/AAC verification core for basic conversion

Date: 2026-10-14 UTC

## Actual change and verification

The production Editor wrapper now invokes a neutral synchronous AVC/AAC inspection core. Its old parser, AVC/AAC and sample/decode policies were mechanically moved with compatibility delegates and the original numeric tolerances and error mapping. No new Direct adapter or new audio tolerance was enabled.

The shared core only borrows the caller's same FD/channel/fixed extent. It owns codecs and extractors, never closes the borrowed descriptor, and retains uncertain native references. The Editor remains the sole read closer and keeps its existing gate and queued cancellation behavior. Successful inspection requires actual native release, then final hashing through the same original channel, then caller close, before valid evidence is returned.

Independent review found and closed one new shared-finally error path: a codec stop failure followed by successful release could otherwise leave a successful result. It now causes ordinary verification failure while retaining confirmed native release; it does not falsely poison the process. The shared cleanup continues releasing other resources and never retries an uncertain release or descriptor close.

Debug/release Kotlin compilation passed. Nine JVM suites ran **96 tests, zero failures/errors/skips**, including 30 new tests (12 borrowed lifecycle, 14 production Editor owner, four source wiring). These use real RAF/production cleanup owners with fake native observations; they do not execute Android codecs. The independently reviewed 12-file manifest SHA256 is `8b502516304a753dec8c322245b793fdb6331d309dc638b6b657de7a5459a663`.

Evidence: `/workspace/shared/esv-webm-basic-conversion-20261014/borrowed-verifier/` (`focused-first.log`, `.exit=0`, `focused-first-xml`, `FROZEN_FILES.json`, `REVIEW.md`). Source-contract input paths are declared in the app Gradle task.

## Remaining integration

Direct's exact adopted-output capability, independent audio expectation and converted-output proof/publication chain remain to be connected. This extraction does not authorize arbitrary FD/path inputs, create conversion evidence, complete WebM support, or pass Android/native/device acceptance. All existing production/device/product gates remain in effect. The immutable AAC checkpoint remains the latest complete all-module regression until the next final rerun.
