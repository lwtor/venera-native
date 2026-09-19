# Venera Native 项目规划

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 项目名称 | Venera Native |
| 项目类型 | Android 原生漫画阅读器 |
| 当前阶段 | 架构与开发规划 |
| 目标平台 | Android 手机、平板、折叠屏、ChromeOS |
| UI 技术 | Jetpack Compose + Material 3 |
| 架构方向 | 多 Module + 分层架构 + MVI/UDF |
| 上游项目 | https://github.com/venera-app/venera |

Venera Native 是一个受 Venera 启发的非官方 Android 原生重构项目。项目以兼容 Venera 漫画源生态为重要目标，同时重新设计领域模型、数据层、下载系统和阅读器，使其符合现代 Android 平台能力与长期维护要求。

## 2. 产品目标

### 2.1 核心目标

1. 使用 Android Native 技术实现高性能、低内存占用的漫画阅读体验。
2. 支持 JavaScript 漫画源，并尽可能兼容现有 Venera 漫画源协议。
3. 支持网络漫画、本地目录、CBZ、ZIP、CB7 和 7z 漫画。
4. 支持探索、分类、排行、搜索、详情、章节、收藏、历史和下载完整闭环。
5. 支持来源账户登录、Cookie、评论、评分、点赞等可选能力。
6. 采用清晰的多模块边界，保证功能可测试、可替换、可持续扩展。
7. 适配手机、平板、折叠屏、横屏和 ChromeOS 窗口化环境。

### 2.2 非目标

首个正式版本不包含以下目标：

- iOS、Windows、macOS 或 Linux 客户端。
- Kotlin Multiplatform 或 Compose Multiplatform。
- 自建漫画内容服务或托管受版权保护的漫画内容。
- 第一阶段即完全复刻原版所有界面和边缘功能。
- 让漫画源脚本直接访问 Android Framework、文件系统或任意系统能力。

## 3. 架构原则

### 3.1 分层

```text
Compose UI
    │ UiAction
    ▼
ViewModel + Reducer
    │ UseCase
    ▼
Domain Repository Contract
    │
    ▼
Repository Implementation
    ├── Room
    ├── DataStore
    ├── OkHttp
    ├── Source Runtime
    └── File / Archive
```

采用 UI、Domain、Data 三层，但避免为每个小功能机械创建三套模块。Domain 层只承载跨页面复用或具有明确业务含义的用例。

### 3.2 状态管理

每个复杂页面使用以下约定：

```kotlin
data class ReaderUiState(...)

sealed interface ReaderAction

@HiltViewModel
class ReaderViewModel @Inject constructor(...) : ViewModel() {
    val uiState: StateFlow<ReaderUiState>
    fun onAction(action: ReaderAction)
}
```

- Compose 只负责渲染 `UiState` 和上报 `UiAction`。
- ViewModel 负责编排用例和产生不可变状态。
- Repository 是数据层唯一入口。
- Room 是收藏、历史、进度、下载等持久数据的单一事实来源。
- 页面导航、错误提示等事件优先建模为可消费状态，避免无约束的事件总线。
- 不引入重量级第三方 MVI 框架，先建立项目内轻量约定。

### 3.3 模块依赖规则

- `:app` 可以依赖 Feature 和 Data 实现模块。
- Feature 依赖 Domain、Model、Design System 和必要的 Core API。
- Feature 之间禁止直接依赖。
- Data 模块实现 Domain 中的 Repository 接口。
- `:source:engine` 只能通过受控 Host API 调用网络、存储和 UI 请求。
- Core 模块禁止反向依赖 Feature 或 App。
- 默认使用 `implementation`，只有稳定公共契约才能使用 `api`。

## 4. Module 规划

### 4.1 入口与构建

| Module | 职责 |
| --- | --- |
| `:app` | Application、MainActivity、根导航、Hilt 装配、应用级配置 |
| `:build-logic` | Convention Plugins、Compose/Kotlin/Android 公共构建配置 |
| `:benchmark` | Macrobenchmark、Baseline Profile、启动和滚动性能测试 |

`:app` 不承载业务页面，只作为唯一 APK/AAB 入口。

### 4.2 Core 模块

