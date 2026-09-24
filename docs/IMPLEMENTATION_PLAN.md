# Venera Native 可执行实施计划

## 1. 文档用途

本文把 `PROJECT_PLAN.md` 的产品与架构蓝图拆成可连续交付的工程任务。它回答：

- 下一步具体修改哪些模块；
- 任务之间有什么依赖；
- 每个任务必须产出什么；
- 如何判断任务真正完成；
- 新会话应把状态推进到哪里。

实时进度以 `STATUS.md` 为准。任务编号一旦进入实现，不应重命名；范围变化通过备注或 ADR 记录。

## 2. 状态和执行规则

状态仅使用 `TODO`、`IN_PROGRESS`、`BLOCKED`、`DONE`。

- 同一时刻原则上只有一个任务为 `IN_PROGRESS`。
- 任务按依赖关系执行，不按“看起来容易”跳过基础能力。
- 每个任务应形成可以独立编译、独立提交的纵向切片。
- 发现计划不合理时先更新计划和原因，再改变实现方向。
- 普通开发节点以受影响代码编译通过为验证底线；完整 Lint、测试、实机和压力验证只在 Stage 退出、发布、专项验证任务或用户明确要求时执行。
- 测试代码和文档仍属于交付物，但普通节点只要求测试源码编译，不默认执行完整测试套件。

### PL-01 全局计划复核 — DONE（2026-09-25）

范围：审核 Stage 0–4 的目标、依赖、执行状态和最终 90% 对齐口径。调整记录见 `docs/reviews/plan-review-2026-09-25.md`。这是用户指定的计划审查任务，不占用当前唯一工程任务 S2-07C；纯文档切片不涉及编译，验收为计划/状态/产品文档一致与差异检查通过。

## 3. 总体里程碑

| 阶段 | 目标 | 退出条件 | 状态 |
| --- | --- | --- | --- |
| Stage 0 | 验证 JS 漫画源和阅读器两项最高风险技术 | JavaScriptEngine 与大图方案形成有证据的 ADR | DONE（2026-09-20 退出） |
| Stage 1 | 打通网络漫画核心阅读闭环 | 测试源可完成搜索、详情、选章、阅读和恢复进度 | DONE（Q00–Q13 整改及阶段复验通过） |
| Stage 2 | 完成书架、下载和本地阅读 | 离线可浏览书架并阅读下载或本地漫画 | IN_PROGRESS（S2-01–S2-06 DONE，当前 S2-07） |
| Stage 3 | 冻结上游对照基线并补齐来源扩展能力 | 逐项对照清单就绪；Core/Extended 协议测试通过，Advanced 有支持矩阵 | TODO |
| Stage 4 | 同步、自适应、产品体验对齐与发布 | RC 通过迁移、压力、无障碍与发布检查；全 App 功能、页面逻辑、视觉设计对照基线分别达到 ≥90% | TODO |

## 4. Stage 0：技术验证

### S0-00 产品与总体规划 — DONE

交付物：

- 项目命名、定位、功能范围与技术方向。
- 长期模块图、功能规划、质量策略。
- GitHub 公开仓库。

### S0-01 多模块工程基线 — DONE

交付物：

- App、Core、Feature、Source 的首批模块。
- Version Catalog 和 Convention Plugins。
- Compose 占位入口、基础领域模型与首个测试。
- Gradle Wrapper、Lint 和 Debug 构建闭环。

验收：`lintDebug`、`:core:model:testDebugUnitTest`、`:app:assembleDebug` 通过。

### S0-02 JavaScript Runtime 最小执行闭环 — DONE

依赖：S0-01。

涉及模块：

- `:source:api`
- `:source:engine`
- 必要时新增 `:source:testing`，但只有测试代码确实跨模块复用时才创建

子任务：

1. S0-02A：确定 Runtime 线程模型、生命周期和公开契约。
2. S0-02B：实现沙箱创建、fixture 加载和类型化函数调用。
3. S0-02C：实现 JSON 参数/结果、错误映射。
4. S0-02D：实现调用 ID、超时、取消、卸载和关闭。
5. S0-02E：验证多来源隔离及引擎不支持路径。
6. S0-02F：补齐 unit/instrumentation tests 和 ADR。

必须交付：

- 与 AndroidX 具体类型隔离的 `SourceScriptRuntime`。
- 一个不联网、不含第三方内容的固定 JavaScript fixture。
- 稳定错误分类：能力不可用、包无效、语法错误、运行错误、超时、取消、未加载、内部错误。
- Runtime 生命周期测试。
- `docs/adr/0002-javascript-runtime.md`。

验收：详见 `STATUS.md` 的当前任务章节。

### S0-03 异步 Host API 与受控网络桥 PoC — DONE

依赖：S0-02。

目标：证明脚本可以发起一个经过允许列表的异步 Host 调用，Kotlin 完成请求后把结果返回脚本，同时保持取消和错误语义。

计划模块：

- 新建 `:core:network`：只放 App 级网络基础设施和通用错误映射。
- 新建 `:source:network`：只放脚本请求模型、来源隔离 Cookie 与策略。
- `:source:api`：Host 调用契约。
- `:source:engine`：消息桥和调用调度。

必须交付：

- 结构化 `SourceHttpRequest` / `SourceHttpResponse`。
- 请求方法、Header、文本 Body、状态码和编码的最小支持。
- MockWebServer 测试，不访问真实网站。
- 脚本调用取消能取消底层网络 Call。
- Header 与日志脱敏。
- 每来源并发限制的最小实现或明确的 S0-04 接口。

验收场景：

- GET 和 POST 往返。
- HTTP 非 2xx 仍保留状态和响应，网络故障映射为错误。
- 超时、取消、超限不会泄漏 Call 或沙箱。
- 两个来源的 Cookie 不互相可见。
- 日志不出现测试 Token 明文。

### S0-04 Runtime 限制、二进制和压力验证 — DONE

依赖：S0-03。

目标：量化 JavaScriptEngine 方案的边界，决定是否可进入 Stage 1。

