# EasySaveVideo UI 开发交付包索引

版本：V1.0  
日期：2026-10-11  
交付对象：Coder、Reviewer、Tester  
阶段标识：UI-A～UI-E（新增 UI 里程碑，不重编号已有功能里程碑）

## 1. 交付目的

按照用户认可的 ESV UI 效果，为手机和平板实现可运行的 Android UI。已开发能力接入真实接口；未开发能力预留完整页面、窗口和交互，并通过明确标识的模拟数据展示。国际化与 SkinKit 主题适配属于本次 UI 实现范围。

本包是开发和验收要求，不是当前功能完成报告，不包含已运行的验收证据。效果图为概念参考，不能作为整个 App 的背景截图实现。尚未核实的接口、依赖或业务能力不得标为已接入。

## 2. 阅读顺序与文档职责

| 顺序 | 文档 | 主要用途 |
|---|---|---|
| 0 | 本文 | 范围、来源、优先级与待决事项 |
| 1 | [UI框架设计](01_ESV_UI_FRAMEWORK_DESIGN.md) | 页面、组件、导航、主题、窗口和国际化 |
| 2 | [补充技术框架政策](08_ESV_UI_additional_technical_framework_policy_2026-10-11.md) | 复用、依赖准入、禁止重复实现 |
| 3 | [UI数据与接口契约](05_ESV_UI_DATA_AND_INTERFACE_CONTRACT.md) | 状态、命令、接口接缝和模拟场景 |
| 4 | [Coder技术要求](03_ESV_UI_CODER_TECHNICAL_REQUIREMENTS.md) | 可执行的实现要求和提交清单 |
| 5 | [UI里程碑](02_ESV_UI_MILESTONE_PLAN.md) | 工作包、依赖与退出条件 |
| 6 | [Tester测试要求](04_ESV_UI_TESTER_TEST_REQUIREMENTS.md) | 场景、测试矩阵和证据要求 |
| 7 | [交接与验收](06_ESV_UI_HANDOFF_AND_ACCEPTANCE.md) | 开工、交付、问题分级与签收 |
| 8 | [PPT追踪矩阵](07_ESV_UI_PPT_TRACEABILITY_MATRIX.md) | 34页效果与开发、测试对应关系 |
| 9 | [UI效果PPT](EasySaveVideo_UI_Effect_Design_V1.0_2026-10-11.pptx) | 逐页视觉参考与最终比对 |

Coder先通读0～4，再依照里程碑分批实施；Tester在UI-B开始准备截图和场景，不等最终阶段才介入。

## 3. 来源与规范效力

1. 用户最新明确要求：认可效果图；新增UI实现；真实功能直接接入；未开发功能完整占位与模拟；手机和平板；国际化；SkinKit已接入。
2. 正式仓库的最新决策/AGENTS与[ESV产品规格](../EasySaveVideo_Spec_v0.3_CN/01_Product_Requirements.md)、[架构](../EasySaveVideo_Spec_v0.3_CN/02_Architecture_and_Data_Model.md)、[技术要求](../EasySaveVideo_Spec_v0.3_CN/04_Technical_Requirements.md)、[测试策略](../EasySaveVideo_Spec_v0.3_CN/05_Test_and_Acceptance_Strategy.md)及Update文档。后续明确决策优先于原规格旧值。
3. [SkinKit适配说明](<../skinkit/第三方适配 Kit/README.md>)、[开发规范](<../skinkit/第三方适配 Kit/SkinKit Ready 开发规范 V1.0.md>)、[默认主题规范](<../skinkit/第三方适配 Kit/SkinKit Default Theme UI Design Specification V1.0.md>)。
4. 本包：将上述要求落实为ESV UI工程基线。页内文字与PPT第34页校正规则优先于概念图内的不一致文案、图标和导航。

