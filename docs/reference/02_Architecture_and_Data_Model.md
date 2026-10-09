# EasySaveVideo 架构与数据模型

| 属性 | 内容 |
|---|---|
| 基线 | EasySaveVideo Specification v0.3_CN / 2026-09-25 |
| 状态 | 正式架构约束；具体库版本、参数与可行性仍须 M0 验证 |
| 需求来源 | [01 产品需求](01_Product_Requirements.md)，编号 PR-01～PR-25 |
| 实现约束 | [04 技术要求](04_Technical_Requirements.md) |
| 替代关系 | v0.1_CN、v0.2_CN 不再作为实现基线 |

## 1. 架构目标与依赖方向

```text
                    EasySaveVideo Specification v0.3
                         /                  \
                        v                    v
             Android 原生实现             iOS 原生实现（后续）
          Kotlin / Compose / WebView     Swift / SwiftUI / WKWebView
                        \                    /
                    相同协议、行为、测试向量
                    各自 PlatformCapabilities

                 +------------------------------------------+
                 |            EasySaveVideo Core            |
                 | VideoDiscovery | SaveEngine              |
                 | Entitlements   | MediaLibrary / Vault    |
                 | Task Commands  | Error / Recovery Policy |
                 +-------------------+----------------------+
                                     |
                             领域接口 / Ports
                    +----------------+----------------+
                    v                v                v
              Provider Adapters Platform Adapters Store Adapters
               搜索/API/规则     Browser/文件/录制   Play/StoreKit
                    |                |                |
                 外部来源        操作系统/官方库       商店服务
```

图中的调用通过 Core 声明的接口完成；编译依赖是 Adapters 指向 Core，Core 不引用 Android、StoreKit、Google Play、NewPipe 数据类型。应用装配层负责注入具体实现。Specification 共享不要求 Kotlin Multiplatform、共享 UI 或逐行翻译 Android 代码。

| 模块 | 职责 | 不得承担 |
|---|---|---|
| Browser | 标签、导航、网页 Session、页面渲染生命周期 | Provider 调度、购买验证、视频转码 |
| MediaDetector + SiteAdapter | 用户触发媒体分析、生成 MediaCandidate | 全网搜索、隐式下载、绕过鉴权 |
| Provider Framework | Registry、统一接口、Runtime、版本/策略校验 | 直接操作 UI、Vault、Store |
| VideoDiscoveryCoordinator | 合并各来源响应、会话一致性、过滤去重、入池 | 无界爬虫、控制系统录屏 |
| DiscoveryScheduler | 高低水位、预算、前台开关、单任务调度 | 后台永久常驻、重试无上限 |
| ResultPool / Pagination UI | 有限库存、稳定分页、真实可见性反馈 | 把 Prefetch 当成 SHOWN |
| SaveCoordinator | ①②③执行计划、权益/授权入口、任务终态 | 写入具体商店 SKU、强制静默录制 |
| DirectDownloader / StreamSaver | 各自协议与文件写入 | 控制其他引擎生命周期 |
| Recorder / Encoder / Editor | 捕获、片段、编码、裁剪、拼接、压缩 | 自动批准权限、直接覆盖原片 |
| MediaLibrary / Vault | 文件索引、归档、加密、解锁与导出 | 依赖付费才能安全保存 |
| EntitlementManager / FeatureGate | 权益快照、到期、能力准入 | 执行视频处理、识别网站 |
| RecoveryManager | 有界恢复、隔离、清理与通知 | 无证据删除用户媒体、无限阻塞启动 |

建议 Android 工程模块：`core-model`、`core-domain`、`discovery`、`provider-runtime`、`browser`、`media-detector`、`save`、`recorder`、`editor`、`library`、`vault`、`entitlements`、`platform-android`、`store-fake`、`app`。M1 可先按包划分，但接口边界与独立测试必须成立；不允许一个巨大 Activity/ViewModel/Service。

## 2. 三类可替换适配器

