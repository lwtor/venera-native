# Venera Native Stage 0 / Stage 1 审查

基线：`cbfb8ea`，2026-09-21。范围：AGENTS.md、STATUS、IMPLEMENTATION_PLAN 中两个阶段全部任务、相关产品规划和 ADR、主要实现与测试、近期提交树。采用当前明确范围及已接受裁剪，不把 Stage 2–4 功能提前算作缺陷。

结论：Stage 0 的技术选型与原型成果存在，但设备侧验收是延期而非通过；Stage 1 尚不满足搜索→详情→选章→网络阅读→恢复进度的退出标准。下面的实现缺陷不能用“未实机验证”解释或豁免。

## 1. 优先修复的确定问题

### R01 / P1：普通 HTTP 响应读取会抛 EOF

位置：[SourceNetworkExecutor.kt](../../source/network/src/main/kotlin/dev/veneranative/source/network/SourceNetworkExecutor.kt)。

`readByteArray(maxResponseBytes + 1)` 是精确长度读取，不是上限读取。默认要求 1,048,577 字节：正常较短响应会抛 EOFException；onResponse 内又没有异常映射和 continuation 收尾。结果是请求无法正常返回，存在回调线程未捕获异常风险。现有 GET/POST MockWebServer 测试在 androidTest 下，历史 JVM 测试数量不能证明该路径可用。

修复：分段读取并累计到上限，正常 EOF 完成；读取异常映射领域错误，确保所有路径完成 continuation。最小验证：短正文、空正文、恰好上限、超限、chunked、读取中断与取消。

### R02 / P1：图片缓存首次下载完成后访问已关闭 editor

位置：[CoilComicImagePipeline.kt](../../core/image/CoilComicImagePipeline.kt)。

`editor.commit()` 后再读 `editor.data`，Coil 3.4.0 抛 `IllegalStateException: editor is closed`。下载文件可能已提交成功，但当前尺寸请求/封面加载失败；重试命中缓存可能掩盖首次失败。

修复：使用 commitAndOpenSnapshot 等正式快照交接；不要提前保存临时路径冒充最终文件。readSnapshot 当前又在返回 File 前关闭 snapshot，需一并明确文件租约，避免解码期间被缓存驱逐。

### R03 / P1：网络图片未交接给正文文件解码器

位置：[SourcePageProvider.kt](../../data/comic/src/main/kotlin/dev/veneranative/data/comic/SourcePageProvider.kt)、[ReaderScreen.kt](../../feature/reader/ReaderScreen.kt)。

Provider 通过网络管线取得尺寸，却把原始 HTTP URL 保留在 imageRef；Reader 直接将它作为 PageDecodeRequest.path。两个解码器调用 BitmapFactory.decodeFile / BitmapRegionDecoder.newInstance(path)，都没有网络获取适配。因此即使 R02 修好，真实网络正文仍不能解码。

修复：在图片层把带 sourceId/鉴权身份的引用解析成受生命周期管理的本地文件/描述符，再交给区域解码；UI 不负责网络。补真实网络下载→尺寸→文件→解码的跨层用例。

### R04 / P1：冷启动/Activity 重建后，已启用来源没有恢复到 Runtime

位置：[SourceRepository.kt](../../data/source/SourceRepository.kt)、[MainActivity.kt](../../app/MainActivity.kt)。

App 每次重新建立新的 QuickJsRuntime；installed() 只读磁盘索引，安装仅发生在新安装、启用切换和回滚路径。没有启动时加载已启用脚本的流程。磁盘显示 enabled=true，Runtime 却是空的，目录探测失败后会把这些源过滤掉。

修复：增加可等待、幂等的来源恢复步骤，或在调用前按需加载；隔离单源失败，并覆盖“安装→销毁应用依赖图→重建→搜索”。同时避免配置变更后 ViewModel 仍持有旧依赖图，而新界面创建新图；Runtime 当前没有生产 close 调用点。

