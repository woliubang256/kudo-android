# Agent Note: 首页新数据提示与 MessageBus 订阅

Status: implemented

## Problem

首页停留期间不知道列表已有新话题或回复。用户希望像酷安一样把底栏首页图标和文字切成“刷新”，点击再更新，保留收到消息时的阅读位置。

## Decision

独立 MessageBus 传输提供可取消的 FeedUpdates 流，与首页差异检查、账号和刷新竞态解耦。用户已批准接入。

Linux.do 使用 ping.ldstatic.com 的 HTTP MessageBus 长轮询，非 WebSocket。独立无 Cookie 的客户端发送 Dont-Chunk 请求读取 JSON 数组，禁止重定向。登录时先由主站同源 HTML 获取 shared_session_key，只通过 X-Shared-Session-Key 发到固定推送端点；不持久化或记录密钥，不转发主站 Cookie。订阅 /latest，登录附加 /new、/unread、/unread/{id}。游标从 -1 初始化，处理 /__status，去重并串行轮询；失败退避、遵守 Retry-After，取消立即停止，重新连接检查遗漏。

推送仅作为检查提示。首页按当前筛选向服务器合并请求第一页，最多每15秒一次；由服务器决定未读、静音、个人列表、分类等成员资格。比较话题 ID、bumped_at、回复数及标题等可见内容，不因浏览量、点赞或排序轻微变化提示刷新。不更改现有列表、分页或滚动位置。顶部“新 (数量)”仍为独立跟踪快照。

首页恢复前台进行差异检查，替代此前自动刷新首页的决定；分类页保留原恢复行为。主页面不可见或后台时停止订阅与检查，恢复重新建立游标并检查缺口。切换账号/筛选取消旧任务、清空提示并重新基线。成功刷新只确认刷新开始前的通知；期间新到通知还会检查，失败保留提示。底栏点击“刷新”先回首页和顶部，再请求第一页。

ObserveHomeUpdates 明确接收首页可见状态，root 路由仅在 selected == 0 且 RESUMED 时启用首页订阅。板块、通知与我的页面仍处于同一 root，不能把 root 可见误认为首页可见。切换底部页面释放 LifecycleResumeEffect 并取消订阅和待处理检查；隐藏时仍同步账号身份，返回首页按既有方式补查。

## Related decisions

[返回列表保持阅读现场](../bug-fix/2026-10-11-feed-return-stability.md)部分取代本篇保留的分类恢复自动刷新：分类和标签返回只同步账号，保留条目、分页和位置。首页的MessageBus、恢复差异检查与主动刷新提示继续沿用；本篇保留最初按页区分恢复策略的决定，新策略避免详情返回破坏分类阅读位置。

检索 MessageBus、推送、订阅、FeedViewModel：与[会话筛选](../../implemented/feature/2026-10-08-session-feed-filters.md)部分重叠，替代首页恢复自动刷新但不改变分类恢复和新话题计数；与[请求契约](../../implemented/architecture/2026-10-07-discourse-data-contract.md)部分重叠，独立传输不放宽一般请求同源限制；客户端验证和液态UI记录属于验证背景，不重写其决定。无完全吸收或过时提案。

## Alternatives considered

- WebSocket：原生双向连接成熟且开销小，但站点实际提供 MessageBus HTTP 协议，猜测端点不可用。
- 定时轮询列表：不需要实现推送协议，但高频空请求浪费网络；使用消息触发、限频合并的列表检查。
- 收到 /latest 就直接亮起刷新：最快且省一次请求，但会对静音、个人和其他筛选误报；列表接口确认当前筛选有变化后再提示。
- 复用主站带 Cookie 的客户端访问推送域名：实现少，但打破会话同源边界；固定推送地址及独立共享会话凭据维持最小凭据范围。

## Testing

