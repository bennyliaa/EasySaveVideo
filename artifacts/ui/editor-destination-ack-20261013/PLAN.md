# Editor destination-selection acknowledgement

Baseline: published 1f25214fcea79113b0278ac24ddc316e6910a9f0, receipt verified by parent and locally readable. Original UI03 UI-R13/section8 and UI04 T-20 require recoverable, explicit cancellation.

## Observed defect
EditorScreen sets its composition click latch before AndroidEditRuntime.submit. submissionRevision advances only in submitSelected, reached only after destination acceptance. ASK dismissal/cancel, early destination rejection and admission failure do not call that path. The still-mounted editor retains disabled draft/submit controls. This does not establish media corruption or lost drafts.

## Bounded correction
Give every originating edit submission a single terminal receipt across destination accepted/cancelled/rejected/failed outcomes. Correlate to the originating attempt; consume before callback and reject stale/duplicate completion. Keep pending selections latched and block duplicate actual submissions, including composition recreation. Preserve the shared destination gate, durable storage/admission, selected copy identity, file preservation, worker/main delivery, recording and export callers. Existing native publication and TOO_LATE cancellation semantics remain unchanged. No editor return confirmation in this slice.

## Validation
Pure outcome/attempt tests plus cross-owner source-contract assertions: cancellation/Back, ready/busy rejection, storage/queue failure, accepted single call, stale admit after cancellation, fresh attempt, duplicate callbacks, repeated taps, background/draft invalidation. Register all source-contract inputs. Independent source review before building. Freeze code/test/build inputs; force complete App JVM, both Kotlin compilers and both lint analyses/reports/entry tasks, runtime admission90 and unchanged compiled boundary checks. Preserve first failures if any. No APK, device, native-media or rendered Compose claim. Two current milestone ledgers refresh published facts while preserving original stage-specific evidence and known failures.
