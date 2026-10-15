# ADR-0015：保留漫画资料与资源

- 状态：Accepted
- 日期：2026-10-15
- 任务：S4-10C1/C2/C3

## 上下文与决定

收藏封面仅保存 URL，图片使用可清理 cacheDir 缓存，缺少持久文件兜底。增加独立 comic_record 与 comic_chapter；资料按来源/作品唯一，章节按来源/作品/版本/章节唯一；收藏归属、阅读、下载任务保持独立。版本键 0 表示无分组、1+原名称表示有分组，避免空组名与 null 混同。

Room 11→12 从收藏及已有下载任务迁入资料，不修改用户收藏/阅读/下载。旧 chapters_json 原样放 legacy_chapters_json，由仓库通过已有 JSON 解析规范化，不依赖设备 SQLite JSON 扩展。旧收藏元数据列暂作为兼容旧备份的投影保留，新界面以独立资料/目录为准，不能将两套字段当成两个事实来源。

沿用 filesDir/downloads/<sourceHash>/<comicHash>/ 作为每漫画资源目录，增加 covers/，原 chapters/ 路径保持；不为目录名称搬移已下载文件。持久封面与普通网络缓存分开，仅明确保留漫画请求可使用本地资源；来源认证头、Cookie、Token 不落入资源索引。取消收藏默认保留资料、封面及下载，不自动清理用户资源。

默认资料成功刷新后 12 小时过期，应用进入前台后统一检查，先显示旧资料；并发最多二，失败保留旧资料/封面并延后至少 15 分钟重试，记录成功刷新与尝试/下次时间，来源更新日期独立。无后台定时任务，不改变 GitHub 手动同步。缺封面可独立修复，不因元数据失败清空旧文件。下载/本地归档不得被封面清理覆盖。

## 模块边界与后果

core:database 定义结构/迁移；data:collection 管理收藏所保留资料、归属及过期协调，允许复用 core:image 的持久资源实现；core:image 保持图片认证/缓存/验证边界，公共纯契约位于 core:model。app 只装配服务与生命周期。Feature 只读取状态和发送动作，不直接访问文件或网络。

选择在既有模块落地，避免为单一切片增加空模块。保留下载根与旧备份投影使迁移可控，代价是目录旧名称及兼容列暂保留；后续移除必须另做迁移。持久资源占用实际存储，不被普通清缓存移除；卸载/清应用数据仍会删除。云备份只存结构化资料，不存封面二进制或设备文件路径，恢复后重新获取资源。

依据：[Android app-specific storage](https://developer.android.com/training/data-storage/app-specific)、[atomic file move](https://developer.android.com/reference/java/nio/file/StandardCopyOption)。验证与待交付切片见 STATUS，不将编译视为设备迁移或视觉通过。