| 接口类别 | 输入 | 输出 | 替换要求 |
|---|---|---|---|
| Provider Adapter | SearchRequest、ProviderProgress | ProviderPage、ProviderError | 删除后 Core、Browser、Save 不需改动 |
| Platform Adapter | 领域命令、请求上下文引用 | CapabilityResult、Artifact、ErrorEnvelope | 平台能力不足返回明确状态 |
| Store Adapter | Purchase/Restore/Refresh 命令 | 已验证的权益来源状态 | 不向 Core 暴露 SDK 对象或 SKU |

```text
Provider A --[独立 deadline / budget]--+
Provider B --[独立 deadline / budget]--+--> Coordinator --> ResultPool
Server C ---[独立 deadline / budget]--+
    | 一个失败                  |
    +-> 熔断/暂时禁用           +-> 其他来源不取消

全部 Provider 不存在/关闭/失败
    -> 空态 + 可选普通网页搜索
    -> 地址栏 -> Browser -> MediaDetector -> SaveCoordinator
```

Provider 独立性包含编译、启动和运行三层，不只是 UI 开关。SiteAdapter 与 Search Provider 分离，删除搜索来源不得顺带删除一般媒体检测。某站独有解析能力可能减少，但受支持通用测试媒体的①②③必须保留。

## 3. 平台无关接口契约

以下为语言无关伪接口，不规定 Kotlin 或 Swift 语法。所有长操作返回可观察状态，并接受取消信号；结果和错误不通过异常穿透模块边界。

```text
VideoSearchProvider.search(SearchRequest, ProviderProgress?, OperationContext)
    -> ProviderPage | ProviderError

VideoDiscoveryCoordinator.start(SearchProfile) -> DiscoverySessionId
VideoDiscoveryCoordinator.stop(sessionId, reason) -> CheckpointResult
DiscoveryScheduler.onDemand(PoolDemand, LifecycleSnapshot, ResourceBudget)
    -> ScheduleDecision
ResultPool.readPage(sessionId, pageIndex, pageSize, revision) -> PageSnapshot

MediaDetector.analyze(BrowserContextRef, userIntent, OperationContext)
    -> MediaCandidate[] | DetectionError
SaveCoordinator.submit(SaveRequest, OperationContext) -> TaskId
SaveCoordinator.respond(TaskId, UserDecision) -> TaskSnapshot
Recorder.start(RecordingRequest, ConsentHandle, OperationContext) -> RecordingId
TaskController.cancel(TaskId) -> CancelResult

EntitlementManager.snapshot() -> EntitlementSnapshot
FeatureGate.evaluate(FeatureRequest, EntitlementSnapshot, PlatformCapabilities,
    BuildFeaturePolicy) -> GateDecision
MediaLibrary.commit(VerifiedArtifact, Destination) -> MediaItemId
Vault.unlock(AuthenticationRequest) -> ScopedUnlockHandle
```

| 公共类型 | 字段 | 不变量 |
|---|---|---|
| OperationContext | operationId、deadline、cancelToken、retryBudget、resourceBudget | 重试继承总预算；不能递归重置 deadline |
| ErrorEnvelope | code、module、retryable、userAction、safeContext | 不含 URL/关键词/文件名/凭证/原响应 |
| PlatformCapabilities | direct、streamProfiles、record、internalAudio、backgroundSave、region、export | 不可用必须有 reason；不伪装跨平台等价 |
| GateDecision | ALLOWED / DENIED_ENTITLEMENT / UNAVAILABLE_PLATFORM / BLOCKED_POLICY | 权益不能覆盖系统拒绝或内容保护 |
| TaskSnapshot | taskId、revision、state、method、progress、errorCode | revision 单调；终态不会被迟到回调改回运行 |

等待用户时释放网络/编码资源，持久化为 `WAITING_USER`。该状态属于可恢复挂起而非无限执行：必须具有取消入口、过期时间和恢复动作。通用执行终态为 `SUCCEEDED / FAILED / CANCELLED`；进程中断先记为 `INTERRUPTED`，经 Recovery Check 转为可恢复等待或失败。

## 4. Discovery 数据流、预算与分页

```text
SearchProfile + epoch
       |
       v
Scheduler -- eligible providers --> search(request, cursor)
                                      |
                             schema / size / domain 校验
                                      |
             Normalize -> Filter -> Deduplicate -> HistoryPolicy -> Rank
                                      |
                 [事务: 入池 + ProviderProgress 检查点]
                                      |
                                   ResultPool
                                      |
                               稳定 PageSnapshot
                                      |
                         UI 可见性 -> Seen History
```