实际执行：按“决策所需最小验证”收尾，只保留影响架构的引擎结论，不追求边界数字收敛。
实机数据表与结论见 `docs/STATUS.md`；压力测量 harness 取得结论后已删除。
未验证项（前后台切换、进程回收、API 26 可用性、ADR-0002 更新）已转入已知风险。

必须验证：

- 大 JSON 参数与结果的实测上限。
- 图片或二进制响应跨桥接传递策略。
- 单调用超时、队列上限、每来源并发和全局并发。
- 沙箱异常终止后的恢复。
- 连续加载/卸载来源后的资源释放。
- 前后台切换和进程回收后的行为。
- API 26 至当前目标版本的可用性策略。

产出：

- 自动化压力测试或可重复 benchmark 脚本。
- 测试数据表，而不是“感觉可用”。
- JavaScriptEngine 采用、限制使用或增加 QuickJS fallback 的初步结论。
- 更新 ADR-0002。

### S0-05 Compose 阅读器基础原型 — DONE

依赖：S0-01。可在 S0-02 至 S0-04 完成后开始，避免并行产生多个长期分支。

实际执行：新增 `:feature:reader` 与 `ComicPage` 页面描述符，交付纵向连续阅读、横向 LTR/RTL
翻页、页码与失败重试、邻近预取、Fake PageProvider 与 Compose 测试。真实图片解码管线不在本
任务范围，由 S0-06 决定。详见 `docs/STATUS.md`。

计划新增模块：

- `:feature:reader`
- 必要的 `:core:ui`

必须交付：

- 统一 `ComicPage` 描述符的最小版本。
- 纵向连续阅读。
- 横向 LTR/RTL 翻页的最小切换。
- 页码、当前章节、加载和错误状态。
- 当前页附近的有限预取。
- 不在 Compose State 中持有 Bitmap。
- Fake PageProvider 与 Compose 测试。

不在范围：

- 下载、本地压缩包、真实漫画源和持久化进度。
- 完整手势设置和正式视觉设计。

### S0-06 超长图、缩放和内存验证 — DONE

依赖：S0-05。

实际执行：把“大图能否用”从观感问题转成可验证的算术问题。交付 ADR-0004（Accepted，默认区域/
分块解码，设备实测补齐前不引入 Coil）、确定性 fixture 生成器、`PageImageDecoder` 的 `Sampled`
与 `Region` 两种实现、有界位图缓存、阅读器缩放与分块渲染，以及 JVM 侧的几何与内存预算测试
（26 项全部通过）。

设备侧验证（解码耗时 / PSS / 掉帧 / 手势冲突）按用户决定“非必要不做实机测试”推迟，
改列为 Stage 0 退出门禁与已知风险，探针 `LargeImageProbeTest` 保留在仓库等待需要时执行。
详见 `docs/STATUS.md`。

必须验证：

- 常见图片、超长条图、超高分辨率图。
- 快速滚动、连续翻页、旋转、后台恢复。
- 双指缩放与滚动手势冲突。
- 不同预取窗口的峰值内存与卡顿。
- Coil 常规解码是否足够；何时切换子采样或分块方案。

产出：

- 固定生成的测试图片或生成脚本，禁止提交版权内容。
- 测试设备/模拟器配置、内存数据和复现步骤。
- `docs/adr/0004-large-image-strategy.md`。

### S0-07 Stage 0 决策收敛 — DONE

依赖：S0-04、S0-06（均已完成）。

实际执行（2026-09-20）：

- ADR-0002 增补 S0-04 实测修订，QuickJS fallback 条件逐条对照实测结果。
- ADR-0003 把 MessagePort 从硬性能力要求降级为可选通道，替代传输留给 Stage 1 的独立 ADR。
- ADR-0004 复核通过（Accepted），解析结论未被后续证据推翻。
- 删除零引用模块与代码：`:core:common`、`:core:navigation`、`JavaScriptEngineSupport`、
  `ReaderZoomState.reset()`，并移除 app 未使用的 `:core:navigation` 依赖。
