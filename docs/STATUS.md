# 项目执行状态

本文件记录当前交付与验证情况。所有后续开发任务由用户指定，不从历史计划中自动选择任务。

## U-33 修正源列表拖拽坐标污染导致的乱跳与卡顿（DONE）

- 用户需求：v2.9.3 后首卡换位仍乱跳，且拖动其他项变卡。
- 根因（U-32 未触及）：`pointerInput` 挂在 `.graphicsLayer` 内层。卡片的 `translationY` 会参与 pointer 坐标转换，
  手势读到的 `change.position` / `dragAmount` 被卡片自身位移污染，形成「位移→坐标→再位移」的反馈循环；
  换位后卡片槽位变化又叠加到 `self.offset`。两者共同导致首卡跳变、其余项拖动发抖。U-32 的公式本身没错，坏在 feeds 它的坐标源。
- 修法：
  - 手势节点与视觉变换节点**分离**：外层 `Box` 只挂 `pointerInput`（永不位移，坐标干净），内层 `Box` 挂 `graphicsLayer` 只做视觉位移。
  - 手指位置改由**累计位移增量**推导：`dragDeltaY += amount.y`（dragAmount 与布局无关），视觉中心 = `initialLayoutCentre + dragDeltaY`。
    `initialLayoutCentre` 在起拖瞬间读取一次，此后不再依赖任何变化的卡片槽位 offset。
  - `draggedTranslation(initialCentre, dragDelta, layoutCentre) = (initialCentre + dragDelta) - layoutCentre`，
    `layoutCentre` 绘制阶段读当前布局，换位/滚动自动抵消，无需补偿减法。
  - 纯函数测试随签名更新，断言「初始中心 + 手指位移」恒定，换位/滚动不改变卡片视觉中心。
- 验证：`:feature:sources:testDebugUnitTest` 9 项全过；kotlin 与 androidTest 编译通过；`:app:assembleRelease` 正式签名 APK 构建通过、签名指纹一致。

## U-32 修正源列表首卡换位时整列表上跳（DONE）

- 用户需求：拖动第一张卡片触发换位后，整个列表会突然向上跳一下。
- 根因（U-31 未触及）：拖拽位移用累积 `dragOffset` + 换位时补偿 `dragOffset -= (target.offset - self.offset)`。
  补偿是一个状态写入，立即影响当前帧的绘制；而 `dragOrder` 改变引发的 LazyColumn 重新布局要到下一帧才生效。
  两帧不同步，被拖卡片先按旧布局减掉一个槽位高度再落到新槽位，视觉上整列表上跳一格；首个卡片起始 offset 最小、
  跨越槽位差最大，最明显。边缘滚动同样靠累加 `dragOffset` 补偿，存在同类时序风险。
- 修法：
  - 位移改为**推导式**：`draggedTranslation(fingerY, grabOffset, layoutCentre) = (fingerY - grabOffset) - layoutCentre`。
    `fingerY` 是手指在列表视口的绝对位置（`self.offset + change.position`，与卡片布局槽位无关），
    `grabOffset` 保证起拖时卡片中心不对齐到手指而不跳。`layoutCentre` 在 `graphicsLayer` 绘制阶段读本次布局后的
    真实卡片中心，因此换位/滚动改变布局中心时，位移在同一帧自动跟上，无需任何补偿减法。
  - 边缘滚动保留 `dispatchRawDelta`，但不再手动补偿 `dragOffset`——滚动改变布局中心，位移跟随即可。
  - 抽出纯函数 `draggedTranslation` 并加 3 项测试，断言换位/滚动改变布局中心后卡片视觉中心恒定（不跳）。
- 验证：`:feature:sources:testDebugUnitTest` 9 项 `SourceReorderTest` 全过；编译与 androidTest 编译通过；
  `:app:assembleRelease` 正式签名 APK 构建通过。

## U-31 重做源列表拖拽：同步换位 + 边缘自动滚动（DONE）

- 用户需求：首卡仍乱跳；拖到底部不会自动滚动，无法跨出可视区调整顺序。
- 根因（U-30 未触及）：UI 发出 `ReorderSources` 后立刻补偿 `dragOffset`，但 ViewModel 的 `reorder()` 异步写盘成功后才更新 state；空窗期布局没变、补偿已生效，持续拖动又用旧顺序重复发 action、重复补偿，越叠越乱。首卡 offset 最小、补偿量最大，跳得最明显。且完全无边缘滚动。
- 修法：
  - UI 层 `dragOrder` 在拖拽期维护临时顺序并直接驱动 `displaySources`，松手才提交一次 `ReorderSources`，补偿与布局同帧，无异步空窗。`swapPending` 经两帧布局后解锁，避免旧 offset 下重复换位。
  - ViewModel `reorder()` 改乐观更新：先同步改 state（松手无旧顺序闪烁），后台写盘失败才回滚。
  - 边缘自动滚动：`LaunchedEffect` 逐帧按手指视口 Y 计算速度，`dispatchRawDelta` 推进列表并用其实际返回值补偿 `dragOffset`，卡片始终跟手；滚动中继续跑换位判定。
- 验证：`:feature:sources:testDebugUnitTest` 35 项通过（新增 VM 乐观更新/失败回滚 2 项）；androidTest 编译通过；`:app:assembleDebug` 通过。

## U-30 修正漫画源列表首张卡片拖拽乱跳（DONE）

- 用户需求：漫画源页面拖拽时第一张会乱跳。
- 根因：拖拽重排用 LazyColumn 的 `layoutInfo.index` 去操作 `state.sources`。列表有更新横幅时横幅占 index 0，
  所有来源的 layout index 比数据位置大 1 —— 拖第一张时 `removeAt(1)` 删掉第二张、`add(2, ...)` 把第一张插到错位，
  出现重复与乱跳；无横幅时这种索引空间耦合同样脆弱。
- 修法：新增纯函数 `reorderedSourceIds(current, from, to)` 按 `SourceId` 身份重排，落点 key 解析成 `SourceId`；
  横幅 key 或自身落点返回 null 不重排。跟手补偿仍用布局 offset，与身份重排解耦。
- 验证：`:feature:sources:testDebugUnitTest` 33 项通过（新增 `SourceReorderTest` 6 项覆盖首卡换位、跨位、上移、
  横幅落点、自身落点、未知 id）；`:app:assembleDebug` 通过。

## U-28 漫画源设置改为两段式（DONE）

