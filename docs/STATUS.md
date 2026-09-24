# 项目执行状态

> 本文件是项目当前进度的唯一事实来源。新会话必须先读根目录 `AGENTS.md`，再读本文件。每次完成任务后必须同步更新。

## 状态快照

| 项目 | 当前值 |
| --- | --- |
| 最后更新 | 2026-09-24 |
| 当前阶段 | Stage 2：增量能力 |
| 当前任务 | S2-06 CBZ/ZIP 与 7z 支持 |
| 当前任务状态 | Stage 0 / Stage 1 DONE；S2-01–S2-05 DONE；当前唯一下一任务 S2-06 |
| 默认分支 | `main` |
| 远程仓库 | `https://github.com/lwtor/venera-native` |
| 当前代码基线 | `main`（以 Git HEAD 为准） |
| 工作基线 | AGP 9.2.1、Gradle 9.4.1、JDK 17、SDK 37、minSdk 26、KSP 2.3.10、Coil 3.4.0、Room 2.8.5 |

## 质量整改（2026-09-22）

审查基线 `cbfb8ea`，发现及切片见 `docs/reviews/stage-01-remediation.md`。下方历史 DONE 是当时记录，不覆盖本次重新打开的验收。

- Q00：新增 Stage 必须审查、修复、复验后退出的规则；完善逐任务提交、暂存检查与用户修改保护。验证：纯文档，`git diff --check`。

- Q01：修复正文有界读取与回调异常映射；新增短/空/边界、chunked 超限和读取异常回归。 验证：`:source:network:testDebugUnitTest :app:assembleDebug` — PASS。

- Q02：修复缓存提交后的文件租约、网络取消与实际字节上限、表单编码、鉴权快照及共享 DiskCache；新增首次下载和缓存命中/请求语义回归。 验证：`:core:image:testDebugUnitTest :app:assembleDebug` — PASS。

- Q03：保留稳定页索引，按邻近页面解析尺寸并支持失败重试；解码器通过带租约的认证缓存文件读取正文 验证：`:core:image:testDebugUnitTest :data:comic:testDebugUnitTest :feature:reader:testDebugUnitTest :feature:reader:compileDebugAndroidTestKotlin :app:assembleDebug` — PASS。

- Q04：以不可变脚本和原子索引替换保证来源升级一致性，失败保留旧包，回滚保留禁用状态 验证：`:data:source:testDebugUnitTest :app:assembleDebug` — PASS。

- Q05：保留根依赖跨配置变化，销毁时关闭资源；冷启动串行恢复启用来源，禁用卸载清理会话，升级能力不再使用陈旧缓存 验证：`:data:source:testDebugUnitTest :source:core:testDebugUnitTest :data:comic:testDebugUnitTest :app:assembleDebug` — PASS。

- Q06：mixed 探索以零基游标解析连续分页，回归覆盖 0、1、2 与末页停止 验证：`:source:core:testDebugUnitTest :app:assembleDebug` — PASS。

- Q07：进度按漫画合并并串行写入，失败保留可重试，尾部定时落盘；生产历史仓库通过 Room 事务同步历史和恢复位置 验证：`:data:history:testDebugUnitTest :app:assembleDebug` — PASS。

- Q08：等待异步恢复后创建阅读会话，只恢复匹配章节；首屏和翻页使用真实漫画、章节与封面元数据记录，离开阅读器立即强刷。验证：`:data:history:testDebugUnitTest :feature:reader:testDebugUnitTest :feature:reader:compileDebugAndroidTestKotlin :app:assembleDebug` — PASS。

- Q09：未缩放时保留连续滚动与分页滑动，缩放后才接管平移；连续模式真实应用偏移并按内容尺寸限位。验证：`:feature:reader:testDebugUnitTest :feature:reader:compileDebugAndroidTestKotlin :app:assembleDebug` — PASS。

- Q10：日志原文脱敏，init 网络请求具备调用 ID，安装/重建/元数据探测受截止时间保护，同源忙时立即返回可重试错误。QuickJS 1.0.5 的 CPU 死循环仍只能让调用方超时并丢弃引擎，不能声称脚本线程已被终止。验证：`:source:engine:testDebugUnitTest :app:assembleDebug` — PASS。

- Q11：同一份 demo fixture 通过 Source Core 完整能力回归并用于人工闭环；支持 SAF 文件选择安装，正文使用生成图片的受控本地 HTTP 服务。人工设备闭环仍按 Q13 明确记录是否实际执行。验证：`:source:core:testDebugUnitTest :data:source:testDebugUnitTest :feature:sources:testDebugUnitTest :feature:sources:compileDebugAndroidTestKotlin :app:assembleDebug` — PASS。

- Q12：Release 构建和 Lint Vital 通过；Debug APK 19 MiB，未签名 Release APK 4.9 MiB，QuickJS 覆盖 arm64-v8a、armeabi-v7a、x86、x86_64。新增第三方声明；完整依赖许可报告、项目许可证、签名发布仍属于 Stage 4 发布门禁。脚本二进制 Host 通道归 Stage 3；设备手势、进程恢复/API 26 与真机取消仍明确未验证。

- Q13：修复阶段门禁发现的 `AppGraph` Context 静态持有、Compose Modifier 参数顺序和预览硬编码存储路径；执行全量 Debug Lint、全部 JVM 单测及 Debug/Release 构建。274 项测试通过，零失败/错误/跳过；Stage 1 质量复验结论为通过。完整结论见 `docs/reviews/stage-01-final.md`。

### 当前事实覆盖说明

下方按任务时间记录的历史段落保留审计价值，但其中“尚未引入 Room/Coil”“fixture 阅读器”以及旧测试
数量等描述已过期。当前实现以本节、`docs/ARCHITECTURE.md` 和质量整改台账为准：Room、Coil、QuickJS、
来源正文阅读与恢复均已接入；WebView Runtime 仅为待删除兼容实现。设备手势、进程回收/API 26、完整
人工闭环没有在本轮执行，均不得记为通过。

## 最近完成：S2-02 下载领域与持久队列 — DONE

依赖：S2-01（Room v2 与 `ComicRef` 已落地）。

实际交付：

- `:core:database` 升到 **version 3**，只加一个 `MIGRATION_2_3`：`download_task`（章节一行，含
  `worker_id` / `heartbeat_at` 用于崩溃后认领）与 `download_page`（每页一行，外键级联删除）。
  `core/database/schemas/.../3.json` 已入库，迁移测试断言 v2→v3 后收藏行仍在。
- 新建 `:data:download`：`DownloadRepository` 契约 + `DefaultDownloadRepository`、`DownloadStateMachine`、
  `DownloadQueue`、`PageDownloader`、`DownloadFileLayout`、`DownloadRecovery`、`ChapterManifest`、
  `DownloadPlanner`、`DownloadMappers`、`DownloadEnvironment`。
- **并发是真的被限制住，不是约定**：`DownloadQueue` 用两个信号量——全局 4、单源 2。单源限制存在的理由
  是来源会限流甚至封禁，而一个章节属于一个来源；没有它，全局 4 会被最先入队的那个章节吃满。
- **页写入是原子的**：`.part` 写 + `fsync` + `rename`，只有改名后调用方才看得到文件；崩溃留下的是
  「没有这个页」而不是「半个页」。写完立刻用 `:core:image` 的 `ImageSizeHeaderParser` 校验头部——防盗链
  会返回 200 加一段 HTML，不校验的话队列会塞满解码出空白的假页。校验失败删文件并计 `Failed`。
- **章节状态由页状态派生**，不存在独立的「章节已完成」字段，因此两者不可能互相矛盾。派生顺序里
  `Failed` 排在 `Queued` 之前：一页失败比「还有页在排队」更值得告诉用户。
- 恢复扫描处理三种「谎」：心跳过期的 `Running` 页回到 `Queued`；`Succeeded` 但文件缺失/大小不符的页
  回到 `Queued`；数据库行丢失但 `chapter.json` 还在时按清单重建（磁盘上已有文件的页直接记为完成，
  不重复下载）。无主文件只报告不删除——它可能是用户还想要的章节。
- `chapter.json` 在入队时原子写入，是数据库丢失后重建的唯一权威。

验收对照：

| 验收项 | 结论 | 依据 |
| --- | --- | --- |
| 页级任务与幂等入队 | 通过 | `DefaultDownloadRepositoryTest`：重复入队不产生重复页行，且已完成的进度不被重置 |
| 状态机与终态 | 通过 | `DownloadStateMachineTest`：`Succeeded` 不可回退、`Canceled` 不可复活、非法迁移抛错而非静默应用 |
| 暂停 / 继续 / 取消 | 通过 | `DefaultDownloadRepositoryTest`：暂停只影响未开始的页（在途页允许落地）、取消后行与文件都不剩 |
| 全局 4 / 单源 2 | 通过 | `DownloadQueueTest`：实测峰值并发；慢来源不会把另一来源的页堵在后面 |
| 原子写 + 完整性校验 | 通过 | `DownloadFileLayoutTest`（无 `.part` 残留、写失败不留文件）+ `PageDownloaderTest`（非图片字节判 `Corrupt` 且文件被删） |
| 恢复扫描 | 通过 | `DownloadRecoveryTest`：僵尸页重置、文件缺失/截断重置、清单收养、孤儿只报告不删除 |
| v3 迁移与基线 | 通过 | `3.json` 入库；`VeneraDatabaseMigrationTest` 新增 v2→v3、v1→v3 两个数据保留用例（编译级） |

S2-02 完成时的已知缺口（截至 2026-09-23；S2-03 已在下方完成）：

- 当时还没有后台执行者；现由 S2-03 提供 Worker、通知与约束。
- **没有 UI**：没有下载列表页，也没有从详情页触发下载的入口。S2-03 提供的唯一工作调度入口已就绪，用户操作与整条产品闭环纳入 S2-07 集成验收。
- 心跳过期阈值定为 5 分钟（`HEARTBEAT_STALE_AFTER_MILLIS`），未经真机验证；Worker 多久打一次心跳
  已由 S2-03 定为每次认领批次前更新；仍需 S2-07 在慢网络和长章节上实测阈值是否合适。
- instrumentation 测试（迁移）只编译未执行；未跑 Lint、未装 APK。

验证记录：

```text
2026-09-23（编译级 + 相关模块 JVM 单测，按 AGENTS.md 第 7 节普通节点策略）
JAVA_HOME=/Users/lwtor/Library/Java/JavaVirtualMachines/corretto-17.0.9/Contents/Home
ANDROID_HOME=/Users/lwtor/Library/Android/sdk
sh gradlew :data:download:testDebugUnitTest :core:model:testDebugUnitTest
           :core:database:compileDebugAndroidTestKotlin :app:assembleDebug
结果：BUILD SUCCESSFUL
```

