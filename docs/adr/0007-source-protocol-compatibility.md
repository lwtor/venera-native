# ADR-0007：Venera 漫画源协议兼容范围

- 状态：Accepted（已核对 `js_api.md` 与真实源实现；剩余待确认项见第 4.4 节）
- 日期：2026-09-20
- 决策者：项目维护者
- 关联任务：S1-01、S1-02、S1-03、S1-04
- 相关：ADR-0008（异步 Host API 传输）

## 1. 上下文

本项目的产品前提是兼容既有 Venera 漫画源生态。上游把源定义为一个 JavaScript 类：

```javascript
class MySource extends ComicSource { ... }
```

类通过仓库 JSON 列表分发（`name` / `url` / `filename` / `version` / `description`），源类本身声明
`name`、`key`、`version`、`minAppVersion`、`url`，可选 `init()`，其余成员按能力分组。

如果不先划定兼容范围，实现会退化成“照抄上游全部成员”，把账号、评论、收藏、WebView 登录等
Stage 3/4 的内容提前拖进 Stage 1。

上游协议的结构、分页与筛选语义已经核对（`venera-app/venera` 的 `doc/comic_source.md`），
但其中的数据类型字段清单在同仓库的 `js_api.md` 里，尚未核对；第 4 节列出由此产生的待确认项。

## 2. 决策

### 2.1 Stage 1 承诺兼容的协议子集

| 上游成员 | Stage 1 | 说明 |
| --- | --- | --- |
| 类基础字段（`name`/`key`/`version`/`minAppVersion`/`url`） | 是 | 安装与展示依赖这些字段 |
| `init()` | 是 | 加载来源后、首次调用前执行 |
| `explore` | 是 | 仅 `multiPartPage`、`multiPageComicList`、`mixed` 三种 type |
| `search` | 是 | `load` 与 `loadNext`，含 `optionList` 筛选 |
| `comic.loadInfo` | 是 | 详情，章节列表随该响应返回（`ComicDetails.chapters`） |
| `comic.loadEp` | 是 | 章节页图片列表 |
| `category` / `categoryComics` | 否（Stage 2+） | 分类与排行榜 |
| `account` | 否（Stage 3+） | 登录、Cookie 校验、WebView 登录 |
| `favorites` | 否（Stage 3+） | 网络收藏夹 |
| `comic.loadComments` 等评论族 | 否（Stage 4） | 评论与回复 |
| `comic.starRating`、`likeComic`、`voteComment`、`likeComment` | 否（Stage 4） | 社交互动 |
| `settings`、`translation` | 否（后续） | 源设置与词条 |
| `comic.loadThumbnails`、`onImageLoad`、`onThumbnailLoad`、`link`、`idMatch` | 否（Stage 2+） | 与图片管线、深链相关 |
| `comic.onClickTag`、`enableTagsTranslate`、`enableTagsSuggestions` | 否 | 标签交互 |

### 2.2 分页语义按上游原样实现，不做“统一抽象”

上游存在两种互斥的分页形态，比例语义还不一致：

| 场景 | 形态 | 语义 |
| --- | --- | --- |
| `explore.multiPageComicList`、`categoryComics` | 页码 | 1-based |
| `explore.mixed` | 页码 | 0-based 索引 |
| `search`、`favorites`、`explore.multiPageComicList` 的 `loadNext` | 游标 | `next === null` 表示首页；返回 `next` 为 null 表示没有下一页 |

规则：**只要源实现了 `load`，`loadNext` 一律被忽略**。领域模型用 `PageCursor` 的
`Page` / `Token` 两种取值表达这两种形态，不把页码基准差异写进模型——基准属于源适配层。
“没有下一页”在模型里统一表达为 `next == null`。

### 2.3 筛选值按上游语义编码

| 上游 type | 选择数量 | 传给源的 `options` 形态 |
| --- | --- | --- |
| `select` | 恰好 1 个 | 字符串 |
| `multi-select` | 多个或 0 个 | **数组序列化后的 JSON 字符串** |
| `dropdown` | 至多 1 个 | 字符串；未选择时为 `null` |

领域模型提供 `FilterValue`（`Single` / `Multiple` / `Unselected`）表达这三种语义，
具体的 JSON 编码由源适配层完成。**不允许把 `multi-select` 简化成原生数组**，那会直接破坏兼容性。

### 2.4 兼容性测试要求

