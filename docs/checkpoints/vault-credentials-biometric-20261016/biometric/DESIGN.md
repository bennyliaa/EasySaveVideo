# 最小生物认证生产适配合同

版本：设计冻结 v2，2026-10-16。本文不是实现通过声明。

## 1. 复用边界

保留现有所有权链，不建立第二套 session/credential/cleanup owner：

- `AndroidVaultRuntimeEntry`：保留唯一 application binding、单 worker lane、计时器和 disabled gate。
- `VaultRuntime` + `VaultSessionReducer`：继续负责 reserve-before-auth、retry RESET 确认、authEpoch、presentationEpoch、自动锁、pendingRoot、cleanup fencing。
- `AndroidVaultAuthentication`：继续是唯一 root/proof/credential mutation registry；Tink KeysetHandle 不离开此 crypto 层及其现有 biometric leaf。
- `AndroidVaultBiometric`：继续使用系统 `android.hardware.biometrics.BiometricPrompt`；不加 AndroidX Biometric 依赖、不引入通用认证框架。
- `AndroidVaultKeystore`：继续使用既有固定 AES-256/GCM 配置和每次操作强生物 key；device alias 和 biometric alias 分离。
- `AndroidVaultStore`：继续走同一 frozen snapshot、credential mutation 校验、整行 CAS、精确读回及 unknown-write receipt。
- `VaultProtectedHost`、`AndroidVaultPresentationOwner`、既有 PrivateWindowGuard/root：继续负责当前 Activity/route 的输入和可见性保护，不能在普通 Compose callback 中直接显示 prompt 或授予 root。

Manifest 已声明 USE_BIOMETRIC，不需要新权限。Core 不引入 Android、Cipher、Context、Keystore 或 Tink 类型。

## 2. 当前已有内容与确实缺的内容

| 触点 | 当前事实 | 最小变化 |
| --- | --- | --- |
| AndroidVaultBiometric.kt:39–146 | wrap/unwrap 叶子；同一个 CryptoObject/Cipher；AAD 含 identity/slot/generation；实际 doFinal；Pending 区分取消与 platform terminal | 增加可 fake 的窄 prompt 边界、统一 prepare/start 异常分类、实际 terminal 通知和完整运行绑定；仍由现 leaf 处理真实平台对象 |
| AndroidVaultKeystore.kt:35–56, 90–112 | 显式创建生物 key；timeout=0、AUTH_BIOMETRIC_STRONG、enrollment invalidation；缺少有范围的退休/删除接口 | 只为经同-owner 退休核验的 exact biometric alias 增加删除及 readback；绝不能泛化成 prefix wipe 或 get-or-create |
| VaultRuntimePorts.kt | 只有密码和 recovery 方法；slot kind 已存在 BIOMETRIC | 增加最少的生物状态/异步操作 port、注册/激活/停用 mutation kind；返回已有 opaque root/mutation，不返回 bool 授权 |
| VaultCryptoWire.kt:121–141 | 只准 password/PIN + optional recovery，最多 2 槽；所有摘要/目录前缀依赖该 shape | 一致支持 primary + biometric + recovery，最多 3 槽；同身份/代次/状态/密文摘要纳入 catalog 与 snapshot fingerprint |
| VaultSnapshotCodec.kt:31, 118–188 | 编码最多 3 槽，但缺生物状态；旧 wire 1/2/3 有精确 canonical 规则 | 增加仅在生物状态存在时使用的新 wire 版本，保留旧字节；拒绝缺状态、重复槽、非法状态组合 |
| VaultMediaCatalogWire.kt | V1/V2 空目录与 V3 媒体目录；凭据变化保留媒体项 | 新凭据版本需升级相应 catalog 前缀/解码，不把有媒体目录转换为空目录；不得改 media key/object identity |
| AndroidVaultAuthentication.kt:674–752 | 恢复注册/reset 重建 slots，尚未考虑生物槽 | 改为只替换本次目标槽；所有不相关槽及生物状态保留，重置恢复码按现有合同处理 |
| VaultCredentialMutationValidation.kt:18–19 | 错误要求重封后的 catalog keyset envelope 等于旧字节 | 先解决本文引用的恢复缺陷 G1；保留同-owner proof 和完整前后状态核验，不以删除整类校验代替修复 |
| VaultRuntime.kt:646–689 | BeginExternalAuthentication 仅选择 password input | 当前请求中显式选择 PASSWORD/BIOMETRIC，绑定一个 reducer attempt；生物走既有 reserve/reset/pendingRoot 链 |
| VaultProtectedHost / CredentialRequest / RuntimeUi | 只有密码/恢复受保护动作 | 增加显式启用、解锁、停用按钮和精确 host request；返回路由或启动不得自动 prompt |

