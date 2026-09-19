# ADR-0001：多模块边界与依赖方向

- 状态：Accepted
- 日期：2026-09-19
- 决策者：项目维护者
- 关联文档：`../ARCHITECTURE.md`

## 上下文

Venera Native 需要覆盖漫画源脚本、网络、图片、持久化、下载、本地文件和多组 UI 功能。如果所有能力都进入单一 App Module，平台实现会泄漏到 UI，Feature 会互相耦合，JavaScript Runtime 也难以单独测试和替换。

项目同时处于早期阶段。一次性创建所有目标模块会产生大量空壳和不真实的边界。

## 决策

采用单 App 入口和按职责分组的多模块结构：

- `:app` 是唯一 Android Application 模块。
- `:feature:*` 承载用户可见功能，Feature 间不直接依赖。
- `:data:*` 实现 Repository 并组合存储、网络与 Source Runtime。
- `:core:*` 承载跨 Feature 的稳定模型和基础能力。
- `:source:*` 隔离脚本协议、执行引擎和 Host 能力。
- 模块仅随当前可交付切片创建，不预先创建全部目标空模块。
- 第三方实现类型不得穿透稳定 API 边界。
- 默认使用 Gradle `implementation`。

允许的高层依赖方向：

```text
app -> feature -> contracts/core
app -> data -> contracts/core
data -> source:api <- source:engine
source:engine -> source host implementations
```

完整规则见 `docs/ARCHITECTURE.md`。

## 理由

- 保持 `:app` 轻量，便于替换页面和测试业务模块。
- 隔离 JavaScriptEngine、OkHttp、Room、Coil 等具体实现。
- Feature 可独立编译和测试，降低跨功能改动范围。
- 延迟创建模块，避免早期架构只有形式没有真实调用关系。
- 为未来 Source Runtime fallback 留出替换空间。

## 后果

正面影响：

- 依赖方向清晰，功能可逐步交付。
- Runtime 和 Reader 两项高风险能力可独立验证。
- 平台实现不会直接成为 UI 契约。

成本：

- Gradle 配置和模块 API 数量增加。
- 跨 Feature 导航需要单独契约。
- 维护者必须持续检查依赖方向，不能只依靠包名约定。

## 替代方案

### 单 App Module

拒绝。早期开发更快，但会把 Source Runtime、数据库、下载和 UI 紧密绑定，后续拆分成本高。

### 每个 Feature 固定拆成 API/Domain/Data/UI 四个模块

暂不采用。当前规模下过度机械化；只有当编译隔离、复用或实现替换产生真实需求时再拆分。

### Kotlin Multiplatform

拒绝。当前目标是充分使用 Android 官方能力，没有跨平台目标。