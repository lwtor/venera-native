# Venera Native

面向 Android 的原生漫画阅读器，使用 Kotlin 和 Jetpack Compose 构建，并兼容 Venera 漫画源生态。

[下载最新版本](https://github.com/lwtor/venera-native/releases/latest) · [问题反馈](https://github.com/lwtor/venera-native/issues) · [项目文档](docs/)

> 本项目由社区独立开发，与 Venera 原项目及其维护团队没有隶属或官方合作关系。应用不捆绑漫画内容；漫画源及其提供的内容由用户自行选择。

## 功能

- 安装和管理兼容 Venera 协议的漫画源，通过搜索、分类和探索发现作品。
- 查看漫画详情、作者和标签，管理收藏夹并跟踪章节更新。
- 纵向连续或横向翻页阅读，保存章节与页码进度。
- 下载章节供离线阅读，也可导入本地漫画目录。
- 可选连接 GitHub，手动备份和恢复应用数据。

## 安装

从 [GitHub Releases](https://github.com/lwtor/venera-native/releases/latest) 下载最新的 `venera-native-*.apk`，在 Android 设备上安装。应用最低支持 Android 8.0（API 26）。

首次使用时，在「我的 → 漫画源管理」安装兼容来源，再从首页搜索或探索漫画。来源由用户管理；来源可用性、登录要求和内容由对应来源维护者决定。

## 构建

需要 JDK 17 和 Android SDK 37。Windows 开发构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。正式 Release 使用独立签名密钥，绝不能使用仓库中的 Debug 密钥；签名与发布流程见 [`docs/release.md`](docs/release.md)。

## 技术结构

项目采用 Kotlin、多模块 Gradle、Jetpack Compose、Room、Paging、Coil 和 QuickJS。`:app` 负责根导航与依赖装配，漫画源通过受限的类型化接口调用。模块职责和依赖方向见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)，当前执行状态见 [`docs/STATUS.md`](docs/STATUS.md)。

## 参与项目

欢迎通过 [Issues](https://github.com/lwtor/venera-native/issues) 报告问题或提出建议，也欢迎提交 Pull Request。提交前请阅读 [`AGENTS.md`](AGENTS.md) 中的架构、验证、版本与提交约定。

## 许可与致谢

本项目以 Apache License 2.0 发布，许可文本见 [`LICENSE`](LICENSE)，第三方依赖说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。本项目受 Venera 启发，兼容其公开漫画源协议；未有意复制 Venera 源码或捆绑内容。Venera 名称仅用于说明兼容关系。
