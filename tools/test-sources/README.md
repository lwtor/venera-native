# 测试漫画源（tools/test-sources）

## 这是什么

一个**完全本地生成**的漫画源，用来在不接触任何真实站点的前提下验证阅读闭环。

`AGENTS.md` 明确禁止测试依赖真实商业漫画站点或真实账号，现有的
`source/engine/src/androidTest/assets/source_fixture.js` 也不是漫画源形态（它只有几个用于验证
引擎绑定的函数，不遵循 `SourceClassConvention`）。所以 Stage 1 的端到端验收需要它。

## 为什么所有数据都是生成的

| 选择 | 理由 |
| --- | --- |
| 不请求真实站点 | 仓库附录要求测试不得依赖真实来源或账号 |
| 由脚本生成的漫画/章节 | 端到端脚本不会因为别人的服务器改版而腐烂 |
| 固定 42 条探索结果 / 3 章 / 每章 3 页 | 数字足够翻页，`maxPage` 与进度保存能被走到 |
| 搜索命中 "miss" 时返回空 | 用来验证空结果不会退化成错误态 |

## 遵循的约定

`SourceClassConvention`（见 `source/engine/.../SourceClassConvention.kt`）要求：

1. `class <Name> extends ComicSource` 必须出现在**行首**（缩进的类声明会被拒绝）；
2. `name` / `key` / `version` / `minAppVersion` 是**实例字段**，不是字面量，因此热水器必须真的执行脚本才能读到。

`key` 只允许字母、数字和下划线。

## 关于章节顺序

`loadInfo` 返回的 chapters 是 id → title 的映射，**按插入顺序传递**。

这不是无关细节：有些来源会倒序发布（最新在前），舷应用层必须保留来源给定的顺序，而不是事后按 id 排序。
`core/model` 的 `chaptersOf` 与 `groupedChaptersOf` 就是靠这一点保持行为的。

## 安装方式

由 `LocalFileScriptFetcher` 从本地文件安装（`:app` → 来源管理 → 安装本地脚本）。

具体步骤见 `docs/STATUS.md` 中记录的端到端人工脚本。
