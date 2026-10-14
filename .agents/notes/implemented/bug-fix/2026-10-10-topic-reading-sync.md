# Agent Note: 实际阅读楼层同步与首页缓存更新

Status: implemented

## Problem

详情只有读取正文的 GET，没有阅读进度写入。即使未读入口修正为 unseen，App 中读过的内容仍可能留在服务器未读列表；已有首页分页缓存也不会因阅读变化更新。

## Decision

使用 Discourse 231b4c9937 的 POST /topics/timings 协议：topic_id、topic_time、timings[楼层号] 都按实际前台可见时长上报，单位毫秒。复用现有 Cookie、CSRF 和同源传输。空内容、加载/预取、楼中楼摘要、被忽略折叠的内容不计时。长正文采用保守规则：正文底部到达可视区域且至少半个较小视口可见后才计入楼层，不把仅露头当读完。

累计至少1秒、每5秒合并提交；离开/后台及时提交尚未发送部分。失焦、弹窗、图片预览、回复编辑器和导航覆盖时暂停；连续3分钟无可见范围变化暂停，单楼层单次会话累计最多6分钟。离页提交使用导航外的会话作用域，换号取消队列和在途请求；网络结果未知不自动重试，避免重复累计。只在服务端确认后使 unread/new/read 的缓存失效，公共列表和其他标签保持滚动与分页。

阅读计算、传输、Compose 可见性、缓存更新分成小单元分别提交；真实账号不执行测试阅读写入，使用离线服务器和屏幕夹具验收。

[逐楼未读蓝点](../feature/2026-10-10-post-unread-indicator.md)解析服务器 read 并增加实际曝光1秒后的本地渐隐反馈；计时、上传确认与首页缓存契约继续由本篇约束。本地反馈不等于服务器确认，不提前失效首页缓存。

[只读楼中楼](../feature/2026-10-10-reply-timeline-and-preview.md)将子回复保留在主时间线，讨论面板改为预览并不再计时；只有跳到原楼层后的实际正文曝光计读。主时间线可以同时显示父帖与子回复，实际可见的两者均可计读，不能继续假定子回复只存在弹窗里。

## Related decisions

[访问历史修复](2026-10-14-topic-visit-history.md)部分取代下述“不用 track_visit”的取舍：登录用户真正打开主题时独立记录访问，预取与补全仍只读。timings 继续只负责实际曝光阅读进度，不能将访问当成读完。

[返回列表保持阅读现场](2026-10-11-feed-return-stability.md)部分取代本篇服务端确认后立即失效当前阅读筛选的策略：当前列表保留条目与分页并提示刷新，其他阅读筛选仍失效。这里的timings计时、服务端确认和账号隔离约束继续保留；避免返回时移除条目、重载第一页而破坏阅读位置。

按阅读、timings、已读、缓存检索活跃笔记。[会话筛选](../../implemented/feature/2026-10-08-session-feed-filters.md)的“本次不新增阅读写入”被本篇部分取代，接口筛选与账号边界保留；[首页横向分页](../../implemented/feature/2026-10-10-home-pager.md)的缓存约束部分重叠；[更新提示](../../implemented/feature/2026-10-08-home-live-refresh.md)仍负责远端新内容提示，不因阅读更新扰动公共列表。无完全吸收或过时提案。

## Alternatives considered

- 打开详情就上报最大已加载楼层：实现最少且列表立刻变化，但深链、分页、楼中楼补全都加载未读内容，会错误标记大量楼层，因此只接受实际正文曝光。
- GET detail 添加 track_visit=true：可记录访问且改动很小，但不能代替楼层阅读时间，预览与补全也使用 detail，因此保留只读 GET，使用明确 timings 写入。
- 原样复用网页自动重试：短暂错误更容易恢复，但原生请求网络失败可能已经写入，累计时长再次提交会重复，采用一次提交、服务端成功后更新缓存。

## Testing

TopicReadingProgressTest 的4项离线测试通过，覆盖闪现不提交、暂停不累计、只上报深链实际楼层、单调计时、闲置暂停与6分钟上限（artifacts/reading-progress-fixed.txt）。新增能力最初因阅读跟踪器不存在而编译失败，不将此称为已有算法的行为复现。Compose 集成另以未接入正文曝光的旧界面验证行为失败。测试不触碰真实社区阅读状态。

TopicReadingSync 使用导航外作用域的串行队列，每次提交核对账号；换号取消在途请求并丢弃旧队列。3项 MockWebServer 测试检查 CSRF、毫秒表单、204成功、失败不重试、访客与旧账号拒绝，以及获取 CSRF 期间换号时不再提交旧正文计时。

正文界面行为复现先在未接入 PostReadingContent 的 TopicPost 上超时失败（artifacts/reading-ui-repro.txt）。可见性使用 onLayoutRectChanged 跟踪祖先滚动与位置变化；几何必须在组合阶段读取，不能只在 SideEffect 里读取，否则不会订阅位置状态的变化。主列表扣除底部操作栏，楼中楼弹窗使用自己的视口。长文用实际末尾位置验证，而非固定次数的滑动，恢复计时后先等待界面完成组合再等待真实时间。Compose 夹具验证短文与摘要排除、长文开头不计读/末尾计读、遮挡及访客/忽略内容、旧会话销毁仍使用原账号回调。

FeedPageCacheTest 验证阅读成功只失效 unread/new/read；已缓存书签和当前 latest 不重新请求话题，计数单独更新，重复epoch与其他账号通知无效，回顶请求不增加。

5项真机阅读用例通过（artifacts/reading-ui-fixed.txt），其中详情页集成验证楼中楼摘要不计读，打开回复弹窗后只由其正文视口计读。ImageGalleryHost 和 TopicActionsHost 向下提供合并的遮挡状态，回复编辑器由导航层暂停，关闭后恢复实际正文曝光。

集中验证：160项 JVM 全部通过，Debug Lint 0错误/30既有警告/1hint（artifacts/feed-reading-final-build.txt）；62项相关真机回归通过，另5项阅读用例通过，共67个不同用例。补跑16项同样通过（artifacts/feed-reading-device-remaining.txt）。Release R8构建和Lint通过（artifacts/feed-reading-release-build.txt），正式包 Kudo-0.1.1-release.apk，versionCode 1002，校验为正式签名且非 debuggable。未覆盖安装正式包，因为手机现有 Debug 签名不同，保留其数据和登录。最后通过首页深链启动的人工复查被自动审批拒绝，仅返回 blocked by policy，没有继续尝试绕过；真实账号的阅读写入端到端仍未验证。

## Consequences

保守长文判定可能较网页版稍晚更新未读；进程被系统直接结束时最后一小段可能无法送达。服务器使用最高实际读到楼层更新阅读位置，深链跳楼与网页同样可能推进位置，不能据此声称中间楼层逐条已阅读。
