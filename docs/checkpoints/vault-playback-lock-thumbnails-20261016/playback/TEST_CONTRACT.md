# 可执行测试合同：Vault controlled playback

状态：实施时必须转换为对应参数化 JUnit / fixed-fixture 测试的验收合同；此处没有声称已有生产测试实现或已运行。测试顺序、fake 接口、交错点、断言和未来命令明确列出；不得用静态 grep 代替行为测试。数字向量见 `DATA_SOURCE_VECTORS.json`。

## Harness / 证据规则

- `VaultPlaybackRuntimeTest` 复用现测试的 fake VaultAuthenticationPort/VaultStorePort、固定非用户目录、手动 clock、queued executor、manual scheduler。提供一个 fake native owner：prepare/attach/release/loader/audio/surface 各有独立可控 receipt，不在默认 close 自动一起成功。
- `VaultPlaybackDataSourceTest` 使用固定字节 `[0..255]` 重复、fake reader，记录每次 readAt 的 position/length/context identity；可在 `before IO / after IO before permit.publish / after publish before finally / before close return` 用 CountDownLatch 精确挂起。
- `VaultPlaybackRetirementTest` 使用受控 renderer、executor、audio output identity + initialized/uninitialized state、Surface generation。必须能独立触发“回调发了但资源仍未退场”，否则不是有效 failure test。
- 所有挂起测试的 await 都有测试 harness 自身有限超时，超时判测试失败；断言资源状态用真实controlled event，不用sleep后猜测。不要通过更长timeout让错误用例过关。
- JVM tests 不创建真实 AndroidVaultAuthentication、Keystore、数据库、FileProvider、设备、MediaProjection或用户凭据。固定Tink向量只能用既有已允许的test fixture/固定fake registry，明确不代表设备认证。
- 结果报告分别写 SOURCE_CONTRACT、JVM_FAKE、EXACT_ARTIFACT_COMPILE、FIXED_ANDROID_MEDIA、DEVICE_SECURITY；不得合并成“Vault通过”。保留原全部NOT_RUN gate。

## A. DataSource/crypto publication（未来类 VaultPlaybackDataSourceTest）

| ID / test 方法 | 注入/步骤 | 必须断言 |
|---|---|---|
| DS01 `unboundedOpenResolvesAuthenticatedRemaining` | size=10，position 0/3，length unset | 返回10/7；只读真实剩余；不重认证/不新建file |
| DS02 `boundedOpenPreservesRequestedLengthBeyondEof` | size=10,pos=8,len=8 | open返回8；实际read=2后EOF，不返回补零；与官方契约一致 |
| DS03 `exactEofOpenSucceeds` | pos==size，无限及有限长度 | 成功；read(positive)=-1；read(0)=0 |
| DS04 `beyondEofHasPositionOutOfRangeCause` | pos=size+1 | IOException可被DataSourceException.isCausedByPositionOutOfRange识别；不回退0 |
| DS05 `rangeAndOverflowAreBounded` | negative pos、Long.MAX length、invalid offset、read0 | 不做overflow加法；invalid失败；不修改sentinel destination |
| DS06 `splitsLargeReadAtCapabilityMaximum` | read请求BUFFER_BYTES+37 | 每次readAt≤BUFFER_BYTES，允许short read；连续最终字节完全等于fixture |
| DS07 `acceptsOnlyPinnedProgressiveTechnicalSpec` | Uri.EMPTY + pinned ICY header + pinned cache flags | 真实generated spec可读；这些字段不触发网络/缓存 |
| DS08 `foreignUriBodyKeyHeadersCannotSelectData` | http/file/content URI、POST/body、key、自定义header | 全拒；auth/store/open计数不增加，无resolver/network fallback |
| DS09 `closeReopenForSeekKeepsSameReader` | read头，close，open尾，close，open中 | 同reader/file对象；reader.closeAndObserve在parser close时未调用 |
| DS10 `secondConcurrentOpenIsRejected` | sourceA已open时sourceB/open | 只一个concurrent transport；不创建第二crypto reader |
| DS11 `cancelBeforePublicationLeavesCallerUnchanged` | 挂起AEAD读后publish前，revoke/cover，再放行 | destination仍sentinel；permit实际finally前inflight不能归零 |
| DS12 `revocationRejectsCachedAndSeekReads` | 先缓存segment，revoke，再同position/seek | 都失败且无字节发布；不得靠Tink缓存绕过gate |
| DS13 `deadlineLineageCannotBeExtended` | unrelated context / deadline超过reader parent | 拒；合法bounded child可读；过期同lineage也拒 |
| DS14 `closeWaitsActualIoAndRemainsStickyOnFailure` | read阻塞，关闭请求，5秒观察；释放后底层close抛 | 等待时不RELEASED；抛后UNCONFIRMED且底层close次数=1 |
| DS15 `failedOpenAndDuplicateCloseRemainOwned` | 注册后open失败、重复close、晚到factory结果 | 不丢ledger；每个实际resource最多一次真实close |
| DS16 `tamperExtentFinalSegmentGenerationFailClosed` | 固定fixture篡改ciphertext、缺尾、extra byte、stale ref | playback准备不发布；不忽略错误继续；fixture无需真实Keystore |
| DS17 `noUnsafeErrorCauseOrMetadataEscapes` | 假错误含敏感诱饵路径/标题/URI | 对外error/view/log仅固定code，不含原cause/message；getUri为空marker |

