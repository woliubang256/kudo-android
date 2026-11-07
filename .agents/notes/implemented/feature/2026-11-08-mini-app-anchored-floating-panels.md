# Agent Note: 微信式小程序浮窗与音乐横条

Status: implemented

## Problem

现有小程序预览与音乐控制都通过 CommunitySheet 从底部展开。用户提供微信参考，要求改为入口附近展开，多个小程序横向排列，收起入口和展开内容均跟随当前材质设置。

## Decision

预览与音乐分别使用贴近原入口的有界 Popup，共用互斥的展开状态；账号、页面或前台状态变化时收起。

右侧使用贴边的细条三点入口，保留纵向拖动。点击后在入口附近向左展开小程序预览，卡片横向排列，超过可用宽度时横向滚动。预览图铺满卡片，左上角为独立关闭按钮，应用图标位于右下角；点击预览恢复原任务。卡片位置限制在安全区域内，无底部面板或重复说明。

左侧保留紧凑音乐入口，只有当前账号、对应音乐小程序的收起条目存在时显示，不能仅凭播放器就绪显示；恢复原小程序时只隐藏入口，不暂停音乐。点击后向右展开单行控制条：音符、歌名、播放/暂停、关闭。关闭调用既有 hideAndPause，暂停并隐藏音乐入口，不能移除小程序浮窗。用户点击外部或系统返回时只收起展开内容，不暂停音乐。两种展开状态互斥，账号或页面变化时收起。条目关闭的音源与服务清理由[音乐会话](2026-11-07-mini-app-music-floating-control.md)负责。

入口、音乐横条及预览的原生覆盖内容绑定当前材质设置，复用不包含浮层自身的真实采样源，覆盖普通半透明、背景模糊和液态玻璃。遵守此前 WebView 不进入 Compose 采样层的兼容边界，不改变网页顶部的普通布局。

高级材质的暗色遮罩使用 0.74 透明度，保证深色主题覆盖浅色网页时歌名可读；背景采样仍透过剩余部分显示。截图对比度断言以正文 4.5:1 为下限，背景色变化断言同时防止用纯色遮罩冒充实际采样。

覆盖内容使用有界的原生 Popup 窗口，折叠时不阻挡页面其他区域，展开时由系统处理外部点击和返回。采样通过 PixelCopy 读取 Activity 主窗口；Popup 独立于被复制的窗口，因此按钮、预览与音乐条都不会出现在自己的采样中。将有尺寸限制的背景帧按屏幕坐标映射给 Backdrop 的模糊和折射，不建立包含 WebView 的 Compose GraphicsLayer。主窗口绘制时标记背景变化，限制复制频率；Activity 暂停或材质为普通半透明时停止采样。

## Existing note audit

检索浮窗、音乐控制与网页材质。[小程序浮窗](../../implemented/feature/2026-11-07-mini-app-floating-windows.md)保留独立任务、账号隔离、快照与恢复契约；本方案替换其预览展示入口。[音乐控制](../../implemented/feature/2026-11-07-mini-app-music-floating-control.md)保留白名单、播放器控制、后台播放及音乐关闭语义；本方案替换其原生展示。[网页普通材质](../../implemented/bug-fix/2026-10-29-webview-ordinary-material.md)保留安全边界。[液态面板](../../implemented/feature/2026-10-17-liquid-action-sheets.md)仍适用于其他操作，不因此改动。均为部分关联，无需归档。

## Alternatives considered

- 底部 Action Sheet 复用成熟拖拽、焦点和安全区域逻辑，但与用户明确提供的就地展开参考不符；本功能使用锚定浮层。
- 将多个预览纵向堆叠能适应窄屏，但用户指定横向排列；使用横向滚动并限制预览宽度。
- 仅调整透明度最省成本，但不能体现背景模糊或液态玻璃；使用实际背景采样并单独验证网页兼容性。
- 根据采样色自动切换前景色能保留更多透明度，但同一横条可能覆盖深浅相间的网页内容，按帧变色也会干扰阅读；保持当前主题并增强暗色遮罩。

## Testing

入口生命周期用例在旧版真机先复现未收起却出现音乐入口，修复匹配条件后单独通过（artifacts/mini-app-music-entry-repro-device.txt、artifacts/mini-app-music-entry-fixed-device.txt），覆盖未收起、其他应用、其他账号及恢复原页不误停音乐。原有仅验证播放器就绪的用例不能代替此生命周期约束。

旧版真机的两项复现均在禁止 Action Sheet 的断言失败，记录在 artifacts/mini-app-anchor-repro-device.txt。

RMX2202 / Android 13 真机的 MiniAppMusicFloatingUiTest、MiniAppAudioPermissionUiTest、WebViewMaterialLayoutUiTest 共 14 项通过（artifacts/mini-app-anchor-fixed-device.txt）。新增用例覆盖两张预览水平排列、四项溢出滚动、独立关闭、页面和账号切换收起、纵向拖动、外部触摸与返回收起；音乐用例覆盖原播放器暂停、继续、切歌及关闭音乐保留小程序。现有网页授权和普通布局用例确认本次浮层变更未改对应逻辑。

增强后的四项浮层回归再次通过，记录在 artifacts/mini-app-anchor-contrast-fixed-device.txt：六种主题/材质的高级模式实际显示底色变化，反复采样同一背景不累积浮层自身遮罩，快照在卡片左右与中间均铺满，音乐和预览互斥展开。暗色音乐条覆盖白网页的文字对比度先复现为 2.51:1（artifacts/mini-app-anchor-contrast-repro-device.txt），增强遮罩后达到 4.5:1 断言下限。已查看六种材质和主题截图，原生中文、关闭按钮与横条可读。测试截图使用离线小程序，不等同于再次打开线上音乐应用；此前实际播放器与桌面恢复的验收保留在对应旧笔记。

Popup 的 OEM 入场动画不受 Compose idle 控制，初版像素断言过早截图导致两项失败。回归等待可见颜色及系统窗口稳定，不能把这种时序失败当作采样源错误；诊断确认 Activity 的 PixelCopy 帧不含 Popup，临时代码已移除。

MiniAppFloatingPositionTest、MiniAppMusicStateTest、MiniAppTaskRequestTest 共 7 项 JVM 测试通过，覆盖左右物理边缘、RTL、窄窗口、键盘占用与拖动边界。Debug/AndroidTest 构建与 lintDebug 通过。覆盖安装始终显式使用 3006 / 0.3.5-dev，签名指纹一致，firstInstallTime 保持 2026-10-23 14:55:34；未卸载或清理开发版数据。回归前后 crash buffer 哈希一致，没有新增崩溃。

## Consequences

多个预览横向滚动，长歌名截断；关闭入口保留 48dp 触控区和名称。音乐关闭等待原播放器确认，错误留在横条中并向无障碍播报。普通半透明不复制背景；高级材质最多每 120ms 复制一次，静止背景约 600ms 更新，宽度限制 540px，存在降采样和短暂延迟。Activity 暂停停止采样，Android 12 以下保留普通材质降级。
