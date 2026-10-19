# Agent Note: 帖子展开与轻量骨架重叠

Status: implemented

## Problem

用户反馈打开时先把来源卡片放到顶部，再出现骨架，看起来仍有停顿。现有共享展开为 480ms，TopicTransitionBody 等到展开完成才组合轻量首屏，随后容器再用 180ms 交接；这是可从代码确认的串行等待，不是实测掉帧结论。返回的 480ms 收缩已获用户认可，不能一并加速。

## Decision

首次共享展开和底层列表虚化统一为 300ms，返回仍为 480ms。有 Topic 元数据时立即组合轻量摘要和三段扫光骨架，用既有进度 0.65 到 1 的区间渐显，与展开后半段重叠；不再把轻量首屏的可交接信号绑在展开完成上。请求继续立即开始，真实正文和错误页仍等待展开完成，再按现有交叉淡变接管。快响应也不在展开中构建正文，提前返回不补建正文。

无 Topic 元数据继续用原快照等待回退；无快照和子页返回沿用原规则。保留来源定位、列表位置、返回透明度对进度的订阅、生命周期释放、阅读与权限边界。只缩短共享打开，不改普通导航或系统动画缩放。

## Existing note audit

- [进场与正文错峰](../../implemented/bug-fix/2026-10-20-topic-opening-render-budget.md)部分取代：正文延后和请求立即执行仍成立，只撤销轻量骨架也必须等展开完成的限制。旧篇保留此前选择和验证，不归档。
- [整卡画面连续性](../../implemented/bug-fix/2026-10-20-topic-card-content-continuity.md)部分重叠：保留整卡位图和返回，更新首次展开的时长与轻量交接时序。
- [共享容器](../../implemented/feature/2026-10-19-topic-container-transition.md)部分重叠：来源键、真实回栈、列表位置和安装签名安全不变。
- [图片来源动画](../../implemented/feature/2026-10-09-image-origin-animation.md)无关：不修改图片预览。其他检索命中为正文或材质进场，没有同主题未落地提案。

## Alternatives considered

- 只把 480ms 改为 300ms：改动最少且正文仍不抢进场，但之后的骨架组合和 180ms 交接仍串行，保留了用户指出的顶部等待阶段，因此同时重叠轻量首屏。
- 将整个真实详情提前组合：快响应可以直接显示正文，省去摘要交接；但会恢复上一轮已复现的进场中重组合竞争，故只提前轻量摘要和骨架，不提前真实正文。

## Testing

先补受控时钟真机测试，在旧实现上分别复现 224ms 时下半屏骨架像素数为 0，以及 384ms 时真实正文仍未组合，两项均失败，日志 artifacts/topic-opening-overlap-repro-device.txt。修复后验证 224ms 时骨架已渐显且真实正文未组合，384ms 内正文开始组合且详情恢复 RESUMED，来源页虚化没有继续拖到 480ms。

RMX2202 / Android 13 的 21 项 TopicTransitionUiTest 全部通过；补强 224ms 时详情仍为 STARTED 的断言后，最终一轮 25.761 秒，日志 artifacts/topic-opening-overlap-final-device.txt。覆盖慢网、快响应、失败接管、提前返回不建正文、无元数据冷加载、来源失效、子页返回、原位返回、骨架无封面及三段扫光。改动未触及续读或阅读上报，沿用上一轮相关 15 项通过证据，不重复运行无关全量测试。设备构建显式传入 0.3.0 / 3001，安装前核对开发签名，使用 install -r / -r -t 和手动 am instrument，未卸载、清数据或使用 connectedDebugAndroidTest。

已检查真机受控帧 topic-opening-overlap-before.png 和 topic-opening-overlap-after.png：旧帧只有放大的红色来源，新帧在尚未铺满窗口时已叠入作者、标题、摘要及三段骨架。红色是高对比夹具，不代表产品配色。深色实色主题夹具 topic-opening-overlap-dark.png 中文可读、无封面且三段骨架保持高度；这不是实际网络限速验收或稳定帧率基准。

Release / R8 / Lint Vital 构建通过，日志 artifacts/topic-opening-overlap-release-build.txt。重新核对最新公开正式版为 v0.3.0（2026-10-18 公开），本地继续构建 0.3.0 / 3001，不打 Tag 或发布。正式包 artifacts/topic-opening-overlap/kodo_0.3.0_release_arm64-v8a.apk 为 9,533,802 字节，SHA256 为 00166b3346f2899896c5ac8eeeb0145dcc97a6694881cb70691f8932cbb5ec78，正式证书为 457c98f5786d224e5117d732126389b8b7cedad5df1b14366b7e662481d22418。

真机保数据包 kodo_0.3.0_device_preserve-data.apk 仅重签同一 Release 运行时，为 9,571,393 字节，SHA256 为 1db56f676f23212e66de5c88f6ac36b92b3ea264ba6b191b911de78cf4fbe5c4。核对其开发证书 95f95aff266413e4c3fb431bf7ae96552d0e5e601b986e4a53d2a116b6623e4f 与真机一致，排除 META-INF 后 379 项运行时内容与正式包一致、不可调试且仅 arm64-v8a，再 install -r 覆盖。覆盖后 firstInstallTime 保持 2026-10-17 01:33:30，拉回已装 APK 的 SHA256 与保数据包相同。

该优化运行时实际打开“闲来无事，开一个 配眼镜相关问题答疑贴”并返回，录屏 artifacts/topic-opening-overlap-release.mp4，已检查 enter / return 逐帧拼图：骨架与展开重叠、真实正文接管、反向收回；中文可读，返回标题边界仍为 [60,1675][1020,1747]，与打开前布局一致。该请求不是人为限速，慢网由受控夹具覆盖；录屏不作为零掉帧承诺。

## Consequences

轻量骨架也有首次组合开销，快响应时可能短暂出现骨架；它不是完整正文布局优化或帧率保证。展开后正文仍需布局，慢网等待不会缩短。只用单台真机和固定窗口验收，跨设备及动画缩放差异仍需后续体验。
