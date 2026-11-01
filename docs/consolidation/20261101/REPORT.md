# EasySaveVideo 本机归拢报告（2026-11-01）

正式开发入口为 D:/CodexProjects/EasySaveVideo，唯一正式本地分支为 main。本次是本机实际合并与可恢复归档；没有 push、删除远端分支、改远端默认分支、重写既有提交历史、生成 APK/AAB、启动 ADB 或设备/模拟器测试。最终 HEAD、branch、status 及归档收据见备份区 FINAL_STATE.json，并可在正式目录运行 git rev-parse HEAD、git branch -a、git status --short 复核。

## 实际来源与覆盖

- 扫描正式目标及相关日期任务目录，找到 13 个具有 settings.gradle.kts 的源码树、16 个带 .git 的目录及相关裸库。共 21 个不同 Git 元数据目录（包括一个没有提交的传输空库）；归档记录中的 22 行包含同一裸库的两种路径写法。
- 从私有 bennyliaa/EasySaveVideo 只读下载完整可访问 Git 历史，核对 18 个远端分支。先将 development/cloud-followup-20261013（6d2edfa5680d0163038fde1faa58f693513d0ab8）快进至 main，再逐一真实合并所有尚非祖先的交付、恢复、验证分支。没有使用 ours/theirs 掩盖功能冲突，实际合并没有冲突。
- 18/18 远端分支 tip、80/80 本地 ref 记录均是 main 的祖先。54 份内容不同的既有 bundle 的所有引用也均已覆盖。逐分支 SHA、来源和提交/文件记录见 branch-coverage.json、local-ref-coverage.json、existing-bundle-coverage.json。保留已有 tags 与原提交历史。
- 独立 ab-validation-20261008 的两个提交通过 git subtree 合入 verification-fixtures/，保留全部原始内容与历史；它仍是独立验证夹具，不冒充 Android 生产引擎。
- Git 工作树清点包括未暂存、暂存、未跟踪文件。真实开发 Git 工作树当时干净；若干 Git 回读目录存在批量文件缺失/索引删除，属于不完整回读副本，未将其删除状态套用到正式源码。空传输库的未跟踪包、脚本及原始索引均有备份。

## 实际源码归并

1. 后续开发分支中的 rules v2、JSON、Coordinator、来源设置、Vault 生命周期及其受限/禁用状态都随原历史进入 main。其后的完整快照覆盖的是同一功能演进链；所有正式模块 src 路径均可在固定 code13 清单或本机诊断增量中定位，没有额外旧版 src 残留混编。
2. 从远端 part07 四分片重组 T0647 全基包：29,125,872 bytes，SHA-256 fca4b4359844f51735aceb3925241a89d10d7a8a4f4a46cafe43ce06f80a0859。保留 T0647 → slice08 → code11 → slice09 → code12 → code13 的完整归档历史。
3. 逐字节验证本机 code12 冻结源码 2,892/2,892 文件，再应用远端 code13 的六文件增量，验证 2,893/2,893 文件。固定最终清单 SHA-256 c5670131a7334e4910f07cd92dae2496b3e7e2a83b032170b1443f0850df8ffc。该清单是导入基线，不是声称加入本机补丁和本报告之后仍与旧快照完全相同。
4. 在此基础上实际加入本机初始化诊断及边界补丁：存储阶段探针、INSTALL 回调重抛原异常、共享八条脱敏日志预算、Context 构造器 ABI、两组回归测试；保留已有 Handoff 语义。七个相对于冻结源码的本机文件包括已批准的精确 Groovy BOM 元数据，详见 diagnostic-integrated.json。Debug 基线为 code13、Release9；没有引入不可用云端 build-copy 的 code14 override。
5. 独立服务 verification-service/ 保留 Windows IPC、Job 和启动器。与 Scrapling_Service/source-windows-20261009 的所有文件对照后，只有 adapter.py、capability.py、protocol.py 不同，现行文件包含更后的受限 DOM/JSON-LD 与协议演进；Windows 专用实现/测试均已覆盖。原始运行环境、wheelhouse、浏览器缓存只保留于本机归档，未当作 Android 源码或新依赖提交。
6. 另保留了历史 UI PPT 的生成/检查源码、36 张参考图及 9 张实际使用的生成图（docs/ui/source-history-20261011），没有重生成 PPT。原目录的需求原文、架构 UTF-16 文档、UI 文档与 PPT、设计参考及后续恢复/评审材料已归拢。相同路径的不同版本存入 history/，不覆盖现行版本；document-imports.json 逐项记录来源与 SHA。历史 Git 文档和许可证/notice 原文一并保留。
7. receiver-hardening-20261020 的八个文件和完整提交已实际进入 main 的 recovery/ 命名空间；candidate 与 baseline 同时保留，没有覆写 code13 固定哈希的 receiver。

## 备份与恢复

永久本机归档：D:/CodexProjects/EasySaveVideo-Archive-20261101T132000Z

