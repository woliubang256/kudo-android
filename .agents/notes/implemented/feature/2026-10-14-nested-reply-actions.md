# Agent Note: 楼中楼弹窗提供点赞和回复

Status: implemented

## Problem

楼中楼弹窗只能预览和跳楼，点赞或回复必须先离开讨论。用户批准按酷安截图增加两个操作，保持已有回复关系、头像、正文、图片及原楼层跳转。

## Decision

弹窗保留底部液态面板，每条原评论及子回复采用头像在左、内容在右的平面布局。正文下方左侧显示已有更新时间，右侧放紧凑的点赞图标与回应数、回复图标与直接回复数；已有服务器回应状态和撤销权限沿用 TopicReactionButton。用户进一步确认点赞和原楼层一样使用爱心，其他回应仍显示其表情，长按可选择已有回应种类。

操作传递实际 Post，复用 TopicScreen / TopicViewModel 的认证、单帖 busy 锁及服务器确认更新。点赞不关闭讨论，同时更新主时间线和辅助缓存；回复调用现有 replyTo 关闭面板并打开实际楼层编辑器。忙碌或无服务器权限时不能重复点赞；关闭/归档话题不能回复。面板退出动画、上层面板及页面遮挡期间禁用点击；忽略作者的内容保持隐藏，面板仅显示其时间和跳楼，不增加互动。预览仍不计读，不增加独立缓存、网络接口或自动写入。

## Existing note audit

检索活跃笔记的楼中楼、只读、互动、编辑器与阅读。[原楼层与只读预览](../../implemented/feature/2026-10-10-reply-timeline-and-preview.md)部分重叠，本篇取代“弹窗无点赞和回复”，保留主时间线、缓存、跳转和不计读边界，不恢复收藏、Boost 或更多菜单。[楼中楼分组](../../implemented/feature/2026-10-10-nested-replies.md)保留父链及分页；[实际回复对象](../../implemented/feature/2026-10-14-nested-reply-attribution.md)保留归属和 #楼层旁的跳转图标。[主题操作](../../implemented/feature/2026-10-09-topic-actions.md)、[阅读同步](../../implemented/bug-fix/2026-10-10-topic-reading-sync.md)和编辑器保留实际 ID、写确认与阅读曝光契约。没有完全吸收或过时提案。

## Alternatives considered

- 操作继续只在原楼层：所有写入入口集中且跳转容易验证，但用户明确要求在讨论里直接点赞和回复，保留共享写入逻辑并增加两个入口。
- 弹窗自行维护点赞数量：反馈立即且局部实现简单，但会让主时间线、预取缓存和服务器状态分离；复用服务器确认后的同一 Post。
- 直接复用整张原楼层卡片：现有功能完整，但会把 Boost、收藏、更多菜单和阅读计时一起带入预览；只复用回应按钮及实际楼层回调。
- 面板使用酷安的拇指赞图标：与参考截图一致且容易辨认，但用户确认这里要和外部原楼层使用同一爱心和长按表情回应；完整复用既有回应按钮，不增加图标分支。

## Testing

NestedReplyActionsUiTest 的按钮展示和回复两项先在旧实现复现缺少入口（artifacts/nested-replies-2026-10-14/actions-repro-device.txt）。爱心与原楼层图形一致性的像素检查先在拇指实现失败（actions-heart-repro-device.txt），复用既有爱心按钮后通过；不保留第二套图标或写入逻辑。

ThreadAndDecorationTest 共 9 项 JVM 测试通过，新增两项用 MockWebServer 和真实 TopicViewModel 验证只在辅助缓存中的回复可以点赞且单帖锁阻止重复请求，以及同一回复后来进入主列表时赞/撤赞的服务器确认状态同时更新两处缓存，旧 Post 参数仍使用最新权限。日志 actions-jvm-lint.txt，服务器确认数量为 27/26，不使用本地加减。

RMX2202 / Android 13 上 23 个不同定向真机用例均已验证：9 项新操作用例、归属弹窗、原楼层时间线、楼中楼、阅读曝光、原回应按钮和图片预览返回。首轮 21 项通过（actions-final-device.txt）；图片夹具遗漏完成游标而显示“查看更多回复”，补齐游标后还暴露测试误将画廊随机 origin 当作 galleryKey，改为校验实际原图及已解码来源，再验证返回讨论后的操作与跳楼（actions-gallery-final-device.txt）。阅读夹具曾在模拟外部遮挡时点击已禁用的跳楼，改为先解除遮挡，回调中暂停阅读检查未读状态后恢复曝光；保留遮挡禁用及只有原正文计读的断言（actions-final-regression-device.txt）。未改动画廊 origin 或阅读上传协议。

Debug 与 AndroidTest 构建和最终 Lint 通过（actions-heart-fixed-build.txt、actions-final-lint.txt）。版本显式使用真机已有的 2005 / 0.2.3，核对相同开发签名后以 adb install -r 覆盖主包、install -r -t 覆盖测试包，保留登录数据。已查看 nested-reply-actions-light.png、nested-reply-actions-dark-large.png，头像与正文对齐，底部时间/爱心/回复按钮和 #楼层/跳转图标在 280dp、130% 字号下仍可见。写入使用测试替身和本地服务器，不向真实社区发帖或点赞。

## Follow-up decision

The dialog rendering scope was refined by [Nested reply dialog shows replies only](./2026-10-15-nested-reply-dialog-replies-only.md): the root remains in the model for attribution and pagination, but its duplicate card is no longer rendered. The interaction contracts described here continue to apply to every reply card that is rendered.

## Consequences

长作者名和大字号会减少正文宽度，需检查右侧按钮与跳楼入口不被挤出。计数只使用服务器状态，等待网络确认时保持旧计数并禁用重复操作。

面板现在可直接互动，但摘要和讨论预览继续不计读；主时间线、缓存、权限和实际回复目标保持同一契约。点赞或撤销能否执行仍取决于服务器返回的权限，忽略内容不会因新增操作被揭示。
