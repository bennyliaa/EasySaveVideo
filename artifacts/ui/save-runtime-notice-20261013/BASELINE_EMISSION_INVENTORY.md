# Save runtime notice emission inventory and minimum complete migration

Baseline emission inventory, captured before this implementation. Line numbers in this inventory refer to the b0c5647 production baseline. Final actual results are recorded separately in VERIFICATION.json.

## Findings

- `AndroidSaveRuntime.kt:124` stores an application-scoped Compose `String?`. There are **49 assignments: 44 non-null emission sites and 5 clears**, excluding the declaration's initial null.
- Emissions split into **36 fixed-literal sites / 34 distinct fixed strings**, **1 integer-capacity message**, **1 detector outcome site with 6 reasons**, **1 network site with 2 messages**, and **5 SaveError sites**.
- The five error sites call legacy `saveErrorText`, which has **35 display groups across 45 SaveError enum values**: 43 values in 34 explicit branches plus two fallback values. The current resource mapper `saveFailureTextResource` is much coarser and is not a lossless substitute.
- There are **13 emission sites within main.post** and **31 remaining emissions on main-thread entry/callback paths**. Preserve their current timing, guards and cleanup ordering.
- The only scalar presentation parameter is `limits.maxJournalEntries`, an application-chosen bounded Int (default 100, accepted range 1..1000). No source URL, title, filename, path, request/task ID, lease, credential, cookie, exception message or Throwable needs to enter notice state.
- Native recording service status strings are a separate Android-notification boundary and must not be silently changed by a Save runtime notice migration.

## Minimum complete typed shape and resource grouping

Use an app-layer sealed `SaveRuntimeNotice` with four data families, for example:
1. `Fixed(kind: SaveRuntimeFixedNotice)` with 36 values: the 34 fixed strings below plus `WIFI_REQUIRED` and `WIFI_SETTINGS_UNAVAILABLE`.
2. `TaskCapacityReached(maxUnfinishedTasks: Int)` for line 325.
3. `DetectionUnavailable(reason: PageSaveDetection.Reason)` for line 270. Store only the reason, never the detection/selection/candidate.
4. `Failure(code: SaveRuntimeFailureCode)` with 35 display-code values for lines 345, 390, 427, 456, 720. Map the 45 classified SaveError values to the existing 35 text-equivalence groups at the emission boundary. Store only the display enum, never Throwable or a record. Keeping the raw 45-value enum in notice state would change equality when two different errors previously produced the identical String.

Preserve structural equality: the two repeated fixed strings use the same fixed value at both sites, and all old same-string error branches use the same failure display code. Locale changes redraw text without changing notice state. Use an exhaustive resource mapping and a pure current-Compose rendering function. Resolve no text in the application singleton, background worker or cached locale. No `Raw(String)`, context-bearing resource wrappers, lambda payloads, or generic Any/format-argument list. Keep the model in the app module; do not add Android resource identifiers to core/save domain modules.

This requires 34 fixed-message groups + 1 capacity group + 6 detection groups + 2 existing network-resource groups + 35 error groups = **78 distinct legacy display groups**. Reusing the two network resources leaves 76 new resource groups for strict verbatim Chinese preservation. An explicitly reviewed copy-only consolidation could reuse the existing Wi-Fi error copy too, but do not pretend it is byte-identical: legacy saveErrorText(WIFI_REQUIRED) omits `请` before `连接`, while save_wifi_required includes it. Resource identifier names may vary; cardinality/semantic exhaustiveness must not.

`Fixed(kind)` could instead be 36 sealed data objects; the four-family representation is the smallest complete grouping. Do not collapse semantically different fixed messages merely because their English summaries sound alike (direct-start vs stream-prepare vs resume session failures; numbered direct/stream accepted notices; unavailable vs capacity vs busy; generic storage failure vs retained-task recovery).

## All fixed emissions

