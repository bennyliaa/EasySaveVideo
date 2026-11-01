# ESV 私有仓库恢复材料同步完成

仓库：bennyliaa/EasySaveVideo。同步前后通过GitHub connector确认visibility=private，default_branch=main。使用既有认证Git及已配置的用户提交身份，没有提取/创建/传递凭据或改变原Git配置。

## 云端接收定位

- 精确提交：`538110ab2e323e66516e992eb2d933ae1c7ba6ed`
- 独立分支：`recovery/lzf-code12-diagnostic-20261025-339b2f76`
- 独立目录：`recovery/lzf-code12-frozen-diagnostic-20261025-339b2f76/`
- 固定提交目录：https://github.com/bennyliaa/EasySaveVideo/tree/538110ab2e323e66516e992eb2d933ae1c7ba6ed/recovery/lzf-code12-frozen-diagnostic-20261025-339b2f76

这是独立恢复root commit，没有main父树/历史的改写，也未创建PR。推送仅指定新分支，未force。main前后均为`ea87963dee631a498774f648c9a1dabd9f665b90`；没有改变仓库可见性、main或现有资料。

## 目录内12项文件

恢复包按既有8MiB可恢复方式分为4片：

| 文件尾名 | bytes | Git blob SHA |
|---|---:|---|
| ESV-code12-frozen-diagnostic-recovery-20261025.tar.gz.part01 | 8388608 | 0003cfed5e62c4cc7dc7c0adffbf967c677d011d |
| ESV-code12-frozen-diagnostic-recovery-20261025.tar.gz.part02 | 8388608 | a4011297cd553bc98d69a5e7299501b2cdfb69b1 |
| ESV-code12-frozen-diagnostic-recovery-20261025.tar.gz.part03 | 8388608 | a4083fc66720fbd18a0ec5a1f1d26455c4ed7845 |
| ESV-code12-frozen-diagnostic-recovery-20261025.tar.gz.part04 | 5549546 | 88e4fbe5b125bec96f63ef1977fd5ffd38558e5d |

其余8项：`MANIFEST.json`、`MANIFEST.sha256`、`TRANSFER_MANIFEST.json`、`REASSEMBLE.py`、`HANDOFF.md`、`ARCHIVE_RECEIPT.json`、`LINT_RESULT.json`、`POM_ADMISSION.json`。回执里的本机绝对归档路径已改为归档basename，未上传原始宿主路径日志。

`TRANSFER_MANIFEST.json`包含有序分片大小/SHA-256/Git blob SHA及整体包hash。接收方可在隔离新目录审查并运行`REASSEMBLE.py`；它拒绝已有输出，严格核对4片和整体摘要，只重组，不提取/构建/签名/安装。不得将独立测试stub放入产品源。

重组恢复包：30,715,370 bytes；SHA-256：

`339b2f76342399445b93ffd2cc263bf00f78b611b934b73f3b50264d6363ae0e`

MANIFEST.json SHA-256：

`65b8f2e5d280bd9e103b2be1bd4c6d4fb7f7a7e4c649301bdbcce07899d43588`

包内2892项冻结源码+16项诊断patch/测试/元数据准入证据此前均从归档回读验证。原批准的公开服务联系配置及合成测试向量按冻结hash保留；没有新增实际账户、设备/个人数据。没有上传任何签名文件/私钥/运行时凭据、SDK二进制缓存、APK、本地.git或其他项目。补充lint结果为Debug/Release零错误；原229失败测试未被冒充通过。

## 真实远端回读验证

推送成功后，新建独立bare Git仓库，从远端重新fetch该分支，确认固定提交SHA；逐一cat-file读取全部12个远端blob，分别核对Git blob SHA、实字节大小及SHA-256。由远端回读的4片重组成独立本地归档，整体大小/hash均匹配上述值；MANIFEST实字节hash也一致。

证据：`private-recovery-remote-verification.json`（12项逐blob结果）、`private-recovery-upload-inventory.json`、`private-recovery-local-commit.json`。回读目录`ESV-private-recovery-readback-339b2f76-files/`保留重组验证包。

终态：已同步并完成远端实字节验证，无阻塞/不确定推送结果；无设备或cloud构建/签名操作。后续由父任务在原cloud接收和验证，不能将此备份当作code13完整恢复或手机保存验收。