## 最近完成：S2-05 SAF 本地目录导入 — DONE

新增 `:data:local` 与 Room v4：持久化 SAF tree 授权、本地漫画/章节/页索引。系统目录选择器接入书架 Local tab；可导入、查看和移除目录。扫描支持图片直接位于根目录的一章布局及子目录分章布局；自然排序处理数字段、前导零和大小写，根 `cover.*` 优先且不作为正文页，否则取首张正文图。重复导入以授权 URI 生成稳定漫画 ID 并替换索引；删除最后一个持有者后释放系统授权。

验证：`:data:local:testDebugUnitTest :app:assembleDebug` — PASS（扫描、自然排序、封面解析 6 项通过，Debug APK 构建通过）；`:core:database:compileDebugKotlin` — PASS，schema v4 已生成。迁移 instrumentation 已新增 v3→v4 schema 和下载行保留用例，尚未在设备上运行。此任务只完成目录导入；本地页阅读闭环列入 S2-07。

## 最近完成：S2-04 离线阅读整合 — DONE

`:data:download` 新增 `OfflineFirstPageProvider` 并由 `AppGraph` 装配为阅读器的 `PageProvider`。章节完整且文件仍完整时，阅读器直接使用下载目录中的绝对文件路径，图片描述不携带来源 ID；本地图片尺寸从文件头解析，并继续经现有 Region/Sampled 解码器解码。缺少完整下载、页状态不完整、文件不可用时会回退到来源提供器。阅读身份仍是原 `ChapterKey`，因此现有阅读进度仓库继续复用。

回归测试覆盖完整下载离线读取（包括无来源 ID 与真实图片尺寸解析）和不完整下载回退。验证：`sh gradlew :data:download:testDebugUnitTest :app:assembleDebug` — PASS（下载模块 JVM 测试通过，Debug APK 构建通过）。未执行设备飞行模式验收；详情页下载入口、下载列表和用户闭环仍归 S2-07。

## 最近完成：S2-03 Android 后台下载执行 — DONE

交付 WorkManager `CoroutineWorker`、唯一工作串与非计量网络/存储约束、可选 expedited 调度、前台进度通知及
暂停/继续/取消操作。`VeneraApplication` 安装进程级下载环境，并与阅读器共享 HTTP、Cookie、Coil 管线；Worker
不依赖 Activity。Manifest 为 WorkManager 前台服务声明 API 34+ `dataSync` 类型及对应权限。中断会通过协程取消
传播，并把尚未完成的已领取页面转为 Paused；取消通知不删除已完成章节。

验证：`:data:download:testDebugUnitTest :data:download:compileDebugAndroidTestKotlin :app:assembleDebug` — PASS；
80 项 JVM 测试通过，instrumentation 测试源码编译通过，Debug APK 构建通过。
合并 Manifest 已核对 `SystemForegroundService` 的 `dataSync` 类型。当前没有可用 ADB 设备，instrumentation 未实跑。
详情页触发下载和下载列表 UI 尚未接入，用户侧闭环明确留在 S2-07 验收任务。
通知权限在 API 33+ 仍没有运行时申请 UI，留待下载界面任务；Android 16 长时 Worker 配额与 5 分钟僵尸阈值
仍需 S2-07 实机验收。WorkManager/UIDT 选择及 Android 16 风险见 ADR-0005。

S2-02 原有新增 JVM 单测：data:download 69（DefaultDownloadRepositoryTest 15 / DownloadRecoveryTest 12 /
  DownloadStateMachineTest 10 / DownloadFileLayoutTest 11 / DownloadMappersTest 8 /
  PageDownloaderTest 7 / DownloadQueueTest 6）、core:model 5（ChapterRefTest）
全量 testDebugUnitTest：BUILD SUCCESSFUL
```

## 最近完成：S2-01 本地收藏与书架 — DONE

依赖：Stage 1（S1-06 Room 已落地）。

实际交付：

- `:core:database` 升到 **version 2**（只做 v1→v2，不预埋 v3）：新增 `FavoriteFolderEntity`（`favorite_folder`）
  与 `FavoriteEntryEntity`（`favorite_entry`，复合主键 `ref_source` + `ref_comic`）、`FavoriteDao`，
  `Migrations.kt` 里只有一个 `MIGRATION_1_2`（建两张表 + 四个索引 + 播种默认分组）。
  `core/database/schemas/.../2.json` 已入库。
- **`:core:database` 仍然不依赖 `:core:model`**：收藏表只用字符串列（`ref_source` / `ref_comic` /
  `folder_id` 等），排序与枚举都存名字，值对象转换全部放在 `:data:collection`。
- 身份用 `ComicRef`（新增于 `:core:model`）：`Remote(ComicKey)` 与 `Local(LocalComicId)` 共用
  `ref_source` / `ref_comic` 两列；本地取保留命名空间 `@local`，而来源安装拒绝以 `@` 开头的 key，
  因此两条路径不会撞。
- 新建 `:data:collection`：`CollectionRepository` 契约、`DefaultCollectionRepository`、
  `UpdateMarker`、`RemoteChapterProbe` / `ComicCatalogChapterProbe`、`CollectionMappers`。
  **排序是 DAO 查询而不是内存重排**：四种 `ShelfSort`（加入时间 / 标题 / 最近阅读 / 有更新）各对应一条
  带 `ORDER BY` 的 SQL（标题 `COLLATE NOCASE`、最近阅读空值排最后），仓库只挑查询，不持有列表。
- **有更新的判定不用时间戳**：把已存的 `(chapterCount, latestChapterId)` 快照与新探测到的
  `ChapterSnapshot` 比较——首次探测只记基线不算更新，章节变少不算更新，章节数相同但最后一章被替换算更新；
  源答不出来（离线 / 无此能力）保持原标记，不误标也不误清。
- 新建 `:feature:library`：`LibraryUiState` / `LibraryAction` / `LibraryViewModel` / `LibraryRoute` /
  `LibraryScreen` + `FolderChips` / `FavoriteGrid`。ViewModel 只有
  `folderId + sort` 的选择 `StateFlow`，数据经 `flatMapLatest` 订阅仓库 Flow，**自身不留任何列表副本**。
- 装配：`:core:navigation` 的 `AppRoute` 增加 `Library` 及其字符串编解码；`:feature:home` 增加
  「书架」入口；`:app` 在 `AppGraph` 里用 `DefaultCollectionRepository(db, ComicCatalogChapterProbe(catalog))`
  装配，`MainActivity` 增加 `AppRoute.Library` 分支。

验收对照：

| 验收项 | 结论 | 依据 |
| --- | --- | --- |
| 分组可新建 / 重命名 / 删除 | 通过 | `DefaultCollectionRepositoryTest`（新建追加并 trim、重命名可见、删除把漫画移回默认分组、默认分组不可删） |
| 条目排序且顺序持久化 | 通过 | 四种排序各自选出不同首项的断言 + `FavoriteDaoTest`（真 SQLite 上的 `ORDER BY`、`COLLATE NOCASE`、空值排最后） |
| 条目可在分组间移动 | 通过 | `moveTo` 移动单条；删除分组时整组移回默认分组 |
| 可标记 / 清除「有更新」 | 通过 | `refreshUpdates()` 与 `clearUpdate()` 的断言，含「清除后再次刷新不会重新标记」 |
| Room 是唯一事实来源 | 通过 | 仓库只暴露 Flow；`LibraryViewModelTest` 断言切换分组与排序各只发一次查询，重复选中同一分组不再查询 |

已知缺口（明确留给后续，不是遗漏）：

- **「加入书架 / 移出书架」入口已补在 `:feature:details`**：详情页有一个收藏开关，未收藏时显示
  「Add to shelf」，已收藏时显示「On the shelf」并可点掉；写进默认分组（`DEFAULT_SHELF_FOLDER_ID`），
  文件夹选择仍然只在书架页。是否已收藏由 `CollectionRepository.observeItem()` 从 Room 读回，
  不是屏幕记住自己点过什么。
- `lastReadAt` 已落库但还没有写入方：阅读链路的进度上报目前只进 `:data:history`，把最近阅读时间同步到
  收藏条目需要一条明确的写入点，未在本轮凭猜测接上。
- instrumentation 测试（`VeneraDatabaseMigrationTest`、`FavoriteDaoTest`）与 Compose 测试只编译未执行，
  按 `AGENTS.md` 第 7 节普通节点策略；迁移的数据保留断言要等真机轮次才真正跑起来。
- 未跑 Lint、未装 APK、未做实机验证。

验证记录：

```text
2026-09-22（编译级 + 相关模块 JVM 单测，按 AGENTS.md 第 7 节普通节点策略）
JAVA_HOME=/Users/lwtor/Library/Java/JavaVirtualMachines/corretto-17.0.9/Contents/Home
ANDROID_HOME=/Users/lwtor/Library/Android/sdk
sh gradlew :app:assembleDebug :core:database:compileDebugAndroidTestKotlin
           :data:collection:testDebugUnitTest :feature:library:testDebugUnitTest
           :core:model:testDebugUnitTest :core:navigation:testDebugUnitTest
结果：BUILD SUCCESSFUL

新增 JVM 单测：data:collection 29（DefaultCollectionRepositoryTest 17 / UpdateMarkerTest 7 /
  CollectionMappersTest 5）、feature:library 11（LibraryViewModelTest）、core:model 5（ComicRefTest）、
  core:navigation 4（AppRouteEncodingTest，较原来 +1）
failures=0 errors=0

补上收藏入口后复跑：
sh gradlew :app:assembleDebug :feature:details:testDebugUnitTest :data:collection:testDebugUnitTest
           :feature:library:testDebugUnitTest
