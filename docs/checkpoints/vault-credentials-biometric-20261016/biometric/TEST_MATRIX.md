# 后续测试矩阵（本包未执行）

标记：F＝固定 fake/虚拟时钟/合成身份；C＝实际源的纯 codec/shape/固定公开 crypto fixture；S＝静态源码/依赖接线；D＝真实 Android/KeyStore/生物/凭据行为，**本任务完全不执行**。

F/C 可在后续允许的代码切片中完成，不需要用户凭据、真实随机 key、SQLite、系统 prompt 或新依赖。假 success 只能测试接受/拒绝规则，不能证明平台强认证。C 的公开 keyset 只复用仓库已有公开 fixture；不得生成新 credential 或访问旧被拒 candidate/probe。

## A. Prompt 与实际结果身份

| ID | 类型 | 输入/调度 | 必查结果 |
| --- | --- | --- | --- |
| B01 | F | current prompt + exact crypto token/cipher + current slot/attempt/host | 只进入 crypto owner finalize，不能直接 UI success |
| B02 | F | null CryptoObject、另一个 wrapper、另一个 cipher、非 BIOMETRIC authType | 全拒；0 root、0 mutation、0 retry RESET |
| B03 | F/C | 同 ciphertext，改 vault/install/alias/slot/generation/purpose 任一项 | 绑定/AAD 拒绝；不能拿密码/恢复结果冒用 |
| B04 | F | old prompt success 在 newer prompt/slot generation 之后到达 | 旧结果退休，不能接受、取消或覆盖新流程 |
| B05 | F | success 两次、error 后 success、success/error 交错 | 至多一个 result；exact terminal 和 continuation retirement 才放行 |
| B06 | F | onAuthenticationFailed 多次后 success | 同一 reservation；不重置失败次数，不开新 prompt |
| B07 | F | cancel/timeout 在 callback 前、doFinal 后、owner 接受前 | 不发布 root/状态；已有 owned output 退休 |
| B08 | F | 已到 deadline、clock 为负/溢出、child deadline 放宽 | 不启动/拒绝；不能刷新原预算 |
| B09 | F | start 前失败、start 后未知、scheduler/executor 拒绝 | NOT_STARTED 与 UNCONFIRMED 分离；无伪造 terminal |
| B10 | F | callback 先于 start 返回、同步 fake callback | Pending 提前登记；无悬空/遗漏 job |
| B13 | F | 真实负按钮通知，无后续 error callback | 记录本次 terminal；app-owned callback/crypto drain 后能进入密码 fallback，不无限 pending |
| B14 | F | Cancel-before-submit、预取消 signal 无 callback、submit/cancel 穿插 | 同平台串行 dispatcher；确证未启动或已提交后排队 cancel，不靠超时冒充释放 |
| B15 | F | negative/error/success 已通知但 submit 未返回或 crypto continuation 在途 | 仍 retained；通知与 owned drain/未知 submission 分离 |
| B11 | F | host generation/route/input epoch/Activity identity 替换 | 旧结果全部失效；current generation 不恢复旧 lease |
| B12 | S | 真实叶子仍调用 authenticate(CryptoObject,…) / exact reference / doFinal | 不退成“回调 success 即解锁”或新 Cipher |

## B. 注册、停用、槽组合与 schema