- 用户需求：把设置参数挪到二级页面；一级页只留开关与拖拽；拖拽改成长按直接拖，去掉触发按钮。
- 路由：新增 `AppRoute.SourceSettings(sourceId)`（`source-settings:{id}`，可空以兼容源被移除后的恢复），
  `BackDestination` 回 `Sources`，`AppRoot` 装配 `SourceSettingsRoute`。点卡片 = `onOpenSettings` → 该路由。
- 一级页 `SourcesScreen`：删除卡片内设置手风琴、顶栏排序按钮、独立 reorder 模式与 `ReorderList`。
  每张卡只有头像/名称/状态行 + 启用开关；点卡片进二级页，长按卡片用
  `detectDragGesturesAfterLongPress` 原地拖拽排序——按 `layoutInfo` 实测的卡片中点判定落位，跨位后立即
  重排并补偿位移让卡片跟手，无模式切换、无把手按钮。
- 二级页 `SourceSettingsRoute`/`SourceSettingsScreen`：独立顶栏，复用 `SourceSettingsBody`（账号 + 脚本声明设置），
  更新/移除 footer 从一级页搬来；进入 `OpenConfiguration`，离开 `CloseConfiguration`。
- 修正：`CloseConfiguration` 不再取消正在进行 settings 防抖写入、不再清空待写队列——二级页返回是高频操作，
  旧逻辑会丢弃防抖窗口内刚改的设置；现在只清显示状态，保存任务在 ViewModel scope 跑完。
- 验证：`:core:navigation:test` 56 项、`:feature:sources:testDebugUnitTest` 54 项全绿；
  `:feature:sources:compileDebugAndroidTestKotlin`、`:app:assembleDebug` BUILD SUCCESSFUL。
  androidTest 更新「开关不导航、点卡片进设置」用例；导航测试覆盖新路由编解码与返回。

## U-27 修正：蒙层常驻，工具栏从蒙层上方开始（DONE）

- 反馈：v2.8.0 把「工具栏不遮挡蒙层」做成了「面板弹出时隐藏蒙层」，用户明确指出要的是蒙层一直在、
  工具栏不要盖住它。
- 实现：`ReaderStatusScrim` 在 `Ready` 状态常驻，不再因 `showControls` 或底部 notice 隐藏。
  `onSizeChanged` 量出蒙层实际高度（含手势区 inset），作为 `scrimClearance` 传给 `ReaderToolOverlay`：
  面板的 `Box` 加 `padding(bottom = scrimClearance)` 整体上移；末话 / 下一话未知 / 加载失败三个底部提示
  同步上移同量级。蒙层与面板、notice 不再共用同一块像素，蒙层全程可见。
- androidTest 改为断言「面板打开后蒙层仍显示，且面板底部不超过蒙层顶部」以及「末话提示底部不超过蒙层顶部」。

## 当前任务：U-26 阅读器底部状态蒙层；长按热区扩到页面 90%（DONE）

- 用户需求：阅读器底部加一个小蒙层展示当前时间 / 章节 / 页数；长按弹出的控制面板必须在蒙层之上、
  不受遮挡；长按弹出面板的区域改到当前页面的 90%。
- 蒙层 `ReaderStatusScrim`（`:feature:reader`）在 `BottomCenter` 渲染 `时间 · 章节 · 章节内页码`，
  深色半透明胶囊 + 白字（黑白漫画与彩色页面上都可读），章节过长省略、缺失显示「—」。
  - 时钟可注入（`ReaderScreen.clock`，默认 `LocalTime.now()` 的 HH:mm），每秒刷新、分钟跳变后 1 秒内跟上。
    时钟状态 scoped 在蒙层内部，tick 只重绘胶囊，不重组上层图片画布。
  - 层级：蒙层在根 Box 中声明于控制面板之前（z 序更低），且 `showControls` 或有底部 notice
    （到末话 / 下一话未知 / 加载失败）时直接隐藏 —— 两者不叠压、不争同一块像素，面板绝不被遮挡。
- 长按热区：`ReaderControlsTapTarget` 的面板热区从 x 0.20–0.80 / y 0.30–0.70 扩到 x/y 各 0.05–0.95
  （页面中央 90%）。分页菜单改用独立的 `containsCompact`（原 60%×40% 区域）并在拆分开启时优先判定 ——
  否则面板扩到 90% 后，菜单只剩 5% 外圈，实际按不到。
- 验证：`:feature:reader:testDebugUnitTest` 48 项通过（热区单测重写为 90% / compact 两组）；
  `:feature:reader:compileDebugAndroidTestKotlin`、`:app:assembleDebug` BUILD SUCCESSFUL。
  androidTest 新增蒙层展示 / 随面板隐藏 / 随底部 notice 隐藏 / 边缘 8% 长按开面板四个用例
  （本节点按约定只保证编译，未跑 instrumentation）。版本 2.8.0 / 2008000。

## 当前任务：U-25 顶栏在内容不足时也可用；换源提示改成一次性（DONE）

- 用户第四轮反馈：①「章节过少的话，根本出现不了顶部的标题栏，导致上面的按钮无法点击」；
  ②「换源后干嘛一直提示，觉得这个提示需要一直显示吗」；③要求把三个按钮的交互和样式一起优化。
- ① 根因：`collapseFraction = 已滚距离 / (hero 高度 - 96dp)`。章节少时列表根本没有那么多可滚距离，
  比例永远到不了 1，于是顶栏一直停在「展开态」：标题 alpha 为 0 不显示，收藏 / 换源
  `enabled = collapseFraction >= 0.98f` 恒为 false —— 控件在屏幕上却点不动。
  - 折叠进度改为按**列表真实可滚范围**计算：`已滚 + 列表剩余可滚（末项 offset+size+afterContentPadding
    - viewportEndOffset）`，分母取 `min(hero 可折叠距离, 真实范围)`。内容不足一屏时范围 ≈ 0，直接视为收起；
    恰好在列表底部也会收满，不再是「永远差一点」。
  - 没有封面可折叠的页面（加载中 / 加载失败 / 源已卸载）直接按收起态渲染，返回按钮不再在浅色背景上用白图形。
- ①③ 交互与样式：顶栏返回、收藏、换源统一为 44dp 圆形控件（`ToolbarAction`）。展开态压着封面时带
  38% 黑色底盘 + 66% 白色描边、白色图形（封面由源提供，亮色封面上裸白图形会看不见）；收起后在 surface 上
  只剩图形本身，颜色过渡到 onSurface / primary。收藏与换源**两种状态都可点**，只有「确认换源」的顶栏勾号
  仍随折叠淡入 —— 它在展开态有 hero 里那枚带文字的实心按钮作为入口，不存在无路可走的情况。
  - 删除 hero 信息区末尾那枚重复的收藏圆钮（`FavoriteAction`）与 `HeroFacts`/`HeroTagGroups` 的
    `trailingAction` 参数：收藏只在顶栏有一处，不会「展开态两个、收起态一个」。