结果：BUILD SUCCESSFUL
feature:details 14（DetailsViewModelTest 8 + 收藏往返 6）/ data:collection 29 / feature:library 11
failures=0 errors=0
```

## 已经完成

### S0-00 产品与架构规划 — DONE

- 确认项目名为 Venera Native。
- 确认 Android-only、Kotlin、Compose、MVI/UDF、多模块方向。
- 梳理 Venera 的主要功能范围与 JavaScript 漫画源兼容目标。
- 完成产品和长期技术蓝图 `docs/PROJECT_PLAN.md`。
- 创建公开仓库 `lwtor/venera-native`。

### S0-01 工程基础 — DONE

已建立模块：

```text
:app
:core:common
:core:designsystem
:core:model
:core:navigation
:feature:home
:source:api
:source:engine
```

其中 `:core:common` 与 `:core:navigation` 已在 S0-07 因零引用删除（见下）；这里记录的是 S0-01
当时的结果，不是当前模块清单。当前清单以 `README.md` 与 `docs/ARCHITECTURE.md` 为准。

已完成：

- Gradle Kotlin DSL、Version Catalog、Convention Plugins。
- AGP 内置 Kotlin、Compose、Material 3、Navigation 3。
- `:app` 单入口和最小首页。
- `ComicKey`、`SourceId`、`RemoteComicId` 基础值对象。
- `SourceScriptRuntime` 初始契约。
- AndroidX JavaScriptEngine 支持检测包装。（S0-07 已删除：零引用，Runtime 直接使用 `JavaScriptSandbox.isSupported`。）
- 首个领域模型单元测试。
- Windows 与类 Unix Gradle Wrapper。
- Git 文本换行规则。

验证记录：

```text
2026-09-19
./gradlew lintDebug :core:model:testDebugUnitTest :app:assembleDebug
结果：BUILD SUCCESSFUL

2026-09-19（Android Studio 兼容基线调整）
./gradlew lintDebug testDebugUnitTest :app:assembleDebug
工具链：AGP 9.2.1 + Gradle 9.4.1 + JDK 17 + API 37
结果：BUILD SUCCESSFUL
APK：app/build/outputs/apk/debug/app-debug.apk
```

### S0-02 JavaScript Runtime 最小执行闭环 — DONE

已完成：

- 将 `:source:api` 细化为类型化函数调用、JSON 结果和稳定 Runtime 错误模型。
- 来源包安装前校验版本、脚本和 SHA-256。
- 使用一个 `JavaScriptSandbox` 和每来源独立 `JavaScriptIsolate`。
- 实现安装、调用、超时、显式取消、卸载和 Runtime 关闭。
- 超时或取消后丢弃当前 isolate，下次调用自动重新加载来源。
- 要求 Promise Return 和 Isolate Termination 能力，不满足时显式返回 `EngineUnavailable`。
- 增加无网络、无第三方内容的固定 JavaScript fixture。
- 记录 `docs/adr/0002-javascript-runtime.md`。

验证记录：

```text
./gradlew :source:api:testDebugUnitTest :source:engine:testDebugUnitTest :source:engine:assembleDebugAndroidTest
结果：BUILD SUCCESSFUL

./gradlew :source:engine:connectedDebugAndroidTest
设备：V2337A / Android 16
结果：8 tests，BUILD SUCCESSFUL

./gradlew --no-daemon --max-workers=2 lintDebug
./gradlew --no-daemon --max-workers=2 testDebugUnitTest :app:assembleDebug
结果：BUILD SUCCESSFUL
```

### S0-03 异步 Host API 与受控网络桥 PoC — DONE

已完成：

- 新增 `:core:network` 和 `:source:network`。
- 引入 OkHttp 5.5.0，提供 Dispatcher、稳定错误映射和可取消请求。
- 实现 GET、POST、Header、UTF-8 文本 Body、结构化状态码与响应。
- 实现每来源 CookieJar、每来源并发上限和 1 MiB 响应限制。
- 在 `:source:api` 增加与引擎、OkHttp 无关的 Host API 契约。
- 使用 JavaScriptEngine MessagePort 实现带调用 ID、请求 ID和允许列表的异步桥。
- Runtime 取消、超时、卸载和关闭会继续取消 Host 协程及 OkHttp Call。
- 增加 MockWebServer、脱敏、Cookie、并发、取消和端到端桥接测试。
- 新增 ADR-0003。

验证记录：

```text
./gradlew :core:network:lintDebug :source:network:lintDebug :source:engine:lintDebug testDebugUnitTest :app:assembleDebug
结果：BUILD SUCCESSFUL

./gradlew :source:network:assembleDebugAndroidTest :source:engine:assembleDebugAndroidTest
结果：BUILD SUCCESSFUL

V2337A / Android 16：
原有 Runtime 8 tests PASS。
设备 JavaScript Sandbox 不支持 JS_FEATURE_MESSAGE_PORTS，Host 桥测试按能力条件跳过。
```

### S0-05 Compose 阅读器基础原型 — DONE

已完成：

- 新增 `:feature:reader`，这是第一个用户可见的交互切片。
- `:core:model` 增加 `ChapterKey` 与 `ComicPage` 页面描述符；描述符只带尺寸与不透明
  `imageRef`，不持有 Bitmap，可安全放入 Compose State。
- 定义 `PageProvider` 契约与 `FakePageProvider`（内存、确定性，供预览、演示与测试）。
- 实现 `ReaderRoute` / `ReaderScreen` / `ReaderViewModel` / `ReaderUiState` / `ReaderAction`；
  状态走 `StateFlow`，Compose 只渲染状态并发送 Action。
- 纵向连续阅读（`LazyColumn`）与横向单页翻页（`HorizontalPager`）最小切换，RTL 由
  `reverseLayout` 表达。
- 章节标题、页码、加载中与失败重试状态。
- 以可见页为中心的邻近预取（默认半径 1），按页索引去重。
- `:app` 首页增加入口，可用内存 Provider 直接打开阅读器。

有意排除：

- 真实图片解码与图像管线（Coil 或子采样方案由 S0-06 决定）。
- 下载、本地压缩包、真实漫画源、持久化阅读进度。
- 缩放手势与正式视觉设计。

验证记录：

```text
2026-09-19（编译级，按 AGENTS.md 第 7 节普通节点策略）
JAVA_HOME 临时指向本机 JDK 17（仅当前命令，不入库）
.\gradlew.bat :app:assembleDebug :feature:reader:compileDebugUnitTestKotlin :feature:reader:compileDebugAndroidTestKotlin
结果：BUILD SUCCESSFUL
说明：ReaderViewModelTest 与 ReaderScreenTest 只编译通过，未执行；实际执行留给关键节点
```

构建期间 Gradle daemon 因原生内存耗尽崩溃（`hs_err` 报告 malloc 失败）并损坏 file-access
缓存，已清理缓存并用 `--no-daemon --max-workers=2` 完成验证。本机资源紧张时建议沿用该参数。

### S0-06 超长图、缩放和内存验证 — DONE

已完成（2026-09-20）：

- `docs/adr/0004-large-image-strategy.md` 状态改为 Accepted：默认策略是区域/分块解码，
  整页 fit 采样降级为对照基线与短页快路径；在设备实测补齐前**不引入 Coil**。
- 新增 `PageTiling` 纯函数：尺度、瓦片、窗口，以及**解码字节记账**；`PageTilingTest`（9 项）覆盖几何。
- 新增 `PageDecodeBudgetTest`（9 项）：把 24 MiB 单次解码预算、整页采样阈值、
  “解码成本与页面长度无关”变成可执行断言，而不是依赖设备观察。
- `:feature:reader` 新增 `image` 包：`PageImageDecoder`、`SampledPageImageDecoder`、
  `RegionPageImageDecoder`、有界 `PageImageCache`、`CachingPageImageDecoder`。
- 阅读器接入真实解码：纵向连续阅读按瓦片渲染，横向翻页按可见窗口渲染；`ReaderZoomState`
  支持双指缩放与平移，缩放激活时关闭列表滚动；底部栏可运行时切换策略做 A/B。
- 新增 `AssetFixturePageProvider`：`assets/fixtures` 存在时用生成图，否则退回占位实现。
- 新增 `tools/test-images`（生成器 + README）：确定性生成 6 个 fixture，禁止提交版权内容。
- 新增 `LargeImageProbeTest`：设备侧的耗时 / PSS / 预取窗口测量入口（只编译，未执行）。

结论（解析内存模型，1080×2000 视口、24 MiB 预算）：

| 页面 | 整页解码 | 整页 fit 采样 | 实际解码 |
| --- | --- | --- | --- |
| 1080×1440 常见页 | 5.9 MiB | 5.9 MiB | 5.9 MiB，整页不切分 |
| 1080×6000 长条 | 24.7 MiB | 24.7 MiB（超预算） | 12.4 MiB × 2 瓦片 |
| 1080×16000 超长条 | 65.9 MiB | 65.9 MiB（超预算 2.7 倍） | 12.4 MiB × 6 瓦片 |
| 3000×4000 高清 | 45.8 MiB | 11.4 MiB | 11.4 MiB，整页不切分 |

- 整页解码成本随页面长度线性增长，区域解码与页面长度无关，因此 `Region` 是默认策略。
- 中等缩放区间必须让采样服从内存预算（代价是最多约 1.41 倍 GPU 放大），
  否则 2 的幂采样在 `scale ∈ (0.5, 1)` 无法收敛。

验证记录：

```text
2026-09-20（编译级 + JVM 单测，按 AGENTS.md 第 7 节普通节点策略）
JAVA_HOME 临时指向本机 JDK 17（仅当前命令，不入库）
.\gradlew.bat --no-daemon --max-workers=2 :feature:reader:testDebugUnitTest :feature:reader:compileDebugAndroidTestKotlin :app:assembleDebug
结果：BUILD SUCCESSFUL
单元测试：PageDecodeBudgetTest 9 / PageTilingTest 9 / ReaderViewModelTest 8，failures=0 errors=0 skipped=0