首次实现不增加跨安装恢复、设备凭据回退、自动录入跳转、root rotation、新后台作业、发布开关或新数据库 owner。

## 3. 必须绑定的身份

每个 prompt 在进入平台前冻结一份不可替换的运行绑定。它只是现 owner 的内部 operation record，不是持久授权 DTO：

1. operation purpose：ENROLL_WRAP、ENROLL_VERIFY、UNLOCK 三者之一；不同方向不可互用。
2. `VaultPersistentIdentity` 全部维度，包括 vault、installation、initialization operation、schema/root version 和 exact biometric alias。
3. slotId、slot generation、slot kind；unwrap 还绑定**本次实际持久化并读回**的 envelope 字节摘要与 immutable envelope 副本。
4. 当前完整 snapshot fingerprint、store revision、catalog revision。UNLOCK 额外绑定该 attempt 的 confirmed reservation、ledger revision 和 outstandingReservationId。
5. `VaultAttemptIdentity(processInstance, attemptId, authEpoch)`（UNLOCK），以及所属 runtime workflow/reference identity。注册步骤必须关联其新鲜密码认证的原流程，不接受任意已解锁状态枚举。
6. 当前 `VaultHostClaim` 的**对象身份**、Activity attachment、route identity、generation、input/action epoch、presentation generation；不持久化 Activity 或把 host bool 当认证。
7. 唯一 operation context 的取消 lineage 和原始绝对 deadline；child context 只能缩短。
8. 本次实际 prompt、CancellationSignal、callback object、提交的 CryptoObject 与 Cipher **引用身份**；一个结果只能完成一次。

两个验证层必须同时满足：

- 平台叶子检查 `result.cryptoObject === submitted`、`result.cryptoObject.cipher === preparedCipher`，以及 `authenticationType == BIOMETRIC`。不得用非空、相同算法、成功布尔值或另起一个 Cipher 代替。
- Authentication/runtime 检查上面 1–7 的全部 current 条件，实际 `Cipher.doFinal` + Tink parse/profile + catalog authentication 完成后才可生成已有 registry 中的 Root。未注册的外部 Root、一个 fake 接口自称成功、旧 callback 都不是权限。

AAD 继续沿用 `VaultCryptoWire.aad(identity, BIOMETRIC_ROOT, BIOMETRIC, slotId, generation)`；不能只靠 callback 里的 slot 数字。AAD 写入必须在提交 prompt 前完成，后续不得修改。解包必须验证当初取出的 bounded ciphertext 与 Tink 调用收到的 ciphertext 完全一致，且只能执行一次 decrypt。

官方 API 将 getCryptoObject 定义为该 transaction 提交的 crypto object；当前严格引用绑定无需放宽。认证本身不能替代加密操作。[Android AuthenticationResult](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.AuthenticationResult)、[CryptoObject](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.CryptoObject)

## 4. 最小持久化模型

在既有 VaultStagedData 和 catalog authenticated credential prefix 中新增有界 `biometricState`：

