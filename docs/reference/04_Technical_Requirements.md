# EasySaveVideo 技术实现要求

| 属性 | 内容 |
|---|---|
| 基线 | EasySaveVideo Specification v0.3_CN / 2026-09-25 |
| 范围 | Android 首发实现；平台无关 Core 与未来 iOS 适配 |
| 关联 | [02 架构与模型](02_Architecture_and_Data_Model.md)、[03 M0与里程碑](03_Development_Milestones.md)、[05 验收](05_Test_and_Acceptance_Strategy.md) |
| 证据边界 | 本文是技术约束，不是 M0 审计完成报告；官方资料核对不代替源码、构建、真机和许可证审查 |

## 1. 工程与版本要求（PR-01/02/23）

| 项目 | 要求 |
|---|---|
| 工程策略 | 新建干净 EasySaveVideo；选择性迁移已审计 Yue/Captord 模块；不直接 Fork 改名 |
| Android 标识 | package / applicationId / namespace 均为 `com.easysavevideo.app` |
| 最低系统 | `minSdk = 30`，Android 11；历史 31/35 已作废 |
| 语言/UI | Kotlin、Jetpack Compose；Browser 使用 Android System WebView |
| SDK/工具链 | M0 选择稳定、兼容、可复现的 compileSdk/targetSdk、AGP、Gradle、Kotlin、JDK；记录精确版本 |
| 首要环境 | Pixel 7 / API35；API30 兼容环境必测；录制、音频、编码至少真机验证 |
| 媒体基础 | 优先官方 Media3 及系统媒体 API；具体版本和能力由 M0 固定 |
| 状态存储 | Room/等价结构化数据库用于任务/索引；DataStore/等价设置存储；不把复杂对象塞入 SharedPreferences |
| 构建变体 | Debug/Internal/Release，渠道差异通过 StoreAdapter 和 BuildFeaturePolicy |
| 依赖 | 固定版本、禁止未经说明动态版本、记录来源/校验/传递依赖；最小必要依赖 |
| Git | 分阶段稳定提交，保留来源、NOTICE 和修改记录；禁止破坏已有历史 |

Core 的领域接口不接受 Activity、Context、WebView、Uri、BillingClient、StoreKit、NewPipe 对象。平台内部可使用这些类型，但在适配边界转换为自有 ID、能力、结果和错误。Android 与 iOS 共用行为规格及测试向量，不强制共享代码。

## 2. 规范性实现清单

| 技术 ID | 要求 | 产品对应 |
|---|---|---|
| TR-01 | 零 Provider 编译/启动/浏览/保存仍成立 | PR-03/07 |
| TR-02 | Discovery 使用有限候选、预算和前台调度门 | PR-04/05 |
| TR-03 | cursor 和入池事务一致，Seen 独立持久化 | PR-06 |
| TR-04 | 规则包签名、沙箱式资源限制、原子激活/LKG | PR-08 |
| TR-05 | 媒体检测用户触发，凭证隔离，候选明确 | PR-09 |
| TR-06 | ①②流式处理并真实生成 MP4 | PR-10/11 |
| TR-07 | SaveCoordinator 只对适当错误降级 | PR-12 |
| TR-08 | 官方捕获授权、正确生命周期和资源释放 | PR-13 |
| TR-09 | 片段/坐标/时间戳/编码参数一致，非破坏编辑 | PR-14/15 |
| TR-10 | 保存任务生命周期与 Discovery 分离 | PR-16 |
| TR-11 | MediaLibrary 提交与导出分开、文件/DB可对账 | PR-17 |
| TR-12 | Vault 认证与密钥绑定、加密和明文最小化 | PR-18 |
| TR-13 | Core 只识别权益，生产无 Fake 解锁 | PR-19 |
| TR-14 | 日志白名单、脱敏、限额、默认不上传 | PR-21 |
| TR-15 | timeout/cancel/bounded retry/terminal state 与 Recovery | PR-22 |
| TR-16 | 来源/许可/依赖/商店审计先于正式开发 | PR-23 |
| TR-17 | Command API 可供未来 TaskQueue 使用 | PR-24 |
| TR-18 | 无痕、Session、权限与历史存储边界明确 | PR-25 |

