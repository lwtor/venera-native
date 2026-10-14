# 漫画列表样式检查（S4-10B2）

日期：2026-10-14。基线：52b2d25。范围：现有漫画展示入口统一，不是 Stage 退出审查。

| 位置 | 统一后组件 | 保留的内容/操作 |
| --- | --- | --- |
| 收藏夹 FavoriteGrid | ComicCoverCard | 三列、打开、长按、更新标记、来源角标、来源更新日期 |
| 首页收藏更新 | ComicCoverCard | 打开、更新标记与同样两行信息 |
| 聚合搜索预览 | ComicCoverCard | 来源名、打开、来源已有更新日期 |
| 普通完整搜索/作者/标签结果 | ComicHorizontalCard | 标签、完整作者、日期、状态角标、可见行补读与分页 |
| 探索全部漫画分区 | ComicHorizontalCard | 来源、封面、名称、作者及来源日期 |
| 首页最近阅读 | ComicHorizontalCard | 章节、进度、页码、继续阅读 |
| 本地漫画条目 | ComicHorizontalCard | 标题占位、章节展开与移除菜单 |
| 下载漫画/版本分组 | ComicHorizontalCard | 标题占位、下载统计、进度及多选 |

`rg ComicImage/ComicImageWithContent` 检查后 Feature 内仅剩详情主封面、内容预览图、评论头像与个人头像；这些不是漫画列表条目。功能入口、空状态、章节行和下载任务行不替换为漫画卡片。详情模型已有 recommendations 但当前未展示，未来接入时复用公共漫画组件。

本地 coverPath 可能是归档内部路径，现有漫画图片管线不直接读取它；下载分组也没有封面字段，本次不新增网络请求/归档提取，仅使用共享占位。旧收藏日期经手动检查更新或重新收藏补齐，源未提供则显示未知；不后台批量请求。

验证：`:app:assembleDebug :core:image:compileDebugUnitTestKotlin` 编译通过；B1 的收藏/备份/迁移测试源码编译通过。差异检查通过。未运行测试、安装或真机验证；深浅主题、主色视觉、长来源名、大字体、长按/菜单点击待用户复验。
