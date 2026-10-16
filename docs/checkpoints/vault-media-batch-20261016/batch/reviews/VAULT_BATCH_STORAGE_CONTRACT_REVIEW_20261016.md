# Vault 一次目录批量复制：storage / SAF 最终合同独立审查

2026-10-16 07:33 UTC。只读现有单项源码、R4 设计和必要官方文档；没有改源码、构建、设备、DB 或真实凭据操作。

## 结论

R4 在本 storage / SAF / scalar 生命周期 scope 可进入三组实现。我提出的必须改项已在合同和 API 中闭合。此为设计可实施结论；不等于实际代码、Android provider 或测试通过。生产仍禁用，后续源码必须按这些精确限制复核。

冻结对象：
- CONTRACT.md：`ed75abb162a03d9931b13348e084ba9edea9750f34d3a7ef94652931f03233a6`
- API_FREEZE.md：`8afed55c1017e2bc2d9345850a7c3c6fd03f9fdb94df792fce7102015e6d0574`
- API_TOUCH_MAP.md：`c180694904ec2e84ca4613a43182ef3220f4d0fe89b4a14e79011df6d99c7035`
- TEST_MATRIX.md：`3d0a433f02f78d10f1d7e6689272c217ab4be3ab17ae7ea182278a897fb6a596`
- DESIGN_MANIFEST.json：`1bc9b3afe08471868d90317fecba6f736ec80a85cf9488bc71f59cdc8c57d822`

目录为 `work/vault-batch-export-design/`；清单所列7份设计文件的字节数和SHA-256全部重算匹配。

## 与实际旧 engine 的最小连接

1. 保留 VaultMediaRuntime 同 owner/single scalar COPY_OUT；从现 confirmCopy 提取同一私有传输过程，单项和批量共用。每轮只有一套源anchor/reader/sink/readback。有限队列不能循环调用旧 beginCopy 产生多个 picker。
2. 批次1–8项、一次临时 tree picker、不取持久grant。数量/八次run为本slice实现预算，不冒称产品原始上限。预算跨项不刷新；运行上限不构成底层阻塞IO已释放的证明。
3. 现16KiB envelope/14KiB payload限额不扩；v1旧记录保持，v2只增加批次及显式single OUTPUT_RETAINED。新增snapshot字段、DB或导出引擎均不在范围。真实worst-case codec/envelope须实现时测，不以设计算式冒称执行。
4. 所有 Vault 原件保留。完成子项不因后项失败/取消回滚；外部副本可在完成前可见，UI必须明确partial/manual边界。

## 已闭合的必须改项

### Scalar终态与真实退休

- VERIFIED_COPY→COMPLETE；非成功已确证终态/显式keep-skip按子项CANCELLED+cancelRequested记录，batch user-stop单独维护。PUBLISH_INTENT不能skip/cancel。
- row归档及active cursor推进只在terminal whole-CAS真实读回和actual drain后进行。原SQL unknown先inspect，不能换proposal或重建child。
- API retirePhoneTarget不能由UI枚举/DTO签发。固定store验证exact batch/row/target/outcome或single OUTPUT_RETAINED，拒pending mutation；adapter再核实际资源释放后永久撤权。未知owner不因队列继续被驱逐。
- 当前单项decisionAccepted原为launch级；新同run跨项必须在同gate内按active operation绑定并原子handover重置，不能让第一项成功屏蔽下一项Cancel，也不能通过新launch刷新deadline。矩阵已加入项1成功、项2复制中取消的真实runtime fake消费者。

### Tree与child身份

- 固定system ExternalStorageProvider / primary非根目录profile，返回真实read/write及有效权限；LOCAL_ONLY只是hint。tree目录mime/nonvirtual/create flag，child MP4/权限/直接父目录均须实际核验。
- isChildDocument只证明后代，直接子项须有界完整immediate-child查询；不靠URI前缀。null/loading/部分或超限查询失败关闭，不能向既有文件开写。
- CHILD_CREATE_INTENT先whole-CAS，provider创建至多一次；返回URI先入同adapter候选ledger，再核fresh-ID/local/direct-parent，再持久TARGET_BOUND，最后才能开writer。候选未通过验证不是删除/写入权限。
- create无返回或返回未持久而进程丢失，只能unknown/manual；禁止nonce/名称/扫描/差集猜归属、重建同op或自动续写。
- 重启同tree新授权是fresh request+selection epoch注册的新进程内witness，必须核canonical URI/authority/treeDocumentId/有效权限。原encrypted tree/grant record保持不变，旧ID不是权力，新witness也不复活旧contexts。

### Existing single 的同一 cleanup 缺口一并纳入

独立新finding见 `VAULT_PHONE_TARGET_IDENTITY_REVIEW_20261016.md`。单项旧cleanup的registry+phase+grant+drain不能证明外部文档incarnation，当前URI直接delete路径具有替代文件风险。R4明确同adapter修single与batch：present且只有URI/token/grant时不自动删；可独立确认absence，否则retain/manual。single新增显式OUTPUT_RETAINED作为持久非成功退出，避免单项被永久卡住；不是cleaned/copied，也不自动retry。未知最终CAS或实际IO仍不得退出。

这不声称每次provider删除后旧grant都有效。官方DocumentsProvider删除路径会revoke；直接FS变化、fresh同URI恢复选择、父tree覆盖等有不同前提，必须保持精准。没有声称设备复现，也未新增一般incarnation框架。

### 凭据恢复不死锁

并发live work/unknown mutation/drain才阻credential；锁定且真实drained的durable pending batch不阻现主密码/恢复码reset。reseal保batch密文；继续时新认证当前catalog、验证原object/generation不变，原selection revision仅留审计。不得因需解锁对账而禁止解锁所需的恢复。

## 实现后的最小核验

- same engine单项/批量不重导已完成、每项决策独立、一个run预算、错/迟到picker及cover即时撤权。
- create返回前后、bind CAS old/exact/unknown、每阶段停止及最终决定竞态；已知prefinal+realdrain才能显式retain/skip。
- 固定store退休事实/拒伪enum、未知owner强留、同URI替换和fresh重授权在single与batch均不delete替代目标。
- v1兼容、v2最大shape/真实envelope限额、immutable plan/tree/旧结果、inactive credential recovery。
- 所有旧单项/move/credential/recovery测试保留；source形状变化只做等价或加强的精确断言适配。

Android API31–36、真实tree grant/Activity重建、system provider、实际关闭/flush/readback、满盘/中断均继续设备NOT_RUN；host测试不能替代。官方参考：[SAF文档](https://developer.android.com/training/data-storage/shared/documents-files)、[ExternalStorageProvider](https://android.googlesource.com/platform/frameworks/base/+/master/packages/ExternalStorageProvider/src/com/android/externalstorage/ExternalStorageProvider.java)、[DocumentsProvider](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/DocumentsProvider.java)。
