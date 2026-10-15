# 主动导出脱敏技术 ERROR 日志：云端实现与验证

## 范围与依据

真实远端基线：`development/cloud-followup-20261013`，`c45a18faa65cee0b1e466e59adb8efdfb4078e1a`。当前目录为该开发工作的源码快照，本报告不把它描述为恢复出的 Git 历史。

依据原始文档 `01_Product_Requirements.md` PR-21/§10、`04_Technical_Requirements.md` TR-14/§10，以及 `05_Test_and_Acceptance_Strategy.md` §7 T-P01–05/FI-24。未取得外部九份视觉 UI 规范，因此仅沿用仓库现有 SkinKit/Settings 组件，不宣称完成外部视觉验收。

本组闭环：用户进入 Settings → Diagnostics，主动准备已有技术 ERROR 日志的重新脱敏快照，主动在一次系统 `ACTION_CREATE_DOCUMENT`（`text/plain`）选择目标，写入一次并展示真实终态。选择云文件提供方可能触发该提供方自己的云同步，界面在操作前明确说明。没有自动上传、不导出不会改变服务功能。

## 安全边界

- 仅检查应用私有 `noBackupFilesDir/technical_errors` 的当天及前六个 UTC 日桶，最多最新五个精确命名的 `.log` 文件。不枚举功能存储、编辑、分享、媒体、Vault 或任何子目录。
- 捕获文件初始 extent；输入读取总预算与输出快照均不超过 256 KiB。丢弃超长、非 ASCII、截断尾行、未知枚举、额外字段、错误数字、文件日期不匹配或未来时间的记录。对时间戳、ERROR、ErrorModule、ErrorCode、正 Int API、0 或 100–599 HTTP 逐项解析后重新序列化，绝不原样复制。
- 文件/目录符号链接、非普通文件、读取/关闭异常、已选文件删除/替换/截断等失败时丢弃整个准备结果。追加只允许已捕获完整行参与快照。源日志不删除、不修改。
- 快照是不可变 String，写入时使用本次独立字节数组并在结束后清零；不保存 URI、request、快照或重试状态。不抓 logcat、不记录 Throwable/message、页面内容、URL、Cookie、Token、路径或用户文件名，也不把既有 InitializationProbe 字段添加到日志。
- 导出端口独立于媒体 AndroidExportPort。只接收本次全新 UUID registry key 对应结果，必须是 content/document URI，并同时具有结果 WRITE 标志及系统实际 WRITE 授权。无持久 grant、无文件路径输入、无 LibraryShareProvider、无新增存储权限。
- 结果只在当前 Activity 已恢复时交付一次。旧 request、重复 callback、退出、重建、后台准备/写入均不能重放。系统 picker 导致的暂停只保留当前等待选择；选择返回前不写。
- 成功仅表示 write、flush 和 close 实际全部成功，不等于云同步已完成或 provider 已持久化到物理介质。写入失败或取消可能留下空/部分目标，界面明确提示。没有无条件删除用户目标、自动重写、分享或网络发送。

## 取消、deadline 与不确定资源

每次外部 open/write/flush 在同一锁下准入，准入是操作开始的线性化点；取消或超时撤销后续准入。已经准入的那一次调用仍可能稍后执行或返回，不能声称强制中止 Binder/外部 I/O、撤回写入或原子回滚。退出始终允许；close 作为必要清理可继续。

准备/写入阶段 30 秒 deadline、等待选择 5 分钟 deadline，使用单调时钟与代际 timer。超时进入 TIMED_OUT，不被迟到成功覆盖。若底层调用或关闭尚未确认结束，另标记 release-unconfirmed 并隔离新导出；阶段终态与资源释放证据分开。

Android 进程共享一个 worker 和 fence，Activity 重建不创建更多工作线程或绕过隔离。真实输出 close 成功且 worker 排空后才释放。输出 provider close 抛异常时保留本进程隔离；不能靠“调用过 close”推断释放。快照输入 reader close 异常则返回准备 Failed，并不属于输出 provider 的永久隔离保证。

系统无法强制保证任意 provider 在 deadline 内停止或关闭。本实现保证请求及时终止、后续准入被撤销、UI 仍可离开，并将未释放资源如实隔离；真实 SAF/Binder/provider/OEM 资源释放仍需设备验证。

## 验证

所有导出测试均使用合成临时文件与 fake port，未向真实 SAF 目标、第三方或设备发送日志。

