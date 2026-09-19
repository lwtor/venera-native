# 项目执行状态

> 本文件是项目当前进度的唯一事实来源。新会话必须先读根目录 `AGENTS.md`，再读本文件。每次完成任务后必须同步更新。

## 状态快照

| 项目 | 当前值 |
| --- | --- |
| 最后更新 | 2026-09-19 |
| 当前阶段 | Stage 0：技术验证 |
| 当前任务 | S0-05：Compose 阅读器基础原型 |
| 当前任务状态 | TODO（S0-04 已按“决策所需最小验证”收尾） |
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

## 当前代码事实

- `:app` 目前直接展示 `HomeRoute`，尚未建立完整根导航。
- `:feature:home` 只是占位 UI，不包含 ViewModel 或真实数据。
- `:source:api` 已有最小 Runtime 契约，但还不是完整 Venera 漫画源协议。
- `:source:engine` 已能执行隔离 fixture、结构化调用、超时、取消和生命周期恢复。
- Runtime 当前为每来源串行调用；支持非 Binder 传输时结果上限配置为 1 MiB。
- Runtime 已有 MessagePort Host API、HTTP 文本请求、每来源 Cookie 和取消链路，尚无二进制通道。
- S0-04 已在 V2337A / SDK 36 取得决策所需的实机结论；压力测量 harness 取得结论后已删除，未保留为长期资产。
- 尚未引入 Hilt、Room、DataStore、OkHttp、Coil、Paging、WorkManager。
- 尚未创建 CI、许可证文件、正式图标或发布配置。
- 当前有领域模型/Runtime JVM 测试和 8 个 Runtime 实机测试。
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
adb shell am instrument -w -e class dev.veneranative.source.engine.SourceRuntimeLimitsTest dev.veneranative.source.engine.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s VeneraStress:I
```

不在 S0-04 范围：

- 真实商业漫画源或真实账户。
- WebView 登录、验证码和账户 UI。
- HTML DOM/CSS Selector Host API。
- 正式 Reader UI 和下载功能。
- 在结论得出前实现完整 QuickJS fallback。

## 当前唯一下一任务

### S0-05：Compose 阅读器基础原型 — TODO

目标：交付第一个可交互的阅读界面原型，验证阅读器架构骨架。

计划新增模块：

- `:feature:reader`

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
- 真实图片解码管线（由 S0-06 决定 Coil 或子采样方案）。

验证命令（普通节点只要求编译）：

```powershell
.\gradlew.bat :feature:reader:assembleDebug :app:assembleDebug
```

## 紧随其后的任务

| 顺序 | ID | 名称 | 前置 |
| --- | --- | --- | --- |
| 1 | S0-05 | Compose 阅读器基础原型 | S0-01 |
| 2 | S0-06 | 超长图、缩放和内存验证 | S0-05 |
| 3 | S0-07 | Stage 0 决策收敛与 ADR | S0-04、S0-06 |

## 已知风险与待确认

| 项目 | 状态 | 解除条件 |
| --- | --- | --- |
| 异步 Host API 在真机缺少 MessagePort | V2337A 上 `messagePorts=false`，S0-03 的桥按能力跳过 | Stage 1 决定异步桥实现方式时处理 |
| 二进制通道 | 已确认：只能走 `provideConsumeArrayBuffer` | — |
| 前后台切换、进程回收、API 26 可用性 | 未验证 | Stage 1 集成 Runtime 时补测 |
| ADR-0002 未按实测结论更新 | 待办 | 下次触及 Runtime 决策时更新 |
| 超长图是否需要专用子采样组件 | 未验证 | 完成 S0-05 至 S0-06 |
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
