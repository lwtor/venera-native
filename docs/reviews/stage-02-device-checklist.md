# Stage 2 真机验证清单（执行中）

状态：**执行中**。用户已于 2026-09-25 确认。设备 Xiaomi 25128PNA1C，Android 16 / API 36；每次执行以当时的 Git HEAD 为基线。执行时记录构建 commit、测试数据、时间、结果和日志/截图路径；失败项建立独立修复任务。

| ID | 步骤 | 预期 |
| --- | --- | --- |
| D01 | 使用仓库内 fixture 来源打开探索/搜索/书架中的漫画详情，再打开阅读器并逐级返回 | 阅读器返回详情；详情返回原入口列表；系统返回键与页面返回按钮行为一致，页面状态不出现空白或崩溃 |
| D02 | 在详情排入至少三页下载，观察通知和书架 Downloads；执行暂停、继续、失败重试、移除 | 页数与章节状态一致，继续/重试会真正启动 Worker；移除后数据库、通知和文件状态一致 |
| D03 | 下载中结束进程并重新启动；运行下载 Worker/Room instrumentation，包括意外异常、相同/不同 Worker ID 恢复、无文件路径成功页、并发暂停/取消 | 遗留 Running 页最终重新入队或明确失败，用户暂停/取消不会被旧 Worker 覆盖；所有新增 instrumentation 通过 |
| D04 | 下载完成后开启飞行模式并重新打开章节，前后翻页，退出并重开 | 图片不依赖网络；进度恢复到上次页面；缺失或截断的文件会被识别而非显示假成功 |
| D05 | 经 SAF 导入含根图片、封面及多个章节子目录的测试漫画，再导入 ZIP/7z 测试归档；刷新归档并退出重进 | 目录全部有效章节可见且自然排序；归档刷新后索引仍为 Archive；权限丢失/取消有可理解的失败状态，不误删既有记录 |
| D06 | 打开总页数据量超过 256 MiB 的本地长章节，前进多页后返回第一页，切换阅读方向并操作长图手势 | 章节进入速度不随总页数线性解档；回访被缓存淘汰的页能重新显示，内存不持续增长、无明显卡顿或崩溃 |
| D07 | 在 API 26 设备/模拟器重复应用启动、来源脚本调用、目录导入、阅读与下载通知；API 34+ 验证前台服务类型 | 低版本兼容，前台服务声明和通知正常，无崩溃 |

使用最小化、自建且有授权的 fixture；不使用真实账号、商业站点或受版权保护内容。

2026-09-25 已完成模块测试：`core:database` 25/25、`data:download` 6/6、`data:local` 2/2。首次离线连接测试缺少 UTP 工件，在线解析并关闭 configuration cache 后通过。`data:local` 首次运行发现 AndroidJUnit4 测试方法返回非 `Unit`，修复并复跑 2/2 通过。以上只覆盖模块 instrumentation，D01–D07 页面闭环尚未记为通过。

2026-09-26：WorkManager 已配对升级至 2.12.0，相关 JVM 回归、测试源码编译及 Lint 通过。`adb devices -l` 再查为空；此前 6 项 Worker 设备测试运行于 2.11.2，升级后须复验，D01–D07 仍未通过。

2026-09-26 API 34 模拟器 `DeviceTest_1`：下载 Worker instrumentation 在 WorkManager 2.12.0 上 6/6 通过。书架导航测试首次因漏点 Downloads 标签失败，补齐测试操作后 `:app:connectedDebugAndroidTest` 1/1 通过，覆盖首页→书架→Downloads/Local 空态及导入入口；尚不代表 D01–D07 完成。

2026-09-26 API 34 fixture 首次页面试跑：系统文件选择器成功安装 `tools/test-sources/demo_comic_source.js`；Explore 显示 c1/c2，c1 详情列出 Chapter 1 的 Read/Download，进入 Reader 显示 1/3，但图片因 API 28+ 默认 HTTP 限制重试。S2-07D1 仅在 Debug 对 `127.0.0.1` 开放 HTTP 后，重装复测 Page 1/2 已显示，服务收到 JPEG/PNG GET 200；Release manifest 未包含例外。此证据仅覆盖 D01 前半段，不把逐级返回或其余项记为通过。

