# Agent Note: 设置选项 ActionSheet 与官方玻璃 Dialog

Status: implemented

## Problem

设置页的主题、材质、字号、说明和退出确认使用 Material AlertDialog，与已经采用官方液态玻璃的导航及详情操作栏不一致。用户指定不用原生 MD 弹窗，并确认选项用酷安式底部 ActionSheet、说明与确认用 Backdrop 官方 Dialog。

## Decision

底部面板的外观与手势由[酷安式可拖动面板](2026-10-10-coolapk-sheets.md)部分替代；下述玻璃底板的原始取舍保留，居中说明/确认和模态隔离契约继续适用。

LiquidModal 封装同窗口的玻璃模态层，设置内容提供 LayerBackdrop，遮暗层与弹窗均不进入采样，防止自身递归折射。说明/确认沿用 Android 1.0.6 catalog DialogContent 的 colorControls、16 / 8dp模糊、24 / 48dp深度折射、Plain高光和浅深色表面；底部选项采用同一材质的宽面板与独立取消区，保留最小48dp触控区域。弹窗圆角36dp、底部面板28dp；面板限制最大440dp宽并避让系统安全区。

模态层在当前窗口内绘制，使用返回键拦截、完整触摸遮罩、dialog语义与焦点分组；焦点离开模态分组时取消离开。小屏和大字号下内容能滚动，底部安全区不能挡住取消，说明不能仅显示摘要。兼容材质沿用API 33折射、31模糊、26半透明边界。接入页面必须在打开及退出动画期间隐藏背景可访问入口；选择仍即时保存，取消/背景/返回键均不修改，退出仅确认触发一次。主题或材质改变后动画使用最新主题，不影响底层业务。

LiquidModal 的 scrollContent 默认开启，说明内容整段滚动；[更新Dialog](../bug-fix/2026-10-14-update-glass-dialog.md)关闭外层滚动，让版本信息固定、内部日志单独滚动。showActions 控制独立底部动作区，隐藏动作时不保留空操作行；既有说明与确认默认保留动作。

SettingsScreen 用独立目标内容和 MutableTransitionState 控制模态，关闭期间保留标题与内容，避免退出动画闪空。背景页面使用 clearAndSetSemantics 和焦点进入拦截，直到动画完全退出。主题、材质、字号选项使用 selectable 语义与黄色勾选；说明、致谢与退出确认不使用 AlertDialog。表面仅采样设置内容，选项或关闭不会触发隐藏页面的设置入口。

## Existing note audit

界面显示增加“主题列表样式”，使用同一单选 ActionSheet。TopicListStyle 默认 FEED，持久化名称而非序号，缺失或未知值回退信息流。首页、分类、搜索使用同一设置；传统行将头像、标题及分类标签放在左侧，回复数和更新时间放在右侧，以分隔线代替卡片间距，不渲染摘要和图片。TopicListStyleUiTest 的3项用例随46项真机回归全部通过，覆盖切换、点击、选择取消和偏好重建，日志 artifacts/presentation-device-final-tests.txt。真实设置及选项截图 presentation-live-settings、presentation-live-style-choice 已查看；传统列表验收后恢复信息流，记录见[主题展示](2026-10-09-topic-presentation.md)。新设置延长列表后，旧测试先滚动至 LazyColumn 未组合的目标，再执行点击。

- [官方玻璃组件](../../implemented/architecture/2026-10-07-backdrop-demo-navigation.md)：部分重叠，保留导航/详情的来源与业务隔离；本篇负责模态层采样和关闭契约，互链。
- [黄色玻璃与原生阅读](../../implemented/feature/2026-10-07-liquid-glass-ui.md)：部分重叠，主题与兼容材质继续适用，不归档。
- [客户端验证](../../implemented/testing/2026-10-07-client-verification.md)：部分重叠，延续真机和夹具规则；新增选择、取消、返回、退出及大字号滚动验证。
- 活跃 proposed / rejected 无其他弹窗方案；网络与构建笔记无关。

## Alternatives considered

- 继续使用 AlertDialog：已有系统关闭、焦点与可访问行为，接入成本最低，但用户明确拒绝MD样式，并确认改用玻璃Dialog和底部选项面板。
- 直接给独立窗口 Dialog 加 drawBackdrop：能复用原生模态窗口，但官方示例在同窗口内采样，跨窗口坐标和绘制层还需单独证明；当前设置均为非输入模态，选择同窗口承载并补齐交互与可访问隔离。
- 全部统一玻璃Dialog：组件与布局最简单，但用户已确认选项用底部ActionSheet，选择列表与说明/破坏性确认分开呈现。

## Interaction contract

- 设置页不再调用 AlertDialog；选项面板黄色勾选，取消/返回/遮罩关闭不改变设置。
- 说明完整可滚动，退出仅确认触发；背景无可访问操作和触摸穿透，模态入口唯一。
- 浅色、深色130%、三种材质与系统关闭动画在真机通过；复用来源及许可同步，交付APK已安装。

## Consequences

统一玻璃材质和安全区行为，减少对Material默认外观的依赖。官方示例本身没有真实模态行为，需要维护焦点、关闭和采样隔离。仅单台Android13不能证明Android12、平板或帧率表现。

## Verification

组件与设置页接入的 Kotlin 编译、Debug / 测试APK与Lint通过，0 errors / 27 warnings，输出 `artifacts/modal-component-build.txt` / `modal-integration-build.txt`。初轮6项3项通过，其余失败为测试条件：LazyColumn未滚到退出项、返回事件注入早于BackHandler注册、点击落在圆角外。补滚动、等待弹窗显示与有效空白坐标后6项全部通过，15.46秒，输出 `modal-repro-instrumentation.txt`，没有为测试误报修改产品布局或行为。

新增键盘焦点测试时需显式请求 InputMode.Keyboard；测试合成Tab不会自动将手机从触摸模式切到键盘模式。初次完整25项有1项焦点误报；键盘模式下的独立测试通过，2.03秒，输出 `modal-focus-repro-keyboard.txt`。浅色选项与深色130%说明截图已人工检查。

RMX2202 / Android13 的完整25项回归全部通过，正常动画45.988秒、系统关闭动画38.548秒，输出 `artifacts/modal-final-instrumentation.txt` / `modal-reduced-motion.txt`。三项全局缩放通过无障碍界面关闭并恢复1；首次系统页面尚未就绪导致找不到视觉标签，没有改变设置，脚本增加布局等待后完成。新7项覆盖选择、关闭、背景与空白隔离、退出、焦点、三种材质深色130%和短窗口滚动，原有18项保留。截图 `settings-sheet-light.png`、`settings-dialog-light.png`、`settings-sheet-liquid-dark-large.png`、`settings-dialog-liquid-dark-large.png` 与兼容材质已检查，玻璃真实采样且内容可读。

同机官方Dialog页面 `artifacts/modal-demo-dialog.png` 已检查，复用其轻表面与弧面高光，主题色和圆角按本应用适配。完整上游许可随APK保留；最终构建、分发和设备包SHA256一致，为 `ae4fbab19e410962474bf69734097226f626667d16f715db26287d2594331326`，验证为 `artifacts/modal-apk-verification.txt`。
