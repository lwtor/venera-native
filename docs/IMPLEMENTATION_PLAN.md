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

## 3. 总体里程碑

| 阶段 | 目标 | 退出条件 | 状态 |
| --- | --- | --- | --- |
| Stage 0 | 验证 JS 漫画源和阅读器两项最高风险技术 | JavaScriptEngine 与大图方案形成有证据的 ADR | IN_PROGRESS |
| Stage 1 | 打通网络漫画核心阅读闭环 | 测试源可完成搜索、详情、选章、阅读和恢复进度 | TODO |
| Stage 2 | 完成书架、下载和本地阅读 | 离线可浏览书架并阅读下载或本地漫画 | TODO |
| Stage 3 | 补齐来源扩展能力 | Core/Extended 协议测试通过，Advanced 有支持矩阵 | TODO |
| Stage 4 | 同步、自适应、性能与发布 | RC 通过迁移、压力、无障碍与发布检查 | TODO |

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

### S1-08 自有引擎（QuickJS）落地 — TODO

依赖：S1-02。**阻塞 S1-03**。

背景：ADR-0008 已确认 WebView 系引擎在没有 MessagePort 的设备上无法提供异步 Host API，
而参考设备正是这种情况。

交付物：

- `:source:engine` 的自有引擎实现，满足 `:source:api` 契约（引擎实现由装配层选择）。
- 仓库内测试源（含 `async`/`await` 网络调用）跑通 Explore、Search、Detail、Chapters、Pages。
- 按 ADR-0008 第 3 节判据产出的数据：调用取消、调用超时、二进制通道、ABI 与体积、许可证登记。
- 通过后按 ADR-0008 §2.4 处置现有 WebView 实现，并更新 ADR-0002 的引擎状态。

### S1-03 探索与搜索纵向切片 — TODO

依赖：S1-01、S1-02、S1-08。

计划模块：

- `:data:comic`
- `:feature:explore`
- `:feature:search`

交付物：

- 单源探索、搜索、页码或 token 分页。
- Paging 3 与来源分页适配。
- 单源失败、重试和取消。
- 搜索结果跳转详情的类型安全 Route。

### S1-04 漫画详情与章节 — TODO

依赖：S1-03。

计划模块：`:feature:details`、`:data:comic`。

交付物：

- 基础元数据、封面、简介和章节列表。
- 分组、排序与刷新。
- 章节选择生成统一 PageProvider。
- 加载、空、部分失败和来源失效状态。

### S1-05 Coil 漫画图片管线 — TODO

依赖：S0-06、S1-01。

计划模块：`:core:image` 或在实际复用边界明确后命名。

交付物：

- Coil 3、共享 OkHttp 连接池。
- `ComicImageRequest`、自定义 Fetcher 和稳定 Cache Key。
- Header、Referer、Cookie、POST 图片的测试。
- 鉴权不同的请求不会错误复用缓存。

### S1-06 Room 历史与阅读进度 — TODO

依赖：S1-01、S0-05。

计划模块：

- `:core:database`
- `:data:history`

交付物：

- ReadingHistory、ReadingProgress Entity/DAO。
- Schema 导出、Migration 基线和 Repository Test。
- 进度节流保存，退出或进入后台强制落盘。
- 恢复章节和页码。

### S1-07 核心闭环集成 — TODO

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

| ID | 任务 | 关键交付物 | 前置 |
| --- | --- | --- | --- |
| S2-01 | 本地收藏与书架 | 收藏夹、排序、更新标记、Room 单一事实来源 | S1-07 |
| S2-02 | 下载领域与持久队列 | 页级任务、暂停/继续/取消、恢复扫描 | S2-01 |
| S2-03 | Android 后台下载执行 | UIDT/Foreground Worker 策略、通知、约束 | S2-02 |
| S2-04 | 离线阅读整合 | 下载内容脱离来源仍可阅读 | S2-02 |
| S2-05 | SAF 本地目录导入 | 权限持久化、自然排序、封面识别 | S1-07 |
| S2-06 | CBZ/ZIP 与 7z 系列 | 索引缓存、ArchiveEntry 页面、错误恢复 | S2-05 |
| S2-07 | Stage 2 集成验收 | 飞行模式下书架、下载、本地阅读和进度 | S2-01 至 S2-06 |

## 7. Stage 3：来源扩展能力

| ID | 任务 | 关键交付物 |
| --- | --- | --- |
| S3-01 | 分类、排行和聚合搜索 | 能力驱动 UI、并发限制、单源失败隔离 |
| S3-02 | 来源设置与私有数据 | 强类型设置、每源隔离、敏感字段保护 |
| S3-03 | 登录与 Cookie | 密码登录、Cookie 导入、Keystore 策略 |
| S3-04 | WebView 登录 | 隔离 WebView、Cookie 同步、验证码流程 |
| S3-05 | 收藏与账户能力 | 远端收藏夹、本地映射、一致性处理 |
| S3-06 | 评论、评分与交互 | 可选能力、分页、错误和权限状态 |
| S3-07 | 高级图片处理 | 二进制变换、解密允许边界、缓存语义 |
| S3-08 | 兼容矩阵与调试工具 | Source Contract Suite、脱敏日志、导出诊断 |

## 8. Stage 4：同步、体验与发布

| ID | 任务 | 关键交付物 |
| --- | --- | --- |
| S4-01 | Proto DataStore 设置 | 外观、阅读、网络设置与迁移 |
| S4-02 | WebDAV 备份恢复 | 格式版本、预览、冲突、安全默认值 |
| S4-03 | Material 3 Adaptive | Navigation Rail、列表详情、折叠屏 |
| S4-04 | 无障碍与国际化 | TalkBack、字体缩放、键盘、简繁体/英文 |
| S4-05 | 性能基线 | Macrobenchmark、Baseline Profile、回归阈值 |
| S4-06 | CI 与供应链 | Wrapper 校验、测试、Lint、SBOM、许可证报告 |
| S4-07 | 发布准备 | 图标、包名、签名、隐私、GPL 义务、Release 文档 |

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
