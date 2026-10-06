# Venera Native 架构与模块边界

## 1. 目标

本文描述代码必须遵守的架构边界。`PROJECT_PLAN.md` 描述长期愿景，本文描述“代码应放在哪里、谁可以依赖谁”。当前尚未创建的模块是目标边界，不代表可以提前创建空壳。

## 2. 总体数据流

```text
Compose Screen
    │ UiAction
    ▼
ViewModel / Reducer
    │ UseCase or Repository contract
    ▼
Repository implementation
    ├── Room / DataStore
    ├── Source Runtime
    ├── Network / Image pipeline
    └── File / Archive
    │
    ▼
immutable UiState
```

原则：

- UI 不认识具体数据源实现。
- Repository 是 Feature 获取业务数据的入口。
- Runtime 不认识 Compose 页面。
- 数据模块不触发导航。
- 持久化数据以数据库或 DataStore 为事实来源，ViewModel 不维护第二套长期状态。

## 3. 当前模块

| 模块 | 当前职责 | 可依赖 |
| --- | --- | --- |
| `:app` | MainActivity、应用 Theme、根装配与首页/书架两个根级 Tab 导航壳；搜索、探索、来源由页面入口进入 | Feature、Core、实现模块 |
| `:core:model` | 稳定领域 ID 与跨层模型（包括远端/本地 `ChapterRef`） | 尽量只依赖 Kotlin |
| `:core:archive` | ZIP/CBZ、7z/CB7 只读访问接口与 Commons Compress 适配 | Commons Compress、XZ、Android Framework |
| `:data:source` | 上游来源目录的获取与解析、来源包远程/本地安装、启停与卸载；协调磁盘存储与运行时加载 | `:core:model`、`:core:network`、`:source:api` |
| `:core:network` | OkHttp 客户端基线、Dispatcher 与通用网络错误 | OkHttp |
| `:core:image` | 漫画图片管线：`ComicImageRequest` 与稳定缓存键、自建 Fetcher（走 `:core:network` 的共享 OkHttp）、Coil 磁盘缓存、图片头部尺寸解析、封面渲染的 `ComicImage`，以及从 `:feature:reader` 迁来的页面解码与分块 | `:core:model`、`:core:network`、Coil 3.6.3、Compose |
| `:core:navigation` | 路由契约：`AppRoute` 与它的字符串编码（供 `rememberSaveable` 使用），不含导航库 | `:core:model` |
| `:data:comic` | 漫画数据访问：可用来源的判定（已安装 + 已启用 + 声明能力）、详情与章节的读取，以及来源分页到 Paging 3 的适配 | `:core:model`、`:data:source`、`:source:api`、Paging |
| `:core:database` | Room 持久化：阅读历史/进度、收藏、下载、本地库索引、搜索历史和轻量页面偏好实体与 DAO，以及 `schemas/<version>.json` 基线。**不依赖 `:core:model`**，主键一律用字符串列 | Room 2.8.5 |
| `:data:history` | 阅读历史与恢复：保存每漫画/章节进度、最近阅读按漫画返回最新章节、实体↔领域映射、节流保存与 `flush()` | `:core:model`、`:core:database` |
| `:data:search` | 最近搜索词仓库：规范化查询词、去重置顶、有界保留及单项删除/清空；数据保存在 Room | `:core:database`、Coroutines |
| `:data:settings` | 轻量页面选择偏好的类型无关持久化仓库；为 S4-01 设置模块前的页面状态切片提供 Room 存储 | `:core:database` |
| `:data:collection` | 书架收藏：隐式“全部”视图、用户收藏夹及多对多归属、排序查询、更新标记（`CollectionRepository` / `UpdateMarker`）。Room 是唯一事实来源，UI 只订阅 Flow | `:core:model`、`:core:database`、`:data:comic`（仅 `RemoteChapterProbe` 的实现） |
| `:data:local` | SAF 目录/归档授权、扫描、自然排序、本地索引及有界页面缓存物化；按需复用图片头尺寸解析 | `:core:model`、`:core:database`、`:core:archive`、`:core:image`、DocumentFile |
| `:data:download` | 下载队列与离线阅读：页级任务与状态机、并发限额（全局 4 / 单源 2）、原子写与图片头部校验、崩溃恢复扫描；`OfflineFirstPageProvider` 在章节下载完整时从文件系统提供页面，否则委托来源提供器。`worker/` 使用 WorkManager、前台通知与操作 Receiver；业务队列仍以 Room 为唯一事实来源，章节状态由页状态派生 | `:core:model`、`:core:database`、`:core:image`；Worker 子包另依赖 WorkManager 与 AndroidX Core |
| `:core:designsystem` | Theme 与设计 Token | Compose、`:core:model`（按需） |
| `:feature:home` | Compose 首页：订阅最近阅读、收藏和本地漫画，呈现搜索、来源管理和书架入口，并把续读意图交给根导航 | Design System、`:core:model`、`:core:image`、`:data:history`、`:data:collection`、`:data:local` |
| `:feature:library` | 书架页：收藏、下载队列与本地目录 tab；收藏支持文件夹筛选、四种排序、更新标记，本地页接入 SAF 导入、逐章阅读和移除；保存用户最后选择的 tab、收藏夹和排序 | Design System、`:core:model`、`:core:image`、`:data:collection`、`:data:download`、`:data:local`、`:data:settings` |
| `:feature:details` | 漫画详情：元数据、封面槽位、简介与章节列表（分组、显示顺序、刷新）、收藏和批量下载；按作品保存章节排序与版本选择，从阅读历史恢复最近章节 | Design System、`:core:model`、`:core:image`、`:data:comic`、`:data:collection`、`:data:download`、`:data:history`、`:data:settings`、`:source:api` |
| `:feature:explore` | 单源探索：来源与探索页选择、该页的分页内容（列表 / 分区 / 混合三种形状） | Design System、`:data:comic` |
| `:feature:search` | 单源搜索和聚合搜索：来源选择、来源能力声明的动态筛选、分页结果、按来源分组预览和隔离错误状态；带可恢复的搜索历史并保存最近来源/聚合/筛选选择 | Design System、`:data:comic`、`:data:search`、`:data:settings`、`:core:image` |
| `:feature:reader` | 阅读器：使用统一 `ChapterRef` 载入页、方向切换、页码、预取及渲染进度；Compose 内管理每页缩放/平移手势状态 | Design System、`:core:model`、`:core:image` |
| `:feature:sources` | 来源管理：分开展示可用目录和已安装来源；安装、启停、卸载，以及加载 / 空 / 失败 / 成功页面状态 | Design System、`:core:model`、`:data:source` |
| `:source:api` | Runtime、包、调用和结果契约，Feature/Data 使用的 `SourceCore` 五个能力契约，以及上游协议的编解码（`protocol` 包） | `:core:model`、kotlinx.serialization JSON（仅树 API，不用编译器插件） |
| `:source:core` | `SourceCore` 的引擎实现：读取源声明的能力，把类型化操作映射成上游调用并解析响应。它只依赖 Runtime 契约，因此换引擎不影响它 | `:source:api`、kotlinx.serialization JSON |
| `:source:engine` | QuickJS 主运行时、受控 Host binding，以及待移除的 AndroidX JavaScriptEngine 兼容实现 | `:source:api`、受控 Host API |
| `:source:network` | 动态来源 HTTP、每来源 Cookie 与并发策略 | `:source:api`、`:core:network`、`:core:model` |

