# ESV Windows 构建终态（2026-10-25 UTC）

**全部 ESV Gradle 编译、测试、打包及只读APK检查已结束。本轮不会再启动构建；SkinKit无需继续等待ESV构建资源。** 没有停止或操作其他任务。没有设备访问、安装、清数据、修改安全设置、上传、推送或改Git配置。

## 构建与APK

- 隔离源码：`esv-code12-diagnostic-build`；原2892项hash匹配的冻结源码及原工程/旧APK保留。
- Debug及Release Kotlin编译完成；运行时准入通过，两个变体95项已审核构件字节完全匹配。
- `:app:assembleDebug` **BUILD SUCCESSFUL，2m55s，63任务（19执行、44已是最新）**；生成新的APK，未覆盖旧候选。
- 产物：`esv-code12-diagnostic-build/app/build/outputs/apk/debug/app-debug.apk`，53,425,158 bytes。
- SHA-256：`94e742af1977ff732da3cfbf69a35666914ba6820327a69effc425741f7114c1`。
- 包名com.easysavevideo.app；versionCode12；versionName0.9.0-ui-dev；minSdk31/targetSdk36，与参考code12一致。
- **签名不兼容，禁止作为保留数据升级包使用**：新APK使用现有本机Debug签名，证书SHA-256为 `5e841a04968f3f790f6996ca5e286d8c2734338542486909f9a136717dde808f`；已装code12对应参考证书为 `18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5`。两包各自apksigner验证通过，但身份不相同。未重签、生成/移动/导出私钥或卸载现有包。
- versionCode仍12，不是递增升级候选。后续若协调构建可安装包，必须使用原有兼容签名，并分配高于当时已装/已占用候选最高编号的版本；code13恢复/编号归属仍未知。本轮没有另行分配编号。
- aapt比较：权限集合及debuggable/allowBackup/fullBackupContent/usesCleartextTraffic/networkSecurityConfig属性一致。原包已有usesCleartextTraffic=true，未新增或放宽；无独立networkSecurityConfig属性。新诊断helper及固定错误前缀已确认在新DEX中，旧包不含它们。
- 完整静态证据：`diagnostic-apk-verification/APK_COMPARISON.json`及对应signer/badging/permissions/manifest文本。旧APK实算仍为b41ff7c…66fc02。

## 本轮真实测试

多次执行结果按testcase身份去重（不是历史7095测试，也不重复相加独立JUnit）：**7100个唯一用例，6869通过、229失败、2跳过**。

| 范围 | 结果 |
|---|---|
| 新增脱敏诊断JUnit | 5/5通过，包含在App结果中；独立JVM的5项不再另加 |
| App | 4948个唯一用例：4858通过、90失败 |
| Commons Provider | 422项：283通过、139失败 |
| Save | 467/467通过 |
| Discovery | 462/462通过 |
| 其他模块 | 其余已执行用例通过；verification-local原有两项外部语料测试跳过 |

初始聚焦测试91项，89通过、2失败；两项为创建合成测试用符号链接时Windows返回缺少特权，尚未到应用拒绝逻辑。本轮没有改变Windows开发者模式/权限或重试这两项。原XML保留于`diagnostic-focused-xml-evidence`。

后续App执行4946项（仅将前述两个已失败用例明确排除，避免重复OS拒绝操作），其中88失败；加回先前两项得到90个唯一App失败。**这90个失败标识与原wfix留存报告的90个完全相同，无本轮新增失败标识**。该比较不证明原wfix是完整code12基线，也不证明所有失败的具体根因。其他新观察到的符号链接失败未被隐藏，未在失败后重试。

Commons139项主要表现为STORAGE_FAILURE断言；源码未修改，具体原因仍未闭合。没有展开Discovery修复，也没有将Windows失败当成手机存储提示的根因。

证据：`diagnostic-unique-test-outcomes.json`、`diagnostic-gradle-combined-counts.json`、`diagnostic-focused-actual-counts.json`、`diagnostic-historical-failure-comparison.json`，以及隔离工程所有JUnit XML。

## 官方元数据恢复及lint阻碍

此前是离线解析缺失，没有下载权限拒绝。本轮仅从原Maven Central地址核验既有锁定版本元数据；原项目仓库、版本、锁定文件和校验规则未改，没有下载JAR/AAR、新工具或执行元数据中的命令。

成功严格恢复六项元数据：Bouncy Castle bcpkix/bcprov/bcutil 1.80.2 POM；Kotlin reflect2.3.21 POM、stdlib2.3.21 module、stdlib-common2.2.10 module。全部官方字节SHA-256匹配冻结校验清单。现有相关JAR亦与清单SHA-256及官方checksum文件匹配（Kotlin官方仅提供SHA-1时保留SHA-1结果，同时仍要求冻结SHA-256匹配）。

lint随后需要原锁定的Groovy/Groovy BOM4.0.29元数据；官方.module均匹配，已有Groovy JAR亦与官方SHA-256和冻结清单匹配。但Gradle在恢复时额外请求 **org.apache.groovy:groovy-bom:4.0.29 的 groovy-bom-4.0.29.pom**，冻结清单只准入.module、未准入此POM，因而触发严格依赖校验失败。

**该恢复路径已停止，未添加白名单、放宽校验、猜造POM或重试绕过。lintDebug/lintRelease未通过，不能声称lint通过。** 这是依赖校验阻断，不是工具自动审批拒绝，也不是官方文件SHA-256与某个已有预期值不符（该POM没有被准入的预期值）。后续需单独审查此元数据缺口的处理方案，本轮不修改准入规则。

相关证据：`official-metadata-audit/AUDIT.json`、`JAR_OFFICIAL_CHECKSUMS.json`、`metadata-recovery-round*.log`，严格拒绝详情位于`metadata-recovery/build/reports/dependency-verification/`。`diagnostic-build-remaining-after-commons.log`保留lint四项Analyze任务离线失败和已完成的runtimeInventory记录。

## 终态与剩余项

终态：编译及APK打包成功；测试非全通过；lint受准入元数据阻断；新APK签名与手机不兼容、版本未递增。所有进程任务均已返回，**无ESV构建占用需要协调释放，不会重复启动**。

下一步应先协调原签名和候选版本编号、审查lint元数据缺口及Windows既有失败；不得用卸载/清数据规避签名问题。手机存储提示原始异常仍未知，①实际保存/取消、②流媒体和③录屏均无本轮设备验证结果。没有安装、冒充真机通过或宣称项目已完成。
