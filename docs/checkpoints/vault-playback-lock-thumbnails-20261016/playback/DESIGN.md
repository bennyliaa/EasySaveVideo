# Vault 单项受控播放：最小下一片设计

日期：2026-10-16 UTC。状态：外部设计候选，等待独立审阅；不是已实施/已验收结果。

## 0. 结论与实施边界

可以继续的最小叶子是：现有已解锁且已揭示目录中的单项引用 → 现有 root/auth/store 打开的同一锚定密文 → 一个受控随机读 capability → 专用 Media3 Progressive/MP4 DataSource → 受保护 host 中的手动播放、暂停和 seek。

不得从 `VaultMediaRuntime.launch` 返回一个异步播放器继续使用的 reader：该函数的 finally 会关闭全部 retained resources、清空 currentSession。播放必须由既有 `VaultRuntime` 持有一个独立生命周期的播放叶子，复用同一 gate、root、认证/存储 owner、取消谱系及 cleanup 计数；不能另造认证层、数据库、通用媒体调度器或路径入口。

真正播放器接入采用待独立审阅的有限释放组合：renderer 实际返回/异常 ledger、owned Loader executor 实际终止、exact AudioTrack 回调后的同对象状态读回、受控 reader/file 关闭、真实 Surface 退场。精确 Media3 1.11.1 的 release/onPlayerReleased/默认音频 onReleased 都不能单独作为 drain。此组合有公开扩展点，不改 Media3、不增 Activity、不重写音频层；编译/运行和设备接受仍未完成。证据见 `media3-release-findings.md`。

本轮没有修改 source、运行 Gradle、构建 APK、操作设备、启动真实认证/Keystore 或运行旧探针。`AndroidVaultRuntimeEntry.ENABLED=false` 不变；库字节/依赖准入/既有安全和设备 gate 不变。文件散列见 `SOURCE_READ_BASELINE.json`；它是读参考快照，不冒充整个仓库封存证据。

## 1. 原需求与当前事实

- PR-18 与产品文档 §8 明确“解锁后受控播放”、免费、锁定时关闭私密画面；不能据此删除生物识别、恢复、后续完整管理等原需求。
- TR-12 §8.2–8.3 要求 AEAD、资源限制、受控流式解密、自动锁撤销会话/明文缓存、最近任务安全占位；不接受验签失败后继续播。
- 架构 §9 的 VaultObject → 用户解锁 → 受控解密播放是本片落点。最新 `03_Development_Milestones_update1.md` 的 M5 是 MediaLibrary/Vault；旧文档的 M6 编号保留原意，不用编号差异重排当前范围。
- T-L05/T-L06/T-L07、T-P07 是后续平台验收关联项；JVM 或假资源测试不能直接把它们改成 PASS。
- 现 `openMediaRead` 已验证同 owner root、snapshot、ref、对象 generation、catalog revision、同锚密文 file identity、整密文 extent/SHA、非空 final segment 和 EOF；读取仍须经过受控 AEAD 随机读和最终 publication gate。
- `AndroidVaultAuthentication.createReader` 当前只容纳一个 reader。所有播放、move/copy、批量导出与恢复的冲突必须在同一个 runtime gate 仲裁，不能只禁 UI 按钮。
- `VaultControlledMediaReader` 捕获原始 `VaultOperationContext`，每次 read 必须共享其 cancellation lineage 且不能延长 deadline。不能为每次 seek 创建不相关 context，也不能靠换 context 延期。
- `VaultProtectedHost.hostResourcesRetired()` 目前只看 dialog/input；`PrivateMainPresentationOwner` 当前 Vault claim 的 contentView/fullscreenView 为 null。增加真实 Surface 后，两个点都需显式接入。
- 普通 `VerifiedMediaPreview` 依赖普通 FileInputStream/MediaPlayer/FD owner。只可借鉴交互方式，不能把 Vault 密文、解密 FD、临时明文或该普通预览 owner 塞给它。

来源：source 内 `docs/requirement and design/{01_Product_Requirements,02_Architecture_and_Data_Model,03_Development_Milestones_update1,04_Technical_Requirements,05_Test_and_Acceptance_Strategy}.md`；精确当前源文件见 touch map 与读参考散列。

## 2. 用户行为及明确非目标

