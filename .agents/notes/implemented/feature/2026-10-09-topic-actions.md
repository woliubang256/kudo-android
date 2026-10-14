# Agent Note: 主题第一排操作与网站举报契约

Status: implemented

## Problem

列表更多图标无操作，长按没有菜单；详情更多菜单不包含复制、收藏、历史与举报。列表没有主帖版本与权限，不能推断历史入口。举报原因和说明字数受网站配置影响。

## Decision

?????[????????](2026-10-15-post-history-page.md)??????????????????????????????????????????????????????????????????????[??????????](2026-10-15-post-editing.md)???

举报的页面承载与表单外观由[酷安式表单](2026-10-10-coolapk-forms.md)部分替代，动态原因、校验与写入锁继续使用本篇契约。

列表入口部分由[移除主题列表操作菜单](../simplification/2026-10-09-remove-list-actions.md)替代；以下列表按需读取的原始理由保留供重访，详情、举报和历史契约继续有效。

数据单元提供主帖权限、举报配置与写入、编辑历史与差异文本契约。TopicActionsHost 已统一两种列表长按、更多图标与详情更多入口；原生历史和举报表单消费同一状态。跨页面菜单由同一 Host 管理，列表按需请求主帖，详情收藏交给既有 TopicViewModel，避免和底部操作状态分叉。列表支持收藏、按需历史、屏蔽、举报；详情用复制替代屏蔽。第一排使用横向圆形图标按钮和底部取消，窄屏允许横向滚动。

主列表及楼中楼的回复行通过 openReply 传入实际 Post，与主题顶部 openDetail 明确区分目标。回复直接复用已加载字段，不退回读取主帖；收藏回调仍交给详情 ViewModel，历史和举报沿用该 Post 的 ID 与权限。主题“复制链接”使用 /t/topic/{topicId}，回复使用 /t/topic/{topicId}/{post.number}；复制正文统一去除 HTML。复制链接不要求登录。

历史需要 version > 1 且 can_view_edit_history，读取 /posts/{id}/revisions/latest.json，翻页跟随 previous_revision / next_revision，不能假定版本连续。原生差异文本保留新增、删除标记及作者、时间、原因；标签数组、作者对象与回复楼层对象按网站实际形态解析。

举报读取 /site.json 中启用且适用于 Post 的 is_flag 原因，与主帖 actions_summary.can_act 求交集。说明下限读取 /latest 的 siteSettings.min_personal_message_post_length，上限遵循 Discourse MAX_MESSAGE_LENGTH=500。支持 notify_user 私信和 illegal 确认；POST /post_actions 只提交 id、post_action_type_id、必要的 message，不发送工作人员强制处理字段。写请求不自动重试；结果未知时阻止再次提交，成功提交的原因在当前会话中从可选项移除，避免详情旧数据再次启用。收藏和举报成功刷新相关列表/详情；消息提示不阻塞账号忽略集合的更新。

屏蔽最初考虑本机隐藏主题，尚未实现即被用户的网站截图纠正；由[网站忽略作者](2026-10-09-ignore-author.md)接管该项决定，其余操作契约保留。

## Related decisions

检索活跃笔记中的菜单、举报、历史、收藏与首页关键词。[数据契约](../../implemented/architecture/2026-10-07-discourse-data-contract.md)保留网络与会话边界，[主题呈现](../../implemented/feature/2026-10-09-topic-presentation.md)保留分类和标签布局，[玻璃弹窗](../../implemented/feature/2026-10-08-glass-settings-modals.md)提供材质与焦点隔离；均为部分重叠，没有完全替代的旧篇。

## Alternatives considered

- 网站主题静音能多端同步，但会改变社区通知偏好；本次采用用户确认的本机隐藏。
- 每条列表预取主帖可让菜单立即呈现，但滚动产生大量请求；仅打开菜单时查询。
- 举报和历史打开网页可自动覆盖网站细节，但用户需要原生操作，因此按权限和配置实现原生界面。

## Testing

回复扩展的 TopicActionsViewModelTest 验证打开回复不额外读取主帖、历史和举报使用实际 Post ID、收藏发送对应 bookmarkable_id，以及主题/回复链接范围。与分页和阅读相关6类共34项 JVM 通过（artifacts/topic-flow-regression.txt）。TopicActionsUiTest 全10项真机通过（artifacts/topic-flow-device.txt），含新增两项：主题/回复复制链接、回复收藏同步，以及外层/楼中楼三点目标与作者点击隔离。暗色大字菜单和历史权限沿用原用例；已检查 reply-actions-light.png，截图只含离线夹具。

最终123项JVM测试通过，RMX2202 / Android13 的34项界面回归通过（56.426秒），覆盖新增8项主题操作、顶部4项、详情5项、列表样式3项、元信息7项、设置7项。初次返回键测试在弹窗渲染前发出系统返回导致Activity结束，补上弹窗已显示的同步断言后通过。构建、Lint通过（0错误/30警告/1提示），笔记门禁通过。日志为 artifacts/topic-actions-final-jvm.txt、topic-actions-verified-device.txt 与 topic-actions-final-package.txt。

已检查 artifacts/topic-actions-ui-final 的复制菜单、暗色大字菜单、举报字数/非法确认、历史差异和期限截图；真实首页与列表/详情液态菜单截图为 topic-actions-real-home.png、topic-actions-real-list-sheet.png、topic-actions-real-detail-sheet.png。真实菜单按主帖字段省略不存在的历史。网站读取验证动态原因与3字下限；报告及忽略写入仅在MockWebServer验证，没有对真实作者提交。

TopicActionsViewModelTest 先复现旧详情重新举报可提交，再修复；覆盖连点单次写入、失败保留草稿、未确认结果跨弹窗阻断、成功原因去重、取消读取不覆盖新目标、忽略期限校验。网络与HTML/差异解析在IO调度器执行，测试先等待取消任务收束再卸载Main调度器。

标签数组和回复对象复现先失败，修复解析后与全部123项JVM回归通过；历史翻页保留网站非连续版本指针。

500字上限复现测试先失败，再依据 Discourse PostActionType.MAX_MESSAGE_LENGTH 修正；集中回归记录随界面完成补齐。

两种列表长按与更多打开同一菜单，置顶主题也有入口。详情复制主帖、收藏同步底栏，无屏蔽。未编辑或无权限的主帖没有历史按钮。举报过滤禁用和无权限原因，校验说明及非法确认，连点只有一次写入。真机检查明暗、大字、取消、背景隔离与顶部折叠。写入测试仅使用 MockWebServer。

## Consequences

权限可能在弹窗打开后变化，提交以服务器结果为准；网络中断可能使结果未知，保留说明并阻止重复写入。主帖读取失败提供重试，不猜测历史或收藏状态。
