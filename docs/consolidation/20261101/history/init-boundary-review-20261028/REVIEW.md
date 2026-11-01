# ESV 初始化与延迟 install 异常边界：只读结论

2026-10-28，本轮在原LZF环境检查成功，无额度阻塞、换模型或换环境。严格没有直接/间接ADB、设备/模拟器操作、Gradle、依赖下载、构建/签名、安全设置变更或上传。只创建本目录检查报告和小型JVM执行日志，未修改任何产品/测试源码或已有补丁。

## 当前状态与可用证据

- 原Git工作树`D:/CodexProjects/EasySaveVideo/esv-source-12f97d43-20261009`：branch `backup/cloud-handoff-20261013`，HEAD `20fddc9c33b399ab75f8b8eee0f21e41e6f5b06f`，git status无未提交项。
- 原恢复上传工作树：branch `recovery/lzf-code12-diagnostic-20261025-339b2f76`，HEAD `538110ab2e323e66516e992eb2d933ae1c7ba6ed`，git status无未提交项。未fetch/push。
- 隔离诊断源码本身无Git工作树；没有据此声称全目录无变化。已核对本次涉及文件：AndroidSaveRuntime仍为补丁后SHA `b9b117193dbb81eff1ccde5d5d9ccd7d80314121c5e52262158baab18cf82ddd`，helper仍为`5f99a063df003ca8b852e74f0247726b95a49b684cfcfeba0312af9fc88d0cc8`，批准后的verification-metadata仍为`1dbc2ad2d476bccf428b2df1c2a8c3d1f1e8ad621f22a324b81ebf99732c2200`。
- Handoff、其测试、AndroidSaveStorage、EditorInitialization四个文件均匹配冻结清单；具体hash见SOURCE_CHECK.json。没有覆盖用户或其他任务文件。

以下源码位置均相对`D:/CodexProjects/2026-10-22/task/esv-code12-diagnostic-build/`。

## 已明确的异常捕获边界

### 1. Storage构造被捕获，但子步骤只显示STORAGE

`app/src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt:346-352`设置helper初始阶段为STORAGE，随后在command工作线程构造AndroidSaveStorage；失败最终由`:1755-1762`捕获并输出限额脱敏错误和原通知。

`app/src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt`：

| 位置 | 真实子步骤/边界 |
|---|---|
| 121-123 | 保存目录符号链接/目录创建检查 |
| 124-127 | 失败所有者围栏检查、PrivateDirectoryLease取得 |
| 128 | 记录进程内目录生命周期；不是成功初始化标记 |
| 130-135 | helper对象构造、Catalog/PrivateCommitProtocol/普通媒体目录与库存储构造 |
| 137 | 有界pending-delete恢复，可能触发数据库访问及文件操作 |
| 140-145 | 启动原始任务快照；查询/解码/筛选 |
| 146-148、400-406 | 构造失败释放数据库/owner；保留资源释放不确定围栏 |
| 953-955、988 | SQLiteOpenHelper版本/配置/迁移；构造helper对象不等于立即打开数据库 |

**最小建议**：下一诊断切片只在真实已有步骤周围加固定子阶段观察，复用现有InitializationProbe思路和错误预算；数据库打开/迁移阶段应在原来真正打开的调用处观察。不要为了日志提前调用writableDatabase、增加写探针、重排恢复/清理、自动重试构造或重置生命周期集合。这些会改变资源和恢复语义。首个失败要在既有cleanup前保留诊断身份，cleanup仍走原路径，不读取/输出SQL、文件路径或异常message。

### 2. 主线程延迟install不在worker catch内

`AndroidSaveRuntime.kt:421-425`调用：

```kotlin
initializationActivity.enqueue({ install -> main.post(install) }) {
    // editor/segment/state assignments; segmentRuntime?.setForeground(...)
    // records/library/destinations assignment; ready = true
}
```

`MediaTaskActivityHandoff.kt:14-27`：enqueue同步调用dispatch并捕获dispatch当场抛出的Exception；被投递lambda在`:19`仅有`try { install() } finally { finish() }`。当Handler稍后执行时，enqueue和command worker都已返回，`:24`外层catch与AndroidSaveRuntime`:1755`都无法捕获该异步install异常。