`DATA_SOURCE_VECTORS.json` 必须被上述参数化行为测试读取或等值编入；它不是当前Source已通过的报告。

## B. 同runtime lifecycle（未来类 VaultPlaybackRuntimeTest）

| ID / test 方法 | 交错点 | 必须断言 |
|---|---|---|
| RT01 `requiresRevealedCurrentCatalog` | locked/ready-but-not-revealed/stale catalog/old host/ticket | 无prepare/read/open；不能凭猜中ref字段授权 |
| RT02 `startsPausedAndNeedsManualPlay` | 打开、prepare ready | playCount=0；明确play后才playing |
| RT03 `playbackAndExportAreAtomicExclusive` | 两个线程同时play与single/batch/move | 同gate仅一个胜者；crypto reader≤1；败者无journal/file变更 |
| RT04 `recoveryCredentialWaitsForPlaybackDrain` | 播放LIVE/RETIRING/UNCONFIRMED时recovery | 不能发起新credential/root/store owner；关闭完成后按现规则再准入 |
| RT05 `handoffOutlivesPreparationFinally` | prepare worker finally完成，稍后seek/read | worker jobs可归零但playback ledger仍live；reader未被launch finally关掉 |
| RT06 `latePreparationAfterCoverCanOnlyRetire` | 文件已打开，reader即将返回时cover | reader到达只登记并关，不能新bind/ready/播放 |
| RT07 `pauseSeekAndEndDoNotInventAuthentication` | pause、seek、ended、replay | 不改authEpoch/auto-lock policy；seek不自动play；播放需用户动作 |
| RT08 `coverStopsPlaybackWithoutAlwaysLockingGraceRoot` | 对立即/1/5/15分钟/重启策略逐一：离页、后台 | 都撤playback/画面；root锁状态与旧reducer预期完全一致；返回不自动play |
| RT09 `explicitLockDeviceLockAlwaysRevoke` | read/pause/prepare各阶段显式lock/keyguard | 同lineage立即cancel；不得late publish；旧ticket永不复活 |
| RT10 `capturingRejectsOnlyVault` | 普通continuous authorization活动；打开或中途qualify失败 | Vault拒显示；fake录屏stop/cancel/revoke计数均0，原录屏owner不变 |
| RT11 `activityReplacementCannotBypassDrain` | 老host detach但surface/audio未完成，新host attach | 原singleton与资源保留；新host不得接旧capability或凭空declare retired |
| RT12 `timerReportsUnknownWithoutForgedDrain` | 卡住read/loader/audio/Surface逐个 | 期限后有限error，无owner decrement，无auto re-create，无release重试循环 |
| RT13 `boundedCommandsCoalesceSeek` | 10000次seek/play/pause，close插入 | 待seek≤1、意图≤1；close不可丢；无无界队列；overflow安全 |
| RT14 `operationsNeverPersistPlaybackState` | play/seek/pause/exit/process模拟 | 无journal/catalog写入、无SavedState/Prefs/index/cache，重启无resume |
| RT16 `factoryObligationPrecedesEveryAcquisition` | 对file/reader/player/renderer/audioOutput/executor逐个在factory内挂起、返回晚到/抛/null | 调用前已计ACQUIRING；撤权后原ticket收养并清理；throw/null无独立未获取/已释放证据不可计零 |
| RT17 `queuedCommandsRecheckAtExecution` | enqueue允许，执行前host/input epoch变化或capture授权变更 | play/pause/seek不执行；coalescing不能覆盖revoke，旧票据不可复活 |
| RT15 `statusAndCallbacksAreExactGeneration` | 旧ready/position/error/ended晚于cover与新session | 不能改新view或绑定surface；旧callback可为旧资源结账但不能发布 |

