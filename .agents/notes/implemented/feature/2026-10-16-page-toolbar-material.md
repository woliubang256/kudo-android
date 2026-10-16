# Agent Note: 页面顶部独立玻璃操作与居中标签

Status: implemented

## Problem

个人内容、设置和阅读辅助页仍使用实色顶部栏与裸操作图标，已接入材质的页面也缺少独立按钮容器。少量 Tab 靠左排列，与用户确认的 iOS 液态玻璃参考布局不一致。

## Decision

共用顶部按钮使用独立圆形容器，文字操作使用胶囊，触控区域至少48dp。材质沿用现有 LiquidButton 及用户选择的液态、模糊或半透明模式。工具栏作用域仅影响顶部操作，不改变正文和 Markdown 工具栏的 CommunityIconButton。

MaterialPageScaffold 将全屏内容与顶部玻璃置于兄弟节点，实测顶部高度通过 PaddingValues 交给列表或滚动内容；禁止将自身玻璃放进自己的采样源。已有弹窗的整页采样源可包含工具栏，但工具栏另取内部的内容源。保留滚动位置、刷新、分页、键盘及导航逻辑。

文字 Tab 按最宽标签和当前字号计算是否容得下等分布局，容得下时铺满可用宽度并逐项居中；超出时使用原有横向滚动和选中项可见机制，不截断文字或压缩触控。现有黄色渐变指示器保留，图标式表情分组不属于文字导航 Tab。

## Related decisions

已检索 active notes 的顶部、材质、Tab、PageTopBar 关键词。[首页标签](../../implemented/feature/2026-10-08-home-tabs-and-collapse.md)、[搜索](../../implemented/feature/2026-10-14-search-experience.md)、[目录](../../implemented/feature/2026-10-15-community-directory.md)部分重叠：本篇取代其固定靠左的少量标签布局，保留渐变指示器、内容状态和手势决定。[公开主页材质](../../implemented/feature/2026-10-16-public-profile-pinned-material.md)保留折叠与采样边界，补充独立按钮；[采样环修复](../../implemented/bug-fix/2026-10-16-directory-material-render-crash.md)继续约束渲染树。没有完全吸收或过时提案。

## Alternatives considered

- 全局改变所有 CommunityIconButton，接入成本最低，但正文、表情和编辑工具也会出现无关玻璃容器；只在顶部作用域内分流，定制工具栏显式使用顶部按钮。
- 只给原 Column 顶部改透明，布局变化最少，但滚动内容不会经过玻璃下方；采用全屏内容和测量后的内容留白。
- 所有 Tab 强制等分，布局最一致，但公开主页多标签和窄屏大字号会溢出；按可用空间选择等分或横向滚动。

Final device verification on RMX2202 / Android13: 183 instrumented test results passed across the page-material, profile/reading, list, account, web/editor, and topic navigation groups. The two intermediate assertion mismatches were corrected in the test fixtures; the final two-test rerun is recorded in artifacts/page-material-last-two-device.txt.

## Consequences

共用 PageTopBar 的按钮通过顶部专用作用域采用独立容器；MaterialPageScaffold 提供真实内容采样与顶部高度。未接入 Scaffold 的空态使用主题色兼容容器。组件不依赖全局窗口采样，页面可以在现有弹窗采样源内部使用独立的内容源。

FeedTabBar 和 AccountTabs 复用 BalancedTabRow，主页、目录、搜索、分类筛选及个人内容标签在可用空间内等分。话题/回复、私信和阅读历史使用独立内容采样，刷新指示器避开顶部。

主题详情的列表同样全屏采样，但 PostReadingViewport 额外扣除实测工具栏高度；玻璃后方的内容不计入阅读曝光。底部操作栏继续从包含顶部的独立源采样，弹窗使用最外层源，三者不互相采样。

登录、验证和应用内网页保留 WebView 在工具栏下方的有界视口，避免改变网页的 CSS 高度、首行可见性和触点映射；独立玻璃操作采样该区域的主题背景，不注入网页留白脚本。网页本身不被放到系统状态栏后方。

公开主页按 ProfileToolbar 实测高度计算折叠终点，不能继续假设48dp。首轮真机用例复现新60dp操作栏与旧折叠终点重叠36px，修复用例与窄屏导航用例分别保留。

PersonalTopicsScreen 的整个组合以 ViewModel 为 key，分页状态、条目键、内容和副作用一起切换。仅给 rememberPagerState 加 key 会让旧 Pager 条目提供者短暂读取新页面的 keys；两项话题切换到单项历史时可越界。旧草稿返回回归在切换目标时复现，设备的 Espresso 空闲等待又被系统归为主线程超时；缩短临时诊断等待后得到 IndexOutOfBoundsException。只为 keys 索引添加兜底值改动最少，但会保留旧页面的列表和分页状态，不能保证页面身份一致，因此不用。修复后草稿导航与返回位置、历史和私信切换通过；LikedPostsUiTest 与 PersonalHistoryUiTest 共9项复查通过（artifacts/page-material-final-regressions-device.txt）。点赞夹具使用30个独立记录、跨页重复记录与真实上拉手势，覆盖加载失败、重试、去重和刷新。

## Testing

RMX2202 / Android13 首轮 PageMaterialUiTest 四项通过，覆盖两/三项等分且文字居中、拥挤标签选中可见、窄屏操作间距及禁用态、浅深色三种材质的真实红蓝列表采样。PublicProfileUiTest 的新增吸顶用例先失败，按实测高度修复后与 TopicReadingUiTest 合计29项通过（artifacts/page-material-profile-reading-device.txt），其中新增玻璃遮挡区不计阅读曝光，窄屏三个导航目标仍为48dp。已人工查看共用浅色液态、窄屏深色及公开主页截图。

新增内容留白可能影响滚动定位、阅读曝光和刷新指示器；WebView 的独立绘制需检查实际采样表现。采样源错误会形成 RenderNode 环。旧系统兼容材质及其他 GPU 的效果不能由当前 Android13 真机测试代替。针对性 UI 回归在页面接入后集中进行。
