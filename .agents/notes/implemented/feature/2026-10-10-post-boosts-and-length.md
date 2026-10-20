# Agent Note: 回帖字数与帖子Boost

Status: implemented

## Problem

普通回帖已遵循[网站字数规则](../bug-fix/2026-10-10-reply-length.md)；详情此前没有Boost短回复及其展示。用户要求底部右侧只保留点赞和Boost。

## Decision

接入Boost的帖子字段、独立写接口、输入与胶囊展示。Boost依据实际网页插件的16字符、最多5个表情规则计数，仅明确发送时POST /discourse-boosts/posts/{postId}/boosts；依据can_boost显示权限，失败保留输入且不自动重试。Boost挂在原帖子，不进入主题楼层流或阅读计时。

Unicode序列表和有效shortcode表取自2026-10-10网站pretty-text/emoji/data，事实数据放在resources/emoji。最长序列匹配覆盖旗帜、ZWJ和肤色，其他字符仍按UTF-16；标准别名忽略大小写，自定义名称由/emojis.json补充。3418个序列均与网站emojiReplacementRegex比对为单一表情。可拖动输入面板采用CommunitySheet，发送中禁止关闭，确认后清空；主帖、回复与楼中楼复用头像加短内容胶囊，保留服务端表情图片。

Boost输入底部只显示“当前/16字”，有效表情各计1字，合并在总数内。最多5个表情的校验继续执行，仅超限时显示明确原因，空输入不显示错误；规则读取中的状态继续保留，避免自定义表情规则未就绪时允许发送。

主帖和回复都显示已有Boost，保留头像与服务端cooked的短内容；发送成功更新同一帖子在主列表、楼中楼和置顶原帖的副本。写操作复用Cookie、CSRF和逐帖互斥，不由UI乐观声称已发送。

点赞、取消点赞和更换回应与 Boost 的数据及权限相互独立。回应写接口直接返回帖子或写后回读 `/posts/{id}.json` 时，仓库按原始 JSON 是否包含 `boosts`、`can_boost` 分别合并：缺失字段沿用操作前的最新帖子值，明确返回的空数组和 `false` 则正常覆盖。该规则只用于回应确认，不改变完整帖子首读的默认值，也不绕过服务端 Boost 权限。逐帖写请求仍互斥，完成后可继续进行另一种操作。

帖子操作栏移除左侧灰色“正文 / 回复”标记，保留右侧回复入口和楼层目标。Boost 在各楼层及底部主帖操作栏统一复用官网 Font Awesome rocket 矢量；官网插件 @icon=rocket 的只读证据位于 artifacts/boost-web-source.js。火箭分别为17dp和19dp，与爱心实际图形高度接近；使用主题 onSurfaceVariant 的75%灰色，并继续服从禁用透明度。列表点赞数量用空心爱心表示，帖子和底部已选爱心使用 Discourse love 红色（#FA6C8D），其他品牌选中色继续使用 LogoYellow。

当前查看者与帖子作者相同时，主帖底部和楼层操作栏不再提供 Boost。可编辑内容在原 Boost 位置显示编辑图标并复用 TopicActionsController.edit；没有编辑权限时编辑图标保持禁用，绝不退回 Boost。未登录用户和他人帖子仍按 canBoost 展示原火箭入口，避免把作者权限误判为 Boost 权限。

## Existing note audit

已检索回帖、编辑器、楼中楼和阅读笔记。[账号与草稿](../../implemented/feature/2026-10-07-account-and-composer.md)的账号/楼层草稿隔离与确认后清理契约继续成立；[酷安式表单](../../implemented/feature/2026-10-10-coolapk-forms.md)约束输入与可拖动面板；[回应](../../implemented/feature/2026-10-09-topic-author-and-responses.md)的短按/长按行为保留；[阅读同步](../../implemented/bug-fix/2026-10-10-topic-reading-sync.md)对遮挡暂停与真实正文曝光继续成立。无完全吸收或过时提案。

## Alternatives considered

- 并列展示字数和表情数能持续说明两种上限，但用户认为重复且占空间，使用总字数计数并在超限时说明具体限制。