### R05 / P1：进度恢复只传入构造参数，异步结果不会应用

位置：[MainActivity.kt](../../app/MainActivity.kt)、[ReaderRoute.kt](../../feature/reader/ReaderRoute.kt)。

resumePageOf 初值为 0，数据库读取随后完成；ViewModel 在初次组合就创建并保存 startPageIndex，之后重组不会重新调用 factory。因此冷打开章节通常按 0 初始化。直接在 Reader 恢复路由时 historyRepository 也可能尚未就绪，recorder=null 同样被构造函数保留。

另外读取进度只用 ComicKey，未核对 chapterId：显式打开另一个章节时会套用上一章页码。当前也没有“恢复保存章节”的入口编排。

修复：把恢复作为明确加载阶段，或让 ViewModel 通过 action 接收恢复结果；区分继续阅读与用户显式选章，避免迟到结果覆盖用户已开始的阅读。

### R06 / P1：离开阅读器不 flush，持久化并发及原子性不足

位置：[MainActivity.kt](../../app/MainActivity.kt)、[ReadingProgressTracker.kt](../../data/history/ReadingProgressTracker.kt)、[DefaultHistoryRepository.kt](../../data/history/DefaultHistoryRepository.kt)。

Reader 返回只切换 Home；flush 唯一生产调用位于 Activity.onStop，应用内离开阅读器不会触发它。Tracker 只有一个全局 pending，短时间进入另一漫画会覆盖前一漫画尚未落盘的位置。

scheduleWrite 与 flush 在锁外并发 record，慢的旧写入可覆盖新的；pending 在写成功前清空，失败无重试保留。history/progress 的两个 DAO 写入与删除也没有共同事务，无法保证注释承诺的一致性。现有 fake 测试没有挂起写入顺序，不能覆盖这些竞争。

修复：会话退出可等待地 flush；串行化持久化写入并保留失败待写值；Room 事务覆盖两张表。补慢旧写/快新写、切漫画、写入失败及只看首屏退出的测试。初始页与恢复页 当前 不触发 recorder，也需处理；漫画标题当前被写成 chapterId。

### R07 / P1：验收 demo 源不符合当前 Source Core 协议

位置：[demo_comic_source.js](../../tools/test-sources/demo_comic_source.js)。

当前引擎要求 explore 数组、search.load、comic.loadInfo、comic.loadEp；demo 声明的是顶层 explore/search/loadInfo/loadPages 函数。能力探测不会得到所需能力，验收从探索/搜索就无法继续。

即使结构修好，demo 的 file:///android_asset/test-images/page-N.jpg 不被当前仅 HTTP(S) 的图片管线接受，而且生成器产物为 fixtures/page_normal_* 等文件名，并不存在该套 page-N/cover-N 生成路径。人工脚本的“断网详情失败”也不适用于完全本地返回数据的 demo。

修复：使用同一份协议正确的 fixture 驱动自动化与人工验收；配套生成图片和受控测试 HTTP 服务，明确 debug 网络配置；来源安装目前只有路径输入，没有文档所述文件选择器，脚本需写清 app 可访问文件如何放入，或实现 SAF 安装入口。

### R08 / P1：来源更新失败可能破坏旧版本磁盘数据

位置：[SourcePackageStore.kt](../../data/source/SourcePackageStore.kt)、[SourceRepository.kt](../../data/source/SourceRepository.kt)。

store.write 先原地覆盖 source.js，再原地写 index.json。若第二步失败，Repository 只回滚 Runtime，不恢复磁盘旧脚本/索引；重启后旧元数据/哈希与新脚本不匹配，或索引损坏导致列表变空。不满足 S1-02“安装失败不污染旧版”。

修复：临时版本目录、原子提交点和故障清理；在脚本已写、索引未完成处注入失败。停用源更新失败时 restore 还会直接加载旧版，需恢复原 enabled 状态。

