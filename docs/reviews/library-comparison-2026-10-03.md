# 书架页面首轮对照记录（2026-10-03）

## 基线与范围

- 对照基线：[`venera-app/venera` 归档仓库 `master@a0eba91`](https://github.com/venera-app/venera/tree/a0eba91)，版本/适用范围与限制见 [Venera 全局对照基线](venera-comparison-baseline-2026-10-02.md)。
- 上游入口：[`lib/pages/favorites/`](https://github.com/venera-app/venera/tree/a0eba91/lib/pages/favorites)、[`lib/pages/downloading_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/downloading_page.dart)、[`lib/pages/local_comics_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/local_comics_page.dart)。
- 本地入口：`feature/library/LibraryScreen.kt`、`LibraryUiState.kt`、`LibraryViewModel.kt`、`component/FavoriteGrid.kt`。
- 本次范围：整理现有收藏、下载、本地书架的导航与页面信息层级，补足既有收藏能力的可见入口和本地检索；不扩展来源 API 或伪造远端收藏。

执行环境无法稳定读取 GitHub 固定提交的完整 Dart 源码（S3-00B2 已记录访问阻塞）。所以本记录将能由本地基线、现有模型/仓库能力和官方发行说明确认的内容作为首轮页面对照，不把无法逐行核验的细节写成已完成。Venera 官方发行说明曾记录收藏页优化、收藏检索忽略大小写及按已读状态筛选等变化：[Venera Releases](https://github.com/venera-app/venera/releases)。

## 对照与改动

| 领域 | 修改前 | 本次实现 | 剩余差距 |
| --- | --- | --- | --- |
| 页面导航 | 根导航已有“书架”入口，页面又显示重复返回栏；收藏、下载、本地入口为普通文字操作；内外 Scaffold 曾重复应用顶部系统 inset | 移除重复返回栏，改成带数量的收藏/下载/本地标签页；内层 Scaffold / TopAppBar 使用零 inset，沿用根 Scaffold 已提供的系统栏安全区 | 顶部留白修正需用户设备复验 |
| 收藏检索 | 无本地收藏过滤入口 | 增加搜索图标和按标题/副标题忽略大小写的即时过滤、无匹配空态 | 与上游源码中的完整过滤行为仍需恢复源码后逐项确认 |
| 文件夹与排序 | 管理动作占据多个按钮行，排序控件重复露出 | 文件夹新建/改名/删除放入菜单；排序放入紧凑菜单；保留文件夹横向切换。用户反馈后把目录移动入口放入漫画长按后从底部弹出的操作面板，直接列出其他目标目录 | 尚无拖动排序；没有批量移动/删除 |
| 收藏列表 | 初次修改后以多列封面卡片呈现，但移动目录入口不够符合用户预期 | 按用户参考恢复三列封面网格；长按作品后从底部弹出操作面板，提供移至其他目录、标记更新已读和从书架移除 | 尚无多选及批量操作 |
| 本地书架 | 作品及所有章节平铺，章节列表持续占空间 | 本地漫画卡片按作品折叠章节，点开后再选章节；加入空态和移除入口 | 设备上验证章节展开、阅读跳转和长列表滚动 |
| 下载列表 | 任务以单行信息呈现，状态与动作不够清楚 | 每个任务卡展示作品/章节、状态、进度，以及暂停/继续/重试/移除操作 | 未增加并发策略、筛选或批量管理；仅重整呈现既有动作 |
| 阅读状态 / 追更 | 书架模型没有足以呈现所有上游阅读状态和远端追更的完整信息 | 不伪造未存在的数据，保留当前收藏和更新标记能力 | 已读筛选、远端收藏/追更仍需上游行为及类型化来源能力审查 |

## 验证与结论

- JDK 17：`sh gradlew :feature:library:compileDebugKotlin :feature:library:compileDebugUnitTestKotlin :app:assembleDebug` — **BUILD SUCCESSFUL**。
- 测试源码编译通过；按普通开发节点策略未执行单元测试。
- `git diff --check` — **PASS**。
- 未安装或操作真机；用户负责视觉和交互验收。Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 2026-10-04 用户反馈后修正双层系统 inset，并按截图恢复三列封面网格；长按作品后由 ViewModel 状态驱动底部操作面板，支持移至其他目录、标记更新已读和移除。修订后 JDK 17 `sh gradlew :feature:library:compileDebugKotlin :feature:library:compileDebugUnitTestKotlin :app:assembleDebug` — **BUILD SUCCESSFUL**；`git diff --check` — **PASS**。
- 结论：首轮页面结构与现有本地能力呈现已完成，但这不是全书架功能等价或达到 90% 的结论。批量管理、阅读完成筛选、拖动排序和远端收藏/追更仍列为差距；S4-09 总审计保持 TODO。

## 2026-10-04：最近阅读排序修复

用户实测发现读过新漫画后“最近阅读”顺序不变。根因是书架排序只使用 `favorite_entry.last_read_at`，而 Reader 将新的阅读位置写在 `reading_history.updated_at`，从未同步收藏快照。`FavoriteDao.observeByLastRead` 现对同源同作品的阅读历史取 `MAX(updated_at)` 排序；无阅读历史时退回已有 `last_read_at`，并按 `added_at` 稳定排序。Room 查询直接引用阅读历史表，使其写入可触发已订阅 Flow 重新查询。新增 `FavoriteDaoTest.aNewReadingHistoryEntryMovesItsFavoriteToTheTopOfLastReadOrder` 验证旧时间在后、加入较新历史后升至第一位。

验证：JDK 17 `sh gradlew :core:database:compileDebugAndroidTestKotlin :data:collection:compileDebugKotlin :data:collection:compileDebugUnitTestKotlin :app:assembleDebug` — **BUILD SUCCESSFUL**；`git diff --check` — **PASS**。回归测试源码已编译但未执行，设备实测留给用户。
