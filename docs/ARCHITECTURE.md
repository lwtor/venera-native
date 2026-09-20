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
| `:app` | MainActivity、应用 Theme、根装配 | Feature、Core、实现模块 |
| `:core:model` | 稳定领域 ID 与跨层模型 | 尽量只依赖 Kotlin |
| `:core:network` | OkHttp 客户端基线、Dispatcher 与通用网络错误 | OkHttp |
| `:core:designsystem` | Theme 与设计 Token | Compose、`:core:model`（按需） |
| `:feature:home` | 首页占位 UI | Design System、领域契约 |
| `:feature:reader` | 阅读器原型：页面描述符渲染、方向切换、页码、预取骨架，以及 S0-06 的解码策略原型（`PageImageDecoder`、有界缓存、缩放与分块） | Design System、`:core:model` |
| `:source:api` | Runtime、包、调用和结果契约，以及 Feature/Data 使用的 `SourceCore` 五个能力契约 | `:core:model` |
| `:source:engine` | AndroidX JavaScriptEngine 与 MessagePort 适配 | `:source:api`、受控 Host API |
| `:source:network` | 动态来源 HTTP、每来源 Cookie 与并发策略 | `:source:api`、`:core:network`、`:core:model` |

当前实现仍是 Stage 0 骨架；表中“职责”是边界，不表示功能已经完成。

S0-07 按本文件第 5 节的模块创建准则删除了两个零引用模块：`:core:common`（`AppResult`/`AppError`
没有任何生产或测试引用）与 `:core:navigation`（`AppRoute` 唯一声明，`Navigation 3` 只被它使用，
app 声明的依赖也没有被代码使用）。它们不属于废弃设计，而是“先建壳后接业务”的产物：
`:core:common` 会在出现第一个真实的 Result/错误聚合需求时重建，`:core:navigation`
会在 S1-03 首次需要类型安全 Route 时重建。

S0-06 的解码代码位于 `:feature:reader` 的 `image` 包，是**已知的临时位置**：它需要在 S1-05
建立 `:core:image` 时迁移过去，迁移前 UI 契约（`PageImageDecoder`、`PageTile`）保持不变，
理由与迁移条件记录在 ADR-0004。

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
（目标名 `:core:navigation`）在 S1-03 首次需要导航时创建，目前不存在。

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
