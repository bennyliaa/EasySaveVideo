# 独立v3对照与批量移动复核

固定源码bb83a530a0904953b92223c39feb77dab6298306、批量移动073d80d。2026-10-12只读源码与获准5556独立载体执行；不构建/改源码/读取ESV数据。

APK SHA175f768bb72ed777d1afb3f0a90ce09adf05a3608bb9594eb39902233c063825，官方签名核验同证书5e841a04968f3f790f6996ca5e286d8c2734338542486909f9a136717dde808f；Manifest权限仍仅本包签名级receiver权限，版本3/API31。保留v2数据install-r成功，无其他包/清数据。生产ESV仍code5未修；前次ESV ANR原因UNKNOWN独立未解决，本次未遇到需关闭的新ANR。

## 实际矩阵

- ORIGINAL：原六辅助页到根settled均不匹配（settings/settings/settings/sources/browser/editor留存），复现6/6；不是通过。
- FIXED：18/18 settled route_matches=true，18/18 MARKER_RESTORE返回原根且marker17仍在。包括原六链及Tasks经四辅助到Discover/Record/Library的12链。
- 同根重复保持discover；fixed Back对照回discover。
- PREPARE_FIXED_RECREATE_AUX：Library17下Settings，instance1→2 recreate仍Settings/Library17/FIXED；横向旋转instance3、恢复纵向instance4仍Settings/Library17/FIXED；真实可见BOTTOM_LIBRARY返回Library17。
- Library根recreate instance5、横向/纵向旋转instance6/7均Library17；随后真实OPEN_SETTINGS，系统KEYCODE_BACK产生destination callback回Library17。
- 显示设置恢复accelerometer_rotation1/user_rotation0/font1.0，无字体/尺寸更改。
- 额外根旋转后第一次定位OPEN_SETTINGS失败，脚本空值处理不足导致一条无有效控件定位输入，日志记录为多余BOTTOM_LIBRARY同根动作；没有业务副作用。之后一次有界滚动、新鲜UI树严格非零bounds定位后完成系统Back。该脚本失败不计功能FAIL或PASS，原日志保留。

完整固定日志matrix-complete.jsonl（carrier_version3）；不含媒体/URL/私人信息。

## 代码结论

RootNavigation先有界弹出已知辅助页，再仅保存根栈；恢复后清除旧版本的辅助tail，保留实际根状态。同route NoOp与生产离页门保留。未发现明确新P1/P2。真实载体支持策略修复，但生产初始route、无动画、chrome和业务owner不同；旧版本保存tail迁移、冷启动直接辅助图和生产真实点击还须最新生产包验证。17回归仅限定模型/协调器，不替代生产装机。

## 批量移动073d80d

未发现明确新P1/P2。UI接startMoveBatch，准确当前foreground/ready/nonbusy快照准入；worker每项重新校验READY、完整ref，仓库CAS revision及独立operation ID。不是原子批量：逐项结果区分MOVED/ALREADY/STALE/UNAVAILABLE/FAILED_UNCONFIRMED/CANCELLED_BEFORE_START。写入后异常不假称未改变；取消只阻止后续项，已接受项不回滚。busy阻止重复确认，后台/dispose请求取消并防止销毁后UI回写。

52限定测试证据为fake repository/model，不能证明Android目录事务/同名冲突/外部权限或真实取消时间；还需新生产包、获准测试ID/文件夹的实际逐项部分成功与失败回归。批量导出/分享/删除不由此提交实现。源码未新增文件读取权限。导航生产ANR与批量移动不合并为同一问题。