- 协议语义测试必须覆盖：页码形态最后一页的游标、游标形态的首页与末页、`load` 与 `loadNext`
  的优先级、三种筛选值的编码形态。
- 测试只用仓库内的合成源，不依赖真实站点或真实账号。
- 上游文档更新时，以文档为准并同步本节表格。

## 3. 后果

正面：

- Stage 1 的范围有明确边界，不会提前吞下账号、评论与收藏。
- 分页与筛选这两处最容易被“统一抽象”破坏的语义被显式固定下来。

代价：

- Stage 1 结束后，只有实现了子集内成员的源才能完整使用；使用 `category` 或 `favorites` 的源
  会出现能力缺失，产品上必须以“能力不可用”而不是“源不可用”呈现。
- 两套分页语义会一直存在于适配层，不能合并。

## 4. 字段级确认

已核对 `js_api.md`（2026-09-20），确认以下内容：

### 4.1 `Comic` 与 `ComicDetails`

`Comic`：`id`、`title`、`subtitle`/`subTitle`、`cover`、`tags`、`description`、`maxPage?`、
`language?`、`favoriteId?`（仅收藏页来源）、`stars?`。

`ComicDetails`：`title`、`subtitle`、`cover`、`description?`、`tags`（`Map<string, string[]>`）、
**`chapters`（`Map<chapterId, chapterTitle>`）**、`isFavorite?`、`subId?`、`thumbnails?`、
`recommend?`、`commentCount?`、`likesCount?`、`isLiked?`、`uploader?`、`updateTime?`、
`uploadTime?`、`url?`、`stars?`、`maxPage?`、`comments?`。

由此确定两条实现约束：

1. **章节列表与详情同源**：章节随 `loadInfo` 的响应返回，不需要也不能假设一次额外的调用。
   章节顺序由源决定（`Map` 迭代序），App 不得重排。
2. `subtitle` 与 `subTitle` 是同一字段的两种写法，解析时必须都接受。

### 4.2 Host API

| 方法 | 参数 | 响应 `body` |
| --- | --- | --- |
| `Network.get` | `(url, headers)` | string |
| `Network.post` / `put` / `patch` | `(url, headers, data: ArrayBuffer)` | string |
| `Network.delete` | `(url, headers)` | string |
| `Network.fetchBytes` | `(method, url, headers, data: ArrayBuffer)` | **ArrayBuffer** |
| `Network.setCookies` / `getCookies` / `deleteCookies` | `(url, ...)` | void / Cookie[] |

所有网络方法返回 Promise，响应统一为 `{status, headers, body}`；非 2xx 不抛错，由脚本自行判断
`status`。

两条对本项目有直接影响的结论：

- **请求体是 `ArrayBuffer`**，与 ADR-0002 的实测结论一致：Host 桥必须支持二进制进出，
  Base64 通道不可用。
- 源与宿主的通用通信入口是 `sendMessage({method: ...})`，与 ADR-0003 的允许列表模型一致。

### 4.3 实现核对（2026-09-20，`venera-configs` 的 `manga_dex.js`）

以真实源为准核对后，得到若干与文档简化描述不同的事实。**这些才是协议 DTO 与 Host 桥的实现依据**：

| 事实 | 影响 |
| --- | --- |
| `loadInfo(id)` 单参数，返回 `ComicDetails` | 与文档一致 |
| `loadEp(comicId, epId)` 返回 `{images: string[]}`；`epId` 实际必填（空值抛错），`comicId` 在该源中未被使用 | Pages 按两参数调用；缺 `epId` 返回无效请求，而不是空列表 |
| **`chapters` 是嵌套结构**：顶层键是分组（如 `"Volume 1 - EN"`），值是章节 map；顶层还混有非章节的附加键（`latestChapterMarker`） | 解析必须只把对象值当分组、跳过标量附加键、保留两层顺序。模型侧由 `chaptersOf`（扁平）与 `groupedChaptersOf`（分组）分别覆盖 |
| **`search.load(keyword, options, page)` 的 `options` 是与 `optionList` 下标对齐的字符串数组**，`"any"` 表示不过滤 | 筛选值必须按声明顺序编码成数组，不能只发一个 map |
| `explore` 的 `viewMore` 是跳转指令对象（`{page, attributes}`），不是字符串 | Stage 1 视为不透明值，不解释、不跟随 |
| **网络主入口是全局 `fetch`**（`res.ok` / `res.json()` / `res.text()`），`Network.*` 只用于 cookie 等少量场景；POST 表单体用 `Convert.encodeUtf8()` | Host API 必须提供 `fetch` 兼容层；只做 `Network.get/post` 不足以运行真实源。**直接约束 S1-08 的引擎与桥设计** |
| 该源没有 `loadThumbnails`，也没有 `next` 字段，分页全部依赖 `maxPage` | 页码式是主路径，游标式仍需保留但属少数 |

