# EasySaveVideo 决策记录

| 属性 | 内容 |
|---|---|
| 基线 | EasySaveVideo Specification v0.3_CN |
| 整理日期 | 2026-09-25 |
| 状态 | 当前有效决策登记；技术实现/许可证/商店可行性未因此视为通过 |
| 来源 | 《EasySaveVideo需求分析》可读取完整历史、本次用户明确重建要求 |
| 历史会话ID | `6aac2829-b2d4-83ee-a1c9-6e3473b57bdf` |
| 旧版处理 | `EasySaveVideo_Spec_v0.1_CN`、`EasySaveVideo_Spec_v0.2_CN` 废弃；不简单增补 |

## 1. 状态、证据与变更规则

| 状态 | 含义 |
|---|---|
| CONFIRMED | 用户已明确确认的产品/架构原则；必须执行 |
| CONFIRMED_DEFERRED | 已确认但放后续阶段，不能从范围中删除 |
| ENGINEERING_BASELINE | 为使本版可实施而明确的工程定义/建议初值；并非历史逐字决策 |
| PENDING_M0 | 技术、许可、兼容或商业架构事实等待 M0 证据 |
| PENDING_RELEASE | 商品参数/发行政策等待 M8 或对应平台发布阶段确认 |
| SUPERSEDED | 已被后续明确决策替代，不得作为当前实现依据 |
| EXPLORATORY | 保留有界实验，不构成产品成功承诺 |

本次已逐页读取历史至最早页；与项目无关的电脑选购话题不纳入规格。历史助手提出但没有固定证据的“最新维护情况、API能力、复用比例”不升级为审计事实。旧文档附件内容不作为新文档骨架，也未对旧文件执行删除。

决策优先级：本次明确要求 > 历史最新明确确认 > 较早未冲突需求 > 本版工程定义。冲突必须写出替代关系；不能将较早一句“先不做”用来覆盖随后“改为保存”的决定。

所有下列日期均为本次整理日期，不虚构原始逐项确认日期。每次后续变更需记录：决策ID、原值、新值、原因、影响的PR/TR/测试、迁移措施、接受记录及生效版本。

## 2. 历史证据锚点

锚点引用具体历史turn ID，便于后续回查；不要求阅读历史才能使用本目录。

| 锚点 | 历史turn ID / 当前请求 | 已确认内容 |
|---|---|---|
| H01 | `f48ab84b-005b-4c26-b4ed-7e9c354eed57` + 当前请求 | 废弃两旧版，从完整历史重建六篇详尽中文规格 |
| H02 | `484d5b7a-36c1-4447-87d5-c03cd87271ff` | 最低Android11/API30 |
| H03 | `8303be13-ee92-4482-a7dd-0be749c5af5b` | 干净工程，选择性迁移Yue/Captord，Android后iOS |
| H04 | `f656ac69-558e-43c4-89b4-00b6846aee89` | Android/iOS实现同一个Specification |
| H05 | `ccfdca5e-8a59-4ebb-9da8-368eacee17cc` | Provider删除仍Build/启动/浏览/保存 |
| H06 | `3d71c2eb-61b0-4da3-962d-8595d1eb4364` | 自有核心是Provider框架、聚合过滤、池、调度、分页 |
| H07 | `922e3954-9a11-41c3-a124-4439af6a72f4` | 前台供应，后台停止寻找新视频 |
| H08 | `546a1003-9cd5-4fc9-840e-a6965080269b` | 最终改为Provider进度和Seen本地长期保存 |
| H09 | `08f17add-18b2-4fbf-9622-76c03d347c85` + 当前请求 | 独立更新Provider包、签名声明式Runtime/Repository |
| H10 | `74b11d07-7775-4d70-9712-8ddc0d37d224` | SearXNG广度、NewPipeExtractor深度与许可审计 |
| H11 | `85598a3e-9ac6-4f51-88f4-01333c88892c` | 点击保存、数字方法提示、选择弹窗、片段录制 |
| H12 | `f96abdb6-9789-441b-bb22-c78f8cfb3d2f` | 整屏MVP优先，框选等后续保留 |
| H13 | `158aafbe-b77b-43b6-b719-9832a729cbb9` | 保险箱作为基础功能免费开放 |
| H14 | `d3a133b5-b739-4bca-9f8d-0e59f66c3e92` | Core只问平台无关权益，不关心购买商店 |
| H15 | `874552e8-38ec-4bcf-a1bf-833c77e4c28e` | 真支付最后实现，先预留适配与Fake |
| H16 | `31fc2061-23a0-4807-927a-7745f4bd99c7` | 仅必要ERROR，普通业务/行为不记录 |
| H17 | `170d7137-671d-4630-8df7-737309ce5fe6` | Fault Isolation、Self Recovery、Graceful Degradation |
| H18 | `875540ca-0b16-4d50-a17c-9c849bad09ca` | M0先审计，完成后再M1正式开发 |
| H19 | `cafd1ebc-d109-45da-a685-92fdb05d970e` | App文件夹、密码私密媒体与导出 |
| H20 | `571fa159-362b-4257-acd8-fff57ec77348` | 视频网站式分页网格，对接原站内容 |

