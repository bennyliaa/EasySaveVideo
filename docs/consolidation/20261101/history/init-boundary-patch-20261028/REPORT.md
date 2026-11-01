# ESV 初始化最小诊断增量

后续兼容性收尾已完成，最终状态见 `COMPATIBILITY_REVIEW.md`：补回 public Context 单参数构造器，17 项测试重编译通过，最终离线 Android 编译成功（52 秒）；本文下方 2m46s 为收尾前那次编译结果。

## 范围与来源

仅修改 `esv-code12-diagnostic-build` 隔离副本。修改前，3 个既有生产文件均与上一轮审查哈希一致，原文件保存在 `backup/`。原源码分支 `backup/cloud-handoff-20261013` 与恢复分支 `recovery/lzf-code12-diagnostic-20261025-339b2f76` 本轮只读检查均干净。此处是冻结 code12 诊断副本，不代表已恢复最新 code13。

## 修改（5 个文件）

- `AndroidSaveRuntime.kt`：将存储阶段观察接入同一个 `failureDiagnostics`；延迟 install lambda 内调用本地诊断边界。原发布赋值、ready 设置和 Handoff 调用顺序保持不变。
- `SaveRuntimeFailureDiagnostics.kt`：增加固定存储枚举与固定 INSTALL 上下文；原子计数器仍为同一实例共享最多 8 条，阶段切换不重置预算。install 捕获原 Exception，尝试分类输出后原样抛出；诊断访问或 sink 抛出 Throwable 也不能替换原异常。
- `storage/AndroidSaveStorage.kt`：只在现有目录、owner fence、lease、helper、protocol、library、pending delete、snapshot 和实际 writableDatabase 获取处包裹阶段观察；无提前开库、构造重试或额外文件操作。构造成功后关闭观察，后续数据库操作不会改变初始化阶段。
- 新增 `storage/SaveStorageInitializationProbe.kt`：固定枚举观察器，成功嵌套恢复父阶段，失败保留最内层阶段，后续清理不能覆盖；观察器失败不影响原操作。
- 新增 `SaveInitializationBoundaryTest.kt`：8 项纯 JVM 回归。

没有修改 MediaTaskActivityHandoff。Handler 投递 false 仍释放 owner，不安装、不重试、不输出新诊断；本轮仅用测试固定这一现状。没有改变 notices、存储恢复、firstProcessLifetime、权限、安全校验或签名逻辑。日志只含固定阶段/类别/布尔值，未新增异常消息、堆栈、路径、URL、任意类名输出。

## 本轮验证

用已有 Kotlin 2.2.10 编译器、JDK、JUnit 4.13.2 与已有缓存 JAR，从修改后的源码重新编译，输出至新目录 `classes-new/`，没有将旧编译产物放入测试 classpath。

最终 **17 项通过**：既有诊断 5 项、既有 Handoff 4 项、新增边界 8 项。新增覆盖：真实排队后 worker 已返回再抛异常、固定 INSTALL、不变的 ready、owner 恰好释放一次、post false 不安装/不重试/不新记日志、sink Exception/Error 不遮蔽原异常、worker/install 共用预算、嵌套数据库阶段失败、成功后关闭观察、观察器失败隔离。原始日志 `compile-current.log`、`junit-current.log`，逐源码/工具哈希 `TEST_RESULT.json`，可重跑脚本 `run-tests.ps1`。

首次独立编译缺少既有测试的 Activity 类型，补入对应源文件后重新编译通过；首次 Android 编译发现批量编辑误触 close 边界，已恢复该处原逻辑并重跑。此前失败日志保留，不能作为最终通过证据。

## 可恢复证据与限制

最终 Android 源码编译：使用既有 JDK/Gradle、`--offline --no-daemon --max-workers=1` 执行 `:app:compileDebugKotlin`，**BUILD SUCCESSFUL，退出 0，2m46s，32 tasks（2 executed/30 up-to-date）**。原 95 个运行时 artifact admission 验证通过。只出现现有未修改文件的编译警告，没有运行 APK/签名/设备任务。终态日志 `android-compile-final.log`；第一次沙箱缓存锁访问失败日志 `android-compile.log`，访问获准后的首次源码编译失败日志 `android-compile-approved.log` 均保留。第二次编译已覆盖最终 4 个生产文件，不只编译独立 helper。

`PATCH_MANIFEST.json` 记录修改前后 SHA256；`backup/` 为 3 个原文件，`after/` 为 5 个修改后文件；`changes.diff` 已在隔离源码执行 `git apply --reverse --check`，退出 0，只检查未回滚。应用/回滚前必须核对当前文件与清单哈希，避免覆盖新修改。

纯 JVM 测试验证生产诊断 helper/probe/Handoff，不能证明 Android Handler、SQLite 迁移、实际磁盘满或设备端初始化通过。未运行全量测试、lint、APK 构建或设备测试，未安装新工具/依赖，未上传/push，未操作签名、安全设置或审批。全程无直接/间接 ADB，Platform-Tools 审批仍独立等待。
