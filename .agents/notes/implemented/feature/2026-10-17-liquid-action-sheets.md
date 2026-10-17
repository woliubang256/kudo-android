# Agent Note: Action Sheet 统一液态玻璃表面

Status: implemented

## Problem

现有 `CommunitySheet` 已经统一了底部贴边、遮罩、动画、滚动优先和拖动关闭，但面板仍是实色平面，标题与拖动把手也没有按用户提供的 iOS 液态玻璃参考统一。帖子更多菜单虽然已改成两排，但圆形操作项还没有与面板材质形成一致的视觉层级。

用户确认参考图只是规范示例：继续使用现有 dim、面板高度逻辑、Android 进入/退出动画和横条下滑手势，不新增无 dim 的场景分配。

## Decision

公共 `CommunitySheet` 使用现有 Backdrop 采样源绘制统一的半透明玻璃表面、边缘描边和 28dp 顶部圆角；没有可用采样源时保留不透明兼容表面，但仍使用同一圆角、把手和间距。`LiquidModal` 的底部模式继续委托公共面板，并把既有采样源与 `GlassMode` 传入。各页面继续沿用现有 dim，不新增无 dim 的面板分配，不改变业务内容、关闭条件、限高、键盘避让或焦点隔离。

更多菜单保留两排网格与不横向滚动，改为 iOS 参考中的圆形图标、短文字和更清晰的圆形操作容器；权限、禁用态、点击回调和取消操作不变。

同窗口面板只采样不包含自身的兄弟内容层：编辑器类别/标签使用独立采样源，话题回复编辑器使用 `replyBackdrop`，跨窗口编辑工具面板明确走兼容表面，避免把宿主或面板自身纳入采样树。

上述编辑工具兼容决定由[编辑工具按需采样](../bug-fix/2026-10-18-editor-tool-sheet-material.md)部分取代：应用内表情、链接和格式工具迁移到根部同窗口兄弟层，使用真实玻璃采样；无根部宿主的独立组件仍保留原 Dialog 兼容表面。其他面板参数、dim、手势及采样源不得包含面板自身的约束不变。

## Existing note audit

已检索 Action Sheet、`CommunitySheet`、Backdrop、液态玻璃和更多菜单相关笔记。`2026-10-10-coolapk-sheets` 保留贴边、滚动优先、拖动关闭和安全区契约；`2026-10-11-sheet-chrome` 保留标题、取消区和 pane title 的业务例外；`2026-10-08-glass-settings-modals` 保留居中说明弹窗与底部选项面板的分工；本篇只接管底部面板表面材质和更多菜单圆形操作项，没有取代这些既有交互决策。`2026-10-16-directory-material-render-crash` 与 `2026-10-16-page-toolbar-material` 的采样隔离约束继续有效。

## Alternatives considered

- 直接引入 `ModalBottomSheet` 可以复用系统玻璃与拖动实现，但会改变当前贴边尺寸、滚动优先和自定义动画，且不符合现有项目的 Action Sheet 契约，因此继续使用 `CommunitySheet`。
- 将所有面板改成独立 `Dialog` 可以统一采样，但会破坏回复编辑器的键盘承载、窗口焦点和同窗口拖动行为，因此只在已有跨窗口工具面板中保留兼容表面。

## Testing

- 在 RMX2202 / Android 13（设备 `1618704c`）上以 `versionCode=2021`、`versionName=0.2.10-dev` 显式构建，并使用 `adb install -r` 与 `adb install -r -t` 覆盖安装，保留既有应用数据。
- `SheetGestureUiTest`、`SettingsModalUiTest`、`TopicActionsUiTest`、`EditorToolbarUiTest`、`PrivateMessageToolbarUiTest`、`PrivateMessageReplyUiTest` 通过，共 37 项；没有使用会自动管理安装生命周期的 `connectedDebugAndroidTest`。
- `:app:compileDebugKotlin`、Debug/AndroidTest 构建和 `:app:lintDebug` 通过。真机截图已查看 `artifacts/liquid-sheet-ui-tests/topic-actions-two-rows.png`、`settings-sheet-light.png`、`settings-sheet-liquid-dark-large.png`、`reply-actions-light.png` 和 `editor-reply-toolbar.png`。

## Consequences

底部面板在不改变现有 dim、动画和手势的前提下统一使用玻璃表面；浅色、深色和半透明模式仍有不透明兼容路径。更多菜单的圆形图标和文字在窄屏与大字号下仍按列折行，操作项不依赖水平滚动。采样源需要由宿主显式维护，独立 Dialog 或缺少父级采样源的面板无法真实透出页面背景，只能使用兼容表面。

应用内编辑工具通过上述根部宿主获得同窗口采样，不再落入独立 Dialog 的限制；来源层只在工具打开时记录，普通 WebView 不因此常驻高级材质渲染路径。

Android 版本或用户选择的半透明材质可能不支持折射效果，因此保留 blur/不透明回退；当前真机验证覆盖 Android 13，其他系统和 GPU 的折射差异仍需后续验收。
