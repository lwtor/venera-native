# 项目执行状态

> 本文件是项目当前进度的唯一事实来源。新会话必须先读根目录 `AGENTS.md`，再读本文件。每次完成任务后必须同步更新。

## 状态快照

| 项目 | 当前值 |
| --- | --- |
| 最后更新 | 2026-09-19 |
| 当前阶段 | Stage 0：技术验证 |
| 当前任务 | S0-03：异步 Host API 与受控网络桥 PoC |
| 当前任务状态 | TODO |
| 默认分支 | `main` |
| 远程仓库 | `https://github.com/lwtor/venera-native` |
| 当前代码基线 | `main`（以 Git HEAD 为准） |
| 工作基线 | AGP 9.2.1、Gradle 9.4.1、JDK 17、SDK 37、minSdk 26 |

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

已完成：

- Gradle Kotlin DSL、Version Catalog、Convention Plugins。
- AGP 内置 Kotlin、Compose、Material 3、Navigation 3。
- `:app` 单入口和最小首页。
- `ComicKey`、`SourceId`、`RemoteComicId` 基础值对象。
- `SourceScriptRuntime` 初始契约。
- AndroidX JavaScriptEngine 支持检测包装。
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

## 当前代码事实

- `:app` 目前直接展示 `HomeRoute`，尚未建立完整根导航。
- `:feature:home` 只是占位 UI，不包含 ViewModel 或真实数据。
- `:source:api` 已有最小 Runtime 契约，但还不是完整 Venera 漫画源协议。
- `:source:engine` 已能执行隔离 fixture、结构化调用、超时、取消和生命周期恢复。
- Runtime 当前为每来源串行调用；支持非 Binder 传输时结果上限配置为 1 MiB。
- Runtime 尚无 Host API、HTTP、Cookie、消息桥或二进制通道。
- 尚未引入 Hilt、Room、DataStore、OkHttp、Coil、Paging、WorkManager。
- 尚未创建 CI、许可证文件、正式图标或发布配置。
- 当前有领域模型/Runtime JVM 测试和 8 个 Runtime 实机测试。
- `applicationId = dev.veneranative` 仍是暂定值。

## 当前唯一下一任务

### S0-03：异步 Host API 与受控网络桥 PoC — TODO

目标：证明来源脚本可以通过显式允许列表异步请求 Kotlin Host API，由 OkHttp 完成网络请求并把结构化响应送回脚本，同时保持超时、取消、来源隔离和脱敏语义。

必须交付：

1. 新建 `:core:network`，提供 OkHttp Client 基础配置、Dispatcher 和通用网络错误映射。
2. 新建 `:source:network`，提供 `SourceHttpRequest`、`SourceHttpResponse`、每来源 CookieJar 和网络执行器。
3. 在 `:source:api` 定义与具体网络库无关的 Host API 契约；不得泄漏 OkHttp 类型。
4. 在 `:source:engine` 实现带请求 ID 的 JS ↔ Kotlin 异步消息桥，并进行 Host 方法允许列表校验。
5. 支持 GET、POST、Header、UTF-8 文本 Body、状态码和文本响应。
6. 调用超时或取消时必须向下取消对应 OkHttp Call。
7. 日志和错误不得包含 Authorization、Cookie、Token 明文。
8. 使用 MockWebServer 和本地 fixture 测试，不访问真实漫画站点。
9. 明确记录 Message Ports 能力要求、payload 限制和 S0-04 待验证项。

验收场景：

- fixture 脚本通过 Host API 完成 GET 和 POST，并收到状态码、Header 和文本 Body。
- HTTP 4xx/5xx 作为结构化 HTTP 响应返回，连接失败映射为稳定网络错误。
- 调用取消后底层 MockWebServer 请求或 OkHttp Call 被取消，不产生成功结果。
- 两个来源的 Cookie 互不可见。
- 未在允许列表中的 Host 方法被拒绝。
- 并发超限得到可识别错误，不无限排队。
- 测试 Token 不出现在捕获日志和错误信息中。

建议验证命令：

```powershell
.\gradlew.bat :core:network:testDebugUnitTest :source:network:testDebugUnitTest
.\gradlew.bat :source:engine:testDebugUnitTest
.\gradlew.bat :source:engine:connectedDebugAndroidTest
.\gradlew.bat lintDebug :app:assembleDebug
```

不在 S0-03 范围：

- 真实商业漫画源或真实账户。
- WebView 登录、验证码和账户 UI。
- HTML DOM/CSS Selector Host API。
- 图片加载、原始二进制和下载。
- QuickJS fallback、完整 Venera 协议和 Reader UI。

完成 S0-03 后，将当前任务推进为 S0-04。

## 紧随其后的任务

| 顺序 | ID | 名称 | 前置 |
| --- | --- | --- | --- |
| 1 | S0-04 | Runtime 限制、二进制和压力验证 | S0-03 |
| 2 | S0-05 | Compose 阅读器基础原型 | S0-01 |
| 3 | S0-06 | 超长图、缩放和内存验证 | S0-05 |
| 4 | S0-07 | Stage 0 决策收敛与 ADR | S0-04、S0-06 |

## 已知风险与待确认

| 项目 | 状态 | 解除条件 |
| --- | --- | --- |
| AndroidX JavaScriptEngine 是否满足异步 Host API 和二进制需求 | 基础执行已通过，Host/二进制验证中 | 完成 S0-03 至 S0-04 |
| 超长图是否需要专用子采样组件 | 未验证 | 完成 S0-05 至 S0-06 |
| QuickJS fallback 是否必要 | S0-02 暂不引入 | S0-04 按 ADR-0002 fallback 条件复核 |
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
- [ ] 已在实现前确定测试策略。
- [ ] 完成后会更新本文件及实施计划状态。