Line numbers below refer to AndroidSaveRuntime.kt. `post` identifies a main.post body; unmarked rows are existing main-thread entry/callback paths. Suggested kind names are plan-only.

- `SAVE_UNAVAILABLE` — 256: 保存暂时不可用，请稍后重试。
- `OPEN_LOADED_PAGE` — 261: 请先打开网页，等待页面加载完成后再保存。
- `CANDIDATE_EXPIRED` — 312: 候选已过期，请重新检测当前页面。
- `CANDIDATE_PROTECTED_OR_UNSUPPORTED` — 313: 该资源受保护或格式未支持，未开始保存。
- `STREAM_ENTITLEMENT_REQUIRED` — 314: ②流媒体保存需要有效权益。本开发版本没有真实购买入口。
- `MEDIA_RESOURCES_EXITING` — 324, 492: 已有保存或系统媒体校验正在退出，请稍后再试。
- `DIRECT_REQUEST_SESSION_UNAVAILABLE` — 326: 无法安全建立短期请求会话，未开始保存。
- `DIRECT_TASK_ACCEPTED` — 343 (post): ①直接下载任务已接受，请在保存任务查看进度。
- `STREAM_UNAVAILABLE` — 359: ②流媒体保存暂不可用，请检查权益并等待现有任务退出。
- `STREAM_REQUEST_SESSION_UNAVAILABLE` — 362: 无法建立短期请求会话。
- `STREAM_PREPARATION_QUEUE_UNAVAILABLE` — 405: 流媒体检查队列暂不可用，未开始保存。
- `STREAM_CONTEXT_CHANGED` — 413: 页面、来源或权益已变化，请重新检查。
- `TASK_CAPACITY_FULL` — 415: 保存任务容量已满，已有媒体未被删除。
- `SAVE_OPERATION_BUSY` — 425: 已有保存操作处理中。
- `STREAM_TASK_ACCEPTED` — 452 (post): ②流媒体任务已接受，固定所选清晰度；仅在独立 MP4 通过验证后完成。
- `RECORDING_RESTART_REQUIRES_FRESH_CONSENT` — 497: 录制会话已中断，不能复用旧授权。请明确开始新录制；旧任务可放弃。
- `STREAM_RESELECT_REQUIRED` — 500: 流媒体片段未完成。请返回网页重新选择固定清晰度，新任务从头开始；旧任务可取消。
- `SOURCE_RESELECT_AFTER_RESTART` — 504: 重启后不会保留完整来源地址。请返回网页重新检测并选择，新任务会从头下载；旧任务可取消。
- `SAME_ORIGIN_LOGIN_REQUIRED` — 509: 请先在浏览器打开并登录该资源的同源页面，再回来手动继续。
- `RESUME_REQUEST_SESSION_UNAVAILABLE` — 512: 无法安全建立短期请求会话，未继续保存。
- `TASK_STATE_READ_FAILED` — 549 (post): 任务状态暂时无法读取，已保留私有文件供恢复。
- `EXECUTION_QUEUE_UNAVAILABLE` — 564 (post): 保存执行队列暂不可用，任务已暂停。
- `RECORDING_UNAVAILABLE` — 593: ③暂不可开始。请检查录制权益，并等待现有保存或预览资源退出。
- `RECORDING_CAPACITY_OR_GEOMETRY_INVALID` — 596: 任务容量或显示尺寸不适合开始录制。
- `RECORDING_SESSION_EXITING` — 608: 录制会话仍在退出，请稍后重新开始。
- `RECORDING_CONFIGURATION_INVALID` — 613: 无法建立有效的录制配置。
- `REGION_RECORDING_UNAVAILABLE` — 625: 选区、权益或媒体资源尚未就绪。请重新核对选区并等待现有任务退出。
- `PREVIEW_RECORDING_UNAVAILABLE` — 632: 当前显示几何、权益或媒体资源尚未就绪，未取得预览或开始录制。
- `RECORDING_FOREGROUND_START_REJECTED` — 677: 系统未能启动本次前台录制服务。旧授权已丢弃，请手动重新开始。
- `RECORDING_EXECUTION_FAILED` — 698 (post): 录制任务未能结束，私有文件已保留供本地恢复。
- `RECORDING_NATIVE_RELEASE_UNCONFIRMED` — 706 (post): 系统录制资源仍未确认退出。已保留前台状态与文件；不能开始另一项录制。必要时请在系统中完全结束应用。
- `RECORDING_PAUSE_RESUME_ENTITLEMENT_REQUIRED` — 754, 760: 暂停/继续按现有契约需要录制与片段录制权益。
- `SAVE_STORAGE_UNAVAILABLE` — 908 (post): 保存存储暂时不可用，原有媒体未被清除。
- `CONTROL_QUEUE_FULL` — 913 (post): 任务操作较多，请稍后再试。

