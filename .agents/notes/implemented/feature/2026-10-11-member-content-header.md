# Agent Note: 个人中心按内容撑高并统一上下留白

Status: implemented

## Problem

个人中心方形背景在头像上方留下过多空白。用户确认取消“我的”顶部1:1，只裁掉多余上方空间，头像到状态栏底边与统计区到底部均留26dp，内部尺寸、点击及吸顶行为保持现状。公开用户主页不属于本次调整范围。

“我的”紧凑身份原先只在完全收起后创建，展开资料消失后要等到吸顶结束才突然出现小头像。用户要求像公开用户主页一样，在收起过程中随滚动渐显。

## Decision

MemberHeader与MemberProfileScreen以自然测量高度决定视口。状态栏安全区外上下统一26dp，保留身份行与统计区原有20dp加40dp的间距，以及正常窗口64dp头像。短窗口沿用原有48dp头像和12dp额外间距的选择边界，尺寸选择根据窗口空间而非新的内容高度，避免缩短头图意外缩小内容。

测量后的展开高度决定折叠范围，为下方列表保留原来的120dp空间；完整内容持续参与测量，吸顶时通过透明度和语义隐藏，避免折叠后高度变成零而循环跳动。背景继续按完整高度绘制并在视口内平移，吸顶仍为同一张图片底部及额外0.08遮罩。异步统计、字体与窗口变化触发重新测量，折叠值始终限制在有效范围内。

紧凑身份复用公开主页的 profileToolbarIdentityAlpha，透明度为 clamp((fraction - 0.5) × 2, 0, 1)：展开资料在前半段淡出，小头像、昵称、等级和箭头在后半段一起渐显，完全收起时不透明。透明度直接取当前滚动比例，不增加独立计时动画，停手即停、反向滚动同步淡出。透明度为零时不创建紧凑身份，避免透明控件拦截触摸或出现在辅助功能语义树中。完全收起判定仍用于尺寸重测时保持吸顶，不再控制紧凑身份是否出现。

## Related decisions

吸顶仍显示同图底部的规则由[我的吸顶材质](2026-10-16-member-pinned-material.md)部分替代，吸顶改为用户选择的玻璃与主题身份/状态栏，反向展开恢复原图。本篇的自然测量、26dp留白、紧凑身份渐显、折叠顺序和高度重测继续有效；下列原图底部验收保留为旧规则的历史记录。

检索member-square、个人中心顶部、内容撑高、26dp和identityGap。[方形个人中心顶部](../../implemented/feature/2026-10-10-member-square-header.md)部分重叠，本篇只替代1:1展开高度及其多余上方空白，点击、图片、角标及折叠顺序继续有效，两篇保留并互链。[个人中心仪表盘](../../implemented/feature/2026-10-10-member-account-dashboard.md)部分重叠，数据来源和菜单不变。[公开主页连续滚动](../../implemented/bug-fix/2026-10-10-profile-header-scroll.md)与[公开主页方形顶部](../../implemented/feature/2026-10-10-square-profile-header.md)属于其他页面，本次不修改；没有完全吸收或过时提案。

## Alternatives considered

- 固定一个更小高度改动最少，也不会产生首次测量反馈；但字体、状态栏和统计加载提示都能改变内容高度，固定值无法保证上下留白一致。
- 继续按比例缩小头图能保持图片比例且易于预测；但用户明确取消1:1，比例仍会在宽屏留下无关空白，并可能触发原先依赖高度的头像及间距缩小。
- 完全收起后才创建紧凑身份，组合数量最少且显隐状态简单；但会导致吸顶末端突然出现头像，使用与公开主页相同的连续滚动透明度。

## Testing

- 本次渐显调整按用户要求直接编译打包，不运行设备测试。复用 PublicProfileHeaderTest 已有的 0、0.5、0.75、1 折叠比例计算用例；本轮未执行这些测试。手工检查用例（未执行）：缓慢上滑时前半段展开身份淡出、后半段紧凑身份逐渐显示；在半透明位置停手及反向拖动时透明度跟随手势；完全展开时没有紧凑身份的点击或辅助功能入口；完全吸顶后头像与整排打开主页，等级独立打开等级条件；大字号、窄窗口和异步统计重测后仍保持渐显与吸顶位置。
- 2026-10-12 渐显修改的 Release 编译通过，命令为 `:app:assembleRelease -PversionName=0.2.0 -PversionCode=2001`，日志 `artifacts/member-header-fade-release-build.txt`。正式包为 `artifacts/release/0.2.0/member-header-fade/kodo_0.2.0_release_arm64-v8a.apk`，没有升级版本、运行设备测试或安装到手机。
- 修改前，contentHeaderBalancesInsetsCollapsesFromListAndKeepsIdentityAndProfileArrow在真机复现上方空白385px，预期78px（26dp）；日志artifacts/member-content-header-repro.txt。
- MemberHeaderUiTest、MemberHeaderStatusUiTest和MemberAccountUiTest原有及迁移用例共10项通过，覆盖26dp上下留白、64dp头像和60dp内部间距、列表收起与恢复、点击分流、完整角标、原图底部和轻微遮罩、白底及状态栏恢复、异步统计和短窗口菜单；日志artifacts/member-content-header-ui.txt。
- fontAndWindowChangesRemeasureContentAndKeepCollapsedIdentity新增1项通过，动态切130%字体和280dp窄窗口后测量正确，吸顶时重新测量保持紧凑身份，展开仍为26dp留白、64dp头像、窄窗口32dp内部间距，列表至少保留120dp；直接拖动顶部也能收起。日志artifacts/member-content-header-resize-ui.txt。
- 实际账号展开与吸顶截图artifacts/member-content-live-expanded.png、member-content-live-compact.png已查看。展开头像top=185px，状态栏底边107px，统计文字bottom=695px，头图底边773px，上下各78px；图片底部、完整角标、昵称后等级与箭头均保留。覆盖安装保留账号及底部搜索偏好。
- Debug与AndroidTest构建通过；未修改公开用户主页、设置或通知功能，不重复无关全量测试。

## Consequences

自然高度消除与内容无关的上方空白，并随字体、统计提示重新测量。内容测量产生一次状态反馈，隐藏内容持续测量、折叠值受限，吸顶期间重测仍保持收起。极矮窗口仍受为列表保留120dp的上限约束；当前真机验收为Android13的RMX2202，其他设备仍需独立验收。公开主页的1:1不变。