## 3. 项目、规范与开发路线决策

### D-01 新正式基线，废弃旧中文版

| 项目 | 记录 |
|---|---|
| 决策 | 以六份重新编写的 v0.3_CN 作为当前正式规格；v0.1_CN/v0.2_CN作废 |
| 理由 | 旧版未充分体现历史讨论、框架与图表，简单增补难以消除矛盾 |
| 影响 | 所有开发引用本目录；旧内容只作溯源，不合并执行 |
| 状态/依据 | CONFIRMED；H01 |
| 对应 | 全文档；PR-01～25 |

### D-02 Android 先行与固定工程参数

| 项目 | 记录 |
|---|---|
| 决策 | Android Studio、Kotlin/Compose/WebView；`com.easysavevideo.app`；minSdk30 |
| 理由 | 先验证一条原生平台链路，减少同时维护两套未经验证实现的成本 |
| 影响 | Pixel7/API35为主要环境而非最低版本；API30必须兼容测试；SDK工具链由M0确定 |
| 状态/依据 | CONFIRMED；H02/H03及当前请求 |
| 替代 | minSdk35、minSdk31、最低版本待定均SUPERSEDED |
| 对应 | PR-01；03 M0/M1；T-M0-02 |

### D-03 Specification 优先于平台代码

| 项目 | 记录 |
|---|---|
| 决策 | 版本化EasySaveVideo Specification；Android、iOS分别原生实现相同行为和协议 |
| 理由 | 浏览器、媒体、捕获高度依赖系统；统一产品标准比翻译代码更可靠 |
| 影响 | Core自有模型；Provider/Platform/Store三类适配；iOS能力独立验证 |
| 状态/依据 | CONFIRMED；H04 |
| 非目标 | 不强制共享UI、Kotlin二进制或假定iOS拥有相同捕获能力 |
| 对应 | PR-02；02 §1～3；M9 |

### D-04 新建干净主工程，选择性迁移

| 项目 | 记录 |
|---|---|
| 决策 | 不直接Fork Yue改名；审计后选择迁移/重构Yue与Captord；媒体优先官方Media3 |
| 理由 | 明确代码归属、包名、模块边界和许可追踪，利于长期维护与iOS对应 |
| 影响 | 文件级来源记录/NOTICE；第三方类型不进入Core；迁移需回归测试 |
| 状态/依据 | CONFIRMED原则；具体文件与版本PENDING_M0；H03 |
| 对应 | PR-01/23；03审计矩阵；TR-16 |

### D-05 M0 强制 Gate

