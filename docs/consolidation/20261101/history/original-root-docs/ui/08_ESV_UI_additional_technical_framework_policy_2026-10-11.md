# EasySaveVideo UI 增补技术框架与复用政策

版本：V1.0｜日期：2026-10-11｜适用：Coder、Reviewer、Tester

## 1. ESV适用范围

本文件借鉴LVB补充政策的“成熟框架与现有能力优先”原则，并按ESV现状重新制定。不是直接导入LVB强制文件；不引入其API31、NAS、VideoKit、VideoBox和M3A～M3E编号。

本次仅新增UI实现里程碑。ESV已有SkinKit及领域功能，Coder不得为了视觉统一而重做保存、录制、媒体库、Vault、来源或支付。产品行为仍服从ESV规格，视觉以本包/PPT校正规则为准。

## 2. 技术决策表

| 能力 | ESV采用方向 | 开工检查/限制 |
|---|---|---|
| UI语言与工具 | Kotlin + Jetpack Compose | 不新增平行整套XML/View业务页面 |
| 基础控件 | Material3 Compose优先 | 当前Foundation桥接需兼容迁移；新增依赖先准入 |
| 主题 | 已有SkinKit Contract/Default + EsvTheme + ESV Compose Adapter | 不建立第二ThemeProvider；SDK无通用Compose Adapter |
| UI状态 | ViewModel、Flow/StateFlow、UDF、Lifecycle | 复用已有；不在Composable执行I/O或创建任务 |
| 导航 | 现有稳定官方导航；无正式实现则选一种已准入官方Compose方案 | 一套返回栈，不自行实现通用路由系统 |
| 窗口 | 官方Window Size Class/Adaptive | 精确版本兼容审查；ESV只写产品布局组合 |
| 图片 | 现有媒体Reader/缩略图+已准入成熟图片加载器 | 无合适公开图方案可评估Coil；Vault不走普通磁盘缓存 |
| 配置 | 现有统一SettingsRepository；缺失则评估DataStore | 局部SharedPreferences需受控迁移，不新造事务配置框架 |
| DI | 现有稳定Composition Root/容器 | 不新增第二容器；手写注入亦可复用 |
| 分页 | 既有ResultPool/显式物化页契约 | Paging3仅适配必要处，不能替换四策略/页码语义 |
| 后台任务 | 现有任务/恢复机制 | UI不新建Worker模拟进度；必要调度另行评估官方机制 |
| 媒体与播放 | 现有Browser Host、播放器、Media3/系统适配 | 不重新开发通用播放器、编码器或下载器 |
| 测试 | 现有JUnit/AndroidX/Compose测试；成熟截图与性能工具 | 工具依赖仍需准入，不自建完整截图引擎 |
| 日志 | 现有脱敏、白名单ERROR机制 | UI不新增行为追踪、点击埋点或默认上传 |

“优先”不是任意否定既有实现的理由。既有能力已经稳定且契约满足时继续复用；确需替换提交具体不满足项、替代方案、许可证、兼容和回归证据。流程遵循现有项目准入，不能靠删除准入任务或放宽哈希比较实现构建通过。

## 3. 依赖与版本

依赖集中于现有版本管理机制，固定版本；禁止动态“最新版”或自动升级整体工具链。精确版本由UI-A核对正式工程后记录，本包不指定未经核实的最新发布号。

当前抽查快照有Compose BOM2025.06.01/activity-compose1.10.1，minSdk31、compile/target36，以及`verifyRuntimeAdmission`运行时字节集合校验。这是快照事实，不是新的工具链升级决策。两快照AGENTS明确记录后续用户决定为Android12/API31最低，替代原规格minSdk30；本包采用API31，UI-A在正式仓库归档同一决定。其依据不是LVB的31。

