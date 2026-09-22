# ADR-0008：异步 Host API 传输与引擎选择

- 状态：Accepted（引擎 spike 验证中，判据见第 4 节）
- 日期：2026-09-20
- 决策者：项目维护者
- 关联任务：S0-02、S0-03、S0-04、S1-01、S1-03
- 相关：ADR-0002（Runtime 与 fallback）、ADR-0003（Host API 与网络桥）

## 1. 上下文

漫画源脚本用 `async`/`await` 调用 Host 能力（网络请求、后续可能的解析与存储）。因此运行时必须存在
**JS → Kotlin** 方向的通道，且该通道要能承载：调用级取消、超时、并发上限，以及二进制数据。

AndroidX JavaScriptEngine 1.1.0 的 API 方向是单向的：

- `JavaScriptIsolate.callFunction(...)`、`evaluateJavaScript(...)`、`provideNamedData(...)` 都是
  Kotlin → JS；
- JS → Kotlin 的唯一通道是 `MessagePort`。

参考设备（vivo V2337A / SDK 36）实测 `messagePorts=false`，而 `isolateTermination`、`promiseReturn`、
`evaluateWithoutTransactionLimit`、`provideConsumeArrayBuffer` 均可用。官方文档说明各 feature 的可用性
由底层 WebView 实现决定，必须在运行时探测，无法在编译期保证。

脚本侧也没有可用的替代驱动：实测 `setTimeout`、`btoa`、`atob`、`TextEncoder` 均不存在，
所以“脚本主动轮询结果”这条路不成立；`provideNamedData` 是单向的，无法回传结果。

结论：**在缺少 MessagePort 的设备上，WebView 系引擎无法提供异步 Host API。** 这不是实现细节问题，
而是该 API 的方向性限制。

## 2. 决策

### 2.1 协议、控制面与传输三层解耦

- `:source:api` 只描述类型化能力调用与结果，不出现任何传输类型。
- 调用级超时、取消、并发上限、错误分类全部由 Runtime 层表达。
- 传输实现（无论 MessagePort 还是自有引擎的 host binding）不得进入协议类型，也不得影响 UI 契约。

这条规则让传输可以替换，而协议与 Feature 层不需要改动。

### 2.2 不使用重放式桥

“让 JS 侧的 Host 调用抛出标记异常，Kotlin 完成请求后从头重放整个函数”的方案被否决：

- 每次重放都会重复执行脚本副作用（日志、全局状态、随机数与时间戳、埋点），
  对一个要运行第三方脚本的运行时来说不可接受；
- 正确性依赖脚本的确定性，而真实源普遍使用时间戳与随机值；
- Promise 拒绝语义与取消语义会绑在实现细节上，难以给出可测试的保证。

### 2.3 Stage 1 引入自有引擎（QuickJS）作为主引擎

理由：

1. 自有引擎可以同时提供同步与异步 JS → host 通道，不再需要 MessagePort。
2. 上游 Venera 使用 QuickJS 系引擎，第三方源脚本的语义兼容性风险最低——本项目的产品前提是兼容
   既有源生态，引擎差异（内建对象、正则、错误信息、`console` 行为）会直接变成“某些源在这台设备上不工作”。
3. 不依赖厂商 WebView 版本，设备覆盖可预期，也不再受 Binder 事务上限约束。
4. 二进制、取消与 isolate 生命周期都在自己的实现里，可测试。

### 2.4 AndroidX 引擎实现的处置

现有 WebView 引擎实现（`:source:engine`）保留到自有引擎通过同一套契约测试为止，之后删除；
`:source:api` 契约保持不变，由装配层选择实现。这样 S0 的成果在过渡期仍然有效，但不会长期维持双引擎。

### 2.5 依赖顺序

- S1-01（协议与模型）与 S1-02（来源安装管理）不依赖 Host API，可以先行。
- 引擎 spike 必须在 S1-03（需要网络请求的纵向切片）之前完成，作为其阻塞前置。

## 3. 引擎 spike 的通过判据

必须全部满足才允许切换默认实现：

