# Media3 1.11.1 Transformer + HLS：固定候选依赖与闭源许可审计

日期：2026-10-06 UTC。基线：EasySaveVideo M3-A commit `87d45ffc94e949ad0c15d0b9989d2d730287fb82`。Media3 固定上游 commit：`8c6678b657ede1e7883fc164ef73ed483c7796c3`。

## 1. 决定

本次精确组合的 **23 个新增/换版运行 GAV 可判 `PERMITTED_WITH_OBLIGATIONS`**：已找到闭源可履行许可路径，本次软件许可范围内没有剩余实际 UNKNOWN、GPL/LGPL/AGPL、不可再分发媒体资产或捆绑专有/native 解码器。此结论不要求公开 ESV 自有源码。

这是候选许可审计通过，不是应用已采用，也不是发布批准。主工程、原 53 官方 + 2 自有依赖准入文件、锁文件、代码、证书/trust 均未由本任务修改。未运行 Android build、设备、真实站点媒体或发布操作。**实际 App 候选 Debug/Release 图、编译/API 行为、HLS 导出及最终 APK/AAB notice 可达性仍未验收。**

若产品不愿履行 CC-BY 的归属及不得附加限制条件，则 jsr305 及依赖它的这套未经调整候选闭包不得采用；不能把混合许可改写为 Apache-only。没有通过假设 R8 会删掉相应类来豁免义务。

## 2. 解析方法和精确图

入口仅有：

- androidx.media3:media3-transformer:1.11.1
- androidx.media3:media3-exoplayer-hls:1.11.1

独立 `gradle-project` 使用 Gradle 9.6.0 的 Java 属性匹配和现工程 Kotlin Gradle plugin API 2.2.10 的原生 `KotlinPlatformType` 匹配规则，consumer 为 Android / androidJvm / java-runtime。没有应用 Android plugin、运行 Android tasks、排除传递库、改写 POM/.module、force 版本或改许可白名单。单独把官方固定 URL 原始文件复制到 file repository 后真实离线解析；字节和原始 URL 均保留。曾遇到 Gradle 代理网络不可达，改用正常默认 HTTPS 校验的官方文件下载，未更改 TLS/trust、未绕过拒绝访问。

- baselineOnly：**55 个唯一 GAV，全部 binary SHA-256 与当前 Debug/Release55 一致**。
- candidateOnly：26 个唯一运行 GAV，仅作为候选闭包观察；其中较旧的 annotation 由现基线较新版本取代，不能直接当成最终准入清单。
- baseline + candidate：**74 个唯一 GAV**，保留原51个精确 GAV/字节，新增19个模块，另4个模块换版；23个新增 GAV逐件审核。
- 原始 Gradle artifact entry 计数分别为56/75：同一个 `androidx.core:core:1.13.1` 文件因两条 artifact 选择记录出现两次。已按 GAV + binary SHA 去重，两个记录是同一路径、同一字节；不是第56/75件库。
- 推荐74-GAV图运行 class 全量静态检查：**0 个跨 GAV 同名类**。AAR只计 classes.jar 和 libs/ 中的运行JAR，lint.jar等构建工具不混入App runtime。
- 所有11个 Media3 AAR 为1.11.1，AAR元数据 `minCompileSdk=36`、`minAndroidGradlePluginVersion=1.0.0`、无core-library-desugaring强制要求；现工程compileSdk36满足该项静态最低条件。不能由此推导整体构建/设备兼容已经通过。

证据：`GRAPH_VERIFICATION.json`、三个 `*-resolution.json`、`combined-inventory.tsv`、`resolution-and-lock-experiment.log`。逐件真实 binary、source JAR、POM/.module SHA-256 和原URL见 `CANDIDATE_COMPONENTS.json`；没有依赖组织名自动批准。

## 3. 必须迁移的4项

| 模块 | 当前 | 推荐候选自然解析 |
|---|---|---|
| androidx.concurrent:concurrent-futures | 1.1.0 | 1.2.0 |
| com.google.guava:listenablefuture | 1.0 | 9999.0-empty-to-avoid-conflict-with-guava |
| org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm | 1.7.3 | 1.9.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.7.3 | 1.9.0 |

原因：Media3 transformer/effect/HLS声明concurrent-futures1.2.0；effect引入coroutines-guava1.9.0及其BOM，应与core/android1.9.0对齐；Guava33.3.1-android引入empty listenablefuture以避免重复类型。

**不要用旧锁硬压出“解析成功”。** 诊断性strict基线约束实验确实可成功解析，但会把新版所请求的concurrent/coroutines降到旧版，并将旧listenablefuture1.0与完整Guava一起选入。静态实证两者重复包含 `com/google/common/util/concurrent/ListenableFuture.class`。所以这份strict实验图不准入，不建议降级，不以Gradle解析成功证明ABI兼容。推荐图采用官方依赖自然收敛版本，无这些强制降级。