- 新增 5 项 JVM 测试覆盖每来源 Cookie 隔离与清理。
- 文档与代码对齐：模块表、命名漂移、已删除测试类的残留命令。
- 完整验证：`lintDebug testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL，
  全量单元测试 39 项、0 失败。

Stage 0 退出标准对照：

- JS Engine 可用性、限制、fallback 条件清晰：**满足**（ADR-0002 / ADR-0003）。
- Reader 图片方案有实测证据：**部分满足**。解析证据由 `PageDecodeBudgetTest` 保证；
  设备侧 PSS、耗时与掉帧按项目决定推迟，未验证，记录在 `docs/STATUS.md` 已知风险。
- Stage 1 不再依赖未回答的关键技术假设：**基本满足**。唯一保留项是异步 Host 桥的替代传输，
  必须在 S1-01 实现 Core 协议前用独立 ADR 定案。

## 5. Stage 1：核心阅读闭环

Stage 1 使用仓库内测试源作为端到端基线，不以真实商业站点稳定性作为验收条件。

### Stage 0 结论对 Stage 1 的修改

1. **协议先行、传输后置**：ADR-0008 已把协议/控制面与传输解耦，并把引擎切到自有实现（QuickJS）；
   S1-01 与 S1-02 不依赖 Host API，新增的 S1-08 必须在 S1-03 之前完成。
2. **图片管线归属确定**：S1-05 建立 `:core:image` 时迁移 `:feature:reader` 的 `PageImageDecoder`
   与 `PageTiling`；Coil 只承担网络获取与缓存，超长图解码仍由自有解码器负责。
3. **导航契约后移**：`:core:navigation` 已在 S0-07 删除，在 S1-03 首次需要类型安全 Route 时重建。
4. **`:core:common` 暂不存在**：只有出现统一的 Result/错误聚合需求时才按模块准则创建。
5. **不再安排引擎边界收敛任务**：Stage 0 已把引擎能力、二进制通道、终止恢复与吞吐量级写成结论；
   Stage 1 只在出现设备相关症状时按 `docs/STATUS.md` 的命令补测。

### S1-01 稳定领域模型与 Source Core 协议 — DONE

依赖：S0-07。

交付物：

- Comic、Chapter、Page、分页结果、筛选项和来源能力模型。
- Explore、Search、Detail、Chapters、Pages 五个 Core 能力。
- 序列化与协议兼容测试。
- `ComicKey = SourceId + RemoteComicId` 全链路使用。

实际执行（2026-09-20）：

- 新增 ADR-0007（源协议兼容范围，含 `js_api.md` 字段级核对）与 ADR-0008（异步 Host 传输与引擎选择），
  两项都是本任务的前置。
- `:core:model` 落地 `Comic`、`ComicDetail`（内嵌章节）、`Chapter`、`PagedResult`/`PageCursor`、
  `ExplorePage`/`ExploreKind`/`ExploreItem`、`SourceFilter` 与 `FilterValue`、`SourceCapability`/
  `SourceCapabilities`；`ChapterKey` 升级为 `ComicKey + RemoteChapterId`。
- `:source:api` 落地 `SourceCore` 五个能力契约与 `SourceOutcome`，并新增 `FakeSourceCore` +
  `SourceCoreTest` 把契约语义（缺失能力可降级、失败以值返回、分页终止、章节顺序）变成可执行断言。
- 测试：模型协议语义 18 项 + 契约 9 项，全量单测 65 项通过。
- 协议编解码：`SourceProtocol`（契约 → `loadInfo`/`loadEp`/`search.load`/`explore.load`）与
  `SourceProtocolParser`（响应 → 领域模型），探索页页码基准由页面类型决定；新增 `SourcePage`
  区分"源给的是 URL"与"阅读器需要尺寸"。
- 协议兼容测试：编码 8 项 + 解析 12 项，覆盖分组章节与 marker 忽略、缺字段条目、`{comics, maxPage}`
  游标、`{images}` 与三种探索页形态；全量单测 91 项通过。
- 转入后续：`SourceCore` 引擎实现依赖 S1-08；`ComicKey` 的 Feature/Data 端到端使用随 S1-02/S1-03 落地。

### S1-02 来源包安装与管理 — DONE

依赖：S1-01。

计划模块：

- `:data:source`
- `:feature:sources`

交付物：

- 本地 URL/文件安装测试源。
- 元数据验证、SHA-256、版本、启停、卸载。
- 安装失败不污染已有可运行版本。
- 来源列表的加载、空、错误和成功状态。

实际执行（2026-09-20，数据层完成）：

- `:data:source` 落地 `SourceRepository` + `SourcePackageStore`（脚本文件 + JSON 索引，列表页无需执行脚本）；
  安装顺序为“运行时先接受、存储后写入”，写入失败则回滚运行时，运行时拒绝则存储不动。
- `SourceMetadataReader` 契约放在 `:source:api`，接收脚本文本而非已安装包（避开包需要 id/版本、
  而 id/版本来自元数据的循环）；引擎实现随 S1-08。
- 安装失败改为领域错误 `SourceInstallError`（位置不可读 / 元数据非法 / 引擎不可用 / 被拒绝 / 存储失败），
  UI 文案由 Feature 映射，下层的诊断字符串不再直接展示。
- `:feature:sources` 落地：`SourcesScreen` / `SourcesViewModel` / `SourcesRoute`，四种状态齐备，
  含卸载确认对话框；`:app` 用 `AppScreen` 枚举装配三个目的地，Home 通过回调暴露入口（Feature 之间不互相依赖）。
- 测试 25 项（存储 7 + 仓库 10 + ViewModel 9）与 5 项 Compose 测试（仅编译）；全量单测 116 项通过。
- 已知限制：真实脚本的元数据读取依赖 S1-08 的引擎实现，因此当前安装真实源会以
  “Comic sources are unavailable on this device.” 失败（`:app` 传入的 `UnavailableMetadataReader`）。
- 未做：从远端 URL 抓取来源包（与来源仓库客户端一起做）。

### S1-08 自有引擎（QuickJS）落地 — DONE（Stage 1 文本 Host API 范围）

依赖：S1-02。**阻塞 S1-03**。

背景：ADR-0008 已确认 WebView 系引擎在没有 MessagePort 的设备上无法提供异步 Host API，
而参考设备正是这种情况。

实际执行（2026-09-20，选型与 spike 完成）：

- 绑定选型定案为 `io.github.dokar3:quickjs-kt:1.0.5`（Apache-2.0），锁定原因与 16KB 页对齐
  证据见 ADR-0008 §7。
- `QuickJsBridgeSpikeTest` 在 JVM 上验证了脚本 `await` 宿主 suspend 调用、宿主异常传递与
  `Promise.all` 并发；实测出"必须脚本模式 + 顶层 await"的调用约束并写进 ADR-0008 §8。
- 引擎模块现在可以在 JVM 上回归（`testImplementation(quickjs-kt-jvm)`）。
- 契约实现已落地：`QuickJsRuntime`（每源一个引擎实例、取消与超时映射、自行计数结果大小）与
  `QuickJsMetadataReader`（一次性引擎读取 key/name/version，替换装配层的占位实现）。
- 兼容层范围按真实源调用点确定（ADR-0008 §9），未提供的全局逐条记录而不是猜测补齐。
- Release 构建已通过；Debug 19 MiB、未签名 Release 4.9 MiB，包含 arm64-v8a、armeabi-v7a、x86、
  x86_64 四个 ABI。JVM 已覆盖取消、超时和超时后恢复。
- 不宣称完成：真机取消/CPU 死循环终止、脚本二进制 Host 通道、设备手势与进程恢复。二进制协议归入
  Stage 3；真机项保留为明确延期风险。WebView 兼容实现随二进制协议决策后删除。

交付物：

- `:source:engine` 的自有引擎实现，满足 `:source:api` 契约（引擎实现由装配层选择）。
- 仓库内测试源（含 `async`/`await` 网络调用）跑通 Explore、Search、Detail、Chapters、Pages。
- 按 ADR-0008 第 3 节判据产出的数据：调用取消、调用超时、二进制通道、ABI 与体积、许可证登记。
- `THIRD_PARTY_NOTICES.md` 已登记直接引擎许可证；完整依赖报告和项目许可证仍是发布门禁。

### S1-03 探索与搜索纵向切片 — DONE

依赖：S1-01、S1-02、S1-08。

实际执行：先补上了三个前置——自有引擎（S1-08）、上游源加载约定（ADR-0007 §4.4）、
`SourceCore` 的引擎实现（新增 `:source:core`）——然后落地本任务的模块。

新增模块：`:source:core`、`:data:comic`、`:core:navigation`、`:feature:explore`、`:feature:search`。

交付物：

- 单源探索与搜索，页码与 token 两种分页都由**源声明**决定（`load` 优先于 `loadNext`）。
- Paging 3 适配：`PageKey.Start` / `At(cursor)`，仅向前分页，领域错误经 `SourceLoadException` 穿透。
- 单源失败、重试（在列表上重试）与"能力不可用"作为产品状态而非源故障。
- 类型安全 Route：`AppRoute.ComicDetails(ComicKey)`，可编码进 `rememberSaveable`。

已知缺口：详情屏本身（S1-04，当前为标注清楚的占位）、筛选器的 UI（编码链路已完成）、
探索页筛选（`ExplorePage` 尚无该字段）。详见 `docs/STATUS.md`。

### S1-04 漫画详情与章节 — DONE

依赖：S1-03（已完成）。

新增模块：`:feature:details`（改动模块：`:data:comic`、`:app`、`settings.gradle.kts`）。

交付物：

- 基础元数据、封面槽位、简介和章节列表。
- 分组、排序与刷新。
- 章节选择生成统一 PageProvider。
- 加载、空、部分失败和来源失效状态。

实际执行（2026-09-20）：

- **详情与章节一次取回**：上游把元数据与章节放在同一个 `loadInfo` 响应里，因此 `ComicCatalog` 只新增
  `detail(comicKey)`（不重复暴露 `chapters`），刷新详情即刷新章节。另有 `enabledSource(sourceId)`
  用于在请求之前区分"源已不在"与"源答不出来"。
- **章节列表按源声明的样子渲染**：支持单列与带组名分区两种形状；显示顺序提供「源顺序 / 倒序」，
  但**不按标题或序号重排**（`Chapter.index` 是源顺序里的位置，部分源按新→旧发布）。
- **四种状态**：Loading / Ready / Failed（可重试）/ SourceUnavailable（只能返回）；源返回空章节是
  部分结果而非失败。失败文案在 feature 内穷举映射 `SourceRuntimeError` 全部分支，兜底为通用文案，
  不泄漏下层诊断文本。
- **边界**：详情屏只把选中的 `ChapterKey` 交给装配层，不依赖 `:feature:reader`；`:app` 接到 Reader 路由，
  并删除了原 `ComicDetailsPlaceholder`。
- 测试：新增 13 项（`DetailsViewModelTest` 8 + `DetailsChaptersTest` 5），`ComicCatalogTest` 新增 4 项；
  全量单测 208 项 0 失败。

范围调整（交付物第 3 项）：**"章节选择生成统一 PageProvider" 只完成了契约侧那一半**。`ComicPage` 要求
真实的 `widthPx`/`heightPx`，而源只给 URL（`SourcePage` 只有 `imageRef`），source-backed `PageProvider`
必须等图片管线解析尺寸，因此该项随 S1-05 完成；`:app` 目前仍用 fixture/占位 Provider。封面同理：
槽位已按最终尺寸就位，像素渲染等 S1-05。两项缺口已记入 `docs/STATUS.md`。

### S1-05 Coil 漫画图片管线 — DONE

依赖：S0-06、S1-01。

新增模块：`:core:image`（改动模块：`:core:model`、`:data:comic`、`:feature:details`、`:feature:reader`、`:app`、构建配置）。

交付物：

- Coil 3、共享 OkHttp 连接池。
- `ComicImageRequest`、自定义 Fetcher 和稳定 Cache Key。
- Header、Referer、Cookie、POST 图片的测试。
- 鉴权不同的请求不会错误复用缓存。

实际执行（2026-09-21）：

- **Coil 锁 3.4.0**，只引 `coil-core` / `coil-compose` / `coil-test`，**不引 `coil-network-okhttp`**：
  网络由 `:core:image` 自建 Fetcher 走 `:core:network` 的共享 OkHttp，连接池、Dispatcher 与超时
  策略和全应用一致。3.5.0+ 用 Kotlin 2.4 编译，本项目 KGP 2.2.10 读不了（ADR-0004 §7.2）。
- **`:core:image` 建立 + 解码代码迁移**：`PageTiling` / `PageImageDecoder` 家族与两个 JVM 测试
  用 `git mv` 从 `:feature:reader` 迁入（包名改为 `dev.veneranative.core.image` 的 `tiling` /
  `decode` 子包，类型名与签名不变），ADR-0004 记录的架构债务解除。
- **缓存键**：URL + Method + Body 摘要 + 响应相关 Header 规范化集合 + 来源分区；Cookie 由
  `ComicImageAuthProvider` 在算键与取图时同源解析，不存进请求模型。已登记已知限制：不解析响应
  的 `Vary`。
- **尺寸解析**：JPEG / PNG / WebP / GIF 走纯 Kotlin 头部解析（JVM 可测），未知格式退回
  `BitmapFactory.inJustDecodeBounds`；单页解析失败**跳过该页**而不是让整章失败。
- **契约下沉**：`PageProvider` / `ChapterContent` / `ImageSize` / `PageImageSizer` 进 `:core:model`
  （`:core:model` 仍不碰 Android / Compose / 网络 / 数据库），`SourcePageProvider` 落在 `:data:comic`；
  详情页封面换成 `ComicImage`。
- **构建基础**：新增 `venera.android.room.library` 约定插件；KSP 升到 2.3.10 才与 AGP 9 内置
  Kotlin 共存（KGP 保持 2.2.10，未加任何 flag），`AndroidRoomLibraryConventionPlugin` 已冒烟验证。
- 测试：`ComicImageCacheKeyTest` 10 项、`ImageSizeHeaderParserTest` 9 项、
  `SourcePageProviderTest` 5 项；迁移过来的 `PageTilingTest` 与 `PageDecodeBudgetTest` 各 9 项不变。
  验证：`:app:assembleDebug` + 相关模块 `testDebugUnitTest` 全绿。

未完成（留给 S1-07 集成，不提前实现）：

- `:app` 装配 `ImageLoader` / `ComicImageAuthProvider`（Cookie 适配器）与 `SourcePageProvider`，
  在此之前封面与阅读器仍走占位路径。
- Header / Referer / Cookie / POST 的**端到端**验证（需设备或 MockWebServer），当前只有 JVM 侧的
  键与请求构造覆盖。

### S1-06 Room 历史与阅读进度 — DONE

依赖：S1-01、S0-05。

计划模块：

- `:core:database`
- `:data:history`

交付物：

- ReadingHistory、ReadingProgress Entity/DAO。
- Schema 导出、Migration 基线和 Repository Test。
- 进度节流保存，退出或进入后台强制落盘。
- 恢复章节和页码。

### S1-07 核心闭环集成 — DONE

依赖：S1-02 至 S1-06。

端到端验收：

1. 安装仓库内测试源。
2. 探索或搜索漫画。
3. 打开详情并选择章节。
4. 使用真实图片请求模型进入阅读器。
5. 退出后重新进入并恢复进度。
6. 来源错误有可恢复 UI。
7. 关键流程有集成测试或清晰可重复的人工脚本。

## 6. Stage 2：书架与离线能力

| ID | 任务 | 关键交付物 | 前置 | 状态 |
| --- | --- | --- | --- | --- |
| S2-01 | 本地收藏与书架 | 收藏夹、排序、更新标记、Room 单一事实来源 | S1-07 | DONE |
| S2-02 | 下载领域与持久队列 | 页级任务、暂停/继续/取消、恢复扫描 | S2-01 | DONE |
| S2-03 | Android 后台下载执行 | UIDT/Foreground Worker 策略、通知、约束 | S2-02 | DONE |
| S2-04 | 离线阅读整合 | 下载内容脱离来源仍可阅读 | S2-02 | DONE |
| S2-05 | SAF 本地目录导入 | 权限持久化、自然排序、封面识别 | S1-07 | DONE |
| S2-06 | CBZ/ZIP 与 7z 系列 | 索引缓存、ArchiveEntry 页面、错误恢复 | S2-05 | DONE |
| S2-07 | Stage 2 集成验收 | 从详情发起并控制下载；飞行模式下书架、下载、本地目录、阅读和进度闭环 | S2-01 至 S2-06 | IN_PROGRESS |

S2-07 当前切片与验收：

- **S2-07A — 统一章节身份与本地阅读：DONE。** `AppRoute.Reader` / `PageProvider` / Reader 使用 `ChapterRef`；SAF 目录与归档章节都可选入现有 Reader；进度写入 `@local` 历史命名空间。验收：远端旧路由 round-trip 兼容、本地路由 round-trip、本地页不调用来源、历史恢复命中同一 local chapter。
- **S2-07B — 下载用户流程：DONE。** 详情可排入章节下载并启动唯一 WorkManager；Library Downloads 显示页进度并提供暂停、继续、重试、移除。验收：`:feature:details:testDebugUnitTest :feature:library:testDebugUnitTest :app:assembleDebug` 通过。
- **S2-07C — Stage 2 质量复验：IN_PROGRESS。** 审查记录：`docs/reviews/stage-02-review.md`。C14–C24 修复本轮审查发现，C25 总回归 439 项 JVM 测试、Debug/Release 构建和 Release Lint Vital 通过；此前复验修复 4 处代码级 Lint 问题，并更新 Core、JavaScriptEngine、Navigation3、XZ、org.json。C12 已升级 AGP 9.3.1 / Gradle 9.5.0 / KGP 2.4.20 / KSP 2.3.12，并通过 Coil 3.6.3 与 QuickJS 1.0.15 相关回归；完整 Lint 只剩 WorkManager runtime/testing 两项版本提示（2.12.0 测试工件未同步到 Aliyun）。设备项由 S2-07D 记录并等待用户确认，不得把未执行的设备闭环写成通过或将 Stage 标为 DONE。
- **S2-07C1 — Room migration 真机断言：DONE。** 真机运行暴露表名断言将 `room_master_table` 误作应用 schema；仅过滤该 Room 内部表后，`:core:database:connectedDebugAndroidTest` 25 项通过。`:data:download:connectedDebugAndroidTest` 同设备 4 项通过。修复与证据记入 Stage 2 审查记录。
- **S2-07C2 — 删除无效通知 API 兼容分支：DONE。** `:data:download:lintDebug` 首次暴露 `ObsoleteSdkInt`：项目 minSdk 为 26，而下载通知 channel 要求 API 26，低于 O 的检查不可达。移除该无效分支后，`:data:download:lintDebug :app:assembleDebug` 均通过。
- **S2-07C3 — 修正详情入口 Modifier 参数顺序：DONE。** 全仓 `lintDebug` 随后暴露 `feature/details/DetailsRoute.kt` 的 `ModifierParameter`：默认参数 `modifier` 之前还有另一个默认参数。把 `modifier` 移至必需回调之后、其他默认参数之前；`:feature:details:lintDebug :app:assembleDebug` 通过。
- **S2-07C4 — 修正书架页面 Modifier 参数顺序：DONE。** 全仓 `lintDebug` 后续暴露 `LibraryRoute` 与 `LibraryScreen` 两处 `ModifierParameter`。均将 `modifier` 移到必需参数之后及其余默认参数之前；`:feature:library:lintDebug :app:assembleDebug` 通过。
- **S2-07C5 — 更新三组 stable AndroidX 依赖：DONE。** 将 Core 升至 1.19.1、JavaScriptEngine 升至 1.1.1、Navigation3 升至 1.2.0（均为 AndroidX 2026-09-23 stable）；`:core:navigation:testDebugUnitTest :source:engine:testDebugUnitTest :data:download:testDebugUnitTest :data:download:compileDebugAndroidTestKotlin :app:assembleDebug` 通过。官方 stable 清单及版本说明见 `docs/reviews/stage-02-review.md`。
- **S2-07C6 — WorkManager 2.12.0 stable 更新：BLOCKED。** 官方 AndroidX stable 清单已列出 2.12.0，但当前 Gradle 镜像 `https://maven.aliyun.com/repository/google` 未提供 `androidx.work:work-testing:2.12.0`；2026-09-25 在工具链升级后重试仍无法解析 `work-testing-2.12.0.aar`。保持 runtime 与 testing 同为已验证的 2.11.2；镜像同步或可用 Google Maven 路径后再升级并运行 Worker 测试、完整编译与 Lint。
- **S2-07C7 — XZ for Java 1.12 更新：DONE。** 为获取 `LZMAInputStream` 使用 `ArrayCache` 时的解码缺陷修复，将 1.10 升至 1.12；验证：`:core:archive:testDebugUnitTest :core:archive:lintDebug :data:local:testDebugUnitTest :app:assembleDebug` — PASS。全仓 `lintDebug` 复验为 8 条版本提示，XZ 提示已消失，剩余提示归属与证据见审查记录。
- **S2-07C8 — org.json 20260814 更新：DONE。** 该库仅用于 `:source:engine` 测试运行时；`:source:engine:testDebugUnitTest :source:engine:lintDebug :app:assembleDebug` 通过，全仓 `lintDebug --rerun-tasks` 中对应提示已消失，当时剩余 7 项（已由 C12 进一步处理）。
- **S2-07C9 — QuickJS 1.0.15 更新：DONE（C12 解锁）。** KGP 2.4.20 下 `:source:engine:testDebugUnitTest :source:engine:compileDebugAndroidTestKotlin :app:assembleDebug` 通过。
- **S2-07C10 — 书架导航 instrumentation smoke test：DONE（仅编译）。** 覆盖首页打开 Library、切换 Downloads/Local tab 与主要空态/导入入口显示；`:app:compileDebugAndroidTestKotlin :app:assembleDebug` 通过。没有设备运行证据，不替代 Stage 2 手动闭环。
- **S2-07C11 — Coil 3.6.3 更新：DONE（C12 解锁）。** KGP 2.4.20 下 `:core:image:testDebugUnitTest :app:assembleDebug` 通过。
- **S2-07C12 — Kotlin/Android 构建工具链协调升级：DONE。** AGP 9.3.1、Gradle 9.5.0、AGP 内置 KGP/Compose Compiler 2.4.20、KSP 2.3.12；同时升级 Coil 3.6.3 / QuickJS 1.0.15。429 项 JVM 测试及图像/来源引擎回归、下载与数据库 AndroidTest 源码编译、Debug/Release 构建和 Release Lint Vital 均通过。全仓 `lintDebug` 仍由 WorkManager runtime/testing 两条更新提示失败。QuickJS 求值取消现已由 C13 接入并以死循环 timeout/cancel 回归验证。版本兼容依据与细节见 Stage 2 审查及 ADR-0004 §7.5。
- **S2-07C13 — QuickJS evaluation cancellation：DONE。** timeout 与 `SourceScriptRuntime.cancel()` 取消实际 evaluation `Deferred`，最多有界等待 1 秒后丢弃引擎；死循环 timeout 返回 `Timeout`、显式取消返回 `Cancelled`，两种路径后的同源重调用都成功。验证：`:source:engine:testDebugUnitTest :source:engine:compileDebugAndroidTestKotlin :app:assembleDebug` — PASS。
- **S2-07C14 — 书架下载调度顺序：DONE。** Resume/Retry 先完成仓库持久化，再通过状态版本通知 Route 启动唯一 Worker；仓库操作失败不触发调度。新增悬挂仓库操作的时序回归；验证：`:feature:library:testDebugUnitTest :app:assembleDebug` — PASS。
- **S2-07C15 — 过期下载任务所有权恢复：DONE。** 恢复先识别并重排旧 Worker 留下的 Running 页，再把过期任务及从清单收养的新任务认领给当前 Worker，心跳可持续更新。回归测试先复现 2 项失败，修复后 `:data:download:testDebugUnitTest :app:assembleDebug` 通过；Room SQL 的设备执行保留 S2-07D。
- **S2-07C16 — 修复无文件路径的成功页：DONE。** `Succeeded` 页缺少 `relativePath` 时同样视为文件损坏并重新入队；新增失败先行的 JVM 回归，`:data:download:testDebugUnitTest :app:assembleDebug` 通过。设备复验归 S2-07D。
- **S2-07C17 — 混合目录与封面扫描：DONE。** 根目录图片和章节子目录同时导入；仅有根封面时不屏蔽子目录，亦不生成空章。2 项回归先复现，`:data:local:testDebugUnitTest :app:assembleDebug` 通过；SAF 真机导入归 S2-07D。
- **S2-07C18 — Worker 处理队列异常结果：DONE（设备回归待执行）。** 把 Queue 捕获的页面意外异常记录为失败页，避免滞留 `Running`；新增 Worker/Room instrumentation 回归并编译，真实执行归 S2-07D。验证：`:data:download:compileDebugAndroidTestKotlin :app:assembleDebug` 通过。
- **S2-07C19 — 下载页状态条件更新：DONE（设备回归待执行）。** 所有页状态变更使用旧状态条件的单条 Room UPDATE，并按影响行数拒绝并发冲突，防止 Worker 覆盖暂停/取消。JVM 并发插入回归先失败、修复后通过；真实 Room 并发回归归 S2-07D。
- **S2-07C20 — WorkManager 重试时恢复 Running 页：DONE（设备回归待执行）。** 同 ID 的新 attempt 直接重排上次遗留页；不同 ID 但旧心跳未过期时返回 Retry 而非提前结束，直到旧页可认领。JVM 回归先复现，相关测试及 AndroidTest 编译通过；真实进程终止/恢复归 S2-07D。
- **S2-07C21 — 本地页面延迟物化和淘汰后重访：DONE（设备回归待执行）。** 本地章节加载只建立引用，当前可见页按需解档/缓存，返回已淘汰页时重新物化；避免大章节加载时把第一页提前淘汰。`:data:local:testDebugUnitTest :feature:reader:testDebugUnitTest :app:assembleDebug` 通过；真实 SAF/归档长章节等待 S2-07D。
- **S2-07C22 — 归档刷新与取消传播：DONE（设备回归待执行）。** 归档刷新按归档重新索引，不以目录扫描失败删除既有记录；导入取消不转为普通失败。Room/协程回归源码通过 `:data:local:compileDebugAndroidTestKotlin :app:assembleDebug`，真实运行归 S2-07D。
- **S2-07C23 — 来源清理取消活动 HTTP：DONE。** `clearSource` 取消该来源全部已注册 Call，并拒绝清理竞态中尚未注册的旧请求，避免禁用/删除后继续发出网络响应。阻塞请求 JVM 回归先超时，修复后通过；`:source:network:testDebugUnitTest :app:assembleDebug` 通过。
- **S2-07C24 — 详情页返回原列表：DONE（设备复验待执行）。** 详情从探索、搜索、书架进入时记录入口；阅读器返回详情不覆盖入口，详情返回时回到原列表。导航 JVM 回归及 `:app:assembleDebug` 通过；页面实际路径归 S2-07D。
- **S2-07C25 — 全仓回归与设备检查单：DONE。** 439 项 JVM 测试全部通过，Debug/Release 构建与 Release Lint Vital 通过；将 D01–D07 真机步骤、预期、证据要求存入 `docs/reviews/stage-02-device-checklist.md`，未执行设备操作。Stage 2 继续等待 S2-07D 与 WorkManager 依赖解除。
- **S2-07D — 真机用户闭环：BLOCKED（待用户确认执行）。** 保留详情发起下载、暂停/继续/重试/移除、飞行模式离线阅读、SAF 目录/归档导入与刷新、进度恢复、长章节/长图手势和内存、低 API 兼容等待验证项；C18 的 Worker 意外异常、C20 的进程恢复、C22 的归档刷新/取消，以及 C15/C16/C19 的 Room 恢复/竞态回归也须执行。执行前记录设备型号/API、步骤和预期，不在本轮审查中安装 APK、运行 instrumentation 或操作真机。解除条件：用户确认可以进行真机验证并提供可操作设备。

