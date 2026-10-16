# Vault 单项媒体连接合同（待双人复核，未授权生产启用）

Date: 2026-10-16. Expected source baseline: `92627321c1c41af54504d83eedbb4e850407926e`.
Source: `esv-runtime-copy-20261015/source`. 该副本没有 `.git`；基线来自 `ACTIVE_COORDINATION.json` 与同步清单，本文附所读文件实际 SHA-256，不冒称本地 Git HEAD 核验。

## 1. 最短交付范围与原文

一个用户选中的、已提交的普通 Library MP4 → 移入 Vault → 受控字节读取 → 明确复制到一次选择的本机 Files 目标 → 每个持久阶段中断后对账。一次只运行一个 Vault 媒体任务/读取者。生产入口仍为 false；不加批量、播放器、缩略图、分享、任意 cloud provider、自动导出或自动解锁。

- 原架构 `docs/requirement and design/02_Architecture_and_Data_Model.md:408–437` 明确：加密 staging → 完整性校验 → 提交 VaultObject → 更新索引 → 清理普通源/缓存；文件与数据库不共享事务，须 journal/commitId 对账。因此移入不是永久保留普通源的复制，也不是提前删除源。
- `04_Technical_Requirements.md:207–239` 要求成熟分块 AEAD、受控流、敏感索引加密、有限资源、锁定清理及明确导出。
- `docs/m5a4a/REQUIREMENTS_DECISION.md:13–19` 保留用户原句：“导出成功也保留原文件，导出的功能是把保险箱里面的文件复制到手机文件系统。”未批准 cloud-backed SAF 或分享。
- 首目标必须复用既有单项 `ACTION_CREATE_DOCUMENT` Files 链：一次目标选择，同一个 outstanding request、exact URI、实际读写 grant、target journal bind。不能用相册替代文件导出。批量的一次目录选择不在本段。普通副本不受 Vault 保护需在选择前说明。
- 本机性是独立于 URI/grant 的门：`EXTRA_LOCAL_ONLY` 只是 chooser 请求，不是数据驻留证明。目标 helper 仅接受经明确 adapter/provider/local-root 验证的本机目标；未知/cloud provider 在解密/写入前拒绝。首个有限profile：返回authority必须为com.android.externalstorage.documents；PackageManager解析包com.android.externalstorage、类com.android.externalstorage.ExternalStorageProvider、system/updated-system身份并与android包签名匹配，且enabled/exported/grantUriPermissions与MANAGE_DOCUMENTS读写保护符合固定profile；documentId只接受primary卷，绝不转换为路径；还须单个非directory/nonvirtual video/mp4、实际read/write grant和完整readback。其它/OEM不匹配明确UNSUPPORTED。此profile的Android/API/OEM证据未验；fake只能证明fail-closed，不能冒充本机性验收。不能把“用户选择任意 provider”当作云上传授权。

## 2. 已核现有接口，不能假装已经具备

1. `MediaReadRef(itemId, revision, representationGeneration)`；`AndroidSaveStorage.openLibraryMedia(ref)` → `AndroidLibraryRepository.acquireRead` 在同 storage lock 核 READY/ref/representation 后取得 `MediaLeaseRegistry` read lease，返回 size/SHA 与真实 owned stream。
2. `requestDelete(ConfirmedDeleteRequest)` 先持久 DELETE_PENDING/隐藏新读，再等原 readers 退场；`processPendingDeletes` 和 `OrdinaryMediaFiles.remove` 才执行 unlink+目录同步+DELETED。DELETE_PENDING 不是删除完成。
3. 现 `VaultRuntime.ReadLease` 仅保护 empty-catalog 展示；`StopPrivatePlaybackAndReaders` 仍为 Unit。`jobs`/`contexts`/`retiringRoots`/实际 cover ACK 已可复用；不能用 timeout 冒充资源释放。
4. `AndroidVaultAuthentication.Root` 是同实例私有 identity registry，绑定完整 catalogFingerprint；`verifyCatalog`/`resealCatalog` 硬编码 emptyCatalog。修改媒体必须一起改凭据/recovery/reset 路径，不能只添加 UI。
5. `AndroidVaultStore` → `AndroidVaultDatabase` 是唯一 Vault DB/lifetime lock owner；已有 exact expected/proposed CAS + reopen/readback + unknown fencing。只能在它下面加文件与媒体 journal，不另开 DB/root/session。
6. `ExportRequest`/`ExportSource` 硬绑定普通 MediaReadRef；`AndroidExportJournal` 把 ref/size/SHA 以普通明文 record 保存。禁止把 Vault 对象冒充普通项或写进该 journal。现 `ExportCoordinator.recover(PREPARED)` 会重启 source read，也缺 Vault sink-write/final-publish admission，不能原封直接套用。
7. `AndroidLibraryThumbnails.cancel(item)` 不等于缓存消失；native source lease 释放后 page LRU 仍可留 Bitmap。移入须实际移除该 ref 的页面/缓存并关闭 frame，收 UI retirement ACK。