| Module | 职责 |
| --- | --- |
| `:core:model` | 跨层稳定领域模型、ID 值对象、分页模型 |
| `:core:common` | Result、Dispatcher、时间、日志接口和通用扩展 |
| `:core:designsystem` | Material 3 Theme、Token、图标和公共组件 |
| `:core:ui` | 加载、空状态、错误页、通用漫画卡片和 Compose 工具 |
| `:core:navigation` | 类型安全 Route、导航契约和 Feature Entry 接口 |
| `:core:network` | OkHttp 配置、拦截器、错误映射、连通性 |
| `:core:database` | Room Database、公共 Converter、Migration |
| `:core:datastore` | Proto DataStore、全局设置和迁移 |
| `:core:filesystem` | SAF、DocumentFile、缓存目录和文件抽象 |
| `:core:security` | Android Keystore、敏感信息加密和脱敏日志 |
| `:core:testing` | Fake、Fixture、Coroutine TestRule、测试 DSL |

### 4.3 漫画源模块

| Module | 职责 |
| --- | --- |
| `:source:api` | 漫画源描述、能力接口、调用和返回模型 |
| `:source:engine` | JavaScript 加载、隔离环境、调用调度、超时和取消 |
| `:source:network` | 面向脚本的 HTTP、Cookie、Header、二进制响应能力 |
| `:source:parser` | HTML DOM、CSS Selector、编码和加密桥接 |
| `:source:webview` | WebView 登录、验证码、Cookie 同步和浏览器隔离 |
| `:source:testing` | 漫画源协议测试、兼容性样例和沙箱测试 |

### 4.4 Data 模块

| Module | 职责 |
| --- | --- |
| `:data:comic` | 漫画详情、章节、远端页面、搜索与探索数据 |
| `:data:library` | 本地收藏、网络收藏映射和书架管理 |
| `:data:history` | 阅读历史、章节进度和最近阅读 |
| `:data:download` | 下载队列、文件布局、恢复扫描和状态持久化 |
| `:data:source` | 漫画源安装、更新、启停、账户和源设置 |
| `:data:local` | 本地目录、压缩包索引、封面识别和导入 |
| `:data:settings` | 阅读器、外观、网络等用户设置 |
| `:data:sync` | WebDAV 备份、恢复、冲突策略和同步记录 |

### 4.5 Feature 模块

| Module | 页面范围 |
| --- | --- |
| `:feature:home` | 首页、最近阅读、更新摘要 |
| `:feature:explore` | 来源探索页、分类和排行 |
| `:feature:search` | 单源搜索、聚合搜索、筛选和搜索历史 |
| `:feature:details` | 详情、标签、章节、推荐、评分和评论入口 |
| `:feature:reader` | 网络、本地和下载漫画统一阅读器 |
| `:feature:library` | 本地收藏、来源收藏夹和追更 |
| `:feature:downloads` | 下载队列、已下载内容和存储管理 |
| `:feature:local` | 本地漫画导入、扫描和管理 |
| `:feature:sources` | 漫画源仓库、安装、更新、编辑和调试 |
| `:feature:login` | 账号密码、Cookie、WebView 登录 |
| `:feature:settings` | 外观、阅读器、网络、同步、关于和调试设置 |

## 5. 技术选型

| 领域 | 方案 | 说明 |
| --- | --- | --- |
| 语言 | Kotlin | 禁止新建 Java 业务代码 |
| UI | Compose + Material 3 | Edge-to-edge、动态颜色、深色模式 |
| 自适应 | Material 3 Adaptive | 手机、平板、折叠屏和窗口化 |
| 导航 | Navigation 3 | 类型安全 BackStack；通过 core 接口隔离版本变化 |
| DI | Hilt + KSP | 官方推荐方案和编译期校验 |
| 异步 | Coroutines + Flow | 层间数据与状态传递 |
| 数据库 | Room | Schema 导出、自动迁移与显式迁移测试 |
| 偏好 | Proto DataStore | 强类型设置，禁止新增 SharedPreferences |
| 分页 | Paging 3 | 支持页码与 token 两种漫画源分页 |
| 网络 | OkHttp 5 | 动态请求、Cookie、拦截器和 Coil 共享连接池 |
| JSON | kotlinx.serialization | 源桥接、设置和备份格式 |
| HTML | Jsoup | 由 Source Host API 包装后提供给脚本 |
| 图片 | Coil 3 | 自定义 Fetcher、Decoder、Cache Key 和鉴权请求 |
| JS | AndroidX JavaScriptEngine | 首选隔离进程；PoC 后决定 QuickJS fallback |
| 后台任务 | WorkManager | 追更、源更新、备份和可延迟同步 |
| 用户下载 | UIDT + fallback | Android 14+ UIDT；低版本前台 Worker 回退 |
| 文件 | SAF | 用户授权目录，不申请宽泛存储权限 |
| Web | AndroidX WebKit | 来源登录、Cookie 获取和验证码 |
| 测试 | JUnit + Compose Test | 配合 MockWebServer、Room 和 Source Contract Tests |
| 性能 | Macrobenchmark | Baseline Profile、启动、滚动和阅读器帧率 |