## 7. Stage 3：来源扩展能力

S3-00 在扩展实现前冻结上游基线。后续任务按该基线维护功能、页面流程与设计的差距清单；每个大项在开始实现时再拆为有独立验收和 commit 的小切片。现有编号保持稳定。

| ID | 任务 | 关键交付物与验收 | 前置 | 状态 |
| --- | --- | --- | --- | --- |
| S3-00 | Venera 对照基线 | 固定上游版本/提交；盘点适用功能、页面流程、关键页面/组件及截图，记录权重、差距、排除理由与证据路径；建立各维度初始分数，不把未测项计为通过；记录上游资源/标识的许可证边界 | S2-07 | TODO |
| S3-01 | 分类、排行和聚合搜索 | 能力驱动 UI、并发限制、单源失败隔离；对照清单中的对应流程和状态验收 | S3-00 | TODO |
| S3-02 | 来源设置与私有数据 | 强类型设置、每源隔离、敏感字段保护；迁移与异常状态有测试 | S3-00 | TODO |
| S3-03 | 登录与 Cookie | 密码登录、Cookie 导入、Keystore 策略；会话隔离、过期与失败恢复有测试 | S3-02 | TODO |
| S3-04 | WebView 登录 | 隔离 WebView、Cookie 同步、验证码流程；退出与失败后的会话清理有测试 | S3-03 | TODO |
| S3-05 | 收藏与账户能力 | 远端收藏夹、本地映射、一致性与冲突处理有测试 | S3-03 | TODO |
| S3-06 | 评论、评分与交互 | 可选能力、分页、错误和权限状态有测试 | S3-03 | TODO |
| S3-07 | 高级图片处理 | 二进制变换、解密允许边界、缓存语义；固定 fixture 与超限/失败测试 | S3-00 | TODO |
| S3-08 | 兼容矩阵与调试工具 | Source Contract Suite、脱敏日志、导出诊断；逐项复核 Stage 3 对照差距 | S3-01 至 S3-07 | TODO |

