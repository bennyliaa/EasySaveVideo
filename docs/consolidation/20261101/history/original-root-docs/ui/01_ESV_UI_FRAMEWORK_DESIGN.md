# EasySaveVideo UI 框架设计

版本：V1.0｜日期：2026-10-11｜关联：PPT第1～34页

## 1. 实现目标与层次

用户认可的蓝绿色、柔和圆角、清晰中性表面为默认视觉方向。页面应可以直接承载真实业务，不能只做不可操作的视觉样板。设备边框、效果板标题和板外宣传背景不进入App。

建议依赖方向：

```text
App Composition Root
  ├─现有Core/Discovery/Browser/Save/Record/Library/Vault/Settings/Store
  │   └─真实或模拟UiPort Adapter → ViewModel/UiState → Feature Screen
  ├─SkinKit Contract/Default/(已选Core)
  │   └─现有ui-theme → ESV Compose视觉模型 → DesignSystem → Feature Screen
  └─官方导航 / Lifecycle / 自适应窗口 → ESV AppShell
```

允许将下面逻辑层先作为现有模块中的包，不强制创建大量Gradle模块：`ui/shell`、`ui/designsystem`、`ui/features/*`、`ui/adapters`、`ui/demo`。现有`ui-theme`继续作为正式主题入口。

Feature不得直接读取SkinKit资源包、访问文件/数据库/网络或构造领域对象图。网页与系统授权页由平台宿主承载，不强制由SkinKit改写。

## 2. 主导航与返回

| 稳定目的地ID | 中文/英文 | 根页面 | 次级目的地 |
|---|---|---|---|
| `discover` | 发现 / Discover | 新标签与发现结果 | 原网页、历史、收藏、候选保存 |
| `tasks` | 任务 / Tasks | 任务列表 | 详情、恢复、导出结果 |
| `record` | 录制 / Record | 录制准备 | 会话、片段、编辑 |
| `library` | 媒体库 / Library | 媒体与文件夹 | 播放、详情、私密保险箱、导出 |
| `settings` | 设置 / Settings | 分类页（辅助入口） | 来源、外观、语言、隐私、权益 |

底部固定四项，不使用图中的第五项设置，也不新增“视频工具”一级底栏。工具从媒体详情/录制流程进入。宽窗口使用Rail或侧栏；辅助设置入口始终可达。目的地及可见性由统一注册表维护，注册表是产品配置而不是第二套路由框架。

返回优先级：关闭系统键盘/弹层 → 结束多选或退出未提交编辑（必要时确认）→ 当前次级页面返回 → 所在根页面/官方导航默认行为。详情和保存选择关闭不取消后台保存；录制停止只能通过明确命令或系统停止事件触发。

每个根目的地保留轻量显示上下文：页码、筛选、稳定选中ID、列表锚点。不得把活WebView、媒体字节、密钥或完整大列表放入SavedState。

## 3. 手机和平板适配

基于实际可用窗口、Insets、键盘、高度与折叠遮挡计算，不能按设备型号或“平板=true”选择页面。官方窗口类别作为输入，不等于所有栏在同一断点强制出现。

| 窗口 | App Shell | 内容规则 |
|---|---|---|
| Compact宽度（通常<600dp） | 四项底栏，设置顶栏入口 | 单主面板，详情单独导航，筛选/保存Sheet |
| Medium（通常600～<840dp） | Rail优先 | 可双列/窄分栏；内容不足仍单面板，不能强挤三栏 |
| Expanded（通常≥840dp） | Rail或侧栏 | 主内容与详情/检查器并排，按各面板最小宽度决定可见性 |

这是初始布局策略，不把数值散落到Feature。Shell暴露`LayoutCapabilities`，由当前已批准的官方窗口API计算。低高度横屏、分屏与折叠遮挡必须进一步收缩布局。

| 页面 | 窄窗口 | 宽窗口 |
|---|---|---|
| 发现 | 默认两列卡片，筛选Sheet | 弹性网格+可收起筛选侧栏 |
| 网页/保存 | 网页+用户打开的选择Sheet | 网页+保存检查器，不创建第二个Browser Session |
| 任务 | 列表→详情 | 列表与选中任务详情 |
| 媒体库 | 网格→详情、多选工具条 | 文件夹/网格+详情；内容不足收起辅助面板 |
| 录制/编辑 | 预览、控制、分区工具 | 预览+片段条+编辑检查器 |
| 设置 | 分类→详情 | 分类+详情；来源信息视剩余空间显示 |

