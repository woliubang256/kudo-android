# Agent Note: 自动与手动更新共用玻璃 Dialog

Status: implemented

## Problem

用户要求新版本提醒沿用设置说明弹窗的液态玻璃。首页自动提醒与关于页手动检查虽然共用 UpdateSheet，但该组件使用实色 CommunitySheet，没有采样页面背景。直接替换外壳还会使固定版本信息与独立日志滚动失效。

## Decision

自动与手动入口继续共用 UpdateSheet，外壳使用 LiquidModal 的居中 Dialog 分支，材质读取 LocalDisplaySettings，复用模糊、折射、高光、系统版本降级、焦点与安全区契约。关于页传入已有 LayerBackdrop；首页在 HomeUpdateHost 的内容层建立 LayerBackdrop，遮罩和弹窗不得进入采样。

LiquidModal 增加默认开启的 scrollContent 参数，已有调用保持整段滚动。更新结果关闭外层滚动，只让日志独立滚动，版本和下载/安装操作固定；检查中和错误内容保留整段滚动。操作区按 showActions 显示，更新 Dialog 不重复显示“检查更新”标题，以保留短窗口的正文空间。高度上限为扣除上下安全区和32dp边距后的80%，短日志按内容收缩。

首页与关于页继续在弹窗打开及退出动画期间隔离背景语义和焦点。关于页的 layerBackdrop 包含页面背景绘制，弹窗、遮罩均位于采样内容之外。检查、下载和安装协议保持原有契约。

## Existing note audit

- [GitHub更新](../../implemented/feature/2026-10-11-github-updates.md)部分重叠：本篇取代更新外壳使用实色底部面板的决定，保留版本读取、静默去重和固定区域契约；两篇互链，旧篇仍有独立理由，不能归档。
- [酷安面板](../../implemented/feature/2026-10-10-coolapk-sheets.md)部分重叠：说明与确认本来保留玻璃 Dialog，其他 Action Sheet 继续实色贴边；其更新面板示例随本篇调整，公共限高与手势能力保留。
- [玻璃模态](../../implemented/feature/2026-10-08-glass-settings-modals.md)与[关于页](../../implemented/feature/2026-10-10-about-settings.md)部分重叠：继续提供采样隔离、关闭与滚动契约，默认调用行为保留。
- [应用内更新](../../implemented/feature/2026-10-14-in-app-update-installation.md)部分重叠：下载、通知、重试和系统安装契约保持。
- 检索到的面板精简、主题作者/回复、回复预览、楼层导航、Markdown脚注和目录笔记只复用公共模态，与本次更新外观决定无关。proposed/rejected 无冲突方案，无完全吸收记录。

## Alternatives considered

- 给 CommunitySheet 增加玻璃模式：保留贴底拖动和已有尺寸最省迁移成本，但会把设置的实色选项面板与居中玻璃说明混成另一套变体，不能直接复用用户指定的说明弹窗；更新改用现有 Dialog。
- 将日志、版本和动作全部放入 LiquidModal 默认滚动列：接入最简单且适合一般说明，但长日志会带走版本信息，下载状态也需要独立底部区域；增加默认不变的滚动开关。
- 从 KudoApp 根层传递已有 Backdrop：可避免新增采样层，但增加跨层参数且现有根采样不含完整导航前景；HomeUpdateHost 只采样自身内容，使更新宿主的遮罩隔离更明确。

## Testing

修改前，RMX2202 / Android13 上两个入口的测试都因没有 liquid-dialog 失败，日志 artifacts/update-glass-repro-device.txt。先扩展公共滚动能力，再接入两个入口，符合三个实施单元的拆分。

UpdateUiTest 七项、HomeUpdateUiTest 六项、UpdateDownloadUiTest 七项共20项通过，覆盖自动静默、延后展示、关闭去重、手动取消和重试、短日志收缩、80%限高、320×360dp深色130%字体的固定版本与下载/安装、下载进度及取消重试和安装回调。背景语义在打开期间不可访问；浅深色三种材质通过切换首页真实底色并读取弹窗空白处像素，验证液态/模糊采样与半透明兼容表面。首次材质测试误用带 dialog 语义节点的 captureToImage，Compose 试图寻找系统 DialogWindowProvider；改为捕获同窗口根图再按面板坐标采样，未为夹具修改产品。

首轮19项通过、1项上述夹具失败，日志 artifacts/update-glass-device.txt；修正后材质测试与公共模态14项一起通过，共15项，日志 artifacts/update-glass-modal-device.txt。公共回归包括 SettingsModalUiTest 七项、SheetGestureUiTest 三项、AboutPageUiTest 两项限高/各材质滚动、MarkdownContentUiTest 两项脚注。合计34个不同真机用例通过。

已查看截图 artifacts/update-manual-glass.png、artifacts/update-home-light-liquid.png、artifacts/update-home-dark-liquid.png、artifacts/update-native-short-dark.png，确认同一圆角玻璃外壳、背景采样和矮屏安装动作可见。Debug与测试包显式版本2005 / 0.2.3构建成功，同签名 install -r 覆盖保留登录数据。Lint为0错误、45项既有警告和1项提示，变更运行时文件无问题，日志 artifacts/update-glass-final-build.txt；笔记门禁通过，日志 artifacts/update-glass-notes.txt。

## Consequences

更新与设置说明共用一套材质和模态交互，自动及手动入口不会各自演化出不同容器。居中窗口上下留白减少短窗口正文行数，紧凑版本头和固定动作使正文仍能独立滚动；代价是首页新增背景采样层，后续调整宿主层级时必须保持采样隔离。真机仅覆盖 Android13，其他系统沿用公共材质降级；未在本轮实际安装正式APK或更改未知来源授权，安装流程用既有注入回调夹具回归。