表中“职责”是边界，不表示功能已经完成；当前处于 Stage 2，已落地范围见 `docs/STATUS.md`。

S0-07 按本文件第 5 节的模块创建准则删除了两个零引用模块：`:core:common`（`AppResult`/`AppError`
没有任何生产或测试引用）与 `:core:navigation`（`AppRoute` 唯一声明，`Navigation 3` 只被它使用，
app 声明的依赖也没有被代码使用）。它们不属于废弃设计，而是“先建壳后接业务”的产物：
`:core:common` 会在出现第一个真实的 Result/错误聚合需求时重建，`:core:navigation`
会在 S1-03 首次需要类型安全 Route 时重建。

S1-05 已建立 `:core:image`，S0-06 的解码代码随之从 `:feature:reader` 的 `image` 包迁入
（`dev.veneranative.feature.reader.image` → `dev.veneranative.core.image` 的 `tiling` / `decode`
子包），**类型名与公开签名不变**，因此阅读器 UI 契约不受影响。ADR-0004 记录的这项架构债务已解除。

`:feature:sources` 直接依赖 `:data:source` 的仓库契约（`SourceRepository`），`:feature:reader` 直接依赖
`:data:history` 的 `HistoryRepository`。这是本阶段有意的取舍：在只有一个实现、且没有依赖注入框架的情况下，
再把契约拆成一个只有接口的模块只是形式主义。出现第二个数据实现或引入 DI 时，把契约拆出去并让 Feature
只依赖契约。