## 3. 同 owner 的最小新增部件

- `VaultRuntime` 持有一个固定单项 media workflow/helper，复用原 gate、worker、clock、context、host claim、cleanup ACK；不新增可独立解锁的 coordinator。
- `AndroidVaultAuthentication` 注册 opaque media mutation/read capability；外部不得传 KeysetHandle、key bytes、路径、bool 解锁或自制 proof。
- `AndroidVaultStore` 增加 bounded encrypted media journal 与媒体 catalog CAS；其 subordinate `VaultMediaFiles` 仅处理内部随机 ID 的 immutable ciphertext staging/final 文件。
- `AndroidSaveStorage`/现 `AndroidLibraryRepository` 增加窄 move reservation/cleanup bridge，同 DB、fileLock、MediaLeaseRegistry。普通目录 move 不复用为 Vault move。
- 保留普通 ExportCoordinator/Request/Codec 外部 API 与语义。只抽 `AndroidExportPort` 的 destination-only SAF/MediaStore exact 目标接收、bind、写入、readback、publish、reconcile、cleanup helper，向它注入相应 owner 的 target journal。Vault 的 source/evidence/状态保留在同 Vault DB 加密 journal；不带入 ordinary folder/batch/share 入口。

### 普通源可移入性与元数据边界

首段对已提交源检查仍在读取、编辑、复制或分享的active owner；待真实退场或明确BUSY，不能用readers=0替代所有owner的真实release。app持有的临时share明文cache必须由原share owner精确撤销/清理；外部接收者或用户此前完成的普通导出副本不自动删除，也不要求能撤回才准移入。

最小必须清理普通原文件、当前普通列表/详情/preview/缩略图与关联app缓存；durable move disposition必须使全部ordinary admission/恢复入口不能重新读取或重新发布已移入source，历史receipt不能复活它。终态save/edit/export的opaque IDs、幂等receipt和来源历史语义可保留；不要求普遍迁移/清空save_tasks历史BLOB，不宣传全历史擦除。若当前UI实际展示该已移入item的敏感详情，按具体入口遮盖/移除，不造通用历史迁移系统。

## 4. 有限媒体格式与 catalog 兼容

