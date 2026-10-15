# Venera Native

Venera Native 是一个受 [Venera](https://github.com/venera-app/venera) 启发的非官方 Android 原生漫画阅读器，使用 Kotlin、Jetpack Compose、MVI/UDF 和 Jetpack 组件重新实现其核心功能与漫画源生态。

> 本项目与原 Venera 维护团队无隶属关系。项目名称中的 “Native” 用于明确区分 Android 原生重构与原 Flutter 项目。

## 当前状态

**Stage 0（技术验证）和 Stage 1（核心阅读闭环）已完成；Stage 2 仍在进行，S2-01 至 S2-06 已交付，S2-07 集成复验进行中。** Stage 1 全部切片均已交付：S1-01 稳定领域模型
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

阅读器点击中央打开工具层，闲置五秒后自动收起，持续拖动期间保持显示。远端漫画（含已下载章节）可从标题栏右侧圆形“详”按钮退出阅读器：从详情进入时回到原详情，其他入口进入时打开对应详情，返回时回到原入口。

Stage 0 的关键结论与遗留项见 `docs/STATUS.md`；设备侧验证（解码耗时、PSS、掉帧、手势冲突）
按项目决定推迟，需要时用 `LargeImageProbeTest` 采集。

漫画列表统一使用 `core:designsystem` 的 `ComicCoverCard`（透明度 70% 的模糊封面信息底、单行名称/阅读与总话数、来源角标）和 `ComicHorizontalCard`（封面等高横向信息行）。收藏、首页、搜索、探索、本地与下载分组共享组件，新增展示应复用它们；取色留在 `core:image`。读过的总话数只取当前版本，未读取所有版本最大话号，不相加；缺目录的可见收藏自动补读并本地保存，加载/失败显示短提示；旧历史版本有歧义时仍不猜测。章节文案为“10/111话”，未读只显示“111话”。设计决定见 [ADR-0014](docs/adr/0014-shared-comic-cards.md)。

收藏使用独立漫画资料和分版本章节表，并将封面保存在应用私有文件目录，优先读取本地封面。前台立即检查过期资料，之后每 15 分钟检查；资料默认 12 小时过期、失败退避 15 分钟，最多两个并发，失败继续使用旧资料。GitHub 备份仍手动触发。取消收藏保留资源；普通网络缓存清理不移除持久封面，卸载/清应用数据会移除。存储及迁移决定见 [ADR-0015](docs/adr/0015-retained-comic-data.md)。

```text
filesDir/downloads/<来源Hash>/<漫画Hash>/
  covers/cover.image       # 持久封面
  covers/source.sha256     # URL 摘要与取得时间
  chapters/…              # 原有下载路径，不搬移
```

开发接管入口：

详情页支持点击来源声明可导航的作者/画师及其他标签，按 `comic.onClickTag` 的搜索或分类目标查看同源相关作品，并返回原详情；Komiic 未声明该回调时，通过专用适配查询精确作者 ID，其他标签使用源声明的分类 ID。没有导航能力的来源保留静态标签，无法解析的标签给出提示。协议和验收限制见 ADR-0007 与 STATUS。

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

主导航为「首页 / 书架 / 我的」；我的页提供 GitHub 授权、头像/昵称、此设备最近成功同步时间、手动备份与恢复入口，以及显示实际安装版本的关于页。MainActivity 只处理生命周期，根导航与装配 UI 位于 AppRoot。当前执行进度始终以 `docs/STATUS.md` 为准。

```text
:app
:core:database
:core:archive
:core:backup
:core:designsystem
:core:image
:core:model
:core:navigation
:core:network
:data:comic
:data:collection
:data:backup
:data:download
:data:history
:data:settings
:data:source
:feature:details
:feature:backup
:feature:profile
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

- Android Gradle Plugin 9.3.1 + Gradle 9.5.0
- AGP 内置 Kotlin（KGP 2.4.20）+ JDK 17
- KSP 2.3.12（与 AGP 9 内置 Kotlin 配合，见 `docs/STATUS.md`）
- compileSdk / targetSdk 37，minSdk 26
- Jetpack Compose + Material 3
- Navigation 3
- Coil 3.6.3（不引 `coil-network-okhttp`，网络走共享 OkHttp）
- QuickJS 1.0.15
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

Stage 2 的下载队列由 Room 持久化，并通过 WorkManager 前台 Worker 在非计量网络且存储空间充足时执行；下载
复用阅读器的来源 Cookie 和图片管线。阅读器已支持完整章节直接从下载文件读取，缺页时回退到来源；书架 Local tab 已支持 SAF 目录导入。详情页下载入口、下载列表以及飞行模式下下载和本地内容的端到端闭环验收列在 S2-07。

## 许可与来源说明

Venera 原项目采用 GPL-3.0。本仓库当前按公开漫画源约定独立实现，没有有意复制 Venera 源码或其捆绑内容。
直接依赖和发布前仍需完成的许可工作见 `THIRD_PARTY_NOTICES.md`；在选择项目许可证和生成完整依赖
许可报告前，不把当前构建视为可公开发布版本。

质量门禁与逐任务提交见 `AGENTS.md`；Stage 1 最终审查见 `docs/reviews/stage-01-final.md`。根依赖由保留的 `AppGraph` 装配，启用来源支持冷启动恢复。
