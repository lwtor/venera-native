# 收藏漫画资料与资源整理审查

日期：2026-10-15。任务 S4-10C1/C2/C3；基线 `0e5d7df`；C1 `0db7b8d`，C2 `fe91a8e`，C3 随本审查提交。本记录是用户任务的实现审查，不作为 Stage 退出证明。

## 验收矩阵

| 验收项 | 代码与回归来源 | 证据/限制 |
| --- | --- | --- |
| 独立资料与分版本目录，旧收藏/阅读/下载保留 | ComicRecordEntity、MIGRATION_11_12、数据库迁移回归 | App 与 AndroidTest 源码编译，未执行设备迁移 |
| 收藏/首页优先持久封面，失效占位可重载 | ComicResourceStore、CoilComicImagePipeline、retainedCoverRevision、FavoriteGrid/HomeScreen | 合成持久/替换/关闭网络缓存回归源码编译，未执行真实图片验证 |
| 每漫画统一资源目录，原章节不搬移 | covers/cover.image 与原 DownloadFileLayout；DownloadFileLayoutTest | 回归源码检查同目录且不混入章节/manifest 扫描，仅编译 |
| 12h过期，二并发、同漫画互斥、15分钟退避 | DefaultCollectionRepository、CollectionRecordStore、RetainedComicRecordsTest | 成功/失败/空数据保留、失效URL、强制刷新、前台循环取消回归源码编译 |
| 收藏/详情更新、取消收藏保留资源 | addToFolders/updateDetails/remove；分版本/删除回归 | 代码审查和回归源码编译，未安装 |
| 旧包兼容，新资料备份，不含本机路径/封面二进制 | BackupArchiveCodec、RoomBackupRepository、新旧加密包 fixture | 编码回归及数据模块测试源码编译；未执行真实账号备份恢复 |
| 模块边界与敏感资料 | App仅装配/生命周期；纯ComicCoverStorage契约；URL摘要索引 | 无Feature直接网络/文件调用，无新增业务依赖边；未存认证头 |

## 发现与处理

1. 旧收藏只有URL及可回收图片缓存，没有保留资源兜底：增加独立资料及持久封面读取。
2. 下载恢复扫描 *.bin：封面使用独立 cover.image，增加排除回归源码。
3. 保存成功但同URL的失败Painter可能不重载：请求包含封面取得时间，新增缓存键回归源码。
4. 目录已知但封面URL失效：不能只因目录已有而跳过，统一补齐入口允许刷新资料，失败退避。
5. 并发资料更新可能覆盖封面时间：事务中重新读资料再写，封面时间单列更新。

## 实际验证

JDK 17：`:app:assembleDebug :core:image:compileDebugUnitTestKotlin :data:collection:compileDebugUnitTestKotlin :data:download:compileDebugUnitTestKotlin :data:backup:compileDebugUnitTestKotlin :core:backup:compileDebugUnitTestKotlin :feature:details:compileDebugUnitTestKotlin :feature:home:compileDebugUnitTestKotlin :feature:library:compileDebugUnitTestKotlin :core:database:compileDebugAndroidTestKotlin` — BUILD SUCCESSFUL。`git diff --check` — PASS。仅编译，未运行单元/设备测试、Lint、安装或真机操作。

## 剩余风险与后续

- 旧收藏尚无持久文件，需要来源至少一次成功返回封面；服务/认证故障、移除来源时保持旧值，不能保证首次获取。
- 真实版本升级迁移、网络中断、重启后封面及视觉验收待用户设备复验；不以编译代替闭环通过。
- 资源占用私有存储，取消收藏不会删除；显式存储管理/清理与可配置刷新周期另行规划。
- 前台维护不等于后台定时同步；GitHub手动备份不上传漫画资源文件。
- 阅读历史跨版本重复章节ID的独立进度不属于本任务，未调整现有主键。

下一唯一执行任务恢复 S4-02G4，项目 Stage 状态保持原有结论。
