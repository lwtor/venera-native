# ADR-0017：GitHub Release 应用内自升级

- 状态：Accepted
- 日期：2026-11-14
- 修订：2026-11-14（安装方式由 `PackageInstaller` 会话改为 FileProvider + `ACTION_VIEW`，见「修订」一节；下载改为 WorkManager 前台服务，见「修订二」）

## 背景

应用以 sideload APK 方式分发在 GitHub Releases 上，没有应用商店。此前的升级路径是"看到新版本 → 浏览器打开 Release 页 → 下载 APK → 手动安装"，用户要求：在当前 APK 上点更新就能完成升级，不跳转浏览器、不手动安装。

## 决策

1. 新建 `:data:update` 模块承载自升级能力；`releases/latest` 匿名轮询（无 token、手动触发、无后台定时检查）。
2. 版本判定用 `versionCode` 而不是 `versionName`：Release 资产命名固定为 `venera-native-v<version>-<versionCode>.apk`，应用从资产名解析 versionCode，解析失败时回退到 tag（`v1.6.0` → `1_006_000`，与 `build-logic` 的编码方案一致）。
3. APK 下载走共享 OkHttp 客户端（流式 + 字节级进度回调），写入 cache 目录，沿用 `.part` → 校验 → 原子改名的模式；校验 Content-Length 与 ZIP 头（`PK\x03\x04`），失败不落盘。fsync 为尽力而为（缓存文件不需要掉电保证，且沙箱/FUSE 文件系统会拒绝 fsync）。
4. 安装经 FileProvider 暴露 APK 给系统安装器，用 `ACTION_VIEW`（`application/vnd.android.package-archive`）发起；系统安装器自行弹出确认对话框。**不声明 `REQUEST_INSTALL_PACKAGES`**，不要求一次性「允许安装未知应用」授权。
5. Feature 层（`AboutViewModel`）只依赖 `SelfUpdateGateway` 接口（纯 Kotlin 契约，无 Android 类型），实现由 `:app` 装配，保证流程逻辑可 JVM 单测。
6. 错误一律映射为领域问题（网络/无发布/无可安装 APK/下载/安装），文案在 UI 层生成，诊断信息不直接当产品文案。

## 修订（2026-11-14）

初版采用 `PackageInstaller` 会话 + `REQUEST_INSTALL_PACKAGES` 永久授权，期望"授权一次、之后自动升级"。实测小米/澎湃系统对「允许安装未知应用」有额外的 8 秒风险确认倒计时，且用户反馈这一步比微信式的"每次点一下确认"更麻烦，遂改为：

- 放弃 `PackageInstaller` 会话与 `REQUEST_INSTALL_PACKAGES`，改走 `FileProvider + ACTION_VIEW`，让系统安装器处理确认。
- 代价：拿不到安装成功/失败回调（只能确认安装器是否被成功拉起）；每次更新仍需用户点一次系统「安装」按钮。

这正符合用户期望的"微信式每次确认"，且避免了永久授权带来的系统级更严审查。

## 修订二（2026-11-14）

真机反馈三连：下载极慢（走系统代理的共享客户端对 `release-assets.githubusercontent.com` 不稳定）、无断点续传（每次失败从头下）、无前台服务（退后台/锁屏后线程被系统回收）。

- **下载慢**：改「代理优先 + 直连回退」。先走系统代理（用户 VPN 加速浏览器的同一通道），连接失败且未产生任何字节时才回退直连（`Proxy.NO_PROXY`）；HTTP 错误与中途断流不重放，避免把已下的字节作废。
- **断点续传**：`.part` 临时文件 + `Range: bytes=N-`。下次尝试从已写字节处续传（GitHub CDN 返回 206），ZIP 头校验后原子改名。
- **前台服务**：下载从 `AboutViewModel.viewModelScope` 迁移到 `data:update` 的 `UpdateDownloadWorker`（`CoroutineWorker` + `setForeground` + `dataSync` 前台类型，通知带进度条），退后台/锁屏后仍存活。复用 `data:download` 的成熟模式；进程内共享 httpClient 经 `UpdateEnvironment` 单例注入（`:data:update` 不能依赖 `:app`）。

代价：Worker 通过 `setProgressAsync` 上报进度 fraction（非挂起回调里不能调 `setForeground`），前台通知进度条停在初始值，精确百分比在 About 页展示；下载完成路径经 `Result.success` 的 outputData 传回，ViewModel 观察 `WorkInfo` 等待 SUCCEEDED 后调用安装。

## 理由

- 完全静默安装对 sideload 应用在非 root 设备上不可实现，平台底线是"至少一次用户确认"；「每次安装确认」与「一次性永久授权」都要点一下，用户明确倾向前者。
- 版本号走 `versionCode` 避免 tag 与 `versionName` 解析歧义；资产名由本仓库发布流程控制，是最可靠的载体。
- 安装 Intent 留在数据层，Feature 与 ViewModel 不接触 Android Intent，维持 MVI 状态的可测性（同 ADR-0001 的边界精神）。
- `releases/latest` 排除 draft 与 prerelease，天然只暴露正式版本；匿名限额（60 次/时/IP）对手动检查绰绰有余。

## 后果

- 发布流程必须遵守资产命名约定（`docs/release.md` 已同步），否则升级端将回退到 tag 解析。
- 更新前提是签名一致：Release APK 必须与已安装 APK 同签名才能覆盖安装；签名不一致时系统安装器会报错。
- 应用无法感知安装是否真正完成，UI 只能提示"已打开系统安装页面"。
- 匿名 API 限额、GitHub 不可达等都会表现为"暂时无法连接"，属预期降级。

## 替代方案

- 跳转浏览器下载：用户明确否决，要求全自动。
- `DownloadManager` + `ACTION_VIEW`：仍需手动点安装，且通知与临时文件不受控。
- `PackageInstaller` 会话 + 永久授权：自动化程度更高但引入系统更严审查（小米 8 秒倒计时），用户已否决。
- 定时后台检查更新：增加后台耗电与限额风险，用户当前明确按"让我打包并更新"节奏手动触发，暂不需要。
