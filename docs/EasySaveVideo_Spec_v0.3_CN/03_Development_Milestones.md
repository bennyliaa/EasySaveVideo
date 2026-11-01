# EasySaveVideo 开发里程碑与阶段门禁

| 属性 | 内容 |
|---|---|
| 基线 | EasySaveVideo Specification v0.3_CN / 2026-09-25 |
| 状态 | 正式实施顺序；本次仅交付规格，未执行 M0、未创建正式 App 工程 |
| 强制门禁 | M0 通过并完成审阅记录后才允许进入 M1 |
| 旧计划 | 历史“阶段 -1 / 阶段0～8”仅作为需求来源，统一映射到本文 M0～M9 |
| 关联 | [产品需求](01_Product_Requirements.md)、[技术要求](04_Technical_Requirements.md)、[测试策略](05_Test_and_Acceptance_Strategy.md) |

## 1. 路线全图

```text
当前：Specification v0.3_CN 文档基线
                  |
                  v
M0 技术/源码/许可证/API30/Gradle/商店商业架构可行性审计
                  |
             [M0 Gate]
          /       |        \
       PASS     BLOCKED     FAIL
        |          |         |
        v          +-> 补证据/替代方案/重新评审
M1 干净主工程 + Browser + Core Ports + Fake Entitlement
        |
M2 Discovery + ResultPool + Scheduler + Seen/Progress + 分页
        |
M3 MediaDetector + Direct + Stream + SaveTask
        |
M4 基础整屏 Record + ①②③协调 + 基础媒体库 = 内部 MVP
        |
M5 高级 Recorder + 多段拼接 + 框选/二次裁剪/压缩
        |
M6 完整 MediaLibrary + 免费 Vault + Provider Package 完整更新
        |
M7 完整功能整合 + 故障注入 + 性能/隐私/安全稳定化
        |
M8 最后接真实支付 + 当期商店规则复审 + Android 发布准备
        |
M9 Android 稳定后的 iOS 原生实现 + 最后接 StoreKit
        |
后续：Automation / TaskQueue 产品化（另立需求 Gate）
```

M5 与 M6 的内部子任务可按依赖交错，但不能把 Vault 付费化，也不能在 M7 前把缺失完整能力称为发布完成。真实支付必须晚于本轮完整功能稳定；M1 提前建立的权益接口不是提前接支付。

## 2. 阶段总表

| 阶段 | 目标 | 主要依赖 | 必交付物 | 退出条件 |
|---|---|---|---|---|
| M0 | 用证据消除路线风险 | v0.3_CN | 审计矩阵、实验结果、依赖锁定候选、Gate 报告 | 阻塞项清零、替代路线可行、用户审阅通过 |
| M1 | 可维护的干净主工程 | M0 PASS | 可编译 Android 工程、基础 Browser、Ports、Fake Gate、错误层 | API30 启动；无 Provider 构建可用 |
| M2 | 搜索到原网页 | M1 | 真实 Provider、协调器、库存、调度、历史、分页设置 | 前台补充/后台停止、四策略和重启恢复通过 |
| M3 | ①②到独立 MP4 | M1/M2 接口 | Detector、候选选择、Direct/Stream、任务中心 | 受支持资源验证成功；取消/中断终态正确 |
| M4 | 核心闭环 MVP | M3 | 基础整屏 Recorder、SaveCoordinator、简单库与预览 | 搜索→网页→①②③→MP4 分别跑通 |
| M5 | 高级片段创作 | M4 | 选区、暂停/继续、多段、编辑、压缩 | 成片时间/画面/同步达标，原件不损坏 |
| M6 | 完整管理、安全和维护 | M4/M5 | 免费 Vault、文件夹/导出、完整 Package 管理、设置 | 安全审查、更新中断回滚、免费权益测试通过 |
| M7 | 完整内部测试版本 | M2～M6 | 故障矩阵、兼容报告、性能报告、修复与回归 | 关键验收全通过、无开放阻塞缺陷 |
| M8 | 商业发行准备 | M7 | 真实 StoreAdapter、商品映射、购买恢复/撤销测试、发布材料 | 商店/许可证/安全复审通过；发布单独记录 |
| M9 | iOS 同规范实现 | Android 稳定 | iOS 可行性表、原生工程、契约测试、StoreKit | 能力差异透明；共通行为一致，iOS 单独验收 |

