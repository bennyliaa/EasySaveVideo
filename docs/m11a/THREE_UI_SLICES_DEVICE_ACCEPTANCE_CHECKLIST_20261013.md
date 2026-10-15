# ESV 当前 UI 分组设备验收清单（待执行）

日期：2026-10-13 UTC。在原设备清单 A–F 基础上增补后续切片 G–N，不扩张功能范围。初稿在仓库外整理，本交付随综合回归报告保存；清单编制仅只读核对现有源码、原始 UI Markdown、切片报告和冻结 manifest，并整理验收步骤，未运行任何测试、Gradle、设备或 APK 操作，未修改项目源码。每一项平台用例均为 **NOT_RUN**；复选框和预期结果均不表示已通过。

## 当前基线与使用方法

- 当前核对源码：`${SOURCE}`。此交接快照不含 `.git`；本清单不核实远端发布头，也不提供新提交 SHA。
- 必须区分以下按顺序相接、发布状态仍待核实的三个冻结：
  1. Editor Back，40 文件，manifest SHA256 `c425c1e0a4ef58073e5366a77dd8e11e1bc1f4b109f3c165c72d64fb5feb4e09`。
  2. A/B 批量目录确认及 Library 退出恢复，34 文件，manifest SHA256 `5681bb206c08caa6c58760783d0c694ef41027e7cf7dea401d89cabccf0f9e9d`；直接前驱是上项。
  3. Picker page return，29 文件，manifest SHA256 `461ef7ecfae37b7267feb06fb1069431b4837578f483b9871a27b22468eaad0a`；直接前驱是上项。
- 上述是本地 manifest 的实际 SHA256，不是 Git commit。历史 `24bc104a51e1b87dc2752c01bd616be29b44dc08` 是 Editor Back 报告的源基线；不得冒充 A/B 或 Picker 的直接发布父提交。后两项的发布父 SHA 仍须依次以真实远端回读建立。
- 已知旧 code8 APK 绑定 `c794ef84344d638f20966a1cd05bb5b340ade7e0`，是 pre-anchor 旧产物。它不能验证后续 reading anchor、authentication-origin、recording notification、editor acknowledgement、Editor Back、A/B 或 Picker 切片。不得以启动/安装这个 APK，或过去的主机验证，关闭这些平台用例；typed Save 等更早内容也必须按各自 APK 输入清单核实，不能猜测包含关系。
- 每条按“前提/动作 → 预期”执行；需要精确时序、故障或状态而缺少既有安全场景时，保留 NOT_RUN 并注明原因。真实平台与受控演示证据分开。本文无设备授权、批准、发布、发行或完整 UI 签收结论。

## 前提与边界

- [ ] **P01 | NOT_RUN | 基线**：填写实际源码 commit/ref、组合文件清单、构建变体、APK SHA256、包名、版本和签名。交接源码快照无 `.git`，个别报告的“待发布”是当时状态；不能把快照、某次 JVM 通过或历史 APK 当成最新已安装版本。
- [ ] **P02 | NOT_RUN | 设备范围**：后续若安排常规模拟器回归，先核对设备确为 `emulator-5556` 及其实际 API。本清单不启动执行，也不授予或扩展任何权限；不得操作已交还的 OPPO 或其他设备。API31、API32 和 API33+ 缺少可用目标时分项记 NOT_RUN，不换设备凑覆盖。
- [ ] **P03 | NOT_RUN | 保留数据**：兼容签名、合法升级版本及当前无待处理用户任务后才进入安装步骤；不卸载、不清数据、不降级、不改安全设置、不撤销既有目录授权。安装成功不单独证明数据完整；启动异常、数据变化或 ANR 时停止相关步骤并保留原状态。
- [ ] **P04 | NOT_RUN | 场景与取证**：仅用已获准公开/原创 QA 素材、测试地址和现有安全场景入口；不用真实账号或私人媒体制造状态，不写数据库造任务，不添加测试功能。缺少受控复现场景即记 BLOCKED/NOT_RUN。截图、语义树和日志不得暴露私人标题、URL 参数、Cookie、凭据、路径或完整响应。
- [ ] **P05 | NOT_RUN | 配置记录**：每条记录 API、实际可用窗口 dp/Insets、方向、字号、语言、主题、真实/模拟和场景 ID。100% 为对照，200% 为重点；代表性 Compact/Medium/Expanded、窄宽、低高度、横屏和键盘覆盖，无需完整笛卡尔积。只恢复明确由本次测试改变的设置。

## A 三处输入清除

依据：原始 01 §6 C-02 / §7、04 T-06～08；`INPUT_CLEAR_20261013.md`、`NEW_TAB_SEARCH_INPUT_20261013.md`。