## 8. Stage 4：同步、体验与发布

| ID | 任务 | 关键交付物与验收 | 前置 | 状态 |
| --- | --- | --- | --- | --- |
| S4-01 | Proto DataStore 设置 | 外观、阅读、网络设置与迁移；配置变更与进程恢复有测试 | S3-08 | TODO |
| S4-02 | WebDAV 备份恢复 | 格式版本、预览、冲突、安全默认值；往返和旧版本恢复测试 | S4-01 | TODO |
| S4-03 | Material 3 Adaptive | Navigation Rail、列表详情、折叠屏；手机与宽屏关键布局验收 | S3-08 | TODO |
| S4-04 | 无障碍与国际化 | TalkBack、字体缩放、键盘、简繁体/英文；关键页面与操作覆盖 | S4-03、S4-08 | TODO |
| S4-05 | 性能基线 | Macrobenchmark、Baseline Profile、回归阈值；记录基线设备与波动范围 | S4-08 | TODO |
| S4-06 | CI 与供应链 | Wrapper 校验、测试、Lint、SBOM、许可证报告；CI 与本地结果一致 | S3-08 | TODO |
| S4-08 | 首页信息架构与视觉对齐 | 对照 S3-00 基线完成原生视觉规范和首页实现；最近阅读、收藏更新、本地入口、来源探索与加载/空/错误状态符合产品规划 | S3-01、S3-00 | TODO |
| S4-09 | 全 App Venera 对照审计与差距收敛 | 复核 S3-00 清单、补齐差距并提交证据；功能、页面逻辑、设计三个维度分别达到至少 90% | S3-08、S4-01 至 S4-06、S4-08 | TODO |
| S4-07 | 发布准备 | 图标、包名、签名、隐私、GPL 义务、Release 文档；RC 门禁与全 App 对齐结果已通过 | S4-09 | TODO |

