# Stage 0/1 质量整改台账

基线：cbfb8ea。完整发现见 [审查记录](stage-01-audit.md)。每项独立编译、测试、文档同步后 commit。

| ID | 切片 | 状态 | 验收范围 |
| --- | --- | --- | --- |
| Q00 | 审查与提交规则 | DONE | 规则、审查基线及整改台账 |
| Q01 | HTTP 正文读取 | DONE | R01；短响应/超限/异常/取消 |
| Q02 | 图片缓存与网络 | DONE | R02/R13；租约、取消、大小、表单、共享缓存 |
| Q03 | 正文按需图片链路 | DONE | R03/R11/R12；稳定页号、按需加载与预取 |
| Q04 | 来源原子存储 | DONE | R08；失败回滚与重启一致 |
| Q05 | 来源恢复与生命周期 | DONE | R04/R14；恢复、版本失效、Cookie 清理 |
| Q06 | 探索分页 | IN_PROGRESS | R09；mixed 完整分页 |
| Q07 | 进度持久化 | TODO | R06；串行、失败保留、事务 |
| Q08 | 恢复与退出集成 | TODO | R05/R06；异步恢复、选章、退出强刷 |
| Q09 | 阅读缩放与手势 | TODO | R10；平移、滚动、模式互斥 |
| Q10 | Runtime 边界 | TODO | R15/R16/R17；日志、init、超时取消队列 |
| Q11 | 测试源与闭环 | TODO | R07；同一协议 fixture、安装、网络图片 |
| Q12 | 文档和引擎判据 | TODO | 旧状态、ADR、构建/ABI/许可及延期归属 |
| Q13 | 阶段复验 | TODO | 明确测试门禁与最终质量结论 |

## 验证记录

Q00：纯文档；git diff --check 通过。

Q01：修复正文有界读取与回调异常映射；新增短/空/边界、chunked 超限和读取异常回归。 验证：`:source:network:testDebugUnitTest :app:assembleDebug` — PASS；`git diff --check`。

Q02：修复缓存提交后的文件租约、网络取消与实际字节上限、表单编码、鉴权快照及共享 DiskCache；新增首次下载和缓存命中/请求语义回归。 验证：`:core:image:testDebugUnitTest :app:assembleDebug` — PASS；`git diff --check`。

Q03：保留稳定页索引，按邻近页面解析尺寸并支持失败重试；解码器通过带租约的认证缓存文件读取正文 验证：`:core:image:testDebugUnitTest :data:comic:testDebugUnitTest :feature:reader:testDebugUnitTest :feature:reader:compileDebugAndroidTestKotlin :app:assembleDebug` — PASS；`git diff --check`。

Q04：以不可变脚本和原子索引替换保证来源升级一致性，失败保留旧包，回滚保留禁用状态 验证：`:data:source:testDebugUnitTest :app:assembleDebug` — PASS；`git diff --check`。

Q05：保留根依赖跨配置变化，销毁时关闭资源；冷启动串行恢复启用来源，禁用卸载清理会话，升级能力不再使用陈旧缓存 验证：`:data:source:testDebugUnitTest :source:core:testDebugUnitTest :data:comic:testDebugUnitTest :app:assembleDebug` — PASS；`git diff --check`。
