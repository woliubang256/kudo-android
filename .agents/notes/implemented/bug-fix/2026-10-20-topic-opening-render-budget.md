# Agent Note: 帖子进场与正文加载错峰

Status: implemented

## Problem

返回整卡动画已经获得用户认可，但打开时仍有停顿感。网络读取已由 TopicViewModel 放到 IO；原实现透明的 TopicScreen 仍立即组合，响应到达后的 HTML 解析、文本测量和图片布局可发生在 480ms 进场内。另外冷加载始终停留在放大的来源位图，视觉等待与掉帧需要分开判断，不能据此宣称已测出帧率问题。

## Decision

请求、ViewModel 和事件观察立即运行，只推迟重的详情 UI 到真实进场完成；使用动画进度完成信号，不固定 delay，也不推迟网络。已进入的正文在返回时保持组合，子页返回和无快照入口不重新等待。提前返回且从未进入正文的详情不在退场中补建正文。

列表来源额外携带只驻留当前回栈的 Topic 元数据。动画完成后，慢网先呈现已有标题、作者和摘要的轻量首屏，再交给真实正文；不构造假 Post，不计阅读、不自动分页、不提供未取得权限的互动。明确目标和服务端续读仍等待真实窗口定位。数据失败立即交给现有错误界面。无元数据来源保留既有快照等待回退。

TopicTransitionBody 门控真实 UI，TopicViewModel 的创建、加载、更新订阅、刷新结果和消息收集留在门控之外。完成标记只置位一次，不跟随返回进度倒退。摘要与正文使用 180ms 交叉衔接；头像用 PLAIN 呈现避免摘要触发用户卡片补取，摘要最多三行。加载首屏不再显示封面或发起封面请求，正文和来源整卡动画中的图片保持原有规则。卡片、标题行、紧凑置顶和搜索结果传入 Topic；仅有话题 ID 的历史行保留既有回退。

用户截图确认加载占位应高一些、分三段并有动效；首屏使用三段各 88dp 的正文骨架，每段四行，段间 24dp 留白。共用一个低对比度、1.7 秒循环扫光，动画状态只在 Canvas 绘制时读取，不让每一帧重新组合作者、标题或测量布局。骨架沿用进场完成门控，正文接管淡出后移出组合停止；遵循 Compose 系统动画时长缩放，关闭系统动画时静止展示。

## Existing note audit

- [整卡画面连续性](../../implemented/bug-fix/2026-10-20-topic-card-content-continuity.md)部分重叠：保留快照缩放、返回、失效回退和生命周期；只替代透明正文立即组合及有来源数据时整页等待的时序。
- [共享容器](../../implemented/feature/2026-10-19-topic-container-transition.md)部分重叠：继续保留来源身份和列表位置。
- [续读](../../implemented/feature/2026-10-11-topic-resume-reading.md)、[阅读同步](../../implemented/bug-fix/2026-10-10-topic-reading-sync.md)部分重叠：不改变目标窗口、权限与实际正文曝光规则。
- 其余检索命中为列表分页、目录材质、网页 loading 和正文语法，无本次决定被取代；没有同主题未落地提案。

## Alternatives considered

- 请求延迟至动画结束：最容易隔离所有网络更新，但会实质增加首屏等待；保持请求立即执行，只控制 UI 时序。
- 预取全部列表详情：缓存命中可直接展示完整内容，但会增加流量、限流和跨账号隔离风险；本次不增加预取或详情缓存。
- 保持放大的整卡直到网络完成：来源画面最连续，代码最少，但慢网等待仍像卡住；已有 Topic 的入口改用非交互摘要首屏，无来源数据仍采用原回退。
- 静态三条短线：绘制开销最低，也不会打扰正文，但用户截图反馈高度不足且等待感像停住；改成三段正文骨架和低对比扫光，不恢复封面或增加位图占位。

## Testing

先用原行为的直通 TopicTransitionBody 和相同真实 NavHost 夹具复现两项失败：进场 128ms 时正文已组合，提前返回也构建了正文，日志 artifacts/topic-opening-budget-repro-device.txt。门控后原 11 项与这两项共 13 项通过，日志 topic-opening-budget-fixed-device.txt。随后慢网用例在旧等待行为下先失败，正文组合次数为 2 而非 0，日志 topic-opening-preview-repro-device.txt。