S4-08 验收要求：

- 使用 S3-00 冻结的原 Venera 基线，记录首页主要分区、导航关系、卡片层级与关键交互，形成可复核的设计依据。
- 首页应呈现最近阅读、收藏更新、本地漫画和来源探索等已规划内容，并能通过可见交互进入对应功能；数据加载、无内容、失败与重试状态完整。
- Android 端使用 Compose / Material 3 实现，整体布局、信息层级和视觉语言应明显贴近原项目；按原生平台适配，不要求逐像素复制 Flutter 布局。
- 实现前确定首页视觉 Token（颜色、间距、形状、文字层级）及品牌标识的处理方式；完成后以截图/设备验收覆盖首页初始态、滚动态与深色/动态主题适配。
- 通过首页相关 UI 测试、受影响模块编译和 Stage 质量审查；剩余偏差或未验证项需在审查记录中逐项说明，不能以“风格相似”代替验收。

S4-09 验收要求：

- 复核 S3-00 所定的 Venera 上游仓库、版本/提交和适用范围清单；任何基线变更须说明对三维度分母、已有验收与时间的影响。
- 功能覆盖、页面逻辑覆盖、设计相似度分别计算，不以跨维度平均分掩盖不足；每一项必须有代码、测试、可复现流程或截图/设备证据支撑。
- 三个维度各自达到 ≥90%；低于门槛的维度继续保持 Stage 4 未完成，缺口拆为可独立提交的小任务并复验。
- 保存完整审计矩阵、计算口径、证据和剩余风险至 `docs/reviews/`；Stage 质量审查确认后才允许将最终产品对齐目标标为 DONE。