`:core:database` 刻意不依赖 `:core:model`：Room 实体若反向依赖领域模型，换一层值对象就要改数据库。
代价是 `:data:history` 承担了全部实体↔领域转换，收益是持久化层保持纯 Schema。
同理，`:core:image` 不依赖 `:source:network`，因此 per-source Cookie 由 `:app` 的 `SourceCookieImageAuth`
适配成 `ComicImageAuthProvider` —— `:app` 是唯一允许同时看到两边的地方。

## 4. 目标依赖方向

```text
                         ┌──────────────────┐
                         │       :app       │
                         └────────┬─────────┘
                                  │ assemble
              ┌───────────────────┼───────────────────┐
              ▼                   ▼                   ▼
         :feature:*            :data:*            platform impl
              │                   │
              │ contracts/models  │ implements
              └──────────┬────────┘
                         ▼
                 :core:* / domain API

:feature:* ──> :source:api <── :source:engine
                              ├──> :source:network
                              ├──> :source:parser
                              └──> :source:webview
```

禁止方向：

```text
:core:*       -X-> :feature:*
:data:*       -X-> :feature:*
:source:api   -X-> :source:engine
:feature:a    -X-> :feature:b
:source:*     -X-> :app
```

Feature 间跳转通过类型安全 Route 或 Feature Entry 契约完成，不通过直接依赖。承载 Route 的模块
`:core:navigation` 已在 S1-03 创建，用于承载当前的 `AppRoute` 契约。

## 5. 模块创建准则

创建新模块前必须同时满足：

1. 有当前阶段中的实际交付物，不是预留空壳。
2. 职责可以用一句话说明。
3. 有明确调用方和依赖方向。
4. 能独立测试或显著隔离平台/第三方实现。
5. 不会只包含一个无复用价值的简单类。

推荐模块前缀：

- `core`：跨功能稳定基础能力。
- `feature`：用户可见的页面或完整交互入口。
- `data`：领域数据聚合与 Repository 实现。
- `source`：脚本来源协议、运行时和 Host 能力。
- `benchmark`：性能与 Baseline Profile。

## 6. API 与 implementation 边界

使用 `api` 的条件：

- 依赖类型有意出现在当前模块公开签名中；
- 调用方必须在编译期感知该类型；
- 该契约具有稳定性承诺。

其他情况使用 `implementation`。不要为了“少写一行依赖”传播传递依赖。

公开契约要求：

- 不泄漏 OkHttp、Room、Coil、JavaScriptEngine 等具体实现类型。
- 不泄漏 Android `Context`、`Activity` 或可变集合。
- 错误使用稳定领域类型。
- 长时间操作必须可取消。
- 二进制对象必须有所有权、大小限制和关闭规则。

## 7. MVI/UDF 页面模板

复杂 Feature 推荐结构：

```text
feature/<name>/
└── src/main/kotlin/.../
    ├── <Name>Route.kt
    ├── <Name>Screen.kt
    ├── <Name>ViewModel.kt
    ├── <Name>UiState.kt
    ├── <Name>Action.kt
    └── component/
```

职责：

- `Route`：获取 ViewModel、收集状态、连接导航回调。
- `Screen`：无状态渲染，支持 Preview 和 Compose Test。
- `ViewModel`：编排操作、Reducer 和状态恢复。
- `UiState`：页面完整可渲染状态。
- `Action`：用户意图，不直接表达实现细节。

简单静态页面不必机械拆成全部文件；当状态和动作增长时再拆分。

## 8. Source Runtime 边界

### 8.1 调用路径

```text
Feature / Repository
       │ typed SourceCall
       ▼
:source:api
       │
       ▼
:source:engine
       │ allow-listed Host request
       ├──────────────> :source:network
       ├──────────────> :source:parser
       └──────────────> :source:webview
```

### 8.2 生命周期

Runtime 最终需要明确以下层级：

- App Runtime：管理全局资源与关闭。
- Source Session：单来源隔离环境。
- Invocation：单次调用 ID、超时、取消和结果。
- Host Request：Invocation 内部派生的受控异步操作。

取消父级必须向下传播；子调用完成后不得持有 Activity 或 Feature 引用。

### 8.3 安全边界

脚本仅能调用显式注册的 Host API。禁止暴露：

