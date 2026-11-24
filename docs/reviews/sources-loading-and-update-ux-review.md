# 漫画源「加载 / 更新」页面交互评审（2026-11-24）

## 范围与依据

- 本轮只做设计评审与方案，未改代码、未升版本、未提交。
- 阅读入口：`feature/sources/.../SourcesScreen.kt`、`SourcesUiState.kt`、`SourcesViewModel.kt`、`ComicSourceSearchScreen.kt`、`SourceConfigurationDialog.kt`；数据层失败分类见 `data/source/.../SourceCatalogRepository.kt`（`SourceCatalogFailure`）与 `SourceRepository.kt`（`SourceInstallError`）。
- 项目当前配色是 Material 3 默认 `lightColorScheme/darkColorScheme`（`core/designsystem/Theme.kt`），无自定义主色阶。下列 token 用 Compose 的 `MaterialTheme.colorScheme` / `typography` 表达，不引入新色值。

## 现状结论

两条链路都能工作，但有三个共性问题让页面显得「不好用」：

1. **状态提示与操作分离**：能看到「更新 N」，却要逐个展开卡片才能更新；能看到失败文字，却拿不到失败原因。
2. **加载时整块替换**：`SourcesStatus.Loading` / `CatalogStatus.Loading` 用居中转圈替换整个列表，已有内容先消失再回来，产生跳动。
3. **全局单一布尔表示并发**：`installing` 一个布尔同时代表安装、更新、切换，任一进行时会禁用页面所有来源的按钮，反馈粒度错误。

## 问题清单（按影响排序）

| # | 领域 | 现状 | 影响 | 建议 |
| --- | --- | --- | --- | --- |
| P1 | 更新可达性 | 顶部 chip 显示「更新 N」，更新按钮却只在 `SourceRow` 的 `expanded` 分支里（SourcesScreen.kt:527-536） | 用户看到数字却找不到入口 | 卡片主行直接放「更新」；顶部增加「N 个来源有更新 + 全部更新」横幅 |
| P1 | 加载反馈 | Loading 分支用全宽居中 `CircularProgressIndicator` 替换整个列表（:299-306、:351-358） | 内容闪烁、位置丢失、无法取消 | 有旧数据时保留列表 + 顶部细 `LinearProgressIndicator`；仅首次无数据时用骨架行 |
| P1 | 失败原因丢失 | `SourceCatalogResult.Failure(reason)` 在 ViewModel 被丢弃，只置 `catalogStatus = Failed`（SourcesViewModel.kt:384-386） | 网址错/断网/目录格式错都显示同一句话 | 在 UiState 增加 `catalogFailure: SourceCatalogFailure?`，映射三条可操作文案 |
| P2 | 更新版本信息 | 更新按钮只写「更新 x.x」（:532），不显示当前版本 | 不知道从哪个版本升到哪个 | 显示 `1.2.3 → 1.2.4`，用 `typography.labelSmall` + `onSurfaceVariant` 做前值 |
| P2 | 并发粒度 | `installing` 为全局布尔（UiState.kt:18），所有来源按钮共用 | 更新一个源，其他源全部禁用并显示「更新中…」 | 复用已有的 `busySourceIds: Set<SourceId>`，把安装/更新改成按源粒度 |
| P2 | 空态重复 | 空态同时有长文案和「发现漫画源」按钮，而顶部已有「发现来源」chip（:367-371） | 两个入口指向同一处 | 空态只留一句短文案 + 一个主按钮；或去掉按钮，让 chip 承担 |
| P3 | 手势发现性 | 长按卡片进入排序（:506），无视觉提示 | 隐藏交互，用户不知道可排序 | 在设置展开区加显式「调整顺序」入口，长按降为快捷方式 |
| P3 | 无障碍 | 登录异常仅用红色 `!` 表示（:513-516），无 contentDescription | 违反「不以颜色单独传达信息」WCAG 1.4.1 | 改为 `Icon(..., contentDescription = "登录状态异常")` + 相邻文字 |
| P3 | 进度语义 | 搜索结果每行 `LinearProgressIndicator`（ComicSourceSearchScreen.kt:187）无进度语义 | 读屏用户无法感知进行中 | 补 `Modifier.semantics { progressBarInfo = ... }` 或至少 `contentDescription` |

## 建议改动（可直接落地）

### 1. 失败原因映射（收益最高、改动最小）

