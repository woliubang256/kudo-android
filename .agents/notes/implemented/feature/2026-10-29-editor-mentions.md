# Agent Note: 编辑器用户提及补全与正文样式

Status: implemented

## Problem

发帖与回复正文支持 Markdown 原文，但输入 @ 没有候选用户，正文把站点的 mention 当作普通下划线链接。本地预览也未识别原文中的用户提及。用户要求补齐网页候选列表和灰色圆角提及效果，已确认方案。

## Decision

用户搜索与词段替换是独立的纯逻辑入口，供编辑器接入；沿用原有草稿、发布和账号生命周期，不新增发布字段。

候选使用现有同源 DiscourseClient 的 GET /u/search/users.json，term 不含 @，最多六项，只包含用户；回复带 topic_id，新帖带 category_id，无上下文的空查询使用 last_seen_users。结果保留服务器顺序，剔除不安全用户名并忽略大小写去重。

补全只替换当前光标所在的 @ 词段，写入真实 username 和一个分隔空格；保留两侧正文。邮箱、转义、行内代码、代码块、已有链接不触发。选择和输入法 composition 期间不插入，点击时重新验证当前词段，防止迟到结果覆盖新内容。

预览转换辅助函数以普通 @username 原文生成用户链接，跳过代码、邮箱、已有链接及转义；它不能保证用户存在或有通知权限，最终由站点解释。

## Existing note audit

[Markdown 工具栏](../../implemented/feature/2026-10-15-editor-markdown-toolbar.md)部分重叠，保留 TextFieldValue、输入法、预览只读和发布契约；[搜索体验](../../implemented/feature/2026-10-14-search-experience.md)部分重叠，仅共享用户模型与接口，不改变全屏搜索的非空查询及分页规则；[账号与编辑器](../../implemented/feature/2026-10-07-account-and-composer.md)保留草稿和账号隔离；通知相关提及检索命中与正文输入无关。无完全吸收或过时提案。

## Alternatives considered

- 直接打开独立选人底部面板能复用现有工具宿主并提供充足空间，但会打断连续输入和键盘操作；采用不抢焦点、受可见窗口约束的候选浮层。
- 把用户名替换为 Markdown 主页链接易于本地显示，但会改变站点原生提及的通知语义；仅提交 @username，渲染在展示层处理。
- 把提及替换成独立行内组件能直接绘制圆角，但会把连续正文变为占位符并影响复制、选区和剧透范围；保留真实文本及链接注解，在文字布局对应范围绘制圆角背景。

## Testing

先用 MentionRenderingTest 复现预览未生成提及、正文仍为下划线的缺口（artifacts/mentions-repro.txt）。MentionEditingTest 五项 JVM 用例通过（artifacts/mentions-editing-tests.txt），覆盖词段替换、空格和光标、中文、邮箱、转义、各类代码、链接、选区、输入法 composition、过期结果和上下文请求。搜索使用 MockWebServer，不向真实用户发帖或通知。

## Consequences

服务端仍决定用户存在性、提及权限及通知，不在本机伪造这些结果；接口可能遇到站点验证或限流。词段检测仅在光标前存在 @ 时解析 Markdown 源范围，换取代码和链接的边界准确性；超长正文中的频繁提及时需要关注解析开销。
