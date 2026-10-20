# Agent Note: 完整公开个人主页与酷安式交互

Status: implemented

## Problem

公开个人主页只有居中头像和网页按钮，缺少用户摘要及活动。资料标题背景与用户卡片背景是独立配置，现有页面读取了错误的背景来源。

## Decision

用户已确认分四单元完成数据、沉浸式顶部、分页标签交互和不可访问帖子处理。独立实现 Kotlin 数据与界面，FluxDO e1bd839 仅用于核对字段、分页与接口，不复制 Flutter 源码。公开主页读取 /u/{username}.json、/u/{username}/card.json 和 summary.json，卡片失败不影响基本资料；背景只使用卡片背景，空值或图片失败回退头像加遮罩。

摘要保留未知统计为 null，解析侧载话题、回复、链接、互动用户、分类和徽章。动态/话题/回复/赞使用 user_actions offset，回应及 Boost 使用各自游标，投票遵循 more_topics_url，已解决使用 offset。各标签按需读取、独立缓存和重试，切换用户或账号销毁旧请求。关注和私信由权限决定，真实社区验证仅做只读操作。

顶部铺至状态栏，资料紧凑排列；下方吸顶标签可横滑切换，沿用黄色文字后渐变指示器。关注/粉丝使用可拖动面板，原生帖子楼层、用户与分类导航不退回网页。

UserProfileViewModel 的资料、卡片、摘要、各标签和关注列表独立读取；刷新推进代次并取消旧请求，页面退出或账号改变停止旧作用域，返回只恢复未完成读取。关注只采纳服务器确认，离开时未确认写入通过重新读取恢复，不自动重发。顶部尺寸、资料密度、收起后的图片材质和状态栏规则由[方形顶部](2026-10-10-square-profile-header.md)接管，连续滚动由[顶部与列表联动](../bug-fix/2026-10-10-profile-header-scroll.md)接管；窄屏长简介通过完整资料面板访问。关注列表使用有高度上限的惰性列表，避免大量粉丝同时创建视图。

当前标签的读取同时观察选中页和缓存状态；刷新清空活动缓存时自动重新加载当前页，已有缓存、正在请求和失败状态不会自动重复读取。只观察页码会让非总结页刷新后停留在加载占位，复现测试先确认这一失败再修复。

从头像入口进入时，完整资料加载前可以使用来源的已知 Member 绘制只读身份，未知统计不填零，关注、私信和资料操作等待真实权限；失败则使用原错误页。来源匹配、进出共享动画和缓存连续性由[头像到个人主页](2026-10-20-avatar-profile-continuity.md)承载，不改变本篇三接口及独立失败规则。

## Related decisions

检索公开资料、背景、用户接口、404：本篇部分取代[用户装饰](../../implemented/feature/2026-10-10-user-decoration.md)的公开主页背景选择，保留该篇签名及“我的”资料标题图约定；[个人中心](../../implemented/feature/2026-10-10-member-account-dashboard.md)保留登录账号入口；[链接](../../implemented/feature/2026-10-10-in-app-web-links.md)保留网页内导航；没有完全吸收或需否决的旧提案。

## Alternatives considered

- 直接嵌入网页个人页：功能齐全且权限提示现成，但无法满足原生酷安布局和连续的帖子/用户跳转。
- 只读取完整资料接口：少一次请求，但其 serializer 不保证卡片背景，不能满足用户明确指定的背景来源。
- 所有标签首屏一起读取：切换时即时显示，但会扩大请求量并增加限流风险；采用按需加载。

## Testing

验证卡片优先、头像回退、统计未知、分页去重与游标、回复楼层跳转、账号隔离、黄色标签横滑及吸顶、深色和大字号。各小单元提交前运行 verify-notes，最终集中运行关联 JVM 和真机测试，保留登录数据。

首轮9项真机只有同文案“关注”的测试定位误报，改为按钮标签定位，未为误报修改行为（artifacts/profile-device-first.txt）。第二轮 PublicProfileUiTest 7项、DeepLinkUiTest 4项和原个人中心5项，共16项通过（23.705秒，profile-device-final.txt）。实际小附件像素证明卡片失败回退头像；public-profile-avatar-fallback.png / public-profile-collapsed.png / public-profile-dark-large-sheet.png 已人工检查。只读真实 mqq 资料、卡片、摘要和30条回复读取成功，记录 profile-live-contract.json，真实社区未关注或发送消息。

最终46项关联JVM、Debug及测试APK构建和Lint通过（profile-contract-final-build.txt）。非总结页刷新先由新增真机用例复现失败（profile-refresh-repro.txt）；修复后重新构建和Lint通过，PublicProfileUiTest 8项全部通过（12.917秒，profile-refresh-fixed-device.txt），原有无关回归不重复运行。只读真实主页与 /404 页面验证通过（8.939秒，profile-live-final.txt）；public-profile-live.png 已确认头像铺底、渐变遮罩和白色状态栏，public-profile-live-404.png 已确认原生不可访问状态。头像异步加载完成后采集真实截图，未把早期加载占位当作头像回退结果。通过同签名 install -r 安装Debug，保留手机登录和数据。

## Toolbar reveal timing

The expanded header content fades out by the halfway point of the collapse range. The pinned avatar, name, and follow control therefore stay transparent until that point, then reveal linearly through the remaining collapse range so the two identity presentations do not overlap abruptly.

## Consequences

热门话题和热门回复共用标题下的单行元数据：分类在左侧，楼层（仅回复）、点赞和日期在右侧；标签和可选封面保持原有展示。分类优先使用摘要已解析信息，否则以保留的 categoryId 查共享完整目录。窄屏优先保留右侧数字和日期，分类单行省略，不另占一行。

2026-10-11：普通话题同行布局及窄屏130%回复分类缺失两项真机测试先失败（summary-layout-repro-device-20261011.txt），修复后两项及既有回复同行回归通过（summary-layout-fixed-device-20261011.txt）。截图 summary-topic-one-row.png / summary-reply-narrow-one-row.png 已检查左右布局。

插件权限与隐私会令部分内容不可用，必须显示局部错误而非空列表。头像铺底可能模糊，遮罩需保证文字可读。页内背景不能改变“我的”的标题图来源。

数据结构与各插件分页的细节由[公开主页协议](../architecture/2026-10-10-public-profile-data.md)承载。顶部使用用户自己的图片而非生成素材，摘要列表保持紧凑；卡片请求暂不可用时保留头像回退和独立重试。
