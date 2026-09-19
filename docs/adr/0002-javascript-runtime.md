# ADR-0002：JavaScript Runtime 与 fallback 条件

- 状态：Accepted
- 日期：2026-09-19
- 决策者：项目维护者
- 关联任务：S0-02、S0-03、S0-04

## 上下文

Venera Native 需要执行第三方漫画源 JavaScript，同时不能向脚本暴露 Android Framework、应用文件或任意 Java 对象。Runtime 必须支持多来源隔离、结构化调用、错误映射、超时、取消和后续异步 Host API。

AndroidX JavaScriptEngine 1.1.0 提供独立进程中的 `JavaScriptSandbox`，并允许一个 Sandbox 创建多个拥有独立全局对象的 `JavaScriptIsolate`。Android 平台当前只允许应用同时连接一个 Sandbox 进程，因此来源之间是 isolate 级隔离，不是不同操作系统进程之间的强隔离。

## 决策

Stage 0 和 Stage 1 默认采用 AndroidX JavaScriptEngine 1.1.0：

- 应用级 Runtime 持有一个 `JavaScriptSandbox`。
- 每个已安装来源持有一个独立 `JavaScriptIsolate`。
- 同一来源的调用串行执行，不同来源可由不同 isolate 执行。
- Feature 和 Data 只依赖 `:source:api`，不接触 AndroidX 类型。
- 调用使用 `InvokeFunction`、函数名和 JSON 参数数组；UI 不提交任意表达式。
- 脚本返回值经过 JSON envelope 转换为稳定 JSON。
- 安装前验证脚本 SHA-256。
- 单次调用有调用 ID、超时和显式取消。
- 超时或取消会关闭当前 isolate；下次调用从已保存的来源包重新创建并加载 isolate。
- Runtime 要求引擎支持 `JS_FEATURE_PROMISE_RETURN` 和 `JS_FEATURE_ISOLATE_TERMINATION`。缺少任一能力时返回 `EngineUnavailable`，不降级为不可靠语义。
- 支持非 Binder 传输时，单次结果当前上限配置为 1 MiB；否则受 Binder transaction limit 约束。S0-04 再用实测决定最终限制。

## 验证结果

在 Android 16 实机上执行 8 个 instrumentation tests，验证：

- 函数参数和结构化 JSON 结果往返；
- 两个来源的全局变量互不污染；
- 语法错误和运行时错误分类；
- 无限循环调用超时后可以重建 isolate 并继续调用；
- 显式取消不会返回成功，取消后可以继续调用；
- unload、close 和引擎不可用路径。

测试 fixture 位于 `source/engine/src/androidTest/assets/source_fixture.js`，不访问网络或第三方站点。

## 理由

- JavaScript 在应用外的隔离进程中执行，比 WebView Java 对象桥更适合不可信脚本。
- AndroidX 是 Android 官方维护能力，生命周期和功能探测 API 清晰。
- 每来源 isolate 能以较低开销隔离全局变量。
- Promise 返回能力为 S0-03 的异步 Host API 留出基础。
- 关闭 isolate 可以统一实现超时、取消和故障后的干净恢复。

## 后果

正面影响：

- 业务层不依赖具体引擎。
- Runtime 生命周期和错误类型已经可回归测试。
- 取消与超时后不会继续复用可能损坏的全局状态。

限制与成本：

- 同一 Sandbox 内 isolate 只是中等安全边界；引擎级漏洞可能影响同进程的其他 isolate。
- 同一来源调用暂时串行，后续需要根据来源协议判断是否增加 isolate pool。
- 关闭和重建 isolate 会丢失脚本内存状态；可持久状态必须通过受控 Host API 保存。
- 当前字符串通道不适合原始二进制；命名数据和二进制策略留到 S0-04。
- 能力依赖设备 WebView/JavaScript Sandbox 实现，必须保留运行时检测。

## Fallback 条件

在 S0-04 完成前不实现 QuickJS fallback。出现以下任一情况时重新评估：

1. 目标设备覆盖中有不可接受比例缺少 Promise 或可靠 isolate termination。
2. Host API 消息桥无法稳定支持取消和并发。
3. 大 JSON 或二进制传输不能满足漫画源需求。
4. Sandbox 崩溃恢复无法达到可用性要求。
5. 实测内存、启动或吞吐明显不满足来源调用。

若需要 fallback，`:source:api` 契约保持不变，新增引擎实现由装配层选择。

## 替代方案

### WebView + JavaScript interface

不采用作为通用 Runtime。WebView 更重，且向不可信内容暴露 Java 对象桥需要额外安全审查。WebView 仅保留给来源登录和验证码场景。

### 立即集成 QuickJS

暂不采用。会引入原生二进制、ABI、更新和安全维护成本；在官方方案尚未证明失败前没有足够依据。

### 每次调用创建新 isolate

不采用。隔离更彻底但会重复加载来源脚本，也无法自然支持来源会话状态。

## 官方依据

- https://developer.android.com/develop/ui/views/layout/webapps/jsengine
- https://developer.android.com/reference/androidx/javascriptengine/JavaScriptSandbox
- https://developer.android.com/reference/androidx/javascriptengine/JavaScriptIsolate