## 4. 可工程准入的精确新增集合

以下每行均须连同字节hash和义务使用，不能推广到其他版本/模块：

| GAV | 许可判断 |
|---|---|
| androidx.media3:media3-common:1.11.1 | Apache-2.0 |
| androidx.media3:media3-container:1.11.1 | Apache-2.0 |
| androidx.media3:media3-database:1.11.1 | Apache-2.0 |
| androidx.media3:media3-datasource:1.11.1 | Apache-2.0 |
| androidx.media3:media3-decoder:1.11.1 | Apache-2.0 |
| androidx.media3:media3-effect:1.11.1 | Apache-2.0 |
| androidx.media3:media3-exoplayer:1.11.1 | Apache-2.0 |
| androidx.media3:media3-exoplayer-hls:1.11.1 | Apache-2.0 |
| androidx.media3:media3-extractor:1.11.1 | Apache-2.0 |
| androidx.media3:media3-muxer:1.11.1 | Apache-2.0 |
| androidx.media3:media3-transformer:1.11.1 | Apache-2.0 |
| androidx.concurrent:concurrent-futures:1.2.0 | Apache-2.0 |
| androidx.exifinterface:exifinterface:1.3.6 | Apache-2.0 |
| com.google.code.findbugs:jsr305:3.0.2 | Apache-2.0 **AND CC-BY-2.5** |
| com.google.errorprone:error_prone_annotations:2.28.0 | Apache-2.0 |
| com.google.guava:failureaccess:1.0.2 | Apache-2.0 |
| com.google.guava:guava:33.3.1-android | Apache-2.0 AND CC0-1.0 AND Public-Domain，按文件适用 |
| com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0；空占位包 |
| com.google.j2objc:j2objc-annotations:3.0.0 | Apache-2.0 |
| org.checkerframework:checker-qual:3.43.0 | MIT；不是GPL checker engine |
| org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0 | Apache-2.0 |

## 5. 实际源码、NOTICE、内嵌资源核验

Media3：1160/1160发布Java/Kotlin文件均有Apache头，并逐文件匹配固定commit。11个AAR共2577个Media3 class，无附加vendor class或native库。19个GLSL字节均与AAR一致；其中2个无头片段已追溯到原有Apache Java/GLSL及精确AOSP文件，不能仅靠根LICENSE判定。83套本地化资源的源文件固定Git blob对应和AAR键值对应已核；译文的精确POM许可、官方导入历史、已许可基础资源及无冲突声明共同支持Apache范围，不声称所有译文都有许可头。

特别保留Sonic.java中的Bill Cox 2010 / AOSP2017版权；该精确Java实现明确Apache，不套用其他Sonic项目版本的许可。当前11件AAR/source和固定树未发现上游NOTICE；随App提供完整Apache文本仍是义务，不能用“JAR没附LICENSE”豁免。

非Media3：Guava615/615和coroutines172/172源文件与各固定官方tag逐字节匹配；checker-qual binary/source中真实MIT全文已提取；failureaccess只有两个Apache源文件，不误套Guava CC0例外；jsr305四个CC-BY-2.5注解源码及对应class全部真实存在；empty listenablefuture零class，source JAR404是已解释的空包，不是遗失实现的UNKNOWN。

三个coroutines1.9.0 Maven JAR虽没有NOTICE，固定上游 `license/NOTICE.txt` 已取回，应保留“Copyright 2016-2024 JetBrains s.r.o and contributors”。

23件候选全部binary、archive成员、源文件头、特殊归属、嵌入资源已盘点，未发现native库。Media3可选FFmpeg/其他扩展的反射类名不是打包证据；所有此类扩展仍不在本次许可集合内。未取用测试媒体素材。

## 6. 必须落实的分发义务

