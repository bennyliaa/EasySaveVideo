# ESV 设备验收记录（emulator-5556，2026-11-02）

## 环境与安装

| 项目 | 值 |
|---|---|
| 设备 | emulator-5556 = AVD `DeviceA12`，Android 12 / API31，冷启动 `-no-snapshot -no-audio`，未 wipe |
| 未操作 | USB 上的 OPPO PDYM20（已交还）与 Lenovo TB138FC 平板：未发送任何命令 |
| 安装前 | `com.easysavevideo.app` versionCode 2 / 0.3.2-commons-dev，UID 10146，签名证书 SHA-256 `18e3ef65…a1a5` |
| 安装包 | 归档 code12：`esv-slice09-code12-diagnostics-debug.apk`，SHA-256 `b41ff7c2…6fc02`（与交接记录一致），同一证书，versionCode 12 / 0.9.0-ui-dev |
| 安装方式 | `adb -s emulator-5556 install -r`：Success；UID、firstInstallTime 保留 |
| 数据保留 | 安装前后 `run-as ls -lR` 应用数据目录清单 0 差异（只比对元数据，未读内容） |
| 注意 | code12 ≠ 当前 main（缺 code13 六文件预算诊断与本机初始化诊断）；本机 Debug 密钥证书 `5e841a04…` 与设备证书不同，**当前 main 无法覆盖安装** |

## 1. 浏览 → 检测 → ① Direct → 验证 MP4 → 播放 → 媒体库：PASS

样本：`https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4`（Big Buck Bunny，Blender Foundation，CC-BY 3.0；主机 HEAD：200、video/mp4、991017 bytes）。先尝试的 Google 样例桶（AccessDenied）与 Blender 镜像直链（404）均不可用，未保存。

| 步骤 | 结果 | 截图 |
|---|---|---|
| 启动 | Discover 页、零来源 “Waiting for a search” | 01-launch |
| 新建标签并打开样本 | 页面加载；轻量检测提示 “Video element found” | 04-sample-mp4 |
| Inspect and choose | 1 个候选：test-videos.co.uk · 640×360 · 10 s；广告/验证提示可见 | 05-inspect |
| Start direct download | “① The Direct download task has been accepted” | — |
| Tasks | Direct download · Completed；Verified MP4 in ordinary library；967 KiB · 640×360 · 10 s · 0 audio tracks | 06-tasks |
| 预览播放 | 已就绪 → 播放中，真实画面解码 | 08-playing |
| Library | 新条目 MP4 · 00:00:10 · 967.8 KiB · 640×360 · Saved 6:46 AM；旧条目保留 | 09-library |

覆盖：PR-03（地址栏/新标签）、PR-09（轻量 + 完整检测、候选弹窗）、PR-10（Direct 流式下载与 MP4 验证）、PR-16（任务中心终态）、PR-17（媒体库列表、预览）。

### 发现的问题

1. **本地化不一致**：界面语言为英文时，“已验证成片预览”页全部、媒体库 “撤销临时分享读取并清理副本”、“位置：媒体库根目录”、“视频” 等仍为中文。
2. **默认名称**：保存后名称为 `Video <UUID>`，不是来源标题（可能是隐私设计，需产品确认）。
3. 模拟器音频关闭，未验证音频；样本本身报告 0 音轨。

## 2. ② Stream（静态 HLS）：权益门控 PASS，保存 FAIL（POLICY_BLOCKED）

样本：Mux 公开测试流 `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`（Big Buck Bunny，CC-BY 3.0）。主机核对：200、`audio/mpegurl`、5 个 H.264/AAC 变体（240p～1080p），变体为同域相对路径、VOD、64 个 10 s TS 分片，DNS 解析为公网 Fastly 地址。

| 步骤 | 结果 | 截图 |
|---|---|---|
| 打开 m3u8 | “Video element found” | 10-hls-page |
| Inspect（默认权益“来源未知”） | 候选 1280×720 · 634 s；按钮 **“Streaming entitlement required”** —— PR-19 门控正确 | 11-hls-inspect |
| Debug 面板“开发权益测试”选“部分权益”（STREAM_SAVE+RECORD_SAVE，仅 Debug） | 按钮变为 “Inspect static HLS” —— 门控随权益解除 | — |
| Inspect static HLS | **失败**：“The resource address or connection does not meet the safety restrictions.”（`SaveError.POLICY_BLOCKED`） | 12-hls-variants |

分析：MIME `audio/mpegurl` 在 `SystemHttpsHlsFetcher.kt:419` 白名单内；地址为 HTTPS 公网同域。该类中 `POLICY_BLOCKED` 来源有：全局 `CookieHandler` 存在（:136/:178）、URL 重复访问（:135）、重定向超限（:182）、URI 编码/相对引用/路径规范化（:374–406）。生产日志按设计不含细节，设备上无法进一步定位。**待开发**：用相同清单结构做 JVM 复现，确定触发分支；若为误判则修复，若为预期限制则在 UI 给出可理解原因。

另见：离开浏览器再返回时，标签页面已释放，需手动 Go 重新加载（符合“Older pages are released”设计，但测试步骤需注意）。

### 2.1 POLICY_BLOCKED 根因（已确认）

