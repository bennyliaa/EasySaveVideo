# ESV Windows A/B 前置验证包（2026-10-08）

本目录是独立测试仓库，不是恢复的 ESV 源码。原项目目录没有 `.git` 或 Kotlin 源码。未覆盖现有文档、测试包和 App 数据。

## 已完成

- 固定 100 条自有本地夹具：25 静态、15 结构化元数据、20 JS 延迟 DOM、10 本地嵌入播放器、20 阴性诱饵、10 受控拒绝。独立标签：70 阳性、20 阴性、10 未知。A/B 输出都不充当真值。
- 每条页面与合成媒体固定 SHA-256；媒体来自已有授权测试包的 `esv-original-6s-640x360-30fps.mp4`，未访问私人媒体。
- 评分器分开统计视频判断、正确媒体身份、正确资源提取，保留全部阳性和阴性分母、precision、已知标签决策覆盖率、正确阳性/全部阳性。未运行不算通过；失败、拒绝、限速、超时、取消只能 UNKNOWN，候选 URL 不能确认视频。
- 回环夹具服务仅绑定 `127.0.0.1`，拒绝非本机 Host 和目录列表；10 条未知样本返回 403。服务仅按需运行，无常驻进程。
- `contract/VerificationEngine.kt` 和 `VerificationScheduler.kt` 已独立编译，含引擎 ID、注册表、能力、请求身份/来源代次、共享预算、取消、网络约束、结果与证据。调度核心只依赖契约，支持任意引擎 ID、偏好、能力与可用性筛选，远端进程需要当前进程明确同意，单活跃任务，后备共用剩余预算。尚未接入 Discovery；A/B 均未实现或部署。
- 调度核心用假引擎测试；它在调用前后检查取消与 deadline，但不能强制终止不返回的引擎。Android IO/WebView 取消、进程终止、允许来源与 TLS enforcement、来源代次更新仍由待集成层落实。异常或消耗未知时停止后备，资源使用标未知，避免重置预算。
- 另冻结 20 条原始夹具生成 80 条受控改版变体（class/ID、包裹重排、相似诱饵、目标删除）。删除目标标签为阴性；原页面 hash 保留。仅验证夹具和标签，真实引擎恢复未测，未更新任何匹配记忆。

## 运行

本机可用 Python：`D:\Program Files\FreeCAD 1.1\bin\python.exe`（3.11.14），只用于独立标准库测试，未改动 FreeCAD。在本目录执行：

```powershell
& 'D:\Program Files\FreeCAD 1.1\bin\python.exe' fixtures.py build
& 'D:\Program Files\FreeCAD 1.1\bin\python.exe' -m unittest -v test_fixtures
& 'D:\Program Files\FreeCAD 1.1\bin\python.exe' fixtures.py serve
& 'D:\Program Files\FreeCAD 1.1\bin\python.exe' fixtures.py score --results results\engine.jsonl
./run-scheduler-checks.ps1
```

结果每行 JSON：`id,status,prediction,evidence,media_identity,resource_extracted`。`positive/negative/unknown` 对应 VIDEO/NON_VIDEO/UNKNOWN，`verified_media` 对应核实媒体证据。评估样本是本地合成页面；不能称为公网网站验收。公网 40 条候选未选择、未逐源准入、未测，不将夹具结果替换为公网结果。

## 实施与测试矩阵

| 项目 | 状态 / 下一步 |
|---|---|
| 样本生成、固定标签、完整性、评分安全、回环 HTTP | 本包测试证据见 evidence/test-framework.txt |
| A: Scrapling 0.4.15 原适配器 | 未运行；原源码下载 HTTP 403 |
| B: OkHttp / Jsoup / WebView | 未实现；需原源码，固定依赖版本、许可证和传递依赖审计后集成 |
| 引擎 C 注册、能力/可用性/选择策略 | 独立 Kotlin 核心和假引擎测试通过；App 集成未运行 |
| 共用 deadline / 请求 / 字节账本、单活跃引擎 | 独立核心检查通过；实际 IO 消耗 enforcement 未集成 |
| 远端明确同意、默认保守、A 不可用 B 后备 | 核心筛选和后备检查通过；A/B App 实现未集成 |
| 取消、超时、Activity 重建、来源切换、旧结果拒绝 | 引擎未接入，未运行 |
| WebView 销毁、renderer 终止、并发 1 资源释放 | 未运行 |
| 20 冻结原夹具的 class/ID、包裹/重排、诱饵、目标删除变体 | 80 变体已生成并验证标签和 hash；引擎恢复未运行 |
| 100 条 A/B 比较 | 未运行；不要以框架测试数代替引擎结果 |
| emulator-5556 | 指定设备当前不在线；未安装或改变数据 |
| 真机 CPU / RAM / 耗电 / 总网络 / 总时间 | 未测；N/A |