- 普通回帖固定16字实现最少，但网站可调整配置，使用服务端设置并在失败时明确提示。
- Boost复用/posts发布链路可以复用草稿和刷新，但网页使用独立协议及权限，不能生成普通楼层。
- 请求后立即乐观追加胶囊可以更快反馈，但网络结果不明会误报成功，采用确认后更新，发送期间展示忙碌状态。
- 回应后无条件保留原 Boost 状态能避免局部响应清空数据，但会吞掉服务端明确返回的权限撤销或删除；因此逐字段区分缺失与明确值。直接移除 Boost 权限检查虽能恢复点击，却会允许服务端不支持的操作，不采用。

## Verification

精简计数先新增离线输入用例，在旧版因缺少“0/16字”失败（artifacts/boost-counter-repro-ui.txt）。修改后2项真机用例通过（boost-counter-fixed-ui.txt），覆盖文字与Unicode/自定义shortcode合计、16字含5表情可发送、6表情及17字明确提示、纠正后恢复发送，以及发送确认前保留内容。PostBoostsTest的3项JVM测试及Debug/AndroidTest构建通过（boost-counter-fixed-build.txt）；真机同签名install -r覆盖，保留账号数据。

PostBoostsTest验证16/17字、5/6表情、旗帜/ZWJ/肤色、UTF-16普通补充字符、标准别名、自定义shortcode、CSRF独立接口及失败不重试。TopicViewModelTest验证仅确认后更新与回调、Boost和点赞共享逐帖互斥。真机夹具检查输入边界、忙碌禁用、失败保留与成功清理、主帖和楼中楼胶囊、深色大字、拖动关闭及各玻璃材质按钮。

新增自己的主帖底栏、自己的回复和无编辑权限的自有回复回归用例先在旧实现复现失败（找不到编辑主帖、仍存在Boost），修复后三项真机用例通过；同时验证了自己的内容只显示编辑、他人内容继续显示Boost。

回应与 Boost 独立性先用 `PostBoostsTest` 的六项新增测试在旧实现复现失败：已有 Boost 被清空，点赞后发送又因 `canBoost=false` 被拒绝。修复后该类及 `TopicViewModelTest`、`ThreadAndDecorationTest`、`TopicInteractionContractTest` 共 39 项 JVM 测试通过，覆盖插件/核心点赞、撤销、切换表情、写后回读、逐字段明确覆盖、无权限不得被提升以及点赞后发送保留回应。`ReactionBoostIndependenceUiTest` 两项真机离线用例分别操作主帖底栏和回复操作行，验证点赞后已有胶囊仍在、可发送新 Boost、发送不取消点赞、随后取消点赞仍保留两个胶囊且可再次打开输入。夹具使用独立客户端拦截响应，不向真实社区点赞或发送内容；截图 `artifacts/boost-reaction-main.png`、`artifacts/boost-reaction-reply.png` 中中文与两条 Boost 均可读，结果见 `artifacts/boost-reaction-device.txt`。

## Consequences

操作栏调整后，TopicBoostsUiTest 与 TopicReadingUiTest 11项真机测试通过（rocket-actions-device.txt）；长文末尾测试改为定位仍存在的Boost操作，继续验证末尾实际曝光才标读。新增主帖/底部红色像素验证与有用操作/回复楼层检查2项通过（heart-rocket-device.txt）。构建通过，post-actions-rocket.png 展示复用官网火箭后的操作行。

火箭尺寸与灰度复现测试先在旧实现因尺寸失败（boost-compact-repro-device.txt），调整后同机通过（boost-compact-device.txt）；同时检查楼层与底栏的实际高度、图形灰度，构建通过。

网页插件的短文本规则或字段可能变化，需要按真实返回确认。网络结果未知时保留输入但不自动重试；用户可在社区确认。

回应响应缺少 Boost 字段时，其他客户端同时删除 Boost 或变更权限的结果需要等待下一次完整帖子读取才能同步；明确返回的变更仍即时生效。完整首读不回退旧值，避免永久保留已经失效的状态。