| ID | 类型 | 输入/中断 | 必查结果 |
| --- | --- | --- | --- |
| E01 | F | fresh password-auth → wrap → stage/readback → independent verify → activate/readback | 所有阶段顺序正确；结束锁定；primary/recovery 保留 |
| E02 | F | 无 fresh password auth 或任意伪 Root/mutation | 0 create-key port、0 prompt、0 store writes |
| E03 | F | key 已存在、NONE/PENDING/REVOKED | NONE 仅经 fresh-password 验证过的 READY catalog + ORPHAN_NONE；generation=N/A、不虚构 slot/CAS；其它状态走 exact retirement |
| E04 | F | key create 后、stage 前中断；许可后 snapshot 改变；delete unknown | 重启不自动清理；实际删除前重验 snapshot、root/owner/alias、drain；unknown 保留 original intent，不新建 key |
| E05 | F | PENDING 读回后 cancel/restart | 不允许普通生物解锁、不自动激活；保留主凭据 |
| E06 | F | WRAP 成功但 VERIFY 缺席/失败/用同 Cipher | 不 ACTIVE；必须新 decrypt operation 和实际 persisted bytes |
| E07 | F | stage/activate/revoke CAS 确认旧、确认新、未知、不一致 | exact bytes/receipt；不能重新造 proposal 刷预算 |
| E08 | F | revoke 生物时 primary/recovery ACTIVE/PENDING × 有媒体/journal | 只改生物与必要目录 reseal，其他内容逐项不变 |
| E09 | F | alias delete 失败/结果未知/错误 alias | REVOKED 保留，禁止 re-enroll；不得触碰 device key/其它 alias |
| E10 | F | old prompt 未 terminal 时 delete/recreate | 拒绝；后续 exact terminal 才可继续 |
| E11 | C | 三槽组合、重复 kind/id、缺 primary、state/slot 不匹配 | canonical profile 接受/拒绝准确，最多 3 槽 |
| E12 | C | old snapshot/catalog v1/v2/v3 | 字节保持完全一致；新生物数据不会被旧版本静默忽略 |
| E13 | C | biostate、slot digest、generation、retired metadata 各自篡改 | catalog/snapshot fingerprint 绑定变化并拒绝认证 |
| E14 | F/C | recovery enrollment/reset 与生物各 state 组合 | 不丢生物槽；reset 按既有规则仅消费旧 recovery/替换主密码 |
| E15 | F/C | slot/catalog/store revision 接近 Long.MAX_VALUE | fail closed，无 wrap、无新 alias generation |
| E16 | F | 一个 workflow 同时遇到 playback/thumbnail/media/recovery pending | 不能并行接受；复用现 owner fence |

## C. 解锁、retry 与生命周期

| ID | 类型 | 场景 | 必查结果 |
| --- | --- | --- | --- |
| U01 | F | biometric unlock 正常链 | reservation durable 在 prompt 前；Root 只在实际 auth/catalog 验证后；RESET 确认前不 active |
| U02 | F | reservation 失败/超时/未知 | 0 prompt；保留 pending write 和 retry restraint |
| U03 | F | RESET 失败/未知 | pendingRoot 隔离/退休，无 reveal、无“已解锁” |
| U04 | F | prompt 中 store/ledger/catalog/slot 换 revision | finalize 拒绝旧 snapshot，不挑一个新槽继续 |
| U05 | F | 用户取消、timeout、temporary/permanent lockout | 本次不 success；保留本地失败计数；不自动 device-credential fallback |
| U06 | F | biometric-only key invalidation/hardware unavailable | slot unavailable；主密码与健康 ACTIVE code 入口仍存在；不全局 KEY_INVALIDATED |
| U07 | F | 主 device key/marker/ledger 不健康 | 所有 auth blocked、无 KDF/新 key；原因与健康生物失效不同 |
| U08 | F | leave/foreground/background、Immediate/1/5/15、deadline equality | 现 reducer 策略不变；认证中 departure 仍使旧 callback 失效 |
| U09 | F | 锁定/Activity detach/捕获冲突 during prompt | 先 cover/revoke，真实 terminal 后 cleanup；旧 UI 无效 |
| U10 | F | cancel 后无 terminal callback | retained Pending，cleanup 不成功；不新建 owner 绕 fence |
| U11 | F | process restart | 无 active Root、无缓存 bool auth、无自动 prompt；按原 ledger 保留 restraint |
| U12 | S/F | password fallback click before/after terminal | 前者不能启动重叠认证；后者创建独立新 attempt，不复用生物授权 |

## D. 恢复旧缺口与生产接合测试