- NONE：无可用生物槽、slots 无 BIOMETRIC。
- PENDING：恰好一个 BIOMETRIC 槽；包装已写入，但尚未通过对持久化字节的独立真实解包验证。普通解锁不可选择。
- ACTIVE：恰好一个 BIOMETRIC 槽；可在新鲜用户解锁请求中使用。
- REVOKED：恰好一个退休 BIOMETRIC 槽；记录原 generation/envelope 供 exact alias cleanup 和审计核对，但绝不能解锁。允许之后在同一 alias 确认退休后替换为更高 generation 的 PENDING。

NONE/REVOKED 的区别不能由内存缓存或一个设置开关替代。REVOKED 保留密文不需要持有 root；它也不能声称已经物理删除硬件 key。新版本应明确记录 alias 清理是否仍需核验，或者在每次重启后的重新注册前进行只读 exact alias absence 检查。不得让一个 persisted `cleaned=true` 代替实际检查。

generation 使用本次新注册的 catalogRevision，必须严格大于该槽既有 generation；Long 上限 fail closed，禁止回绕。激活/撤销推进 catalogRevision，但不改已包装槽 generation/AAD。最多一个 primary、一个 biometric、一个 recovery，总数最多 3。`required=true` 继续是现有存储 profile invariant，不表示所有凭据都必须同时成功，也不能为了“可选”擅改为 false。

旧无生物数据仍编码/解码到原 v1/v2/v3 的完全相同字节。包含生物数据的新 snapshot 和 catalog version 必须绑定 state、slot generation、slotId、密文摘要；格式不支持时安全拒绝，禁止降级读成 password-only 以丢弃未知状态。Media catalog 保持原 entries、wrapped media keys、journal、指针和 source-cleanup 状态不变。

不要修改 `identity.biometricKeyAlias`，以免改变旧密码、ledger 或 media 的既有身份 AAD；复用已经随初始化 identity 持久记录的 exact alias。一个 alias 重用只允许在下述退休条件全部成立后发生。

## 5. 注册：密码仍是独立主路径

用户从受保护 Vault 页面明确选择启用。不存在 app 启动或设置 composition 自动创建 key 的路径。

1. 复用现 credential host 清屏/输入退休和 runtime workflow 门。无播放、缩略图、导出、恢复密码或未完 prompt；当前 route/foreground/capture guard 有效。
2. 新鲜验证现有密码/PIN，完成现 retry reservation → password auth → retry RESET CAS；流程不发布媒体。已有恢复码不得被替换或删除。
3. 在相同 authentication owner 中只读确认目标 alias 属于该 persisted identity；读 strong biometric availability。没有强生物、无录入或硬件暂时不可用时退出注册，密码与 ACTIVE recovery 原样保留，不打开系统录入 UI。
4. 无既有 alias 时可由独立获准的真实平台步骤 createBiometricKey。存在 alias 而无 ACTIVE 可用槽时，不能 `getOrCreate` 或直接覆盖：先执行第 7 节的 explicit exact-alias retirement；NONE + orphan 必须走其独立 ORPHAN_NONE 分支，不虚构槽/代次。首次 wrap 前没有新 durable slot，但 alias 已由既有 initialization identity 注册；崩溃只留下可识别的 orphan，重启不得自动创建、删除或复用它。
5. 在 `AndroidVaultAuthentication` 既有 Root registry 中取当前 root，`AndroidVaultBiometric.wrapRoot` 使用专用 ENROLL_WRAP prompt。每次使用强生物授权，只做一次 encrypt。root 继续仅由 crypto owner 持有；UI/runtime 不收到 KeysetHandle。
6. 实际包装成功、全部绑定仍有效后，同 owner 创建 `STAGE_BIOMETRIC` mutation：保留 primary/recovery/pendingPassword 的允许状态、媒体和 ledger；新 slot + PENDING、catalogRevision +1、重封 catalog。默认禁止与未完成 password reset 同时进行。
7. 走 existing CAS/write receipt，精确 readback；未知写入按原 receipt 对账，不生成另一份密文“重试”。退休已不适用于新 catalog revision 的旧 Root，**不能强行更新它的 catalogBinding 让它复活**。
8. 对实际持久化的 PENDING slot 启动第二次、不同的 ENROLL_VERIFY prompt，初始化**新的 decrypt Cipher 和新的 CryptoObject**，使用第二个短 deadline；整个 workflow 仍受原总 deadline 限制。解包只在 crypto owner 内得到暂存 KeysetHandle，验证 staged catalog 和 credential metadata 后注册 `ACTIVATE_BIOMETRIC` mutation。
9. 激活 mutation 只改 PENDING→ACTIVE 与必要 catalog reseal；slot 字节/generation 保持不变。CAS + exact readback 完成才显示“已启用”。验证 keyset 不得注册成普通 Root，不得重置用户失败次数或打开页面。结束后保持锁定，下一次解锁必须是新请求。