1. 用户已完成现认证，手动揭示目录，再点击某一项“打开播放”。准备可以解析，但初始 `playWhenReady=false`；没有自动播、自动切下一项或自动恢复。
2. 就绪后可播放/暂停、前后 15 秒、从头定位。暂停保留当前已获授权的会话；暂停不是锁定或释放。seek 不扩大授权，不启用缩略图预览、抓帧或磁盘 cache。
3. 跳转保留暂停/播放意图；duration/seekable 未知时禁 seek。目标用溢出安全加法，约束到 `[0,duration]`，采用明确的 Media3 sync seek 精度，不声称逐帧精确。结束保留 ENDED 的有限 UI 状态；再次播放须用户点击。
4. 关闭播放、离开 Vault、后台、遮盖、失焦、锁屏、宿主销毁/替换、保护窗口资格丢失，都立即撤销本播放读取/输出发布，再执行实际释放。返回后必须重新手动打开，不保存播放引用、位置、画面或自动续播任务。
5. 播放撤销与认证锁状态分开。沿既有自动锁 reducer 的立即/1/5/15 分钟/重启策略决定 root 是否仍有效；不把普通离页、后台遮盖或关闭播放变成无条件永久销毁解锁状态。无需改 core 自动锁算法。
6. 捕获授权/例外有效时只拒 Vault 揭示、打开播放及继续输出。不得为了 Vault 成功或失败停止普通连续录屏，也不得调用录屏 stop/cancel/revoke。已经在录的普通会话照旧；Vault 不借 browser capture 例外。
7. 本片不加私密导出/分享、PiP、后台音频、MediaSession、通知控制、Cast、外部播放器、外部 URI、网络/HLS/DASH、字幕/图片输出、DRM、playlist、双播放器、自动预加载、持久播放历史/进度或私密缩略图。保留原需求，后续另片完成。

## 3. 同 owner 生命周期与有限 API

以下是内部候选签名，落在 app/vault；不进入平台中立 core。命名可在 source 审阅前统一，但权限与释放语义不可弱化。

```kotlin
// UI 只能给出当前目录的普通引用，拿不到 root/reader/file/player。
VaultRuntime.openPlayback(ref: VaultMediaReadRef): Boolean
VaultRuntime.playPlayback(ticket: VaultPlaybackTicket)
VaultRuntime.pausePlayback(ticket: VaultPlaybackTicket)
VaultRuntime.seekPlayback(ticket: VaultPlaybackTicket, targetMillis: Long)
VaultRuntime.closePlayback(ticket: VaultPlaybackTicket)

internal interface VaultPlaybackHostPort {
    // 回调/事件必须绑定确切 ticket + host generation；晚到一律不能挂 surface。
    fun offerPrepared(ticket: VaultPlaybackTicket, prepared: VaultPreparedPlayback)
    fun revokePresentation(ticket: VaultPlaybackTicket)
    fun observeRetirement(ticket: VaultPlaybackTicket): VaultPlaybackDrain
}
internal enum class VaultPlaybackDrain { LIVE, RETIRING, RELEASED, UNCONFIRMED }
// ticket 是 runtime identity registry 内的 process-local 对象，不以相等字段重建权限。
// prepared 只流转于内部 owner/host adapter，不进入 Compose state 或 SavedState。
```

不新增通用 handle framework。播放叶子只持有一份有限 ledger：exact ticket、捕获的 readLease/root/authEpoch/presentationEpoch/generation/snapshot/ref、一个 operation context、一个 file、一个 reader、一个实际播放器 owner、一个 surface owner、打开/读/释放中的真实任务计数与 sticky failure。

准入必须在现 gate 一次完成：

- 既有 session permitsPresentation，route+foreground 当前，未 closed/failureSticky，现认证/恢复/凭据 jobs 不占用；实际目录的 readLease 仍当前，ref 属于刚揭示且同 revision 的认证目录。
- `media.pending()/active()` 为 false、owner drains 全部可确认；没有上一个播放的 LIVE/RETIRING/UNCONFIRMED。
- 主线程 host 的 exact claim/输入 epoch/secure+capture 禁令先检查；异步创建/首次 Surface 绑定/每条用户指令在 UI owner 再检查。
- 播放 reservation 必须在离开 gate 前安装。正常 media session 获取在播放 occupied 时拒绝。反向先有 media reservation 则拒 playback。恢复和新认证同理。

准备使用同 store.openMediaFile、authentication.openMediaRead。每个file/reader/player/renderer/audioOutput/executor factory调用之前，先给exact ticket登记ACQUIRING义务；返回资源立即由原票据收养，即使已撤权也只能清理，不能转移给新票据。throw/null不能因没有可见handle就自动记零：只有已有owner/实际factory合同能够证明无资源取得或真实释放，才结账；否则保留UNCONFIRMED和原owner。晚到/cancel/rejected executor不能漏掉返回前义务。准备任务结束只能移交到同 ticket 的持续 ledger，不能把 reader 交给 launch finally 或让 worker jobs 归零冒充播放完全退出。

