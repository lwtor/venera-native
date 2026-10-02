# Venera 对照基线（S3-00）

审查日期：2026-10-02
工作区代码基线：`main` HEAD（开始审查时 `main...origin/main [ahead 10]`；用户现有的 `gradle.properties` 修改不属于本任务）
状态：**IN_PROGRESS**。逐项清单、上游版本和权重已冻结；上游源码逐文件审查尚待当前环境恢复 GitHub 访问后完成。视觉维度仍为未核验；截图采集是各页面实现后的视觉验收工作，不是本基线或后续实现任务的前置条件。

## 1. 基线和范围

| 项目 | 冻结值与证据 |
| --- | --- |
| 上游仓库 | [`venera-app/venera`](https://github.com/venera-app/venera) |
| 对照代码 | 归档仓库最终 `master@a0eba91`；固定引用：[commit 页面](https://github.com/venera-app/venera/commit/a0eba91)、[代码树](https://github.com/venera-app/venera/tree/a0eba91) |
| 最新正式版 | `v1.6.3`，短提交 `17a8cc1`，2026-03-08；[Release](https://github.com/venera-app/venera/releases/tag/v1.6.3) |
| 选择理由 | 比较对象取归档前最后代码状态，避免只以较早的 v1.6.3 tag 漏掉最终归档提交；Release 另存为发行参考。归档日期为 2026-04-05，见[官方仓库](https://github.com/venera-app/venera)。 |
| 官方产品范围 | README 列出本地漫画、JavaScript 漫画源、网络漫画、收藏、下载，以及来源支持时的评论/标签/其他信息与登录评分操作；见[README](https://github.com/venera-app/venera)。页面源码范围见[`lib/pages`](https://github.com/venera-app/venera/tree/a0eba91/lib/pages)。 |
| 上游截图证据 | F-Droid 的 Venera 包页展示 7 张手机截图：[截图 1](https://f-droid.org/repo/com.github.wgh136.venera/en-US/phoneScreenshots/1.png)、[2](https://f-droid.org/repo/com.github.wgh136.venera/en-US/phoneScreenshots/2.png)、[3](https://f-droid.org/repo/com.github.wgh136.venera/en-US/phoneScreenshots/3.png)、[4](https://f-droid.org/repo/com.github.wgh136.venera/en-US/phoneScreenshots/4.png)、[5](https://f-droid.org/repo/com.github.wgh136.venera/en-US/phoneScreenshots/5.png)、[6](https://f-droid.org/repo/com.github.wgh136.venera/en-US/phoneScreenshots/6.png)、[7](https://f-droid.org/repo/com.github.wgh136.venera/en-US/phoneScreenshots/7.png)；[包页](https://f-droid.org/en/packages/com.github.wgh136.venera/)列有 1.6.3 版本并链接原仓库。图片内容尚未逐张核验，分发页也未将单张截图绑定到 `a0eba91` 或具体 APK build，因此这些只作为待核验的上游参考，不能声称为精确提交截图。 |

`a0eba91` 是 GitHub 上可解析的短提交前缀；此基线以 GitHub 固定 commit URL 解析它，不声称已经从本地 Git 对象库核对完整 SHA。若未来 GitHub 对该前缀无法唯一解析，应先补录完整 SHA，不能静默改用 `master`。

### 比较方法

- 功能、流程、设计分别以 100 权重单位计分，不跨维度平均。以下适用项权重预先固定；后续仅可通过有理由的基线变更调整分母。
- `PASS` 仅在实现和适用验收证据都存在时计入得分；`PARTIAL`、`MISSING`、`UNVERIFIED` 都计 0。未核验项继续留在分母。
- 当前分数是证据覆盖度，不是主观估计的 UI 相似度。流程/功能采用 Stage 2 退出报告、自动化 fixture 验收及代码入口证据；视觉需要同页面/同状态的可比较截图，目前没有证据。实现前应先从固定上游源码提取布局层级、导航和状态行为并翻译为 Compose；截图用于实现后的真实渲染差异复核，不替代源码分析，也不作为开工门槛。
- 除明确平台差异外，原版能力一律适用。Android 原生权限/SAF picker 可替代 Flutter picker 的具体实现，但目录导入、权限恢复和失败处理用户目标仍计入。

## 2. 功能覆盖矩阵（当前：60 / 100）

| ID | 适用能力 | 权重 | 状态 | 当前实现 / 证据 / 差距 |
| --- | --- | ---: | --- | --- |
| F01 | 安装、启停、更新和移除 JS 来源 | 9 | PASS | `feature/sources`、`data/source`；Stage 1/2 来源 fixture 导航及阶段审查证据。来源设置/私有配置深度另列 F13。 |
| F02 | 按来源浏览探索内容 | 7 | PASS | `feature/explore`、`data/comic`；支持来源选择、分页及失败重试。 |
| F03 | 单来源搜索漫画并打开结果 | 7 | PASS | `feature/search`、`data/comic`；支持搜索、分页、空/错/重试状态。 |
| F04 | 分类、排行、筛选能力 | 7 | MISSING | 当前 route/UI 无分类和排行入口；来源探索筛选不等同完整分类/排行页面。S3-01。 |
| F05 | 跨来源聚合搜索 | 6 | MISSING | 目前只有选择单一来源的搜索流程；没有并发聚合及单源错误隔离 UI。S3-01。 |
| F06 | 漫画详情、信息展示和章节选择 | 8 | PASS | `feature/details`；已有章节和收藏入口。标签/评论/评分等额外能力不由此项覆盖。 |
| F07 | 网络漫画阅读及章节页面加载 | 9 | PASS | `feature/reader`、`data/comic`、`core/image`；阶段测试包含 fixture 阅读与 Reader 行为验收。 |
| F08 | 用户可访问的阅读历史、续读与进度恢复 | 7 | PARTIAL | `data/history` 持久化与进度恢复已有实现/测试，但 `HomeScreen` 的 `onOpenReader` 在 `MainActivity` 装配为空；当前无原版式最近阅读/历史页面入口。 |
| F09 | 本地收藏夹、分组、排序与管理 | 6 | PASS | `feature/library`、`data/collection`、Room；Stage 2 书架验收通过。 |
| F10 | 远端收藏/追更及来源同步 | 5 | MISSING | 当前收藏为本地书架；无远端收藏一致性和跟更交互。S3-05 / S4 后续对照。 |
| F11 | 下载队列控制、状态、离线读取 | 7 | PASS | `feature/library` 下载 tab、`data/download`；D07 实机通知/暂停/继续/移除通过，离线优先 provider 有测试。飞行模式整链仍未在设备实测。 |
| F12 | 本地目录/压缩包导入并阅读 | 7 | PASS | `data/local`、`feature/library`、统一 Reader；D07 API 26 SAF 目录导入和阅读通过，阶段报告注明未覆盖的压缩格式/权限边界。 |
| F13 | 来源登录、Cookie 导入和设置 | 5 | MISSING | 无账号登录/WebView/Cookie 设置产品流程；来源隔离基础设施不能替代用户功能。S3-02–04。 |
| F14 | 评论、评分、点赞及相关权限状态 | 5 | MISSING | 未发现对应 Route/feature/UI。S3-06。 |
| F15 | 全局/阅读偏好与来源配置设置页 | 4 | MISSING | 没有可导航的 Settings 页面；本项目技术默认值不计用户设置能力。S3-02、S4-01。 |
| F16 | 阅读器图片收藏等辅助入口 | 1 | UNVERIFIED | 上游具体辅助操作尚待源码/截图逐项核对；不据 README 总述推测通过。 |
| **合计** |  | **100** |  | PASS 权重 60；其余 40 保持分母并未计分。 |

## 3. 页面流程矩阵（当前：66 / 100）

| ID | 用户流程 | 权重 | 状态 | 证据与未闭合处 |
| --- | --- | ---: | --- | --- |
| L01 | 安装/启用来源 → 探索并选择来源 | 12 | PASS | 来源管理与探索现有入口；Stage 1/2 fixture 导航证据。 |
| L02 | 探索列表 → 漫画详情 → Reader | 16 | PASS | `Stage2FixtureNavigationTest` 覆盖探索、详情、阅读及返回来源；分页/筛选/分类/排行未由此项宣称通过。 |
| L03 | 单源搜索 → 结果 → 详情 → 选章阅读 | 16 | PASS | fixture UI 导航及 Stage 1 核心阅读验收；聚合搜索另列 L08。 |
| L04 | 离开 Reader → 历史页面/首页续读 → 恢复精确进度 | 10 | PARTIAL | `data/history` 和进度会话单测存在，但首页 Reader callback 为空且缺历史浏览/续读表面，完整用户流程不成立。 |
| L05 | 详情 → 下载 → 暂停/继续 → 移除/清理 | 12 | PASS | `Stage2DownloadControlTest` 真机验证暂停、继续、通知和移除清理；D07 退出审查。 |
| L06 | 下载完成 → 断网启动/打开 → 离线阅读 | 8 | PARTIAL | `OfflineFirstPageProvider` 代码与单测验证回退/离线来源；设备断网端到端未运行，不能宣称设备流程通过。 |
| L07 | SAF 授权目录或压缩包 → 漫画列表 → 章节 → Reader | 10 | PASS | API 26 实机目录授权、导入并打开 Reader 已验证；压缩格式和拒权场景受阶段报告列明的覆盖限制。 |
| L08 | 聚合搜索 → 单来源故障隔离 → 结果详情 | 6 | MISSING | 当前无聚合搜索入口/处理。S3-01。 |
| L09 | 来源登录 → Cookie 同步 → 评论/评分及失败恢复 | 5 | MISSING | 用户流程尚未实现。S3-02–06。 |
| L10 | 追更更新提示/历史刷新/阅读续接 | 5 | PARTIAL | 书架手动检查更新有入口；跟更 UI、更新状态完整交互、可访问历史流程均不足。 |
| **合计** |  | **100** |  | PASS 权重 66；PARTIAL/MISSING 保持分母并未计分。 |

## 4. 设计与视觉矩阵（当前：0 / 100 已核验证据）

以下都是适用项。`UNVERIFIED` 不表示确认视觉质量为零，只表示没有当前版/上游版成对截图可核查。当前代码可见：首页明确为 Android foundation 提示 + 五个居中按钮，与原版成熟主页存在明显信息架构差距；其余页面只对源码结构做过盘点，不能替代像素/布局证据。

| ID | 页面/组件视觉核验项 | 权重 | 状态 | 当前源码证据 / 需补证据 |
| --- | --- | ---: | --- | --- |
| V01 | 首页信息层级、最近阅读/更新/探索内容 | 18 | UNVERIFIED（已知大差距） | `feature/home/HomeScreen.kt` 是占位文案与按钮；需上游和 Android 同状态截图。 |
| V02 | 主导航、首页到来源/探索/书架的层级 | 10 | UNVERIFIED | `MainActivity.kt` / `AppRoute.kt`；没有可比较截图，当前首页以动作按钮导航。 |
| V03 | 来源列表、安装与来源操作菜单 | 8 | UNVERIFIED | `feature/sources/SourcesScreen.kt`；需列表、空态、菜单和安装流截图。 |
| V04 | 探索页、来源筛选、分组漫画卡片 | 9 | UNVERIFIED | `feature/explore/ExploreScreen.kt`；需首屏、滚动、空/错/载入截图。 |
| V05 | 搜索输入、筛选栏、结果密度与聚合状态 | 8 | UNVERIFIED | `feature/search/SearchScreen.kt`；上游聚合入口与本地单源模式不等价。 |
| V06 | 详情封面、信息分区、章节列表与操作层级 | 10 | UNVERIFIED | `feature/details/DetailsScreen.kt`；缺对照截图。 |
| V07 | Reader 页面、工具栏、阅读反馈及沉浸态 | 12 | UNVERIFIED | `feature/reader/ReaderScreen.kt`；Reader 有功能实现，不代表视觉对齐。 |
| V08 | 收藏、下载、本地书架及空/加载状态 | 9 | UNVERIFIED | `feature/library/LibraryScreen.kt`；当前简单 tab 文本与手工控件需截图比较。 |
| V09 | 分类、排行、聚合搜索、登录、设置等缺失页 | 10 | UNVERIFIED / 页面缺失 | `AppRoute.kt` 当前只有 Home/Sources/Library/Explore/Search/Details/Reader；页面不存在即视觉项不能通过。 |
| V10 | 字体、颜色、间距、图标、卡片和组件反馈 | 6 | UNVERIFIED | `core/designsystem/Theme.kt`；需按页面采集并对照，不从主题定义推断相似。 |
| **合计** |  | **100** |  | 已有成对截图得分 0；100 权重均保持未核验分母。 |

## 5. 页面/截图索引和后续证据要求

| 页面组 | 上游代码入口（固定 commit） | 本地实现入口 | 当前截图状态 |
| --- | --- | --- | --- |
| Home / 主导航 | [`home_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/home_page.dart)、[`main_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/main_page.dart) | `feature/home/HomeScreen.kt`、`app/MainActivity.kt`、`core/navigation/AppRoute.kt` | 7 个截图链接已列出，图片内容及页面映射待核对；本地 screenshot missing |
| Source / Explore | [`comic_source_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/comic_source_page.dart)、[`explore_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/explore_page.dart) | `feature/sources/SourcesScreen.kt`、`feature/explore/ExploreScreen.kt` | 截图内容/页面映射待核对；本地 screenshot missing |
| Search / categories / ranking | [`search_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/search_page.dart)、[`aggregated_search_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/aggregated_search_page.dart)、[`categories_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/categories_page.dart)、[`ranking_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/ranking_page.dart) | `feature/search/SearchScreen.kt`、`feature/explore/ExploreScreen.kt`；分类/排行无入口 | 截图内容/页面映射待核对；本地 screenshot missing / 页面差距 |
| Details / Reader | [`comic_details_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/comic_details_page.dart)、[`reader/`](https://github.com/venera-app/venera/tree/a0eba91/lib/pages/reader) | `feature/details/DetailsScreen.kt`、`feature/reader/ReaderScreen.kt` | 截图内容/页面映射待核对；本地 screenshot missing |
| Library / downloads / local | [`favorites/`](https://github.com/venera-app/venera/tree/a0eba91/lib/pages/favorites)、[`downloading_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/downloading_page.dart)、[`local_comics_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/local_comics_page.dart) | `feature/library/LibraryScreen.kt`、`data/download`、`data/local` | 截图内容/页面映射待核对；本地 screenshot missing |
| History / follow updates / settings / account | [`history_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/history_page.dart)、[`follow_updates_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/follow_updates_page.dart)、[`settings/`](https://github.com/venera-app/venera/tree/a0eba91/lib/pages/settings)、[`auth_page.dart`](https://github.com/venera-app/venera/blob/a0eba91/lib/pages/auth_page.dart) | `data/history`（无历史页）；Settings/Auth/Follow UI 无 route | 上游页面需追加图片/流程证据；本地页面缺失 |

截图应记录：上游基线 URL/版本、页面与状态、设备逻辑尺寸和系统栏、同类本地构建版本、捕获日期、匿名 fixture/内容来源。不要为比较引入真实用户 Cookie、商业源数据或受版权漫画内容。对应页面完成后再收集截图，存放在 `docs/reviews/assets/venera-baseline/` 或记录受控外部引用；若上游素材不允许入库，仅记来源 URL 与人工核对日期，不复制到仓库。无需为 S3-00 或 S3-01 等源码/功能工作等待截图。

## 6. 初步结论、许可证边界和行动项

- 当前已证据覆盖：功能 `60/100`、流程 `66/100`、视觉 `0/100 已核验`。这是保守的验收证据分数；尤其视觉值只代表暂无截图证据，不代表对当前视觉质量作出 0% 的判断。
- 已发现可直接列为差距：主页仍为技术占位页；分类/排行/聚合搜索缺失；历史和续读没有可访问 UI；远端收藏/追更、账号登录、评论/评分、完整设置页缺失。Stage 3/4 任务须映射到上述矩阵项，不能只新增底层接口后把对应项标 PASS。
- 已实现但缺闭环证据：下载完成后断网端到端阅读；阅读历史 UI 的精确续读；所有页面在真实设备上的成对视觉比较。
- 退出前行动：确认并固定归档提交完整 SHA；逐文件审查上游页面、导航、主题与状态源码并对照本地 Compose；把矩阵 ID 绑定到后续 Stage 3/4 每个功能任务。当前执行环境 `git ls-remote https://github.com/venera-app/venera.git a0eba91` 因 DNS 无法解析 `github.com` 失败，网页工具读取固定提交源码也返回 Cache miss；这是源码审查的真实外部限制，不应以截图替代源码读取。恢复上游源码访问后完成此项。截图的页面映射/版本关联核验及本地成对截图移入对应页面实现验收和 S4-09，不再阻断 S3-00/S3-01。
- 上游仓库标明 **GPL-3.0**；许可证文本：[LICENSE](https://github.com/venera-app/venera/blob/a0eba91/LICENSE)。本项目只把上游用作行为/界面对照，不复制 Dart/Kotlin 代码、翻译文本、图标、品牌素材、截图或漫画内容。复用/派生上游代码或视觉资产前，必须另行做 GPL 义务、商标与素材授权评估并记录来源；Venera Native 保持非官方关系，不暗示官方背书。

## 7. 本次验证

- 只读源码与计划审查；没有改产品代码、安装 App 或操作真机。
- 编译：不适用（文档任务）。
- 提交前要求：`git diff --check`。