- [ ] **A01 | NOT_RUN | Browser 地址草稿**：输入普通文字、空白、错误地址后点 Clear；仅草稿清空并保持编辑态，当前网页/标签/历史不变；不导航、不扫描、不发保存命令。重新输入后现有 Go 与 IME 提交仍按原策略工作。
- [ ] **A02 | NOT_RUN | 新标签草稿**：Auto、Web address、Video keywords 各测一次；Clear 仅清文字与错误，保留解释模式，不打开网址、不传递查询。正常显式提交仍分别打开准入地址或转到 Discovery 草稿；搜索需要用户按现有 Search。
- [ ] **A03 | NOT_RUN | Discovery 查询草稿**：清除只变更 query；其余筛选草稿、已应用 profile、已物化结果、页状态及历史不被清空，不自动 Search。接受的新编辑优先于等待中的 Browser 查询草稿，不被晚到旧交接覆盖。
- [ ] **A04 | NOT_RUN | 空值与防重**：三处空字段 Clear 禁用、纯空白允许清除；连续点按、已消费 claim、同值编辑和 A→B→A 后旧操作均不能清掉更新后的草稿或重复触发副作用。精确过期回调仅在既有适用测试入口可用时验证；肉眼连点不能代替 claim 时序证据。
- [ ] **A05 | NOT_RUN | 失效与占用**：切标签后切回、页面离开返回、暂停恢复、导航/地址同步、保存检查 revision 变化、页面销毁后，旧 Clear 不生效。Browser 的扫描、弹窗、全屏、picker、设置/收藏/同意和保存检查器占用，以及 Discovery 的不可见、未就绪、存储异常、busy、profile/目标不匹配和本地/全局 modal 占用，均遵守原 gate。
- [ ] **A06 | NOT_RUN | 可访问性**：三处当前语言的可见 Clear 文案、字段专属语义、正确 disabled 状态；200% 字号和键盘开启时可达，实际触控矩形至少 48×48dp，无错误聚焦/重复提交。

## B 认证等待任务返回浏览器

依据：原始 05 §8、03 UI-R13、04 T-14/T-16；`AUTHENTICATION_BROWSER_NAVIGATION_20261013.md`。

- [ ] **B01 | NOT_RUN | 正确入口**：DIRECT/STREAM 的 WAITING_USER/AUTHENTICATION，在紧凑任务卡与宽屏选中详情均显示准确“返回浏览器”及手动打开原网页登录的说明；不把其他等待原因、RECORD 或失败状态误呈现为可自动登录。
- [ ] **B02 | NOT_RUN | 唯一当前任务**：点击只导航到当前 Browser 路由；任务 ID/revision/state/wait reason/method/error 必须仍匹配唯一当前行。换选任务、任务修订/终态、重复行、缓存 revision 不一致和已消费 claim 拒绝旧操作。
- [ ] **B03 | NOT_RUN | 生命周期与占用**：重复点击至多接受一次；离页、后台、失焦、重建/销毁和返回后旧命令无效。初始化、pending command/media/expiry、扫描/选择/stream/native/recording/modal 占用保持禁用或点击时拒绝，不依赖过期的 enabled 外观。
- [ ] **B04 | NOT_RUN | 冷恢复边界**：仅在现有安全恢复场景可测时，分别覆盖“无 coordinator cache 但当前发布行与 gate 合法”和“存在但不匹配的 cache”；前者最多允许导航，不能据此称来源已验证或任务可恢复。不能为造场景删除用户数据。
- [ ] **B05 | NOT_RUN | 无隐式业务**：无 URL 推断/自动打开原站、自动登录、凭据请求、自动扫描、resume/cancel/提交或任务选择变更。返回后保留手动任务指导；普通导航成功不能记为认证成功或原始 OpenOriginForLogin 全流程完成；后续精确原页入口另见 I 组。

## C 中文 英文与跟随系统

依据：原始 01 §7、03 §7、04 T-05～07；`SAVE_DISPLAY_LOCALE_20261013.md`，以及 m11a 的 `ORDINARY_DESTINATION_FIXED_COPY`、`DESTINATION_TYPED_DISPLAY`、`CANDIDATE_PERMISSION_DISPLAY` 三份 20261013 报告。

