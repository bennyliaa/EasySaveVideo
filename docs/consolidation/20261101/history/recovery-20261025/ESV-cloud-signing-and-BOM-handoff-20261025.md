# 待恢复签名环境与Groovy BOM精确审查材料

本轮仅查阅现有公开记录、交接归档和既存审查报告；没有新探针、构建、设备、换环境、网络、签名或配置变更。没有读取/导出密钥、密码或tokens。保持待恢复原签名及合适文件系统测试环境。

## 原cloud签名环境定位

| 字段 | 现有证据可确认的内容 |
|---|---|
| 原任务ID | **未找到**。`29eb2a4f835786855a26e27319cd3f5a29a332f0`是本地交接Git提交，不是任务ID；不能用当前委派任务ID替代。 |
| 环境名/host | **未记录可直接选择的环境名或host**。FINAL_APK_VERIFICATION只称“pre-existing original cloud identity”。 |
| 可定位的cloud路径锚点 | slice09归档内`run-gradle.sh:9`固定`GRADLE_USER_HOME=/workspace/scratch/df6c1284d5ab/android-build-home/gradle-home`。该scratch路径是查找原执行环境的线索，不证明环境现在仍在线，也不是密钥位置。 |
| 工具链引用 | 同脚本JAVA_HOME=`$ROOT/toolchain/jdk21-module-launchers`、ANDROID_HOME=`$ROOT/toolchain/sdk`；使用POSIX脚本/flock串行锁和严格依赖校验。HANDOFF_README要求既有JDK21/SDK36/Kotlin-Compose2.2.10离线环境。 |
| 原构建目录 | 脚本HERE为脚本所在目录，ROOT为HERE父目录，然后`cd "$HERE/source"`。**原绝对HERE未记录**，不能由缓存路径推出构建目录。公开验包记录只给出`source/app/build/outputs/apk/debug/app-debug.apk`相对路径。 |
| 原公开证书SHA-256 | `18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5`。原APK的公开apksigner回执及前轮复验均一致。 |
| 原APK SHA-256 | `b41ff7c237218922ab367d2743e2ae131e9185a7fa3ca54913dc60a49466fc02`。 |
| 密钥可复用引用 | **未找到足够的本机引用**。README明确交接物不含签名key/凭据/缓存，FINAL_APK_VERIFICATION记录未生成/导出/移动key。没有打开任何密钥容器核验。 |

公开记录根目录：`D:/CodexProjects/EasySaveVideo/discovery-live-20261017/code12-candidate-20261019/`：README.md、FINAL_APK_VERIFICATION.json、APK_SIGNER_VERIFICATION.txt、APK_TOOL_EXECUTION_RECEIPT.json、LOCAL_CANDIDATE_RECEIPT.json。旧APK为该目录`esv-slice09-code12-diagnostics-debug.apk`。

## 已知恢复/交接包路径

- `D:/CodexProjects/EasySaveVideo/discovery-live-20261017/code12-candidate-20261019/code12-source-delta-on-slice09.tar.gz`，SHA-256 `fdc6d6530c997eb7fa07f4e1ce92663dbcecf391e40d660660c8a5ae0d8aec2b`。
- 同目录`commons-stage-diagnostics-slice09-delta-20261019T0353Z.tar.gz`，SHA-256 `d3013dd6f299b52e4094dd3cb366b7f718db6044c82e2440a1f8ce353d60cb20`；环境路径线索来自其`run-gradle.sh`，交接说明来自其`HANDOFF_README.md`。未执行归档内脚本。
- 本轮已从既有本地Git对象恢复的slice08包：`D:/CodexProjects/2026-10-22/task/discovery-latency-slice08-delta-20261019T010239Z.tar.gz`，SHA-256 `d42936099ec4270b92fd914c0fc69c87ac9383a643869dd10476962b80530171`。
- 本地交接对象库：`D:/CodexProjects/EasySaveVideo/discovery-live-20261017/oppo-code9-test/github-transfer`，已知提交`29eb2a4f835786855a26e27319cd3f5a29a332f0`；code12前缀`recovery/context-reconstruction-20261017/candidates/code12-slice09-diagnostics-20261019/`。只有Git定位，不是签名任务身份。
- 完整冻结源快照：`D:/CodexProjects/2026-10-22/task/esv-code12-frozen-baseline/source`；2892项与清单匹配，清单SHA-256 `76f33eadb01902dfe0024f1bb21501f66b48429037dff2650e87edcab69c6b32`。此快照不含签名凭据，不是code13最新恢复。

**必要恢复动作**：由原执行环境/任务的控制方根据scratch路径、交接提交和公开18e3证书定位已有签名资源；未找到前不换新签名、不要求上传私钥、不安装当前Windows签名包。本轮无法提供未记录的原任务ID或可立即恢复的环境名。

## Groovy BOM待审材料（不等于已准入）

- 坐标：`org.apache.groovy:groovy-bom:4.0.29`。
- 原配置仓库：Maven Central，`https://repo.maven.apache.org/maven2`。
- 精确候选POM源：`https://repo.maven.apache.org/maven2/org/apache/groovy/groovy-bom/4.0.29/groovy-bom-4.0.29.pom`。
- 前轮缓存且被拒绝的候选POM：27277bytes，SHA-256 `c24277dec93f146bcda25f5ae4391d6527e384e2132efa32184c1e852b42bca9`。这是观察到的候选hash，尚不是准入批准。
- 原已准入`groovy-bom-4.0.29.module` SHA-256：`2392853c20ff3b7474791d66f831909f25396cb397cf63bf15e1998a5c0b82fc`。
- 现有策略为`verify-metadata=true`、`verify-signatures=false`；原component只有.module条目，没有POM条目。解析器前轮请求POM时严格验证拒绝，并非已有预期hash比对不一致。没有找到历史“为何只录module”的审批理由；warm-cache历史成功不足以证明POM已获准。

**待批准的最小校验项**：若精确POM的官方来源/字节/身份、约束/BOM关系及冷解析必要性审查通过，在后续隔离补丁中仅向`gradle/verification-metadata.xml`既有`component(group=org.apache.groovy, name=groovy-bom, version=4.0.29)`增加artifact `groovy-bom-4.0.29.pom`及其核实后的SHA-256项。保留原.module、版本、仓库、锁定与verify-metadata=true；不批准通配白名单或近似版本。本轮**未制作/应用该准入修改**。

审查材料：`file-identity-diagnostic/GROOVY_BOM_REVIEW_INPUT.json`；严格拒绝日志`metadata-recovery-round4.log`及`metadata-recovery/build/reports/dependency-verification/at-1792937578915/dependency-verification-report.html`。

当前保持：测试失败证据完整、lint未通过、Windows APK签名不兼容且版本仍12、无设备安装；等待原签名及合适测试环境恢复，不自行启动下一轮构建。
