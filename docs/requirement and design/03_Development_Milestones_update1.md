# EasySaveVideo --- 03 Development Milestones --- Update 1

**主题：** 将 `Search/API → Scrapling Validation → VideoSearchResult`
正式纳入 M0 与后续开发阶段\
**Android：** `com.easysavevideo.app`；minSdk Android 11 / API 30

# 1. 总体Gate原则

``` text
需求/规格冻结
    ↓
M0 技术 + 开源源码 + 许可证 + 兼容性可行性验证
    ↓
       ┌───────────────┐
       │ M0 Gate通过？ │
       └───────┬───────┘
          NO   │   YES
          ↓    │    ↓
      修正架构 │   M1正式App开发
               │
               ↓
       M2 Discovery → M3 Save → M4 Record → ...
```

**M0未通过，不进入M1。**

本次更新在M0增加独立工作包：

> **M0-Scrapling / Search-to-Validation Feasibility**

并与 SearXNG、NewPipeExtractor、Yue Browser、Captord、Media3
等一起进行源码、许可证和技术验证。

------------------------------------------------------------------------

# 2. M0目标

M0不是正式产品开发，而是降低以后推倒重来的风险。

M0必须回答：

1.  哪些开源项目可以直接复用？
2.  哪些只能重构/隔离/参考？
3.  GPL/AGPL/BSD/Apache/MIT等许可证如何影响商业上架？
4.  Android API 30兼容性如何？
5.  `Search/API → Scrapling` 是否能显著提高Video Discovery结果质量？
6.  Scrapling应该运行在哪里？
7.  ①Direct、②Stream、③Record是否有稳定技术路径？
8.  Provider零硬依赖是否可以真正做到？
9.  Android/iOS共享Specification是否合理？
10. 是否存在会导致M1以后大规模返工的阻塞项？

------------------------------------------------------------------------

# 3. M0开源项目审计矩阵

M0对每个候选输出以下结论：

  --------------------------------------------------------------------------------------------------
  项目               主要用途              M0重点                                  最终分类
  ------------------ --------------------- --------------------------------------- -----------------
  Yue Browser        WebView浏览器基础     架构、Compose、WebView、许可证、API30   待审计

  Captord            MediaProjection录制   录屏、内部音频、前台服务、许可证        待审计

  AndroidX Media3    播放/媒体处理         HLS、播放、Transformer等                待审计

  SearXNG            搜索广度              API/部署/AGPL隔离                       待审计

  NewPipeExtractor   视频站深度Provider    Extractor架构/GPL隔离                   待审计

  Scrapling          网页验证/动态提取     Fetcher/Dynamic/XHR/Adaptive/许可证     待审计
  --------------------------------------------------------------------------------------------------

统一分类：

-   `CAN REUSE`
-   `REFACTOR`
-   `ISOLATE`
-   `REFERENCE ONLY`
-   `REJECT`

必须记录审计的仓库、版本/Commit、日期、License和依赖。

------------------------------------------------------------------------

# 4. M0-Scrapling 工作包

## 4.1 样本集

选择 **10～20种不同结构、合法公开可测试的视频网页**。

尽量覆盖：

-   静态HTML视频页；
-   JavaScript动态视频页；
-   XHR/fetch加载metadata的页面；
-   HLS/m3u8播放页面；
-   多种不同站点布局；
-   页面metadata完整/不完整情况；
-   可模拟轻微DOM结构变化的样本；
-   无视频的相似页面作为负样本。

不得以破解DRM、绕过系统保护或违反访问控制作为测试目标。

## 4.2 验证流水线

``` text
Search/API/SearXNG
      ↓
Candidate URLs
      ↓
AsyncFetcher / Fetcher
      ↓
 ┌────┴───────────┐
 ↓                ↓
成功           信息不足
 │                ↓
 │          DynamicFetcher
 │          JS + DOM + XHR
 └──────┬─────────┘
        ↓
Adaptive / 字段检查
        ↓
VideoSearchResult
        ↓
Filter / Deduplicate
        ↓
ResultPool
```

## 4.3 必测指标

  指标                    说明
  ----------------------- ---------------------------------------------------
  L1成功率                Fetcher/AsyncFetcher直接完成验证的比例
  Dynamic补救率           L1失败后DynamicFetcher成功比例
  XHR/API Capture有效率   能否获取有价值结构化metadata
  Adaptive恢复率          DOM轻微变化后重新定位能力
  视频页识别准确率        真视频页被正确识别
  误判率                  非视频页被误认为视频页
  漏判率                  视频页未被识别
  字段完整率              title/thumbnail/duration/source/pageUrl等完整程度
  平均耗时                单页及批量验证耗时
  CPU/RAM                 Worker/Chromium资源
  网络开销                单页/批次流量
  并发吞吐                不同并发下的速度/稳定性
  Timeout/Crash率         动态页面、Chromium异常情况

