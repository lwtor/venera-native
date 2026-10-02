# Stage 2 退出门禁复核（2026-10-02）

结论：**通过；Stage 2 标记 `DONE`。** 先前阻断的 API 26 环境已准备完成，来源调用、真实 SAF 目录授权、Reader 加载和运行期下载通知闭环均在 API 26 实测通过。Stage 2 退出门禁复跑也通过；D01–D07 已具备与范围相符的跨模块验收证据。可推进 S3-00。

## 基线与复核范围

- 代码基线：当前 HEAD `0990eba`（D07 API 26 设备证据）；产品代码最后修改在此前审查和局部修复提交中，本次没有产品代码变更。
- 复核依据：`docs/reviews/stage-02-review.md` 中 C1–C25 的代码质量审查与整改记录、`docs/reviews/stage-02-device-checklist.md` 的设备证据，以及当前 Android Manifest、Worker 前台通知测试和 D01/D02 App instrumentation。
- 本次未更改产品代码。D28 在 Xiaomi 25128PNA1C / API 36 上运行下载通知与暂停/继续/移除闭环；D30 在 API 26 完成最低版本用户闭环。两个设备上的测试服务和 reverse 均已清理，App 与测试 APK 保留。

## 验收矩阵

| 门禁 | 结论 | 证据与限制 |
| --- | --- | --- |
| S2 代码质量审查及 C1–C25 整改 | 通过（历史审查） | `stage-02-review.md` 记录逐项缺陷、回归和提交；S2-07C25 全仓 439 项 JVM 测试、Debug/Release 构建及 Release Lint Vital 通过。 |
| D01 来源、导航、阅读器 | 通过 | Xiaomi API 36 真机 fixture 探索/搜索/书架导航与 Reader 返回闭环见设备清单；API 30 补充来源脚本测试 `OK (1 test)`。 |
| D02 下载通知与控制 | 通过 | Xiaomi API 36 `Stage2DownloadControlTest#slowChapterCanPauseResumeAndRemove` `OK (1 test)`、62.712 秒；通知权限已授予，运行通知可见，暂停/继续完成 3/3，移除后 Room 与文件清理一致。 |
| D03 Worker 恢复及并发取消 | 通过 | 设备清单记载 Xiaomi API 36 真实进程恢复、Room 恢复边界和迟到页取消回归通过。 |
| D04 飞行模式离线阅读与进度恢复 | 通过 | Xiaomi API 36 设备清单记载断网三页阅读及退出重开位置恢复通过。 |
| D05 SAF 目录与归档 | 通过（限定范围） | 目录导入 Room 断言和合成 ZIP 导入/刷新/重开有证据；真机归档格式覆盖 ZIP，7z 仅模块 fixture，取消/权限丢失未在设备 UI 演练。 |
| D06 长章节、缓存淘汰、Reader 手势 | 通过 | Xiaomi API 36 长章节压力 instrumentation 与 Reader 横向前进/返回、纵向长图滚动各 `OK (1 test)`。 |
| D07 API 26 最低版本闭环 | 通过 | AOSP ARM64 API 26 模拟器上，fixture 来源脚本及 Explore/Search/Library→Reader 返回测试 1/1；DocumentsUI SAF picker 实际授权生成的目录，App 显示导入成功，Reader 页面显示为 1/2 且 Page 1 图像节点正常；下载通知 instrumentation `OK (1 test)`，运行期通知可见，Pause/Resume 达到 3/3，Remove 后数据库和文件清理一致。另有 Xiaomi API 36 的通知与控制闭环以及 API 34+ `dataSync` 类型断言。 |

## 本次验证与发现

- Xiaomi API 36：`DownloadWorkerTest#theForegroundPromiseUsesARealChannel` — `OK (1 test)`；`Stage2DownloadControlTest#slowChapterCanPauseResumeAndRemove` — `OK (1 test)`。
- AOSP ARM64 API 26 模拟器：`Stage2FixtureNavigationTest#exploreSearchAndShelfReturnToTheirOwnOrigins` 与 `Stage2DownloadControlTest#slowChapterCanPauseResumeAndRemove` — 各 `OK (1 test)`；另通过 DocumentsUI 实际导入合成 SAF 目录并在 Reader 打开第一页。
- Xiaomi API 36：`DownloadWorkerTest#theForegroundPromiseUsesARealChannel` 与 `Stage2DownloadControlTest#slowChapterCanPauseResumeAndRemove` — 各 `OK (1 test)`。隔离 API 30 ARM64 的来源导航与书架入口测试亦各 `OK (1 test)`。
- 最终 Stage 2 门禁（JDK 17）：`sh gradlew --offline --no-daemon --max-workers=2 testDebugUnitTest :app:assembleDebug :app:assembleRelease` — `BUILD SUCCESSFUL`；62 份 JVM 报告合计 442 项，0 失败、0 错误、0 跳过；Debug/Release 构建及 Release Lint Vital 通过。`git diff --check` 通过。
- API 26 fixture 三页请求全部 HTTP 200；测试后移除 `tcp:8765` reverse 并停止服务。API 26 和 Xiaomi 的 App/AndroidTest APK 均保留安装；没有卸载或清除用户数据。
- 本次复核未发现新的产品代码缺陷。通知在下载期间由 Android `NotificationManager.activeNotifications` 实测可见，结束后系统通知抽屉无 Venera 卡片；另有 `dumpsys notification` 保留状态记录但 WorkManager 已记录 `Removing Notification` 的观察，未发现用户可见的完成后残留。
- 复核涉及测试设备与文档，没有产品代码变更。

## 剩余阻断和风险

1. D05 真实设备格式覆盖 ZIP；7z/CB7 由仓库归档 fixture 覆盖，实际设备导入未重复每种压缩格式。该范围不阻断 S2-05/S2-06 已有的导入和解析验收。
2. SAF picker 的用户取消与持久权限丢失没有单独在设备 UI 演练；底层导入、归档刷新/取消和权限持久化路径有对应模块回归。后续如调整 SAF 生命周期，再补设备场景。
3. API 26 验证使用 AOSP 模拟器，不代表各 OEM 的文件选择器和通知呈现完全一致；Xiaomi API 36 另有实机闭环证据。下载期间通知经 NotificationManager 验证可见，系统抽屉与 dumpsys 完成后状态的差异已如上记录，未发现用户可见残留。

上述为覆盖范围与平台差异风险，不属于 Stage 2 必需闭环的未完成验收项；没有将未测项目写成已通过。Stage 2 退出门禁满足，下一任务为 S3-00。