## 9. 通用任务完成模板

新 Agent 完成任务时，在 `STATUS.md` 留下以下信息：

```markdown
### <任务 ID> 完成记录

- 完成日期：
- 主要改动：
- 关键设计决定：
- 测试：
  - `<command>` — PASS/FAIL
- 未解决风险：
- 下一任务：
```

如果任务被阻塞：

```markdown
- 阻塞原因：
- 已尝试：
- 需要的外部输入：
- 解除阻塞后第一步：
```

不要用“待完善”“之后处理”替代具体任务编号。

## 10. Stage 0/1 质量整改

执行表与验收：`docs/reviews/stage-01-remediation.md`。Q00–Q13 全部 DONE，Stage 1 已通过阶段复验；当前任务已进入 Stage 2。

- Q01 DONE：修复正文有界读取与回调异常映射；新增短/空/边界、chunked 超限和读取异常回归。；验证见 STATUS 与整改台账。下一项 Q02 IN_PROGRESS。

- Q02 DONE：修复缓存提交后的文件租约、网络取消与实际字节上限、表单编码、鉴权快照及共享 DiskCache；新增首次下载和缓存命中/请求语义回归。；验证见 STATUS 与整改台账。下一项 Q03 IN_PROGRESS。

- Q03 DONE：保留稳定页索引，按邻近页面解析尺寸并支持失败重试；解码器通过带租约的认证缓存文件读取正文；验证见 STATUS 与整改台账。下一项 Q04 IN_PROGRESS。