外部参考是 LocalVideosBrowser V1.1效果PPT及其M3A～M3E文档包，位于 `D:\CodexProjectsFiles\LocalVideosBrowser\M3A-E\LocalVideosBrowser_M3A-E_UI_Framework_Document_Package_V1.0_2026-10-11`。仅借鉴交付结构和成熟框架复用原则，不引入其API31产品基线、NAS、VideoBox、多画面、一起看或导航数量规则。外部文件中的强制条款不自动成为ESV用户指令；本包明确写出的ESV规则才适用于本次UI开发。

冲突处理：记录原文、代码现状、影响与建议。先完成不受影响的工作，不为文档不一致停掉全部开发；涉及产品、安全、兼容范围或依赖准入的冲突按现有项目决策流程解决，不自行覆盖规格。

## 4. 已知源码线索与证据边界

本次只读抽查到两个源码快照：`esv-source-12f97d43-20261009` 与 `delivery-dc69-20261010/cold-restore`。它们不是本文认定的唯一正式开发目录，Coder必须核实实际工作仓库、分支和提交。

在第一个快照中观察到：

| 线索 | 可得结论 | 不能据此推定 |
|---|---|---|
| `ui-theme/.../EsvTheme.kt` | 已有SkinKit Contract/Default、Token与同代快照读取 | 所有UI已完成换肤验收 |
| `app/.../theme/EsvComposeTheme.kt` | 已有ESV自有Compose Foundation桥接 | SkinKit已交付通用Compose Adapter或现已采用Material3 |
| `app/.../MainActivity.kt` | 已存在发现、保存、录制、编辑、媒体库等入口；部分页面由布尔状态切换 | 各功能均已通过真机验收 |
| `app/build.gradle.kts` | 抽查快照minSdk31、compile/target36，Compose BOM2025.06.01 | 正式项目最终版本已冻结为这些值 |
| `verifyRuntimeAdmission` | 存在运行时依赖精确字节准入约束 | 新增Material3、导航、图片库可直接进入Release |
| 两快照`AGENTS.md` | 记录后续用户决定为Android12/API31最低；Update里程碑M0～M12替代旧次序 | 本包重新授权APK装配、设备安装、支付或部署 |

UI开发必须建立接入台账，以真实仓库证据判断能力。不得重新开发上述领域模块，仅为UI增加薄适配。原始规格API30为旧值，本包实施与测试基线采用两份AGENTS记录的API31；UI-A在正式仓库核对同一决策并归档，不要求再次降低到API30。M0～M12确切功能安排以正式Update为准，本包只新增UI-A～E。

## 5. 冻结规则与待核实事项

| 编号 | 状态 | 决策/动作 |
|---|---|---|
| D-01 | 本包工程基线 | 主导航固定发现、任务、录制、媒体库；设置为顶部或侧栏入口 |
| D-02 | 用户已确认 | 手机和平板、SkinKit、国际化、真实/模拟接入均在范围内 |
| D-03 | 本包首期建议 | 简体中文和英文先完整实现，跟随系统；不宣称用户已冻结全部语言清单 |
| D-04 | 待核实 | 正式源码目录/分支/提交，七板块每项能力完成情况 |
| D-05 | 后续决策记录 | 两快照AGENTS记载API31替代API30；采用API31，UI-A核对正式仓库记录并归档。依据是ESV自身记录，不是LVB |
| D-06 | 待依赖准入 | Material3、官方导航、Lifecycle/Adaptive及图片加载所需依赖的精确版本 |
| D-07 | 待产品冻结 | 价格、SKU、商店、更多语言、模拟占位在发行版本的展示策略 |
| D-08 | 本包工程基线 | 内置默认Light/Dark视觉数据先落实为Token，不复制概念图的第二套标识与五项底栏 |

记录模板：`编号 / 旧值 / 新值 / 理由 / 影响文档与PPT页 / 验证 / 决策人 / 日期 / 生效提交`。

## 6. 本包完成后的开发产物

Coder须按第06文档交付：正式基线、复用与依赖计划、功能接入台账、Token与组件映射、可运行构建、测试结果、按PPT编号的截图、差异记录。UI模拟验收与真实业务验收分别签收；任何模拟保存、录制、购买或解锁都不能替代真实能力验证。