- [ ] **C01 | NOT_RUN | locale 所有者**：在 API31/32 分别覆盖中文→英文→System、系统语言与 App 语言不同、Activity 重建及重启；显示与语义取当前 Compose resource locale，不沿用进程默认值或旧缓存。API33+ 另测现有平台每应用语言路径；不把其通过外推到 API31/32。
- [ ] **C02 | NOT_RUN | Save 显示**：验证输出元数据、录制有效时长、候选元数据、HLS 数量/带宽/预算文案、音频结果/停止原因；数量、时间、KiB/MiB/GiB 和复数随当前 locale，未知数据仍未知。媒体成功与发现历史的失败/过期/缺失结果分开，切语言不重提任务。
- [ ] **C03 | NOT_RUN | 目的地固定与类型消息**：Settings、选择/notice Dialog、任务目的地历史三处切语言；覆盖四种偏好、26 个 typed notice 的现有可构造场景、folder/album 名称及六类 copy-state 文案。保留状态优先级：未选额外复制→内部尚未验证→已准备未开始→已验证成功→失败/取消→处理中或不明；缺场景逐项 NOT_RUN。
- [ ] **C04 | NOT_RUN | 目的地语义不变**：内部 App Library 先验证；相册/文件夹为额外副本；失败/取消保留原件；不明复制不报完成；改/忘记文件夹不宣称撤销旧系统授权，也不改已接受任务。用户文件名、源文、任务 ID 不被翻译或重新解析。
- [ ] **C05 | NOT_RUN | 保存候选编号**：多视频/多来源受控样本中，完整句子在当前 locale 生成；保留 video 编号 clamp、原 source-array 序号及过滤导致的间隙。切语言不改候选 opaque identity、URL、HLS 升级 DTO、选择或有效性 gate。
- [ ] **C06 | NOT_RUN | 网站权限拒绝**：受控网页触发现有 camera/microphone 与 geolocation 拒绝；拒绝先于 notice，授权仍被拒绝。通知保持 typed 值到当前 Browser Composition 才本地化，已有 notice 切语言也正确；仅已脱敏 origin 原样展示，unknown-origin 用资源。不增加授权、自动重试或录制同意流程。
- [ ] **C07 | NOT_RUN | 重建与长文**：上述画面 200% 英文/中文、键盘和窄窗可读；TalkBack 使用对应当前语言且不泄露私人内容。语言、主题、窗口重组不自动搜索、保存或恢复；残余未迁移文字按实际缺口记录，不宣称全 App 已双语。

## D 四主导航与 200% 字号

依据：原始 01 §2/3/7、04 T-01/02/06/07；`SHELL_VISIBLE_LABELS_20261013.md`。

- [ ] **D01 | NOT_RUN | 四项名称**：发现、任务、录制、媒体库在 100%/200% 都保留可见文字，设置为辅助入口；不出现第五底栏、不因字号隐藏名称、不缩小字体或截断关键标签。逐项验证选中、disabled、点击和 Back 的实际路由。
- [ ] **D02 | NOT_RUN | 极窄宽度**：以扣除实际水平 Insets 后的内容宽度测 216dp 边界两侧及正常窄窗；四个 48dp 目标加三段 8dp 间隔不足时应走现有 Rail 回退。实际测量触控矩形与可达性，不能只凭源码 minimumSize 判通过。
- [ ] **D03 | NOT_RUN | 低高度与横屏**：200% 下测低高度、横屏、分屏及键盘显示；Rail 和顶部/底部有限滚动区域不崩溃、无双重 Insets。最后一项可滚动到达并点击，设置仍可达。低高度不要求四项同时完整露出，但不得永久隐藏任何主入口。
- [ ] **D04 | NOT_RUN | 状态与无障碍**：窗口/方向转换保留根选中、合理轻量滚动位置和业务 ID；不创建重复任务。TalkBack/键盘焦点、选中/禁用语义正确，无装饰图标重复播报；物理可视区小于 48dp 的极端配置明确记边界，不伪称完全可用。

## E Discovery 内嵌与浮层筛选

依据：原始 01 §3/5、05 §5/6；`DISCOVERY_FILTER_PANEL_ROLE_20261013.md`。

- [ ] **E01 | NOT_RUN | 表面角色**：实际 sidebar 使用 INLINE 的 surface/cardCorner；实际浮层使用 FLOATING 的 elevated/sheetCorner；沿用原 spacingLarge 和内部控件形状。当前浮层为 Dialog，不能当 BottomSheet 已实现或已验收。
- [ ] **E02 | NOT_RUN | 单份内容迁移**：打开面板，填写未提交 source-editor/filter/query 草稿并滚至中部；宽→窄→宽、方向/字号变化时观察同一可移动内容。草稿、claims、筛选和 scroll 保留，无双实例、重复回调、重置或自动 Search；安全 gate 原本要求失效的操作仍应失效。
- [ ] **E03 | NOT_RUN | 关闭与所有权**：系统 Back、dismiss、离开/返回页、背景恢复后保持现有关闭和 query-clear 生命周期规则；shown-result blocking 与页面可见性门不退化，不提交未确认来源草稿。
- [ ] **E04 | NOT_RUN | 200% 可用性**：中文/长英文、键盘、低高度 Dialog、窄/宽转换时，筛选/清除/关闭与现有恢复操作可达；无嵌套无限高度滚动异常、关键裁切或丢失滚动位置。对应 empty/loading/waiting/error 正文不误报“无结果”，已有结果不因新工作/失败消失。

## F 主题角色与覆盖值

依据：原始 01 §5、03 §3、08 §5、04 T-03/04/07；`SEMANTIC_THEME_TOKENS_20261013.md`、`ESV_UI_TOKEN_AND_COMPONENT_MAP.md`。

