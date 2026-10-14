# ADR-0014：共享漫画卡片与横向列表

- 状态：Accepted
- 日期：2026-10-14
- 任务：S4-10B1/B2

## 背景与决定

用户要求漫画列表仅使用竖向卡片或横向列表，其他位置复用。不同 Feature 不能互相依赖，既有 core:designsystem 有实际共享消费者，因此在该模块定义 ComicCoverCard、ComicHorizontalCard、ComicCornerBadge。允许 core:designsystem → core:image；core:image 不依赖 Design System，不形成循环。Explore 为封面请求新增 core:image 依赖，遵循其他展示 Feature 的图片边界。

竖向卡片 8dp 圆角，封面比例 0.75，封面下沿与信息上沿直角无间距连接；下方单行标题与单行章节进度（S4-10B3 替代原日期），外层底部圆角和顶部相同。来源小角标在封面右上，已有更新标记在左上，收藏长按保持。缺少章节目录显示“总章节未知”，不使用合并版本的 chapterCount 冒充总话数。来源日期字段保留供已有横向展示/兼容，不在竖向卡片显示。

core:image 的 ComicImageWithDominantColor 复用加载结果，在 Default dispatcher 缩为 32×32 并统计 3bit RGB 色簇、忽略透明像素；只传出 Color，不在 Compose State、数据库或 Feature 保存 Bitmap。取色模式使用软件解码避免读取硬件位图，不增加封面网络调用或引入 Palette 依赖。保留已有认证、源隔离、磁盘缓存及重试。取色前/失败时回退主题背景；按亮度选择黑白文字。非取色调用维持已有解码行为。

横向组件统一固定 120dp 行高、80×120dp 左侧封面、原模糊渐变及状态小角标。内容、底部操作和右侧控件通过组合槽提供，支持搜索标签、首页进度、本地章节展开和下载多选。API 26–30 模糊降级沿用旧行为。无可加载封面的本地/下载条目显示标题占位，不临时新增文件访问或详情请求。

来源显示名由 AppGraph 在既有来源初始化与变更后读取本地 SourcePackageStore，发布字符串映射 StateFlow，根装配提供 CompositionLocal。Feature 只读取映射，不访问仓库；禁用来源仍可显示名字，卸载后退回稳定 ID。

## 依据与验证范围

- [Coil toBitmap](https://coil-kt.github.io/coil/api/coil-core/coil3/to-bitmap.html)：从加载 Image 生成指定尺寸 Bitmap。
- [Coil recipes](https://github.com/coil-kt/coil/blob/main/docs/recipes.md)：像素取色需关闭硬件位图。
- App 与新增取色测试源码编译；日期保存/备份/迁移测试源码编译。普通任务未执行测试或真机视觉验证。
- 展示入口核对见 docs/reviews/shared-comic-cards-audit.md。

## 后果与替代方案

新增依赖只指向已存在的图片模块，Feature 不重复布局，公共组件可以集中调整。代价是取色卡片使用软件封面解码和小量 CPU 采样，视觉需真机确认。未选择 Feature 间互相引用，避免破坏模块边界；未选择额外下载缩略图或新 Palette 依赖，现有加载结果即可满足主色需求。

## S4-10B3：章节进度与版本

日期：2026-10-14。按用户新要求，竖向卡片第二行改为章节进度。最新阅读版本存在时仅取该目录；未读按各版本最大正篇话号取最大值。话名含明确中英数字/小数编号时以最大编号为终点，避免倒序列表和附加番外影响；无可靠编号时取单个版本的实际目录数量，未读取最大版本数量，绝不合计所有版本。

core:model 纯模型 ComicChapterEntry/ComicReadingChapter 与 comicChapterProgressLabel 集中规则。data:collection 使用现有 JSON 依赖保存 ID、标题、版本，Room chapters_json 可空；收藏观察合并 ReadingHistoryDao 的每漫画最新记录，以 source/comic 索引查找，不逐行订阅/请求。data:history 在 ReaderProgressSession 保存 ChapterRef.Remote.group 到 chapter_group。Room 10→11 增列不改变既有主键；跨版本重复 ID 的旧记录覆盖问题不在此处扩展，但最新记录的版本能正确展示/续读。

目录来自既有收藏/手动更新/详情响应；Details 成功加载时 updateChapters 只更新已有收藏目录，空目录不覆写既有数据。缺失旧目录通过打开详情或手动检查更新补齐，不在列表后台批量请求详情。旧版本无明确 group 时仅由唯一 ID/唯一版本解析，跨版本歧义不猜测；显式版本已移除则显示未知。聚合预览无目录也显示未知，不取日期替代、不新增详情请求。可选备份字段随目录和阅读版本往返，旧包缺键仍兼容。