### 4.1 Scheduler 状态与水位

`availableCount` 是当前策略下尚未分配给已物化页、仍可供新页使用的唯一候选数量；已返回页面保留可回看快照，但不继续算未浏览库存。被严格历史策略过滤掉的候选不计库存。

```text
                 前台 + 当前 Discovery 活跃
                             |
                  +----------v----------+
                  | IDLE / CHECK_DEMAND |
                  +----------+----------+
                             |
                  availableCount < L ?
                    /               \
                  是                 否
                  v                  v
               FILLING          BUFFERED / IDLE
                  |
         达 H / 本轮预算耗尽 / 无新增
                  |
           +------+----------------+
           v                       v
        BUFFERED              BACKOFF / EXHAUSTED

任意运行状态 -- App后台/离开发现/取消 --> SUSPENDED / CANCELLED
SUSPENDED -- 返回前台并重新有效 --> CHECK_DEMAND
```

| 参数 | 暂定起点 | 规则 |
|---|---:|---|
| 每页 `P` | 20 | 用户可改；保持当前锚点并重建后续页 |
| 低水位 `L` | 2P = 40 | 低于此值触发一轮补充 |
| 高水位 `H` | 5P = 100 | 达到即停；不追求固定找满所有来源 |
| 单会话候选上限 | 500 | 超限淘汰未显示旧候选；不能删当前页/Seen History |
| Provider 全局并发 | 平衡 2；省电 1；积极不超过 3 | 受设备/网络/温度预算进一步收紧 |
| 单 Provider 并发 | 1 | 防 cursor 竞争与上游突发请求 |
| 单轮页请求预算 | 最多 6 次 | 全部重复/空结果也消耗预算 |
| 无新增阈值 | 连续 2 页 | Provider 进入 cooldown，不能无限翻页 |

这些是本版建议的验收配置，不是历史已确认性能数值。M0 测量后可以记录调整，但始终满足 `0 < L < H <= poolCap`。用户选大 pageSize 时必须重新计算有效上限，不能因设置导致无限内存。库存够时不发 Provider 请求；网络失败遵守退避；低电量与保存/录制竞争时优先用户已发起任务。

### 4.2 会话一致性

1. 搜索词、影响来源查询的筛选、语言或安全设置变化时生成新的 `queryKey`；换搜索生成新的 `epoch`。
2. Provider 请求捕获 sessionId、epoch、providerVersion。迟到响应若不匹配则丢弃，不写新池或新进度。
3. 一页处理成功后，把结果入池、去重索引和下一 cursor 放在同一事务中。未入池不能先前移 cursor。
4. 后台先关闭调度门，再取消正在请求/解析/预取的工作，保存最后完整检查点。已经完成但迟到的响应不得再追加。
5. 重启恢复当前页和缓存结果；cursor 过期可重置该 Provider，Seen History 不清除。
6. 包版本改变或远程排序改变不重排当前页；后续请求使用新版本并评估 cursor 兼容性。

### 4.3 过滤、去重与分页稳定性

| 顺序 | 规则 | 注意事项 |
|---|---|---|
| Normalize | 统一字段/时间单位/来源名；保留合法原页面链接 | 不任意删除决定资源身份的 URL 参数 |
| Filter | 非视频、无效 URL、用户来源/时长筛选、明确不支持项 | 未知时长与不满足筛选分开；未知不能伪造 |
| Identity | 首选 sourceNamespace + sourceVideoId；再 canonicalPageUrl | Provider ID 不应使同站同视频重复 |
| Deduplicate | 同 identity 合并来源和更完整字段 | 相同标题/时长只作相似提示，不能轻率硬合并 |
| HistoryPolicy | 四策略依据 SeenRecord | `SHOW_ALL` 不关闭本会话去重 |
| Rank | 来源相关度归一、历史优先、稳定 tie-break | 不要求本版建立用户画像或遥测 |
| Pagination | 物化有序 resultId 列表并记录 revision | 异步新结果放后续页，避免当前页面跳动 |

