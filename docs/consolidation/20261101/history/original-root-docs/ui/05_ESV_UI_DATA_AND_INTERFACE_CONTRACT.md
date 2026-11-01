# EasySaveVideo UI 数据与接口契约

版本：V1.0｜日期：2026-10-11｜适用：真实、部分实现与演示UI

## 1. 契约性质

下面名称和Kotlin片段是本次UI层设计提案，不是对已存在类/SDK API的声明，也不是可直接复制编译的完整源文件。Coder先映射现有领域类型，只补缺失的UI接缝；不重复定义Core已存在的ID、状态机和命令。

UI DTO不携带Activity、Context、WebView、Uri、Cookie、系统授权令牌、密钥或SDK资源句柄。平台类型停留在平台Adapter。UI模型不进入Core公共接口，平台无关Core行为仍按原规格。

## 2. 标识与作用域

| 标识 | 用途 | 生命周期/限制 |
|---|---|---|
| SearchProfileId / QueryRevision | 关键词、筛选上下文与搜索修订 | 过期回调不得覆盖新查询 |
| ResultId / Fingerprint | 结果稳定键/领域去重身份 | 不使用卡片下标或可变标题 |
| ProviderId | 来源选择与版本状态 | 来源失效局部隔离 |
| TabId / BrowserSessionRef | 原网页标签和授权上下文引用 | 仅引用，不能暴露Cookie |
| CandidateId / CandidateRevision | 检测结果选择 | URL/候选版本过期必须重新核验 |
| TaskId / CommandId / IdempotencyKey | 任务、用户动作与防重 | UI重组不产生新ID |
| MediaId / FolderId | 媒体与文件夹 | 文件移动后ID稳定 |
| RecordingSessionId / SegmentId | 录制会话和片段 | 无授权令牌复用 |
| VaultSessionRef / PreviewRef | 受控访问和图片引用 | 可撤销，不保存到SavedState |
| UiRequestId | 导航/系统用户交互请求 | 与前台实例关联，不跨进程重放授权 |

引用解析和访问校验由现有领域/平台Adapter执行。展示标题不是磁盘路径；不可把网页标题直接用作文件名或唯一身份。

## 3. 公共状态

```kotlin
enum class BindingMode { REAL, DEMO, UNAVAILABLE }
enum class LoadPhase { IDLE, LOADING, CONTENT, EMPTY, OFFLINE, ERROR }
enum class Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }

data class CapabilityUi(
    val id: String,                 // 稳定能力ID
    val binding: BindingMode,
    val availability: Availability,
    val reasonKey: String?,         // 资源键；未知不视为可用
    val requiredEntitlements: Set<String>
)

data class UiError(
    val code: String,               // 脱敏稳定码
    val messageKey: String,
    val allowedActions: Set<String>,
    val retryable: Boolean
)
```

每个屏幕还需按本板块定义内容、选择、分页、提交、等待和错误；不把全部状态塞进一个含数百字段的AppState。`LoadPhase`不能代替领域任务阶段。混合页面每个动作有CapabilityUi，页级“部分模拟”标记只是摘要。

`messageKey`由AndroidUiTextResolver映射为R.string及类型化参数；允许现有UI层直接使用资源ID，但Core不能依赖R。外部内容保持源文，错误模型不包含原响应、堆栈或请求凭证。

## 4. 统一屏幕输入输出

推荐形态：

```kotlin
interface ScreenUiPort<S, I> {
    val state: StateFlow<S>
    suspend fun dispatch(intent: I): DispatchResult
}
// DispatchResult区分Accepted(commandId/taskId)、Rejected(reason)和NoOp。
// 领域任务结果继续由state及任务观察接口提供，不把Accepted当Succeeded。
```

这层在UI/ViewModel与真实领域之间，不能把所有领域方法重新包为第二套通用RPC。实际项目已有Flow/ViewModel入口时复用其签名并满足本文件语义。

短暂Snackbar可使用受控UiEffect；导航与授权请求不能依靠无重放Channel作为唯一事实。关键用户动作状态保留`requestId/commandId`、是否已启动/已消费/已取消，并与领域对账。新的Activity可恢复显示等待说明，但不得自动重放MediaProjection请求或购买。

## 5. Shell与恢复