## C. Media3退休证据（未来类 VaultPlaybackRetirementTest / exact artifact adapter）

| ID / test 方法 | 故障/正常路径 | 必须断言 |
|---|---|---|
| RE01 `releaseReturnAndAnalyticsEventAreNotProof` | public release返回并onPlayerReleased，但renderer挂起 | hostResourcesRetired=false |
| RE02 `synchronousReleaseTimeoutIsLatched` | onPlayerError timeout在release内部同步发 | listener还在；记录exact session；getPlayerError为空不抹掉事实 |
| RE03 `detachTimeoutDoesNotFreeSurfaceProof` | clearVideoSurface返回但detach timeout | 不把字段null当holder退场；secure不降级 |
| RE04 `rendererExceptionsAreRecordedBeforeMedia3Swallows` | disable/reset/release分别抛 | sticky failure；内部完成condition或thread结束都不改PASS |
| RE05 `neverEnabledRendererUsesExactLifecycle` | 从未enable vs enabled各一 | 不要求不可能的disable，但每个resource-using生命周期有对应返回；release-only默认no-op不足 |
| RE06 `loaderCancelCallbackPrecedesRealReturn` | released=true取消回调先到、load仍阻塞 | ledger不归零；DataSource无publish；executor真实终止之前不retired |
| RE07 `serialLoaderTerminationIncludesExtractorRelease` | load返回后排入onLoaderReleased再关executor | 等onLoaderReleased/extractor释放返回且正常termination，不只shutdown |
| RE08 `loaderTaskFailureIsNotSuccessfulThreadDeath` | release runnable/extractor close抛或线程失败 | 保留失败；不以isTerminated=true单独宣布RELEASED |
| RE09 `audioFinallyCallbackWithInitializedTrackRejects` | initial INITIALIZED，onReleased到而state仍INITIALIZED | 此output未retired；无二次release；secure保持 |
| RE10 `audioReleaseNeedsInitialAndTerminalSameIdentity` | A initialized，B uninitialized，交换callback/format/sessionId | 不能替A结账；无initial INITIALIZED记录的输出不能正常算成功 |
| RE11 `audioAllInstancesAreTracked` | flush/reconfigure使2个实际AudioOutput产生 | 全部exact实例terminal才retired；provider总pending=0不能替代 |
| RE12 `audioCallbackStateReadUsesHandlerHandoff` | callback前state已写，手动调度looper | 不用unsynchronized轮询；callback后same-track UNINITIALIZED才记terminal |
| RE13 `callerOwnedLooperLivesThroughLateAudioRelease` | renderer/control已完但audio callback晚到 | looper仍活；audio完成后才quit/join；join不阻塞UI |
| RE14 `lateSuccessCannotEraseActualFailure` | timeout后实际正常完成 vs actual close失败后callback | 前者在exact证据齐全可收敛；后者sticky，不重建owner |
| RE15 `surfaceDestroyedIsExactAndRequired` | root已有遮罩但旧holder未destroy；新holder先到 | old surface未retired；late old/new callbacks不得跨generation结账 |
| RE16 `everyResourceBitIndependentlyBlocksOrdinaryRelease` | 对reader/file/loader/renderer/audio/surface/dialog/input逐个保留一项 | 每种情况下ordinaryReleaseAllowed=false；全部真实完成才true |
| RE19 `publicPlayerWaitCannotBlockMainCover` | 挂起application-looper内clear/release，同时Main执行cover/Surface removal | Main仍可完成opaque cover；正常detach成功前不改SurfaceView visibility/attachment且route/status重组保留同host slot，成功后才hide/remove并等holder；系统先destroy为异常证据。所有public Player调用与构造为同一非UIapplication looper；独立playback looper保持audio事件；无Main join/await |
| RE18 `coverRemovesSurfaceWithoutRetirementCycle` | hostResourcesRetired因surface仍live为false；立即cover | 实际View独立移出hierarchy，exact holder真实destroy后才结账；无z-order-on-top/media-overlay越过opaque cover；不等待ordinaryReleaseAllowed触发移除 |
| RE17 `captureAudioPolicyCannotBeOverridden` | 默认音频output/provider所有创建分支 | AudioAttributes allowed capture=NONE；无audio offload/tunneling/prewarm意外旁路 |