## 3. Provider 与 Discovery（TR-01～04）

### 3.1 网络与输入约束

- 只处理 Provider 已缩小范围的有限候选；不实现无深度上限的递归爬虫。
- Provider 请求必须支持 deadline 与取消；HTTP、解析、映射、缩略图分别受预算约束。
- 为每个 Provider 维护独立失败计数和 cooldown；单来源失效不能取消全部兄弟任务。
- 解析字段限制长度、数量、嵌套深度、响应体大小；未知必要 schema 拒绝，可选新字段可忽略。
- thumbnail 按可视区域按需加载；取消前台预取时同时取消缩略图队列；不预下载视频本体。
- 非视频或无法确认的视频候选应按 Provider contract 标注/过滤，不用隐藏 WebView 持续播放来验证所有候选。
- HTTP Redirect 每跳重新校验目标，Provider 网络权限受域名声明约束；默认禁止访问本机、私有地址和云实例元数据地址，测试环境例外必须显式配置且不进入 Release。
- Provider 返回的 URL、HTML、文本不具有 App 命令权限；不得据此调用 Shell、任意文件路径或商店接口。
- TLS 校验不得关闭。搜索来源会接收用户搜索请求，应在隐私说明中说明 Local-first 不等于网络来源看不到请求。

### 3.2 调度预算（本版暂定工程值）

| 项目 | 初始值 | 适用边界 |
|---|---:|---|
| Provider 单次总 deadline | 15秒 | 包含网络与解析；远程只能在本地上限内调整 |
| 网络连接超时 | 5秒 | 可按 M0 网络样本校准 |
| Provider 失败重试 | 最多2次重试 | 仍受本轮总预算/截止时间限制 |
| 退避 | 1秒、3秒，加 jitter | 限流遵守有上限的 Retry-After |
| Provider 正文上限 | 2 MiB | 大响应拒绝，不无限缓冲 |
| 每次返回候选上限 | 100 | 上游超量截断/拒绝并记安全错误码 |
| 单轮请求次数 | 最多6次 | 空页/重复页也计入 |
| 连续无新增页 | 2页后冷却 | 防止 cursor 重复和空转 |
| 高/低水位 | H=5P、L=2P | 默认P20；受池上限500约束 |
| 后台停止时限 | 2秒内取消在途发现工作 | 进入后台后立即禁止发起新请求 |

数值是可测试的初始预算，不是已测性能承诺。调整须在规格变更中记录测量结果。省电/平衡/积极只改变有限预算，不突破后台停止原则。

### 3.3 持久化一致性

`ProviderProgress` 按 profile/provider/queryKey 持久化，cursor 同时包含版本、过期/兼容信息。返回页入池与 cursor 前移一个事务提交，避免进度跳过丢结果。Seen 使用 fingerprint 与独立事件时间戳；清除历史与重置进度是独立动作。

cursor 过期只重置对应来源，不清空 Seen。Provider Package 更新若 cursor 不兼容，重置该来源进度并依靠历史过滤；未知兼容性按不兼容处理。数据库持久化这些功能数据不构成允许记录业务日志的例外。

### 3.4 Provider Package 安全协议

| 层 | 要求 |
|---|---|
| Repository | 签名版本索引、HTTPS、下载大小限制、推荐版本/禁用状态、可缓存策略 |
| Manifest | 固定 ID、版本、Runtime范围、最低App版本、域名/能力、权益、预算、payloadHash、keyId |
| Signature | 使用成熟签名算法/库；签名覆盖规范化 manifest 和全部规则字节，不能只签文件名 |
| Runtime | 仅受限模板、JSON path、HTML selector、regex、字段映射和分页；无通用语言 eval |
| 资源 | 请求/重定向次数、内存、解析时间、正则复杂度、返回数量硬上限 |
| 权限 | 不能访问 Vault、任意文件、全局 Cookie、商店、设备身份或调用任意原生方法 |
| 激活 | staging→校验→有限自测→原子切换版本指针→健康观察→LKG |
| 回滚 | 签名授权策略+未撤销LKG；失败包隔离；没有安全旧包则禁用 |
| 远程策略 | 用户禁用、安全策略、兼容性和 FeatureGate 的交集；不能强制启用用户关闭项 |

