# ADR-0017：GitHub Release 应用内自升级

- 状态：Accepted
- 日期：2026-11-14

## 背景

应用以 sideload APK 方式分发在 GitHub Releases 上，没有应用商店。此前的升级路径是"看到新版本 → 浏览器打开 Release 页 → 下载 APK → 手动安装"，用户要求：在当前 APK 上点更新就能完成升级，不跳转浏览器、不手动安装。

## 决策

1. 新建 `:data:update` 模块承载自升级能力；`releases/latest` 匿名轮询（无 token、手动触发、无后台定时检查）。
2. 版本判定用 `versionCode` 而不是 `versionName`：Release 资产命名固定为 `venera-native-v<version>-<versionCode>.apk`，应用从资产名解析 versionCode，解析失败时回退到 tag（`v1.6.0` → `1_006_000`，与 `build-logic` 的编码方案一致）。
3. APK 下载走共享 OkHttp 客户端（流式 + 字节级进度回调），写入 cache 目录，沿用 `.part` → 校验 → 原子改名的模式；校验 Content-Length 与 ZIP 头（`PK\x03\x04`），失败不落盘。fsync 为尽力而为（缓存文件不需要掉电保证，且沙箱/FUSE 文件系统会拒绝 fsync）。
4. 安装走 `PackageInstaller` 会话 API（`MODE_FULL_INSTALL`，API 31+ 尽力声明 `setRequestUpdateOwnership(true)`）。**系统要求确认时由数据层直接拉起系统安装确认页**，Intent 不出 `:data:update`；`install()` 挂起直到系统给出最终结果。
5. Manifest 声明 `REQUEST_INSTALL_PACKAGES`；首次使用时引导用户到"允许安装未知应用"系统页一次性授权，返回后流程自动继续（About 页监听 ON_RESUME）。之后不再需要。
6. Feature 层（`AboutViewModel`）只依赖 `SelfUpdateGateway` 接口（纯 Kotlin 契约，无 Android 类型），实现由 `:app` 装配，保证流程逻辑可 JVM 单测。
7. 错误一律映射为领域问题（网络/无发布/无可安装 APK/下载/安装），文案在 UI 层生成，诊断信息不直接当产品文案。

## 理由

- 完全静默安装对 sideload 应用在非 root 设备上不可实现，平台底线是"一次系统确认"；把授权与确认压到各一次、且只发生一次，是不跳浏览器前提下自动化程度最高的方案。
- 版本号走 `versionCode` 避免 tag 与 `versionName` 解析歧义；资产名由本仓库发布流程控制，是最可靠的载体。
- 确认 Intent 留在数据层，Feature 与 ViewModel 不接触 Android Intent，维持 MVI 状态的可测性（同 ADR-0001 的边界精神）。
- `releases/latest` 排除 draft 与 prerelease，天然只暴露正式版本；匿名限额（60 次/时/IP）对手动检查绰绰有余。

## 后果

- 发布流程必须遵守资产命名约定（`docs/release.md` 已同步），否则升级端将回退到 tag 解析。
- 更新前提是签名一致：Release APK 必须与已安装 APK 同签名才能覆盖安装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE` 会被映射为"安装未完成"）。当前真机装的是共享 Debug 签名，首次切到独立 Release 签名需卸载重装（丢本地数据），须先备份。
- 安装成功瞬间系统可能杀死进程，"更新完成"状态不保证可见，UI 文案已按此设计。
- 匿名 API 限额、GitHub 不可达等都会表现为"暂时无法连接"，属预期降级。

## 替代方案

- 跳转浏览器下载：用户明确否决，要求全自动。
- `DownloadManager` + `ACTION_VIEW`：仍需手动点安装，且通知与临时文件不受控。
- 定时后台检查更新：增加后台耗电与限额风险，用户当前明确按"让我打包并更新"节奏手动触发，暂不需要。