不提供未经 M0 支持的开发时长承诺。历史“约 65%～75% 可复用”只是早期估算，不作为工期或验收依据。

## 3. M0：正式开发前强制审计

### 3.1 M0 允许和禁止的工作

| 允许 | 不属于 M0 |
|---|---|
| 固定候选源码 commit，阅读许可证和传递依赖 | 创建正式产品后大规模迁移再补审计 |
| 独立、可丢弃的 Media Lab / 构建验证工程 | 完整首页、正式 Vault、真实支付、生产后端 |
| API30 和目标系统的官方能力实验 | 为绕过系统限制使用 Root/Hook/自动点授权 |
| 小范围功能原型、依赖冲突和媒体样本测试 | 将 GPL/AGPL 类型渗透进 Core |
| 编写审计、风险、备选方案、Specification 变更建议 | 未经 Gate 直接进入 M1 |

M0 原型仅证明能力，不能因“已经能运行”自动获得代码迁移许可。M1 重新创建干净主工程；可迁移实验代码仍需追踪来源与许可证。

### 3.2 源码审计操作单

1. 确认候选仓库身份与 URL，固定 tag/commit/hash；记录检查日期，不能只读 README。
2. 保存 LICENSE、NOTICE、源码文件头、依赖清单和构建配置证据。
3. 核查直接与传递依赖、二进制组件、原生库、资产/字体/图标等授权。
4. 定位实际可用文件或模块；检查与包名、资源名、Manifest、Service、权限、数据库的耦合。
5. 构建固定源码快照；记录 AGP/Gradle/Kotlin/JDK/SDK/ABI、依赖仓库和网络条件。
6. 在 API30 与主要环境 Pixel 7/API35 验证；媒体捕获、编码、音频必须有真机证据，未测标注未测。
7. 提出具体迁移边界、替代实现、删除该组件的验证方法。
8. 给出逐组件处置结论，不以整个项目“开源”代替逐文件许可确认。

### 3.3 开源审计矩阵模板与候选初始状态

下表是待审计计划，不是现成审计报告。任何 `CAN REUSE` 最终结论都必须有固定版本和证据。

| 候选 | 预期用途 | 初始处理方向 | M0 必核事项 | 失败替代 |
|---|---|---|---|---|
| Yue Browser | WebView、标签、导航、Session、恢复 | 倾向 REFACTOR，未批准 | 仓库身份、实际许可、依赖、API30、模块耦合、隐私行为 | Android 官方 WebView + 自有小模块 |
| Captord | MediaProjection、音频、Service、录制 | 倾向 REFACTOR，未批准 | 实际许可、录制核心、编码路径、生命周期、API30/新系统 | 官方录制 API + 审计通过实现 |
| AndroidX Media3 | 播放、转换、编辑、媒体基础 | 倾向 CAN REUSE，未批准版本 | 当前稳定版本、依赖冲突、输出 MP4 路径、API30 | 官方底层 API/兼容版本 |
| SearXNG | 搜索广度 | ISOLATE 研究 | AGPL、服务部署及修改义务、请求/响应契约、JSON 可用性、访问限制 | 本地 Provider/其他合规服务 |
| NewPipeExtractor | 视频站深度、Extractor | ISOLATE 或 REFERENCE ONLY 待判 | GPL、链接/发布方式、类型隔离、商业架构相容性 | 独立重实现/其他 Provider/不集成 |
| Media Downloader | 媒体检测、HLS、任务管理参考 | 许可未清前禁止复制 | 是否存在有效 LICENSE、代码来源、实际可编译性 | 自有 Direct/Stream 接口+官方库 |
| REC | 捕获/编码路线参考 | REFERENCE ONLY 待审 | GPL 边界、不得以改名绕过义务 | Captord 候选/官方 API |
| SecureVault / LibreVault | Vault 威胁模型参考 | REFERENCE ONLY 待审 | 许可、密码学正确性、密钥/缓存/备份风险 | 官方安全能力+自有经过审查设计 |
| NewPipeWeb / Videouri | 聚合/分页架构参考 | REFERENCE ONLY 待审 | 维护与许可、架构适用性 | 自有 Discovery Core |
| SepiaSearch / PeerTube API | 可选来源 | 未决 | API 条款、限流、服务稳定性和元数据 | 零依赖替换其他 Provider |