## 2. 其他应安排的修正

- **R09 / P2，mixed 探索分页跳页**：[EngineSourceCore.kt](../../source/core/src/main/kotlin/dev/veneranative/source/core/EngineSourceCore.kt) 首次调用参数按协议为 0，但解析传 pageNumber=1，next 变 2，跳过索引 1；现有测试只断言首屏内容。应覆盖完整 0→1→2 及结束条件。
- **R10 / P2，连续模式缩放后不能正确平移**：[ReaderScreen.kt](../../feature/reader/ReaderScreen.kt) 更新 zoomState.offsetX/Y 后未在 ContinuousPages 渲染使用，却关闭列表滚动。需接入平移坐标与正确的手势消费，并验证单指滚动不被吞；横向模式也需明确缩放与翻页互斥。
- **R11 / P2，跳过坏图破坏页号语义**：SourcePageProvider mapNotNull 后保留原 index；连续 UI 发 page.index，VM 却按列表位置 clamp。原页 [0,1,2] 跳过 1 后变 [0,2]，看到 2 时被记作位置 1，恢复/模式切换不一致。区分稳定源页号与显示位置，或保留可重试坏页占位。全部图片失败现在表现成“本章无页面”，缺少可恢复错误。
- **R12 / P2，章节必须全量下载才能显示第一页**：SourcePageProvider 串行 sizeOf 每一页；sizeOf 实际先下载完整图片；source-backed prefetch 仍是接口默认 no-op。长章节首屏成本是所有图片下载总耗时，违背邻近预取目标。应分离章节描述与可见页按需解析，限定并发/预取范围。
- **R13 / P2，图片网络执行与缓存边界**：同步 execute 没有协程取消到 Call.cancel 的桥；64 MiB 只检查 Content-Length，未知长度正文通过 writeAll 无界写入；Form 未 URL 编码（`&`、`=`、`+` 会改变语义）；App 为 pipeline 和 ImageLoader 各创建一份指向同目录的 DiskCache，应共享单实例。鉴权 key 与下载头在两个时刻重新读取，也需统一请求快照。
- **R14 / P2，源能力缓存不会随更新失效**：EngineSourceCore descriptions 只按 SourceId 缓存，成功替换脚本后没有版本/会话失效机制。新增/删除能力及变更分页形式后继续用旧描述。source/network clearSource 也没有生产调用，卸载后的 Cookie 生命周期需接好。
- **R15 / P1，脚本日志未脱敏**：[QuickJsHostBridge.kt](../../source/engine/QuickJsHostBridge.kt) 把 console 文本直接交给默认 println。已有真实生产日志入口，STATUS 中“脱敏工具尚无生产调用点，等日志功能落地”不成立。应默认禁用原文脚本日志，或在宿主输出边界执行可靠的敏感值处理，补含测试 Token 的断言。
- **R16 / P2，init 内无法调用 Host 网络**：QuickJsHostScript.callHost 要求 __veneraInvocationId，SourceClassConvention.INIT_SCRIPT 直接 await init，没有建立 invocation。声明 init 时需要请求配置/会话的源安装失败。初始化也需要受控调用身份、超时与取消。
- **R17 / 待专项验证，QuickJS 限制未收口**：计算型脚本不可强制中断是已记录限制；但 metadataReader 的直接 evaluate + engine.use.close 及 install/reload 的求值路径没有与 invoke 相同的隔离处理，应单独验证这些路径的超时是否真能返回。invoke 的 timeout 也不包含 invocationMutex 等待和 engine 重建，排队 cancel(callId) 无效；缺少队列上限。不得把这些全部写成“契约已满足”。

## 3. 两个阶段的逐任务对照

“基本满足”表示静态实现/已有记录符合主要范围，不代表本轮重跑全部测试。