播放结束/撤销之后，read/reader close/file close 走原有唯一 owner，`observeMediaDrain` 不得因未接入播放而漏算。root 关闭可能主动触发 reader retirement；播放 ledger必须接受同一真实 close 的幂等观察，不能二次盲关 underlying channel。既有 cleanup/hostCleanupComplete 条件显式纳入播放 ledger。

## 4. 有限预算与 context 设计

沿用单对象 2 GiB、ciphertext extent 与单 read 最大 `VaultMediaLimits.BUFFER_BYTES`、Tink 单 reader 约 2 MiB 工作集。不得把 export 的 30 分钟任务 timeout 偷偷当用户播放时长。

候选固定策略供本次独立审阅：准备/整密文认证最多沿既有 30 分钟预算；一次前台打开的播放 context 最大 24 小时；单次数据读取 stall 30 秒；实际释放观察 5 秒后只显示 UNCONFIRMED，不 decrement ledger、不延长 auth、不把期限当 release。24 小时是 source-only 有限会话保护上界，实际平台参数和产品提示需设备验收；不减少原自动锁配置或以活跃播放自动延长背景 grace。

在 openMediaRead 前创建整个播放 lifetime context，准备有独立 watchdog：超时取消同一 lineage。不能给 openMediaRead 一个短 bounded child 然后在异步阶段交一个更长 parent，因为 reader.requireOwner 捕获的原 context 已过期。每次 read 用 lifetime 的 bounded child，deadline 不超过 lifetime；read timeout 取消整次播放，后续返回只能清理。

每条play/pause/seek在实际执行点重查exact ticket、host/input generation、仍然前台/当前/允许发布；enqueue时通过不等于以后仍获准。该校验用owner同步/epoch事实，不能在后台线程无同步读取Compose/View可变状态。主线程平台绑定/发布前另核真实host。revoke立即提高epoch且不可逆。

同一线程队列仅保留一个待执行 seek（覆盖旧 target）、一个待执行 play/pause 意图、一个不可丢弃且优先的 revoke/close；coalescing绝不能覆盖或推迟revoke。资源获取与释放任务有限且不重试创建；队列满走终态撤销，不能清掉 active 任务或伪造已释放。普通 UI 位置更新最多每 250ms 一次；无无限轮询重启/seek/release。

Media3 buffer target 只是加载阈值，不是整个内存硬上限。候选 source-only配置：backBuffer=0、不 retain keyframe、不 preloading、前向 target=8 MiB、buffer 时间 1–5 秒、单 parser loading检查=64 KiB。不得据此宣称 decoder/native/metadata 总 RAM≤8 MiB。已验证普通 MP4 输入谱系、受限 extractor、恶意 atom/sample 测试与设备内存实测仍需验收；若必须新增绝对 sample allocator cap/zeroing adapter，应单独小审，不能把库内存零化承诺写成已有事实。

## 5. Media3 受控 DataSource 合同

限定 `ProgressiveMediaSource.Factory(ownedFactory, ExtractorsFactory{Mp4Extractor()})`，一个 item、一个 period、一个读源。不使用 DefaultMediaSourceFactory/DefaultDataSource/HttpDataSource/CacheDataSource/SimpleCache。仅复用已准入 1.11.1 工件；不补 media3-ui、session 或新库。

Media3 需要一个 item URI 字段，使用固定 `Uri.EMPTY` 作为非地址占位。它不包含名称、vault/object ID、path、token 或用户信息，也不授予权限；所有 authority 来自注入的 exact playback lease。`getUri()` 打开时返回该占位，关闭时 null。禁止把任何字符串转成路径/网络/ContentResolver 参数；不能有 fallback。

DataSpec 校验：仅 empty URI、GET、无 body、无自定义 cache key/自定义数据/uriPositionOffset，位置非负。注意精确 ProgressiveMediaPeriod 自己添加 ICY 请求头和 `FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN | FLAG_ALLOW_CACHE_FRAGMENTATION`：只容纳此精确固定技术组合并忽略其网络含义，不错误要求全空导致真实 Media3 路径无法打开；响应 headers 必须为空，不提供 ETag/重定向/ICY 元数据。除固定技术组合外的任意输入拒绝。metadata/mediaId 使用固定技术值，不传私密条目名给 Media3 analytics 或平台媒体会话。

