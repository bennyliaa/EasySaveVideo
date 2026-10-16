# G1/G2 最终独立审查结论

**PASS：最小实现、源码冻结和实际中央编译/JVM/lint 证据足以封存本次代码切片。生产入口保持 false。**

## 确证修复

1. G1 真实恢复 reseal 会按新 catalogRevision AAD 重封 catalog-keyset envelope；原 shape equality 与此冲突。现在只删除这个错误的字节相等要求，保持同 owner registry、exact before snapshot、lineage/deadline、全记录 CAS、安装标记、retry/media 保留及每一有限 slot 转换。实际 fixed-keyset crypto → registry → validator → decrypt 连接测试通过。
2. G2 原 device-key/marker/ledger/store 故障会失去原因或落成普通取消/泛化不可用。现在可信有限分类经过原 fail-closed 链到错误展示，保留首个确认原因，并修复 startup state=null 后生命周期 cover 丢因。普通取消、超时、拒绝、未知 release 没有变成新授权或 repair 策略。
3. 增加 enum 导致 media journal encoder 穷举编译失败，已用明确旧码映射修复，无 schema/code 扩张。当前真实媒体调用没有新分类 producer；未来若使其进入 row，编码粗化与内存 whole-record equality 的限制仍须解决，详见增量审查。

## 最终冻结身份

- 16 target manifest：`07a6b76b4b20bfa050d8fbc614fb6c0e41e557455693dfc969f768697f96133f`。
- 完整 2,792 source manifest：`81b6c837adf1241f80f6357d22ea0e43638d136c9b6b31fd75122b6dfeb3bb7c`。
- 独立读取并重算全部 2,792 文件，0 mismatch。相对 2,788 条基线仅 12 个声明旧文件改变，无丢失，4 个新测试文件。
- 未改 reducer、依赖、Gradle、安全设置、schema 或生产 gate。代码/文档/测试差异限于声明范围。

## 实际执行复核

中央 `full-final` exit 0，`BUILD SUCCESSFUL in 8m 12s`。

- 13 个 JVM test task 本轮实际执行。
- 独立 XML 汇总 595 suites / 6,591 testcase：6,589 passed、2 skipped、0 failure、0 error。属性计数与节点数一致。
- 两个 skip 来自未改动的 FrozenCorpusHostTest.same100FrozenBodiesActualStaticB 和 FrozenRecoveryComponentTest.authored80MutationsActualJsoupComponent，均要求 ESV_OWNED_CORPUS 的既有 Assume 条件。没有把 skip 计为通过。
- G1 新 8 + G2 diagnostics 13 + UI contract 4 + wire compatibility 3，共 28 个新测试在本轮再次通过。
- debug/release Kotlin 本轮 UP-TO-DATE，证据来自此前同 16 target 的 `focused-second` 实际双编译成功；两轮来源字节一致。
- debug/release lint 本轮实际执行，按 SARIF result level / rule defaultConfiguration 解析，每个 variant 为 86 warning + 10 note、0 error。不是 warning-free。
- 首组合 `focused-first` 的编译失败保留为历史，不被后来成功覆盖或算成测试结果。

机器证据：`FINAL_FULL_EXECUTION_VERIFICATION.json`、`FINAL_FULL_XML_HASHES.json`；前轮范围见 `COMBINED_EXECUTION_REVIEW.md`。

## 不扩大结论

G1 测试执行真实 Tink reseal/wrap/decrypt，但使用公开固定 keyset，临时随机 IV；没有真实用户凭据或新持久 key。四个完整高层恢复业务 wrapper、Android SQLite/KeyStore 未执行。G2 实际 auth/runtime 与 fake device-Aead/store/View 相连；真实 Android PresentationOwner/Compose 像素末端只有编译/静态合同，不是设备认证验收。

本切片只修复 reseal/validator 接缝和可靠报错。坏 ledger、丢失或永久失效 device key、跨安装恢复与不可读 store 仍无受支持修复路径，密文保留且关闭访问。生物生产适配尚未实现，v2 目前只是已审设计；其设计测试矩阵不算已执行测试。

本 reviewer 全程只读代码、设计、hash、日志和 XML/SARIF，未自行执行代码/crypto、旧 denied probe、真实 key/认证/KeyStore/设备操作。
