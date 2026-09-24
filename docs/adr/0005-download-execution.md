# ADR-0005：跨 Android 版本的下载执行策略

- 状态：Accepted（2026-09-23）
- 依据：设计 `docs/design/S2-incremental-design.md` §1.4（关键决策 2）、§2 的 S2-03 验收点；实现见 `:data:download` 的 `worker/` 包与 `:app` 的 `VeneraApplication`
- 日期：2026-09-23
- 相关：ADR-0001（模块边界）、ADR-0004（图片管线，下载复用同一条取字节链路）、`docs/STATUS.md` S2-03

## 1. 上下文

S2-02 交付的下载队列（`DownloadRepository` + Room 的 `download_task` / `download_page`）已经完整可测，
但**没有任何东西调用它**：队列里堆着 `Queued` 页，没有执行者，恢复扫描也就没有意义。要让它在 Android 上
真正跑起来，必须回答一个跨版本问题：**谁在后台拉这些字节？**

约束条件：

- 一次下载可能持续几分钟到几十分钟，远超任何 `CoroutineScope` 或 `ExecutorService` 的寿命。
- 用户会按下 Home、会切到别的应用、系统会杀进程回收内存。队列的真相在 Room 里，所以执行者死了队列不能丢；
  但执行者自己必须能被系统重新拉起。
- 下载应当尊重约束：**默认只在非计量网络下跑**（漫画一章几十 MiB，用户不会想用流量），且**存储不足时不跑**。
- 用户点了「下载」就期待它开始，而不是等到晚上充电时——所以需要一个「立刻跑」的入口。
- Android 8.0（API 26，本项目 `minSdk`）之后，后台服务被严格限制；Android 12（API 31）之后后台启动
  前台服务被禁止；Android 14（API 34）之后前台服务必须声明**类型**，且类型要与权限对应。

候选方案有三个：

| 方案 | 形态 | 说明 |
| --- | --- | --- |
| A. 裸 `ExecutorService` / 自建协程 | 进程内线程池 | 最简单，但进程被杀后队列无人认领，也不感知网络与存储约束 |
| B. 用户发起的数据传输作业（UIDT） | `JobScheduler` + `JobService` + `RUN_USER_INITIATED_JOBS` | Android 14 引入，专门为「用户发起、需要立刻开始、长时间运行」的数据传输设计 |
| C. WorkManager | `CoroutineWorker` + 约束 + 持久化工作 | 官方推荐的「可延迟、需约束、需跨进程重启存活」的后台任务方案 |

## 2. 决策

**选择 C：WorkManager 2.11.2 的 `CoroutineWorker` + `setForeground()` + 条件性 `setExpedited()`。**

### 2.1 官方依据与为什么不是 UIDT

Android 官方当前把 WorkManager 用于通常少于 10 分钟、可延后或可中断的工作；用户发起、需要展示进度且被
系统中断会明显损害体验的数据传输则应考虑 UIDT。我们的下载以每页为单位写入 Room、可从中断页恢复，
用户不会丢失已完成页，因此选 WorkManager 的持久队列与网络/存储约束；手动触发只通过 expedited 请求表达
尽快开始，不能向用户承诺立即启动。参见 [后台数据传输选型](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options)
和 [WorkManager 长时任务说明](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)。

更实际的理由：

- UIDT 需要额外声明 `RUN_USER_INITIATED_JOBS` 权限、实现 `JobService`、自己处理 `onStartJob` /
  `onStopJob` 的持久化与重启语义。**这些 WorkManager 已经提供了**：工作请求持久化在自己的 Room 里，
  进程被杀后 `WorkManager` 会重新调度未完成的工作，约束不满足时它自己等待。
- UIDT 只覆盖 API 34+，而 `minSdk = 26`。用 UIDT 就要为 26—33 再写一套，两套执行路径的队列语义很难
  保证一致——而队列语义不一致正是下载功能最容易出错的地方（重复下载、漏页、僵尸页）。
- 我们本来就需要 WorkManager 的约束与重试，UIDT 不能替代它们，只能叠加。

因此当前版本选择 WorkManager，但这不是“下载一定能连续跑完”的承诺。UIDT 只在用户发起并且中断对体验有
实质损害的场景更合适；未来若需要保证一次用户发起的长下载连续执行，应重新评估 API 34+ UIDT 与旧版本
WorkManager 回退的双路径实现。

### 2.2 前台服务与类型声明

`CoroutineWorker` 默认跑在后台，随时可能被系统停止并触发重试。下载不能接受这种中断，所以：

- Worker 实现 `getForegroundInfo()` 并在 `doWork()` 循环里 `setForeground(...)`，把它提升为前台服务；
  通知是前台服务的强制伴生物，也正好是进度展示的位置。
- 清单声明三项权限：`FOREGROUND_SERVICE`（API 28+ 必需）、`POST_NOTIFICATIONS`（API 33+ 通知需要
  运行时授权，但前台服务本身仍可运行）、**`FOREGROUND_SERVICE_DATA_SYNC`（API 34+ 必需）**。
- API 34+ 启动前台服务必须给出类型，且类型必须有对应的权限声明，否则抛
  `MissingForegroundServiceTypeException`。下载属于「数据同步」类，因此 `ForegroundInfo` 携带
  `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`（低版本传 0，因为该类型在 API 34 才存在）。

### 2.3 expedited 只是「尽快」，不是「立刻」

用户手动按下下载时用 `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`。必须清楚它的
退化行为：

