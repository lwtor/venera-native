# ADR-0003：MessagePort Host API 与来源网络桥

- 状态：Accepted（能力要求在 2026-09-20 按 S0-04 实测结论修订）
- 日期：2026-09-19
- 决策者：项目维护者
- 关联任务：S0-03、S0-04、S0-07

## 上下文

来源脚本需要异步 HTTP，但不得获得 Android `Context`、OkHttp 对象、反射或任意 Java/Kotlin 对象。网络调用必须保持来源 Cookie 隔离、显式允许列表、调用级取消、并发上限和敏感信息脱敏。

AndroidX JavaScriptEngine 1.1.0 提供命名 `MessagePort`。应用端和 isolate 可以交换字符串或 `ArrayBuffer`，端口按顺序投递，但未注册 `onmessage` 前发送的消息会被丢弃，排队消息也计入 isolate 内存。

## 决策

- Host API 稳定契约放在 `:source:api`，不泄漏 JavaScriptEngine 或 OkHttp 类型。
- `:source:engine` 为每个来源 isolate 创建一个名为 `veneraHost` 的端口。
- 来源脚本只能调用 `veneraHost.call(method, payload)`，Kotlin 端再次执行允许列表校验。
- S0-03 只允许 `http.request`，仅支持 GET、POST、Header、UTF-8 文本请求体与文本响应。
- 每条消息包含调用 ID 和请求 ID；Runtime 关闭 invocation 所在 isolate 时，同时关闭端口并取消所有 Host 子协程。
- `:source:network` 使用 OkHttp 5.5.0。协程取消调用 `Call.cancel()`。
- HTTP 4xx/5xx 仍返回 `SourceHttpResponse`；只有连接、超时、取消、请求无效、超限和响应过大属于 Host 错误。
- 每个来源拥有独立内存 CookieJar 和独立并发计数。当前每来源最多 4 个并发网络请求，超限立即失败，不进入无界队列。
- HTTP 正文最多 1 MiB。MessagePort 字符串按 UTF-8 最多 1 MiB；脚本侧请求先采用 262,144 字符的保守上限，确保最坏四字节 Unicode 不超过 1 MiB。
- 错误只返回固定分类文案，不拼接请求、Header、URL 查询值或底层异常。Authorization、Cookie、Token、Secret、Password 和 API Key Header 可统一脱敏。

## 生命周期与取消

```text
Source Invocation
  -> JavaScript Promise
    -> MessagePort request
      -> Host coroutine
        -> OkHttp Call
```

父调用超时、显式取消、来源卸载或 Runtime 关闭时，isolate 和 Host bridge 一并关闭。Bridge 取消子协程，`suspendCancellableCoroutine` 再调用 `Call.cancel()`。取消完成后不允许把迟到响应作为脚本成功结果。

## 能力要求

启用 Host API 时设备必须支持：

- `JS_FEATURE_PROMISE_RETURN`
- `JS_FEATURE_ISOLATE_TERMINATION`

**MessagePort 不是硬性前提。** S0-04 在参考设备（vivo V2337A）实测 `messagePorts=false`，
把 `JS_FEATURE_MESSAGE_PORTS` 作为必需能力会让 Host API 在唯一的目标设备上不可用。
修订后的要求：

- 传输层按能力探测：设备支持 Message Ports 时优先使用，作为低延迟通道。
- 缺少 Message Ports 时 Host API 必须仍然可用，且保持同一调用语义（调用 ID、超时、取消、
  并发上限、错误分类）。替代传输的具体形式（脚本侧拉取式轮询，或把 Host 调用改为同步
  请求-响应）由 Stage 1 的独立 ADR 决定，本 ADR 不预先选定。
- 二进制传输统一使用 `provideConsumeArrayBuffer`，与 Message Ports 是否可用无关。

修订理由与实测数据见 ADR-0002 的“S0-04 实测修订”。在替代传输 ADR 落地前，带 Host API 的
集成测试在参考设备上按能力探测跳过，不得写成“已验证”。

## 后果

正面影响：

- 脚本无法直接接触 Android 和 OkHttp 对象。
- 网络能力可按方法逐项授权。
- 来源 Cookie、并发和错误语义可以独立测试。
- Runtime 取消可以向下传播到实际 socket 请求。

限制与成本：

- S0-03 只处理 JSON 字符串和 UTF-8 文本，不传输原始二进制。
- Cookie 当前只在进程内保存，持久化和 WebView 同步属于后续任务。
- Host Promise 的迟到消息依赖端口关闭丢弃，脚本侧没有单独的请求取消方法。
- 每来源 4 并发仍是 PoC 默认值；S0-04 实测 32 次同源并发全部成功（153 ms，串行执行），
  并发上限的具体取值需要按来源协议在 Stage 1 复核。

## S0-04 待验证

1. MessagePort 双向大 JSON 的实际安全上限和多字节文本开销。
2. `ArrayBuffer` 与 `provideNamedData` 的二进制传递策略。
3. 全局并发、每来源并发和 Dispatcher 队列的压力数据。
4. 大量并发 Promise、取消与 isolate 终止时的资源释放。
5. API 26 至当前版本的 Message Ports 支持比例和 fallback 条件。

## 官方依据

- https://developer.android.com/reference/androidx/javascriptengine/JavaScriptIsolate
- https://developer.android.com/reference/androidx/javascriptengine/MessagePort
- https://developer.android.com/reference/androidx/javascriptengine/JavaScriptSandbox
- https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp/overview
