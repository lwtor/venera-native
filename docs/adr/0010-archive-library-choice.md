# ADR-0010：ZIP/7z 归档库与许可证选择

- 状态：Accepted
- 日期：2026-09-24

## 背景

S2-06 要从 SAF 提供的文档 URI 读取 ZIP/CBZ 与 7z/CB7，并为页提供随机条目读取。不能把 `Context` 或文件系统能力暴露给归档格式模型，也不能未经许可审查复用上游实现。

## 决策

在 `:core:archive` 内统一使用 Apache Commons Compress 1.28.0，7z LZMA/LZMA2 可选解码依赖 XZ for Java 1.12。该版本修复了 `LZMAInputStream` 使用 `ArrayCache` 时的解码异常；版本说明见 [XZ for Java NEWS](https://github.com/tukaani-project/xz-java/blob/master/NEWS.md)。输入通过独占 `SeekableByteChannel` 传入，调用方负责关闭 reader；`:core:archive` 是唯一直接依赖 Commons Compress 的模块。归档页索引在导入时写入 Room；读取条目时不重新枚举索引，页面字节经有界 LRU 缓存物化。

未知格式、无效归档、缺失条目和 I/O 错误映射到类型化错误。当前验证覆盖普通 ZIP 和 COPY 7z fixture；Stage 2 不宣称覆盖所有加密方式或压缩方法，不支持 RAR 与多卷归档。

## 理由与替代方案

两项依赖分别采用 Apache-2.0 与 Public Domain/0BSD，避开 7-Zip-JBinding 的 LGPL 分发/可替换性问题。JDK `ZipFile` 需要普通文件，不适合直接读取 SAF 通道。归档先复制到临时文件会增加 I/O、存储占用与失败恢复路径，因此采用随机访问通道。

## 后果

7z 解码设 128 MiB 字典内存限制；页面缓存限制为 256 MiB 总量、100 MiB 单页。具体归档 Provider、加密样例和设备兼容仍需专项覆盖；用户可重新导入/重新物化失败页面。