| 判据 | 通过条件 |
| --- | --- |
| 能力覆盖 | 仓库内测试源（含 `async`/`await` 网络调用）跑通 Explore、Search、Detail、Chapters、Pages 五个能力 |
| 真实源兼容 | Host API 提供全局 `fetch`（`ok`/`json()`/`text()`）与 `Network.*` 两套入口，`venera-configs` 中的真实源可在不改写的前提下运行（依据见 ADR-0007 §4.3） |
| 取消 | 取消进行中的调用能中断实际网络请求，且取消后 isolate 仍可继续调用 |
| 超时 | 调用级超时生效，超时后可恢复（不要求重建整个 runtime） |
| 二进制 | `ArrayBuffer` 等价通道可用，图片数据无需 Base64 |
| 构建 | ABI 覆盖策略明确；Debug 与 Release 构建均通过；体积增量有记录；许可证与来源已登记 |
| 安全 | 不向脚本暴露 `Context`、任意 Java 对象、文件系统或反射，保持 `:source:api` 的允许列表模型 |

spike 结果（ABI、体积、实测数据）必须回填本节。

## 4. 替代方案

| 方案 | 结论 |
| --- | --- |
| 保持 WebView 引擎 + MessagePort | 否决作为唯一路径：参考设备不可用，且可用性取决于用户设备的 WebView 版本，等于把产品可用性交给厂商实现。 |
| 重放式桥（throw + replay） | 否决，理由见 2.2。 |
| WebView + `addJavascriptInterface` | 否决：向不可信内容暴露 Java 对象桥，安全审查成本高于自有引擎。ADR-0002 已排除。 |
| 只支持不使用 `await` 的源 | 否决：与既有源生态不兼容，等于放弃产品前提。 |
| 服务端代理网络请求 | 否决：与离线与自建源方向冲突，且不解决 JS → host 的根本问题。 |

## 5. 后果

正面：

- Host API 能力不再受厂商 WebView 影响，可用性可预期。
- 二进制、取消、超时与 isolate 生命周期统一在一个实现里，可以写成契约测试。
- 与上游源生态的语义一致性风险显著降低。

代价与风险：

- 引入原生依赖：NDK 构建、ABI 拆分、APK 体积、许可证与安全维护成本。
- S0 的 WebView 引擎实现最终会被删除，是明确的沉没成本。
- QuickJS 与 V8 仍存在语义差异，spike 必须覆盖真实源写法，不能只测玩具脚本。

## 6. 待完成

- 引擎 spike：按第 3 节判据执行并回填数据。
- spike 通过后：更新 ADR-0002 的引擎选择状态，并按 2.4 删除 WebView 实现。
- 若 spike 失败：回到本 ADR 重新评估，不得直接在 S1-03 上叠加临时方案。

## 7. 引擎绑定选型（2026-09-20 补充）

### 决策

使用 **`io.github.dokar3:quickjs-kt:1.0.5`**（Apache-2.0）作为自有引擎的原生绑定，
而不是自己写 JNI 或使用 `app.cash.quickjs`（0.9.2 / 2021-08，已停更 4 年以上）。

**为什么不是最新版**：1.0.6 及之后（含 1.0.15）用 Kotlin 2.4.x 编译，其元数据版本 2.4.0 超出本项目
工具链可读范围（AGP 内置 Kotlin 2.2，可读到 2.3.0），直接引用会在编译期失败。1.0.5 用 Kotlin 2.3.20
编译，是 2.4 之前的最后一版，因此锁定 1.0.5。什么时候可以升：等工具链的 Kotlin 提到 2.4 之后，
升级必须伴随契约测试通过。

### 依据

