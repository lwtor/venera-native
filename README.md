# Venera Native

Venera Native 是一个受 [Venera](https://github.com/venera-app/venera) 启发的非官方 Android 原生漫画阅读器，使用 Kotlin、Jetpack Compose、MVI/UDF 和 Jetpack 组件重新实现其核心功能与漫画源生态。

> 本项目与原 Venera 维护团队无隶属关系。项目名称中的 “Native” 用于明确区分 Android 原生重构与原 Flutter 项目。

## 当前状态

项目处于阶段 0（技术验证）。基础多模块工程已经建立，下一步是完成 JavaScript 漫画源运行时与 Compose 阅读器原型。

- [完整项目规划](docs/PROJECT_PLAN.md)
- 原项目：[venera-app/venera](https://github.com/venera-app/venera)
- 原漫画源协议：[Comic Source](https://github.com/venera-app/venera/blob/master/doc/comic_source.md)
- 原 JavaScript API：[JavaScript API](https://github.com/venera-app/venera/blob/master/doc/js_api.md)

## 当前模块

```text
:app
:core:common
:core:designsystem
:core:model
:core:navigation
:feature:home
:source:api
:source:engine
```

## 技术基线

- Android Gradle Plugin 9.4 + Gradle 9.7.1
- AGP 内置 Kotlin + JDK 17
- compileSdk / targetSdk 37，minSdk 26
- Jetpack Compose + Material 3
- Navigation 3
- AndroidX JavaScriptEngine
- Gradle Kotlin DSL + Version Catalog + Convention Plugins

## 构建

```shell
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat assembleDebug
```

## 许可与来源说明

Venera 原项目采用 GPL-3.0。本项目在进入功能实现阶段前，将根据实际复用的源码、资源和漫画源兼容层完成许可证确认，并持续在应用“关于”页面和项目文档中保留上游致谢与链接。
