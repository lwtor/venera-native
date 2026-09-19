# Architecture Decision Records

ADR 记录已经接受或需要长期保留上下文的架构决定。

| ADR | 决策 | 状态 |
| --- | --- | --- |
| [ADR-0001](0001-module-boundaries.md) | 多模块边界与依赖方向 | Accepted |
| ADR-0002 | JavaScript Runtime 与 fallback | Stage 0 待创建 |
| ADR-0003 | Venera 漫画源兼容范围 | Stage 1 前待创建 |
| ADR-0004 | 超长图与子采样策略 | Stage 0 待创建 |
| ADR-0005 | 跨 Android 版本的下载执行策略 | Stage 2 前待创建 |
| ADR-0006 | 许可证、上游复用与品牌声明 | 复用上游实现前待创建 |

## 编写规则

- 文件名：`NNNN-short-title.md`。
- 状态：Proposed、Accepted、Superseded 或 Rejected。
- 必须包含上下文、决策、理由、后果和替代方案。
- 旧 ADR 不删除；被替代时互相链接。
- ADR 记录“为什么”，实现文档记录“怎么做”。