| 判据 | 事实 |
| --- | --- |
| 许可证 | Apache-2.0（绑定层）；QuickJS 本体为 MIT。两者都需在 `:app` 的许可页登记 |
| 维护状态 | 1.0.15 发布于 2026-09-03，2026 年内持续发布（1.0.1 → 1.0.15），非停更项目 |
| **JS → Kotlin 异步** | `asyncFunction` 注册的宿主函数内部可直接 `suspend`，JS 侧用 top-level `await` / `Promise.all` 等待 —— **这正是 ADR-0008 §1 里 MessagePort 缺失导致无法实现的能力** |
| 取消与超时 | 取消调用协程可中断正在执行的 JS；`evaluationTimeoutMillis` 与 `interruptEvaluation()` 提供实例级超时 |
| 二进制 | 类型映射含 `Int8Array ↔ ByteArray`，无需 Base64 |
| **16KB 页对齐** | 已核对 `v1.0.5` 标签的 `quickjs/native/CMakeLists.txt`：Android 且 `LIBRARY_TYPE=shared` 时显式添加 `-Wl,-z,max-page-size=16384`，并同时开启 `CONFIG_BIGNUM`（源脚本用得到大整数哈希）。这是 Google Play 自 2025-11-01 起的强制要求，必须核实而不是假设 |
| 无设备验证 | 同时发布 `quickjs-kt-jvm`，因此引擎与契约测试可以在 JVM 上跑，不必依赖真机（符合项目“非必要不做实机测试”的约束） |
| 构建成本 | 消费者只依赖预编译 AAR；NDK/CMake/Zig 只是上游构建自身的需要 |

### 后果与限制

- **引擎实现可以在 JVM 上被回归**：契约测试用 JVM 版本，真机只用于确认平台差异。
- 绑定是社区项目，不是 Google 或商业支持：升级前必须跑契约测试，`1.0.x` 内保持版本锁定。
- 引擎实现仍必须自己完成：Host API 允许列表、`fetch`/`Network.*` 两套入口、调用取消映射、
  结果大小自行计数（ADR-0002 已确认引擎的大小上限不可依赖）。
- 若绑定方案出现问题，替代路径是自行交叉编译 QuickJS 并复用同一 `:source:api` 契约；
  协议、数据与 UI 层都不受影响。

## 8. Spike 结果（2026-09-20，JVM）

绑定选定后先做了最小桥接 spike，用 JVM 版产物运行（无需设备）：

| 验证项 | 结果 |
| --- | --- |
| 脚本 `await` 宿主调用并取回值 | **通过**：宿主 `asyncFunction` 内 `suspend`，脚本侧 `await` 得到返回值 |
| 宿主异常变成脚本侧 rejected await | **通过**：`try/catch` 捕获到宿主抛出的文本，引擎不崩 |
| 并发宿主调用 | **通过**：`Promise.all` 三个调用全部完成且顺序符合预期 |
| 原生库加载 | **通过**：JVM 产物自带桌面原生库，测试无需设备 |
| 引擎资源限制接口 | 存在 `setMemoryLimit` / `setMaxStackSize` / `getMemoryUsage` / `gc`，可用于后续限额 |

**实测到的调用约束（文档没写，必须写进实现）**：

- 代码必须按**脚本**求值并使用**顶层 `await`**，例如
  `const r = await source.search(...); r;`。这是唯一能直接拿到值的形态。
- 用 async IIFE 包裹（`(async () => { ... })()`）会返回 **Promise 对象本身**，宿主拿到的是未解的值。
- 用 module 模式（`asModule = true`）求值**没有完成值**，返回 null；`export` 的值也读不到。

因此引擎适配层必须生成"顶层 await + 最后一条表达式"的包装代码，不能自行包一层 async 函数。
这条约束已写成 `QuickJsBridgeSpikeTest` 的断言。

当前判据状态：调用级取消、宿主网络取消、超时后重建、初始化 Host 调用和同源队列边界已有 JVM
回归。正文图片通过共享 OkHttp/Coil 管线传输，Stage 1 的 Host API 只承诺文本响应；真正的二进制请求体
与脚本字节返回不宣称通过，归入 Stage 3 扩展协议。ABI、Debug/Release 包体与许可证状态以
`docs/STATUS.md` 的最新质量审查记录为准。

## 9. 兼容层范围（2026-09-20，按真实源实测）

引擎只提供 ECMAScript 内建对象：实测 `Object.getOwnPropertyNames(globalThis)` 里没有 `fetch`、
`console`、`URL`、`TextEncoder`、`atob`、`setTimeout`、`crypto`、`Intl`。兼容层由
`QuickJsHostScript` 注入，范围按**真实源的调用点**确定，而不是按"Web 平台一般应该有什么"推测：

