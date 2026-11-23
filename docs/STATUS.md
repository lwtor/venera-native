# 项目执行状态

本文件记录当前交付与验证情况。所有后续开发任务由用户指定，不从历史计划中自动选择任务。

## 当前任务：U-05 Release 签名备份与校验

- 按用户要求生成新 Release 密钥并创建独立 GitHub 私有库 `lwtor/private-vault`；加密备份和用户要求明文保存的随机恢复密码存于该库。可读取私有库者可以解密，需限制账号权限并另行备份。
- 恢复脚本可在 Mac / Windows 通过已登录的 GitHub CLI 与 GnuPG 恢复 Git 忽略的签名文件，不覆盖不同的现有文件；原有 Release 密钥和配置未覆盖。
- 项目固定新证书公开指纹，Release 构建前和 APK 生成后均自动校验；错误指纹分别验证会拒绝。实施和风险记录见 ADR-0018，操作见 `docs/release.md`。
- 新证书与旧版不同：2.0.0 不能直接覆盖旧签名的已安装版本。用户数据迁移尚未实现或验证，不卸载设备应用。

## 当前任务：U-04A 来源管理与即时同步

- 漫画源管理默认显示已安装列表，提供发现来源、手动安装和文件导入入口；已安装条目突出启用状态和设置操作。
- 来源安装、覆盖安装、启停和移除成功后发出变更版本，搜索、探索、相关作品与详情页面刷新；同版本脚本覆盖也会使能力、详情和标签缓存失效。
- 来源更新、账号有效性检测、按漫画寻找来源和详情来源替换是用户指定的后续批次，须先等待用户验证本批次。
- 页面交互与实时来源行为尚未在用户设备验证。

## 当前状态

- Venera Native 是兼容 Venera 漫画源生态的非官方 Android 原生阅读器。模块职责见 [架构文档](ARCHITECTURE.md)。
- 应用版本：2.0.0（versionCode 2000000），本次交付新的签名备份与验证机制；来源管理改动仍待用户验证。
- 上一功能交付：1.9.11 应用内更新。正式 APK 构建和签名证书核对通过；Release Lint 报告 13 项依赖有新版可用，依赖升级留待单独任务。
- 来源兼容性：支持二进制响应、图片 Hook URL 改写和有序章节结果；飞翔实时封面检测及相关 Android UI 未单独复验。
- 应用内更新的设备端完整升级、授权返回及后台下载行为仍需设备复验；不能将构建或 JVM 测试等同于真机验收。
- 再漫画多个作品详情失败的原因尚未确认；之前的 Debug 诊断与结果可在 Git 历史查阅。是否继续排查由用户指定。

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