- Android `Context` 或任意 Framework 对象；
- Java/Kotlin 反射；
- 任意路径文件读写；
- 任意 Intent；
- 未限制大小的二进制传输；
- 未脱敏的应用日志；
- 绕过证书校验的网络客户端。

当前允许列表包含文本 HTTP 请求 `http.request` 与受限计时器 `timer.sleep` / `timer.cancel`。
计时器仅用于兼容真实来源 `setTimeout` 等待限流恢复；每来源调用最多 8 个活动计时器，单次等待不超过 120 秒，取消必须释放宿主等待任务。

## 9. 网络与图片边界

目标客户端：

- `AppHttpClient`：源仓库、版本检查、WebDAV。
- `SourceHttpClient`：来源动态请求和每源 Cookie。
- `ImageHttpClient`：与 Coil 共享连接池的图片请求。

`ComicImageRequest` 必须包含影响响应内容的来源、Method、Header、Body 和 transform 标识；Cache Key 必须覆盖这些鉴权差异。

Retrofit 可以用于固定的应用服务，但不得成为动态漫画源请求的核心抽象。

命名对应关系：上表是目标形态，当前实现为 `SourceNetworkExecutor`（每来源客户端、Cookie、并发上限、
响应大小限制）与 `SourceNetworkHostApi`（Host API 允许列表与错误映射）；`ImageHttpClient`
随 `:core:image` 与 Coil 在 S1-05 引入，`AppHttpClient` 随 `:core:network` 在需要固定服务时扩展。

## 10. 存储边界

- Room：收藏、历史、进度、下载、已安装来源等结构化持久状态。
- Proto DataStore：全局设置和阅读器偏好。
- Android Keystore：需要保护的密钥材料。
- App 私有文件：下载页、缓存和来源包。
- SAF：用户选择的本地漫画目录或压缩包。

SharedPreferences 不用于新功能。数据库实体不得直接传到 UI；通过领域模型或 Projection 转换。

## 11. 导航边界

- 单 Activity。
- Route 使用可序列化、类型安全的小型参数。
- 大对象、Bitmap、脚本文本不得作为导航参数。
- 详情通过 `ComicKey` 定位；章节通过稳定 `ChapterKey` 定位。
- Feature 暴露导航意图或 Entry，不直接依赖目标 Feature。
- Reader 全屏状态由根布局响应，不让 Reader 操纵 MainActivity 内部实现。

## 12. 测试归属

| 测试 | 位置 |
| --- | --- |
| 纯模型、Reducer、Mapper | 对应模块 `src/test` |
| Room、文件、Android Framework | 对应模块 `src/androidTest` 或 Robolectric（有明确理由时） |
| Source 协议 fixture | `:source:testing` 或 Runtime 测试资源 |
| Compose 状态与交互 | Feature 的 Compose Test |
| 跨模块用户链路 | `:app` integration/androidTest |
| 启动、滚动、Reader 性能 | `:benchmark` |

测试不得依赖真实漫画站点、真实用户账号或不可重复的公网状态。

## 13. 需要 ADR 的变化

以下变化必须先写或同步 ADR：

- 更换 JavaScript 引擎或增加 fallback。
- 改变 Feature/Data/Core 依赖方向。
- 引入新的数据库、网络或图片主框架。
- 改变下载执行模型。
- 改变超长图解码策略。
- 复用 GPL-3.0 上游实现或资源。
- 修改备份格式的兼容承诺。

### 应用级共享网络与下载执行

`VeneraApplication` 持有进程级 OkHttp、来源 Cookie 仓库、Coil 缓存与图片管线；阅读器和下载 Worker 使用同一组实例，
避免来源图片因 Cookie/Referer 不一致而无法保存。Application 同时安装 `DownloadEnvironment`，所以 WorkManager
在没有 Activity 的冷启动进程里也能创建 Repository 和下载器。`AppGraph` 只关闭本图拥有的 Runtime 与内存缓存，
不关闭仍可能被后台 Worker 使用的网络和图片资源。WorkManager 前台服务类型在 App Manifest 与 API 34+ 权限中声明。

### Stage 1 质量整改：依赖生命周期

根依赖由 `AppGraph` AndroidViewModel 装配并跨配置变更保留；销毁时关闭 runtime、图片缓存与网络调用。来源仓库串行修改，冷启动按启用状态恢复脚本。禁用和卸载清理来源网络会话；Core 从当前实例探测能力，避免升级后的旧声明缓存。