严格历史模式下，本会话已物化页面仍允许返回查看；历史标记阻止它再次进入新的页，而非在用户眼前即时删除卡片。切换模式时从当前锚点构造新视图 revision，说明结果变化；不改变底层媒体文件。

## 5. 数据模型与持久化

所有 ID 使用平台无关字符串，时间使用 UTC epoch milliseconds；UI 按本地时区显示；duration 使用毫秒。可选字段显式 null，不以 0 表示未知。数据库 schemaVersion、Specification version、Package version、Runtime version 独立管理。

```text
SearchProfile 1 --- N DiscoverySession
      |                    |
      |                    +--- N ResultPoolEntry --- 1 VideoSearchResult
      +--- N ProviderProgress         |
      +--- N SeenRecord <-------------+ fingerprint

BrowserTab --- BrowserContextRef --- N MediaCandidate
                                       |
                                       v
                                    SaveTask
                                       |
                      +----------------+----------------+
                      v                                 v
               RecordingSession                   DownloadCheckpoint
                      |
                    N Segment -> ProcessingJob -> VerifiedArtifact
                                                       |
                                                       v
Folder 1 --- N MediaItem ------------------------- ArtifactCommit
                  |
               VaultObject（可选）
```

### 5.1 Discovery 模型

| 实体 | 关键字段 | 主键/约束/生命周期 |
|---|---|---|
| SearchProfile | profileId、query、queryKey、filters、locale、historyPolicy、createdAt | queryKey 含规范化词+影响查询的参数；本地敏感功能数据 |
| DiscoverySession | sessionId、profileId、epoch、state、poolRevision、pageAnchor、updatedAt | 一个当前 epoch；关闭可恢复；缓存按容量淘汰 |
| VideoSearchResult | resultId、fingerprint、identityVersion、title、thumbnailUrl?、sourceName、pageUrl、durationMs?、description?、publishedAt?、provenance[] | pageUrl 是页面，不是下载 URL；字段有长度上限 |
| ResultPoolEntry | sessionId、fingerprint、orderKey、status、discoveredAt、expiresAt | 唯一(sessionId,fingerprint)；不含视频二进制 |
| PageSnapshot | sessionId、revision、pageIndex、orderedResultIds、pageSize | 同 revision 页内容稳定；总页数可未知 |
| ProviderProgress | profileId、providerId、providerVersion、cursorType、cursorValue、queryKey、cursorSchemaVersion、expiresAt?、exhausted、lastSuccessAt | 唯一(profileId,providerId,queryKey)；版本不兼容重置 |
| SeenRecord | profileId、fingerprint、firstDiscoveredAt、shownAt?、openedAt?、playedAt?、savedAt?、dismissedAt? | 唯一(profileId,fingerprint)；事件独立、幂等、不倒退 |
| ProviderHealth | providerId、localFailureCount、cooldownUntil、lastErrorCode | 只存必要恢复信息，不存响应正文或用户搜索轨迹 |

`SeenRecord` 不是单值枚举：保存可能发生在没有可靠播放信号的页面，不能用一个最大状态抹掉真实事实。`SHOWN` 的实现建议为卡片至少 50% 可见持续 500ms（M2 校准），`DISCOVERED` 仅表示找到。无痕上下文使用内存 Session，不写长期 ProviderProgress/SeenRecord；退出时清理。

### 5.2 保存、录制与媒体模型