- [ ] **F01 | NOT_RUN | 默认与跟随系统**：同一公开场景测 Light/Dark/System，surface 与 elevated、control/card/sheet 圆角、section/title 层级按实际 Token 显示；默认 Light primary 保留已说明的 #087582，不按概念初值 #087F8C 强行判错。
- [ ] **F02 | NOT_RUN | 非默认覆盖**：仅用已有获准主题/测试入口验证不同 surface/elevated、card 13dp、sheet 21dp、section 23sp 等覆盖可经同一快照生效；如无运行时入口则此项 NOT_RUN，不新建主题机制。检查 200% 使用逻辑字号，无二次缩放；48dp 触控安全下限不可被皮肤缩小。
- [ ] **F03 | NOT_RUN | 真实使用点**：检查 Discovery 卡片/筛选、任务卡/详情、Library、Editor、录制片段、Provider 和 SaveSelectionPanel 的相应角色；保存选择 INLINE 为 surface/card/16dp 默认 padding，FLOATING 为 elevated/sheet/32dp 默认 padding，并能响应覆盖。控件内层形状及按钮自身 corner 不被一律改成 sheet。
- [ ] **F04 | NOT_RUN | 切换连续性**：快速换主题、重建和打开面板时无混代显示、陈旧资源覆盖或业务实例重复；保留选择、草稿、scroll 和现有任务。未接入资源/lease 场景单列 NOT_RUN，不能以本次纯 Token 切换证明完整图片资源生命周期。
- [ ] **F05 | NOT_RUN | 可读状态**：实际测普通正文对比度目标 4.5:1，检查错误/禁用/焦点等当前实现状态及非纯颜色表达。新增 elevated/card/sheet 等部分通过不代表 success/warning/selected/loading/scrim/system-bar 或全组件状态 Token 已完整。

## G Save runtime typed notice（三个既有显示点）

依据：原始 01 §7、02 UI-B/UI-D、03 §7、04 T-05～07；`SAVE_RUNTIME_NOTICE_DISPLAY_20261013.md`。承接 C02，不把这个 notice 通道当成全部 App 文案已迁移。

- [ ] **G01 | NOT_RUN | 留存通知切语言**：在 Tasks、Browser、Recording Save fallback 各留存一个现有安全场景通知，再切中文→英文→System；API31/32 与 API33+ 分别记录。预期留存 typed notice 按当前资源重新呈现，原任务、状态、选择不变，无重新提交、重试、resume、检测或导航。
- [ ] **G02 | NOT_RUN | 分类与变量**：按既有受控入口分别列出固定提示、网络准入、任务容量、检测结果和分类失败可覆盖项；容量显示配置上限而非当前任务数，整数/复数随语言。网络准入 WIFI_REQUIRED 与详细错误 WIFI_REQUIRED 保留两种原有语义；未知错误不得显示原异常。全部 78 显示组没有实际场景时逐项保留 NOT_RUN，不为凑齐而增加入口、填满用户存储或制造真实失败。
- [ ] **G03 | NOT_RUN | Recording 披露优先级**：同时存在 editor-owned notice 与 Save fallback 时保留前者优先；详细内容默认收起，仅用户明确展开/收起。已展开文本切语言后遵循现有重新收起行为，不绕过披露或误认为新的错误。
- [ ] **G04 | NOT_RUN | 替换与可达性**：现有 dismiss、快速重复 dismiss、新通知替换旧通知、离页返回/重建分别记录；按既有显式 dismiss 的所有权与清除规则收束，记录有无误清除新通知或业务命令重放。窄/宽窗、横屏、键盘、200% 长英文/中文下全文可读，显式关闭与展开控制可达，语义及日志不含 URL、标题、文件名、路径、ID、凭据或原异常。

## H 录制前台通知的语言与生命周期

依据：原始 03 §7、04 T-05/T-17/T-18；`RECORDING_NOTIFICATION_LOCALIZATION_20261013.md`。真实捕获/编码仍需合适的获准设备证据，模拟器通知表现不能证明媒体能力。

