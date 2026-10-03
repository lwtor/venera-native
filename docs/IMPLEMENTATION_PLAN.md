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
| Stage 2 | 完成书架、下载和本地阅读 | 离线可浏览书架并阅读下载或本地漫画 | DONE（D01–D07 及退出质量复核通过） |
| Stage 3 | 冻结上游对照基线并补齐来源扩展能力 | 逐项对照清单就绪；Core/Extended 协议测试通过，Advanced 有支持矩阵 | IN_PROGRESS（S3-00 基线核验中） |
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
| S2-07 | Stage 2 集成验收 | 从详情发起并控制下载；飞行模式下书架、下载、本地目录、阅读和进度闭环 | S2-01 至 S2-06 | DONE |

S2-07 当前切片与验收：

- **S2-07A — 统一章节身份与本地阅读：DONE。** `AppRoute.Reader` / `PageProvider` / Reader 使用 `ChapterRef`；SAF 目录与归档章节都可选入现有 Reader；进度写入 `@local` 历史命名空间。验收：远端旧路由 round-trip 兼容、本地路由 round-trip、本地页不调用来源、历史恢复命中同一 local chapter。
- **S2-07B — 下载用户流程：DONE。** 详情可排入章节下载并启动唯一 WorkManager；Library Downloads 显示页进度并提供暂停、继续、重试、移除。验收：`:feature:details:testDebugUnitTest :feature:library:testDebugUnitTest :app:assembleDebug` 通过。
- **S2-07C — Stage 2 质量复验：DONE。** 审查记录：`docs/reviews/stage-02-review.md`。C1–C25 的代码、测试和文档整改已完成；C6 解除依赖镜像阻塞后，全仓 439 项 JVM 测试、Debug/Release 构建、完整 `lintDebug` 通过。设备页面闭环独立归 S2-07D；Stage 2 在 D 完成前仍为 IN_PROGRESS。
- **S2-07C1 — Room migration 真机断言：DONE。** 真机运行暴露表名断言将 `room_master_table` 误作应用 schema；仅过滤该 Room 内部表后，`:core:database:connectedDebugAndroidTest` 25 项通过。`:data:download:connectedDebugAndroidTest` 同设备 4 项通过。修复与证据记入 Stage 2 审查记录。
- **S2-07C2 — 删除无效通知 API 兼容分支：DONE。** `:data:download:lintDebug` 首次暴露 `ObsoleteSdkInt`：项目 minSdk 为 26，而下载通知 channel 要求 API 26，低于 O 的检查不可达。移除该无效分支后，`:data:download:lintDebug :app:assembleDebug` 均通过。
- **S2-07C3 — 修正详情入口 Modifier 参数顺序：DONE。** 全仓 `lintDebug` 随后暴露 `feature/details/DetailsRoute.kt` 的 `ModifierParameter`：默认参数 `modifier` 之前还有另一个默认参数。把 `modifier` 移至必需回调之后、其他默认参数之前；`:feature:details:lintDebug :app:assembleDebug` 通过。
- **S2-07C4 — 修正书架页面 Modifier 参数顺序：DONE。** 全仓 `lintDebug` 后续暴露 `LibraryRoute` 与 `LibraryScreen` 两处 `ModifierParameter`。均将 `modifier` 移到必需参数之后及其余默认参数之前；`:feature:library:lintDebug :app:assembleDebug` 通过。
- **S2-07C5 — 更新三组 stable AndroidX 依赖：DONE。** 将 Core 升至 1.19.1、JavaScriptEngine 升至 1.1.1、Navigation3 升至 1.2.0（均为 AndroidX 2026-09-23 stable）；`:core:navigation:testDebugUnitTest :source:engine:testDebugUnitTest :data:download:testDebugUnitTest :data:download:compileDebugAndroidTestKotlin :app:assembleDebug` 通过。官方 stable 清单及版本说明见 `docs/reviews/stage-02-review.md`。
- **S2-07C6 — WorkManager 2.12.0 stable 更新：DONE。** 2026-09-26 配置的 Aliyun 镜像已提供 `work-testing:2.12.0`。runtime/testing 配对升级后，下载 JVM 回归、AndroidTest 编译、Debug/Release 构建、439 项全仓 JVM 测试与完整 `lintDebug` 均通过。真实 Worker instrumentation 在 2.11.2 上执行过，升级后的设备复验归 S2-07D。
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
- **S2-07D0 — 书架导航设备测试修正：DONE。** API 34 模拟器首次运行发现测试未切换 Downloads 标签即断言该标签空态；补全用户操作后 `:app:connectedDebugAndroidTest` 1/1 与 Debug 构建通过。
- **S2-07D1 — Debug fixture 回环图片访问：DONE。** 为仓库测试图片的 `127.0.0.1` HTTP 服务增加仅 Debug 的 Network Security Config；Release merged manifest 未包含例外。API 34 模拟器原复现阅读页失败，重装后图片 GET 200、Page 1/2 显示；Debug/Release 构建通过。
- **S2-07D2 — 系统返回键导航：DONE。** 根导航统一页面和系统返回目标，首页仍由系统正常退出；新增导航 JVM 与应用设备回归，修复前设备测试复现 Activity 退出，修复后 API 34 模拟器 2/2 测试及 Debug 构建通过。D01 阅读器逐级返回仍需继续记录。
- **S2-07D3 — 离线阅读短尾页与立即重开进度：DONE。** API 34 模拟器关闭 fixture 服务并开飞行模式后，3/3 页能从下载文件显示；修复重开时连续模式首帧把末页写回前一页，以及节流期间读取旧数据库页码。历史 JVM、Reader 设备回归与 Debug 编译通过；同设备手动重开维持 3/3 且反向滚动仍更新页码。
- **S2-07D4 — D01 来源导航设备回归：DONE（D01 真机复验待执行）。** App AndroidTest 复用仓库 demo 来源，在真实 App 图中覆盖探索、搜索、书架三条详情/阅读器/返回路径，断言第一页图片加载，清理新增测试数据。API 34 模拟器 1/1 通过，Debug 构建通过；小米真机未被 ADB 枚举，D01 不据此标为完整通过。
- **S2-07D5 — D01 真机测试停滞定位：DONE（D01 真机复验待执行）。** 2026-10-01 小米真机上 D01 单项 instrumentation 启动，但约 4 分钟没有页面结果，测试前台返回系统桌面；主动中断后 UTP 标记 driver canceled。测试现记录阶段日志，且对来源仓库初始化、安装和清理使用 20 秒上限，下一轮可区分初始化停滞与页面等待。`:app:compileDebugAndroidTestKotlin :app:assembleDebug` 通过；尚未取得真机通过证据。
- **S2-07D6 — 小米后台 Activity 启动限制诊断：DONE（D01 真机复验待执行）。** 第二轮真机日志证实 MIUI 拒绝 instrumentation 从后台启动 `MainActivity`，结果码 102；测试方法虽已开始，但尚未进入第一条测试语句。一次显式 shell 启动缺少 MAIN/LAUNCHER 标识，被 `ActivityScenario` 忽略；设备另拒绝 shell `input keyevent` 的 `INJECT_EVENTS`。下一轮使用与测试启动 Intent 一致的 shell 前台启动方式复验；本诊断为纯文档结论，`git diff --check` 通过，D01 未通过。
- **S2-07D7 — D01 真机导航闭环：DONE。** 基线 `641e46d`，Xiaomi 25128PNA1C / API 36：测试开始后以匹配 MAIN/LAUNCHER 和 flags 的 shell Intent 将 App 拉到前台，`ActivityScenario` 收到 RESUMED；App AndroidTest 依次完成探索、搜索、书架三条详情→阅读器→逐级返回路径及第一页图片断言。UTP 1/1、0 失败/错误/跳过，Gradle 退出 0，fixture 图片 GET 200；记录见设备检查单。本项纯证据文档切片不涉及编译，执行 `git diff --check`。
- **S2-07D8 — 用户真机安装保留：DONE。** 用户要求已安装 App 不在测试后卸载。上一轮 Gradle connected test 后真机 `pm path` 确认 App 和测试 APK 都已消失；已在 `AGENTS.md` 与设备检查单规定后续真机仅构建 APK、按需覆盖安装、直接运行 instrumentation 并保留安装与数据，避免使用会清理安装的 connected test。纯文档切片执行 `git diff --check`；D02 仍是唯一下一检查项。
- **S2-07D9 — D02 受控下载 fixture：DONE。** 仓库内 `download_control_source.js` 提供慢速与失败重试两本三页漫画，`download_fixture_server.py` 在回环端口提供 8 秒延迟、前三次 503 后成功的端点。语法、HTTP 响应及 `:app:assembleDebug` 已验证；页面、通知、暂停/继续、重试与移除仍属 D02 后续验收，不据 fixture 存在标记通过。
- **S2-07D10 — D02 慢速下载页面测试：DONE（设备执行待验收）。** App AndroidTest 编译通过，覆盖独立测试源的详情入队、书架 Downloads 暂停/继续、完成离线检查、移除后的数据库与文件清理，以及通知启用时的活动通知。章节显示名唯一化以定位测试任务；`:app:compileDebugAndroidTestKotlin :app:assembleDebug` 通过。下一切片补失败重试测试，之后执行 D02 真机闭环；当前未声明 D02 通过。
- **S2-07D11 — D02 失败重试页面测试：DONE（设备执行待验收）。** App AndroidTest 覆盖 fixture 失败后的 Partial/2 of 3 与页级 Failed 状态，再经书架 Retry 触发 Worker 并验收 Completed、3/3 与离线完整性。失败请求按每次测试来源 ID 独立计数，保证测试可重复。`:app:compileDebugAndroidTestKotlin :app:assembleDebug`、Node 检查、Python AST 解析和 `git diff --check` 均通过。尚未设备执行，D02 不据此标为通过。
- **S2-07D12 — D02 测试按钮定位修正：DONE（真机复验待执行）。** 首次小米两项用例选择到 Downloads tab 节点，失败重试场景已验证初始 2/3 Partial 和页级 Failed，但未点击 Retry；测试 helper 改为按章节标题和按钮垂直位置定位操作。`:app:compileDebugAndroidTestKotlin :app:assembleDebug` 通过；修正版尚待小米真机复跑，不据此标记 D02 通过。
- **S2-07D13 — D02 暂停用例等待窗口修正：DONE（真机复验待执行）。** 首轮修正版真机复跑中，失败重试用例通过；暂停用例的 8 秒慢页在回到 Downloads 并点击 Pause 前完成，未能稳定覆盖“运行页完成后队列页保持暂停”。fixture 延时增至 35 秒，Paused 等待延至 60 秒，单独复跑慢速用例以减少前台占用。AndroidTest 编译及 D02 真机结果待记录；D02 仍未完成。
- **S2-07D14 — 修复下载队列中尚未开始页面无法暂停：DONE。** 小米复跑稳定复现 `Paused` 等待超时；根因是 Worker 在并发队列启动前将整批页面写成 `Running`，故等待并发槽位的第三页也不能被 Pause。改为取得并发槽位时再原子认领页面；如果期间已暂停则不发请求。新增真实 Room/WorkManager 回归：两个下载阻塞时暂停三页任务，确认前两页运行、第三页暂停，放行后续完成、恢复再下载并全部成功。`:data:download:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug` 与两模块 `assembleDebugAndroidTest`、`git diff --check` 通过；D02 小米真机闭环随后在 S2-07D16 通过。
- **S2-07D15 — D02 慢速 fixture 遵守网络读取超时：DONE。** 设备确认 Pause 到达 Paused，Resume 也成功排入新 Worker；完成断言超时。服务日志出现同一页面重复请求，根因为 fixture 延迟 35 秒，超过 `AppHttpClientFactory` 的 30 秒读取超时，Worker 因此重试页面。改为 20 秒，保留暂停操作窗口并低于网络超时；20 秒 fixture 下真机闭环通过，详见 S2-07D16。
- **S2-07D16 — D02 小米真机用户闭环：DONE。** Xiaomi 25128PNA1C / API 36，POST_NOTIFICATIONS 已开启；暂停/恢复/移除测试 1/1 通过（57.53 秒：活动通知可见、3/3 离线完成、移除后 Room 与文件均清理），失败重试测试 1/1 通过（16.291 秒：2/3 Partial/Failed 后 Retry 至 3/3 Completed）。App 与数据保留，fixture 服务及 ADB reverse 已清理；详见 `docs/reviews/stage-02-device-checklist.md`。S2-07D 下一项为 D03。
- **S2-07D17 — D03 Worker/Room 真机 instrumentation：DONE（D03 整体验收仍进行中）。** 基线 `ffe63a3`，Xiaomi 25128PNA1C / API 36。JDK 17 下执行 `:data:download:assembleDebugAndroidTest :app:assembleDebug :app:packageDebugAndroidTest` 构建成功；直接通过 `adb shell am instrument -w -r -e class dev.veneranative.data.download.worker.DownloadWorkerTest dev.veneranative.data.download.test/androidx.test.runner.AndroidJUnitRunner` 执行 7/7，0 失败/跳过，覆盖空队列、异常页记 Failed、停止 Worker 不落文件、前台通知通道、下载校验、并发槽位暂停后恢复，以及新 Worker 遇到仍新鲜的其他 Worker 所有权时保持待处理。没有使用 `connectedDebugAndroidTest`，没有卸载 App 或清除 App 数据；安装了独立模块测试 APK。此结果不覆盖真实 App 进程终止/重启、相同 Worker ID 恢复、无文件路径成功页和并发取消，这些仍须作为 D03 未完成项继续验证。本记录为文档切片，执行 `git diff --check`。
- **S2-07D18 — D03 Room 恢复边界真机回归：DONE（D03 整体验收仍进行中）。** 基线 `018cb25`，Xiaomi 25128PNA1C / API 36。新增实际 Room instrumentation 覆盖同 Worker ID 重试、过期的不同 Worker ID 认领、以及 Succeeded 但 `relativePath` 缺失时重排入队。`:data:download:assembleDebugAndroidTest :app:assembleDebug` 构建通过，直接重跑 `DownloadWorkerTest` 10/10、0 失败/跳过。App 与数据未清理。真实 App 进程被明确终止后重新启动并确认下载恢复，及并发取消仍待完成；本次测试代码已通过真机验收，D03 仍为 IN_PROGRESS。
- **S2-07D19 — D03 App 进程恢复真机验证：DONE（并发取消仍待验收）。** 基线 `b768785`，Xiaomi 25128PNA1C / API 36。两阶段 App instrumentation 在慢速章节进入 Running 后明确杀掉 App 与测试进程，再用 MAIN/LAUNCHER 启动 App；WorkManager 接管并完成 3/3 页，离线完整性通过，测试来源清理完成。App 与数据保留。
- **S2-07D20 — 并发取消迟到页面清理：DONE。** 小米 Xiaomi 25128PNA1C / API 36 的 `DownloadWorkerTest` 发现并复现取消章节后在途页面仍会落成孤儿文件。Worker 现在检查成功页能否写入现存 Room 记录；取消已删记录时清理迟到页面文件。`:data:download:assembleDebugAndroidTest :app:assembleDebug` 构建通过，直接真机执行 11/11、0 失败/跳过，取消竞态测试通过。D03 全部验收项满足，Stage 2 继续由 D04 开始。
- **S2-07D21 — 截断下载页检测与恢复：DONE。** 小米 25128PNA1C / API 36 新增 Room/WorkManager instrumentation：成功页文件被截断后，`isCompleteOffline` 返回 false，`recover` 将页重新排队，Worker 重新下载后恢复离线完整性。`:data:download:assembleDebugAndroidTest :app:assembleDebug` 构建成功，直接真机运行 `DownloadWorkerTest` 12/12、0 失败/跳过。D04 的 Reader 飞行模式与退出重开进度闭环仍待执行。
- **S2-07D22 D04 飞行模式离线阅读与进度恢复 — DONE。** Xiaomi 25128PNA1C / API 36。新增 App AndroidTest 完成三页下载、加入书架、导航回章节；等待主机切飞行模式并停止 fixture 后，Reader 显示已下载页面、由第 1 页翻至第 2 页；退出后重开恢复第 2 页，再离线翻到第 3 页。fixture 与 ADB reverse 清理，飞行模式恢复关闭，测试来源/收藏/历史/下载均由测试清理。构建 `:app:assembleDebugAndroidTest :app:assembleDebug` 通过；小米直接 instrumentation 1/1、0 失败/跳过。与 D21 截断页检测结合，D04 全部验收项通过。
- **S2-07D23 D05 SAF ZIP 选取与归档读取 — DONE（D05 其余项仍待验收）。** 2026-10-01 Xiaomi 25128PNA1C / API 36 上，用户通过 Venera 的归档选择器选中合成 `VeneraD05Archive.zip`。设备授予 App 对该 FileExplorer document URI 的持久读取权；仓库仅在成功打开归档、筛出至少一张可读图片并准备写入归档记录后调用 `takePersistableUriPermission`，因此该授权证明本次 picker 返回的 ZIP 已被 App 实际读取并解析出页面。`:data:local` 的 `LocalArchiveRefreshTest` 直接真机复跑 `OK (2 tests)`。归档 Room 行/Reader 页面显示、归档刷新和退出重开未单独观察；随后启动的 `Stage2LibraryNavigationTest#homeOpensDownloadsAndLocalLibraryTabs` 在 AndroidJUnitRunner 启动后停滞，无测试结果，已只终止测试包进程。未卸载 App、未清理其数据；`gradle.properties` 的用户修改保留。D05 仍为唯一下一检查项，剩余真实刷新/重开闭环；ZIP 为本次真机格式，7z 仅有模块 fixture 覆盖。Stage 2 保持 IN_PROGRESS，不以授权本身替代剩余闭环。
- **S2-07D24 D05 归档标题、刷新与进程重启复验：DONE。** 修复 SAF 编码 URI 被用作归档标题的问题，改为使用 DocumentProvider display name，并添加 Android regression。`:data:local:assembleDebugAndroidTest :app:assembleDebug` 成功；Xiaomi 25128PNA1C / API 36 直接运行 `LocalArchiveRefreshTest` 为 `OK (3 tests)`。保留数据覆盖安装后，归档刷新得到标题 `VeneraD05Archive`、类型 `Archive`、1 章、2 页；`LocalFirstPageProvider` 实际读取第一页。强停 App 进程再验仍成功，证明记录和持久 SAF 授权可恢复。D05 实机格式覆盖 ZIP，7z 有模块 fixture；设备 UI 未单独演练 picker 取消/权限拒绝。App 与数据保留；下一项 D06。
- **S2-07D25 D06 长章节缓存压力回归准备：DONE（真机执行待完成）。** 新增 `LocalLongChapterTest`：257 个 1 MiB 本地页；验证章节索引不物化文件、超过 256 MiB 后缓存淘汰首屏、回访时重新读取且缓存保持有界。`:data:local:assembleDebugAndroidTest` 编译成功。测试尚未在设备运行；真机断开，D06 继续 IN_PROGRESS。
- **S2-07D26 D06 小米长章节与 Reader 手势验收：DONE。** Xiaomi 25128PNA1C / API 36。`LocalLongChapterTest` 直接真机 `OK (1 test)`、1.417 秒，覆盖 257 MiB 懒索引、256 MiB 缓存淘汰及回访重读；`ReaderScreenTest#longChapterCanAdvanceReturnAndScrollTallPages` `OK (1 test)`、18.651 秒，覆盖 LTR 前进/返回和切换纵向后长图滚动。D06 全部验收通过，下一项 D07。
- **S2-07D27 D07 API 34+ 通知类型与 API 30 导航复验：DONE（API 26 闭环待完成）。** Xiaomi API 36 的通知 channel / `dataSync` 类型 instrumentation 通过；API 30 ARM64 模拟器的来源脚本、Reader 导航及书架入口各 1/1 通过。API 26 系统镜像和真实 SAF 目录导入仍待验证。
- **S2-07D28 D07 Xiaomi API 36 下载通知运行闭环：DONE（API 26 闭环待完成）。** Xiaomi 25128PNA1C / API 36 上 `Stage2DownloadControlTest#slowChapterCanPauseResumeAndRemove` `OK (1 test)`、62.712 秒；通知可见、三页暂停/继续后完成、移除后数据库与文件清理一致。App 及数据保留，fixture 和 ADB reverse 已清理。
- **S2-07D29 Stage 2 退出质量复核：DONE（当时门禁未通过）。** 复核记录见 `docs/reviews/stage-02-exit-review-2026-10-02.md`；当时 API 26 环境、实际 SAF 目录导入和低版本通知闭环缺少证据，故 Stage 2 保持 IN_PROGRESS。
- **S2-07D30 D07 API 26 最低版本闭环：DONE（Stage 2 退出复核待执行）。** AOSP API 26 ARM64 模拟器上，来源脚本/探索搜索书架至 Reader 返回测试 `OK (1 test)`；系统 SAF picker 实际授权合成 `Download/VeneraD07Dir`，App 显示 `Folder imported.`，Reader 显示 `1 / 2` 且 Page 1 图像节点正常（目录包含两张生成 fixture 图）。`Stage2DownloadControlTest#slowChapterCanPauseResumeAndRemove` `OK (1 test)`、41.933 秒，验证活动通知、暂停/继续至 3/3 与移除清理；fixture 请求全部 HTTP 200。API 26 App 与测试包保留安装，未清数据；fixture 服务与 reverse 已清理。纯验证/文档切片执行 `git diff --check`。
- **S2-07D31 Stage 2 退出质量复核：DONE。** 更新 [Stage 2 退出审查](reviews/stage-02-exit-review-2026-10-02.md)：D01–D07 验收矩阵通过，API 26 最低版本闭环补齐，JDK 17 下最终 `testDebugUnitTest :app:assembleDebug :app:assembleRelease` 通过（442 项 JVM 测试，0 失败，Debug/Release 及 Release Lint Vital 成功）。未发现新增代码缺陷；将未逐项设备演练的 7z/CB7 格式与 SAF 取消/权限丢失场景如实保留为非阻断范围风险。Stage 2 标记 DONE；下一项 S3-00。