### 4.4 源对象的注册与调用约定（2026-09-20，核对上游 `parser.dart` 与 `assets/init.js`）

上游宿主加载一个源脚本的完整约定，**逐条照抄而不是自创**，因为它决定了既有源能否不改一行就跑：

| 步骤 | 上游做法 |
| --- | --- |
| 找类 | 取**第一行** `trim()` 后以 `class ` 开头、原始行也以 `class ` 开头（**缩进声明直接判为 Invalid Content**）、且包含 `extends ComicSource` 的行；类名取 `class` 与 `extends ComicSource` 之间的文本 |
| 实例化 | 把**整段脚本文本**包进 IIFE：`(() => { <脚本> ; this['temp'] = new <ClassName>() }).call()` |
| 读元数据 | 从实例读 `name` / `key` / `version`（三者必填）/ `minAppVersion` / `url`；`key` 只允许 `[A-Za-z0-9_]+` |
| 注册 | `ComicSource.sources[key] = 实例`，此后一律通过该注册表调用成员 |
| 成员调用 | **路径**形式：`ComicSource.sources[key].comic.loadInfo(id)`、`.search.load(kw, options, page)`、`.explore[i].load(page)` —— 因此成员的 `this` 是**声明它的那个对象**，不是全局对象 |
| 探索页调用 | `explore[i].load(page)`：页面由**位置**标识，`load` 只收一个参数（页面号或 `null`）。把页面 key 当参数传会直接改变源的返回值——这是实现中实际踩到并修正的坑（见 §4.6） |
| `init()` | 只有存在时才调用；上游在解析完成后异步延迟 50ms 触发。本项目在首次真实来源调用前触发，结构能力探测不触发 |

基类 `ComicSource` 由宿主在 JS 侧提供（上游在 `assets/init.js`），成员包括身份字段、
`loadData` / `saveData` / `deleteData` / `loadSetting` / `isLogged` / `translate` 与静态 `sources` 注册表。

两条对本项目的直接结论：

- **成员是路径不是标识符**，引擎的调用包装必须按路径解析，且不能把 `this` 绑成全局对象——
  `SourceInvocationScript` 与它的测试按此实现。
- **缩进的类声明会被拒绝**，这是上游既有行为（他们的解析器同样拒绝）。本项目的错误信息显式说明
  “必须在行首声明”，避免使用者以为是我们的 bug。

与上游的一处有意差异：本项目不在来源安装和能力探测期间执行 `init()`，而是在首次真实来源调用前等待它，
并且每个来源独立初始化。这样搜索页只需实例化脚本和读取声明能力，不会因所有启用来源的可选网络探测而等待；
用户第一次实际搜索、打开详情或阅读时仍会执行该来源初始化。真实拷贝漫画源会在 `init()` 中尝试更新可选
搜索/API 地址；设备无法访问发现端点时仍可使用默认 API 配置。因此初始化失败按最佳努力处理，当前 QuickJS
包装会捕获任意 `init()` 异常以保留默认配置，尚未把网络失败与脚本缺陷分别呈现；诊断细化是已知限制。

`ComicSource` 基类有以下已知简化：`saveData` 只活到引擎实例销毁；`loadSetting` 读取对象形式声明的
`default`，以及来源初始化期间替换后的 string/number/boolean 标量，不保存用户选择值。持久化数据与来源设置
属于后续任务（见 §2.1）。

### 4.5 仍待确认

- `loadThumbnails` 的真实签名：该源未实现，需要再找使用它的源核对（影响 S1-05 多页缩略图）。
- `Comment` 与 `ImageLoadingConfig` 的字段（分别影响 Stage 3 评论与 S1-05 图片管线）。
- 错误约定：文档无专门章节，只在 `favorites` 系列出现“抛出字符串 `Login expired` 触发重登”；
  Stage 1 范围不涉及，记录备查。

### 4.6 拷贝漫画来源兼容验证（2026-10-02）

按用户要求选上游配置目录中的“拷贝漫画”验证真实闭环（当前脚本版本 1.4.2），实现只针对该源已经观察到的
契约调用，不将脚本复制进仓库：