| 能力 | 决定 | 依据 |
| --- | --- | --- |
| `fetch(url, options)` + `ok` / `status` / `headers` / `text()` / `json()` / `arrayBuffer()` | 提供 | 真实源 12 处调用，网络主力入口 |
| `Convert.encodeUtf8` / `decodeUtf8` | 提供 | 真实源用它编码 POST 表单体 |
| `Network.get/post/put/patch/delete/fetchBytes` | 提供 | 协议要求；当前 Host API 只放行 `http.request` 的 GET/POST，其余方法会收到 `INVALID_REQUEST` |
| `veneraHost.call(method, payload)` | 提供 | 与 WebView 实现和既有 fixture 保持一致，允许列表仍在 Kotlin 侧执行 |
| `console.*` | 提供，但宿主只接收级别与固定脱敏标记 | 不可信日志可能含 Cookie、Token、密码或用户输入，原文不得进入 logcat |
| `APP.locale` / `APP.version` | 提供 | 真实源读取 `APP.locale` |
| `URL` / `URLSearchParams` | **不提供** | 真实源 0 次使用；自写 URL 解析器会带来静默误解析风险 |
| `TextEncoder` / `TextDecoder`、`atob` / `btoa` | **不提供** | 真实源 0 次使用（编码统一走 `Convert`） |
| `setTimeout` / `setInterval`、`crypto`、`structuredClone`、`Intl` | **不提供** | 真实源 0 次使用 |

规则：**新增一个全局必须同时给出用到它的源和一条测试**。未提供的项记在 `docs/STATUS.md`。

已知传输限制：Host API 的请求体是文本（`http.request` 的 `body` 为字符串），因此
`Convert.encodeUtf8` 编码后的表单体在桥内被解码回字符串再发送——UTF-8 文本语义等价，
但真正的二进制体需要字节通道，属于 §8 列的未验证项。

## 10. 实测：脚本中断能力（2026-09-20）

ADR-0008 §3 把"调用取消与超时"列为 spike 判据。实测结果分两半，必须分开记录：

| 场景 | 结果 |
| --- | --- |
| 脚本**挂起在宿主调用上**时取消 | **通过**：取消传导到宿主 `suspend` 函数（测试断言宿主请求也被取消），调用方收到 `Cancelled` |
| 脚本**在引擎内死循环**（`while(true){}`）时超时 | **不能中断**：`QuickJs` 1.0.5 的公开接口只有 `memoryLimit` / `maxStackSize` / `evaluate` / `gc` / `close`，没有求值超时或中断入口（README 描述的 `evaluationTimeoutMillis` / `interruptEvaluation` 属于更新的版本） |

由此产生两条实现约束，已写进 `QuickJsRuntime` 与测试：

1. **求值不能作为调用方的子协程**。作为子协程时 `coroutineScope` 会等待无法中断的脚本，把"超时"变成"卡死"
   （实测：测试挂到 JUnit 120s 超时）；`async` 子任务的失败还会绕过错误映射直接抛给调用方。
   现在求值运行在会话自己的 `SupervisorJob` 作用域里。
2. **超时或取消后引擎视为脏**：下次调用重建引擎并重新执行脚本（与 WebView 运行时丢弃被终止 isolate 的做法一致）。
   丢弃时的 `close()` 在独立守护线程执行，因为对一个仍在跑脚本的引擎调用 `close()` 可能阻塞。

**已知限制**：死循环脚本会占用一个 CPU 核，直到引擎被丢弃且其线程自然结束——宿主无法强制终止它。
这是选择 1.0.5 的直接代价（§7 记录了为什么不能升到新版本）。升级路径：工具链 Kotlin 提到 2.4 后升级绑定
（新版本提供中断），或自行交叉编译 QuickJS 并复用同一契约。**在升级前，不得把"超时"描述为"脚本已停止"。**

质量整改补充：安装与元数据探测在独立会话作用域执行，调用方可在期限到达时返回；同一来源只允许一个
进行中调用，忙时立即返回可重试错误，避免无法中断的脚本后方形成无界队列。`init()` 执行期间设置专用
调用 ID，使初始化网络请求仍经过 Host API 的调用追踪。以上约束不改变 1.0.5 无法强停 CPU 死循环的事实。

## 官方依据

- https://developer.android.com/develop/ui/views/layout/webapps/jsengine
- https://developer.android.com/reference/androidx/javascriptengine/JavaScriptIsolate
- https://developer.android.com/reference/androidx/javascriptengine/MessagePort