## Other assignments

### Capacity parameter

- 325, `chooseDirect`: `最多同时保留 ${limits.maxJournalEntries} 条未完成任务，当前已满。已有媒体未被删除。`
- Source: runtime `limits` constructed at 65; SaveLimits in save/SaveModels.kt:101 onward, `maxJournalEntries=100` at 111 and validation range 1..1000 at 119. It is not user content and is not the active-task count.
- Carry the Int unchanged; format using the current Compose locale (existing SaveDisplay formatting conventions), with matched locale placeholders. Do not recalculate capacity or add a new gate in the renderer.

### Detection outcome

- 270 is one assignment site, inside the original detector callback; six exhaustive mapping branches at 271–276.
- `PageSaveDetection.Reason` is declared in save/PageSaveDetector.kt:42. Its declaration order is STALE_PAGE, NO_MEDIA, UNSUPPORTED, JAVASCRIPT_DISABLED, TIMED_OUT, INVALID_RESPONSE.

- 271: PageSaveDetection.Reason.STALE_PAGE -> "页面或标签已变化，请在当前页面重新点击保存。"
- 272: PageSaveDetection.Reason.JAVASCRIPT_DISABLED -> "当前关闭了 JavaScript。启用后可在页面点击保存进行一次检测。"
- 273: PageSaveDetection.Reason.TIMED_OUT -> "页面检测超时，未开始下载。"
- 274: PageSaveDetection.Reason.UNSUPPORTED -> "当前资源属于 blob 或其他未支持类型。仅支持 HTTPS MP4 与受限静态 HLS。"
- 275: PageSaveDetection.Reason.NO_MEDIA -> "当前主页面未找到可检测的视频。跨域播放器、blob 或嵌套页面可能无法检测。"
- 276: PageSaveDetection.Reason.INVALID_RESPONSE -> "页面返回的检测数据无法安全读取，未开始下载。"

Detector callback ownership: PageSaveDetector.kt:47–69 creates a main Handler, increments cancellation generation, allows one delivery, cancels timeout, and uses a 5-second timeout. `inspectFromUserClick` enforces main at 57; synchronous outcomes at 59–60, WebView callback at 72–126. Keep both pre-evaluation and response-time current-page checks, page identity validation, callback generation/single-delivery fences, 32-candidate cap, 60-second expiry and cancel behavior. The notice must not schedule a scan or acquire additional browser authority.

### Network admission

- 305–306, `networkSaveAllowed`: inside catch SaveFailure, and only if showNotice, it posts an assignment to `application.getString(if (networkPolicy.initialized) R.string.save_wifi_required else R.string.save_wifi_settings_unavailable)`.
- The initialized branch is currently evaluated **inside main.post at delivery time**, not before posting. Preserve that timing when replacing with typed values. Keep catching only SaveFailure here, preserve `false` return and default showNotice=true.
- Existing resources: res/values/save_wifi.xml:6,8 and values-zh-rCN/save_wifi.xml:6,8.
- `SaveWifiNetworkPolicy.kt:34–37,77` distinguishes unknown settings in memory but throws WIFI_REQUIRED for both; retain this explicit two-message UI branch rather than replacing all such notices with Failure(WIFI_REQUIRED).
- This helper is called at chooseDirect 323, prepareStream 357, preparation delivery gate 379, selected HLS variant 424, resume 494, and launchExecution 532. Rendering cannot perform checkAdmission or toggle settings.

