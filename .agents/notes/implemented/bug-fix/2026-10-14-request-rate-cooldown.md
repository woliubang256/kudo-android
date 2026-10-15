# Agent Note: 长时间浏览后的主站限流与图片请求退避

Status: implemented

## Problem

用户反馈长时间刷帖后各页面提示请求频繁，图片也经常需要重试，同网络浏览器正常。DiscourseClient 对 429 仅向当前调用者抛错，没有共享冷却；头像元数据仅缓存 60 秒，首页订阅在其他底部页面仍活跃；图片使用独立客户端且没有限流退避。这些是已确认的保护缺口，尚不能据此认定线上首次 429 的确切来源。

## Decision

RequestCooldown 为主站调用提供统一冷却，DiscourseClient 在构造网络请求前检查它，在收到 429 时记录截止时间。当前单元覆盖请求层；辅助请求节奏与图片接入分开验证、提交。测试只使用离线夹具，不向真实社区制造限流。

共享冷却按 scheme/host/port 隔离，解析 Retry-After 整数或 HTTP 日期以及 Discourse wait_seconds（包括 extras）。使用单调时钟保存截止时间，只延长不缩短；无有效等待信息时退避 15 秒。冷却中的新请求直接返回剩余秒数，不排队、不自动恢复或重放写操作。原有已经在途请求可以完成；会话检查不能清除仍有效的限流。不同来源的推送、GitHub 与第三方图片不受主站冷却牵连。

图片客户端通过 createImageHttpClient 接入同一 RequestCooldown，每次实际网络交换（包括重定向目的地）重新检查来源并记录 429。Cookie 仍仅发到 HTTPS linux.do，其他来源每跳移除 Cookie。AppContainer 继续将该客户端交给 DohNetwork 路由，Coil 保持原有缓存与解码器。

图片同时持有最多 4 个网络响应体，许可证在读取到 EOF、读取失败或关闭时只释放一次，不能只用 OkHttp Dispatcher 的回调并发数来约束实际下载。等待许可证每 100ms 检查取消，网络调用有 45 秒总超时；取消不触发重试。只有 GET/HEAD 的临时连接错误可在 300ms 短退避后再尝试一次，HTTP 错误、证书/协议错误、一般中断和冷却错误不重试。503 的 Retry-After:0 在网络拦截器中提升到 1 秒，阻止 OkHttp 自带的立即重放；不会排队重试该响应。

原生 JNI 的非超时连接故障映射为普通 IOException，已知临时 IO 安全码参与一次连接重试，包括旧码 connection_failed 及分阶段 connect/read/write 的明确临时原因；未知 read_failed/write_failed、profile_closed、tls_record_invalid、native_unavailable 等代码不猜成临时故障。响应头已返回后的正文读取失败释放许可证并交给现有界面处理，不自动重新下载部分正文。

[DoH 地址恢复](2026-10-14-doh-image-address-recovery.md)补足首个地址失败后再次选择相同 IP 的缺口，仅短暂调整当前节点候选的顺序；本篇的一次恢复、并发及冷却边界继续生效。

## Existing note audit

[网络请求调度](../feature/2026-10-15-network-request-scheduling.md)增加429前的同源共享滑动窗口与并发控制；本篇429后的立即失败、来源隔离及图片4路保护继续保留，本地排队不等于冷却后的自动重放。

检索活跃笔记的限流、429、头像、首页刷新与图片。与[请求契约](../../implemented/architecture/2026-10-07-discourse-data-contract.md)部分重叠：新增冷却，保留同源与写请求不重放；与[首页推送](../../implemented/feature/2026-10-08-home-live-refresh.md)部分重叠：落实已有的页面不可见即暂停约束；与[头像展示](../../implemented/feature/2026-10-08-avatar-presentation.md)部分重叠：调整元数据缓存节奏，保留状态语义与账号隔离。与[DoH 网络](../../implemented/feature/2026-10-12-doh-network.md)及[图片容量](../../implemented/bug-fix/2026-10-13-image-capacity-resolution.md)边界相关，不改变路由及按需 HEAD。通知流、详情推送、阅读历史与各图像解码笔记无决定翻转。没有完全吸收或过时提案。

