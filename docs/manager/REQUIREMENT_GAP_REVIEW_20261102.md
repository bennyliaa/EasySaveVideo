# EasySaveVideo 需求差距核查（2026-11-02）

基线：`main` @ `2ab2a7a`。依据：`docs/requirement and design/01_Product_Requirements.md`（PR-01～PR-25）、`03_Development_Milestones_update1.md`、AGENTS.md 最新用户决定、`docs/ui/implementation/ORIGINAL_REQUIREMENTS_REMAINING_QUEUE_20261014.md`、`ESV_UI_FEATURE_INTEGRATION_LEDGER.md`、`docs/vault/CURRENT_IMPLEMENTATION_STATUS.md`、`docs/m11a/RELEASE_BLOCKERS.md`、10-17～10-19 discovery/candidate 记录，以及本次对源码开关和调用链的直接核对。测试基线见 `EasySaveVideo test-workspace/baseline-20261102/BASELINE_REPORT.md`（7122 tests，229 项均为 Windows 宿主文件系统差异）。

本文是管理用差距清单，不是验收报告，也不新增产品范围或授权。

> **2026-11-02 设备验收更新（emulator-5556 / DeviceA12 / API31，安装归档签名 code12，保留数据升级 code2→12）**：浏览→检测→① Direct→验证 MP4→预览播放→媒体库 **PASS**（CC-BY 样本 Big Buck Bunny 10 s MP4）。下表 PR-03/09/10/16/17 的 D 列据此更新。更正：`docs/media-close-hotfix/` 早已记录该模拟器上一次普通 Direct 下载成功发布，§2 第 2 条原“没有任何成功保存”表述不准确。新阻塞：本机 Debug 证书 `5e841a04…` ≠ 设备证书 `18e3ef65…`，当前 main 不能保留数据覆盖安装，需原签名材料或由持有者签名。新缺陷：英文界面下预览页/媒体库部分文案仍为中文；保存名称为 `Video <UUID>`。

## 1. 状态分级

| 级别 | 含义 |
|---|---|
| S 源码 | 代码存在 |
| W 接入 | 已接入 App 组合根/UI 调用链 |
| E 启用 | Release 默认可被用户实际使用（非 Debug、非 false 开关、非 UNCONFIGURED） |
| H 宿主 | JVM/宿主测试覆盖（当前 Windows 基线中通过，或失败已归因为平台差异） |
| D 设备 | Android 设备/模拟器上有成功证据 |

`✓` 达到；`◐` 部分；`✗` 未达到；`—` 不适用。

## 2. 总体判断

1. **源码与接入基本覆盖 M1～M6 范围**：浏览器、发现、①②③、编辑、媒体库、设置、权益抽象均有源码并接入 UI。
2. **核心 MVP 闭环（M4：Search→Validation→Grid→WebView→①②③→MP4）在设备上一次都未成功**。仓库内设备证据只有：code3 模拟器冒烟（零来源搜索如实无结果、本地 HTML 无视频提示、网络错误提示、媒体库缩略图显示）、code4 模拟器 UI 路由/设置持久化、code12 手机 Commons 真实搜索 3 次均在 TCP/TLS 阶段失败（根因未知）。**没有任何成功保存、录制、编辑输出或播放的设备记录。**
3. **Release 默认几乎没有可用的发现来源**：Commons 仅在配置联系方式时启用（Release 为空 → 关闭），SearXNG 需用户配置实例，签名声明式 Provider 未接入 App，远程仓库根缺失。
4. **刻意关闭的能力**：Vault（`AndroidVaultRuntimeEntry.ENABLED=false`）、MOV/WebM 基础转换（`movAdmissionEnabled=false`，WebM 公共准入 false）、真实商店（`UNCONFIGURED`）。
5. 交付主风险从“写代码”转为“**设备验收与外部决策**”。当前设备操作全部暂停，这是最大的进度瓶颈。

## 3. 逐项矩阵