**结论**：队列接受成功不代表安装成功；异步异常仍会传播到主线程执行方。handoff finally能释放初始化占用，但不能证明ready、安装成功或错误已记录。本结论是源码控制流事实，不是手机实际崩溃/存储故障归因。

**最小建议**：在AndroidSaveRuntime传入的install lambda内部加局部诊断边界，使用明确的固定INSTALL上下文，记录后重新抛出原Exception；保留handoff现有finally和原安装/ready顺序。不要把这个问题修成catch后吞异常并强设ready、自动重装对象、重复恢复或无依据回滚已发布状态。若希望后续改为可恢复失败，那是另一个需要明确发布/回收语义的行为切片。

当前helper`SaveRuntimeFailureDiagnostics.kt:26`使用`ready ? CONTROL : stage`格式，因此不能盲目在回调中调用同一接口就假设永远标成INSTALL；可在下一切片支持显式固定阶段参数，继续共用八条预算，避免异常发生在状态发布附近时被泛化成CONTROL。也应避免把“当前共享stage”当作所有后台命令的真实操作身份。

### 3. 非抛异常的投递拒绝目前不可观察

Handoff`:21`会在dispatch返回false时finish并返回false；AndroidSaveRuntime`:421`忽略enqueue返回值。原行为是ready不被设置，owner释放，但没有专门投递拒绝诊断。

**最小建议**：仅观察返回值并记录一个固定拒绝事件，不伪造底层异常，不调用install、不自动重试、不改变owner或ready语义；新事件继续共享限额。已有CONTROL_QUEUE_FULL是controls executor拒绝，与Handler.post返回false不是同一边界。

### 4. 可选组件有单独失败政策，不能统一升级为保存故障

`AndroidSaveRuntime.kt:402`的export构造失败转null；`:407-413`的segment构造失败转null；`:416-419`使用initializeEditor。`editor/EditorInitialization.kt:11-13`捕获存储/运行时失败并返回状态与probe结果，不重新抛出。这些路径不会进入command通用catch，部分是已有的可选降级设计。

最小诊断可增加可选组件固定阶段标记/读取已有结果；**不能**因编辑器或导出不可用就全局关闭DIRECT、改ready成功条件，或把不同组件异常都当成同一SQLite故障。

## 现有覆盖与本轮实际测试

本轮只复用已存在编译产物，直接用现有JDK/JUnit运行两个已阅读、无Android/ADB/进程启动依赖的纯JVM测试类，限制1个处理器、128MiB：

- MediaTaskActivityHandoffTest：4项通过。源码`:7`覆盖worker返回后主线程尚未安装仍保持活动；`:33`覆盖准备失败释放；`:41`覆盖队列拒绝；`:47`同时覆盖dispatcher抛异常及延迟install抛异常时owner最终释放。最后一项由测试自己捕获异常，不验证运行时诊断事件。
- SaveRuntimeFailureDiagnosticsJUnitTest：5项通过。覆盖固定类别/脱敏、恶意Throwable字段、阶段/ready格式、八条限额及并发预算；不验证真实Handler或SQLite构造链。

**本轮实际结果：OK (9 tests)，exit0。** 日志junit.log、运行记录TEST_RUN.json。没有重新编译Android源码；这不是新的全工程/设备通过结果。历史7100项、229失败及已完成lint均未重跑，不改其结论。

## 可直接实施的下一切片与补测

优先只做诊断边界修正，不猜测性修复存储行为：

1. 给实际storage构造子步骤增加固定、无文本数据的诊断阶段；保留首次失败和既有释放流程。
2. 给延迟install lambda添加“记录后原样rethrow”的局部边界，并明确Handler拒绝事件；不改MediaTaskActivityHandoff的占用释放合同。
3. 补纯JVM集成用例：先接受并保存队列回调、worker返回后再抛install异常，断言诊断恰一次、原异常仍传播、owner恰一次释放；投递false断言不安装/不重试；子阶段失败断言不增加打开DB或再次构造。继续验证日志sink异常不替换原异常、只输出固定枚举且总预算不变。

本轮未应用以上修改。无待处理执行阻塞；所有测试进程已结束。ADB/Platform-Tools及原cloud审批等待状态均未解除。
