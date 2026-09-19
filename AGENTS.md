# Venera Native Agent Guide

本文件是所有自动化 Agent 和新会话进入本仓库后的第一入口。目标是让执行者无需依赖历史聊天即可判断项目现状、选择下一任务、完成验证并留下可继续接管的状态。

## 1. 首次接管顺序

开始修改前必须按顺序阅读：

1. `AGENTS.md`
2. `docs/STATUS.md`
3. `docs/IMPLEMENTATION_PLAN.md` 中当前任务对应章节
4. `docs/ARCHITECTURE.md`
5. `docs/PROJECT_PLAN.md` 中与当前任务相关的产品章节
6. 当前任务涉及模块的源码与测试

如果文档冲突，优先级为：

```text
用户当前明确要求
  > docs/STATUS.md
  > 已接受的 ADR / docs/ARCHITECTURE.md
  > docs/IMPLEMENTATION_PLAN.md
  > docs/PROJECT_PLAN.md
  > README.md
```

`docs/STATUS.md` 是项目执行状态的唯一事实来源。不要仅根据 README、提交信息或聊天记录推断当前进度。

## 2. 项目身份

- 名称：Venera Native
- 仓库：`https://github.com/lwtor/venera-native`
- 默认分支：`main`
- 平台：Android Native
- applicationId：`dev.veneranative`（暂定，发布前仍需确认）
- 上游参考：`https://github.com/venera-app/venera`
- 定位：受 Venera 启发、以兼容其漫画源生态为目标的非官方 Android 原生实现

项目不以逐行翻译 Flutter 代码为目标。复用上游代码、资源或协议实现前，必须先确认许可证影响并记录来源。

## 3. 开始任务前

1. 执行 `git status --short --branch`，不得覆盖用户未提交的更改。
2. 从 `docs/STATUS.md` 的“当前唯一下一任务”开始；除非用户明确指定其他任务。
3. 核对任务的依赖项是否已经完成。
4. 阅读将要修改的模块，确认依赖方向。
5. 对不稳定的 Android API 或依赖版本查阅官方文档，禁止凭记忆猜测。
6. 先明确本次切片的编译命令，再开始实现；非关键节点不得自行扩大验证范围。

如果当前任务过大，应按 `docs/IMPLEMENTATION_PLAN.md` 中的子任务边界拆分，但不要同时铺开多个未完成架构。

## 4. 架构硬约束

- `:app` 只负责应用入口、根导航和依赖装配，不承载业务实现。
- Feature 之间禁止直接依赖。
- `:core:model` 不得依赖 Android Framework、Compose、数据库或网络。
- `:source:api` 是漫画源稳定契约，不依赖具体 JavaScript 引擎。
- `:source:engine` 不得向脚本暴露 `Context`、任意 Java 对象、文件系统或反射能力。
- UI 只能通过类型化 `SourceCall` 使用漫画源，禁止拼接 JavaScript。
- 默认使用 `implementation`；只有有意暴露的稳定契约才使用 `api`。
- 跨模块新增依赖前，先核对 `docs/ARCHITECTURE.md` 的允许依赖表。
- 不为“以后也许需要”创建空模块；模块随可交付切片创建。

违反边界的实现即使能运行，也不视为任务完成。

## 5. Kotlin、Compose 与 MVI 约定

- 使用 Kotlin；不新增 Java 业务代码。
- 公开类型优先不可变数据模型和 `sealed interface`。
- 复杂页面使用 `UiState + UiAction + ViewModel` 的单向数据流。
- Compose 只渲染状态并发送 Action，不直接调用数据库、网络或 Source Runtime。
- 状态使用 `StateFlow`；一次性行为优先建模为可消费状态，避免全局 EventBus。
- Dispatcher、时间和随机数在需要测试时必须注入。
- 不在 Compose State、SavedStateHandle 或数据库中保存 Bitmap、大型二进制和 Android `Context`。
- 错误必须映射为领域错误，不把异常文本直接作为产品文案。
- 新增业务逻辑必须包含同层级测试；修复缺陷应先添加可复现测试。