- 系统负载高、应用超出 expedited 配额、或应用处于受限待机桶时，**expedited 工作会退化成普通工作**，
  只是按约束排队执行，不会失败。配额由系统决定，应用无法查询或申请更多。
- 省电模式下 expedited 工作同样可能不立即执行。
- 因此 **UI 不得假设「点了就开始」**：入队后状态就是 `Queued`，进度由 Room 驱动，用户看到的是队列状态而
  不是「正在跑」。这正是 §2.1 里「可中断」语义的一部分。

### 2.4 约束与唯一工作串

- 默认约束：`NetworkType.UNMETERED` + `setRequiresStorageNotLow(true)`。约束不满足时 WorkManager 自己
  等待，**Worker 不轮询约束**。
- 唯一工作串名为 `download`，策略 `ExistingWorkPolicy.KEEP`：队列在 Room 里，同时跑两个 worker 只会让
  并发上限和心跳归属变乱，`KEEP` 保证已经在跑的就继续跑。
- **暂停必须返回 `Result.success()`**。返回 `Result.failure()` 会让 WorkManager 按退避策略重试，等于把
  用户的「暂停」翻译成「稍后再试」；队列里的页保持 `Paused`，继续时由用户或后续入队重新调度工作。
- 不使用 `work-multiprocess`（单进程足够），**不使用 `PeriodicWorkRequest`**：追更检查是用户在书架上
  手动触发的（设计 §10.10），不是后台定时轮询，引入周期任务属于范围增加且会给来源带来无意义的请求。

### 2.5 Worker 不依赖 Activity

进程可能被 WorkManager 直接拉起（没有 Activity），所以 Worker 只能通过 `DownloadEnvironment.get(context)`
取依赖。`VeneraApplication.onCreate()` 负责装配并安装它：下载根目录、时钟、IO dispatcher、并发上限、
取字节的 `PageByteSource`、以及 Room。Application 的 `onCreate` 在进程里一定先于任何 Worker 执行，
这是「冷启动也能跑」的依据。

## 3. 理由

1. **队列的持久化和工作的持久化是两套东西，但只需要一套执行者。** Room 保存「哪些页还没下」，
   WorkManager 保存「有没有一个执行者该在跑」。二者职责不重叠，且 WorkManager 重启后第一件事就是
   `recover()`（认领任务、回收僵尸页、校验文件），所以「执行者死了」退化成「下一页晚一点开始」，而不是
   「队列永久卡住」。
2. **取字节复用阅读器的图片管线，而不是另开 HTTP 客户端。** 很多来源的图片主机要求 Cookie/Referer，
   一个自建客户端会静默 403 每一页。这不是下载模块的品味问题，是能不能下到的问题。
3. **并发由 `DownloadQueue`（全局 4 / 单源 2）决定，Worker 不自己开并发。** Worker 只是「把队列跑空」
   的循环，换一个执行者（测试、前台、另一个平台形态）不会改变限流语义。
4. **前台服务的代价（通知）是必要的**：没有通知的长时间后台下载在系统上就是不合法的，与其被系统杀掉
   后靠重试兜底，不如一开始就按规则声明。

## 4. 后果

正面：

- 队列终于有执行者；进程被杀、重启、约束不满足三种情况都由既有机制覆盖。
- 通知是唯一的用户可见出口，文案集中在无 `Context` 的纯函数里，可在 JVM 上测。

代价与约束：

- 引入 WorkManager 2.11.2 与 `androidx.core`（通知）两个依赖，`:data:download` 从此有一个依赖 Android
  框架的子包（`worker/`）；其余部分仍是纯 Kotlin 且 JVM 可测。
- `POST_NOTIFICATIONS` 在 API 33+ 需要**运行时**授权，本轮只做清单声明。未授权时通知不显示，但前台服务
  与下载继续运行（系统行为），这一点必须在 UI 阶段补上申请入口，不能当作「已处理」。
- 前台服务期间应用无法被系统随意回收，因此 Worker 必须尽快结束：队列空即返回，不做空转。
- Android 16 起，长时间前台 Worker 仍受 JobScheduler 配额影响，系统可能中断并重新调度；每页原子落盘和
  Room 队列使其可以恢复，但实际延迟与长章体验需在 S2-07 设备验收验证。WorkManager 的长时任务并非无限期
  运行许可，若实机表现不满足体验要求，按上文重新评估 UIDT。
- `HEARTBEAT_STALE_AFTER_MILLIS` 是 5 分钟，Worker 每轮循环打一次心跳；单页若超过 5 分钟没有进展，
  下一次 `recover()` 会把它判为僵尸页重新排队——这个阈值仍未经真机验证（见 `docs/STATUS.md`）。

## 5. 替代方案

| 方案 | 否决理由 |
| --- | --- |
| 裸 `ExecutorService` / 自建 `CoroutineScope` | 进程被杀即无人认领队列；不感知网络与存储约束；无法跨重启恢复 |
| 用户发起的数据传输作业（UIDT） | 官方建议此类场景继续用 WorkManager；需要 `RUN_USER_INITIATED_JOBS` 与 `JobService`；仅 API 34+，与 `minSdk 26` 冲突会形成两套执行路径 |
| `PeriodicWorkRequest` 追更 | 追更是用户手动触发（设计 §10.10）；周期任务会给来源带来无意义请求，属于范围增加 |
| `work-multiprocess` | 单进程足够；多进程会让 Room 与 WorkManager 的归属关系复杂化 |
| `ForegroundService`（不用 WorkManager） | 需要自己实现约束等待、重启调度、退避重试，这些都是 WorkManager 已提供且经过验证的部分 |
