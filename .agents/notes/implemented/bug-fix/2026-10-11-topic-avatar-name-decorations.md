# Agent Note: 话题头像挂件与昵称图标分工

Status: implemented

## Problem

话题详情使用 PLAIN 头像，把资质和状态全部放到昵称后，虽然图标没有重复，却把本应位于头像右下角的挂件移走了。用户要求与列表一致保留头像挂件，昵称后只展示其余图标。只检查图标数量的旧测试无法守住位置。

## Decision

楼主标题栏和完整回复的头像使用 PUBLIC：有资质时显示资质，无资质时显示有效状态。昵称图标根据同一 PUBLIC 选择规则剔除头像已展示的装饰，保留其余有效图标；没有剩余图标时不创建空行。复用现有状态到期计时和图片组件，不改变账号入口、列表、Boost 小头像或元数据请求规则。

共享纯函数 memberNameDecorations 以 avatarDecoration 的 PUBLIC 结果作为排除项，头像与昵称不各自猜测优先级。MemberNameDecorations 在状态到期后重新筛选，剩余列表为空则不创建图标行。两处作者栏一起使用 PUBLIC，避免头像和昵称只完成一半的状态。

## Existing note audit

- [头像展示](../../implemented/feature/2026-10-08-avatar-presentation.md)：部分重叠，PUBLIC 优先级、PLAIN 的 Boost 场景、状态到期及元数据缓存继续有效，补充详情使用相同头像规则。
- [用户装饰](../../implemented/feature/2026-10-10-user-decoration.md)：部分取代，取代其详情全部装饰迁移到昵称后的两段约定；签名、背景图及失败隐藏继续有效，不归档。
- [详情作者](../../implemented/feature/2026-10-09-topic-author-and-responses.md)：部分重叠，固定作者栏、关注及昵称单行布局继续有效。

没有完全被吸收的笔记或过时提案。

## Alternatives considered

- 保留 PLAIN 头像并把全部图标放到昵称后：数量去重简单且所有图标可见，但丢失列表和网页的头像挂件位置，用户已明确纠正。
- 仅恢复 PUBLIC 头像：复用现有公开头像即可恢复位置，但昵称仍重复显示同一资质或状态，需要按实际头像选择结果筛选。
- 删除全部昵称图标：不会重复头像挂件，但同时有资质和状态时会漏掉其余图标，保留未由头像展示的部分。

## Testing

旧版真机位置复现运行的5项用例均失败，日志 artifacts/topic-avatar-placement-repro-device-20261011.txt；楼主、回复及账号/公开内容用例明确显示挂件没有头像祖先节点。复现不仅检查数量，还检查头像祖先、右下角几何位置及昵称后图标的位置。

AvatarPresentationTest 的5项单元测试通过，覆盖资质与状态同时存在、单一资质、单一状态、无装饰及到期边界，日志 artifacts/topic-avatar-filter-unit-20261011.txt。

RMX2202 / Android13 的24项关联真机用例完成：首轮21项通过，3项因夹具只替换正文作者而没有同步 TopicDetail.author 失败。修正夹具中的独立话题作者及 authorProfile，保留原有位置和空行断言，单独复验3项全部通过；日志 artifacts/topic-avatar-placement-fixed-device-20261011.txt 与 artifacts/topic-avatar-placement-fixture-device-20261011.txt。覆盖楼主、回复、长昵称、深色130%字号、关注、单行元信息、分类与标签跳转、公开/账号头像优先级、状态到期和 Boost 无挂件。

Debug 与 AndroidTest 构建通过，日志 artifacts/topic-avatar-placement-fixed-build-20261011.txt；三项夹具修正后测试包再次构建通过，日志 artifacts/topic-avatar-placement-fixture-build-20261011.txt。已查看 artifacts/topic-avatar-and-name-light-20261011.png、artifacts/topic-reply-avatar-and-name-light-20261011.png 和 artifacts/topic-avatar-and-name-dark-large-20261011.png：头像右下角保留资质，昵称后只显示状态图标。签名与已安装开发版一致，使用 adb install -r 保留数据覆盖安装，已启动正常应用。

## Consequences

## Follow-up: author row width

The fixed toolbar author row no longer adds a second weighted spacer after the nickname and remaining decorations. The nickname keeps `weight(fill = false)`, so medium names receive the available width while the existing single-line ellipsis still protects genuinely long names. `TopicDetailUiTest.mediumNicknameIsNotEllipsizedByTrailingAuthorSpacer` reproduces the old truncation and passes after the layout fix.

The same trailing spacer was removed from the reply author row. `TopicDetailUiTest.replyNicknameIsNotEllipsizedByTrailingAuthorSpacer` covers the reply layout independently; the action-row spacer remains unchanged because it still aligns post actions.

资质和自定义状态的远程图片仍可能不可用，继续使用现有占位与语义。详情解析已将头像元数据标记完整，不为本次去重新增用户卡片请求；未知图标不凭空生成。位置用确定性夹具和真机语义树验证，不能用夹具代替实际远端图片的可用性结论。
