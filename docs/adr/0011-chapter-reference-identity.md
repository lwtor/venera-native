# ADR-0011：统一远端与本地章节身份

- 状态：Accepted
- 日期：2026-09-24

## 背景

下载章节来自来源网络，本地章节来自 SAF 目录或归档；阅读器、路由和进度都必须能处理两者。若用合成的远端 `ChapterKey` 表示本地章节，来源调用边界依赖特殊字符串判断，类型无法阻止错误调用。

## 决策

Reader 路由、`PageProvider` 与阅读器 ViewModel 统一使用 `ChapterRef.Remote` / `ChapterRef.Local`。来源 Provider 仅接受 Remote；Local-first Provider 仅处理 Local 并将 Remote 委托给原下载优先/来源提供器。路由对 Local 使用 `reader:@local:<comic>:<chapter>` 编码，旧版四段远端 `reader:<source>:<comic>:<chapter>` 保持兼容。

历史 Room 表继续用字符串键，不做数据库迁移：本地进度的 `source_id` 使用保留命名空间 `@local`，漫画和章节 ID 分别使用本地 ID；已安装来源包不能声明以 `@` 开头的 ID。`ReaderProgressSession` 据 `ChapterRef` 映射该持久化键。

## 理由与后果

类型边界能在进入来源调用前区分本地和远端身份；已有 Reader UI、历史表与进度聚合可复用。路由和 PageProvider 契约变更由阶段内编译器与 round-trip 测试覆盖，历史 schema 不变。未来如历史需要跨设备同步，再由对应阶段设计可同步的本地 ID 语义。
