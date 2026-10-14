# Agent Note: 原生正文的剧透模糊与点击显隐

Status: implemented

## Problem

用户要求 `[spoiler]…[/spoiler]` 包含的文字、行内表情或图片默认模糊，点击显示，再点恢复。现有正文解析忽略 Discourse cooked 的 `span.spoiler` / `div.spoiler`，图片抽取还会把剧透图片移出原区域而直接显示。

## Decision

读取 Discourse 已渲染的 HTML，不重新解释代码块中的 BBCode。纯文本行内剧透保留 AnnotatedString 的范围，保持同段文字排版；包含图片或多段的区域保留独立 Spoiler 块。Android 12+ 使用 RenderEffect 模糊实际内容，旧系统使用不透明遮罩，不能悄悄显示原文。点击剧透范围只切换显隐，不打开图片或链接；未隐藏的普通正文保留现有操作。

隐藏内容不提供选择复制或无障碍原文，提供显隐动作；状态按正文及区域保存，不跨楼层串用。剧透图片不参与普通相册，防止从外部普通图片翻页时提前看到隐藏图片。签名解析复用此数据模型，必须补全分支，不能因新类型崩溃或直接泄露剧透。

## Related decisions

[剧透图片操作调整](../bug-fix/2026-10-14-spoiler-image-actions.md)部分取代图片揭示后的点击行为：清晰图片点击预览，右上角按钮隐藏。此处原决定的隐藏态、纯文字显隐、旧系统遮罩和普通相册隔离仍有效。

检索剧透、spoiler、正文解析、引用和相册。没有既有同主题笔记。[引用分组](../../implemented/bug-fix/2026-10-10-discourse-quotes.md)和[行内表情](../../implemented/bug-fix/2026-10-09-inline-emoji-images.md)部分重叠，保留嵌套结构和表情图片；[相册](../../implemented/feature/2026-10-09-image-gallery-and-transitions.md)保留普通图片索引和来源定位；[签名](../../implemented/feature/2026-10-10-user-decoration.md)保留默认开关和图片失败隐藏。无完全取代或需要归档的决定。

## Alternatives considered

- 把任意剧透拆为独立块：实现简单，适合多段和图片；但句中的短剧透会使同段内容断行，纯文本行内采用范围裁切和局部模糊。
- 全部正文改用 WebView：可沿用网页 CSS 与插件；但改变原生滚动、链接和相册协议，本轮仅扩展既有 Compose 渲染。

## Verification

解析测试先复现 4 项中 3 项失败，代码字面标签原本通过（`artifacts/spoiler-parser-repro.txt`）。实现后的 6 项剧透解析测试及正文、引用、相册相关共 46 项 JVM 测试通过，Debug / Release R8 构建与 Lint 通过（`artifacts/image-spoiler-final-build.txt`，Lint 0 errors / 41 warnings / 1 hint）。

RMX2202 / Android 13 上的 3 项剧透设备测试通过，验证混合文字/表情/图片的两次点击、链接与图片不误开、行内排版、状态恢复以及强制旧系统遮挡分支；与图片、引用、链接等回归共 39 项通过（`artifacts/image-spoiler-device.txt`，62.34 秒）。`artifacts/spoiler-hidden.png` 已实际查看，确认文字、表情和图片均被模糊；像素和可访问语义分别验收。

最新高刷手势版的 39 项回归通过（`artifacts/image-spoiler-final-device-recheck.txt`，61.96 秒）。前一轮 AVIF 启动超时、独立复测只采到首帧，属于正文图片生命周期竞态；[正文图片](../bug-fix/2026-10-09-article-image-layout.md)记录稳定复现及修复。最终新增专项与原回归共 40 项全部通过（`artifacts/release-0.2.0-final-device.txt`，64.083 秒），没有以重跑成功代替修复。

## Consequences

模糊会增加受影响区域的离屏绘制成本，只在隐藏时启用。局部裁切依赖文字布局回调，布局尚未就绪时先不绘制隐藏内容。隐藏只是防止意外剧透，不是加密或访问控制。