初始平台建议：

```text
minSdk: 26
compileSdk: 36
targetSdk: 使用启动开发时可发布的最新稳定版本
JDK: 17 或构建工具要求的更新稳定版本
```

所有依赖版本集中在 `gradle/libs.versions.toml`，只使用稳定版本；必须使用预览版的依赖需单独记录原因和退出方案。

## 6. 漫画源系统

### 6.1 能力模型

漫画源采用可选能力组合，而不是一个包含大量 nullable 方法的巨型接口：

```text
ComicSource
├── ExploreCapability
├── CategoryCapability
├── RankingCapability
├── SearchCapability
├── DetailCapability
├── FavoriteCapability
├── AccountCapability
├── CommentCapability
└── ImageRequestCapability
```

UI 根据能力决定显示哪些入口，避免在 Feature 内判断脚本字段。

### 6.2 Runtime 接口

```kotlin
interface SourceScriptRuntime {
    suspend fun install(source: SourcePackage): InstallResult
    suspend fun invoke(call: SourceCall): SourceResult
    suspend fun cancel(callId: String)
    suspend fun unload(sourceId: String)
}
```

`SourceCall` 使用结构化类型表达操作，禁止 UI 拼接 JavaScript 字符串。

### 6.3 Host API

首批桥接能力：

- UTF-8、Base64、Hex 转换。
- MD5、SHA、HMAC、AES、RSA 等兼容能力。
- HTTP 文本和二进制请求。
- 独立 Cookie 存取。
- HTML DOM 和 CSS Selector。
- 漫画源私有 Key-Value 数据。
- 漫画源自定义设置读取。
- UUID、随机数和受控日志。
- Web 登录请求。
- 受控消息、选择和输入请求。

### 6.4 安全策略

- JavaScript 运行在隔离环境，不暴露 `Context`、反射或 Java 对象。
- 每个源独立 Cookie、私有数据和执行会话。
- 单次调用有超时、取消、最大响应体和最大并发限制。
- 来源安装记录 URL、版本、SHA-256 和更新时间。
- 更新前保留上一个可运行版本，失败时自动回滚。
- 安装页面显示来源地址和脚本能力提示。
- 日志默认隐藏 Cookie、Authorization、密码和 Token。
- Release 构建禁止忽略 TLS 证书错误。
- 源脚本无法读取任意本地文件或发送任意 Android Intent。

### 6.5 兼容策略

兼容工作分为三档：

1. `Core`：探索、搜索、详情、章节和图片。
2. `Extended`：登录、收藏、排行、评论、评分和标签建议。
3. `Advanced`：动态 UI、图片二次处理、源私有设置和特殊认证。

建立公开兼容矩阵和一组固定样例源。每次修改 Runtime 时必须运行协议回归测试。

## 7. 网络与图片架构

### 7.1 网络

漫画源请求地址和方法是动态的，因此不使用 Retrofit 作为核心网络入口。OkHttp Client 按用途配置：

- `AppHttpClient`：源仓库、版本检查和 WebDAV。
- `SourceHttpClient`：漫画源动态请求。
- `ImageHttpClient`：与 Coil 共享连接池，优化图片并发。

需要支持：

- GET、POST、PUT、PATCH、DELETE 和自定义 Method。
- 文本、JSON、表单和二进制请求体。
- 自定义 User-Agent、Referer、Cookie 和 Header。
- 每个来源独立 CookieJar。
- 超时、取消、重试和指数退避。
- 全局及每个来源的并发限制。
- 可选代理与 DNS 配置。
- WebView Cookie 同步。
- 统一错误模型和脱敏网络日志。

### 7.2 图片

定义统一请求模型：

```kotlin
data class ComicImageRequest(
    val sourceId: String?,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val transformKey: String?
)
```

Coil 自定义 Fetcher 负责：

