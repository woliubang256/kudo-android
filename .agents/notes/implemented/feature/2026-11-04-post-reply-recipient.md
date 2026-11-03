# Agent Note: 主时间线显示实际回复对象

Status: implemented

## Problem

主时间线保留 reply_to_post_number 却不显示回复对象，用户无法看出当前楼层在回复谁。楼中楼摘要已有归属规则，不能因补主时间线而改变其直接回复省略规则。

## Decision

解析服务器 reply_to_user，主时间线在作者栏右侧、手机图标前，用朝右的回复箭头、小头像和昵称同行显示显式回复目标；点击走已有楼层定位。手机图标仅在有设备信息时显示，位于作者栏最右侧；更多按钮位于底部操作栏，紧接回复按钮右侧。作者与回复对象分享扣除设备图标后的宽度，作者占较多空间，长昵称单行省略，不单独增加一行。回复入口和更多按钮保留 48dp 触控区域及完整无障碍名称；底部空间不足时解决方案仅显示图标，给回应汇总保留触控宽度。服务器未返回作者时依次复用已加载的实际父楼层、主帖作者，仍缺失则显示 #楼层。只接受早于当前楼层的正数目标，不为名称另发请求，不从引用或讨论根猜作者。正文、阅读及楼中楼分组不变。

## Existing note audit

[楼中楼归属](../../implemented/feature/2026-10-14-nested-reply-attribution.md)部分重叠，其摘要和弹窗规则保留，本篇只补主时间线。[楼层跳转](../../implemented/feature/2026-10-10-reply-timeline-and-preview.md)保留既有跳转和预览不计读边界。未发现其他回复对象提案。

## Alternatives considered

- 仅查已加载父帖无需增加字段，但跨页或父帖不可访问时即使服务器给了 reply_to_user 也无法显示名字；优先使用服务器明确返回的对象。
- 点击头像进入用户主页能复用作者入口，但无法查看被回复内容；整个回复对象入口定位实际父楼层，作者主页仍由原头像入口提供。

## Testing

PostReplyRecipientUiTest 的两条用例先在旧实现上失败，记录为 artifacts/post-metadata-repro-device.txt。修复后的四条用例与 PostDisplayUiTest、NestedReplyAttributionUiTest 共 17 项在 RMX2202 / Android13 全部通过，记录为 artifacts/post-reply-fixed-device.txt。覆盖已加载与跨页目标、缺失作者、直接回复主帖、无目标及非法楼层；跨页点击确实调用原楼层导航，窄屏 1.5 倍字号不遮作者。PostDisplayMetadataTest 解析回归与 Debug / AndroidTest 构建通过，记录为 artifacts/post-reply-fixed-build.txt。

同行布局的两条新增用例先在旧版失败（artifacts/reply-inline-repro-device.txt），修复后回复对象、正文显示及设备头部共 15 项真机用例通过（artifacts/reply-inline-fixed-device.txt）。断言回复对象位于作者右侧、垂直范围与作者相交且不越过更多按钮，覆盖 320dp / 1.5 倍字号和长目标昵称。真机截图 reply-recipient-inline-light.png 与 reply-recipient-inline-dark-large.png 用于检查中文可读、右向箭头及手机图标位置；主包仅覆盖安装，签名及 3002 / 0.3.1-dev 版本与已安装开发版一致。

手机图标右置、更多下移的三个位置用例先在旧版失败（artifacts/reply-action-position-repro-device.txt）；窄屏解决方案挤压回应汇总也先复现失败（artifacts/reply-actions-summary-repro-device.txt）。修正后共 25 项不同真机用例通过：初轮 23 项成功，收尾复验两项失败用例及受影响的互动行，共 11 项成功，分别见 artifacts/reply-action-position-fixed-device.txt 与 artifacts/reply-action-position-final-device.txt。覆盖有无回复对象、长昵称、机型点击、更多菜单、收藏与回复的实际楼层、解决方案点击和禁用状态；楼中楼用例等待跳楼结束并滚动到底部入口，回复图标使用现有测试标记定位。浅色及深色 320dp / 1.5 倍字号截图用于确认图标位置、中文可读和紧凑解决方案仍保留回应数量；布局适配不改既有回复解析与导航规则。

## Consequences

主时间线能明确呈现实际回复对象，不额外读取远端父帖。私有或已删除父楼层可能无法访问，沿用现有定位错误提示。只知道楼层时不能虚构昵称或头像。头部仅在有合法目标时增加必要的回复关系入口；原有楼中楼直接回复省略规则不变。