网格列数/每页数量来自配置。默认两列、20条/页是规格建议值；用户列数设置超出当前可用宽度时进行可解释的适配，保留偏好值。字体200%时允许减列、按钮换行、面板收起；不得缩小文字规避布局问题。

参考：[Android窗口类别](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)。本包采用其窗口输入方式；具体双栏条件属于ESV工程设计。

## 4. 七个功能板块的交互要求

### 4.1 发现（PPT6～8）

地址与关键词入口独立且常驻。网址跳网页，关键词发起发现；不确定输入允许用户纠正。卡片包含标题、来源、缩略图和已知时长；点击打开原网页，不能直接下载。

来源、重复策略、每页/每行、搜索强度使用现有领域配置。分页展示已物化页；未知总页数不显示虚假尾页。追加结果不重排用户当前阅读卡片；库存不足给出继续查找、稍后重试、更换来源。缺少Provider不阻断地址访问。

准确策略：`PREFER_NEW`优先显示新视频、`HIDE_WATCHED`隐藏已看过的视频、`HIDE_SHOWN`隐藏所有已展示过的视频、`SHOW_ALL`显示全部视频。真正进入可见区域才记录SHOWN；打开只记录OPENED，不能假造PLAYED。皮肤重组不重复写历史。

### 4.2 浏览与保存（PPT9～11）

复用Browser Host、标签和会话。轻量提示不代表可导出，完整检测由用户触发。候选Sheet/检查器让用户选择媒体、清晰度、目的地和方法。未知大小/能力显示未知。

①Direct免费，②Stream和③Record需Save Pro。降级由现有SaveCoordinator决定并由UI显示，不能在按钮回调里另建①→②→③循环。③必须用户确认与新系统授权。取消、满盘、认证失效、受保护内容不得自动转录屏。

### 4.3 任务（PPT12～14）

区分分析、下载/捕获、封装、验证、入库、等待用户、中断、失败、取消与完成。只有媒体验证和提交完成才成功；已下载字节不是最终成功。未知进度使用阶段说明。恢复/导出是独立动作，失败保留库内原件。

### 4.4 录制与编辑（PPT15～17）

准备页先说明捕获范围与音频能力，麦克风默认关闭。系统授权不是普通模拟弹窗。暂停不计入成片，示例20秒+30秒=50秒由领域状态推导。框选、旋转、片段合成需真实能力支持，否则为模拟场景。

编辑非破坏性，生成新视频，保留原件。纯编辑需要Video Tools Pro；高级录制同时需要基础录制权益与工具权益。UI不重写编码、计时和片段状态机。

### 4.5 媒体与私密（PPT18～20）

文件夹/网格、多选、播放、重命名、移动、导出经现有接口。清理历史/缓存不删除媒体。保险箱免费；锁定时清除私密标题、缩略图、播放视图与最近任务预览。私密导出是普通副本，提交前说明。模拟保险箱只使用公开演示素材，不能提供真实安全承诺。

### 4.6 设置与来源（PPT21～23）

发现、保存、录制编辑、私密、隐私诊断、权益，增设外观与语言。来源启停、检查更新、版本、权限范围、安全回退经现有管理接口。清关键词历史、全部发现历史、来源进度与缓存分别展示，不混为“清理全部”。

### 4.7 权益与异常（PPT24～26）

展示免费能力与三个付费模块，未知价格不编造。未接真实商店时标明未开放，模拟购买仅在演示构建。来源耗尽、空结果、网络、满盘、受保护内容、音频不可用各有操作出口，不使用统一空白页面掩盖故障。

## 5. SkinKit与默认视觉

当前Kit明确未提供通用Compose Adapter；ESV已存在自有桥接，应扩展该桥接而不是重新引入私有ThemeProvider。调用方向为SkinKit→现有EsvTheme→不可变ESV视觉模型→公共组件→Feature。

在一次pin内读取同一generation所需值，映射后释放read view；异步资源工作持有自己的lease，取消/替换时释放，旧generation结果不覆盖新图。Subscription随宿主销毁释放。同步getter和Composition不执行资源I/O。

Light/Dark `AppDefaultTheme`覆盖全部必需Token和组件状态，本地可用。Core/主题资源缺失不能阻断业务；包无效按正式fallback处理，不伪造成功。主题只影响外观，不改业务导航树、权限或任务。

以下是供UI-B冻结的视觉初值，必须集中放入默认主题数据；不是皮肤强制常量，也不是既有代码已采用的数值：