### SaveError emission sites

- 345 (post): direct coordinator rejection; payload result.error, after lease revoke.
- 390 (post): stream prepare exception; payload SaveErrors.classify(failure), guarded by `token == preparationGeneration`.
- 427 (immediate): HLS selection exception; after setExecuting(false) and dismissSelection; payload SaveErrors.classify(failure).
- 456 (post): HLS coordinator rejection; after prepared stream discard and lease revoke; payload result.error.
- 720 (post): recording submission/start exception; payload SaveErrors.classify(failure), after not-launched deny/cancel cleanup.

`SaveErrors.classify` is save/src/main/kotlin/com/easysavevideo/save/SaveErrors.kt:34–61. SaveFailure.code passes through; RecordingFailure maps its safe enum (CLOCK_INVALID joins TIMESTAMP_INVALID; BYTE_LIMIT becomes BYTE_LIMIT_EXCEEDED; STORAGE_LOW becomes STORAGE_FULL; unsupported recording codes become INVALID_REQUEST); every other Exception becomes UNKNOWN_TECHNICAL_ERROR. Never substitute `.message`, `.toString()`, raw exception types, or diagnostic text for this classifier. Existing policy/coreCode mappings at 9–31 stay unchanged.

### Five clear sites

- 265: beginning accepted inspection (`scanning = true; selection = null; notice = null`).
- 293: dismissNotice.
- 610: successful basic recording preparation, after assigning pendingRecording/currentRecordingId.
- 626: successful region recording preparation, after execution/currentRecordingId/snapshot changes.
- 633: successful preview recording preparation, same ordering.
- Initial state at 124 is null. Keep dismissal/reset behavior; do not clear on foreground, selection dismissal, every render, or locale change. `dismissNotice` currently has no requireMain assertion; display migration need not add behavior changes.

## Complete legacy SaveError display mapping

Authoritative source: app/save/SaveTaskScreen.kt:310–346. Each row is a resource-equivalence group, not authority to merge domain error codes. Use exhaustive when over SaveError; name both fallback values explicitly. All 45 enum values are in save/SaveModels.kt:87–98. The notice stores the 35-value display grouping; the domain retains the original SaveError without modification.