- [ ] **H01 | NOT_RUN | 现有状态文案**：使用获准原创/公开录制场景，记录初始 preparing、六类原 basic 状态组、五个已发出的 segment 阶段；加上 channel 名称、标题和 Stop & Save 共 15 个显示叶。中文/英文对应当前状态；未发出通知的其他 segment 阶段不要求新通知，processing/publication 不得被新增成功提示覆盖。不可安全达到的状态逐项 NOT_RUN。
- [ ] **H02 | NOT_RUN | 静止状态换语言**：在已进入前台且暂停/无新进度的同一会话切 App 语言，记录通知是否无需状态迁移即可更新；重复相同语言不创建第二通知/服务/任务。仍使用通知 ID 4301 和 esv_recording channel；不改变 channel 的权限、importance、声音或震动设置。
- [ ] **H03 | NOT_RUN | 启动与平台差异**：API31/32 显式 EN/ZH、System 和 API33+ 平台语言分别测。使用既有可控场景观察冷启动设置延迟/失败与 Activity 重建：初始 SYSTEM fallback 的实际情况必须记录，不能保证设置加载前第一帧已有最终语言；临时默认 AppSettings 不应覆盖已存在 runtime 的有效语言镜像。
- [ ] **H04 | NOT_RUN | 结束与旧通知**：停止完成、销毁、旧 session Stop action、退后台/回前台和重建后观察；语言刷新不得复活结束会话通知、停止后来会话或启动新的录制。须记录 API33+ 语言变化引发的 Activity 生命周期及原有 onPause/preview 行为，不要求或声称无缝不断录；已有安全停止/同意规则保持。
- [ ] **H05 | NOT_RUN | 系统显示与无障碍**：实际通知权限下的可见性、长文本/200%/TalkBack、channel 名称与 Stop & Save 动作可理解且可达；无用户文件名/标题/URL/session ID 出现在通知文案。没有可用通知权限或平台场景时记 NOT_RUN，不在本轮修改安全/权限设置。仅通知语言通过不能关闭第二次录制、ANR、音轨或文件验证缺口。

## I 认证等待任务选择仍存活的原页面

依据：原始 03 §8、05 §8 的 OpenOriginForLogin；`AUTHENTICATION_ORIGIN_NAVIGATION_20261013.md`。本组扩展 B 的普通 Browser fallback，二者是独立动作。

- [ ] **I01 | NOT_RUN | 精确同源成功路径**：受控、已有权限使用的 HTTPS 同源页面中显式创建新的 Direct/Stream 任务，并通过既有安全场景进入 WAITING_USER/AUTHENTICATION；保持原 document 存活。点原页入口应只选中已有精确标签并进入 Browser，不创建 WebView、reload、load URL 或产生新请求；记录实际 tab/document 和受控请求证据。任务不自动 resume/retry/login，不把页面选中记为认证成功。
- [ ] **I02 | NOT_RUN | 不可用仍有退路**：冷恢复/无关联、无痕、跨源或 CDN、最终 HLS variant 跨源、只有 existing=true 幂等接受而没有旧关联，各自显示明确不可用说明，普通 Browser fallback 仍单独可用。不得从任务记录、持久化 origin 或相似 URL 推断原页；不要求普遍支持恢复原站。
- [ ] **I03 | NOT_RUN | 活文档身份**：只切换标签后返回可保留同一个活文档；关闭/驱逐/重建 WebView、导航/reload/history revision、同 URL A→B→A 后旧原页动作均失效。若真实 renderer death 或精确回调不可安全复现则 NOT_RUN，不能拿相同 tabId/URL 替代原 document 身份。
- [ ] **I04 | NOT_RUN | 过期与后台**：令原关联达到 15 分钟单调时间上限，及前后台/Activity 或进程重建；旧令牌不能复活或自动重新关联。后台晚到的创建回调也不能恢复已清除关联。精确晚回调只用既有可控入口，不为本清单增加调试功能。
- [ ] **I05 | NOT_RUN | 点击时重新核验**：任务被修改/终止/换选、browser interaction revision 改变、失焦、file chooser、JS dialog、全屏、扫描/保存/录制占用时重试旧按钮；无旧选择或旧取消副作用。快速连点至多一次，native dismiss 回调之后的进一步变化仍可拒绝。中文/英文不可用说明、200%/48dp/TalkBack 同验；不查看或收集 Cookie、凭据或私人来源内容。

## J Discovery 稳定阅读锚点

依据：原始 01 §2、02 §4、04 T-02、05 §5/§6；`DISCOVERY_READING_ANCHOR_20261013.md`。仅当前已物化页面的显示恢复，不代表来源/访问/业务权限。