| 最终分类 | 准确定义 | 所需证据 |
|---|---|---|
| `CAN REUSE` | 可按记录版本直接依赖或迁移批准文件 | 许可、构建、测试、来源清单通过 |
| `REFACTOR` | 许可可接受，但需调整边界和实现 | 文件级迁移计划、依赖剥离、回归范围 |
| `ISOLATE` | 必须独立组件/服务并明确义务 | 隔离设计、分发/网络义务分析；不是自动法律免责 |
| `REFERENCE ONLY` | 只研究概念与行为，不复制受限代码 | 参考记录、自有实现计划、无源码混入证据 |
| `REJECT` | 本项目不采用 | 明确原因和可执行替代路线 |

正式报告每行另需：`repository、commit、licenseEvidence、files、dependencies、API30Result、buildResult、securityFindings、commercialFit、decision、owner、reviewDate`。许可未知不得写 CAN REUSE；包装为 Adapter 也不自动消除 GPL/AGPL 义务。

### 3.4 M0 技术实验矩阵

| 编号 | 实验 | 最少证据 | Gate 含义 |
|---|---|---|---|
| M0-E01 | API30 干净实验工程构建/安装 | 工具链版本、构建结果、设备结果 | 必须通过 |
| M0-E02 | WebView 播放、Session、标签切换、renderer 恢复 | 本地页面+许可测试站点、异常结果 | 基础浏览器必须可行 |
| M0-E03 | 普通 MP4 检测与 Direct 保存 | 原样本、输出哈希/轨道/播放结果 | ①必须可行 |
| M0-E04 | HLS master/media、variant、分离音轨到 MP4 | 支持组合表；不能只证明离线缓存 | ②基础受支持范围必须可行 |
| M0-E05 | 整屏捕获、授权、停止、MP4 | API30/主要真机的画面和容器结果 | ③基础必须可行 |
| M0-E06 | 内部音频允许/拒绝 | 两类样本与设备配置 | 允许样本可行；拒绝如实提示 |
| M0-E07 | 暂停/片段、拼接、裁切 | 小规模 proof、配置与同步结果 | 高级路线有依据；可记录后续限制 |
| M0-E08 | A 保存/录制时切 B、退后台 | ①②③分别记录结果 | 隐藏 A 捕获失败不阻断 MVP |
| M0-E09 | 一个真实 Provider、SearXNG/Extractor 边界 | 有限候选转换结果；许可边界 | 发现链至少一条可行且可替换 |
| M0-E10 | 签名声明式 Runtime 安全边界 | 包格式草案、拒绝代码执行、预算方案 | 路线可行，完整管理留 M6 |
| M0-E11 | Entitlement/Store 结构 | Fake 组合场景、无商店类型污染 | 商业架构可后接 |
| M0-E12 | 商店商业架构可行性 | 当期官方政策核对、渠道限制与变体方案 | 不保证上架，但不得忽略已知结构性冲突 |
| M0-E13 | 故障与原子操作 proof | 断网、磁盘满、进程杀死后的结果 | 核心恢复方案具有验证依据 |

M0 可进行临时原型代码与实验，但不开发正式 App 功能。没有真机或证据不足时写 `NOT_RUN/BLOCKED`，不得用模拟器成功替代真实音频/编码结论。

### 3.5 M0 Gate 判定

```text
许可证可接受？ -- 否 --> 替代/隔离/拒绝 -> 重新审计
      |
核心①②③+Browser+至少一个Provider可行？ -- 否 --> 修正路线
      |
API30 / Gradle / 商业架构成立？ -- 否 --> 解决阻塞
      |
非阻塞限制有明确降级和后续任务？ -- 否 --> 补报告
      |
完整报告 + 审阅接受记录
      |
     PASS -> M1
```

Gate 必须逐项有结论；不能以“部分功能能跑”笼统通过。允许非阻塞限制：特定站点不支持、隐藏 A 独立录制不可行、后台保存受限制、某 Provider 被 REJECT 但替代可用。禁止带入的阻塞项：基本媒体输出不可行、来源许可证不明却计划复制、API30 无法运行、远程任意代码、免费核心依赖商业服务、关键媒体丢失风险未处理。

M0 报告包括建议进入 M1 的具体范围，由用户审阅接受；当前“生成规格文档”不等于 M0 通过或授权开发。

## 4. M1：干净主工程与基础能力