## 7. Stage 3：来源扩展能力

S3-00 在扩展实现前冻结上游基线。后续任务按该基线维护功能、页面流程与设计的差距清单；每个大项在开始实现时再拆为有独立验收和 commit 的小切片。现有编号保持稳定。

| ID | 任务 | 关键交付物与验收 | 前置 | 状态 |
| --- | --- | --- | --- | --- |
| S3-00 | Venera 对照基线 | 固定上游版本/提交；基于上游页面、导航、主题及状态源码盘点适用功能、页面流程与组件，并对照本地 Compose 实现；记录权重、差距、排除理由与证据路径；建立各维度初始分数，不把未测项计为通过；记录上游资源/标识的许可证边界。矩阵见 `docs/reviews/venera-comparison-baseline-2026-10-02.md`。截图只用于页面实现后的视觉验收，不阻断源码审查、S3-00 或 S3-01 | S2-07 | IN_PROGRESS |
| S3-01 | 分类、排行和聚合搜索 | 能力驱动 UI、并发限制、单源失败隔离；对照清单中的对应流程和状态验收 | S3-00 | TODO |
| S3-02 | 来源设置与私有数据 | 强类型设置、每源隔离、敏感字段保护；迁移与异常状态有测试 | S3-00 | TODO |
| S3-03 | 登录与 Cookie | 密码登录、Cookie 导入、Keystore 策略；会话隔离、过期与失败恢复有测试 | S3-02 | TODO |
| S3-04 | WebView 登录 | 隔离 WebView、Cookie 同步、验证码流程；退出与失败后的会话清理有测试 | S3-03 | TODO |
| S3-05 | 收藏与账户能力 | 远端收藏夹、本地映射、一致性与冲突处理有测试 | S3-03 | TODO |
| S3-06 | 评论、评分与交互 | 可选能力、分页、错误和权限状态有测试 | S3-03 | TODO |
| S3-07 | 高级图片处理 | 二进制变换、解密允许边界、缓存语义；固定 fixture 与超限/失败测试 | S3-00 | TODO |
| S3-08 | 兼容矩阵与调试工具 | Source Contract Suite、脱敏日志、导出诊断；逐项复核 Stage 3 对照差距 | S3-01 至 S3-07 | TODO |

