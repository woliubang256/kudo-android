# Agent Note: 编辑工具面板按需采样宿主背景

Status: implemented

## Problem

表情、链接和格式工具共用的 EditorToolSheet 使用独立全窗口 Dialog，显式传入空 Backdrop，因此无法呈现其他 Action Sheet 的玻璃材质。表情分类未选中时还填充不透明 surface，选中时使用非品牌色容器。用户要求先复现色差，再统一工具面板材质，保留现有键盘避让、全窗口尺寸和拖动关闭。

## Decision

在应用根部增加专供编辑工具使用的同窗口宿主，工具面板与内容采样层为兄弟节点，保留全窗口可用空间。只有存在工具面板时才创建、记录 LayerBackdrop，关闭后移除采样层并释放 Backdrop；普通页面尤其关闭高级材质的 WebView 不增加该渲染路径。面板和 dim 永不进入来源层。

工具调用者保留内容、选区与菜单状态，通过捕获的 CompositionLocalContext 在根部绘制表情和其他工具，保留账号元数据及上下文。宿主遮挡期间隐藏背景语义并阻止新焦点进入，底层回复面板暂时解除焦点外移锁，工具面板恢复自己的焦点隔离；关闭动画结束后才通知调用者清理菜单。没有根部宿主的独立组件保留原 Dialog 兼容路径。

工具打开时单独保存目标 TextFieldValue；工具输入框导致正文失焦时，不以折叠后的正文选区覆盖目标。异步正文更新通过 syncMarkdown 重定位目标选区，插入命令对最新目标操作；关闭或取消后恢复目标选区和正文焦点，保留最新正文。调用者退出时同步移除请求和采样层，不让工具脱离所属编辑器。

表情、链接、文本大小、列表和更多格式继续共用 CommunitySheet 的材质参数与兼容路径。表情搜索与网格保持无底色，未选中分类透明，选中分类使用 LogoYellow。缺少专用宿主的独立工具组件保留原不透明回退。

## Existing note audit

- [统一 Action Sheet](../feature/2026-10-17-liquid-action-sheets.md)：部分重叠；本篇替代应用内编辑工具一律使用不透明兼容表面的决定，其他面板的表面参数、dim、手势与隔离约束保留；无根部宿主的独立组件保留原回退。
- [Markdown 工具栏](../feature/2026-10-15-editor-markdown-toolbar.md)：部分重叠；保留全窗口空间避免 300dp 回复容器裁切的理由与全部编辑行为，应用内工具改由根部同窗口宿主承载，无宿主时保留独立 Dialog。
- [渲染环崩溃](2026-10-16-directory-material-render-crash.md)：部分重叠；继续禁止来源层包含引用它的面板绘制节点，以真实透色测试和反复开关验证，不以语义节点存在替代材质验收。
- 其他搜索命中是普通页面工具栏和个人主页的固定材质，不改变其布局或采样来源。无完全吸收或过时提案。

## Alternatives considered

- 保留独立 Dialog 并引用应用窗口的 LayerBackdrop 可以最小化焦点与键盘改动。真机尝试后透色差只有 0.102，固定背景条纹在面板里仍是清晰边界，没有达到真实采样和模糊的标准（artifacts/editor-tool-material-cross-window-rejected.txt）；不把窗口透明透色误认为 Backdrop 生效，改用同窗口兄弟层，并显式传递组合上下文和处理底层焦点锁。
- 只降低 Dialog 容器透明度改动最少，但窗口没有应用内容采样源，不能得到真实背景模糊或折射，也无法证明材质一致；显式接入隔离的来源层。
- 始终记录整个应用窗口可以省去开关状态，但会把额外图层引入普通 WebView 和其他无关页面；只在工具打开期间创建和记录专用层。

## Testing

- 先在旧实现上复现未选分类实色底、工具不采样背景以及跨窗口采样不足；证据为 artifacts/emoji-tab-background-red-device.txt、editor-tool-material-red-device.txt 和 editor-tool-material-cross-window-rejected.txt。选区回归先复现链接重复插入而非替换选中文字（artifacts/editor-tool-selection-red-device.txt），再补充目标选区保持。
- RMX2202 / Android 13（设备 1618704c）以 versionCode=2021、versionName=0.2.10-dev 显式构建，核对同签名后使用 adb install -r 与 adb install -r -t 覆盖主包及测试包，保留开发版数据；不使用 connectedDebugAndroidTest。
- :app:assembleDebug、:app:assembleDebugAndroidTest、:app:lintDebug 通过，Lint 为 0 错误、53 警告及 1 hint，不修改无关项。日志为 artifacts/editor-tool-material-final-build.txt。
- 通过 adb shell am instrument 运行 EditorToolMaterialUiTest、EditorToolbarUiTest、PrivateMessageToolbarUiTest、CommunityFormsUiTest 和 SheetGestureUiTest，30 项全部通过，耗时 57.316 秒；日志为 artifacts/editor-tool-material-final-device.txt。
- 材质用例覆盖浅深色与三种材质的动态红蓝透色、同窗口坐标和条纹边界模糊、把手/搜索/分类/网格空白表面一致、按需采样与重复关闭。通过真实 layoutInfo 祖先链确认面板不在来源层内，不依赖已清除的背景子语义。编辑回归覆盖回复/私信工具、异步正文重定位、取消恢复选区与焦点、调用者组合上下文更新及退出清理。
- 已目视检查 artifacts/editor-emoji-unified-light.png 与 editor-emoji-unified-dark.png，分类与网格背景一致、选中态为黄色，键盘避让正常。截图使用紫色背景与本地回退表情目录夹具，不代表真实账号完整目录的人工验收；坐标及模糊另由条纹像素测试验证。检查本轮 crash buffer 未见 App 崩溃。

## Consequences

共用编辑工具使用真实背景采样，未选中表情分类不再产生实色块，同时保留 dim、全窗口空间、键盘避让、拖动关闭和草稿选区。代价是根部宿主需要管理工具生命周期、调用者组合上下文及底层焦点锁；新工具必须遵守这些契约，不能仅迁移绘制位置而忽略输入行为。

来源层只能在工具打开时存在，不能因设置或普通网页进入常驻，且不得包含面板或其遮罩。无根部宿主的独立组件仍使用不透明 Dialog 兼容表面。当前真机仅覆盖 Android 13，其他系统与 GPU 仍需额外验收；后续调整渲染层级必须重跑动态透色、坐标、模糊边界和真实父链测试，不能用单色透出或语义节点存在代替材质验证。
