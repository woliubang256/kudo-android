# Agent Note: 长时间浏览后的主站限流与图片请求退避

Status: implemented

## Problem

用户反馈长时间刷帖后各页面提示请求频繁，图片也经常需要重试，同网络浏览器正常。DiscourseClient 对 429 仅向当前调用者抛错，没有共享冷却；头像元数据仅缓存 60 秒，首页订阅在其他底部页面仍活跃；图片使用独立客户端且没有限流退避。这些是已确认的保护缺口，尚不能据此认定线上首次 429 的确切来源。

## Decision

RequestCooldown 为主站调用提供统一冷却，DiscourseClient 在构造网络请求前检查它，在收到 429 时记录截止时间。当前单元覆盖请求层；辅助请求节奏与图片接入分开验证、提交。测试只使用离线夹具，不向真实社区制造限流。

共享冷却按 scheme/host/port 隔离，解析 Retry-After 整数或 HTTP 日期以及 Discourse wait_seconds（包括 extras）。使用单调时钟保存截止时间，只延长不缩短；无有效等待信息时退避 15 秒。冷却中的新请求直接返回剩余秒数，不排队、不自动恢复或重放写操作。原有已经在途请求可以完成；会话检查不能清除仍有效的限流。不同来源的推送、GitHub 与第三方图片不受主站冷却牵连。

## Existing note audit

检索活跃笔记的限流、429、头像、首页刷新与图片。与[请求契约](../../implemented/architecture/2026-10-07-discourse-data-contract.md)部分重叠：新增冷却，保留同源与写请求不重放；与[首页推送](../../implemented/feature/2026-10-08-home-live-refresh.md)部分重叠：落实已有的页面不可见即暂停约束；与[头像展示](../../implemented/feature/2026-10-08-avatar-presentation.md)部分重叠：调整元数据缓存节奏，保留状态语义与账号隔离。与[DoH 网络](../../implemented/feature/2026-10-12-doh-network.md)及[图片容量](../../implemented/bug-fix/2026-10-13-image-capacity-resolution.md)边界相关，不改变路由及按需 HEAD。通知流、详情推送、阅读历史与各图像解码笔记无决定翻转。没有完全吸收或过时提案。

## Alternatives considered

- 每个页面分别处理 429：改动局部且能针对各接口调整，但无法阻止其他页面、头像和图片继续请求，共用冷却才能约束同一来源。
- 冷却后自动排队重试所有请求：用户无需再操作，但会积累过时请求并在恢复时形成突发，写入结果未知时还会重复操作；冷却期间立即返回可重试错误。
- 停用阅读同步及所有实时订阅：最直接减少请求，但会破坏浏览历史、已读与新回复功能；仅减少辅助请求并约束页面生命周期。
- 图片无限重试：更容易掩盖短时故障，但限流时会持续放大请求；只补一次可恢复的连接失败。

## Testing

DiscourseClientTest 的两项新增用例先失败，证明分类请求和 CSRF/写入在 429 后仍发到服务器，记录为 artifacts/request-cooldown-repro.txt。修复后验证冷却期间请求数不变、会话重置不清除等待、错误显示剩余秒数及到期正常恢复。

RequestCooldownTest 覆盖最长服务端等待、整数/日期/JSON/非法等待值、并发不缩短、同源路径共享和不同 scheme/host/port 隔离；单调计时不随系统时间变化，HTTP 日期优先以服务端 Date 为参考，读取媒体错误体不消费原流。DiscourseClient、RequestCooldown、DoH 写入策略及 Feed/Topic 契约的定向回归通过，日志 artifacts/request-cooldown-regression-fixed.txt。原有测试中紧接 429 发起后续操作的场景使用虚拟时钟推进至冷却结束，保留已加载列表和写入确认断言。

## Consequences

未知范围的 429 保守暂停同一来源，某些接口独立限额也会短时影响其他页面；这是防止持续触发全局限流的代价。到期不自动重放历史请求，用户主动重试或既有订阅下一轮检查恢复访问。已在途请求不被强制取消。线上 Cloudflare、账号或网络差异仍需实际长时间使用验证，离线测试不能证明所有图片失败都有相同原因。
