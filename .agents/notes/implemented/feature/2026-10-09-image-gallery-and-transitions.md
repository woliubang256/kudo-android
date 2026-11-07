# Agent Note: 按楼层分组的图片相册与返回目标

Status: implemented

## Problem

图片路由只携带一个 URL，无法翻看同帖多图，也不能从当前大图缩回对应图片。列表的图片点击被整张话题卡片接收；旧查看器的固定按钮和无边界缩放不符合全屏阅读需求。

## Decision

列表直接预览及返回时同步封面的约定被[封面进入详情](../simplification/2026-10-10-feed-image-opens-topic.md)部分取代；以下原始决定保留此前取舍，正文／回复相册及旧会话恢复仍适用。

以主帖或单条评论为相册边界，保留每次图片出现的索引，重复 URL 不合并。列表点击图片时立即显示预览，并按需读取该主帖的全部图片；读取失败保留已打开图片并允许重试。列表返回时将当前图同步至同一预览位，正文返回时将该楼层内的当前图片带入可视范围。

查看器放在同一窗口的根覆盖层，保留原页面组合、滚动位置和图片定位信息；不把整组图片编码进导航 URL。覆盖层隔离背景焦点与可访问语义。关闭先固定当前索引再调用来源定位，期间的翻页和重复关闭不改变目标；来源失效不阻塞关闭。会话保存地址、索引和元信息，不保存 LayoutCoordinates、Painter 或回调；来源以 rememberSaveable 的实例 ID 在重建后重新注册。切换账号重置会话。

相册使用 Compose HorizontalPager 与现有 Coil 加载器。列表请求完成只更新仍活跃、未关闭的同一会话；匹配已显示的原图或 Discourse 上传缩略图，并保留原顺序及重复项。补齐相册沿用当前图片的变换状态，不重置正在进行的缩放。独立封面不在正文中时保留封面作为额外首张。[全屏手势](2026-10-09-image-viewer-motion.md)负责图片变换与沉浸窗口，[来源过渡](2026-10-09-image-origin-animation.md)负责实际进出画面。

每次组合固定图片列表及对应变换状态，Pager的页数、键、页面回调和信息菜单使用同一份数据。Android可以在重组前测量页面，回调不能读取新会话的页数却访问旧的变换列表。旧Pager的页码通知只在相册身份未变时生效；关闭发生在重组前时使用新相册已经匹配好的当前索引，避免旧单图页码0覆盖第5张预览的位置。

固定图片组可选传入与图片逐项对齐的原帖来源列表，按当前页索引读取来源；明确的空来源不回退到另一张图的来源。图片与逐项来源在打开时复制成快照，逐项来源随会话保存恢复；旧状态没有此字段时继续使用整组来源。逐项来源仅用于调用方已提供完整图片组的会话，不与按帖子延迟补齐混用。重复 URL 保留各自来源与索引，图片信息菜单读取所操作的当前页来源。

## Existing note audit

- [正文图片](../../implemented/bug-fix/2026-10-09-article-image-layout.md)：部分重叠，提供原图、比例与元信息；本篇负责相册与交互。
- [原生架构](../../implemented/architecture/2026-10-07-native-community-client.md)：部分重叠，复用单 Activity、受限网络与全局 Coil。
- [详情交互](../../implemented/feature/2026-10-09-topic-author-and-responses.md)：部分重叠，保留楼层与主帖状态；不改变回应、关注契约。
- [原生阅读](../../implemented/feature/2026-10-07-liquid-glass-ui.md)：部分重叠，替换单张图片路由；玻璃材质无关。
- [客户端验证](../../implemented/testing/2026-10-07-client-verification.md)：部分重叠，功能回归与真机动效证据分开记录。
- 未发现同主题的 proposed / rejected 决定；没有完全吸收或需要归档的篇目。

## Alternatives considered

- 给原 image 路由增加 URL 数组：沿用导航最直接；原页面被移出活动组合后，定位尚未可见的第 3 张会更复杂，覆盖层保留源页面更适合双向动画。
- 只对首次图片应用导航 shared element：Compose 能自动管理过渡；不能独自解决切换页面后的源项定位、列表单预览位同步与拖动关闭，使用明确的相册会话和来源定位协议。
- 为图片引入新的完整浏览库：缩放与分块解码有成熟方案；这次主要边界在来源定位和全屏手势协调，先复用现有 Compose / Coil。若超大长图的清晰度或内存成为已验证问题，再考虑分块解码。

## Verification

逐图来源扩展通过 ImageLibrarySourceTest 与 ImageGalleryStateTest 共 11 项 JVM 用例，见 artifacts/library-gallery-sources-unit.txt。覆盖重复 URL 的不同原帖、明确空来源、逐页保存恢复、旧格式兼容及打开后外部列表变化不影响会话快照。

ImageGalleryStateTest 的6项与正文解析14项通过，日志 artifacts/gallery-state-tests.txt。覆盖第3次图片出现位置、重复URL、原图/缩略图匹配、失败重试、晚到结果、重复关闭与纯数据状态保存。补齐相册重置缩放先由 gallery-album-zoom-repro.txt 复现；保留变换状态后通过。

真机录屏发现单图补齐5张后立即横滑越界，现场为 gallery-live-list-crash.mp4 / gallery-live-list-crash.txt。新增用例冻结Compose时钟并发送原生MotionEvent，使补齐完成早于下一次重组，先复现相同IndexOutOfBoundsException，日志 gallery-late-album-repro.txt。普通Compose触摸助手隐式同步时钟会隐藏此窗口，不能替代原生输入。后续同一用例又复现第5张预览重开后被旧Pager重置为0，日志 gallery-reopen-repro.txt；修复后该用例3.215秒通过，日志 gallery-late-album-fixed.txt，并进入41项完整回归及5项关闭动画回归。

最终93项JVM、41项真机回归及额外1项当前画面退场测试全部通过；同机列表和正文当前图片返回的真实录屏、截图与耗时见[来源过渡](2026-10-09-image-origin-animation.md)。真实列表初开含独立封面共5页，正文相册4页；返回正文中的图片后重新打开仍显示相同原图，页码按该次完整相册计算。

## Consequences

[引用分组](../bug-fix/2026-10-10-discourse-quotes.md)递归收集引用正文图片，排除引用标题头像，并保留整条回复的图片出现顺序和重复项；折叠不改变相册索引。

列表、正文与评论复用同一相册，返回目标使用当前图片而非初始索引。列表完整相册首次需要一次只读主帖请求；独立封面可能额外占一页，随后从正文图片重开时页数可能不同，但当前原图保持。来源保留回调只到该组合销毁，源内容刷新或消失时只能关闭而不能定位。超大静态图片继续受 Coil 采样约束，不承诺无限原像素缩放。