- ② 根因有两处，都是「提示被当成状态挂在页面上」：
  - 换源成功：`switchConfirmed` 与 `switchMessage` 同一次更新里写入，`DetailsRoute` 立刻换路由，
    候选页的 snackbar 协程被销毁、`SwitchMessageConsumed` 没发出；落点页（同一 ViewModel）重新组合时
    又弹一次。现在候选页不显示这条消息，只由落点页显示一次（`SnackbarDuration.Short` + 可手动关闭）。
  - 换源失败：原先写进 `shelfMessage`，而 `shelfMessage` 会作为白字常驻在 hero 左下角，直到下次收藏动作才清。
    现在失败走独立的 `switchError` + snackbar，一次即清；`onScreenEntered()` 也会清掉它，
    刷新页面不会重放上一次点击的答案。hero 里的常驻提示文本一并删除，`shelfMessage` 只留在收藏弹窗内，
    关闭弹窗即清。
- 验证：`:feature:details:compileDebugKotlin`、`:feature:details:compileDebugUnitTestKotlin` 通过；
  `:feature:details:testDebugUnitTest` 51 项通过（新增「换源失败不随下次进入重放」）；`:app:assembleDebug`
  BUILD SUCCESSFUL；版本 2.7.2 / 2007002。

## 当前任务：U-24 换源的确认动作、三个按钮的关系、返回保留滚动位置（DONE）

- 用户第三轮反馈（2.7.0 之后）：①「无法替换，现在的替换按钮直接又跳转到换源搜索的页面」；
  ②「换源搜索列表滑动后点击进入详情后，返回又回到列表顶部了」；③要求重新规划收藏 / 进入换源 /
  确认换源三个按钮的关系，确认换源必须与另外两个互斥。
- ① 根因：候选页封面上放的是**换源图标**，点了又开一页换源搜索，等于原地打转；改绑却在点选瞬间
  就发生了，用户没有任何「确认」的落点。现在点选只负责「看」，确认才负责「换」：
  - `SourceSwitchViewModel` 不再持有改绑能力（`moveShelfEntry`/`shelfOwner`/`chosen` 全部删除），
    换源页退回成纯搜索页；点选直接 `onOpenComic`。
  - 候选页（`backTo is AppRoute.SourceSwitch`）新增**确认换源**：`DetailsAction.ConfirmSourceSwitch(from)`
    → `CollectionRepository.replaceSource(from, 本页 key)` → 成功后 `switchConfirmed`，
    `DetailsRoute` 交给装配层 `sourceSwitchCommit(current)` 把返回目标折成原详情页的 `backTo`
    （即跳出换源链，返回直接回到最初那部作品来的列表），页面原地变成「这部作品的页面」，
    收藏按钮同时点亮。失败留在本页并提示，可重试；从未收藏过的作品无需迁移，直接通过。
  - 成功后有 snackbar 明确说明结果（迁移了收藏 / 未迁移），因为「按钮消失」本身不足以证明换源成功。
- ② 根因：换源页每次返回都是从零重建的 composable，`rememberLazyListState()` 无从恢复。
  位置改由 ViewModel 持有（`ScrollSlot`，不放进 `UiState` 以免每帧重组）：分组总览、每个来源的横向
  卡片行、单源分页列表各占一个 slot；重新搜索或重新打开来源时清空对应 slot（结果变了，旧位置无意义）。
  「列表还没有 item 时测得的 0」不写回，避免恢复值被空列表抹掉。
- ③ 三个按钮的关系（同一时刻只出现一个）：
  - 普通详情页：封面角标 = **收藏**；顶栏 = **进入换源** + 收藏（互不遮挡，只在顶栏收起后出现）；
  - 候选详情页：只有 **确认换源**——封面信息区下方一枚带文字的实心按钮（不再用 44dp 圆形图标，
    那个位置此前正是「换源」图标，用户会以为要再搜一次），顶栏右侧同位置是勾号；收藏与进入换源都不出现。
- 验证：`:core:navigation` 26 项（新增候选页认源、确认后折链、非候选页无事可做）；
  `:feature:search` 29 项（改绑用例随实现删除，新增滚动位置三条：恢复、重搜清空、重开来源清空）；
  `:feature:details` 50 项（新增确认迁移收藏、未收藏直接通过、迁移失败可重试三条）；
  `:app:assembleDebug` BUILD SUCCESSFUL；版本 2.7.1 / 2007001。

## 当前任务：U-23 换源重做成独立搜索页（DONE）

- 用户判定前三版（2.6.6 / 2.6.12 / 2.6.13）都不可用：「都是类似搜索的逻辑，但是跟搜索的体验差太多了」，
  要求把换源逻辑重做。根因不是修细节，而是形态错了：换源一直是详情页里的一个 Dialog，
  自己维护查询、结果与选中态，与搜索页是两套实现，自然越改越不像。
- 形态：新增目的地 `AppRoute.SourceSwitch(comicKey, title, backTo)`，换源成为真正的页面
  （`:feature:search` 的 `SourceSwitchRoute`），返回链自然变成「详情 → 换源结果 → 新详情 → 新换源结果…」，
  每一步 back 都回到用户刚才站着的那一页，不再靠复制出来的面板状态。
- 与搜索同源：`searchEverySource()` 从 `SearchViewModel` 抽出为共享实现，聚合搜索与换源共用同一份
  并发/预览/逐源错误隔离逻辑；`AggregateSourceResults` 从 `SearchScreen` 抽出为共享组件，
  两个页面渲染的是同一个列表（来源标题 + 横向 `ComicCoverCard` 流 + 「查看全部」）。
  换源页因此天然具备搜索的：可编辑标题（种子是作品名）、逐源进度、单源失败不遮挡其他源、
  点「查看全部」进该源分页列表。
- 交互：点选作品即改绑书架条目并打开它的详情页（仍需一次点击决定，不做选中态）；改绑失败留在本页并提示；
  连续改选时从「上一次选中」的来源改绑，而不是从最初那个已不在书架上的来源。
  书架改绑通过 `onMoveShelfEntry` 回调传入，`:feature:search` 不依赖 `:data:collection`。
- 详情页瘦身：删除 `SourceSwitchPanel`、`SourceSwitchRow`、7 个换源 Action、ViewModel 里的换源搜索与改绑
  实现、`:feature:details` 的 paging 主源码依赖；详情只保留「跳转换源页」一个回调。
  换源进入的详情页仍只显示换源入口、不显示收藏（判定改为 `backTo is AppRoute.SourceSwitch`）。
