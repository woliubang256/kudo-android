# Agent Note: 酷安式可拖动底部面板

Status: implemented

## Problem

用户提供酷安截图，要求 Action Sheet 底部贴边、跟手下拉关闭，选项和操作区采用扁平实色。现有 LiquidModal 的底部模式有四边浮动间距、玻璃表面和胶囊取消，只有无标题模式的把手能累计下拉距离，面板不跟随手指。

## Decision

CommunitySheet 在同窗口绘制，手机底部贴边、顶部20dp圆角、实色表面、灰色拖动条；可见标题与取消区由[精简面板](../simplification/2026-10-11-sheet-chrome.md)按用途决定；宽窗口限制600dp居中。手柄直接拖动，滚动内容到顶后继续下拉由 nested scroll 接管，按距离与速度关闭，未达到阈值则回弹。保持遮罩、返回键、焦点与背景语义隔离，退出动画保留内容；键盘和系统安全区由面板统一避让。

LiquidModal 的 actionSheet 分支委托新面板，说明和破坏性确认保留现有玻璃 Dialog。设置单选采用浅灰底与白色分组、分隔线及黄色字和勾；操作菜单保留已有第一排功能。

面板通过可选 `maxHeightFraction`（大于0且不超过1）限制可用高度占比，默认 1 保留既有面板尺寸；可用高度先扣除顶部安全区和 12dp 间距，比例限制覆盖整个面板及底部导航避让区。固定操作区使用独立 actions 槽位，滚动内容不带动底部动作。[版本更新面板](2026-10-11-github-updates.md)曾使用该能力，其容器现由[玻璃更新Dialog](../bug-fix/2026-10-14-update-glass-dialog.md)取代；CommunitySheet 的限高和固定动作能力继续保留给底部面板。

## Related decisions

检索弹窗、sheet、表单、编辑器、举报。[玻璃模态](../../implemented/feature/2026-10-08-glass-settings-modals.md)部分被替代，仅替换底部面板外观和手势，保留居中说明/确认及模态隔离；[主题操作](../../implemented/feature/2026-10-09-topic-actions.md)和[楼中楼](../../implemented/feature/2026-10-10-nested-replies.md)部分重叠，保持业务与阅读契约。无完全吸收或过时提案。

## Alternatives considered

- Material ModalBottomSheet 自带拖动和嵌套滚动，维护成本低，但默认尺寸、动画和控件外观不符合用户明确要求，选择 Foundation 组合实现。
- 延续玻璃浮动面板能保持原材质一致性，但与这次实色贴边截图方向不同；只保留玻璃居中说明/确认。

## Testing

SheetGestureUiTest 先在旧实现失败，无法找到设置面板拖动条，日志 artifacts/sheet-repro-device.txt。RMX2202 / Android 13 上三项手势用例通过：把手跟手、小幅回弹与长拖关闭；长内容先滚动、到顶后的下拉关闭；320dp 短窗口与130%字号下取消可达、忙碌时拖动和遮罩不能关闭。

集中回归36项全部通过，日志 artifacts/coolapk-forms-final-device.txt，同时覆盖设置、详情操作、楼中楼、详情、签名开关和列表样式。129项 JVM 测试通过。最终 Debug 与测试 APK 构建通过，Lint 为0错误、30条现有警告和1条提示，日志 artifacts/coolapk-final-build.txt；笔记门禁通过。实色贴边面板与黄色选中状态截图为 artifacts/coolapk-settings-sheet-light.png、artifacts/coolapk-topic-actions-detail-light.png。

新增限高参数后，默认比例的 SheetGestureUiTest 3项与 SettingsModalUiTest 7项在同一台 RMX2202 / Android13 上全部通过，覆盖滚动优先、拖动关闭、回弹、焦点、遮罩以及深色大字；日志 artifacts/update-panel-cap-device.txt。显式 Debug 版本0.2.3 / 2005构建通过，原签名覆盖安装保留数据；笔记门禁通过。

## Consequences

同窗口面板显式处理滚动、键盘、焦点和遮罩，不能依赖系统 Dialog 自动处理。真机覆盖一台 Android 13 手机，短窗口与大字由测试约束覆盖，平板、折叠屏和其他系统版本仍需实际设备验收。