测试预算待调参建议是每条总 20 秒、静态最多 5 秒、后续共享剩余、并发 1/单 WebView。不是生产指标，也不是已批准生产设置；生产 3 秒子预算单列。Windows 与 Android 资源分列，不能直接排序；实体机比较只在同一设备比较 B 和策略。CPU/RAM 包括 WebView renderer；Network Inspector 不能代表全部 WebView 流量。Android 最低目标是 Android 12 / API 31；旧 code2 收据也是 31，新源码仍待核对。

## Windows 平台与真实阻碍

用户指定服务目录 `D:\CodexProjects\EasySaveVideo\Scrapling_Service` 已存在且为空，未覆盖。未安装运行服务依赖或浏览器。未复制 Linux 云端二进制。现有 Java 21.0.10 和 Android SDK 可读；SDK 目录存在 API 34/36/36.1/37.2，尚未构建。

系统报告 Windows 10.0.26200 / x64；本机 Chrome 154.0.8037.98、Edge 154.0.4258.62 存在，但未启动或读取浏览器资料。当前 PATH 未发现 WSL/Docker 命令，不能声称存在 Linux 兼容运行环境。Linux 专用 `fork`/signal/进程组机制不能据此视作 Windows 已支持；需拿到适配器后检查 Windows Job Object 或等效受控进程树回收及取消测试。服务目录新增 `.gitignore` 隔离运行环境、缓存、浏览器、日志和秘密；它不等于部署完成。

独立 Git 提交使用 `Codex <codex@localhost>` 作为工具提交身份（不创建账户、不改全局 Git 配置）。原项目没有配置 Git 作者，独立仓库首轮提交因此未完成，后以单命令局部身份保存。

官方 [PyPI 0.4.15](https://pypi.org/project/scrapling/0.4.15/) 发布通用 Python wheel；这不能证明全部 Windows 运行时、取消和进程树回收安全。项目 [官方仓库](https://github.com/D4Vinci/Scrapling) 标示 BSD-3-Clause；闭源发行需保留通知，完整传递依赖仍未审计。

另需核对原适配器安全配置：[DynamicFetcher 文档](https://scrapling.readthedocs.io/en/latest/fetching/dynamic.html) 说明存在自动内部 stealth 行为；[Playwright 官方 API](https://playwright.dev/python/docs/api/class-browsertype#browser-type-launch-option-chromium-sandbox) 说明 Chromium sandbox 默认关闭。必须审查固定版本实现和显式启动配置，保证无 stealth、TLS 校验、sandbox 开启、禁下载以及 Windows 子进程可靠取消；本次未通过这些保证，不能直接使用默认启动配置。

Library 已授权四文件准备成功；官方 helper 首次沙箱下载失败，允许网络执行后源码 HTTP 403；重新签发并用同一 helper 下载恢复收据仍 HTTP 403。没有改用其他下载方法。未成功获得源码、bundle 或回归证据字节，因此无法核实新提交 `12f97d43c11fa19ea7dd08ccc0805a3030c71346` 或声称 2309+57 本机通过。旧包收据提交是 `0c178320cd78391bfedd5f9846e3ee873f0f5fce`，versionCode 2，最低 API 31；安装前仍须在线核对签名与更高 versionCode。

恢复入口仍是所提供 Library 文件 ID；需要 Library 传输可用后，在另一个独立源码目录恢复并核验 bundle，再完成服务与 Android 集成。没有重试 Vault/M7-D、修改防火墙、创建凭证、访问其他模拟器、开麦或录屏。

后续已改为等待用户手动放置源码分片并通知；不轮询目录或设备、不再重试 Library 传输。收到确认后验证 001（16000000 字节，SHA-256 `d554b4f6e1861e620180834c4d8a33365262699be7042e6a7452fddbda1a2674`）、002（10522932 字节，SHA-256 `75c0062ad5bee568c1d3e60d451d15a33d75d2f11bee0eab83e349322b1be923`）；连接顺序 001→002，合并 26522932 字节，SHA-256 `163f77e4c435b90b8a6fdc1dd8217b45b8a03e9e8c4c8224ae2272c1793b3481`。源码快照没有 Git 历史；不能把新 init 冒充云端原历史。