2026-09-20（生成器实跑）
java -Xmx2g tools/test-images/GenerateTestImages.java tools/test-images/out
结果：6 个 fixture 全部生成，输出已删除（仅验证，未保留）
```

按用户决定推迟的设备验证（**不得写成已验证**）：

用户决定（2026-09-20）：**非必要不做实机测试**。以下项目改为 Stage 0 退出门禁与已知风险：

- fixture × 策略 × 模式（连续 / 翻页 × zoom 1 与 3）的解码耗时、位图字节数与 PSS 增量。
- 预取窗口 1/2/3 的峰值内存与掉帧。
- 快速滚动、连续翻页、旋转、后台恢复的人工验证。
- 双指缩放与滚动手势冲突的人工验证（规则已定，行为待确认）。

需要时按下面的命令采集：

```powershell
java -Xmx2g tools/test-images/GenerateTestImages.java feature/reader/src/main/assets/fixtures
.\gradlew.bat :feature:reader:assembleDebugAndroidTest
adb install -r -t feature\reader\build\outputs\apk\androidTest\debug\reader-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w dev.veneranative.feature.reader.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s VeneraImage:I
```

### S0-07 Stage 0 决策收敛与 ADR — DONE

已完成（2026-09-20）：

- ADR-0002 增补“S0-04 实测修订”：引擎选择维持 AndroidX JavaScriptEngine 1.1.0；二进制只能走
  ArrayBuffer，超时可靠但结果大小上限不可依赖，沙箱终止后必须重建整个 runtime。
  QuickJS fallback 条件逐条对照实测结果，其中“消息桥无法稳定支持取消与并发”部分命中。
- ADR-0003 修订能力要求：**MessagePort 不再是 Host API 的硬性前提**（参考设备 `messagePorts=false`），
  传输层改为能力探测，替代传输的具体形式留待 Stage 1 的独立 ADR；二进制统一走
  `provideConsumeArrayBuffer`。
- 删除零引用代码：`:core:common`（`AppResult`/`AppError`）、`:core:navigation`（`AppRoute`）、
  `JavaScriptEngineSupport` 与 `ReaderZoomState.reset()`，并移除 app 未使用的 `:core:navigation` 依赖。
  判定依据与重建条件见 `docs/ARCHITECTURE.md` 第 3 节。
- 新增 `PerSourceCookieJarRegistryTest`（5 项 JVM 测试）：每来源 Cookie 隔离、清理与过期规则
  现在可在 JVM 回归，不再只依赖无法在参考设备上完整执行的 instrumentation 测试。
- 文档与代码对齐：修正 `SourceHttpClient` 命名漂移、S0-04 已删除测试类的残留命令、模块表与依赖。

Stage 0 退出标准对照：

| 退出标准 | 结论 | 依据 |
| --- | --- | --- |
| JS Engine 可用性、限制、fallback 条件清晰 | 满足 | ADR-0002 实测修订 + ADR-0003 能力探测修订 |
| Reader 图片方案有实测证据 | 部分满足：解析证据充分，设备证据推迟 | ADR-0004 + `PageDecodeBudgetTest`；PSS/耗时/掉帧未测，见已知风险 |
| Stage 1 不再依赖未回答的关键技术假设 | 基本满足，保留一项待决策 | 异步 Host 桥的替代传输必须在 S1-01 实现协议前用新 ADR 定案 |

验证记录（关键节点完整验证）：

```text
2026-09-20
JAVA_HOME 临时指向本机 JDK 17（仅当前命令，不入库）
.\gradlew.bat --no-daemon --max-workers=2 lintDebug testDebugUnitTest :app:assembleDebug
结果：BUILD SUCCESSFUL（1m 26s）

全量单元测试 39 项，failures=0 errors=0 skipped=0：
  core:model 1 / core:network 1 / feature:reader 26 / source:engine 5 / source:network 6
  feature:reader = PageDecodeBudgetTest 9 + PageTilingTest 9 + ReaderViewModelTest 8
  source:network = PerSourceCookieJarRegistryTest 5 + SensitiveDataRedactorTest 1

Lint：lintDebug 在各模块执行，无阻断问题，未使用 lint baseline。
instrumentation 与实机验证本轮未执行（按用户决定不做实机测试）。
```

## 当前代码事实

- `:app` 用一个 `AppRoute` 状态切换七个目的地（Home / Sources / Explore / Search / ComicDetails /
  Reader / Library），路由经 `encode()` / `decodeAppRoute()` 存进 `rememberSaveable`；还没有返回栈。
- 书架与收藏已落地：`:data:collection` + `:feature:library`，Room v2 的 `favorite_folder` /
  `favorite_entry` 是唯一事实来源；首页「书架」入口进书架，详情页的收藏开关负责加入与移出。
- `:feature:home` 只是占位 UI，不包含 ViewModel 或真实数据。
- 来源链路已可用：安装 / 启停 / 卸载（`:feature:sources` + `:data:source`）、探索与搜索
  （`:feature:explore` / `:feature:search` + `:data:comic` + Paging 3，仅向前分页）、详情与章节
  （`:feature:details`）。
- `:feature:details` 的封面槽位是**占位块**：`coverUrl` 已在状态里，但解码与加载依赖尚未建立的
  图片管线（S1-05）。
- `:feature:reader` 已能渲染页面描述符、切换阅读方向并调度邻近预取；当 `assets/fixtures`
  存在时会改用 `AssetFixturePageProvider` 与真实解码管线，否则退回尺寸占位块。
- `:feature:reader` 的解码实现处于原型阶段：`PageImageDecoder` 有 `Sampled`（整页降采样，
  等价于通用图片库 fit 采样）与 `Region`（`BitmapRegionDecoder` 区域/分块）两种策略，
  可在阅读器底部栏运行时切换；解码结果只存在于有界 `PageImageCache`，不进入 `ReaderUiState`。
- 图片解码代码目前位于 `:feature:reader`，与“图片管线属于 core”的目标边界不一致；
  S1-05 建立 `:core:image` 时必须迁移，已在 ADR-0004 记为技术债。
- 阅读器仍由 fixture / 占位 `PageProvider` 驱动，**尚无 source-backed 实现**：`ComicPage` 需要
  `widthPx`/`heightPx`，而源只给 URL，尺寸只能由图片管线解析（S1-05）。
- `:source:api` 是唯一稳定契约：Runtime 契约 + `SourceCore` 五能力 + 上游协议编解码；
  `:source:core` 是它的引擎实现，`:source:engine` 提供 QuickJS 运行时。
- Runtime 当前为每来源串行调用；支持非 Binder 传输时结果上限配置为 1 MiB。
- Runtime 已有 Host API 兼容层（全局 `fetch` 与 `Network.*`）、HTTP 文本请求、每来源 Cookie 与取消链路，
  尚无二进制通道。
- 引擎已从 AndroidX JavaScriptEngine 切到自有 QuickJS（ADR-0008）；WebView 实现仍留在 `:source:engine`
  及其 androidTest 中，等待判据补齐后按 ADR-0008 §2.4 处置。
- 已引入 OkHttp、Paging 3、kotlinx.serialization 与 QuickJS 绑定；尚未引入 Hilt、Room、DataStore、
  Coil、WorkManager。
- 尚未创建 CI、许可证文件、正式图标或发布配置。
- 当前 JVM 单元测试 208 项（最近一次全量执行 0 失败，见 S1-04 记录）；Compose 与 instrumentation
  测试源码会随改动编译，但本轮没有执行 androidTest。
- S0-04 已在 V2337A / SDK 36 取得决策所需的实机结论；压力测量 harness 取得结论后已删除，未保留为长期资产。
- `applicationId = dev.veneranative` 仍是暂定值。

## 最近完成：S0-04 Runtime 限制、二进制和压力验证 — DONE

目标：量化 JavaScriptEngine 方案的边界，决定是否可进入 Stage 1。

本轮执行方式与范围裁剪：

- 曾新增 4 个压力测试文件（`SourceRuntimeLimitsTest`、`SourceRuntimeStressTest`、`StressProbe`、
  `stress_fixture.js`）并在 V2337A 上执行一轮，取得下方结论后已全部删除。
- 删除理由：这些测试覆盖的超时、队列、并发、加载卸载 churn 等项测的是 Runtime 自身逻辑而非引擎，
  后续应在 JVM 单测中用假引擎和注入时钟覆盖，不值得长期保留实机 harness。
- S0-04 按“决策所需最小验证”收尾：只保留真正影响架构的引擎结论，不追求边界数字收敛。

第一轮实测结果（设备 vivo V2337A / SDK 36，2026-09-19，25 个测试全部通过）：

| 场景 | 实测结果 |
| --- | --- |
| 沙箱能力 | `isolateTermination=true`、`promiseReturn=true`、`evaluateWithoutTransactionLimit=true`、`provideConsumeArrayBuffer=true`、**`messagePorts=false`** |
| 脚本内建能力 | `ArrayBuffer`/`Uint8Array`/`Promise` 可用；**`btoa`/`atob`/`TextEncoder`/`setTimeout` 均不可用** |
| 大参数 | 64 KiB 至 8 MiB 全部成功（8 MiB 耗时 489 ms），未触到失败点 |
| 大结果 | 在 1 MiB 与 8 MiB 两种配置下，8 MiB 结果均成功；**配置的结果上限在真机上未生效** |
| 每来源并发 | 32 次并发全成功，153 ms（串行，约 4.8 ms/次） |
| 跨来源并发 | 4 源 × 8 = 32 次全成功，54 ms（真实并行） |
| 顺序吞吐 | 100 次调用 375 ms，平均 3.75 ms/次 |
| 队列深度 | 200 次排队全成功，1045 ms |
| 单调用超时 | 配置 300 ms，实测 307 ms，返回 `Timeout`；超时后 isolate 重建 |
| 沙箱异常终止 | 1287 ms 后 `EngineTerminated`；**同一 runtime 复用仍失败，重装来源也失败，只有新建 runtime 才恢复** |
| 加载/卸载 churn | 30 轮 0 失败；应用进程 PSS 79 → 82 KiB |
| 沙箱重连 | 关闭后新建 sandbox 计数归 1，可正常使用 |

注：沙箱运行在独立进程，应用 PSS 不含 isolate 内存，PSS 数据只能作为趋势参考。

由实测得到的三条硬结论：

1. **二进制只能走 ArrayBuffer。** `messagePorts=false`，且脚本侧没有 `btoa`/`atob`/`TextEncoder`，
   Base64 字符串通道在真机上不可用；图片等二进制数据必须使用 `provideConsumeArrayBuffer`。
2. **超时是可靠护栏，内存上限不是。** 超时误差在 2% 以内；但配置的结果大小上限真机上没有生效，
   不能当作内存保护，Runtime 需要自行计数与截断。
3. **沙箱终止后必须重建整个 runtime。** 终止后复用 runtime 与重新安装来源都无法恢复。

本轮未验证（需要后续实机轮次）：

- 大参数与大结果上限的真实收敛点（8 MiB 内未触底）。
- 前后台切换、进程回收后的 Runtime 行为。
- API 26 至当前目标版本的低版本可用性。

实机验证按 `AGENTS.md` 第 7 节只在关键节点执行；上述未验证项不在本轮范围，不得写成“已验证”。

验收状态（裁剪后）：

| 原验收项 | 状态 |
| --- | --- |
| 大 JSON 参数与结果上限 | 部分完成：8 MiB 内全部通过，未收敛到具体边界；判定为无需继续收敛 |
| 二进制跨桥接传递策略 | 完成：只能走 `provideConsumeArrayBuffer` |
| 超时、队列、每来源并发、全局并发 | 完成（见上方数据表）；对应测试已删除，后续转 JVM 单测 |
| 沙箱异常终止恢复与资源释放 | 完成：终止后必须重建整个 runtime |
| 前后台切换、进程回收、API 26 可用性 | 未验证，转入已知风险 |
| 可重复压力测试与数据表 | 数据表保留在上方；harness 已删除，不作为长期资产 |
| 更新 ADR-0002 | 待办，转入已知风险 |

是否进入 Stage 1 的结论：**可以进入**。S0-02 已证明脚本执行、超时、取消与生命周期可用；
本轮补充确认了二进制通道与终止恢复路径。剩余不确定项不影响 Stage 1 的 UI 与数据层推进。

验证记录（本轮）：

```text
2026-09-19（编译级）
JAVA_HOME 临时指向本机 JDK 17（仅当前命令，不入库）
./gradlew :source:engine:assembleDebugAndroidTest
结果：BUILD SUCCESSFUL

