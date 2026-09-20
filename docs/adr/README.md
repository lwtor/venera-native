# Architecture Decision Records

ADR 记录已经接受或需要长期保留上下文的架构决定。

| ADR | 决策 | 状态 |
| --- | --- | --- |
| [ADR-0001](0001-module-boundaries.md) | 多模块边界与依赖方向 | Accepted |
| [ADR-0002](0002-javascript-runtime.md) | JavaScript Runtime 与 fallback 条件 | Accepted（含 S0-04 实测修订） |
| [ADR-0003](0003-host-api-network-bridge.md) | Host API 与来源网络桥（MessagePort 为可选通道） | Accepted（S0-07 修订能力要求） |
| [ADR-0004](0004-large-image-strategy.md) | 超长图与子采样策略、Coil 引入条件 | Accepted（设备侧验证推迟，未验证项见 `docs/STATUS.md`） |
| ADR-0005 | 跨 Android 版本的下载执行策略 | Stage 2 前待创建 |
| ADR-0006 | 许可证、上游复用与品牌声明 | 复用上游实现前待创建 |
| [ADR-0007](0007-source-protocol-compatibility.md) | Venera 漫画源协议兼容范围 | Accepted（字段级已核对，剩余待确认见 4.3） |
| [ADR-0008](0008-async-host-transport.md) | 异步 Host API 传输与引擎选择 | Accepted（选型与 JVM 桥接 spike 已完成，实现进行中） |

## 编写规则

- 文件名：`NNNN-short-title.md`。
- 状态：Proposed、Accepted、Superseded 或 Rejected。
- 必须包含上下文、决策、理由、后果和替代方案。
- 旧 ADR 不删除；被替代时互相链接。
- ADR 记录“为什么”，实现文档记录“怎么做”。