| 任务 | 审查判断 |
| --- | --- |
| S0-00 产品规划 | 文档存在；当前状态字段漂移，应清理。 |
| S0-01 工程基础 | 多模块、约定插件、版本目录、wrapper 已有；本轮未重跑构建。 |
| S0-02 Runtime 最小闭环 | 历史 AndroidX PoC 与测试记录存在；现在主引擎已变，不能把旧引擎结论直接算给 QuickJS。 |
| S0-03 Host/网络桥 | 契约、隔离、限并发与取消骨架存在；R01 破坏实际 HTTP 正常往返，需重开修复。 |
| S0-04 限制与压力 | 裁剪后的历史数据完整记录；设备前后台/API26 与新引擎二进制不是已验证能力。 |
| S0-05 阅读器原型 | 三种方向、状态与预取调度原型存在；source-backed 实际预取未闭合。 |
| S0-06 大图/缩放 | 纯函数几何和预算测试是有效成果；R10 及设备 PSS/耗时/手势仍缺，不能等同设备验收。 |
| S0-07 决策收敛 | 历史阶段退出有记录；ADR-0004仍说设备验证是退出前必做，与退出裁剪冲突，需正式改为延期门禁并指定归属。 |
| S1-01 模型/协议 | 类型与协议测试较完整；R09、R14 需修，协议文档里的当前/历史范围需统一。 |
| S1-02 安装管理 | 基本操作有实现；R04/R08 阻断持久可用及升级回滚，普通用户文件安装路径也需落实。远端 URL 已明确延期，不作为本轮额外强制功能。 |
| S1-08 QuickJS | 实现存在但计划仍 IN_PROGRESS；二进制、ABI/体积/Release、许可登记等未完成或未验证，ADR“全部判据通过才切默认”与实际已切换矛盾。 |
| S1-03 探索/搜索 | UI/Paging/能力驱动实现存在；受 R01/R04/R07/R09 影响，尚不能证明真实闭环。筛选 UI 已明确延期。 |
| S1-04 详情章节 | 模型、分组、显示排序和错误 UI 基本具备；封面被 R02 阻断，Provider 问题属于 S1-05/07。 |
| S1-05 图片管线 | 请求/键/头部解析/解码迁移有成果；R02/R03/R11/R12/R13 使网络正文链路不满足验收。Header/Referer/Cookie/POST 的端到端测试缺失。 |
| S1-06 历史与进度 | Room/schema/仓库/节流代码存在；R05/R06 使恢复与强刷要求未满足。 |
| S1-07 核心集成 | 不满足退出标准；demo 与人工脚本本身不可执行到预期结论，不能仅凭存在脚本标 DONE。 |

当前 STATUS“当前代码事实”还写未引入 Room/Coil、无 source-backed Provider、208 项测试等旧状态；架构表仍把 engine 写成 AndroidX。应将当前事实和历史完成记录分开，避免以后接管继续误判。

## 4. 每个小任务完成即 commit：规则审查

方向正确。新增于 `cbfb8ea`；下面的旧提交发生在规则引入之前，不能追溯说它们违反后来规则，但恰好说明新规则要防止什么。

实际提交树证据：

- `2a4623d` 已在 settings 和 app 依赖中引用 core:image/core:database/data:history，然而当时这些模块的 build.gradle.kts 尚不完整；它还先移动解码文件并删除 PageProvider 定义，后续提交才补齐新结构。
- `85f62c0`、`d166996` 时 core:database/data:history 仍未交付，直至 `e832420` 才补齐。因此按文件/时间拆出的这些 commit 不能视为独立可编译小任务。
- S1-05/06/07 的状态、架构、README 更新集中在 `cbfb8ea`，并非分别跟随各功能 commit。
- 没有证据证明“每一个中间 commit”都独立编译通过；最终工作区构建通过不能证明各 commit 树通过。无需重写这些已提交历史，后续按规则执行即可。

建议在现有 §8.2 后补充以下可执行约束：