新增Material3、Navigation、Adaptive、Lifecycle、Coil或AppCompat时逐项记录：用途、坐标/精确版本、下载来源、SHA256、许可证与NOTICE、传递闭包/Native内容、工具链兼容、minSdk、已有替代、构建变体影响和回退。项目已有准入机制要求的审查完成后才能进入受限生产依赖图；测试依赖也需项目许可检查。

Alpha/Beta/RC不是默认选项。确有必要遵循项目例外流程；UI规格、资源和适配设计可先推进，不把未准入候选伪装为生产版本。Kit文件名`0.3.0-recovery-r1`不等于内部各artifact都为0.3.0；依包内清单锁定实际模块坐标与字节。

## 4. 导航与布局

选择现有正式AndroidX导航实现；尚无则在复用计划中比较官方方案，关注稳定性、当前Compose/Activity兼容、类型安全路由、SavedState、返回与测试。不能同时引入两个框架并保持两套正式目的地事实。

ESV目的地登记是产品配置，统一四主入口和设置辅助入口。皮肤不能改变目的地树。窗口分类由Shell一次产出，Feature消费语义布局能力，不能各自维护型号名单和尺寸阈值副本。

分栏是业务组合层，列表和详情共用状态。媒体裁切或片段时间线可自定义业务绘制，但通用按钮、Dialog、Sheet、导航语义应使用标准组件。

## 5. SkinKit边界

业务Feature只读ESV视觉模型；公共主题Adapter依赖唯一正式Contract，沿用完整默认与同代pin/lease。Contract读取不做I/O，不把SDK ResourceHandle暴露给页面。缺资源/无效包用正式fallback并记录诊断。

ESV默认主题永久保留，离线不需商店/账号。UI-B增补必要Token、状态和本地资源，不改SDK实现源码。皮肤包不能提供业务代码、布局树、系统授权或购买凭证。未选用的动画/个人照片/Store模块不因UI设计而成为硬依赖。

当前自有Foundation桥接是可复用的主题接缝；标准组件迁移可逐步进行，但最终不能形成同一业务中两套互不一致的主题控制。

## 6. 模拟与正式实现

只为演示引入真实网络、Worker、Projection、Keystore或Store是不允许的。Mock/真实共用UI契约，能力级选择，Release隔离。状态与授权逻辑仍在领域，不能在UI提前重写未来的整套后台业务。

不使用Fake样本填充真实库，不把模拟结果写进用户发现历史。用户配置与演示选择器分离。真实接口故障应如实反馈，不能自动切模拟正常状态。

## 7. 允许与禁止的自有代码

允许：ESV媒体卡/保存选择/任务呈现/片段条/裁切/私密门禁组件；SkinKit到ESV Compose薄Adapter；UI模型、错误映射、目的地配置；有限确定性模拟数据；依Locale格式化与平台交互适配。

禁止未经必要性审查另建：通用UI/主题/导航引擎、图片下载解码多级缓存框架、配置数据库事务系统、DI容器、线程/协程调度器、通用分页缓存、后台调度器、日志引擎、截图比对框架；禁止为UI另建SaveCoordinator或Vault算法。

“代码不多”“更容易控制”“不想增加依赖”不能单独证明重复实现必要。成熟方案确不满足时，以具体证据申请项目例外，优先Adapter而非复制维护。

## 8. 开工/审查/验收检查

开工：正式目录/提交已确认，复用台账、API差异、版本与依赖计划可阅读。

审查：新增依赖与精确准入匹配；页面无公共视觉硬编码；无双导航/双主题/双任务实现；同generation与资源取消正确；模拟和真实不混；locale/窗口转换保留业务；私密与授权安全不回退。

验收：原功能回归、目标设备/构建检查、Release隔离、语言/主题/窗口/可访问性、PPT矩阵完成；未实测指标不能写通过。原则依据可见[Android状态管理](https://developer.android.com/develop/ui/compose/state-hoisting)、[窗口类别](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)、[每应用语言](https://developer.android.com/guide/topics/resources/app-languages)；依赖具体版本仍以正式基线为准。