open 的边界与官方 DataSource 完全一致：

- position < authenticatedSize：实际可读字节上限为 size-position；无限长度 open 返回剩余字节。
- 有限请求 length 可超尾；open 返回请求 length，read 在真实 EOF 返回 END_OF_INPUT，不能向 caller 多返回或分配补齐数据。
- position==size：open 成功，正长度 read 立即 EOF。
- position>size：抛可分类 position-out-of-range IOException，不从头回退。
- 禁止以 `position + length` 先做可能溢出的算术；用剩余差值和 min。

read(0) 返回 0；正常正长度读取最多 BUFFER_BYTES，直接调用现 reader.readAt 的最终受控发布，不额外复制进持久/共享缓存。拒绝负值和非法 range；每次调用检查 exact ticket/lineage，撤销后连旧缓存命中都不能返回。任何不可恢复 crypto/extent/generation/EOF 错误终止该播放，不静默 seek 或换数据源重试。

DataSource.close 只结束这次 parser transport open，不关闭整个 reader：MP4 extractor 在 seek/重新定位时会 close+open。只有 playback owner 在终止时撤销 reader并等待 active read 真返回后关闭 reader/file。Factory/open/close 的计数与同一 ticket 绑定；只允许一个 concurrently open transport；close 重复幂等，partial open失败仍有 cleanup。seek 后 reopen 复用同一锚定 file/reader，不再次从 UI ref/URI 获取能力。

音频明确设置`ALLOW_CAPTURE_BY_NONE`，禁offload/tunneling与未登记输出旁路；不修改普通音频/连续录屏策略。

Error mapper只生成固定安全 IOException/error code，不能串上原 exception.message、URL、名称或路径；原 cause不得交给会自动记录正文的公开 listener/logger。不可通过“关错误日志”隐藏资源失败。日志/metrics测试包括 Media3自身错误路径；显式`ExoPlayer.Builder.setUsePlatformDiagnostics(false)`关闭platform media metrics/reporting，不挂EventLogger/Crash/upload adapter。此flag是官方1.11.1公开API，不影响保留必要技术错误和释放事实。

官方契约：
- [DataSource 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/datasource/src/main/java/androidx/media3/datasource/DataSource.java)
- [ProgressiveMediaPeriod 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java)
- [DefaultLoadControl 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java)

## 6. 播放器/Surface 实际退场：不能缩写为 release 回调

准备与实际播放均只在捕获的 host generation 对应的受保护 Surface 上进行。SurfaceView须 `setSecure(true)` 在 holder创建前设置；保留现窗口 FLAG_SECURE。不得设置z-order-on-top或media-overlay越过既有opaque cover。由 host登记 exact View/holder/Surface generation，纳入 PrivateMainPresentationOwner 实际 composition shape。UI onDispose只提出退出；空 composition、遮罩覆盖、stop命令排队都不是 player/surface 真实退场。

撤销顺序：

1. 同 gate撤销ticket、context、读publication；主线程立即撤销action并安装cover barrier。root/认证是否最终锁定仍由现 reducer决定。
2. 拒绝新 prepare/seek/play/attach；播放器命令owner请求 pause/mute/stop并去掉输出surface。每项失败都是本ticket sticky事实；不得影响普通录屏owner。
3. 保留player listener直到release调用/错误分类收敛；执行Media3 release与其明确的renderer/output/loader观察合同，详见release findings。不得把onPlayerReleased或onLoadCanceled当成功。
4. 每个已开始 read必须真实返回、scratch已清理、permit真正finally归还；reader.closeAndObserve RELEASED；随后同file.closeAndObserve RELEASED。未知close不重复底层调用。
5. cover/撤销先立即安装独立opaque遮盖并撤交互；正常路径先不改SurfaceView visibility/attachment，以免INVISIBLE/GONE提前触发surfaceDestroyed。offMain实际detach成功且没有detach-timeout/失败事实后，Main才主动hide/remove并移出hierarchy，推动exact holder销毁。detach失败/超时仍可安全移除View但保留UNKNOWN，系统先destroy也不能伪认player detach已完成。此流程不得等待ordinaryReleaseAllowed或hostResourcesRetired，避免把surface纳入drain后的循环等待。hide仅是立即遮挡，最终仍须Surface detached/destroyed真实事件与exact holder配对；不可仅将变量设null。Surface晚到创建必须在旧ticket上立即安全退场，不能绑定到新播放器。
6. 只有播放器已选定的可观察释放证据、DataSource/loader收尾、reader/file、surface和现dialog/input全部满足，才让 hostResourcesRetired/cover receipt/ordinaryReleaseAllowed 为true。source-only尚未选定实际音频释放adapter时，此分支仍是未实现，不以“总是true”或“总是unknown”上线。