取消/超时/崩溃：

- key 创建前：无改动。
- key 已存在、未 stage：记录为未完成/orphan；保留密码和恢复码，下一次明确注册先核验/退休 exact alias。
- PENDING 已 stage：保留 PENDING 密文并解释未启用；从普通解锁排除。可在新鲜密码确认后重新走有界验证或撤销/重新注册，不能仅凭历史状态在重启后直接激活。
- ACTIVE 写入结果未知：沿旧 receipt 对账；不可显示已启用，不重复 prompt 写出不同 envelope。

这条流程复用现有 recovery enrollment 的“先 stage、读回、独立确认、activate”方式，不增加新 owner。允许新增一个有界 biometric workflow record 及少量 enum/method；不得让它绕过 runtime 的 job、context、host 和 cleanup 门。

## 6. 正常生物解锁

1. 用户显式点击“用生物识别解锁”；只有 ACTIVE 槽、健康账本/marker、当前 protected host 且清理完成时接受。可用性查询只能决定展示，不能授予 root。
2. `VaultRuntime` 在同一个 UNLOCK attempt 上记录认证方法 BIOMETRIC；继续既有 reducer reserve-before-auth/CAS 逻辑。一场系统 prompt 对应一次本地 reservation；`onAuthenticationFailed()` 是该 prompt 的非终结扫描，不另起 reservation，不重置 ledger。
3. 既有单 worker lane 校验 snapshot、ledger 与 confirmed reservation，准备实际 persisted slot 的 decrypt Cipher；prompt 及 UI callback 在绑定当前 Activity 的平台边界执行，不能阻塞 serial worker 等用户操作。
4. 返回结果在 owner lane 上继续验证，先确定 callback 未过期/取消、仍是同 request/slot/host，实际 decrypt/parse/profile 和 catalog 验证成功；必要读回必须与捕获的 snapshot 匹配。任何并发变更都拒绝旧完成，不跨 revision 自动重试。
5. 唯有 `AndroidVaultAuthentication` 登记 Root；runtime 放入既有 pendingRoot，再向 reducer 报 SESSION_ESTABLISHED。RESET CAS 精确确认后，既有代码才把 pendingRoot 移为 activeRoot。仍需独立当前 host 的 reveal 操作。
6. 错误、取消、无硬件、timeout、锁屏、离开 route、Activity 替换、enrollment invalidation 或 callback 不匹配都不创建 root；原 reservation 不回退。密码备用按钮只有在当前 prompt 的平台会话终结已观察、app-owned callback/continuation 全退场且没有 submission 不确定性后才开始新的密码 attempt，不能自动沿用生物认证结果。

不得把 optional biometric slot invalidation 映射成 reducer 的全局 KEY_INVALIDATED：该事件目前转为整个 Vault 的 RECOVERY_REQUIRED。应记录 slot-specific availability/reason，再用既有 CANCELLED / TEMPORARILY_UNAVAILABLE 结果结束本次 attempt，保留密码与恢复入口。全局 device key/marker/ledger 问题则走第 9 节分类阻断。

## 7. 撤销、alias 退休与重新注册

普通停用由用户显式点击，并使用受保护主密码确认；强生物不可用不能妨碍这条密码操作。复用 fresh password attempt 与同 owner mutation。