| 项目 | 记录 |
|---|---|
| 决策 | 正式开发前做技术、源码、许可证、API30、Gradle、商店商业架构审计；M0通过才M1 |
| 理由 | 避免产品开发完才发现不能集成、不能兼容或许可架构需推倒 |
| 影响 | 输出CAN REUSE/REFACTOR/ISOLATE/REFERENCE ONLY/REJECT矩阵、实验与Gate报告 |
| 状态/依据 | CONFIRMED；H18 |
| 边界 | M0可做隔离实验，不建正式功能；本次文档任务未执行M0，也不构成M0通过 |
| 对应 | PR-23；03 §3；T-M0-01～12 |

### D-06 分阶段实现而不删除完整需求

| 项目 | 记录 |
|---|---|
| 决策 | M4内部MVP闭环；M5高级录制；M6完整媒体/Vault/Provider维护；M7稳定；M8真实支付；M9 iOS |
| 理由 | 先证明核心可用，再完善体验；保留最初高级需求 |
| 影响 | “MVP完成”不等于完整版完成；不以延期为删除功能理由 |
| 状态/依据 | 阶段化原则CONFIRMED；本版M0～M9编号ENGINEERING_BASELINE；H12/H18 |
| 对应 | PR-13～19；03全篇 |

## 4. 发现与 Provider 决策

| 决策ID | 决定了什么 | 理由 | 影响/边界 | 状态与依据 |
|---|---|---|---|---|
| D-07 | 核心闭环包含关键词发现→原网页→播放→①②③→MP4→媒体库 | 用户不必先知道网址，完成端到端价值 | Discovery进入MVP；不托管第三方视频本体 | CONFIRMED；H06/H20；PR-04/12/17 |
| D-08 | 地址栏永久免费兜底，Provider零硬依赖 | 搜索来源变动不能导致整个App失效 | 任意/全部Provider删除后仍Build、启动、浏览及通用保存 | CONFIRMED；H05；PR-03/07 |
| D-09 | 自研重点是Provider Framework、Coordinator、ResultPool、Filter/Deduplicate、Scheduler、Pagination UI | 复用上游搜索成果，专注结果质量与消费体验 | 不自建全网搜索引擎，不无限爬取 | CONFIRMED；H06；PR-04/05 |
| D-10 | Local-first，手机聚合有限候选；服务器可选 | 用户消费有限，减少基础设施硬依赖和耗电 | 不是离线全网搜索；网络来源仍看到请求 | CONFIRMED；历史Local-first讨论与当前请求；PR-05 |
| D-11 | 前台低功耗持续补充，后台停止Discovery | 用户不再浏览时停止寻找，节省资源 | 停止Provider/预取/缩略图新工作；保存任务独立 | CONFIRMED；H07；PR-05/16 |
| D-12 | 库存高低水位与有限并发驱动调度 | 维持下一页而非追求最大搜索速度 | H/L随P计算；用预算和无新增熔断防空转 | 原则CONFIRMED；数值ENGINEERING_BASELINE；PR-05 |
| D-13 | Provider Progress与Seen History长期本地持久化 | 一周后继续浏览、减少重复；cursor可能过期 | cursor是效率优化，Seen才是长期防重复依据 | CONFIRMED；H08；PR-06 |
| D-14 | 四策略：PREFER_NEW默认、HIDE_WATCHED、HIDE_SHOWN、SHOW_ALL | 尊重用户对新旧内容的不同需要 | SHOW_ALL仍本会话去重；可按词/全部清除历史 | CONFIRMED；H08；PR-06/20 |
| D-15 | 视频网站式网格分页；每行/每页可设置 | 提高浏览效率，适配屏幕和偏好 | 点击原网页；未知总量不虚构尾页；2/20为建议默认 | CONFIRMED；H20；PR-04/20 |
| D-16 | 多Provider独立超时/熔断、远程开关/优先级/超时/版本 | 网站改版时可局部停用，不拖累全部 | 本地禁用、安全上限和权益优先；无默认结果遥测 | CONFIRMED；H05/H09；PR-07 |
| D-17 | 签名声明式Provider Package+Runtime+Repository | 更新规则不必频繁升级App，并可共享两平台规则格式 | v1禁任意Kotlin/Dex/脚本；代码能力变动发App | CONFIRMED；H09；PR-08 |
| D-18 | 更新先验证再原子激活，保留last-known-good回滚 | 防半包、新版错误导致搜索全坏 | 不能回滚到已撤销/不安全版本；仓库故障保留安全旧包 | CONFIRMED原则；安全细则ENGINEERING_BASELINE；PR-08/22 |
| D-19 | SearXNG广度、NewPipeExtractor深度作为研究/整合方向 | 少造轮子，利用成熟来源与Extractor经验 | 实际集成方式等待M0，不要求两者都成为正式依赖 | CONFIRMED方向/PENDING_M0采用；H10；PR-04/23 |
| D-20 | GPL/AGPL必须先审计隔离，Core不依赖其类型 | 降低商业发布阶段架构返工风险 | Adapter只是技术边界，不自动免除许可义务 | CONFIRMED原则/PENDING_M0许可结论；H10；PR-23 |

