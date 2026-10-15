# Agent Note: 个人中心方形顶部和吸顶身份

Status: implemented

## Problem

个人中心标题图是普通列表首项，滚动后身份消失，等级不在昵称后且缺少独立主页箭头。头像外层重复裁切截断状态角标，空背景仍为黄色。用户确认和公开主页一致的方形展开、底部图片保留和轻微加深遮罩。

## Decision

头像昵称整排在正常高度窗口向上增加40dp留白，与靠底部的统计区拉开；短窗口缩至12dp，避免身份行进入状态栏。展开和吸顶身份行整块点击进入公开主页，等级仍是独立的等级条件入口，子按钮点击不得同时触发主页。菜单图标采用更鲜明的蓝、橙、青绿、紫、绿，设置与关于也用彩色，文字和选择态不跟随改色。

顶部独立固定展开高度为页面宽度，短窗口为下方内容保留120dp。上滑及惯性先收起顶部，下拉先回列表顶部再展开；顶部自身也能拖动。完整图片在缩小视口内向上平移，吸顶看到原图底部，遮罩只增加0.08透明度。无背景或加载失败回退头像，底色白色。昵称后显示等级并继续能打开等级条件，独立右箭头和头像进入公开主页。吸顶保留小头像、昵称、等级和箭头；仅头像图像内部裁圆，状态角标不裁。

## Related decisions

本篇的1:1展开高度及多余上方空白由[内容撑高个人中心顶部](2026-10-11-member-content-header.md)部分替代。吸顶保留图片底部、图片上的文字和状态栏样式由[我的吸顶材质](2026-10-16-member-pinned-material.md)部分替代。原先按窗口缩小头像和间距的边界继续有效，展开图片、点击、滚动顺序、退出恢复状态栏和完整角标规则继续使用，因此本篇保留；下列吸顶图片验证为旧决策的历史证据。

检索个人中心、吸顶、1:1和头像角标。[原个人中心](../../implemented/feature/2026-10-10-member-account-dashboard.md)部分重叠，本篇替代其标题图的黄色回退与普通列表首项布局，菜单、统计来源与会话隔离继续有效。[公开主页连续滚动](../../implemented/bug-fix/2026-10-10-profile-header-scroll.md)部分重叠，沿用滚动顺序和惯性处理。[头像展示](../../implemented/feature/2026-10-08-avatar-presentation.md)保留ACCOUNT状态优先；没有完全吸收或过时提案。

## Alternatives considered

- 单个LazyColumn最简单且自然连续滚动，但不能保留吸顶身份和原图底部，需要独立的折叠视口。
- 使用折叠后单独的纯色工具栏容易实现，但用户要求原图片底部持续显示，使用同一张完整图片的平移视口。

## Testing

2026-10-11：MemberHeaderUiTest新增整排点击与等级独立分流，5项真机通过；MemberHeaderStatusUiTest和MemberAccountUiTest共5项回归通过。实际账号截图artifacts/interface-member-live.png已查看，头像行上移、完整角标及鲜明菜单图标符合本次要求。上移测试按身份区与统计区距离验证，不更改1:1视口和原图底部规则。

MemberHeaderUiTest、MemberHeaderStatusUiTest和MemberAccountUiTest共7项真机通过，角标先在旧版复现0个外侧像素，修复后完整显示；方形、昵称同行等级、吸顶身份及主页箭头、长昵称短窗口验证通过。日志artifacts/member-header-badge-repro.txt与member-navigation-fixed-ui.txt；访客4项已有回归通过。最终增加上下两色图片验证吸顶保留底部、遮罩只增加0.08、无图白底及退出恢复状态栏；个人中心5项真机通过，日志artifacts/runtime-header-chat-device.txt。真实展开与吸顶截图artifacts/runtime-member-square.png和runtime-member-compact.png已人工检查。

## Consequences

短窗口不能机械维持1:1，否则下方无触摸区域；该场景有明确高度上限。图片就绪时展开和吸顶均使用白色状态栏图标，无图白底使用深色图标，离开恢复主题。视觉与滚动已在RMX2202验证，其他设备尺寸仍需独立验收。