- 验证：`:core:navigation:testDebugUnitTest` 23 项（新增 switch 路由往返与旧链接、返回链、判定）；
  `:feature:search:testDebugUnitTest` 29 项（新增 `SourceSwitchViewModelTest` 7 项：只在其他源搜索、
  无标题不搜、改标题重搜、改绑并回传、连续改选从上次选中改绑、改绑失败可重试、单源列表不干扰分组）；
  `:feature:details:testDebugUnitTest` 通过（换源用例随实现删除）；`:app:assembleDebug` BUILD SUCCESSFUL；
  `git diff --check` PASS。版本 2.7.0 / 2007000。

## 当前任务：U-22 换源返回落到候选结果（DONE）

- 用户第二轮反馈：2.6.12 让返回落到「上一层详情页」，但换源这一步的上一层是**换源候选列表**，
  从候选进入详情后按返回应当回到那页结果继续挑，而不是回到被结果盖住的旧详情页。
- 做法：`AppRoute.ComicDetails` 增加 `showSourceSwitch: Boolean`（随路由编码，`comic:...:backTo:1`，
  旧链接缺该段解析为 false）。`sourceSwitchDestination()` 把返回目标写成
  `current.copy(showSourceSwitch = true)`：返回的目标页会自己把候选面板重新打开。
  装配层把 `current.showSourceSwitch` 传进 `DetailsRoute`，路由变化时发 `RestoreSourceSwitch`。
- 面板重开复用内存里的行：`RestoreSourceSwitch` 只置 `sourceSwitchOpen = true`，不清空
  `sourceSwitchRows`，也不会重复搜索；只有进程重建后行已丢失（`sourceSwitchRows` 为空）才回到
  `openSourceSwitch()` 用标题重搜。新增用例断言重开不产生第二次搜索、重建后会补搜。
- 链路保持一致：A →(换源) B →(换源) C，返回依次回到 B 的候选列表、A 的候选列表、A 自己的来源；
  `openedBySourceSwitch()` 仍以 `backTo is ComicDetails` 判定，B、C 继续隐藏收藏、只留换源。
- 验证：`:core:navigation:testDebugUnitTest` 21 项通过（`AppRouteEncodingTest` 6 项含带标记往返与旧链接兼容、
  `SourceSwitchNavigationTest` 3 项断言返回目标带标记）；`:feature:details:testDebugUnitTest` 54 项通过
  （新增「返回时重开已有结果不重搜」「重建后补搜」）；`:app:assembleDebug` BUILD SUCCESSFUL；`git diff --check` PASS。
  版本 2.6.13 / 2006013。

## 当前任务：U-21 换源页面的返回目标与按钮取舍（DONE）

- 用户反馈两点：①从换源结果打开的详情页按返回落在首页，「每次做一个新页面都是返回首页」；
  ②该页面只需换源、不需要收藏，换源按钮应占据收藏的位置。
- 返回目标（`SourceSwitchNavigation.kt`）：2.6.6 把换源当「替换」，新页面继承旧页面的 `backTo`，
  于是旧页面被抹掉、返回跳到更早的列表（常见就是首页）。现改为压栈：新页面的 `backTo` 是
  当前详情页本身，层层可回退（A → B → C，返回依次 C→B→A→A 自己的列表）。
  纯函数 `sourceSwitchDestination(current, key, title)` 与 `openedBySourceSwitch()` 放在
  `:core:navigation`，装配层只做连线，语义可单测。
- 按钮取舍：`openedBySourceSwitch()` 为真（`backTo is ComicDetails`）的页面隐藏收藏——
  封面右下角的收藏圆钮换成同尺寸同位置的换源圆钮（`SwitchSourceAction`），顶栏只留一个换源按钮，
  正好落在原收藏的右端位置。非换源进入的页面不受影响，仍是「换源 + 收藏」。
  收藏状态本身没变：改绑时 `replaceSource` 已把书架条目迁到新来源，只是这一页不再提供收藏入口。
- 已知取舍：换源后旧源页面仍留在返回链上，若旧源已卸载/停用，返回时显示的是它的「源不可用 + 切换其他源」
  状态页而不是列表。这是用户明确要求的「返回上一页」，比抹掉历史更符合预期。
- 验证：`:core:navigation:testDebugUnitTest` 20 项通过（新增 `SourceSwitchNavigationTest` 3 项：
  换源页返回上一详情页、连续换源逐层回退、换源进入判定）；`:app:assembleDebug` BUILD SUCCESSFUL；
  `git diff --check` PASS。版本 2.6.12 / 2006012。
- 未加 Compose 断言：`:feature:details` 没有 androidTest 源集，为一条按钮可见性断言新建整套
  instrumentation 依赖不划算，按钮条件保持在渲染层参数 `enteredBySourceSwitch`（由装配层传入）。

## 当前任务：U-20 详情页切换其他漫画源（DONE）

- 场景：已安装源加载详情失败、或源被卸载后进入详情页时，按钮区提供「切换其他源」。点击后拿当前标题
  去其余已安装源各搜第一页，结果按来源分组；点选一部作品即把书架条目改绑到新来源并打开它的详情页。
- 列表形态（2.6.9 修正）：2.6.6 初版是自绘的 44×60 缩略图行 + 单选圆点 + 顶栏「替换」，用户反馈
  「不是我想要的效果」。现直接复用搜索页的 `ComicHorizontalCard`——封面、标题、连载状态、作者、
  更新时间、标签，整卡可点；取消选中态与顶栏替换按钮。`SearchScreen` 的标签条是私有实现，
  详情侧另写了一份 `SourceSwitchTags`，未让详情模块依赖搜索模块。
- 交互语义：一次点击即结论。应用没有详情返回栈（`AppRoute` 单状态 + `detailsOrigin`），
  预览后回不到候选列表，所以「先选后确认」既无意义也无法补确认，确认步骤整体去掉。
- 分组保留：每个来源一行小标题（搜索中 / N 个结果 / 无同名结果 / 搜索失败），单个源慢或坏
  不遮挡其他来源已经返回的结果。
- 改绑失败不导航：`replaceSource` 抛错时留在面板并提示「无法把收藏迁移到新来源，请重试。」，
  否则书架仍指向坏来源而界面上没有任何说明。未收藏的作品改绑返回 false 属正常结果，照常进入详情页。
- 数据：`CollectionRepository.replaceSource(from, to)` 只改书架条目身份与收藏夹，保留封面与加入时间、
  清空旧章节快照与更新标记；下载与阅读进度不迁移（两源章节 ID 不互通），文案如实说明。