Regex 不可仅依赖“协程超时”应对不能中断的计算；选用有复杂度保证的引擎或把规则复杂度限制到可证明边界。解压需限制总量、文件数并防 Zip Slip；包下载不能跨目录覆盖应用文件。

Provider 版本变化不能暗中扩展域名/能力。新权限范围须通过受信策略校验并在 Provider 管理可查看。自动更新设置、检查频率及密钥轮换方案由 M6 完成；仓库不可用不阻断启动。代码能力改变必须发布 App，不使用下载 Dex/APK/脚本方式绕过。

## 4. Browser 与 MediaDetector（TR-05/18）

| 关注点 | 实现要求 |
|---|---|
| WebView | 主线程生命周期正确；销毁时解除回调、释放资源，防止 Activity 泄漏 |
| Renderer crash | 移除失效 WebView、重建受影响标签；共享 renderer 时识别所有受影响标签 |
| 标签 | 限制活跃 WebView 数量；保存导航状态与媒体任务分离 |
| 权限 | 摄像头/麦克风/位置/下载/外部跳转按来源与用户选择管理，不默认全授予 |
| JavaScript bridge | 不向任意页面暴露高权限对象；桥接最小化、校验来源和消息 schema |
| Session | Cookie/Token 不记日志；只把需要的最小上下文交给对应保存任务 |
| 无痕 | 退出销毁 Session 与临时发现记录；不写普通浏览/Seen数据库 |
| Detector | 轻量媒体存在提示；完整扫描仅用户请求；多资源必须让用户确认 |
| SiteAdapter | 可替换、局部失效；不把站点特判堆在 Browser 主体 |

媒体 URL 可能短期失效、依赖认证、分轨、播放 blob/MSE 或受 DRM 保护。检测不到不等于网页没有视频；保存能力必须明确为可用/不可用/未知，不以播放成功推断可导出。读取凭证和带凭证重定向必须限制来源，不能把站点A Cookie发给B。

## 5. Direct、Stream 与 MP4 输出（TR-06/07）

### 5.1 格式边界

| 输入 | M3/M4 目标 | 失败或限制 |
|---|---|---|
| 普通兼容 MP4 | ①直接下载并校验 | 空文件/HTML错误页/损坏轨道不得成功 |
| MOV/WebM 等直接资源 | 检测实际容器/codec，可行时 remux 或兼容转换为 MP4 | 不支持组合显式报告；禁止改后缀假转换 |
| HLS VOD master/media | ②选 variant、取分片/音轨、生成独立 MP4 | 缺分片、轨道不支持、加密/受保护情况分类报告 |
| HLS live/无结束点 | 默认不承诺完整无限保存 | 后续需用户设时长/明确停止，资源有上限 |
| DASH/其他协议 | 作为未来 Stream profile 扩展 | M0 未批准不得宣称支持 |
| DRM/受保护路径 | 拒绝绕过 | 不以③“解锁”受保护内容 |

