# code12 冻结源映射与最小脱敏诊断切片

2026-10-25 UTC。本轮纯电脑端，没有任何ADB/设备操作、Gradle/APK构建、安装、网络访问、上传或推送。未修改原工程、Git配置/历史或其他任务资源。

## 冻结基线已收敛到文件级

已装code12二进制归属沿用前轮设备端实算SHA-256 `b41ff7c237218922ab367d2743e2ae131e9185a7fa3ca54913dc60a49466fc02`。本轮没有访问手机。

依据code12交接包CANDIDATE_SOURCE_MANIFEST.json（其原始字节SHA-256 `76f33eadb01902dfe0024f1bb21501f66b48429037dff2650e87edcab69c6b32`）逐文件核对2892项：

- 原wfix：2860项相符，20项缺失、12项不同；未覆盖或修补原wfix。
- code12和slice09现存归档提供28项匹配文件；另4项为slice08回调计时源/测试/文档。
- 在已存在的本地Git提交 `29eb2a4f835786855a26e27319cd3f5a29a332f0` 的 `recovery/context-reconstruction-20261017/slices/discovery-latency-slice08-20261019/` 找到slice08增量归档；仅git cat-file读取本地blob，没有fetch。
- 用所有匹配字节在任务目录建立 `esv-code12-frozen-baseline/source`，2892/2892项写入后再次hash相符，missing=[]。没有把任何一个增量包单独当作完整源码。

源映射、归档hash和缺项结果见 `esv-code12-frozen-baseline/BASELINE_MAPPING.json`；原始清单保留在同目录。重建脚本 `prepare_code12_baseline.py` 对已存在目标拒绝覆盖，所有文件均按目标清单hash选取。

**可信范围**：可用于审查的、与code12交接清单逐文件一致的完整冻结源码快照已在本机建立。不是最新开发树、code13恢复或独立源码到APK字节复现证明。SDK路径、忽略文件、离线依赖缓存/签名环境及Windows全量构建仍未验证，因此不称“已证明可构建”。没有复制原构建缓存或旧APK进隔离源树。

## Git / code13只读检查

原 `esv-source-12f97d43-20261009` HEAD仍为 `20fddc9c33b399ab75f8b8eee0f21e41e6f5b06f`，分支backup/cloud-handoff-20261013；本轮git status --porcelain=v1输出为空（包括未跟踪项检查），未重置或清理。该历史最新摘要为初始化诊断交接WIP，不代表code12完整源码。

`receive_slice07.py` 明确映射code10、code11、code12的提交/增量路径。已查看code12对应本地Git树；工程目录未发现名含code13的文件，discovery-live内小型README/handoff/receipt/recovery/source/delta记录的限定内容检索也没有code13匹配。**未找到code13恢复完成凭据**；不代表机器其他位置或其他任务不存在记录。没有搜索个人聊天数据或联网补取。

## 可恢复诊断补丁（尚未集成）

交付 `diagnostic-patch/save-diagnostic.patch` 和 `PATCH_MANIFEST.json`，仅两项生产源变更：

1. 对已验证的AndroidSaveRuntime.kt增加固定初始化阶段标记，在既有command异常catch中额外输出一条技术错误日志。原通知post、finally、ready赋值、返回/异常控制、预算、恢复/重试和权限逻辑保持原样；日志sink异常不会替换原有处理。
2. 新增纯Kotlin SaveRuntimeFailureDiagnostics：输出仅固定phase/category枚举和ready布尔值；不读取异常message/cause/stack/toString，不输出任意类名、URL、token、路径、SQL或个人内容。异常类别覆盖DATABASE、SAVE_FAILURE、SECURITY、IO、ARGUMENT、STATE、REJECTED、OTHER；类别依据固定身份/类型，不解析文本。SQLite/SaveFailure superclass检查最多16层。单个AndroidSaveRuntime所有者最多8条错误日志，原运行时为进程singleton；阶段切换不重置限额。不输出成功或轮询日志。

已有InitializationProbe继续用于编辑器/共享初始化诊断，未改动它的阶段、errno/原因链规则；新增切片针对原command统一吞异常的位置，不扩展已有诊断的敏感信息范围。没有增加初始化重试、改变录屏行为或制造成功状态。

隔离变更后的文件在 `diagnostic-patch/patch-source/`；冻结基线没有改动。生成脚本 `prepare_diagnostic_patch.py` 先校验源hash及锚点唯一性。第一次生成遇到同名清理调用两个位置而主动拒绝；后将锚点限定为初始化邻接语句，成功生成。失败尝试未改原源树。

## 本轮真实验证

- 使用已有缓存Kotlin **2.2.10**编译器及已有依赖，直接Java运行纯JVM测试；未新增依赖、联网或运行Gradle。编译进程上限512MB、2个处理器。
- 编译exit=0，测试exit=0；5组回归全部通过：8类异常分类及敏感message不泄漏；恶意Throwable字段不可读仍安全；全部阶段/ready输出；8条限额及阶段切换；16线程320次并发故障仅产8条固定日志。
- SQLiteException/SaveFailure身份分支使用测试专用类型stub，只证明分类/脱敏；stub不进生产补丁，不能冒充真实Android/SQLite行为测试。
- `git apply --check` 对冻结基线通过；`git apply --reverse --check` 对隔离变更文件通过。两者只检查、未修改冻结树。
- 冻结AndroidSaveRuntime.kt最后实算仍为原hash `3cdee98fd328e955b40758b0802f8f192b96387efb390ded4a0d90ddd6a5785f`。
- 证据：`diagnostic-patch/compile.log`、`test.log`、`TEST_RESULT.json`（包括工具及测试源hash）。运行入口 `run_diagnostic_tests.py`。

**未验证**：AndroidSaveRuntime整类/项目编译、全量原测试、lint、APK、真实日志输出、手机初始化异常根因与保存/取消链。测试只证明新增纯诊断分类与脱敏限额，不证明补丁已经在手机生效。

## 下一步

先审查该两文件切片及冻结基线的Windows构建条件，确认对应完整快照的编译/依赖验证后再决定是否构建诊断包；若后续允许保留数据升级，应保持签名和递增versionCode，届时仅采集固定技术错误事件。当前没有安装计划执行，也没有因未知异常去修数据库/清数据。

剩余阻碍：原始初始化异常类型/阶段仍未知；code13最新恢复状态无本地完成证据；完整Windows构建尚未测试。本轮无审批拒绝或不确定的设备写入结果。