`SourcesUiState` 增加字段，`refreshCatalog()` 保留 reason：

```kotlin
val catalogFailure: SourceCatalogFailure? = null
```

```kotlin
is SourceCatalogResult.Failure -> _state.update {
    it.copy(catalogStatus = CatalogStatus.Failed, catalogFailure = result.reason)
}
```

`CatalogContent` 的 `Failed` 分支按 reason 给不同文案：

```kotlin
CatalogStatus.Failed -> Column(/* 居中、spacedBy(8.dp) */) {
    Text(
        when (state.catalogFailure) {
            SourceCatalogFailure.InvalidLocation -> "目录地址不是有效的 https 链接。"
            SourceCatalogFailure.Network -> "网络无法连接目录服务器，请检查网络后重试。"
            SourceCatalogFailure.InvalidCatalog -> "目录内容格式不正确，请切换到其他目录。"
            null -> "目录加载失败，请重试。"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { onAction(SourcesAction.RefreshCatalog) }) { Text("重试") }
    TextButton(onClick = onSwitchCatalog) { Text("切换目录") } // 新增回调
}
```

发布说明里那句「拷贝漫画接口全挂、换线无效」正属于 `Network` 类；如果后续要做「服务端不可用」的专门提示，可在这里加一个 `CatalogFailure` 枚举值，不必塞进文案字符串。

### 2. 更新入口提到卡片主行

把 `SourceRow` 的更新按钮从 `expanded` 分支移到主行，并补版本变化；设置区保留开关、移除、配置：

```kotlin
Row(modifier = Modifier.fillMaxWidth().combinedClickable(...)) {
    Column(Modifier.weight(1f)) {
        Text(source.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (update != null) "${source.version} → ${update.version}"
                else "版本 ${source.version}",
                style = MaterialTheme.typography.labelSmall,
                color = if (update != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (update != null) {
        TextButton(
            onClick = onUpdate,
            enabled = !busy && !installing,
            modifier = Modifier.testTag("source-update-${source.sourceId.value}"),
        ) { Text(if (busy) "更新中…" else "更新") }
    }
    Text(if (expanded) "收起" else "设置", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
}
```

顶部 chip 文案去掉「更新 N」的歧义，或让 chip 点击后滚动到第一个可更新项；更直接的做法是增加一条横幅：

```kotlin
if (state.catalogUpdateCount > 0 && section == SourcesSection.Installed) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${state.catalogUpdateCount} 个来源有更新",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            TextButton(onClick = { /* 逐个更新，见下 */ }) {
                Text("全部更新", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
```

「全部更新」当前没有现成 action，需要新增 `SourcesAction.UpdateAll`；ViewModel 按 `catalogUpdateFor` 收集 entries，串行调用 install，避免并发把全局 `installing` 打乱。若暂不实现批量，宁可先只放横幅提示而不放按钮，也不要按钮点了没反应。

### 3. 加载态保留已有内容

把 `SourcesStatus.Loading` 和 `CatalogStatus.Loading` 从「替换列表」改为「列表上方叠加细进度条」：

```kotlin
SourcesSection.Available -> {
    CatalogLocationHeader(/* ... */)
    if (state.catalogStatus == CatalogStatus.Loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    // CatalogContent 的 Loading 分支仅在 catalogEntries 为空时显示居中转圈
}
```

`CatalogContent` 改：

```kotlin
CatalogStatus.Loading -> if (state.catalogEntries.isEmpty()) {
    Column(/* 居中 */) {
        CircularProgressIndicator(Modifier.testTag(CATALOG_LOADING_TAG))
        Text("正在加载漫画源目录…")
    }
} else {
    // 继续渲染已有 entries；顶部细进度条表达刷新中
    CatalogEntryList(state, onAction, onInstalledSection)
}
```

已安装列表同理。首次进入仍用转圈/骨架，刷新则原地更新，消除跳动。

### 4. 并发粒度（配合 P2）

`install()` 增加可选 `sourceId`，进入时把 id 加入 `busySourceIds`，结束时移除；UI 用 `busySourceIds` 而非全局 `installing` 控制单个来源的按钮。全局 `installing` 只保留给「手动安装」输入框那一条，因为它确实是一次一个。

### 5. 无障碍与微交互（收尾）