同一进程内对照实验（code13，emulator-5556）：先 Direct 下载同一 MP4 → Completed（任务 5c0282f1）；打开 Mux HLS 页面后再 Direct 下载同一 MP4 → **Failed**（任务 cb85ad85）。logcat（ESV 进程）：`MediaHTTPService: makeHTTPConnection: CookieManager created: java.net.CookieManager@…`。

结论：WebView 用系统 `MediaPlayer` 播放 HLS 时，`MediaHTTPService` 在 ESV 进程内安装了进程级 `java.net.CookieManager`；ESV 网络策略对全局 CookieHandler 失败关闭（`SystemHttpsConnectionFactory.kt:33`、`SystemHttpsHlsFetcher.kt:136/178`），因此此后同进程所有网络保存（Direct 与 HLS）均被拦截，直到应用重启。Direct MP4 页面由 Chromium 自带管线播放，不触发该问题。

附带 UX 缺陷：失败后任务页提示“不要切换方式绕过内容保护或访问限制”，与真实原因不符。

修复方向（待用户决定，涉及网络安全策略）：A 改用已准入 OkHttp 4.12 且不使用任何 CookieJar，复用公网地址/TLS/网络绑定检查；B 网络保存移到独立进程；C 先给出准确原因与“重启应用”指引。

## 3. ③ 基础整屏录制：开始→跨页面交互→通知 Stop & Save→验证发布 PASS；成片播放未完成

用户在聊天中确认后，由测试执行者在系统对话框 “Start recording or casting with EasySaveVideo?” 点了一次 “Start now”。模式：Basic recording（系统选择范围）；音频：No audio（未申请录音权限）。

| 步骤 | 结果 | 截图 |
|---|---|---|
| 录制页 | 风险披露完整：每次系统授权、Android 12 最近任务缩略图、仅内部音频不用麦克风、受保护内容黑屏、前台服务 | 13-record |
| 系统授权 | 系统对话框 → Start now | 14-consent |
| 录制中切换 Discover/Record | 录制不中断；底部出现 “Screen recording” 面板 | — |
| 停止 | **App 内 “Stop and save” 无法点击（见缺陷 1）**；改用系统通知 “③ Screen recording” 的 **Stop & Save** 动作 | 17/18-notifications |
| 结果 | `media_projection` 无活动会话；Tasks：**Recording · Completed**，Verified MP4 in ordinary library，31.5 MiB · 876×1898 · 416 s · 0 audio tracks（时长与 06:57→07:04 实际一致） | 20-after-back |
| 成片预览 | 预览页仅显示标题，播放控件未出现；录制面板仍打开且其关闭按钮被系统导航栏遮挡 —— **未完成** | — |

### 缺陷与观察

1. **（缺陷）底部录制面板被系统导航栏遮挡**：1080×2340 / 三键导航下，面板按钮（录制中为 “Stop and save” / “Discard this recording…”，结束后为 “Close recording panel”）绘制在导航栏下方，无法在 App 内点击；底部导航被推到 y≈1718。疑为 window insets 未处理。用户只能经通知停止录制。截图 20-after-back 可见按钮与导航栏重叠。
2. **（缺陷，待确认）录制面板未关闭时预览不可用**：预览页无播放控件，且面板无法关闭，形成死路。
3. **（观察）录制页启用截屏保护**：在录制页时 `screencap` 与模拟器主机截图均为黑/空；离开录制页后恢复。录制期间 UIAutomator 无法取得 idle 状态。属测试工具限制，可能为有意设计。
4. 早先“按钮被挤出可见区域”的判断来自单次 UI 导出，已由截图更正为“被导航栏遮挡”。

## 3.1 复测：当前 main（code13，含面板修复 21a26bd）—— 面板 PASS，竖屏预览 FAIL

用户授权卸载重装（数据清空）。`app-debug.apk` versionCode 13，本机 Debug 证书 `5e841a04…`，安装成功。新装后权益为默认，录制页正确提示需要权益；在 Debug 面板选“部分权益”后继续。

| 步骤 | 结果 | 截图 |
|---|---|---|
| 录制中切到 Tasks | 底部面板 “Stop and save” / “Discard this recording…” 完整显示在系统导航栏上方，底部导航与面板之间无多余空白 —— **缺陷 1 修复确认** | 23-panel-fixed |
| App 内点 Stop and save | 录制保存发布；“Close recording panel” 同样可见可点 | 25-saved |
| 结果 | Recording · Completed，2.2 MiB · 876×1898 · 27 s · 0 audio tracks | — |
| 预览录制成片 | **FAIL**：竖屏视频区域按宽度撑满，高度超出屏幕，遮住“关闭预览”，播放控件被挤出屏幕且页面不可滚动 | 26-record-playback |

缺陷 2 更正：与录制面板无关，是**预览页未限制竖屏视频高度**（横屏 640×360 的下载成片可正常播放）。

**修复后复测 PASS**（`install -r` 保留数据）：竖屏 876×1898 成片按比例缩小居中，“关闭预览”、播放状态“播放中”、“暂停播放/从头播放”均可见（截图 27-preview-fixed）。画面为黑色属预期：该段录制的是启用截屏保护的 ESV 录制页，Android 以黑帧录入；帧内可见被录入的状态栏录屏指示点，说明成片可正常解码播放。

## 4. 未执行
- 真实来源搜索、编辑、导出/分享、无痕、Vault：未开始。
- Debug 权益已切到“部分权益”；进程重启后恢复默认（内存状态）。