## 6. 漫画源安全约定

- 所有脚本调用必须有调用 ID、超时和取消路径。
- 每个来源独立会话、Cookie 和私有数据。
- 日志必须脱敏 Cookie、Authorization、Token、密码和用户输入。
- 测试使用仓库内最小化 fixture，不依赖真实商业漫画站点或真实账号。
- 禁止提交来源密钥、用户数据、受版权保护内容或绕过访问控制的代码。
- Release 构建不得忽略 TLS 错误。
- Host API 使用显式允许列表；未列出的能力默认不可用。

## 7. 构建与验证

要求 JDK 17。Windows 若 `JAVA_HOME` 不正确，可为当前命令临时指定 JDK 17，但不得提交本机绝对路径。

### 默认策略：只保证编译

普通开发任务、功能切片和中间提交只执行与改动直接相关的编译：

```powershell
.\gradlew.bat :app:assembleDebug
```

如果改动不经过 `:app`，可改为对应模块的 `assembleDebug`、`compileDebugKotlin` 或测试 APK 编译任务。默认不运行全量 Lint、全量单元测试、instrumentation test、压力测试、benchmark、安装 APK 或实机验证。

编写测试代码属于实现工作，但普通节点只要求测试源码能够编译；除非用户明确要求，不因为新增了测试就自动执行完整测试。

提交前最低检查：

```powershell
.\gradlew.bat :app:assembleDebug
git diff --check
```

### 关键节点才做完整验证

仅在以下情况执行 Lint、单元测试、instrumentation test、压力测试、benchmark、安装 APK 或实机验证：

- 一个 Stage 或明确里程碑准备退出；
- Release Candidate 或正式发布前；
- 用户明确要求执行；
- 当前任务本身就是专项验证任务；
- 编译无法定位问题，必须运行最小相关测试才能继续。

即使处于关键节点，也只执行计划明确要求的验证，不擅自增加重复验证。耗时验证开始前应确认它对当前交付确有必要。无法执行的关键验证在 `docs/STATUS.md` 记录原因和风险，不得写成“已验证”。

## 8. 完成任务后的文档协议

每个任务完成后必须：

1. 更新 `docs/STATUS.md`：
   - 已完成内容；
   - 实际验证命令与结果；
   - 新增风险或决策；
   - 将“当前唯一下一任务”推进到下一项。
2. 更新 `docs/IMPLEMENTATION_PLAN.md` 中任务状态。
3. 架构决策发生变化时新增或更新 `docs/adr/`，不得只留在聊天或提交信息里。
4. 模块、构建方式或入口改变时更新 `README.md` 和 `docs/ARCHITECTURE.md`。
5. 确保工作区只包含本任务相关修改。

状态标记固定使用：

- `TODO`：未开始
- `IN_PROGRESS`：当前唯一执行任务
- `BLOCKED`：有明确外部阻塞，并在状态文档写明解除条件
- `DONE`：交付物和验收项全部完成

同一时刻原则上只有一个 `IN_PROGRESS` 任务。

## 9. Definition of Done

一个任务只有同时满足以下条件才可标记为 `DONE`：

- 验收标准逐项满足。
- 所需测试代码已添加并能够编译；仅关键节点要求实际运行并通过。
- 受影响模块编译通过；Lint 和完整测试仅按第 7 节的关键节点策略执行。
- 没有越过模块边界或引入循环依赖。
- 没有提交本机配置、缓存、构建产物、凭据或真实用户数据。
- 文档状态与代码事实一致。
- 新 Agent 只阅读仓库即可理解下一步，不需要依赖本次聊天。

## 10. 禁止事项

- 不要重新初始化 Git、改变远程地址或重写历史。
- 不要在未说明原因时大规模升级依赖。
- 不要使用 lint baseline、跳过测试或吞掉异常来制造“通过”。
- 不要把业务代码放进 `:app`。
- 不要提前实现后续阶段并留下半成品接口。
- 不要删除或覆盖不属于当前任务的用户修改。
- 不要将上游 Venera 源码直接复制进来而不记录来源与许可证判断。
