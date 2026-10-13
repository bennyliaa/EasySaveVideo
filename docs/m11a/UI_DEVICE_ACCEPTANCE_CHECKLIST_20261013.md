# ESV 最新 UI 分组设备验收清单

日期：2026-10-13 UTC。用途：为测试者准备有限、可记录的设备验收；本次仅整理文档，未构建、安装、运行设备或修改源码。以下设备用例全部为 **NOT_RUN**，复选框表示待执行，不表示通过。

## 前提与边界

- [ ] **P01 基线**：填写实际源码 commit/ref、组合文件清单、构建变体、APK SHA256、包名、版本和签名。交接源码快照无 `.git`，个别报告的“待发布”是当时状态；不能把快照、某次 JVM 通过或历史 APK 当成最新已安装版本。
- [ ] **P02 设备范围**：后续若安排常规模拟器回归，先核对设备确为 `emulator-5556` 及其实际 API。已有常规构建、单元测试和该模拟器保留数据安装授权，本清单不启动执行；授权不扩展至 OPPO 或其他设备。API31、API32 和 API33+ 缺少可用目标时分项记 NOT_RUN，不换设备凑覆盖。
- [ ] **P03 保留数据**：兼容签名、合法升级版本及当前无待处理用户任务后才进入安装步骤；不卸载、不清数据、不降级、不改安全设置、不撤销既有目录授权。安装成功不单独证明数据完整；启动异常、数据变化或 ANR 时停止相关步骤并保留原状态。
- [ ] **P04 场景与取证**：仅用已获准公开/原创 QA 素材、测试地址和现有安全场景入口；不用真实账号或私人媒体制造状态，不写数据库造任务，不添加测试功能。缺少受控复现场景即记 BLOCKED/NOT_RUN。截图、语义树和日志不得暴露私人标题、URL 参数、Cookie、凭据、路径或完整响应。
- [ ] **P05 配置记录**：每条记录 API、实际可用窗口 dp/Insets、方向、字号、语言、主题、真实/模拟和场景 ID。100% 为对照，200% 为重点；代表性 Compact/Medium/Expanded、窄宽、低高度、横屏和键盘覆盖，无需完整笛卡尔积。只恢复明确由本次测试改变的设置。

## A 三处输入清除

依据：原始 01 §6 C-02 / §7、04 T-06～08；`INPUT_CLEAR_20261013.md`、`NEW_TAB_SEARCH_INPUT_20261013.md`。

- [ ] **A01 Browser 地址草稿**：输入普通文字、空白、错误地址后点 Clear；仅草稿清空并保持编辑态，当前网页/标签/历史不变；不导航、不扫描、不发保存命令。重新输入后现有 Go 与 IME 提交仍按原策略工作。
- [ ] **A02 新标签草稿**：Auto、Web address、Video keywords 各测一次；Clear 仅清文字与错误，保留解释模式，不打开网址、不传递查询。正常显式提交仍分别打开准入地址或转到 Discovery 草稿；搜索需要用户按现有 Search。
- [ ] **A03 Discovery 查询草稿**：清除只变更 query；其余筛选草稿、已应用 profile、已物化结果、页状态及历史不被清空，不自动 Search。接受的新编辑优先于等待中的 Browser 查询草稿，不被晚到旧交接覆盖。
- [ ] **A04 空值与防重**：三处空字段 Clear 禁用、纯空白允许清除；连续点按、已消费 claim、同值编辑和 A→B→A 后旧操作均不能清掉更新后的草稿或重复触发副作用。精确过期回调用现有自动化入口验证；肉眼连点不能代替 claim 时序证据。
- [ ] **A05 失效与占用**：切标签后切回、页面离开返回、暂停恢复、导航/地址同步、保存检查 revision 变化、页面销毁后，旧 Clear 不生效。Browser 的扫描、弹窗、全屏、picker、设置/收藏/同意和保存检查器占用，以及 Discovery 的不可见、未就绪、存储异常、busy、profile/目标不匹配和本地/全局 modal 占用，均遵守原 gate。
- [ ] **A06 可访问性**：三处当前语言的可见 Clear 文案、字段专属语义、正确 disabled 状态；200% 字号和键盘开启时可达，实际触控矩形至少 48×48dp，无错误聚焦/重复提交。

