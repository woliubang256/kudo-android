# Agent Note: 主时间线显示实际回复对象

Status: implemented

## Problem

主时间线保留 reply_to_post_number 却不显示回复对象，用户无法看出当前楼层在回复谁。楼中楼摘要已有归属规则，不能因补主时间线而改变其直接回复省略规则。

## Decision

解析服务器 reply_to_user，主时间线在作者信息下用回复箭头、小头像和昵称显示显式回复目标；点击走已有楼层定位。服务器未返回作者时依次复用已加载的实际父楼层、主帖作者，仍缺失则显示 #楼层。只接受早于当前楼层的正数目标，不为名称另发请求，不从引用或讨论根猜作者。正文、阅读及楼中楼分组不变。

## Existing note audit

[楼中楼归属](../../implemented/feature/2026-10-14-nested-reply-attribution.md)部分重叠，其摘要和弹窗规则保留，本篇只补主时间线。[楼层跳转](../../implemented/feature/2026-10-10-reply-timeline-and-preview.md)保留既有跳转和预览不计读边界。未发现其他回复对象提案。

## Alternatives considered

- 仅查已加载父帖无需增加字段，但跨页或父帖不可访问时即使服务器给了 reply_to_user 也无法显示名字；优先使用服务器明确返回的对象。
- 点击头像进入用户主页能复用作者入口，但无法查看被回复内容；整个回复对象入口定位实际父楼层，作者主页仍由原头像入口提供。

## Testing

PostReplyRecipientUiTest 的两条用例先在旧实现上失败，记录为 artifacts/post-metadata-repro-device.txt。修复后的四条用例与 PostDisplayUiTest、NestedReplyAttributionUiTest 共 17 项在 RMX2202 / Android13 全部通过，记录为 artifacts/post-reply-fixed-device.txt。覆盖已加载与跨页目标、缺失作者、直接回复主帖、无目标及非法楼层；跨页点击确实调用原楼层导航，窄屏 1.5 倍字号不遮作者。PostDisplayMetadataTest 解析回归与 Debug / AndroidTest 构建通过，记录为 artifacts/post-reply-fixed-build.txt。

## Consequences

主时间线能明确呈现实际回复对象，不额外读取远端父帖。私有或已删除父楼层可能无法访问，沿用现有定位错误提示。只知道楼层时不能虚构昵称或头像。头部仅在有合法目标时增加必要的回复关系入口；原有楼中楼直接回复省略规则不变。
