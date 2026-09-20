# 大图验证测试图生成器（S0-06）

生成器用 JDK 内置的 `ImageIO` 产生确定性测试图，供阅读器大图验证使用。

仓库内**不提交任何真实漫画页**。所有 fixture 都由本工具生成，且输出目录已被 `.gitignore` 排除，
因此从干净克隆构建时不会进入 Release 包。

## 运行

需要 JDK 17（与 Gradle 构建同一版本）：

```powershell
java -Xmx2g tools/test-images/GenerateTestImages.java feature/reader/src/main/assets/fixtures
```

默认输出目录为 `tools/test-images/out`。生成后会打印一张 Markdown 表，可直接粘贴进
`docs/STATUS.md` 的实测记录。

## 生成的 fixture

| fixture | 像素 | 覆盖场景 |
| --- | --- | --- |
| `page_normal_1080x1440.png` | 1080 x 1440 | 常见单页 |
| `page_normal_1080x1440.jpg` | 1080 x 1440 | 常见单页，JPEG 编码 |
| `page_wide_1920x1080.png` | 1920 x 1080 | 横向页与旋转 |
| `page_long_1080x6000.png` | 1080 x 6000 | 长条图 |
| `page_ultralong_1080x16000.png` | 1080 x 16000 | 超长条图 |
| `page_hires_3000x4000.png` | 3000 x 4000 | 高分辨率页 |

## 图案设计

图案由垂直渐变、网格线、棋盘格和每 600 px 一条的刻度尺组成。刻度尺与棋盘格的作用是让
“过度降采样导致的模糊”在屏幕上肉眼可见，而不只是数字上可测。

生成结果只依赖尺寸与图案规则，重复执行结果一致。

## 注意事项

- 生成 1080 x 16000 的位图需要较大堆内存，按上面的命令显式设置 `-Xmx2g`。
- fixture 只用于本地验证与 instrumentation 测试；不要提交、不要放进 Release 构建。