## B 认证等待任务返回浏览器

依据：原始 05 §8、03 UI-R13、04 T-14/T-16；`AUTHENTICATION_BROWSER_NAVIGATION_20261013.md`。

- [ ] **B01 正确入口**：DIRECT/STREAM 的 WAITING_USER/AUTHENTICATION，在紧凑任务卡与宽屏选中详情均显示准确“返回浏览器”及手动打开原网页登录的说明；不把其他等待原因、RECORD 或失败状态误呈现为可自动登录。
- [ ] **B02 唯一当前任务**：点击只导航到当前 Browser 路由；任务 ID/revision/state/wait reason/method/error 必须仍匹配唯一当前行。换选任务、任务修订/终态、重复行、缓存 revision 不一致和已消费 claim 拒绝旧操作。
- [ ] **B03 生命周期与占用**：重复点击至多接受一次；离页、后台、失焦、重建/销毁和返回后旧命令无效。初始化、pending command/media/expiry、扫描/选择/stream/native/recording/modal 占用保持禁用或点击时拒绝，不依赖过期的 enabled 外观。
- [ ] **B04 冷恢复边界**：仅在现有安全恢复场景可测时，分别覆盖“无 coordinator cache 但当前发布行与 gate 合法”和“存在但不匹配的 cache”；前者最多允许导航，不能据此称来源已验证或任务可恢复。不能为造场景删除用户数据。
- [ ] **B05 无隐式业务**：无 URL 推断/自动打开原站、自动登录、凭据请求、自动扫描、resume/cancel/提交或任务选择变更。返回后保留手动任务指导；导航成功不能记为认证成功或原始 OpenOriginForLogin 全流程完成。

## C 中文 英文与跟随系统

依据：原始 01 §7、03 §7、04 T-05～07；`SAVE_DISPLAY_LOCALE_20261013.md`，以及 m11a 的 `ORDINARY_DESTINATION_FIXED_COPY`、`DESTINATION_TYPED_DISPLAY`、`CANDIDATE_PERMISSION_DISPLAY` 三份 20261013 报告。

- [ ] **C01 locale 所有者**：在 API31/32 分别覆盖中文→英文→System、系统语言与 App 语言不同、Activity 重建及重启；显示与语义取当前 Compose resource locale，不沿用进程默认值或旧缓存。API33+ 另测现有平台每应用语言路径；不把其通过外推到 API31/32。
- [ ] **C02 Save 显示**：验证输出元数据、录制有效时长、候选元数据、HLS 数量/带宽/预算文案、音频结果/停止原因；数量、时间、KiB/MiB/GiB 和复数随当前 locale，未知数据仍未知。媒体成功与发现历史的失败/过期/缺失结果分开，切语言不重提任务。
- [ ] **C03 目的地固定与类型消息**：Settings、选择/notice Dialog、任务目的地历史三处切语言；覆盖四种偏好、26 个 typed notice 的现有可构造场景、folder/album 名称及六类 copy-state 文案。保留状态优先级：未选额外复制→内部尚未验证→已准备未开始→已验证成功→失败/取消→处理中或不明；缺场景逐项 NOT_RUN。
- [ ] **C04 目的地语义不变**：内部 App Library 先验证；相册/文件夹为额外副本；失败/取消保留原件；不明复制不报完成；改/忘记文件夹不宣称撤销旧系统授权，也不改已接受任务。用户文件名、源文、任务 ID 不被翻译或重新解析。
- [ ] **C05 保存候选编号**：多视频/多来源受控样本中，完整句子在当前 locale 生成；保留 video 编号 clamp、原 source-array 序号及过滤导致的间隙。切语言不改候选 opaque identity、URL、HLS 升级 DTO、选择或有效性 gate。
- [ ] **C06 网站权限拒绝**：受控网页触发现有 camera/microphone 与 geolocation 拒绝；拒绝先于 notice，授权仍被拒绝。通知保持 typed 值到当前 Browser Composition 才本地化，已有 notice 切语言也正确；仅已脱敏 origin 原样展示，unknown-origin 用资源。不增加授权、自动重试或录制同意流程。
- [ ] **C07 重建与长文**：上述画面 200% 英文/中文、键盘和窄窗可读；TalkBack 使用对应当前语言且不泄露私人内容。语言、主题、窗口重组不自动搜索、保存或恢复；残余未迁移文字按实际缺口记录，不宣称全 App 已双语。

