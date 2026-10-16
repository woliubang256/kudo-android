# Agent Note: 主题第一排操作与网站举报契约

Status: implemented

## Problem

列表更多图标无操作，长按没有菜单；详情更多菜单不包含复制、收藏、历史与举报。列表没有主帖版本与权限，不能推断历史入口。举报原因和说明字数受网站配置影响。

帖子与回复的更多菜单还需要对当前作者发私信，并像网页一样附带来源，不误选主帖作者或自动提交。

## Decision

操作区的单排横滑布局由[帖子操作菜单两排展示](2026-10-17-topic-actions-grid.md)部分替代，以下按钮权限、网络和上下文契约继续有效，原始横滑理由保留供重访。

历史展示由[完整版本编辑历史页面](2026-10-15-post-history-page.md)部分替代，历史权限与服务器版本指针继续沿用本篇契约；编辑入口由[已发布内容编辑](2026-10-15-post-editing.md)承载。

举报的页面承载与表单外观由[酷安式表单](2026-10-10-coolapk-forms.md)部分替代，动态原因、校验与写入锁继续使用本篇契约。

列表入口部分由[移除主题列表操作菜单](../simplification/2026-10-09-remove-list-actions.md)替代；以下列表按需读取的原始理由保留供重访，详情、举报和历史契约继续有效。

数据单元提供主帖权限、举报配置与写入、编辑历史与差异文本契约。TopicActionsHost 已统一两种列表长按、更多图标与详情更多入口；原生历史和举报表单消费同一状态。跨页面菜单由同一 Host 管理，列表按需请求主帖，详情收藏交给既有 TopicViewModel，避免和底部操作状态分叉。列表支持收藏、按需历史、屏蔽、举报；详情用复制替代屏蔽。第一排使用横向圆形图标按钮和底部取消，窄屏允许横向滚动。

主列表及楼中楼的回复行通过 openReply 传入实际 Post，与主题顶部 openDetail 明确区分目标。回复直接复用已加载字段，不退回读取主帖；收藏回调仍交给详情 ViewModel，历史和举报沿用该 Post 的 ID 与权限。主题“复制链接”使用 /t/topic/{topicId}，回复使用 /t/topic/{topicId}/{post.number}；复制正文统一去除 HTML。复制链接不要求登录。

主帖与回复菜单增加“私信”，以所点击 Post 的作者为收件人，不回退成主帖作者。游客先引导登录，自身帖子不显示入口；明确点击后才读取公开资料的 `can_send_private_message_to_user`，并按需读取话题标题。主帖元数据读取已经取得的标题在菜单会话内复用，普通开菜单不新增请求。准备操作只发 GET，不创建私信；成功后关闭菜单并交给[既有私信编辑器与来源草稿](2026-10-07-account-and-composer.md)预填标题和具体楼层链接。失败留在菜单，可重试同一私信操作；取消、切换楼层或账号会取消读取，旧结果不能打开错误收件人的编辑器。

私信导航事件还携带菜单上下文代次：打开新目标或主动关闭菜单会推进代次，消费事件时丢弃旧代次。仅取消读取任务不足以清理已经进入 Channel 缓冲区的导航；账号切换时 Host 主动关闭旧 ViewModel 的菜单，返回旧账号也不能重放之前未消费的编辑器入口。其他成功写入的刷新和消息事件不受此过滤影响。

历史需要 version > 1 且 can_view_edit_history，读取 /posts/{id}/revisions/latest.json，翻页跟随 previous_revision / next_revision，不能假定版本连续。原生差异文本保留新增、删除标记及作者、时间、原因；标签数组、作者对象与回复楼层对象按网站实际形态解析。

举报读取 /site.json 中启用且适用于 Post 的 is_flag 原因，与主帖 actions_summary.can_act 求交集。说明下限读取 /latest 的 siteSettings.min_personal_message_post_length，上限遵循 Discourse MAX_MESSAGE_LENGTH=500。支持 notify_user 私信和 illegal 确认；POST /post_actions 只提交 id、post_action_type_id、必要的 message，不发送工作人员强制处理字段。写请求不自动重试；结果未知时阻止再次提交，成功提交的原因在当前会话中从可选项移除，避免详情旧数据再次启用。收藏和举报成功刷新相关列表/详情；消息提示不阻塞账号忽略集合的更新。

屏蔽最初考虑本机隐藏主题，尚未实现即被用户的网站截图纠正；由[网站忽略作者](2026-10-09-ignore-author.md)接管该项决定，其余操作契约保留。

## Related decisions

检索活跃笔记中的菜单、举报、历史、收藏与首页关键词。[数据契约](../../implemented/architecture/2026-10-07-discourse-data-contract.md)保留网络与会话边界，[主题呈现](../../implemented/feature/2026-10-09-topic-presentation.md)保留分类和标签布局，[玻璃弹窗](../../implemented/feature/2026-10-08-glass-settings-modals.md)提供材质与焦点隔离；均为部分重叠，没有完全替代的旧篇。