`ShellUiState`包括：根目的地、官方导航返回栈引用、辅助设置入口、当前LayoutCapabilities、主题/语言选择、模拟摘要。Shell不复制领域任务列表。

可保存：根目的地、非敏感ID、页码、公开筛选、列表锚点、普通编辑草稿参数。无痕搜索/网址/历史和私密媒体标题不进入普通SavedState或日志；Vault只允许恢复锁定入口，不恢复解锁Boolean。

窗口变化复用TaskId/MediaId/TabId与同一选择状态。选择已失效时显示“已不可用”并清除选择，不能自动选择另一媒体并执行原命令。

## 6. 发现契约

```kotlin
enum class RepeatPolicy { PREFER_NEW, HIDE_WATCHED, HIDE_SHOWN, SHOW_ALL }
data class VideoCardUi(
    val resultId: String,
    val title: String,
    val providerId: String,
    val providerLabel: String,
    val preview: PreviewRef?,       // 引用类型由已有模型映射
    val durationMs: Long?,          // null=未知
    val openPageRef: String,        // 受控网页引用/已校验地址
    val isSelected: Boolean
)
data class PageUi(
    val pageIndex: Int,             // 1-based
    val pageSize: Int,              // 建议20
    val materializedPages: Set<Int>,
    val totalPages: Int?,           // 未知为null，不估算假尾页
    val canRequestMore: Boolean,
    val isExhausted: Boolean
)
```

`DiscoveryUiState`：profileId、queryRevision、关键词、来源选择、RepeatPolicy、搜索强度、LoadPhase、cards、PageUi、可见锚点、providerWarnings、UiError。来源警告可与正常结果共存，不把一个来源失败映射为全部ERROR。

意图：SubmitQuery、OpenAddress、SelectProvider、SetPolicy、SetPageSize、RequestPage、RetryProvider、CardVisible、OpenCard、ClearKeywordHistory、ClearAllDiscoveryHistory、ResetProviderProgress、SetVisibility。

规则：

- OpenCard先领域校验网页目标，再交Browser；不调用保存。
- CardVisible由可见区观察去重后发送，遵守现有SHOWN语义；皮肤/语言重组不重复。
- HIDE_WATCHED过滤OPENED或PLAYED；HIDE_SHOWN过滤真正SHOWN；PREFER_NEW不足时补历史；SHOW_ALL仍去重。
- 修改页大小/策略形成明确查询修订，原请求取消或忽略旧结果。不得承诺旧页码仍有效；如重置到第一页需向用户一致呈现。
- UI只消费已物化页；不足时请求领域继续补充，不能自身建立无限爬虫/Paging仓库。
- SetVisibility(false)触发现有发现取消门，与保存任务独立。

## 7. Browser、候选与保存

`BrowserUiState`：TabId列表、activeTabId、可显示地址、canBack/canForward、加载/崩溃状态、Incognito、mediaHint与能力。不暴露Session凭证。一般地址是公开来源数据，但无痕地址禁止落普通恢复/日志。

```kotlin
enum class SaveMethod { DIRECT, STREAM, RECORD }
data class SaveVariantUi(
    val variantId: String,
    val label: String,
    val width: Int?, val height: Int?,
    val sizeBytes: Long?,
    val availability: Availability
)
data class SaveOptionUi(
    val method: SaveMethod,
    val capability: CapabilityUi,
    val gateStatus: String,         // 由FeatureGate映射
    val needsConfirmation: Boolean
)
```

`SaveSelectionUiState`：candidateId/revision、候选列表、选中variantId、destinationRef、三种SaveOptionUi、submitting、commandId、等待原因、error。选择不改变真实来源上下文；null大小显示“大小未知”。

意图：RequestDetection、SelectCandidate、SelectVariant、SelectMethod、SelectDestination、SubmitSave、ConfirmRecordFallback、CancelSelection。Submit携带candidateRevision、幂等键和明确用户选择；Adapter重核有效性与权益，返回Accepted TaskId或拒绝。

Direct免费；Stream/Record需Save Pro。没有权益/未知能力显示原因和可用动作，不伪装故障后自动选择录制。自动方法变化只消费SaveCoordinator已允许的事件，并持续显示当前①②③。

系统授权由独立PlatformInteractionHost执行：