| ID | 需求 | S | W | E | H | D | 主要差距 / 证据 |
|---|---|---|---|---|---|---|---|
| PR-01 | Android 先行、固定包名、干净工程（API31） | ✓ | ✓ | ✓ | ✓ | ◐ | 模拟器 API31 安装过 code3/code4；手机 OPPO 已交还；平板/多尺寸未验收 |
| PR-02 | 平台无关 Specification、Core 无平台类型 | ✓ | ✓ | — | ✓ | — | 边界检查在；iOS 属 M12 |
| PR-03 | 永久地址栏、导航、多标签、零 Provider 可用 | ✓ | ✓ | ✓ | ✓ | ◐ ✓11-02 新标签+公网 HTTPS 页面 | 模拟器：本地 HTML 加载、标签开关、冷启动恢复通过；真实网站浏览/全屏/会话未测 |
| PR-04 | 关键词发现、聚合、过滤去重、分页卡片 | ✓ | ✓ | ✗ | ✓ | ✗ | **须“至少一个通过审计的真实来源跑通”未达成**：Release 无来源；code12 手机 Commons TLS 失败；卡片缩略图走 Coil core + ESV 匿名适配器，设备未验 |
| PR-05 | Local-first 有限候选、前台补充、后台停止 | ✓ | ✓ | ◐ | ✓ | ✗ | 依赖真实来源；生命周期/功耗未在设备测 |
| PR-06 | Provider Progress、Seen History、四策略 | ✓ | ✓ | ◐ | ✓ | ✗ | `Settings.kt`/`SeenHistory.kt`/设置页已接；原生 SAVED/PLAYED 身份 UNKNOWN（M11-A 阻塞清单） |
| PR-07 | Provider 零硬依赖、单独启停、隔离 | ✓ | ✓ | ✓ | ✓ | ◐ | 零来源启动/浏览在模拟器通过；远程策略未接 |
| PR-08 | 签名声明式 Package/Runtime/Repository/回滚 | ◐ | ◐ | ✗ | ✓ | ✗ | commons-provider 有签名 JSON/HTTP 规则、仓库、校验、健康/隔离；App 只接入 Commons 维护与管理 UI，**通用签名规则 Provider 未接入 App**，生产仓库根/端点缺失，解释器门禁关闭 |
| PR-09 | 用户触发检测、候选/清晰度选择 | ✓ | ✓ | ✓ | ✓ | ✓ 11-02 MP4 轻量+完整检测 | 模拟器“无可检测视频”提示正确；真实媒体检测未测 |
| PR-10 | ① Direct、流式、MP4 验证 | ✓ | ✓ | ◐ | ✓ | ◐ 11-02 MP4 PASS；MOV/WebM 未测 | MP4 直存已接；MOV/WebM 基础转换源码到位但 `movAdmissionEnabled=false`、WebM 公共准入 false、Opus 音频未完成；**无设备成功保存** |
| PR-11 | ② Stream（HLS、remux） | ✓ | ✓ | ◐ | ✓ | ✗ | 静态 HLS 受限 profile；Media3 本地处理未在设备验证 |
| PR-12 | SaveCoordinator ①→②→③、可见可控 | ◐ | ◐ | ◐ | ✓ | ✗ | Direct→Stream 显式建议已实现；Stream→Record 交接 UNKNOWN/GATE |
| PR-13 | ③ 整屏录制、系统授权、内部音频 | ✓ | ✓ | ◐ | ✓ | ✗ | 持续 BASIC 录制已集成；音频/像素/OEM 生命周期 NOT_RUN；需 `RECORD_SAVE` |
| PR-14 | 高级录制：框选、暂停/继续、片段拼接 | ✓ | ✓ | ◐ | ✓ | ✗ | 源码/UI 在；Tools 权益门控；设备 NOT_RUN |
| PR-15 | 二次裁剪、预览、压缩、非破坏 | ✓ | ✓ | ◐ | ✓ | ✗ | Media3 编辑引擎在；编码/输出/播放 NOT_RUN |
| PR-16 | 任务中心、①② 后台尽力继续 | ✓ | ✓ | ✓ | ✓ | ◐ 11-02 终态显示；后台未测 | Direct 1/2 并发已实现；后台/恢复未在设备测 |
| PR-17 | 媒体库、文件夹、导出/分享 | ✓ | ✓ | ✓ | ✓ | ◐ 11-02 列表+预览播放 | 模拟器：列表/缩略图/旋转/冷启动通过；播放、重命名、移动、删除、SAF/MediaStore 导出、分享未测 |
| PR-18 | 免费 Vault（密码/生物识别/加密/隐私） | ◐ | ◐ | ✗ | ✓ | ✗ | 运行时/UI/导入导出/播放/缩略图源码已接但入口 `ENABLED=false`；密钥丢失/账本损坏/外来安装/不可读存储修复未实现；新 setup-completion 状态机碎片未恢复；Keystore/SQLite/像素保护全部待平台验收 |
| PR-19 | 权益抽象、Fake（仅 Debug）、真实支付最后 | ✓ | ✓ | ◐ | ✓ | — | FeatureGate/只读权益说明页在；商店 `UNCONFIGURED`；商业/渠道/SKU 决策待定 |
| PR-20 | 系统设置（网格/分页/重复策略/保存/隐私/浏览器） | ✓ | ✓ | ✓ | ✓ | ◐ | code4：深色/中英文/重启持久化通过；其余类别设备未测 |
| PR-21 | Release 仅必要 ERROR、禁止字段零泄漏 | ✓ | ✓ | ✓ | ✓ | ✗ | 有界枚举诊断 + 导出 UI；Release 泄漏/限额设备未验 |
| PR-22 | 故障隔离、自恢复、原子提交 | ✓ | ✓ | ✓ | ◐ | ✗ | 大量宿主故障测试；229 项 Windows 失败集中于原子提交/文件身份路径，需 Linux/设备复核 |
| PR-23 | M0 技术/许可证 Gate | ◐ | — | — | — | — | 精确依赖准入持续执行；**原 M0 报告（Scrapling GO/NO-GO、10～20 样本指标）不在当前仓库**，早期历史未找回 |
| PR-24 | Automation/TaskQueue 接口预留 | ◐ | ✗ | — | — | — | 仅 `core-model/Operations.kt` 有领域预留；按规格属后续 |
| PR-25 | 历史、收藏、无痕、站点权限/Session | ✓ | ✓ | ◐ | ✓ | ✗ | 历史/书签实现；WebKit 1.17.1 无痕隔离与私密保存①②③已接；三项私密窗口平台门禁、分区隔离/异常退出清理未验；站点相机/麦克风/定位仍一律拒绝（非完整权限管理器） |

