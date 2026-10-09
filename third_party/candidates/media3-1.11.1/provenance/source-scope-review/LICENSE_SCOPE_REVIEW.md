# Media3 1.11.1：11 个模块的源码许可与嵌入资源复核

证据截点：2026-10-06T17:04:02.923531+00:00

## 结论与边界

本次仅完成候选证据审查，没有把任何源码、AAR、GLSL 或第三方依赖加入 EasySaveVideo，也未运行 Gradle/Android 构建。

11 个 Media3 模块的 1,160 个发布 Java/Kotlin 源文件均有明确 Apache-2.0 文件头，而且每个文件的 Git blob SHA-1 与固定官方 commit `8c6678b657ede1e7883fc164ef73ed483c7796c3` 完全一致。19 个发布 GLSL 的 AAR 原始字节全部与固定 commit 相同。两个无文件头 GLSL 已逐一追溯到同项目原有 Apache-2.0 文件及精确 AOSP 引用，未仅凭根 LICENSE 判定。未发现这 11 个模块中的 GPL/LGPL/AGPL、其他不兼容条款、专有二进制或捆绑 native 库。

本节所列 Media3 源码/资源按逐制品许可、源头和嵌入资源审计门槛判定为 `PERMITTED_WITH_OBLIGATIONS`（Apache-2.0）。不是全传递依赖准入决定。Guava、Coroutines、ExifInterface 等外部依赖、版本冲突、API/行为兼容性和整个应用发布审查不在本子审查内。

## 精确范围

上游树未截断（truncated=false）。11 个模块共 1,273 个 src/main 文件：1,160 个 Java/Kotlin、19 个 GLSL、83 个 XML 字符串文件、11 个 AndroidManifest.xml；另复核 26 个 README/build/proguard 文件。139 个非 Java/Kotlin下载文件逐个通过上游 tree 的 Git blob SHA-1 验证。

| 模块 | 发布源文件 | 与固定 commit 相同 | AAR SHA-256 | Sources JAR SHA-256 |
|---|---:|---:|---|---|
| media3-common-1.11.1 | 158 | 158 | 50813cf219c7c93ed8a32a750891ca6d1280729ecf7a90c49567adacd67ef31b | c9a67ff8d485c9aeb77229a53ae91e39acdf9f591c9c8aff358307a4da031f21 |
| media3-container-1.11.1 | 16 | 16 | 930fca2f018f06f1d08c12599f8c97f31eddbd728edee5de06eb53a882f8fc54 | 655d0a470126610cceeec1122665d87b041d36942be4eaaab1e01ceb4504fdbd |
| media3-database-1.11.1 | 7 | 7 | 520e6f814e42a7d428db187ac073b453c0fcba0f4f5f4edd1cf33fafd2cfb740 | 1baca4ff0a32e76d08b92ed5d33e0278aa34a9e9c537edbc2fd39573e8ec46a3 |
| media3-datasource-1.11.1 | 57 | 57 | 490fd23c7562a6a1183d63bf854b291ae3a77dce5a151450aa1937148c80c60b | a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a |
| media3-decoder-1.11.1 | 12 | 12 | c3bcd704959026e0d644b0260f9e682a2e26e7357c2cf82d990bcb323f800d49 | 1570e072a3c4a8b9915f95bada24f0e4955e4fc4343d738adbb2794d9539d9c5 |
| media3-effect-1.11.1 | 112 | 112 | aacaf7d4cc410ee0e4fe731a1d1b156d22ce263a45bc6bf17a6dbd6f2182f0ab | a452414ffe8dbc89bf7958120694e061603babb919517b13b79849a01a91685d |
| media3-exoplayer-1.11.1 | 352 | 352 | 588592baec110837c33ce0c82bb76464203e6b0621177bbe5d88779001a6f1ed | a13b3e52cf278f147020d9b98f29ebd5a19e1dc0798f02595c6849291eb33233 |
| media3-exoplayer-hls-1.11.1 | 38 | 38 | bd7aeac8dc60b3ee926eddbe05e29f27dc53c81514cae3271b2edab693f34262 | 27f36d1714018c7b2a17656f8b5fbf3e93b6bec72aac82990c9ae0fab34a3160 |
| media3-extractor-1.11.1 | 280 | 280 | 6b2d58d23a0c53425249d8b7c945600019d61a8b404c8fb8cd9ca860c5738508 | 547129d7df7ef9cc3d2622036d3880ab205e5fe3241095049b39c6632c64bc4a |
| media3-muxer-1.11.1 | 32 | 32 | f68fa5996882a5f08ea0925463bc56e1c2b20ead3f556dadba24686fc4c979f1 | eefa3f69ad13ccc7c59988bd934cc90653fe24ea64f1eb072db5cf9a674ea47e |
| media3-transformer-1.11.1 | 96 | 96 | 86cef51c2b7602a1bacd0c172e24fdd562b4601b616b56f3a39bb85ae1b46218 | 876a3b2ab33638fdef57d085afdbb63b110b8d8c2ba970e9c32ff25589d0e446 |

