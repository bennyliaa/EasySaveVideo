# 精确POM准入、lint与本地恢复包终态

本轮完成；ESV lint/Gradle进程已返回，无后续自动构建。没有设备、安装、重签、密钥访问/搬运、上传或推送；原签名使用由父任务在原cloud处理。

## 唯一获批元数据变更

重新从原官方Maven Central URL核验`org.apache.groovy:groovy-bom:4.0.29` POM：27277bytes，SHA-256与获批候选完全一致：

`c24277dec93f146bcda25f5ae4391d6527e384e2132efa32184c1e852b42bca9`

只在隔离诊断源码`esv-code12-diagnostic-build/gradle/verification-metadata.xml`既有component中增加`groovy-bom-4.0.29.pom`的精确SHA-256项。语义检查确认已有artifact条目及验证配置全部不变，未改版本、仓库、锁定或关闭校验；冻结2892项原源码保持原字节。

- 清单修改前SHA-256：`5a94b42c6d27955fd4386dc30f1ee5a8966e0f0e14d5d7c3328f9ca51d6888af`。
- 修改后：`1dbc2ad2d476bccf428b2df1c2a8c3d1f1e8ad621f22a324b81ebf99732c2200`。
- 备份、原POM、可恢复patch、ADMISSION.json：`groovy-bom-exact-admission/`。之前严格拒绝日志完整保留，没有覆盖或伪装为历史通过。

## 本轮lint实际结果

收到SkinKit构建结束协调后，实查没有Gradle/Kotlin构建进程，才启动单worker、2GiB/2处理器、严格离线的既定lintDebug/lintRelease和runtimeInventory；未重跑全量测试或229个失败用例，没有APK/签名构建。

**BUILD SUCCESSFUL，exit0，15m45s；70任务，10执行、60已是最新。**

| 变体 | 错误/Fatal | Warning | Hint |
|---|---:|---:|---:|
| Debug | 0 | 94 | 10 |
| Release | 0 | 104 | 10 |

两个变体原运行时准入保持95项精确已审核构件；runtimeInventory成功。lint过程较长，过滤线程快照显示UAST路径收集；生成模型声明的路径在隔离工程、既有SDK和依赖内，未修改扫描范围或资源限制。只读GC/线程诊断未读取堆内容或凭据。

原始结果：

- `diagnostic-lint-after-approved-pom.log`
- `esv-code12-diagnostic-build/app/build/reports/lint-results-debug.html` / `.xml`
- `esv-code12-diagnostic-build/app/build/reports/lint-results-release.html` / `.xml`
- 脱敏机器摘要：`groovy-bom-exact-admission/LINT_RESULT.json`

这只解除此前的lint元数据阻塞。之前7100个唯一测试用例的6869通过、229失败、2跳过终态没有被替代；本轮未重新证明文件系统相关失败已修复。

## 原cloud复用的本地恢复包

路径：`D:/CodexProjects/2026-10-22/task/ESV-code12-frozen-diagnostic-recovery-20261025.tar.gz`

大小：**30,715,370 bytes**。

SHA-256：

`339b2f76342399445b93ffd2cc263bf00f78b611b934b73f3b50264d6363ae0e`

旁置checksum：同路径加`.sha256`。**尚未上传、未在cloud执行。**

载荷白名单2908项：

- `frozen-source/`下2892项原冻结源码，全部hash匹配原code12清单；不会把任一增量包单独当作全量。
- 另外16项诊断生产patch、测试源/合成stub、脱敏测试计数/历史对照及精确POM准入证据。stub只用于独立测试，README明确不得放入产品源集。
- 加上归档内MANIFEST.json、MANIFEST.sha256、RECOVERY_README.md，共2911个普通文件；无链接或特殊成员。
- 创建后逐项从归档读回验证2908项的大小和hash，全数一致。未复制任何原工程.git、Gradle/Android缓存、local.properties、APK/构建产物、签名key容器/运行时凭据、个人/设备数据或原始宿主路径日志。原已批准公开服务联系配置及合成测试向量保持源字节，未加入实际账户或设备材料。
- 恢复顺序为原2892项 -> 诊断patch -> JUnit测试 -> 获批精确元数据patch；不含签名配置，不改变原cloud签名身份。Windows源快照未独立证明Unix执行位，原环境可使用已有Gradle或bash调用已核验脚本。

逐文件清单：`esv-cloud-recovery-plan/MANIFEST.json`，其SHA-256为`65b8f2e5d280bd9e103b2be1bd4c6d4fb7f7a7e4c649301bdbcce07899d43588`。创建及排除证据：`esv-cloud-recovery-plan/ARCHIVE_RECEIPT.json`。原始失败报告保留在本机，包仅收录脱敏汇总，不包含带宿主路径的原始XML。

**补充说明**：包在Release lint结束前已封存，故不包含后生成的LINT_RESULT.json；它包含全部源码/补丁/获批准入材料。lint成功证据作为本地补充文件提供，未为追加结果重做恢复包，以上包hash保持不变。

剩余协同：由父任务决定传送并在已恢复原cloud环境复验/使用原签名。本机不换签名、不生成新APK，不操作手机；Windows既有失败及code13版本归属仍按原报告保留。
