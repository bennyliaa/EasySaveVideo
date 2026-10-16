# ESV biometric 平台 / runtime 最终审查补充

时间：2026-10-16 12:43 UTC。

**结论：源码、fixed-data 回归、编译和 lint 边界 PASS。本范围无剩余 must-fix；真实平台验收仍未执行，入口保持 false。** 此补充关闭旧报告的 full/lint pending 项，不批准启用功能。

## 证据归属更正

旧 `SOURCE_FOCUSED_REVIEW.md` / `SOURCE_FOCUSED_EVIDENCE.json` 中标为 focused-second 的 9 份 XML，实际在复制前已经被 full 测试更新，为 12:33 UTC 的 full-final 结果。旧副本逐字节匹配中央 `full-final-xml/app`，不能继续当作 second 的 XML 证据。旧记录保留为历史，不覆盖修改。

本次已分别从中央冻结的 `focused-second-xml/app`（12:30 UTC）和 `full-final-xml/app`（12:33 UTC）核验九个 suite 的实际计数、timestamp 与 SHA-256。两轮均为本范围新增 65 项和既有 host/capture 38 项全部通过。修正只涉及来源标注，不需要重新执行测试，也不改变首轮失败历史。

准确副本分别在 `focused-second-verified-xml/` 和 `full-final-verified-xml/`，机器记录 `FINAL_PLATFORM_EVIDENCE.json` 给出每份中央路径、正确轮次、时间及哈希。本补充及该记录取代旧报告中的 XML 归属声明。

## 最终源码与执行身份

- FINAL32 manifest：57e9c5fc0c1d6ccdcbc7e7ccce873e9386438b6d83c4846ea696fa97275e016c。本次再次逐文件核验 32 个实际目标的大小及 SHA-256，全匹配。
- LOCAL2801 manifest：83ce6116b0ec42164d01b624d11e19311af6e0ea8fe427c31c9b4b0f57cff30b。全量源文件和 full XML 总计由独立 owner reviewer 复算，本补充读取其 `BIO_FINAL_OWNER_REVIEW.md` 终态及中央 totals，不冒称再次独立全量扫描。
- full-final.log：dee0d6c84c61a84dc5c445eee05c3f84944903e70fb1a93babd30752a9818fff；exit=0，BUILD SUCCESSFUL in 8m 36s，101 tasks 中 42 executed、59 up-to-date。
- 全 13 模块冻结总计 602 suites / 6,675 cases：6,673 pass、2 既有 optional-corpus skips、0 failures、0 errors。app 4,737 项无跳过。
- full 的 app:testDebugUnitTest、lintDebug、lintRelease 实际执行。该轮 Kotlin compile 为 UP-TO-DATE；同源码第二轮实际 debug/release 编译证据保持，不把缓存状态描述为本轮重新编译。
- 本范围两轮均实核：runtime 21、host source 7、prompt lifecycle 27、fixed cipher 5、exact alias 5，共 65。既有 capture Main 4、protected host 14、playback host 9、thumbnail host 11，共 38。
- 双 lint XML 实核各 86 Warning、10 Hint、0 Error/Fatal；不是零警告。两份 XML SHA-256 均为 ca70b22b4a680c5753329de8643e7be5bb0cc2555f9d5eae10f3e8793ee230ab，准确副本在 `full-final-verified-lint/`。中央 SARIF 总计由 owner reviewer 独立核验。

## 审查边界与剩余平台 gate

源码结论沿用原报告：exact CryptoObject/Cipher/BIOMETRIC 类型及一次性 crypto 绑定、同 dispatcher 提交/取消、负按钮终态、实际 start-return/callback/continuation drain、unknown 保留、exact host 原子撤权、窄系统 prompt focus 例外、双 reveal cover 门、unlock intent 到 pending 的原子 cover 交接、密码 fallback 的新 attempt 和有限双语状态均已核。owner/schema/CAS 结论以独立 owner 报告为准。本次没有更改源码、构建、KeyStore、设备或凭据。

这些测试不是一条真实端到端设备链：runtime 使用 fake auth/store；host 是源码契约；prompt lifecycle 运行生产通用状态机配合 fake platform；cipher 是固定公开 JCA/Tink 数据。入队阻塞测试支持当前 owned-work 不变量，但不声称确定性复现旧微窗口。

**NOT RUN**：真实强生物录入/逐次授权、真实 KeyStore 生成/删除/失效、真实两次系统 prompt 与 CryptoObject 返回、Android focus/旋转/锁屏/进程死亡、SQLite 崩溃与 unknown-write 恢复、FLAG_SECURE/最近任务/保护像素、媒体/Surface/native 实际退场。

app-owned drain 不证明 HAL/KeyStore 物理释放或安全擦除。unknown-alias 保留是进程内阻断，不是已实现的 UI 自动修复；没有跨安装、device-key loss 或坏 ledger 的新恢复能力。真实平台验收与入口启用仍需独立决定。