| 实体 | 关键字段 | 约束 |
|---|---|---|
| MediaCandidate | candidateId、browserContextRef、pageRef、resourceRef、type、variants[]、requestContextRef、capabilityHints、expiresAt? | URL/凭证为敏感引用；不写日志；不保证可保存 |
| MediaVariant | variantId、width?、height?、bitrate?、codec?、container?、estimatedBytes?、durationMs? | 估算必须标记；音视频分轨需描述关联 |
| SaveRequest | requestId、idempotencyKey、candidateId、selectedVariant?、destination、userIntent、allowedMethods | 不能由 Provider 直接创建；必须来自用户或未来已授权命令 |
| SaveTask | taskId、requestId、state、method、attemptCount、progress、checkpointRef?、errorCode?、createdAt、updatedAt | 同 idempotencyKey 不重复建任务；成功只提交一个主媒体 |
| DownloadCheckpoint | taskId、resourceIdentity、etag?、lastModified?、bytesWritten、segments[] | 续传核验资源身份；不同资源不能拼接 |
| RecordingSession | recordingId、taskId?、mode、state、region?、audioMode、effectiveDurationMs、captureConfig、segmentIds[] | 同时至多一个活跃捕获会话；授权句柄不可持久化复用 |
| RecordingRegion | normalizedRect、captureWidth、captureHeight、rotation、insets、transformVersion | UI 坐标与视频像素坐标必须显式变换 |
| Segment | segmentId、recordingId、sequence、privatePathRef、durationMs、codecConfig、orientation、verificationState | 仅验证合格片段可参与拼接 |
| ProcessingJob | jobId、inputRefs、operation、outputTempRef、state、deadline、commitId | 不覆盖输入；支持取消和恢复 |
| VerifiedArtifact | artifactId、privateLocationRef、bytes、container、tracks、durationMs、verificationResult | 文件非空不等于验证成功 |
| MediaItem | mediaItemId、artifactId、folderId、displayName、durationMs、size、dimensions、createdAt、privacyClass | 只有已提交 artifact 进入正常列表 |
| Folder | folderId、parentId?、name、privacyClass、order | 普通/私密命名空间隔离；移动是事务 |
| ExportTask | exportId、mediaItemId、destinationGrantRef、state、outputRef? | 外部导出独立于内部保存成功状态 |
| VaultObject | objectId、keyVersion、ciphertextRef、encryptedMetadataRef、formatVersion、integrityInfo | 不保存明文文件名、缩略图或未包装密钥 |

### 5.3 权益、更新与恢复模型

| 实体 | 关键字段 | 约束 |
|---|---|---|
| EntitlementSnapshot | revision、grants[]、validatedAt、validUntil?、sourceState | 免费能力不依赖网络；付费 UNKNOWN 不自动视为购买 |
| EntitlementGrant | entitlementId、state、expiresAt?、originRef | ACTIVE/EXPIRED/REVOKED/UNKNOWN；Core 不读取商店收据 |
| ProductMapping | mappingVersion、store、productId、grantSet、productType | 仅商店/配置层知道 SKU；未知 SKU 不授予权益 |
| ProviderManifest | providerId、packageVersion、specVersion、runtimeRange、minAppVersion、domains、capabilities、requiredEntitlements、limits、payloadHash、signingKeyId | 签名覆盖 manifest 与规则内容；ID/路径规范化 |
| InstalledProvider | providerId、activeVersion、lastKnownGoodVersion、status、health | 激活指针持久化；有可验证旧版才可回滚 |
| OperationJournal | operationId、kind、phase、inputRefs、tempRefs、commitRef、updatedAt | 功能恢复状态，不是业务日志；私密引用不泄漏内容 |

### 5.4 数据保留与删除

| 数据 | 存放 | 清理规则 |
|---|---|---|
| SearchProfile / Seen / Progress | App 私有本地数据库 | 用户可按关键词/全部清除；设容量上限并提示淘汰影响 |
| ResultPool / 缩略图 | 私有缓存 | TTL/LRU/空间预算淘汰；与长期 Seen 分离 |
| Cookie / Token / 请求上下文 | 隔离 Session 或受保护短期存储 | 最短生命周期；退出无痕立即清理；不过度持久化 |
| 普通媒体 | App 私有目录或用户授权目录 | 仅用户删除/明确移动；不能当缓存回收 |
| Vault / 私密索引 | 加密存储 | 不因购买过期、认证失败或缓存修复而删除 |
| 临时片段/处理中产物 | 私有任务目录 | 终态后按引用关系清理；可恢复片段保留提示 |
| ERROR 日志 | 独立限额目录 | 轮转/到期清除；不能从数据库导出行为记录 |

缓存可重建；长期历史损坏不能悄悄以“清缓存”重置；Vault 密文和密钥失效不能通过重建空数据库假装修复。备份排除私密明文、Session 凭证及不可跨设备恢复的密钥相关材料。