### D-13/D-14 的历史冲突处理

历史曾同意“第一版不保存进度/Seen，先只做本次去重”，随后用户明确要求改回本地持久化并保留四种选择。当前采用后者，M2即落实。不能从早期MVP减项重新推导出“不做历史”。

Seen是必要本地功能数据，不是允许写日志的业务轨迹。为避免模糊状态，本版将`SHOWN/OPENED/PLAYED/SAVED`定义为独立事实字段；`SAVED`不自动算看过，`DISCOVERED`不自动算展示。按SearchProfile隔离是本版工程基线；全局跨关键词历史过滤暂不增加。

### D-17/D-18 的技术边界

声明式不代表无限自由。网络域名、重定向、解析复杂度、包大小、请求数量和Runtime版本都必须受限。普通升级版本单调，受信回滚策略单独授权；损坏包永不覆盖当前版本。运行健康检测只使用本地必要错误计数，不采集用户搜索内容。

## 5. 保存、录制与媒体决策

| 决策ID | 决定了什么 | 理由 | 影响/边界 | 状态与依据 |
|---|---|---|---|---|
| D-21 | 用户点击保存才完整分析，存在视频才显示明显保存/录制入口 | 干净浏览体验、减少耗电 | 轻量存在检测可先做；不持续深扫所有页面 | CONFIRMED；H11；PR-09 |
| D-22 | 多视频/多清晰度弹窗选择 | 用户决定保存对象和质量 | 不默认误选广告；未知大小/时长不伪造 | CONFIRMED；H11；PR-09 |
| D-23 | 保存顺序①Direct免费→②Stream→③Record，UI显示数字和名称 | 优先高效保存，最后才实时捕获 | 仅适合的失败允许降级；取消/满盘/DRM不能盲目换方法 | CONFIRMED；H11；PR-10～13 |
| D-24 | 成功产物目标为独立可播放MP4，remux优先 | 用户可管理/导出，避免无谓编码损耗 | 离线缓存不等于MP4；WebM/MOV不能仅改后缀 | CONFIRMED目标；细化ENGINEERING_BASELINE；PR-10/11 |
| D-25 | ③MVP先整屏，高级框选/片段/编辑后续 | 降低首轮系统捕获与坐标复杂度 | M4开始/停止；高级暂停/继续、片段编排M5，需求不删 | CONFIRMED；当前请求优先于早期MVP表；H12；PR-13/14 |
| D-26 | Recorder独立，Save③复用；支持边看边录喜欢片段 | 保存全片与采集片段是不同用户意图 | 开始/暂停/继续/停止，暂停不进成片，多段自动合一 | CONFIRMED_DEFERRED M5；H11；PR-14 |
| D-27 | 框选后仍可二次画面裁剪，预览/压缩可选 | 去掉状态栏、边缘等无用画面 | 非破坏编辑；原质量/节省空间；不强制重复转码 | CONFIRMED_DEFERRED M5；H11/H12；PR-15 |
| D-28 | 官方录屏授权，内部音频仅系统允许，DRM不绕过 | 遵守系统能力，明确实际支持范围 | 禁模拟授权、Root/Hook；不可录音不能偷换麦克风 | CONFIRMED；当前请求及历史；PR-13 |
| D-29 | ①②后台尽力继续，非MVP硬成功条件；Discovery后台必须停 | 已发起保存代表不同用户意图 | 保存独立任务；受Session/系统限制时checkpoint和提示 | CONFIRMED；H07/H11；PR-16 |
| D-30 | 隐藏A、浏览B仍录A保留为有界实验 | 用户希望不中断使用，技术能力未知 | 失败不阻断MVP；不得假设整屏录制能捕获隐藏A | EXPLORATORY；历史后台录制讨论；PR-13/16 |
| D-31 | App MediaLibrary、自定义文件夹、相册/Files/分享 | 用户先管理自己的文件再决定导出 | 默认App媒体库；导出单独事务，不自动上传 | CONFIRMED；H19；PR-17 |
| D-32 | Vault是免费基础能力，真加密而不只是密码页面 | 隐私保护不应成为额外收费门槛 | PIN/密码、生物识别、Keystore、自动锁、最近任务、明文清理全免费 | CONFIRMED_DEFERRED M6；H13/H19；PR-18 |

