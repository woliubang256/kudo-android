# Agent Note: 发帖与回复 Markdown 工具栏及预览

Status: implemented

## Problem

EditorForm 被新话题、回复面板及已发布内容编辑共用，目前只有图片、代码块和引用三个按钮。正文是 String 输入，工具总追加到末尾，无法按选区格式化；长内容缺少发出前预览。用户提供网页工具栏截图，并明确选择先完善 Markdown 工具栏和预览。

## Decision

正文保留 Markdown 原文及既有账号草稿键，光标、选区和输入法 composition 使用 TextFieldValue 单独管理；格式化作用于选区或插入点，多行块处理整行，空选区把光标放到占位内容中。相同正文回传保留 composition，外部正文变化重定位选区，工具操作明确提交 composition。

底部单行工具栏可横向滚动，回复发布按钮独立固定。常用入口为粗体、斜体、文本大小（标题1～4、段落、小字）、链接、引用、图片、列表（项目符号、编号、任务）、文字方向及代码。更多提供行内代码、删除线、表格、分割线、折叠内容、模糊剧透、目录、脚注、日期时间、公式、Mermaid及Graphviz模板。链接表单沿用无描边输入，工具底部面板使用独立全窗口 Dialog 承载 CommunitySheet，避免被300dp回复容器裁切；发送或保存中禁用正文修改。

新话题、回复和已发布内容编辑共享 EditorForm。完整所见即所得编辑器不属于本轮用户选择的范围。用户确认预览使用独立弹窗，保持正文、选区和草稿不变。

## Existing note audit

检索编辑器、工具栏和Markdown。[账号与编辑器](../../implemented/feature/2026-10-07-account-and-composer.md)、[酷安表单](../../implemented/feature/2026-10-10-coolapk-forms.md)、[已发布内容编辑](../../implemented/feature/2026-10-15-post-editing.md)部分重叠，保留草稿隔离、发布与保存契约、写操作不重放、编辑权限及回复面板承载，只扩展共享正文交互。[正文列表](../../implemented/feature/2026-10-13-markdown-lists.md)、[颜色](../../implemented/feature/2026-10-13-markdown-colors.md)、[脚注](../../implemented/feature/2026-10-13-markdown-footnotes.md)等属于接收cooked内容的解析决策，本轮预览生成内容后复用，不改变服务端正文契约。导航、回复目标、字数规则及其他搜索命中不受影响。无完全吸收或过时提案。

## Reference

2026-10-15使用Playwriter打开网站空白新话题编辑器，只查看按钮和菜单，未输入或发布。证据为artifacts/editor-toolbar-web-composer.txt和editor-toolbar-web-options.txt：语言图标为帖子语言，弯箭头为文字方向；文本大小含标题1～4、段落、小字，列表含项目符号、编号、任务。网站更多菜单还包含图表构建、投票及模板等插件功能，本提案先覆盖常用格式与可独立插入的语法模板。网站当前版本a114182432的PostsController及routes未提供面向普通用户的任意raw转cooked接口，预览采用本地解析，不假定/posts/cook.json存在。

## Alternatives considered

- 沿用String输入并继续尾部拼接可减少改动，但工具无法服务真实光标和选区，插入长回复尤其容易打断写作。
- 嵌入网页编辑器可直接使用网站所有插件，但改变原生回复面板、键盘及草稿生命周期，且依赖在线网页状态；本轮扩展原生Markdown编辑。
- 完整所见即所得编辑最接近网页模式切换，但需要双向文档模型及插件兼容，用户明确选择先完成Markdown工具栏和预览。
- 服务端生成预览最接近最终发布效果，但当前核对的普通用户路由未提供独立cook接口；不以创建草稿或帖子代替预览，采用本地基础格式与明确的插件边界。

## Testing

MarkdownEditingTest的6个单元测试通过，覆盖空选区、反向选区、段落中插入、多行列表、Unicode、重复格式操作、composition及异步插入点随编辑移动。完整工具栏真机回归随预览单元集中执行。

## Consequences

工具不再强制追加到文末，格式命令可复用且可独立测试。文字方向仅影响本地输入方向，不新增服务器帖子语言元数据。表格等模板保留可编辑源码，图表和投票构建器不在当前范围。输入法composition、外部草稿更新及工具弹窗涉及焦点，必须回归回复面板的键盘和草稿保留。