## 6. Provider Package 更新与回滚

```text
Repository（签名索引/建议版本/禁用策略）
                         |
                         v
CHECK -> DOWNLOAD staging -> VERIFY SIGNATURE/HASH/COMPATIBILITY
                                     |
                                VALIDATE RULES
                                     |
                                LIMITED SELF-TEST
                                     |
                        ATOMIC ACTIVATE version pointer
                                     |
                          PROBATION（本地健康检测）
                            /                 \
                           v                   v
                    ACCEPT AS LKG          ROLLBACK TO LKG
                           |                   |
                        CLEANUP          QUARANTINE BAD VERSION
```

| 阶段 | 必须检查 | 失败结果 |
|---|---|---|
| 下载 | 包大小、连接/总超时、磁盘空间 | 旧版本继续；临时包可安全清理 |
| 验签 | 受信任 keyId、签名、完整 payload hash | 拒绝；不得因 HTTPS 正常而跳过验签 |
| 兼容 | spec/runtime/app version、平台允许的能力 | 标记不兼容，不改活动指针 |
| 规则校验 | 域名、重定向、字段长度、请求数、正则预算、路径穿越 | 拒绝超范围规则 |
| 试运行 | 固定测试向量、解析结构、资源预算 | 不使用真实用户数据上报 |
| 激活 | 同文件系统原子指针替换及恢复记录 | 启动恢复到完整新版本或旧版本 |
| 健康观察 | 有界失败计数；版本级隔离 | 恢复可验证 LKG；若旧版也不可用则停用 |

回滚不是任意接受旧签名包：普通更新要求单调版本，批准的回滚需要签名策略指明允许版本，并仍满足撤销/安全下限。过期、撤销密钥或不安全旧包不能因 LKG 名称被重新启用。信任根轮换、离线策略和签名算法由 M0/M6 安全方案冻结。

Runtime v1 只执行有限 HTTP 模板、HTML selector、JSON path、受限 regex、分页及字段映射。新算法或协议需要 App 更新；不得通过规则字段偷偷引入脚本解释器。Repository 不接触 Vault 或商店购买数据。

## 7. SaveCoordinator 状态机与降级

```text
CREATED -> ANALYZING -> WAITING_SELECTION（需要时）
                           |
                           v
                      CHECK_DIRECT
                           |
                    RUNNING_DIRECT ① ----- success ----+
                           |                           |
              METHOD_UNAVAILABLE / 可降级失败          |
                           v                           |
                     CHECK_STREAM                      |
                           |                           |
            Gate通过 -> RUNNING_STREAM ② ---- success -+
                           |                           |
              METHOD_UNAVAILABLE / 可降级失败          |
                           v                           |
                 WAITING_RECORD_CONFIRMATION           |
                           | 用户同意+权益               |
                    WAITING_SYSTEM_CONSENT              |
                           | 官方授权                   |
                     RUNNING_RECORD ③ ---- success -----+
                                                       v
                                         FINALIZING -> VERIFYING
                                                       |
                                                   COMMITTING
                                                       |
                                                   SUCCEEDED

任意执行态 -- cancel --> CANCELLING -> CANCELLED
任意执行态 -- 不可恢复错误 --> SAFE_STOP -> FAILED
进程中断 -> INTERRUPTED -> Recovery Check -> WAITING_USER / FAILED
缺权益/选项/权限 -> WAITING_USER（可取消、有截止时间、不占执行资源）
```

| 错误类型 | 是否自动重试 | 是否允许下一方法 | 处理 |
|---|---|---|---|
| 短暂网络错误 | 有界退避 | 不能把相同断网直接转录屏 | 网络恢复后重试或等待用户 |
| 无 Direct 资源 | 不重复空扫描 | 可评估② | 显示方法变化 |
| 流格式不受支持 | 不重复同一解析 | 可建议③ | 确认后系统授权 |
| 用户取消 | 否 | 否 | 取消整个请求，不偷偷继续 |
| 存储满/写入失败 | 修复后才重试 | 否 | 保留有效临时产物，提示释放空间 |
| Cookie/Token 过期 | 经用户重新登录后 | 不作为绕过授权的手段 | 等待重新认证 |
| DRM/受保护输出 | 否 | 否 | 明确不支持，不尝试绕过 |
| 权益缺失 | 否 | 只能用户主动选择其他已拥有且可用方式 | 不将购买当作运行失败 |
| 编码/封装失败 | 参数替代有界重试 | 不能递归回①造成循环 | 保留输入，明确失败 |