S3-00 小任务：

| ID | 交付 | 状态 |
| --- | --- | --- |
| S3-00A | 冻结上游仓库/提交与发行版本；完成适用功能、流程、设计项及权重清单；核对本地实现和证据；记录许可证与品牌边界 | DONE（文档/源码审查，`git diff --check` 通过） |
| S3-00B1 | 找到并记录上游发行页面里的截图引用及来源信息，不复制图片素材 | DONE（7 个 F-Droid 截图引用；图片与具体页面/归档提交的对应关系尚待人工核验） |
| S3-00B2 | 阅读固定提交的上游页面/导航/主题与状态源码；逐项映射本地 Compose 页面、路由、交互和缺口，补足功能及流程基线。保留上游截图引用为后续视觉 QA 线索，成对截图和视觉复评安排在页面实现验收及 S4-09 | BLOCKED；当前执行环境访问 `github.com` 失败，解除条件为恢复源码访问；截图不构成阻断 |
| S3-00C | 复刻上游 `index.json` 来源目录解析：兼容当前 `fileName` 与文档 `filename` / `url`，在来源页展示在线目录，支持刷新与 HTTPS 脚本安装；保留 SAF 本地安装 | DONE（代码与测试源码编译通过；未执行设备联网验收） |
| S3-00D | 以拷贝漫画验证在线配置目录 → 下载/安装真实来源脚本 → 通过来源运行时搜索并在 Compose 页面显示结果；补齐当前源所需的 QuickJS 兼容能力 | DONE（JDK 17 构建与引擎 JVM 测试通过；Xiaomi 25128PNA1C 实机完整流程 1/1 通过，来源保留安装） |
| S3-00E | 完善真实来源详情：保留源返回的标签分组与详情元数据，显示作者/状态/更新时间、缩略图、简介和章节；用真实搜索结果加载详情并验证详情内容可见 | DONE（解析/引擎回归测试、详情 UI 真实数据 Android 测试均编译通过；详情真机验收仍待完成，未记录为通过） |
| S3-00F | 修复阅读器将解码后页面 bitmap 拉伸到 tile 全尺寸导致的宽高比失真；按比例适配并添加图像像素级 Compose 回归 | DONE（Xiaomi Xiaomi 25128PNA1C / API 36 仪器测试 1/1 通过） |
| S3-00G | 阅读器从首页续读时按实际进入路由返回；覆盖首页、详情和书架入口 | DONE（导航 JVM 回归及 `:app:assembleDebug` 通过；真机页面路径未在本次执行） |
| S3-00H | 修复阅读器图片白屏/过小问题，验证纵向连续与横向分页的真实图片尺寸和布局 | DONE（修复 WebP VP8 有损帧头的尺寸解析偏移；小米真机首页续读在纵向/横向、采样/区域解码下均通过可视像素验收） |
| S3-00I | 修复退出后重复进入阅读器偶发的章节加载失败及无效重试；覆盖源调用取消/重试路径 | DONE（同源调用忙碌状态有界重试；引擎、章节提供器和阅读器单测通过） |
| S3-00J | 减少横向阅读翻页等待：围绕当前页对前后各一页预解码首个显示 tile，复用有界图片缓存；离开邻近窗口时取消过远预取；增加 Reader Compose 回归 | DONE（`:feature:reader:compileDebugAndroidTestKotlin :feature:reader:assembleDebugAndroidTest :app:assembleDebug` 均通过；Xiaomi 上启动 `predecodesOnlyTheImmediatePagesAroundTheCurrentPage` 后约 40 秒未返回测试结果，未记为设备通过；实机翻页耗时仍待用户观察） |
| S3-00K | 阅读器性能补强：纵向与分页均在滚动停稳后预解码邻页，远端页数据预取扩至前后两页，快速滑动取消过时解码 | DONE（`:data:comic:testDebugUnitTest :feature:reader:testDebugUnitTest :feature:reader:compileDebugAndroidTestKotlin :app:assembleDebug` 通过；未做设备耗时对比） |
| S3-00L | 阅读到章节末尾后自动衔接来源顺序中的下一话；首页续读也可用 | DONE（后续由 S3-00O 将路由跳转改为同一阅读画布追加） |
| S3-00M | 修复漫画源调用并发时详情/章节被标记失败；同源请求排队，阅读期间预载下一话与首张图片 | DONE（QuickJS 会话排队回归、预载缓存复用回归及相关编译通过；末页路由跳转已由 S3-00O 替换为同画布追加） |
| S3-00N | 修复竖屏末页状态由 Pending 更新为 Ready 后，末页监听仍持有旧状态而不触发下一话；补回归用例 | DONE（`:feature:reader:compileDebugAndroidTestKotlin :app:assembleDebug` 通过；设备执行被 auto-review 拦截，因为 UTP 可能卸载用户 App/测试 APK） |
| S3-00P | 修复来源脚本并发 HTTP 请求超过每源上限时立即失败；改为有界并发排队，并记录脱敏错误分类以便定位持续加载故障 | DONE（来源网络并发排队回归和调用层单测通过；错误日志仅含来源 ID、固定成员名和错误类型） |
| S3-00O | 下一话作为页段追加进当前 Reader 状态，竖向长画布连续下滑、横向分页连续翻页；保留分话阅读进度；章节/详情临时加载失败可重试且不能伪装成系列结尾 | DONE（data/comic、source/network、source/core、feature/reader JVM 测试及 Android 测试源码编译通过；app debug 构建通过；未做设备 UI 测试） |
| S3-00Q1 | 详情页和阅读器共享漫画详情短缓存，避免每次预载章节都重复下载完整目录；手动刷新绕过缓存；失败响应不缓存 | DONE（`data:comic`、`feature:details` JVM 测试与 App Debug 编译通过） |
| S3-00Q2 | 适配拷贝漫画章节限流所需的 `setTimeout`；限制等待时长、支持取消，并给章节调用足够但有上限的超时 | DONE（`:source:network:testDebugUnitTest :source:engine:testDebugUnitTest :source:core:testDebugUnitTest` 通过；QuickJS 计时器与清理、Host 等待取消和章节调用专属超时均有回归；App 编译通过，未做设备 UI 验证） |
| S3-00R | 来源函数抛出未处理的 JS 异常后，废弃可能已变更的 QuickJS 单例状态，并在下一次调用时从已安装脚本重新初始化；覆盖异常后状态重置 | IN_PROGRESS（针对“进程内多个详情持续失败、重启进程后恢复”增加恢复逻辑；JVM 定向回归与 App Debug 构建通过，等待用户真机复验） |
| S3-00S | 阅读器沉浸全屏；点击画面中央呼出/收起工具层；按本话页码拖动定位；快捷跳转上一话/下一话且保持同一阅读画布 | DONE（默认隐藏系统栏；控制层展开时显示状态栏、隐藏导航栏；详情及阅读器顶部栏使用 safeDrawing inset 避让挖孔区；阅读器顶部栏从上向下滑入，底部工具区从下向上滑入，均带淡入；页码自绘轨道支持点击和拖动；前后话图标置于滑块两侧；翻页/解码方式分组等宽；编译与真机验收记录见 STATUS） |

