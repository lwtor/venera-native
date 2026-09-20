# 项目执行状态

> 本文件是项目当前进度的唯一事实来源。新会话必须先读根目录 `AGENTS.md`，再读本文件。每次完成任务后必须同步更新。

## 状态快照

| 项目 | 当前值 |
| --- | --- |
| 最后更新 | 2026-09-20 |
| 当前阶段 | Stage 1：核心阅读闭环（Stage 0 已于 2026-09-20 退出） |
| 当前任务 | S1-01：稳定领域模型与 Source Core 协议 |
| 当前任务状态 | TODO（Stage 0 已完成；设备侧验证推迟，见已知风险） |
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

- `:app` 目前用本地状态在 `HomeRoute` 与 `ReaderRoute` 之间切换，尚未建立完整根导航。
- `:feature:home` 只是占位 UI，不包含 ViewModel 或真实数据。
- `:feature:reader` 已能渲染页面描述符、切换阅读方向并调度邻近预取；当 `assets/fixtures`
  存在时会改用 `AssetFixturePageProvider` 与真实解码管线，否则退回尺寸占位块。
- `:feature:reader` 的解码实现处于原型阶段：`PageImageDecoder` 有 `Sampled`（整页降采样，
  等价于通用图片库 fit 采样）与 `Region`（`BitmapRegionDecoder` 区域/分块）两种策略，
  可在阅读器底部栏运行时切换；解码结果只存在于有界 `PageImageCache`，不进入 `ReaderUiState`。
- 图片解码代码目前位于 `:feature:reader`，与“图片管线属于 core”的目标边界不一致；
  S1-05 建立 `:core:image` 时必须迁移，已在 ADR-0004 记为技术债。
- `:source:api` 已有最小 Runtime 契约，但还不是完整 Venera 漫画源协议。
- `:source:engine` 已能执行隔离 fixture、结构化调用、超时、取消和生命周期恢复。
- Runtime 当前为每来源串行调用；支持非 Binder 传输时结果上限配置为 1 MiB。
- Runtime 已有 MessagePort Host API、HTTP 文本请求、每来源 Cookie 和取消链路，尚无二进制通道。
- S0-04 已在 V2337A / SDK 36 取得决策所需的实机结论；压力测量 harness 取得结论后已删除，未保留为长期资产。
- 尚未引入 Hilt、Room、DataStore、Coil、Paging、WorkManager；OkHttp 已在 `:core:network` 使用。
- 尚未创建 CI、许可证文件、正式图标或发布配置。
- 当前有领域模型、Runtime 与阅读器 JVM 测试，以及 8 个 Runtime 实机测试和 3 个阅读器
  Compose 测试；本轮只保证编译，均未执行。
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

## 当前唯一下一任务

### S1-01：稳定领域模型与 Source Core 协议 — TODO

依赖：S0-07（已完成）。

交付物（摘自 `docs/IMPLEMENTATION_PLAN.md`）：

- Comic、Chapter、Page、分页结果、筛选项和来源能力模型。
- Explore、Search、Detail、Chapters、Pages 五个 Core 能力。
- 序列化与协议兼容测试。
- `ComicKey = SourceId + RemoteComicId` 全链路使用。

开始前必须先处理的两项遗留（来自 Stage 0）：

1. **异步 Host API 传输方式定案。** 参考设备 `messagePorts=false`，ADR-0003 已把 MessagePort
   降级为可选通道，替代传输需要一个独立 ADR（脚本侧拉取式轮询，或把 Host 调用改为同步
   请求-响应）。这决定 Core 协议里超时、取消与并发的表达方式，必须在实现 Pages/Explore 之前定案。
2. **`:core:common` 是否重建。** 如果 S1-01 确实需要统一的 Result/错误聚合类型，按
   `docs/ARCHITECTURE.md` 第 5 节的准则创建，不要照搬 Stage 0 的空壳。

编译检查：

```powershell
.\gradlew.bat :app:assembleDebug
```

## 紧随其后的任务

| 顺序 | ID | 名称 | 前置 |
| --- | --- | --- | --- |
| 1 | S1-01 | 稳定领域模型与 Source Core 协议 | S0-07（已完成） |
| 2 | S1-02 | 来源包安装与管理 | S1-01 |

## 已知风险与待确认

| 项目 | 状态 | 解除条件 |
| --- | --- | --- |
| 异步 Host API 在真机缺少 MessagePort | V2337A 上 `messagePorts=false`，S0-03 的桥按能力跳过 | Stage 1 决定异步桥实现方式时处理 |
| 二进制通道 | 已确认：只能走 `provideConsumeArrayBuffer` | — |
| 前后台切换、进程回收、API 26 可用性 | 未验证 | Stage 1 集成 Runtime 时补测 |
| 异步 Host 桥的替代传输未定案 | ADR-0003 已把 MessagePort 降级为可选通道，替代形式未选 | Stage 1 需要独立 ADR，且必须在实现 Pages/Explore 之前定案 |
| `SensitiveDataRedactor` 无生产调用点 | 错误路径不拼接敏感值且有测试断言；脱敏工具本身有 JVM 测试 | 日志功能落地时必须接入，否则删除 |
| 导航契约模块已删除 | `:core:navigation` 零引用，S0-07 删除以符合模块创建准则 | S1-03 首次需要类型安全 Route 时重建 |
| 大图策略的设备侧验证（解码耗时 / PSS / 掉帧 / 手势冲突） | 规则已由 JVM 预算测试保证，设备数据缺失，未验证 | Stage 0 退出门禁；需要时按 S0-06 记录的命令采集 |
| 解码代码暂驻 `:feature:reader` | 已知技术债，已在 ADR-0004 记录 | S1-05 建立 `:core:image` 时迁移 |
| Coil 仍未引入 | 有意推迟，见 ADR-0004 | S1-05 决策，且决策前先更新 ADR-0004 |
| 中等缩放区间允许最多约 1.41 倍 GPU 放大 | 内存上界的代价，观感未验证 | 设备验证时确认是否可接受 |
| 本机 `JAVA_HOME` 指向失效的 temurin21 路径 | 构建前需临时指向 temurin17 | 用户修复环境变量，或继续按命令临时指定 |
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