## D 四主导航与 200% 字号

依据：原始 01 §2/3/7、04 T-01/02/06/07；`SHELL_VISIBLE_LABELS_20261013.md`。

- [ ] **D01 四项名称**：发现、任务、录制、媒体库在 100%/200% 都保留可见文字，设置为辅助入口；不出现第五底栏、不因字号隐藏名称、不缩小字体或截断关键标签。逐项验证选中、disabled、点击和 Back 的实际路由。
- [ ] **D02 极窄宽度**：以扣除实际水平 Insets 后的内容宽度测 216dp 边界两侧及正常窄窗；四个 48dp 目标加三段 8dp 间隔不足时应走现有 Rail 回退。实际测量触控矩形与可达性，不能只凭源码 minimumSize 判通过。
- [ ] **D03 低高度与横屏**：200% 下测低高度、横屏、分屏及键盘显示；Rail 和顶部/底部有限滚动区域不崩溃、无双重 Insets。最后一项可滚动到达并点击，设置仍可达。低高度不要求四项同时完整露出，但不得永久隐藏任何主入口。
- [ ] **D04 状态与无障碍**：窗口/方向转换保留根选中、合理轻量滚动位置和业务 ID；不创建重复任务。TalkBack/键盘焦点、选中/禁用语义正确，无装饰图标重复播报；物理可视区小于 48dp 的极端配置明确记边界，不伪称完全可用。

## E Discovery 内嵌与浮层筛选

依据：原始 01 §3/5、05 §5/6；`DISCOVERY_FILTER_PANEL_ROLE_20261013.md`。

- [ ] **E01 表面角色**：实际 sidebar 使用 INLINE 的 surface/cardCorner；实际浮层使用 FLOATING 的 elevated/sheetCorner；沿用原 spacingLarge 和内部控件形状。当前浮层为 Dialog，不能当 BottomSheet 已实现或已验收。
- [ ] **E02 单份内容迁移**：打开面板，填写未提交 source-editor/filter/query 草稿并滚至中部；宽→窄→宽、方向/字号变化时观察同一可移动内容。草稿、claims、筛选和 scroll 保留，无双实例、重复回调、重置或自动 Search；安全 gate 原本要求失效的操作仍应失效。
- [ ] **E03 关闭与所有权**：系统 Back、dismiss、离开/返回页、背景恢复后保持现有关闭和 query-clear 生命周期规则；shown-result blocking 与页面可见性门不退化，不提交未确认来源草稿。
- [ ] **E04 200% 可用性**：中文/长英文、键盘、低高度 Dialog、窄/宽转换时，筛选/清除/关闭与现有恢复操作可达；无嵌套无限高度滚动异常、关键裁切或丢失滚动位置。对应 empty/loading/waiting/error 正文不误报“无结果”，已有结果不因新工作/失败消失。

## F 主题角色与覆盖值

依据：原始 01 §5、03 §3、08 §5、04 T-03/04/07；`SEMANTIC_THEME_TOKENS_20261013.md`、`ESV_UI_TOKEN_AND_COMPONENT_MAP.md`。