## 8. Stage 4：同步、体验与发布

| ID | 任务 | 关键交付物与验收 | 前置 | 状态 |
| --- | --- | --- | --- | --- |
| S4-01 | Proto DataStore 设置 | 外观、阅读、网络设置与迁移；配置变更与进程恢复有测试 | S3-08 | TODO |
| S4-02 | WebDAV 备份恢复 | 格式版本、预览、冲突、安全默认值；往返和旧版本恢复测试 | S4-01 | TODO |
| S4-03 | Material 3 Adaptive | Navigation Rail、列表详情、折叠屏；手机与宽屏关键布局验收 | S3-08 | TODO |
| S4-04 | 无障碍与国际化 | TalkBack、字体缩放、键盘、简繁体/英文；关键页面与操作覆盖 | S4-03、S4-08 | IN_PROGRESS（用户要求提前完成简体中文 UI 支持；完整无障碍与其它语言仍未开始） |
| S4-05 | 性能基线 | Macrobenchmark、Baseline Profile、回归阈值；记录基线设备与波动范围 | S4-08 | TODO |
| S4-06 | CI 与供应链 | Wrapper 校验、测试、Lint、SBOM、许可证报告；CI 与本地结果一致 | S3-08 | TODO |
| S4-08 | 首页信息架构与视觉对齐 | 对照 S3-00 基线完成原生视觉规范和首页实现；最近阅读、收藏更新、本地入口、来源探索与加载/空/错误状态符合产品规划。用户指定提前交付首屏小切片，依赖未满足这一事实保持显式记录 | S3-01、S3-00 | IN_PROGRESS（用户要求提前执行首页/启动页首版） |
| S4-10 | 漫画详情页 Venera 对齐 | 详情主视觉、作品信息、阅读/收藏入口、章节管理与来源支持的互动内容按上游能力逐步对齐；不伪造来源不支持的数据 | S3-00、S3-01 | IN_PROGRESS（用户指定优先修整详情页） |
| S4-09 | 全 App Venera 对照审计与差距收敛 | 复核 S3-00 清单、补齐差距并提交证据；功能、页面逻辑、设计三个维度分别达到至少 90% | S3-08、S4-01 至 S4-06、S4-08 | TODO |
| S4-07 | 发布准备 | 图标、包名、签名、隐私、GPL 义务、Release 文档；RC 门禁与全 App 对齐结果已通过 | S4-09 | TODO |