- Q04 DONE：以不可变脚本和原子索引替换保证来源升级一致性，失败保留旧包，回滚保留禁用状态；验证见 STATUS 与整改台账。下一项 Q05 IN_PROGRESS。

- Q05 DONE：保留根依赖跨配置变化，销毁时关闭资源；冷启动串行恢复启用来源，禁用卸载清理会话，升级能力不再使用陈旧缓存；验证见 STATUS 与整改台账。下一项 Q06 IN_PROGRESS。

- Q06 DONE：mixed 探索以零基游标解析连续分页，回归覆盖 0、1、2 与末页停止；验证见 STATUS 与整改台账。下一项 Q07 IN_PROGRESS。

- Q07 DONE：进度按漫画合并并串行写入，失败保留可重试，尾部定时落盘；生产历史仓库通过 Room 事务同步历史和恢复位置；验证见 STATUS 与整改台账。下一项 Q08 IN_PROGRESS。

- Q08 DONE：异步恢复绑定章节，进入首屏即记录，离开阅读器强刷，并保存真实显示元数据；验证见 STATUS 与整改台账。下一项 Q09 IN_PROGRESS。

- Q09 DONE：阅读缩放使用与父级滚动协作的手势，缩放后平移可见且有界；验证见 STATUS 与整改台账。下一项 Q10 IN_PROGRESS。

- Q10 DONE：Runtime 日志、初始化调用 ID、安装/探测超时和同源队列边界完成整改；验证见 STATUS 与整改台账。下一项 Q11 IN_PROGRESS。

- Q11 DONE：生产协议 demo、Source Core 自动回归、本地 HTTP 图片和 SAF 安装入口形成同一闭环；验证见 STATUS 与整改台账。下一项 Q12 IN_PROGRESS。

- Q12 DONE：当前事实、QuickJS 验收范围、ABI/包体、许可状态与延期风险已对齐；验证见 STATUS 与整改台账。后续 Q13 已完成。

- Q13 DONE：全量 Debug Lint、274 项 JVM 单测、Debug/Release 构建通过；修复门禁发现的 4 项 Lint 错误并形成最终审查记录。Stage 1 DONE；下一项 S2-01 TODO。