C组的fake状态测试只验证我们自己的ledger。至少RE02/03/06/07/09/11/12/13需另外用精确1.11.1 adapter与固定Android fixture建立真实接口/线程证据；不可把fake完成等同平台完成。

## D. 源码/回归合同

- `VaultPlaybackSourceContractTest`：ENABLED仍false；同一个AndroidVaultRuntimeEntry构造auth/store；没有temp plaintext、FD/proxy/ContentProvider/URL resolver、DefaultDataSource/CacheDataSource、新持久状态或MediaSession；Vault capture仍FORBIDDEN。
- 不调用普通recording stop/cancel/revoke或改变continuous授权。UI availability限制必须与runtime仲裁并存，不能只有按钮enabled。
- `VaultProtectedHostSourceContractTest`：真实playback+Surface retirement并入hostResourcesRetired，且不循环依赖ordinaryReleaseAllowed；窗口真实cover原逻辑保留。
- 复跑现VaultRuntimeTest、VaultMediaRuntimeTest、VaultBatchRuntimeTest、VaultControlledMediaReaderTest、VaultMediaOwnershipTest、VaultRecoveryRuntimeTest，以及相关PrivateMainPresentation/PrivateCaptureWindow/ContinuousRecording owner测试。
- metadata敏感诱饵扫描包含fixed样本名、标题、文件路径、对象ID、错误cause；官方Media3 internal日志也是检查对象。无用户数据参与。
- 原依赖准入runtime equality、library hashes、accessible notices不变；源码审阅不能替代artifact equality。

## E. 后续执行顺序（仅由parent串行build owner在获准改源码后运行）

1. 检查最终diff/touch map，确认无生产gate/依赖/其它设备改动。
2. `./gradlew --offline :app:testDebugUnitTest --tests 'com.easysavevideo.app.vault.VaultPlayback*' --tests 'com.easysavevideo.app.vault.crypto.VaultControlledMediaReaderTest'`。
3. 上述focused运行成功后，跑parent既有full regression与严格dependency检查。命令名称采用当前build owner已验证的task，不新发明等价“全通过”。
4. 检查并保存真实测试XML/编译log/最终source hashes，再给独立reviewer核最终diff。
5. 固定Android fixture/设备任务单独安排；此设计不自行运行Gradle、测试APK、真实认证或旧probe。

## F. 仍为NOT_RUN的设备/平台项

- 精确1.11.1真实音视频prepare/play/pause/seek（头/中/尾、连续seek、moov头/尾、片段边界），API31最小平台和API36目标平台；不同codec/OEM覆盖继续保留gate。
- 像素/Surface：SurfaceView secure与Window secure、实际surfaceDestroyed/重建、旋转/切页/后台/最近任务、截图/录屏/屏幕共享、多窗口和异常遮盖，均不能用JVM替代。
- AudioTrack exact初末state+callback顺序，异步flush/release异常、播放器release超时、looper保持与最终join；真实输出静音/退场与无音频capture。
- Loader阻塞/取消/seek reopen，codec teardown晚到，Activity毁销，无leaked old rendering；长duration/大文件/低内存/内存峰值与前后台。
- 按现各auto-lock policy返回：无自动续播、不泄露旧帧，允许宽松root grace而不继续私密输出。
- 普通连续录屏已运行时Vault始终拒展示，录制会话/时长/声音/输出不因Vault错误被停止；恢复普通页面必须有真实保护退场证据。
- 进程被系统终止后磁盘/备份/日志无本片生成的私密明文或持久cache，不自动恢复读取。无真实用户私密样本或credentials。
- 原Keystore、认证/生物识别、恢复、密钥失效、R8/包内容、发布和其它Vault完整安全gate仍独立NOT_RUN；本片不能“顺便验收”。