Media3 官方下载功能主要面向离线播放及缓存，不能直接当作“已经生成单文件 MP4”的证明；M0 必须另行验证封装/导出路径。[官方离线下载文档](https://developer.android.com/media/media3/exoplayer/downloading-media)

Media3 Transformer 提供媒体转换与编辑基础，应优先研究官方能力；具体输入、设备编码器和输出配置仍需样本验证。[官方 Transformer 文档](https://developer.android.com/media/media3/transformer)

### 5.2 Direct 要求

- 使用流式 IO，内存占用不随视频体积线性增长；大文件不使用一次性 byte array。
- 验证响应 MIME 与实际媒体结构；HTTP 成功不等于视频成功。
- 断点续传检查 Range、ETag/Last-Modified 或等价资源身份；服务端返回200时不能当206拼接。
- 限流、超时、登录过期、404、磁盘满分别处理；重试不跨越用户取消。
- 用户选择 variant 后固定该资源身份；后台 Session 无法保持时进入可恢复等待，不强行重复登录。

### 5.3 Stream 要求

1. 解析 Master 与 Media Playlist，明确 variant、音视频轨道、时长与不连续点。
2. 下载分片有界并发，维护序列/资源身份，单分片失败有限重试。
3. 校验 init segment、时间戳、缺片、discontinuity 和轨道配置变化；不简单拼接字节假装 MP4。
4. 能 remux 就不重新编码；必须转码时说明原因、耗时和输出质量。
5. 处理任务支持取消、磁盘预算、临时文件引用与恢复检查。
6. 基础容器兼容转换属于保存输出，不能强迫免费 Direct 用户购买高级压缩。

### 5.4 统一完成条件

```text
下载/捕获结束
      -> 封装/必要兼容处理
      -> 容器可解析
      -> 有有效视频轨、合理时长、预期音轨
      -> 头/中/尾抽样解码与Seek检查
      -> 校验文件/元数据一致
      -> 原子提交到MediaLibrary
      -> SUCCEEDED
```

M0/M3 确定详细验证成本；不要求每次完整解码长片，但必须能识别明显损坏、空壳和缺尾索引。外部导出是独立任务，导出失败不能把已成功内部保存的媒体标为消失。

## 6. Recorder、音频与编辑（TR-08/09）

录制采用官方 MediaProjection 流程；用户每次会话授权，正确声明适用前台服务类型并处理停止回调。新系统对授权令牌与会话的限制不能用旧版代码假设替代。[官方 MediaProjection 文档](https://developer.android.com/media/grow/media-projection)

内部音频捕获取决于系统、播放来源及捕获策略；只能在允许条件下使用，不能无提示切换麦克风。[官方播放音视频捕获文档](https://developer.android.com/media/platform/av-capture)

| 事项 | 必须做到 |
|---|---|
| MVP 捕获范围 | 先整屏；系统提供单应用选择时尊重系统选择并标明实际范围，不承诺任意矩形原生捕获 |
| 授权拒绝 | 不创建无效录制，不重弹循环；返回可操作 UI |
| 系统停止 | 释放 Projection/VirtualDisplay/Surface/Codec/Audio，完成可用片段收尾 |
| 停止幂等 | 重复停止、并发系统回调、Activity销毁不会二次释放崩溃 |
| 编码 | 优先硬件；验证 H.264/AAC 等兼容输出配置，具体 profile 由设备能力决定 |
| 音频 | 显示可用/不可用/被阻止/未知；不可根据静音直接断言DRM |
| 麦克风 | 默认关闭；未来需独立权限和显式选择 |
| 暂停/继续 | M5 片段编排；时间戳连续，暂停时段排除，录制时长为有效总和 |
| 旋转/变尺寸 | M4 可安全停止；M5 分段并统一方向/尺寸，不静默录错区域 |
| 框选 | 归一化矩形+捕获尺寸+Insets+旋转+变换版本，边界与偶数尺寸校验 |
| 拼接 | 相同配置可 remux；不同配置先规范化；保留原片段直到成功 |
| 压缩 | 用户可取消，空间不足提前提示；不能破坏源文件 |
| 后台/切页 | 明确录到当前屏幕的风险；隐藏A捕获只作实验，不用Hook/Root/模拟点击 |

长录制不能被一个过短固定任务 timeout 错误中断。采用会话最大时长/用户停止边界、分片/心跳 stall timeout、资源上限与安全停止；即使允许较长会话，每个准备、收尾、编码操作仍有 deadline。

## 7. 保存与发现生命周期分离（TR-10）

| 事件 | Discovery | Direct/Stream | Recorder/Editor |
|---|---|---|---|
| 进入后台 | 立即禁新请求，2秒内取消在途预取 | 在系统允许范围尽力继续，否则checkpoint等待 | Recorder按系统捕获状态；Editor按系统预算 |
| 切换浏览标签 | 离开发现页即暂停补充 | 保存任务独立于页面视图 | 整屏画面可能变化，需提示 |
| 关闭来源标签 | 停止该浏览上下文发现 | 已具备稳定上下文可继续；需认证则等待 | 不承诺隐藏页面继续捕获 |
| 用户取消任务 | 不影响其他域 | 清理/保留按任务策略，不能自动换方法 | 安全停止；保留已验证输入/片段 |
| 系统杀进程 | 不后台重启搜索 | Recovery Check核验后按允许机制恢复 | 不能自动复用录屏授权；提示恢复有效片段 |

WorkManager、Foreground Service 或其他平台机制由 M0 按当期系统规则选择。不能认为持有前台服务就能无限运行，也不能为 Discovery 滥用长期后台服务。

## 8. MediaLibrary、Vault 与安全（TR-11/12）

### 8.1 文件与目录

普通媒体、缓存、任务临时文件、私密密文、日志分目录；路径通过内部 ID 生成，不直接使用网页标题。自定义文件夹名需拒绝路径穿越、非法字符和冲突；删除文件夹需有清楚的包含文件行为。导出使用系统授权能力，不申请与需求无关的全盘访问。

同一文件系统内可采用原子 rename/replace；跨卷或 SAF 必须按 staging/复制/验证/发布处理，不假设原子移动。数据库事务不能替代文件 durability；operation journal 和 commitId 用于恢复对账。

### 8.2 Vault 威胁模型

| 威胁 | 设计响应 | 明确限制 |
|---|---|---|
| 别人拿到已解锁手机 | App内认证、自动锁、最近任务遮罩 | 用户主动导出后的普通副本不受保护 |
| 读取App存储或备份副本 | 私密媒体及索引加密、备份排除、密钥包装 | 已解锁且运行环境被完全控制无法绝对保证 |
| PIN离线猜测 | 成熟KDF/参数、Keystore绑定、在线失败节制 | 不能靠6位PIN本身提供高熵密钥 |
| 生物识别变化/密钥失效 | 明确失效状态与已设计的密码恢复路径 | 无可用恢复材料不得声称能解密 |
| 崩溃残留明文 | 私有staging、最小寿命、启动清理、引用追踪 | 闪存无法保证逐字节安全擦除；应减少落盘与用密钥销毁 |
| 恶意媒体/错误密文 | AEAD完整性验证、尺寸/资源限制、隔离解析 | 不能将验签失败作为“忽略后继续播放” |

Keystore 可用于保护关键密钥及限制使用条件，不能被当作“视频自动加密”的完整实现。[Android Keystore 官方说明](https://developer.android.com/privacy-and-security/keystore)

### 8.3 安全设计交付要求

- M6 必须提交密钥层级图：用户认证材料、包装密钥、媒体数据密钥、密钥版本与轮换路径。
- 使用成熟 AEAD 与库；大文件随机访问采用经审查的分块加密格式，不自创拼接密码学。
- 不明文保存 PIN/密码、数据密钥、恢复密钥；密码校验与实际解封流程关联，禁止仅用 Boolean 绕过认证。
- 生物识别解锁必须与密钥操作或受控认证会话关联；密码回退需能安全解封，不能生物识别成功就永久缓存密钥。
- 自动锁清除会话句柄和明文缓存；播放器暂停/关闭私密内容，最近任务预览显示安全占位。
- 优先受控流式解密；若组件必须明文临时文件，限定私有目录、短生命周期，排除备份/索引/日志并在启动清理。
- 私密缩略图、名称和敏感索引加密，不只加密 MP4 主体。
- 私密导出/分享仅用户明确请求；临时授权撤销和清理不能声称撤回接收 App 已复制内容。
- 忘记密码、卸载、换设备、生物识别变更、Keystore密钥失效分别说明。当前不提供云同步/云端万能恢复。
- 免费 Vault 不受购买状态、Store不可用、Provider故障影响。

## 9. Entitlements 与 Store（TR-13）

从 M1 建立 `EntitlementManager + FeatureGate + StoreAdapter + ProductMapping`。UI 隐藏按钮不是安全边界，命令入口也要校验。免费 Browser、Direct、MediaLibrary、Vault 即使支付源未知仍可用。

| 情况 | 行为 |
|---|---|
| Fake开发授权 | 可切换各种状态并覆盖测试；不调用真实支付 |
| 购买成功 | StoreAdapter验证结果后转换grant，不由UI自行置true |
| 未知SKU/未知mapping | 拒绝授予并显示可恢复状态，不全部解锁 |
| 重复回调 | 幂等去重，不重复发放/创建任务 |
| 过期/退款/撤销 | 禁新付费任务；不删除用户媒体；进行中任务按会话策略安全完成 |
| 离线 | 免费不受影响；已验证付费缓存遵守有效期/宽限规则，M8冻结 |
| 恢复购买 | 各商店适配器负责恢复；Core只消费权益快照 |
| 跨商店 | 不自动承诺一个商店购买可跨平台迁移；账号/映射另行设计 |
| 发布版 | 无隐藏任意授权开关、无Fake模块链接到生产装配 |

高级录制依赖 `RECORD_SAVE` 加对应工具权益；纯视频编辑只检查对应编辑权益。商品类型、价格、订阅、总包和免费 Provider 数量未冻结。M8 必须完成验证、恢复、撤销、挂起购买及错误处理，不能将真实接支付描述为只翻转布尔值。

## 10. ERROR-only 日志（TR-14）

```text
模块技术错误
    -> SafeError映射（白名单字段）
    -> 脱敏/异常消息剥离
    -> Release ERROR级别过滤
    -> 有界本地环形日志
    -> 自动轮转/到期清理
    -> 用户主动导出（默认不上传）

普通业务/用户动作/搜索/浏览/Provider结果/敏感字段 -> 丢弃，不入日志
```

| 类别 | Release策略 | 示例 |
|---|---|---|
| 必要技术ERROR | 允许最小字段 | module、errorCode、appVersion、API级别、匿名技术配置 |
| 网络错误 | 只记类型/状态码 | TIMEOUT / HTTP_5XX；不记URL/headers/body |
| Provider解析 | 只记受信Provider ID、包版本、安全错误码 | SCHEMA_INVALID；不记结果内容或关键词 |
| WebView/编码异常 | 允许清洗后的类/函数定位 | 不包含网页标题、媒体文件名和完整异常message |
| 普通业务过程 | 禁止 | 点击、翻页、搜索、播放、保存流水 |
| 内容/凭证 | 严禁 | URL、关键词、历史、Cookie、Token、PIN、密码、密钥、私密媒体 |
| 第三方SDK日志 | 默认关闭/拦截清洗 | HTTP interceptor、crash breadcrumb不能绕开统一Logger |
| Debug日志 | 可增加技术状态，仍禁敏感数据 | 状态机跳转用合成任务，不打印真实内容 |

暂定限额：每文件 1 MiB、最多 5 个文件、保留 7 天，先达到者触发清理；限额可以缩小，增大需说明。日志写入失败不能导致主功能失败。用户拒绝导出不影响服务；导出前再次脱敏。异常堆栈可能夹带 URL/路径，因此不能直接 `exception.toString()` 落盘。

Seen History、任务数据库和 OperationJournal 是必要功能存储，受独立最小化与清除策略管理；不允许复制到日志。没有默认用户行为遥测或错误自动上传。未来若需遥测，必须另立产品与隐私决策。

## 11. 故障、任务预算与自恢复（TR-15）

| 操作类型 | deadline/退出方式 | 重试原则 |
|---|---|---|
| Provider/解析 | 短deadline、可取消、受单轮预算 | 最多2次重试；参数错误不重试 |
| Direct/Stream传输 | 连接/无进展timeout + 总任务策略 + 用户取消 | 有界、校验checkpoint；断网等待不忙轮询 |
| Recorder会话 | 用户停止/系统停止/时长与空间上限；阶段超时 | 不自动重新获取授权或重复启动 |
| Encoder/Editor | 根据输入规模估算deadline + 无进展watchdog | 单次受控替代配置；失败保留输入 |
| 数据库迁移 | 事务/备份与明确超时策略 | 不破坏性无限重试；失败隔离 |
| 用户交互等待 | 持久化WAITING_USER，建议24小时到期，取消可见 | 不占网络/编码资源；到期安全取消 |
| Recovery Check | 启动轻检查预算建议2秒，重扫描后台分批 | 每对象有次数上限；反复失败隔离 |

```text
RUNNING
   |
 ERROR -> CLASSIFY
             +--> RETRY（预算剩余） -> RUNNING
             +--> FALLBACK（允许且具备授权）
             +--> WAITING_USER（已释放执行资源）
             +--> SAFE_STOP -> FAILED / CANCELLED

关键写入：prepare -> execute -> verify -> atomic commit/replace -> cleanup
进程终止：重启 -> Recovery Check -> 幂等完成/隔离/清理 -> 可操作UI
```

取消必须贯穿 HTTP、解析、文件IO、编码和回调；不能只把 UI 的loading隐藏。同步不可中断调用需资源隔离，不能依靠协程 cancel 假装已停止。任务终态转换CAS/事务保护，迟到 success 不能覆盖 cancelled。

数据库、缓存、Provider、下载、录制、编码各有隔离边界。原生崩溃无法保证留在协程内，应在 M0 评估平台回调恢复或独立进程；恢复失败提供安全模式。地址栏是产品最低降级目标，但系统 WebView 不可用时必须如实说明，不宣称可以修复操作系统。

## 12. 开源与商店商业架构审计（TR-16）

完整审计矩阵和分类见 03。M0 固定每个仓库 commit 后检查 LICENSE/NOTICE/文件头/传递依赖；M8 再做发行复审。选择性迁移依然保留版权和许可义务，代码改名不会改变来源责任。

| 来源 | 本次资料核对用途 | 不能据此得出的结论 |
|---|---|---|
| [Yue Browser 官方仓库](https://github.com/fazza-abiyyu/Yue-Browser) | 确认候选入口 | 未做文件级/API30/Gradle迁移审计 |
| [Captord 官方仓库](https://github.com/Drenzzz/Captord) | 确认录制候选入口 | 不能保证所有录制/音频设备兼容 |
| [SearXNG LICENSE](https://github.com/searxng/searxng/blob/master/LICENSE) | AGPL家族许可审计入口 | 独立服务并不自动消除服务端义务 |
| [NewPipeExtractor LICENSE](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/LICENSE) | GPL家族许可审计入口 | Adapter封装不自动解除链接/分发义务 |

SearXNG 不复制进 APK，作为可选服务/API 边界研究；NewPipeExtractor 在批准前不直接链接进可能闭源发布的主工程。M0 必须针对实际组合、分发和修改方式判断，必要时取得专业许可意见。许可未知组件禁止复制；可只研究公开行为并独立实现。

远程规则、视频保存/录制和数字商品均可能涉及商店审核。本版仅规定 M0 做商业架构可行性筛查、M8 按当期条款再审；不承诺声明式包或用户主动录制必然过审。[Apple App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)、[Google Play Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646)

## 13. 性能、资源与未来扩展

- UI主线程不执行网络、数据库迁移、大文件复制、媒体解析或编码。
- 录制/编码与Discovery竞争资源时，降低发现并发或暂停发现，优先已授权保存任务。
- 长视频按流和分片处理；ResultPool、缩略图缓存、下载并发、文件临时预算均有上限。
- M7用固定设备/网络/测试媒体测量首屏、翻页、内存、温度与耗电；没有测量结果不宣传节能百分比。
- 当前 Command API 预留 Automation/TaskQueue，不实现无人值守录屏、不自动解锁 Vault、不后台持续 Discovery。
- iOS具体捕获 API、最低版本与后台可行性在 M9验证；使用 PlatformCapabilities 表达差异，不复制历史中未证实的 API 说法。

## 14. 技术完成证据

每个实现项应提交：PR/TR-ID、代码commit、依赖版本、设备/API/WebView版本、样本ID、正常/异常结果、资源释放结果、支持/不支持范围、后续限制。不能用“理论支持”“README写支持”“模拟器跑过”替代需要的真机或许可证证据。