首屏错峰一轮在 RMX2202 / Android 13 共 16 项通过，20.689 秒，日志 artifacts/topic-opening-preview-final-device.txt。覆盖网络立即启动、进场中响应不建正文、提前返回不建正文、慢网仅显示已有摘要且可返回、失败接管，以及快响应跳过摘要。原整卡缩放、快照释放、状态栏避让、失效来源、重复点击、子页返回与列表位置继续通过。同轮 TopicResumeReadingTest 12 项、TopicReadingSyncTest 3 项通过，后续占位调整未改续读或阅读上报实现，不重复运行。所有设备构建显式使用 0.3.0 / 3001，安装前核对签名后 install -r / -r -t，未卸载、清数据或运行 connectedDebugAndroidTest。

三段动态占位先补三项回归，在旧实现上分别因仍有封面、缺少三段高占位、缺少动态骨架而全部失败，日志 artifacts/topic-skeleton-repro-device.txt。调整后同机 19 项转场测试全部通过，23.897 秒，日志 topic-skeleton-fixed-device.txt。新增断言每段至少 80dp、段间留白，以及受控时钟推进 600ms 后像素发生变化而边界不变；原提前响应、慢网、失败与返回测试保持通过。深色实际主题截图 topic-skeleton-dark-device.png 与高对比浅色夹具 topic-skeleton-light-fixture-device.png 已检查：无加载封面、三段各四行、标题与加载文案可读；浅色夹具仍为红底，不作为产品主题证据。

三段占位版本的 Release / R8 / Lint Vital 构建通过，日志 artifacts/topic-skeleton-release-build.txt，继续使用已核对线上版本 0.3.0 / 3001。正式包 artifacts/topic-skeleton/kodo_0.3.0_release_arm64-v8a.apk 为 9,533,802 字节，SHA256 为 a5f849d4bda69acddb8647133a6e1f3b256f092d9c0a558dd0220d0c231cd5ef；保数据同运行时重签包 kodo_0.3.0_device_preserve-data.apk 为 9,571,393 字节，SHA256 为 c0791cb0b6561be5cf7525acb085a6e23a0dccbdb29ae5008789d4302aa8bdad。校验正式证书与已装开发证书，核对排除 META-INF 后运行时条目逐项一致、不可调试、仅 arm64-v8a 后，使用 install -r 覆盖真机，未卸载或清数据；仅本地打包，不发布。

真机离线夹具截图 artifacts/topic-opening-preview-device.png 已检查标题、作者、摘要和“正在加载正文…”可读。截图的红底来自原高对比转场测试配色，不是产品主题。组合时序测试证明正文错峰，不代表测得真实应用稳定帧率或已消除全部掉帧。

GitHub 最新公开正式版核对为 v0.3.0（2026-10-18 公开），本次只本地构建 0.3.0 / 3001，不打 Tag 或发布。Release / R8 / Lint Vital 通过，日志 artifacts/topic-opening-final-release-build.txt。正式包 artifacts/topic-opening/kodo_0.3.0_release_arm64-v8a.apk 为 9,533,802 字节，SHA256 为 4f1f60d069af040b7d76280e39d88a2848be5c6b00f8bb36ce8366f45821431e，正式证书仍为 457c98f5786d224e5117d732126389b8b7cedad5df1b14366b7e662481d22418。

保数据真机包 artifacts/topic-opening/kodo_0.3.0_device_preserve-data.apk 仅重签同一正式运行时，排除 META-INF 后 ZIP 条目逐项一致，aapt 确认不可调试、仅 arm64-v8a。文件为 9,571,393 字节，SHA256 为 eaccc11b1cbe07bdd63de10d6be59c7ae41f250796183e2d2bb290f268fc8733；核对已安装开发证书 95f95aff266413e4c3fb431bf7ae96552d0e5e601b986e4a53d2a116b6623e4f 后覆盖，firstInstallTime 保持 2026-10-17 01:33:30。

该优化运行时在真机录制无图“现在的国模水平怎么样”（artifacts/topic-opening-final-release.mp4）和有图“这是什么新的claude封号方式吗...我真睡不着了”（topic-opening-final-release-image.mp4）。已查看 opening / handoff / return 对应逐帧拼图，核对整卡展开、真实正文接管、反向收回和列表位置；真实正文中文可读。这两次真实请求较快直接进入正文，慢网摘要由上述受控真机夹具覆盖，不把正常请求录屏当作限速网络验收。

## Consequences

本次优化移开进场期间的重组合，不保证首次正文布局零耗时，也不缩短真实网络时间。摘要与完整正文的排版不同，需要柔和衔接；续读可能仍在等待其他楼层窗口。扫光只影响骨架绘制，但长时间网络等待仍有持续绘制开销，不能视为稳定帧率验收。正式正文图片仍遵守既有 NSFW 设置，来源元数据不持久化、不跨账号复用。