- 先撤销当前 prompt 的接受权限，要求该 alias 上所有 prompt 的实际会话终结已观察，app-owned callback 和 crypto continuation **全部退场**，且无未决 submission；清屏、媒体/read/root 退休仍按原链执行。
- `REVOKE_BIOMETRIC` 将 slot 状态原子改为 REVOKED，primary/recovery/ledger/media 不变，catalog 正常 reseal，CAS + exact readback。
- 此 durable 阻断生效后，才允许由后续独立获准步骤删除 exact `identity.biometricKeyAlias`。Authentication owner 验证该 alias、原 generation、退休 snapshot/receipt、实际平台会话终结及全部 app-owned crypto 工作已退场；Keystore port 不接受任意字符串或前缀删除。
- 删除失败/未知：REVOKED 状态仍禁止生物使用；保留 exact retirement intent，阻断重新注册，密码和健康 recovery 流程不受牵连。只读检查可确认确实不存在，不能通过盲目再 create 探测。
- 新注册必须确认旧 key absence、旧 callback/crypto continuation 无未完成、无 submission 不确定性、使用更高 generation，然后重新走两个 prompt。不得把“当前暂时无硬件”当成删除钥匙的授权。

### 7.1 NONE + 既有 alias 的 orphan 专用分支

pre-stage orphan 没有持久化 biometric slot，故 generation 必须是 N/A；不能虚构 REVOKED envelope，也不能走要求原槽 generation 的正常撤销 receipt。

只在用户明确重新启用/清理该未完成注册、当前 READY catalog 已由新鲜主密码实际验证、其 authenticated biometricState 确为 NONE 且无 BIOMETRIC slot 时，允许原 `AndroidVaultAuthentication` 在既有 owner 内签发并一次性消费窄 `ORPHAN_NONE` alias-retirement 许可。该许可绑定 exact root registry identity、完整 authenticated snapshot fingerprint、persisted Vault identity/exact biometric alias、原 context/deadline 和已观察的 prompt/owned-work drain；不是公开 DTO、自报 bool 或 arbitrary alias API。

实际删除前 runtime/store 重新确认完整 snapshot 仍 current，仍无 pending credential/media/alias 操作、无 store unknown write；NONE 仅来自 SQL shape 或 hasAlias 不足。删除 exact biometric alias、absence readback 及后续 create 同受原 runtime 排他工作门；删除不改 slots/catalog、不走伪 credential CAS、不触碰 device key。unknown 结果保留该 exact intent 及阻断，不因新 workflow/超时刷新预算，也不以 create 探测。确认 alias 不存在并且原操作已终结后，下一次明确注册才可从当前 catalogRevision 选择新的 generation。

这只是现 authentication owner 中一个受限操作分支，不建立新 owner/独立权限注册体系。无法建立以上证据时安全阻断并保留主密码/恢复码，不声称已可重新注册。

硬件错误、锁定、无录入、key invalidated 默认只更新 slot unavailable 的运行状态，不自动删除生物 key、恢复码或媒体。系统录入变化导致永久失效时，向用户说明先用密码登录，再明确重新启用。其依据是平台 enrollment invalidation 规则。[KeyGenParameterSpec.Builder](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setInvalidatedByBiometricEnrollment(boolean))

## 8. 中断、竞态及资源实际退休

现 leaf 的 `Pending.close()` 只取消接受并要求平台取消；`isPlatformTerminal()==false` 时不能当 cleanupComplete。必须补一个窄 terminal 通知/观察接口，纳入现 runtime `tryAcknowledgeCleanup` 和 host retirement 条件。