- 媒体仅现有已验证 MP4；复用 `ExportLimits.MAX_BYTES = 2 GiB`、`BUFFER_BYTES = 64 KiB`。拒绝 0、溢出、越界；不整文件入 heap，不落明文临时文件。
- 新 raw file 是 Tink `AES256_GCM_HKDF_1MB` 密文，逻辑 offset=0，无自创 chunk crypto、无拼接 ESV header/trailer、无密文断点续写。原operation在UNKNOWN/cleanup未确认时只对账原target，不重seal、不换generation/key/output。只有确认未commit、旧owned输出实际清理且普通源恢复可用之后，新显式operation才可新generation/keyset/output。
- 每对象/generation 独立 streaming keyset，由既有 root AES256_GCM AEAD 加密包装；仅指定 profile、一个 ENABLED primary key，canonical encrypted envelope，沿用 16 KiB envelope 上限；无 AndroidKeysetManager/cleartext fallback。
- 稳定 AAD 为 version/purpose/vault/rootVersion/objectId/generation/mediaRole/profile/预先已知的 plaintext length+SHA；不能用 ciphertext hash 自绑定，不能放易变 auth/presentation epoch 或 catalogRevision。
- catalog wire v3 保留现 v1/v2 credential 字段，增加 authenticated media entries：opaque object/generation、格式/profile、plaintext size/SHA、ciphertext exact extent/SHA、wrapped keyset、有限 MP4 metadata、finalization 标记。名称使用现 Library 名称字节约束。身份/源关联/媒体 metadata 均在加密域。
- 沿用 catalog ciphertext 64 KiB 与 snapshot 256 KiB 上限，不无故放大。由字节预算与最小记录长度约束 count，在任何分配前校验。容量不足在创建文件前失败，不静默丢条目。展示分页可沿用 Library MAX_PAGE_SIZE；这不是总媒体条数承诺。
- 旧 v1/v2 原字节继续认证；首次媒体变化显式写 v3，不以空库“迁移”。credential enrollment/activation/password reset 必须认证、保留全部媒体字段/key envelopes，只更改批准的凭据部分；不能再次调用 emptyCatalog 丢媒体。
- media journal 为同 VaultStoredSnapshot 的独立 encrypted envelope + journalRevision，不改每阶段 catalogFingerprint；其 AEAD 单独 purpose 绑定 vault/rootVersion、operationId/kind、journalRevision、预期 catalog/object 身份。纳完整 snapshotFingerprint/CAS。凭据变化保留 journal 原密文字节，使用时再认证。
- 每阶段journal/commit proof绑定exact phase snapshot、proposal bytes与原context lineage；不自动rebase到更新snapshot。journal 只记录进度，不能自行授权源删除。只维护一个活动工作流及足够的幂等完成记录；若压缩历史，持久单调序号拒绝已退休序号，不能让旧 operationId 再次创建文件。未知版本/超限/尾随字节 fail closed。

## 5. 移入事务，跨 DB 是可对账协议

1. 同 host 当前动作验证、精确源 ref、明确“移入完成后普通源删除”的确认，注册不可伪造 workflow。持久加密 PREPARED journal，再由普通 owner 持久 move intent。源 ref/gen 不变；相同 operation 重放无重复文件，冲突拒绝。
2. 普通 owner 的 durable move intent 阻止新读/rename/move/delete 越过；重启亦须先恢复 intent gate，不能只靠空的内存 registry。取消/失败可还原普通可用性，原字节未动。
3. 退场普通列表/preview/缩略图缓存并取得实际 ACK，再等原 native/read owners 真正释放；在同 MediaLeaseRegistry 得到 exclusive move source capability 后才能读源。不能拿普通 read lease 再等待自己持有的 writer。
4. Vault owner 先持久输出归属，create-new 密文 staging；逐块 source hash/count 验证并加密，成功 close 完成末 tag，force/fsync/真实关闭。取消、源变化、零进展或异常均不提交。
5. 重新从已持有 immutable 文件身份解密完整验证到 discard sink，确认 plaintext count/SHA、密文 exact extent/SHA、最终段与 EOF，关闭真实 reader。原源仍在。
6. 同私有 FS 原子 staging→final，force/目录同步后，由现 DB owner exact CAS 发布新 encrypted catalog + COMMITTED_SOURCE_PENDING journal；不声称文件+SQL 是一个物理事务。旧 catalog 可见性在 CAS 前保持。
7. 提交同时形成durable target pin，绑定operation/object/generation，保留至exact普通DELETED且关联cache/native retirement ACK均成立；任何final-file cleanup、后续catalog/credential路径均不得删除/替换pinned对象。receipt registry必须核验该pin，而非仅信journal COMMITTED字样。只有同 owner 注册且 exact durable file/catalog readback 成立的 `CommittedMoveReceipt` 才可让普通 bridge 在同 reservation/ref 下写 DELETE_PENDING 并 unlink。外来 proof、自报 bool、只有 journal 字样均拒绝。完成须普通 DELETED + owned source/native/cache 全释放。
8. catalog 变化后旧 root/catalog binding 立即无效；本段采用强制遮盖/锁定，再次用户解锁取得新 binding。锁后仅已登记 exact receipt 的非解密源 cleanup 可继续；Vault journal terminal 写回可等下次解锁对账。不得为记状态重建 root。
9. UI 严分“加密提交完成、普通副本清理待完成”和“移入完成”。源清理失败保留密文、封住新普通读取，不能宣称私密迁移完成；本段不宣称擦除闪存、JVM/GPU 残留或用户此前主动导出的副本。