- 路由：`AppRoute.ComicDetails` 增加可选 `title`，供失败态没有详情可取标题时发起搜索；旧格式链接仍可解析。
- 验证：`:feature:details:testDebugUnitTest` 52 项通过（新增「改绑失败保持面板打开」，原「选中再替换」
  两条用例改写为一次点击）；`:app:compileDebugKotlin` BUILD SUCCESSFUL；`git diff --check` PASS。
  版本 2.6.9 / 2006009，已按发布流程发布 Release。

## 当前任务：U-19 漫画源设置页 v2 改版（DONE）

- 按用户三轮反馈重做已安装来源列表：问题不是视觉样式，而是层级与热区——来源与设置项压在同一层看不出分区，
  头行整行可点导致「点开关/更新」与「展开设置」语义冲突，配置面板还有 520dp 嵌套滚动与重复的账号按钮堆。
- 渲染层（`SourcesScreen.kt`）：多张 Card 改为一张连续列表 + 细分割线 + 淡色分组头；每个来源头拆成两个独立热区，
  左侧（箭头 + 名称 + 版本）只管展开，右侧（登录异常文字 / 有更新角标 / 启用开关 / ⋮ 溢出菜单）永不触发展开；
  更新、调整顺序、移除收进溢出菜单；展开体缩进 16dp 并带 2dp 引导线，视觉上从属于来源头。箭头 180° 旋转 + `animateContentSize`，遵守系统减少动态效果。
- 配置面板（`SourceConfigurationDialog.kt`）：去掉内部 520dp 滚动；账号收敛为「登录状态（带检测）」「重新登录 / 更换账号」
  「注销」「注册」等统一行；四种设置类型统一为 `SettingRow(title, subtitle?, control)`，INPUT 就地展开编辑；保存按钮全页仅一处，
  移除了与页面顶部重复的消息条。
- 更新入口上提：单个来源在分组头显示「有更新」角标；≥2 个来源有更新时顶部出现「N 个来源有更新 · 全部更新」横幅，
  新增 `SourcesAction.UpdateAllOutdated` 与 ViewModel 串行批量实现（逐个走 `installFromCatalog`，单个失败不中断其余）。
- 状态层未动：展开仍是单 id 手风琴（`expandedSourceId`），配置仍按展开的来源按需加载，不增加脚本执行；未触碰数据层与配置缓存。
- 依赖：新增 `libs.compose.animation` 并在 `:feature:sources` 显式 implementation（供 `animateContentSize` 使用）。
- 验证：`:feature:sources:compileDebugKotlin :feature:sources:compileDebugUnitTestKotlin :feature:sources:compileDebugAndroidTestKotlin :app:assembleDebug` — BUILD SUCCESSFUL。
  新增 2 条批量更新 ViewModel 用例（全成功 / 无过期源）与 2 条 Compose 用例（批量横幅动作 / 点开关不触发展开）均编译通过；
  `testDebugUnitTest --tests '...update all*'` — BUILD SUCCESSFUL。全量 `SourcesViewModelTest` 24 项中 1 项失败：
  `login validity check reports a source confirmed expired session`（期望 `checkLogin` 失效时写 message，但 HEAD 原实现只写 outcome），
  经 `git show HEAD` 核实为改动前既有失败，与本次无关，未在本任务顺带修改。
- `git diff --check` — PASS。未发布 Release（等待用户指令）；v2 真机观感与操作手感待用户验收。
- 方案与逐条依据见 `docs/reviews/sources-loading-and-update-ux-review.md`（含 v2 定稿章节）。

## 当前任务：U-18 失败页按章节内页码显示（DONE）

- 用户反馈阅读器顶部显示 16 / 22，但失败页按钮写「重试第 33 页」。根因是翻页/连续模式下
  `ComicPage.index` 是跨章节画布的全局索引：上一章 17 页时，本章第 16 页的全局索引为 32。
  顶部页码与滑杆用章节内索引计算，失败页、占位文字和无障碍描述却直接用全局 `index + 1`。
- `ReaderUiState` 新增 `chapterPageRanges` 与 `pageNumber(index)`；View Model 在初始化、追加下一章、
  前置上一章和重算拆分页时同步各章节的全局页区间。页面失败/占位/无障碍文案改用章节内页码；
  `ReaderAction.RetryPage` 仍传全局索引，重试目标不变。
- 排查「最近频繁加载失败」：索引计算不参与图片请求，加载与重试全程使用同一全局索引；失败按钮只在
  `resolvePageBounded` 三次超时/IO 失败后出现。`CoilPageImagePipeline.sizeOf` 会下载整张图片再读尺寸，
  View Model 预取半径为 3（当前页前后共 7 页），屏幕层另有同等半径的预解码；在有限流/签名过期的来源上
  容易并发触发下载失败。本次未把网络失败归因于页码计算，也未改动预取策略。
- JDK 17：`:feature:reader:compileDebugUnitTestKotlin :feature:reader:compileDebugAndroidTestKotlin :app:assembleDebug`
  — BUILD SUCCESSFUL。新增 View Model 与 Compose 回归用例；`:feature:reader:testDebugUnitTest --tests
  'dev.veneranative.feature.reader.ReaderViewModelTest'` — BUILD SUCCESSFUL。
  `git diff --check` — PASS。未发布 Release；真机观感与来源网络状况待用户验收。

## 当前任务：U-17 目录搜索复用聚合搜索结果并逐源呈现（DONE）