成功是“验证且提交”的状态，下载字节完成不等于成功。转换为 MP4 的基础兼容处理是 SaveEngine 输出职责；用户主动压缩/画面裁剪才是高级工具能力。

## 8. Recorder、片段与编辑

```text
IDLE -> WAITING_CONSENT -> PREPARING -> RECORDING
                                       |
                     +-----------------+----------------+
                     | 暂停（M5）                       | 停止
                     v                                  v
                 PAUSING -> PAUSED -> RESUMING       STOPPING
                                |       |                |
                                +-------+----------> FINALIZING
                                                        |
                                                  MERGING（多段）
                                                        |
                                                  VERIFYING -> COMPLETED
任意异常 -> SAFE_STOP -> 保留已验证 Segment -> FAILED / 待用户恢复
```

Recorder 所有控制串行化，重复 start/stop 幂等；release 只执行一次。`effectiveDurationMs = sum(valid Segment duration)`，不包含暂停等待。每个 Segment 保存编码配置、方向、音轨与序号；不能直接拼接不兼容配置。

```text
UI rectangle
  -> 去除/映射 Insets
  -> 旋转与缩放变换
  -> Capture canvas
  -> 编码像素坐标（偶数尺寸/边界检查）
  -> Crop recipe
```

选区变化、旋转、分辨率变化的策略必须固定：M4 可选择安全停止并说明；M5 可在分段边界重建配置，统一输出参数后合并。不能静默拉伸或录错区域。录制控制条是否进入成片须实机验证，未证实前不得宣称自动排除。

Editor 使用不可变输入引用与 ProcessingJob。Merge/Crop/Compress 输出到新临时文件，验证成功后提交为新版本；用户取消或失败不修改原片。高级录制器和 SaveCoordinator 通过 RecordingRequest/Result 通信，不能互相读取内部可变状态。

## 9. MediaLibrary / Vault 提交协议

```text
prepare -> execute -> verify -> atomic commit/replace -> cleanup
   |          |          |               |                 |
写Journal  新临时产物  容器/轨道/完整性   文件指针+DB状态    删除无引用临时项
   +----------+----------+---------------+-----------------+
                              |
                   进程被杀 -> Recovery Check
```

文件系统与数据库不共享事务，不能假设一次 SQL 提交就保证视频文件持久。采用 journal + 幂等补偿：先创建可验证文件，再记录可恢复提交意图，原子切换私有文件指针，提交数据库可见记录，最后清理。崩溃恢复按 commitId 对账，确保既不重复入库也不丢旧文件。

SAF/外部目录不保证原子 rename；先在私有空间形成已验证主产物，再复制到目标新文件，校验后发布可见完成状态。中断的外部文件标记失败/清理，不删除内部主产物。相册导出使用平台允许的待发布机制；只有验证后使之可见。

```text
普通 MediaItem -- 移入 Vault --> 加密 staging
                                  |
                               完整性校验
                                  |
                             提交 VaultObject
                                  |
                     更新索引 -> 清理普通源/缓存

VaultObject -- 用户解锁 --> 受控解密播放
                     +--> 明确导出 -> 普通 MP4 -> 相册/Files
锁定/退出/异常 -> 关闭解密句柄 -> 清理临时明文 -> 最近任务遮罩
```

PIN/密码不能只是 UI 门锁。密钥解封必须绑定有效认证会话；生物识别失败可使用受保护密码路径。具体 KDF、AEAD、分块随机访问与 Keystore 包装方式属于 M6 安全设计，不自创加密算法。Vault 锁定时后台任务如无可用加密写入能力，必须等待或安全停止，不能悄悄落普通明文目录。

## 10. Entitlement 与商品映射

