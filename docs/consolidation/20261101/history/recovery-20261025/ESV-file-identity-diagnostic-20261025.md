# ESV 三组最小文件身份诊断

2026-10-25 UTC。本轮没有Gradle、工程测试/全量重跑、APK构建、设备、重签、网络请求或上传。仅用现有JDK编译/执行一个独立小型Java探针：单CPU，javac上限128MiB，运行上限64MiB。只创建本任务目录内的合成文件；未创建符号链接，未改权限、安全设置或生产代码，未跳过安全断言。源/编译日志/探针日志/合成夹具均保留在`file-identity-diagnostic/`。

## 1. 文件身份前提：对应App传播组30项

探针实测：普通文件isRegularFile=true；目录canonical==absolute、文件canonical parent==预期目录均true；NOFOLLOW_LINKS只读打开成功；但**目录和普通文件BasicFileAttributes.fileKey均为null**。当前文件系统无unix属性视图。

源码链：DiagnosticSnapshotReader.kt:147附近要求目录/文件fileKey非null，19项普通快照断言在此前提就无法到达正路径；RecordedSegmentFiles.kt:67附近要求fileKey字符串，6项为RECORDED_SEGMENT_IDENTITY；LocalOnlyDataSource.kt:45在key为null时直接LOCAL_IDENTITY_UNAVAILABLE，对应1项；VaultMediaFiles.fileKey中的requireNotNull对应4项。19+6+1+4=30。

**归因**：已在当前Windows/JBR复现共享的最早身份前提缺失；本探针没有观察到路径规范化或NOFOLLOW读能力失败。它不是30项逐例产品重跑，也不证明其他路径均正常。不能以canonical path、大小/mtime或文件名替代稳定对象身份，否则削弱防替换校验。

**最小行动**：保持生产失败关闭及现有断言；把需要真实fileKey的集成测试安排到有该能力的既有Linux/Android测试环境。若将来必须支持Windows真实文件身份，需独立审查能提供真实稳定文件标识的适配，而非把null当成功。本轮不新增依赖、不实现适配、不清除失败。

## 2. Provider持久化边界：Commons138项的代表性早期阻碍

探针按源码同形API顺序执行：NOFOLLOW_LINKS锁文件创建/tryLock成功，普通文件写入及force(true)成功；随后**FileChannel.open(directory, READ) / force路径出现AccessDeniedException**。未输出异常消息或个人路径，也未重试该失败操作。

ProviderRepository.kt:818的syncDirectory正是此API；事务路径在:768、:777、:781调用它，:801附近将事务中异常毒化为STORAGE_FAILURE，后续维护/执行器可能呈现DISABLED或其他断言。由此已定位“能写文件但不能完成目录持久性步骤”的具体当前宿主边界，区别于磁盘满/无普通文件写权限/网络问题。

**限制**：这支持Commons138项传播簇的共同前置阻碍，但源码catch未保留原始异常、未逐例重跑，不能认定138项每个最终断言只有这一原因。另1项Commons符号链接特权失败不在本簇；之前229分类中的60项明确平台限制也未重复计数。

**最小行动**：维持目录同步/事务安全断言；优先在具备目录同步语义的既有环境做少量真实对照，或单独审查Windows持久化适配。不能把syncDirectory改成空操作来让测试变绿，也不展开Discovery功能修复。

## 3. 打开句柄后的重命名：Direct夹具1项

独立探针确认读描述符有效；在该RandomAccessFile仍打开时，File.renameTo返回false。原FixedDirectOutputInputTest.replacedPathIsNeverImplicitlyReopenedByFixedView先assertTrue(file.renameTo(moved))，因此在本机**还未执行到“固定描述符不重开路径”的核心断言**就失败。

**归因**：复现了该测试的开放句柄重命名夹具前提不满足，不是诊断补丁改变Direct读逻辑的证据。未更改句柄共享标志或绕过安全限制。

**最小行动**：保留原替换攻击断言，在支持该真实文件操作的既有测试平台运行；如需Windows专用夹具，应另审能够真实制造等价替换事件的方式，不能把assertTrue删掉、改成跳过或伪造替换成功。