## Alternatives considered

- 网站主题静音能多端同步，但会改变社区通知偏好；本次采用用户确认的本机隐藏。
- 每条列表预取主帖可让菜单立即呈现，但滚动产生大量请求；仅打开菜单时查询。
- 举报和历史打开网页可自动覆盖网站细节，但用户需要原生操作，因此按权限和配置实现原生界面。
- 从所有主帖、回复及楼中楼调用点传递标题，可避免点击私信后的标题读取，但会扩张现有菜单控制器契约并影响无关操作；只在用户实际选择私信时按需读取，已有主帖读取则复用标题。

## Testing

TopicPrivateMessageTest 的 7 项验证主帖标题复用、回复作者及楼层上下文、连点只准备一次、权限与标题读取失败可重试、取消与切换目标丢弃旧结果、游客和本人不发请求，以及已缓冲的过期导航不重放。缓冲事件用例先复现新菜单收到旧楼层（artifacts/post-message-buffered-navigation-repro.txt），再由菜单代次过滤修复；与 TopicActionsViewModelTest 的 6 项既有回归通过，日志为 artifacts/post-message-final-tests-build.txt。PostPrivateMessageUiTest 的 5 项真机测试覆盖登录引导、自身隐藏、权限重试、来源草稿与账号切换取消（artifacts/post-message-device-tests.txt）；TopicActionsUiTest 和 PrivateMessageReplyUiTest 的 13 项真机回归通过（artifacts/post-message-regression-device.txt）。准备过程只读夹具，没有向真实社区发私信。开发版使用 0.2.10-dev / 2021，安装前核对证书一致，仅覆盖安装，保留真实账号数据；真实账号的最终用户验收仍未替代。

菜单代次修复后，四类共 30 项 JVM 用例通过，三个界面类共 18 项在 RMX2202 / Android 13 重新通过（artifacts/post-message-final-device.txt，29.927 秒）。

回复扩展的 TopicActionsViewModelTest 验证打开回复不额外读取主帖、历史和举报使用实际 Post ID、收藏发送对应 bookmarkable_id，以及主题/回复链接范围。与分页和阅读相关6类共34项 JVM 通过（artifacts/topic-flow-regression.txt）。TopicActionsUiTest 全10项真机通过（artifacts/topic-flow-device.txt），含新增两项：主题/回复复制链接、回复收藏同步，以及外层/楼中楼三点目标与作者点击隔离。暗色大字菜单和历史权限沿用原用例；已检查 reply-actions-light.png，截图只含离线夹具。

最终123项JVM测试通过，RMX2202 / Android13 的34项界面回归通过（56.426秒），覆盖新增8项主题操作、顶部4项、详情5项、列表样式3项、元信息7项、设置7项。初次返回键测试在弹窗渲染前发出系统返回导致Activity结束，补上弹窗已显示的同步断言后通过。构建、Lint通过（0错误/30警告/1提示），笔记门禁通过。日志为 artifacts/topic-actions-final-jvm.txt、topic-actions-verified-device.txt 与 topic-actions-final-package.txt。

已检查 artifacts/topic-actions-ui-final 的复制菜单、暗色大字菜单、举报字数/非法确认、历史差异和期限截图；真实首页与列表/详情液态菜单截图为 topic-actions-real-home.png、topic-actions-real-list-sheet.png、topic-actions-real-detail-sheet.png。真实菜单按主帖字段省略不存在的历史。网站读取验证动态原因与3字下限；报告及忽略写入仅在MockWebServer验证，没有对真实作者提交。

TopicActionsViewModelTest 先复现旧详情重新举报可提交，再修复；覆盖连点单次写入、失败保留草稿、未确认结果跨弹窗阻断、成功原因去重、取消读取不覆盖新目标、忽略期限校验。网络与HTML/差异解析在IO调度器执行，测试先等待取消任务收束再卸载Main调度器。

标签数组和回复对象复现先失败，修复解析后与全部123项JVM回归通过；历史翻页保留网站非连续版本指针。

500字上限复现测试先失败，再依据 Discourse PostActionType.MAX_MESSAGE_LENGTH 修正；集中回归记录随界面完成补齐。

两种列表长按与更多打开同一菜单，置顶主题也有入口。详情复制主帖、收藏同步底栏，无屏蔽。未编辑或无权限的主帖没有历史按钮。举报过滤禁用和无权限原因，校验说明及非法确认，连点只有一次写入。真机检查明暗、大字、取消、背景隔离与顶部折叠。写入测试仅使用 MockWebServer。

## Consequences

权限可能在弹窗打开后变化，提交以服务器结果为准；网络中断可能使结果未知，保留说明并阻止重复写入。主帖读取失败提供重试，不猜测历史或收藏状态。

私信准备新增一次公开资料读取；未缓存话题标题时再读一次详情，网络不可用时留在菜单重试，不导航到缺少上下文的空编辑器。已有复制、收藏、历史和举报入口不为此预取资料。
