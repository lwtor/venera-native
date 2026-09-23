# Venera Native

Venera Native 是一个受 [Venera](https://github.com/venera-app/venera) 启发的非官方 Android 原生漫画阅读器，使用 Kotlin、Jetpack Compose、MVI/UDF 和 Jetpack 组件重新实现其核心功能与漫画源生态。

> 本项目与原 Venera 维护团队无隶属关系。项目名称中的 “Native” 用于明确区分 Android 原生重构与原 Flutter 项目。

## 当前状态

**Stage 0（技术验证）已完成，Stage 1（核心阅读闭环）已完成**。Stage 1 全部切片均已交付：S1-01 稳定领域模型
与 Source Core 协议、S1-02 来源包安装与管理、S1-03 探索与搜索、S1-04 漫画详情与章节、S1-05 Coil 漫画图片
管线、S1-06 Room 历史与阅读进度、S1-07 核心闭环集成。

端到端可完成的动作：安装仓库内测试源 → 探索或搜索 → 打开详情选章 → 经真实图片管线进入阅读器 →
退出后重新进入恢复阅读进度 → 来源错误有可恢复 UI。人工验证脚本与其未执行的说明记录在
[`docs/STATUS.md`](docs/STATUS.md)。

Stage 1 的两项前置决策已经落地：[ADR-0007](docs/adr/0007-source-protocol-compatibility.md) 划定
承诺兼容的源协议子集，[ADR-0008](docs/adr/0008-async-host-transport.md) 决定用自有引擎替代
WebView 系引擎来提供异步 Host API。

阅读器原型可以直接运行（纵向连续阅读、横向 LTR/RTL 翻页、页码、邻近预取、双指缩放），
并且在生成测试图后会走真实解码管线。大图问题已收敛为可验证的算术结论：确定性测试图生成器
（`tools/test-images`）、`Sampled` 与 `Region` 两种解码策略、有界位图缓存，以及把单次解码
上界写成断言的 JVM 预算测试。默认策略与依据见 `docs/adr/0004-large-image-strategy.md`。

Stage 0 的关键结论与遗留项见 `docs/STATUS.md`；设备侧验证（解码耗时、PSS、掉帧、手势冲突）
按项目决定推迟，需要时用 `LargeImageProbeTest` 采集。

开发接管入口：

- [Agent 接管规则](AGENTS.md)
- [当前状态与下一任务](docs/STATUS.md)
- [可执行实施计划](docs/IMPLEMENTATION_PLAN.md)
- [架构与模块边界](docs/ARCHITECTURE.md)
- [完整产品规划](docs/PROJECT_PLAN.md)
- [架构决策记录](docs/adr/README.md)

上游参考：

- 原项目：[venera-app/venera](https://github.com/venera-app/venera)
- 原漫画源协议：[Comic Source](https://github.com/venera-app/venera/blob/master/doc/comic_source.md)
- 原 JavaScript API：[JavaScript API](https://github.com/venera-app/venera/blob/master/doc/js_api.md)

## 当前模块

```text
:app
:core:database
:core:designsystem
:core:image
:core:model
:core:navigation
:core:network
:data:comic
:data:collection
:data:download
:data:history
:data:source
:feature:details
:feature:explore
:feature:home
:feature:library
:feature:reader
:feature:search
:feature:sources
:source:api
:source:core
:source:engine
:source:network
```

`:core:common` 与 `:core:navigation` 在 S0-07 因零引用被删除，会分别在出现真实聚合需求与
S1-03 需要类型安全导航契约时重建，见 `docs/ARCHITECTURE.md` 第 3 节。

## 技术基线

- Android Gradle Plugin 9.2.1 + Gradle 9.4.1
- AGP 内置 Kotlin（KGP 2.2.10）+ JDK 17
- KSP 2.3.10（不得降级到 2.2.10：会与 AGP 内置 Kotlin 冲突，见 `docs/STATUS.md`）
- compileSdk / targetSdk 37，minSdk 26
- Jetpack Compose + Material 3
- Navigation 3
- Coil 3.4.0（不引 `coil-network-okhttp`，网络走共享 OkHttp）
- Room 2.8.5
- Gradle Kotlin DSL + Version Catalog + Convention Plugins

## 构建

```shell
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat assembleDebug
```

## 大图验证测试图

S0-06 使用本地生成的图片，不提交任何真实漫画页：

```powershell
java -Xmx2g tools/test-images/GenerateTestImages.java feature/reader/src/main/assets/fixtures
```

生成后阅读器会改用真实解码管线；未生成时自动退回占位渲染。输出目录已被 `.gitignore` 排除。

Stage 1 的完整本地来源闭环使用 `tools/test-sources/demo_comic_source.js`。生成图片、启动本地 HTTP
服务、通过系统文件选择器安装以及离线重试步骤见 `tools/test-sources/README.md`。

## 许可与来源说明

Venera 原项目采用 GPL-3.0。本仓库当前按公开漫画源约定独立实现，没有有意复制 Venera 源码或其捆绑内容。
直接依赖和发布前仍需完成的许可工作见 `THIRD_PARTY_NOTICES.md`；在选择项目许可证和生成完整依赖
许可报告前，不把当前构建视为可公开发布版本。

质量门禁与逐任务提交见 `AGENTS.md`；当前整改状态见 `docs/reviews/stage-01-remediation.md`。根依赖由保留的 `AppGraph` 装配，启用来源支持冷启动恢复。