三组涵盖此前169项传播簇的候选分解：App文件身份30、Commons传播138、Direct重命名夹具1。它们是三次独立诊断组，不是169项新的测试通过。原7100项测试终态不变；手机保存存储异常仍未据此归因。

## 4. 旧18e3签名环境：公开记录可定位，复用路径仍缺失

只读code12交接README.md:3/8/23和FINAL_APK_VERIFICATION.json：旧证书为`18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5`，来源被明确记录为“pre-existing original cloud identity”，并记录未生成/导出/移动key；README明确交接物不含key/凭据/缓存，并声明warm-cache cloud结果不证明Windows冷元数据重现性。

这定位到**原有cloud构建/签名身份**，而不是当前Windows默认Debug身份。公开工具回执显示apksigner/aapt2/zipalign验包exit=0；旧APK和公开证书记录已保留。本机项目无显式签名覆盖，默认候选路径与旧环境之间没有已证明的映射。没有打开密钥容器、读取/输出密码、复制凭据或自行重签；没有足以安全复用旧key的本机配置引用。

**最小行动**：后续产出可安装包时，应在有权使用旧18e3身份的既有环境完成签名，或先由负责人给出该既有环境的可用引用；不需要上传私钥/密码。当前签名不匹配APK继续禁止安装。code13/版本编号仍需协调，不在本轮改变。

## 5. Groovy BOM准入材料：仅整理，仍拒绝

- 精确坐标：org.apache.groovy:groovy-bom:4.0.29。
- 原仓库/请求：https://repo.maven.apache.org/maven2/org/apache/groovy/groovy-bom/4.0.29/groovy-bom-4.0.29.pom 。本轮未联网；只读前轮缓存的被拒绝POM。
- 缓存POM：27277bytes，SHA-256 `c24277dec93f146bcda25f5ae4391d6527e384e2132efa32184c1e852b42bca9`。这是被拒绝文件的观察值，**不是已批准的预期hash**。
- 原已准入.module SHA-256：`2392853c20ff3b7474791d66f831909f25396cb397cf63bf15e1998a5c0b82fc`。
- 原策略verify-metadata=true且仅.module条目，没有POM条目；前轮解析器请求POM后严格校验拒绝。为什么历史只记录.module没有找到审查意图记录；原报告只承诺warm-cache构建，不能推断POM已经获准或被认定不可信。
- 后续若要解除，需另审精确官方POM字节、与.module的身份/约束/BOM关系及冷解析需要，并获得最小元数据准入变更批准。本轮未改白名单、版本、仓库、锁定或校验，lint继续未通过。

机器可读材料：`file-identity-diagnostic/GROOVY_BOM_REVIEW_INPUT.json`。

## 原始证据入口

- 三组探针：`file-identity-diagnostic/FileIdentityProbe.java`、`compile.log`、`probe.log`；编译exit0、运行exit0，探针把观察到的能力失败记录为结果，不声称这些操作成功。
- 既存失败XML：`esv-code12-diagnostic-build/app/build/test-results/testDebugUnitTest/TEST-com.easysavevideo.app.diagnostics.DiagnosticSnapshotReaderTest.xml`、`TEST-com.easysavevideo.app.recording.segments.RecordedSegmentFilesTest.xml`、`TEST-com.easysavevideo.app.save.stream.LocalHlsAddressTest.xml`、`TEST-com.easysavevideo.app.vault.storage.VaultMediaFilesTest.xml`、`TEST-com.easysavevideo.app.save.FixedDirectOutputInputTest.xml`。
- Provider原XML：`esv-code12-diagnostic-build/commons-provider/build/test-results/test/TEST-com.easysavevideo.commons.maintenance.ProviderRepositoryTest.xml`及其他同目录XML。
- 旧签名原记录：`D:/CodexProjects/EasySaveVideo/discovery-live-20261017/code12-candidate-20261019/README.md`、`FINAL_APK_VERIFICATION.json`、`APK_SIGNER_VERIFICATION.txt`。

本轮探针已结束，无ESV Gradle任务或持续资源占用；无需打断SkinKit。没有把安全断言改成跳过，也没有宣称生产修复完成。