2026-09-26 API 34 返回键复验：从 Reader 按系统返回曾直接退出应用；新增书架返回设备回归，修复前 Activity 退出且无 Compose 树。统一根导航返回目标后，书架手动系统键返回首页、`:app:connectedDebugAndroidTest` 2/2、导航 JVM 测试通过。Reader→详情→Explore 的完整手动路径仍待复测，D01 保持未通过。

2026-09-26–30 API 34 离线续验：fixture 三页下载在模拟器网络满足 `VALIDATED` 后从 0/3 Queued 推进到 3/3 Completed；最初模拟器外网探测失败使 JobScheduler CONNECTIVITY 未满足，临时将其 captive portal 探测关闭并刷新 Wi‑Fi，仅改变模拟器测试环境。随后开启飞行模式并关闭本地图片服务，Reader 显示第 1、2、3 页，LTR 翻页正常。发现退出 3/3 后立即重开显示 2/3；SQLite 记录显示退出时索引 2、重开后被写成 1。S2-07D3 修复后，历史 JVM 与 Reader 设备回归通过；手动复测重开仍为 3/3、数据库仍为索引 2，反向滚动更新为 2/3。D02 的暂停/继续/失败重试/移除、D04 的损坏文件和进程死亡、D05–D07 仍未完整通过。

2026-09-30 API 34 模拟器后续 D02 试跑：受控失败来源的三页任务首次在第 3 页返回 HTTP 503 后显示 `2/3 Partial`，点击 Retry 后实际完成 `3/3 Completed`。点击 Remove 后，Room 的该章节任务行消失，下载目录仅余另一来源已完成章节的三页及清单。暂停/继续尚未通过，故 D02 仍未完成。

2026-09-30 小米真机在基线 `87a314c` 重新枚举为 `6857c5aa`。WorkManager 2.12.0 上执行 `ANDROID_SERIAL=6857c5aa sh gradlew --no-configuration-cache :data:download:connectedDebugAndroidTest :app:connectedDebugAndroidTest`；下载 Worker 6/6、0 失败（`data/download/build/outputs/androidTest-results/connected/debug/TEST-25128PNA1C - 16-_data_download-.xml`）。App 导航测试启动后手机前台切换到其他应用，长时间未完成，主动中断本轮 Gradle（退出 130）；这两项不能记为通过，需在设备可保持测试界面前台时重跑。D01–D07 页面闭环仍未全部通过。

2026-09-30 API 34 模拟器 D01 逐项试跑，基线 `a30d806`：使用已安装的仓库自建来源及三页图片，探索 `Demo Comic c1` → 详情 → 阅读器，经页面 Back 返回详情、系统 Back 返回探索，再返回首页；搜索关键词 `Demo` 得到 `Demo Comic search-1`，进入详情和阅读器后经系统 Back 返回详情、页面 Back 返回原搜索结果，关键词和结果保留；将 `Demo Comic c1` 加入书架，从 Favorites 进入详情和阅读器后，经页面 Back 返回详情、系统 Back 返回书架，收藏条目仍在。阅读器显示 `1 / 3` 且无 Retry；本地 fixture 服务记录图片 GET 200。测试通过 `adb shell input` 和 `uiautomator dump` 逐步核对页面文字；只覆盖模拟器。随后查询真机 `adb -s 6857c5aa` 返回 `device not found`，D01 真机复验未执行，D01 暂不标记完整通过；按用户要求，完成 D01 前不开始 D02。

2026-09-30 S2-07D4：新增 `Stage2FixtureNavigationTest`，AndroidTest 打包仓库 `tools/test-sources/demo_comic_source.js`，每次为其生成独立测试来源 ID，按探索、搜索、书架顺序验证详情与阅读器页面（含 `Page 1` 图片语义）及页面/系统返回，并清理新建的来源、收藏与阅读历史。首次运行在阅读器页码等待超时，来源选择不明确；测试改为只点击可点击的测试来源 chip，且在详情页断言来源名称，API 34 模拟器 1/1 通过。命令：`ANDROID_SERIAL=emulator-5554 sh gradlew --no-configuration-cache :app:connectedDebugAndroidTest :app:assembleDebug -Pandroid.testInstrumentationRunnerArguments.class=dev.veneranative.app.Stage2FixtureNavigationTest`（JDK 17，fixture HTTP 服务与 `adb reverse tcp:8765 tcp:8765` 已启动）。小米真机当时仍不在 `adb devices -l` 中，D01 真机复验继续待执行。