**输入**：M0 PASS、批准的依赖与迁移文件、工具链版本。

| 子项 | 实施要求 | 验收 |
|---|---|---|
| 工程 | Android Studio、Kotlin、Compose、`com.easysavevideo.app`、minSdk30 | Debug Build、安装启动 |
| Git | 初始可编译提交，记录来源和分支 | 不覆盖用户已有工作、不重写历史 |
| Core | 自有模型/Ports/ErrorEnvelope/OperationContext | 不引用具体 Provider/商店 SDK |
| Browser | 地址栏、导航、多标签、播放、错误恢复 | 无 Provider 构建仍可浏览 |
| Entitlement | EntitlementManager、FeatureGate、StoreAdapter、ProductMapping、Fake | 免费/部分/全部权益可测试 |
| 可靠性 | 取消、deadline、任务状态、恢复日志、ERROR Logger | 失败返回稳定 UI |
| 自动化预留 | Command API、idempotencyKey、TaskSnapshot | 业务不依赖 Activity |

**禁止**：Fork Yue 后直接改名作为主工程；未审计源码混入；为了方便将 Android 类型写入 Specification。

## 5. M2：发现与分页

- 完成 ProviderRegistry、至少一个真实 Provider、Fake Provider 测试集与故障模拟器。
- 实现 Coordinator、Normalize/Filter/Deduplicate、ResultPool、Scheduler 和稳定分页。
- 实现前台/后台隔离、资源预算、来源独立超时/熔断；Discovery 退出后停止新请求与预加载。
- 实现 Provider Progress、Seen History 本地持久化、四策略、关键词/全部历史清理；无痕不持久化。
- 完成网格与分页设置；来源无结果或全部失败时仍有地址栏。
- 建立 Package/Runtime/Repository 接口和本地签名包契约测试；完整发布更新 UI 留 M6。

**退出证据**：PR-04～PR-08、PR-20 对应基础用例；500 条候选边界测试、迟到响应测试、重启/cursor 过期测试、0 Provider 测试。

## 6. M3：媒体检测与①②

| 子链 | 要求 |
|---|---|
| MediaDetector | 视频存在轻检测，点击保存才完整分析；多候选和多清晰度弹窗 |
| Direct | 流式 IO、正确 Session、合法续传验证、免费 MP4 输出 |
| Stream | HLS 基础支持、变体/音轨/分片、remux 优先、真实 MP4 输出 |
| SaveTask | 方法、进度、取消、错误类别、恢复状态；后台可行性明示 |
| 文件提交 | staging→verify→commit→cleanup，任务与媒体索引对账 |

**退出标准**：受支持 Direct/HLS 样本均输出独立可播放 MP4；下载完成与导出成功分开；取消不触发③；存储错误不盲目降级；磁盘/网络/进程中断均可恢复或稳定失败。

## 7. M4：基础③与内部 MVP

M4 把系统录屏授权、整屏开始/停止、内部音频能力说明、基础预览和媒体列表接到 SaveCoordinator。

```text
搜索路径 -> Provider -> 卡片 -> 原网页 -> 播放 -> ① -> MP4 -> 库
地址路径 -> 原网页 -> 播放 -> ② -> MP4 -> 库
用户确认 -> 系统授权 -> ③整屏 -> MP4 -> 库
所有 Provider 关闭 -> 地址路径仍然成立
```

基础媒体库至少能查看、播放、删除已完成文件；完整文件夹、Vault 和导出增强在 M6。高级框选、暂停/继续片段编排在 M5。M4 可以提前实验底层暂停，但不得把尚未完成的高级编辑宣称为 MVP 已交付。

**退出标准**：三方法均有成功与失败证据；UI 明示①②③；录屏拒绝/撤销后资源释放；没有音频时提示；不绕过 DRM；至少 API30 与 Pixel 7/API35 基线通过，真机录制验证完成。

## 8. M5：高级录制与编辑

| 功能 | 必须行为 | 退出证据 |
|---|---|---|
| 框选 | 可拖动/缩放/全屏/取消，明确坐标转换 | 状态栏/旋转/不同尺寸用例 |
| 暂停/继续 | 暂停时段不进入成片；显示有效时长 | 20秒+暂停120秒+30秒≈50秒 |
| 多段拼接 | 顺序一致，时间戳正确，单文件 | 音画同步、黑帧/接缝检测 |
| 二次裁剪 | 停止后仍可裁切上下左右 | 原件与裁后画面对照 |
| 压缩 | 原质量/节省空间，估算不伪造 | 输出质量/体积/取消/温控测试 |
| 高级权益 | 独立编辑 Gate；高级录制组合 Gate | 免费/Save Pro/Tools/总包矩阵 |

