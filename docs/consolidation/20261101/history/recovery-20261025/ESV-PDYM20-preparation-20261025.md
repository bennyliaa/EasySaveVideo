# ESV PDYM20 真机测试准备（2026-10-25 UTC）

本轮仅使用已确认的 PDYM20，所有设备命令绑定其授权序列号（报告中不重复记录）。没有访问其他设备，没有安装、卸载、降级、清数据、启动录屏或更改安全/网络设置；没有访问应用私有文件、个人媒体、账户或保险箱。没有上传或提交。

## 本轮实际检查

- ADB get-state：device；ro.product.model：PDYM20。
- com.easysavevideo.app：versionCode 12；versionName 0.9.0-ui-dev；minSdk 31；targetSdk 36；DEBUGGABLE；单一 base APK；APK signing version 2。
- 包管理器记录最后更新为 2026-10-19 17:46:18（设备显示时间，未校验时区）；检查时 stopped=true。
- 对已安装 base.apk 在设备上执行 sha256sum，得到 b41ff7c237218922ab367d2743e2ae131e9185a7fa3ca54913dc60a49466fc02，与本地 code12 候选实算摘要及 FINAL_APK_VERIFICATION.json 一致。未将 APK 或个人数据从手机上传。
- 匹配文件：D:/CodexProjects/EasySaveVideo/discovery-live-20261017/code12-candidate-20261019/esv-slice09-code12-diagnostics-debug.apk。
- 匹配 APK 的历史 APK_SIGNER_VERIFICATION.txt 记录 v2 签名验证通过，证书 SHA-256 为 18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5。签名身份由完全一致的 APK 字节及历史签名检查建立；本轮没有重新运行 apksigner，也不把 dumpsys 的短 signatures 值视为证书摘要。

## 可信范围及缺口

可以将现有手机安装明确定位为该 code12 二进制，不意味着它是最新完整开发源码。SOURCE_DELTA.json 明确其为 frozen slice09 的候选 overlay，而非完整恢复树。code13 恢复与最新完整源码仍未查明。wfix 无 Git 元数据，不能视为该安装的已证明源码。

本地 wfix 的 SaveEvaluationPanel 是手动评估/汇总界面，需要评估页面及明确 Save 动作，不自带下载合成小文件入口。OwnedFixtureActivity 仅为空白生命周期测试界面、非导出；androidTest 中 OwnedEvaluationFixtures/Provider 用于合成 SAF 诊断报告导出，不能当作①HTTP 媒体下载 fixture。

wfix 的 HttpsRequestPolicy.validate/requirePublic 拒绝非 HTTPS、localhost、回环和私网目标，并要求公共连接端点。该文件仅作为现有本地实现线索，尚未证明与 code12 对应源码逐字一致。因此既不能假定 localhost/ADB reverse 可覆盖真实保存链，也不能为了测试绕过策略。

## 测试结果

- 设备身份、连接：通过。
- 已装包版本识别、APK 字节归属：通过。
- 签名来源：与匹配 APK 的既有已验证证书建立关联；未独立重验签名。
- ①合成小文件完整保存：未执行（缺少已核实兼容的受控网络 fixture 和现有应用入口）。
- ①取消不生成伪成功媒体：未执行（同上）。
- 本轮无功能失败结论；未执行项不计通过。历史 7095 测试或旧设备诊断均不计入本轮真机结果。

## 最小后续方案及阻碍状态

先由协调方定位现有 code12 对应完整来源/测试入口，以及一个已授权、受控、公共 HTTPS 的合成媒体 fixture（固定小文件字节、长度、SHA-256，另有可控慢速响应供取消）。如果已有不需新安装的隔离 fixture 入口，先核实其身份和行为。当前不建立公网服务、不扩大网络范围、不改设备设置。

入口与 fixture 核实后，只在 PDYM20 现有应用执行：保存一份唯一命名合成小媒体，核对任务终态、输出长度/摘要；慢速第二任务取消，确认无成功状态及无已发布完整媒体；仅检查该两项测试产物。任何清理只针对明确拥有的测试产物并另行核实。

阻碍：受控①下载 fixture/入口未明确，状态为等待协调、未尝试保存。没有权限拒绝或不确定的设备写入结果。本轮未启动 ESV，未触及 5558/5582/TB138FC。
