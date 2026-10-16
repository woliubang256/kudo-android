# Agent Note: 首页紧凑标签与登录态信息流

Status: implemented

## Problem

首页 Tab 的文字、指示器和默认内边距叠加，使顶部过高；搜索行固定在列表外，滚动后继续占据阅读空间。社区横幅和快捷入口重复首页筛选。所有账号共用同一组 Tab，也没有按官网区分访客与个人信息流。

## Decision

少量标签的固定靠左布局由[顶部操作与居中标签](2026-10-16-page-toolbar-material.md)部分取代：空间足够时等分居中，容不下时继续横向滚动，文字和渐变指示器保持。

Tab 点击、类别入口和阅读位置由[首页横向分页](2026-10-10-home-pager.md)扩展，搜索行折叠、紧凑尺寸和黄色渐变继续生效。

首页删除社区横幅和四个快捷入口，信息流直接从话题开始。紧凑 Tab 与搜索行分离，前者固定、后者使用 Material3 enterAlwaysScrollBehavior 协调嵌套滚动。布局阶段读取高度偏移，不额外复制列表滚动状态。

首页搜索行最小44dp，头像28dp，搜索内层30dp，左右12dp、上下2dp留白。搜索框采用椭圆胶囊，左侧搜索图标、可省略的社区标语、细竖线与固定“搜索”文字依次排列；整条是同一个搜索入口，不引入独立按钮或材质覆盖。搜索行随上滑收起，Tab 留在状态栏下，反向滑动即展开搜索。首页采用 enter-always 嵌套滚动，状态栏安全区始终保留，Tab 不参与纵向折叠。大字号允许 Tab 增高。分类详情保留返回标题，不折叠。

首页顶部作为独立前景覆盖在全屏 Pager 上，列表通过内容顶部留白避让当前测得的页头高度，滚动条目能够经过状态栏和标签背后。仅 Pager 进入 LayerBackdrop 采样源，顶部自身不进入采样，避免递归反馈。搜索行展开时顶部为实色，随折叠比例渐变为带主题底色的玻璃。初版为压低滚动图片干扰、保护文字可读性，采用 14dp 模糊及 54% 表面色、不加镜片折射；这项材质决定已由[延续现有液态玻璃](../bug-fix/2026-10-16-home-header-liquid-material.md)取代，本篇的采样布局与手势决定继续有效。顶部保持平直，不套用悬浮胶囊的外形与按压变形。分类、标签和搜索页共用的 FeedTabBar 继续默认实色，仅首页传入透明背景。

标签横向滚动，选中项通过 BringIntoViewRequester 保持可见；标签文字与指示器共用 34dp 最小布局，不叠加 Material Tab 默认文本内边距。选中标签使用17sp文字，未选中15sp；指示器为文字下半部背后的 LogoYellow 横向渐变，宽度跟随文字。字号增大时布局随字形增高。

账号变化时重建横向标签滚动位置，避免退出后保留在个人标签末端。两组顺序、计数、分类动作和列表会话隔离由[登录态筛选](2026-10-08-session-feed-filters.md)维护。

## Related decisions

已检索 proposed / implemented / rejected 的首页、Feed、Tab、吸顶关键词。与[密度与节奏](../../implemented/feature/2026-10-07-coolapk-density-and-motion.md)和[玻璃界面](../../implemented/feature/2026-10-07-liquid-glass-ui.md)部分重叠，仅取代其中首页介绍区和顶部标签布局，保留其余排版、导航和材质决定。与[原生架构](../../implemented/architecture/2026-10-07-native-community-client.md)和[验证边界](../../implemented/testing/2026-10-07-client-verification.md)兼容；设置模态、底栏官方示例与本次顶部改动无关。

## Alternatives considered

- 将搜索行和 Tab 都放进 LazyColumn，用 stickyHeader 固定 Tab：结构直观、无需自定义高度，但反向轻滑不能立即显示搜索，要滚回列表顶部才出现；因此采用酷安观察到的 enter-always 折叠。
- 保持所有筛选固定显示，并在点击时提示登录：权限实现简单，但访客看到无权使用的个人入口，与用户给定顺序不同；改为按会话生成列表并在状态层校验。
- 用当前加载的新话题条数充当数量：无需额外请求，但分页长度不等于全量新话题数；未知计数不展示数字。
- 只把原来的页头背景改半透明：修改局部且不增加采样层，但原布局将列表放在页头下方，玻璃背后没有滚动内容；改为前景页头与全屏列表分层，列表顶部留白保持首屏位置。

