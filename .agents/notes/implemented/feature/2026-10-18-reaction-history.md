# Agent Note: 回应汇总与分类历史

Status: implemented

## Problem

正文互动行只在点赞按钮右侧显示总数，缺少网站的热门回应图标和分类用户列表。汇总图标重叠会遮挡细节，多排分类与冗余标题也会挤占历史名单空间。

## Decision

正文互动行左侧显示按数量降序的前三种回应与服务端总人数；图标以 2dp 间距并排，不重叠遮挡，没有回应时隐藏汇总。右侧点赞按钮移除数字，保留短按切换和长按选择，Boost 与回应继续独立。点击汇总打开同窗口液态 Action Sheet，使用现有遮罩和下滑关闭手势，不创建 WebView。

历史只读请求 `/discourse-reactions/posts/{id}/reactions-users.json`，读取 `reaction_users` 分组、各类真实总数及用户。面板不显示视觉标题，仍保留“点赞与回应”的无障碍面板语义。多种回应时，“全部”及各类型筛选以单行横向滑动展示，不换行挤占名单；单种或空历史不显示分类栏。加载、空态与失败重试保持不变，用户行可进入公开主页。筛选不写入回应。请求随帖子、账号和关闭取消，迟到结果不能覆盖新面板。

上游控制器 `MAX_USERS_COUNT=26`，实际每类最多返回 27 位，且不接受分页游标；不能通过反复请求相同接口伪造分页。面板每批展开 40 条已返回名单，并在数量大于可用名单时明确显示站点仅返回部分记录。无 reactions 插件的普通点赞使用 `/post_action_users.json?id={postId}&post_action_type_id=2&page={page}&limit=50`，读取 `post_action_users` 与 `total_rows_post_action_users`，按真实页码继续加载；不将插件权限错误回退成空名单。

历史由独立 `TopicReactionHistoryHost` 承载，以账号和导航条目隔离控制器；关闭或离开取消读请求，并同时检查取消状态与请求代次。用户名可在没有数字 ID 时作为名单去重键；隐去身份的核心记录不生成用户主页。打开历史阻断底层阅读上报，不改变点赞、长按选择或 Boost 写操作。

## Existing note audit

- [详情作者与回应](../../implemented/feature/2026-10-09-topic-author-and-responses.md)：部分重叠，本篇调整计数位置，保留服务端写操作权限与返回帖子契约。
- [楼中楼互动](../../implemented/feature/2026-10-14-nested-reply-actions.md)：部分重叠，完整回复面板复用同一汇总入口，不改变回复关系。
- [表单与面板](../../implemented/feature/2026-10-10-coolapk-forms.md)：部分重叠，沿用面板材质、遮罩及手势，不添加独立弹窗体系。

## Alternatives considered

- 点击点赞按钮打开历史：复用入口，但改变既有短按点赞行为，容易误操作；历史独立放在左侧汇总。
- 将各类返回名单长度相加作为总人数：无需依赖额外计数，但接口截断时会少算，展示服务器统计并说明名单范围。
- 所有历史一次渲染：代码简单，但大名单增加首帧工作；按批展示已返回记录，真实支持游标的接口才继续网络分页。
- 分类自动换行：无需横滑便能看到所有类型，但种类多或字号大时占用多排名单空间；使用单行滚动，并在只有一种时移除无意义的切换。

## Consequences

热门图标排序稳定且最多三个，总数不是前三类之和；楼中楼时间移至互动行上方，为汇总与按钮保留空间。数据与控制器测试覆盖计数、缺失用户 ID、真实核心分页、权限失败、取消和迟到结果；真机用例覆盖筛选、重试、跳主页、账号/页面切换、窄屏大字号和拖动关闭。

站点可能限制历史可见范围，不能声称名单完整；图片失败沿用既有表情占位。用户撤销或新增回应后重新打开需读取最新列表。分类较多时需要横滑才能看到后续类型，分类按钮保持单行文字；横滑不改变面板原有下滑关闭手势。

## Testing

面板收紧后的 20 项针对性真机测试通过（历史面板 6 项、帖子展示 4 项、楼中楼互动 10 项），包括无视觉标题但保留无障碍名称、单类型和空历史隐藏分类、窄屏 1.5 倍字号下单行横滑与末尾分类可点击，以及加载、重试、跳主页和下滑关闭。构建与 lint 通过，记录为 `artifacts/reaction-history-layout-build.txt` 和 `artifacts/reaction-history-layout-device.txt`。已查看 `artifacts/reaction-history-no-title.png`、`artifacts/reaction-history-single-type.png`、`artifacts/reaction-history-single-row.png`，确认标题和多余分类栏隐藏、末尾分类仍在同一行。验证机型限于当前连接的 RMX2202；图片占位不用于证明远端回应图标可用。

同签名、同版本覆盖开发版并保留登录数据后，打开真实公告第 24 楼的只读历史。已查看 `artifacts/reaction-layout-live-topic.png` 和 `artifacts/reaction-layout-live-history.png`：实际回应图片在互动行紧凑并排、不重叠，面板无视觉标题、三种分类单行显示，名单与液态材质可见；未发送点赞或 Boost。更多分类的横滑边界以七种类型的窄屏真机用例验证。

图标间距调整后的 `PostDisplayUiTest` 四项真机用例通过，额外检查前三种图标边界互不重叠、间距紧凑且纵向居中一致，并保留服务端总数及点赞短按、长按行为断言。已查看 `artifacts/reaction-summary-spaced.png` 确认汇总位置与间距；夹具中的表情占位不用于证明远端图片加载。构建记录为 `artifacts/reaction-summary-layout-build.txt`，真机记录为 `artifacts/reaction-summary-layout-device.txt`。

相关 43 项 JVM 单测通过，包含历史解析、没有数字 ID 的名单、核心真实分页、权限错误和取消隔离。8 项新增真机用例通过，涵盖本篇及配套作者/系统记录调整；构建和 lint 通过。已查看 `artifacts/post-reaction-row.png`、`artifacts/reaction-history-light.png`、`artifacts/reaction-history-narrow.png`，汇总位于左侧、计数为服务器 100 而非前三类 90，浅色面板和深色窄屏布局可读。窄屏截图中的图片加载占位不作为远端图标可用性的判断依据。

楼中楼旧用例将计数断言从点赞按钮迁到独立汇总，并同时检查点赞按钮没有该数字；不降低对重复提交锁定、楼层一致更新和关闭面板后原楼层状态的要求。最终 18 项针对性真机测试全部通过（8 项新增加 10 项楼中楼回归），记录于 `artifacts/post-display-targeted-final-device.txt`。广回归保留首页头像回顶可见性失败记录，不修改无关滚动逻辑。

保留原登录数据启动正式应用流程，在真实公告帖子中检查主帖及三楼汇总、二楼全站置顶通知；只点击历史入口和 heart 分类，不发送点赞或 Boost。真实接口返回分类计数、头像及用户名，筛选后仅显示 heart 记录；已查看 `artifacts/post-display-live-topic.png` 与 `artifacts/post-display-live-history.png`，自定义回应图片可显示，面板沿用遮罩、模糊折射和贴边布局。正文计数来自帖子快照，历史分类计数来自打开时的新请求，两者不以已返回名单长度互相覆盖。
