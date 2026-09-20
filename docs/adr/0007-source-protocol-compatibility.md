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
| `init()` | 只有存在时才调用；上游在解析完成后异步延迟 50ms 触发 |

基类 `ComicSource` 由宿主在 JS 侧提供（上游在 `assets/init.js`），成员包括身份字段、
`loadData` / `saveData` / `deleteData` / `loadSetting` / `isLogged` / `translate` 与静态 `sources` 注册表。

两条对本项目的直接结论：

- **成员是路径不是标识符**，引擎的调用包装必须按路径解析，且不能把 `this` 绑成全局对象——
  `SourceInvocationScript` 与它的测试按此实现。
- **缩进的类声明会被拒绝**，这是上游既有行为（他们的解析器同样拒绝）。本项目的错误信息显式说明
  “必须在行首声明”，避免使用者以为是我们的 bug。

与上游的一处有意差异：`init()` 在本项目里是**在安装内被等待**的，而不是延迟 50ms 异步触发。
理由是可重复性：安装完成即代表源可用，`init` 抛错会变成安装失败，而不是稍后变成难以定位的运行时错误。

`ComicSource` 基类的 Stage 1 实现有两处已知简化（写在 `SourceBaseScript` 的文档注释里）：
`saveData` 只活到引擎实例销毁，`loadSetting` 返回源自己声明的默认值而不是用户选择值——
源的持久化数据与设置属于 Stage 2/3（见 §2.1）。

### 4.5 仍待确认

- `loadThumbnails` 的真实签名：该源未实现，需要再找使用它的源核对（影响 S1-05 多页缩略图）。
- `Comment` 与 `ImageLoadingConfig` 的字段（分别影响 Stage 3 评论与 S1-05 图片管线）。
- 错误约定：文档无专门章节，只在 `favorites` 系列出现“抛出字符串 `Login expired` 触发重登”；
  Stage 1 范围不涉及，记录备查。

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