## 6. 受控读取与真实 drain

- 开读需要同 runtime 当前 root、catalog/object exact revision/generation、foreground/route/presentation epoch、单 reader admission、live context。先认证 catalog，再核实际 extent，显式读取非空媒体最后一个字节验证 final segment；size 只来自认证 metadata，不能信任裸 Tink size() 或 seek EOF。
- capability 只给 bounded `readAt`/size，不泄露 Tink stream/channel/keyset/FD。每次 position+read 同 reader IO mutex；前置 admission → 私有 64 KiB scratch → 后置同 runtime revocation gate 发布到 caller。任何异常整块 scratch 清零/丢弃，caller buffer 不变；EOF 和 offset+length 用 checked long；zero-progress 有界失败（沿用现 export 最多 8 次）。
- `StopPrivatePlaybackAndReaders` 必须同步撤销全部 media admission，再请求 cancel/close。gate 只包注册/撤销和最多64KiB发布copy，不跨Tink、文件IO或close；reader在可取消worker排队前注册，排队失败也保留exact owner直到close。已登记 in-flight IO 回来后才可退场，锁后不得发起新 read/seek/write 或晚发布 plaintext。
- stream.close 不能证明撤权，Tink 可留下 cached plaintext。只有 in-flight=0、所有真实 cipher/channel/source/sink close confirmed、app buffers cleared、UI hidden ACK 后才清 cleanup fence。timeout/throw/unknown 保留原 owner，禁止替换 owner 绕过。
- 约 2 MiB/Tink seekable reader；首段最多一个 reader/media job，并复用原 bounded lane。受控 read API 通过不等于 Media3 播放/seek/native decoder 通过。

## 7. 单项复制导出

1. 当前解锁对象 + 一次本机 Files 目标选择，固定 operation/source generation；Vault 原件在成功/失败/取消/中断各分支均保留。
2. 首 pass 完整 authenticated read 到 discard，阻断未访问中段篡改在外部写入前漏过。第二 pass 使用同打开的 immutable 文件身份/generation 解密复制，不能仅按相同路径重开。无明文落盘 staging。
3. target journal 在同 Vault encrypted journal；复用 `acceptSafResult` 的 exact outstanding-request、content document、读写 flags、实际 grant 检查以及先 bind 后写。目标 helper须另外核本机性。picker返回后首先保留exact target-only ownership，若此前后台/route事件撤权则遮盖并等待重新解锁；只重新认证该工作流，不重开第二次picker，不把旧root延长。目标已由picker创建而绑定不明时保留UNKNOWN/cleanup提示，不猜URI、不按名称删除、不新建第二输出。
   若用户在返回后取消或source已stale，只清该精确owned空/partial目标。无可确认target ownership时不擅删。
4. 每次 source read、sink write admission 与撤权共 gate；阻塞平台调用在 gate 外，已发起的调用可能在锁后返回，不能谎称收回已写外部字节。锁后只退场/精确 target cleanup 或完成已线性化决定，不再解密。
5. 逐块 count/SHA+完整EOF核对，writer flush/force/close 与 source close confirmed 后，目标完整 readback count/SHA，所有 handle release confirmed，方可原子争取最终 PUBLISH_INTENT 决定。该决定与 lock/cancel 的胜负明确；决定已成立则 TOO_LATE，不重做 source pass。
6. SAF从创建起可能可见，不宣称原子发布；只有完整readback、真实release与durable完成决定齐全才显示成功。readback不支持/读权限不足不能成功：记录未确认副本与cleanup待办；读取失败也不能冒充“不支持”。失败仅清本operation精确owned output，归属未知/close unknown不擅删。MediaStore可作可选目标但不替代本段Files链，其publish仍必须清IS_PENDING后核验。
7. process death 不自动从 PREPARED 重读/重导出。重启仍锁定；重解锁认证 journal 后只对账/cleanup，若用户确需重新复制则新显式请求。SAF瞬时grant绝不由URI字符串复原；失去授权则待重新解锁+用户重新授权同一精确目标，仅对账/cleanup，不自动重新复制。

