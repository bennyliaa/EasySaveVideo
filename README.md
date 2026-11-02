# EasySaveVideo（ESV）

视频发现、浏览、保存（① Direct / ② Stream / ③ Record）与私密管理工具。Android 先行：`com.easysavevideo.app`，Android 12 / API31 起，手机与平板，Kotlin + Compose + SystemWebView。

- 正式开发目录 `D:/CodexProjects/EasySaveVideo`，本地只用 `main`；远端 `github.com/bennyliaa/EasySaveVideo`（private）。
- 执行边界以 [AGENTS.md](AGENTS.md) 为准；接手先读 [开发交接](docs/handover/ESV_开发交接_2026-11-01.md)。
- 当前状态：[需求差距核查](docs/manager/REQUIREMENT_GAP_REVIEW_20261102.md)、[设备验收记录](docs/manager/DEVICE_ACCEPTANCE_20261102.md)、[本机整理报告](docs/consolidation/20261101/REPORT.md)。

## 目录结构

### 构建入口（可改）

| 路径 | 说明 |
|---|---|
| `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties` | 模块清单、插件版本、精确离线仓库映射 |
| `gradle/` | Wrapper 与 `verification-metadata.xml`（严格依赖校验，勿放宽） |
| `gradlew`、`gradlew.bat` | Gradle Wrapper |
| `.gitattributes` | `* -text`：任何机器检出都保持提交字节 |
| `local.properties` | 本机 SDK 路径，Git 忽略，勿提交 |

### 产品源码（可改）

`app/` 是 Android 应用；其余 Gradle 模块统一放在 `modules/`，Gradle 项目名保持 `:core-model` 等不变（`settings.gradle.kts` 映射 `projectDir`）。

| 路径 | 职责 |
|---|---|
| `app/` | Android 组合根：SystemWebView 浏览器、UI、保存/录制/编辑/媒体库/Vault 平台适配、存储 |
| `modules/core-model/`、`modules/core-domain/` | 平台无关模型、命令、权益与状态机（不得引入 Android/Store/Provider 类型） |
| `modules/ui-theme/` | ESV 视觉默认值与快照适配（运行时依赖已授权的 SkinKit Contract/Default 1.0.0 二进制） |
| `modules/discovery/` | Provider 调度、候选验证、结果池、进度与 Seen History |
| `modules/commons-provider/`、`modules/searxng-provider/` | Wikimedia Commons 与 SearXNG 来源、签名维护仓库 |
| `modules/save/`、`modules/hls/` | 平台无关 Direct/Stream 协调与受限静态 HLS 解析 |
| `modules/recording/`、`modules/library/` | 录制生命周期/片段规则，普通媒体目录/租约 |
| `modules/verification-client/`、`modules/verification-local/` | 可选验证服务客户端与本地 JVM 解析 |
| `modules/nav-diagnostics/` | 仅 `-Pesv.navdiagnostics=true` 时纳入的 QA 载体，非生产依赖 |

### 非 Gradle 代码

| 路径 | 说明 |
|---|---|
| `services/verification-service/` | 独立 Python 验证服务（Android 不含其依赖） |
| `services/verification-fixtures/` | 独立夹具与调度契约（git subtree，前缀已改为此路径），未接入生产 |
| `scripts/` | 边界检查与工作区脚本；先读适用范围，勿为跑通而改门禁 |

2026-11-02 起模块从根目录移入 `modules/`、服务移入 `services/`。`docs/` 历史报告中的源码路径指旧布局。

### 依赖与许可（只按准入流程变更）

| 路径 | 说明 |
|---|---|
| `vendor/` | 精确离线 Maven 构件：`maven/`（SkinKit 授权构件）、`media3-maven/`、`webkit-maven/`、`coil-maven/`、`tink-maven/` |
| `third_party/` | `admitted-runtime.tsv`（95 项运行时准入）、各依赖许可证/NOTICE 与候选审查证据 |

### 文档（可增补）