2026-09-19（实机级，设备安装限制解除后执行）
adb install -r -t engine-debug-androidTest.apk → Success
adb shell am instrument -w dev.veneranative.source.engine.test/androidx.test.runner.AndroidJUnitRunner
结果：OK (25 tests)
数据：adb logcat -s VeneraStress:I，已归档为本文件上方实测表
说明：按 AGENTS.md 第 7 节，本轮只针对 S0-04 这一专项验证任务执行实机测量
```

建议验证命令：

```powershell
.\gradlew.bat :source:engine:testDebugUnitTest :source:engine:assembleDebugAndroidTest
.\gradlew.bat :source:engine:connectedDebugAndroidTest
.\gradlew.bat lintDebug :app:assembleDebug
```

实测数据收集命令（后续实机轮次，需连接设备）：

```powershell
adb logcat -c
adb shell am instrument -w dev.veneranative.source.engine.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s VeneraStress:I
```

S0-04 的 `SourceRuntimeLimitsTest`、`SourceRuntimeStressTest`、`StressProbe`、`stress_fixture.js`
已按上文删除，所以上面的命令只运行当前保留的引擎测试类；重建压力数据需要先重建 harness。
`VeneraStress` tag 只对存活下来的测试有效。

不在 S0-04 范围：

- 真实商业漫画源或真实账户。
- WebView 登录、验证码和账户 UI。
- HTML DOM/CSS Selector Host API。
- 正式 Reader UI 和下载功能。
- 在结论得出前实现完整 QuickJS fallback。

## 最近完成

### S1-04：漫画详情与章节 — DONE

依赖：S1-03（已完成）。

已完成（2026-09-20）：

- 新增 `:feature:details`：`DetailsRoute` / `DetailsScreen` / `DetailsViewModel` / `DetailsUiState` /
  `DetailsAction`。`:app` 里那个标注为占位的 `ComicDetailsPlaceholder` 已删除，路由链路现在是真实实现。
- **详情与章节一次取回**：上游把元数据与章节放在同一个 `loadInfo` 响应里，所以刷新详情就是刷新章节，
  不为同一份数据发第二次请求。`ComicCatalog` 因此只暴露 `detail`，不重复暴露 `chapters`。
- **章节列表按源声明的样子渲染**：单列与带组名分区两种形状都支持，组名和组的顺序都来自源。
  显示顺序提供「源顺序 / 倒序」，**不做按标题或序号重排**——`Chapter.index` 是源顺序里的位置，
  部分源按新→旧发布，重排会呈现源从未描述过的顺序（`DetailsChaptersTest` 把它写成断言）。
- **四种状态**：加载、成功、失败可重试、来源失效（已卸载或已停用）。来源失效在详情请求**之前**判定，
  因为"源不在了"和"源答不出来"对用户不是同一件事，只有后者值得重试（`catalog.enabledSource`）；
  源返回空章节算**部分结果**而不是失败。
- **失败文案在 feature 内穷举映射**：`when` 覆盖 `SourceRuntimeError` 的全部分支，兜底是通用文案而不是
  下层的 `message`，因此引擎诊断文本不会进界面（有测试断言 `TypeError` 不出现在文案里）。
- **边界**：详情屏只把选中的 `ChapterKey` 通过 `onOpenChapter` 交给装配层，不依赖 `:feature:reader`；
  `:app` 负责把它接到 Reader 路由。
- `:data:comic` 新增 `detail(comicKey)` 与 `enabledSource(sourceId)`。后者从已安装列表解析而不是问运行时，
  因为运行时只会回答 "not loaded"，那是引擎细节而不是用户看到的理由。
- 测试：`DetailsViewModelTest`（8）+ `DetailsChaptersTest`（5）+ `ComicCatalogTest` 新增 4 项（现共 11）。

已知缺口（明确留给后续，不是遗漏）：

- **封面的像素渲染依赖 S1-05**：`coverUrl` 已在状态里，但把它变成图片需要图片管线（`:core:image` + Coil）。
  详情屏的封面槽位按最终尺寸占位并显示标题，与 `:feature:reader` 在没有 fixture 时退回占位块的既有做法一致。
- **章节选择生成 `PageProvider` 需要 S1-05 先解决尺寸**：`ComicPage` 要求 `widthPx`/`heightPx` > 0，而源只给
  URL（`SourcePage` 只有 `imageRef`），source-backed 的 `PageProvider` 无法在 S1-04 内诚实实现。
  当前 `:app` 仍用 fixture/占位 Provider，真实链路随 S1-05 接通。
- 详情屏的返回键回 Home，与 `:app` 现有单一 `route` 状态一致；完整返回栈仍待后续。

验证记录：

```text
2026-09-20（编译级 + JVM 单测）
JAVA_HOME 临时指向本机 JDK 17（C:\Users\11196859\.jdks\jbr-17.0.14，仅当前命令，不入库）
.\gradlew.bat --no-daemon --max-workers=2 :app:assembleDebug
              :feature:details:compileDebugUnitTestKotlin :data:comic:compileDebugUnitTestKotlin
              :feature:explore:compileDebugUnitTestKotlin :feature:search:compileDebugUnitTestKotlin
结果：BUILD SUCCESSFUL in 41s

.\gradlew.bat --no-daemon --max-workers=2 testDebugUnitTest
结果：BUILD SUCCESSFUL in 31s
全量单元测试 208 项，failures=0 errors=0 skipped=0
  feature:details 13（DetailsViewModelTest 8 + DetailsChaptersTest 5）/ data:comic 11 /
  其余模块与 S1-03 记录一致（191 → 208）

git diff --check：无空白错误；改动文件换行符已按 .gitattributes 统一为 LF
```

本轮改了 `ComicCatalog` 这个被三个模块共享的接口（`explore`、`search`、`details`），所以虽然按普通节点
策略只要求编译，仍补跑了全量 JVM 单测确认没有连带影响。instrumentation 与实机验证本轮未执行。

### S1-03：探索与搜索纵向切片 — DONE

依赖：S1-01、S1-02、S1-08。

已完成（2026-09-20，地基部分）：

- **纠正了源的加载与调用约定**。此前运行时把源当成"一堆全局函数"，而**真实源不是这样**：
  核对上游 `parser.dart` 与 `assets/init.js` 后确认的约定是"找第一行 `class X extends ComicSource` →
  整脚本包进 IIFE 实例化 → 写进 `ComicSource.sources[key]` → 成员按**路径**调用"。按此实现：
  `SourceClassConvention`（约定与生成代码）、`SourceBaseScript`（JS 侧基类，含 `sources` 注册表）、
  加载时校验"脚本声明的 key 与安装的 id 一致"，成员调用解析路径并把 `this` 绑到声明它的对象。
  这条纠正之前，任何真实源都无法安装（ADR-0007 §4.4）。
- `QuickJsMetadataReader` 改为同一约定：实例化后读实例字段，而不是读全局变量。
- 新增 `SourceClassConventionTest`（3）、`SourceInvocationScriptTest`（3，改为路径语义），
  `QuickJsRuntimeTest` 扩到 20 项（注册表、`this` 绑定、`init` 执行、`loadSetting` 默认值、
  路径成员、未知成员、key 不一致被拒、缩进声明被拒等）。

- **`SourceCore` 的引擎实现已落地**（新增 `:source:core` 模块）：`EngineSourceCore` 读取源声明的能力
  （引擎侧新增通用结构探针 `__venera.probe`，只报告形状、不解释协议），据此决定调用形态：
  有 `load` 就不用 `loadNext`、探索页按**位置**寻址（`explore.0.load` 这种带下标段的成员路径）、
  未声明的能力返回 `UnsupportedCapability` 而不是尝试调用。能力每个源只探测一次并缓存。
- 这一轮又抓到**两个真实缺陷**：协议层给 `explore[i].load(page)` 多传了一个页面 key 参数
  （上游只传一个页面参数，页面靠位置标识）；适配器把"源没有探索能力"误判成"未知页面"。
  两个都是只有拿上游形态的源真跑才会暴露的问题。
- 测试：新增 `EngineSourceCoreTest`（13 项，用真实引擎跑上游形态的源），
  `SourceProtocolParserTest` 补 3 项（游标列表与游标探索页），`SourceProtocolTest` 改为显式的
  `searchLoad` / `searchLoadNext`（分页形态由源声明决定，不再由调用方的游标类型决定）。

- **数据层 `:data:comic`**：`ComicCatalog` 承担两件 feature 不该各自重做的事——"哪些源对某个能力可用"
  （已安装 + 已启用 + 声明了该能力，能力读不到就排除而不是让整张列表失败）与"源的两种分页怎么变成
  Paging 3"。Paging 键用 `PageKey.Start / At(cursor)` 表达"首页不是游标"：首页让**源**决定（页码源从 1 开始、
  游标源收到 null token），Paging 与 UI 都不需要知道源是哪种。只提供向前分页（`prevKey` 恒为 null），
  因为游标源无法回退，页码源回退需要另一种请求形态。
- **`SourceLoadException`** 把领域错误穿过 Paging 3（它只能以 Throwable 失败），错误对象仍可取到，
  所以 UI 映射的是产品文案而不是异常信息。
- **两个 feature**：`:feature:explore`（来源选择 + 该源声明的探索页标签 + 分页内容，页面形状按源给的
  原样渲染：单列表 / 带标题分区 / 混合）与 `:feature:search`（来源选择 + 关键词 + 分页结果）。
  两者都只提供声明了对应能力的源；列表的加载/失败/空状态取自 Paging 本身（失败在列表上重试），
  文案由各自 feature 提供（feature 之间不互相依赖，也不共享字符串表）。
- **类型安全路由 `:core:navigation`**：`AppRoute` 是带类型的封闭集合，`ComicDetails(ComicKey)` 携带
  来源与远端 id（同一个远端 id 在不同来源可以是不同漫画）。路由与 `encode()`/`decodeAppRoute()` 分离，
  后者是纯函数并处理 id 里的 `:` 与 `%`，`:app` 用它把导航状态存进 `rememberSaveable`。
- **`:app` 装配**：一个 `QuickJsRuntime` 同时服务来源仓库与目录；`EngineSourceCore` 挂在它上面；
  首页新增 Explore/Search 入口，搜索结果与探索结果都发 `ComicDetails` 路由。

S1-03 已知缺口（不是遗漏，是明确留给后续）：

- 详情屏本身属于 S1-04：`:app` 目前渲染一个**明确标注**的占位屏（它显示路由里的 comic key），
  路由链路已经端到端打通。
- 筛选器的 **UI** 未做：筛选值已能从源声明读取、按声明顺序编码并随请求发出（有测试），
  但界面上还没有控件，用户暂时只能用默认值。
- 探索页筛选：上游把筛选挂在**单个探索页**上，我们的 `ExplorePage` 还没有该字段，Stage 1 的
  探索调用也不带筛选（ADR-0007 §2.1）。
- Paging 的加载路径由 `:data:comic` 直接驱动 `PagingSource.load` 覆盖；VM 层验证"选择 → 请求"的
  纯函数。用 `paging-testing` 的 presenter 做端到端 paging 断言是可选的后续加固。

验证记录：

```text
2026-09-20（编译级 + JVM 单测，按 AGENTS.md 第 7 节普通节点策略）
JAVA_HOME 临时指向本机 JDK 17（仅当前命令，不入库）
.\gradlew.bat --no-daemon --max-workers=2 testDebugUnitTest :app:assembleDebug
              :feature:sources:compileDebugAndroidTestKotlin