- Pending operation **先登记，后调用平台 authenticate**，覆盖同步 start failure/同步 fake callback；调用未返回不能丢失 handle。平台提交相位最少区分 PREPARED、SUBMITTING、SUBMITTED、TERMINAL_OBSERVED；acceptanceRevoked 和 app-owned drain 独立计数。
- 取消接受/timeout 与平台会话实际终结是两个事实。timer 和主动 CancellationSignal.cancel 只代表请求，不得置 terminal。
- **真实系统负按钮 OnClickListener 是另一条合法终结来源**：API31 的该通知已表示 dialog 完成 dismiss，注册了 listener 时平台不会再保证调用 onAuthenticationError。因此 exact 当前 prompt 的负按钮通知可记录 TERMINAL_OBSERVED/CANCELLED；不能永久等待不存在的 error 回调而卡住密码 fallback。
- success/error/系统负按钮通知都只是平台会话结果/终结证据，不等于 HAL 或 Keystore 的物理销毁回执。只有它们已到达，且本次 callback、crypto continuation、已产生 owned result 均退场、无 submission 不确定性，才能完成 app-owned prompt drain。不能将其命名成“硬件已安全擦除”。
- Android API31 对 authenticate 收到的已取消 CancellationSignal 可以直接返回且无 callback。接受权可立即撤销，但 **实际 signal.cancel 与 authenticate 提交必须在同一平台串行 dispatcher 上有序执行**：若尚未提交，确证 NOT_STARTED 并不调用 authenticate；一旦开始提交，signal.cancel 排在 start 返回之后。不能把 cancel-before-submit 留成无终结回调的 pending。
- factory/build/scheduler/dispatch 在确证 authenticate 从未被调用时失败可 NOT_STARTED；进入 authenticate 后的泛化 throw 保留 UNCONFIRMED，不能按异常类猜平台是否启动。收到确切 terminal 但 start 仍未返回时保留 pending，直到调用也退场；不阻塞现单 worker lane 等用户。
- success 后取消、doFinal 后但 owner 接受前取消、结果发送队列失败：丢弃未登记 keyset、退休已登记 Root/mutation，不能显示成功。Tink handle 的 GC 不能被描述为内存安全擦除。
- 新旧 callback 交错、两次 success、error 后 success、Activity 更换、host action epoch 变化、slot 撤销/重注册：至多一个完成结果，旧结果不能杀新流程或复活旧能力。
- 一个未终结 prompt 必须保持同 owner 的阻断，不能新建 binding/scheduler/Activity owner 绕过。若平台会话结果和提交状态始终无法确认，说明此次认证尚不能安全重试；这不是密码凭据被删除。重新启动仍必须锁定，真实平台清理行为另验。
- 清理工作不得在 registry/revocation gate 内等待 Cipher/KeyStore/平台回调，沿用现“撤销快、实际释放另证”的方式。

## 9. 失败分类与回退

| 分类 | 本次行为 | 保留的路线 |
| --- | --- | --- |
| 用户取消/负按钮/超时 | 不给 root、保留 reservation；等待真实 terminal | 新鲜密码；已 ACTIVE 且健康的 recovery reset |
| biometric mismatch 非终结 | 留在同 prompt，由系统继续扫描/限流 | 用户可取消后选择密码 |
| 临时 hardware unavailable / lockout | 结束本次，不将可重试时间说成确定平台解锁时刻 | 本地 retry ladder 后密码；不自动解除系统锁定 |
| 永久 biometric lockout | 生物本次不可用；不自动调用 device credential 或设置 | 密码 / 健康 ACTIVE recovery |
| 生物 key 缺失或永久失效 | 标记此槽不可用；不自动生成替代 key | 密码后显式重新注册；健康 ACTIVE recovery |
| CryptoObject/Cipher/AAD/slot/generation/owner 不匹配 | fail closed、无 root、无 activation | 退休完成后新请求；不可把异常结果切换到另一个槽接受 |
| device key/marker/ledger 损坏或缺失 | 全局认证不可用，保留数据库和密文；不尝试密码 KDF 或 recovery unwrap | 只能准确说明当前无受支持修复路线；不虚构恢复码保证 |
| SQL/CAS unknown / cleanup unknown | 保留 exact receipt/pending；遮蔽、对账，绝不重复造新 proposal | 只有可信同-owner reconciliation 后再给适当入口 |

