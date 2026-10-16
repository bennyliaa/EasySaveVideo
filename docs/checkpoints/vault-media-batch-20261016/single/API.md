# Vault 单项媒体 API 与文件触点（拟议，非生产实现）

基线：92627321c1c41af54504d83eedbb4e850407926e。具体命名可实现时收敛，以下身份/authority/存储边界不可削弱。

## 同 runtime 入口

- `beginMoveToVault(source: MediaReadRef, hostClaim: VaultHostClaim): VaultMediaWorkflow?`：runtime 发 operationId/sequence；UI 传入的 ref 不是读取或删除 authority。
- `confirmMove(workflow)`：精确当前源与可见说明绑定；单活动媒体工作流；未完成 credential/recovery work 时 BUSY。
- `revealCatalog()`：替代 empty-only consumer，同时兼容空库；返回受 epoch 保护的 metadata snapshot。
- `openMediaRead(ref: VaultMediaReadRef, purpose: INTERNAL_VERIFY | CONTROLLED_READ | EXPORT)`：仅 runtime 内部调用。`VaultMediaReadRef(vaultId, objectId, objectGeneration, catalogRevision)` 是选择身份，不是能力。
- `beginCopyToPhoneFiles(ref, hostClaim)` → 单次 CREATE_DOCUMENT → `acceptCopyTarget(workflow, ownedTarget)` → `confirmCopy(workflow)`；仅Android picker adapter处理exact URI/grant，本机性验核独立。公开runtime/UI不接受路径/provider字符串；无自动复制。
- `cancelMedia(workflow)` / `recoverMediaAfterExplicitUnlock()` / `mediaDrainState()`；取消撤权与返回的真实 release 分开。
- 扩展 `VaultPresentationPort`：`presentCatalog(...)`、bounded technical media status；遮盖仍走原 cover generation/actual hidden ACK。

## 同 authentication owner 的 opaque 能力

- `VaultAuthenticatedCatalog`：有限不可变媒体描述，只能由认证 owner 返回；data class 本身不作为 proof。
- `VaultMediaReadCapability`：same-owner registry identity；`authenticatedSize`、`readAt(offset, callerBuffer, range, context)`、`revoke()`、`closeAndObserve(): RELEASED | UNCONFIRMED`。不得返回原始 stream/channel/keyset/FD；runtime 掌控 final publication gate。
- `prepareMediaImport(root, exactSnapshot, sourceEvidence, operation, context): VaultMediaMutation`：新对象独立 streaming key；mutation 注册其 exact snapshot fingerprint、context cancellation lineage、immutable deadlines、目标元数据。
- `validateMediaMutation(expected, mutation, context): VaultMediaProposal`：store 必须调用同注入 auth owner验证，不能调用 proof 自证。
- `authenticateCatalog(root, snapshot, context)` / `authenticateMediaJournal(root, snapshot, context)` / `sealMediaJournal(root, expected, next, context)`：journal 不进 catalogFingerprint，但进完整 snapshotFingerprint/CAS。
- `verifyFinalMedia(mutation, ownedCiphertextFile, context)`：完整认证到 discard，返回 owner-registered finalized file proof；file proof只证明 exact bytes，不证明 DB commit。
- 现 credential reseal改为 parse/authenticated catalog → preserve media →更新credential fields；原 journal envelope原样保留。

## 同 Vault store/file owner