- 311: SaveError.WIFI_REQUIRED -> "正在等待已确认的默认 Wi-Fi 网络。连接 Wi-Fi 或关闭“仅通过 Wi-Fi 保存”后手动重试。"
- 312: SaveError.CANCELLED -> "任务已取消，不会自动换用其他保存方式。"
- 313: SaveError.AUTHENTICATION_REQUIRED -> "资源需要有效的登录会话或访问权限。"
- 314: SaveError.STORAGE_FULL -> "可用存储空间不足。"
- 315: SaveError.STORAGE_FAILURE, SaveError.JOURNAL_UNAVAILABLE -> "私有存储暂时不可用，未清除已有媒体。"
- 316: SaveError.NETWORK_TIMEOUT, SaveError.DEADLINE_EXCEEDED -> "操作超时，迟到结果不会提交。"
- 317: SaveError.NETWORK_UNAVAILABLE, SaveError.RETRY_EXHAUSTED -> "网络未能在有限重试内完成，请稍后重试。"
- 318: SaveError.TLS_REJECTED -> "无法验证网站安全连接，未绕过证书检查。"
- 319: SaveError.POLICY_BLOCKED -> "资源地址或连接不符合安全限制。"
- 320: SaveError.BYTE_LIMIT_EXCEEDED -> "已达到本开发版本的文件或累计传输预算。"
- 321: SaveError.PROTECTED_MEDIA -> "检测到受保护内容，不支持保存。"
- 322: SaveError.UNSUPPORTED_MEDIA, SaveError.METHOD_UNSUPPORTED -> "该媒体或保存方式不符合本开发版本的兼容范围。网络保存不支持直播、加密、分轨、格式变化或转码。"
- 323: SaveError.INVALID_MEDIA, SaveError.INCOMPLETE_TRANSFER -> "文件不完整或未通过真实媒体验证，没有作为成功视频入库。"
- 324: SaveError.INVALID_RANGE, SaveError.RESOURCE_CHANGED -> "源资源或断点身份不一致，没有拼接不匹配的字节。"
- 325: SaveError.EXPIRED_RESOURCE -> "候选资源已过期，请回到网页重新检测。"
- 326: SaveError.RESOURCE_RELEASE_UNCONFIRMED -> "系统资源尚未确认退出，任务文件保留，迟到结果不会提交。"
- 327: SaveError.VERIFICATION_UNAVAILABLE -> "系统媒体校验资源尚未可用，请稍后重试。"
- 328: SaveError.COMMIT_MISMATCH, SaveError.RECOVERY_REQUIRED -> "提交状态需要重新核验，文件已保留供恢复。"
- 329: SaveError.RATE_LIMITED -> "网站限制了请求，请稍后重试。"
- 330: SaveError.SOURCE_RESELECTION_REQUIRED -> "短期来源上下文已清除。请回网页重新检测并选择；新任务从头下载。"
- 331: SaveError.HTTP_NOT_FOUND -> "资源已不存在（404），请回网页重新选择。"
- 332: SaveError.HTTP_REJECTED -> "网站拒绝了此资源请求，未自动换用其他保存方式。"
- 333: SaveError.HTTP_FAILURE -> "网站未返回可保存的完整媒体。"
- 334: SaveError.BUSY -> "已有任务处理中，请稍后再试。"
- 335: SaveError.CONSENT_DENIED -> "未获得本次系统录制授权，没有开始录制。"
- 336: SaveError.AUDIO_PERMISSION_DENIED -> "内部声音权限未允许；必须明确选择无声音重新开始，或取消。"
- 337: SaveError.CAPTURE_UNAVAILABLE, SaveError.FGS_START_REJECTED -> "系统暂时无法开始本次录制；不会重复使用旧授权。"
- 338: SaveError.PROFILE_UNSUPPORTED -> "设备编码器不支持本次录制配置。"
- 339: SaveError.CAPTURE_EMPTY, SaveError.CAPTURE_TOO_SHORT -> "有效录制样本为空或太短，未发布成片。"
- 340: SaveError.KEYFRAME_TIMEOUT -> "等待录制关键帧超时，未确认完整成片。"
- 341: SaveError.TIMESTAMP_INVALID -> "录制媒体时间线不符合要求，未发布成片。"
- 342: SaveError.AUDIO_FAILED -> "内部播放音频采集失败；没有自动改用麦克风或无声音。"
- 343: SaveError.ENCODER_FAILED -> "系统录制编码器失败，未确认保存成功。"
- 344: SaveError.FINALIZATION_FAILED -> "录制文件未完整收尾，未发布成片。"
- 345: SaveError.INVALID_REQUEST, SaveError.UNKNOWN_TECHNICAL_ERROR -> "任务未完成，请检查页面与存储后重试。"

Do not reuse `SaveTaskTextResources.kt:18–32` as the full mapper. That existing helper coalesces expiry/reselection/resource-changed, all storage cases, protected/unsupported, and almost all recording/security/network/commit failures into coarse summaries. Its purpose and existing call sites can remain untouched; a separate runtime notice error mapper preserves all 35 detailed messages.

## Threading and behavioral perimeter