独立 A 捕获实验必须限时并有结论；失败时保留整屏前台方案，不以危险机制追求“全能后台录制”。

## 9. M6：完整媒体、安全与 Provider 维护

| 工作流 | 子项 | 退出条件 |
|---|---|---|
| MediaLibrary | 文件夹、重命名/移动、信息、相册/Files/分享、目标权限失效处理 | 导出失败不损坏内部文件 |
| Vault | PIN/密码、生物识别、Keystore、真正加密、自动锁、最近任务保护、临时明文 | 安全方案审查与负向测试通过 |
| Provider Package | 签名索引、下载、校验、原子激活、LKG、回滚、版本/启停 UI | 任意阶段中断不破坏旧包 |
| 完整浏览器设置 | 历史、收藏、无痕、权限、Cookie、缓存清理 | 无痕/私密信息不串入普通历史 |
| 恢复 | 数据库迁移、文件对账、私密明文清理、半完成导出 | 启动不被长扫描阻塞 |

Vault 完成后不因缺付费权益而限制任何隐私基础功能。密码遗失/密钥失效说明必须在设计和用户界面中一致，不默认上传钥匙或媒体。

## 10. M7～M9

### M7 完整内部版稳定化

执行 05 全部关键用例与故障注入；测试不同 Android 版本、WebView 版本、真机厂商、网络、存储与屏幕尺寸。审计 Release 日志、构建依赖、权限、无痕/Vault 缓存。产出支持范围表、已知限制、恢复说明和剩余问题清单。

缺陷分级：P0 数据泄漏/丢失/系统性不可用；P1 核心闭环失败；P2 有替代路线的局部问题；P3 体验小问题。M7 不得留下开放 P0/P1；P2 需有可见限制和后续安排。

### M8 最后接支付与 Android 发布准备

1. 冻结商品-权益映射、SKU、一次性/订阅选择、免费 Provider 数量及总包策略；这些不在当前凭空定价。
2. 实现 GooglePlayStoreAdapter 或经批准的渠道 Adapter，验证购买、挂起、恢复、重复回调、撤销、到期、离线与网络错误。
3. 删除生产 Fake 解锁入口，核验 FeatureGate 和实际命令层检查一致。
4. 按发布时有效官方政策重新审查媒体保存、录制、远程规则和数字商品销售，不把声明式 Package 等同于必然过审。
5. 更新第三方声明、隐私说明、数据安全信息、版本支持表、渠道 FeaturePolicy 和发布签名流程。
6. 形成可审阅 Release Candidate；真正上架/发布是单独记录的发行操作。

### M9 iOS 原生实现

Android 稳定后，以最新 Specification 为输入重新做 iOS 能力 Gate。使用 Swift/SwiftUI/WKWebView 等原生方案，具体捕获 API、系统下限、后台机制和商店政策按当期官方资料重新验证；不沿用历史对某 iOS 捕获 API 的推测。共享 Provider 格式、领域语义、错误码与测试向量，平台限制通过 PlatformCapabilities 表达。iOS 完整功能稳定后最后接 StoreKit。

## 11. 每阶段工程纪律与交付模板

每次修改先检查工作区、当前分支和最近稳定提交；不能覆盖未知修改。每个独立可运行变更分批提交；高风险重构前有稳定提交；不得为 Build 通过删除重要能力。迁移代码必须保留版权/NOTICE 与文件级来源记录。

```text
阶段报告
1. Specification version / 阶段 / commit
2. 已完成 PR-ID 与实现范围
3. 架构及主要文件变更
4. Build / Lint / 自动测试结果及证据位置
5. 设备、API、WebView、样本与人工测试结果
6. 未测试项（不能写成通过）
7. 已知限制、系统限制、许可证结论
8. 故障注入与数据恢复结果
9. Gate：PASS / BLOCKED / FAIL 及原因
10. 下一阶段输入与未决决策
```

阶段验收应使用本版统一 PR/测试/决策编号，不把聊天里已废弃的 minSdk31/35、服务器强依赖或暂不持久化方案重新带入。