```text
用户点击③并确认
→领域进入WAITING_SYSTEM_CONSENT
→Host发起当前requestId的官方系统授权
→Host校验回调是否仍有效
→领域接受授权并创建录制会话，或归约拒绝/取消
```

授权数据不穿过UiState/SavedState/Core DTO。重复点击不重复授权；模拟演示只能显示说明状态，不仿造系统授权成功创建真实录制。

## 8. 任务状态与恢复

任务UI映射现有状态，建议归一展示：ANALYZING、RUNNING_DIRECT、RUNNING_STREAM、RUNNING_RECORD、FINALIZING、VERIFYING、COMMITTING、WAITING_USER、INTERRUPTED、SUCCEEDED、FAILED、CANCELLING、CANCELLED。若现有枚举更细，保留细节而非删除。

`TaskUi`：taskId、非敏感标题、当前方法、阶段、进度（fraction?/bytesDone?/bytesTotal?）、等待原因、allowedActions、outputMediaId?、UiError?、bindingMode。任务顺序与选中ID稳定。

fraction只在领域已知且有效时0～1，不能从阶段序号推导百分比。SUCCEEDED仅表示验证+入库完成；外部导出有独立ExportTask状态。UI接受命令不等于任务成功。

意图：SelectTask、CancelTask、ResumeAfterCheck、OpenOriginForLogin、ChooseExportTarget、RetryExport、DismissNotice。Retry/Resume需任务层重新校验资源身份/checkpoint；UI不能直接拼接部分文件或循环重试。

取消幂等：commandId与taskId对应，CANCELLING期间再次点击返回NoOp，系统回调与用户命令收敛到一个终态。进程死亡后从现有Recovery重新读，不从UI百分比续跑。

## 9. 录制与编辑

`RecordingUiState`：sessionId?、prepare/consent/recording/paused/finalizing/stopped/error阶段、捕获范围说明、internalAudioAvailability、micEnabled、有效时长、segments、allowedActions、bindingMode。

时长单位统一Long毫秒，使用领域有效片段总和；不能用UI墙钟计时充当产物时长。内部音频未知不显示可用；静音本身不能断言DRM。麦克风默认关闭，启用需能力与独立授权。

`CropSpecUi`：归一化Rect、捕获尺寸引用、旋转、Insets/变换版本；UI只生成用户选择，领域校验偶数尺寸、边界和有效坐标。旋转后未经校验的旧裁切不能自动执行。

`EditorUiState`：inputMediaIds/segmentIds、tool、cropSpec、输出质量模式、estimatedSizeBytes?、preserveOriginal=true、submitting、outputTaskId、error。未知估算显示未知，不保证预测大小。

意图：Prepare、ConfirmConsent、Pause、Continue、Stop、SelectTool、ChangeCrop、ReorderSegments、ChooseQuality、SubmitEdit、CancelEdit。正式命令串行幂等，UI必须遵守allowedActions。编辑成功产出新MediaId，原输入保留。高级录制需Save Pro+Video Tools Pro；纯工具编辑只需后者。

## 10. 媒体库、保险箱与导出

`LibraryUiState`：folderId、items、LoadPhase、selectionIds、selectedMediaId、sort/filter、capabilities、error。媒体字段durationMs/sizeBytes/resolution可null；不伪造元数据。

`VaultUiState`：LOCKED/AUTHENTICATING/UNLOCKED/EXPIRED/KEY_UNAVAILABLE/ERROR；锁定时不能包含私密标题、PreviewRef、可解密Reader或秘密参数。解锁态数据由受控会话作用域提供，自动锁撤销后立即清理UI。

意图：OpenMedia、SelectMedia、Rename、Move、DeleteWithConfirmation、MoveToVault、RequestUnlock、LockNow、BeginExport、ConfirmPlainCopy、ChooseTarget、CancelExport。

Vault授权会话不得变成页面Boolean绕过；模拟状态只访问公开样本，明确演示。真正认证、Keystore绑定、加密/解密由既有Vault持有。免费能力与商店状态无关。

`PreviewRef`抽象建议区分公开网络、普通本地媒体、私密会话引用、主题资源、演示资源。引用包含安全域与版本/会话身份；主题租约不进入Feature，图片Adapter管理。私密图不能走普通磁盘缓存，锁定时取消/清理。

