# Agent Note: 正文保留站点表情图片

Status: implemented

## Problem

RichContent 将独立和段落内的 emoji 图片替换成 alt 字符串，因此 `:distorted_face:` 等名称直接出现在正文。2026-10-09 只读核查确认 /emojis.json 提供 distorted_face，图片返回 200、72×72 PNG；问题不需要通过更换系统字体解决。

## Decision

保留 cooked 中 img.emoji 的 HTML 和 HTTPS src，在 Compose 文本中使用随字号缩放、与文字居中的行内图片。普通正文图片仍作为独立图片块。复用现有图片加载与动画生命周期；表情有可访问名称，非法图片地址保留文字替代，不把远端内容作为代码执行。

### 帖子标题

`TopicTitleText` 只在显示层将原始标题中的表情短码转换为行内图片，数据模型、编辑、搜索和分享继续使用原标题。信息流、传统列表、精简置顶、搜索、详情、书签及个人主页帖子使用同一组件，保留各自字号、行数和省略规则。图片占位使用相对字号，原短码作为可访问替代文本。

表情地址优先取账号级 `AvatarMetadataCache.emojiDirectory()`，兼容站点自定义表情和肤色短码；目录没有的名称尝试既有标准表情地址。没有短码的标题不请求目录，目录由现有缓存合并并发请求。只接受 HTTPS 图片，非法地址与加载失败均回到原短码，不使用通用表情图标代替未知名称。原始标题不是 HTML，必须保留尖括号、实体字面值、普通冒号与 Unicode 表情。

## Existing note audit

- [原生阅读](../../implemented/feature/2026-10-07-liquid-glass-ui.md)：部分重叠，保留块渲染与浏览器回退决策，本篇负责行内表情。
- [头像显示](../../implemented/feature/2026-10-08-avatar-presentation.md)：部分重叠，复用图片解码和动画生命周期，不改变头像与角标规则。
- [数据契约](../../implemented/architecture/2026-10-07-discourse-data-contract.md)：部分重叠，沿用 HTTPS 图片及跨域不携带会话的边界。

## Alternatives considered

- 加入 EmojiCompat 或第三方字体：可以补全旧 Android 的 Unicode 字形；但无法修复被渲染器直接输出的表情名称，也不能覆盖 L 站自定义图片，优先保留站点图片。
- 将表情当作普通正文图片：复用已有图片块最简单；会打断句子并放大到整行，不适合行内表情。
- 直接使用 `fancy_title`：可沿用服务端处理后的标题；但当前列表、搜索与详情模型保留原始标题，这会扩大数据契约改动并增加各接口缺字段时的分支。标题只需要补齐表情，复用现有目录并保留原文即可，不将原文当 HTML 解析。

## Verification

独立 emoji 图片被转成名称的单元测试先失败，输出 artifacts/topic-emoji-repro.txt；修复后 ContentParserTest 的 10 项通过，输出 topic-emoji-fixed.txt，覆盖段落、重复表情、链接、引用、非法地址与普通图片混排。真实站点图片请求只读验证通过，字段和图片状态记录在 topic-detail-contract-review.json。

TopicDetailUiTest 在真机以确定性PNG验证行内图片完成加载、没有裸露名称、130%字号时占位矩形完整；完整28项UI回归通过，输出 topic-final-instrumentation.txt。同一SiteEmoji组件在真实回应面板中显示 distorted_face 与自定义图片，截图 topic-responses-real.png 已人工确认；[详情交互](../feature/2026-10-09-topic-author-and-responses.md)复用此组件并记录完整验收材料。

标题修复按本次用户要求先实现，再补回归验证。`TopicTitleTextTest` 7 项覆盖反馈标题、重复及相邻表情、肤色、自定义名称、字面文本、HTTPS 边界、失败与地址更新；连同 `ContentParserTest`、`AvatarMetadataCacheTest` 共 42 项单测通过，Debug 构建及 Lint 通过，日志为 artifacts/topic-title-emoji-build.txt。

真机显式安装同版本开发包与测试包，运行 `TopicTitleTextUiTest`、`TopicDetailUiTest`、`TopicPresentationUiTest`、`TopicListStyleUiTest` 共 35 项，34 项通过，其中新增标题测试 6/6 通过。唯一失败是既有 `detailHeaderShowsRealEditStateAndRepliesHideCategoryMetadata` 断言作者栏包含“已编辑”；覆盖安装修改前 APK 后单独运行，复现同一断言失败，不在标题修复中改动该行为。结果分别见 artifacts/topic-title-emoji-device.txt 和 artifacts/topic-title-emoji-baseline-device.txt。

新增真机测试使用确定性 PNG 代替远程表情图片，分别验证三种列表布局、详情标题、目录并发合并、无账号目录、失败回退、无表情零请求、130% 字号与单行省略；截图 topic-title-emoji-lists.png、topic-title-emoji-detail.png、topic-title-emoji-dark-large.png 已检查占位和文字布局，不能据此声称完成真实远端图片验收。真机版本维持 2021 / 0.2.10-dev，签名核对一致，全程覆盖安装，登录数据保留。

## Consequences

[完整引用](2026-10-10-discourse-quotes.md)采用分组子块，引用正文仍使用本篇行内表情规则，作者头像独立于正文图片。

图片首次显示依赖缓存或网络；失败使用带表情名称的占位。超出原生块解析范围的插件内容仍需浏览器打开。

标题在目录和图片加载期间可能短暂显示短码或图片占位；加载失败保留原短码而非吞掉标题字符，不影响帖子进入与原标题编辑。