| 观察到的调用 | 兼容实现 / 验证 |
| --- | --- |
| `Convert.decodeBase64` / `encodeBase64`、`hmacString(..., "sha256")`、`hexEncode` | QuickJS Host 兼容层同步实现；标准 HMAC-SHA256 向量与 Base64 往返测试通过 |
| `randomInt(min, max)` | 提供含上下界的整数 helper；确定性单值区间测试通过 |
| init 更新 `settings.base_url` 后再通过 `loadSetting` 取值 | `loadSetting` 保留来源运行时替换的标量值；JVM 回归通过 |
| init 阶段刷新可选搜索/API 地址 | 安装期 Host 网络失败返回 status 0；初始化异常按最佳努力忽略。安装后的请求仍按 Host API 错误处理 |
| 在线目录安装后执行签名搜索 | 小米 Xiaomi 25128PNA1C / API 36 上从在线目录安装官方脚本，以“海贼王”搜索并在 Compose 页面显示实际结果，instrumentation 1/1 通过 |

实机测试期间 CopyManga 对 `www.copy20.com` 的可选发现请求失败，但来源仍通过声明的默认 API 完成实际搜索。此
验收证明配置目录、脚本安装、一个真实搜索调用和结果渲染贯通，不代表账号登录、详情、章节、阅读等其它源能力
已经通过设备验收。真实脚本及真实漫画内容没有提交到仓库。

### 4.7 嵌套 Map 与详情展示（2026-10-02）

来源详情可返回嵌套 JavaScript `Map`：CopyManga 的 `tags` 是标签组 Map，`chapters` 是“分组 → 章节 Map”。
原生 `JSON.stringify` 会将 `Map` 写成空对象，因此 source invocation 序列化必须先把 Map 按迭代顺序递归转换为普通对象；不得重排来源声明的标签组或章节。

领域层同时保留兼容既有扁平 `Comic.tags` 的 `ComicDetail.tagGroups`，并从明确允许展示的标量字段提取 metadata；不将 `subId` 等不透明字段直接暴露在页面上。详情页面展示标签组、元数据、来源提供的预览图、简介和章节数/分组。解析和嵌套 Map 行为有合成 fixture 回归测试；真实来源详情页面 Android 测试已加入并编译通过，但当前还没有完成设备端运行，不能视为 CopyManga 详情调用及 UI 已通过实机验收。

### 4.8 作者标签跳转（2026-10-13，S4-10A6）

- 详情保留原始标签 namespace 和文本，仅作者/画师分组提供本次入口；是否可点由来源 `comic.onClickTag` 的可调用声明决定。
- 原源回调负责构造目标，兼容旧 `{action, keyword, param}` 和新 `{page, attributes}`，搜索读取 `text`（兼容 `keyword`），分类读取 `category`；保留 `options`。没有回调、返回 null 或不支持的目标不猜测普通关键词搜索。
- `TagNavigation` 在纯模型层表达 Search / Category；`:source:api` 增加类型化 tagNavigation、relatedWorksFilters、category 操作。SearchRequest 的 optionsOverride 只用于源明确提供的位置选项；普通搜索继续由 SourceFilter 编码。
- 分类读取 `categoryComics.optionList` 或异步 `optionLoader(category,param)`，支持 showWhen/notShowWhen、首个选项默认值及 callback options；分页调用 `categoryComics.load(category,param,options,page)`，页码从 1 开始。分类页只按源默认/跳转指定选项展示作品；本次未增加全局分类首页或筛选编辑。
- `:feature:details` 通过 ViewModel 获取可消费导航状态；`:feature:search` 承载原源的作者作品分页列表；`:app` 仅装配路由与返回。AuthorWorks 路由保存原详情及其返回来源，避免作品详情→作者结果→另一作品详情形成返回循环。
- 所有脚本操作沿用带 ID、timeout 和 cancel 的 SourceCall，不向 UI 暴露 JS；不记录作者参数或源码异常为产品文案。