现有 `VaultFailure` 的 category 太粗且 `failure(category)` 丢弃 category，需给 UI 增加受限技术原因（不含异常文本、alias、文件名或敏感数据）。失败原因不能作为授权或删除信号。

## 10. 与现有恢复流程的组合约束

- 生物注册/停用/失效都必须逐字节保留 primary password 槽与 recovery 槽和 ACTIVE/PENDING recovery 状态；不能用 `listOf(passwordSlot, biometric)` 覆盖整个 slots。
- recovery enrollment 只替换 recovery；不得再用现有 `listOf(passwordSlot(data), recovery)` 丢弃 biometric。
- 当前 password-reset store validator 的既有合同是保留不属于 primary/recovery 的槽。最小兼容实现遵守它，保留 biometric 状态/槽，只替换主密码并消耗旧 recovery；重置 UI 当前也只承诺撤销旧密码和旧恢复码。若产品要改为重置时一并撤销生物，需独立明确该行为，不能由实现默默改变。
- 不完成 G1 的 catalog keyset reseal/CAS 冲突修复，就不准把 biometric 注册接进同一坏的 credential mutation 链。
- 每次 credential reseal 都保留现有 media entries、wrapped media key、media journal、source-cleanup 证据；只能推进目录 revision 和 credential 相关认证元数据。
- 没有跨安装、坏 ledger 或 device-key loss 的新救援能力。相关说明必须从 UI 注册前就可见，而非失败后才补一句。

## 11. 固定 Tink 版本结论

已只读核对官方 Tink Java v1.23.0 的 `serializeEncryptedKeyset → writeWithAssociatedData → encrypt`：该版本包装仅调用一次 wrapper.encrypt；其后不调用 wrapper.decrypt。因此不能错误地为了注册而把现有 one-direction AEAD 改成可重复或提供 cleartext serialize fallback。独立的第二个 decrypt prompt 用于验证持久化后的可解包性，不是绕开 Tink API 的补丁。[TinkProtoKeysetFormat v1.23.0](https://raw.githubusercontent.com/tink-crypto/tink-java/v1.23.0/src/main/java/com/google/crypto/tink/TinkProtoKeysetFormat.java)、[KeysetHandle v1.23.0](https://raw.githubusercontent.com/tink-crypto/tink-java/v1.23.0/src/main/java/com/google/crypto/tink/KeysetHandle.java)

## 12. 提交/接受顺序

1. 独立审查本合同与恢复缺陷，先定可达性和 slot-preservation 前置修复。
2. 实现新窄 fake port、schema/CAS/reducer/host 接线和固定测试；仍禁真实 KeyStore/随机凭据与平台执行，entry false。
3. 构建 owner 串行编译/静态 hash gates/聚焦及完整回归；只声明代码与 fixed-data 验证。
4. 另立经允许的真实设备认证、Keystore 生命周期、SQL 崩溃恢复、保护画面验收。任何一步失败，entry 保持 false。

本设计不授权实际密钥创建、删除、系统录入、安装 APK 或启用生产入口。

## v2 平台事实依据

API31 固定源码 android-12.0.0_r1：BiometricPrompt 的 NEGATIVE 表示 dialog 结束退出动画，onDialogDismissed 对已注册负按钮只调用 listener；预取消 signal 会使 authenticate 直接返回。AuthSession 在通知 dialog dismiss 后才执行 cancelAllSensors，因此不得把 listener 夸大成底层硬件物理释放。以下仅用于合同核查，未复制平台实现或运行它：

- [API31 BiometricPrompt](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/hardware/biometrics/BiometricPrompt.java)
- [API31 AuthSession](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/biometrics/AuthSession.java)

G1/G2 已从大生物接线拆出，先行实施合同见同级目录 `../vault-credential-repair-contract/G1_G2_PREIMPLEMENTATION_v2.md`；其代码不等待本方案的生物 schema/alias 工作。