结果：BUILD SUCCESSFUL
全量单元测试 191 项，failures=0 errors=0
  source:engine 39、source:core 13、source:api 33、core:model 24、core:navigation 3、
  data:comic 7、data:source 16、feature:search 7、feature:explore 6、feature:sources 9、
  feature:reader 26、source:network 6、core:network 1
```

## 上一任务：S1-08 自有引擎（QuickJS）落地 — 代码部分完成

### S1-08：自有引擎（QuickJS）落地 — 代码部分完成

依赖：S1-02（已完成）。

ADR-0008 已定案：WebView 系引擎在没有 MessagePort 的设备上无法提供异步 Host API，参考设备正是
这种情况；协议与 UI 都已在它之上就绪，缺的是引擎。

已完成（2026-09-20，选型 + 桥接 spike）：

- **绑定选型定案**（ADR-0008 §7）：`io.github.dokar3:quickjs-kt:1.0.5`（Apache-2.0）。
  选它而不是最新版是因为 1.0.6+ 用 Kotlin 2.4 编译、元数据版本超出本工具链可读范围
  （AGP 内置 Kotlin 2.2 只能读到 2.3），1.0.5 是 2.4 之前的最后一版。
- 已核实 `v1.0.5` 标签的 CMake 配置：Android + shared 时带 `-Wl,-z,max-page-size=16384`
  （Google Play 强制要求）并开启 `CONFIG_BIGNUM`。
- **JS → Kotlin 异步打通**：`QuickJsBridgeSpikeTest`（3 项，已在 JVM 执行通过）证明脚本可以
  `await` 宿主 suspend 调用并取回值、宿主异常变成脚本侧 rejected await、`Promise.all` 并发可用。
  这正是 WebView 引擎在参考设备上做不到的事。
- **实测出调用约束并写进 ADR**：必须按脚本求值 + 顶层 `await`；async IIFE 会返回未解的 Promise 对象，
  module 模式没有完成值。适配层不能自己包 async 函数。
- 引擎模块已能在 JVM 上被回归：`testImplementation(quickjs-kt-jvm)` + 在单元测试类路径上排除
  Android 产物（否则同一批类出现两次）。
- **契约实现已落地**：`QuickJsRuntime`（每源一个引擎实例、调用串行、取消与超时都通过取消求值协程
  映射、结果大小自行计数）、`QuickJsHostBridge`（允许列表 + 大小上限 + 响应信封）与
  `QuickJsHostScript`（兼容层）。`AndroidJavaScriptRuntime` 的结果信封解析已提取为
  `SourceResultEnvelope`，两个引擎共用同一套语义。
- **Host API 两套入口已提供**：全局 `fetch`（`ok`/`status`/`headers`/`text()`/`json()`/`arrayBuffer()`）、
  `Network.*`、`Convert.encodeUtf8`/`decodeUtf8`、`console.*`、`APP.locale`/`version`、`veneraHost.call`。
  兼容层范围按真实源的调用点确定（ADR-0008 §9），未提供的全局逐条记录，不凭想象补齐。
- **`QuickJsMetadataReader` 已替换 `:app` 的 `UnavailableMetadataReader`**：脚本在一次性引擎里执行后
  读取 `key`/`name`/`version`/`minAppVersion`。
- **装配层已切换到自有引擎**：`:app` 用 `QuickJsRuntime(hostApi = SourceNetworkHostApi(SourceNetworkExecutor()))`
  + `QuickJsMetadataReader`；WebView 实现保留在 `:source:engine` 及其 androidTest 中，按 ADR-0008 §2.4
  等待自有引擎通过全部判据后再处置。

仍待完成：

- 二进制通道在 Host API 上的验证；ABI 与 APK 体积实测；许可证登记。
- 通过后按 ADR-0008 §2.4 处置 WebView 实现，并更新 ADR-0002 的引擎状态。

验证记录：

```text
2026-09-20（编译级 + JVM 单测，按 AGENTS.md 第 7 节普通节点策略）
JAVA_HOME 临时指向本机 JDK 17（仅当前命令，不入库）

第一轮：发现三个真实缺陷（三个测试失败）
- 超时测试挂死至 JUnit 120s 超时：coroutineScope 会等待无法中断的子协程
- 另两项把 QuickJsException 直接抛出，绕过错误映射：async 子任务失败会传给父作用域
- 根因是同一个：求值不能作为调用方的子协程；该绑定无法中断引擎内的死循环（ADR-0008 §10）

第二轮（修正后）：
.\gradlew.bat --no-daemon --max-workers=2 testDebugUnitTest :app:assembleDebug
结果：BUILD SUCCESSFUL in 35s
全量单元测试 139 项，failures=0 errors=0
  source:engine 27（QuickJsRuntimeTest 13、QuickJsMetadataReaderTest 6、QuickJsBridgeSpikeTest 3、
  SourceInvocationScriptTest 2、SourcePackageValidatorTest 3）
  其中 QuickJsRuntimeTest 的"超时后源仍可用"用 2.0s 完成（重建引擎生效），
  "取消传导到宿主请求"断言通过
```

编译检查：

```powershell
.\gradlew.bat :app:assembleDebug
```

## 最近完成

### S1-02 来源包安装与管理 — DONE

依赖：S1-01。

已完成（2026-09-20）：

- 新增 `:data:source` 模块：`SourceRepository`（安装 / 启停 / 卸载 / 列表）与 `DefaultSourceRepository`。
- `SourcePackageStore`：每来源一个目录存脚本，外加 JSON 索引供列表页读取——**列表页不需要重新读取
  甚至执行脚本**。目录名用 source id 的十六进制编码，脚本自选的 id 无法逃出存储根目录（有测试断言）。
- 安装顺序保证“失败不污染已有版本”：先让运行时接受新包，**只有运行时接受后才写存储**；
  写入失败时把运行时回滚到存储仍然描述的那个版本。运行时拒绝时存储完全不动。
- 新增 `SourceMetadataReader` 契约（`:source:api`）：接收**脚本文本**而不是已安装包，因为包需要 id
  与版本，而这两个正是元数据要提供的；引擎实现在 S1-08 落地，本阶段用测试替身。
- `LocalFileScriptFetcher`：支持本地路径与 `file://`，满足“本地 URL/文件安装测试源”。
- 安装失败改为领域错误 `SourceInstallError`（位置不可读 / 元数据非法 / 引擎不可用 / 被拒绝 / 存储失败），
  UI 文案由 Feature 映射；下层的诊断字符串只用于日志，不再直接展示（有测试断言）。
- `:feature:sources` 落地：`SourcesScreen` / `SourcesViewModel` / `SourcesRoute`，加载 / 空 / 失败 / 成功
  四种状态齐备，卸载带确认对话框；`:app` 用 `AppScreen` 枚举装配三个目的地，Home 通过回调暴露入口，
  Feature 之间不互相依赖。
- 测试：`SourcePackageStoreTest`（7）+ `SourceRepositoryTest`（10）+ `SourcesViewModelTest`（9），
  覆盖升级替换、运行时拒绝后旧版可用、元数据不可读不落盘、启停的加载与卸载、卸载清理、
  索引损坏不隐藏其它条目、恶意 id 不越界、失败保留用户输入、列表失败可重试；另有 5 项 Compose 测试（仅编译）。

已知限制（不是缺陷遗漏，是依赖顺序）：

- 真实脚本的元数据读取依赖 S1-08 的引擎实现，因此当前安装真实源会以
  “Comic sources are unavailable on this device.” 失败；`:app` 传入的 `UnavailableMetadataReader`
  会在 S1-08 被真实实现替换。
- 从远端 URL 抓取来源包未做，需要复用应用 HTTP 客户端。

验证记录：

```text
2026-09-20（编译级 + JVM 单测，按 AGENTS.md 第 7 节普通节点策略）
JAVA_HOME 临时指向本机 JDK 17（仅当前命令，不入库）
.\gradlew.bat --no-daemon --max-workers=2 :feature:sources:testDebugUnitTest :feature:sources:compileDebugAndroidTestKotlin :app:assembleDebug
结果：BUILD SUCCESSFUL
全量单元测试 116 项，failures=0 errors=0 skipped=0
  core:model 24 / data:source 16 / source:api 29 / feature:reader 26 / feature:sources 9 / source:network 6 / source:engine 5 / core:network 1
```

编译检查：

```powershell
.\gradlew.bat :app:assembleDebug
```

## 最近完成

### S1-01 稳定领域模型与 Source Core 协议 — DONE

依赖：S0-07。

已完成（2026-09-20）：