- [ ] **F01 默认与跟随系统**：同一公开场景测 Light/Dark/System，surface 与 elevated、control/card/sheet 圆角、section/title 层级按实际 Token 显示；默认 Light primary 保留已说明的 #087582，不按概念初值 #087F8C 强行判错。
- [ ] **F02 非默认覆盖**：仅用已有获准主题/测试入口验证不同 surface/elevated、card 13dp、sheet 21dp、section 23sp 等覆盖可经同一快照生效；如无运行时入口则此项 NOT_RUN，不新建主题机制。检查 200% 使用逻辑字号，无二次缩放；48dp 触控安全下限不可被皮肤缩小。
- [ ] **F03 真实使用点**：检查 Discovery 卡片/筛选、任务卡/详情、Library、Editor、录制片段、Provider 和 SaveSelectionPanel 的相应角色；保存选择 INLINE 为 surface/card/16dp 默认 padding，FLOATING 为 elevated/sheet/32dp 默认 padding，并能响应覆盖。控件内层形状及按钮自身 corner 不被一律改成 sheet。
- [ ] **F04 切换连续性**：快速换主题、重建和打开面板时无混代显示、陈旧资源覆盖或业务实例重复；保留选择、草稿、scroll 和现有任务。未接入资源/lease 场景单列 NOT_RUN，不能以本次纯 Token 切换证明完整图片资源生命周期。
- [ ] **F05 可读状态**：实际测普通正文对比度目标 4.5:1，检查错误/禁用/焦点等当前实现状态及非纯颜色表达。新增 elevated/card/sheet 等部分通过不代表 success/warning/selected/loading/scrim/system-bar 或全组件状态 Token 已完整。

## 记录与签收限制

每例填写：`ID / commit 与 APK hash / REAL 或 DEMO / 场景 / 设备 API / 窗口 dp 与 Insets / 语言 / 主题 / 字号 / 操作 / 实际结果 / PASS、FAIL、NOT_RUN 或 BLOCKED / 脱敏证据 / 缺陷 ID / 执行者及时间`。一个分支缺证据，不得把整条记为无条件 PASS；模拟界面不能关闭真实业务 Gate。

- **历史 OPPO 问题仍开放**：编辑 `STORAGE_UNAVAILABLE` 与分享 `INITIALIZATION_FAILED` 没有被这些 UI/locale/主题源码测试修复证明。模拟器后续通过也不能自动关闭 OPPO 问题；ANR 和同进程二次录制的历史未闭环项保持各自状态。
- **Windows 旧问题未关闭**：code8 的符号链接 fixture 创建曾失败，支持范围重跑排除了该方法；该环境 setup 为 FAILED、该 guard assertions 为 NOT_RUN。云端或其他主机通过不改写此结论。原 Windows 最终签名设备报告未在该云快照取到，不把其路径写为已核验附件。
- **无 PPT 视觉验收结论**：原始 00～08 Markdown 可读，07 是 34 页需求追踪矩阵；PPT 二进制/图像未取得或查看。本清单未做截图、像素比较或完整 UI-E 签收。
- **本轮停止条件**：交付这份待执行清单即完成；不启动构建、模拟器/手机、登录、分享发送或任何新功能。后续按实际缺陷分级，P0/P1 未清零不得签收对应范围。

## 来源索引

原始依据：`docs/ui/` 下 00～08 共九份 Markdown，重点为 01、03、04、05、06、07、08。以上报告路径相对于 `仓库根目录`：UI 报告在 `docs/ui/implementation/`，认证与目的地/候选/权限报告在 `docs/m11a/`。历史证据边界另见 `docs/ui/implementation/CODE7_DEVICE_DEFECTS_20261012.md`、`CODE8_INITIALIZATION_INVESTIGATION_20261012.md` 和 `docs/m11a/PROJECT_COMPLETION_EVIDENCE_2026-10-13.md`。后者包含早期快照状态；最新独立 slice 报告用于本清单的类型消息及角色覆盖，不用早期“尚未迁移”文字覆盖后续实现。
