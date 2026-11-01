# PDYM20 code12 保存存储提示限定诊断

2026-10-25 UTC。本轮严格绑定授权 PDYM20，未操作其他设备。未安装/降级/卸载、清数据/缓存、修改权限/设置、访问个人媒体/保险箱/账户、操作 Cloudflare 或上传。没有修改 ESV 源码。使用 run-as 只读取固定保存目录和数据库文件的类型/属主/模式，未列目录内容、打开数据库或读取媒体。

## 结论

这条提示证明当前应用进程曾有一个控制命令抛出异常，不等价于“现在磁盘满”或“数据库损坏”。通知可手动清除且空白页观察期间未重新出现；这只证明通知不是每次绘制都重新触发，不能证明保存运行时已恢复，也不能排除启动初始化已失败。

初始异常类型/阶段没有现存证据，因此未能闭合到具体运行故障根因。不能据此执行数据库修复或给出针对根因的补丁。

## 实查证据

- 再次 get-state=device，型号 PDYM20；com.easysavevideo.app 仍 versionCode=12 / 0.9.0-ui-dev；进程 PID 23689。
- df -k：/data 与 /storage/emulated/0 所在卷 113,792,992 KiB，Available 97,782,724 KiB，使用率14%。约93.25 GiB可用，未见卷级磁盘满。该结果不排除应用配额、预留容量或特定I/O故障。
- 包权限：INTERNET、ACCESS_NETWORK_STATE、WAKE_LOCK 等已授予；RECORD_AUDIO未授予，与①保存无关，不应为此申请麦克风。当前包未申请传统外部存储权限。没有授予任何权限。
- `no_backup` 存在，模式771，属应用UID；`no_backup/save_m3_v1` 存在，模式700，属应用UID；`save_m3_v1.db` 为普通文件，模式660，属应用UID。未做写入探针，因此没有证明实际可写性、SQLite可打开性或SAF目标授权；这些不能从模式值推断。
- 两次仅限当前应用PID的1200行上限既存logcat检查，没有获得可用于归因的SQLite/Exception/ENOSPC/EACCES技术错误证据。未清日志、未保存原始消息/URL/路径/标题。
- 空白“新标签/浏览网页”界面仍显示原提示。仅点击该提示的“关闭提示”，没有改变设置或数据；即时检查为提示消失。2026-10-25T13:29:31Z再次观察仍未出现。
- 对空白页“手动检测当前页”做了一次点击探针，但事后核对该控件父节点 enabled=false；动作未执行。它不能用作 ready=true 或 ready=false 的证据。没有建立扫描/下载任务。
- 最终保持空白浏览器页，无验证码、媒体播放、保存任务或录屏操作。

## code12 对应证据及范围

已装APK SHA-256此前设备端核实为 `b41ff7c237218922ab367d2743e2ae131e9185a7fa3ca54913dc60a49466fc02`，完全匹配本地code12候选。

本轮读取该候选交接包 `code12-source-delta-on-slice09.tar.gz` 的 `CANDIDATE_SOURCE_MANIFEST.json`，以下 wfix 文件实算hash与清单一致：

| 文件（相对wfix） | SHA-256 |
|---|---|
| app/src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt | 3cdee98fd328e955b40758b0802f8f192b96387efb390ded4a0d90ddd6a5785f |
| app/src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt | d71ef49b7532aababee10973034b603c3c55ae4dfbbde4d87eb057daa1ec288b |
| app/src/main/kotlin/com/easysavevideo/app/save/SaveRuntimeNoticeResources.kt | 9e460cd8771e8578cc07516af6afc923ce3e5dd1e5fabe6a0a55137b52e76a6c |
| app/src/main/res/values-zh-rCN/save_runtime_notice.xml | d4a5300530e38cd7c875b18356c21fd5b67d626bba0cd134ef01d2122985a7f8 |

这建立了所查文件与已装候选交接记录的映射，不是独立可复现构建证明，也不表示wfix是完整最新源码/code13已恢复。未修改该副本或任何主项目。

## 代码归因

- AndroidSaveRuntime.kt:313：notice 为内存 mutableState，默认null。
- :1733附近 command：任意 action 抛出的 Exception 都被捕获，统一 post `SAVE_STORAGE_UNAVAILABLE`；没有保留阶段、异常类型或是否影响核心可用性的诊断。队列拒绝有独立提示，因此不是本提示的同一分支。
- :587 dismissNotice 仅设 notice=null，不重试、不修复、不改变ready。
- :346初始化也通过command执行。初始化过程覆盖存储构建、传输/HLS/协调器构建、恢复/清理、快照、目标运行时等。任一步抛出异常都可能落入同一泛化提示；不能把责任缩小到SQLite。
- :411只有完整安装初始化结果后ready=true；:1608刷新遇到!ready直接返回。因而初始失败可能持续阻断保存而不反复弹提示，清除通知后不复现不等于恢复。
- AndroidSaveStorage.kt:56左右：主保存目录为应用 `noBackupFilesDir/save_m3_v1`，数据库为该目录 `save_m3_v1.db`；初始直接落片并不因缺传统共享存储权限而必然失败。具体导出/选定SAF目标仍需另行授权检查，本轮没有访问其个人路径或内容。
- 初始目录构建/符号链接检查、失败所有者状态、数据库/目录同步等均可能抛异常；文件存在与属主正常不足以排除它们。
- BrowserScreen的空白页检测按钮由页面loadState等条件禁用；当前禁用不能证明保存存储初始化失败。

## 最小修复建议（未实施）

1. 先在对应源码隔离副本为初始化/控制命令加入小范围诊断：固定阶段枚举、仅异常类别枚举、ready状态、单调序号；绝不保存异常message、URL、标题、文件名、SQL参数、凭据或媒体信息。保留现有失败关闭行为。
2. 区分“初始化失败导致运行时不可用”和“已就绪运行时某条命令失败”的提示/状态；提示显示历史事件而不是未经证实的实时存储健康结论。不要简单自动重试所有命令，因为恢复/清理等具有副作用。
3. 对初始化阶段抛出、后台命令抛出、关闭提示不改变ready、敏感异常message不泄漏等作有意义的隔离纯单测，再考虑受控技术诊断构建。当前缺具体异常证据，未写一个仅复述catch行为的测试来冒充根因复现，也没有准备未经证实的根因补丁。

下一步需要确定对应完整源码及技术诊断途径；不建议先清数据、授外存权限、删数据库或重装。若无需新包的现有受限诊断入口可暴露ready/初始化阶段，可优先核查；本轮未使用隐藏入口或任意反射探针。

状态：限定检查完成；精确运行故障根因未知，因诊断信息缺失而停下。没有权限拒绝、无不确定的安装/数据修改结果。保存与取消仍未测试通过。