- `VaultStagedData` 追加 optional `mediaJournalEnvelope`, `mediaJournalRevision`（snapshot wire新版本，保留legacy decode）；catalog媒体明文结构仅在crypto/authenticated层，不复制到SQLite明文列。
- `compareAndSwapMediaJournal(expected, ownedMutation, context): VaultStoredSnapshot`。
- `commitMedia(expected, ownedMediaMutation, finalizedFileProof, context): VaultMediaCommitResult`；严格 expected/proposed exact readback。UNKNOWN 持久保留同proposal，不让新mutation越过。每阶段proof绑定exact phase snapshot/bytes/context，不自动rebase。
- `CommittedMoveReceipt` 为 store私有registry opaque identity，绑定 vault/object/generation、catalog commit bytes、原 MediaReadRef、operation、file identity/evidence。只有已认证 exact catalog+file commit且durable target pin存在可发；journal phase不能发。pin绑定operation/object/generation，保留到exact普通DELETED及cache/native retirement ACK；所有cleanup/catalog/credential路径必须尊重pin。
- `validateCommittedMove(receipt, exactSourceRef, operation)` 为同实例 bounded registry验证，无SQL/crypto/blocking IO；Library bridge注入它且在正确锁顺序使用。跨owner锁不得相互持锁回调。重启 receipt不可反序列化自授，重解锁+重新认证对账后发新receipt。
- `VaultMediaFiles` 是 AndroidVaultStore 的 subordinate，复用 vault noBackup目录与lifetime owner：create-new staging、force、rename final、持有immutable file identity、bounded readback、exact-owned cleanup。无调用者路径、无目录猜测清理、无第二DB。

## 现普通 Library owner 的窄桥

- `reserveVaultMove(ref, operation): MoveReservation`：同现DB/fileLock持久intent+MediaLeaseRegistry pending writer；restart gating必须来自durable intent。
- `acquireVaultMoveSource(reservation): MoveSourceCapability?`：核exact ref/READY/representation后只有exclusive writer可取得；返回owned stream+既有size/SHA/有限metadata，无路径。
- `retireOrdinaryPresentation(ref, operation, onActuallyRetired)`：原preview/list/session/page-LRU exact ref失效与frame.close；native/read owners实际释放后才返回稳定ACK。不能以cancel(item)代替purge。
- `commitVaultMoveCleanup(reservation, committedReceipt): DeleteOutcome`：proof校验后持久DELETE_PENDING并沿现unlink+directorysync+DELETED机制。distinct move intent不能伪装ConfirmedDeleteRequest(confirmed=true)。
- `cancelUncommittedVaultMove(reservation)`：先确认无已commit目标，持久取消才释放queuedintent。已commit只走cleanup，不能恢复普通可见性。
- schema最小additive operation表/约束迁移，保留ordinary item/representation种类；不会造一个Vault representation进ordinary库。

## Destination-only复用

从现 AndroidExportPort 抽出SAF exact target helper（MediaStore可保留选项但不是替代），依赖 `TargetJournal` 窄接口（reserve/exactBind/get）、有限本机profile验证和 owned handles；普通adapter仍用AndroidExportJournal，Vault adapter用same VaultStore encrypted journal。helper不openSource、不持有root、不恢复authenticated读权。

复用 `ExportEvidence`, `ExportOutput`, `ExportRelease`, `ExportCleanup`, `ExportReconciliation` 这些平台中立值与技术状态；若将full verify loop抽出，buffer必须finally清零且checkpoint在write admission前，普通行为回归保持。不要泛化整个ordinary source/codec/recovery链只为复用。

Vault单项状态固定：PREPARED → SOURCE_VALIDATED → CREATE_INTENT → COPYING → VERIFYING → PUBLISH_INTENT → SUCCEEDED；失败落CLEANUP_PENDING/RECONCILIATION_REQUIRED。PREPARED recovery不自动run。inflight/write/publication token绑定原runtime gate；lock后只target-only cleanup/既有决定对账。

## 预计文件触点与实施顺序

