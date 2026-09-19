# 项目执行状态

> 本文件是项目当前进度的唯一事实来源。新会话必须先读根目录 `AGENTS.md`，再读本文件。每次完成任务后必须同步更新。

## 状态快照

| 项目 | 当前值 |
| --- | --- |
| 最后更新 | 2026-09-19 |
| 当前阶段 | Stage 0：技术验证 |
| 当前任务 | S0-02：JavaScript Runtime 最小执行闭环 |
| 当前任务状态 | TODO |
| 默认分支 | `main` |
| 远程仓库 | `https://github.com/lwtor/venera-native` |
| 最近基线提交 | `054e654 build: scaffold Android multi-module project` |
| 工作基线 | AGP 9.4、Gradle 9.7.1、JDK 17、SDK 37、minSdk 26 |

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
APK：app/build/outputs/apk/debug/app-debug.apk
```

## 当前代码事实

- `:app` 目前直接展示 `HomeRoute`，尚未建立完整根导航。
- `:feature:home` 只是占位 UI，不包含 ViewModel 或真实数据。
- `:source:api` 只有初始模型，不代表最终兼容协议。
- `:source:engine` 目前只能检查 JavaScriptEngine 是否受支持，尚不能创建沙箱或执行脚本。
- 尚未引入 Hilt、Room、DataStore、OkHttp、Coil、Paging、WorkManager。
- 尚未创建 CI、许可证文件、正式图标或发布配置。
- 目前唯一测试是 `ComicKeyTest`。
- `applicationId = dev.veneranative` 仍是暂定值。

## 当前唯一下一任务

### S0-02：JavaScript Runtime 最小执行闭环 — TODO

目标：在 `:source:engine` 中使用 AndroidX JavaScriptEngine 建立可测试的最小沙箱执行链路，并通过 `:source:api` 暴露稳定、与具体引擎无关的接口。

必须交付：

1. 细化 `SourcePackage`、`SourceCall`、`SourceResult` 和错误模型，使最小脚本执行无需 UI 拼接 JavaScript。
2. 增加引擎生命周期：创建、加载、调用、取消、卸载和关闭。
3. 每个已加载来源拥有隔离的命名空间或沙箱实例；实现中必须明确生命周期所有权。
4. 实现 JSON 参数和 JSON 结果往返。
5. 处理不支持 JavaScriptEngine、脚本语法错误、执行错误、超时、取消和已卸载来源。
6. 增加仓库内测试 fixture；不得依赖在线漫画站点。
7. 增加单元测试；必须依赖 Android Runtime 的部分增加 instrumentation test。
8. 用 ADR 记录 AndroidX JavaScriptEngine API 选型、限制和仍待验证的 fallback 条件。

验收场景：

- 加载一个最小 fixture 后调用纯函数并得到结构化结果。
- 两个来源的全局变量互不污染。
- 语法错误和运行时错误映射为稳定错误类型。
- 超时后调用结束，后续调用仍可控。
- 取消调用不会错误返回成功。
- 卸载后不能继续调用该来源。
- 不支持引擎的设备返回显式能力错误，不崩溃。

建议验证命令：

```powershell
.\gradlew.bat :source:api:testDebugUnitTest :source:engine:testDebugUnitTest
.\gradlew.bat :source:engine:connectedDebugAndroidTest
.\gradlew.bat lintDebug :app:assembleDebug
```

不在 S0-02 范围：

- 真实 HTTP Host API。
- Cookie、登录、HTML 解析或图片请求。
- 安装远程漫画源的 UI。
- 完整 Venera 协议兼容。
- QuickJS fallback 的实现。
- Reader UI。

完成 S0-02 后，将当前任务推进为 S0-03。

## 紧随其后的任务

| 顺序 | ID | 名称 | 前置 |
| --- | --- | --- | --- |
| 1 | S0-03 | 异步 Host API 与受控网络桥 PoC | S0-02 |
| 2 | S0-04 | Runtime 限制、二进制和压力验证 | S0-03 |
| 3 | S0-05 | Compose 阅读器基础原型 | S0-01 |
| 4 | S0-06 | 超长图、缩放和内存验证 | S0-05 |
| 5 | S0-07 | Stage 0 决策收敛与 ADR | S0-04、S0-06 |

## 已知风险与待确认

| 项目 | 状态 | 解除条件 |
| --- | --- | --- |
| AndroidX JavaScriptEngine 是否满足异步 Host API 和二进制需求 | 验证中 | 完成 S0-02 至 S0-04 |
| 超长图是否需要专用子采样组件 | 未验证 | 完成 S0-05 至 S0-06 |
| QuickJS fallback 是否必要 | 暂缓决定 | JavaScriptEngine PoC 形成数据后写 ADR |
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
- [ ] 已在实现前确定测试策略。
- [ ] 完成后会更新本文件及实施计划状态。