### D-23/D-24 的降级解释

①→②→③是能力选择顺序，不是任何错误都自动换下一种。无Direct资源可以评估Stream；可捕获且用户授权才进入Record。网络全断、磁盘满、取消和受保护内容有独立处理。所有成功必须经过验证和提交，不能把“开始下载”“分片进缓存”“停止录制”直接显示成保存成功。

为了保住免费①与MP4目标，基础容器兼容转换归SaveEngine，不因为调用了同一Encoder就要求购买Video Tools Pro。用户主动裁剪/压缩等高级操作才受对应工具权益控制。

### D-25 的排期解释

历史早期MVP表曾将暂停/继续列入首轮；本次用户明确写出“MVP③先整屏，后续框选、开始暂停继续停止、边看边录、多段拼接、二次裁剪、压缩”。本版将基础开始/停止留M4，高级暂停/继续与片段编排放M5。底层能力可提前实验，但不提前改变商品分层。

## 6. 商业架构决策

### D-33 权益层从第一天存在，真实支付最后接

| 项目 | 记录 |
|---|---|
| 决策 | M1建立EntitlementManager、FeatureGate、StoreAdapter、ProductMapping；开发使用Fake；完整功能稳定后才接真实支付 |
| 理由 | 不让支付阻碍研发，也避免后补收费导致业务重构 |
| 影响 | UI和命令层统一Gate；测试覆盖各种权益；Release不带任意解锁开关 |
| 状态/依据 | CONFIRMED；H15；PR-19 |
| 澄清 | 支付最后接不代表只改Boolean；验证、恢复、撤销、挂起、离线仍属M8必做 |

### D-34 Core 只识别平台无关权益

| 项目 | 记录 |
|---|---|
| 决策 | Core只问`STREAM_SAVE`等权益；商店SKU和SDK对象停留Adapter/Mapping |
| 理由 | 价格、组合、商店、平台变化不污染媒体核心 |
| 影响 | Google Play/StoreKit/其他市场可替换；不自动承诺跨商店共享购买 |
| 状态/依据 | CONFIRMED；H14；PR-02/19 |

### D-35 三个付费支柱与未来总包