- [ ] **J01 | NOT_RUN | 根页往返**：在有稳定 result ID 的已物化页滚到中部，记录首个可见 ID 与偏移，再离开/返回 Discover。相同 session/page/applied-query/view/revision/demo 身份恢复到该 ID 与合理偏移；改变网格列数或 Compact/Expanded 不因索引变化跳到错误条目。
- [ ] **J02 | NOT_RUN | 草稿与真正新页区分**：仅 Clear 查询草稿或执行既有“保留已物化页”的历史操作时，应用查询身份、当前结果与阅读位置不被清除，不自动 Search。新搜索（包括相同查询的新 session/epoch）、翻页、来源或真实 applied-query/revision/demo 改变应按身份变化回顶，不能把旧页锚点套入新结果。历史保留策略不由本清单重新决定。
- [ ] **J03 | NOT_RUN | 一次恢复与有界退路**：旋转/实际保存状态恢复后，仅具有精确直接 old→new restore provenance 且相同保留池/页面时接纳旧 epoch 锚点。屏幕未再显示期间多次重建、没有直接证明、缺失/重复 stable ID、格式无效时安全回顶；不把“回顶”自动认定为缺陷或承诺无限次恢复。进程恢复仅在既有获准安全场景下操作。
- [ ] **J04 | NOT_RUN | 拖动和晚回调**：恢复滚动时手动拖动应可中断该滚动，不争抢用户手势，之后仍能保存最新位置；恢复未完成期间变更页/query/source、退出页面、退后台及返回时，旧回调不能覆盖新锚点。无精确时序入口的分支逐项 NOT_RUN。
- [ ] **J05 | NOT_RUN | 可见性与副作用**：modal/pending content 隐藏时不从错误网格捕获锚点，root/window/locale/theme 变化不增加 search/getNext 或重复 SHOWN 事件。结合现有安全技术计数而非私人内容取证；在 200%/不同列数记录实际 LazyGrid 布局与可达性，不能用 JVM codec 通过代替真实 Android 恢复。

## K 编辑提交的目的地回执

依据：原始 03 UI-R13/§8、04 T-20、05 §9；`EDITOR_DESTINATION_ACKNOWLEDGEMENT_20261013.md`。对应已存在目的地选择，不增加编辑工具或导出目标。

- [ ] **K01 | NOT_RUN | ASK 取消后可重提**：用已有合法编辑草稿 Submit→ASK→Cancel/dismiss/modal Back→继续编辑→再次 Submit；等待选择时 Submit 和草稿控件禁用，取消回执结束后重新可用、草稿未被改写，无输出、native probe 或媒体工作由取消触发。
- [ ] **K02 | NOT_RUN | 拒绝与失败可恢复**：用既有安全入口分别覆盖 unready/busy/source 无效、source predicate 异常、worker/queue 拒绝/失败。只有匹配 attempt 的 terminal receipt 解除 pending；UI 可继续编辑/重提，错误真实、不假报成功。不能安全造出的失败记 NOT_RUN，不通过真实满盘、私有媒体或改数据库制造状态。
- [ ] **K03 | NOT_RUN | 不可用文件夹两条路径**：ASK 中不可用的 folder choice 仍保持本轮选择，允许改选其他合法目的地；自动 folder choice 不可用则终止为 rejected 并解除编辑 pending。二者不能互换为“永远等待”或自动复制到新目录；既有目录授权策略不改变。
- [ ] **K04 | NOT_RUN | 重复、重入与旧回执**：连续 Submit、取消后新一次 Submit、旧/重复回执及旧页面恢复时，只有当前 attempt 能推进 submissionRevision；不得替换正在等待的 attempt、清掉后一次 pending 或覆盖新 notice。旧 editor cancellation 不影响其他 Save/Record 目的地选择。精确异步分支仅凭现有时序入口取证。
- [ ] **K05 | NOT_RUN | 重建与真正接受**：目的地 pending 时重建/退后台/显式换输入/安全取消，按已有所有权规则完成或退休，不留下永久 disabled、不重放提交。合法 accepted 路径仍可做既有 missing-geometry metadata probe 并进入原生处理；只有验证与入库后可称新输出，原输入保留。共享的 MP4/HLS/录制入口不得因编辑回执变更而多次接受。实际编码、文件与 OEM 能力分别取证。

## L 编辑 Back 确认与发布反馈

依据：原始 01 §2、03 §8、04 T-20；`EDITOR_BACK_CONFIRMATION_20261013.md`。本组对应 Editor Back 40 文件冻结。

- [ ] **L01 | NOT_RUN | 同一 Back 分派**：PROCESSING/VERIFYING 分别点系统 Back、可用平台上的 predictive Back、页头 Return；出现 stop confirmation，未确认不取消也不 pop。Continue Processing、dismiss 或再按 Back 只关闭本对话框，处理继续；裁切弹层仍优先关闭。实际键盘/系统回调顺序单独记录。
- [ ] **L02 | NOT_RUN | 确认只请求停止**：确认 Stop 后仍在当前编辑页，按实际取消结果显示 stopping 或已取消状态，worker 完成不导航；连续确认、被替换的对话框以及过期回调均不能再次取消或影响新编辑。确认本身不能删除原件或被视为成功输出。
- [ ] **L03 | NOT_RUN | 发布竞态**：在已有可控场景中，让同一操作从 PROCESSING/VERIFYING 推进至 publication admission，再点原 stop dialog 的确认；原子 token 决定 ACCEPTED/ALREADY_CANCELLED/TOO_LATE，TOO_LATE 显示结果/发布核查而非伪称停止。不能仅凭 PUBLISHING UI 阶段判定已过取消点；无受控 native 时序则该分支 NOT_RUN。
- [ ] **L04 | NOT_RUN | PUBLISHING 状态说明**：直接在 PUBLISHING 按 Back 只显示状态对话框，无新 stop 命令、无 pop、无成功承诺；较新状态/legacy 请求取代旧 dialog 后，旧确认不得再执行。自然阶段推进与“新请求替代对话框”分别验证，不混同失效规则。
- [ ] **L05 | NOT_RUN | 页面与延迟反馈**：退后台/恢复、重建、离页重进、换 editor runtime/operation 后旧命令无效；旧页面关闭不得退休新页面。既有 delayed-stop 回调只更新同一个 current token：未准入发布时等待 native release，已准入时核查结果；终态/换 phase 后不保留旧 stop 文案，不改变既有安全停止或计时。
- [ ] **L06 | NOT_RUN | 保留 legacy 边界与显示**：idle draft、明确 Safe Stop、以及已请求 stop 后 CANCELLING/WAITING_NATIVE_RELEASE 的新 Back 保留原行为；不把此切片当作所有未保存草稿确认或“必须等终止才可退出”。新弹框/反馈 EN/ZH、200%/窄窗/低高度/TalkBack/48dp 可达；只有已验证 published reference 才提供成功预览，uncertain 仍走既有 blocked/recovery。

