# Agent Note: 正文代码块复制

Status: implemented

## Problem

正文代码只能逐字选择，用户无法一键取得包含缩进和换行的整段代码。

## Decision

每个代码块右上角使用纯复制图标，保留“复制代码”的无障碍说明，写入解析后的原始整段文本，不走 plainText、不包含正文或按钮文案。保留原有横向滚动和手动选择；剪贴板写入成功后通过共享 `showKudoToast` 提示“已复制”，按钮不再显示“复制”或“已复制”文字。

## Related decisions

检索代码块、复制：[主题操作](../../implemented/feature/2026-10-09-topic-actions.md)部分重叠，但其复制正文采用纯文本，不能替代代码的空白保持；无同主题或过时提案。

## Alternatives considered

- 仅保留系统文字选择：没有新增控件，但长代码选择困难且不能直接取得整段。
- 复用复制正文：入口现成，但会混入其他正文并丢失代码空白，使用每块独立复制。
- 按钮内切换“复制 / 已复制”：反馈与点击位置相邻，但仍保留用户要求去掉的中文按钮文案；采用纯图标和共享短 Toast，不改复制内容。

## Testing

纯图标与 Toast 调整后的 26 项相关真机测试通过（代码块 2 项、共享 Toast 2 项、帖子展示 6 项、话题详情 16 项），记录于 `artifacts/author-code-copy-device.txt`。复制测试继续检查两个代码块各自保留缩进、制表符、换行和 HTML 实体；新增用例检查按钮无可见中文、无障碍入口保留，并捕获含“已复制”的系统通知事件与实际黄色 Toast 像素。已查看 `artifacts/code-copy-icon-toast.png`，复制图标、中文 Toast 与系统剪贴板浮层均可见。构建和 lint 通过，记录于 `artifacts/code-copy-icon-build.txt`；当前仅在 RMX2202 / Android13 实测。

ArticleContentUiTest 先因找不到复制按钮失败（artifacts/article-repro-device.txt），实现后 1 项真机测试通过（code-copy-device.txt），两个代码块分别复制并保留缩进、制表符、换行和 HTML 实体；正文不混入剪贴板。

## Consequences

长代码可以完整复制，横向滚动与手动选择保留。系统剪贴板浮层由 Android 控制，Android 13 及以上可能同时显示该浮层与应用 Toast；不为隐藏系统提示而改变剪贴板行为。App 使用统一的短 Toast，连续复制会由共享实现替换上一条提示。
