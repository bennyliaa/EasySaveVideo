# code12 诊断切片 Windows 构建验证：离线元数据阻断

## 结果

完整构建未通过，诊断APK未生成。原因是已有精确工具链的buildscript classpath离线依赖解析在根工程配置阶段失败，尚未进入项目编译/单测。没有新依赖下载、工具链升级、设备操作、安装、清数据、设置修改或上传。

本轮实际完整项目测试执行数 **0**，项目编译任务执行数 **0**；不能把未运行说成通过。新增诊断的独立JUnit **5/5通过**，仅证明纯诊断分类/脱敏/限额，不替代完整工程编译或Android测试。之前的7095历史测试不计入本轮。

## 隔离与构建环境

- 原2892项文件hash一致的 `esv-code12-frozen-baseline/source` 保持不变。
- 新建独立 `esv-code12-diagnostic-build`，复制前重新校验全部2892项文件hash；只合入已审查的运行时诊断修改、新增helper、5项JUnit测试，以及本机SDK路径local.properties。未复制/修改原Git配置、签名材料、构建缓存或旧候选APK。
- 原AGENTS/README要求离线、严格依赖验证、串行构建。使用现有Gradle9.6.0、AGP9.4.0声明、Kotlin2.2.10、现有Android Studio JBR与SDK36；未改wrapper/plugin版本。
- 启动前只读进程快照未见Gradle/Kotlin Java构建进程；未停止/操作SkinKit或其他项目。本轮1个worker，Gradle堆上限2GiB、ActiveProcessorCount=2、Kotlin in-process。配置失败约5秒。另一次1GiB的离线configuration-only诊断用于展开依赖错误，没有进入编译。
- 现有本机debug.keystore文件存在，未读取/生成/复制/导出签名私钥，也没有走到签名任务；不能由文件存在推断与已装包的签名兼容。

## 配置失败与精确缺口

执行的聚焦任务为`:app:testDebugUnitTest`，过滤新增诊断、Initialization、SaveRuntimeNotice、SystemHttpsDirectTransfer相关测试；参数包含`--offline --dependency-verification=strict --no-daemon --max-workers=1`。Gradle exit=1，根工程classpath配置失败。

现有默认Gradle缓存有以下JAR，但其对应POM/.module文件不在常规artifact缓存目录，Gradle离线无法解析该精确版本；未伪造依赖描述符、忽略严格验证或启用网络：

- org.jetbrains.kotlin:kotlin-stdlib:2.3.21
- org.jetbrains.kotlin:kotlin-reflect:2.3.21
- org.bouncycastle:bcpkix-jdk18on:1.80.2
- org.bouncycastle:bcprov-jdk18on:1.80.2

这里2.3.21是既有AGP构建classpath要求，不是本轮升级项目Kotlin版本。日志中“12 more failures with identical causes”未提供额外不同坐标；带stacktrace的离线配置诊断也仅提取出以上4个唯一坐标。不声称它们补齐后所有后续依赖一定完整。

证据：`diagnostic-build-focused.log`、`diagnostic-build-dependency-detail.log`、`diagnostic-build-missing-coordinates.txt`、`diagnostic-build-cache-gaps.json`。未继续运行必然受同一配置失败阻断的全模块任务/assemble/lint。

## 不受阻断的实际检查

1. 直接使用已有Kotlin2.2.10编译器、JUnit4.13.2、Hamcrest1.3缓存，以512MiB/2处理器串行编译纯诊断helper与JUnit文件；compiler exit=0，JUnit exit=0，**OK (5 tests)**。没有测试stub混入工程。测试覆盖固定类别/敏感payload不泄漏、恶意Throwable字段不可读取、所有固定阶段/ready状态、八条预算、16线程320次故障并发限额。
2. 测试证据：`diagnostic-patch/junit-compile.log`、`junit-test.log`、`junit-TEST_RESULT.json`（工具和源hash），运行命令为`run_diagnostic_tests.py --junit`。
3. 主/Debug AndroidManifest、app/build.gradle.kts、根build/settings、wrapper及HttpsRequestPolicy在隔离构建树中仍与冻结清单hash一致。未改权限、网络安全策略、版本/签名配置、重试/预算。
4. 两文件生产补丁仍可正向/反向检查应用；原控制命令的catch继续post原notice，finally/ready逻辑不变，新增日志sink异常被隔离，不追加重试。新增JUnit源为`diagnostic-patch/SaveRuntimeFailureDiagnosticsJUnitTest.kt`。
5. 使用SDK36现有apksigner对**旧code12参考APK**重新验签exit=0，证书SHA-256 `18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5`。aapt核对其包名com.easysavevideo.app，versionCode12/versionName0.9.0-ui-dev，minSdk31/targetSdk36，与前轮设备身份一致。这不是新APK签名验证。

## APK与版本结论

**新APK：不存在；新APKhash、签名匹配、合并manifest差异：未验证。** 不能使用旧候选替代新产物汇报。没有安装任何包。

冻结Debug版本仍12，本轮未私改。后续若批准生成可供保留数据升级的诊断包，建议版本取“当时确认的最高已装/已分配候选versionCode + 1”，至少高于已确认12，并先协调未知code13候选编号；包名和证书必须一致。当前没有分配新编号或覆盖code12候选。

## 下一步需要决定

需要允许获取上述**既有精确版本的官方元数据及随后发现的缺项**，按现有verification-metadata.xml严格校验，才能继续完整工程验证；或提供已审计、完整的离线缓存。当前授权要求新依赖/权限需求先报告，所以没有打开网络或新装依赖。不要通过改工具链、生成猜测POM、关闭校验或换未经核查镜像绕过。

code13完整恢复状态仍未知。存储提示根因、真实诊断日志、手机保存/取消均未进一步测试。本轮无权限审批拒绝；状态为明确的本地离线解析阻断，未发生不确定安装或设备写入。
