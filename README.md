# Venera Native

Venera Native 是一个受 [Venera](https://github.com/venera-app/venera) 启发的非官方 Android 原生漫画阅读器，计划使用 Kotlin、Jetpack Compose、MVI/UDF 和 Jetpack 组件重新实现其核心功能与漫画源生态。

> 本项目与原 Venera 维护团队无隶属关系。项目名称中的 “Native” 用于明确区分 Android 原生重构与原 Flutter 项目。

## 当前状态

项目处于规划阶段，尚未开始功能开发。

- [完整项目规划](docs/PROJECT_PLAN.md)
- 原项目：[venera-app/venera](https://github.com/venera-app/venera)
- 原漫画源协议：[Comic Source](https://github.com/venera-app/venera/blob/master/doc/comic_source.md)
- 原 JavaScript API：[JavaScript API](https://github.com/venera-app/venera/blob/master/doc/js_api.md)

## 目标技术栈

- Kotlin
- Jetpack Compose + Material 3
- ViewModel + StateFlow + 轻量 MVI/UDF
- Navigation 3
- Hilt + KSP
- Coroutines + Flow
- Room + Paging 3 + Proto DataStore
- OkHttp 5 + Coil 3
- AndroidX JavaScriptEngine
- WorkManager / User-Initiated Data Transfer
- Gradle Kotlin DSL + Version Catalog + Convention Plugins

## 许可与来源说明

Venera 原项目采用 GPL-3.0。本项目在进入实现阶段前，将根据实际复用的源码、资源和漫画源兼容层完成许可证确认，并持续在应用“关于”页面和项目文档中保留上游致谢与链接。