| 商品 | 领域权益 | 免费边界 | 状态 |
|---|---|---|---|
| Provider Plus | PROVIDER_PLUS | 地址栏始终免费，基础Provider 0～1待定 | CONFIRMED模块 |
| Save Pro | STREAM_SAVE + RECORD_SAVE | DIRECT_SAVE永久免费 | CONFIRMED模块 |
| Video Tools Pro | SEGMENT_RECORDING、REGION_RECORDING、VIDEO_CROP、VIDEO_MERGE、VIDEO_COMPRESS | 基础播放/管理免费 | CONFIRMED模块 |
| EasySaveVideo Pro | 上述权益并集 | Vault无须总包 | CONFIRMED_DEFERRED |

理由：发现增强、保存增强、处理增强容易理解，销售不必与技术能力一样碎片化。内部保留细粒度权益以便未来调整，但当前不强制将②③拆成两次购买。价格、一次性/订阅、宽限期、总包时间、SKU和免费Provider数量为PENDING_RELEASE。

高级录制依赖基础Record能力。当前工程定义为`RECORD_SAVE AND 对应高级权益`；M8可通过商品Mapping组合授权，必须在购买前说明依赖。纯编辑既有媒体不额外要求RECORD_SAVE。这是ENGINEERING_BASELINE，不冒充历史已确定的售价/套餐细节。

### D-36 免费隐私与已保存数据不随权益撤销

| 项目 | 记录 |
|---|---|
| 决策 | Vault/Library永久免费；付费到期不删除已有媒体、不锁走用户的基础隐私功能 |
| 理由 | 用户文件与付费能力准入是不同生命周期 |
| 影响 | 免费能力无网络也可用；进行中付费任务按安全会话策略处理 |
| 状态 | 免费原则CONFIRMED；运行中策略ENGINEERING_BASELINE，M8复核 |
| 对应 | PR-18/19；T-E01/04 |

## 7. 可靠性、隐私与未来扩展决策

| 决策ID | 决定 | 理由 | 影响/验收 | 状态/依据 |
|---|---|---|---|---|
| D-37 | Release只记录必要ERROR，不记录普通业务与用户行为 | 保留排错能力并减少隐私暴露 | 白名单、脱敏、轮转限额、默认不上传、用户主动导出 | CONFIRMED；H16；PR-21 |
| D-38 | Fault Isolation + Self Recovery + Graceful Degradation | 局部Bug不能轻易拖死整个App | Provider/WebView/Save/Recorder/Encoder隔离，恢复失败安全停止 | CONFIRMED；H17；PR-22 |
| D-39 | 所有异步任务必须timeout/cancel/bounded retry/terminal state | 防无限loading、无限重试和资源泄漏 | 等用户状态持久化且释放资源；执行终态唯一 | CONFIRMED；H17；PR-22 |
| D-40 | 关键更新统一prepare→execute→verify→atomic commit/replace→cleanup | 防更新/媒体处理做到一半破坏旧数据 | 文件+DB用journal对账，跨卷不假设rename原子性 | CONFIRMED原则；工程细化；PR-22 |
| D-41 | 启动Recovery Check | 进程终止后找回半任务，清理危险残留 | 轻检查先行，长扫描异步；Vault密钥异常不删密文 | CONFIRMED；H17；PR-22 |
| D-42 | 故障注入为必需验收 | 正常路径通过不能证明可靠 | 覆盖断网/错误Provider/WebView/满盘/权限/DB/下载/录制/kill/更新中断 | CONFIRMED；当前请求/H17；PR-22 |
| D-43 | Automation/TaskQueue现在只预留接口 | 后续可复用核心命令，无需把逻辑绑死UI | idempotency/cancel/observe；不绕过授权/Vault/后台Discovery禁令 | CONFIRMED_DEFERRED；当前请求；PR-24 |
| D-44 | 先完整内部测试版，后渠道适配和上架复审 | 先验证能力，同时尽早识别架构性发行风险 | M0做可行性筛查，M8重审当期政策；不保证过审 | CONFIRMED；历史自用测试讨论/当前请求；PR-23 |
| D-45 | 历史/收藏/无痕/Session等浏览器需求保留 | 新增Discovery不能遗漏初始浏览器范围 | M1基础、M6完整；无痕不进入长期Seen | CONFIRMED；历史完整需求；PR-25 |

