# Agent Note: 酷安式楼中楼回复

Status: implemented

## Problem

话题将所有楼层平铺，回复关系只显示“回复 #楼层”，无法在原评论下阅读一段讨论。

## Decision

reply_to_post_number > 1 的帖子按实际父链归入一级评论，更深后代平铺在同一讨论中，按楼层排序。回复主帖及无指定目标的帖子仍为一级评论。只使用实际回复字段，不把引用关联当父关系；缺失或不可访问的父帖不得导致正文消失。最初“外层不重复”的展示决定由[原楼层与只读预览](2026-10-10-reply-timeline-and-preview.md)部分取代，分组与分页协议继续保留。

一级评论下浅灰框预览3条，作者与实际回复对象由[楼中楼归属展示](2026-10-14-nested-reply-attribution.md)定义；完整讨论展示富文本和图片，操作留在原楼层。服务器 /posts/{id}/replies.json 每页最多20条，after 是最后返回的楼层；游标跟随未过滤的返回值，再只接收 reply_to_post_number 指向当前父帖的回复。展开后按需逐个父帖分页，合并去重，刷新撤销旧任务。已加载的回复立即复用，不全量预取整个话题。

最初“操作留在原楼层”由[弹窗互动](2026-10-14-nested-reply-actions.md)部分取代：完整讨论采用头像左、内容右、正文下时间和操作的平面布局，增加同一爱心/表情回应及回复入口；其他操作留在原楼层。分组、分页、忽略内容和富文本图片的契约继续保留。

主列表分页或深链遇到未加载的父帖时按楼层补齐祖先，辅助缓存不替代主阅读流。深链按[原楼层定位](2026-10-10-reply-timeline-and-preview.md)进入主时间线。点赞、收藏、忽略和编辑器仍用实际 Post，发布保留目标楼层；最初隐藏“A 回复 B”的展示决定由[楼中楼归属展示](2026-10-14-nested-reply-attribution.md)取代。

## Related decisions

检索楼中楼、reply_to、回复关系、深链、编辑器。[账号与草稿](../../implemented/feature/2026-10-07-account-and-composer.md)、[站内深链](../../implemented/feature/2026-10-09-linuxdo-links.md)、[详情作者](../../implemented/feature/2026-10-09-topic-author-and-responses.md)、[忽略用户](../../implemented/feature/2026-10-09-ignore-author.md)部分重叠，分别保留写入目标、链接格式、固定作者和隐藏语义；无完全吸收或过时提案。

## Alternatives considered

- 保留平铺并加回复标签：无需额外读取且维持时间线，但没有用户要求的聚合讨论。
- 递归缩进每一级：忠实展示树结构，但窄屏阅读宽度持续缩小，也不符合酷安式平铺楼中楼。
- reply_ids 递归列表一次获取后代：请求少，但网站默认排除关联多个被引用帖子的回复；逐父帖分页并校验实际 reply_to 可避免遗漏和错误归组。
- 在加权正文 Text 后放图片按钮：点击区域独立且布局简单，但按钮会停在正文分配区域的末端，无法紧跟最后一行文字；图片入口使用 InlineTextContent 参与文字排版。
- 直接追加图片占位符并依赖两行省略：无需额外测量，但长文字会把唯一的图片入口一起省略；有图片时先按实际字体和可用宽度测量，必要时省略文字并保留入口。

## Testing

ThreadAndDecorationTest 的5项测试与全部129项JVM通过，覆盖分组、无效和缺失父帖、深链祖先补齐、引用混入、未过滤游标和逐父分页、失败重试。补齐父帖后旧错误未消失先由新断言复现，再修复，日志 artifacts/nested-context-error-repro.txt 与 community-features-regression-build.txt。回复加载失败现在一次状态更新同时结束 loading 并写入 error，重试不会在失败和结束之间的瞬时状态被丢弃；`failedThreadCanRetryAndMissingAncestorDoesNotHideTheReply` 等待这两个条件共同成立，避免 CI 调度差异造成超时。首轮真机用例发现中文编码损坏，修复前日志 artifacts/community-features-first-device.txt。

NestedReplyUiTest 的3项测试随50项真机回归通过，日志 artifacts/community-features-final-device.txt：三条预览、外层不重复、展开后的回复与收藏使用实际楼层；深链直达第7楼及系统返回；深色130%字体下忽略内容先隐藏、显式查看后显示。已查看 nested-reply-preview.png、nested-reply-thread.png 和 nested-reply-dark-large-text.png。写入回调为测试替身，未向真实社区发帖或收藏。

## Consequences

祖先不可访问时保留独立评论，不能虚构父帖；超长祖先链限制补齐次数。完整讨论按需读取多个父节点，后续由[到底自动续载](2026-10-10-topic-continuation.md)替代手动加载按钮，分页与游标契约保留。

带图片的短预览增加文字测量，结果按内容、字体和可用宽度复用；摘要仍最多两行，正文可能提前省略以保留图片入口。完整讨论和原楼层的富文本图片布局不变。

## Image preview extension

Reply previews keep the existing plain text and emoji extraction. The author, text and compact `查看图片` action share one text flow: the action follows the final text character, or the author's colon for image-only replies, and wraps only when space requires it. Its placeholder uses the measured label, icon and gap at the current font scale instead of a fixed dp width. Long previews shorten text with an ellipsis to keep the action inside two lines. Multiple images include their count. The action opens the shared image gallery with original URLs, so it does not navigate to the floor; clicking the author or text still navigates to the original floor, and ignored replies never expose the image action.

## Verification

`ReplyPreviewImagesUiTest` covers image-only and short-text inline placement, explicit line breaks, a 12-image label at 130% font scale, a 280dp-wide two-line preview, emoji preservation, original gallery URLs, text navigation, gallery-unavailable fallback, and ignored reply redaction. The original layout fails the three new placement assertions (`artifacts/reply-image-inline-repro-device.txt`). The six image-preview tests, four timeline tests and two discussion/navigation tests pass together on RMX2202 / Android 13 (`artifacts/reply-image-inline-complete-device.txt`, 12 tests). Debug builds and Lint pass with explicit 0.2.3 / 2005 parameters; signing matches the installed app, and installation uses `adb install -r` to preserve data.

The separate `NestedReplyUiTest.darkLargeTextKeepsIgnoredRepliesHiddenUntilExplicitlyRevealed` assertion expects the preview container to expose one combined string even though ignored author/content remain separate clickable Text nodes. It also fails with the unchanged HEAD runtime (`artifacts/reply-image-inline-baseline-device.txt`); this change leaves that existing assertion untouched. The focused image test verifies that ignored content and its image action remain hidden. Screenshots `reply-preview-inline-image-only.png`, `reply-preview-inline-short-text.png`, `reply-preview-inline-narrow-large-text.png` and `reply-preview-inline-multiline-large-count.png` in `artifacts/` are visually checked.
