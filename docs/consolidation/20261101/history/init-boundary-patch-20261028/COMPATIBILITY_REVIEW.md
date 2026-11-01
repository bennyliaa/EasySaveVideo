# 诊断补丁收尾兼容性审查

## 最终状态

实际审查 5 文件 diff，发现并修复 1 处 JVM 构造器兼容性缺口：默认回调参数替代了原 `AndroidSaveStorage(Context)` 构造器。仅增加 `@JvmOverloads`，恢复原 public `(android.content.Context)` 签名；未改构造体或控制语义。`constructor-before.txt` 为缺失证据，`constructor-after.txt` 为修复后字节码；已对 javap 结果执行精确签名断言，成功。

本轮因这一确定缺陷重新运行必要验证，没有新增测试凑数：

- 修改后的纯 JVM 源码重新编译，17 项全部通过（既有诊断 5、既有 Handoff 4、本补丁边界 8）。`TEST_RESULT.json` 列出实际源码及已有工具 JAR 哈希，审查末尾逐项复核仍匹配。
- `:app:compileDebugKotlin --offline --no-daemon --max-workers=1` 成功，退出 0，52 秒；日志 `android-compile-compatibility.log`。未构建 APK。
- 重新生成 `PATCH_MANIFEST.json`、`after/` 与 `changes.diff`，反向应用只读检查退出 0。最终 5 文件逐项与 manifest 匹配。

## 审查结论与依据

| 重点 | 实际源码/测试结论 |
| --- | --- |
| 异常对象/传播 | install 与 probe 均 `throw original`；测试按 `===` 核对原对象。install 原 Exception 继续向外传播，未转换为成功、重试或替代异常。 |
| 嵌套阶段/预算 | probe 只设置固定枚举，不调用 failure/日志；内层失败后 failed 阻止清理覆盖，成功后恢复父阶段，构造完成后关闭观察。原 worker catch 消耗一次预算。真实 main.post 异步 install 由其局部边界记一次，不会回到已退出的 worker catch。 |
| 跨线程上限 | 原实例 AtomicInteger CAS 先领取名额再格式化，8 后返回 null；阶段或 ready 改变不重置。既有 16 线程测试共尝试 320 次，返回恰好 8 条；新测试确认 worker/install 共享此计数器。sink 失败也不返还名额，实际日志不会因重试突破上限。 |
| owner/ready | Handoff 源码未改，仍在 callback finally 调用 finish，AtomicReference 防重复释放。新测试在 worker 返回后执行失败回调，确认 owner 仅通知一次且 ready 不被改成成功。成功发布赋值顺序和 ready=true 位置不变，失败不回滚已发布字段。post false 原样不安装/不重试/不新增日志。 |
| 诊断失败 | 新 install 诊断内部与 probe observer 均隔离 Throwable；测试覆盖 sink Exception、AssertionError 与 observer 失败，不遮蔽原操作异常。原 worker logger 的 Exception-only 捕获未扩大，本轮不重新定义其既有 Error 语义。 |
| 隐私 | 新日志路径只复用固定 `save_error` 模板：固定 phase/category 枚举、ready 布尔值。分类内部仅比较允许的类标识，不输出类名，不读取 Throwable message/cause/stack/toString。既有 hostile Throwable 与带 URL/path/token 的测试均通过。 |

没有发现其他需要继续修改的确凿补丁缺陷。

## 保留的限制

- 这是冻结 code12 隔离诊断副本，未证明为最新 code13；没有合并主源码。
- 原异常仍可能导致应用崩溃，诊断补丁没有提供恢复或原子发布事务；ready 前已有字段赋值的原语义不变。Error 类原操作异常继续按原边界传播，没有新增对其分类记录的承诺。
- Handler 投递 false 仍可能没有可见诊断/ready，当前仅固定原行为。非 Android 的同步 dispatcher 若用于其他集成，内外捕获可能各申请一次日志名额；当前实际 main.post 路径是异步，仍共用 8 条总预算。
- 阶段是共享观察值，不是每项并发命令的 trace；原 volatile stage 语义保留。probe 本身只用于串行构造期，未承诺通用并发 probe。
- 纯 JVM 回归和 Android 编译未运行实际 Handler/SQLite 迁移、磁盘满、Android 日志 sink、真机或模拟器验证。未声称已修复真实手机存储故障。

原 3 文件备份在 `backup/`，本轮构造器修正前快照另存 `backup/AndroidSaveStorage.pre-compatibility.kt`；最终补丁 `changes.diff`，最终逐文件哈希 `PATCH_MANIFEST.json`。集成前先核对目标基线及哈希，勿覆盖他人修改。

全程无直接/间接 ADB、设备操作、上传/push、新依赖/工具、签名/安全设置变更。Platform-Tools 审批仍独立等待。全部本轮编译/测试操作已终态，按要求收尾停止。