### D-37 与 D-13 不冲突

Provider Progress、Seen History、Task Repository和OperationJournal是实现指定功能的本地数据，不是运行日志。它们必须最小化、可清理、无痕例外正确，不能复制成业务流水，也不能默认上传。Release ERROR中的异常字符串同样可能泄漏URL/文件名，必须先映射为安全错误码。

### D-38 的现实边界

该原则是控制故障影响范围，不是保证操作系统、WebView和native库永不崩溃。独立进程是否必要由M0证据判断；不可恢复时关闭受影响能力并提供可理解UI。系统WebView不可用时不能声称地址栏仍一定能渲染网页，应展示系统故障及可用替代。

## 8. 本版工程定义登记

以下具体值/语义用于让六篇规格可测试，可在M0/M2/M6校准；不冒充用户逐项批准过的性能数字。

| ID | 工程定义 | 理由/影响 | 验证/变更阶段 |
|---|---|---|---|
| E-01 | 默认P20、L2P、H5P、池上限500 | 有限库存与可预期内存 | M0/M2资源测试 |
| E-02 | Provider15秒deadline、2次重试、单轮最多6请求、连续2页无新增冷却 | 给所有任务明确出口 | M0/M2弱网测试 |
| E-03 | 退后台立即禁新发现请求，2秒内取消在途 | 明确“停止Discovery”的可验收含义 | M2生命周期测试 |
| E-04 | SHOWN建议50%可见500ms；独立事件时间戳 | 避免预取算看过、SAVED误推PLAYED | M2 UI与历史测试 |
| E-05 | Seen按SearchProfile隔离，cursor版本不兼容只重置该来源 | 避免跨关键词意外隐藏和进度污染 | M2 |
| E-06 | 已物化页稳定，可回看；历史过滤影响新页分配 | 防卡片在用户眼前消失 | M2 |
| E-07 | WAITING_USER释放执行资源、建议24小时到期 | 兼顾用户控制与有界任务 | M3/M6 |
| E-08 | 日志1MiB/文件、最多5份、7天保留 | 防无限增长，保留最低诊断能力 | M1/M7 |
| E-09 | MP4基础兼容转换不算高级收费编辑 | 免费Direct与MP4目标保持一致 | M3/M8 |
| E-10 | 高级录制要求Record+工具权益，纯编辑只工具权益 | 防入口绕过并说明能力依赖 | M5/M8 |
| E-11 | 自动锁初始建议立即；用户可设置其他时间 | 默认保护私密内容 | M6 |
| E-12 | 音画目标<=100ms、拼接50秒±0.5秒、选区误差<=2像素 | 可量化输出质量 | M0/M5/M7 |
| E-13 | 文件/DB使用commitId+journal对账，SAF先私有产物后导出 | 跨存储不能假设事务原子性 | M3/M6 |
| E-14 | 仓库更新有安全版本下限、签名回滚策略、撤销密钥不接受 | 防LKG变成降级攻击入口 | M0/M6 |

## 9. 被替代方案登记