导出独立Command/Task，明确目标与普通副本提示。失败只影响导出，不删除库内MediaId。撤销分享授权不承诺撤回接收App已经复制的内容。

## 11. 设置、来源、权益

`SettingsUiState`：ThemeMode(SYSTEM/LIGHT/DARK)、LanguageChoice(SYSTEM/语言tag)、repeatPolicy、preferredColumns、pageSize、discoveryIntensity、autoUpdatePolicy、隐私选项、提交状态。记录稳定ID而非枚举ordinal；由现有SettingsRepository持久化，非法值迁移为受控默认并记录非敏感诊断。

`ProviderUi`：providerId、名称、enabledByUser、effectiveAvailability、版本、授权域名/能力摘要、更新状态、safeRollbackAvailable。用户关闭、安全禁用和不兼容有不同原因；远程策略不能强制启用用户关闭项。

`EntitlementUiState`：模块ID、OWNED/NOT_OWNED/UNKNOWN、商店可用性、priceLabel?（仅真实Store验证信息）、allowedActions、bindingMode。Core只接grant/Gate，不接SKU价格。UNKNOWN不误判为已拥有，免费能力不受阻。

意图：SetThemeMode、SetLanguage、SetDisplayPreferences、ClearSpecificData、ToggleProvider、CheckUpdate、RequestSafeRollback、OpenProduct、Purchase、RestorePurchase。来源更新与支付验证由现有Adapter；UI不能下载Dex或自行写授权true。

## 12. 绑定台账与确定性场景

台账每行字段：`能力ID / PPT页 / 当前状态 / REAL接口与证据 / DEMO场景 / 依赖 / 负责人 / 发行展示 / 验收状态`。表中“未核实”不是“未开发”。

| 场景ID | 数据与表现 | 关键断言 |
|---|---|---|
| DEMO-01 | 发现20条公开样本、已物化1～3页 | 点击原网页引用，标为模拟；没有伪造总页数 |
| DEMO-02 | 0 Provider、0结果 | 地址入口仍在；明确无可用来源 |
| DEMO-03 | A正常/B超时/C关闭 | 有内容+局部警告，错误不会拖死页面 |
| DEMO-04 | 多候选、大小未知、质量不同 | 提交固定结果，无真实文件写入 |
| DEMO-05 | 四策略历史样本 | DISCOVERED/SHOWN/OPENED/PLAYED/SAVED分别测试 |
| DEMO-06 | Direct64%，Stream封装，等待登录，中断 | 已知百分比和未知阶段表达不同 |
| DEMO-07 | 有效片段20s+30s、暂停 | 总有效50s，无真实Projection |
| DEMO-08 | 框选/压缩/输出模拟 | 原公开样本保持，结果不入真实库 |
| DEMO-09 | 公共媒体/多选/文件夹/导出失败 | 普通原件不变 |
| DEMO-10 | Vault锁定/解锁/自动锁模拟 | 仅公开样本，不写“加密完成”真结论 |
| DEMO-11 | 权益未有/已有/未知，Store不可用 | 模拟授权醒目标识，免费能力可达 |
| DEMO-12 | 满盘/DRM/音频不可用 | 取消与安全出口，无自动录屏降级 |
| DEMO-13 | Light/Dark、中文/英文、长文案 | 同数据ID与场景，不随语言换随机数据 |
| DEMO-14 | 系统授权拒绝/过期返回 | 不创建真实会话，不重放授权 |

时间、随机种子、素材ID与进度步进固定；场景选择器只切换模拟实例，不更改真实用户配置。模拟请求支持取消，释放定时器；测试可手动推进虚拟时间。公开样本和许可归档，不拿用户私密素材生成截图。

## 13. 契约冻结与测试

UI-A先列现有类型映射、UI-B/C冻结页面模型；破坏性变更记录影响Adapter/模拟/测试/PPT页。接口变更不得迫使全部页面重写。

必要契约测试：REAL失败不转DEMO；旧queryRevision结果忽略；命令接受与完成区分；重复提交/停止幂等；锁定撤销资源；null元数据正确显示；页码合法性；后台发现停止与保存独立；主题/语言重建不重放命令。测试验证可观察行为，不仅镜像data class字段。
