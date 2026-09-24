# Stage 2 质量复验

日期：2026-09-24

实现基线：`1f4f23d`（S2-07B；包含 S2-07A、S2-06、S2-05 及之前的 Stage 2 提交）
结论：**未通过阶段退出门禁；Stage 2 保持 IN_PROGRESS。**

## 验收矩阵

| 验收项 | 结论 | 证据与限制 |
| --- | --- | --- |
| SAF 目录与 ZIP/CBZ/7z 导入 | JVM/构建通过 | S2-05、S2-06 记录了目录扫描、自然排序、归档读取、缓存淘汰/上限测试与 Debug 编译。未覆盖真实 SAF Provider、所有压缩算法、加密归档。 |
| 本地章节进入统一阅读器 | 自动化通过，设备未验 | S2-07A 的 `ChapterRef` 路由兼容、本地页读取隔离来源、本地历史恢复测试已通过；没有在设备逐项操作目录与归档章节。 |
| 详情页排队及下载列表控制 | 相关 JVM 测试通过 | S2-07B：`:feature:details:testDebugUnitTest :feature:library:testDebugUnitTest :app:assembleDebug` 通过；覆盖详情排队、列表暂停/继续/重试/移除委派。 |
| 离线下载章节读取 | 自动化通过，飞行模式未验 | `OfflineFirstPageProvider` 有完整下载命中与不完整下载回退测试；真实断网翻页未执行。 |
| 全仓 JVM 单测 | 通过 | `testDebugUnitTest`：429 tests、0 failures、0 errors、0 skipped。 |
| Debug APK | 通过 | `:app:assembleDebug`。 |
| Release APK / Release Lint Vital | 通过 | `:app:assembleRelease` 含 `:app:lintVitalRelease` 成功；未签名构建。 |
| 完整 Debug Lint | **失败** | `lintDebug` 有 13 条 `GradleDependency` / `NewerVersionAvailable` 错误，均在 `gradle/libs.versions.toml` 指向较新依赖版本。未建立 baseline 或关闭检查。 |
| 跨模块设备用户闭环 | **未验证** | 没有执行安装、详情下载、下载控制、本地导入/阅读、飞行模式阅读及恢复位置的人工脚本；不能由单测与构建替代。 |

## 真机补充复验（2026-09-24）

设备：Xiaomi 25128PNA1C，Android 16 / API 36，1200×2608。当前 Debug APK 已通过 `adb install -r` 覆盖安装，保留了原应用数据；启动到首页正常，最近日志未发现应用崩溃。

```text
:core:database:connectedDebugAndroidTest — 首次 25 tests 中 5 个失败，均为 schema 表清单误包含 Room 内部表 room_master_table；修正过滤后重跑 25/25 通过。
:data:download:connectedDebugAndroidTest — 4/4 通过：队列为空、Worker 停止、前台通知 channel、页面下载/校验/完成状态。
```

上述属于模块 instrumentation，不等价于页面端到端手工验收。手机 MIUI 拒绝 `adb shell input tap`（缺少 `INJECT_EVENTS`），因此没有进入书架、详情、文件选择器或飞行模式页面流。没有修改安全设置绕过该系统限制；详情下载、本地导入阅读、离线翻页与进度恢复仍未验证。

本轮发现并修复的是数据库 AndroidTest 对 Room 内部表的测试假失败，不是产品数据库缺表；修复已独立提交。

## 阶段门禁复验

命令（JDK 17，离线）：

```sh
sh gradlew --offline --no-daemon --max-workers=2 lintDebug testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

该组合命令因 `:app:lintDebug` 的 13 条版本新鲜度错误返回失败，故另行运行其余构建和测试：

```sh
sh gradlew --offline --no-daemon --max-workers=2 testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

结果：`BUILD SUCCESSFUL`；全仓 JVM 测试 429/429 通过，Debug、Release 与 Release Lint Vital 通过。`git diff --check` 在本审查提交前执行。

Lint 报告列出的候选版本：AndroidX Core 1.19.1、JavaScriptEngine 1.1.1、Navigation 3 1.2.0、WorkManager 2.12.0、Coil 3.6.3、quickjs-kt 1.0.15、org.json 20260814、XZ 1.12。它们不是实现代码错误，但当前仓库把 Lint warnings-as-errors，因此完整 Debug Lint 门禁确实未通过。依赖版本升级需各自核对 Kotlin/AGP 元数据兼容性、QuickJS 契约和 Commons Compress/XZ 兼容矩阵，不能只为消掉提示而无验证地批量升级。

## 退出阻断项与解除条件

1. 对 13 个版本检查逐项作出有证据的兼容性处理，并使完整 `lintDebug` 成功。允许升级的依赖应在独立小任务中逐个验证并提交；不能升级的项需要调整项目门禁设计并记录决策，不能用 baseline 或静默抑制代替结论。
2. 在 Android API 26+ 设备或模拟器执行 `STATUS.md` 所列详情下载、暂停/继续、目录/归档阅读、飞行模式阅读及历史恢复闭环，并记录设备/API 与结果。本轮已验证 Xiaomi 25128PNA1C（API 36）的数据库与下载 Worker instrumentation，但 MIUI 拒绝 shell 输入事件，无法执行页面交互。后续需用允许 UI 自动化的设备连接方式完成页面闭环；不得通过修改设备安全设置绕过。
3. 完成以上处理后重跑规定 Stage 门禁，再更新本报告、`STATUS.md` 和 `IMPLEMENTATION_PLAN.md`；通过后才将 Stage 2 标为 `DONE`。

本轮没有发现生产代码缺陷；已修复真机才暴露的 instrumentation 表清单断言错误。自动化证据仅覆盖报告列出的模块测试，不能推导页面设备闭环或 Stage 退出门禁通过。
