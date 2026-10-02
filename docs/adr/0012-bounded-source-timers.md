# ADR-0012：受限且可取消的来源计时器

- 状态：Accepted
- 日期：2026-10-03
- 相关：ADR-0003（Host API allowlist）、ADR-0008（异步 Host API）

## 背景

已连接 Xiaomi 设备的 `copy_manga` 来源包为上游 `copy_manga` 1.4.2。脚本在章节 API 返回限流状态时使用 `setTimeout` 等待后重试；上游脚本也会从响应中解析等待时间，缺失时默认等待 40 秒（[上游脚本](https://github.com/venera-app/venera-configs/blob/main/copy_manga.js)）。当前 QuickJS 环境此前没有计时器，导致这条路径以 `ScriptExecution` 失败；章节 source call 原有 10 秒上限也短于来源要求的等待时间。

## 决策

- QuickJS 兼容层提供 `setTimeout` 和 `clearTimeout`，不提供 `setInterval`。
- 计时器通过明确允许的 `timer.sleep` / `timer.cancel` Host API 实现；脚本不能访问线程、Executor、Android `Context` 或任意 Java 对象。
- 每次等待限制在 0–120 秒；每个来源调用最多 8 个活动计时器。调用结束或取消时清除其计时器；`clearTimeout` 取消对应宿主等待。
- `comic.loadEp` 使用 60 秒调用超时；其他来源调用仍保持原 10 秒超时。

## 理由与后果

该实现满足已验证来源的限流等待契约，同时限制脚本占用资源的时间和数量。设备真实源配合 QuickJS Host timer 有 JVM 回归；Host timer 等待与上游源站行为仍需在用户正常使用新 APK 时确认。上游脚本没有被复制或修改。

## 考虑过的替代方案

- 直接在脚本中立即执行定时回调：会跳过来源服务端要求的限流等待，并可能加重限流，拒绝。
- 为脚本暴露通用线程或 Executor：越过安全边界，拒绝。
- 全局把所有来源调用超时提高到 60 秒：扩大了所有来源的资源上限，拒绝；只延长章节图像请求。
