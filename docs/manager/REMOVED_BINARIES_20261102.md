# 2026-11-02 工作目录大文件移除记录

用户批准从 `main` 工作目录移除历史安装包及其传输编码，以缩小检出体积。删除使用 `git rm`，**没有改写历史**：所有文件仍在基准提交及更早历史中，外置永久归档 `D:/CodexProjects/EasySaveVideo-Archive-20261101T132000Z` 也保留副本。`.git` 本身大小不变。

- 基准提交（删除前最后一个包含这些文件的提交）：`35a62b553a996c8b72f496a1494310a0723784da`
- 移除：1231 个文件，约 916 MB；仅限二进制扩展名 `.b64`、`.apk`、`.partNN`
- 完整路径清单：[REMOVED_BINARIES_20261102.txt](REMOVED_BINARIES_20261102.txt)

| 目录 | 移除文件数 | 内容 |
|---|---:|---|
| `artifacts/apk-transfer/ui-code4-d9bdf34` | 179 | code4 APK 与 Base64 分块 |
| `artifacts/apk-transfer/ui-code5-c18cb37` | 181 | code5 APK 与 Base64 分块 |
| `artifacts/apk-transfer/ui-code6-afca2ab` | 183 | code6 APK 与 Base64 分块 |
| `artifacts/apk-transfer/ui-code7-9efc346` | 231 | code7 APK 与 Base64 分块 |
| `artifacts/apk-transfer/ui-code8-c09c5db` | 233 | code8 APK 与 Base64 分块 |
| `artifacts/transfers/20261017` | 3 | code9 APK zip 分卷 |
| `verification-transfer/esv-code3-b714a87/signed` | 102 | code3 已签名 APK Base64 分块 |
| `verification-transfer/esv-code3-b714a87/unsigned` | 102 | code3 未签名 APK Base64 分块 |
| `recovery/context-reconstruction-20261017` | 13 | code10–12 APK gz 分卷、slice07 源码 tar 分卷 |
| `recovery/lzf-code12-frozen-diagnostic-20261025-339b2f76` | 4 | code12 冻结诊断源码 tar 分卷 |

保留：上述目录中的清单、收据、README、测试报告、lint 结果与补丁等文本证据；`recovery/` 中的 `code12-to-code13.apk.range-delta.zip` 与冻结 receiver 脚本；`artifacts/test-fixtures/`；设计文档。

## 追加：整个 `recovery/` 目录（同日）

用户确认 `recovery/` 对开发无用后，整体移除剩余 104 个文件（约 7 MB）：`context-reconstruction-20261017/`（各 code 候选清单、收据、审查结论、冻结 code13 receiver、code12→13 增量包）、`lzf-code12-frozen-diagnostic-20261025-339b2f76/`、`receiver-hardening-20261020/`。

- 基准提交：`4b9a65ad0c126447d7bcd64ac1e2e7884de9cdd5`
- 路径清单：[REMOVED_RECOVERY_20261102.txt](REMOVED_RECOVERY_20261102.txt)
- 外置归档 `D:/CodexProjects/EasySaveVideo-Archive-20261101T132000Z/recovery/` 保留更完整的原始恢复材料。

恢复：`git -C D:\CodexProjects\EasySaveVideo worktree add --detach <新目录> 4b9a65a`，或 `git show 4b9a65a:recovery/<路径>`。

## 追加：历史运行证据、设计稿与历史脚本（同日）

用户确认后整体移除 6669 个文件（约 176 MB）。删除前已核对：构建配置、全部源码、全部测试（app/modules/services）与在用脚本均不引用这些路径。

- 基准提交：`e7a4e01c04ad5e73242bee82cb846586f14cea58`
- 路径清单：[REMOVED_HISTORY_20261102.txt](REMOVED_HISTORY_20261102.txt)

| 内容 | 文件数 |
|---|---:|
| `docs/**/evidence/`（37 个目录的历史日志、XML、过程 JSON） | 5241 |
| `artifacts/` 中除 `local-windows-20261009/`、`test-fixtures/` 外的子目录 | 1323 |
| `verification-transfer/` | 15 |
| `docs/ui/source-history-20261011/`（UI PPT 生成源码与图片） | 53 |
| `docs/ui/*.pptx`、`docs/ui/*.zip`（UI 效果图与概念包） | 2 |
| `scripts/` 中绑定历史基准提交的准入/SQLite 校验脚本 | 32 |

恢复：`git -C D:\CodexProjects\EasySaveVideo worktree add --detach <新目录> e7a4e01`，或 `git show e7a4e01:<路径>`。

## 影响

- 文档、清单中指向这些文件的路径在当前 `main` 上不再存在，需按下文从历史恢复后再使用。
- 冻结 code13 receiver 的还原输入（code12 APK 分卷）与 slice07/code12 源码重建分卷现在只在历史与外置归档中。
- 无构建脚本、源码或测试引用这些文件；构建与测试不受影响。

## 恢复方法

恢复到新的独立目录，不要覆盖正式工作树：

```powershell
$base = '35a62b553a996c8b72f496a1494310a0723784da'
$out  = 'D:\CodexProjects\EasySaveVideo test-workspace\restore'
git -C 'D:\CodexProjects\EasySaveVideo' worktree add --detach $out $base
```

或只取单个文件：`git show 35a62b5:<路径> > <目标文件>`（二进制文件请在 Git Bash 中执行，避免 PowerShell 改写字节）。