## 8. 预算、互斥与最小 UI 实际调用链

- authentication 135s 仅用于认证，不包住2GiB媒体IO。本段单media operation采用独立30min单调硬预算；任何既有session/foreground/route失效立即撤销，其预算不延长解锁会话。DB/cleanup继续沿现10s/5s预算；到期只撤销acceptance，不能当平台线程已停止。该媒体预算为明确可调实现常量，非产品自动锁设置。
- media workflow与credential enrollment/reset/mutation互斥；read/export/import共享一个媒体admission。每chunk只检查epoch/context/registered identity，不重复KDF或全catalog解密。
- commit前cancel：撤销新IO、等待真实drain、精确清staging/目标，普通源保持；import catalog commit赢得final gate后cancel不能回滚密文或重新开放普通源，只完成receipt约束下的源cleanup。export完成决定后同理TOO_LATE，但不再新读。
- LibraryScreen单项动作“移入保险箱” → Main当前selection/ref → 原VaultProtectedHost（先退普通preview/thumbnail，取得root claim）→ setup/unlock → 明确移入确认 → VaultRuntime media workflow → same crypto/store/source桥 → 状态更新。源ref跨认证等待必须重新校验。
- VaultRuntimePage展示同epoch authenticated catalog item，单项“复制到手机文件” → host action closure → runtime issued export workflow → 主Activity既有single CREATE_DOCUMENT host → exact request/grant回调 → protected host重新核presence（必要时解锁）→同runtime读/写/verify →结果。按钮须调用真实ports；不能只造可调用模型。production=false时这些入口仍不可对真实用户启用。
- 受控read实际由import readback、export双pass调用；可单独fixed-fixture consumer证明bounded read行为，但本段不显示伪播放器。

## 9. 中断矩阵与必须证据

- PREPARED/源排队/未完整 staging：原普通源保留；只清确属本 operation 的不完整密文，释放 durable reservation。
- final 文件存在、catalog仍旧：只在认证对账确认未引用后清理；未知/mismatch 隔离，原源不动。
- CAS unknown：保留源+目标+原 operation，先 exact readback；不按异常猜 rollback、不新 operation 重试。
- catalog exact committed：完整 file/catalog 认证后可重新签发同 owner cleanup receipt；普通 DELETE_PENDING/DELETED 对账，绝不回退删除 Vault 原件。
- locked/process restart：先技术状态与遮盖；无自动 auth/媒体读。外部 target plaintext 可能残留须诚实提示，授权不足/归属未知保留 recovery-required。

测试最小集合：v1/v2兼容、v3合法/畸形/溢出/容量；跨 vault/object/generation/AAD/错误 key拒绝；首/中/末tag篡改、截断/附加字节、1MiB边界、0进展EOF；异常 caller buffer不变；排队后lock无crypto、阻塞read后lock无晚发布、真实close未回不得ACK；source stale/delete/thumbnail缓存竞态；每个持久边界kill；重复op与CAS unknown；两pass未访问中段坏字节在外写前拒绝；凭据enroll/activate/reset前后媒体与pending journal保留；旧root不得用于新catalog；导出target create/bind/write/readback/publish/cleanup每点失败和撤销。

允许证据仅源码/static contract、公开固定fixture/key与fake端状态测试；后续若执行真实 Tink wrapper 固定向量须明确单列结果。此设计未执行真实 Tink、SQLite、Android认证/Keystore、用户凭据、APK或设备；历史被拒 probe 不执行。新Streaming FQN只按精确source scope复核，不改旧dependency gate的历史含义。最终生产仍 false，平台安全/耐久性/视觉与设备验收待单独证明。

## 复核与冻结

2026-10-16：crypto/runtime与storage/source/SAF两路复核均认为本有限方向可实施。最终补入durable target pin、同op只对账而新显式op才fresh、exact phase snapshot proof三项；这是设计准许进入实现，不是生产启用/平台验收。基线所选65文件均与已同步source manifest SHA-256一致，0 mismatch。