## 4. 差距分类与待办（按交付影响排序）

### A. 需用户决定/授权（阻塞，不能由开发自行解决）

| # | 事项 | 影响需求 | 最小决定 |
|---|---|---|---|
| A1 | 恢复设备测试（哪台设备、保留数据、签名兼容、递增 versionCode） | 全部 D 列 | 指定设备（模拟器 emulator-5556 或新手机）并授权打 Debug 包/安装 |
| A2 | 生产发现来源 | PR-04/05/06/08 | 选定 Release 来源（Commons 联系方式写入 Release，或指定 SearXNG 实例），以及使用条款审查 |
| A3 | 商业/渠道方向 | PR-19、M10/M11 | 市场、渠道、SKU/价格、权益组合 |
| A4 | Vault 安全设计确认 | PR-18 | 是否先补密钥丢失/账本损坏等修复，再进入平台验收 |
| A5 | 录制相关产品选择 | PR-12/13 | Stream→Record 交接方式；API31/32 截屏快照风险已接受（10-14 记录） |

### B. 可直接开发（无外部门禁）

| # | 事项 | 影响需求 | 说明 |
|---|---|---|---|
| B1 | Commons 连接预算诊断落地与 TLS 失败分析 | PR-04 | code12 设备失败根因未知；诊断归因修正已设计，需完成并在设备复测 |
| B2 | WebM 音频（Opus→AAC）与输出验证 | PR-10 | 当前带音频的 WebM 被整体拒绝 |
| B3 | Vault 修复路径（设备密钥丢失、账本损坏、外来安装、不可读存储） | PR-18 | 保持 `ENABLED=false` |
| B4 | 签名声明式 Provider 接入 App（门禁默认关闭） | PR-08 | 解释器启用仍需单独门禁 |
| B5 | UI-E：无障碍/200% 字体/触控目标/对比度源码侧整改 | UI 验收 | 设备验收仍需 A1 |
| B6 | 测试侧平台前提标注（Windows 上 229 项改为 Assume/SKIP 平台），或接入 Linux CI | PR-22 | 只改测试；CI 需确认 runner/成本 |
| B7 | Gradle 10 弃用项（`app/build.gradle.kts` `registering` 委托） | 构建 | 小改动 |

### C. 需设备验收（A1 授权后执行，按 MVP 闭环优先）

1. 浏览→检测→① Direct 保存自有测试 MP4→媒体库播放（PR-03/09/10/17）
2. ② 静态 HLS 自有样本→MP4（PR-11）
3. ③ 整屏录制开始/暂停/继续/停止→MP4（PR-13/14）
4. 真实来源搜索→卡片→打开原网页（PR-04/05/06，依赖 A2/B1）
5. 编辑裁剪/压缩/拼接输出（PR-15）
6. 导出/分享、SAF/MediaStore（PR-17）
7. 无痕隔离/清理与私密保存（PR-25）
8. Vault 平台验收（PR-18，依赖 A4/B3）

每项验收须使用自有/合法测试素材，记录设备、版本、签名、步骤、结果和保留数据，不以宿主测试或模拟成功替代。

### D. 历史缺口（不阻塞当前开发，但不得冒称已恢复）

- discovery-session-report（3,034 文件）与 media-UI（3,049 文件）完整快照未找回。
- Vault setup-completion 新状态机碎片（`docs/consolidation/20261101/candidates/pre-T0647-fragments/`）。
- 原 M0 审计/Scrapling 可行性报告与 3907618 之前历史。

## 5. 建议的下一迭代（Manager 视角）

1. **向用户申请 A1（设备）与 A2（来源）两项决定**：这两项解锁 MVP 闭环的全部设备验收，是交付关键路径。
2. 在等待期间并行推进 B1（Commons 网络诊断）、B2（WebM 音频）、B6（测试平台标注/CI），均不需要外部授权。
3. A1 获批后按 §4.C 顺序执行设备验收，每项产出可复核的验收记录，并据结果回填本矩阵 D 列。
