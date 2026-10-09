# Media3候选补充：实际72件图与4项迁移的静态ABI核对

证据时间：2026-10-06 UTC。观察的主工程HEAD：`ee4e398e35f5051088f1fd5df6aefff98750af1f`。本轮只读主工程和已下载制品，只在隔离审计目录写证据；未运行Gradle、Android编译、设备或应用代码。实际App编译/测试结果由集成人另行保留，本报告不替其背书。

## 1. 74→72是正常依赖收敛，72件沿用精确许可结论

已重新读取 `app/build/reports/debugRuntimeClasspath-inventory.tsv` 和 release 对应文件，核验所有外部制品的实际磁盘SHA-256：

- Debug和Release外部运行图完全一致，均为72个唯一GAV。
- 它们是此前74件候选图的精确字节子集：没有新GAV、没有hash差异，23件新候选全部保留。
- 仅缺 `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.20` 和 `kotlin-stdlib-jdk8:1.8.20`。
- 原74件隔离审计把原55件全部当direct roots，用于保守组合核验；所以即使生产传递入口已不再需要两个wrapper，它们仍被审计配置直接保留。这个74是保守候选组合，不是要求App制造同样数量。

固定官方 `.module` 的自然选择原因：

1. coroutines-core-jvm/android **1.7.3** 的运行依赖均包含 `kotlin-stdlib-jdk8:1.8.20`；jdk8 POM再依赖jdk7同版。
2. coroutines-core-jvm/android **1.9.0** 改为依赖 `kotlin-stdlib:2.0.0`，不再请求jdk8/jdk7；现有stdlib2.2.10满足自然版本收敛。
3. stdlib2.2.10元数据中jdk7/jdk8的constraint只约束存在的模块，不会自行引入已经不再被请求的模块。
4. 两个1.8.20 wrapper实际JAR只有MANIFEST、目录和Java9 `module-info.class`，均为 **0个运行实现class**。没有业务实现因移除wrapper而消失。

这不是R8剥离，也不是AGP神秘排除。无需添加无用direct依赖以凑74。**实际72件可以沿用原精确候选许可结论与全部发布义务**；原23件中的CC-BY/MIT/NOTICE条件完全保留。

证据：`SELECTION_74_TO_72.json`、`ACTUAL_72_RUNTIME.tsv`、两份inventory快照、`metadata/`中四份coroutines模块元数据和两个wrapper POM。

inventory原始文件另外包含5个com.easysavevideo工程JAR，不计外部72；其中core-domain/save在本轮开始时已被构建负责人重建，与旧inventory内的hash不同。本轮没有将这些变动中的项目产物纳入固定依赖ABI声明；最终冻结时应重生完整项目inventory。

## 2. 方法和覆盖范围

- 逐字节读取原55件与实际72件的JAR/AAR并核输入SHA；只扫描AAR的classes.jar和libs/运行JAR，不把lint.jar混入App。
- 解析JVM classfile的公开/保护成员、erased descriptor、访问/static/final/abstract标志、类/接口种类、父类/接口链及泛型signature；不执行库代码。
- 检查保留的 **49个原基线GAV**（其中45件含运行class）的真实class常量池成员引用、类型引用、相关子类/接口实现；不是通过源码搜索推测调用。
- 成员在新图中沿继承链解析；原owner消失、签名移除、访问范围收窄、static/instance变化、class/interface变化分别记录。
- 同时用JDK21 `javap -protected -s` 独立导出四组旧/新公开类声明，保存原始文本及SHA。concurrent两版输出逐字节相同；ListenableFuture接口两份输出也相同。

静态引用记录表示某制品中的编译符号存在，不表示该路径已在设备运行，也不是调用次数。反射字符串、动态加载、Kotlin元数据/inline源码兼容性、并发语义和Android行为并非该扫描的充分证明。

## 3. 四项替换的结论