**M0先测量，再根据数据制定正式阈值。** 不在测试前伪造成功率目标。

------------------------------------------------------------------------

# 5. Adaptive专项测试

构造或选择可以安全模拟DOM变化的页面：

``` text
版本A
 ↓
记录目标元素特征
 ↓
改变class / 层级 / 周边DOM
 ↓
版本B
 ↓
Adaptive重新定位
 ↓
统计：
成功 / 误匹配 / 无法匹配
```

必须关注"误匹配"。错误地把别的内容当成标题/视频，比明确失败更危险。

------------------------------------------------------------------------

# 6. XHR/API Capture专项测试

对动态网页记录：

``` text
页面加载
  ↓
XHR/fetch请求
  ↓
Scrapling捕获
  ↓
是否包含：
title / thumbnail / duration / media metadata
  ↓
是否比DOM解析更完整/稳定
```

结果用于决定哪些Provider适合"API Capture优先"，哪些仍以DOM为主。

------------------------------------------------------------------------

# 7. Scrapling部署决策

M0必须比较：

  方案               成本   稳定性   Android负担   iOS复用   运维 建议
  --------------- ------- -------- ------------- --------- ------ ------------
  Server/Worker      待测     待测            低        高     有 M0决定
  开发维护工具      低/中       高            无        高     低 M0决定
  Android本地        待测     待测        高风险        低     低 当前非首选
  混合                 中   潜在高            低        高     中 M0重点评估

M0输出：

-   `GO`
-   `CONDITIONAL GO`
-   `NO-GO`

并写明推荐部署模式和理由。

------------------------------------------------------------------------

# 8. M0故障测试

Scrapling相关故障注入：

-   Worker超时；
-   Chromium进程崩溃；
-   JavaScript无限加载；
-   XHR无响应；
-   页面返回空HTML；
-   字段缺失；
-   DOM结构变化；
-   HTTP 4xx/5xx；
-   网络中断；
-   Scrapling服务器不可达；
-   Provider返回异常/超长字段；
-   Validation任务取消。

验收原则：

> Scrapling失败只能影响该验证路径，不得拖死
> `VideoDiscoveryCoordinator`，更不能影响Browser/SaveEngine。

------------------------------------------------------------------------

# 9. M0-Scrapling输出报告模板

``` text
【M0 Scrapling / Search-to-Validation Feasibility Report】

1. 测试环境
- Scrapling版本/Commit：
- Python：
- Chromium/Browser：
- OS：
- Worker配置：

2. 样本
- 总数：
- 静态页：
- 动态页：
- XHR/API页：
- HLS页：
- 负样本：

3. Fetcher/AsyncFetcher
- 成功：
- 失败：
- 成功率：
- 平均耗时：

4. DynamicFetcher
- 补救成功：
- 补救失败：
- 补救率：
- 平均耗时：
- RAM/CPU：

5. XHR/API Capture
- 有效样本：
- 字段提升：

6. Adaptive
- 测试次数：
- 正确恢复：
- 误匹配：
- 失败：

7. VideoSearchResult
- title完整率：
- thumbnail完整率：
- duration完整率：
- pageUrl完整率：
- description完整率：

8. 准确性
- 误判：
- 漏判：

9. 稳定性/资源
- 并发：
- Timeout：
- Crash：
- 网络：

10. License/依赖审计
- Scrapling：
- 关键依赖：
- 商业/分发影响：

11. 部署比较
- Server/Worker：
- 开发维护工具：
- Local：
- Hybrid：

12. 结论
- GO / CONDITIONAL GO / NO-GO
- 推荐模式：
- 风险：
- M1建议：
```

------------------------------------------------------------------------

# 10. M0 Gate更新

M0通过前必须完成：

-   Yue/Captord/Media3/SearXNG/NewPipeExtractor/Scrapling审计；
-   `Search/API → Scrapling → VideoSearchResult`实测；
-   ①②③技术路径验证；
-   Provider零硬依赖验证；
-   API30兼容路线；
-   GPL/AGPL等隔离方案；
-   Android/iOS Specification边界；
-   Entitlement/StoreAdapter抽象；
-   故障隔离与Recovery设计。

任何关键许可证/架构阻塞未解决：

**不得进入M1。**

------------------------------------------------------------------------

# 11. 后续里程碑更新

## M1 --- Clean Android Foundation

