# Agent Note: 发帖类别与多选标签同排

Status: implemented

## Problem

新话题编辑器只有全宽“发布到”类别入口，没有标签选择和提交能力。用户要求将其改名为“类别”，与多选“标签”并排，选中的标签必须随草稿恢复并随话题发布。

## Decision

先接入 Draft.tags、本地 JSON 数组持久化和新话题 tags[] 表单数组，再提供同排的类别和标签入口。标签面板使用现有 CommunitySheet、自绘扁平选项和 LogoYellow 选中态，搜索服务端已有标签、多选、确认后保存；取消不改草稿。请求按类别查询，查询或类别变化取消旧请求，失败可重试，已选项不会因搜索结果变化而消失。切换类别清空旧标签，避免把前一类别的选择带入新类别；服务端仍校验最终权限和必填规则。

标签过多时同排摘要显示“已选 N 个”，不撑高该行。面板开启时屏蔽底层表单语义、焦点与发布；发送中禁止修改。回复和正文编辑不增加标签入口，不改变原发布流程。

## Related decisions

检索 proposed / implemented / rejected。[分类发帖](../../implemented/feature/2026-10-10-category-publish.md)保留分类上下文和草稿隔离；[编辑工具栏](../../implemented/feature/2026-10-15-editor-markdown-toolbar.md)保留正文选择与工具行为；[酷安面板](../../implemented/feature/2026-10-10-coolapk-sheets.md)继续提供贴边面板和交互隔离；本篇只扩展新话题标签。云草稿原始字段须继续保留，不重置其他已有标签或未知 composer 数据。

## Alternatives considered

- 在编辑器直接展开所有标签：不需要选择面板，但数量多时挤占正文编辑区，违背同一行的紧凑要求。
- 单纯用逗号分隔的输入框：实现少且支持任意名称，但会制造拼写错误和越权新建标签；只提供服务器返回的已有标签。

## Testing

Draft.tags 使用 JSON 数组保存名称并兼容旧草稿；新话题通过 `/tags/filter/search` 的 `categoryId`、`filterForInput` 和重复 `selected_tag_ids[]` 查询，禁用结果不可选；标签面板确认后才写入草稿，取消、拖拽关闭和失败重试保留已保存值，类别改变清空旧标签。新话题发布以重复 `tags[]` 表单字段提交，回复不携带标签元数据。

离线 ComposerTagsDataTest 4 项通过，覆盖查询参数、禁用项、提交数组和旧草稿兼容；AccountDataTest 与 SearchDataTest 通过。真机 EditorTagsUiTest 11 项通过，覆盖同排布局、多选确认、取消/拖拽、类别切换、重试、过期请求隔离、窄屏深色大字、发送锁定和回复/编辑隔离；关联编辑器回归已通过。

## Consequences

站点可能另有限额或类别必选规则，最终提交错误仍保留完整草稿。旧草稿缺少 tags 字段时默认为空；本地草稿只保存名称，查询时对完整 ID 集合优先使用 `selected_tag_ids[]`，混合旧数据则使用兼容的名称参数。云草稿的未知字段和既有 tags 继续保留。