```text
StoreAdapter -> 验证后的 Purchase State -> ProductMapping
                                             |
FakeEntitlementSource（仅开发） --------------+-> EntitlementManager
                                                    |
                                             FeatureGate.evaluate
                                                    |
                         Entitlement AND Platform AND BuildPolicy
                                                    |
                                               Core Command
```

| 领域权益 | 免费或商品映射 | 入口 |
|---|---|---|
| `DIRECT_SAVE` | 永久免费基础能力 | DirectDownloader |
| `VAULT` | 永久免费基础能力 | Vault；不得被付费撤销影响 |
| `PROVIDER_PLUS` | Provider Plus | 高级 Provider 激活与调用 |
| `STREAM_SAVE` | Save Pro | StreamSaver |
| `RECORD_SAVE` | Save Pro | 基础 Recorder |
| `SEGMENT_RECORDING` | Video Tools Pro | 暂停/继续片段编排；录制还需 RECORD_SAVE |
| `REGION_RECORDING` | Video Tools Pro | 框选录制；录制还需 RECORD_SAVE |
| `VIDEO_CROP / VIDEO_MERGE / VIDEO_COMPRESS` | Video Tools Pro | 独立高级编辑 |

`SAVE_PRO`、`VIDEO_TOOLS_PRO`、`EASYSAVEVIDEO_PRO` 是商品概念，不替代细粒度领域权益。总包由 ProductMapping 展开为 grants；Core 不写 `if GooglePlaySku == ...`。退款/到期禁止新任务；进行中任务如何安全完成由会话权益策略定义，不能中途删除用户文件。推荐已合法开始任务允许完成，M8 根据商店条款核验。

## 11. 故障隔离与启动恢复

```text
                    App Shell / 地址栏
                           |
            +--------------+--------------+
            |              |              |
       Discovery域       Browser域       Save域
       Provider独立      Renderer恢复     Downloader / Stream
            |              |              |
          熔断          标签重建      Recorder / Encoder隔离
            +--------------+--------------+
                           |
                RecoveryManager（有界检查）
                           |
          能恢复 -> 恢复；不能 -> 隔离/安全停止；UI继续
```

进程级崩溃无法由同进程 try/catch 全部隔离。M0 必须区分可取消协程错误、WebView renderer 进程故障和 native encoder 崩溃；必要时评估独立进程服务。产品目标是把风险限制到尽可能小的范围，不宣称任何 Bug 都不会导致进程退出。

| 恢复对象 | 检查 | 动作 |
|---|---|---|
| 数据库 | 能否打开/迁移、版本一致性 | 失败时隔离问题库；浏览器独立启动 |
| 下载 | checkpoint 与源身份、临时文件一致 | 条件满足才续传，否则等待用户重试 |
| 录制 | 片段索引、有效封装、未完成 session | 保留有效片段；不自动恢复系统录屏授权 |
| Provider | 活动指针、签名、LKG | 恢复完整有效包，否则禁用该来源 |
| 编辑 | 输入完好、临时输出、commit phase | 幂等完成提交或移除未提交产物 |
| Vault | 密文完整性、密钥状态、明文残留 | 先清理明文；密钥异常隔离而非删库 |

Recovery Check 优先做轻量检查，长扫描分批异步；数据库/Vault 问题不能让启动无限转圈。安全模式至少提供地址栏，前提是系统 WebView 自身可用；WebView 也不可用时提供清晰错误和系统浏览器入口，不能保证不存在底层平台故障。

## 12. Automation / TaskQueue 扩展契约

```text
当前 UI Intent ----+
                  +--> Command API -> FeatureGate -> Task Repository
未来 Automation --+                         |
                                             v
                                  有界 TaskQueue / Executor
                                             |
                        SaveCoordinator / Editor / Discovery
                                             |
                                      TaskSnapshot / Result
```

预留 `CommandId`、`idempotencyKey`、`origin`、`constraints`、`requiresUserAction`、`cancel`、`observe`。当前任务中心是用户发起任务的管理，不等于已实现自动化。未来自动化不得使用隐藏 Activity 模拟授权，不得绕过 Vault 解锁、权益检查或后台 Discovery 禁令；如未来要引入定期后台发现，必须先正式变更 Specification。