- 执行漫画源提供的请求规则。
- Cookie、Referer、Header 和 POST 图片。
- 稳定 Cache Key，防止不同账户或 Header 错用缓存。
- 图片请求失败后的来源回退策略。
- 下载响应解密或二进制变换。
- 缩略图裁剪参数。

超长图和超大图必须单独验证分块解码或子采样方案，不能仅依赖完整 Bitmap 解码。

## 8. 数据模型与存储

### 8.1 核心领域标识

远端漫画不能只用服务端 ID 标识，统一身份为：

```text
ComicKey = SourceId + RemoteComicId
ChapterKey = ComicKey + RemoteChapterId
```

本地漫画使用稳定生成的 `LocalComicId`，并通过统一 `ComicRef` 与远端漫画共享收藏、历史和阅读器逻辑。

### 8.2 主要表

- `comic`
- `chapter`
- `comic_source`
- `source_account`
- `source_setting`
- `source_private_data`
- `favorite_folder`
- `favorite_entry`
- `reading_history`
- `reading_progress`
- `download_task`
- `download_chapter`
- `download_page`
- `local_comic`
- `local_chapter`
- `sync_record`
- `search_history`

### 8.3 文件布局

应用私有下载目录建议：

```text
downloads/
└── {sourceId}/
    └── {comicIdHash}/
        ├── metadata.json
        ├── cover
        └── chapters/
            └── {chapterIdHash}/
                ├── chapter.json
                └── pages/
```

文件名使用稳定 Hash，真实标题保存在元数据中，避免非法字符、超长路径和重名问题。通过原子临时文件写入，成功后再重命名，避免中断产生伪完成文件。

## 9. 功能规划

### 9.1 首页与探索

- 最近阅读、收藏更新和本地漫画入口。
- 按来源展示探索页。
- 支持多分区、分页列表和混合布局。
- 分类筛选、排行和查看更多。
- 下拉刷新、错误重试和来源切换。

### 9.2 搜索

- 单漫画源搜索。
- 多漫画源聚合搜索。
- select、multi-select 和 dropdown 筛选项。
- 页码和 next-token 分页。
- 搜索历史、标签建议和历史清理。
- 聚合搜索并发限制和单源失败隔离。

### 9.3 漫画详情

- 封面、标题、副标题、简介、作者和上传者。
- 标签、评分、点赞数和更新时间。
- 章节列表、排序、分组和阅读状态。
- 收藏夹选择、推荐漫画和缩略图。
- 评论、回复、点赞、投票和评分。
- 来源网页跳转与来源信息展示。

### 9.4 收藏与历史

- 本地收藏夹。
- 来源远端收藏夹及多文件夹支持。
- 最近阅读和完整历史。
- 章节、页码、阅读百分比和时间记录。
- 追更检查和更新标记。
- 删除漫画时分别处理文件、收藏和历史。

### 9.5 本地漫画

- SAF 选择目录或文件。
- 无章节目录、章节子目录。
- CBZ、ZIP、CB7、7z。
- 自然文件名排序。
- `cover.*` 或首图作为封面。
- 扫描已有下载目录重建数据库。
- 缓存压缩包索引，避免每次阅读全量扫描。

### 9.6 下载

- 章节批量选择和任务队列。
- 页级受控并发。
- 暂停、继续、取消和失败重试。
- Wi-Fi、充电和存储空间约束。
- 前台进度通知与取消操作。
- 下载完整性校验。
- 漫画源失效后继续离线阅读。
- 数据库丢失后的目录扫描恢复。

### 9.7 同步与备份

- WebDAV 配置和连通性测试。
- 设置、收藏、历史、进度和漫画源备份。
- 敏感账号信息默认不进入普通备份。
- 增量版本号和数据格式版本。
- 上传、下载、覆盖前预览和冲突提示。
- 自动备份使用 WorkManager 和网络约束。

## 10. 阅读器设计

### 10.1 页面来源统一

```kotlin
sealed interface ComicPage {
    data class Remote(val request: ComicImageRequest) : ComicPage
    data class Downloaded(val path: StoredPage) : ComicPage
    data class LocalFile(val uri: UriRef) : ComicPage
    data class ArchiveEntry(val archive: UriRef, val entry: String) : ComicPage
}
```

阅读器只依赖页面描述符，不感知漫画源、下载数据库或文件系统实现。

### 10.2 阅读模式

- 纵向连续滚动。
- 横向从左到右。
- 横向从右到左。
- 单页和双页。
- 首页单独显示。
- 点击区域翻页。
- 双击缩放、双指缩放和拖动。
- 沉浸模式、状态栏切换和常亮。
- 屏幕方向、亮度和背景色。
- 章节间连续阅读和边界提示。