1. 共享纯合同/codec：VaultRuntimePorts.kt、VaultCryptoWire.kt、VaultSnapshotCodec.kt、新VaultMediaContracts/CatalogWire/JournalWire与JVM fake tests。
2. crypto：AndroidVaultAuthentication.kt、VaultTinkKeysets.kt或窄VaultTinkStreamingKeysets.kt（独立固定profile）、VAULT_RUNTIME_SOURCE_SCOPE.json及static负例。保持现root registry，不另建auth系统。
3. store：AndroidVaultStore.kt、AndroidVaultDatabase.kt（same owner扩展）、新VaultMediaFiles + exact CAS/file receipts、store codec/recovery tests。
4. ordinary桥：AndroidSaveStorage.kt、AndroidLibraryRepository.kt、LibrarySchema.kt、最小缩略图purge/preview退休ACK；原ordinary功能全回归。
5. same runtime/host/UI：VaultRuntime.kt、VaultProtectedHost.kt、AndroidVaultPresentationOwner.kt、VaultRuntimeUi.kt、LibraryScreen.kt与Main连接；保留ENABLED=false。
6. sink：AndroidExportPort.kt的destination-only抽取及对应回归；Vault helper接同runtime工作流，不另立后台/解锁owner。

每步先fake与纯边界测试。主源码只在双复核批准合同/API后由父任务统一集成；此目录只是设计交付。真实Tink固定向量执行、SQLite Android持久性、认证、设备与生产启用不能从fake PASS推定。

## 三组不重叠写入责任（只在父任务放行后实施）

A 共享/runtime/UI（本设计worker唯一维护）：VaultRuntimePorts.kt；新VaultMediaContracts.kt、VaultMediaRuntime.kt、VaultMediaUi.kt；VaultRuntime.kt、VaultProtectedHost.kt、VaultRuntimeUi.kt、AndroidVaultPresentationOwner.kt、AndroidVaultRuntimeEntry.kt；LibraryScreen.kt/MainActivity.kt连线；strings与其runtime/host/fake测试。A负责共享签名，其他组只发必要签名需求不直接写Ports。

B crypto/catalog：crypto/AndroidVaultAuthentication.kt、VaultCryptoWire.kt、VaultTinkKeysets.kt或新VaultTinkStreamingKeysets.kt、新VaultMediaCatalogWire.kt、VaultMediaJournalWire.kt及crypto测试；VAULT_RUNTIME_SOURCE_SCOPE.json与static checker精准名单。不得写共享Ports、runtime、storage。

C store/files/普通桥/destination：vault/storage/下文件及新VaultMediaFiles；AndroidSaveStorage.kt、AndroidLibraryRepository.kt、LibrarySchema.kt和exact-source durable disposition/active-reference核验；AndroidExportPort.kt及destination-only helper、exact单项picker adapter；缩略图exact purge接口与其tests。C向A提供retirement callback，不自行改Main/LibraryScreen。普通ExportCoordinator/Request/Codec默认保持原样；需改必须先报告。

A在B/C实现前冻结精确共享声明；凡跨组字段/constructor变化由A集中合并一次。所有组各自overlay，父任务集成，禁止共享source并发编辑。

## 首个本机 SAF profile（固定，不建可配置provider框架）

一次CREATE_DOCUMENT真实回调；authority=com.android.externalstorage.documents；解析provider包com.android.externalstorage与类com.android.externalstorage.ExternalStorageProvider；system/updated-system且与android签名匹配，enabled/exported/grantUriPermissions与MANAGE_DOCUMENTS读写保护符合profile；documentId限primary卷，不解析为文件路径；read/write flags及当前grant；单个非directory/nonvirtual video/mp4；实际全readback。helper输出opaque LocalPhoneFileTarget capability，经registry核验后才可write。其它provider/OEM明确UNSUPPORTED，只有精确owned空目标可清理；LOCAL_ONLY仅chooser提示。该profile真实Android验证未执行。

历史边界：只要求原文件/当前展示/关联app缓存清理与防恢复复活；不将全历史BLOB清空、撤回用户既有外部副本或通用历史迁移作为移入前置。

重试签名不自动换generation：`recoverMediaAfterExplicitUnlock`只reconcile原operation/target；`beginMoveToVault`的新operation只能在原operation已确认未commit、旧owned输出真清理且源恢复可用后重新接受。
