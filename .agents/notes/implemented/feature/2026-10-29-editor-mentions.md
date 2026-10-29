# Agent Note: 编辑器用户提及补全与正文样式

Status: implemented

## Problem

发帖与回复正文支持 Markdown 原文，但输入 @ 没有候选用户，正文把站点的 mention 当作普通下划线链接。本地预览也未识别原文中的用户提及。用户要求补齐网页候选列表和灰色圆角提及效果，已确认方案。

## Decision

共享 EditorForm 的发帖、回复和已有内容编辑都通过 EditorMentionField 接入用户搜索与词段替换；沿用原有草稿、发布和账号生命周期，不新增发布字段。

候选使用现有同源 DiscourseClient 的 GET /u/search/users.json，term 不含 @，最多六项，只包含用户；回复带 topic_id，新帖带 category_id，无上下文的空查询使用 last_seen_users。结果保留服务器顺序，剔除不安全用户名并忽略大小写去重。

补全只替换当前光标所在的 @ 词段，写入真实 username 和一个分隔空格；保留两侧正文。邮箱、转义、行内代码、代码块、已有链接不触发。选区及非 ASCII 组词期间不查询；英文键盘的词段内 ASCII composition 可以查询，但搜索不修改 composition，只有明确选人时才提交它。点击时重新验证当前词段，防止迟到结果覆盖新内容。

输入约 300ms 后请求；光标离开词段、返回键关闭候选、失去焦点、发布中及打开预览使候选关闭，并取消旧查询。取消后再次检查协程活跃状态，不能让不响应取消的旧请求覆盖新结果。候选使用非焦点 Popup，行内仅显示头像、用户名及不重复的昵称，点击保留键盘。位置读取文本光标和窗口可见范围，下方空间足够时贴近光标，否则放到输入区域上方，高度有上限并允许滚动，不受回复的 300dp 容器裁切。Popup 设置 clippingEnabled=false，避免系统再次挪位而遮住输入行；边界由自定义位置提供器限制在状态栏下、键盘上。失败显示错误及重试，草稿不变。

预览通过独立的 suggestionsEnabled 关闭候选，不禁用 BasicTextField；否则平台会收拢选区，破坏预览前后的光标契约。

预览在 Markdown 解析前以普通 @username 原文生成站点 mention 链接，跳过代码、邮箱、已有链接及转义。正文只给站内用户链接且带 a.mention 的节点添加 mention 注解，不改变普通链接或外站链接。预览和正文共用文字布局对应范围的灰色圆角背景，无下划线，保留原始文字、链接、复制和选区，点击沿用用户主页路由。提及换行按行绘制；隐藏剧透不暴露用户名、可点击链接或清晰背景。预览不能保证用户存在或有通知权限，最终由站点解释。

## Existing note audit

[Markdown 工具栏](../../implemented/feature/2026-10-15-editor-markdown-toolbar.md)部分重叠，保留 TextFieldValue、输入法、预览只读和发布契约；[搜索体验](../../implemented/feature/2026-10-14-search-experience.md)部分重叠，仅共享用户模型与接口，不改变全屏搜索的非空查询及分页规则；[账号与编辑器](../../implemented/feature/2026-10-07-account-and-composer.md)保留草稿和账号隔离；通知相关提及检索命中与正文输入无关。无完全吸收或过时提案。

## Alternatives considered

- 直接打开独立选人底部面板能复用现有工具宿主并提供充足空间，但会打断连续输入和键盘操作；采用不抢焦点、受可见窗口约束的候选浮层。
- 把用户名替换为 Markdown 主页链接易于本地显示，但会改变站点原生提及的通知语义；仅提交 @username，渲染在展示层处理。
- 把提及替换成独立行内组件能直接绘制圆角，但会把连续正文变为占位符并影响复制、选区和剧透范围；保留真实文本及链接注解，在文字布局对应范围绘制圆角背景。

## Testing

先用 MentionRenderingTest 复现预览未生成提及、正文仍为下划线的两项失败（artifacts/mentions-repro.txt），修复后通过。最终 JVM 共 23 项通过：MentionEditingTest 六项、MentionRenderingTest 三项、MarkdownPreviewTest 五项、MarkdownInlineParserTest 两项、MarkdownEditingTest 七项（artifacts/mentions-final-build.txt）。覆盖词段替换、空格和光标、中文、邮箱、转义、各类代码、链接、选区、输入法 composition、过期结果、上下文请求及提及渲染。搜索使用 MockWebServer，不向真实用户发帖或通知。

补充英文键盘 composition 复现用例先失败（artifacts/mentions-composition-repro.txt），修复后通过，避免把所有组合状态都当作中文组词而使常用键盘永远不能补全。

候选 UI 五项真机用例全部通过（artifacts/mentions-position-fixed-device.txt），覆盖发帖光标与键盘、真实 ReplyEditorSheet 中 280dp 宽度与深色 130% 字号、迟到响应、重试与发布锁定、返回键与预览。回复用例断言候选不覆盖光标且在键盘上方；旧屏幕坐标断言没有抓到系统重定位，必须同时看系统截图。定位修正前后人工截图确认最终输入行和键盘无遮挡（artifacts/mentions-reply-dark-large.png），浅色候选中文也已目视检查（artifacts/mentions-topic-light.png）。

首轮八项真机测试为七通过、一失败：剧透用例点击到整行中心空白，改用实际提供的无障碍 OnClick 展开，未改生产逻辑（artifacts/mentions-device.txt）。相关 45 项回归为 44 通过、一失败，发现预览关闭候选时禁用输入框会把选区从 (3,5) 收拢为 (5,5)（artifacts/mentions-final-device.txt）。改用 suggestionsEnabled 后的 18 项定向为 17 通过、旧表情横滑一次失败（artifacts/mentions-preview-fixed-device.txt）；原样定向重跑表情横滑与预览选区两项均通过（artifacts/mentions-focused-retest-device.txt）。未改表情代码，间歇性失败原因未确定。

最终定位修正后的九项全部通过：候选五项、正文三项、预览选区一项（artifacts/mentions-position-fixed-device.txt）。正文用例验证浅色与深色的灰色圆角实际像素、原用户名路由、隐藏剧透及预览中的代码和邮箱。人工查看 artifacts/mentions-content-LIGHT.png、mentions-content-DARK.png 及 mentions-preview.png，确认中文、背景和无下划线样式；没有把整批 45 项记为全绿。

当前真实网络路由的只读测试通过一项，验证 typed="ne" 与裸 @ 空查询均返回最多六项候选（artifacts/mentions-live-device.txt）。没有真实发布或提及通知。Debug、AndroidTest 构建成功，最终 lintDebug 为 0 errors、60 warnings、1 hint（artifacts/mentions-position-final-lint.txt）。验证设备为 RMX2202 / Android 13；开发版保留 versionCode=3002、versionName=0.3.1-dev，核对签名后覆盖安装，firstInstallTime 仍为 2026-10-23 14:55:34。

## Consequences

服务端仍决定用户存在性、提及权限及通知，不在本机伪造这些结果；接口可能遇到站点验证或限流。词段检测仅在光标前存在 @ 时解析 Markdown 源范围，换取代码和链接的边界准确性；超长正文中的频繁提及时需要关注解析开销。

候选额外使用一个非焦点窗口，需要随输入法可见范围更新位置；它不接管正文输入连接，也不隐藏键盘。其他 Android 版本及输入法仍需对应设备验证。灰色圆角使用额外绘制而不占用正文宽度，收益是保留连续文本，代价是按文字布局而非独立控件表达内边距；真实发布后的提及通知尚未验证。
