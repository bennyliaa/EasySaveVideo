# ESV现有精确版本运行依赖许可义务（恢复件）

本目录为新生成的恢复包，不能写成旧73文件完整原字节恢复。详见RECOVERY_STATUS.json。53个官方同版本组件有PERMITTED_WITH_OBLIGATIONS工程依据；2个自有SkinKit仅对外发行权属记录UNRESOLVED。构建负责人输出的Debug/Release实际运行图已静态对齐53+2，详见ACTUAL_RUNTIME_GRAPH_VERIFICATION.json；这不是最终APK/AAB内容审核。

A1. 对Apache-2.0组件，随分发提供完整许可与对应组件/版权归属；保留实际适用NOTICE；分发源码时保留相应版权/专利/商标/归属声明；若改上游文件应标明变更。已收集的source jar只有20项自带许可全文，不能由其余未自带文本推导免除许可义务。许可族或hash都不等于整体发布许可通过。

K1. Kotlin stdlib 2.2.10的Instant.kt包含ThreeTenBP派生代码。含该代码的二进制发行须随附BSD-3-Clause全文、Stephen Colebourne与Michael Nascimento Santos版权、条款和免责，不用作者名背书。这里未对Release裁剪作结论。

K2. MathJVM.kt的Boost派生内容已按官方Kotlin许可范围取回Boost-1.0原文。建议随App提供，但该许可证对纯机器可执行目标码有文本随附例外，不能一概宣称所有APK均强制附此文本。若分发所覆盖源码/非纯目标码，保留完整版权/许可。

K3. Kotlin中的GWT/Guava Apache派生代码、Compose runtime内嵌JetBrains immutable collections、ui-graphics FastFloatParser注明的Apache派生来源，均在逐项归属内。provenance/KOTLIN_COMPILER_NOTICE_NOT_APP_RUNTIME.txt仅证明上游NOTICE的Compiler scope，不可作为App runtime所含编译器的证明，也不应把其余编译器/测试许可混入App许可判断。

N1. graphics-path 1.0.1官方发行关联提交的10份native C++/头文件Apache许可声明和构建依据已重新取回；记录的是固定源码许可及-nostdlib++标志，旧四ABI ELF原始报告没有恢复；已对当前制品重做4ABI静态读取，结果另存NATIVE_CURRENT_BINARY_INSPECTION.json，未执行或分发库。不能因源码提及Skia布局就宣称捆绑完整Skia，也不能用当前源码头代替最终Release native成分检查。

S1. Contract/Default 0.0.1来自用户自有SkinKit并已有UI内部复用指示；两个POM和制品与保留旧hash相符。缺公共license字段不等于MIT/Apache，亦不撤销既有内部使用。外发前由实际权利主体保留自有/外部贡献权属与ESV闭源分发归属记录。

R1. 实际Debug/Release解析图、二进制hash与许可索引已对齐；最终APK/AAB中真实保留代码/native内容、可访问的许可/NOTICE、商店与内容/隐私要求仍待发布阶段验证。本次未构建、安装、部署或生成APK/AAB。

新版本/子模块/候选/源码迁移均不自动从此表取得准入。Material/Material3不在原53项中；Yue/Captord/Scrapling仍未采用；NewPipe GPL版排除；SearXNG未接入。BOM 2025.06.01是平台元数据，不是第54个官方运行二进制；Kotlin Compose compiler 2.2.10是构建工具，不能混记为本53项App runtime审查。

这份材料是工程审计证据，不是专业法律意见、应用市场批准或全部第三方权利穷尽保证。
