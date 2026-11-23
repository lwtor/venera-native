# 项目执行状态

本文件记录当前交付与验证情况。所有后续开发任务由用户指定，不从历史计划中自动选择任务。

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
- 账号有效性检测、按漫画寻找来源和详情来源替换仍是后续用户指定批次。
- 页面交互与实时来源行为尚未在用户设备验证。

## 当前状态

- Venera Native 是兼容 Venera 漫画源生态的非官方 Android 原生阅读器。模块职责见 [架构文档](ARCHITECTURE.md)。
- 应用版本：2.4.1（versionCode 2004001），同步 U-11 的 Release 发布状态；功能首次交付于 2.4.0。
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