| 语义 | Light初值 | Dark初值/策略 |
|---|---|---|
| canvas | `#F5F8FA` | `#0E1B22` |
| surface / elevated | `#FFFFFF` / `#EDF4F6` | `#152832` / `#1C3440` |
| primary / onPrimary | `#087F8C` / `#FFFFFF` | `#62D9DF` / `#07343A` |
| text / muted | `#142B36` / `#546D79` | `#E5F2F5` / `#A7BCC5` |
| spacing | 4/8/12/16/24/32dp层级 | 相同语义，可由有效Token覆盖 |
| corner | 控件12dp、卡片16dp、Sheet24dp | 同语义，不将所有组件做大胶囊 |
| typography | title24sp、section20sp、body16sp、caption12sp；系统字体 | 随系统字号，行高与权重统一 |

实际前景/背景及所有状态对比度实测；这里未宣称所有配对通过。错误/成功/警告、按压/焦点/选中/禁用/加载、scrim、边框、图标、系统栏均补齐正式Token。触控至少48dp是宿主安全下限，不允许皮肤缩小。

Material3是本次标准控件目标，但当前Foundation桥接不必整体重写；先增加MaterialTheme映射和公共组件替换，对必要状态使用显式Token。默认动态配色不能悄悄覆盖用户选择的SkinKit主题。图标只用一套已准入资产，ESV波浪标记为候选，最终文件在UI-B归档。

## 6. 公共组件

| 组件ID | 组合与状态 | 归属 |
|---|---|---|
| C-01 ShellNavigation | NavigationBar/Rail、选中、返回、设置入口 | Shell |
| C-02 AddressSearch | 地址、搜索、清除、提交、错误、IME | DesignSystem/Browser |
| C-03 VideoCard | 缩略图、标题、来源、时长、选中、加载失败 | Discovery/Library |
| C-04 FilterPagination | 四策略、来源、页码、库存不足、已物化页 | Discovery |
| C-05 SaveSelector | 候选/质量/方法/目的地、Gate、提交防重 | Save |
| C-06 TaskCardDetail | 阶段、已知/未知进度、等待、恢复/取消 | Tasks |
| C-07 RecordControls | 捕获说明、系统授权、暂停/停止、有效时长 | Record |
| C-08 EditorPanels | 裁切手柄、片段条、参数、非破坏输出 | Editor |
| C-09 VaultGate | 锁定、认证中、失败、失效、不泄漏占位 | Vault |
| C-10 SettingsProvider | 分组、开关、更新状态、历史范围 | Settings |
| C-11 EntitlementPanel | 免费/已有/未有/未知权益、商店不可用 | Store |
| C-12 StateFeedback | loading/empty/offline/error、重试/取消 | DesignSystem |

每个公共组件明确参数、意图、状态、语义标签、Token依赖和Compact/Expanded行为。自定义裁切Canvas只承担业务几何绘制；控制按钮与可访问性动作仍用标准语义组件。

## 7. 国际化与可访问性

首期工程建议简体中文与英文，保留跟随系统；所有产品文字、错误、通知、确认、模拟标签、contentDescription和plurals使用资源。UI状态只持有消息ID及类型化参数，网页标题等来源文本按原文显示，不自动翻译。

Android13+对接平台每应用语言入口；旧系统采用经批准的兼容方案并与Compose资源上下文一致。当前Activity基类若不满足AppCompat条件，不可只调用API就声称实现完成。明确语言选择唯一存储所有者，避免平台locale与DataStore互相覆盖。语言变更/Activity重建保留任务与安全规则，不在磁盘持久化Vault解锁。

数量、时间、日期、文件大小依Locale格式化，不能拼接完整句子。准确“无痕”英文为Incognito，不能使用概念图中的Offline。长译文允许换行，RTL预留start/end与镜像策略。首期完整RTL语言是否发行另行冻结。

200%字号、TalkBack、键盘焦点、48dp目标、非纯颜色状态均必测；主题图片文字保护须有效。重要按钮不可只显示图标，进度播报节流，私密内容不进入语义树。

参考：[应用语言](https://developer.android.com/guide/topics/resources/app-languages)、[本地化资源](https://developer.android.com/guide/topics/resources/localization)。

## 8. 页面状态与模拟

屏幕使用不可变`UiState`与用户意图；UI收集具备Lifecycle边界的Flow。短暂动画/展开状态可在Composition，任务、认证和恢复决策在领域/适配层。ViewModel不持有Activity/WebView/Projection令牌。

模拟按动作配置能力，不按“整页全部Fake”强制替换。正常、加载、空、错误、离线、等待、中断固定场景可重复；页面及受影响动作明确“模拟演示”。真实失败不能静默转模拟成功。完整契约见第05文档。

参考：[Compose状态提升](https://developer.android.com/develop/ui/compose/state-hoisting)。