## M 批量目录确认 A 与 Library 退出 B

依据：原始 01 §2/§4、03 §8、04 T-21/T-25、05 §10；`LIBRARY_BATCH_RECOVERY_20261013.md`。本组对应 A/B 34 文件冻结；不改变复制引擎或权限/存储格式。

- [ ] **M01 | NOT_RUN | A：确认至多一次**：选受控普通媒体→每批仅一次 ACTION_OPEN_DOCUMENT_TREE→选目录→显式 Confirm，迅速双击。只启动一批；第二次不能把已接受批次改为 stale/cancel，返回 picker 本身不开始复制。
- [ ] **M02 | NOT_RUN | A：取消与新请求隔离**：取消/重新选目录后旧确认、旧 picker result、与新 DirectorySelection 不同的 action 均无作用；当前仍 pending 的确认如果最新 source/host/selection proof 失效，应按现有 stale/cancel 收束，而非使用旧 refs。不得让一次确认批准后来批次。
- [ ] **M03 | NOT_RUN | B：真实离开 Library**：执行既有 Library→其他 destination 的 root switch、navigate、成功 backTo/popBackStack；在实际 destination 变化后对活跃批次使用同一原有取消规则。停止尚未开始项，已完成副本保留；同一 Library 的详情/对话框关闭、无目标的失败 Back 不应取消仍拥有页面的批次。
- [ ] **M04 | NOT_RUN | Picker 暂停与生命周期**：预期系统 tree picker 暂停不误取消，取消/结果/回前台后仍要求有效身份及显式确认；非预期后台、宿主替换/销毁、旋转和进程重建按当前所有权失效，旧回调不复活批次。恢复仅核对 durable 结果，不从 UI 重造操作。
- [ ] **M05 | NOT_RUN | 部分结果与不确定性**：受控 provider/已有故障入口下观察已完成、未开始和不确定项；逐项结果准确，取消不承诺 rollback，原媒体及已完成输出保留。provider 返回不确定时不静默新建第二份文档、不把 chooser 打开或 journal 接受记为送达/文件完成；复制后完整性与实际接收权限需单独证据，不发送分享。

## N 文件夹 picker 返回后保留 Library 页与重验

依据：原始 01 §2、04 T-02/T-21/T-25、05 §10；`LIBRARY_PICKER_PAGE_RETURN_20261013.md`。本组对应 Picker 29 文件冻结，是 M 后续，不能用 M 的旧报告替代。

