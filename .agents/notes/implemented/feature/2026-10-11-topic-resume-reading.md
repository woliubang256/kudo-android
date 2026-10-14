# Agent Note: 按服务端阅读进度续读话题

Status: implemented

## Problem

登录用户在 Web 读过话题后再次点击标题会进入续读楼层，App 却始终从首楼开始。现有 timings 已上报实际阅读时间，但详情解析丢弃 last_read_post_number，普通入口未将阅读进度用于定位。

## Decision

[访问历史](../bug-fix/2026-10-14-topic-visit-history.md)在登录首个打开请求增加 track_visit；本篇续读位置与相邻页补全请求仍不记录额外访问，阅读进度语义保持。

详情模型保留服务器 last_read_post_number，并计算 min(last_read_post_number + 1, highest_post_number) 作为 resumePostNumber；零、负数、缺失或null不产生目标，加一使用Long避免溢出。字段是最高已读楼层，不是像素滚动位置，继续使用现有实际正文曝光和 timings 同步。

首次详情统一恢复：普通登录用户进入读取最新进度，明确指定楼层（包括首楼）优先；游客和首次访问从头开始。首次加载成功后不再套用服务器位置，刷新沿用已有targetNumber，返回已有页面沿用其视口；关闭后重新进入创建新ViewModel，再读取服务器最新进度。

目标已在首批正文中时直接定位，不额外读取窗口。远楼层读取目标附近窗口，使用与手动跳楼相同的adjacentPages按stream ID并行预取前后各最多20条，不逐页下载整个间隔；首楼单独保留pinnedOriginal，之后仍双向逐页续载。删除目标时选择返回窗口中最近的可访问楼层；空窗口、403、404及网络失败保留首批内容及正常分页，通知续读失败。Cloudflare或失效登录保留原有提示，避免降级提示吞掉手动过盾信息。邻页失败只影响对应方向重试，不阻止目标定位。

加载态保持至续读窗口准备完成，再一次发布detail和targetNumber，复用TopicScreen的定位门控，在定位结束前暂停阅读上报和自动分页。刷新取消旧续读请求，旧窗口不能覆盖新结果。阅读进度仍只来自实际正文曝光，预取本身不发送timings。

两个小单元各不超过3个文件：详情模型、解析测试、笔记；ViewModel、续读测试、同篇笔记，分别提交。用户明确账号切换不用验证，真机体验由用户手试；运行本功能及关联楼层单元测试、构建、Lint 和笔记门禁，不安装或清理手机数据，不升级0.1.7/1008。

## Related decisions

按阅读进度、阅读位置、last_read、timings 和楼层检索活跃笔记：[阅读同步](../../implemented/bug-fix/2026-10-10-topic-reading-sync.md)部分重叠，保留曝光与上报协议；[楼层导航](../../implemented/feature/2026-10-10-topic-floor-navigation.md)与[连续窗口](../../implemented/bug-fix/2026-10-11-topic-jump-window.md)部分重叠，复用明确定位和三页窗口；[前页预取](../../implemented/bug-fix/2026-10-11-topic-previous-prefetch.md)继续管理视口锚点及后续分页。其他检索命中是列表缓存、个人主页或界面呈现，与首次续读无关；没有完全吸收或过时提案。

## Source evidence

Discourse 的 [Topic.lastUnreadUrl](https://github.com/discourse/discourse/blob/main/frontend/discourse/app/models/topic.js) 按已读楼层加一并限制到最高楼层；[TopicViewSerializer](https://github.com/discourse/discourse/blob/main/app/serializers/topic_view_serializer.rb) 返回 last_read_post_number；[TopicUser](https://github.com/discourse/discourse/blob/main/app/models/topic_user.rb) 对 timings 使用最高实际已读编号更新。网页链接包含楼层后缀不等于 API 直接返回完整 href。

## Alternatives considered

- 在每个列表保留已读位置并拼带楼层的路由：最接近网页生成标题链接的方式，也能省一次首楼请求；但各列表缓存的进度可能陈旧，搜索、相关推荐和无楼层正文链接仍需各自接入，选择首次详情统一恢复，接受远楼层多一次只读请求。
- 本地记录最后可见楼层：能精确回到上次停留处，且不依赖上报时延；但与 Web 的最高已读语义不同，也不能自然跨设备同步，用户要求与 Web 对齐，先复用服务端记录。

## Testing

TopicResumeDataTest 的3项单测通过，覆盖缺失/null/零/负数、7楼到8楼、读完及越界封顶、Int.MAX_VALUE防溢出。新增能力最初因字段尚未实现而编译失败（artifacts/topic-resume-data-repro-20261011.txt），这是新契约的缺失验证，不称为旧行为复现；实现后通过（artifacts/topic-resume-data-fixed-20261011.txt）。

TopicResumeReadingTest 先在未接入恢复的真实ViewModel上运行9项用例，6项因targetNumber仍为null而断言失败（artifacts/topic-resume-reading-repro-20261011.txt）。接入后扩充为12项，覆盖已有页零额外窗口、499已读恢复500及三页预取/向前分页、末楼封顶、明确首楼/17楼优先、游客/首次访问、重进读取新值而刷新保留目标、删除楼层选501、403/404/503及空窗口退回首批内容、邻页失败重试、刷新取消旧窗口、过盾提示保留。测试使用MockWebServer，不向真实社区写入。

42项相关JVM用例全部通过：解析3、续读12、楼层导航12、ViewModel9、阅读进度6。Debug、Release R8构建和Lint通过（0错误、38项既有警告、1项hint），记录保存在artifacts/topic-resume-final-build-20261011.txt。正式包仍为0.1.7/1008，输出artifacts/release/0.1.7/kodo_0.1.7_release_arm64-v8a.apk，核对正式证书、非debuggable和唯一arm64-v8a ABI，生成sha256文件。真机账号切换和实际阅读体验按用户要求留给用户验证。

## Consequences

服务端进度按最高已读单调前进，向前翻阅再退出不会退回更早楼层；短暂阅读不足上报门槛或网络失败时，重进进度可能稍旧。普通远楼层恢复增加一次首批GET，但请求量不随楼层间隔增长；无有效进度、明确链接及目标在首批时无额外窗口请求。首屏保持加载态直到窗口就绪，避免首楼闪现和误计读；邻页慢请求仍可能延长首次定位等待。没有新增本地持久阅读位置，若后续要求精确恢复最后停留楼层或像素位置，应另行评估与Web最高已读语义的差异。
