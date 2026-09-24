# Stage 2 质量复验

日期：2026-09-25

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
| 完整 Debug Lint | **失败（仅剩 2 项）** | 工具链与可兼容依赖更新后，完整 `lintDebug` 仅报告 WorkManager runtime/testing 2.12.0 提示；testing 工件当前无法从 Aliyun 镜像解析，版本保持配对的 2.11.2。未建立 baseline 或关闭检查。 |
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

本报告初版日期为 2026-09-24；以下 C12 是 2026-09-25 增补复验。

### 后续复验发现（2026-09-24）

按 JDK 17 重跑 `sh gradlew --offline --no-daemon --max-workers=2 lintDebug` 时，依次暴露下载通知的 `ObsoleteSdkInt`（`minSdk=26`，低于 API O 的分支不可达）、详情页 `DetailsRoute` 与书架 `LibraryRoute` / `LibraryScreen` 的 `ModifierParameter`。S2-07C2–C4 分别移除不可达分支并调整三个 Composable 的 `modifier` 参数位置；各受影响模块 Lint 和 Debug 编译均通过。完整全仓 Lint 仍需继续运行，原先记录的 13 条依赖提示暂不视为本轮复验已确认结果。

随后 S2-07C5 将 Core 1.19.0→1.19.1、JavaScriptEngine 1.1.0→1.1.1、Navigation3 1.1.7→1.2.0，均已列入 AndroidX 2026-09-23 stable 版本表（[官方版本清单](https://developer.android.com/jetpack/androidx/versions)、[JavaScriptEngine 1.1.1 发布说明](https://developer.android.com/jetpack/androidx/releases/javascriptengine)、[Navigation3 发布说明](https://developer.android.com/jetpack/androidx/releases/navigation3)）。验证：`:core:navigation:testDebugUnitTest :source:engine:testDebugUnitTest :data:download:testDebugUnitTest :data:download:compileDebugAndroidTestKotlin :app:assembleDebug` — PASS。随后全仓 `lintDebug` 初次报告剩余 9 项：WorkManager 2 个、Coil 3 个、QuickJS 2 个、org.json 1 个、XZ 1 个。

WorkManager 2.12.0 亦已列入同一 AndroidX stable 清单，但更新后 `:data:download:compileDebugAndroidTestKotlin` 无法从环境实际使用的 `https://maven.aliyun.com/repository/google` 解析 `androidx.work:work-testing:2.12.0`（No matching artifact）。为保持 runtime/testing 版本配对且可复现，未提交该版本更新，保留 2.11.2 并将 S2-07C6 标为 BLOCKED。2026-09-25 工具链升级后再次尝试 `:data:download:compileDebugAndroidTestKotlin`，仍因 `work-testing:2.12.0.aar` 未出现在 Aliyun 镜像而失败；解除条件仍是镜像同步或可用的 Google Maven 访问路径。

C12（2026-09-25）将 AGP/Gradle/KGP/Compose Compiler/KSP 升至 9.3.1 / 9.5.0 / 2.4.20 / 2.4.20 / 2.3.12，并更新 Coil 3.6.3、QuickJS 1.0.15。此前分别阻塞 Coil、QuickJS 的 Kotlin 2.4 metadata 限制已解除。更新后图像和来源引擎 JVM 测试通过，来源引擎与应用 AndroidTest 源码编译通过，Debug/Release 构建及 Release Lint Vital 通过；429 项全仓 JVM 测试全部通过。Release APK 内四种 ABI 的 QuickJS `libquickjs.so` ELF LOAD alignment 均为 16 KB。工具链版本依据：[Kotlin/KGP 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)、[Kotlin 2.4.20 发布说明](https://kotlinlang.org/docs/whatsnew2420.html)、[AGP 9.3 发布说明](https://developer.android.com/build/releases/agp-9-3-0-release-notes)、[KSP releases](https://github.com/google/ksp/releases)。

完整 `lintDebug` 当前剩余 2 项，均是 WorkManager runtime/testing 2.12.0 更新提示；testing AAR 在配置的 Aliyun 镜像不存在，C6 重试后仍未解除。Coil 与 QuickJS 相关提示已清零。另，C13 已修复 QuickJS 超时路径只取消等待者的问题：实际 evaluation `Deferred` 被取消并最多等待 1 秒，真实死循环 timeout（2.02 秒返回）、显式取消（0.12 秒返回）及之后同源重调用均通过。验证：`:source:engine:testDebugUnitTest :source:engine:compileDebugAndroidTestKotlin :app:assembleDebug` — PASS。

命令（JDK 17，离线）：

```sh
sh gradlew --offline --no-daemon --max-workers=2 lintDebug testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

该组合命令因 `:app:lintDebug` 的 13 条版本新鲜度错误返回失败，故另行运行其余构建和测试：

```sh
sh gradlew --offline --no-daemon --max-workers=2 testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

结果：`BUILD SUCCESSFUL`；全仓 JVM 测试 429/429 通过，Debug、Release 与 Release Lint Vital 通过。`git diff --check` 在本审查提交前执行。

初次报告有 13 条依赖版本提示。C5 更新 Core / JavaScriptEngine / Navigation3，C7 更新 XZ 1.12，C8 更新 org.json，C12 更新 Coil / QuickJS 并升级工具链后，当前完整 Lint 仍有 WorkManager runtime/testing 两项提示；WorkManager 工件限制和恢复条件见 C6。

## 退出阻断项与解除条件

1. 处理剩余 WorkManager runtime/testing 两条版本提示：须在 runtime/testing 配对更新可解析且完成 Worker 验证后使完整 `lintDebug` 成功；不能用 baseline 或静默抑制代替结论。
2. 在 Android API 26+ 设备或模拟器执行 `STATUS.md` 所列详情下载、暂停/继续、目录/归档阅读、飞行模式阅读及历史恢复闭环，并记录设备/API 与结果。本轮已验证 Xiaomi 25128PNA1C（API 36）的数据库与下载 Worker instrumentation，但 MIUI 拒绝 shell 输入事件，无法执行页面交互。新增书架导航 Compose instrumentation smoke test，`:app:compileDebugAndroidTestKotlin` 通过，但未在设备执行。本报告复查时 Android Studio Device Manager 与 ADB 均未发现连接设备；2026-09-25 在用户表示重新连接后再查，`adb devices -l` 仍返回空列表，UI 批次暂停。待设备实际枚举后使用正常授权的控制方式完成页面闭环，不通过修改安全设置绕过。
3. 完成以上处理后重跑规定 Stage 门禁，再更新本报告、`STATUS.md` 和 `IMPLEMENTATION_PLAN.md`；通过后才将 Stage 2 标为 `DONE`。

初版审查只修复了真机暴露的 instrumentation 表清单断言错误；后续代码审查发现与修复见下节。自动化证据仅覆盖报告列出的模块测试，不能推导页面设备闭环或 Stage 退出门禁通过。

## 后续代码审查发现（2026-09-25）

- **S2-07C14，已修复：书架继续/重试的 Worker 启动顺序。** 原 Route 在 ViewModel 异步调用 `resume`/`retryFailed` 后立刻启动 Worker，数据库尚未变为 Queued 时 Worker 可看到空队列并成功退出。改为仓库操作返回后更新调度版本，再由 Route 启动；悬挂仓库操作的 JVM 回归证明写入前不会发出调度信号。验证：`:feature:library:testDebugUnitTest :app:assembleDebug` — PASS。设备下载闭环仍未执行。
- **S2-07C15，已修复：过期任务认领顺序。** 旧实现仅认领无所有者/同 ID 的任务，使过期 Worker 的任务无法由新 Worker 更新心跳；清单收养也发生在认领之后。回归先复现旧任务与新收养任务均未认领，再改为先重排旧 Running 页、验证文件/收养清单，最后认领过期和无所有者任务。验证：`:data:download:testDebugUnitTest :app:assembleDebug` — PASS。真实 Room SQL 的设备复验等待 S2-07D。
- **S2-07C16，已修复：无路径成功页被漏检。** 数据库中 `Succeeded` 页的 `relativePath` 为 null 时，旧恢复逻辑直接跳过，致使损坏记录无法重试；新增回归先复现失败，现与文件缺失一样重排入队。验证：`:data:download:testDebugUnitTest :app:assembleDebug` — PASS。
- **S2-07C17，已修复：混合目录丢失章节。** 根目录有图片（包括仅有封面）时旧扫描完全忽略章节子目录。新增两项失败先行回归，现合并根目录有效图片章与自然排序的子目录章。验证：`:data:local:testDebugUnitTest :app:assembleDebug` — PASS；真实 SAF 设备验证等待 S2-07D。
- **S2-07C18，已修复：Queue 异常结果未落库。** 页面执行抛出非取消异常时 Queue 返回 `PageRunResult(error)`，原 Worker 未消费该结果，页保持 `Running` 且 Worker 返回成功。现对失败结果调用受状态机保护的 `markFailed`；新增真实 Worker + Room 回归源码并通过编译，设备运行等待 S2-07D，不能记作已通过。
- **S2-07C19，已修复：下载页读写竞态。** `movePage` 旧实现按读到的状态判断后无条件写入，期间用户取消/暂停可能被覆盖。新增回归在读写之间取消，先复现失败；Room 更新现带 `expectedState` 条件，影响行数为 0 则保持用户新状态。验证：`:data:download:testDebugUnitTest :data:download:compileDebugAndroidTestKotlin :app:assembleDebug` — PASS；真实 Room 并发设备执行待 S2-07D。
- **S2-07C20，已修复：WorkManager 重试后的运行页遗留。** 原恢复跳过同 ID 页，而 WorkManager 同一 WorkSpec 的重试会沿用 ID；另一个 Worker 遇到旧心跳尚新的 Running 页也会错误地返回成功。现在新 attempt 重排自己上次留下的页，其他所有者尚新时保持 Retry 等待过期。JVM 回归先复现同 ID 场景；验证：`:data:download:testDebugUnitTest :data:download:compileDebugAndroidTestKotlin :app:assembleDebug` — PASS。进程死亡/重试设备场景仍待 S2-07D。
- **S2-07C21，已修复：本地章节提前填满缓存。** 旧本地 Provider 在 `loadChapter` 物化全章页面，有界缓存会淘汰尚未打开的前页；Reader 也会把已预取页永久记为完成，返回被淘汰页时不再修复。现按当前可见页物化、离开窗口后允许重解析，并在页文件被淘汰时按原 SAF/归档引用重新物化。验证：`:data:local:testDebugUnitTest :feature:reader:testDebugUnitTest :app:assembleDebug` — PASS；真实长章节、手势和内存表现等待 S2-07D。
- **S2-07C22，已修复：归档刷新误用目录扫描与取消吞没。** 原 `refresh` 对 Archive 调 `SafTreeAccess.read`，失败或空树会删除索引；导入归档的宽泛异常捕获也会把协程取消变成 `Unavailable`。现按 kind 分支刷新，并在兜底捕获前重新抛出取消。新增 Room/协程设备回归源码；验证：`:data:local:compileDebugAndroidTestKotlin :app:assembleDebug` — PASS，设备运行待 S2-07D。