- `docs/adr/0007-source-protocol-compatibility.md`（Accepted）：划定 Stage 1 承诺兼容的上游协议子集
  （类基础字段、`init`、`explore`、`search`、`comic.loadInfo`、`comic.loadEp`），把 `category`、
  `account`、`favorites`、评论与社交互动明确排除在 Stage 1 之外；固定两套分页语义与三种筛选值形态。
- `docs/adr/0008-async-host-transport.md`（Accepted）：确认 AndroidX JavaScriptEngine 的 JS → Kotlin
  方向只有 MessagePort，参考设备缺失该能力时无法实现异步 Host API；否决重放式桥，决定 Stage 1
  引入自有引擎（QuickJS），并给出 spike 通过判据。
- `:core:model` 新增稳定领域模型：`Comic`、`ComicDetail`、`Chapter`、`PagedResult` / `PageCursor`、
  `SourceFilter`（select / multi-select / dropdown）与 `FilterValue`、`SourceCapability` /
  `SourceCapabilities`。
- `ChapterKey` 升级为 `ComicKey + RemoteChapterId`，`ComicKey` 现在贯穿章节标识；阅读器、示例装配
  与测试同步更新。
- 新增协议语义测试：`PagingTest`（6）、`SourceFilterTest`（8）、`ChapterKeyTest`（3）。
- 补齐 ADR-0007 的字段级确认：核对 `js_api.md` 后确认**章节列表与详情同源**
  （`ComicDetails.chapters` 是 `Map<chapterId, title>`，顺序由源决定），并确认 Host 网络 API 的
  请求体是 `ArrayBuffer`、源与宿主通过 `sendMessage({method})` 通信。
- `:core:model` 增加 `ExplorePage` / `ExploreKind` / `ExploreItem`（三种探索页形态）与
  `chaptersOf()`（把上游 map 形态转成有序章节列表）；`ComicDetail` 内嵌章节。
- `:source:api` 新增 `SourceCore` 契约（Explore、Search、Detail、Chapters、Pages）与 `SourceOutcome`，
  固定两项产品规则：**缺失能力返回 `UnsupportedCapability` 而不是源故障**、
  **源失败以值返回而不是异常**。
- `:source:api` 新增 `FakeSourceCore` 与 `SourceCoreTest`（9 项）作为契约语义的可执行定义；
  真机引擎实现落地后应复用同一组断言，而不是另写一套。
- 用 `venera-configs` 的 `manga_dex.js` 核对真实实现（ADR-0007 §4.3），修正了三处从文档看不出来的假设：
  **`chapters` 是分组嵌套结构且顶层混有非章节键**、**`search` 的 `options` 是与 `optionList` 下标
  对齐的数组**、**网络主入口是全局 `fetch` 而不是 `Network.get/post`**。
- `:core:model` 增加 `groupedChaptersOf()`（分组章节，保留组名与两层顺序）与
  `encodeFilterSelection()`（按声明顺序生成筛选数组）；新增 `ChaptersOfTest`（4）与筛选顺序测试（2）。
- `:source:api` 新增 `protocol` 包：`SourceProtocol` 把契约编码成上游调用
  （`loadInfo` / `loadEp` / `search.load` / `search.loadNext` / `explore.load`），
  `SourceProtocolParser` 把响应解析回领域模型。**探索页的页码基准由页面类型决定**
  （`multiPageComicList` 1-based、`mixed` 0-based、`multiPartPage` 传 null），选错会静默错一页。
- 新增 `SourcePage`（只有 `imageRef` 与序号）：真实源的页面只有 URL、没有尺寸，尺寸由图片管线解析；
  `ComicPage` 继续表示"尺寸已知"的阅读器契约，两者分工写在 KDoc 里。
- 协议兼容测试：`SourceProtocolTest`（8）覆盖调用形态，`SourceProtocolParserTest`（12）用真实源的响应
  形状覆盖分组章节与 marker 忽略、扁平章节、缺字段条目被丢弃、`{comics, maxPage}` 游标、`{images}`、
  三种探索页形态。

验证记录：

```text
2026-09-20（编译级 + JVM 单测，按 AGENTS.md 第 7 节普通节点策略）
JAVA_HOME 临时指向本机 JDK 17（仅当前命令，不入库）
.\gradlew.bat --no-daemon --max-workers=2 :core:model:testDebugUnitTest :source:api:testDebugUnitTest :app:assembleDebug
结果：BUILD SUCCESSFUL
全量单元测试 91 项，failures=0 errors=0 skipped=0
  core:model 24 / source:api 29 / feature:reader 26 / source:network 6 / source:engine 5 / core:network 1
```

转入后续任务（不属于 S1-01）：

- `SourceCore` 的引擎实现依赖 S1-08 引擎落地，落地后复用 `SourceCoreTest` 与协议测试的断言。
- `ComicKey` 在 Feature / Data 层的端到端使用，随 S1-02 建立 `:data:source`、S1-03 建立 `:data:comic` 落地。
- `loadThumbnails` 签名核对（S1-05 多页缩略图需要）。

## Stage 1 收尾：S1-05 / S1-06 / S1-07 — DONE

Stage 1 的三个剩余任务已全部交付。以下按任务记录做了什么、验证到什么程度、以及哪些明确没验证。

### S1-05 Coil 漫画图片管线 — DONE

依赖：S0-06、S1-01（均已完成）。

实际交付：

- 新建 `:core:image`，Coil **3.4.0**。`gradle/libs.versions.toml` 只引入 `coil-core` / `coil-compose` /
  `coil-test`，**不引 `coil-network-okhttp`**（网络走自建 Fetcher + `:core:network` 共享 OkHttp 连接池）。
- `ComicImageRequest`（URL / Method / Body / Header / Referer / sourceId / variant）、`ComicImageCacheKey`、
  `ComicImageKeyer`、`ComicImageFetcher`、`ComicImagePipeline` 与 `CoilComicImagePipeline`、`CoilPageImageSizer`。
- **缓存键覆盖确认**：不同 Cookie → 异键、不同 Authorization → 异键、只有 `User-Agent` 不同 → 同键、
  GET vs POST → 异键、POST body 不同 → 异键、同 URL 不同源 → 异键。`RESPONSE_AFFECTING_HEADERS` 之外的
  易变请求头不参与建键，避免缓存碎片化。
- `ImageSizeHeaderParser`：JPEG / PNG / WebP（VP8、VP8L、VP8X）/ GIF 的纯 Kotlin 头部解析，未知格式回退
  `BitmapFactory.inJustDecodeBounds`，因此这一段可以在 JVM 上测。
- **`:feature:reader` 的 `PageImageDecoder` / `PageTiling` 已用 `git mv` 迁到 `:core:image`**，包名改为
  `dev.veneranative.core.image.{decode,tiling}`，类型名与公开签名不变，两个测试类各 9 项保持不变。这解除了
  ADR-0004 §4 标记的"解码代码临时放在 feature"技术债。
- `PageProvider` / `ChapterContent` / `ImageSize` / `PageImageSizer` 下沉到 `:core:model`，使 `:data:comic`
  能提供 source-backed 实现而不越界依赖 feature。
- `:data:comic` 新增 `SourcePageProvider`：**单页尺寸解析失败时跳过该页，而不是让整章失败**——源给出失效
  URL、防盗链或过期 token 是常态，已由测试断言。

先更新了 ADR-0004（新增 §7 选型证据：为何锁 3.4.0 而不是最新的 3.6.3），再动代码。

### S1-06 Room 历史与阅读进度 — DONE

依赖：S1-01、S0-05（均已完成）。

实际交付：

- 新建 `:core:database`（Room 2.8.5）：`ReadingHistoryEntity` / `ReadingProgressEntity`、两个 DAO、
  `@Database(version = 1, exportSchema = true)` 的 `VeneraDatabase`、`VeneraDatabaseFactory`。
  **不依赖 `:core:model`**，主键用字符串列，值对象在 `:data:history` 转换。
- `core/database/schemas/dev.veneranative.core.database.VeneraDatabase/1.json` 已入库，作为 Migration 基线。
- 新建 `:data:history`：`HistoryRepository` 契约、`HistoryMappers`、`DefaultHistoryRepository`、
  `ProgressThrottlePolicy`、`ReadingProgressTracker`、假 DAO 与两个测试类。
- **节流保存**：默认窗口 2000 ms；`onPageChanged` 只记住最新值，`flush()` 立即落盘。时钟与 CoroutineScope
  注入，因此节流行为在 JVM 上可测，已覆盖"窗口内只写一次"、"超时后写入"、"flush 立即写"、
  "flush 两次只写一次"、"最新值不被旧值覆盖"等 8 项。
- `MainActivity.onStop()` 用 `NonCancellable` 调 `flush()`，这是节流能安全存在的前提：最后一次翻页必须落盘。
- 删除历史时同时清 `reading_progress`——否则删掉记录后重进，仍会被残留的恢复点拉回原章节。

### S1-07 核心闭环集成 — DONE

依赖：S1-02 至 S1-06（均已完成）。

实际交付：

- `:app` 装配换成真实实现：`SourcePageProvider(catalog, CoilPageImageSizer(pipeline))`，`FakePageProvider`
  与 `AssetFixturePageProvider` 不再参与主链路。
- **Cookie 与连接池同源**：`:app` 的 `SourceCookieImageAuth` 把 `:source:network` 的
  `PerSourceCookieJarRegistry` 接成 `ComicImageAuthProvider`，且用的是**同一个** OkHttp dispatcher 与
  **同一个** cookie registry 实例——源请求写下的 Cookie 正是它的图片主机后续索要的那个。这点错了不会报错，
  只会让每一页都 403。
- `LocalComicImageLoader` 由 `:app` 提供，feature 不 import Coil。
- 阅读器接入恢复与保存：`ReaderRoute` 增加 `startPageIndex` 与 `progressRecorder`；进入时按记录的页码恢复，
  翻页时经 tracker 节流上报。
- 来源错误的可恢复 UI 沿用既有形态：详情 feat 的失败/失效状态与阅读器的 `ReaderStatus.Failed` 重试，
  **未新增文案体系**。
- 新增 `tools/test-sources/demo_comic_source.js`：遵循 `SourceClassConvention` 的最小仓库内测试源，全部数据
  本地生成，不请求真实站点。

#### 端到端人工脚本（记录于本文件，未实机执行）

按既定约定"非必要不做实机测试"，以下脚本**尚未在设备上运行**，因此不标记为"已验证"。执行条件：

```
AGP 9.2.1 / JDK 17 / compileSdk 37，adb 连接的设备或模拟器（API ≥ 26）。
构建：sh gradlew :app:assembleDebug
安装：sh gradlew :app:installDebug
```

