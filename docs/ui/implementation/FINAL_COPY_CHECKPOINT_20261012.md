# Finite status and static copy checkpoint

Base: f8aab9376c69f58dee2fc0b60332b5b69cc18aa5. This increment is source-only and local-only. It is not in the fixed code4 APK. The signing input remains d9bdf34567bf2d138e796a50560419d359385cf4, versionCode4/minSdk31. No new dependencies, device operation, screen capture, microphone, Vault, dynamic rules or real payment are enabled.

New resources: 71 finite recording state/audio/outcome/stop/error/segment resources, 96 selected recording/editor/save/unavailable-library static resources, 13 crop-control/coordinate resources and 3 diagnostic resources (183 paired resources). Actual screen call sites use resource IDs. Original pure string helpers remain for existing tests and service/runtime consumers. These helpers are not runtime translation dictionaries.

The segmented-recording success label additionally requires verifiedOutputAvailable. A SUCCEEDED phase without verified library output shows unconfirmed output, not saved success. Capture finished is separate from publication. Unknown or silent audio stays distinct from observed PCM, which still does not confirm target content. Cancellation, timeout, interrupted process and unconfirmed native resource release remain distinct states.

Recording/editor diagnostics are shown behind a localized explicit Show original diagnostic control. Raw values are neither translated nor interpreted. Query/page/library names, URLs and webpage content remain verbatim. The generic diagnostic summary is not an outcome claim. The original diagnostic remains reachable even when recording admission is unavailable; no callback or resource owner changes.

Crop semantics use localized diagram and coordinate descriptions. Both languages retain all four coordinates and percent units. The semantics lambda receives precomputed text, not a composable call. Existing handle hit/drag math is unchanged. Settings selectable group/roles from the prior increment remain unchanged.

Evidence: artifacts/ui/final-copy-20261012, including a conservative scan of 12 source files. The scan records 369 remaining CJK literal/template fragments for review, including legacy pure helper copies and partial nested interpolation. It is not a count of unique UI strings or a complete Kotlin parser. Remaining owned advanced templates, source/filter/provider controls, recording dialogs and service status consumers are explicitly not fully migrated. These are source localization work, not solely device acceptance. Do not declare complete bilingual UI.

Device TalkBack/layout/configuration restoration and real recording/edit/save acceptance remain NOT_RUN. Full Windows suite remains NOT_RUN. The existing scoped regression results do not replace these acceptances or earlier unrelated full-suite failures.

Separate authorized transport: private delivery/ui-code4-unsigned-d9bdf34 fixed ref f549a7a6b7b9857d3f2f7f82badc173765e1bbab contains 89 base64 text parts and manifest; every remote part/blob/hash and reconstructed original APK was checked. This increment does not include those transmission files. Existing direct APK is preserved and not rebuilt. No signing key is accessed or transferred.