The complete posted emission-site set is **305, 343, 345, 390, 452, 456, 549, 564, 698, 706, 720, 908, 913**. All other emissions run through main-asserting public/private UI entry functions or the main detector callback. `prepareStream` itself has no requireMain but is reached from main-asserting choose; its immediate catch at 405 remains in that calling context. `networkSaveAllowed` is callable from main/control and deliberately posts. Do not “simplify” posts or add coroutines/flows/LaunchedEffect/event queues.

Preserve, byte for byte outside allowed notice RHS/type substitutions:
- Singleton lifetime; two single-thread executors esv-save-control(queue16) and esv-save-transfer(queue1), AbortPolicy (62–63,925–926); no additional workers.
- Inspection/candidate/page/identity fences (253–291,309–317) and immediate selection-null before submit (331–332).
- Destination admission callbacks and their isCurrent closures, direct/HLS request construction, lease acquisition/revocation, coordinator submit/execute/resume/cancel and publishRecords placement.
- All preparationGeneration increments/checks, cancellation ownership and stream.discard ordering (356–469), especially post deliver/discard before setExecuting(false) and variant handoff ownership.
- launchExecution foreground/network/execution reservation gates, discovery bookkeeping and resource cleanup, scheduling-deferral distinction (526–574).
- All recording gates, system consent grant handoff, unique request IDs, id checks, timeouts (10 s start, 120 s pending consent), denial paths, native resource occupancy fences, service lifecycle, record retryBudget=0 (588–777).
- Existing bounded network operation.retryBudget=2 (916–918), timeout/resource budgets and all core policies. “No automatic retry” for this migration means **no new automatic retry/resume/reinspection/fallback or method switching**; it does not remove existing bounded core retry policies.
- Network changes only retire/check transfers. Wi-Fi waiting is not retryable and remains manual; rendering/localization cannot call resume or create a new task.
- Periodic refresh/expiry ownership, background pausing, authentication generation and navigation authority, task counts and media publication truth.

## State, persistence, privacy and presentation scope

- Notice is transient application state, not SaveRecord. The singleton at 920–924 survives activity recreation; the state disappears with process death. No Bundle/SavedStateHandle/prefs/journal/JSON/codec notice representation was found.
- SaveRecord (save/SaveModels.kt:224–243) separately contains `error: SaveError?`, phase/wait reason/retry data. SaveRecordCodec writes error enum names at 55 and reads at 88; it does not store runtime.notice. Do not change this codec/schema/version, create migrations, serialize a typed notice, or change core error enums.
- The model payload allowlist is safe enum(s) and maxUnfinishedTasks Int. Notice should retain no Application/Activity/WebView/selection/source/record/service/callback/Throwable. No new logging, analytics or toString exposure is needed.
- Read/render boundaries: SaveTaskScreen.kt:77–79 directly draws the string; MainActivity.kt:562 forwards to ui/BrowserScreen.kt:70,271; MainActivity.kt:441 combines `recordedEditorNotice ?: saves.notice` into RecordingScreen.kt:34,147.
- Resolve the Tasks notice at its direct use, and resolve the Browser/Recording strings immediately at the two MainActivity call sites within the current EsvLocaleHost. This preserves the existing Screen String contracts. Keep `recordedEditorNotice` precedence (and other consent/segment/editor notices) intact; avoid inventing a global “all diagnostics” conversion. Browser's null/dismiss behavior and SaveTaskScreen's dismiss button must remain.

## Separate native notification boundary (explicitly outside runtime.notice)

AndroidSaveRuntime.kt:147–162 builds a **different** `label` from RecordingSnapshot.state then calls recordingService.showStatus(id,label):
- 151 PAUSED: 已暂停，通知可停止并保存
- 152 RECORDING: 录制中，系统选择的共享范围
- 153 STOPPING/FINALIZING: 正在结束录制
- 154 CANCELLING: 正在放弃录制
- 155 RECOVERY_REQUIRED: 系统资源仍未退出，请在系统中结束应用
- 156 all remaining states: 正在准备或验证录制

