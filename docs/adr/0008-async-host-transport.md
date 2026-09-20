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

## 官方依据

- https://developer.android.com/develop/ui/views/layout/webapps/jsengine
- https://developer.android.com/reference/androidx/javascriptengine/JavaScriptIsolate
- https://developer.android.com/reference/androidx/javascriptengine/MessagePort