1. **安装测试源**：应用内 → 来源管理 → 安装本地脚本 → 选择 `tools/test-sources/demo_comic_source.js`。
   预期：列表出现 `Demo Comic Source`，版本 1.0.0。
2. **探索**：首页 → 探索。预期：翻到第 3 页仍能加载，`maxPage` 为 3（42 条 / 每页 20）。
3. **搜索**：搜索 `demo`。预期：返回 2 条；搜索 `miss`。预期：空结果，不进入错误态。
4. **详情与选章**：打开任一漫画 → 章节按 `Chapter 1/2/3` 顺序展示（脚本刻意顺序发布，应用层不得重排）→
   选择 `Chapter 2`。
5. **阅读器**：预期封面与正文页由图片管线加载不再是占位块；垂直连续与横向 LTR/RTL 均可翻页；
   底栏可在 `Sampled` 与 `Region` 两套解码策略间切换。
6. **进度恢复**：翻到第 3 页 → 返回首页 → 再次打开同一漫画的同一章节。预期：直接落在第 3 页。
7. **退出强刷**：翻页后立刻按 Home 键退到后台。预期：重新进入仍在刚才那一页（验证 `onStop` 的 `flush()`）。
8. **来源错误可恢复**：断网后进入详情，预期显示既有失败态并提供重试；恢复网络后重试成功。

#### 验证程度

- 已完成：`:app:assembleDebug` 通过；全仓库 `testDebugUnitTest` **250 项通过、0 失败**；
  `:core:database:compileDebugAndroidTestKotlin` 通过；`:feature:reader:compileDebugAndroidTestKotlin` 通过。
- **未实机执行**：上述 8 步人工脚本，以及 Source 之外的真机行为（进程回收、前后台切换、API 26 兼容性）。
- Header / Referer / Cookie / POST 图片目前只有 JVM 侧的"缓存键"与"请求构造"覆盖，端到端需在设备上或
  用 MockWebServer 验证。

## Stage 1 完成后的已知限制

| 限制 | 影响 | 何时处理 |
| --- | --- | --- |
| 图片缓存键不解析响应的 `Vary` | 真实源若大量依赖 `Vary`，可能复用错误 | 发现命中时回来改 `ComicImageCacheKey`，不要改调用方 |
| 图片的 Header/Referer/Cookie/POST 只有 JVM 侧覆盖 | 需要端到端才能确认真实链路 | 引入 MockWebServer 或首次实机验证时 |
| 阅读进度只在 App 进程退出前 flush，未区分"切后台"与"旋转/多窗口" | 旋转会触发一次多余但无害的落盘 | 需要严格区分时引入 `androidx.lifecycle:lifecycle-process` |
| Coil 锁 3.4.0，未用最新的 3.6.3 | 后者用 Kotlin 2.4 编译，本工具链 KGP 2.2.10 读不了 | 升级工具链时连带决策（会影响 quickjs-kt 的锁定） |
| `demo_comic_source.js` 的图片走 `file:///android_asset` | 与真实 http 图片链路不完全等价 | 端到端验证时把 image URL 换成本地 http 服务 |

## 已知风险与待确认

| 项目 | 状态 | 解除条件 |
| --- | --- | --- |
| 异步 Host API 在真机缺少 MessagePort | V2337A 上 `messagePorts=false`，S0-03 的桥按能力跳过 | Stage 1 决定异步桥实现方式时处理 |
| 二进制通道 | 已确认：只能走 `provideConsumeArrayBuffer` | — |
| 前后台切换、进程回收、API 26 可用性 | 未验证 | Stage 1 集成 Runtime 时补测 |
| WebView 引擎在参考设备上无法提供异步 Host API | 已定案转向自有引擎；JVM spike 已证明 JS→宿主异步可用（ADR-0008 §8） | 剩余判据（取消/超时映射、二进制、ABI 与体积）在引擎实现阶段完成 |
| 图片缓存键不解析响应的 `Vary` | 明确接受的已知限制，键只覆盖请求侧允许列表 | 发现真实源依赖 `Vary` 导致复用错误时，回来改 `ComicImageCacheKey`，不要改调用方 |
| KSP 与 AGP 9 内置 Kotlin 的共存 | 曾因 KSP 通过 `kotlin.sourceSets` 注册生成目录而失败（google/ksp#2729）；已用 KSP 2.3.10 解决，KGP 仍为内置的 2.2.10，未加任何 flag | 升级 AGP/KGP 时重新冒烟 `:core:database` |
| 引擎绑定为社区项目（Apache-2.0） | 版本已锁定 1.0.5；升级受 Kotlin 元数据兼容约束 | 升级前必须跑契约测试；若方案失效则自行交叉编译 QuickJS，契约不变 |
| 真实源依赖全局 `fetch`，而现有 Host API 只有 `Network.*` | 已解决：兼容层提供 `fetch`（含 `ok`/`status`/`json()`/`text()`）与 `Network.*` | 若在真实源上发现 `fetch` 语义缺口，按 ADR-0008 §9 的规则补实现并加测试 |
| 来源 id 冲突（上游存在两个源共用 `copy_manga`） | 已决策：`sourceId` 取脚本自报的 `key`，同 id 的第二次安装**替换**第一次，不共存 | 若产品上需要共存，必须先改 `SourceId` 语义并同步 `ComicKey` |
| **绑定无法中断引擎内的死循环脚本** | 已实测（ADR-0008 §10）：挂起型取消可用，计算型不可中断；超时后靠丢弃并重建引擎保证源仍可用 | 死循环脚本会占一个 CPU 核直到引擎被丢弃；升级绑定（需工具链 Kotlin 2.4）或自行编译 QuickJS 才能根治；在此之前不得把"超时"当作"脚本已停止" |
| 兼容层有意不提供的全局（`URL`、`URLSearchParams`、`TextEncoder`/`TextDecoder`、`atob`/`btoa`、`setTimeout`、`crypto`、`structuredClone`、`Intl`） | 按真实源实测（0 次使用）决定，避免自写实现静默误解析 | 遇到需要它们的源时补实现 + 测试，并更新 ADR-0008 §9 |
| `Network.*` 的非 GET/POST 方法、二进制请求体 | Host API 目前只放行 `http.request` 的 GET/POST，且请求体是文本 | 需要时扩展 `http.request`；二进制需增加字节通道 |
| `APP.version` 仍是占位值 `"0"` | `APP.locale` 已按设备区域传入，`version` 未接 | 与 `minAppVersion` 校验一起在协议适配层接入 |
| `:feature:sources` 直接依赖 `:data:source` | 有意的边界取舍，已记录在 ARCHITECTURE §3 | 出现第二个数据实现或引入 DI 时把契约拆出去 |
| `loadThumbnails` 签名仍未确认 | 核对过的源未实现该方法 | S1-05 多页缩略图开始前，再找使用它的源核对 |
| `SensitiveDataRedactor` 无生产调用点 | 错误路径不拼接敏感值且有测试断言；脱敏工具本身有 JVM 测试 | 日志功能落地时必须接入，否则删除 |
| 收藏开关只写默认分组，详情页不做文件夹选择 | 文件夹管理属于书架页；一个只看得到一本漫画的屏幕没有可选项 | 需要「加入时选分组」时再在详情页加一次选择 |
| `favorite_entry.lastReadAt` 无写入方 | 「最近阅读」排序恒为空值排最后 | 阅读进度上报时同步写收藏条目 |
| `:feature:library` 的 instrumentation 与 Compose 测试只编译未执行 | 迁移的数据保留断言尚未在真机跑过 | 关键节点执行 `connectedDebugAndroidTest` |
| `:feature:details` 的封面是占位块 | **已解决**：S1-05 的 `ComicImage` 已接进封面槽位；但 `LocalComicImageLoader` 仍由 `:app` 提供，未提供前渲染占位（不回退成空白） | S1-07 集成时由 `:app` 装配 ImageLoader |
| 阅读器仍无 source-backed `PageProvider` | **已解决契约与实现**：`PageProvider` / `ChapterContent` 已下沉 `:core:model`，`SourcePageProvider` 已在 `:data:comic` 落地并测试 | S1-07 集成时由 `:app` 装配进 `ReaderRoute` |
| 本机 `JAVA_HOME` 指向失效的 temurin21 路径 | 构建前需临时指定 JDK 17；本机可用的是 `C:\Users\11196859\.jdks\jbr-17.0.14` | 用户修复环境变量，或继续按命令临时指定 |
| 大图策略的设备侧验证（解码耗时 / PSS / 掉帧 / 手势冲突） | 规则已由 JVM 预算测试保证，设备数据缺失，未验证 | Stage 0 退出门禁；需要时按 S0-06 记录的命令采集 |
| 解码代码暂驻 `:feature:reader` | **已解除**：S1-05 已迁入 `:core:image` 的 `tiling` / `decode` 子包，类型名与公开签名不变 | — |
| Coil 仍未引入 | **已引入 3.4.0**（只引 `coil-core` / `coil-compose` / `coil-test`，网络走共享 OkHttp），ADR-0004 §7 已记录选型证据与职责边界 | 升级到 3.5.0+ 需先把 KGP 提到 2.4，单独决策 |
| 中等缩放区间允许最多约 1.41 倍 GPU 放大 | 内存上界的代价，观感未验证 | 设备验证时确认是否可接受 |
| 本机 `JAVA_HOME` 指向失效的 temurin21 路径 | 构建前需临时指向 JDK 17（本机可用的是 `C:\Users\11196859\.jdks\jbr-17.0.14`） | 用户修复环境变量，或继续按命令临时指定 |
| QuickJS fallback 是否必要 | S0-02 暂不引入 | 与 MessagePort 缺失问题一并决策 |
| GPL-3.0 衍生边界和最终许可证 | 待确认 | 复用上游实现前完成许可证 ADR |
| 最终 applicationId | 待用户确认 | 发布配置开始前确认 |
| Venera 名称与正式视觉标识 | 待确认 | 首个公开测试版前完成品牌审查 |

## 接管检查表

新 Agent 开始工作时确认：

- [ ] 已阅读 `AGENTS.md`。
- [ ] 已确认 Git 工作区状态。
- [ ] 已定位“当前唯一下一任务”。
- [ ] 已阅读对应源码和任务验收项。
- [ ] 已确认没有依赖未完成。
- [ ] 已按 `AGENTS.md` 确认验证级别；普通任务只做编译检查，关键节点才做完整验证。
- [ ] 完成后会更新本文件及实施计划状态。