证据：`source-identity.json`（每个源码文件 SHA-256/Git blob 对应）、`non-java-download-evidence.json`（URL、时间、SHA-256、Git blob）、`scoped-tree.json`。

## 许可文本、NOTICE 与特殊版权

- 官方发布 POM 的许可声明为 Apache License 2.0；发布源码均与官方固定树对应。根 LICENSE SHA-256：`cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`。
- 完整官方树中未发现根或模块 NOTICE/其他 LICENSE 文件；11 个 AAR、其 classes.jar、sources.jar 也未发现 LICENSE/NOTICE/COPYING 条款文件。不能因 AAR 未附带 LICENSE 而免除应用分发方提供完整 Apache-2.0 文本的责任。
- `common/audio/Sonic.java` 的文件头同时保留 `Copyright (C) 2017 The Android Open Source Project` 与 `Copyright (C) 2010 Bill Cox, Sonic Library`，且明确 Apache-2.0。源文件 SHA-256：`6fdaffeca98ad1e4d3d0bfc84eeba5501fb2b69adb8d1f5201e9b76547b4fa04`。应按此精确文件版本判断，不能套用其他版本或独立 Sonic 项目的许可。
- 其他“copyright”搜索结果为媒体元数据术语（例如 copyright character、MPEG copyright bit），没有形成额外第三方版权条款。完整命中供复核：`source-special-notices.json`。

## 两个无头 GLSL 的来源闭环

### insert_overlay_fragment_shader_methods.glsl

- 发布 AAR 路径：`media3-effect-1.11.1.aar!/res/raw/insert_overlay_fragment_shader_methods.glsl`。SHA-256：`943e4effeebc2aca742572e6c215f642d6c340d1c9636a12679214b44524cbe9`；Git blob：`ebc1260c3ee7073958cd771381abb72dd4c1b0a4`。
- 文件首次加入于官方 commit `1640becddd1b84d5be0d815e6f87b8454b5870c4`，提交主题“Use file inserts and string replacements in overlay shaders”，作者记录为 Google 域贡献者，2024-05-29。patch 明确从同模块 `OverlayShaderProgram.java` 的字符串拼接中抽出两个已有函数。
- 抽出前 parent `e41a966237528148590712048dc735dabfd344ca` 的 Java 文件有 AOSP Apache-2.0 头，SHA-256：`3c904cf5c048ba4774e1d29fc317ab8565dee5f39530d60aeea705414ad4b766`。还原 Java 字符串后，两个 GLSL 函数都与当前文件逐字符相同（只忽略空白与注释）。
- 源代码中的 open.gl/textures 链接是纹理 wrap 行为参考；直接可核实的文件来源是上述已有 Apache Java 字符串，而不是本次从教程另行取得源码。历史 patch 没有新增第三方许可或署名条款。
- 首次加入的 GLSL 与当前字节完全一致；后续 `17893cd69d261e84b30789280651c2c874b992aa` 仅从 assets/shaders 移到 res/raw，0 增删行。

### insert_ultra_hdr.glsl