1. Apache组件：随App给完整Apache-2.0副本，保留适用版权及上游NOTICE；修改上游文件需明显说明变更。发布源形式（包括原样GLSL文本资源）应保留相应头部。无需开放ESV私有业务代码；不得暗示作者背书。
2. checker-qual：随二进制保留实际MIT版权、许可全文和免责声明。
3. jsr305的CC四注解：清晰署名Brian Goetz，标明GuardedBy/Immutable/NotThreadSafe/ThreadSafe及来源与CC BY2.5许可URI；保留声明，说明随Android App包含。不得给这部分作品施加与CC授权冲突的EULA/DRM/技术限制，不能当作ESV独占授权或排他再许可。闭源整体App可作为collective work，但CC所覆盖部分的接收者权利须保留。DEX/R8机械变换不能被笼统说成“完全没有任何改动”；当前仅证明未改官方输入制品字节。
4. Guava：分别保留Apache与实际Doug Lea CC0/public-domain、MurmurHash归属，不将其改报MIT/GPL，不把特殊文件条款扩大到failureaccess。
5. 已准备Apache/MIT完整文本、协程原始NOTICE、CC许可URI和混合归属草案。CC官方raw下载403没有绕过；已保存正常官方网页条款证据。CC BY §4(a)允许许可副本或URI，因此使用准确URI可以履行该项，不伪造未取得的全文。
6. 采用时先保存本候选证据并更新精确准入/锁，新增可离线访问的第三方许可展示/随包文本和EULA开源例外；在实际Debug/Release图及最终APK/AAB上复核仍包含哪些代码、资源、native和可访问notice。对未证明剥离的内容保持保守义务。

本审计未替应用实际完成以上集成动作，**分发gate仍为 `DENY_UNTIL_NOTICES_AND_REQUIRED_CONDITIONS_VERIFIED`**。

## 7. 不确定/不准入清单与能力边界

本23件软件许可范围内actual UNKNOWN为空。以下仍不准入或不在通过范围：

- 任何不同版本、额外Media3模块、FFmpeg/libvpx/Opus/FLAC/MPEG-H/IAMF等codec扩展、直接迁移源码、外部字体/图像/媒体fixture，需新精确审计。
- 上述strict旧锁降级图：已验证重复ListenableFuture class；其余downgrade的API/行为也未验证。
- 未履行CC/EULA/DRM、Apache/MIT/NOTICE条件的发行方案；未知来源artifact及哈希不符文件。
- HLS的加密/DRM/动态format/discontinuity/fMP4独立音轨功能、网络安全、独立MP4封装、解码/seek/时长/AV同步：许可pass不承诺功能。必须另做受控合法fixture及Android设备验证。
- 整个ESV发布合法性、用户媒体下载权利、标准必要专利/地区codec专利许可、商店条款、各贡献者私人合同/CLA尽调不在本工程开源许可审计范围。Apache专利许可不是所有codec专利保证。
- 原53件许可与自有SkinKit2件的既有发布边界不因本审计被扩大或撤销；本次没有重新宣布整App可以发行。

## 8. 交付目录与采用顺序

`ready-to-import/third_party/candidates/media3-1.11.1/` 是隔离、尚未复制进ESV的证据目录：

- `CANDIDATE_GATE.json`：候选审查决定、未采用/未发布状态
- `CANDIDATE_COMPONENTS.json`：23件精确binary/source/POM/module哈希、URL、许可与特殊情况
- `PROPOSED_RUNTIME_DELTA.tsv` / `PROPOSED_COMBINED_RUNTIME.tsv`：23件变化与74件候选图；文件名称刻意不是当前admitted-runtime.tsv
- `metadata/`、`metadata-parents/`、`sources/`：原始固定审计输入，不作为App源码导入
- `licenses/`、`notices/`、`COPYRIGHT_ATTRIBUTIONS.txt`、`LICENSE_LINKS.txt`：分发合规输入和审阅草案
- `artifact-inspections/`、`provenance/`、`resolution/`：逐文件、特殊来源、图和诊断证据
- `IMPORT_MANIFEST.json` / `SHA256SUMS`：本候选包内容校验

后续只应在明确采用权限下：导入候选证据 → 实现许可证/NOTICE可访问分发义务 → 精确升级4项/新增19模块并更新准入 → 独立实际App图/编译/测试 → HLS合法fixtures及设备验收。不能因为本目录叫ready-to-import就自动执行这些步骤。

完整审计工作区还保留官方runtime字节、原始下载URL证据、恢复过程日志和隔离file repo。报告中的许可决定为工程审计意见，不是专业法律意见或完整第三方权利担保。

## 官方来源入口

- Media3固定源码：https://github.com/androidx/media/tree/8c6678b657ede1e7883fc164ef73ed483c7796c3
- Transformer固定POM：https://dl.google.com/dl/android/maven2/androidx/media3/media3-transformer/1.11.1/media3-transformer-1.11.1.pom
- HLS固定POM：https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer-hls/1.11.1/media3-exoplayer-hls-1.11.1.pom
- Guava固定源：https://github.com/google/guava/tree/v33.3.1
- Coroutines固定源：https://github.com/Kotlin/kotlinx.coroutines/tree/1.9.0
- Apache条款：https://www.apache.org/licenses/LICENSE-2.0
- CC BY2.5条款：https://creativecommons.org/licenses/by/2.5/legalcode
- CC0条款：https://creativecommons.org/publicdomain/zero/1.0/legalcode
