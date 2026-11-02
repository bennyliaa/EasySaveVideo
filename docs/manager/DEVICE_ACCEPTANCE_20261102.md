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
