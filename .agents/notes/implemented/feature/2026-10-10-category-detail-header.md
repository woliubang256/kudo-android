# Agent Note: 分类详情顶部与滚动联动

Status: implemented

## Problem

分类详情只有普通标题栏，没有目录中的图标、简介与分类色，也没有顶部与列表联动的吸顶效果。

## Decision

分类详情使用独立顶部组件，复用目录的 CategoryIcons 和服务端 color。展开时使用分类色14%叠加主题 surface，显示大图标、名称和最多三行简介，内容高度随字号和窗口测量。收起保留返回、图标、名称、搜索，顶部和筛选 Tab 一起过渡到用户所选材质。类别元数据可异步补齐，缺失时保留分类ID及默认图标、颜色。

类别和标签流使用全屏列表作为采样源，顶部为兄弟节点；列表通过 contentPadding 避让实测顶部高度，不能用外部 Spacer 把列表视口裁到顶部下方。共同的 screenMaterial 延续首页 20% 表面色、4dp 模糊、液态24/24dp折射及旧系统94%表面色降级，类别按收起比例进入材质，标签直接使用材质。该约束沿用[采样环修复](../bug-fix/2026-10-16-directory-material-render-crash.md)，不让工具栏出现在自身采样源内。

上滑先消耗顶部展开高度再交给列表；下滑先让列表回顶再展开顶部。处理手指与惯性滚动，分类连接放在列表和下拉刷新之间，使展开优先于刷新。顶部自己也支持纵向拖动。首页仍使用自己的 enterAlways 行为。

## Related decisions

已检索分类图标、首页折叠和 profile nested scroll。[目录图标](../../implemented/feature/2026-10-08-category-web-icons.md)部分重叠，复用图标及颜色并保留无目录标题的决定；[首页顶部](../../implemented/feature/2026-10-08-home-tabs-and-collapse.md)保持首页的反向立即展开；个人页的手势与惯性教训适用，但不复用其图片高度与遮罩。完整筛选由[分类筛选](../../implemented/feature/2026-10-10-category-feed-filters.md)负责。无完全吸收或过时提案。

## Alternatives considered

- 给分类套首页 enterAlways：可复用 Material 行为，但列表尚未回顶便会展开，和本次确认的滚动顺序不符。
- 将顶部作为每个列表的首项：天然参与列表滚动，但切换筛选会重复顶部并失去独立吸顶栏，采用共用顶部与独立列表。
- 仅在整个页面外层连接 nestedScroll：结构最简单，但下拉刷新会先消费剩余下滑；将连接放在 LazyColumn 外、刷新容器内。

## Testing

2026-10-16 材质调整先改断言，旧包真机失败（artifacts/category-material-repro.txt），确认吸顶仍是分类色。新布局7项类别用例全部通过（首次6项通过；短页刷新测试的默认触点落在新增全屏列表上方的顶部，改为从列表中部下拉后通过），标签页回归通过；记录 artifacts/category-material-tests.txt、top-material-tests.txt。旧标签断言误把数字slug当成标题，按既有匿名标签回退“标签”修正。已检查吸顶截图，滚动内容实际经过玻璃后方，单机Android13验收不代表其他GPU。

CategoryFeedUiTest 先复现缺失图标和简介（category-header-repro-device.txt）。首轮10项中首页分页/搜索顶部6项和分类3项通过，另1项发现叠加工具栏虽然遮住简介，简介仍有可见语义（category-header-device.txt）；固定工具栏改为真实占位，简介独立收起到零高度。最终4项分类用例全部通过（category-header-fixed-device.txt），覆盖上滑先收顶部、列表回顶后展开、惯性、短页刷新、背景像素不变、异步元数据和未知图标/坏颜色、深色280dp宽130%字号及标题列表模式。展开浅色与深色窄屏截图已检查。

## Consequences

背景色透明度叠加主题导致深浅主题呈现不同明度，需分别截图检查；缩放窗口后按新的测量高度收敛折叠位置。