## Alternatives considered

- 每个页面分别处理 429：改动局部且能针对各接口调整，但无法阻止其他页面、头像和图片继续请求，共用冷却才能约束同一来源。
- 冷却后自动排队重试所有请求：用户无需再操作，但会积累过时请求并在恢复时形成突发，写入结果未知时还会重复操作；冷却期间立即返回可重试错误。
- 停用阅读同步及所有实时订阅：最直接减少请求，但会破坏浏览历史、已读与新回复功能；仅减少辅助请求并约束页面生命周期。
- 图片无限重试：更容易掩盖短时故障，但限流时会持续放大请求；只补一次可恢复的连接失败。
- 只设置 Dispatcher.maxRequests：内置队列简单且线程数量有界，但异步回调交出响应后正文可能仍在下载；响应体许可证同时约束同步与异步调用的真实网络占用，Dispatcher 另限制工作线程。

## Testing

DiscourseClientTest 的两项新增用例先失败，证明分类请求和 CSRF/写入在 429 后仍发到服务器，记录为 artifacts/request-cooldown-repro.txt。修复后验证冷却期间请求数不变、会话重置不清除等待、错误显示剩余秒数及到期正常恢复。

RequestCooldownTest 覆盖最长服务端等待、整数/日期/JSON/非法等待值、并发不缩短、同源路径共享和不同 scheme/host/port 隔离；单调计时不随系统时间变化，HTTP 日期优先以服务端 Date 为参考，读取媒体错误体不消费原流。DiscourseClient、RequestCooldown、DoH 写入策略及 Feed/Topic 契约的定向回归通过，日志 artifacts/request-cooldown-regression-fixed.txt。原有测试中紧接 429 发起后续操作的场景使用虚拟时钟推进至冷却结束，保留已加载列表和写入确认断言。

ImageHttpPolicyTest 先复现图片不共享 API 冷却、图片 429 后 API 继续请求、已交付响应体没有并发上限、临时连接错误缺少有限恢复（artifacts/image-http-policy-repro.txt）。另用原生安全码复现普通 IOException 未参与重试（artifacts/image-native-retry-repro.txt）。修复后覆盖双向冷却、异源隔离、重定向逐跳检查及 Cookie 移除、等待取消、响应体 EOF/读取失败/重复关闭释放、GET 仅一次连接重试、HTTP 及证书/协议/原生配置错误不重试、写入不重放。

ImageNetworkDeviceTest 使用本机 MockWebServer 与真实 Rust Socket/Coil 图片加载器验证 429、冷却到期解码、缓存图片在冷却期间仍可展示，以及 8 张图片并行解码不会泄漏许可证；不对外部社区制造限流。它和正文、列表图片重试与图片导出共 15 项真机用例通过（artifacts/image-http-device.txt）。

最终图片、API、冷却、原图导出与 DoH 写入策略共 43 项定向单测、Debug/测试包构建和 Lint 通过，Lint 为 0 errors / 45 warnings / 1 hint（artifacts/request-rate-final-checks.txt）；原生安全码补齐后再次执行上述两项 Coil/Rust 真机集成用例通过（artifacts/request-rate-final-device.txt）。真机开发版保持 2005 / 0.2.3、核对同一证书后使用 install -r 覆盖，保留原有数据；正式包采用更高补丁版本单独构建。

## Consequences

The shared `ErrorState` now renders a live countdown for rate-limit failures. When `retryAfter` is positive, the countdown ticks once per second and invokes the supplied retry callback once at zero; a manual retry uses the same one-shot guard, so it cannot be followed by a duplicate automatic request. Other failure kinds and rate-limit failures without a positive retry duration keep their existing manual actions.

未知范围的 429 保守暂停同一来源，某些接口独立限额也会短时影响其他页面；这是防止持续触发全局限流的代价。到期不自动重放历史请求，用户主动重试或既有订阅下一轮检查恢复访问。已在途请求不被强制取消。线上 Cloudflare、账号或网络差异仍需实际长时间使用验证，离线测试不能证明所有图片失败都有相同原因。