- 登录异常：`Icon(Icons.Filled.Warning, contentDescription = "登录状态异常", tint = MaterialTheme.colorScheme.error)`，旁边补 `Text("登录异常", style = labelSmall)`，不靠红色 `!` 单独传达。
- 排序：在设置展开区加 `TextButton(onClick = onStartReorder) { Text("调整顺序") }`，长按保留为快捷方式。
- 搜索进度：给每行的 `LinearProgressIndicator` 加 `Modifier.semantics(mergeDescendants = true) { contentDescription = "正在搜索 $name" }`（需要 import `androidx.compose.ui.semantics.contentDescription` / `semantics`）。
- 按钮状态色只作为辅助；禁用态配合「更新中…」「安装中…」文字，不靠颜色单独区分。
- 动效：跨页面/横幅出现用 `animateAppear` 的 `opacity` + `translateY` 8.dp，时长 200-250ms；遵守 `prefers-reduced-motion`。Material 3 Compose 可用 `androidx.compose.animation.animateContentSize()`，避免自写动画。

## 设计 token（与现有主题对齐）

| 用途 | Compose token |
| --- | --- |
| 页面背景 | `MaterialTheme.colorScheme.background` |
| 卡片 | `surfaceContainerLow` / `shapes.large` |
| 更新横幅 | `primaryContainer` + `onPrimaryContainer` |
| 版本号前值 | `labelSmall` + `onSurfaceVariant` |
| 版本号新值 / 更新入口 | `labelSmall` / `labelMedium` + `primary` |
| 失败文字 | `bodyMedium` + `onSurfaceVariant`（错误语义用 `error` 仅用于图标/强调） |
| 间距 | 4 / 8 / 12 / 16 dp（8dp 基线） |

## 无障碍检查清单

- [ ] 所有进度状态有文字或 contentDescription，读屏可感知
- [ ] 登录异常不只靠颜色/符号
- [ ] 「全部更新」「更新」有可访问标签，禁用态有原因文字
- [ ] 点击目标不小于 48.dp（当前 FilterChip / TextButton 需复核高度）
- [ ] 焦点顺序：顶栏 → 更新横幅 → 标签 → 卡片，逻辑连贯
- [ ] 骨架/进度动画遵守 reduced motion

## 优先级建议

1. **先做失败原因映射（P1，改动小、见效快）**：直接解决「加载失败到底是哪里错了」。
2. **再做更新入口上提 + 版本变化文案（P1）**：解决「看到更新数字找不到按钮」。
3. **然后改加载保留内容（P1）**：解决闪屏跳动。
4. **最后处理并发粒度、批量更新与无障碍（P2/P3）**：批量更新需要新 action 与测试，单独一个任务更稳妥。

以上每一项都可独立成一个任务；本次评审不含代码改动，等你指定要先做哪几项再实施。

## 2026-11-24 追加：设置项展示逻辑（用户参考原项目截图）

用户反馈「设置项又多又乱」，并给出原项目（Venera）漫画源设置页截图：所有来源的设置平铺在同一张纵向列表里，来源之间用细分割线分组；每个设置项都是统一的「左标题 / 右控件」行；账号动作收敛成「重新登录」「注销」两行。

### 现状的三个结构问题

1. **嵌套层级过深**：`SourcesScreen` 的卡片默认折叠，点「设置」后才展开 `SourceConfigurationPanel`，而面板内部又有一个 `heightIn(max = 520.dp).verticalScroll()`。外层 `LazyColumn` + 内层滚动 + 多段标题（"XX 设置" / "账号" / "来源设置"），同一屏里滚动容器和标题层级都重复。
2. **控件形态不统一**（`SourceSettingControl`，SourceConfigurationDialog.kt:197-235）：`INPUT` 是整行 `OutlinedTextField`，`SELECT` 是「标题：值」的 `TextButton`，`SWITCH` 是文字+开关行，`CALLBACK` 是动作按钮。四种类型四种长相，扫描成本很高。
3. **账号动作堆成会话区**：登录态说明、账号/密码输入、登录按钮、重新登录、更换账号信息、检测登录状态、退出登录、注册账号——最多 7 个按钮纵向堆叠，和来源设置混在一个滚动区。

### 架构约束（决定了不能照搬）

`SourceRepository.configurationLocked()`（SourceRepository.kt:387）每次读取都会：
- `metadataReader.read(script)`：`QuickJsMetadataReader.read()` 新建一个临时 QuickJS 引擎、执行整段脚本来读 metadata；
- `invokeInternal(sourceId, "__veneraIsLogged")`：在运行时再执行一次 JS 调用。

