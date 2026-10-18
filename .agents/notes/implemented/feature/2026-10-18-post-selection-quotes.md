# Agent Note: 正文选区引用与回复草稿

Status: implemented

## Problem

正文长按只能使用系统文字操作，用户无法直接引用选中的部分回复，也无法单独复制带来源的引用 Markdown。引用整帖或覆盖已有草稿都会丢失用户的选择或输入。

## Decision

主帖、完整回复及楼中楼正文通过 Compose Foundation 公开的 `appendTextContextMenuComponents` 扩展系统文字菜单，只保留“复制”“全选”及自定义的“引用”“复制引用”四项，过滤翻译、文本分类和厂商扩展项。自定义两项使用 Android 原生编辑/复制图标，扩展直接挂在正文和代码块的 `SelectionContainer`，不挂在外层帖子布局；这样系统选区能实际收集到新增菜单。

引用使用选中文字和实际帖子作者、post number、topic ID 生成 Discourse quote BBCode，保留换行；复制引用仅写剪贴板并复用统一 Toast。引用打开目标楼层的现有回复面板，在当前账号和目标楼层的已有草稿末尾追加并持久化，不自动发布。未登录时沿用现有登录入口，关闭或归档话题隐藏回复引用，但仍保留复制、全选和复制引用。

SelectionContainer 不公开选区文字，因此正文局部代理 `LocalClipboard`，通过系统 Copy 菜单回调同步捕获选中文字；捕获只在引用操作期间生效，普通复制仍委托真实剪贴板，不先写入再读取或恢复。`filterTextContextMenuComponents` 记录公开 `CopyKey` 对应的 `TextContextMenuItem` 后，仅放行 `CopyKey`、`SelectAllKey` 和两个自定义 key，避免不同系统继续注入菜单项。隐藏剧透仍由原组件禁止选中，引用不扩展到输入框、用户签名、编辑历史或 WebView；主列表和楼中楼共用同一格式，楼中楼引用前关闭原面板。

## Existing note audit

检索 proposed、implemented、rejected 的选区、引用、SelectionContainer 与回复目标。[引用展示](../../implemented/bug-fix/2026-10-10-discourse-quotes.md)继续负责接收 cooked 引用，不改分组、折叠和图片契约；[编辑工具栏](../../implemented/feature/2026-10-15-editor-markdown-toolbar.md)继续提供编辑器光标与普通 Markdown 工具，本篇只提供正文选区入口；[账号与草稿](../../implemented/feature/2026-10-07-account-and-composer.md)和[回复对象提示](../../implemented/feature/2026-10-10-editor-target-hints.md)继续提供账号、楼层隔离与原提示规则。没有完全吸收、过时提案或被替代决定。

## Alternatives considered

- 替换成只读输入框能够直接得到公开 TextFieldValue.selection，但会改变正文链接、行内图片、剧透与无障碍交互，所以保留 SelectionContainer。
- 先执行复制再读取并恢复系统剪贴板最容易取得选区，但会产生隐私提示、系统复制浮层和短暂的数据覆盖，因此使用局部 Clipboard 代理捕获。
- 直接复制整个楼层不需要选区桥接，但不符合用户仅引用选中段落的要求。

## Verification

`PostQuotesTest` 覆盖作者来源、多行 Unicode、特殊昵称回退和草稿追加；`PostQuoteUiTest` 覆盖只保留复制、全选、引用、复制引用四项菜单、图标菜单点击、引用不改原剪贴板、复制引用 Toast、代码块、后续段落、关闭/归档话题和楼中楼实际目标。本轮使用已安装 `versionCode=2023`、`versionName=0.2.10-dev` 显式参数构建 Debug 与 AndroidTest，同签名 `adb install -r` / `-r -t` 覆盖安装并保留数据；`PostQuoteUiTest` 真机 7 项全部通过。

## Consequences

系统决定四项操作的布局，部分设备可能把它们放进“更多”；系统复制和全选保留，翻译、文本分类及厂商扩展不再出现在该选区菜单中。选区内容是当前原生文字容器提供的文字，不是服务端 raw 格式的逆向恢复；Compose 升级若改变 Copy 回调时序，必须先运行真实选择测试，不能退回覆盖真实剪贴板的方案。回复引用会复用并保存现有草稿，用户可以继续编辑后再发布。