- 发布 AAR 路径：`media3-effect-1.11.1.aar!/res/raw/insert_ultra_hdr.glsl`。SHA-256：`e3925fff5d15ff61c656d8da0252705b3ab52d44d3b98ae400b7363cb49e4221`；Git blob：`9455f8ed2444247c1ba9fa7f380880d48a58d1d1`。
- 文件首次加入于官方 commit `9622411b508684a02f8d6370f88e14b90eef3898`（“Add support for ultra HDR overlays”，Google 域贡献者，2024-05-28）；至固定版本内容未变，仅有同一资源目录迁移。
- 加入前 parent `3bb6cf212957391fddf26c977060300bb0b8ade0` 已有 `fragment_shader_transformation_ultra_hdr_es3.glsl`，带 AOSP Apache-2.0 头，SHA-256：`8645165d621b2ac0089cd118bf27314aaa834b07250e781af2925a4866059ce5`。
- 逐段对应：HDR_SDR_RATIO 与两个 BT 色彩矩阵相同（矩阵只移除 f 字面量后缀）；bt709ToBt2020、scaleHdrLuminance 相同；srgbEotfSingleChannel 只改参数名；srgbEotf 从 vec3 RGB 扩为 vec4 RGBA并透传 alpha；applyGainmap 来自 applyGainmapToBase，改为传入纹理样本和 uniforms，移除原先纹理采样语句后函数体完全相同。没有未对应的实质处理函数。
- gainmap 代码的精确引用为 AOSP frameworks/base commit `fadc20184ccb27fe15bb862e6e03fa6d05d41eac` 的 `libs/hwui/effects/GainmapRenderer.cpp`，已从 android.googlesource.com 获取，明确 AOSP 2023 Apache-2.0；该文件无额外许可头。底层 AOSP 文件为来源证据，没有被作为本候选的 native 代码收入。

### 构建与分发对应

固定版本 `OverlayShaderProgram.java` 第 273/276 行实际加载 `R.raw.insert_overlay_fragment_shader_methods` / `R.raw.insert_ultra_hdr`，并拼入 GLSL；两个文件真实存在于正式 AAR。未以“测试资源”或“可能未使用”排除其义务。证据：`shader-identity.json`、`shader-provenance-comparison.json`、`historical-source-evidence.json`、`history/`、`upstream-aosp/GainmapRenderer.cpp.evidence.json`。

## 本地化 XML

83 套字符串中基础 `values/strings.xml` 有 AOSP 2016 Apache-2.0 头，82 个翻译版本没有独立文件头。它们的资源键值全部与实际 AAR 的对应 XML 相同；所有翻译源码字节与固定官方树一致，且资源集合仅为同一下载功能的翻译字符串，没有字体、图片、音乐、模型或外部素材。

官方目录历史完整列表只有4条：2021-10-26 的 androidx.media3 迁入（933e207）、2023-02-28 的翻译更新（a7faa5b）、2025-05-14 的翻译更新（028d059）、2026-02-06 的 as/or 翻译新增（d1d4a72）；作者均为官方 Google 域贡献者记录。该目录未发现独立第三方许可或来源声明。这提供了“同包 Apache 基础资源的官方翻译”范围证据，而非将它们误报为逐文件都有 Apache 头。

精确 `media3-exoplayer:1.11.1` POM 明确以 Apache-2.0 发布制品并将 SCM 指向官方 androidx/media 仓库。该 POM SHA-256：`f540fd426f3ed2cf58792fad094052876817d764f88b2a072e530105dfe9d797`。复核82份译文全文未发现实际冲突许可、外部第三方归属声明，或明确排除该制品/仓库 Apache 适用范围的证据。结合精确制品声明、固定源码对应、已许可基础资源、官方翻译历史及 AAR 资源键值对应，82个无头译文判为 `PERMITTED_WITH_OBLIGATIONS`，并非仅凭根LICENSE自动放行。

额外抓取这4次提交的完整 patch 遭 GitHub API 403 rate limit，未绕过限制；已保留完整官方历史列表、作者、时间和提交主题。该附加取证限制没有产生与现有制品声明相冲突的实际许可证据。未进行逐贡献者私人合同/CLA权属尽调属于普通审查范围限制，不是本任务准入门槛，也不列为实际 UNKNOWN。精度说明：源码原始字节对应固定Git blob；打包后的AAR XML格式不同，验证的是83/83资源键值一致，不声称全部XML原始字节相等。证据：`localized-resource-correspondence.json`、`non-java-license-headers.json`、`history/exoplayer-res.commits.json`。

## Native、FFmpeg 和媒体素材