| ID | 类型 | 场景 | 必查结果 |
| --- | --- | --- | --- |
| R01 | C | 四种 recovery mutation 使用真实 reseal helper 的固定 fixture 输出 | 真实 shape validator 可接受正确 changed catalog-keyset envelope；不是 fake helper 自说可用 |
| R02 | C | 新 catalogRevision 配旧 keyset envelope | 不允许通过“保留旧字节”绕开 AAD 更新 |
| R03 | C/F | 伪造 changed keyset envelope/外部 mutation/错误 owner | registry/exact snapshot 拒绝；不能仅放宽 equality |
| R04 | C | reseal 前后有媒体、wrapped key、journal、pending source cleanup | 目录只变认证前缀/必要 envelope；媒体内容和证据保留 |
| R05 | F | corrupt/missing retry ledger、marker、device key、SQL corrupt 各个类别 | 原因分别可理解；不自动修库/新 key/零化 ledger |
| R06 | F | ACTIVE recovery 但坏 ledger/device key | code reset 不可开始；不虚构能力；保留密文 |
| R07 | F | NONE/PENDING/OPTED_IN without ACTIVE | 不显示可恢复保证、不进入 reset |
| R08 | F/S | failureSticky 启动故障 → rendered state | 不被泛化 UNAVAILABLE 吞掉原因；通往正确说明而非死路说明缺失 |
| R09 | F/S | 忘记密码、生物变化、卸载、换设备、device key 失效 | 与原 PR-18/TR-12/T-L08/FI-23 说明一致 |
| R10 | F | stale recovery reset metadata、unknown SQL receipt、late ack | 保留既有 anti-replay/retirement tests，无覆盖旧 pending 请求 |

## E. 静态与聚合门

- S01：Entry.ENABLED 保持 false，Main 无后门；新生物配线只有该 gate 内可构造，参数不初始化或生成 key。
- S02：同一 auth owner/keystore/registry 及 store 注入；不能额外 new 一个 auth owner 接受旧 Root。
- S03：无新依赖、无 core Android 类型、无网络/日志/明文 keyset/AndroidKeysetManager fallback。
- S04：existing Tink source scope/FQN 清单若需变更只列新明确触点；历史 dependency admission 文件与 hashes 不改、不重跑旧 probe。
- S05：schema 最大 envelope/目录/slot 数量，trailing/noncanonical 字节拒绝；所有状态文字中英文同义。
- S06：AndroidKeyStore/delete/generate/system prompt 的 F 测试均使用 fake port；通过工具计数证明真实 leaf 不执行。
- S07：build owner 串行执行必要 compile、聚焦 unit、既有回归和聚合 gates；记录实际命令/结果，不能把本矩阵当成通过数量。

## F. 真实平台验收，后续独立步骤

以下 D 项在本任务均为 **NOT RUN**；不能用 F/C/S 成绩代替：

1. API31 起受支持手机/平板/厂商的强生物能力判断、真实录入及每次使用授权；弱生物不得满足 crypto unlock。
2. 实际 KeyStore AES profile/安全等级、密钥生成、同 alias 生命周期、录入新增/清除导致失效、permanent lockout、无硬件/硬件错误；不自动改变系统安全设置。
3. 两个独立注册 prompt、persisted pending envelope 实际回读与解包；真实用户密码与可选恢复码完整流程及撤销边界。
4. 真正 CryptoObject/Cipher identity、AAD/AEAD failure、平台负按钮/取消/重复回调/屏幕旋转/锁屏/进程死亡、实际 callback termination。
5. Android SQLite 事务/重开、未知提交、断电/kill、满盘、当前 marker/ledger/device key 缺失；保留原数据，不真实清数据。
6. 生物状态与密码/恢复码组合，以及普通媒体、播放、缩略图/Surface/native 资源的真实退休。
7. FLAG_SECURE、最近任务、捕获冲突、Activity replacement、系统 prompt 覆盖与可见输入；fake host 不能证明这些像素行为。
8. 安装/设备/备份能力边界说明与真实支持范围一致；不能未经许可卸载/换设备/改录入来试验。

即使未来 D 项通过，也需独立决定是否启用生产入口；本矩阵不承诺或自动执行该步骤。