| 路径 | 说明 |
|---|---|
| `docs/requirement and design/` | 原始规格 01～06 与 update1（架构 update_1 为 UTF-16LE 原文） |
| `docs/manager/` | 项目管理：差距核查、设备验收记录 |
| `docs/handover/` | 交接文档 |
| `docs/ui/` | UI 设计、实现切片与功能接入台账 |
| `docs/m0` … `docs/m11a` | 各里程碑/切片的实现与验收边界（例：[m3a Direct](docs/m3a/IMPLEMENTATION_AND_ACCEPTANCE.md)、[m3b-eof HLS](docs/m3b-eof/IMPLEMENTATION_AND_ACCEPTANCE.md)、[m4 Record](docs/m4/IMPLEMENTATION_AND_ACCEPTANCE.md)、[m6c 授权帧选区](docs/m6c/IMPLEMENTATION_AND_ACCEPTANCE.md)、[m7b 签名仓库](docs/m7b/IMPLEMENTATION_AND_ACCEPTANCE.md)、[m11a 发行阻塞](docs/m11a/RELEASE_BLOCKERS.md)） |
| `docs/discovery/`、`docs/vault/`、`docs/verification*/` 等 | 专题状态报告 |
| `docs/consolidation/` | 2026-11-01 归拢报告与覆盖清单 |

### 测试输入（在用，勿删）

| 路径 | 说明 |
|---|---|
| `artifacts/local-windows-20261009/` | 验证服务 Windows 测试读取的浏览器准入收据与冻结表 |
| `artifacts/test-fixtures/` | 合成测试夹具 |

### 已移除的历史材料

2026-11-02 起以下内容已从工作目录移除，均可从 Git 历史或外置归档恢复，路径清单与恢复方法见 [移除记录](docs/manager/REMOVED_BINARIES_20261102.md)：历史 APK/Base64/分卷（约 916 MB）、`recovery/`、`verification-transfer/`、`docs/**/evidence/` 历史运行日志、其余 `artifacts/` 验收记录、`docs/ui/source-history-20261011/`、UI 设计稿 PPT/ZIP、绑定历史基准提交的准入脚本。`docs/` 中 `.md` 报告若链接到这些文件，需先按移除记录恢复。

### 仓库之外

- 构建中间产物：`app/build/`、`modules/*/build/`、根 `.gradle/`、`.kotlin/`（Git 忽略，可随时删除）。
- 测试日志、临时文件与中间产物：`D:/CodexProjects/EasySaveVideo test-workspace/`。
- 永久恢复归档：`D:/CodexProjects/EasySaveVideo-Archive-20261101T132000Z`（勿清理）。

## 构建与测试（Windows，串行、离线、严格校验）

```powershell
$env:JAVA_HOME = 'D:\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'D:\Android\SDK'
$gradle = 'C:\Users\benny\.gradle\wrapper\dists\gradle-9.6.0-bin\42k10rwplmzkhuboz9kdazi7s\gradle-9.6.0\bin\gradle.bat'
& $gradle --offline --dependency-verification=strict --no-daemon --max-workers=1 --console=plain --continue `
  '-Dorg.gradle.jvmargs=-Xmx2048m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' `
  '-Pkotlin.compiler.execution.strategy=in-process' `
  :core-model:test :core-domain:test :ui-theme:test :discovery:test :commons-provider:test :save:test `
  :recording:test :library:test :hls:test :searxng-provider:test :verification-client:test :verification-local:test `
  :app:compileDebugKotlin :app:compileReleaseKotlin :app:testDebugUnitTest :app:runtimeInventory
```

Windows 宿主当前有 229 项已归因的平台失败（目录 fsync、`fileKey`、POSIX 属性、symlink、打开文件重命名），回归时只比较新增/消失的失败 ID。新克隆需 `core.longpaths=true`。Linux/macOS 可用 `scripts/gradle-workspace.sh`。

## 工作规则摘要

- 不强推、不改写历史、不删除远端分支；提交只暂存本任务文件。
- 日志不得包含 URL、标题、搜索词、Cookie、Token、文件名、凭据或异常消息。
- Vault 保持 `ENABLED = false`；Fake 权益仅 Debug；零 Provider 时浏览与保存仍可用。
- 新依赖、APK/AAB、设备操作、发布与支付均需单独授权。
- 用户代码与授权构件均未授予任何公开许可证。
