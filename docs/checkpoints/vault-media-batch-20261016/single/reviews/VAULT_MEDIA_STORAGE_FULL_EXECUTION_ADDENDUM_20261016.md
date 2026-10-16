# Vault 单项媒体 storage：全量验证独立收口

2026-10-16 07:17 UTC。只读中央冻结证据、逐 XML/SARIF 复算、56 项目标哈希重算；没有运行构建、测试、Android DB、设备、凭据或旧 probe。此前 focused 与源码报告字节保持不变。

## 结论

本 scope 可按已冻结源码和 host 回归结果收口。全量 13 模块测试及双 lint 成功，新增 owner-drain 三个负例在最终字节上实际通过；本次没有剩余已报告源码阻点。生产仍为 false，真实 Android/设备验收边界继续保留。

## 全量结果

证据目录 `esv-runtime-copy-20261015/evidence/vault-single-media/`：

- full-first.exit 内容 0，日志 BUILD SUCCESSFUL in 5m4s。
- 独立遍历 full-first-xml：568 suites，6284 tests = 6282 PASS + 2 SKIP，0 failure、0 error。
- 13 模块 test tasks 和 app lintDebug/lintRelease 本轮实际执行。Debug/Release Kotlin 编译为同源 UP-TO-DATE；最终字节的双编译已由此前 third 实际完成。
- 两个 skipped 测试名：`FrozenRecoveryComponentTest.authored80MutationsActualJsoupComponent`、`FrozenCorpusHostTest.same100FrozenBodiesActualStaticB`。没有把 skip 算 PASS；未访问其旧测试输入或 probe。
- 双 lint SARIF 都是 77 warnings + 10 notes，0 errors。等级按 result.level 或对应 rule.defaultConfiguration.level 解析；不能把省略 result.level 的 note 默算 warning。两份 SARIF 字节相同。
- Files 最终7项与 owner/codec/profile/source-contract 消费者的具体范围见 focused execution addendum；全量 app 4360项中保持零失败。

## 哈希和无漂移范围

- FINAL_TOTALS.json：`35945209445b1aba4f87d0b40eaf5da4ab24a96b753e54b0b4eba2075df66ad4`。
- FINAL_SOURCE_MANIFEST.json：`5cb9065737beaef79e553f77e3ef1a17ace832af865527b807fdb4849ec807a4`，与 FOCUSED_THIRD_MANIFEST.json 逐字节相同。
- 56 个最终目标在审查时中央 source 全部重算匹配；此前已核同56个冻结 focused-third-source 也匹配。LOCAL_SOURCE_MANIFEST.json 内对应56项一致。
- LOCAL_SOURCE_MANIFEST.json：`dac68666a6a5b2d04512b84df9b1f0d0d9f1dd9ddd04883a7651b8f6b888f943`，2742条且路径唯一。本报告核清单结构及本 scope 的56个条目，不声称逐读/重哈希所有旧 candidate/probe/冻结输入内容。
- full-first.log：`f73463a1ca540d030782e52cca9db21ab6a0ca1742af21db33b977b080979029`。
- full-first.exit：`9a271f2a916b0b6ee6cecb2426f0b3206ef074578be55d9bc94f6f3fe3ab86aa`。
- full-first-lint/lint-results-debug.sarif 与 release.sarif 均为 `08394a30d3b866f35fbac600e04e35a390f3f95f357f0420699f1153565c8d1c`。

## 不扩张的验收结论

这证明中央编译、固定公开输入/JVM 临时文件的 owner 状态机、既有回归与 lint 在这些最终字节上通过。Android SQLite 实际事务/断电、内部文件系统真实 fsync/rename 耐久、SAF system provider/临时授权/OEM profile、实际 native/FD/窗口生命周期仍待设备验收。明确导出是复制且保留 Vault 原件；未知 native 释放/提交结果继续阻止虚假 cleanup ACK。没有启用生产 Vault，没有以 UI 提示代替真实 readback/receipt，没有删除旧 dependency-only gate，也未自动删除外部历史副本。
