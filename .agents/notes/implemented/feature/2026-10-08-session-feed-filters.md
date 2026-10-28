# Agent Note: 登录态筛选与新话题计数

Status: implemented

## Problem

固定首页筛选向访客暴露未读和新话题，同时缺少官网截图中的个人列表。会话变化时原 FeedViewModel 没有账号边界，退出仍可能保留个人筛选及数据。新话题数量不能从单页长度推定。

## Decision

筛选切换清空和类别跳转的部分由[首页横向分页](2026-10-10-home-pager.md)替代，分类排除三个个人筛选的部分由[分类内完整筛选](2026-10-10-category-feed-filters.md)替代，现有权限、计数和账号边界保留；下述原始切换理由供重访。

访客按最新、热门、排行榜、类别显示；账号按未读、最新、新、热门、排行榜、我的帖子、已读、书签、类别显示。首页登录默认未读、退出默认最新；分类详情保留最新默认，只提供适用于分类的筛选。类别复用底部分类入口，个人标签直接更新首页列表。

这组首页顺序作为各身份的默认值保留；[按身份编辑首页 Tab](2026-10-29-editable-home-tabs.md)允许本机自定义顺序、显示项及分类/标签来源，启动选择配置首项。访客权限、个人筛选校验和账号切换清数据边界不变，分类详情不采用首页配置。

列表使用 Discourse 的 latest / hot / top / unseen / new / posted / read / bookmarks 过滤接口，保留服务端分页链接。FeedFilter 统一接口许可、登录要求和显示顺序，类别作为单独页面动作。新话题数量使用 /u/{username}/topic-tracking-state.json 的跟踪结果，按 topic_id 去重；created_in_new_period 为真、last_read_post_number 为空、notification_level 为空或至少为2，且未看、未删除才计入新话题。账号启用 unified_new_enabled 时按官网约定同时计入跟踪中的未读话题。分类页不显示首页全站计数。数量与分类元数据均为辅助请求，失败不得覆盖成功的话题；数量未知时只显示“新”。

FeedViewModel 持有账号身份，会话切换立即取消旧请求并清空结果、计数和分页。界面只呈现当前账号对应的 FeedState，防止 LaunchedEffect 尚未同步时短暂显示旧账号数据。私有筛选在状态层再次校验；退出不能请求个人列表。刷新、分页和切换使用同一状态边界。

刷新任务与分页任务分别持有 Job，共享 generation；切换筛选或会话撤销两者，辅助请求仅在对应 generation 写入状态。分类页生命周期恢复时刷新已有信息流；首次进入仍由 ViewModel 初始加载。原首页恢复自动刷新决定被[首页更新提示](2026-10-08-home-live-refresh.md)部分取代：首页返回时只检查差异，保留阅读位置，由用户点击刷新后更新列表及计数。DiscourseClient.getArray 复用同源、重定向、Cookie 与错误分类，只为显式数组调用接受顶层 JSON 数组。

## Protocol evidence

公开 Discourse 的 lib/discourse.rb 列出 posted/read/bookmarks 等过滤器；ListController 为其生成同一 TopicQuery 列表路由。UsersController.topic_tracking_state 返回顶层数组，TopicTrackingStateItemSerializer 和前端 topic-tracking-state.js 定义判断字段。参考文件只保存在 artifacts/discourse-*，独立实现客户端契约；用户账号和跟踪数据不提交仓库。

## Related decisions

检索首页、Feed、Tab、会话、计数；与[顶部折叠](../../implemented/feature/2026-10-08-home-tabs-and-collapse.md)部分重叠、共同组成首页，与[原生客户端](../../implemented/architecture/2026-10-07-native-community-client.md)会话和只读边界兼容；原有密度与玻璃笔记只需同步首页参数，不重写其余决定。无过时提案需要归档。

## Alternatives considered

- 为个人标签跳转已有个人页和收藏页：复用最多，但会失去首页统一筛选、吸顶和分页行为；使用话题列表接口直接更新首页内容。
- 仅在 Compose 隐藏私有标签：代码少，但旧状态、分页及异步返回仍可能保留个人结果；状态层同时绑定账号并撤销旧任务。
- 数量复用新话题第一页长度：请求少但显示的是分页长度；跟踪接口提供官网判断所需字段，失败时明确省略数字。

## Testing

FeedRepositoryTest 先复现三个个人列表被旧白名单拒绝，再验证各自路径和分页；数组测试覆盖编码用户名、同源重定向、计数去重、已看/已读/静音排除、合并新话题、401 与错误对象。FeedSessionTest 覆盖访客拒绝私有筛选、登录与换号默认项、退出清空、辅助接口失败、分页期间退出、分页重试和重复游标。24项 JVM 测试通过。测试最初把真实网络等待绑定到虚拟5秒超时，修正为 Dispatchers.Default 上的实际超时，功能断言不变。

FeedHeaderUiTest 检查两组标签、每项点击与横向滚动、类别回调、退出恢复最新、深色130%字号及窄窗口、空列表折叠后的下拉刷新。文字检查使用逐字 bounding box：真机“未读”报告 hasVisualOverflow=true，但两字均完整落在134×93px内；截图复核通过，不用该标记替代实际裁切。

最终32项真机界面测试全部通过（68.994秒），含新增4项首页测试与原有28项；日志 artifacts/home-tabs-final-instrumentation.txt。280dp宽深色130%截图及两组Tab截图已人工检查，真实未登录首页与排行榜可加载。构建、JVM与Lint记录为 home-tabs-final-build.txt；分发APK与真机安装包SHA256一致，详见 home-tabs-apk-verification.txt。真实账号的个人列表和数量尚未线上验收。

## Consequences

访客只看到公共列表，账号得到完整个人筛选，退出不会保留个人内容。每次首页刷新增加一次跟踪请求；顶部数量仍为刷新时快照，[独立的更新订阅](2026-10-08-home-live-refresh.md)只驱动底栏刷新提示。真实个人接口需要账号及站点验证，模拟契约不能证明真实登录链路可用。原本仅依赖服务器已有阅读记录的边界，被[实际阅读同步](../bug-fix/2026-10-10-topic-reading-sync.md)部分取代：正文实际曝光后提交阅读计时，成功确认才失效相关列表缓存。

## 官网入口核对

已登录官网只读核对得到 /unseen、/latest、/new、/hot、/top、/posted、/read、/bookmarks，与全部首页项逐一对应。内部 unread 键在请求层映射到 unseen，首屏、分类和刷新探测使用同一规则；后续分页遵守服务器地址，不在本地排除自己发的帖子、置顶或重新排序。新增路径测试先失败（expected /unseen.json, actual /unread.json；artifacts/feed-endpoints-repro.txt），修复后覆盖全部首屏路径、分类路径、服务端排序与带参数的分页链接。动态首页只能同账号同时间比较，不能把不同时间的计数差异直接判为算法错误。
