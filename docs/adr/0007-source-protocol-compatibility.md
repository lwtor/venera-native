# ADR-0007：Venera 漫画源协议兼容范围

- 状态：Accepted（字段级细节待补，见第 4 节）
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
| `comic.loadInfo` | 是 | 详情 |
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

## 4. 待确认（字段级）

以下内容在 `js_api.md` 中定义，本 ADR 尚未核对，必须在写协议 DTO（S1-03 的源绑定）之前补齐：

- `Comic` 与 `ComicDetails` 的字段清单，以及章节列表来自 `loadInfo` 还是独立调用。
- `ImageLoadingConfig` 的字段与 `onImageLoad` 的调用时机（影响 S1-05 图片管线）。
- `Network` 等 Host API 的签名与返回形态（影响 ADR-0008 的 adapter 设计）。
- 错误约定：例如收藏相关方法抛出字符串 `Login expired` 触发自动重登。

补齐后本节应替换为完整的字段映射表，而不是保留“待确认”。

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