新建干净
`com.easysavevideo.app`，建立Core/Adapter边界、Git、基础Browser、Settings、Fake
Entitlement。Scrapling不应在M1未经M0结论直接嵌入APK。

## M2 --- Video Discovery MVP

实现：

-   `Provider Framework`
-   `VideoDiscoveryCoordinator`
-   `CandidateUrlPool`
-   `ValidationPipeline`
-   `VideoSearchResult`
-   `Normalize/Filter/Deduplicate`
-   `ResultPool`
-   `DiscoveryScheduler`
-   Pagination/Grid UI
-   Provider Progress / Seen History基础

若M0选择Server/Worker：

``` text
ScraplingProvider Adapter
    ↓
HTTPS
    ↓
Scrapling Validation Worker
```

若M0选择"开发维护工具"，M2则只接收Provider产生的标准化结果，不让App运行时依赖Scrapling。

## M3 --- Save MVP

实现①Direct和②Stream，保持与Discovery完全解耦。Scrapling发现层不得直接控制SaveEngine。

## M4 --- Record MVP

实现③整屏录制、授权、内部音频能力验证、开始/暂停/继续/停止、MP4。

此阶段形成主闭环：

``` text
Search/API
  ↓
Validation
  ↓
Video Grid
  ↓
WebView
  ↓
① / ② / ③
  ↓
MP4
```

## M5 --- MediaLibrary / Vault

普通媒体库和免费私密保险箱。

## M6 --- Video Tools Pro能力开发

框选、片段、多段拼接、二次裁剪、压缩等。仍使用Fake Entitlement。

## M7 --- Provider Maintainability

结合M0结论完善：

-   Provider Package；
-   Runtime；
-   Repository；
-   签名；
-   远程开关；
-   版本；
-   rollback；
-   last-known-good；
-   Scrapling规则/Worker维护流程（如采用）。

## M8 --- Discovery Quality

完善长期Seen
History、四种重复策略、Provider进度、排序、过滤、低功耗补充。

Scrapling Validation需受ResultPool水位控制，不允许无边界抓取。

## M9 --- Reliability / Compatibility

增加Scrapling专项故障注入：

-   Worker不可达；
-   Chromium crash；
-   JS挂起；
-   XHR无响应；
-   Adaptive误匹配；
-   网站结构变化；
-   字段异常；
-   Validation取消；
-   服务器返回异常。

同时验证：

``` text
Scrapling失效
  ↓
其他Provider继续
  ↓
全部Provider失效
  ↓
地址栏仍正常
  ↓
Browser + SaveEngine仍正常
```

## M10 --- Real IAP

最后才接Google Play
Billing等真实商店。Scrapling/Provider的商业权益通过`PROVIDER_PLUS`等Entitlement控制，不把支付逻辑写进Provider。

## M11 --- Android Release

重新进行当时最新的商店规则、许可证、隐私和Provider远程更新审查。

## M12 --- iOS

iOS实现相同`VideoSearchResult`、Provider协议、Provider
Package规范和EasySaveVideo Specification。

如果Scrapling采用Server/Worker：

``` text
              Scrapling Worker
                 ↑       ↑
                 │       │
             Android    iOS
```

两平台共享验证服务和Provider维护成果。

------------------------------------------------------------------------

# 12. 开发阶段总路线图

``` text
M0
技术/许可证/Search→Scrapling可行性
      ↓ Gate
M1
Android干净主工程 + Browser
      ↓
M2
Discovery + ValidationPipeline + ResultPool
      ↓
M3
①Direct + ②Stream
      ↓
M4
③Record（整屏）
      ↓
核心MVP闭环完成
      ↓
M5
MediaLibrary + Vault
      ↓
M6
Advanced Video Tools
      ↓
M7
Provider Package / Remote Maintenance / Scrapling维护体系
      ↓
M8
Seen History / 四策略 / Discovery优化
      ↓
M9
可靠性 + API30+兼容 + 故障注入
      ↓
M10
真实IAP
      ↓
M11
Android发布准备
      ↓
M12
iOS实现同一Specification
```

------------------------------------------------------------------------

# 13. 最终原则

Scrapling的加入不能改变EasySaveVideo的核心边界：

> **Search/API负责找候选；Scrapling负责验证候选；EasySaveVideo负责过滤、调度和展示；SaveEngine负责保存。**

Scrapling必须是可替换能力，不得成为Browser、SaveEngine或App启动的硬依赖。

M0的目的不是证明"我们一定要用Scrapling"，而是用数据决定：

> **它是否值得用、应该放在哪里、能节省多少Provider维护工作、会带来多少资源与运维成本。**
