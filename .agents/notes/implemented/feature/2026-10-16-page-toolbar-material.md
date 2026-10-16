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

## Consequences

共用 PageTopBar 的按钮通过顶部专用作用域采用独立容器；MaterialPageScaffold 提供真实内容采样与顶部高度。未接入 Scaffold 的空态使用主题色兼容容器。组件不依赖全局窗口采样，页面可以在现有弹窗采样源内部使用独立的内容源。

新增内容留白可能影响滚动定位、阅读曝光和刷新指示器；WebView 的独立绘制需检查实际采样表现。采样源错误会形成 RenderNode 环。旧系统兼容材质及其他 GPU 的效果不能由当前 Android13 真机测试代替。针对性 UI 回归在页面接入后集中进行。
