# Agent Note: 小程序更多面板跟随全局材质

Status: implemented

## Problem

用户要求小程序更多弹窗跟随材质设置。修改前 MiniAppPanels 将共享 CommunitySheet 固定为 backdrop=null、GlassMode.TRANSLUCENT，因此更多宫格和菜单子面板忽略全局材质。早期固定普通材质用于避免 WebView 参与 Compose 图层采样的兼容风险，不能只移除参数后重新把网页纳入同一采样层。

## Decision

更多宫格以及从菜单进入的关于、评分、打赏和举报面板，读取当前全局半透明、背景模糊、液态玻璃设置。沿用贴底宫格布局、拖动关闭、安全区、返回键和操作忙碌时禁止关闭的既有交互。网页权限确认保留现有展示范围与执行逻辑。

MiniAppPanelWindow 复用小程序浮窗的 PixelCopy 背景帧，需采样的原生面板位于独立 Dialog 窗口；只复制 Activity 主窗口，不把弹窗、遮罩或 WebView 放入 Compose GraphicsLayer。采样在进入 Dialog 的 LocalView 之前建立，坐标按屏幕映射，避免把 Dialog 自己当作源。普通材质、无可用采样和低版本系统使用兼容表面；没有弹窗或进入后台时停止采样。全局材质变化不重建、不重新加载网页。

Dialog 使用全宽、边到边视口，禁用系统额外 dim 和窗口动画，遮罩与进出动画仍由 CommunitySheet 管理。直到原面板退出动画结束才移除窗口；材质与子面板变化复用窗口和表单状态。返回、遮罩、拖动关闭与请求忙碌状态继续受原关闭条件控制，输入法通过面板既有 imePadding 避让。

深色高级材质的面板遮罩使用 0.74 透明度，保证覆盖白色网页时正文对比度至少 4.5:1。CommunitySheet 增加可选 surfaceAlpha，只由本场景覆写，默认调用保留原有透明度。保留真实背景模糊与液态折射，不能用纯色背景代替采样。

## Existing note audit

检索非归档小程序、更多菜单和材质笔记：[网页普通材质](../../implemented/bug-fix/2026-10-29-webview-ordinary-material.md)保留网页布局与图层边界；[小程序网页容器](../../implemented/feature/2026-11-04-mini-app-square.md)与[社区操作](../../implemented/feature/2026-11-06-mini-app-community-actions.md)的面板普通材质决定由本方案部分接管，网页桥与操作契约保留；[原生目录](../../implemented/feature/2026-11-04-native-app-square.md)保留自己的兄弟层采样，仅更新运行页关于的材质归属；[锚定浮层](../../implemented/feature/2026-11-08-mini-app-anchored-floating-panels.md)保留独立 Popup 与 PixelCopy 的安全机制。本方案不替换这些笔记的其他职责。没有同主题未实施提案，Sentry 提案无关。

## Alternatives considered

- 直接移除固定材质参数改动最少，但没有可靠的背景源，或者会重新走包含 WebView 的 Compose 采样路径；采用已有窗口隔离方案。
- 只降低面板背景透明度没有窗口调整成本，但无法提供真实模糊和折射，不能满足跟随材质的要求。
- 把主窗口整体 PixelCopy 后仍在主窗口持续绘制面板可保留当前窗口结构，但后续采样会包含面板与遮罩自身；将覆盖内容与被复制窗口隔离。

## Testing

RMX2202 / Android 13 的新增真机测试先在高级材质下等待真实蓝色采样失败，证明原菜单固定普通材质（artifacts/mini-app-menu-material-repro-device.txt）。接入窗口采样后，浅深色六种材质组合的菜单、关于与同一 WebView 保留用例通过；进一步测试先复现深色覆盖白网页时文字对比度仅 3.095:1（artifacts/mini-app-menu-material-contrast-repro-device.txt），增强局部遮罩后通过 4.5:1 断言。

最终 21 项定向真机回归全部通过，见 artifacts/mini-app-menu-material-final-device.txt：覆盖六种组合的更多、关于、评分、打赏、举报，真实背景从蓝变红、重复采样不积累面板自身遮罩、深色可读性、举报输入框在材质切换和输入法显示时保留焦点与文字，以及忙碌时系统返回、遮罩和拖动均不能关闭。重新进入、标题栏切换、站内导航、全屏返回、剪贴板和声音/视频/全屏授权的原逻辑继续通过，网页未进入高级材质图层。

Debug / AndroidTest 构建与 lintDebug 通过（artifacts/mini-app-menu-material-final-build.txt）。覆盖安装前签名一致，显式使用已安装的 0.3.5-dev / 3006，不卸载、不清数据；firstInstallTime 保持 2026-10-23 14:55:34，前后 crash buffer 哈希一致。已目视查看 artifacts/mini-app-menu-material-shots/ 中六种菜单组合、深色白背景、关于与举报键盘截图，中文可读、底部表单完整。截图及操作响应使用离线网页夹具，不代表真实评分、打赏或举报已提交。

## Consequences

更多与其子面板现在遵循全局材质，网页、胶囊和权限流程保留原有职责。独立窗口增加焦点、键盘和返回事件的归属边界，相应用例覆盖材质切换与表单操作。PixelCopy 复用原浮窗的限尺寸和限频策略，存在降采样和更新延迟，低版本或采样失败时降级。真机通过不能代替所有系统 WebView 版本的兼容验收。