时间到只有安全占位+有限故障提示，强持有该exact owner，阻止替换播放器和绕过credential cleanup。不要无限创建播放器/Activity、刷新来重建owner或反复release。最小有限secure-retain异常路径可由review选择：仅保留受影响window的保护和旧owner，显示可理解的关闭/重启提示；不能将此异常路径冒充每次正常播放的成功退场，不能在未授权情况下杀进程。

“实际释放”指本版本可观测到的API/owner资源终态，不声称证明Android内核/codec实现没有任何隐藏资源，也不声称系统已逐字节擦除全部曾经解码的RAM。需要观测的例外是已知异步AudioTrack release：它确实会在上层返回之后继续，不能忽略。


播放器有两个明确且有限的非UI looper职责：`ExoPlayer.Builder.setLooper(applicationLooper)`固定构造/所有public Player命令和监听的application/command owner；`setPlaybackLooper(playbackLooper)`固定Media3内部播放及AudioOutput事件线程。二者不能混为“已指定后台播放线程所以public release不阻塞Main”。Main只做cover、View/holder生命周期、当前host校验和有限UI状态更新；不调用或等待可能同步等待的clearVideoSurface/release，不join/await。player构造、play/pause/seek、setVideoSurface/clearVideoSurface和release都在同一application looper执行。

Main的SurfaceHolder.Callback只登记exact Surface/holder/generation并投递到application owner；以`setVideoSurface(Surface)`绑定该借用surface，不将需Main生命周期的SurfaceView/holder回调交给不匹配的application looper。holder拥有的Surface不能由adapter随意release；主线程View移除驱动holder实际destroy，播放器自身输出脱离证据独立记录。销毁先撤ticket再投递detach，晚到attach必须被execution current fence拒绝。正常路由/status重组也不能抢先dispose SurfaceView：在现protected root的唯一Compose child内部保留一个同host的固定surface slot，其生存期由exact detach/retirement驱动；metadata与交互UI仍按cover立即清除。该slot不展示额外私密内容、不绕过opaque cover、不新增Window/owner，退场不等待ordinaryReleaseAllowed。系统/异常先destroy时继续fail-closed收集实际证据，不能假造detach成功。两个owner队列都有限；application looper在release返回、监听终态收集和最后回调fence完成后才退出，playback looper按下面audio规则退出。

### 6.1 本次优先候选：公开 hook 的同对象证据

- `DefaultRenderersFactory` 返回的每个实际 renderer 都以官方 `ForwardingRenderer` 做窄生命周期代理。记录 exact 实例、是否曾 enable、disable/reset/release 的进入/正常返回/异常；关闭secondary/prewarming，若实际仍创建任何secondary必须登记。不把默认no-op release当此前disable/reset成功。异常在Media3内部catch之前记录，技术错误不含私密字段。
- `ProgressiveMediaSource.Factory.setDownloadExecutor(supplier,releaser)`为每个实际period给一个先登记的serial executor；release调用只启动orderly shutdown，另一个受限观察worker等待真termination。关闭新period/source准入后，全体executor的真实退出证明load与随后onLoaderReleased/extractor清理已返回；executor仅shutdown/无当前任务不算。任务异常也登记，不能以异常死亡冒充成功。
- 通过 `DefaultAudioSink.Builder.setAudioOutputProvider` 注入一个 `ForwardingAudioOutputProvider` 包装默认provider。每次getAudioOutput调用前先登记该ticket的ACQUIRING义务；返回后由原票据收养 exact `AudioTrackAudioOutput`及其公开getAudioTrack，然后确认该track曾为STATE_INITIALIZED并登记每个独立output/track的Listener。即使返回时已撤权，仍必须由原票据登记、发起释放并等其证据；throw/null按前述获取义务收敛，不能算成“从未存在”。全部构造必须覆盖，format/sessionId/count不能代替对象identity。
- 该output的onReleased可能来自finally，所以只在其同一Handler handoff之后观察同一track的getState()==STATE_UNINITIALIZED，才登记该output的终态。AOSP API31/36参考实现是在native_release正常返回后才写入该状态；这提供API边界证据，不承诺硬件buffer擦除。初始化失败/不同output类型/缺回调/仍initialized均不算成功，不能盲retry release。未知类型关闭本片播放，不fallback新audio owner。
- 使用caller-owned非UI playback looper（`setPlaybackLooper`）；Media3内部release不会自动关掉此looper。保持它存活直到已排入的release控制屏障、全部audio terminal事件、renderer和loader/reader ledger收敛；再由非UI worker quit/join并确认正常退出。独立Handler屏障只证明顺序，不代替任何资源返回。
- `Player.Listener`保留到release调用之后，同步latch release/detach timeout与error。单纯等待超时与实际cleanup异常分开记录：晚到的同owner实际完成证据可以使RETIRING收敛；实际disable/reset/close失败仍sticky，不能因后续无错误而抹掉。撤销和旧generation永远不可逆。

