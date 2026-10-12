# 一次选目录的 Files 批次（本地源码，code6 不变）

用户已明确选择“一次选择目标文件夹”。此前逐项另存建议是历史方案；当前交互为一次
ACTION_OPEN_DOCUMENT_TREE，返回前台后确认冻结的所选项，再逐项复制。使用现有目录
picker 的 durable request-code / 非恢复回调桥，不增加 flavor、依赖、默认目录修改或支付规则。

## 准入与权限

- LibraryBatchSelection 冻结 READY ID、revision、representation generation 和当前查询。
  返回目录后仍须前台/准确快照；活动重建、取消、迟到/重复结果不能恢复旧 consent。
- picker 的预期暂停不启动复制；返回后点击确认才保留实际返回的 read/write/persistable
  tree 权限。所有来源/授权/SQLite/文档 IO 在 worker/control，沿用已有 picker 的主线程
  durable request-code 消费，不宣称主线程完全无 IO 或真实耗时已验收。
- AndroidFolderGrantRegistry 验证实际根 tree、目录可创建、返回 flags 与 Android 当前权限，
  仅持久化已返回的读/写能力。它不选择或修改 App 默认保存目录。
- 新 library_directory_batch/item 为已存库导出绑定独立不可变 folder grant、源引用、序号、
  operation/child grant ID。没有伪造 pre-creation admission、creation ID 或 READY。
  一个批次最多100项；全局既有 ExportLimits 活跃容量/worker 队列保持原限制。
- 同 URI 已观察到权限丢失时旧 registry generation 永久失效；重新授权不能复活旧批次。
  Android 不提供不可变 grant-generation token，未观察的外部 revoke/regrant ABA 不冒称已排除。
  取消不自动 releasePersistableUriPermission，避免撤销其他已有 owner 的合法能力。

## 文档与逐项结果

- 每项使用 ESV-随机nonce.mp4 新建文档，不保留或覆盖任意 caller path。先持久化 CREATE_INTENT，
  然后验证返回 child 确属该 frozen tree；准确 URI 必须绑定本 operation 后才能写/清理。
- 新批次创建前仅读取受选 tree 的 document ID 元数据，最多1000子项；不枚举名称/读取文件
  内容，不向诊断/保存 UI 泄漏 IDs。已知既有 child ID、已被别的 operation 绑定的 URI 被拒绝，
  不获得 writer 或 cleanup 所有权。元数据不完整/超预算则失败，不换默认目录或猜名字重试。
- 标准 createDocument 契约及随机新名称不等于对恶意提供方底层行为的全能保证。返回不明、
  bind 失败等 create-to-record gap 可能留下无法安全关联的空文档；不自动删除未知文件。
- SUCCEEDED 才是完整核对副本；COPIED_UNVERIFIED、STALE、UNAVAILABLE、目标不可用、
  FAILED_UNCONFIRMED、CLEANUP_PENDING、CANCELLED 和取消后未开始分别显示，原件不删除。
  写入后异常不能解释为 rollback；已发布副本不因取消收回。不承诺整批原子性。
- 中途授权丢失停止剩余工作；读写实际退出/释放未确认时不提前清理。只清理本 operation
  确认拥有的输出，不删除既有用户文档。同名冲突不覆盖、不无界重试、无成功子集冒称全批成功。

## 中断、迁移与自查

- 单项 started fence、结果与批次状态持久化，不复用已开始/已完成 operation。启动或显式
  recovery 在普通 ExportCoordinator 前停止旧批次并取消它所有 active export rows，包括
  “尝试结束但 export 仍 PREPARED/不明”的窗口。未开始条目不自动继续/新建；重新主动确认
  新批次是新副本，不是旧 operation 的恢复重建。
- 数据库 v7→v8 仅加表/索引/约束；v1..v6 的既有迁移仍按原条件执行，v7 不重复旧 folder DDL。
  SQLiteOpenHelper 原事务包括 DDL/version，一处失败整体 rollback；无 DROP/清库/数据重造。
- SQL 约束防止更换目标/源/序号/operation、倒退 started/outcome/STOPPED 或删除 replay 历史。
  parent FK 指向实际 registry；已有退役目标只支持先前已准入工作，不供新准入。
- 自查修正：历史 PREPARED 不得由 recovery 自动创建目录文档；新 admission 清空旧 operation
  correlation，目录确认前取消不能作用于上一导出；返回既有文档不得进入 bind/write/cleanup。

JVM/model/fake、Desktop SQLite :memory: 结果在 artifacts/ui/directory-batch-20261012；
不是 Android SQLite、真实目录授权/提供方/耗电或 OPPO 媒体链通过。未访问真实目录、操作设备、
推送或打包；源 app versionCode 仍6，但新实现只供后续审查后的更高版本包。固定 code6 仍使用
afca2ab/d112c13 原签名输入。后续媒体测试应复用既有 Debug 权益面板，Release 必须排除 fake，
不新增 flavor/收费政策或伪购买/数据库。OPPO 媒体闭环由独立 reviewer 执行，当前仅进行中。
