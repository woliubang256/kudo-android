# Agent Note: 发帖与回复 Markdown 工具栏及预览

Status: implemented

## Problem

EditorForm 被新话题、回复面板及已发布内容编辑共用，目前只有图片、代码块和引用三个按钮。正文是 String 输入，工具总追加到末尾，无法按选区格式化；长内容缺少发出前预览。用户提供网页工具栏截图，并明确选择先完善 Markdown 工具栏和预览。

## Decision

正文保留 Markdown 原文及既有账号草稿键，光标、选区和输入法 composition 使用 TextFieldValue 单独管理；格式化作用于选区或插入点，多行块处理整行，空选区把光标放到占位内容中。相同正文回传保留 composition，外部正文变化重定位选区，工具操作明确提交 composition。

底部单行工具栏可横向滚动，回复发布按钮独立固定。常用入口为粗体、斜体、文本大小（标题1～4、段落、小字）、链接、引用、图片、列表（项目符号、编号、任务）、文字方向及代码。更多提供行内代码、删除线、表格、分割线、折叠内容、模糊剧透、目录、脚注、日期时间、公式、Mermaid及Graphviz模板。链接表单沿用无描边输入，工具底部面板使用独立全窗口 Dialog 承载 CommunitySheet，避免被300dp回复容器裁切；发送或保存中禁用正文修改。

新话题、回复和已发布内容编辑共享 EditorForm。完整所见即所得编辑器不属于本轮用户选择的范围。用户确认预览使用独立弹窗，保持正文、选区和草稿不变。

预览使用独立 Dialog，短文按内容伸缩，长文限制为可用高度的85%并滚动。点击预览后才使用 CommonMark 0.24.0（表格、删除线、任务列表扩展）在后台解析，复用 RichContent 的原生排版、折叠和剧透；不发布、不写云草稿、不随输入请求服务器。先显示基础预览，再以共享缓存中的站点表情补全自定义表情。CommonMark许可证随资源分发并在开源致谢列出。

基础BBCode和行内脚注只在代码节点的源范围之外转换，代码示例保持原样；upload://图片使用已核对的Discourse只读路由/uploads/short-url/解析。预览使用独立图片浏览宿主，防止图片浏览落到弹窗下方；预览链接展示可复制地址，避免离开当前草稿。公式、图表、目录、日期等复杂站点插件保留源码并说明边界，最终以站点发布为准。正文共享解析器补齐small标签字号。

表情面板复用账号级 AvatarMetadataCache 的站点目录与单次合并请求，保留 `/emojis.json` 的分组、分组内顺序和服务端自定义分组；网页标准分组显示中文标题，自定义分组保留服务器名称。搜索跨分组过滤，最近使用按名称写入本地偏好并限制为32项，点击后立即出现在“最近使用”，目录失败时使用内置常用表情回退。标准与自定义表情都以站点短代码插入，避免把不同平台的 Unicode 组合写进帖子。图片选择前将 UTF-16 插入位置交给草稿所属 ViewModel；每次正文编辑按共同前后缀移动该位置，上传完成时向最新草稿插入，不覆盖上传期间输入。插入点不绑定短生命周期的编辑器 Composable，关闭重开面板仍保留上传定位；图片插在选区末尾而不删除选中文字。

预览入口作为横向滚动工具栏的第一个图标，与粗体等格式按钮共享滚动容器；发布按钮仍保持回复面板右侧的独立固定位置。预览按钮不再占用工具栏外的固定槽位，窄屏下可随工具栏滚动回到首项。

## Existing note audit

检索编辑器、工具栏和Markdown。[账号与编辑器](../../implemented/feature/2026-10-07-account-and-composer.md)、[酷安表单](../../implemented/feature/2026-10-10-coolapk-forms.md)、[已发布内容编辑](../../implemented/feature/2026-10-15-post-editing.md)部分重叠，保留草稿隔离、发布与保存契约、写操作不重放、编辑权限及回复面板承载，只扩展共享正文交互。[正文列表](../../implemented/feature/2026-10-13-markdown-lists.md)、[颜色](../../implemented/feature/2026-10-13-markdown-colors.md)、[脚注](../../implemented/feature/2026-10-13-markdown-footnotes.md)等属于接收cooked内容的解析决策，本轮预览生成内容后复用，不改变服务端正文契约。导航、回复目标、字数规则及其他搜索命中不受影响。无完全吸收或过时提案。

## Reference

2026-10-15使用Playwriter打开网站空白新话题编辑器，只查看按钮和菜单，未输入或发布。证据为artifacts/editor-toolbar-web-composer.txt和editor-toolbar-web-options.txt：语言图标为帖子语言，弯箭头为文字方向；文本大小含标题1～4、段落、小字，列表含项目符号、编号、任务。网站更多菜单还包含图表构建、投票及模板等插件功能，当前覆盖常用格式与可独立插入的语法模板。网站当前版本a114182432的PostsController及routes未提供面向普通用户的任意raw转cooked接口，预览采用本地解析，不假定/posts/cook.json存在。

## Alternatives considered

- 给单个表情网格增加一次横滑手势，改动较少，但不能跟手展示相邻分组、每组网格位置也会共用；采用Pager并保留图标短按和长按提示，搜索仅临时替换当前页。