### 10.3 性能策略

- 当前页附近预取，远离视口立即释放。
- 图片请求遵循可见性和滑动方向调整优先级。
- 超长图使用子采样或分块解码验证方案。
- 避免在 Compose State 中保存 Bitmap。
- 阅读进度节流写入，退出和进入后台时强制落盘。
- 对低内存事件主动缩小预取窗口和清理内存缓存。
- 使用真实中低端设备验证 OOM、快速翻页和长章节。

## 11. 自适应 UI 与导航

- 单 Activity、Edge-to-edge。
- 手机使用 Navigation Bar；宽屏使用 Navigation Rail。
- 列表—详情使用 `ListDetailPaneScaffold`。
- 设置页在宽屏使用双栏。
- 阅读器进入全屏时隐藏应用级导航。
- 支持预测性返回、深色模式和动态颜色。
- 所有页面支持字体缩放、TalkBack、键盘和方向键操作。
- 触控目标、颜色对比度和语义描述纳入验收。

## 12. 测试策略

### 12.1 测试层级

| 类型 | 重点 |
| --- | --- |
| Unit Test | Reducer、UseCase、解析、排序、进度计算和错误映射 |
| Repository Test | Room、DataStore、文件和网络组合逻辑 |
| Source Contract Test | JavaScript API、超时、取消、Cookie 和返回模型兼容 |
| Compose Test | 页面状态、操作、无障碍语义和导航入口 |
| Integration Test | 搜索到阅读、下载到离线阅读、本地导入完整链路 |
| Migration Test | Room Schema 和备份格式升级 |
| Benchmark | 冷启动、首页滚动、详情进入阅读器、连续阅读 |

### 12.2 测试约束

- 网络测试使用 MockWebServer，不依赖真实漫画网站。
- JavaScript Runtime 使用固定测试源，不依赖线上脚本。
- 所有 Repository 提供 Fake 实现。
- Coroutine Test 使用测试 Dispatcher 和虚拟时间。
- 数据库每次版本升级必须提交 Schema JSON 和 Migration Test。
- Release 候选版本必须执行真实设备阅读器压力测试。

## 13. 安全、隐私与合规

- 应用本身不内置侵权内容或不受控制的商业源。
- 用户明确安装第三方漫画源，并看到来源地址和风险说明。
- Release 禁止明文 HTTP；单个来源确有需要时要求用户显式授权。
- 账户密码只用于登录请求，不持久化；必要凭据由 Keystore 保护。
- Cookie、Token 和备份密码禁止进入普通日志与崩溃报告。
- WebView 禁止不必要的文件访问、调试和跨来源能力。
- 源脚本下载采用 Hash 记录，可选签名验证。
- 关于页面展示第三方许可证、原 Venera 项目致谢和非官方声明。
- 若复用 GPL-3.0 代码、资源或衍生实现，发行物遵循 GPL-3.0 对应义务。
- 发布前进行包名、名称、图标、商标及应用商店政策复核。

## 14. 开发阶段与里程碑

### 阶段 0：技术验证

目标：验证两项最高风险技术。

- 建立基础 Gradle 多模块工程。
- AndroidX JavaScriptEngine 执行真实结构的测试源。
- 实现 JS 调用 Kotlin 网络并异步返回结果。
- 验证超时、取消、隔离和大二进制传输。
- 实现 Compose 阅读器原型。
- 验证长图、缩放、连续滚动、预加载和内存表现。

退出标准：确认 JS 引擎方案和大图方案；如失败，记录 QuickJS 或子采样替代方案。

### 阶段 1：核心阅读闭环

- Source Core 能力。
- 来源安装和管理。
- 探索、搜索、详情和章节。
- 网络图片加载。
- 基础阅读器。
- 阅读历史和进度。

退出标准：安装一个测试源后，可以完成“搜索—详情—选章—阅读—恢复进度”。

### 阶段 2：书架与离线能力

- 本地收藏夹和来源收藏。
- 追更检查。
- 下载队列和离线阅读。
- 本地目录、CBZ/ZIP、CB7/7z 导入。
- 下载目录恢复扫描。

退出标准：无网络时可浏览书架、打开下载及本地漫画，并保持进度。

### 阶段 3：来源完整能力