| 迁移 | 公开/保护JVM符号变化 | 保留基线真实member引用 | 静态链接结果 |
|---|---|---:|---|
| concurrent-futures1.1.0→1.2.0 | 0个旧公开类移除；0个旧公开/保护签名移除；javap公开文本相同 | 7条记录，3个不同签名 | 全部可解析 |
| listenablefuture1.0→Guava33.3.1-android中的接口 + empty占位包 | `ListenableFuture<V> extends Future<V>`及`addListener(Runnable,Executor):void`完全保留 | 保留49件中无直接member调用记录；类型形状已单独核 | 无接口签名缺失；必须移除旧1.0避免重复class |
| coroutines-core-jvm1.7.3→1.9.0 | 15个原JVM-public类不再存在，另6个类收窄为package-private；150个旧public/protected成员符号移除/收窄，其中60个属于消失类、90个位于仍存在owner；16项泛型signature变化 | 649条记录，127个不同签名 | 全部可解析；未发现指向消失类型或class可见性/种类变化的保留基线引用 |
| coroutines-android1.7.3→1.9.0 | 0个公开类消失；移除1个synthetic accessor | 2条记录，2个不同签名 | 全部可解析 |

**不能把coroutines迁移写成“全库无ABI变化”或“对任意旧二进制完全兼容”。** 这里的public包括Kotlin internal、PublishedApi、synthetic及已过时API在JVM层可见的符号，不等同于上游稳定公共API承诺。完整真实移除/收窄表在 `REMOVED_JVM_SYMBOLS.tsv`；完整结构变化在 `ABI_REPORT.json`。

代表性变化：

- `CancellableContinuation.tryResume(Object,Object,Function1):Object`不再保留，新增Function3版本及resume相关接口成员。
- `FlowKt.asFlow(BroadcastChannel):Flow`不再保留。
- 内部 `TaskContext`、AtomicOp/OpDescriptor、部分旧completion handler类消失；CancelHandler从class改为interface。ChildContinuation、ChildHandleNode、DisposeOnCompletion、InactiveNodeList、TaskImpl、SemaphoreImpl六类从public改为package-private，另有父类链变化，均保留在JSON中单独记录。
- android移除 `HandlerContext.access$getHandler$p(HandlerContext):Handler` synthetic accessor。

这49件保留的原二进制没有引用上述移除/收窄的成员或消失类型；没有发现继承/实现本轮新增abstract要求的旧基线类。因此就当前精确依赖的已编译符号而言，**没有发现迁移导致的静态链接阻断**。这与集成人进行真实App重新编译、JVM回归及后续设备验证互为补充，不能互相替代。

## 4. 哪些原基线制品真实引用迁移库

concurrent-futures：
- androidx.core:core:1.13.1：5条
- androidx.profileinstaller:profileinstaller:1.4.0：2条

coroutines-core-jvm：
- activity-compose1.10.1：10；activity1.10.1：10
- compose animation1.8.3：5；animation-core1.8.3：40
- foundation1.8.3：287；foundation-layout1.8.3：11
- runtime1.8.3：95
- ui1.8.3：89；ui-graphics1.8.3：3；ui-text1.8.3：21
- lifecycle-common-jvm2.8.7：33；lifecycle-runtime-android2.8.7：32；runtime-compose-android2.8.7：4
- lifecycle-viewmodel-android2.8.7：5；viewmodel-savedstate2.8.7：4

coroutines-android：compose ui-android1.8.3：2条。

这些是class常量池按引用class统计的符号记录，均在新图解析成功。每条精确GAV、引用class、target owner、方法/字段名、descriptor及新声明owner见 `BASELINE_MEMBER_REFERENCES.tsv`。

不再保留的旧coroutines/concurrent制品自身内部调用不作为旧代码兼容义务冒充统计；它们已整体换版。App自有模块需按新编译图重新编译，实际运行层仍需各自验收。

## 5. 准入与发布边界

- 接受72件精确子集，不扩张原许可集合，不重新写成另一个未经审计74件目标。
- 保留四迁移的新版本对齐；不force回旧版。compile和runtime的版本一致性由集成人的实际配置/锁/编译证据验证，本报告只核runtime输入及静态旧字节使用。
- 原CC BY2.5接收者权利、EULA/DRM例外、Apache/MIT完整文本、Guava特殊归属及协程NOTICE仍适用。
- 未检测到当前固定旧基线字节的静态链接阻断，不等于批准发布、真实HLS导出成功、线程/取消/调度行为与旧版完全相同，或认证任意第三方provider二进制。
- 本补充不改变原7.8MB候选证据包的manifest/hash；它是独立新增证据，供集成人在授权范围内保存。