2026-10-14 HomeRequestLifecycleTest 先复现 root 内切到其他底部页面后首页订阅未停止（artifacts/home-request-lifecycle-repro.txt）。修复后验证隐藏、隐藏期间后台恢复、重新进入首页、首页前后台切换的订阅次数与请求数；连同头像和首页返回回归共 13 项真机测试通过（artifacts/auxiliary-request-device.txt）。JVM 推送与阅读同步定向回归及构建通过（artifacts/auxiliary-request-fixed.txt）。

MessageBusTest 的5项测试通过：无 Cookie、共享密钥、游标初始化/重置/去重、重定向拒绝、限流退避与取消，日志 artifacts/live-refresh-transport.txt。FeedUpdatesTest 的5项测试通过：合并20次提示、忽略仅浏览量变化、切换筛选、失败保留与成功清除、刷新期间更新遗漏/已包含两条路径、后台停止/恢复、换号撤销在途检查，日志 artifacts/live-refresh-state.txt。

完整35项 JVM 回归、Debug构建和Lint通过（0 errors / 27 warnings），见 artifacts/live-refresh-final-build.txt。35项真机界面测试通过（74.976秒），见 artifacts/live-refresh-ui-tests.txt；新增3项验证推送保持位置、真实触摸点击回顶、成功后恢复首页文案、三种材质深色130%唯一黄色入口、从其他Tab切回和离开/恢复阅读位置。旧的 LaunchedEffect 每次重建都会回顶：先用旧逻辑复现返回后第13条消失，再仅在筛选/账号变化或显式刷新回顶请求时滚动；失败日志 live-refresh-scroll-repro.txt，修复包含在35项回归中。

登录/发布的 refreshEpoch 在返回 root 后会重新执行同值效果；先用 FeedSessionTest 复现同一事件产生第二次列表请求（live-refresh-epoch-repro.txt），再由 FeedViewModel 消费每个非零 epoch 一次。epoch 使用 rememberSaveable 保留配置重建后的序号，避免新事件与旧已消费序号冲突。这项回归包含在最终35项JVM内；最终包的7项首页界面回归再次通过（16.218秒），见 live-refresh-final-home-ui.txt。

首页吸顶及刷新图标夹具截图 home-refresh-scrolled.png / home-refresh-top.png / home-refresh-liquid-dark.png 已人工查看，图标与文案使用 LogoYellow，采样层同步更新且无重复操作。

真机线上访客探针 LiveUpdatesProbeTest 通过（21.297秒）：收到3次真实通知后 FeedState.hasUpdates 为真，日志 live-refresh-online-probe.txt / live-refresh-online-events.txt。探针只在 instrumentation 参数 liveReadOnlyProbe=true 时运行，默认跳过，等待最多60秒的可见更新，不纳入离线回归。随后正式首页观察到图标及文字从首页变刷新，同一可见话题边界仍为 [60,627][588,690]；点击后回顶并成功恢复首页，回复数1175→1176。截图 live-refresh-real-prompt.png / live-refresh-real-clicked.png 与布局快照已检查。没有向社区发帖或进行互动。

材质观察独立于测试结果：本机静止截图与12秒慢拖录屏 live-refresh-material-motion.mp4 检查了黄色高亮、静止透明中性灰、镜片凸出且不裁切、经过发布按钮的真实前景折射；抽帧 live-refresh-material-rest.png / -drag.png / -end.png。手机没有 screenrecord，复用 scrcpy 无窗口录屏；解码器的 ycgco 标记导致首次抽帧失败，按既有工具设置 bt709 后取得可查看帧。录屏不作为帧率性能结论。

分发 APK 已保留数据覆盖安装，构建/分发/设备包 SHA256 均为 cab9cc981232c221f7ede530e4e3ce8cf9927f149b7cb1a1e170adeaf1631d91，见 live-refresh-apk-verification.txt。

## Consequences

共享会话凭据或 HTML、推送主机配置可能变化；站点验证和断网会暂停提示，但手动刷新继续可用。检查合并约15秒，并叠加网络和站点数据可见性延迟。真实账号与其私有频道需要独立线上验收，模拟协议不证明账号链路。