- [ ] **N01 | NOT_RUN | 第二页及之后正常返回**：以现有受控目录中足够公开测试媒体进入 page2+，记录 full query/cursor 与所选 exact refs；开 picker、选目录、返回。拥有同一 request/host/runtime/intent 的情况下保持原页并执行一次既有 bounded load；READY/foreground/非 busy/strict selection 全部重验之前不能 Confirm，确认前不复制。page1 作对照，不以新建导入功能造素材。
- [ ] **N02 | NOT_RUN | WAITING 与结果先后**：慢选目录、暂回 App 而未给结果、重复 WAITING resumes，不重置页、不消耗未来 matching-result 额度；分别观察 result-before-resume、resume-before-result、以及 Compose false effect 被合并的真实情况。RECEIVED 时触发一次原 query 重读，重复 result/重复 resume 不多次恢复或自动确认。精确平台排序无法取得时分项 NOT_RUN。
- [ ] **N03 | NOT_RUN | 用户新意图优先**：返回重验中显式改变搜索/排序/页/folder/refresh/selection，再改回相同值；旧 allowance 仍退休，不被值相等重新认可。旧读取/晚结果不能发布到新 generation/lease，选中的新媒体不被替换为旧 refs；同样覆盖实际 route exit 与取消。
- [ ] **N04 | NOT_RUN | Cursor/来源变化失败**：在已有受控 provider 场景中，使 cursor STALE_PAGE、ref revision/generation 改变、来源缺失/删除或读失败/队列拒绝。STALE_PAGE 仍回第一页、废弃旧选择并说明 reselect；其他失败保留既有明确失败路径。不得猜新 cursor、替换媒体或继续复制；未获准删除任何媒体时该故障分支保持 NOT_RUN。
- [ ] **N05 | NOT_RUN | 宿主与普通恢复边界**：旋转/重建/host detach/新 runtime/process death 后没有相同 process-local lease 时，不承诺返回原页或延续授权，旧 picker 回调不可恢复能力；没有 owned picker lease 的普通 resume 仍按原有第一页 refresh 策略。区分 UI 连续性 allowance 与 URI grant/复制权限。
- [ ] **N06 | NOT_RUN | 待删除轮询与最终输出**：仅使用已存在的安全 pending-delete 场景检查未重验 round trip 暂停轮询，重验完成/退休后恢复；后续 catalog 变化继续使旧 selection 无效。不得为本条创建删除意图。最后以一次新显式确认核对本批实际输出完整性、逐项状态、原件保留和无扩大 URI grants；200%/键盘/TalkBack 下确认与 stale/reselect 说明可读可达。

## 记录与签收限制

每例填写：`ID / commit 与 APK hash / REAL 或 DEMO / 场景 / 设备 API / 窗口 dp 与 Insets / 语言 / 主题 / 字号 / 操作 / 实际结果 / PASS、FAIL、NOT_RUN 或 BLOCKED / 脱敏证据 / 缺陷 ID / 执行者及时间`。每条本清单初始状态均为 NOT_RUN。执行后按分支另填实际状态；BLOCKED 必须说明具体缺少场景/设备/权限/输入。一个分支缺证据，不得把整条记为无条件 PASS；模拟界面不能关闭真实业务 Gate。设备、native 文件、无障碍与视觉证据分别记录，不能把主机回归数字相加或外推成当前平台通过。

- **历史 OPPO 问题仍开放**：编辑 `STORAGE_UNAVAILABLE` 与分享 `INITIALIZATION_FAILED` 没有被这些 UI/locale/主题源码测试修复证明。模拟器后续通过也不能自动关闭 OPPO 问题；ANR 和同进程二次录制的历史未闭环项保持各自状态。
- **Windows 旧问题未关闭**：code8 的符号链接 fixture 创建曾失败，支持范围重跑排除了该方法；该环境 setup 为 FAILED、该 guard assertions 为 NOT_RUN。云端或其他主机通过不改写此结论。原 Windows 最终签名设备报告未在该云快照取到，不把其路径写为已核验附件。
- **无 PPT 视觉验收结论**：原始 00～08 Markdown 可读，07 是 34 页需求追踪矩阵；PPT 二进制/图像未取得或查看。本清单未做截图、像素比较或完整 UI-E 签收。
- **本轮停止条件**：交付这份待执行清单即完成；不启动构建、模拟器/手机、登录、分享发送或任何新功能。后续按实际缺陷分级，P0/P1 未清零不得签收对应范围。

## 来源索引

原始依据：本次读取的独立交接目录 `/workspace/shared/esv-ui-original-handoff-20261013/` 中 00～08 共九份 Markdown，重点为 01、02、03、04、05、06、07、08；源码快照 `docs/ui/` 仅见 implementation 报告，不能把独立原件误写成已纳入该快照。以上切片报告路径相对于源码根目录：早期 UI 报告在 `docs/ui/implementation/`，认证、目的地、typed Save、阅读锚点、录制通知、编辑回执/Back、批量恢复及 picker 返回报告在 `docs/m11a/`。历史证据边界另见 `docs/ui/implementation/CODE7_DEVICE_DEFECTS_20261012.md`、`CODE8_INITIALIZATION_INVESTIGATION_20261012.md` 和 `docs/m11a/PROJECT_COMPLETION_EVIDENCE_2026-10-13.md`。后者包含早期快照状态；最新独立 slice 报告用于本清单的类型消息及角色覆盖，不用早期“尚未迁移”文字覆盖后续实现。


关键源码核对点（只读，未执行）：`EditorBackPolicy.kt`、`EditorScreen.kt`、`EditorSubmissionAttempts.kt`、`DirectoryBatchConfirmation.kt`、`LibraryPickerPageReturn.kt`、`AndroidLibraryRuntime.kt`、`AndroidExportRuntime.kt`、`RecordingNotificationStatus.kt` 及 `MainActivity.kt` 的对应接线。冻结 manifest 来自仓库外既有 editor-back、batch-recovery、picker-page 三份 frozen 目录；其文件数与上述 SHA256 已读取/重算，本文不改变冻结文件，也不声称远端已发布。