即**每读一个来源的配置就要跑两次脚本**，并且引擎读取串行（`mutex.withLock`）。原项目之所以能一屏平铺所有来源设置，前提是设置定义/登录态已有廉价缓存；当前代码没有这层缓存。若直接在首次进入时对所有来源调用 `configuration()`，N 个来源会带来 N 次引擎创建 + 2N 次脚本执行，页面会明显卡顿。

### 方案 A（推荐先做）：保留按需加载，只把单来源设置页做平铺

不动数据层，把「展开面板」改成独立设置页面/整块区域，去掉内部嵌套滚动，然后按原项目规则统一行：

- 进入某个来源设置时才加载该来源配置（沿用现有 `OpenConfiguration`），页面自己占满可用高度，不再嵌 520dp 滚动区。
- 所有设置项统一成一个 `SettingRow(title, subtitle?, control)`：`INPUT` 显示为「标题 + 当前值 + 铅笔」，点击就地切换为输入框；`SELECT` 显示为「标题 + 下拉框（当前值）」；`SWITCH` 为「标题 + 开关」；`CALLBACK` 为「标题（+说明）+ 动作图标」。四种类型一套行高、一套对齐。
- 账号区收敛为两行：`重新登录`（副标题「登录已过期时点此」）+ `注销`；首次登录/更换账号点 `重新登录` 后弹出登录表单（`OutlinedTextField` + 登录按钮）；`检测登录状态` 的结果作为 `重新登录` 行的副标题或行尾状态图标，不再单独占一个按钮；注册链接放在行尾。
- 保存动作：`INPUT` 就地输入结束后保存，或保留一个底部「保存设置」按钮，但**只在本页出现一次**（当前 `MessageRow` 在页面顶部和设置面板里各出现一次，重复）。

### 方案 B（彻底对齐原项目）：先加配置缓存，再做全列表平铺

新增一层「来源设置摘要」缓存（metadata 里的 `settings` 定义 + 保存的取值，登录态单独按需刷新），在来源安装/更新/保存设置时写入，`SourcesScreen` 进入时只读缓存即可平铺全部来源。`configuration()` 不再为渲染而跑脚本，只在用户真正操作登录态时调用。改动涉及数据层、状态模型和安装/更新链路，工作量大，建议单独一个任务，不要在页面重构里顺带做。

### 建议

先做方案 A：视觉和交互对齐原项目（统一行、账号收敛、去掉嵌套滚动），但保留「按来源加载」以避免脚本开销；等实测确认进入页面不卡，再评估方案 B 的缓存层。

### 追加的无障碍项

- 每个 `SettingRow` 的控件要有 `contentDescription`（尤其图标化的 `INPUT` 铅笔、`CALLBACK` 动作）。
- 「登录已过期」「检测失败」这类状态不能只靠颜色/图标，要有文字副标题。
- 就地编辑的输入框获得焦点时保持可见，避免被系统键盘遮挡后用户不知道在编辑哪一项。

## 2026-11-24 再追加：方案 A 的结构修正（用户反馈「来源与设置混在一起」）

用户对方案 A 的「全部平铺」提出异议：所有来源和设置项压在同一层，**看不出哪个是来源、哪个是设置项**，分区不清楚，操作也不方便。这个反馈成立——原项目截图之所以够用，是因为它把「来源」当分组头、设置项缩进在组内；单纯拉平会把两级信息压成一级。修正如下。

### 核心原则：用结构分区，不靠颜色分区

颜色只做辅助强调，分区必须靠层级结构本身表达。三个结构性手段：

1. **来源即分组头（Section header）**：每个来源是独立的 `Card`，整行可点；行内固定「展开箭头 ▾/▸ + 来源名 + 版本/更新/启用状态」。未展开时就是一个清晰的来源清单，一眼扫完有哪些来源。
2. **设置项缩进从属（Indented group body）**：只有展开的来源才显示设置，且设置区相对来源头**左缩进**并加一条 2dp 的左侧引导线（`verticalIndicator`），视觉上明确「这些设置属于上面这个来源」。
3. **手风琴：一次只展开一个**：`expandedSourceId` 从集合改成单个 id，展开 A 自动收起 B。页面始终只有一组设置展开，避免多来源设置同时铺开再次造成混淆，也把内部滚动需求降到最低。