- 按用户反馈，目录搜索结果与搜索页聚合结果一致：按来源分组显示封面横向列表，复用相同 PagingSource、空筛选请求和首屏结果顺序；来源目录搜索全部条目，搜索页仍只覆盖已安装来源。每个有结果的来源行增加添加/更新/切换操作。
- 数据层为每个来源完成后立即回调整组搜索结果；页面先显示来源加载状态，再逐行替换为成功、空结果或错误，不等待所有来源结束。
- 移除了试搜阶段的额外详情/章节筛选，避免与搜索页采用不同结果判断规则。新增回归覆盖整组结果映射与较快来源先于慢来源呈现。
- JDK 17：`:data:comic:compileDebugUnitTestKotlin :feature:sources:compileDebugUnitTestKotlin :app:assembleDebug` — BUILD SUCCESSFUL。
- JDK 17：`:data:comic:testDebugUnitTest --tests '...ComicCatalogTest.catalog search emits each source row without waiting for slower sources' --tests '...ComicCatalogTest.catalog search streams ordered source results after bootstrap and always unloads'` — BUILD SUCCESSFUL。
- JDK 17：`:app:assembleRelease` 与 `--no-configuration-cache :app:assembleRelease` — BUILD SUCCESSFUL，Release Lint Vital 与签名身份检查通过。`apksigner` 证书 SHA-256 与 `signing/release-cert.sha256` 一致；`aapt dump badging` 确认 `dev.veneranative`、2.6.3 / 2006003。
- `git diff --check` — PASS。正式 APK：[GitHub Release](https://github.com/lwtor/venera-native/releases/tag/v2.6.3)。用户设备交互待验收。

## 当前任务：U-16 修复漫画源目录试搜并拆分搜索页面（DONE）

- 已确认 U-15 “64 源均无结果”的根因：试搜以随机临时 `SourceId` 安装脚本，但 QuickJS 校验要求它等于脚本声明的 key；因此来源全部在安装阶段失败。`SourcePackage` 现单独保留脚本声明 ID，在保持临时运行时隔离的同时通过校验。
- 搜索入口改为独立全屏页面，恢复目录列表完整高度；结果页按运行时不可用、脚本准备/安装、搜索、详情/章节、超时分阶段显示失败数。
- 新增 QuickJS 临时来源身份回归测试和 ComicCatalog 别名传递断言；两个目标 JVM 测试通过。
- JDK 17：`:source:engine:compileDebugUnitTestKotlin :data:comic:compileDebugUnitTestKotlin :core:navigation:compileDebugUnitTestKotlin :feature:sources:compileDebugUnitTestKotlin :app:assembleDebug` — BUILD SUCCESSFUL。
- JDK 17：两个定向回归用例 `:source:engine:testDebugUnitTest --tests '...QuickJsRuntimeTest.isolated runtime alias...'` 与 `:data:comic:testDebugUnitTest --tests '...ComicCatalogTest.catalog search...'` — BUILD SUCCESSFUL。
- JDK 17：`:app:assembleRelease` — BUILD SUCCESSFUL，Release Lint Vital 与构建前签名身份检查通过。生成 APK 的 SHA-256 证书指纹与 `signing/release-cert.sha256` 一致；`aapt dump badging` 确认 `dev.veneranative`、2.6.2 / 2006002。
- `git diff --check` — PASS。正式 APK：[GitHub Release](https://github.com/lwtor/venera-native/releases/tag/v2.6.2)。搜索联网结果和页面交互等待用户验收。

## 当前任务：U-15 修复漫画源目录试搜全空与搜索页布局（DONE）

- U-14 试搜原先对脚本获取、运行时安装、搜索、详情和章节验证设置 15 秒总超时；来源核心自身的详情/章节调用最长 60 秒，导致正常来源会被提前判失败。另有原生端标题子串过滤，误删来源已返回的本地化/别名标题结果；全部失败又被折叠成空列表。
- 修正为来源搜索排序负责匹配，不再二次过滤结果标题；单来源总超时 120 秒、并发上限仍为 3，支持取消，进度显示已检查数，并区分无结果与试搜失败。布局改为清晰的搜索卡片及独立全屏结果列表。
- 新增回归覆盖 20 秒来源准备、来源返回本地化标题、临时来源隔离与清理；测试源码编译通过，测试未执行。
- JDK 17：`:data:comic:compileDebugUnitTestKotlin :feature:sources:compileDebugUnitTestKotlin :app:assembleDebug` — BUILD SUCCESSFUL。
- JDK 17：`:app:assembleRelease` — BUILD SUCCESSFUL；Release Lint Vital 与构建前签名身份检查通过。
- `apksigner verify --print-certs` SHA-256 与 `signing/release-cert.sha256` 一致；`aapt dump badging` 确认 `dev.veneranative`、2.6.1 / 2006001。
- `git diff --check` — PASS。正式 APK：[GitHub Release](https://github.com/lwtor/venera-native/releases/tag/v2.6.1)。新 UI 和实际目录网络行为等待用户验收。

## 当前任务：U-14 漫画源目录按作品试搜（DONE）

- 漫画源页新增按作品标题试搜当前目录的入口；来源脚本以临时 SourceId 在共享 Runtime 中独立执行，不写入安装存储，不覆盖同 ID 的已安装来源。试搜验证标题匹配、详情与非空章节后才列为命中；用户只需安装选中的来源。
- 单次探测并发上限为 3，每个来源最多运行 15 秒；取消及失败时清理临时 Runtime 会话和来源私有临时数据。登录凭据不会被临时探测使用，因此依赖账号登录的来源可能无法命中；探测也不验证页面图片实际下载。
- `:data:comic` 回归测试源码已添加，验证临时来源 ID、结果映射和会话卸载；尚未执行测试。
- JDK 17：`:data:comic:compileDebugUnitTestKotlin :feature:sources:compileDebugUnitTestKotlin :app:assembleDebug` — BUILD SUCCESSFUL；单元测试未运行。
- JDK 17：`:app:assembleRelease` — BUILD SUCCESSFUL；Release Lint Vital 与构建前签名身份检查通过。
- `apksigner verify --print-certs` SHA-256 与 `signing/release-cert.sha256` 一致；`aapt dump badging` 确认 `dev.veneranative`、2.6.0 / 2006000。
- `git diff --check` — PASS。正式 APK：[GitHub Release](https://github.com/lwtor/venera-native/releases/tag/v2.6.0)。来源目录 UI 及实机目录试搜尚待用户验收。

## 当前任务：U-13 避免凭据持久化错误覆盖漫画源登录成功（DONE）

- 2.5.0 在来源 `account.login` 成功后新增 Keystore 凭据保存；原逻辑让保存异常逸出，ViewModel 因而把来源登录成功误报为失败。
- 改为独立返回“登录成功但凭据未保存”，保留来源返回的登录结果，并向用户说明下次需要手动登录；新增仓库回归测试。
- JDK 17：`:data:source:compileDebugUnitTestKotlin :feature:sources:compileDebugUnitTestKotlin :app:assembleDebug` — BUILD SUCCESSFUL；测试源码编译通过，未执行测试。
- JDK 17：`:app:assembleRelease` — BUILD SUCCESSFUL；Release Lint Vital 与正式签名验证通过。`apksigner` 指纹与 `signing/release-cert.sha256` 一致；`aapt dump badging` 确认 `dev.veneranative`、2.5.1 / 2005001。
- `git diff --check` — PASS。Release：[GitHub Release](https://github.com/lwtor/venera-native/releases/tag/v2.5.1)。当前 USB 连接设备仍报告安装版本 1.4.2，并非 2.5.0；需要用户在运行 2.5.0 的设备上用 2.5.1 验证。

## 当前任务：U-12 漫画源内联设置、登录恢复与来源排序（DONE）

- 已确认采用单列表：折叠项展示来源名称和版本，展开项内提供启停、更新、移除、账号操作及来源自定义设置。
- 展开账号来源后后台检查登录状态；每来源同一时刻最多一个检查请求，手动触发复用正在执行的请求；异常状态在来源行以红色感叹号标识。
- 成功的账号密码登录将账号信息独立写入 Android Keystore AES-GCM 加密的来源私有记录，提供自动重新登录；数据不混入脚本 settings/saveData。此前版本未保存的账号需要首次补录。
- 长按来源行进入顺序调整操作，顺序保存到来源索引；仓库列表及聚合搜索依该顺序展示。
- JDK 17：`:data:source:compileDebugUnitTestKotlin :feature:sources:compileDebugUnitTestKotlin :feature:sources:compileDebugAndroidTestKotlin :app:assembleDebug` — BUILD SUCCESSFUL；测试源码编译通过，未执行测试。
- JDK 17：`:app:assembleRelease` — BUILD SUCCESSFUL；Release Lint Vital、构建前签名身份与生成后 APK 签名检查均通过。
- `apksigner verify --print-certs` SHA-256 与 `signing/release-cert.sha256` 一致；`aapt dump badging` 确认 `dev.veneranative`、2.5.0 / 2005000。
- `git diff --check` — PASS。正式 APK 已发布：[GitHub Release](https://github.com/lwtor/venera-native/releases/tag/v2.5.0)。真机交互及升级前账号凭据补录留待用户验收。

## 当前任务：U-11 漫画源登录状态校验与重新登录（DONE）

- 当前配置中的“已登录”只是本地会话数据标记，不能作为服务器有效性结论。
- 2.4.0 支持来源提供的 `account.validate(): Promise<boolean>` 以及上游 `loginWithCookies.validate(values)` 协议在线检查；网络 API 只允许来源读取自身、HTTPS 地址下匹配的 Cookie。配置弹窗区分本地会话与服务端校验结果，并支持有会话时直接重新登录，不要求先退出。
- Debug、Release 编译通过，Release Lint Vital 和签名指纹检查通过；来源仓库、来源管理、元数据及来源网络测试源码编译通过，未运行单元测试。2.4.0 APK 已发布：[GitHub Release](https://github.com/lwtor/venera-native/releases/tag/v2.4.0)；真机验收待用户完成。

## 当前任务：U-10 漫画源目录切换与更新范围（DONE）

- 来源安装记录目录地址；更新只匹配同目录下同一来源，其他目录的同一来源显示“切换”操作。
- 目录页仅显示当前目录；添加新目录和切换已有目录分别通过对话框操作。
- 来源包索引为目录地址新增可选字段；旧记录和手动安装可正常读取，手动安装不会伪造目录归属。
- 2.3.0 APK 已发布：[GitHub Release](https://github.com/lwtor/venera-native/releases/tag/v2.3.0)。页面测试源码通过编译，设备交互留待用户验收。

## 当前任务：U-08 漫画源目录历史（DONE）

- 漫画源目录设置保存最多 10 个成功加载过的地址，按最近使用排序；点击历史项会立即加载并切换目录。
- 旧版的最后成功目录会作为历史迁移来源；失败的加载不会进入历史。真机页面交互尚待用户验证。

## 当前任务：U-09 已安装漫画源更新（DONE）

- 来源目录与已安装来源按来源 ID（无 ID 时按安装地址）匹配；版本不同会在发现页和已安装列表显示更新提示，可直接覆盖更新。同版本或未声明目录版本时仍可手动重新安装。
- 更新通过现有原子替换流程，失败保留旧版本；成功更新保留启用/停用状态、来源私有设置和会话数据。更新发布来源变更版本，使搜索、探索、详情等既有缓存刷新。

## 当前任务：U-07 漫画源目录地址恢复

- 目录地址原只保存在页面状态，重建后恢复默认值。现使用 `data:settings` 保存成功加载的目录地址，重新打开或重启时自动恢复并加载。
- 失败的目录地址不会覆盖上一次成功的地址；设置存储稍后就绪时不覆盖用户正在输入的内容，并保存此前已成功应用的地址。目录与已安装漫画源彼此独立。
- 目录地址恢复有 ViewModel 回归；未在设备上单独执行重启验收，等待用户验证。

## 当前任务：U-06 GitHub 设备授权错误

- GitHub 设备授权明确请求 `offline_access`，确保支持可刷新的用户令牌；授权轮询遇到短暂连接错误或服务端暂时性错误时有限重试，不立即丢弃授权流程。非网络类错误不再直接提示网络断开。
- GitHub 官方允许不返回刷新令牌的配置，旧实现却无条件读取；它是已确认的兼容缺口，但尚不能确定就是本次设备故障的唯一原因。
- 最小回归覆盖授权 scope、临时失败恢复和客户端错误不重试。Xiaomi 25128PNA1C 原 2.0.0 与新 APK 签名一致，已用 `adb install -r` 保留数据覆盖安装 2.0.1；用户反馈 GitHub 连接已恢复。未获得原始异常，不将具体缺口认定为唯一根因。
- 本次修正已有 `AboutViewModelTest` 替身遗漏 `cachedApk` 实现导致的测试编译错误。

## 当前任务：U-05 Release 签名备份与校验

- 按用户要求生成新 Release 密钥并创建独立 GitHub 私有库 `lwtor/private-vault`；加密备份和用户要求明文保存的随机恢复密码存于该库。可读取私有库者可以解密，需限制账号权限并另行备份。
- 恢复脚本可在 Mac / Windows 通过已登录的 GitHub CLI 与 GnuPG 恢复 Git 忽略的签名文件，不覆盖不同的现有文件；原有 Release 密钥和配置未覆盖。
- 项目固定新证书公开指纹，Release 构建前和 APK 生成后均自动校验；错误指纹分别验证会拒绝。实施和风险记录见 ADR-0018，操作见 `docs/release.md`。
- 新证书与旧版不同：2.0.0 不能直接覆盖旧签名的已安装版本。用户数据迁移尚未实现或验证，不卸载设备应用。

## 当前任务：U-04A 来源管理与即时同步

- 漫画源管理默认显示已安装列表，提供发现来源、手动安装和文件导入入口；已安装条目突出启用状态和设置操作。
- 来源安装、覆盖安装、启停和移除成功后发出变更版本，搜索、探索、相关作品与详情页面刷新；同版本脚本覆盖也会使能力、详情和标签缓存失效。
- 账号有效性检测已在 U-11 完成；按漫画试搜来源目录已在 U-14 完成。详情页跳转到试搜并提供替换原来源的流程仍待处理。
- 页面交互与实时来源行为尚未在用户设备验证。

## 当前状态

- Venera Native 是兼容 Venera 漫画源生态的非官方 Android 原生阅读器。模块职责见 [架构文档](ARCHITECTURE.md)。
- 应用版本：2.6.4（versionCode 2006004），对应 U-18；2.6.3 是上一个正式发布版本。
- 上一功能交付：1.9.11 应用内更新。正式 APK 构建和签名证书核对通过；Release Lint 报告 13 项依赖有新版可用，依赖升级留待单独任务。
- 来源兼容性：支持二进制响应、图片 Hook URL 改写和有序章节结果；飞翔实时封面检测及相关 Android UI 未单独复验。
- 应用内更新的设备端完整升级、授权返回及后台下载行为仍需设备复验；不能将构建或 JVM 测试等同于真机验收。
- 再漫画多个作品详情失败的原因尚未确认；之前的 Debug 诊断与结果可在 Git 历史查阅。是否继续排查由用户指定。

## U-07 验证

- JDK 17：`.\gradlew.bat --no-daemon --no-configuration-cache --max-workers=2 :feature:sources:testDebugUnitTest --tests '*SourcesViewModelTest' :app:assembleDebug :app:assembleRelease` — BUILD SUCCESSFUL；16 项 SourcesViewModel 测试通过，Release Lint Vital 与 APK 签名校验通过；包含目录恢复与失败回退专项回归。
- 未安装设备 APK，未执行设备重启验收；签名沿用 2.0.1 的正式证书。

## U-08 验证

- JDK 17：`.\gradlew.bat --no-daemon --no-configuration-cache --max-workers=2 :feature:sources:testDebugUnitTest --tests '*SourcesViewModelTest' :app:assembleDebug :app:assembleRelease` — BUILD SUCCESSFUL；18 项测试通过，Release Lint Vital 与 Release APK 签名检查通过。
- `apksigner verify --print-certs` 与 `signing/release-cert.sha256` 指纹一致；`aapt dump badging` 确认 `dev.veneranative`、2.1.0 / 2001000。
- APK 已附于 GitHub Release；未安装到真机，等待用户验证历史切换体验。

## U-09 验证

- JDK 17：`:data:source:testDebugUnitTest --tests '*SourceRepositoryTest' :feature:sources:testDebugUnitTest --tests '*SourcesViewModelTest' :app:assembleDebug` — BUILD SUCCESSFUL；14 项来源仓库测试与 19 项来源管理测试通过。
- JDK 17：`:app:assembleDebug :app:assembleRelease` — BUILD SUCCESSFUL，Release Lint Vital 和 APK 签名校验通过。
- `apksigner verify --print-certs` 指纹与 `signing/release-cert.sha256` 一致；`aapt dump badging` 确认 `dev.veneranative`、2.2.0 / 2002000。
- APK 已附于 GitHub Release；未安装到真机，等待用户验收更新提示和更新操作。

## U-10 验证

- JDK 17：`:data:source:testDebugUnitTest --tests '*SourceCatalogParserTest' --tests '*SourcePackageStoreTest' --tests '*SourceRepositoryTest' :feature:sources:testDebugUnitTest --tests '*SourcesViewModelTest' :feature:sources:compileDebugAndroidTestKotlin :app:assembleDebug` — BUILD SUCCESSFUL；相关单测共 48 项通过，Compose 页面测试源码编译通过。
- JDK 17：`:data:source:testDebugUnitTest --tests '*SourcePackageStoreTest'` — BUILD SUCCESSFUL；新增的旧索引/目录字段兼容断言通过。
- JDK 17：`:app:assembleRelease` — BUILD SUCCESSFUL；Release Lint Vital、构建前身份检查和 APK 签名检查通过。
- `apksigner verify --print-certs` 指纹与 `signing/release-cert.sha256` 一致；`aapt dump badging` 确认 `dev.veneranative`、2.3.0 / 2003000。
- APK 已附于 GitHub Release；未安装到真机，等待用户验收目录添加、切换和同 ID 来源操作。

## U-06 验证

- JDK 17：`.\gradlew.bat --no-daemon --no-configuration-cache --max-workers=2 :data:backup:testDebugUnitTest --tests '*GitHubDeviceFlowTest' :feature:profile:testDebugUnitTest --tests '*ProfileViewModelTest' :app:assembleDebug :app:assembleRelease` — BUILD SUCCESSFUL；5 项测试通过，Release Lint Vital 与最终 APK 签名检查通过。
- 真机 25128PNA1C 保留数据从 2.0.0 覆盖安装 2.0.1 成功；用户反馈 GitHub 连接已恢复。设备原始应用及数据均保留。

## U-05 验证

- JDK 17：`.\gradlew.bat --no-daemon --no-configuration-cache --max-workers=2 :app:assembleDebug :app:assembleRelease` — BUILD SUCCESSFUL；包括新签名身份校验、Release Lint Vital 和最终 APK 签名校验。
- 默认启用配置缓存的 `:app:assembleRelease` 亦 BUILD SUCCESSFUL；签名验证任务主动禁用配置缓存并导致缓存条目丢弃，避免复用签名状态。
- 临时错误指纹使 `:app:verifyReleaseSigningIdentity`、`:app:verifyReleaseApk` 均按预期失败；随后恢复正确指纹。
- 从私有库在隔离目录全新解密恢复并逐文件核对一致；目标存在不同文件时拒绝覆盖。
- `apksigner verify --print-certs` 与公开指纹一致；`aapt dump badging` 确认应用 ID `dev.veneranative`、2.0.0 / 2000000；私有库可见性确认为 PRIVATE。
- Mac 尚未实际执行恢复；未安装设备 APK，也未进行旧签名数据迁移验证。

## U-04A 验证

- JDK 17：`.\gradlew.bat --no-daemon --max-workers=2 :data:source:compileDebugUnitTestKotlin :data:comic:compileDebugUnitTestKotlin :feature:search:compileDebugUnitTestKotlin :feature:explore:compileDebugUnitTestKotlin :feature:sources:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleRelease` — BUILD SUCCESSFUL，含 Release Lint Vital。
- 新增测试覆盖来源成功与失败操作的变更通知、同版本覆盖的缓存失效、搜索和探索页面的来源列表刷新；测试源码已编译，未执行。
- `apksigner verify --print-certs` 通过，证书与既有正式签名一致；`aapt dump badging` 确认包名 `dev.veneranative`、版本 1.10.0 / 1010000。
- `git diff --check` — PASS；未安装或执行真机验证，等待用户验收。