- 11 个 AAR 共 2,577 个 class，全部位于 androidx/media3；无 bundled 非 Media3 class；除了 effect 的 Kotlin module 元数据，没有其他 nested 非 class 载荷。
- 无 jni/、libs/、prefab/、.so、.a、.o、.dll、.dylib、可执行文件或额外 vendor JAR。没有本次实际收入的 FFmpeg、libvpx、Opus、FLAC、MPEG-H/IAMF 原生实现。
- `DefaultRenderersFactory` 存在可选扩展反射引用与对应 ProGuard keep 规则，默认 extensionRendererMode=OFF；引用类名不是打包证据。若以后显式加入扩展，必须另外审查扩展及其构建的第三方 native 依赖。
- common `LibraryLoader` 是抽象加载辅助类；effect `HardwareBufferJniWrapper` / common `HardwareBufferNativeHelpers` 是接口。它们不等于随这11个模块打包的 native 实现。effect 的 `lib-effect-ndk` 依赖仅出现在 androidTestImplementation，未进入本范围生产 AAR。
- build.gradle.kts 中共用 test_data 的 assets 仅加入 test/androidTest source set；没有将测试媒体、golden 图片或测试 native 扩展计入生产分发。

证据：`runtime-payload-summary.json`、`aar-payload-inventory.json`、官方模块 build.gradle.kts、固定源码 `DefaultRenderersFactory.java`。

## 拟准入后需履行的 Apache-2.0 义务

这些是后续采用时的合规清单；本次没有修改应用 notices 或准入清单。

1. 给接收者提供完整 Apache-2.0 许可证副本；不能只给一个 SPDX 标识。
2. 任何修改过的文件需明确说明修改；分发源码形式时保留相关版权、专利、商标和署名通知，包括实际以 GLSL 文本形式分发的资源。
3. 若以后所用分发版本带 NOTICE，应按 §4(d) 保留相关署名。当前固定树和这11个工件未找到 NOTICE；不要声称存在一份上游 NOTICE，也不要凭空创造上游条款。
4. 在应用第三方说明中保留 Media3/AOSP 署名，列明 Sonic 的 Bill Cox 版权和已追溯的 shader AOSP 来源，是本候选的保守合规做法。不要把这份自拟说明称为原版上游 NOTICE。
5. Apache-2.0 不要求发布应用自身闭源代码；但专利授权限于贡献者有权授予的必要权利，且含专利诉讼终止条件，不是所有编解码标准、设备实现、内容下载权利的全面授权，也不授予额外商标权。

权威条款：https://www.apache.org/licenses/LICENSE-2.0 （§§2–4、6–9）。

## 未声称完成的验证

- 未做可复现构建，故“source JAR 字节=固定 commit”不等于证明 AAR 编译字节一定由该源码可复现生成。已分别记录官方 AAR 和 source JAR 的哈希及来源。
- 未证明每个贡献的版权权属合同，也未审查标准必要专利、分发地区专利风险、媒体内容本身的下载/转码授权或第三方 DRM 服务条款。
- 本范围内两无头 GLSL 的直接代码来源已闭合；无头本地化资源也已按实际逐制品/来源/嵌入资产审计门槛确认。未发现本范围内阻止闭源分发准入的实际许可 UNKNOWN。
- 所有非 Media3 传递依赖及跨版本替换由主审单独裁决；本报告不能用来维持、变更或扩张原有批准依赖集合。

## 关键官方链接

- 固定树：https://github.com/androidx/media/tree/8c6678b657ede1e7883fc164ef73ed483c7796c3
- LICENSE：https://github.com/androidx/media/blob/8c6678b657ede1e7883fc164ef73ed483c7796c3/LICENSE
- overlay 抽取：https://github.com/androidx/media/commit/1640becddd1b84d5be0d815e6f87b8454b5870c4
- ultra HDR 加入：https://github.com/androidx/media/commit/9622411b508684a02f8d6370f88e14b90eef3898
- 资源迁移：https://github.com/androidx/media/commit/17893cd69d261e84b30789280651c2c874b992aa
- 精确 AOSP 参考：https://android.googlesource.com/platform/frameworks/base/+/fadc20184ccb27fe15bb862e6e03fa6d05d41eac/libs/hwui/effects/GainmapRenderer.cpp