### 这版方案相对当前代码改了什么

| 维度 | 当前 | 修正后 |
| --- | --- | --- |
| 来源头 | 「设置/收起」文字按钮，状态藏展开区里 | 箭头 + 来源名 + 版本 + 更新/启用状态直接在头行；整行可点 |
| 展开区 | 卡内 520dp 嵌套滚动，账号+设置混排 | 无内部滚动，缩进 + 左侧引导线；账号区与设置区各一个小标题分隔 |
| 同时展开 | `expandedSourceIds` 可多个 | `expandedSourceId` 单个，手风琴互斥 |
| 更新入口 | 藏在展开区 | 提到来源头行，有更新时头行直接显示「有更新」并可点更新 |
| 设置行形态 | 四种四种长相 | 统一 `SettingRow(title, subtitle?, control)`（见方案 A） |

### 与架构约束的兼容

继续沿用方案 A 的「按需加载」：只有被展开的来源才调用 `configuration()`，未展开的来源头只读 `InstalledSource` 本地字段（名称、版本、启用、更新角标都来自本地状态，不需要跑脚本）。因此手风琴结构**不增加任何脚本执行**，反而因为一次只展开一个，把原来的多次并发配置读取降到最多一次。方案 B 的缓存层仍是可选后续，不阻塞这版。

### 可落地的 Compose 骨架

```kotlin
// SourcesUiState: val expandedSourceId: String? = null  （替换原 expandedSourceIds 集合）

@Composable
fun SourceGroupCard(
    source: InstalledSource,
    update: SourceCatalogEntry?,
    expanded: Boolean,
    configuration: InstalledSourceConfiguration?,   // 仅 expanded 时非 null
    state: SourcesUiState,
    onToggle: () -> Unit,                            // 手风琴：Viewmodel 里设置 expandedSourceId = id
    onAction: (SourcesAction) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        // 分组头：整行可点，状态一目了然
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .background(if (expanded) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▾" else "▸")
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.titleMedium)
                Text("版本 ${source.version}", style = MaterialTheme.typography.labelSmall)
            }
            if (update != null) UpdateBadge(update.version, onClick = { /* 更新 */ })
            Text(if (source.enabled) "已启用" else "已停用", style = MaterialTheme.typography.labelSmall)
        }

        // 分组体：缩进 + 左侧引导线，明确从属于来源头
        if (expanded && configuration != null) {
            Row(modifier = Modifier.padding(start = 20.dp, end = 12.dp, bottom = 12.dp)) {
                Box(
                    Modifier.width(2.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountSection(configuration, state, onAction)   // 收敛为两行，见方案 A
                    SettingsSection(configuration, state, onAction)  // 统一 SettingRow
                    SaveSettingsButton(state, onAction)              // 全页仅一处
                }
            }
        }
    }
}
```

无障碍：展开箭头用 `contentDescription`（「展开/收起 XX 的设置」），头行 `semantics(mergeDescendants = true)` 作为一个整体读屏单元；更新角标不能只靠颜色，带「有更新」文字。

### 修正后的建议

先做这版「分组手风琴 + 统一 SettingRow + 账号收敛」（结构解决分区，样式解决简洁），它同时回应了「分区不清」「层级混淆」「操作不便」三个反馈，且完全复用现有按需加载、不碰数据层。方案 B 的缓存层仅在用户明确要求「一屏平铺所有来源设置」时再做。

## 2026-11-24 终版：v2（建议定稿，替换上面的 v1 结构）

用户追问「这是最终方案吗，感觉还能优化」。以评审视角复查，v1 有两个**可用性问题**（非审美）：

1. **头行点击冲突**：v1 分组头整行 `clickable(onClick = onToggle)`，但头行里又有启用 `Switch`、更新按钮。一个手势承载两种意图，`Switch` 的点击还会冒泡触发展开/收起；而且 v1 去掉了「设置」文字后，用户不知道展开后是什么，可发现性下降。
2. **多 Card 表达分区，信号弱、噪音大**：一堆独立卡片堆叠，分区靠卡片间隙表达，既占空间又不像原项目「单列表 + 分组」清晰。

### v2 的三处结构性改动