1. 小任务是验收明确、依赖完整、可独立编译的切片。大任务拆分时先登记子任务 ID、交付物和编译命令；不要做完多个任务再按文件凑提交。
2. 完成当前子任务的代码、测试源码、必要 schema/ADR 和状态更新后立即提交；未提交前不开始下一子任务。文档中保留大任务 IN_PROGRESS 和已完成子任务状态。
3. 只暂存当前任务的明确路径；提交前同时检查 `git diff`、`git diff --cached`、`git diff --cached --check` 和状态。现有 `git diff --check` 默认检查未暂存差异，不能替代 staged 检查。
4. 编译验证必须对应将被提交的代码内容，不得依赖未暂存/未跟踪的实现文件才能成功；提交后确认 HEAD 和预期变更一致，报告 commit hash。
5. “工作区干净”适用于本任务新增改动；保留进入仓库时已有的用户更改，记录其归属。不得为了凑干净工作区自行提交、stash、删除或重置无关用户更改。现有“无关更改先 commit/stash”的措辞需修正，避免与不得覆盖用户修改冲突。
6. 规则明确 commit 不等于 push，不等于阶段验收完成；只读审查无需制造空提交。编译失败或外部阻塞时如实记录，不能写 DONE；不要为了赶提交绕过验证。
7. 沿用第 7 节按风险选择验证：普通实现编译相关代码及新增测试源码，阶段退出执行明确门禁；纯文档任务说明不涉及编译，避免每个文案提交机械全量构建。命令同时提供 Windows 与 Unix 形式。

## 5. 建议修复顺序和验证

建议暂停推进 S2-01，先顺序完成以下独立切片，每个切片代码+测试+文档一个 commit：

1. R01：HTTP 正文有界读取及异常收尾。
2. R02/R13：图片缓存交接、单实例和请求执行边界（若范围过大再按可编译切片拆分）。
3. R03/R11/R12：正文文件适配、稳定页标识、按需加载。
4. R04/R08/R14：来源恢复、事务式安装和失效通知，分别提交。
5. R05：阅读恢复编排。
6. R06：进度写入顺序、数据库事务、退出 flush，可按依赖拆分。
7. R07：正确的集成 fixture 与可重复验收脚本。
8. R09/R10/R15/R16：分页、缩放、日志、初始化调用各自一个修复任务。
9. 同步剩余 S1-08 判据与延期验收的正式范围，再执行 S1-07 闭环回归并决定 Stage 1 状态。

普通切片按仓库约定编译；此次属于阶段验收审查，修复后针对被证明缺失的边界执行最小回归：真实 OkHttp/缓存、同一 fixture 的 SourceCore 能力、来源重建、异步恢复与写入竞争。设备测试只安排文档确需的手势/Android生命周期项目，不用全量压力测试代替缺失的跨层断言。

## 6. 本轮实际验证及限制

- 只读检查 Git 状态及历史提交树；工作区仍干净，未修改项目文件、未 commit、未 push。
- 检查本机已缓存的实际 Okio 3.18.1 和 Coil 3.4.0 源码。
- 用缓存依赖运行临时 Java 探针：7 字节正文 `readByteArray(1048577)` → EOFException；DiskCache editor.commit 后 editor.data → `IllegalStateException: editor is closed`，均已复现。首次探针选错本机 coroutines 旧 jar 出现 NoSuchMethodError，改用项目锁定 1.11.0 后通过；该探针环境错误不算项目缺陷。
- 用 Node 执行仓库 demo 源的声明结构探针：exploreIsArray=false，search.load / comic.loadInfo / comic.loadEp 均 undefined。该验证只证明 fixture 结构，不冒充 Android/QuickJS 端到端实测。
- `git diff --check` 通过。
- 本轮没有运行 Gradle 全量构建、单测、instrumentation、实机测试；250 项通过是仓库既有记录，不是本轮验证结果。
- 最初怀疑 Manifest 缺 INTERNET，但既有 merged manifest 确有该权限，因此未列为缺陷。
