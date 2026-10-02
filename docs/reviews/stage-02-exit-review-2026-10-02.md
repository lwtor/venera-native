# Stage 2 退出门禁复核（2026-10-02）

结论：**未通过；Stage 2 保持 `IN_PROGRESS`。** 本次复核确认 D01–D06 已有真机闭环证据，新增复验了 Xiaomi API 36 的前台下载通知运行流程；但计划要求的 API 26 闭环仍无可用运行环境，且 SAF 目录导入未在低版本实际完成。不得以 API 30/36 证据替代 API 26，也不得推进 Stage 3。

## 基线与复核范围

- 代码基线：`49569a2`（D06 Reader 手势回归）；本次 D27、D28 后续提交只更新测试状态文档。
- 复核依据：`docs/reviews/stage-02-review.md` 中 C1–C25 的代码质量审查与整改记录、`docs/reviews/stage-02-device-checklist.md` 的设备证据，以及当前 Android Manifest、Worker 前台通知测试和 D01/D02 App instrumentation。
- 本次未更改产品代码。D28 在 Xiaomi 25128PNA1C / API 36 上运行下载通知与暂停/继续/移除闭环；测试服务和 reverse 已清理，App 与测试 APK 保留。

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
| D07 API 26 最低版本闭环 | **未完成/阻断** | API 36 `DownloadWorkerTest#theForegroundPromiseUsesARealChannel` 验证通知 channel 与 `dataSync` 类型；D28 真机验证活动通知及下载控制；API 30 的两项导航/书架入口测试通过。但 API 26 镜像安装停在 `Preparing`，没有镜像文件；低版本实际 SAF 目录导入和通知尚无证据。 |

## 本次验证与发现

- Xiaomi API 36：`DownloadWorkerTest#theForegroundPromiseUsesARealChannel` — `OK (1 test)`；`Stage2DownloadControlTest#slowChapterCanPauseResumeAndRemove` — `OK (1 test)`。
- 隔离 API 30 ARM64 模拟器：`Stage2FixtureNavigationTest#exploreSearchAndShelfReturnToTheirOwnOrigins` 与 `Stage2LibraryNavigationTest#homeOpensDownloadsAndLocalLibraryTabs` — 各 `OK (1 test)`。
- 下载 fixture 的三页响应均为 HTTP 200；测试结束已移除 `tcp:8765` reverse 并停止服务。Xiaomi `dev.veneranative` 与 App AndroidTest 包仍安装；未卸载 App 或清除数据。
- 本次复核未发现新的产品代码缺陷。当前阻断是 API 26 测试镜像无法取得，不能推断为应用低版本兼容性故障或通过。
- 本次只涉及验证记录，无产品代码变更；执行 `git diff --check`。未重复 Stage 2 已通过的全仓构建/测试门禁。

## 剩余阻断和风险

1. 准备可启动的 API 26 设备/模拟器；当前 SDK 元数据能列出 Google APIs ARM64 包，但安装无下载产物，重试仍停在 `Preparing`。
2. 在 API 26 完成冷启动、仓库 fixture 来源调用、真实 SAF 目录选择并核对导入章节/页面、进入 Reader、下载并验证通知的完整闭环。
3. D07 通过后重跑计划要求的 Stage 2 退出门禁并复核本报告；只有门禁满足后才能将 Stage 2 标记 `DONE`。

API 26 和低版本 SAF/通知风险尚未被用户接受为延期项，故不视为完成。后续任务从 S2-07D 的 API 26 环境与闭环继续。