**改动 1：多卡片 → 单列表 + 强分组头。** 所有来源放进一张连续列表，来源之间用细分割线；分组头用 `surfaceContainerLow` 淡色底 + 加粗来源名，视觉重量明显高于下面的设置行。分区靠「头的视觉重量 + 设置项缩进」表达，而不是靠一堆 Card 边框。这既解决「分区不清」，又比 v1 省空间。

**改动 2：拆分头行点击热区（关键）。** 头行分成左右两块独立可点区域：

- 左侧「箭头 + 来源名 + 版本」→ 只管展开/收起，`Modifier.clickable(onClick = onToggle).semantics { contentDescription = "展开/收起 XX 设置" }`，箭头带旋转动画；
- 右侧「状态/开关/溢出菜单」→ 独立控件，不落在展开热区里。`Switch` 用 `Modifier.clickable { }` 消费点击阻止冒泡，或把 `onToggle` 只绑在左侧 `Row` 上（推荐后者）。

有更新时左侧显示「有更新」角标（带文字，不只靠颜色），右侧 `⋮` 溢出菜单收纳「更新 / 移除 / 检测登录」等低频操作。启用开关是高频操作，直接放右侧常驻。

**改动 3：顶部批量更新横幅。** 有 ≥2 个来源可更新时，列表顶部显示 `N 个来源有更新 · 全部更新`，与前面评审的「更新入口上提」合并落地。

### v2 Compose 骨架（替换 v1 的 SourceGroupCard）

```kotlin
@Composable
fun SourceGroupHeader(
    source: InstalledSource,
    update: SourceCatalogEntry?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onAction: (SourcesAction) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {

        // —— 左侧：唯一的展开热区 ——
        Row(
            modifier = Modifier.weight(1f)
                .clickable(onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    contentDescription = "${if (expanded) "收起" else "展开"}${source.name}的设置"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val rotation by animateFloatAsState(if (expanded) 180f else 0f)
            Icon(Icons.Filled.ExpandMore, contentDescription = null,
                modifier = Modifier.rotate(rotation))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(source.name, style = MaterialTheme.typography.titleMedium) // 加粗、 ellipsis
                Text("版本 ${source.version}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // —— 右侧：独立操作区，不触发展开 ——
        if (update != null) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small, modifier = Modifier.padding(end = 8.dp)) {
                Text("有更新", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Switch(checked = source.enabled, onCheckedChange = onEnabledChange)
        Box { // ⋮ 溢出菜单：更新 / 移除 / 检测登录
            IconButton(onClick = { /* expand menu */ }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "${source.name}的更多操作")
            }
            // DropdownMenu(...)
        }
    }
}
```

分组体（展开后）沿用「缩进 + 2dp 左侧引导线」，里面是 `AccountSection` + `SettingsSection`（统一 `SettingRow`）+ 全页唯一的 `SaveSettingsButton`。

### 微交互参数

- 箭头旋转：`animateFloatAsState` 0°↔180°，250ms，`FastOutSlowInEasing`；仅动画 `transform`。
- 展开/收起内容：用 `androidx.compose.animation.AnimatedVisibility` + `expandVertically`/`shrinkVertically`，220ms；遵守 `prefers-reduced-motion`（系统开启时退化为瞬时切换）。
- 分组头按压态：`surfaceContainerLow` → `surfaceContainerHigh`，150ms，无缩放（列表项不做 1.05 放大，避免整行跳动）。
- 开关、菜单按钮点击目标 ≥48dp。

### 相对 v1 的净改动小结

| 维度 | v1 | v2 |
| --- | --- | --- |
| 容器 | 每源一张 Card | 单列表 + 细分割线 + 淡色分组头 |
| 头行热区 | 整行点击（与开关冲突） | 左侧展开、右侧操作，两个独立热区 |
| 可发现性 | 无「设置」字样 | 箭头语义 + contentDescription + 有更新角标 |
| 低频操作 | 平铺在展开区 | 收进 ⋮ 溢出菜单 |
| 批量更新 | 无 | 顶部「N 个有更新 · 全部更新」横幅 |
| 动手脚的模块 | 仅 feature/sources UI + ViewModel 状态 | 同左，仍不碰数据层配置读取 |

架构约束不变：配置仍按需加载（只展开的那个来源调用 `configuration()`），方案 B 缓存层仍是可选后续。这版是建议定稿；若认可即可按「分组头热区拆分 → 单列表改版 → SettingRow 统一 → 批量横幅」顺序实施，逐项提交。