There is one additional call at 707 with the same recovery-required label. Thus 2 call sites in this runtime, 6 distinct labels (7 literal occurrences across these paths), no runtime.notice reads. The nearby long notice at 706 is a distinct app notice and belongs in this migration.

RecordingForegroundService.kt:65–68 checks session ID/finishing and passes status to NotificationManager.notify; notification builder at 83–92 writes status into setContentText. It owns channel title `屏幕录制` (52), initial status `正在准备录制` (54), title `③ 屏幕录制` (90), action `停止并保存` (92). These require a separate Android/service locale design if later requested. Never route a composable resolver into a Service, modify foreground notification behavior, alter notification/channel identifiers or change PendingIntent/session authority in this slice.

## Verification plan (not run in this read-only audit)

1. **Exact reverse runtime check, strongest narrow proof:** capture the baseline file/hash below; maintain an explicit allowlist of the type declaration and each changed notice RHS span. Reverse only those exact replacements (including restoring the whole detector when block/network resource expression) and compare entire file bytes with baseline. Fail for changed/missing/reordered callbacks, gates, return/cleanup statements, post boundaries, workers, retry values, native labels, or extra mutations. Do not use a broad “strip strings/comments/whitespace” normalizer that can hide changes.
2. Assert inventory exhaustiveness: 49 assignments/44 emissions/5 clears, original null state; 34 fixed + 2 network fixed values, six detector reasons, 45 error enums/35 expected equivalence groups, exactly one Int-only parameter. Test exact expected group sets rather than merely nonempty resources.
3. Bilingual resource parity, nonempty values, matched placeholder count/types and capacity argument origin. Verify long messages preserve all safety clauses (manual retry, no method fallback, no auth reuse, no success before validation/commit, retention/recovery, no microphone/silent fallback, no TLS bypass). Strict Chinese baseline preservation catches subtle losses; allow only explicitly reviewed text changes.
4. Locale-switch test holding each notice value steady: en→zh→en should redraw immediately on Browser, Tasks and Recording with no emitted runtime event or notice recreation. No application.getString, Locale.getDefault, cached remember(locale-less) or static cached String in notice production/rendering. Capacity formatting at values 1,100,1000 must be locale-consistent and never reflect activeTaskCount.
5. Source/shape privacy tests: typed payload contains only allowlisted enum/Int fields; no String/Any/Throwable/Context/callback/request/record fields. Mappers are pure and have no coordinator/browser/network/recorder authority.
6. Existing regression suites to retain: SaveSameDocumentContractTest, SaveInspectionPageTest, SaveUiPolicyTest (update the two legacy helper assertions at 75–76 to the new resources/mapping or keep helper test-only), SaveAuthenticationWiringTest/NavigationTest, SaveFailureGuidanceContractTest/Test, SaveWifiWiringContractTest/NetworkPolicyTest, SaveTaskCapacityTest, SaveDisplayResourcesTest/FormattingTest, MediaTaskActivityHandoffTest/ExpiryTest/SaveLinksTest, SaveRecordCodecTest, core SaveWifiCoordinatorTest and SaveCoordinatorTest.
7. Especially keep current focused invariants: SaveSameDocumentContractTest detector fences/no automatic full scan; SaveWifiCoordinatorTest.gateWaitsWithoutTransferAndOnlyExplicitResumeCanContinue and completedTransferLocalWorkAndPublishedSuccessIgnoreLaterNetworkChanges; SaveWifiWiringContractTest no retryable Wi-Fi policy; MediaTaskActivitySaveLinksTest failure cannot retry/change save truth; authentication/failure bridges cannot invoke task commands.
8. Future authorized device acceptance should cover stale callbacks, repeated clicks, background/return, selection dismissal, locale change with retained notice, queue rejection, recording consent interruption and old-service callback; source/JVM checks alone do not prove native callback ordering or actual render behavior. This audit performed none of those executions.


Baseline SHA-256, AndroidSaveRuntime.kt: `0c0f3c806b60ba63290db802b185df6301570211f3e4319926e5231d36e55a76`.