参考基线：[Venera a0eba914](https://github.com/venera-app/venera/tree/a0eba914f4c2a84ac1bc925adec2baabe920b9be)，重点核对 [actions.dart](https://github.com/venera-app/venera/blob/a0eba914f4c2a84ac1bc925adec2baabe920b9be/lib/pages/comic_details_page/actions.dart)、[parser.dart](https://github.com/venera-app/venera/blob/a0eba914f4c2a84ac1bc925adec2baabe920b9be/lib/foundation/comic_source/parser.dart)、[models.dart](https://github.com/venera-app/venera/blob/a0eba914f4c2a84ac1bc925adec2baabe920b9be/lib/foundation/comic_source/models.dart)、category_comics_page.dart 和漫画源协议文档。上游 [LICENSE](https://github.com/venera-app/venera/blob/a0eba914f4c2a84ac1bc925adec2baabe920b9be/LICENSE) 为 GPL-3.0。本任务仅对照公开协议和行为独立实现 Kotlin，未复制上游 Flutter/JS 源码或资源；测试为仓库内合成 fixture，不含真实来源脚本、账号或漫画内容。

### 4.9 Komiic 作者兼容适配（2026-10-13，S4-10A6K）

用户实测拷贝漫画作者跳转正常、Komiic 不可用。核对 [Komiic 1.0.3 脚本基线 78c87f00](https://github.com/venera-app/venera-configs/blob/78c87f00aa708b4740fd5ccae4e9bced5b8ae583/komiic.js)：只有作者标签，没有 onClickTag；普通搜索的 searchComicsAndAuthors 响应虽然包含 authors，但脚本只返回 comics。上游详情没有无回调兜底，因此仅实现上游标签回调无法满足本次用户要求。

只读核对 [Komiic 官方页面](https://komiic.com/) 的公开前端 bundle `/assets/index-BAq7RFlu.js`：作者页先确定 ID，再以 `comicsByAuthor` 操作查询 `getComicsByAuthor(authorId: ID!)`，不提供分页参数。本项目在 source/core 独立实现最小 GraphQL 查询与解析，借助源实例的 queryJson 保持原有网络、登录及会话行为，没有复制上游脚本或网站前端代码；参考文件仅位于忽略的 build 目录。

这是 §4.8 的显式例外：仅 source key 为 Komiic、缺少 onClickTag 且 queryJson/search 可调用时启用；只处理原始 namespace 为“作者”的标签。按 name/chName/enName 精确匹配并去重 ID，歧义不自动选第一个；显式源回调始终优先，其他源仍保持无回调不跳转。Search 目标及 SearchRequest 通过可空 authorId 表达精确作者检索，源适配执行专门作品接口，不转为标题关键词搜索。路由以 author-search 类型保留 ID，既有 search/category 编码保持兼容。无 API 错误、但确实没有作品时展示空结果；错误不得伪装为空列表。

### 4.10 详情标签检索（2026-10-13，S4-10A6T）

按用户要求将 §4.8 的作者入口扩展至来源所有详情标签组。上游 actions.dart 的 onTapTag 将原始 namespace/tag 交给 handleClickTagEvent，因此本项目移除 UI 作者分组白名单；是否提供导航能力仍由源回调或已明确支持的源适配决定，回调返回 null/不支持目标时不进行普通搜索兜底。Details Action/UiState/导航回调改为标签语义，复用既有 AuthorWorks 路由与 Search 结果页，产品文案统一为“相关作品”，保持原路由编码和返回来源兼容。

拷贝漫画源的“标签”回调返回 category 目标，沿用 §4.8 的分类加载和筛选默认值。Komiic 1.0.3 没有回调，但其 category.parts 声明 fixed/category 的 categories 与 categoryParams 一一对应。本项目仅在原始 namespace 为“标签”时探测这一声明和 categoryComics.load，精确匹配唯一目标后返回 Category(tag,param)，不将标签名作为作品名搜索，不硬编码站点分类 ID。未知标签、多个不同分类 ID 或缺少加载器均返回无目标。实际分类加载仍通过已安装源及现有 categoryComics 协议，作者查询逻辑保持不变。参考源码及许可证边界沿用 §4.8/§4.9，测试采用合成 fixture。

## 5. 替代方案

| 方案 | 结论 |
| --- | --- |
| 只支持自定义的新协议 | 否决：放弃产品前提，既有源生态无法复用。 |
| 一次实现全部上游成员 | 否决：把登录、评论、收藏提前到 Stage 1，范围失控且无法验证。 |
| 把两套分页语义合并成一套 | 否决：会改变源侧收到的分页参数，直接破坏兼容性。 |

## 官方依据

- https://github.com/venera-app/venera/blob/master/doc/comic_source.md
- https://github.com/venera-app/venera/blob/master/doc/js_api.md
- https://github.com/venera-app/venera-configs