- 账户密码、Cookie 和 WebView 登录。
- 分类、排行和聚合搜索。
- 评论、回复、点赞、投票和评分。
- 标签建议、来源设置和高级图片处理。
- 漫画源调试日志和兼容矩阵。

退出标准：Core 与 Extended 兼容测试全部通过，Advanced 有明确支持清单。

### 阶段 4：同步、体验与发布

- WebDAV 备份和恢复。
- Material 3 Adaptive 完整适配。
- 无障碍、国际化和简繁体支持。
- Baseline Profile、Macrobenchmark 和性能治理。
- 崩溃恢复、数据库迁移和发布签名流程。
- 隐私说明、第三方许可证和正式文档。

退出标准：Release Candidate 通过功能、迁移、压力、无障碍和真实设备测试。

## 15. 每阶段质量门禁

合并到主分支前至少满足：

- 编译、Lint、单元测试通过。
- 新业务逻辑包含对应测试。
- 未引入 Feature 间直接依赖或模块循环。
- 未提交密钥、Cookie、账号、真实用户数据或版权内容。
- 数据库和备份格式变更包含迁移方案。
- UI 状态包含加载、空、成功和失败路径。
- 影响阅读器的修改通过长章节与旋转场景测试。
- 依赖升级通过许可证与 Release Notes 检查。

## 16. CI/CD 规划

Pull Request 流水线：

1. Gradle Wrapper 校验。
2. Debug 编译。
3. Android Lint 和 Kotlin 静态检查。
4. 单元测试和 Source Contract Test。
5. Room Schema/Migration 检查。
6. Compose 截图或关键 UI 测试。
7. 依赖和许可证报告。

Release 流水线：

1. 生成签名 AAB/APK。
2. 执行 R8 和资源压缩。
3. 生成并验证 Baseline Profile。
4. 输出 mapping、SBOM、校验和及第三方许可证。
5. 创建 Git Tag 和 Release Notes。
6. 发布渠道由维护者显式批准，不自动上传生产商店。

## 17. Git 协作约定

- 主分支：`main`。
- 功能分支：`feature/<name>`。
- 修复分支：`fix/<name>`。
- 架构变更通过 `docs/adr/` 记录 ADR。
- Commit 推荐使用 Conventional Commits：`feat`、`fix`、`refactor`、`test`、`docs`、`build`、`ci`。
- 大型功能拆分为可独立验证的小提交。
- 不允许将生成 APK、签名文件、真实配置和本地缓存提交到仓库。

## 18. 首批 ADR

项目创建后应优先补充：

1. `ADR-001`：多模块边界和依赖规则。
2. `ADR-002`：JavaScriptEngine 与 fallback 决策。
3. `ADR-003`：Venera 漫画源兼容范围。
4. `ADR-004`：超大图和子采样方案。
5. `ADR-005`：下载任务在不同 Android 版本上的执行策略。
6. `ADR-006`：许可证、上游复用和品牌声明。

## 19. 已确认决策

- 项目名使用 `Venera Native`。
- 项目为 Android-only。
- 使用 Kotlin 和 Jetpack Compose。
- 使用多 Module，`:app` 是唯一应用入口。
- 使用 ViewModel、StateFlow 和轻量 MVI/UDF。
- 使用 Hilt、Room、DataStore、Paging 3、OkHttp 5 和 Coil 3。
- 以兼容现有 Venera JavaScript 漫画源协议为重要目标。
- JavaScriptEngine 先进行 PoC，再决定是否增加 QuickJS fallback。
- UI 继承原项目的信息架构，但不逐像素复制 Flutter UI。
- 功能按技术验证、核心闭环、离线能力、完整来源能力和发布优化分阶段交付。

## 20. 开发启动清单

在开始阶段 0 前完成：

- [ ] 确认 GitHub 组织或用户名与最终 applicationId。
- [ ] 确认项目许可证和 Venera 名称使用方式。
- [ ] 创建全新 Logo 和 Material 3 视觉基线。
- [ ] 创建 Gradle Version Catalog 与 Convention Plugins。
- [ ] 创建基础 Module 和依赖规则检查。
- [ ] 建立 CI、代码格式、Lint 和测试任务。
- [ ] 选取不包含敏感账号的漫画源兼容测试样例。
- [ ] 完成 JavaScriptEngine PoC。
- [ ] 完成 Compose 阅读器 PoC。
- [ ] 根据 PoC 结果更新 ADR 和阶段 1 范围。