1. 定向编译和 JVM：`./gradlew :app:testDebugUnitTest --tests 'com.easysavevideo.app.diagnostics.*' --tests 'com.easysavevideo.app.ui.shell.Settings*' --offline`，真实执行 test，76/76，0 失败（当时本组新增 54，既有 InitializationProbe 12、Settings 10）。
2. 完整 App 单测 + Release Kotlin 编译 + Debug lint：`:app:testDebugUnitTest :app:compileReleaseKotlin :app:lintDebug --offline`，三项均实际执行，BUILD SUCCESSFUL。该次 168 suites、1486 tests、0 failures/errors/skipped；Debug lint 0 error、60 warnings、8 hints。diagnostics 新文件无 lint 条目，不据此宣称其他警告均为已核验的历史基线。
3. 追加独立审阅建议的两项回归后：`:app:testDebugUnitTest --offline`，test 与 compileDebugUnitTestKotlin 实际重新执行，最终 168 suites、1488 tests、0 failures/errors/skipped。该次生产 Debug 编译 UP-TO-DATE（生产源码未变），3 executed / 48 up-to-date。
4. 最终新增诊断组为 reader 32、controller 20、picker policy/delivery 4，共 56/56；包含敏感诱饵 URL/path/Cookie/Token/PIN/password/key/title/body、严格语法/数字/日期/尺寸/截尾、I/O/文件身份变化、读取及输出关闭失败、重复/错 request/迟到/重建、部分写、flush/close 失败、跨 Activity 隔离、单调 deadline 和旧 timer。
5. EN/ZH `diagnostics.xml` 17 个键一致、无重复；XML 解析通过。
6. Release lint：`:app:lintRelease --offline` 实际执行并成功（5 executed / 36 up-to-date）；0 error、60 warnings、8 hints。该次 compileReleaseKotlin UP-TO-DATE，生产源码保持上一轮实际成功编译的内容。

脱敏证据：`sensitiveBaitInAnyExtraOrUnknownFieldIsDiscarded` 将十类明确合成诱饵分别注入额外字段、前缀、模块与错误码，验证输出严格等于唯一合法重新序列化记录，并对每项诱饵执行不包含断言。`exactNamesOnlyAndNoFunctionalStorageTraversal` 验证非目标文件与子目录中的合成敏感内容不进入快照且原样留存。控制器故障测试注入带诱饵的异常 message，验证用户可见状态不包含诱饵。测试通过只证明这些合成覆盖，不等于全业务日志隐私验收。

## 独立审查

独立只读审查发现并推动修复了初版取消准入边界、无限等待/provider 工作线程重建风险及 Android policy/delivery JVM 可测性缺口。修订复审未发现阻塞；追加输出 close 阻塞后超时排空、旧 timer 迟到两个回归场景。审阅者未执行真实 Android/模拟器操作。

## NOT_RUN / 不在本组验收内

- Android 真机、emulator-5556、真实 SAF 文件提供方、跨 OEM grant/Activity 重建/Binder 阻塞行为：NOT_RUN，按用户要求延后装机。
- 未组装 APK/AAB，未安装设备，未发布/上传；由父任务统一处理已授权仓库备份。
- 不宣称全项目产品验收、Release 运行时隐私审计、T-P01–05 全业务/Logcat 验收完成。
- 不解决 OPPO 编辑/分享未知根因，不修改既有门禁；不引入依赖、Vault 启用、数据迁移、本地导入或真实支付。

## 改动文件

- `app/src/main/kotlin/com/easysavevideo/app/MainActivity.kt`
- `app/src/main/kotlin/com/easysavevideo/app/ui/shell/SettingsNavigation.kt`
- `app/src/main/kotlin/com/easysavevideo/app/ui/shell/EsvSettingsScreen.kt`
- `app/src/main/kotlin/com/easysavevideo/app/diagnostics/DiagnosticSnapshotReader.kt`
- `app/src/main/kotlin/com/easysavevideo/app/diagnostics/DiagnosticExportController.kt`
- `app/src/main/kotlin/com/easysavevideo/app/diagnostics/AndroidDiagnosticExport.kt`
- `app/src/main/kotlin/com/easysavevideo/app/diagnostics/DiagnosticPickerPolicy.kt`
- `app/src/main/kotlin/com/easysavevideo/app/diagnostics/DiagnosticSettings.kt`
- `app/src/main/res/values/diagnostics.xml`
- `app/src/main/res/values-zh-rCN/diagnostics.xml`
- `app/src/test/kotlin/com/easysavevideo/app/diagnostics/DiagnosticSnapshotReaderTest.kt`
- `app/src/test/kotlin/com/easysavevideo/app/diagnostics/DiagnosticExportControllerTest.kt`
- `app/src/test/kotlin/com/easysavevideo/app/diagnostics/DiagnosticPickerPolicyTest.kt`
- `app/src/test/kotlin/com/easysavevideo/app/ui/shell/SettingsNavigationTest.kt`

以上 14 个实现、资源和测试文件；另新增本报告及对应脱敏证据。旧 timer 测试已额外断言旧回调不会新增 alarm，并在最终整套 App 单测重新执行通过。