- 沿用String输入并继续尾部拼接可减少改动，但工具无法服务真实光标和选区，插入长回复尤其容易打断写作。
- 嵌入网页编辑器可直接使用网站所有插件，但改变原生回复面板、键盘及草稿生命周期，且依赖在线网页状态；本轮扩展原生Markdown编辑。
- 完整所见即所得编辑最接近网页模式切换，但需要双向文档模型及插件兼容，用户明确选择先完成Markdown工具栏和预览。
- 服务端生成预览最接近最终发布效果，但当前核对的普通用户路由未提供独立cook接口；不以创建草稿或帖子代替预览，采用本地基础格式与明确的插件边界。

## Testing

MarkdownEditingTest的7个单元测试通过，覆盖空选区、反向选区、段落中插入、多行列表、Unicode、重复格式操作、composition及异步插入点随编辑移动。任务列表切换普通列表残留复选框先用失败测试复现，再修正普通列表的匹配规则。

PostEditViewModelTest增加延迟上传期间连续输入的回归，验证图片在移动后的原位置插入，前后文字保留。AvatarMetadataCacheTest验证既有目录缓存与合并请求不变。

MarkdownPreviewTest的5个用例验证常用格式、表格任务、details/spoiler、表情脚注、代码示例不被转换、短图片URL、安全链接和插件源码提示。MarkdownInlineParserTest复查共享正文样式。共31个针对性JVM用例通过。

AvatarModelTest补充分组与自定义组字段解析；EditorToolbarUiTest增加网页式分组、跨组搜索及最近使用回显，5项编辑器真机用例全部通过。分组顺序测试不依赖 `org.json.JSONObject.keys()` 的实现顺序，但每个组及组内项目仍按解析结果保留。

真机1618704c：CommunityFormsUiTest、PostEditingUiTest和EditorLifecycleUiTest共14项通过；EditorToolbarUiTest与MarkdownContentUiTest共9项通过，覆盖选择格式、独立预览保留选区且不发布、回复内的链接/列表弹层、固定发布、表情与模板、深色大字及发送锁。首轮截图API在嵌套弹窗退出时失败，改用UiAutomation并等待菜单消失采集证据，功能断言没有失败。构建与Lint通过；覆盖前核对版本0.2.5/2006及相同开发签名，仅adb install -r/-r -t，保留开发版数据。证据为artifacts/editor-device-tests.log、editor-device-retest.log、editor-preview-light.png、editor-preview-dark-large.png和editor-reply-toolbar.png。

EditorToolbarUiTest新增预览首项与滚动容器归属回归，真机完整6项通过；预览弹窗、格式面板和发送锁定继续沿用原有验证。

## Consequences

工具不再强制追加到文末，格式命令可复用且可独立测试。文字方向仅影响本地输入方向，不新增服务器帖子语言元数据。表格等模板保留可编辑源码，图表和投票构建器不在当前范围。输入法composition、外部草稿更新及工具弹窗涉及焦点，必须回归回复面板的键盘和草稿保留。

本地预览不等同站点完整插件引擎，复杂嵌套BBCode和插件组合仍有差异；联网图片和表情仍受实际网络影响。插入点通过连续正文编辑移动；一次整体替换多处正文时无法还原编辑意图，落在变更区的插入点移到替换内容末尾，始终保留最新原文。

## Emoji group tabs

表情内容使用HorizontalPager，最近使用和站点分组的点击与左右滑动共享页码，网格位置按页保存。分组图标自动滚入可见范围；长按仍仅提示分组名。搜索临时替换当前页内容，切组清空搜索并恢复对应分组，不为切组重新请求目录或图片；图片缓存身份保持。

The emoji picker keeps server-provided group order and content, while rendering the horizontal group tabs as icon-only controls like the website. Standard groups choose a representative emoji when that emoji exists in the group; otherwise they use the first item in the group. Custom groups also use their first item. The recent-use tab uses a star icon. Accessible labels, selected-state descriptions, and stable test tags remain on each tab container; group names appear only through the long-press toast and accessibility labels.

`EditorToolbarUiTest.emojiGroupTabsUseIconsInsteadOfTextLabels` verifies the recent and first server group tabs are visible, icon-labelled, and contain no visible group title.

2026-10-16 EditorToolbarUiTest的8项真机回归通过（artifacts/personal-emoji-pagers-tests.txt与content-tabs-final-tests.txt），包括最近/分组左右滑动、图标同步、切组退出搜索且不插入内容、搜索插入后最近回显、长按名称与原工具栏/预览行为。首次最近回显断言在旧表情面板退出动画期间就重开，明确等待关闭后通过；不改产品动画或插入逻辑。

## Group name visibility

Group names are no longer rendered as a heading inside the emoji picker, so the grid starts immediately after the search and icon tabs. The icon tabs keep their accessibility labels, and a long press shows the same name through the app toast without changing the short-press group selection behavior. Empty recent content uses a neutral `暂无表情` message rather than repeating the group name.

## Emoji image cache identity

Emoji image requests now carry a stable memory and disk cache key derived from the server URL and requested pixel size. Cache read/write and network policies remain enabled, so the first request still loads normally while revisiting the picker can reuse the cached image. The URL, including the server version query, remains part of the key so a server-side emoji version change invalidates the old entry. Other avatar, post, and gallery image requests keep their existing cache behavior.