其余平台线程不加入无限join框架：已准入codec adapter reset/release的API返回与异常是本片所承诺边界；旧callback经generation fence无权发布。已知异步audio属于必须补足的显式例外。所有API31–36/OEM假设、回调顺序和失败注入须在对应测试中验证；只能先宣称source-feasible。

同对象包装是优先方案。备用AudioTrack subclass/MediaDataSource或独立Window是不同实现选择，当前不纳入touch map，不能未经重新review替代。

## 7. Touch map 与不得碰的边界

建议第一片保持可独立审阅，不把播放器输出问题散落到多层。

新增（具体是否合并文件由owner决定）：
- `app/.../vault/VaultPlaybackContracts.kt`：有限ticket/view/drain/profile，纯值+owner接口。
- `app/.../vault/VaultPlaybackRuntime.kt`：同VaultRuntime持有的单播放叶子；资源登记/当前性/读gate/cleanup。
- `app/.../vault/playback/VaultMedia3DataSource.kt`：精确读适配器；不接触auth/store构造。
- `app/.../vault/playback/AndroidVaultPlayback.kt`：Media3 owner、Surface绑定、精确实际释放观察；需要release审阅决议。
- `app/.../vault/VaultPlaybackUi.kt`：手动有限控件与现SkinKit visual consumer。

窄修改：
- `VaultRuntime.kt`：注入/构造播放叶子；现session同gate互斥；effect/protectPresentation/cleanup/hostCleanupComplete接入；保持自动锁reducer语义。
- `AndroidVaultRuntimeEntry.kt`：只给原singleton注入平台playback factory；ENABLED保持false，不另造auth/store/lane权限入口。
- `VaultProtectedHost.kt`：同claim action与native surface登记/真实retirement；不得新增browser capture例外。
- `AndroidVaultPresentationOwner.kt`、`VaultRuntimeUi.kt`：有限播放view，cover清除敏感view，旧generation回调拒绝。
- `PrivateMainPresentationOwner.kt`：真实Vault播放View的shape/当前性；现FORBIDDEN capture policy不变。
- `MainActivity.kt`：只在原protected root的单Compose child内连接host持有的稳定surface slot，防route/status重组先dispose；不增root child/window、录屏控制或任何gate旁路。
- `res/values*/strings.xml`：中文/现有语言必要文案，不写死业务主题。
- 对应单元测试/窄source contract与后续固定fixture测试。

预期不用改：core reducer、Vault cryptographic/wire/catalog schema、AndroidVaultStore、Recovery flow、批量journal、ordinary VerifiedMediaPreview、Recorder/Projection停止逻辑、依赖版本/锁/准入散列、Manifest外部provider/新权限。若实施需要上述改动，先说明具体缺口并重新review，不以“顺手修复”扩scope。

## 8. 分片与停止条件

A. 可先签的读/lifetime基础：finite接口、同gate互斥、DataSource、fake native owner、源合同/JVM测试；production gate保持false。不宣称已有真播放。

B. 在精确release候选获签后接真实Media3/Surface和UI；使用现已准入binary，保留真实timeout/异常事实，重新review最终diff。

C. parent独占build后跑focused与全回归，正式artifact seal；不覆盖旧gate的NOT_RUN证据。

D. emulator-5556未来验收仅在父任务明确安排后执行，保留数据且兼容签名/递增code。此次设计任务不发起设备/真实Keystore/账号操作。真实密钥、个人媒体、其他设备和旧probe均禁止。

本设计完成的停止条件：原需求映射、finiteAPI/touchmap、参数/行为/故障测试合同、exact release问题与最小候选、独立review意见交父。不是持续研究到证明所有平台细节；不能跨过release决议擅自实施。
