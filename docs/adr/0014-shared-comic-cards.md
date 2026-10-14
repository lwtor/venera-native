# ADR-0014：共享漫画卡片与横向列表

- 状态：Accepted
- 日期：2026-10-14
- 任务：S4-10B1/B2

## 背景与决定

用户要求漫画列表仅使用竖向卡片或横向列表，其他位置复用。不同 Feature 不能互相依赖，既有 core:designsystem 有实际共享消费者，因此在该模块定义 ComicCoverCard、ComicHorizontalCard、ComicCornerBadge。允许 core:designsystem → core:image；core:image 不依赖 Design System，不形成循环。Explore 为封面请求新增 core:image 依赖，遵循其他展示 Feature 的图片边界。

竖向卡片 8dp 圆角，封面比例 0.75，封面下沿与信息上沿直角无间距连接；下方单行标题与单行来源更新日期，外层底部圆角和顶部相同。来源小角标在封面右上，已有更新标记在左上，收藏长按保持。缺少日期显示“暂无更新日期”，不使用 addedAt/updatedAt 冒充来源日期。

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
