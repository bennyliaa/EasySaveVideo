# 批量 Files 的具体选择点（不改变 code6）

基线：e26f6c0b71f60f0e33d196713cfda7128de7f268；已签名流程仍使用固定
afca2ab 构建 / d112c13 交付。本说明不新增功能、依赖、权限或包。

## 原文与现有实现

- 原 UI PPT slide18：“选择后出现移动、分享、更多操作，结束选择后恢复普通导航。”
  明确批量选择后的移动/分享，没有指定批量 Files 选址或目录权限模型。
- 原 Product Requirements 9.1 / PR17：“按需导出相册、Files 和系统分享；禁止静默发布或上传”；
  “外部文件夹：用户通过系统选择授权；授权失效时保留内部产物并提示重新选择”。
- UI interface contract section10：导出是独立 Command/Task，必须明确目标与普通副本提示；
  失败不删除库内 MediaId；撤销分享不承诺收回接收方已经复制的内容。
  系统分享的只读临时 URI 与 Files 的外部文档副本是两个不同目标。

当前单项 Files 的入口是 AndroidExportRuntime.createDocument：
1. 冻结一个 MediaReadRef，新建独立 operation/grant ID，持久化 PREPARED、预留容量。
2. 在打开系统界面前保存 CREATE_INTENT，承认提供方可能已经创建空文档。
3. 前台打开 ACTION_CREATE_DOCUMENT + CATEGORY_OPENABLE，video/mp4，建议名
   EasySaveVideo.mp4，申请该文档的读/写临时授权；不是目录树或持久化目录授权。
4. 返回结果必须关联当前 pendingSaf 和 picker 状态；确认 URI/flags 后才交给既有
   ExportCoordinator。源引用/读租约、逐项写入、实际资源释放、完整回读由现有端口负责。
5. 完整核对才 SUCCEEDED；回读不支持时 COPIED_UNVERIFIED；失败/撤销/资源释放不明
   保持独立状态。取消或回调丢失不证明外部空文件不存在。丢失的授权不从历史 URI 重建。

另有 ACTION_OPEN_DOCUMENT_TREE 的保存目的地入口，但它属于普通产物的创建路由。
AndroidOrdinaryDestinationJournal.admit 要求创建前准入（requireNotCreated），
folderGrantForOperation 从不可变 creation admission 解析。已存库媒体不能借用当前
可变“默认目录”，也不能伪造新的 creation/READY 来套用这个端口。

## 可行选择

| 方式 | 用户体验 | 必须补充的业务边界 |
|---|---|---|
| 串行逐项另存 | 每项一次系统新建文档界面；位置可不同 | 一个批次冻结源引用；每项仍独立 operation；处理完当前 picker/副本后才继续下一项；取消后不自动继续；不是同时打开多个 picker 或 UI 循环假装原子 |
| 一次选目录逐项复制 | 一次选择目录，所有新文档在该目录 | 为“已存库批量导出”增加不可变目录 grant 准入/绑定；每项创建、回读、授权失效和清理独立；不修改全局默认目的地、不覆盖既有文档、不重建旧授权 |
| 暂保留单项 Files | code6 批量分享/相册复制可先验收 | 已完整实现的路径继续推进；批量 Files 明确尚未实现，不以禁用按钮或假成功代替业务 |

**最小实现推荐：先串行复用逐项另存。** 现有单项端口/权限边界可继续使用，新增面主要
是批次与 picker 的关联状态机；其代价是选择界面较多。若产品要求一次选择目录，则应选
第二行并明确承认需要新的导出 grant 绑定，而不是声称已有保存目录路由可以直接复用。

只需回答一个产品问题：**批量 Files 每项分别选位置，还是一次选择一个目录？**
不需要再次决定保留原件、禁止覆盖、逐项失败/取消/不明结果语义，这些已由原规格固定。
该问题仅决定下一次 Files 实现，不阻塞当前分享/相册复制、兼容签名或固定 UI 验收。
