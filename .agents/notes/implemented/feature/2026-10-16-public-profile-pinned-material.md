# Agent Note: 用户主页吸顶跟随设置材质

Status: implemented

## Problem

公开用户主页吸顶仍裁切显示卡片图片底部，顶部工具栏始终使用白色文字和状态栏图标。用户要求参考“我的”，吸顶后隐藏背景图并使用设置材质；标签栏也需要沿用相同材质。

## Decision

返回、搜索与更多按钮的独立容器及宽窗口等分标签由[页面顶部规范](2026-10-16-page-toolbar-material.md)补充，展开与吸顶的内容源继续使用本篇定义的兄弟层。

保留展开方形卡片图、头像回退、资料、关注权限和后半程身份渐显。头图随紧凑身份显示淡出，完全吸顶隐藏背景图，反向展开恢复同一张缓存图片。工具栏文字和图标渐变至主题 onSurface，状态栏在过渡中点切换到主题适用的深浅图标，离开页面恢复默认。

分页列表使用全屏视口和随头部、标签栏高度变化的顶部 contentPadding，让真实内容经过玻璃下方。头部与列表同为采样源，工具栏与标签栏作为源外的兄弟节点绘制，禁止采样包含自身的祖先。复用 screenMaterial 的首页参数和旧系统降级规则，展开工具栏透明、标签栏保持主题底色，收起后都使用用户所选材质。保留先收起头部再滚动列表、先回列表顶部再展开以及按页保存位置、按需加载的规则。

各页 LazyListState 保存在分页外，父层读取已稳定当前页的 canScrollBackward。列表未回顶时下拉交给列表；回顶后父层在 pre-scroll 优先展开头部，避免子层 PullToRefreshBox 先消耗拖动、让吸顶停住。完全展开后才把剩余下拉交给刷新，不依赖拖动发生在工具栏的空白处。

## Existing note audit

- [公开主页方形顶部](../../implemented/feature/2026-10-10-square-profile-header.md)：部分替代吸顶保留同图底部、白色工具栏和状态栏的规则；保留方形尺寸、资料密度、头像与关注入口和完整资料面板，不归档。
- [完整公开主页](../../implemented/feature/2026-10-10-public-user-profile.md)：保留数据、权限、分页缓存及退出请求管理。
- [我的吸顶材质](../../implemented/feature/2026-10-16-member-pinned-material.md)：复用材质、图片淡出与主题颜色约定，公开主页独立实现，不修改“我的”。
- [连续滚动](../../implemented/bug-fix/2026-10-10-profile-header-scroll.md)：保留滚动消费顺序；[采样环修复](../../implemented/bug-fix/2026-10-16-directory-material-render-crash.md)继续约束玻璃不进入采样源。没有完全吸收或需否决的旧提案。

## Alternatives considered

- 保留旧 Column，仅叠一层玻璃：布局风险最少，但列表不会经过工具栏下方，只能采样静态头图，不能满足吸顶去图和真实玻璃效果。
- 直接用主题纯色替换头图：改动小且文字稳定可读，但忽略用户选择的液态、模糊或半透明材质，不能与“我的”一致。
- 整页作为玻璃祖先采样源：接线简洁，但此前目录与消息已发生原生 RenderNode 环，采样内容和玻璃必须是兄弟节点。

## Testing

PublicProfileUiTest 的展开/吸顶用例先在旧实现的绿色卡片图上失败（artifacts/public-profile-material-repro-device.txt）。材质组合用例检查浅深色和三种材质：吸顶无头图、文字与状态栏适配主题、真实蓝色封面经过工具栏和标签栏下方、下拉恢复原图和尺寸。列表中小幅下拉先展开头部且不触发刷新；其余回归覆盖方形头部、头像回退、关注、横滑切页、长短列表联动和窄屏大字号。

真机使用 RMX2202 / Android 13。每次先核对默认构建配置和手机已安装版本，再显式构建 0.2.6-dev / 2011；核对原开发证书 SHA-256 一致后 install -r 覆盖安装，测试 APK install -r -t 并手动 am instrument，保留登录数据。

材质组合用例曾复现从列表中央下拉后标签仍停在251px、没有恢复原1080px展开位置（artifacts/public-profile-material-second-device.txt），推动父层在已回顶时优先展开头部。修复后 PublicProfileUiTest 的18项全部通过，51.588秒（artifacts/public-profile-material-restoration-device.txt）。继续补充“小幅下拉先展开、不提前刷新”断言后，仅重跑关联组合用例通过，25.901秒（artifacts/public-profile-material-final-device.txt），运行时代码未再变化。

Debug、AndroidTest、PublicProfileHeaderTest 的1项单测和 Lint 全部通过（artifacts/public-profile-material-final-build.txt）；Lint 为0错误、53警告、1提示。134篇笔记结构、链接和归档校验通过。浅深色液态及浅色半透明截图已独立查看，工具栏和标签栏能分别显示滚过的封面、吸顶身份没有卡片背景；截图 public-profile-material-{light,dark}-liquid-toolbar.png 和 public-profile-material-light-translucent-tabs.png 来自真机离线夹具，不代表真实用户主页的网络数据验收。覆盖安装后 firstInstallTime 仍为2026-10-13 04:31:25。

## Consequences

吸顶后不再显示卡片图片，顶部与设置材质一致，列表能真实经过玻璃下方。代价是全屏列表的顶部留白随折叠变化，头部和玻璃需独立保持绘制顺序，下拉刷新指示器跟随内容起点。分页外保存各页滚动状态用于手势协调，预组合不产生额外数据请求。图片淡出保持组合和缓存，不能据此推断隐藏图像不占内存。真机覆盖 Android 13，旧系统降级仍需额外设备验证。