- recovery/ 内有内容寻址 blobs/、backup-files.json、Git 原始元数据/索引与 all.bundle、下载的 remote.git、历史清单、原始验证日志及整理脚本。
- 清理/归档前已对 70,755 个文件完成内容备份，对 12,743 个独立 SHA-256 内容块全部重读验证。覆盖未提交与未跟踪内容和文档；缓存/构建产物不冒充源码。原目标全目录另以 original-target/ 保留，包括未收录为项目源码的运行环境。
- 所有含 refs 的仓库 bundle 均通过 verify。一个无提交的传输空库无法生成 commit bundle，其原始元数据及未跟踪文件仍完整保存。这不是丢失提交。
- 已在新的 restore-check.git 中实际 unbundle，并恢复 20fddc9 的 AndroidSaveRuntime.kt，与原 Git blob 做字节相等比较，通过。证据 RESTORE_PROOF.json。其余内容按 backup-files.json 将 blobs/<sha256> 复制回原路径或安全的新目录即可恢复；Git 恢复也可从 all.bundle clone。
- original-target/ 和 original-external/ 为旧开发副本的归档区，不再从这些位置开发。Git worktree 指针可能记录归档前路径；完整原始 .git/common-dir 与 bundle 已保存，恢复时按原位置重建或从 bundle 新建 checkout，勿直接把归档中的 worktree 指针当作新正式仓库。
- 本机敏感配置与运行环境仅留在本机备份；没有上传或把 local.properties/凭据/签名密钥加入本次提交。

## 本次实际验证

- 初始化边界：使用现有 Kotlin 2.2.10/JUnit 4.13.2 从现行源码重新独立编译，17 项通过，未复用旧 classes。见 boundary-test-result.json。
- 独立服务：13 项可移植协议/默认服务测试通过；9 项 Windows IPC 回归通过。
- 原 test_service.py 全套尝试保留：48 tests，20 failure 子项、6 error、12 skip。该套包含 Linux SIGSTOP/pidfd、缺失的云端 admission 路径及受沙箱拒绝的 loopback 操作，不能在 Windows 上宣称通过；随后仅对适用的现有可移植/Windows 测试进行了明确独立验证，没有改测试或生产约束来抹除失败。
- Gradle：严格离线、单 worker、现有工具/缓存；执行 12 个 JVM 模块测试、App Debug/Release Kotlin 编译、App Debug JVM 测试和 runtimeInventory。实际结果：Debug/Release Kotlin 编译和 runtimeInventory 通过；两个测试任务失败，整体 exit 1，BUILD FAILED in 14m45s。。
- 当前 XML 统计：7122 tests，6891 passed，229 failed，0 errors，2 skipped；638 suites。229 项历史 Windows 失败中，本次匹配 229 项；新增失败 ID 0 个。具体以 TEST_SUMMARY.json 和完整日志为准；相同 ID 仅证明重复出现，不能替代同环境 A/B，也不能把历史云端通过算成本次通过。
- 原有 95 个已评审运行时 artifact 严格验证通过；未放宽哈希、依赖锁、TLS、文件身份、安全、签名或配置检查。
- 未运行：lint（此次没有声称重新通过）、APK/AAB assembly、发布、ADB、手机、模拟器、账号与线上目标测试。

## 明确仍缺失的历史材料

- 早期 M0/3907618 原仓库历史仍不能声称已恢复，遵守原 AGENTS.md 的重建说明。
- 云端曾丢失的 discovery-session-report 3,034 文件快照（manifest c34fa18b6e0e8c300fd0f58ccbfc5b2bebf69614143e83556e8c761b98cc5272）及未测 media-UI 3,049 文件副本（f4d8196b257ca49561f30317c5c84b7d61159226142a8fed0b3a333a6d6164c7）没有找到完整 bytes。现存 code13 不能据此宣称包含这些丢失快照的全部功能。
- 旧 source-overlay 的 VaultSessionContract 有 SETUP_COMPLETION、SetupCompletionRequested、SetupStatusRestored、setupAttemptIdentity 等接口碎片，现行可恢复完整基线缺少配套 reducer/runtime/测试。它们保留在 candidates/pre-T0647-fragments/，没有把孤立模型字段接入正式运行代码。此处是具体未恢复功能缺口，而不是“已集成”。另外旧 deferCandidateValidation/singleCandidateValidation 探针接口已由完整快照中的受信来源与 SessionCandidateAdmission 路径演进替代，不能把旧绕行开关直接覆盖回新安全流程。
- 云端独立 diagnostic build-copy 当前不可用；其中未同步差异和 code14 override 不冒称已导入。本机可验证诊断源码、精确 BOM admission 与本机完整恢复归档已保全。

因此本轮完成的是全部现存可访问、可核验分支/源码/文档的实际合并与单正式工作树整理；上述确已丢失的历史内容不能伪造为“最全已找回”。后续只在正式 main 开发，先查看本报告、AGENTS.md 与各模块当前边界，设备测试继续暂停。
