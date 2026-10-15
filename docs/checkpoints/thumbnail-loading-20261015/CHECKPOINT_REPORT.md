# Discovery actual thumbnail integration — 2026-10-15

## Implemented user-visible path
The existing Commons registration issues an ephemeral current-result image capability. The actual card path now performs anonymous bounded OkHttp fetching, verifies complete EOF, runs platform bounds-only inspection, decodes the same bytes with Coil core's BitmapFactoryDecoder, and displays a software image in the existing Compose card. Loading/unavailable/error states have paired EN/ZH text. Existing exact card navigation, rights/details, history and save/play evidence are unchanged.

Limits: 1 MiB encoded response, source dimensions <=8192 per side and <=16,777,216 pixels, returned image <=256x144 and <=147,456 allocation bytes; <=16 visible images /2,359,296 retained bytes. These are request/retained policies, not a guarantee of native/GPU peak allocation. Recoverable platform partial/static-first-frame previews are allowed after successful bounded complete transport. No custom image parser is introduced.

The dedicated client has no browser cookies, authentication, contact/referrer, redirects, disk cache or retries. Exact existing Commons endpoint policy and public DNS checks apply. Source/route/background/intent/power withdrawal signals real cancellation; a process-wide lane remains occupied until the actual worker and claimed cancellation hooks drain. Cooldown and poison survive controller recreation. Independent Handler delivery survives the controller's ordinary Handler cleanup. No new source is enabled and no missing source configuration/contact is populated.

## Dependency admission
95 exact runtime artifacts: 82 unchanged, 9 reviewed upgrades, 4 additions. Strict original runtime equality gate remains; 657 existing checksum records are preserved and 44 exact records added. Complete notices, WebKit and separate SkinKit terms are preserved. Independent license/byte review and the 133-file admission overlay passed. The production original gate and both Kotlin variants passed (dependency-first, exit0, 28s).

The isolated full current-project dependency regression passed 5636+2 skipped, zero failures/errors; double lint 75 warnings+8 notes each, zero errors. This result precedes actual thumbnail implementation. The reduction from baseline 76/77 warnings is solely disappearance of dependency-newer-version suggestions, not a product-code fix.

## Execution chronology
- focused-first: failed Kotlin compilation because OkHttp Dns is not a SAM interface. No focused tests executed. The directory marked stale-preexisting-xml-NOT_THIS_RUN is explicitly not evidence for that run.
- revision02: actual dual compile and 63 focused tests passed, exit0, 51s.
- full-first: failed with two legacy source/resource contracts (branch shape and 280-to-283 resource count); 5682 items included 2 failures and 2 skips. Retained as failure history.
- revision03: actual dual compile and 83 focused tests passed, exit0, 29s. An independent reviewer subsequently found a remaining old-refresh/new-intent-generation race; this was not considered final acceptance.
- revision04: requested/applied intent tracking plus entry-captured refresh generation closes the publication race. Prior exact-generation adapter, ReleaseUnconfirmed cancellation/drain, and synchronous intent withdrawal fixes remain. Actual dual compile and 86 focused tests passed, exit0, 27s. Discovery's identical 28 cases were up-to-date here and actually executed in the prior run; app 58 cases executed anew. 54 new cases +19 prior thumbnail cases+13 retained legacy cases =86.
- Final all-module regression/lint: exit0, SUCCESS4m17s. 515 suites/5692 items =5690 PASS+2 SKIP, zero failures/errors. Debug lint77 warnings+10 notes, Release78 warnings+10 notes, zero errors. New findings are one process-lifetime application-Context StaticFieldLeak warning and two primitive-state suggestions. Independent source review confirmed that the singleton stores only applicationContext, not an Activity or UI callback. No lint suppression/baseline was added. Production dependency-newer-version suggestions remain as in the prior production baseline.

## Frozen identities and independent review
Function revision04 manifest: db6fc2161d128ec31ac1ce0b80a7787874c3109a14bd50619132f6da191121b4 (18 targets).
Central final manifest: 90999cf2f91302cdf474d3e2397b23719103cc1a512a8a13562f165534188464 (151 targets including admission and exact source-input registration).
Reader/cancellation and lifecycle/issuer/UI scopes independently passed source+focused review. License/admission was independently reviewed separately.

## Remaining acceptance
JVM tests execute production policies, actual anonymous OkHttp configuration, session/capability/cancellation hooks, lane and publication seams, plus source wiring contracts. They do not execute actual Android BitmapFactory/Coil native pixels, real HTTP response/close/CDN interoperability, Compose drawing or device scrolling/background timing. One publication fixture uses a real thread/latch and another is deterministic sequential scheduling, both using production Session/Capability; neither is Android Controller execution. No device actions or new APK distribution occurred. Public MOV/WebM/native audio and private device gates remain separate. GitHub synchronization is paused.