## Testing

2026-10-16 搜索胶囊样式：Debug / AndroidTest 构建通过（artifacts/home-search-pill-build.txt），FeedHeaderUiTest 6 项真机回归通过（17.719 秒，artifacts/home-search-pill-tests.txt）。新增用例检查图标、标语、竖线、搜索文字从左到右不重叠，浅色普通宽度与深色 280dp / 130% 字号下搜索文字完整，实际点击图标、标语和右侧文字各触发一次搜索。已查看浅深色测试截图；测试截图带点击涟漪，静止外观以真实首页 artifacts/home-search-pill-real.png 为准。真实点击右侧文字能进入搜索页（home-search-pill-search-page.png）。材质分支未改；以原版本 2011 / 0.2.6-dev 核对签名后覆盖安装，原登录数据保留。

2026-10-16 顶部玻璃在 RMX2202 / Android 13 验收。最终 FeedHeaderUiTest 5 项通过（15.536 秒，artifacts/home-header-glass-final-tests.txt），覆盖首屏卡片不被遮挡、吸顶与反向展开、窄屏大字号刷新、账号标签，以及真实话题封面由红变蓝时浅深色顶部同步采样。列表变为全屏视口后，下拉手势从可见列表区域开始，避免落在前景标签上。HomePagerUiTest 2 项与 HomeRefreshUiTest 5 项关联回归通过，保留切页、回顶和返回阅读位置。

最终构建通过（artifacts/home-header-glass-final-build.txt）。已查看真实首页展开、吸顶及彩色封面截图，记录为 artifacts/home-header-glass-real-expanded.png、home-header-glass-real-pinned.png 和 home-header-glass-review.png；顶部使用 drawPlainBackdrop，无组件默认高光边框。安装前核对签名一致，以 versionCode=2011、versionName=0.2.6-dev 显式构建并 adb install -r 覆盖，保留账号数据。旧系统及半透明模式的降级路径尚未另做真机复核。布局采集工具的辅助 instrumentation 与测试共用 UiAutomation，测试前停止该辅助进程，避免截图服务占用。

2026-10-09 紧凑顶部调整的 FeedHeaderUiTest 4项真机回归全部通过，验证折叠、反向展开、账号切换与窄屏大字号；同批界面共34项通过。真实首页截图 artifacts/topic-actions-real-home.png 已检查，搜索行44dp、Tab最小34dp，黄色渐变贴在文字背后且随文本宽度。历史验证记录如下。

FeedHeaderUiTest 在 RMX2202 / Android13 先复现两个失败：上滑后 Tab 位置不变、横幅仍存在。修复后 2 项通过（4.1秒），验证连续上滑吸顶稳定、反向滑动搜索展开、首屏直接显示话题。日志为 artifacts/home-tabs-repro.txt 与 home-tabs-layout-test.txt。最终24项JVM及32项真机界面回归通过（68.994秒），构建与Lint通过（0错误/27警告）。真实首页、排行榜吸顶与反向展开截图分别为 home-tabs-real-top.png、home-tabs-real-pinned.png、home-tabs-real-expanded.png，已人工检查；吸顶Tab位于状态栏107px下方，40dp高，与同机酷安一致。

## Consequences

滚动后的阅读区增加一个搜索行的高度，移除重复入口使首页直接进入内容。顶部折叠需要与下拉刷新及分类页安全区共同检查。34dp 为普通字号视觉目标，放大字体优先保证文字完整。普通字号 Tab 的布局触控高度随视觉高度为34dp，低于48dp常规建议；使用宽松的水平留白和完整语义入口，并按用户确认的酷安比例实现。

玻璃增加一个列表采样图层；顶部高度随搜索行折叠变化时，同步更新列表内容留白与刷新指示器位置。材质必须用实际经过顶部的图像颜色检查，不能仅凭半透明参数或折叠位置测试证明有效。