| 旧说法/方案 | 当前结论 | 替代原因/决策 |
|---|---|---|
| 继续增补v0.1_CN/v0.2_CN | 从完整历史重建v0.3_CN | D-01 |
| minSdk35或31 | minSdk30；Pixel7/API35只是测试环境 | D-02 |
| 直接Fork Yue改名 | 干净主工程+选择性迁移 | D-04 |
| 先写正式App再补审计 | M0 Gate先行 | D-05 |
| 必须经过自有搜索服务器 | Local-first、服务器可选 | D-10 |
| 第一版进度/Seen不持久化 | 最终确认本地长期保存 | D-13 |
| 所有后台行为统一取消 | Discovery停，保存独立 | D-11/29 |
| MVP必须一次做好全部框选编辑 | 整屏先行，高级M5，完整需求保留 | D-25/26/27 |
| Vault可收费/纳入Pro | Vault及隐私能力免费 | D-32/36 |
| ②③强制分别出售 | Save Pro包含两权益，内部仍细分 | D-35 |
| 立即接真实支付 | Fake开发、M8最后真实支付 | D-33 |
| 下载新Kotlin/Dex插件热更新 | 签名声明式包v1，无任意代码 | D-17 |
| 保存一定适用全部视频 | 受保护/不支持场景明确失败 | D-28 |
| 技术隔离即可免除GPL/AGPL义务 | M0按实际组合审计，不自动免责 | D-20 |
| 历史复用百分比等于工期节省 | 只是估算，M0给文件级证据 | D-04/05 |
| 历史对iOS捕获API的推测即为确定选型 | M9重新核查原生能力与政策 | D-03/30 |

## 10. 开放问题与关闭条件

| 问题ID | 未决事项 | 默认工作方向 | 关闭条件 | 阶段 |
|---|---|---|---|---|
| O-01 | 候选仓库具体版本/文件/许可证 | 未批准前不迁移 | 完整五类审计矩阵 | M0 |
| O-02 | AGP/Gradle/Kotlin/JDK/SDK精确组合 | 稳定可复现，保住API30 | 构建/安装/依赖证据 | M0 |
| O-03 | HLS到MP4支持profile、容器转换路线 | 普通VOD优先，remux优先 | 测试样本与支持表 | M0/M3 |
| O-04 | 内部音频与编码器设备覆盖 | 官方能力，明确不可用 | 真机允许/拒绝样本 | M0/M4 |
| O-05 | A隐藏/B浏览独立捕获 | 有界实验，整屏为保底 | 明确支持/不支持结论 | M0/后续 |
| O-06 | Package签名算法/密钥轮换/Runtime版本策略 | 成熟方案、无任意代码 | 安全设计与攻击测试 | M0/M6 |
| O-07 | Vault密钥层级/KDF/随机访问/恢复策略 | Keystore+成熟加密、无云恢复承诺 | 威胁模型、安全审查和失效测试 | M6 |
| O-08 | 免费Provider0还是1、首发哪些来源 | 开发至少一个真实来源，免费数量未定 | 商品/来源选择决策 | M8 |
| O-09 | 价格、订阅/一次性、总包SKU、Tools组合 | 三付费支柱固定，售价不猜 | ProductMapping和用户接受记录 | M8 |
| O-10 | 离线权益宽限与进行中到期策略 | 免费不受影响、已合法开始任务安全完成 | 当期商店与验证策略 | M8 |
| O-11 | targetSdk/商店规则/渠道FeaturePolicy | 每次发行重新查验 | 发行审查证据 | M8/M9 |
| O-12 | iOS最低系统/捕获API/后台限制 | 原生实现同Specification | iOS能力Gate | M9 |
| O-13 | Automation是否允许定期后台发现 | 当前不允许，只有接口预留 | 新需求和隐私/生命周期决策 | 未来 |

未决实现选择不阻止本版规格交付；对应Gate到期前必须关闭或明确降级。不能将“待验证”静默解释为“允许直接采用”。

## 11. 后续决策记录模板

```text
Decision ID：
生效Specification版本：
状态：CONFIRMED / ENGINEERING_BASELINE / PENDING... / SUPERSEDED
背景与问题：
考虑方案：
最终决定：
理由与证据：
产品/架构/许可/安全/商业影响：
受影响PR/TR/测试/里程碑：
迁移与回滚：
替代的旧决策：
未决事项与截止Gate：
审阅/接受记录：
```

任何改动若会提高minSdk、改变免费Vault/Direct、引入Provider硬依赖、放开远程任意代码、后台持续Discovery、取消系统录屏授权或削弱故障恢复约束，都属于规格级决策变更，不能仅以实现细节名义提交。
