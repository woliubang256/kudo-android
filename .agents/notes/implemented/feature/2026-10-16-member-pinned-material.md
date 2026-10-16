# Agent Note: 我的吸顶工具栏跟随用户材质

Status: implemented

## Problem

用户要求“我的”展开保持现状，吸顶后不再显示背景图片，顶部跟随所选材质。当前吸顶仍显示原图底部，身份文字与状态栏也沿用图片上的白色样式，未落实这项要求。

## Decision

保留展开图片、自然测量高度、26dp上下留白、统计、头像角标和滚动/点击分流。紧凑身份继续沿用现有后半程渐显比例，原图片随之淡出，完全吸顶不显示头图；反向滚动恢复原图，不重复创建图片请求或改变账号数据。

列表改为全屏采样内容，顶部contentPadding随折叠视口高度变化，保留先收起头部再滚动列表、先回列表顶部再展开的顺序。采样源包含列表与原头部，不包含兄弟节点中的紧凑玻璃工具栏，防止祖先采样导致RenderNode环。工具栏使用首页相同的20%表面色、4dp模糊和液态24/24dp折射；半透明与旧系统保留既有94%表面色降级。零圆角形状保持lens契约，不另造磨砂参数。

紧凑身份颜色随头图淡出过渡到主题onSurface，状态栏在过渡中点切换到主题表面适用的深/浅图标，离开仍恢复默认。头像、昵称、等级和箭头保留；“不显示图片”指顶部背景，不移除身份头像。吸顶后的主页箭头使用48dp独立玻璃按钮，和身份栏采样同一兄弟内容源；身份栏高度与折叠终点同步为60dp，展开头部的整排身份入口和等级分流不变。

## Existing note audit

- [方形个人顶部](../../implemented/feature/2026-10-10-member-square-header.md)与[自然内容顶部](../../implemented/feature/2026-10-11-member-content-header.md)：部分替代吸顶保留图片底部、图片文字和状态栏的约束；展开测量、留白、渐显、点击与滚动顺序保留，双方互链，不归档。
- [首页玻璃](../../implemented/bug-fix/2026-10-16-home-header-liquid-material.md)：复用既有材质参数和系统能力边界，不改变首页。
- [目录与消息采样环修复](../../implemented/bug-fix/2026-10-16-directory-material-render-crash.md)：复用工具栏不进入采样源的约束，不修改已验证页面。
- 个人中心仪表盘的数据与菜单、公开个人主页的独立顶部均不改变；没有同主题待取代的proposed或rejected提案。

## Alternatives considered

- 保留图片并在上方叠加玻璃，改动最少且无需调整头图绘制，但仍会透出图片，违背吸顶不显示背景图的要求。
- 保留原Column并只给紧凑身份加玻璃，最能避免列表测量变化，但列表永远在工具栏下方，顶部无法采样滚动经过的内容；采用全屏列表和随头部高度变化的contentPadding。
- 把整页作为工具栏祖先采样源，接线简单，但目录与消息已在真机证明会形成原生渲染环；采样内容与工具栏必须为兄弟节点。

## Testing

- 修改运行时源码前，pinnedHeaderUsesSelectedMaterialWithoutPictureAndRestoresExpandedHeader在红绿图片夹具上失败：LIGHT/LIQUID吸顶仍保留绿色头图，记录 artifacts/member-material-repro-device.txt。
- 吸顶主页箭头新增独立容器断言：浅深色×三种材质均为48dp方形触控区，按钮不进入内容采样源；相关 MemberHeaderUiTest / MemberHeaderStatusUiTest 仍全部通过。
- Debug与AndroidTest构建通过（artifacts/member-material-final-build.txt）。先读构建配置默认版本及真机2011 / 0.2.6-dev，再显式传入相同版本；核对SHA-256签名一致后使用adb install -r覆盖主包，测试包-r -t安装并手动am instrument，无卸载或清数据。
- 首轮12项中账号/消息5项和顶部3项通过，另外3项暴露旧测试契约：未合并树包含持续测量的隐藏展开昵称、白色角标断言依赖系统主题、吸顶状态栏仍按旧图片白底断言。紧凑文字按祖先定位、角标固定浅色、状态栏迁移到浅深色新契约后，最终MemberHeaderUiTest与MemberHeaderStatusUiTest共7/7通过，18.341秒（artifacts/member-material-final-device.txt），crash buffer为空。运行时代码未因这些测试迁移再变更，已通过的5项账号/消息测试不重复运行。
- 顶部测试覆盖浅深色×三种材质共6组合：展开红绿图片、吸顶去头图、文字onSurface颜色、采样内容与工具栏为兄弟、反向展开恢复原图和高度、退出恢复状态栏；并回归26dp留白、头像角标、真实触摸整排/等级分流、箭头、280dp窗口与130%字号及吸顶重测。六组合截图保存为 artifacts/member-material-{light,dark}-{liquid,blur,translucent}.png，已独立查看浅深色液态截图，不以用例通过代替所有材质/设备的人工验收。
- 真实账号展开、吸顶及反向恢复截图分别为 artifacts/member-material-live-expanded.png、member-material-live-pinned.png、member-material-live-restored.png，均已查看：展开仍是原头图与统计，吸顶背景图消失、菜单经过玻璃下方、身份与主题文字可读，恢复后原图与四项统计回到原位置。工具栏包含状态栏，实际无崩溃；没有点击真实通知或修改账号设置。
- 同签名开发包已在RMX2202 / Android13覆盖安装，版本保持2011 / 0.2.6-dev、登录仍在，firstInstallTime仍为2026-10-13 04:31:25。

## Consequences

吸顶与现有用户材质一致，不再透出头图，滚动内容能够经过玻璃下方；展开样式和缓存保持。代价是全屏列表的contentPadding随折叠视口变化，原头部需要比列表更高的zIndex才能保留真实触摸入口。头图淡出时仍参与测量和图片缓存，恢复不跳高；透明度为零的紧凑工具栏不创建。当前只有Android13的RMX2202可实测，旧系统降级、其他GPU及极端背景的文字对比仍需独立验收。