S4-04 小任务：

| ID | 交付 | 状态 |
| --- | --- | --- |
| S4-04A | 全 App 自有界面中文支持：为用户可见的导航、按钮、标题、空/错/加载状态和无障碍描述提供简体中文文案/资源；保留漫画源返回的作品名、章节名及用户创建内容原文；覆盖首页、搜索、探索、详情、阅读器、书架、来源及通用系统界面 | DONE（核心页面与下载通知中文化；测试源码编译通过；无障碍与其它语言留待 S4-04 后续） |

S4-10 小任务：

| ID | 交付 | 状态 |
| --- | --- | --- |
| S4-10A | 重做详情首屏层级与作品操作；提供开始阅读、简介展开、章节检索/排序/分组筛选及多选下载；来源元数据在中文 UI 中正确呈现 | DONE（详情 JVM 回归与 App Debug 构建通过；未做设备视觉复核） |
| S4-10B | 扩展并核对源契约支持的详情附加内容（推荐、评分、评论、来源网页）；只在来源返回且实现可用时展示，补解析及 UI 接线 | DONE（详情可展示并导航至推荐作品、显示评论预览/评分元数据/来源网页；Source API 与详情回归、App 测试源码编译及 Debug 构建通过） |
| S4-10A2 | 详情页沉浸式封面折叠栏：移除“漫画详情”和刷新操作栏，使用无底色返回图标；封面铺满顶部并随滚动收起，作品名过渡到只含返回和标题的紧凑栏；以内容保留式下拉刷新替代按钮；信息项采用紧凑标签和值卡片，标签分组采用彩色 chips | DONE（详情 JVM 单测和 App Debug 构建通过；用户真机视觉确认通过） |
| S4-10A3 | 收紧详情章节目录：搜索改为次要图标入口，章节改为每行三项网格、移除普通列表序号和逐行下载按钮；底部主按钮优先续读历史中最近章节及其已保存页码，无进度时才从首章开始 | IN_PROGRESS（详情测试源码编译与 App Debug 构建通过；等待用户真机验证） |
| S4-10A4 | 将详情中部作品信息与作者/题材标签组并入封面主视觉，以半透明信息卡片和标签呈现并随封面折叠；按内容量自适应封面高度 | DONE（`:feature:details:compileDebugKotlin :feature:details:compileDebugUnitTestKotlin :app:assembleDebug` 通过；设备视觉确认由用户执行） |
| S4-10A5 | 将作品名/来源移到简介上方，封面收藏入口移至右下角并在完全折叠栏显示收藏图标；依据来源嵌套章节组提供版本切换；按阅读历史区分当前章高亮和已读章置灰 | DONE（补齐来源 `isAppVersionAfter` 兼容能力并以 1.3.1 兼容级触发拷贝漫画分组返回；封面内联收藏、交换更新/标签顺序以让变长标签行避开收藏按钮；收藏状态统一为未选中书签轮廓、选中实心书签；版本筛选标题及全量列表的组标题明确标识为版本；JDK 17 下 Source/History/Details 测试源码与 Room AndroidTest 源码编译、`:app:assembleDebug` 通过；设备视觉验收由用户执行） |
| S4-10C | 接入源支持的详情操作与完整评论流程：漫画点赞/评分、评论分页/发送/回复、评论点赞/投票；按脚本能力显示入口并处理登录失效/不支持错误 | TODO（当前 `SourceCore` / `ComicCatalog` 尚无这些类型化操作契约） |

S4-08 验收要求：

| ID | 小任务 | 状态 |
| --- | --- | --- |
| S4-08A | AndroidX SplashScreen 启动页，兼容 API 26+、明暗系统主题，使用本项目图标并正确转入主主题 | DONE（`:app:assembleDebug` 通过） |
| S4-08B | 首页首版：以 Compose 建立首页视觉层级、搜索/来源/探索/书架入口；读取已有阅读历史、收藏和本地漫画，不展示虚构作品 | DONE（`:feature:home:testDebugUnitTest :app:assembleDebug` 通过；Xiaomi 首屏已查看，滚动/点击因 MIUI 注入权限限制未验证） |
| S4-08C | 按已读取的上游源码完成首页其余信息结构、动态来源探索/更新摘要、错误恢复、视觉对照及设备验收 | TODO；仍依赖 S3-00/S3-01 |
| S4-08D | 将首页、探索、书架、来源作为四个根级 Tab 切换内容并保持共享底部导航；搜索、详情和阅读器继续使用内部导航 | DONE（App Debug 与 AndroidTest 源码编译通过；真机交互待用户验收） |

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

- **S2-07D / D07 — IN_PROGRESS。** Xiaomi API 36 的 `dataSync` 类型断言和真实慢速下载通知/暂停/继续/移除闭环均已通过；API 30 ARM64 模拟器的来源脚本/Reader 导航与本地导入入口测试各 1/1 通过。API 26 镜像尚不可用，且 API 30 的 SAF 目录选择器未完成实际导入；不得将 D07 或 Stage 2 标为完成。后续仍需按 `docs/reviews/stage-02-device-checklist.md` 完成 API 26 的启动、来源调用、实际目录导入、阅读、下载通知闭环。

- **Stage 2 退出复核（2026-10-02）— 未通过门禁。** 本轮复核未发现新的代码缺陷，但 D07 的 API 26 运行环境与闭环证据仍缺；完整验收矩阵及剩余风险见 `docs/reviews/stage-02-exit-review-2026-10-02.md`。Stage 2 继续 `IN_PROGRESS`。
